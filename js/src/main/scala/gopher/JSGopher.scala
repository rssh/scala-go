package gopher

import cps._

class JSGopher[F[_]:CpsSchedulingMonad](cfg: JSGopherConfig) extends Gopher[F]:


   def makeChannel[A](bufSize:Int) =
       if (bufSize == 1)
          new impl.UnbufferedChannel[F,A](this)
       else 
          ???

object JSGopher extends GopherAPI:

   def apply[F[_]:CpsSchedulingMonad](cfg: GopherConfig):Gopher[F] =
      val jsConfig = cfg match
                        case DefaultGopherConfig => JSGopherConfig("default")
                        case jcfg:JSGopherConfig => jcfg
      new JSGopher[F](jsConfig)

