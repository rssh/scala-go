package gopher.channels

import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context
import scala.reflect.api._
import gopher._
import gopher.util._
import scala.concurrent._
import scala.annotation.unchecked._


/**
 * Factory for select instantiation.
 * Can be obtained via gopherAPI
 *
 * {{{
 *   val selector = gopherApi.select.forever
 *   for(s <- selector) ...
 * }}}
 */
class SelectFactory(val api: GopherAPI)
{
 
  selectFactory =>

  trait SelectFactoryApi
  {
    def api = selectFactory.api
  }

  /**
   * forever builder. 
   *@see ForeverSelectorBuilder
   */
  def forever: ForeverSelectorBuilder = new ForeverSelectorBuilder with SelectFactoryApi {}

  /**
   * once builder, where case clause return type is `T`
   */
  def once[T]: OnceSelectorBuilder[T] = new OnceSelectorBuilder[T] with SelectFactoryApi {}

  def inputBuilder[T]() = new InputSelectorBuilder[T](api)

  /**
   * generic selector builder
   */
  def loop[A]: SelectorBuilder[A] = new SelectorBuilder[A] with SelectFactoryApi {}

  def afold[S](s:S)(op:(S,Any)=>S):Future[S] = macro FoldSelectorBuilderImpl.afold[S]
  
  def fold[S](s:S)(op:(S,Any)=>S):S = macro FoldSelectorBuilderImpl.fold[S]

  def map[B](f:Any=>B):Input[B] = macro SelectorBuilder.mapImpl[B]

  def input[B](f:PartialFunction[Any,B]):Input[B] =
                                    macro SelectorBuilder.inputImpl[B]

  def amap[B](f:PartialFunction[Any,B]):Input[B] =
                                    macro SelectorBuilder.inputImpl[B]


}
