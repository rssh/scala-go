package gopher.channels

import gopher._
import gopher.channels._
import gopher.tags._

import org.scalatest._

import scala.language._
import scala.concurrent._
import scala.concurrent.duration._

class SelectErrorSuite extends FunSuite
{

   import scala.concurrent.ExecutionContext.Implicits.global

  

   test("select error handling for foreach")  {
     import gopherApi._
     val channel = makeChannel[Int](100)

     var svEx: Throwable = null

     val g = go {
       var nWrites = 0
       var nErrors = 0
       for (s <- select.forever) {
         s match {
           case x: channel.write if (x == nWrites) =>
             nWrites = nWrites + 1
             if (nWrites == 50) {
               throw new RuntimeException("Be-be-be")
             }
             if (nWrites == 100) {
               select.exit(())
             }
           case ex: select.error =>
             { };  svEx = ex  // macro-system errors: assignments accepts as default argument
         }
       }
     }

     val tf = channel.atake(60)

     Await.ready(tf, 10 seconds)

     assert(svEx.getMessage == "Be-be-be")

   }


  test("select error handling for once")  {
    import gopherApi._
    val channel = makeChannel[Int](100)

    var svEx: Throwable = null
    val x = 1

    val g = go {
      for (s <- select.once) {
        s match {
          case x: channel.write  =>
              throw new RuntimeException("Be-be-be")
          case ex: select.error =>
          { };  svEx = ex  // macro-system errors: assignments accepts as default argument
                3
        }
      }
    }

    val r = Await.result(g, 10 seconds)

    assert(svEx.getMessage == "Be-be-be")

    assert(r === 3)


  }

  test("select error handling for input")  {
    import gopherApi._
    val channel = makeChannel[Int](100)

    var svEx: Throwable = null

    val out = select.map {
      case x: channel.read =>
             if (x==55) {
               throw new RuntimeException("Be-be-be")
             }
             x
      case ex: select.error =>
             {}; svEx = ex
             56
    }

    channel.awriteAll(1 to 100)

    val g = out.atake(80)

    val r = Await.result(g, 10 seconds)

    assert(svEx.getMessage == "Be-be-be")

    assert(r.filter(_ == 56).size == 2)

  }

  test("select error handling for fold") {
    import gopherApi._
    val ch1 = makeChannel[Int]()
    val ch2 = makeChannel[Int]()
    val ch3 = makeChannel[Int]()

    var svEx: Throwable = null

    val g = select.afold((ch1,ch2,0,List[Int]())) { case ((x,y,z,l),s) =>
        s match {
          case z1: ch3.read =>
               if (z1==10) {
                 throw new RuntimeException("Be-be-be!")
               }
               (x,y,z1,z1::l)
          case a:x.read =>
               if (z > 20) {
                 throw new RuntimeException("Be-be-be-1")
               }
               (y,x,z+a,z::l)
          case b:y.read =>
               (y,x,z+100*b,z::l)
          case ex: select.error =>
             {}; svEx = ex
               if (z > 20) {
                 select.exit((x,y,z,z::l))
               } else
                (x,y,z,l)

        }
    }

    ch3.awriteAll(1 to 11).flatMap{
      x => ch2.awriteAll(1 to 5)
    }

    val r = Await.result(g, 5 seconds)

    assert(svEx.getMessage=="Be-be-be-1")


   // System.err.println(s"received: ${r._4.reverse}")

  }





    lazy val gopherApi = CommonTestObjects.gopherApi
   
}
