package gopher.channels

import gopher._
import org.scalatest._

class AsyncSelectSuite extends AsyncFunSuite {

  val MAX_N=100

  test("async base: channel write, select read")  {

    val channel = gopherApi.makeChannel[Int](10)

    channel.awriteAll(1 to MAX_N)

    var sum = 0;

    val consumer = gopherApi.select.loop.onRead(channel){
      (a:Int, cont:ContRead[Int,Unit]) => sum = sum + a
        if (a < MAX_N) {
          cont
        } else {
          Done((),cont.flowTermination)
        }
    }.go

    //val consumer = go {
    //  for(s <- select) {
    //     s match {
    //        case `channel` ~> (i:Int) =>
    //                //System.err.println("received:"+i)
    //                sum = sum + i
    //                if (i==1000)  s.shutdown()
    //     }
    //  }
    //  sum
    //}

    consumer map { x =>
      val xsum = (1 to MAX_N).sum
      assert(xsum == sum)
    }

  }

  test("async base: select write, select read")  {

    val channel = gopherApi.makeChannel[Int](10)

    var sum=0
    var curA=0
    val process = gopherApi.select.loop.
      onRead(channel){
        (a:Int, cont:ContRead[Int,Unit]) => sum = sum + a
          //System.err.println("received:"+a)
          if (a < MAX_N) {
            cont
          } else {
            Done((),cont.flowTermination)
          }
      }.onWrite(channel){
      cont:ContWrite[Int,Unit] =>
        curA = curA+1
        if (curA < MAX_N) {
          (curA, cont)
        } else {
          (curA,Done((),cont.flowTermination))
        }
    }.go

    process map { _ =>
      assert(curA == MAX_N)
    }

  }

  test("async base: select read, default action")  {

    val channel = gopherApi.makeChannel[Int](10)

    val consumer = channel.atake(100)

    var i = 1
    var d = 1
    val process = gopherApi.select.loop[Int].onWrite(channel) {
      cont:ContWrite[Int,Int] => i=i+1
        (i,cont)
    }.onIdle{
      cont:Skip[Int] =>
        if (i < 100) {
          d=d+1
          cont
        } else {
          Done(d,cont.flowTermination)
        }
    }.go

    for{rp <- process
        rc <- consumer } yield assert(i > 100)

  }

  test("async base: catch exception in read")  {
    val ERROR_N = 10
    var lastReaded = 0
    val channel = gopherApi.makeChannel[Int](10)
    val process = gopherApi.select.loop.
      onRead(channel){
        (a:Int, cont:ContRead[Int,Unit]) => lastReaded=a
          if (a == ERROR_N) {
            throw new IllegalStateException("qqq")
          }
          cont
      }.go

    channel.awriteAll(1 to MAX_N)

    recoverToSucceededIf[IllegalStateException]{
      process
    }

  }

  test("async base: catch exception in idle")  {
    val process = gopherApi.select.loop.onIdle(
      (cont: Skip[Int]) =>
        if (true) {
          throw new IllegalStateException("qqq")
        } else cont
    ).go

    recoverToSucceededIf[IllegalStateException]{
      process
    }

  }


  def actorSystem = CommonTestObjects.actorSystem
  def gopherApi = CommonTestObjects.gopherApi


}

