package gopher.channels;

import gopher._

import scala.concurrent._
import scala.language._

/**
 * represent continuated computation from A to B.
 */
sealed trait Continuated[+A]
{
 type R =  X forSome { type X <: A @annotation.unchecked.uncheckedVariance }
}

sealed trait FlowContinuated[A] extends Continuated[A]
{
  def flowTermination: FlowTermination[A]
}


case class Done[A](result:A, override val flowTermination: FlowTermination[A]) extends FlowContinuated[A]

/**
 * read A and compute B as result.
 */
case class ContRead[A,B](
   function: ContRead[A,B] => 
               Option[
                  ContRead.In[A] => Future[Continuated[B]]
               ], 
   channel: Input[A], 
   override val flowTermination: FlowTermination[B]) extends FlowContinuated[B]
{
  type El = A
  type S  = B
  type F = ContRead[A,B]=>Option[ContRead.In[A] => Future[Continuated[B]]]
}

object ContRead
{


  sealed trait In[+A]
  case class Value[+A](a:A) extends In[A]
  case object Skip extends In[Nothing]
  case object ChannelClosed extends In[Nothing]
  case class Failure(ex:Throwable) extends In[Nothing]
  

  object In
  {
    def value[A](a:A) = ContRead.Value(a)
    def failure(ex:Throwable) = ContRead.Failure(ex)
    def channelClosed = ContRead.ChannelClosed
    def skip = ContRead.Skip
  }

   @inline
   def liftInValue[A,B](prev: ContRead[A,B])(f: Value[A] => Future[Continuated[B]] ): In[A] => Future[Continuated[B]] =
      {
        case v@Value(a) => f(v)
        case Skip => Future successful prev
        case ChannelClosed => prev.flowTermination.throwIfNotCompleted(new ChannelClosedException())
                              Never.future
        case Failure(ex) => prev.flowTermination.doThrow(ex)
                              Never.future
      }

   @inline
   def liftIn[A,B](prev: ContRead[A,B])(f: A => Future[Continuated[B]] ): In[A] => Future[Continuated[B]] =
    {
      //    liftInValue(prev)(f(_.a)) 
      // we do ilining by hands instead.
      case Value(a) => f(a)
      case Skip => Future successful prev
      case ChannelClosed => prev.flowTermination.throwIfNotCompleted(new ChannelClosedException())
                              Never.future
      case Failure(ex) => prev.flowTermination.doThrow(ex)
                              Never.future
    }


   def chainIn[A,B](prev: ContRead[A,B])(fn: (In[A], In[A] => Future[Continuated[B]]) => Future[Continuated[B]] ): 
                                            Option[In[A] => Future[Continuated[B]]] =
         prev.function(prev) map (f1 => liftInValue(prev) { v => fn(v,f1) } )

   type Aux[A,B] = ContRead[A,B]{ type El=A
                    type S=B
                    type F = ContRead[A,B]=>Option[ContRead.In[A]=>Future[Continuated[B]]] 
                   }

   type AuxF[A,B] = ContRead[A,B]=>Option[ContRead.In[A]=>Future[Continuated[B]]]

   type AuxE[A] = ({type B; type L=ContRead[A,B]})#L
}


/**
 * write A and compute B as result
 */
case class ContWrite[A,B](function: ContWrite[A,B] => Option[(A,Future[Continuated[B]])], channel: Output[A], override val flowTermination: FlowTermination[B]) extends FlowContinuated[B]
{
  type El = A
  type F = ContWrite[A,B]=>Option[(A,Future[Continuated[B]])]
}

object ContWrite
{
  type Aux[A,B] = ContWrite[A,B]
  type AuxF[A,B] = ContWrite[A,B]=>Option[(A,Future[Continuated[B]])]
}

/**
 * skip (i.e. do some operation not related to reading or writing.)
 */
case class Skip[A](function: Skip[A] => Option[Future[Continuated[A]]], override val flowTermination: FlowTermination[A]) extends FlowContinuated[A]

object Skip
{
  type AuxF[A] = Skip[A]=>Option[Future[Continuated[A]]]
}


/**
 * never means the end of conversation
 */
case object Never extends Continuated[Nothing]
{
  val future = Future successful Never
}



