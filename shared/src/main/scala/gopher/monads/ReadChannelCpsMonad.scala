package gopher.monads

import gopher._
import cps._

import gopher.impl._


given ReadChannelCpsMonad[F[_]](using Gopher[F]): CpsMonad[ [A] =>> ReadChannel[F,A]] with

  def pure[T](t:T): ReadChannel[F,T] = 
     ReadChannel.fromValues[F,T](t)

  def map[A,B](fa: ReadChannel[F,A])(f: A=>B): ReadChannel[F,B] =
     fa.map(f)

  def flatMap[A,B](fa: ReadChannel[F,A])(f: A=>ReadChannel[F,B]): ReadChannel[F,B] =
    new ChFlatMappedReadChannel[F,A,B](fa,f)   


given futureToReadChannel[F[_],T](using Gopher[F]): Conversion[F[T], ReadChannel[F,T]] with

   def apply(ft: F[T]): ReadChannel[F,T] = futureInput(ft)


   


