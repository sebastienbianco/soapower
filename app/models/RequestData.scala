package models

import java.util.{GregorianCalendar, Calendar, Date}

import play.api.db._
import play.api.Play.current
import play.api._
import play.api.cache._
import play.api.libs.json._
import play.api.http._

import anorm._
import anorm.SqlParser._

case class RequestData(
  id: Pk[Long],
  sender: String,
  var soapAction: String,
  environmentId: Long,
  localTarget: String,
  remoteTarget: String,
  request: String,
  requestHeaders: Map[String, String],
  startTime: Date,
  var response: String,
  var responseHeaders: Map[String, String],
  var timeInMillis: Long,
  var status: Int) {

  def this(sender: String, soapAction: String, environnmentId: Long, localTarget: String, remoteTarget: String, request: String, requestHeaders: Map[String, String]) =
    this(null, sender, soapAction, environnmentId, localTarget, remoteTarget, request, requestHeaders, new Date, null, null, -1, -1)

  /**
   * Add soapAction in cache if neccessary.
   */
  def storeSoapActionAndStatusInCache() {
    if (!RequestData.soapActionOptions.exists(p => p._1 == soapAction)) {
      Logger.info("SoapAction " + soapAction + " not found in cache : add to cache")
      val inCache = RequestData.soapActionOptions ++ (List((soapAction, soapAction)))
      Cache.set(RequestData.keyCacheSoapAction, inCache)
    }
    if (!RequestData.statusOptions.exists(p => p._1 == status)) {
      Logger.info("Status " + soapAction + " not found in cache : add to cache")
      val inCache = RequestData.statusOptions ++ (List((status, status)))
      Cache.set(RequestData.keyCacheStatusOptions, inCache)
    }
  }
}

object RequestData {

  val keyCacheSoapAction = "soapaction-options"
  val keyCacheStatusOptions = "status-options"
  val keyCacheMinStartTime = "minStartTime"

  /**
   * Parse a RequestData from a ResultSet
   */
  val simple = {
    get[Pk[Long]]("request_data.id") ~
      str("request_data.sender") ~
      str("request_data.soapAction") ~
      long("request_data.environmentId") ~
      str("request_data.localTarget") ~
      str("request_data.remoteTarget") ~
      get[Date]("request_data.startTime") ~
      long("request_data.timeInMillis") ~
      int("request_data.status") map {
        case id ~ sender ~ soapAction ~ environnmentId ~ localTarget ~ remoteTarget ~ startTime ~ timeInMillis ~ status =>
          RequestData(id, sender, soapAction, environnmentId, localTarget, remoteTarget, null, null, startTime, null, null, timeInMillis, status)
      }
  }

  /**
   * Parse required parts of a RequestData from a ResultSet in order to replay the request
   */
  val forReplay = {
    get[Pk[Long]]("request_data.id") ~
      str("request_data.sender") ~
      str("request_data.soapAction") ~
      long("request_data.environmentId") ~
      str("request_data.localTarget") ~
      str("request_data.remoteTarget") ~
      str("request_data.request") ~
      str("request_data.requestHeaders") map {
        case id ~ sender ~ soapAction ~ environnmentId ~ localTarget ~ remoteTarget ~ request ~ requestHeaders =>
          val headers = headersFromString(requestHeaders)
          new RequestData(id, sender, soapAction, environnmentId, localTarget, remoteTarget, request, headers, null, null, null, -1, -1)
      }
  }

  /**
   * Construct the Map[String, String] needed to fill a select options set.
   */
  def soapActionOptions: Seq[(String, String)] = DB.withConnection {
    implicit connection =>
      Cache.getOrElse[Seq[(String, String)]](keyCacheSoapAction) {
        Logger.debug("RequestData.SoapAction not found in cache: loading from db")
        SQL("select distinct(soapAction) from request_data order by soapAction asc").as((get[String]("soapAction") ~ get[String]("soapAction")) *).map(flatten)
      }
  }

  /**
   * Construct the Map[String, String] needed to fill a select options set.
   */
  def statusOptions: Seq[(String, String)] = DB.withConnection {
    implicit connection =>
      Cache.getOrElse[Seq[(String, String)]](keyCacheStatusOptions) {
        Logger.debug("RequestData.status not found in cache: loading from db")
        SQL("select distinct(status) from request_data").as(get[Int]("status") *).map(c => c.toString -> c.toString)
      }
  }

  /**
   * find Min Date.
   */
  def getMinStartTime: Option[Date] = DB.withConnection {
    implicit connection =>
      Cache.getOrElse[Option[Date]](keyCacheMinStartTime) {
        Logger.debug("MinStartTime not found in cache: loading from db")
        SQL("select min(startTime) as startTimeMin from request_data").as(scalar[Option[Date]].single)
      }
  }

  /**
   * Insert a new RequestData.
   *
   * @param requestData the requestData
   */
  def insert(requestData: RequestData) = {
    var xmlRequest = ""
    var xmlResponse = ""

    val environment = Environment.findById(requestData.environmentId).get
    val date = new Date()
    val gcal = new GregorianCalendar()
    gcal.setTime(date)
    gcal.get(Calendar.HOUR_OF_DAY); // gets hour in 24h format

    if (environment.hourRecordXmlDataMin <= gcal.get(Calendar.HOUR_OF_DAY) &&
      environment.hourRecordXmlDataMax > gcal.get(Calendar.HOUR_OF_DAY)) {
      xmlRequest = requestData.request
      xmlResponse = requestData.response
    } else {
      val msg = "Xml Data not recording. Record between " + environment.hourRecordXmlDataMin + "h to " + environment.hourRecordXmlDataMax + "h for this environment."
      xmlRequest = msg
      xmlResponse = msg
      Logger.debug(msg)
    }

    try {
      DB.withConnection {
        implicit connection =>
          SQL(
            """
            insert into request_data 
              (id, sender, soapAction, environmentId, localTarget, remoteTarget, request, requestHeaders, startTime, response, responseHeaders, timeInMillis, status) values (
              (select next value for request_data_seq), 
              {sender}, {soapAction}, {environmentId}, {localTarget}, {remoteTarget}, {request}, {requestHeaders}, {startTime}, {response}, {responseHeaders}, {timeInMillis}, {status}
            )
            """).on(
              'sender -> requestData.sender,
              'soapAction -> requestData.soapAction,
              'environmentId -> requestData.environmentId,
              'localTarget -> requestData.localTarget,
              'remoteTarget -> requestData.remoteTarget,
              'request -> xmlRequest,
              'requestHeaders -> headersToString(requestData.requestHeaders),
              'startTime -> requestData.startTime,
              'response -> xmlResponse,
              'responseHeaders -> headersToString(requestData.responseHeaders),
              'timeInMillis -> requestData.timeInMillis,
              'status -> requestData.status).executeUpdate()
      }
    } catch {
      case e: Exception => Logger.error("Error during insertion of RequestData ", e)
    }
  }

  /**
   * Delete all request data !
   */
  def deleteAll() {
    DB.withConnection {
      implicit connection =>
        SQL("delete from request_data").executeUpdate()
    }
    Cache.remove(keyCacheSoapAction)
    Cache.remove(keyCacheStatusOptions)
  }

  /**
   * Delete XML data (request & reponse) between min and max date
   * @param environmentIn environmement or "" / all if all
   * @param minDate min date
   * @param maxDate max date
   * @param user use who delete the data : admin or akka
   */
  def deleteRequestResponse(environmentIn: String, minDate: Date, maxDate: Date, user : String) {
    Logger.debug("Environment:" + environmentIn + " mindate:" + minDate.getTime + " maxDate:" + maxDate.getTime)
    Logger.debug("EnvironmentSQL:" + sqlAndEnvironnement(environmentIn))

    val d = new Date()
    val deleted = "deleted by " + user + " " + d.toString

    DB.withConnection {
      implicit connection =>
        SQL(
          """
            update request_data
            set response = {deleted},
            request = {deleted},
            purged = true
            where startTime >= {minDate} and startTime <= {maxDate} and purged = false
          """
            + sqlAndEnvironnement(environmentIn)).on(
            'deleted -> deleted,
            'minDate -> minDate,
            'maxDate -> maxDate).executeUpdate()

    }
    Cache.remove(keyCacheSoapAction)
    Cache.remove(keyCacheStatusOptions)
  }

  /**
   * Delete entries between min and max date
   * @param environmentIn environmement or "" / all if all
   * @param minDate min date
   * @param maxDate max date
   */
  def delete(environmentIn: String, minDate: Date, maxDate: Date) {
    DB.withConnection {
      implicit connection =>
        SQL(
          """
            delete from request_data where startTime >= {minDate} and startTime <= {maxDate}
          """
            + sqlAndEnvironnement(environmentIn)).on(
            'minDate -> minDate,
            'maxDate -> maxDate).executeUpdate()

    }
    Cache.remove(keyCacheSoapAction)
    Cache.remove(keyCacheStatusOptions)
  }

  /*
  * Get All RequestData, used for testing only
  */
  def findAll(): List[RequestData] = DB.withConnection {
    implicit c =>
      SQL("select * from request_data").as(RequestData.simple *)
  }

  /**
   * Return a page of RequestData
   * @param environmentIn name of environnement, "all" default
   * @param soapActionIn soapAction, "all" default
   * @param minDate Min Date
   * @param maxDate Max Date
   * @param status Status
   * @param offset offset in search
   * @param pageSize size of line in one page
   * @param filterIn filter on soapAction. Usefull only is soapActionIn = "all"
   * @return
   */
  def list(environmentIn: String, soapActionIn: String, minDate: Date, maxDate: Date, status: String, offset: Int = 0, pageSize: Int = 10, filterIn: String = "%"): Page[(RequestData)] = {

    val filter = "%" + filterIn + "%"

    var soapAction = "%" + soapActionIn + "%"
    if (soapActionIn == "all") soapAction = "%"

    var sqlStatus = ""
    if (status != "all") sqlStatus = " and status = {status}"

    DB.withConnection {
      implicit connection =>

        val requests = SQL(
          """
          select id, sender, soapAction, environmentId, localTarget, remoteTarget, startTime, timeInMillis, status from request_data
          where request_data.soapAction like {filter}
          and request_data.soapAction like {soapAction}
          and startTime >= {minDate} and startTime <= {maxDate}
          """
            + sqlStatus + sqlAndEnvironnement(environmentIn) +
            """
          order by request_data.id desc
          limit {pageSize} offset {offset}
            """).on(
            'pageSize -> pageSize,
            'offset -> offset,
            'soapAction -> soapAction,
            'minDate -> minDate,
            'maxDate -> maxDate,
            'status -> status,
            'filter -> filter).as(RequestData.simple *)

        val totalRows = SQL(
          """
          select count(id) from request_data 
          where request_data.soapAction like {filter}
          and request_data.soapAction like {soapAction}
          and startTime >= {minDate} and startTime <= {maxDate}
          """
            + sqlStatus + sqlAndEnvironnement(environmentIn) +
            """
          """).on(
            'soapAction -> soapAction,
            'minDate -> minDate,
            'maxDate -> maxDate,
            'status -> status,
            'filter -> filter).as(scalar[Long].single)
        Page(requests, -1, offset, totalRows)
    }
  }

  /**
   * Load reponse times for given parameters
   */
  def findResponseTimes(environmentIn: String, soapActionIn: String, minDate: Date, maxDate: Date, status: String): List[(Long, String, Date, Long)] = {

    var soapAction = "%" + soapActionIn + "%"
    if (soapActionIn == "all") soapAction = "%"
    var sqlStatus = ""
    if (status != "all") sqlStatus = " and status = {status}"

    DB.withConnection { implicit connection =>
      SQL(
        """
        select environmentId, soapAction, startTime, timeInMillis from request_data
        where soapAction like {soapAction}
        and startTime >= {minDate} and startTime <= {maxDate}
        """
          + sqlStatus + sqlAndEnvironnement(environmentIn) +
          """
        order by request_data.id asc
        """).on(
          'status -> status,
          'minDate -> minDate,
          'maxDate -> maxDate,
          'soapAction -> soapAction)
        .as(get[Long]("environmentId") ~ get[String]("soapAction") ~ get[Date]("startTime") ~ get[Long]("timeInMillis") *)
        .map(flatten)
    }
  }

  def load(id: Long): RequestData = {
    DB.withConnection { implicit connection =>
      SQL("select id, sender, soapAction, environmentId, localTarget, remoteTarget, request, requestHeaders from request_data where id= {id}")
        .on('id -> id).as(RequestData.forReplay.single)
    }
  }

  def loadRequest(id: Long): String = {
    DB.withConnection { implicit connection =>
      SQL("select request from request_data where id= {id}").on('id -> id).as(str("request").single)
    }
  }

  def loadResponse(id: Long): Option[String] = {
    DB.withConnection { implicit connection =>
      SQL("select response from request_data where id = {id}").on('id -> id).as(str("response").singleOpt)
    }
  }

  private def sqlAndEnvironnement(environmentIn: String): String = {

    var environment = ""

    // search by name
    if (environmentIn != "all" && Environment.options.exists(t => t._2 == environmentIn))
      environment = "and request_data.environmentId = " + Environment.options.find(t => t._2 == environmentIn).get._1

    // search by id
    if (environment == "" && Environment.options.exists(t => t._1 == environmentIn))
      environment = "and request_data.environmentId = " + Environment.options.find(t => t._1 == environmentIn).get._1

    environment
  }

  private def headersToString(headers: Map[String, String]): String = {
    headers.foldLeft("") { (string, header) => string + header._1 + " -> " + header._2 + "\n" }
  }

  private def headersFromString(headersAsStr: String): Map[String, String] = {
    def headers = headersAsStr.split("\n").collect {
      case header =>
        val parts = header.split(" -> ")
        (parts(0), parts.tail.mkString(""))
    }
    headers.toMap
  }

  // use by Json : from scala to json
  implicit object RequestDataWrites extends Writes[RequestData] {

    def writes(o: RequestData): JsValue = {
      val dlRequestUrl = "/download/request/" + o.id
      val dlResponseUrl = "/download/response/" + o.id

      JsObject(
        List("0" -> JsString(status(o.status)),
          "1" -> JsString(Environment.options.find(t => t._1 == o.environmentId.toString).get._2),
          "2" -> JsString(o.sender),
          "3" -> JsString(soapAction(o)),
          "4" -> JsString(o.startTime.toString),
          "5" -> JsString(o.timeInMillis.toString),
          "6" -> JsString("<a href='" + dlRequestUrl + "?asFile=true' title='Download'><i class='icon-file'></i></a> <a target='_blank' href='" + dlRequestUrl + "' title='Open in new tab'><i class='icon-eye-open'></i></a>"),
          "7" -> JsString("<a href='" + dlResponseUrl + "?asFile=true' title='Download'><i class='icon-file'></i></a> <a target='_blank' href='" + dlResponseUrl + "' title='Open in new tab'><i class='icon-eye-open'></i></a>"),
          "8" -> JsString("<a href='#' class='replay' data-request-id='" + o.id + "'><i class='icon-refresh'></i></a>")))
    }

    private def status(status: Int): String = {
      if (status == Status.OK) {
        "<span class='label label-success'>" + status.toString + "</span>"
      } else {
        "<span class='label label-important'>" + status.toString + "</span>"
      }
    }

    private def soapAction(o: RequestData): String = {
      "<a class='popSoapAction' href='#' rel='tooltip' title='Local: " + o.localTarget + " Remote: " + o.remoteTarget + "'>" + o.soapAction + "</a>"
    }
  }

}
