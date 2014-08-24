package gopher.channels


import org.scalatest._
import scala.concurrent._
import scala.concurrent.duration._
import gopher.channels.Naive._
import gopher.channels.naive.SelectorContext

class SelectSuite extends FunSuite 
{

 
   test("basic select emulation")  {
     
     System.err.println("started basic select emulation")
     
     val channel = make[Int](100)
     
     val producer = channel.put(1 to 1000).shutdownFuture
       
     @volatile var sum = 0;
     val consumer = {
       val sc = new SelectorContext()
       sc.addInputAction(channel,  
            (i: Int) => { sum = sum + i; 
                          System.err.println("received:"+i+", now sum:"+sum);
                          if (i == 1000) {
                            System.err.println("shutdowned");
                            sc.shutdown()
                          }
                          Promise successful true future 
                        }
       )
       sc.start.shutdownFuture
     }
   
     
     
     Await.ready(consumer, 1000.second)

    // System.err.println("sum="+sum);
     
     val xsum = (1 to 1000).sum
    // System.err.println("xsum="+xsum);
     assert(xsum == sum)
     
     
   }
    
   
/*
  
   test("select with traditional producer") {
     
     val channel = make[Int](1)
     
     val producer = Future {
       for( i <- 1 to 1000) {
        // System.err.println("sending:"+i)
         channel.<~(i)
       }       
     }
          
     @volatile var sum = 0;
     @volatile var lastI = 0
     val consumer = Future {
       val sc = new SelectorContext()
       sc.addInputAction(channel, 
            (i: Int) => { sum = sum + i; 
                         // System.err.println("received, i="+i);
                          if (i == 1000) {
                            sc.shutdown()
                          }
                          Promise successful true future 
                        }
       )
       Await.ready(sc.go, 5.second)
     }
   
    
     Await.ready(consumer, 1000.second)
     
   }
  
   
   test("select with queue type") {

     val channel = make[Int](100)

     val producer = Future {
       for( i <- 1 to 1000) {
         channel <~ i 
       }       
     }
          
     var sum = 0;
     val consumer = Future {
       val sc = new SelectorContext()
       sc.addInputAction(channel, 
            (i: channel.OutputElement) => { sum = sum + i; 
                          if (i == 1000) {
                            sc.shutdown()
                          }
                          Promise successful true future 
                        }
       )
       Await.ready(sc.go, 1000.second)
     }
   
    
     Await.ready(consumer, 1000.second)
     
   }
    
  */ 
  
   
}