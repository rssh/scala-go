package gopher

import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import com.typesafe.config._
import gopher.channels._
import gopher.transputers._

import scala.concurrent.duration._
import scala.concurrent.{Channel => _, _}
import scala.language.experimental.macros
import scala.language.postfixOps
import scala.reflect.macros.blackbox.Context
import scala.util._

/**
 * Api for providing access to channel and selector interfaces.
 */
class  GopherAPI(as: ActorSystem, es: ExecutionContext)
{

  /**
   * obtain select factory
   *
   * {{{
   *  goopherApi.select.once[String] {
   *    case x: a.read => s"\${x} from A"
   *    case x: b.read => s"\${x} from B"
   *    case _ => "IDLE"
   *  }
   * }}}
   */
  val select: SelectFactory =
    new SelectFactory(this)

  /**
   * Generic schema for making objects, which requiere gopherAPI for constructions.
   *
   **/
  def make[T](args: Any*): T = macro GopherAPI.makeImpl[T]

  /**
   * obtain channel
   *
   *{{{
   *  val channel = gopherApi.makeChannel[Int]()
   *  channel.awrite(1 to 100)
   *}}}
   */
  @inline
  def makeChannel[A](capacity: Int = 0): Channel[A] =
      Channel[A](capacity)(this)

  /*
  def makeEffectedInput[A](in: Input[A], threadingPolicy: ThreadingPolicy = ThreadingPolicy.Single): EffectedInput[A] =
     EffectedInput(in,threadingPolicy)

  def makeEffectedOutput[A](out: Output[A], threadingPolicy: ThreadingPolicy = ThreadingPolicy.Single) =
     EffectedOutput(out,threadingPolicy)

  def makeEffectedChannel[A](ch: Channel[A], threadingPolicy: ThreadingPolicy = ThreadingPolicy.Single) =
     EffectedChannel(ch,threadingPolicy)
  */

  /**
   * Represent Scala future as channel from which we can read one value.
   *@see gopher.channels.FutureInput
   */
  def futureInput[A](future:Future[A]): FutureInput[A] = new FutureInput(future, this)

  /**
   * Represent Scala iterable as channel, where all values can be readed in order of iteration.
   */
  def iterableInput[A](iterable:Iterable[A]): Input[A] = Input.asInput(iterable, this)


  /**
   * create and start instance of transputer with given recovery policy.
   *@see gopher.Transputer
   */
  def makeTransputer[T <: Transputer](recoveryPolicy:PartialFunction[Throwable,SupervisorStrategy.Directive]): T = macro GopherAPI.makeTransputerImpl2[T]

  def makeTransputer[T <: Transputer]: T = macro GopherAPI.makeTransputerImpl[T]

  /**
   * create transputer which contains <code>n</code> instances of <code>X</code>
   * where ports are connected to the appropriate ports of each instance in paraller.
   * {{{
   *   val persistStep = replicate[PersistTransputer](nDBConnections)
   * }}}
   */
  def replicate[T<: Transputer](n:Int): Transputer = macro Replicate.replicateImpl[T]

  /**
   * actor system which was passed during creation
   **/
  def actorSystem: ActorSystem = as

  /**
   * execution context used for managing calculation steps in channels engine.
   **/
  def gopherExecutionContext: ExecutionContext = es

  /**
   * the configuration of the gopher system. By default is contained under 'gopher' key in top-level config.
   **/
  def config: Config = as.settings.config.atKey("gopher")

  /**
    * time API
    */
  lazy val time = new Time(this,gopherExecutionContext)


  lazy val idleTimeout: FiniteDuration = {
    val m = try {
              config.getInt("idle-detection-tick")
            } catch {
              case ex: ConfigException.Missing => 100
            }
    m.milliseconds
  }


  lazy val defaultExpireCapacity: Int = {
    try {
      config.getInt("default-expire-capacity")
    } catch {
      case ex: ConfigException.Missing => 1000
    }
  }

  def currentFlow = CurrentFlowTermination


  //private[gopher] val idleDetector = new IdleDetector(this)

  private[gopher] val continuatedProcessorRef: ActorRef = {
    val props = Props(classOf[ChannelProcessor], this)
    actorSystem.actorOf(props,name="channelProcessor")
  }

  private[gopher] val channelSupervisorRef: ActorRef = {
    val props = Props(classOf[ChannelSupervisor], this)
    actorSystem.actorOf(props,name="channels")
  }

  private[gopher] val transputerSupervisorRef: ActorRef = {
    val props = Props(classOf[TransputerSupervisor], this)
    actorSystem.actorOf(props,name="transputerSupervisor")
  }

  private[gopher] def newChannelId: Long =
                        channelIdCounter.getAndIncrement

  private[gopher] def continue[A](next:Future[Continuated[A]], ft:FlowTermination[A]): Unit =
                       next.onComplete{
                          case Success(cont) =>
                                              continuatedProcessorRef ! cont
                          case Failure(ex) => ft.throwIfNotCompleted(ex)
                       }(gopherExecutionContext)
 
  private[this] val channelIdCounter = new AtomicLong(0L)

  
}

object GopherAPI
{

  def makeImpl[T : c.WeakTypeTag](c:Context)(args: c.Expr[Any]*): c.Expr[T] = {
    import c.universe._
    val wt = weakTypeOf[T]
    if (wt.companion =:= NoType) {
      c.abort(c.prefix.tree.pos,s"type ${wt.typeSymbol} have no companion")
    } 
    val sym = wt.typeSymbol.companion
    val r = q"${sym}.apply[..${wt.typeArgs}](..${args})(${c.prefix})"
    c.Expr[T](r)
  }

  def makeTransputerImpl[T <: Transputer : c.WeakTypeTag](c:Context):c.Expr[T] = {
    import c.universe._
    c.Expr[T](q"${c.prefix}.makeTransputer[${weakTypeOf[T]}](gopher.Transputer.RecoveryPolicy.AlwaysRestart)")
  }

  def makeTransputerImpl2[T <: Transputer : c.WeakTypeTag](c:Context)(recoveryPolicy:c.Expr[PartialFunction[Throwable,SupervisorStrategy.Directive]]):c.Expr[T] = {
    import c.universe._
    //----------------------------------------------
    // generate incorrect code: see  https://issues.scala-lang.org/browse/SI-8953
    //c.Expr[T](q"""{ def factory():${c.weakTypeOf[T]} = new ${c.weakTypeOf[T]} { 
    //                                            def api = ${c.prefix} 
    //                                            def recoverFactory = factory
    //                                 }
    //                val retval = factory()
    //                retval
    //              }
    //           """)
    //----------------------------------------------
    // so, let's create subclass
    val implName = c.freshName(c.symbolOf[T].name)
    c.Expr[T](q"""{ 
                    class ${implName} extends ${c.weakTypeOf[T]} { 
                        def api = ${c.prefix}
                        def recoverFactory = () => new ${implName}
                    }
                    val retval = new ${implName}
                    retval.recoverAppend(${recoveryPolicy})
                    retval
                  }
               """)
  }

}
