/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.terfor.preds;

import ap.basetypes.IdealInt
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.equations.EquationConj
import ap.util.{Debug, Logic, Seqs}

object Atom {
  
  private val AC = Debug.AC_PREDICATES

  def apply(pred : Predicate,
            args : Iterator[LinearCombination],
            order : TermOrder) : Atom =
    new Atom (pred, Seqs toRandomAccess args, order)

  def apply(pred : Predicate,
            args : Iterable[LinearCombination],
            order : TermOrder) : Atom =
    new Atom (pred, Seqs toRandomAccess args, order)

}

class Atom private (val pred : Predicate,
                    args : RandomAccessSeq[LinearCombination],
                    val order : TermOrder)
      extends Formula
              with SortedWithOrder[Atom]
              with RandomAccessSeq[LinearCombination] {

  //////////////////////////////////////////////////////////////////////////////
  Debug.assertCtor(Atom.AC,
                   args.size == pred.arity &&
                   Logic.forall(for (lc <- this.elements) yield (lc isSortedBy order)))
  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Derive equations that describe under which condition this atom describes
   * the same location as that atom (basically, all the argument term have to
   * have the same values)
   */
  def unify(that : Atom, order : TermOrder) : EquationConj =
    if (this.pred == that.pred) {
      if (this.size == 0)
        // no arguments to compare
        EquationConj.TRUE
      else
        EquationConj(unificationConditions(that, order), order)
    } else {
      EquationConj.FALSE
    }
    
  def unificationConditions(that : Atom, order : TermOrder) : Iterator[LinearCombination] =
    for ((arg1, arg2) <- (this.elements zip that.elements))
      yield LinearCombination.sum(Array((IdealInt.ONE, arg1),
                                        (IdealInt.MINUS_ONE, arg2)),
                                  order)
  
  //////////////////////////////////////////////////////////////////////////////
    
  def length : Int = args.length
      
  def apply(i : Int) : LinearCombination = args(i)
  
  override def elements = args.elements

  def updateArgs(newArgs : Iterable[LinearCombination])
                (implicit order : TermOrder) : Atom =
    if (this sameElements newArgs)
      this
    else
      Atom(pred, newArgs, order)
  
  //////////////////////////////////////////////////////////////////////////////

  def sortBy(newOrder : TermOrder) : Atom =
    if (isSortedBy(newOrder))
      this
    else
      new Atom (pred,
                (for (a <- args) yield (a sortBy newOrder)).toArray,
                newOrder)
  
  //////////////////////////////////////////////////////////////////////////////
  
  lazy val variables : Set[VariableTerm] =
    Set.empty ++ (for (lc <- this.elements; v <- lc.variables.elements) yield v)

  lazy val constants : Set[ConstantTerm] =
    Set.empty ++ (for (lc <- this.elements; c <- lc.constants.elements) yield c)

  val predicates : Set[Predicate] = Set(pred)

  lazy val groundAtoms : Set[Atom] =
    if (variables.isEmpty) Set(this) else Set.empty

  /** Return <code>true</code> if this formula is obviously always true */
  def isTrue : Boolean = false
      
  /** Return <code>true</code> if this formula is obviously always false */
  def isFalse : Boolean = false

  //////////////////////////////////////////////////////////////////////////////

  override def equals(that : Any) : Boolean = that match {
    case that : Atom => (this.pred == that.pred) && (this sameElements that)
    case _ => false
  }
    
  private lazy val hashCodeVal =
    pred.hashCode + Seqs.computeHashCode(this, 911887, 9)

  override def hashCode = hashCodeVal

  override def toString = {
    val strings = for (lc <- this.elements) yield lc.toString
    pred.name +
    (if (strings.hasNext)
       "(" + strings.reduceLeft((s1 : String, s2 : String) => s1 + ", " + s2) + ")"
     else
       "")
  }

}