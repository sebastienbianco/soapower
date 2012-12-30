package controllers

import play.api.mvc._
import models._
import play.api.libs.iteratee._
import play.api.http._
import play.api.data._
import play.api.data.Forms._
import java.util.Date
import java.io.File
import play.api._


object Admin extends Controller {

  val Home = Redirect(routes.Admin.index())

  case class AdminForm(environment: String, minDate: Date, maxDate: Date, typeAction: String)

  val adminForm = Form(
    mapping(
      "environment" -> text,
      "minDate" -> date,
      "maxDate" -> date,
      "typeAction" -> nonEmptyText
    )(AdminForm.apply)(AdminForm.unapply)
  )

  def index = Action {
    implicit request =>
      Ok(views.html.admin.index(Environment.options, adminForm))
  }

  def uploadConfiguration = Action(parse.multipartFormData) {
    request =>
      request.body.file("fileUploaded").map {
        fileUploaded =>
          import scala.io._
          var err = ""
          Source.fromFile(fileUploaded.ref.file).getLines().foreach {
            line =>
              try {
                if (line.startsWith(Service.csvKey)) {
                  Service.upload(line)
                } else if (line.startsWith(Environment.csvKey)) {
                  Environment.upload(line)
                } else if (line.startsWith(SoapAction.csvKey)) {
                  SoapAction.upload(line)
                } else if (line.startsWith(RequestData.csvKey)) {
                  RequestData.upload(line)
                }

              } catch {
                case e: Exception => {
                  err += e.getMessage
                }
              }
          }
          if (err.size > 0) {
            Home.flashing("warning" -> "Configuration uploaded partially. See Warn Logs")
          } else {
            Home.flashing("success" -> "Configuration Uploaded")
          }
      }.getOrElse {
        Home.flashing("warning" -> "Failed to upload configuration")
      }
  }

  def downloadConfiguration = Action {
    // Title
    var content = "#for key " + Environment.csvKey + "\n"
    Environment.csvTitle.toList.sortBy(_._2).foreach { case (k, v) => content += k + ";" }
    content = content.dropRight(1) + "\n" // delete last ; and add new line
    content += "#for key " + SoapAction.csvKey + "\n"
    SoapAction.csvTitle.toList.sortBy(_._2).foreach { case (k, v) => content += k + ";" }
    content = content.dropRight(1) + "\n"
    content += "#for key " + Service.csvKey + "\n"
    Service.csvTitle.toList.sortBy(_._2).foreach { case (k, v) => content += k + ";" }
    content = content.dropRight(1) + "\n"

    // data
    Environment.fetchCsv().foreach { s => content += Environment.csvKey + ";" + s }
    SoapAction.fetchCsv().foreach { s => content += SoapAction.csvKey + ";" + s }
    Service.fetchCsv().foreach { s => content += Service.csvKey + ";" + s }

    // result as a file
    val fileContent: Enumerator[String] = Enumerator(content)
    SimpleResult(
      header = ResponseHeader(play.api.http.Status.OK),
      body = fileContent
    ).withHeaders((HeaderNames.CONTENT_DISPOSITION, "attachment; filename=configuration.csv")).as(BINARY)
  }

  def downloadRequestDataStatsEntries = Action {
    // Title
    var content = "#for key " + RequestData.csvKey + "\n"
    RequestData.csvTitle.toList.sortBy(_._2).foreach { case (k, v) => content += k + ";" }
    content = content.dropRight(1) + "\n" // delete last ; and add new line

    // data
    RequestData.fetchCsv().foreach { s => content += RequestData.csvKey + ";" + s }

    // result as a file
    val fileContent: Enumerator[String] = Enumerator(content)
    SimpleResult(
      header = ResponseHeader(play.api.http.Status.OK),
      body = fileContent
    ).withHeaders((HeaderNames.CONTENT_DISPOSITION, "attachment; filename=requestDataStatsEntries.csv")).as(BINARY)
  }

  def deleteAllRequestData = Action {
    //RequestData.deleteAll()
    Home.flashing("error" -> "You can't delete all data. Actually disabled on Soapower")
  }

  def delete = Action { implicit request =>
      adminForm.bindFromRequest.fold(
        formWithErrors => BadRequest(views.html.admin.index(Environment.options, formWithErrors)),
        form => {
          val maxDate = new Date(form.maxDate.getTime +  ((24*60*60)-1)*1000) // 23h59,59s
          Logger.debug("Delete min:" + form.minDate + " max:" + maxDate)
          form.typeAction match {
            case "xml-data" => RequestData.deleteRequestResponse(form.environment, form.minDate, maxDate, "Admin Soapower")
            case "all-data" => RequestData.delete(form.environment, form.minDate, maxDate)
          }
          Home.flashing("success" -> "Success deleting data")
        })
  }
}
