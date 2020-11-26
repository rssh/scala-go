package example

import gopher.channels._
import org.scalatest._

import scala.async.Async._
import scala.concurrent._
import scala.language._

class FibonaccyAsyncUnsugaredSuite extends AsyncFunSuite {

  
 object Fibonaccy {
 
  //  illustrate usage of internal low-level API
  //  
  def fibonacci(c: Output[Long], quit: Input[Int]): Future[Unit] = {

    @volatile var (x,y) = (0L,1L)

    val selector = new Selector[Unit](gopherApi)

    selector.addWriter(c,
        ((cont:ContWrite[Long,Unit]) => Some{
                   (x, async{
                        val z=x
                        x=y
                        y=z+y
                        cont}
                   )
                 }
        )
    )
    selector.addReader(quit,
       ((cont:ContRead[Int,Unit]) => Some{ (in:ContRead.In[Int]) =>
                                             Future successful Done((),cont.flowTermination) 
                                         }
       )
    )
    selector.run
  }
  
  def run(max:Int, acceptor: Long => Unit ): Future[Unit] =
  {
    val c = gopherApi.makeChannel[Long]()
    val quit = gopherApi.makeChannel[Int]()
    
    val selector = new Selector[Long](gopherApi)
    selector.addReader(c zip (1 to max),
              (cont:ContRead[(Long,Int),Long]) => Some(ContRead.liftIn(cont){ in =>
                        val (n,i) = in
                        //Console.println(s"received:${i}:${n} from channel ${cont.channel}")
                        Future successful {
                          if (i >= max) 
                             Done(n,cont.flowTermination)
                          else 
                           cont
                        }
                      })
    )
    val consumer = selector.run

    val producer = fibonacci(c,quit)

    consumer.map(x => acceptor(x))
  }
  

 }
 
 test("fibonaccy must be processed up to 50") {
    var last:Long = 0
    Fibonaccy.run(50, last = _ ) map (x => assert(last != 0))
  }
  
  val gopherApi = CommonTestObjects.gopherApi

}

  

