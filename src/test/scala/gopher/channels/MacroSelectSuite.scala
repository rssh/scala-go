package gopher.channels

import gopher._
import org.scalatest._

import scala.concurrent.{Channel=>_,_}
import scala.concurrent.duration._
import scala.language._

class MacroSelectASyncSuite extends AsyncFunSuite
{

    override implicit def executionContext: ExecutionContext = ExecutionContext.Implicits.global

    test("select emulation with macroses")  {

        val channel = gopherApi.makeChannel[Int](100)

        go {
            var i = 1
            while(i <= 1000) {
                channel <~ i
                i+=1
            }
            //TODO: implement for in goas preprocessor to async
            //for( i <- 1 to 1000)
            //  channel <~ i
        }

        var sum = 0
        val consumer = go {
            for(s <- gopherApi.select.forever) {
                s match {
                    case i: channel.read =>
                        //System.err.println("received:"+i)
                        sum = sum + i
                        if (i==1000)
                            implicitly[FlowTermination[Unit]].doExit(())
                }
            }
            sum
        }

        for{
            _ <- consumer
            xsum = (1 to 1000).sum
        } yield assert(xsum == sum)

    }

    test("select with run-once")  {
        import gopherApi._
        val channel1 = makeChannel[Int](100)
        val channel2 = makeChannel[Int](100)

        val g = go {
            var nWrites=0
            for(s <- select.once)
                s match {
                    case x: channel1.write if (x==1) => {
                        {}; nWrites = nWrites + 1
                    }
                    case x: channel2.write if (x==1) => {
                        {}; nWrites = nWrites + 1
                    }
                }

            var nReads=0
            for(s <- select.once)
                s match {
                    case  x: channel1.read => { {}; nReads = nReads + 1 }
                    case  x: channel2.read => { {}; nReads = nReads + 1 }
                }

            (nWrites, nReads)
        }

        g map { case(nWrites,nReads) => assert(nWrites==1 && nReads==1)}

    }

    test("select from futureInput") {
        import gopherApi._
        val channel = makeChannel[Int](100)
        val future = Future successful 10
        val fu = futureInput(future)
        var res = 0
        val r = select.forever {
            case x: channel.read =>
                Console.println(s"readed from channel: ${x}")
            case x: fu.read =>
                //Console.println(s"readed from future: ${x}")
                res = x
                implicitly[FlowTermination[Unit]].doExit(())
            //  syntax for using channels/futures in cases without
            //  setting one in stable identifers.
            case x: Int if (x == future.read) => {};
                res = x
        }
        r map (_ => assert(res == 10))
    }

    test("select syntax with read/writes in guard")  {
        import gopherApi._
        val channel1 = makeChannel[Int](100)
        val channel2 = makeChannel[Int](100)
        var res = 0
        val r = select.forever{
            case x: Int if (x==channel1.write(3)) =>
                Console.println(s"write to channel1: ${x} ")
            case x: Int if (x==channel2.read) =>
                Console.println(s"readed from channel2: ${x}")
            case x: Int if (x==(Future successful 10).read) =>
                res=x
                implicitly[FlowTermination[Unit]].doExit(())
        }
        r map (_ => assert(res==10))
    }


    test("select syntax with @unchecked annotation")  {
        import gopherApi._
        val channel1 = makeChannel[List[Int]](100)
        val channel2 = makeChannel[List[Int]](100)
        var res = 0
        val r = select.once{
            case x: channel1.read @ unchecked =>
            {};
                res=1
            case x: List[Int] @ unchecked if (x==channel2.read) =>
            {};
                res=2
        }
        channel1.awrite(List(1,2,3))
        r map (_ => assert(res==1))
    }

    test("tuple in caseDef as one symbol")  {
        import gopherApi._
        val ch = makeChannel[(Int,Int)](100)
        var res = 0
        val r = select.once{
            case xpair: ch.read @unchecked  =>
                // fixed error in compiler: Can't find proxy
                val (a,b)=xpair
                res=1
        }
        ch.awrite((1,1))
        r map ( _ => assert(res==1))
    }

    test("multiple readers for one write")  {
        import gopherApi._
        val ch = makeChannel[Int](10)
        var x1 = 0
        var x2 = 0
        var x3 = 0
        var x4 = 0
        var x5 = 0
        val f1 = select.once{
            case x:ch.read =>
            {};
                x1=1
        }
        val f2 = select.once{
            case x:ch.read =>
            {};
                x2=1
        }
        val f3 = select.once{
            case x:ch.read =>
            {};
                x3=1
        }
        val f4 = select.once{
            case x:ch.read =>
            {};
                x4=1
        }
        val f5 = select.once{
            case x:ch.read =>
            {};
                x5=1
        }
        for {_ <- ch.awrite(1)
             _ <- Future.firstCompletedOf(List(f1, f2, f3, f4, f5))
             _ = ch.close()
             _ <- Future.sequence(List(f1, f2, f3, f4, f5)).recover{
                 case _ :ChannelClosedException => ()
             }
        } yield assert(x1+x2+x3+x4+x5==1)
    }


    test("fold over selector")  {
        import gopherApi._
        val s0 = Seq[Future[Assertion]]()
        val assertions = (1 to 100).foldLeft(s0) { (s, e) =>
            val ch = makeChannel[Int](10)
            val back = makeChannel[Int]()
            val quit = Promise[Boolean]()
            val r = select.afold(0){ (x,s) =>
                s match {
                    case a:ch.read => back <~ a
                        x+a
                    case q:Boolean if (q==quit.future.read) => CurrentFlowTermination.exit(x)
                }
            }
            ch.awriteAll(1 to 10)
            back.aforeach{ x =>
                if (x==10) {
                    quit success true
                }
            }
            val fs = r map (sum => assert(sum==(1 to 10).sum))
            fs +: s
        }
        Future.sequence(assertions).map(_.head)
    }

    test("fold over selector with idle-1")  {
        import gopherApi._
        val ch1 = makeChannel[Int](10)
        val ch2 = makeChannel[Int](10)
        ch1.awrite(1)
        for {
            _ <- Future.successful(())
            sf = select.afold((0, 0, 0)) { case ((n1, n2, nIdle), s) =>
                s match {
                    case x: ch1.read =>
                        val nn1 = n1 + 1
                        if (nn1 > 100) {
                            CurrentFlowTermination.exit((nn1, n2, nIdle))
                        } else {
                            ch2.write(x)
                            (nn1, n2, nIdle)
                        }
                    case x: ch2.read =>
                        ch1.write(x)
                        (n1, n2 + 1, nIdle)
                    case _ =>
                        (n1, n2, nIdle + 1)

                }
            }
            (n1, n2, ni) <- sf
            _ = assert(n1 + n2 + ni > 100)
            sf2 = select.afold((0, 0)) { case ((n1, nIdle), s) =>
                s match {
                    case x: ch1.read =>
                        (n1 + 1, nIdle)
                    case _ =>
                        val nni = nIdle + 1
                        if (nni > 3) {
                            CurrentFlowTermination.exit((n1, nni))
                        } else {
                            (n1, nni)
                        }
                }
            }
            (n21, n2i) <- sf2
        } yield
           assert(n2i>3)
    }


    lazy val gopherApi = CommonTestObjects.gopherApi
}


class MacroSelectSyncSuite extends FunSuite
{

   import scala.concurrent.ExecutionContext.Implicits.global



   test("amap over selector")  {
     import gopherApi._
     val ch1 = makeChannel[Int](10)
     val ch2 = makeChannel[Int](10)
     val quit = Promise[Boolean]()
     val out = select.amap {
         case x:ch1.read => x*2
         case x:ch2.read => 
                         //System.err.println(s"received:${x}")
                         x*3
         case q:Boolean if (q==quit.future.read) => 
                         //System.err.println("received quit")
                         select.exit(1)
     }
     ch1.awriteAll(1 to 10)
     ch2.awriteAll(100 to 110)
     val f = out.afold(0){ 
               case (s,x) => //System.err.println(s"in afold ${x}")
                             s+x }
     Thread.sleep(1000)
     quit success true
     val x = Await.result(f, 10 seconds)
     assert(x > 3000)
   }

   test("generic channel make")  {
     val ch1 = gopherApi.make[Channel[Int]]()
     val ch2 = gopherApi.make[Channel[Int]](1)
     // yet not supported by compiler.
     //val ch3 = gopherApi.make[Channel[Int]](capacity=3)
     val f1 = ch1.awrite(1)
     val f2 = ch2.awrite(2)
     val x = Await.result(ch1.aread, 10 seconds)
     assert(x==1)
   }

   test("input afold") {
     import gopherApi._
     val ch1 = makeChannel[Int]()
     ch1.awriteAll(1 to 10) map { _ => ch1.close() }
     val f = ch1.afold(0){ case (s,x) => s+x }
     val x = Await.result(f, 10 seconds)
     assert(x==55)
   }

   test("map over selector")  {
     import gopherApi._
     val ch1 = gopherApi.make[Channel[Int]]()
     val ch2 = gopherApi.make[Channel[Int]](1)
     val f1 = ch1.awrite(1)
     val f2 = ch2.awrite(2)
     val chs = for(s <- select) yield {
                s match {
                  case x:ch1.read => x*3
                  case x:ch2.read => x*5
                }
              }
     val fs1 = chs.aread
     val fs2 = chs.aread
     val s1 = Await.result(fs1, 1 second)
     val s2 = Await.result(fs2, 1 second)
     assert(s1==3 || s1==10)
   }

   test("one-time channel make")  {
     val ch = gopherApi.make[OneTimeChannel[Int]]()
     val f1 = ch.awrite(1)
     val f2 = ch.awrite(2)
     val x = Await.result(ch.aread, 10 seconds)
     val x2 = Await.result(f2.failed, 10 seconds)
     assert(x==1)
     assert(x2.isInstanceOf[ChannelClosedException])
   }

   test("check for done signal from one-time channel")  {
     import gopherApi._
     val ch = gopherApi.make[OneTimeChannel[Int]]()
     val sf = select.afold((0)){ (x,s) =>
        s match {
          case v: ch.read => x + v
          case _: ch.done => select.exit(x)
        }
     }
     val f1 = ch.awrite(1)
     val r = Await.result(sf,1 second)
     assert(r==1)
   }

   test("check for done signal from unbuffered channel")  {
     import gopherApi._
     val ch = gopherApi.make[Channel[Int]]()
     val sf = select.afold((0)){ (x,s) =>
        s match {
          case v: ch.closeless.read => x + v
          case _: ch.done => select.exit(x)
        }
     }
     val f1 = ch.awriteAll(1 to 5) map (_ =>ch.close)
     val r = Await.result(sf,1 second)
     assert(r==15)
   }

   test("check for done signal from buffered channel")  {
     import gopherApi._
     val ch = gopherApi.make[Channel[Int]](10)
     val sf = select.afold((0)){ (x,s) =>
        s match {
          case v: ch.closeless.read => x + v
          case _: ch.done => select.exit(x)
        }
     }
     val f1 = ch.awriteAll(1 to 5) map{ _ =>
        // let give all buffers to processe
        Thread.sleep(200)
        ch.close
     }
     val r = Await.result(sf,1 second)
     assert(r==15)
   }

   test("check for done signal from channel with dummy var")  {
     import gopherApi._
     val ch = gopherApi.make[Channel[Int]]()
     val sf = select.afold((0)){ (x,s) =>
        s match {
          case v: ch.closeless.read => x + v
          case v: ch.done => select.exit(x)
        }
     }
     val f1 = ch.awriteAll(1 to 5) map (_ =>ch.close)
     val r = Await.result(sf,1 second)
     assert(r==15)
   }


   test("check for done signal from select map")  {
     import gopherApi._
     val ch1 = gopherApi.make[Channel[Int]]()
     val ch2 = gopherApi.make[Channel[Int]]()
     val q = gopherApi.make[Channel[Boolean]]()
     val chs = for(s <- select) yield {
                s match {
                 case x: ch1.read => x*3
                 case x: ch2.read => x*2
                 case _: q.read =>
                   select.exit(1)
                }
     }
     val chs2 = select.afold(0){ (n,s) =>
        s match {
          case x:chs.closeless.read =>
                    n + x
          case _:chs.done =>
                    select.exit(n)
        }
     }
     // note, that if we want call of quit after last write,
     //   ch1 and ch2 must be unbuffered.
     val sendf = for{ _ <- ch1.awriteAll(1 to 10) 
                      _ <- ch2.awriteAll(1 to 10) 
                      _ <- q.awrite(true) } yield 1
     val r = Await.result(chs2,1 second)
     assert( r ==  (1 to 10).map(_ * 5).sum + 1)
   }


   lazy val gopherApi = CommonTestObjects.gopherApi
   
}
