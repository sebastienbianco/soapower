package models

import akka.actor._
import scala.concurrent.duration._

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

object Robot {

  var liveRoom: ActorRef = null

  def apply(room: ActorRef) {
    liveRoom = room

    // Create an Iteratee that logs all messages to the console.
    val loggerIteratee = Iteratee.foreach[JsValue](event => Logger("robot").info(event.toString))

    implicit val timeout = Timeout(1 second)
    // Make the robot join the room
    liveRoom ? (Join("Robot")) map {
      case Connected(robotChannel) =>
        // Apply this Enumerator on the logger.
        robotChannel |>> loggerIteratee
    }
  }

  def talkMsg(msg: String, typeMsg: String) {
    Akka.system.scheduler.scheduleOnce(
      0 seconds,
      liveRoom,
      Talk("Robot", msg, typeMsg)
    )
  }

  def talk(requestData: RequestData) {
    Akka.system.scheduler.scheduleOnce(
      0 seconds,
      liveRoom,
      TalkRequestData("Robot", requestData)
    )
  }
}

object LiveRoom {

  implicit val timeout = Timeout(1 second)

  lazy val default = {
    val roomActor = Akka.system.actorOf(Props[LiveRoom])

    // Create a bot user (just for fun)
    Logger.info("Init Robot Live Room")
    Robot(roomActor)

    roomActor
  }

  def init {
    Logger.info("Init LiveRoom")
    default
  }

  def join(username: String): scala.concurrent.Future[(Iteratee[JsValue, _], Enumerator[JsValue])] = {

    (default ? Join(username)).map {

      case Connected(enumerator) =>

        // Create an Iteratee to consume the feed
        val iteratee = Iteratee.foreach[JsValue] {
          event =>
            default ! Talk(username, (event \ "text").as[String], "talk")
        }.map {
          _ =>
            default ! Quit(username)
        }

        (iteratee, enumerator)

      case CannotConnect(error) =>
        // Connection error
        // A finished Iteratee sending EOF
        val iteratee = Done[JsValue, Unit]((), Input.EOF)

        // Send an error and close the socket
        val enumerator = Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))

        (iteratee, enumerator)

    }
  }
}

class LiveRoom extends Actor {

  var members = Set.empty[String]
  val (liveEnumerator, channel) = Concurrent.broadcast[JsValue]

  def receive = {

    case Join(username) => {
      if (members.contains(username)) {
        sender ! CannotConnect("You have already a navigator on this page !")
      } else {
        members = members + username
        sender ! Connected(liveEnumerator)
        self ! NotifyJoin(username)
      }
    }

    case NotifyJoin(username) => {
      notifyAll("join", username, "has entered the room")
    }

    case Talk(username, text, typeMsg) => {
      notifyAll(typeMsg, username, text)
    }

    case TalkRequestData(username, requestData) => {
      notifyAll("talkRequestData", username, requestData)
    }

    case Quit(username) => {
      members = members - username
      notifyAll("quit", username, "has left the room")
    }

  }

  def notifyAll(kind: String, user: String, requestData: RequestData) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user),
        "message" -> JsArray(Seq(Json.toJson(requestData))),
        "members" -> JsArray(
          members.toList.map(JsString)
        )
      )
    )
    channel.push(msg)
  }

  def notifyAll(kind: String, user: String, text: String) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user),
        "message" -> JsString(text),
        "members" -> JsArray(
          members.toList.map(JsString)
        )
      )
    )
    channel.push(msg)
  }

}

case class Join(username: String)

case class Quit(username: String)

case class TalkRequestData(username: String, requestData: RequestData)

case class Talk(username: String, text: String, typeMsg: String)

case class NotifyJoin(username: String)

case class Connected(enumerator: Enumerator[JsValue])

case class CannotConnect(msg: String)
