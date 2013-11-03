package gopher.channels

import scala.concurrent._
import scala.util._
import akka.actor._


/**
 * Tie is an object which connect one or two channels and process messages between ones.
 * 
 */
trait Tie[ API <: ChannelsAPI[API] ] extends TieJoin {
 
  thisTie =>
  
  def addReadAction[A](ch: API#IChannel[A], action: ReadAction[A]): this.type
  
  def addWriteAction[A](ch: API#OChannel[A], action: WriteAction[A]): this.type
  
  def setIdleAction(action: IdleAction): this.type
  
  def readJoin[A](ch:API#IChannel[A]) = new TieReadJoin[A] {
    @inline
    def putNext(action: ReadAction[A]): Unit = addReadAction(ch,action)
    @inline
    def processExclusive[A](f: =>A, whenLocked: =>A):A =
      thisTie.processExclusive(f, whenLocked)
    @inline
    def shutdown() = thisTie.shutdown() 
  }
  
  def writeJoin[A](ch:API#OChannel[A]) = new TieWriteJoin[A] {
    @inline
    def putNext(action: WriteAction[A]): Unit = addWriteAction(ch,action)
    @inline
    def processExclusive[A](f: =>A, whenLocked: =>A):A =
      thisTie.processExclusive(f, whenLocked)
    @inline
    def shutdown() = thisTie.shutdown()     
  }
  
  /**
   * If implementation require starting of tie before action (for example - when tie contains
   *  thread), than do this action.  In some implementations can do nothing.
   */
  def start(): this.type
  /**
   * shutdown tea  (and activate next tie if set)
   */
  def shutdown(): Unit
   
  /**
   *  return Future which is fired when tie is shutdowned.
   */ 
  def shutdownFuture: Future[Unit]
  
  /**
   * If tie is exclusive, try or run f, if nothing is running
   * in the same exclusive mode or perform <code> whenLocked </code>
   */
  def processExclusive[A](f: => A, whenLocked: =>A):A
  
  /**
   * 
   */
  def next(implicit api: ChannelsAPI[API], ec: ExecutionContext, ac: ActorSystem = ChannelsActorSystemStub.defaultSystem): Tie[API] =
  {
    val n = api.makeTie
    shutdownFuture.onSuccess{ case _ => n.start() }
    n
  }
  
  /**
   * put tie, which will activated after shutdown of this tie.
   */
 // def putAfter(next: Tie[API])(implicit ec: ExecutionContext): Tie[API] =
 // {
 //  shutdownFuture.onComplete{
 //   case Success(x) => next.start
 //   case Failure(ex) => // TODO: keep exception here [?]
 //                       // for now: do noting
 //  }
 //  next
 // }
  
}