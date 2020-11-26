package gopher.channels

import akka.actor._
import scala.language._
import scala.concurrent._
import scala.collection.immutable._
import gopher._


/**
 * ChannelActor - actor, which leave
 */
class BufferedChannelActor[A](id:Long, capacity:Int, api: GopherAPI) extends BaseBufferedChannelActor[A](id,api)
{


  protected[this] def onContWrite(cwa: gopher.channels.ContWrite[A, _]): Unit = 
  {
            if (closed) {
               cwa.flowTermination.throwIfNotCompleted(new ChannelClosedException())
            } else {
              if (nElements==capacity) {
               writers = writers :+ cwa
              } else {
               val prevNElements = nElements
               if (processWriter(cwa) && prevNElements==0) {
                 processReaders()
               }
              }
            }
  }

  protected[this] def onContRead(cra: gopher.channels.ContRead[A, _]): Unit =
  {
            if (nElements==0) {
               if (closed) {
                 processReaderClosed(cra)
               } else {
                 readers = readers :+ cra
               }
            } else {
               val prevNElements = nElements
               if (processReader(cra)) {
                 if (closed) {
                    stopIfEmpty
                 } else if (prevNElements==capacity) {
                    checkWriters
                 }
               }
            }
   }


  protected[this] def processReader[B](reader:ContRead[A,B]): Boolean =
   reader.function(reader) match {
       case Some(f1) => 
              val readedElement = elementAt(readIndex)
              nElements-=1
              readIndex+=1
              readIndex%=capacity
              Future{
                val cont = f1(ContRead.In value readedElement )
                api.continue(cont, reader.flowTermination)
              }(api.gopherExecutionContext)
              true
       case None =>
              false
   }


  def checkWriters: Boolean =
  {
    var retval = false
    while(!writers.isEmpty && nElements < capacity) {
      val current = writers.head
      writers = writers.tail
      val processed = processWriter(current)
      retval ||= processed
    }
    retval
  }

  private[this] def processWriter[B](writer:ContWrite[A,B]): Boolean =
   writer.function(writer) match {
       case Some((a,cont)) =>
                nElements+=1
                setElementAt(writeIndex,a)
                writeIndex+=1
                writeIndex%=capacity
                api.continue(cont, writer.flowTermination)
                true
       case None => 
                false
   }


  @inline
  private[this] def elementAt(i:Int): A =
    buffer(i).asInstanceOf[A]

  @inline
  private[this] def setElementAt(i:Int, a:A): Unit =
    buffer(i) = a.asInstanceOf[AnyRef]


  // boxed representation of type.
  val buffer= new Array[AnyRef](capacity+1)
  var readIndex=0
  var writeIndex=0

}
