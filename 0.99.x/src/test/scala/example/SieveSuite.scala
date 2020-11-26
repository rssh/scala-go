package example

import gopher._
import gopher.channels.CommonTestObjects.gopherApi._
import gopher.channels._
import org.scalatest._

import scala.concurrent.{Channel => _, _}
import scala.language.postfixOps

/**
 * this is direct translation from appropriative go example.
 **/
object Sieve
{
  import scala.concurrent.ExecutionContext.Implicits.global


  def generate(n:Int, quit:Promise[Boolean]):Channel[Int] =
  {
    val channel = makeChannel[Int]()
    channel.awriteAll(2 to n) foreach (_ => quit success true)
    channel
  }

  // direct translation from go

  def filter0(in:Channel[Int]):Input[Int] =
  {
    val filtered = makeChannel[Int]()
    var proxy: Input[Int] = in;
    go {
      // since proxy is var, we can't select from one in forever loop.
      while(true) {
          val prime = proxy.read
          proxy = proxy.filter(_ % prime != 0)
          filtered.write(prime)
      } 
    }
    filtered
  }

  // use effected input
  /*
  def filter(in:Channel[Int]):Input[Int] =
  {
    val filtered = makeChannel[Int]()
    val sieve = makeEffectedInput(in)
    sieve.aforeach { prime =>
       sieve apply (_.filter(_ % prime != 0))
       filtered <~ prime
    }
    filtered
  }
  */

  def filter1(in:Channel[Int]):Input[Int] =
  {
   val q = makeChannel[Int]()
   val filtered = makeChannel[Int]()
   select.afold(in){ (ch, s) => 
     s match {
       case prime: ch.read => 
                         filtered.write(prime)
                         ch.filter(_ % prime != 0)
     }
   }
   filtered
  }

  def primes(n:Int, quit: Promise[Boolean]):Input[Int] =
    filter1(generate(n,quit))

}

class SieveSuite extends AsyncFunSuite
{

 test("last prime before 1000") {

   val quit = Promise[Boolean]()
   val quitInput = futureInput(quit.future)

   val pin = Sieve.primes(1000,quit)

   var lastPrime=0;
   val future = select.forever {
       case p: pin.read => 
                   if (false) {
                     System.err.print(p)
                     System.err.print(" ")
                   }
                   lastPrime=p
       case q: quitInput.read =>
                   //System.err.println()
                   CurrentFlowTermination.exit(())
   }
   future map (_ => assert( lastPrime == 997))
 }

}

