package akka.concurrent


import java.util.concurrent.TimeUnit

import scala.util.{Try, Success, Failure }
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import scala.scalajs.js
import scala.scalajs.js.Dynamic.global
import scala.scalajs.js.timers._
import scala.scalajs.runtime.UndefinedBehaviorError
import scala.collection.mutable.{ Queue, ListBuffer }
 

object ManagedEventLoop {

  private val jsSetTimeout = global.setTimeout
  private val jsSetInterval = global.setInterval
  private val jsClearTimeout = global.clearTimeout
  private val jsClearInterval = global.clearInterval

  def timer() = Duration.fromNanos(System.nanoTime())

  type JSFun = js.Function0[Any]
  private abstract class Event(handler: JSFun) {
    val creationDate = timer()
    var externalQueueH: Option[js.Dynamic] = None
    def clear(): Unit
    def globalize(): Unit
    def hasToRun(now: Duration): Boolean
    val hasToBeRemoved: Boolean

    def run(): Unit = handler()
  }
  private final case class TimeoutEvent(handler: JSFun, time: Double) extends Event(handler) {
    def clear(): Unit = externalQueueH.map(x => jsClearTimeout(x))
    def globalize(): Unit =
      externalQueueH = Some(
        jsSetTimeout(() => {
          handler()
          events -= this
        }, time.asInstanceOf[js.Any])
      )
    def hasToRun(now: Duration): Boolean = {
      (now > creationDate + Duration.fromNanos(time*1000000))
    }
    val hasToBeRemoved: Boolean = true
  }
  private final case class IntervalEvent(handler: JSFun, time: Double) extends Event(handler) {
    var lastRan = creationDate
    def clear(): Unit = externalQueueH.map(x => jsClearInterval(x))
    def globalize(): Unit =
      externalQueueH = Some(
        jsSetInterval(() => {
          handler()
          lastRan = timer
        }, time.asInstanceOf[js.Any])
      )
    def hasToRun(now: Duration): Boolean = {
      (now > lastRan + Duration.fromNanos(time*1000000))
    }
    val hasToBeRemoved: Boolean = false
  }

  private val events = new ListBuffer[Event]()

  private var isBlocking: Int = 0

  def setBlocking = {
    if (isBlocking == 0)
      events.foreach(_.clear())

    isBlocking += 1
  }

  def resetBlocking = {
    isBlocking -= 1
    if (isBlocking == 0)
      events.foreach(_.globalize())
  }

  def tick(): Duration = {
    val now = timer()

    val toBeProcessed = events.filter(_.hasToRun(now))

    toBeProcessed.foreach(_.run())

    val toBeRemoved = toBeProcessed.filter(_.hasToBeRemoved)

    events --= toBeRemoved

    now
  }

  def reset: Unit = {
    global.setTimeout = jsSetTimeout
    global.setInterval = jsSetInterval
    global.clearTimeout = jsClearTimeout
    global.clearInterval = jsClearInterval
  }
 
  def manage: Unit = {
    global.setTimeout = { (f: js.Function0[_], delay: Number) =>
      if(f.toString() != "undefined") {
        val event = TimeoutEvent(f, delay.doubleValue())
        if (isBlocking == 0)
          event.globalize
        events += event
        event.asInstanceOf[SetTimeoutHandle]
      }
    }
    global.setInterval = { (f: js.Function0[_], interval: Number) =>
      val event = IntervalEvent(f, interval.doubleValue())
      if (isBlocking == 0)
        event.globalize()
      events += event
      event.asInstanceOf[SetIntervalHandle]
    }
    global.clearTimeout = (event: SetTimeoutHandle) => {
      event.asInstanceOf[TimeoutEvent].clear()
      events -= event.asInstanceOf[TimeoutEvent]
    }
    global.clearInterval = (event: SetIntervalHandle) => {
      event.asInstanceOf[IntervalEvent].clear()
      events -= event.asInstanceOf[IntervalEvent]
    }
  }
  
  manage
}

sealed trait CanAwait

object AwaitPermission extends CanAwait

trait Awaitable[T] {
   def ready(atMost: Duration)(implicit permit: CanAwait): Awaitable.this.type
   def result(atMost: Duration)(implicit permit: CanAwait): T
}

case class LastRan(time: Double)

object Await {

  def ready[T](awaitable: Awaitable[T], atMost: Duration): awaitable.type =
    awaitable.ready(atMost)(AwaitPermission)

  def result[T](f: Future[T]): T = {
    import scala.concurrent.duration._
    result[T](f, Duration.Inf)
 }

  def result[T](f: Future[T], atMost: Duration): T = {
    val initTime = ManagedEventLoop.timer()
    val endTime = initTime + atMost

    ManagedEventLoop.setBlocking
    @scala.annotation.tailrec
    def loop(f: Future[T]): Try[T] = {
      val execution: Duration = ManagedEventLoop.tick

      if(execution > endTime) throw new java.util.concurrent.TimeoutException(s"Futures timed out after [${atMost.toMillis}] milliseconds")
      else f.value match {
        case None => loop(f)
        case Some(res) => res
      }
    }

    val ret = loop(f)
    ManagedEventLoop.resetBlocking
    ret match {
      case Success(m) => m
      // if it's a wrapped execution (something coming from inside a promise)
      // we need to unwrap it, otherwise just return the regular throwable
      case Failure(e) => throw (e match {
        case _: scala.concurrent.ExecutionException => e.getCause()
        case _ => e
      })
    }
  }

  def result[T](awaitable: Awaitable[T], atMost: Duration): T =
    awaitable.result(atMost)(AwaitPermission)

}

class CountDownLatch(val c: Int) {
  import scala.concurrent.Promise
  private var counter = c
  private var closed = Promise[Int]

  def countDown() = {
    counter -= 1
    if(counter == 0) closed.success(1)
  }
  def getCount() = counter
  def reset() = counter = c


  def await(timeout: Long, unit: TimeUnit): Boolean = {
    try {
      Await.result(closed.future, Duration.fromNanos(TimeUnit.NANOSECONDS.convert(timeout, unit)))
      true
    } catch {
      case e: Exception => throw e
    }
  }
}
