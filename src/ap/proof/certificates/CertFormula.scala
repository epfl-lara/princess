/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2010,2011 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.proof.certificates

import ap.basetypes.IdealInt
import ap.terfor.{ConstantTerm, SortedWithOrder, TermOrder, TerFor}
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.preds.{Predicate, Atom}
import ap.terfor.conjunctions.Conjunction
import ap.terfor.equations.EquationConj
import ap.terfor.TerForConvenience._
import ap.util.Debug

object CertFormula {
  private val AC = Debug.AC_CERTIFICATES
  
  /**
   * Convert a formula/conjunction in internal representation to the
   * certificate formula datastructures
   */
  def apply(f : Conjunction) : CertFormula =
    if (!f.isTrue && !f.isFalse && f.isLiteral) {
      
      if (f.arithConj.positiveEqs.size == 1)
        CertEquation(f.arithConj.positiveEqs(0))
      else if (f.arithConj.negativeEqs.size == 1)
        CertNegEquation(f.arithConj.negativeEqs(0))
      else if (f.arithConj.inEqs.size == 1)
        CertInequality(f.arithConj.inEqs(0))
      else if (f.predConj.positiveLits.size == 1)
        CertPredLiteral(false, f.predConj.positiveLits(0))
      else {
        //-BEGIN-ASSERTION-/////////////////////////////////////////////////////
        Debug.assertInt(AC, f.predConj.negativeLits.size == 1)
        //-END-ASSERTION-///////////////////////////////////////////////////////
        CertPredLiteral(true, f.predConj.negativeLits(0))
      }
      
    } else {
      
      CertCompoundFormula(f)
      
    }
}

/**
 * Hierarchy of formulae specifically for representing certificates; the reason
 * is that the standard formula datastructures perform too much simplification
 * implicitly
 */
abstract sealed class CertFormula(underlying : SortedWithOrder[TerFor]) {

  val order = underlying.order
  def constants = underlying.constants
  def predicates = underlying.predicates

  /** Return <code>true</code> if this formula is obviously always true */
  def isTrue : Boolean
  
  /** Return <code>true</code> if this formula is obviously always false */
  def isFalse : Boolean

  /** Negate this formula */
  def unary_! : CertFormula
  
  /** Convert this formula to the corresponding formula in internal
   *  representation */
  def toConj : Conjunction
  
}

////////////////////////////////////////////////////////////////////////////////

abstract class CertArithLiteral(val lhs : LinearCombination)
               extends CertFormula(lhs) {
  
  def update(newLhs : LinearCombination) : CertArithLiteral
  
}

/**
 * Formula expressing an equation <code>lhs = 0</code>
 */
case class CertEquation(_lhs : LinearCombination)
           extends CertArithLiteral(_lhs) with SortedWithOrder[CertEquation] {
 
  def sortBy(otherOrder : TermOrder) = CertEquation(lhs sortBy otherOrder)

  def update(newLhs : LinearCombination) : CertEquation =
    if (lhs == newLhs) this else CertEquation(newLhs)

  def isTrue : Boolean = lhs.isZero
  def isFalse : Boolean = lhs.isNonZero

  def toConj : Conjunction = {
    implicit val o = order
    lhs === 0
  }

  def unary_! : CertNegEquation = CertNegEquation(lhs)

  override def toString = "(" + lhs + " = 0)"
}

/**
 * Formula expressing a negated equation <code>lhs != 0</code>
 */
case class CertNegEquation(_lhs : LinearCombination)
           extends CertArithLiteral(_lhs) with SortedWithOrder[CertNegEquation] {
 
  def sortBy(otherOrder : TermOrder) = CertNegEquation(lhs sortBy otherOrder)

  def update(newLhs : LinearCombination) : CertNegEquation =
    if (lhs == newLhs) this else CertNegEquation(newLhs)

  def isTrue : Boolean = lhs.isNonZero
  def isFalse : Boolean = lhs.isZero

  def toConj : Conjunction = {
    implicit val o = order
    lhs =/= 0
  }

  def unary_! : CertEquation = CertEquation(lhs)

  override def toString = "(" + lhs + " != 0)"
}

/**
 * Formula expressing an inequality <code>lhs >= 0</code>
 */
case class CertInequality(_lhs : LinearCombination)
           extends CertArithLiteral(_lhs) with SortedWithOrder[CertInequality] {
 
  def sortBy(otherOrder : TermOrder) = CertInequality(lhs sortBy otherOrder)

  def update(newLhs : LinearCombination) : CertInequality =
    if (lhs == newLhs) this else CertInequality(newLhs)

  def isTrue : Boolean = lhs.isConstant && lhs.constant.signum >= 0
  def isFalse : Boolean = lhs.isConstant && lhs.constant.signum < 0

  def toConj : Conjunction = {
    implicit val o = order
    lhs >= 0
  }

  def unary_! : CertInequality =
    CertInequality(lhs.scaleAndAdd(IdealInt.MINUS_ONE, IdealInt.MINUS_ONE))

  override def toString = "(" + lhs + " >= 0)"
}

/**
 * Formula expressing a positive or negative occurrence of a predicate atom
 */
case class CertPredLiteral(negated : Boolean, atom : Atom)
           extends CertFormula(atom) with SortedWithOrder[CertPredLiteral] {
  
  def sortBy(otherOrder : TermOrder) =
    CertPredLiteral(negated, atom sortBy otherOrder)

  def isTrue : Boolean = false
  def isFalse : Boolean = false

  def toConj : Conjunction = {
    implicit val o = order
    if (negated) !atom2Conj(atom) else atom
  }

  def unary_! : CertPredLiteral = CertPredLiteral(!negated, atom)

  override def toString = (if (negated) "!" else "") + atom.toString
}

////////////////////////////////////////////////////////////////////////////////

object CertCompoundFormula {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Compound formulae in certificates
 */
case class CertCompoundFormula(f : Conjunction)
           extends CertFormula(f) with SortedWithOrder[CertCompoundFormula] {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(CertCompoundFormula.AC, f.isFalse || !f.isLiteral)
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  def sortBy(otherOrder : TermOrder) = CertCompoundFormula(f sortBy otherOrder)

  def isTrue : Boolean = f.isTrue
  def isFalse : Boolean = f.isFalse

  def toConj : Conjunction = f

  def unary_! : CertCompoundFormula = CertCompoundFormula(!f)

  override def toString = f.toString
}