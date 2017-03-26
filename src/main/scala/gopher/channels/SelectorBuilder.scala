package gopher.channels

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
import scala.reflect.api._
import gopher._
import gopher.util._
import gopher.goasync._

import scala.concurrent._
import scala.concurrent.duration._
import scala.annotation.unchecked._
import scala.reflect.macros.blackbox

trait SelectorBuilder[A]
{

   type timeout = FiniteDuration

   def api: GopherAPI

   def onRead[E](ch:Input[E])(arg: ReadSelectorArgument[E,A]): this.type =
   {
     selector.addReader(ch,arg.normalizedFun)
     this
   }

   def onWrite[E](ch:Output[E])(arg: WriteSelectorArgument[E,A]): this.type =
   {
     selector.addWriter(ch,arg.normalizedFun)
     this
   }

   def onIdle(arg: SkipSelectorArgument[A]): this.type =
   {
     withTimeout(api.idleTimeout)(arg.normalizedFun)
     this
   }

   def onTimeout(t:FiniteDuration)(arg: SkipSelectorArgument[A]): this.type =
     withTimeout(t)(arg.normalizedFun)

   @inline
   def withReader[B](ch:Input[B], f: ContRead[B,A] => Option[ContRead.In[B]=>Future[Continuated[A]]]): this.type =
   {
     selector.addReader(ch,f)
     this
   }

   @inline
   def withWriter[B](ch:Output[B], f: ContWrite[B,A] => Option[(B,Future[Continuated[A]])] ): this.type =
   {
     selector.addWriter(ch,f)
     this
   } 

   @inline
   def withIdle(f: Skip[A] => Option[Future[Continuated[A]]]):this.type =
   {
     withTimeout(api.idleTimeout)(f)
   }
     
   @inline
   def withTimeout(t:FiniteDuration)(f: Skip[A] => Option[Future[Continuated[A]]]):this.type =
   {
     selector.addTimeout(t,f)
     this
   }

   final def go: Future[A] = selectorRun

   // for call from SelectorTransforment wich have another 'go'
   def selectorRun: Future[A] = selector.run

   implicit def ec: ExecutionContext = api.executionContext

   private[gopher] val selector=new Selector[A](api)

   // used for reading from future
   @inline
   def futureInput[A](f:Future[A]):FutureInput[A]=api.futureInput(f)

}


class SelectorBuilderImpl(val c: Context) extends ASTUtilImpl
{

  import c.universe._

  trait ActionGenerator {

    def genReading(builder: TermName, channel:Tree, param: ValDef, body: Tree): Tree

    def genWriting(builder: TermName, channel:Tree, expr: Tree, param: ValDef, body: Tree):Tree

    def genDone(builder: TermName, channel: Tree, param: ValDef, body: Tree): Tree

  }

  class DefaultActionGenerator extends ActionGenerator {

    override def genReading(builder: TermName, readed: Tree, param: ValDef, body: Tree): Tree = {
      val channel = readed match {
        case q"gopher.`package`.FutureWithRead[${t2}](${future})" =>
          q"${builder}.futureInput(${future})"
        case _ =>
          if (readed.tpe eq null) {
            readed  // it was generated by us
          } else if (readed.tpe <:< typeOf[gopher.channels.Input[_]]) {
            readed
          } else if (readed.tpe <:< typeOf[gopher.`package`.FutureWithRead[_]]) {
            q"${builder}.futureInput(${readed}.aread)"
          } else {
            c.abort(readed.pos, "reading in select pattern guide must be channel or future, we have:" + readed.tpe)
          }
      }
      q"${builder}.reading(${channel}){ ${param} => ${body} }"
    }

    override def genWriting(builder: TermName, channel: Tree, expr: Tree, param: ValDef, body: Tree): Tree =
    {
      q"${builder}.writing(${channel},${expr})(${param} => ${body} )"
    }


    override def genDone(builder: TermName, channel: Tree, param: ValDef, body: Tree): Tree =
      q"${builder}.onDone(${channel})(${param} => ${body} )"

  }

  val defaultActionGenerator = new DefaultActionGenerator()


  def foreach[T](f:c.Expr[Any=>T]):c.Expr[T] =
   {
     val builder = f.tree match {
       case Function(forvals,Match(choice,cases)) =>
                                // TOD: check that forvals and choice are same 
                                foreachBuildMatch(cases)
       // TODO: think, are we need syntax with common-expr ?
       //case Function(forvals,Block(commonExpr,Match(choice,cases))) =>  
       //                         foreachBuildMatch(forvals,choice,cases, commonExpr)
       case Function(a,b) =>
                     c.abort(f.tree.pos, "match expected in gopher select loop, have: ${MacroUtil.shortString(b)} ");
       case _ => {
            c.abort(f.tree.pos, "match expected in gopher select loop, have: ${MacroUtil.shortString(f.tree)}");
       }
    }
    c.Expr[T](MacroUtil.cleanUntypecheck(c)(q"gopher.goasync.AsyncWrapper.await(${builder}.go)"))
   }

   def foreachBuildMatch(cases:List[c.universe.CaseDef]):c.Tree =
   {
     import c.universe._
     val bn = TermName(c.freshName)
     val calls = transformSelectMatch(bn,cases, defaultActionGenerator)
     q"""..${q"val ${bn} = ${c.prefix}" :: calls}"""
   }


   def transformSelectMatch(bn: c.universe.TermName, cases:List[c.universe.CaseDef], actionGenerator: ActionGenerator):List[c.Tree] =
   {
     cases.zipWithIndex map { case (cs, i) =>
       transformSelectCaseDef(bn,cs, i, actionGenerator)
     }
   }





   def transformSelectCaseDef(builderName:c.TermName,
                              caseDef: c.universe.CaseDef,
                              caseDefIndex: Int,
                              actionGenerator: ActionGenerator):c.Tree = {

     val symbolsToErase: Set[Symbol] = Option(caseDef.pat.symbol).toSet.flatMap { (sym: Symbol) =>
       Set(sym) ++ Option(sym.owner).toSet
     }

     //  when we split cassDef on few functions, than sometines, symbols
     // entries in identifier tree are not cleared.
     //   So, we 'reset' symbols which belong to caseDef which will be erased by macros
     //   //TODO: check, may be will be better to use scala-compiler internal API and changeOwner instead.
     //           yet one alternative - untypedef 'up' term
     def clearCaseDefOwner(oldName: c.Name, newName: c.TermName, tree: Tree): Tree = {
       val oldTermName = oldName.toTermName

       def changeName(name: c.TermName): c.TermName =
         if (name == oldTermName) newName else name

       def ownerWillBeErased(sym: Symbol): Boolean =
         symbolsToErase.contains(sym)

       class ClearTransformer extends Transformer {

         var insideMustBeErased: Boolean = false

         override def transform(tree: Tree): Tree = {
           tree match {
             case Typed(ident@Ident(`oldTermName`), _) => if (ident.symbol != null && ownerWillBeErased(ident.symbol))
               atPos(tree.pos)(Ident(newName))
             else
               super.transform(tree)
             case ident@Ident(`oldTermName`) => if (ident.symbol != null && ownerWillBeErased(ident.symbol))
               atPos(tree.pos)(Ident(newName))
             else
               super.transform(tree)
             case _ =>
               if (tree.symbol != null && tree.symbol != NoSymbol) {
                 if (ownerWillBeErased(tree.symbol)) {
                   var prevMustBeErased = insideMustBeErased
                   insideMustBeErased = true
                   try {
                     val (done, rtree) = doClear(tree)
                     insideMustBeErased = prevMustBeErased
                     if (done) {
                       rtree
                     } else {
                       super.transform(tree)
                     }
                   } catch {
                     case ex: Exception =>
                       System.err.println(s"ex, tree.symbol=${tree.symbol}")
                       ex.printStackTrace()
                       throw ex
                   }
                 } else super.transform(tree)
               } else {
                 if (false && insideMustBeErased) {
                   val (done, rtree) = doClear(tree)
                   if (done) rtree else super.transform(rtree)
                 } else
                   super.transform(tree)
               }
           }
         }

         def doClear(tree: c.Tree): (Boolean, c.Tree) = {
           tree match {
             case Ident(name: TermName) =>
               (true, atPos(tree.pos)(Ident(changeName(name))))
             case Bind(name: TermName, body) =>
               (true, atPos(tree.pos)(Bind(changeName(name), transform(body))))
             case ValDef(mods, name, tpt, rhs) =>
               (true, atPos(tree.pos)(ValDef(mods, changeName(name), transform(tpt), transform(rhs))))
             case Select(Ident(name: TermName), proj) =>
               (true, atPos(tree.pos)(Select(Ident(changeName(name)), proj)))
             case _ =>
               // (false, tree)
               throw new IllegalStateException("unexpected shapr")
               c.abort(tree.pos,
                 """Unexpected shape for tree with caseDef owner, which erased by macro,
                                       please, fire bug-report to scala-gopher, raw=""" + showRaw(tree))
           }
         }

       }
       val transformer = new ClearTransformer()
       transformer.transform(tree)
     }

     def retrieveOriginal(tp: Tree): Tree =
       tp match {
         case tpt: TypeTree => if (tpt.original.isEmpty) tpt else tpt.original
         case _ => tp
       }

     def unUnapplyPattern(x: Tree): Tree =
       x match {
         case Bind(name, UnApply(_, List(t@Typed(_, _)))) => Bind(name, t)
         case _ => x
       }



     val acceptor = new SelectCaseDefAcceptor[TermName, Tree] {


       private def paramWithTransformedBody(v:c.universe.TermName, tp:c.universe.Tree):(ValDef,Tree) =
       {
         val newName = v
         val tpoa = clearCaseDefOwner(v, newName, tp)
         val param = ValDef(Modifiers(Flag.PARAM), newName, tpoa, EmptyTree)
         val body = clearCaseDefOwner(v, newName, caseDef.body)
         (param,body)
       }

       override def onRead(bn: TermName, v: TermName, ch: Tree, tp: Tree): Tree = {
         val (param,body) = paramWithTransformedBody(v,tp)
         val reading = actionGenerator.genReading(builderName, ch, param, body)
         atPos(caseDef.pat.pos)(reading)
       }

       override def onWrite(bn: TermName, v:TermName, expr: Tree, ch: Tree, tp:Tree): Tree = {
         val (param,body) = paramWithTransformedBody(v,tp)
         val writing = actionGenerator.genWriting(builderName, ch, expr, param, body)
         atPos(caseDef.pat.pos)(writing)
       }

       override def onSelectTimeout(bn: TermName, v:TermName, select: Tree, tp: Tree): Tree = {
         val (param,body) = paramWithTransformedBody(v,tp)
         val expression = if (!caseDef.guard.isEmpty) {
           parseGuardInSelectorCaseDef(v, caseDef.guard)
         } else {
           atPos(caseDef.pat.pos)(q"implicitly[akka.util.Timeout].duration")
         }
         val timeout = q"${builderName}.timeout(${expression})(${param} => ${body} )"
         atPos(caseDef.pat.pos)(timeout)
       }

       override def onIdle(bn: TermName): Tree = {
         if (!caseDef.guard.isEmpty) {
           c.abort(caseDef.guard.pos,"guard is not supported in select case")
         }
         val r = q"${builderName}.timeout(${builderName}.api.idleTimeout)( _ => ${caseDef.body})"
         atPos(caseDef.pat.pos)(r)
       }

       override def onDone(bn: TermName, v:TermName, ch: Tree, tp: Tree): Tree = {
         val ch1 = q"${ch}.done"
         onRead(bn,v,ch1,tp)
       }
     }

     acceptSelectCaseDefPattern(caseDef, builderName, acceptor)

   }


   def mapBuildMatch[T:c.WeakTypeTag](cases:List[c.universe.CaseDef], actionGenerator: ActionGenerator):c.Tree =
   {
     val bn = TermName(c.freshName)
     val calls = transformSelectMatch(bn,cases,actionGenerator)
     q"""..${q"val ${bn} = ${c.prefix}.inputBuilder[${weakTypeOf[T]}]()" :: calls}"""
   }

   def map[T:c.WeakTypeTag](f:c.Expr[Any=>T]):c.Expr[Input[T]] =
   {
     val builder = f.tree match {
       case Function(forvals,Match(choice,cases)) => 
                                mapBuildMatch[T](cases,defaultActionGenerator)
       case Function(a,b) =>
            c.abort(f.tree.pos, "match expected in gopher select map, have: ${MacroUtil.shortString(b)} ");
       case _ =>
            c.abort(f.tree.pos, "match expected in gopher select map, have: ${MacroUtil.shortString(f.tree)}");

     }
     c.Expr[Input[T]](MacroUtil.cleanUntypecheck(c)(q"${builder}.started"))
   }

   def builder[T](f:c.Expr[PartialFunction[Any,T]]):c.Tree =
   {
     f.tree match {
        case q"{case ..$cases}" =>
                  foreachBuildMatch(cases)
        case _ => c.abort(f.tree.pos,"expected partial function with syntax case ... =>, have ${MacroUtil.shortString(f.tree)}");
     }
   }

   def apply[T](f:c.Expr[PartialFunction[Any,T]]):c.Expr[Future[T]] =
   {
     val b = builder[T](f)
     c.Expr[Future[T]](c.untypecheck(q"${b}.go"))
   }

   /**
    * processor: loop => just add waiters to this selector.
    */
   def loop[T](f:c.Expr[PartialFunction[Any,T]]):c.Expr[Unit] =
   {
     val b = builder[T](f)
     c.Expr[Unit](c.untypecheck(q"{selectorInit = ()=>${b}; selectorInit()}"))
   }

   def input[T:c.WeakTypeTag](f:c.Expr[PartialFunction[Any,T]]):c.Expr[Input[T]] = 
   {
     val builder = f.tree match {
        case q"{case ..$cases}" =>
                         mapBuildMatch[T](cases,defaultActionGenerator)
        case _ => c.abort(f.tree.pos,"expected partial function with syntax case ... =>, have ${MacroUtil.shortString(f.tree)}");
     }
     c.Expr[Input[T]](MacroUtil.cleanUntypecheck(c)(q"${builder}.started"))
   }

  trait SelectCaseDefAcceptor[A,B] {
    def onRead(s:A, v: TermName, ch:Tree, tp: Tree):B
    def onWrite(s:A, v:TermName, expression: Tree, ch:Tree, tp: Tree ):B
    def onSelectTimeout(s:A, v: TermName, select:Tree, tp: Tree):B
    def onIdle(s:A):B
    def onDone(s:A,v: TermName,ch:Tree, tp: Tree):B
  }

  //TODO: generalize and merge with parsing in SelectorBuilderImpl
  def acceptSelectCaseDefPattern[A,B](caseDef:CaseDef,
                                       a: A,
                                       acceptor: SelectCaseDefAcceptor[A,B]):B =
  {

    def acceptTypeTree(tp:Tree, termName:TermName): B = {
      val unwrappedType = MacroUtil.unwrapOriginUnannotatedType(c)(tp)
      unwrappedType match {
        case Select(ch,TypeName("read")) => acceptor.onRead(a,termName,ch,unwrappedType)
        case Select(ch,TypeName("write")) =>
          val expression = if (!caseDef.guard.isEmpty) {
               parseGuardInSelectorCaseDef(termName, caseDef.guard)
          } else {
               atPos(caseDef.pat.pos)(Ident(termName))
          }
        acceptor.onWrite(a,termName, expression, ch, unwrappedType)
        case Select(select,TypeName("timeout")) => acceptor.onSelectTimeout(a,termName,select, tp)
        case Select(ch,TypeName("done")) => acceptor.onDone(a,termName,ch,tp)
        case _ =>
          if (caseDef.guard.isEmpty) {
            c.abort(tp.pos,
              """match pattern in select without guard must be in form x:channel.write or x:channel.read
                |our raw caseDef:
              """.stripMargin + showRaw(caseDef))
          } else {
                parseGuardInSelectorCaseDef(termName, caseDef.guard) match {
                  case q"scala.async.Async.await[${t}](${readed}.aread):${t1}" =>
                    // here is 'reverse' of out read macros
                    acceptor.onRead(a,termName,readed,unwrappedType)
                  case q"gopher.goasync.AsyncWrapper.await[${t}](${readed}.aread):${t1}" =>
                    acceptor.onRead(a,termName,readed,unwrappedType)
                  case q"scala.async.Async.await[${t}](${ch}.awrite($expression)):${t1}" =>
                    acceptor.onWrite(a,termName,expression,ch,unwrappedType)
                  case q"gopher.goasync.AsyncWrapper.await[${t}](${ch}.awrite($expression)):${t1}" =>
                    acceptor.onWrite(a,termName,expression,ch,unwrappedType)
                  case x@_ =>
                    c.abort(tp.pos, "can't parse match guard: "+x);
                }
          }
      }
    }
    caseDef.pat match {
      case Bind(name,t) =>
        val termName = name.toTermName
        t match {
          case Typed(_,tp) =>
            acceptTypeTree(tp,termName)
          //case Typed(_,tp) =>
          //  c.abort(caseDef.pat.pos,s"x:channel.read or x:channel.write , tp must-br TypeTree, our tp=${showRaw(tp)}")
          case _ =>
            c.abort(caseDef.pat.pos,s"x:channel.read or x:channel.write form is required, we have ${showRaw(caseDef.pat)}")
        }
      case Ident(n@TermName("_")) => acceptor.onIdle(a) // was - caseDef.pat
      case Typed(Ident(name),tp) =>  acceptTypeTree(tp,name.toTermName)
      case Typed(_,tp) => c.abort(caseDef.pat.pos,s"name in typed expression is expected, we have ${showRaw(caseDef.pat)}")
      case _ =>
        c.abort(caseDef.pat.pos,s"bind in pattern is expected, we have ${showRaw(caseDef.pat)}")
    }
  }


}

object SelectorBuilder
{


   def readingImpl[A,B:c.WeakTypeTag,S](c:Context)(ch:c.Expr[Input[A]])(f:c.Expr[A=>B]):c.Expr[S] =
   {
      import c.universe._
      f.tree match {
         case Function(valdefs, body) => 
               buildAsyncCall[B,S](c)(valdefs,body, 
                                { (nvaldefs, nbody) =>
                                 q"""${c.prefix}.readingWithFlowTerminationAsync(${ch},
                                       ${Function(nvaldefs,nbody)}
                                      )
                                  """
                                })
         case _ => c.abort(c.enclosingPosition,"argument of reading.apply must be function")
      }
   }

   def writingImpl[A,T:c.WeakTypeTag,S](c:Context)(ch:c.Expr[Output[A]],x:c.Expr[A])(f:c.Expr[A=>T]):c.Expr[S] =
   {
     import c.universe._
     f.tree match {
         case Function(valdefs, body) => 
            val retval = buildAsyncCall[T,S](c)(valdefs,body,
                   { (nvaldefs, nbody) =>
                     q"""${c.prefix}.writingWithFlowTerminationAsync(${ch},${x},
                             ${Function(nvaldefs,nbody)}
                       )
                     """
                   })
            retval
         case _ => c.abort(c.enclosingPosition,"second argument of writing must have shape Function(x,y)")
     }
   }

   def transformDelayedMacroses[T:c.WeakTypeTag](c:Context)(block:c.Tree):c.Tree =
   {
     import c.universe._
     val transformer = new Transformer {
        override def transform(tree:Tree): Tree =
            tree match {
               case Apply(TypeApply(Select(obj,TermName("implicitly")),List(objType)), args) =>
                    // unresolve implicit references of specific type
                    if (!(obj.tpe eq null) && obj.tpe =:= typeOf[Predef.type] &&
                        objType.tpe <:< typeOf[FlowTermination[Nothing]]
                        ) {
                       TypeApply(Select(obj,TermName("implicitly")),List(objType))
                    } else {
                       super.transform(tree)
                    }
               case Apply(TypeApply(Select(obj,member),objType), args) =>
                    if (!(obj.tpe eq null) && obj.tpe =:= typeOf[CurrentFlowTermination.type] ) {
                       member match {
                          case TermName("exit") => 
                                 Apply(TypeApply(Select(obj,TermName("exitDelayed")),objType), args) 
                          case _ => super.transform(tree)
                       }
                    } else {
                       super.transform(tree)
                    }
                case Apply(Select(obj,member), args) =>
                    if (!(obj.tpe eq null) && obj.tpe =:= typeOf[CurrentFlowTermination.type] ) {
                       member match {
                          case TermName("exit") => 
                                   Apply(Select(obj,TermName("exitDelayed")),args)
                          case _ => super.transform(tree)
                       }
                    } else {
                       super.transform(tree)
                    }
                case _ => 
                    super.transform(tree)
          }
     }
     transformer.transform(block)
   }

   def buildAsyncCall[T:c.WeakTypeTag,S](c:Context)(valdefs: List[c.universe.ValDef], body: c.Tree,
                                     lastFun: (List[c.universe.ValDef], c.Tree) => c.Tree): c.Expr[S] =
   {
     import c.universe._
     val Seq(ft, ft1, ec, ec1) = Seq("ft","ft","ec","ec1") map (x => TermName(c.freshName(x)))
     val ftParam = ValDef(Modifiers(Flag.PARAM),ft,tq"gopher.FlowTermination[${weakTypeOf[T]}]",EmptyTree)
     val ecParam = ValDef(Modifiers(Flag.PARAM),ec,tq"scala.concurrent.ExecutionContext",EmptyTree)
     val nvaldefs = ecParam::ftParam::valdefs
     val asyncBody =  GoAsync.transformAsyncBody[T](c)(body)
     val nbody = q"""{
                      implicit val ${ft1} = ${ft}
                      implicit val ${ec1} = ${ec}
                      gopher.goasync.AsyncWrapper.async(${transformDelayedMacroses[T](c)(asyncBody)})(${ec})
                     }
                  """
     val newTree = lastFun(nvaldefs,nbody)
     // untypecheck is necessory: otherwise exception in async internals
     c.Expr[S](MacroUtil.cleanUntypecheck(c)(newTree))
   }

   def idleImpl[T:c.WeakTypeTag,S](c:Context)(body:c.Expr[T]):c.Expr[S] =
   {
     import c.universe._
     c.Expr[S](q"${c.prefix}.timeout(${c.prefix}.api.idleTimeout)(_ => ${body})")
   }

   def timeoutImpl[T:c.WeakTypeTag,S](c:Context)(t:c.Expr[FiniteDuration])(f:c.Expr[FiniteDuration=>T]):c.Expr[S] = 
   {
     import c.universe._
     f.tree match {
       case Function(valdefs, body) =>
               val r = SelectorBuilder.buildAsyncCall[T,S](c)(valdefs,body,
                   { (nvaldefs, nbody) =>
                      q"""${c.prefix}.timeoutWithFlowTerminationAsync(${t},
                                    ${Function(nvaldefs,nbody)}
                          )
                       """
                   })
              r
      case _ => c.abort(c.enclosingPosition,"second argument of timeout must have shape Function(x,y)")
     }
   }



}



