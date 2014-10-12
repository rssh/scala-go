package gopher.channels

import scala.concurrent._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
import scala.reflect.api._
import scala.util._
import java.util.concurrent.ConcurrentLinkedQueue
import gopher._


/**
 * Entity, from which we can read objects of type A.
 *
 *
 */
trait Input[A]
{

  thisInput =>

  type <~ = A
  type read = A


  /**
   * apply f, when input will be ready and send result to API processor
   */
  def  cbread[B](f:ContRead[A,B]=>Option[ContRead.In[A]=>Future[Continuated[B]]], ft: FlowTermination[B]): Unit


  /**
   * async version of read. Immediatly return future, which will contains result of read or failur with StreamClosedException
   * in case of stream is closed.
   */
  def  aread:Future[A] = {
    val ft = PromiseFlowTermination[A]() 
    cbread[A](cont => Some(in => ContRead.applyIn(in,cont) { a =>
                                    a => Future.successful(Done(a,ft))
                                 }), ft)
    ft.future
  }

  /**
   * instance of gopher API
   */
  def api: GopherAPI

  /**
   * read object from channel. Must be situated inside async/go/action block.
   */
  def  read:A = macro InputMacro.read[A]

  /**
   * synonym for read.
   */
  def  ? : A = macro InputMacro.read[A]

  /**
   * return feature which contains sequence from first `n` elements.
   */
  def atake(n:Int):Future[IndexedSeq[A]] =
  {
    if (n==0) {
      Future successful IndexedSeq()
    } else {
       val ft = PromiseFlowTermination[IndexedSeq[A]]
       @volatile var i = 0;
       @volatile var r: IndexedSeq[A] = IndexedSeq()
       def takeFun(cont:ContRead[A,IndexedSeq[A]]):Option[ContRead.In[A]=>Future[Continuated[IndexedSeq[A]]]] =
       Some{ in =>
             ContRead.applyIn(in, cont) {
               i += 1
               r = r :+ gen()
               if (i<n) {
                  Future successful ContRead(takeFun,this,ft)
               } else {
                  Future successful Done(r,ft)
               }
             }
       }
       api.continuatedProcessorRef ! ContRead(takeFun, this, ft)
       ft.future
    }
  }

  /**
   * run <code> f </code> each time when new object is arrived. Ended when input closes.
   *
   * must be inside go/async/action block.
   */
  def foreach(f: A=>Unit): Unit = macro InputMacro.foreachImpl[A]

  def aforeach(f: A=>Unit): Future[Unit] = macro InputMacro.aforeachImpl[A]

  def filter(p: A=>Boolean): Input[A] =
       new Input[A] {

          def  cbread[B](f:ContRead[A,B]=>Option[ContRead.In[A]=>Future[Continuated[B]]], ft: FlowTermination[B]): Unit =
           thisInput.cbread[B]( 
                               (cont) => f(cont) map {
                                            f1 => (in => { val a = gen()
                                                            if (p(a)) f1(()=>a) 
                                                              else Future successful ContRead(f,this,ft)
                                                          } )
                                        }, ft)  

           def api = thisInput.api

       }

  def withFilter(p: A=>Boolean): Input[A] = filter(p)

  def map[B](g: A=>B): Input[B] =
     new Input[B] {

        def  cbread[C](f: ContRead[B,C] => Option[(()=>B)=>Future[Continuated[C]]], ft: FlowTermination[C] ): Unit =
        {
         def mf(cont:ContRead[A,C]):Option[(()=>A)=>Future[Continuated[C]]] =
            f(ContRead(f,this,ft)) map (f1 => (gen:(()=>A))=>f1(()=>g(gen())))
         thisInput.cbread(mf,ft)
        }

        def api = thisInput.api

     }

  def zip[B](x: Iterable[B]): Input[(A,B)] = zip(Input.asInput(x,api))

  def zip[B](x: Input[B]): Input[(A,B)] = new ZippedInput(api,this,x)

  def |(other:Input[A]):Input[A] = new OrInput(this,other)

  def async = new {
  
     def foreach(f: A=> Unit):Future[Unit] = macro InputMacro.aforeachImpl[A]

     @inline
     def foreachSync(f: A=>Unit): Future[Unit] =  thisInput.foreachSync(f)
           
     @inline
     def foreachAsync(f: A=>Future[Unit])(implicit ec:ExecutionContext): Future[Unit] =
                                                  thisInput.foreachAsync(f)(ec)

  }

  def foreachSync(f: A=>Unit): Future[Unit] =
  {
    val ft = PromiseFlowTermination[Unit]
    lazy val cont = ContRead(applyF,this,ft)
    def applyF(_cont:ContRead[A,Unit]):Option[(()=>A)=>Future[Continuated[Unit]]] =
          Some{
            (gen:()=>A) => var ar = false
                          try {
                            val a = gen()
                            ar = true
                            f(a)
                          } catch {
                            case ex: ChannelClosedException if (!ar) => ft.doExit(())
                          }
                          Future successful cont
          }
    cbread(applyF,ft)
    ft.future
  }

  def foreachAsync(f: A=>Future[Unit])(implicit ec:ExecutionContext): Future[Unit] =
  {
    val ft = PromiseFlowTermination[Unit]
    def applyF(_cont:ContRead[A,Unit]):Option[(()=>A)=>Future[Continuated[Unit]]] =
          Some{ (gen:()=>A) => {
                    val next = Try(gen()) match {
                              case Success(a) => f(a) 
                                                 ContRead(applyF,this,ft)
                              case Failure(ex) => if (ex.isInstanceOf[ChannelClosedException]) {
                                                    Done((),ft)
                                                  } else {
                                                    ft.doThrow(ex)
                                                    Never
                                                  }
                    } 
                    Future successful next
                }
          }
    cbread(applyF,ft)
    ft.future
  }

}

object Input
{
   def asInput[A](iterable:Iterable[A], api: GopherAPI): Input[A] = new IterableInput(iterable.iterator, api)

   class IterableInput[A](it: Iterator[A], override val api: GopherAPI) extends Input[A]
   {

     def  cbread[B](f:ContRead[A,B]=>Option[(()=>A)=>Future[Continuated[B]]], ft: FlowTermination[B]): Unit =
      f(ContRead(f,this,ft)) map (f1 => { val next = this.synchronized {
                                                       if (it.hasNext) {
                                                         val x = it.next
                                                         f1(()=>x) 
                                                       } else throw new ChannelClosedException()
                                                    }
                                          api.continue(next,ft)
                                        }
                              )
   }


}



object InputMacro
{

  def read[A](c:Context):c.Expr[A] =
  {
   import c.universe._
   c.Expr[A](q"{scala.async.Async.await(${c.prefix}.aread)}")
  }

  def foreachImpl[A](c:Context)(f:c.Expr[A=>Unit]): c.Expr[Unit] =
  {
   import c.universe._
   c.Expr[Unit](q"scala.async.Async.await(${aforeachImpl(c)(f)})")
  }


  def aforeachImpl[A](c:Context)(f:c.Expr[A=>Unit]): c.Expr[Future[Unit]] =
  {
   import c.universe._
   val findAwait = new Traverser {
      var found = false
      override def traverse(tree:Tree):Unit =
      {
       if (!found) {
         tree match {
            case Apply(TypeApply(Select(obj,TermName("await")),objType), args) =>
                   if (obj.tpe =:= typeOf[scala.async.Async.type]) {
                       found=true
                   } else super.traverse(tree)
            case _ => super.traverse(tree)
         }
       }
      }
   }
   f.tree match {
     case Function(valdefs,body) =>
            findAwait.traverse(body)
            if (findAwait.found) {
               val nbody = q"scala.async.Async.async(${body})"
               c.Expr[Future[Unit]](q"${c.prefix}.foreachAsync(${Function(valdefs,nbody)})")
            } else {
               c.Expr[Future[Unit]](q"${c.prefix}.foreachSync(${Function(valdefs,body)})")
            }
     case _ => c.abort(c.enclosingPosition,"function expected")
   }
  }


}
