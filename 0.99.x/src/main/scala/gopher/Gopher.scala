package gopher

import akka.actor._
import com.typesafe.config._
import scala.concurrent._
import gopher.channels._
import java.util.concurrent.Executors

/**
 * Akka extension which provide gopherApi interface
 *@see GopherAPI
 **/
class GopherImpl(system: ExtendedActorSystem) 
            extends GopherAPI(system, 
                    GopherAPIExtensionHelper.retrieveConfiguredExecutor(system))
                with Extension
{

}

/**
 * Factory object for Akka extension
 *
 *{{{
 *  val actorSystem = ActorSystem("myapp")
 *  val gopherApi = Gopher(actorSystem)
 *}}}
 **/
object Gopher extends ExtensionId[GopherImpl]
                             with ExtensionIdProvider
{
   override def lookup = Gopher

   override def createExtension(system: ExtendedActorSystem) 
                             = new GopherImpl(system)

   override def get(system: ActorSystem): GopherImpl = super.get(system)

}

object GopherAPIExtensionHelper
{

  def retrieveConfiguredExecutor(system: ExtendedActorSystem): ExecutionContext = {
    val config = system.settings.config.atKey("gopher")
    if (config.hasPath("threadPool")) {
      var sType = "fixed"
      try {
        sType = config.getString("threadPool.size")
      } catch {
        case ex: ConfigException.Missing => 
          system.log.warning("gopher initialization, threadPool.type is missign, use default" +sType)
      }
      sType match {
        case "fixed" => 
            var size = 4;
            try {
              size = config.getInt("threadPool.size")
            } catch {
              case ex: ConfigException.Missing => 
               system.log.warning("gopher initialization, threadPool.size is missing, use default: "+size)
            }
            ExecutionContext.fromExecutorService( Executors.newFixedThreadPool(size) )
        case "cached" => 
            ExecutionContext.fromExecutorService( Executors.newCachedThreadPool() )
        case "single" => 
            ExecutionContext.fromExecutorService( Executors.newSingleThreadExecutor() )
        case "global" => ExecutionContext.global
        case _ => throw new IllegalStateException("""Invalid threadPool.type in config, must be one of "fixed", "cached", "single", "global" """)
      }
    } else {
       // use defautl execution context.
       ExecutionContext.global
    }
  }
 

}
