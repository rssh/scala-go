package gopher.impl

import cps._
import gopher._
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try
import scala.util.Success
import scala.util.Failure


/**
 * Guarded channel work in the next way:
 *   reader and writer asynchronically added to readers and writers and force evaluation of internal step function
 *    or ensure that currently running step function will see the chanes in readers/writers.
 *   Step functions is executed in some thread loop, and in the same time, only one instance of step function is running.
 *   (which is ensured by guard)
 **/
abstract class GuardedSPSCBaseChannel[F[_]:CpsAsyncMonad,A](gopherApi: JVMGopher[F], controlExecutor: ExecutorService, taskExecutor: ExecutorService) extends Channel[F,A,A]:

  import GuardedSPSCBaseChannel._

  protected val readers = new ConcurrentLinkedDeque[Reader[A]]()
  protected val writers = new ConcurrentLinkedDeque[Writer[A]]()

  protected val publishedClosed = new AtomicBoolean(false)

  protected val stepGuard = new AtomicInteger(STEP_FREE)

  override protected def asyncMonad: CpsAsyncMonad[F] = summon[CpsAsyncMonad[F]]

  protected val stepRunnable: Runnable = (()=>entryStep())

 
  def addReader(reader: Reader[A]): Unit =
    if (reader.canExpire) then
      readers.removeIf( _.isExpired )        
    readers.add(reader)
    controlExecutor.submit(stepRunnable)

  def addWriter(writer: Writer[A]): Unit =
      if (writer.canExpire) then
          writers.removeIf( _.isExpired )        
      writers.add(writer)
      controlExecutor.submit(stepRunnable)

  def close(): Unit =
    publishedClosed.set(true)
    controlExecutor.submit(stepRunnable)    

  protected def step(): Unit
  

  protected def entryStep(): Unit =
    var done = false
    while(!done) {
       if (stepGuard.compareAndSet(STEP_FREE,STEP_BUSY)) {
          done = true
          step()
       } else if (stepGuard.compareAndSet(STEP_BUSY, STEP_UPDATED)) {
          done = true
       } else if (stepGuard.get() == STEP_UPDATED) {
         // merge with othwer changes
          done = true
       } else {
         // other set updates, we should spinLock
         Thread.onSpinWait()
       }
    }

  /**
  * if truw - we can leave step, otherwise better run yet one step.
  */  
  protected def checkLeaveStep(): Boolean =
    if (stepGuard.compareAndSet(STEP_BUSY,STEP_FREE)) then
      true
    else if (stepGuard.compareAndSet(STEP_UPDATED, STEP_BUSY)) then
      false
    else
      // impossible, let'a r
      false

  
  protected def processReadClose(): Boolean  = 
    var progress = false
    while(!readers.isEmpty) {
      val r = readers.poll()
      if (!(r eq null) && !r.isExpired) then
         r.capture() match
            case Some(f) =>
              progress = true
              taskExecutor.execute(() => f(Failure(new ChannelClosedException())) )
            case None =>
              progress = true
              Thread.onSpinWait()
              if (!r.isExpired ) then
                readers.addLast(r)  
    }
    progress

  protected def processWriteClose(): Boolean =
    var progress = false
    while(!writers.isEmpty) {
      val w = writers.poll()
      if !(w eq null) && !w.isExpired then
        w.capture() match
          case Some((a,f)) =>
            progress = true
            taskExecutor.execute(() => f(Failure(new ChannelClosedException)) )
          case None =>
            progress = true
            if (!w.isExpired) then
              writers.addLast(w)
    }
    progress


object GuardedSPSCBaseChannel:

  final val STEP_FREE = 0

  final val STEP_BUSY = 1

  final val STEP_UPDATED = 2



