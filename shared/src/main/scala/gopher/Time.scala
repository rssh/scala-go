package gopher

import cps._
import gopher.impl._

import scala.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import scala.language.experimental.macros
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.util.concurrent.atomic.AtomicBoolean
import java.util.TimerTask


/**
  * Time API, simular to one in golang standard library.
  * @see gopherApi#time
  */
abstract class Time[F[_]](gopherAPI: Gopher[F]) {

    type after = FiniteDuration

    def after(duration: FiniteDuration): ReadChannel[F,FiniteDuration] =
    {
        val ch = gopherAPI.makeOnceChannel[FiniteDuration]()
        schedule( () => {
                val now = FiniteDuration(System.currentTimeMillis, TimeUnit.MILLISECONDS)
                ch.awrite(now)
            },
            duration
        )
        ch    
    }

    def asleep(duration: FiniteDuration): F[FiniteDuration] =
    {
        var fun: Try[FiniteDuration] => Unit = _ => ()
        val retval = gopherAPI.asyncMonad.adoptCallbackStyle[FiniteDuration](listener => fun = listener)
        schedule(() => {
                  val now = FiniteDuration(System.currentTimeMillis, TimeUnit.MILLISECONDS)
                  fun(Success(now))
                }, 
                duration)
        retval
    }

    inline def sleep(duration: FiniteDuration): FiniteDuration = 
        given CpsSchedulingMonad[F] = gopherAPI.asyncMonad
        await(asleep(duration))

    /**
      * create ticker. When somebody read this ticker, than one receive duration
      *  messages.  When nobody reading - messages are expired.
      * @param duration
      * @return
      */
    def tick(duration: FiniteDuration): ReadChannel[F,FiniteDuration] =
    {
     newTicker(duration).channel
    }

    
    class Ticker(duration: FiniteDuration) {
        
        val channel = gopherAPI.makeChannel[FiniteDuration](0).withExpiration(duration, false)

        private val scheduled = schedule(tick, duration)
        private val stopped = AtomicBoolean(false)

        def stop(): Unit = {
            scheduled.cancel()
            stopped.set(true)
        }

        private def tick():Unit = {
            if (!stopped.get()) then 
                channel.addWriter(SimpleWriter(now(),{
                        case Success(_) => // ok, somebody readed
                        case Failure(ex) =>
                            ex match
                                case ex: ChannelClosedException => 
                                    scheduled.cancel()
                                    stopped.lazySet(true)
                                case ex: TimeoutException => // 
                                case other => // impossible, 
                                    gopherAPI.logImpossible(other)
                }))
                schedule(tick, duration)
        }
        
    }

    def newTicker(duration: FiniteDuration): Ticker =
    {
        new Ticker(duration)
    }
    

    def now(): FiniteDuration = 
        FiniteDuration(System.currentTimeMillis(),TimeUnit.MILLISECONDS)


    /**
    * Low lwvel interface for scheduler
    */
    def schedule(fun: () => Unit, delay: FiniteDuration): Time.Scheduled


}

object Time:

    /**
     * Used in selector shugar for specyfying tineout.
     *```
     * select{
     *    ......
     *    case t: Time.after if t > expr =>  doSomething
     * }
     *```
     * is a sugar for to selectGroup.{..}.setTimeout(expr, t=>doSomething)
     *@see Select
     **/
    type after = FiniteDuration


    def after[F[_]](duration: FiniteDuration)(using Gopher[F]): ReadChannel[F,FiniteDuration] =
        summon[Gopher[F]].time.after(duration)

    
    def asleep[F[_]](duration: FiniteDuration)(using Gopher[F]): F[FiniteDuration] =
        summon[Gopher[F]].time.asleep(duration)

    inline def sleep[F[_]](duration: FiniteDuration)(using Gopher[F]): FiniteDuration = 
        summon[Gopher[F]].time.sleep(duration)    
    

    /**
    * Task, which can be cancelled.
    **/    
    trait Scheduled {

       def cancel(): Boolean

       def onDone( listener: Try[Boolean]=>Unit ): Unit

    }

    


