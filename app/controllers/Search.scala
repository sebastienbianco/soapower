package controllers

import play.api.mvc._
import play.api.libs.json._
import models._
import models.UtilDate._
import play.api.libs.iteratee.Enumerator
import play.api.http.HeaderNames

import scala.xml.PrettyPrinter
import org.xml.sax.SAXParseException
import java.net.URLDecoder
import java.util.regex.Pattern

import scala.concurrent.{Await, ExecutionContext, Future}
import ExecutionContext.Implicits.global
import reactivemongo.bson.{BSONDocument, BSONDocumentWriter, BSONObjectID, BSONString}

import scala.util.parsing.json.JSONObject
import scala.concurrent.duration._
import com.fasterxml.jackson.core.JsonParseException

case class Search(environmentId: Long)

object Search extends Controller {

  private val UTF8 = "UTF-8"

  def listDatatable(groups: String, environment: String, serviceAction: String, minDate: String, maxDate: String, status: String, sSearch: String, page: Int, pageSize: Int, sortKey: String, sortVal: String, request: Boolean, response: Boolean) = Action.async {
    val futureDataList = RequestData.list(groups, environment, URLDecoder.decode(serviceAction, UTF8), getDate(minDate).getTime, getDate(maxDate, v23h59min59s, true).getTime, status, (page - 1), pageSize, sortKey, sortVal, sSearch, request, response)
    val futureTotalSize = RequestData.getTotalSize(groups, environment, URLDecoder.decode(serviceAction, UTF8), getDate(minDate).getTime, getDate(maxDate, v23h59min59s, true).getTime, status, sSearch, request, response)

    for {
      futureDataListResult <- futureDataList
      futureTotalSizeResult <- futureTotalSize
    } yield (Ok(Json.toJson(Map("data" -> Json.toJson(futureDataListResult),
      "totalDataSize" -> Json.toJson(futureTotalSizeResult.asInstanceOf[Long])))))
  }

  /**
   * Used to download or render the request
   * @param id of the requestData to download
   * @return
   */
  def downloadRequest(id: String) = Action.async {
    val future = RequestData.loadRequest(id)
    downloadInCorrectFormat(future, id, true)
  }

  /**
   * Get the headers request content and send it to the client
   * @param id
   * @return
   */
  def getRequestHeaders(id: String) = Action.async {
    import RequestData.MapBSONReader
    RequestData.loadRequest(id).map {
      tuple => tuple match {
        case Some(doc: BSONDocument) => {
          Ok(UtilConvert.headersToString(doc.getAs[Map[String, String]]("requestHeaders").get))
        }
        case None =>
          NotFound("Request not found")
      }
    }
  }

  /**
   * Get the headers response content and send it to the client
   * @param id
   * @return
   */
  def getResponseHeaders(id: String) = Action.async {
    import RequestData.MapBSONReader
    RequestData.loadResponse(id).map {
      tuple => tuple match {
        case Some(doc: BSONDocument) => {
          Ok(UtilConvert.headersToString(doc.getAs[Map[String, String]]("responseHeaders").get))
        }
        case None =>
          NotFound("Response not found")
      }
    }
  }

  /**
   * Get the headers response content and send it to the client
   * @param id
   * @return
   */
  def getDetails(id: String) = Action.async {
    RequestData.loadDetails(id).map {
      tuple => tuple match {
        case Some(doc: BSONDocument) => {
          Ok(Json.toJson(Map("timeInMillis" -> doc.getAs[Long]("timeInMillis").getOrElse("-").toString,
            "status" -> doc.getAs[Int]("status").getOrElse("-").toString,
            "purged" -> doc.getAs[Boolean]("purged").getOrElse("-").toString,
            "isMock" -> doc.getAs[Boolean]("isMock").getOrElse("-").toString,
            "sender" -> doc.getAs[String]("sender").getOrElse("-").toString,
            "environmentName" -> doc.getAs[String]("environmentName").getOrElse("-").toString,
            "serviceId" -> doc.getAs[BSONObjectID]("serviceId").get.stringify.toString)))
        }
        case None =>
          NotFound("Response not found")
      }
    }
  }



  /**
   * Get the requestData request content and send it to the client
   * @param id
   * @return
   */
  def getRequest(id: String) = Action.async {
    RequestData.loadRequest(id).map {
      tuple => tuple match {
        case Some(doc: BSONDocument) => {
          doc.getAs[String]("contentType").get match {
            case "application/json" =>
              var content = doc.getAs[String]("request").get
              try {
                val content = Json.parse(doc.getAs[String]("request").get)
                Ok(Json.toJson(content));
              }
              catch {
                case e: JsonParseException =>
                  Ok(content)
              }
            case "application/xml" | "text/xml" => {
              var content = doc.getAs[String]("request").get
              try {
                content = new PrettyPrinter(250, 4).format(scala.xml.XML.loadString(content))
              } catch {
                case e: SAXParseException =>

              }
              Ok(content)
            }
            case _ =>
              Ok(doc.getAs[String]("request").get)
          }
        }
        case None =>
          NotFound("The request does not exist")
      }
    }
  }

  /**
   * Get the response requestData and send it to the client
   * @param id
   * @return
   */
  def getResponse(id: String) = Action.async {
    RequestData.loadResponse(id).map {
      case Some(doc: BSONDocument) => {

        val patternJson = ".*(application/json).*".r
        val patternXml = ".*(application/xml).*".r
        val patternTextXml = ".*(text/xml).*".r

        doc.getAs[String]("contentType").get match {
          case patternJson(_) =>
            val content = doc.getAs[String]("response").get
            try {
              val content = Json.parse(doc.getAs[String]("response").get)
              Ok(Json.toJson(content));
            }
            catch {
              case e: JsonParseException =>
                Ok(content)
            }
          case patternXml(_) | patternTextXml(_) => {
            var content = doc.getAs[String]("response").get
            try {
              content = new PrettyPrinter(250, 4).format(scala.xml.XML.loadString(content))
            } catch {
              case e: SAXParseException =>

            }
            Ok(content)
          }
          case _ =>
            Ok(doc.getAs[String]("response").get)
        }
      }
      case None =>
        NotFound("The response does not exist")
    }
  }

  /**
   * Used to download or render the response
   * @param id of the requestData to download
   * @return
   */
  def downloadResponse(id: String) = Action.async {
    val future = RequestData.loadResponse(id)
    downloadInCorrectFormat(future, id, isRequest = false)
  }


  /**
   * Download the response / request in the correct format
   * @param future the request or response Content
   * @param isRequest
   * @return
   */
  def downloadInCorrectFormat(future: Future[Option[BSONDocument]], id: String, isRequest: Boolean) = {

    val keyContent = {
      if (isRequest) "request" else "response"
    }
    var filename = ""
    if (isRequest) {
      filename = "request-" + id
    } else {
      filename = "response-" + id
    }

    var contentInCorrectFormat = ""

    future.map {
      case Some(doc: BSONDocument) => {
        val contentType = doc.getAs[String]("contentType").get
        // doc.getAs[String]("response")
        val content = doc.getAs[String](keyContent).get
        val patternJson = ".*(application/json).*".r
        val patternXml = ".*(application/xml).*".r
        val patternTextXml = ".*(text/xml).*".r

        contentType match {
          case patternXml(_) | patternTextXml(_) => {
            try {
              contentInCorrectFormat = new PrettyPrinter(250, 4).format(scala.xml.XML.loadString(content))
              filename += ".xml"
            } catch {
              case e: SAXParseException => contentInCorrectFormat = content
                filename += ".txt"
            }

            var result = Result(
              header = ResponseHeader(play.api.http.Status.OK),
              body = Enumerator(contentInCorrectFormat.getBytes))

            result = result.withHeaders((HeaderNames.CONTENT_DISPOSITION, "attachment; filename=" + filename))
            result.as(XML)

          }

          case patternJson(_) => {
            try {
              contentInCorrectFormat = Json.parse(content).toString
              filename += ".json"
            }
            catch {
              case e: Exception =>
                contentInCorrectFormat = content
                filename += ".txt"
            }
            var result = Result(
              header = ResponseHeader(play.api.http.Status.OK),
              body = Enumerator(contentInCorrectFormat.getBytes))
            result = result.withHeaders((HeaderNames.CONTENT_DISPOSITION, "attachment; filename=" + filename))
            result.as(JSON)
          }
          case _ => {
            filename += ".txt"
            var result = Result(
              header = ResponseHeader(play.api.http.Status.OK),
              body = Enumerator(content.getBytes))
            result = result.withHeaders((HeaderNames.CONTENT_DISPOSITION, "attachment; filename=" + filename))
            result.as(TEXT)
          }

        }
      }
      case _ =>
        NotFound("The request does not exist")
    }

  }
}
