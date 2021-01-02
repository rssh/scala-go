package gopher

import cps._
import scala.concurrent.duration.Duration

import java.util.logging.{Level => LogLevel}


trait Gopher[F[_]:CpsSchedulingMonad]:

  type Monad[X] = F[X]
  def asyncMonad: CpsSchedulingMonad[F] = summon[CpsSchedulingMonad[F]]

  def makeChannel[A](bufSize:Int = 0,
                    autoClose: Boolean = false): Channel[F,A,A]                  

  def makeOnceChannel[A](): Channel[F,A,A] =
                    makeChannel[A](1,true)                   

  def select: Select[F] =
    new Select[F](this)  

  def time: Time[F] 

  def setLogFun(logFun:(LogLevel, String, Throwable|Null) => Unit): ((LogLevel, String, Throwable|Null) => Unit)

  def log(level: LogLevel, message: String, ex: Throwable| Null): Unit

  def log(level: LogLevel, message: String): Unit =
    log(level,message, null)

  protected[gopher] def logImpossible(ex: Throwable): Unit =
    log(LogLevel.WARNING, "impossible", ex)

  
def makeChannel[A](bufSize:Int = 0, 
                  autoClose: Boolean = false)(using g:Gopher[?]):Channel[g.Monad,A,A] =
      g.makeChannel(bufSize, autoClose)

def makeOnceChannel[A]()(using g:Gopher[?]): Channel[g.Monad,A,A] =
      g.makeOnceChannel[A]()                   

def select(using g:Gopher[?]):Select[g.Monad] =
      g.select

