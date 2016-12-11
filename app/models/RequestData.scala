package models

import java.util.{Calendar, Date, GregorianCalendar, TimeZone}

import play.api.Play.current // should be deprecated in favor of DI
import play.api.cache.Cache
import play.api.libs.json._
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import java.nio.charset.Charset
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import reactivemongo.api.indexes.{Index, IndexType}

import scala.concurrent.duration._
import play.api.Logger
import play.modules.reactivemongo.{MongoController, ReactiveMongoApi, ReactiveMongoComponents}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Await, Future}
import reactivemongo.bson._
import play.modules.reactivemongo.json.BSONFormats._
import play.api.http.HeaderNames
import org.joda.time.DateTime

import scala.collection.mutable.ListBuffer
import reactivemongo.bson.BSONDateTime
import reactivemongo.bson.BSONBoolean

import scala.util.Failure
import scala.Some
import reactivemongo.core.commands.RawCommand
import reactivemongo.bson.BSONLong
import reactivemongo.bson.BSONInteger

import scala.util.Success
import reactivemongo.bson.BSONString
import play.api.libs.json.JsObject
import models.Stat.AnalysisEntity
import reactivemongo.api.collections.bson.BSONCollection

case class RequestData(_id: Option[BSONObjectID],
                       sender: String,
                       var serviceAction: String,
                       environmentName: String,
                       serviceId: BSONObjectID,
                       var groupsName: Option[List[String]],
                       var request: Option[String],
                       var requestHeaders: Option[Map[String, String]],
                       var contentType: String,
                       var requestCall: Option[String],
                       startTime: DateTime,
                       var response: Option[String],
                       var responseOriginal: Option[String],
                       var responseHeaders: Option[Map[String, String]],
                       var timeInMillis: Long,
                       var status: Int,
                       var purged: Boolean,
                       var isMock: Boolean) {

  var responseBytes: Array[Byte] = null

  def this(sender: String, serviceAction: String, environmentName: String, serviceId: BSONObjectID, contentType: String) =
    this(Some(BSONObjectID.generate), sender, serviceAction, environmentName, serviceId, null, null, null, contentType, null, new DateTime(), null, None, null, -1, -1, false, false)

  def toSimpleJson: JsObject = {
    Json.obj(
      "_id" -> _id,
      "status" -> status,
      "contentType" -> contentType,
      "serviceId" -> serviceId,
      "environmentName" -> environmentName,
      "groupsName" -> groupsName.get,
      "sender" -> sender,
      "serviceAction" -> serviceAction,
      "startTime" -> startTime.toString(),
      "timeInMillis" -> timeInMillis,
      "purged" -> purged
    )
  }

  /**
   * Test if the requestData match the criterias given in parameter
   * @param criterias
   * @return
   */
  def checkCriterias(criterias: Criterias): Boolean = {
    checkGroup(criterias.group) &&
      checkEnv(criterias.environment) &&
      checkServiceAction(criterias.serviceAction) &&
      checkStatus(criterias.code) &&
      checkSearch(criterias.request, criterias.response, criterias.search)
  }

  /**
   * Check that the group of the requestData match the group in parameter
   * @param group
   */
  private def checkGroup(group: String): Boolean = {
    group == "all"
  }

  /**
   * Check that the environment of the requestData match the environment in parameter
   * @param environment
   */
  private def checkEnv(environment: String): Boolean = {
    environment == "all" || environment == this.environmentName
  }

  /**
   * Check that the serviceAction of the requestData match the serviceAction in parameter
   * @param serviceAction
   */
  private def checkServiceAction(serviceAction: String): Boolean = {
    serviceAction == "all" || serviceAction == this.serviceAction
  }

  /**
   * Check that the status of the RequestData match the status in parameter
   * @param status
   */
  private def checkStatus(status: String): Boolean = {
    if (status.startsWith("NOT_")) {
      val notCode = status.split("NOT_")(1)
      this.status.toString != notCode
    } else {
      status == "all" || status == this.status.toString
    }
  }

  /**
   * Check that the request or the response of the RequestData match the search query in parameter
   * @param searchRequest search in Request
   * @param searchResponse search in Response
   * @param search search in the string
   */
  private def checkSearch(searchRequest: Boolean, searchResponse: Boolean, search: String): Boolean = {
    if (searchRequest && this.request.get.indexOf(search) > -1) return true
    if (searchResponse && this.response.get.indexOf(search) > -1) return true
    // if the two checkbox are unchecked, the search field is ignore
    if (!searchRequest && !searchResponse) return true
    false
  }
}

object RequestData extends MongoController with ReactiveMongoComponents {
  lazy val reactiveMongoApi = current.injector.instanceOf[ReactiveMongoApi]

  def collection: BSONCollection = db.collection[BSONCollection]("requestData")

  def ensureIndexes() {
    Logger.info("Collection requestData, ensure index")
    collection.indexesManager.ensure(Index(Seq("serviceAction" -> IndexType.Ascending, "groupsName" -> IndexType.Ascending)))
    collection.indexesManager.ensure(Index(Seq("environmentName" -> IndexType.Ascending, "startTime" -> IndexType.Descending)))
    collection.indexesManager.ensure(Index(Seq("status" -> IndexType.Ascending)))
  }

  implicit val requestDataFormat = Json.format[RequestData]

  implicit object MapBSONWriter extends BSONDocumentWriter[Map[String, String]] {
    def write(m: Map[String, String]): BSONDocument = {
      val elements = m.toStream.map {
        tuple =>
          tuple._1 -> BSONString(tuple._2)
      }
      BSONDocument(elements)
    }
  }

  implicit def MapBSONReader[T](implicit reader: BSONReader[_ <: BSONValue, T]): BSONDocumentReader[Map[String, T]] =
    new BSONDocumentReader[Map[String, T]] {
      def read(doc: BSONDocument): Map[String, T] = {
        doc.elements.collect {
          case BSONElement(key, value) => value.seeAsOpt[T](reader) map {
            ov => (key, ov)
          }
        }.flatten.toMap
      }
    }

  implicit object RequestDataBSONReader extends BSONDocumentReader[RequestData] {
    def read(doc: BSONDocument): RequestData = {
      RequestData(
        doc.getAs[BSONObjectID]("_id"),
        doc.getAs[String]("sender").get,
        doc.getAs[String]("serviceAction").get,
        doc.getAs[String]("environmentName").get,
        doc.getAs[BSONObjectID]("serviceId").get,
        Some(doc.getAs[List[String]]("groupsName").toList.flatten),
        doc.getAs[String]("request"),
        doc.getAs[Map[String, String]]("requestHeaders"),
        doc.getAs[String]("contentType").get,
        doc.getAs[String]("requestCall"),
        new DateTime(doc.getAs[BSONDateTime]("startTime").get.value),
        doc.getAs[String]("response"),
        doc.getAs[String]("responseOriginal"),
        doc.getAs[Map[String, String]]("responseHeaders"),
        doc.getAs[Long]("timeInMillis").get,
        doc.getAs[Int]("status").get,
        doc.getAs[Boolean]("purged").get,
        doc.getAs[Boolean]("isMock").get
      )
    }
  }


  implicit object RequestDataBSONWriter extends BSONDocumentWriter[RequestData] {
    def write(requestData: RequestData): BSONDocument = {
      Logger.debug("requestData:" + requestData)
      BSONDocument(
        "_id" -> requestData._id,
        "sender" -> BSONString(requestData.sender),
        "serviceAction" -> BSONString(requestData.serviceAction),
        "environmentName" -> BSONString(requestData.environmentName),
        "groupsName" -> requestData.groupsName,
        "serviceId" -> requestData.serviceId,
        "request" -> requestData.request.get,
        "requestHeaders" -> requestData.requestHeaders.get,
        "contentType" -> BSONString(requestData.contentType),
        "requestCall" -> {
          if (requestData.requestCall != null) requestData.requestCall.get else ""
        },
        "startTime" -> BSONDateTime(requestData.startTime.getMillis),
        "response" -> requestData.response,
        "responseOriginal" -> requestData.responseOriginal,
        "responseHeaders" -> requestData.responseHeaders,
        "timeInMillis" -> BSONLong(requestData.timeInMillis),
        "status" -> BSONInteger(requestData.status),
        "purged" -> BSONBoolean(requestData.purged),
        "isMock" -> BSONBoolean(requestData.isMock)
      )
    }
  }

  val keyCacheServiceAction = "serviceaction-options"
  val keyCacheStatusOptions = "status-options"
  val keyCacheMinStartTime = "minStartTime"

  /**
   * Retrieve all distinct serviceactions using name and groups
   * @return a list of serviceAction's name and groups
   */
  def serviceActionOption: Future[List[(String, List[String])]] = {
    val command =
      BSONDocument(
        "aggregate" -> collection.name, // we aggregate on collection
        "pipeline" -> BSONArray(
          BSONDocument(
            "$group" -> BSONDocument(
              "_id" -> BSONDocument("serviceAction" -> "$serviceAction", "groupsName" -> "$groupsName"),
              "nbServiceAction" -> BSONDocument("$sum" -> 1)))
        )
      )

    var listRes = ListBuffer.empty[(String, List[String])]
    val query = db.command(RawCommand(command))
    query.map {
      b =>
        b.getAs[List[BSONValue]]("result").get.foreach {
          // For each results
          saOptions =>
            saOptions.asInstanceOf[BSONDocument].elements.foreach {
              idOption =>
                if (idOption._1 == "_id") {
                  // If the key is "_id", we retrieve the serviceAction's name and groups
                  var serviceAction = ""
                  var groups = ListBuffer.empty[String]
                  idOption._2.asInstanceOf[BSONDocument].elements.foreach {
                    s =>
                      if (s._1 == "serviceAction") {
                        serviceAction = s._2.asInstanceOf[BSONString].value
                      } else {
                        s._2.asInstanceOf[BSONArray].values.foreach(e => groups += e.asInstanceOf[BSONString].value.toString)
                      }
                  }
                  listRes += ((serviceAction, groups.toList))
                }
            }
        }
        listRes.toList.sortBy(_._1)
    }
  }


  /**
   * Construct the Map[String, String] needed to fill a select options set.
   */
  def statusOptions: Future[BSONDocument] = {
    Cache.getOrElse(keyCacheStatusOptions) {
      val command = RawCommand(BSONDocument("distinct" -> collection.name, "key" -> "status", "query" -> BSONDocument()))
      // example of return {"values":[200],"stats":{"n":16,"nscanned":16,"nscannedObjects":16,"timems":0,"cursor":"BasicCursor"},"ok":1.0}
      db.command(command) // result is Future[BSONDocument]
    }
  }

  /**
   * find the oldest requestData
   */
  def getMinRequestData: Future[Option[RequestData]] = {
    Cache.getOrElse(keyCacheMinStartTime) {
      collection.find(BSONDocument()).sort(BSONDocument({
        "startTime" -> 1
      })).one[RequestData]
    }
  }

  /**
   * Insert a new RequestData.
   * @param requestData the requestData
   * @param service service used by this requestDate
   */
  def insert(requestData: RequestData, service: Service) = {

    var contentRequest: String = null
    var contentResponse: String = null

    val date = new Date()
    val gcal = new GregorianCalendar()
    gcal.setTime(date)
    gcal.get(Calendar.HOUR_OF_DAY); // gets hour in 24h format

    Environment.findByName(service.environmentName.get).map {
      e => {
        val environment = e.get
        requestData.groupsName = Some(environment.groups)
        Robot.talk(requestData)

        if (!service.recordData || !environment.recordData) {
          Logger.debug("Data not recording for this service or this environment")
        } else {
          var msg = ""
          if (!service.recordContentData) {
            msg = "Content Data not recording for this service. See Admin."
          } else if (!environment.recordContentData) {
            msg = "Content Data not recording for this environment. See Admin."
          }

          if (msg != "") {
            requestData.request = Some(msg)
            requestData.responseOriginal = Some(msg)
            requestData.response = Some(msg)
            Logger.debug(msg)
          } else if (requestData.status != 200 || (
            environment.hourRecordContentDataMin <= gcal.get(Calendar.HOUR_OF_DAY) &&
              environment.hourRecordContentDataMax > gcal.get(Calendar.HOUR_OF_DAY))) {
            // Record Data if it is a soap fault (status != 200) or
            // if we can record data with environment's configuration (hours of recording)
            def transferEncodingResponse = requestData.responseHeaders.get.filter {
              _._1 == HeaderNames.CONTENT_ENCODING
            }

            transferEncodingResponse.get(HeaderNames.CONTENT_ENCODING) match {
              case Some("gzip") =>
                Logger.debug("Response in gzip Format")
                requestData.responseOriginal = Some(requestData.response.get)
                requestData.response = Some(uncompressString(requestData.responseBytes))
              case _ =>
                Logger.debug("Response in plain Format")
            }
          } else {
            val msg = "Content Data not recording. Record between " + environment.hourRecordContentDataMin + "h to " + environment.hourRecordContentDataMax + "h for this environment."
            contentRequest = msg
            contentResponse = msg
            Logger.debug(msg)
          }

          val f = collection.insert(requestData)
          f.onComplete {
            case Failure(e) => throw e
            case Success(lastError) => {
              Logger.debug(s"Successfully inserted RequestData with LastError: $lastError")
            }
          }
        }
      }
    }
    Cache.remove(keyCacheStatusOptions)
  }

  /**
   * Delete data (request & reponse) between min and max date
   * @param environmentIn environmement or "" / all if all
   * @param minDate min date
   * @param maxDate max date
   * @param user use who delete the data : admin or akka
   */
  def deleteRequestResponse(environmentIn: String, minDate: Date, maxDate: Date, user: String): Int = {
    val minDateTime = new DateTime(minDate)
    val maxDateTime = new DateTime(maxDate)

    val selector = environmentIn match {
      case "all" =>
        // Modify the request data between the two datetime from all environment
        BSONDocument(
          "startTime" -> BSONDocument(
            "$gte" -> BSONDateTime(minDateTime.getMillis),
            "$lt" -> BSONDateTime(maxDateTime.getMillis)),
          "purged" -> false
        )

      case _ =>
        BSONDocument(
          "environmentName" -> environmentIn,
          "startTime" -> BSONDocument(
            "$gte" -> BSONDateTime(minDateTime.getMillis),
            "$lt" -> BSONDateTime(maxDateTime.getMillis)),
          "purged" -> false
        )
    }

    val modifier = BSONDocument(
      "$set" -> BSONDocument(
        "response" -> "",
        "responseOriginal" -> "",
        "request" -> "",
        "requestHeaders" -> "",
        "responseHeaders" -> "",
        "purged" -> true
      ))

    Cache.remove(keyCacheServiceAction)
    Cache.remove(keyCacheStatusOptions)
    Cache.remove(keyCacheMinStartTime)

    var updatedElement = 0
    val futureUpdate = collection.update(selector, modifier, multi = true)

    futureUpdate.onComplete {
      case Failure(e) => throw e

      case Success(lastError) => {
        if (lastError.nModified > 0) {
          updatedElement = lastError.nModified
          Logger.debug(updatedElement + " RequestData of the environment " + environmentIn + " has been purged by " + user)
        }
      }
    }
    updatedElement
  }

  /**
   * Delete entries between min and max date
   * @param environmentIn environmement or "" / all if all
   * @param minDate min date
   * @param maxDate max date
   */
  def delete(environmentIn: String, minDate: Date, maxDate: Date): Int = {

    val minDateTime = new DateTime(minDate)
    val maxDateTime = new DateTime(maxDate)

    val selector = environmentIn match {
      case "all" =>
        // Remove the request data between the two datetime from all environment
        BSONDocument(
          "startTime" -> BSONDocument(
            "$gte" -> BSONDateTime(minDateTime.getMillis),
            "$lt" -> BSONDateTime(maxDateTime.getMillis))
        )

      case _ =>
        BSONDocument(
          "environmentName" -> environmentIn,
          "startTime" -> BSONDocument(
            "$gte" -> BSONDateTime(minDateTime.getMillis),
            "$lt" -> BSONDateTime(maxDateTime.getMillis))
        )
    }
    var removedElement = 0
    val futurRemove = collection.remove(selector)

    futurRemove.onComplete {
      case Failure(e) => throw e

      case Success(lastError) =>
        removedElement = lastError.n
        Logger.debug(removedElement + " RequestData of the environment " + environmentIn + " has been purged")
    }
    Cache.remove(keyCacheServiceAction)
    Cache.remove(keyCacheStatusOptions)
    Cache.remove(keyCacheMinStartTime)

    removedElement
  }

  /**
   * Return a page of RequestData
   * @param groups groups name
   * @param environment name of environnement, "all" default
   * @param serviceAction serviceAction, "all" default
   * @param minDate Min Date
   * @param maxDate Max Date
   * @param status Status
   * @param page offset in search
   * @param pageSize size of line in one page
   * @return
   */
  def list(groups: String, environment: String, serviceAction: String, minDate: Date, maxDate: Date, status: String, page: Int = 0,
           pageSize: Int = 10, sortKey: String, sortVal: String, sSearch: String, request: Boolean, response: Boolean): Future[List[RequestData]] = {

    var order = 1
    if (sortVal == "desc") order = -1

    val command =
      BSONDocument(
        "aggregate" -> collection.name, // we aggregate on collection
        "pipeline" -> BSONArray(
          BSONDocument(
            "$match" -> getMatchQuery(groups, environment, serviceAction, minDate, maxDate, status, sSearch, request, response)
          ), BSONDocument(
            "$project" -> BSONDocument(
              "sender" -> 1,
              "serviceAction" -> 1,
              "environmentName" -> 1,
              "groupsName" -> 1,
              "serviceId" -> 1,
              "contentType" -> 1,
              "startTime" -> 1,
              "timeInMillis" -> 1,
              "status" -> 1,
              "purged" -> 1,
              "isMock" -> 1
            )
          ), BSONDocument(
            "$sort" -> BSONDocument(
              sortKey -> order
            )
          ), BSONDocument(
            "$skip" -> page * pageSize
          ), BSONDocument(
            "$limit" -> pageSize
          )
        )
      )

    var ret: List[RequestData] = null

    db.command(RawCommand(command)).map {
      list => {
        list.elements.foreach {
          results => if (results._1 == "result") {
            ret = results._2.asInstanceOf[BSONArray].as[List[RequestData]]
          }

        }
        ret
      }
    }

  }

  /**
   * Return a page of RequestData
   * @param groups groups name
   * @param environment name of environnement, "all" default
   * @param serviceAction serviceAction, "all" default
   * @param minDate Min Date
   * @param maxDate Max Date
   * @param status Status
   * @return BSONDocument of query
   */
  def getMatchQuery(groups: String, environment: String, serviceAction: String, minDate: Date, maxDate: Date, status: String, sSearch: String, request: Boolean, response: Boolean): BSONDocument = {

    var matchQuery = BSONDocument()
    if (environment == "all") {
      // We retrieve the environments of the groups in parameter
      val environments = Environment.optionsInGroups(groups)
      // We add the environments names to the query
      matchQuery = matchQuery ++ ("environmentName" -> BSONDocument("$in" -> environments.map {
        e => e._2
      }.toArray))
    } else {
      matchQuery = matchQuery ++ ("environmentName" -> environment)
    }

    if (serviceAction != "all") {
      matchQuery = matchQuery ++ ("serviceAction" -> serviceAction)
    }

    matchQuery = matchQuery ++ ("startTime" -> BSONDocument(
      "$gte" -> BSONDateTime(minDate.getTime),
      "$lt" -> BSONDateTime(maxDate.getTime))
      )

    if (status != "all") {
      if (status.startsWith("NOT_")) {
        val notCode = status.split("NOT_")(1)
        matchQuery = matchQuery ++ ("status" -> BSONDocument("$ne" -> notCode.toInt))
      }
      else matchQuery = matchQuery ++ ("status" -> status.toInt)
    }

    if (sSearch != "") {
      // We use regex research instead of MongoDb $text
      if (request && response) matchQuery = matchQuery ++ ("$or" -> BSONArray(BSONDocument("request" -> BSONDocument("$regex" -> sSearch)), BSONDocument("response" -> BSONDocument("$regex" -> sSearch))))
      else if (request) matchQuery = matchQuery ++ ("request" -> BSONDocument("$regex" -> sSearch))
      else if (response) matchQuery = matchQuery ++ ("response" -> BSONDocument("$regex" -> sSearch))
    }

    matchQuery
  }

  def getTotalSize(groups: String, environment: String, serviceAction: String, minDate: Date, maxDate: Date,
                   status: String, sSearch: String, request: Boolean, response: Boolean): Future[Long] = {

    val command =
      BSONDocument(
        "aggregate" -> collection.name, // we aggregate on collection
        "pipeline" -> BSONArray(
          BSONDocument(
            "$match" -> getMatchQuery(groups, environment, serviceAction, minDate, maxDate, status, sSearch, request, response)
          ),
          BSONDocument(
            "$group" -> BSONDocument(
              "_id" -> "singleton",
              "total" -> BSONDocument(
                "$sum" -> 1
              )
            )
          )
        )
      )
    db.command(RawCommand(command)).map {
      list =>
        var result = 0L
        list.elements.foreach {
          results =>
            if (results._1 == "result") {
              results._2.asInstanceOf[BSONArray].values.foreach {
                singleResult =>
                  singleResult.asInstanceOf[BSONDocument].elements.foreach {
                    total =>
                      if (total._1 == "total") {
                        result = total._2.asInstanceOf[BSONInteger].value.toLong
                      }
                  }
              }
            }
        }
        result
    }
  }

  /**
   * Find the 90 percentiles response time for each day and for each serviceaction in the requestData collection
   * @param groups
   * @param environment
   * @param serviceAction
   * @param minDate
   * @param maxDate
   * @return
   */
  def findResponseTimes(groups: String, environment: String, serviceAction: String, minDate: Date, maxDate: Date): Future[List[AnalysisEntity]] = {
    var matchQuery = BSONDocument()
    if (groups != "all") {
      matchQuery = matchQuery ++ ("groupsName" -> BSONDocument("$in" -> groups.split(',')))
    }
    if (environment != "all") {
      matchQuery = matchQuery ++ ("environmentName" -> environment)
    }

    if (serviceAction != "all") {
      matchQuery = matchQuery ++ ("serviceAction" -> serviceAction)
    }

    matchQuery = matchQuery ++ ("startTime" -> BSONDocument(
      "$gte" -> BSONDateTime(minDate.getTime - 1000),
      "$lt" -> BSONDateTime(maxDate.getTime))
      )

    val command =
      BSONDocument(
        "aggregate" -> collection.name, // we aggregate on collection
        "pipeline" -> BSONArray(
          BSONDocument(
            "$match" -> matchQuery
          ),
          BSONDocument(
            "$project" -> BSONDocument(
              "groupsName" -> "$groupsName",
              "environmentName" -> "$environmentName",
              "serviceAction" -> "$serviceAction",
              "year" -> BSONDocument(
                "$year" -> BSONArray("$startTime")
              ),
              "month" -> BSONDocument(
                "$month" -> BSONArray("$startTime")
              ),
              "days" -> BSONDocument(
                "$dayOfMonth" -> BSONArray("$startTime")
              ),
              "timeInMillis" -> "$timeInMillis"
            )
          ),
          BSONDocument(
            "$sort" -> BSONDocument(
              "groupsName" -> 1,
              "environmentName" -> 1,
              "serviceAction" -> 1,
              "timeInMillis" -> 1
            )
          ),
          BSONDocument(
            "$group" -> BSONDocument(
              "_id" -> BSONDocument(
                "groupsName" -> "$groupsName",
                "environmentName" -> "$environmentName",
                "serviceAction" -> "$serviceAction",
                "year" -> "$year",
                "month" -> "$month",
                "days" -> "$days"
              ),
              "avgs" -> BSONDocument(
                "$push" -> "$timeInMillis"
              )
            )
          ),
          BSONDocument(
            "$sort" -> BSONDocument(
              "_id.groupsName" -> 1,
              "_id.environmentName" -> 1,
              "_id.serviceAction" -> 1,
              "_id.year" -> 1,
              "_id.month" -> 1,
              "_id.days" -> 1
            )
          )
        )
      )

    db.command(RawCommand(command)).map {
      list => {
        var res = ListBuffer.empty[AnalysisEntity]
        list.elements.foreach {
          results => if (results._1 == "result") {
            // The current Tuple will hold the data (serviceAction, groups) for the current document in the loop
            var currentTuple = (List.empty[String], "", "")
            // The previous tuple will hold the data (serviceAction, groups) for the n-1 document in the loop
            var previousTuple = (List.empty[String], "", "")

            var currentTupleDate = 0L
            var datesAndAvgForSameTuple = ListBuffer.empty[(Long, Long)]
            var first = true
            var isDifferent = false

            results._2.asInstanceOf[BSONArray].values.foreach {
              singleElement =>
                singleElement.asInstanceOf[BSONDocument].elements.foreach {
                  key =>
                    if (key._1 == "_id") {
                      if (first) {
                        // If this is the first element retrieved, the previous element is set to the current element
                        previousTuple = (key._2.asInstanceOf[BSONDocument].get("groupsName").get.asInstanceOf[BSONArray].values.map(e => e.asInstanceOf[BSONString].value).toList,
                          key._2.asInstanceOf[BSONDocument].get("environmentName").get.asInstanceOf[BSONString].value,
                          key._2.asInstanceOf[BSONDocument].get("serviceAction").get.asInstanceOf[BSONString].value)
                        first = false
                      }

                      // The current tuple is set to the groupsName and the serviceAction of the current document
                      currentTuple = (key._2.asInstanceOf[BSONDocument].get("groupsName").get.asInstanceOf[BSONArray].values.map(e => e.asInstanceOf[BSONString].value).toList,
                        key._2.asInstanceOf[BSONDocument].get("environmentName").get.asInstanceOf[BSONString].value,
                        key._2.asInstanceOf[BSONDocument].get("serviceAction").get.asInstanceOf[BSONString].value)

                      currentTupleDate = new GregorianCalendar(key._2.asInstanceOf[BSONDocument].get("year").get.asInstanceOf[BSONInteger].value,
                        key._2.asInstanceOf[BSONDocument].get("month").get.asInstanceOf[BSONInteger].value - 1,
                        key._2.asInstanceOf[BSONDocument].get("days").get.asInstanceOf[BSONInteger].value).getTime.getTime

                      // We check if the current Tuple is equal to the previous tuple
                      isDifferent = !currentTuple.equals(previousTuple)
                    }
                    if (key._1 == "avgs") {

                      if (isDifferent) {
                        // If the current tuple was different from the previous tuple, the previous tuple is saved in the result list
                        res += new AnalysisEntity(previousTuple._1, previousTuple._2, previousTuple._3, datesAndAvgForSameTuple.toList)
                        datesAndAvgForSameTuple = ListBuffer.empty[(Long, Long)]
                      }

                      // We retrieve the timeInMillis of each request data for the current document
                      val avgs = key._2.asInstanceOf[BSONArray].values.map {
                        avg => avg.asInstanceOf[BSONLong].value
                      }.toList

                      // We keep only 90 percentiles
                      val ninePercentiles = avgs.slice(0, avgs.size * 9 / 10)
                      val avg = {
                        if (ninePercentiles.size > 0) {
                          ninePercentiles.sum / ninePercentiles.size
                        } else if (avgs.size == 1) {
                          avgs.head
                        } else {
                          -1L
                        }
                      }
                      // The date and the avg of the current element is save to the dateAndAvgForSameTuple list
                      datesAndAvgForSameTuple += ((currentTupleDate, avg))

                      // The previous tuple is set to the current tuple
                      previousTuple = currentTuple
                    }
                }

            }
            // At the end of the loop the last tuple is add to the result list only if a result exists
            if (!first) {
              res += new AnalysisEntity(previousTuple._1, previousTuple._2, previousTuple._3, datesAndAvgForSameTuple.toList)
            }
          }
        }
        res.toList
      }

    }
  }

  /**
   * Find all day before today, for environment given and state = 200.
   * @param environmentName environment name
   * @return list of unique date
   */
  def findDayNotCompileStats(environmentName: String, groups: List[String]): List[Date] = {

    val gcal = new GregorianCalendar
    TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
    val today = new GregorianCalendar(gcal.get(Calendar.YEAR), gcal.get(Calendar.MONTH), gcal.get(Calendar.DATE))

    val query = BSONDocument("environmentName" -> environmentName,
      "groupsName" -> groups,
      "status" -> 200,
      "isMock" -> false,
      "startTime" -> BSONDocument("$lt" -> BSONDateTime(today.getTimeInMillis)))

    var uniqueStartTimePerDay: Set[Date] = Set()
    val queryStartTimes = collection.find(query).cursor[RequestData].collect[List]().map {
      listRequestData =>

        listRequestData.map {
          requestData =>
            Logger.debug(requestData.serviceAction)
            Logger.debug(requestData.environmentName)
            gcal.setTimeInMillis(requestData.startTime.getMillis)
            val ccal = new GregorianCalendar(gcal.get(Calendar.YEAR), gcal.get(Calendar.MONTH), gcal.get(Calendar.DATE))
            uniqueStartTimePerDay += ccal.getTime
        }
        uniqueStartTimePerDay.toList
    }
    Await.result(queryStartTimes, 5.second)
  }

  /**
   * Retrieve a list of requestData grouped by serviceAction, environment, groups and day (year + day). The list will contain
   * the avg responseTime of the day for this requestData, and the number of requestData.
   * @return
   */
  def findStatsPerDay(groups: String, environmentName: String, minDate: Date, maxDate: Date, realTime: Boolean = false): Future[List[Stat]] = {
    var finalGroupById = BSONDocument()
    if (realTime) {
      finalGroupById = BSONDocument(
        "_id" -> BSONDocument(
          "groups" -> "$groups",
          "environmentName" -> "$environmentName",
          "serviceAction" -> "$serviceAction"
        )
      )
    } else {
      finalGroupById = BSONDocument(
        "_id" -> BSONDocument(
          "groups" -> "$groups",
          "environmentName" -> "$environmentName",
          "serviceAction" -> "$serviceAction",
          "year" -> "$year",
          "month" -> "$month",
          "days" -> "$days"
        )
      )
    }

    val finalGroupBy = finalGroupById ++(
      "timeInMillis" -> BSONDocument(
        "$push" -> "$timeInMillis"
      ),
      "nbRequest" -> BSONDocument(
        "$sum" -> 1
      )
      )

    var matchQuery = BSONDocument("startTime" -> BSONDocument(
      "$gte" -> BSONDateTime(minDate.getTime),
      "$lt" -> BSONDateTime(maxDate.getTime)),
      "isMock" -> false,
      "status" -> 200
    )

    if (groups != "all") {
      matchQuery = matchQuery ++ ("groupsName" -> BSONDocument("$in" -> groups.split(',')))
    }
    if (environmentName != "all") {
      matchQuery = matchQuery ++ ("environmentName" -> environmentName)
    }

    val command =
      BSONDocument(
        "aggregate" -> collection.name, // we aggregate on collection
        "pipeline" -> BSONArray(
          BSONDocument(
            "$match" -> matchQuery
          ),
          BSONDocument(
            "$project" -> BSONDocument(
              "groups" -> "$groupsName",
              "serviceAction" -> "$serviceAction",
              "environmentName" -> "$environmentName",
              "year" -> BSONDocument("$year" -> BSONArray("$startTime")),
              "month" -> BSONDocument("$month" -> BSONArray("$startTime")),
              "days" -> BSONDocument("$dayOfMonth" -> BSONArray("$startTime")),
              "timeInMillis" -> "$timeInMillis"
            )
          ),
          BSONDocument(
            "$sort" -> BSONDocument(
              "groups" -> 1,
              "environmentName" -> 1,
              "serviceAction" -> 1,
              "timeInMillis" -> 1
            )
          ),
          BSONDocument(
            "$group" -> finalGroupBy
          )
        )
      )

    val query = db.command(RawCommand(command))
    query.map {
      list =>
        val listRes = ListBuffer.empty[Stat]
        list.elements.foreach {
          results =>
            if (results._1 == "result") {
              results._2.asInstanceOf[BSONArray].values.foreach {
                e =>
                  var groups = ListBuffer.empty[String]
                  var environment = ""
                  var sa = ""
                  var year = 0
                  var month = 0
                  var days = 0
                  var avgList = ListBuffer.empty[Long]
                  var nbRequest = 0.toLong
                  e.asInstanceOf[BSONDocument].elements.foreach {
                    e2 =>
                      if (e2._1 == "_id") {
                        e2._2.asInstanceOf[BSONDocument].elements.foreach {
                          test =>
                            if (test._1 == "groups") {
                              test._2.asInstanceOf[BSONArray].values.foreach {
                                group =>
                                  groups += group.asInstanceOf[BSONString].value
                              }
                            }
                            else if (test._1 == "environmentName") {
                              environment = test._2.asInstanceOf[BSONString].value
                            }
                            else if (test._1 == "serviceAction") {
                              sa = test._2.asInstanceOf[BSONString].value
                            }
                            else if (test._1 == "year") {
                              year = test._2.asInstanceOf[BSONInteger].value
                            }
                            else if (test._1 == "month") {
                              month = test._2.asInstanceOf[BSONInteger].value
                            }
                            else if (test._1 == "days") {
                              days = test._2.asInstanceOf[BSONInteger].value
                            }
                        }
                      }
                      else if (e2._1 == "timeInMillis") {
                        e2._2.asInstanceOf[BSONArray].values.foreach {
                          avg =>
                            avgList += avg.asInstanceOf[BSONLong].value
                        }
                      }
                      else if (e2._1 == "nbRequest") {
                        nbRequest = e2._2.asInstanceOf[BSONInteger].value.toLong
                      }
                  }
                  val date = new GregorianCalendar(year, month - 1, days).getTime
                  val ninePercentiles = avgList.slice(0, avgList.size * 9 / 10)
                  val avg = {
                    if (ninePercentiles.size > 0) ninePercentiles.sum / ninePercentiles.size
                    else if (avgList.size == 1) avgList.head
                    else -1
                  }
                  val stat = new Stat(groups.toList, environment, sa, avg, nbRequest, (new DateTime(date)))
                  listRes += stat
              }
            }
        }
        listRes.toList
    }
  }

  /**
   * Compile all requestData
   */
  def compileStats() {
    // all requestDatas between the minimum requestData' startTime in DB and today will be compiled
    TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
    val minDate = UtilDate.getDate("2014-05-05T00:00").getTime
    val maxDate = UtilDate.getDate("today", UtilDate.v23h59min59s, true).getTime

    // We retrieve the list of all statistics, using mongodb aggregation framework on requestData collection
    val query = findStatsPerDay("all", "all", minDate, maxDate)
    // create a calendar based on today
    val gcal = new GregorianCalendar
    val today = new GregorianCalendar(gcal.get(Calendar.YEAR), gcal.get(Calendar.MONTH), gcal.get(Calendar.DATE))
    Logger.debug(today.getTime.toString)
    query.map {
      list =>
        list.foreach {
          stat =>
            if (today.getTime.getTime == stat.atDate.getMillis) {
              Logger.debug("Today stats, it will not be saved ")
            } else {
              Stat.insert(stat)
            }
        }
    }
  }

  def loadDetails(id: String): Future[Option[BSONDocument]] = {
    val query = BSONDocument("_id" -> BSONObjectID(id))
    val projection = BSONDocument("environmentName" -> 1, "serviceId" -> 1, "sender" -> 1, "timeInMillis" ->1, "status" -> 1, "purged" -> 1, "isMock" -> 1, "startTime" -> 1)
    collection.find(query, projection).cursor[BSONDocument].headOption
  }

  def loadRequest(id: String): Future[Option[BSONDocument]] = {
    val query = BSONDocument("_id" -> BSONObjectID(id))
    val projection = BSONDocument("request" -> 1, "contentType" -> 1, "environmentName" -> 1, "serviceId" -> 1, "sender" -> 1, "requestHeaders" -> 1, "requestCall" -> 1)
    collection.find(query, projection).cursor[BSONDocument].headOption
  }

  def loadResponse(id: String): Future[Option[BSONDocument]] = {
    val query = BSONDocument("_id" -> BSONObjectID(id))
    val projection = BSONDocument("response" -> 1, "contentType" -> 1, "responseHeaders" -> 1)
    collection.find(query, projection).cursor[BSONDocument].headOption
  }

  /**
   * Uncompress a String with gzip
   *
   * @param compressed compressed String
   * @return clear String
   */
  def uncompressString(compressed: Array[Byte]): String = {
    try {
      val BUFFER_SIZE = 1000
      val is = new ByteArrayInputStream(compressed)
      val gis = new GZIPInputStream(is, BUFFER_SIZE)
      val output = new StringBuilder()
      val data = new Array[Byte](BUFFER_SIZE)
      var ok = true
      while (ok) {
        val bytesRead = gis.read(data)
        ok = bytesRead != -1
        if (ok) output.append(new String(data, 0, bytesRead))
      }
      gis.close()
      is.close()
      output.toString()
    } catch {
      case e: Exception => Logger.error("decompressString : Error during uncompress string:" + e.getStackTraceString)
        new String(compressed, 0, compressed.length, Charset.forName("utf-8"))
    }
  }
}
