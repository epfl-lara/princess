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

package ap.proof.certificates

import ap.basetypes.IdealInt
import ap.terfor.{TermOrder, ConstantTerm}
import ap.terfor.TerForConvenience._
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.equations.{EquationConj, NegEquationConj}
import ap.terfor.inequalities.InEqConj
import ap.terfor.arithconj.ArithConj
import ap.terfor.conjunctions.{Conjunction, Quantifier}
import ap.terfor.preds.{Atom, PredConj}
import ap.terfor.substitutions.ConstantSubst
import ap.util.Debug

/**
 * Abstract superclass of all certificate nodes that only have a single subtree
 */
abstract class CertificateOneChild(val child : Certificate)
               extends Certificate {

  lazy val localProvidedFormulas : Seq[Set[CertFormula]] =
    List(uniqueLocalProvidedFormulas)

  protected val uniqueLocalProvidedFormulas : Set[CertFormula]

  //////////////////////////////////////////////////////////////////////////////

  def length = 1
  def apply(i : Int) : Certificate = {
    if (i != 0)
      throw new NoSuchElementException
    child
  }
  def iterator = Iterator single child

}

////////////////////////////////////////////////////////////////////////////////

object BranchInferenceCertificate {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inferences that do not cause proof splitting and that do not close a branch
 * are collected in nodes of this class.
 */
case class BranchInferenceCertificate(inferences : Seq[BranchInference],
                                      _child : Certificate,
                                      order : TermOrder) extends {

  private val providedAssumed : (Set[CertFormula], Set[CertFormula]) =
    ((Set[CertFormula](), Set[CertFormula]()) /: inferences) {
      case ((provided, assumed), inf) =>
        (provided ++ inf.providedFormulas,
         assumed ++ (inf.assumedFormulas -- provided))
    }

  val uniqueLocalProvidedFormulas : Set[CertFormula] = providedAssumed _1
  val localAssumedFormulas : Set[CertFormula] = providedAssumed _2

  val closingConstraint =
    (inferences :\ _child.closingConstraint)(_ propagateConstraint _)
  
} with CertificateOneChild(_child) {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(BranchInferenceCertificate.AC,
                   !inferences.isEmpty &&
                   (uniqueLocalProvidedFormulas forall (child.order isSortingOf _)))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  override def toString : String =
    "BranchInferences(" + (inferences mkString ", ") + ", " + child + ")"
  
  override def inferenceCount : Int = super.inferenceCount - 1 + inferences.size

  def update(newSubCerts : Seq[Certificate]) : Certificate = {
    //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
    Debug.assertPre(BranchInferenceCertificate.AC, newSubCerts.size == 1)
    //-END-ASSERTION-/////////////////////////////////////////////////////////////
    val newChild = newSubCerts.head
    if (newChild eq child) this else copy(_child = newChild)
  }

}

////////////////////////////////////////////////////////////////////////////////

object BranchInference { 
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Abstract superclass of all inferences that do not cause proof splitting and
 * that do not close proof branches
 */
abstract class BranchInference {
  
  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(BranchInference.AC,
                   (assumedFormulas forall ((c:CertFormula) => !c.isTrue)) &&
                   !(providedFormulas forall ((c:CertFormula) => c.isTrue)))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
  
  val assumedFormulas : Set[CertFormula]
  
  /**
   * Formulae that are introduced into the antecedent by this rule application.
   * This will implicitly simplify formulae (all
   * simplifications that are built into the datastructures are carried out).
   */
  val providedFormulas : Set[CertFormula]

  /**
   * Define the modification imposed by this rule application on the closing
   * constraint.
   */
  def propagateConstraint(closingConstraint : Conjunction) : Conjunction
  
}

////////////////////////////////////////////////////////////////////////////////

object AlphaInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to an application of alpha rules.
 */
case class AlphaInference(splitFormula : CertCompoundFormula,
                          providedFormulas : Set[CertFormula]) extends {
  
  val assumedFormulas = Set[CertFormula](splitFormula)
  
} with BranchInference {
  
  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(AlphaInference.AC, {
                     implicit val o = splitFormula.order
                     splitFormula.f == conj(for (f <- providedFormulas) yield f.toConj)
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint

  override def toString : String =
    "Alpha(" + splitFormula + " -> " + (providedFormulas mkString ", ") + ")"
}

////////////////////////////////////////////////////////////////////////////////

object QuantifierInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to applications of the rules <code>all-left</code>,
 * <code>ex-left</code>, etc. A uniform prefix of quantifiers (only forall or
 * only exists) is instantiated with a single inference.
 * <code>newConstants</code> are the constants introduced to instantiate the
 * quantifiers, starting with the innermost instantiated quantifier.
 */
case class QuantifierInference(quantifiedFormula : CertCompoundFormula,
                               newConstants : Seq[ConstantTerm],
                               result : CertFormula,
                               order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](quantifiedFormula)
  val providedFormulas = Set(result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(QuantifierInference.AC,
                   !newConstants.isEmpty &&
                   {
                     val quans = quantifiedFormula.f.quans
                     !quans.isEmpty &&
                     // the instantiate quantifiers are uniform
                     (quans.drop(quans.size - newConstants.size) forall
                                                          (quans.last == _)) &&
                     // and no quantifiers of the same kind are left
                     (quans.size == newConstants.size ||
                      quans(quans.size - newConstants.size - 1) != quans.last)
                   } &&
                   result.toConj == quantifiedFormula.f.instantiate(newConstants)(order))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  def propagateConstraint(closingConstraint : Conjunction) = {
    implicit val o = order
    quantify(quantifiedFormula.f.quans.last.dual, newConstants, closingConstraint)
  }

  override def toString : String =
    "Quantifier((" + quantifiedFormula + ") [" +
    (newConstants mkString ", ") + "] -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object GroundInstInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to applications of the rules <code>all-left</code>,
 * <code>ex-left</code>, etc. A uniform prefix of quantifiers (only forall or
 * only exists) is instantiated with a single inference.
 * <code>newConstants</code> are the constants introduced to instantiate the
 * quantifiers, starting with the innermost instantiated quantifier.
 */
case class GroundInstInference(quantifiedFormula : CertCompoundFormula,
                               instanceTerms : Seq[LinearCombination],
                               result : CertFormula,
                               order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](quantifiedFormula)
  val providedFormulas = Set(result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(GroundInstInference.AC,
                   !instanceTerms.isEmpty &&
                   (instanceTerms forall (_.variables.isEmpty)) &&
                   {
                     val quans = quantifiedFormula.f.quans
                     !quans.isEmpty &&
                     // the instantiate quantifiers are universal
                     (quans.drop(quans.size - instanceTerms.size) forall
                                                          (Quantifier.ALL == _)) &&
                     // and no quantifiers of the same kind are left
                     (quans.size == instanceTerms.size ||
                      quans(quans.size - instanceTerms.size - 1) != quans.last)
                   } &&
                   result.toConj == quantifiedFormula.f.instantiate(instanceTerms)(order))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint

  override def toString : String =
    "GroundInst((" + quantifiedFormula + ") [" +
    (instanceTerms mkString ", ") + "] -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object ReduceInference {
  private val AC = Debug.AC_CERTIFICATES
  
  def apply(equations : Seq[(IdealInt, CertEquation)],
            targetLit : CertArithLiteral,
            order : TermOrder) : ReduceInference =
    ReduceInference(equations, targetLit,
                    computeResult(equations, targetLit, order),
                    order)
  
  private def computeResult(equations : Seq[(IdealInt, CertEquation)],
                            targetLit : CertArithLiteral,
                            order : TermOrder) = {
    implicit val o = order
    val modifier =
      sum(for ((c, e) <- equations.iterator) yield (c, e.lhs))
    targetLit match {
      case CertNegEquation(lhs) => CertNegEquation(lhs + modifier)
      case CertInequality(lhs) => CertInequality(lhs + modifier)
      case _ => { assert(false); null }
    }
  }
}

/**
 * Inference corresponding to a series of applications of the reduce rule to a
 * negated equation or an inequality (reduction of positive equalities is
 * described using <code>CombineEquationsInference</code>).
 */
case class ReduceInference(equations : Seq[(IdealInt, CertEquation)],
                           targetLit : CertArithLiteral, result : CertArithLiteral,
                           order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](targetLit) ++
                             (for ((_, e) <- equations.iterator) yield e)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(ReduceInference.AC,
                   !equations.size.isEmpty &&
                   result ==
                     ReduceInference.computeResult(equations, targetLit, order))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint  

  override def toString : String =
    "Reduce(" + targetLit + " + " +
    ((for ((c, e) <- equations) yield "" + c + " * (" + e + ")") mkString " + ") +
    " -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object ReducePredInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to a series of applications of the reduce rule to the
 * arguments of a predicate literal. This is essentially the same as the
 * <code>ReduceInference</code>, only that all of the arguments can be reduced
 * simultaneously
 */
case class ReducePredInference(equations : Seq[Seq[(IdealInt, CertEquation)]],
                               targetLit : CertPredLiteral, result : CertPredLiteral,
                               order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](targetLit) ++
                             (for (eqs <- equations.iterator;
                                   (_, e) <- eqs.iterator) yield e)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(ReducePredInference.AC,
                   targetLit.predicates == result.predicates &&
                   targetLit.negated == result.negated &&
                   targetLit.predicates.iterator.next.arity == equations.size &&
                   (equations exists (!_.isEmpty)) &&
                   result.atom == {
                     implicit val o = order
                     val targetAtom = targetLit.atom
                     
                     val newArgs =
                       for ((lc, eqs) <- targetAtom.iterator zip equations.iterator)
                       yield (lc + sum(for ((c, e) <- eqs.iterator) yield (c, e.lhs)))
                     
                     targetAtom pred newArgs
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint  

  override def toString : String =
    "ReducePred(" + targetLit + " + (" +
    ((for (eqs <- equations) yield
      ((for ((c, e) <- eqs) yield "" + c + " * (" + e + ")") mkString " + "))
     mkString ", ") +
    ") -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object CombineEquationsInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to a series of applications of the reduce rule: form
 * the linear combination of a sequence of equations. The result is implicitly
 * made primitive (divided by common coefficients)
 */
case class CombineEquationsInference(equations : Seq[(IdealInt, CertEquation)],
                                     result : CertEquation,
                                     order : TermOrder)
           extends {

  val assumedFormulas =
    Set[CertFormula]() ++ (for ((_, e) <- equations.iterator) yield e)
  val providedFormulas =
    Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(CombineEquationsInference.AC,
                   // no interesting inferences can be made from only one
                   // equation
                   equations.size >= 2 &&
                   (equations forall { case (c, e) => !c.isZero }) &&
                   result.lhs == {
                     implicit val o = order
                     sum(for ((c, e) <- equations.iterator) yield (c, e.lhs))
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint

  override def toString : String =
    "CombineEquations(" +
    ((for ((c, e) <- equations) yield "" + c + " * (" + e + ")") mkString " + ") +
    " -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object ColumnReduceInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to an application of the <code>col-red</code> or
 * <code>col-red-subst</code> rule. This will simply introduce a new constant
 * <code>newSymbol</code> that is defined by <code>definingEquation</code>.
 */
case class ColumnReduceInference(oldSymbol : ConstantTerm, newSymbol : ConstantTerm,
                                 definingEquation : CertEquation,
                                 subst : Boolean, order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula]()
  val providedFormulas = Set[CertFormula](definingEquation)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(ColumnReduceInference.AC,
                   !definingEquation.isFalse &&
                   (definingEquation.lhs get oldSymbol).abs.isOne &&
                   (definingEquation.lhs get newSymbol) ==
                     -(definingEquation.lhs get oldSymbol))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  private lazy val constraintSubst = {
    implicit val o = order
    ConstantSubst(newSymbol,
                  newSymbol - definingEquation.lhs * (definingEquation.lhs get newSymbol),
                  order)
  }
  
  def propagateConstraint(closingConstraint : Conjunction) = {
    implicit val o = order
    if (subst)
      constraintSubst(closingConstraint)
    else
      forall(newSymbol, closingConstraint)
  }

  override def toString : String =
    "ColumnReduce(" + oldSymbol + " -> " + newSymbol + ", " + definingEquation + ")"
}

////////////////////////////////////////////////////////////////////////////////

object CombineInequalitiesInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference corresponding to a series of applications of the reduce rule: form
 * the linear combination of a sequence of equations. The result is implicitly
 * made primitive (divided by common coefficients) and rounded
 */
case class CombineInequalitiesInference(leftCoeff : IdealInt, leftInEq : CertInequality,
                                        rightCoeff : IdealInt, rightInEq : CertInequality,
                                        result : CertInequality,
                                        order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](leftInEq, rightInEq)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(CombineInequalitiesInference.AC,
                   !leftInEq.isFalse && !rightInEq.isFalse &&
                   leftCoeff.signum > 0 && rightCoeff.signum > 0 &&
                   !result.isTrue &&
                   result.lhs == {
                     implicit val o = order
                     leftInEq.lhs * leftCoeff + rightInEq.lhs * rightCoeff
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint

  override def toString : String =
    "CombineInequalities(" +
    leftCoeff + " * (" + leftInEq + ") + " + rightCoeff + " * (" + rightInEq +
    ") -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object SimpInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Inference representing the simplification of an equation, a negated equation,
 * or an inequality
 */
case class SimpInference(targetLit : CertArithLiteral, result : CertArithLiteral,
                         order : TermOrder)
           extends {
  
  val assumedFormulas = Set[CertFormula](targetLit)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  val (factor, constantDiff) = {
    val unsimplified = getLHS(targetLit)
    val simplified = getLHS(result)
    
    if (unsimplified.isConstant) {
      (IdealInt.ONE, unsimplified.constant - simplified.constant)
    } else {
      val factor = unsimplified.leadingCoeff / simplified.leadingCoeff
      val constantDiff = unsimplified.constant - simplified.constant * factor
      (factor, constantDiff)
    }
  }

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(SimpInference.AC,
                   !targetLit.isFalse &&
                   !result.isFalse && !result.isTrue &&
                   targetLit != result &&
                   getLHS(result) * factor + constantDiff == getLHS(targetLit) &&
                   constantDiff.signum >= 0 &&
                   ((targetLit, result) match {
                     case (CertEquation(unsimplified), CertEquation(simplified)) =>
                       constantDiff.isZero &&
                       simplified.isPrimitive && simplified.isPositive
                     case (CertNegEquation(unsimplified), CertNegEquation(simplified)) =>
                       constantDiff.isZero &&
                       simplified.isPrimitive && simplified.isPositive
                     case (CertInequality(unsimplified), CertInequality(simplified)) =>
                       factor.isPositive && simplified.isPrimitive
                     case _ => false
                   }))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint
  
  private def getLHS(f : CertFormula) = f match {
    case CertEquation(lhs) => lhs
    case CertNegEquation(lhs) => lhs
    case CertInequality(lhs) => lhs
    case _ => { assert(false); null }
  }
  
  override def toString : String =
    "Simp(" + targetLit + " -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object AntiSymmetryInference {
  private val AC = Debug.AC_CERTIFICATES
  
  def apply(leftInEq : CertInequality, rightInEq : CertInequality,
            order : TermOrder) : AntiSymmetryInference =
    AntiSymmetryInference(leftInEq, rightInEq,
                          if (leftInEq.lhs.isPositive)
                            CertEquation(leftInEq.lhs)
                          else
                            CertEquation(rightInEq.lhs),
                          order)
}

/**
 * Turn two complementary inequalities into an equation
 */
case class AntiSymmetryInference(leftInEq : CertInequality, rightInEq : CertInequality,
                                 result : CertEquation,
                                 order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](leftInEq, rightInEq)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(AntiSymmetryInference.AC,
                   !leftInEq.isFalse && !rightInEq.isFalse &&
                   result.lhs.isPositive &&
                   leftInEq.lhs == -rightInEq.lhs)
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint
  
  override def toString : String =
    "AntiSymmetry((" + leftInEq + "), (" + rightInEq + ") -> " + result + ")"
}

////////////////////////////////////////////////////////////////////////////////

object DirectStrengthenInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * Given the two formulae <code>t >= 0</code> and <code>t != 0</code> (or,
 * similarly, <code>t >= 0</code> and <code>-t != 0</code>), infer
 * the inequality <code>t-1 >= 0</code>. This kind of inference exists as a
 * separate rule to keep certificates more compact.
 */
case class DirectStrengthenInference(inequality : CertInequality,
                                     equation : CertNegEquation,
                                     result : CertInequality,
                                     order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](inequality, equation)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(DirectStrengthenInference.AC,
                   !inequality.isFalse && !equation.isTrue &&
                   !result.isTrue &&
                   (Set(equation.lhs, -equation.lhs) contains inequality.lhs) &&
                   result.lhs == {
                     implicit val o = order
                     inequality.lhs - 1
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint
  
  override def toString : String =
    "DirectStrengthen((" + inequality + "), (" + equation + ") -> " + result + ")"
}


////////////////////////////////////////////////////////////////////////////////

object DivRightInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * An inference that turns a universally quantified divisibility constraint into
 * an existentially quantified conjunction of inequalities and an equation.
 */
case class DivRightInference(divisibility : CertCompoundFormula,
                             result : CertCompoundFormula, order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](divisibility)
  val providedFormulas = Set[CertFormula](result)

} with BranchInference {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(DivRightInference.AC,
                   divisibility.f.isNonDivisibility && {
                      implicit val o = order
                      val divTerm = divisibility.f.arithConj.negativeEqs(0)
                      val divCoeff = (divTerm(0) _1)
                      
                      result.f == exists(exists(
                        (divTerm + v(1) === 0) & (v(1) > 0) & (v(1) < divCoeff)))
                    })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint
  
  override def toString : String =
    "DivRight(" + divisibility +" -> " + result + ")"
}


////////////////////////////////////////////////////////////////////////////////

object PredUnifyInference {
  private val AC = Debug.AC_CERTIFICATES
}

/**
 * An inference describing the unification of two predicates, producing a
 * system of equations (in the succedent) that express the unification
 * conditions: the predicate arguments are matched pair-wise
 */
case class PredUnifyInference(leftAtom : Atom, rightAtom : Atom,
                              result : CertFormula, order : TermOrder)
           extends {

  val assumedFormulas = Set[CertFormula](CertPredLiteral(false, leftAtom),
                                         CertPredLiteral(true, rightAtom))
  val providedFormulas = Set[CertFormula](!result)

} with BranchInference {
  
  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(PredUnifyInference.AC, {
                     implicit val o = order
                     leftAtom.pred == rightAtom.pred &&
                     result.toConj ==
                       eqConj2Conj((for ((l, r) <- leftAtom.iterator zip rightAtom.iterator)
                                    yield (l - r)).toList === 0)
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
             
  def propagateConstraint(closingConstraint : Conjunction) = closingConstraint
  
  override def toString : String =
    "PredUnify(" + leftAtom + ", " + rightAtom + " -> " +
                   providedFormulas.iterator.next + ")"
}
