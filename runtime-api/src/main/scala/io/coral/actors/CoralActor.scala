package io.coral.actors

// scala
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._

// akka
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

//spray utils
import spray.util.actorSystem

//json goodness
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._

//coral

import io.coral.actors.Messages._

sealed class TimerBehavior
object TimerExit     extends TimerBehavior
object TimerContinue extends TimerBehavior
object TimerNone     extends TimerBehavior

abstract class CoralActor(json: JObject)
  extends Actor
  with NoTrigger
  with NoTimer
  with ActorLogging {

  // begin: implicits and general actor init
  def actorRefFactory = context

  def jsonDef = json

  var emitTargets = SortedSet.empty[ActorRef]

  // numeric id  or None or "external"
  var collectSources = Map.empty[String, String] // zero or more alias to actorpath id

  var inputJsonDef: JValue = JObject()

  // getting the default executor from the akka system
  implicit def executionContext: ExecutionContextExecutor = actorRefFactory.dispatcher

  implicit val timeout = Timeout(1000.milliseconds)

  def askActor(a: String, msg: Any)  = actorRefFactory.actorSelection(a).ask(msg)
  def tellActor(a: String, msg: Any) = actorRefFactory.actorSelection(a).!(msg)

  implicit val formats = org.json4s.DefaultFormats

  def getCollectInputField[A](actorAlias: String, by: String, field: String)(implicit mf: Manifest[A]): Future[Option[A]] = {
    collectSources.get(actorAlias) match {
      case Some(actorPath) =>
        askActor(actorPath, GetFieldBy(field, by)).mapTo[JValue].map(json => json.extractOpt[A])
      case None => Future.failed(throw new Exception(s"Collect actor not defined"))
    }
  }

  def in[U](duration: FiniteDuration)(body: => U): Unit =
    actorSystem.scheduler.scheduleOnce(duration)(body)

  override def preStart() {
    timerInit()
  }

  // timer logic
  def timerInit() = {
    if (timerDuration > 0 && (timerMode == TimerExit || timerMode == TimerContinue))
      in(timerDuration.seconds) {
        self ! TimeoutEvent
      }
  }

  def timerDuration: Double = (jsonDef \ "attributes" \ "timeout" \ "duration").extractOrElse(0.0)

  def timerMode: TimerBehavior =
    (jsonDef \ "attributes" \ "timeout" \ "mode").extractOpt[String] match {
      case Some("exit") => TimerExit
      case Some("continue") => TimerContinue
      case _ => TimerNone
    }

  def receiveTimeout: Receive = {
    case TimeoutEvent =>
      executeTimer

      // depending on the configuration,
      // end the actor (gracefully) or ...
      // reset the timer

      timerMode match {
        case TimerContinue =>
          in(timerDuration.seconds) {
            self ! TimeoutEvent
          }
        case TimerExit =>
          tellActor("/user/coral", Delete(self.path.name.toLong))
        case _ => // do nothing
      }
  }

  def executeTimer = {
    val future = timer
    future.onSuccess {
      case Some(result) =>
        emit(result)

      case None => log.warning("not processed")
    }

    future.onFailure {
      case _ => log.warning("actor execution")
    }
  }

  def emitAdmin: Receive = {
    case RegisterActor(r) =>
      emitTargets += r
  }

  def emit(json:JValue) = {
    json match {
      case json: JObject =>
        emitTargets map (actorRef => actorRef ! json)
      case _ =>
    }
  }

  def propHandling: Receive = {
    case UpdateProperties(json) =>
      // update trigger
      val triggerSource = (json \ "attributes" \ "input" \ "trigger" \ "in" \ "type").extractOpt[String]
      val triggerJsonDef = triggerSource match {
        case Some("none") => render("trigger" -> (json \ "attributes" \ "input" \ "trigger"))
        case Some("external") => render("trigger" -> (json \ "attributes" \ "input" \ "trigger"))
        case Some("actor") =>
          val source = (json \ "attributes" \ "input" \ "trigger" \ "in" \ "source").extractOpt[String]
          source map { v =>
            tellActor(s"/user/coral/$v", RegisterActor(self))
          }
          render("trigger" -> (json \ "attributes" \ "input" \ "trigger"))

        case _ =>
          JObject()
      }

      // update collect
      val collectAliases = (json \ "attributes" \ "input" \ "collect").extractOpt[Map[String, Any]]
      val result = collectAliases match {
        case Some(v) =>
          val x = v.keySet.map(k => (k, (json \ "attributes" \ "input" \ "collect" \ k \ "source")
            .extractOpt[Int].map(v => s"/user/coral/$v")))
          (x.filter(_._2.isDefined).map(i => (i._1, i._2.get)).toMap, render("collect" -> (json \ "attributes" \ "input" \ "collect")))
        case None =>
          (Map[String, String](), JObject())
      }
      collectSources = result._1
      val collectJsonDef = result._2

      inputJsonDef = triggerJsonDef merge collectJsonDef

      sender ! true
  }

  def resourceDesc: Receive = {
    case Get() =>
      sender ! (jsonDef
        merge render("attributes" -> render("state" -> render(state)))
        merge render("attributes" -> render("input" -> inputJsonDef)))
  }

  def execute(json:JObject, sender:Option[ActorRef]) = {
    val future = trigger(json)

    future.onSuccess {
      case Some(result) =>
        emit(result)
        sender.foreach(_ ! result)

      case None => log.warning("not processed")
    }

    future.onFailure {
      case _ => log.warning("actor execution")
    }
  }

  def jsonData: Receive = {
    case json: JObject =>
      execute(json,None)

    case Shunt(json) =>
      execute(json,Some(sender()))
  }

  var children = SortedMap.empty[String, Long]

  def receiveExtra:Receive = {case Unit => }

  def receive = jsonData           orElse
                stateReceive       orElse
                emitAdmin          orElse
                propHandling       orElse
                resourceDesc       orElse
                receiveTimeout     orElse
                receiveExtra

  def state: Map[String, JValue] = noState
  val noState: Map[String, JValue] = Map.empty

  def stateResponse(x:String,by:Option[String],sender:ActorRef) = {
    if ( by.getOrElse("").isEmpty) {
      val value = state.get(x)
      sender ! render(value)
    } else {
      val found = children.get(by.get) flatMap (a => actorRefFactory.child(a.toString))

      found match {
        case Some(actorRef) =>
          actorRef forward GetField(x)

        case None =>
          sender ! render(JNothing)
      }
    }
  }

  def stateReceive: Receive = {
    case GetField(x) =>
      stateResponse(x,None, sender())
    case GetFieldBy(x,by) =>
      stateResponse(x,Some(by), sender())
  }
}