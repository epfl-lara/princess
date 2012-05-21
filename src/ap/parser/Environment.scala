/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2011 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princess is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Princess.  If not, see <http://www.gnu.org/licenses/>.
 */

package ap.parser;

import ap.Signature
import ap.terfor.{VariableTerm, ConstantTerm, TermOrder}
import ap.util.{FilterIt, Debug}

object Environment {
  
  private val AC = Debug.AC_ENVIRONMENT
  
  abstract sealed class SymKind
  case object NullaryFunction extends SymKind
  case object Universal extends SymKind
  case object Existential extends SymKind

  abstract sealed class DeclaredSym[ConstantType,
                                    VariableType,
                                    PredicateType,
                                    FunctionType]
  case class Constant[CT,VT,PT,FT](c : ConstantTerm, k : SymKind, typ : CT)
             extends DeclaredSym[CT,VT,PT,FT]
  case class Variable[CT,VT,PT,FT](index : Int, typ : VT)
             extends DeclaredSym[CT,VT,PT,FT]
  case class Predicate[CT,VT,PT,FT](pred : ap.terfor.preds.Predicate, typ : PT)
             extends DeclaredSym[CT,VT,PT,FT]
  case class Function[CT,VT,PT,FT](fun : IFunction, typ : FT)
             extends DeclaredSym[CT,VT,PT,FT]

  class EnvironmentException(msg : String) extends Exception(msg)

}

class Environment[ConstantType, VariableType, PredicateType, FunctionType] {

  import Environment._
  
  type DSym = DeclaredSym[ConstantType, VariableType, PredicateType, FunctionType]
  
  /** The declared symbols */
  private val signature = new scala.collection.mutable.HashMap[String, DSym]
  
  /** The variables bound at the present point, together with a flag
   *  telling whether the variables represent integers or booleans */
  private val context =
    new scala.collection.mutable.ArrayBuffer[(String, VariableType)]
  
  /** A <code>TermOrder</code> containing all declared constants */
  private var orderVar = TermOrder.EMPTY
  
  private val partNames = new scala.collection.mutable.HashMap[String, PartName]
  
  def order = orderVar
  def order_=(newOrder : TermOrder) = {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertPre(Environment.AC,
                    signature.valuesIterator forall {
                      case Constant(c, _, _) => newOrder.orderedConstants contains c
                      case Predicate(pred, _) => newOrder.orderedPredicates contains pred
                      case _ => true
                    })
    //-END-ASSERTION-///////////////////////////////////////////////////////////
    orderVar = newOrder
  }
  
  override def clone : Environment[ConstantType,
                                   VariableType,
                                   PredicateType,
                                   FunctionType] = {
    val res = new Environment[ConstantType, VariableType, PredicateType, FunctionType]
    
    res.signature ++= this.signature
    res.context ++= this.context
    res.orderVar = this.orderVar
    
    res
  }
  
  def lookupSym(name : String) : DSym =
    (context lastIndexWhere (_._1 == name)) match {
      case -1 => (signature get name) match {
        case Some(t) =>
          t
        case None =>
          throw new EnvironmentException("Symbol " + name + " not declared")
      }
      case index =>
        Variable(context.size - index - 1, context(index)._2)
    }
  
  def isDeclaredSym(name : String) : Boolean =
    (context exists (_._1 == name)) || (signature contains name)
  
  def addConstant(c : ConstantTerm, kind : SymKind, typ : ConstantType) : Unit = {
    addSym(c.name, Constant(c, kind, typ))
    orderVar = kind match {
      case Universal =>
        // universal constants are minimal
        orderVar.extend(c, Set.empty)
      case NullaryFunction =>
        // nullary functions are maximal
        orderVar.extend(c, universalConstants ++ existentialConstants)
      case Existential =>
        // existential constants are small than nullary functions, but bigger
        // than universal constants
        orderVar.extend(c, universalConstants)
    }
  }
 
  def addPredicate(pred : ap.terfor.preds.Predicate, typ : PredicateType) : Unit = {
    addSym(pred.name, Predicate(pred, typ))
    orderVar = orderVar extend pred
  }
  
  def addFunction(fun : IFunction, typ : FunctionType) : Unit =
    addSym(fun.name, Function(fun, typ))
  
  def nullaryFunctions : Set[ConstantTerm] = constants(NullaryFunction)
  def universalConstants : Set[ConstantTerm] = constants(Universal)
  def existentialConstants : Set[ConstantTerm] = constants(Existential)
  def nonNullaryFunctions : Set[IFunction] =
    Set.empty ++ (for (Function(f, _) <- signature.values) yield f)
  
  private def constants(kind : SymKind) : Set[ConstantTerm] =
    Set.empty ++ (for (Constant(c, `kind`, _) <- signature.valuesIterator)
                  yield c)
  
  private def addSym(name : String, t : DSym) : Unit =
    if (signature contains name)
      throw new EnvironmentException("Symbol " + name + " is already declared")
    else
      signature += (name -> t)
  
  def pushVar(name : String, typ : VariableType) : Unit =
    context += ((name, typ))

  def popVar : Unit =
    if (context isEmpty)
      throw new EnvironmentException("Trying to pop a non-existing variable")
    else
      context reduceToSize (context.size - 1)
  
  def existsVar(pred : VariableType => Boolean) =
    context exists { case (_, t) => pred(t) }
  
  def declaredVariableNum = context.size
  
  def lookupPartName(name : String) : PartName =
    partNames.getOrElseUpdate(name, new PartName (name))
  
  def toSignature =
    new Signature (universalConstants, existentialConstants,
                   nullaryFunctions, order)
}
