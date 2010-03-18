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

package ap.terfor.conjunctions;

import scala.collection.mutable.ArrayBuffer

import ap.basetypes.IdealInt
import ap.terfor.{TerFor, Term, Formula, ConstantTerm, TermOrder}
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.equations.{EquationConj, NegEquationConj}
import ap.terfor.arithconj.{ArithConj, ModelFinder}
import ap.terfor.inequalities.InEqConj
import ap.terfor.substitutions.Substitution
import ap.util.{Logic, Debug, Seqs, FilterIt}

object ConjunctEliminator {
  
  val AC = Debug.AC_ELIM_CONJUNCTS
  
}

/**
 * Class for removing irrelevant conjuncts from a conjunction
 * (like equations that have been applied to all other formulas)
 */
abstract class ConjunctEliminator(oriConj : Conjunction,
                                  // symbols that are universally quantified on
                                  // the innermost level
                                  universalSymbols : Set[Term],
                                  order : TermOrder) {
  
  private var conj = oriConj

  //////////////////////////////////////////////////////////////////////////////
  // we only know how to eliminate constants and variables
  Debug.assertCtor(ConjunctEliminator.AC,
                   Logic.forall(for (t <- universalSymbols.elements)
                                yield (t match {
                                  case _ : ConstantTerm => true
                                  case _ : VariableTerm => true
                                  case _ => false
                                })))
  //////////////////////////////////////////////////////////////////////////////
  
  private def occursIn(f : TerFor, c : Term) = c match {
    case c : ConstantTerm => f.constants contains c
    case v : VariableTerm => f.variables contains v
  }
  
  private def occursInPositiveEqs(c : Term) =
    occursIn(conj.arithConj.positiveEqs, c)

  private def occursInNegativeEqs(c : Term) =
    occursIn(conj.arithConj.negativeEqs, c)

  private def occursInInEqs(c : Term) =
    occursIn(conj.arithConj.inEqs, c)

  private def occursInPreds(c : Term) =
    occursIn(conj.predConj, c)

  private def containsEliminatedSymbols(f : TerFor) =
    !Seqs.disjointSeq(universalSymbols, f.constants) ||
    !Seqs.disjointSeq(universalSymbols, f.variables)
  
  /**
   * Called when a formula was eliminated that does not contain universal
   * symbols
   */
  protected def nonUniversalElimination(f : Conjunction)      
  
  /**
   * Called when formulas were eliminated that contained the universal symbol
   * <code>eliminatedSymbol</code> (which so far can only be a constant).
   * A method is provided for
   * constructing an assignment for <code>eliminatedSymbol</code> that satifies
   * all eliminated formulas, given any partial assignment of values to other
   * symbols (this is the justification why the formulas can be eliminated).
   */
  protected def universalElimination(eliminatedSymbol : ConstantTerm,
                                     witness : (Substitution, TermOrder) => Substitution)

  ////////////////////////////////////////////////////////////////////////////
  // Positive equations
    
  private def eliminablePositiveEqs(c : Term) : Boolean = {
    var occurred : Boolean = false
    val lcIt = conj.arithConj.positiveEqs.elements
    while (lcIt.hasNext) {
      val lc = lcIt.next
      if (occursIn(lc, c)) {
        // the constant must occur in at most one equation
        if (occurred) return false
        occurred = true

        // and the coefficient must be at most one
        if ((lc get c).abs > IdealInt.ONE) return false
      }
    }
    
    true
  }

  private def eliminablePositiveEqsNonU(c : Term) : Boolean = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ConjunctEliminator.AC, !(universalSymbols contains c))
    ////////////////////////////////////////////////////////////////////////////
    // we do not remove the equation if c is not an eliminated constant, but
    // the equation contains other eliminated constants;
    // there are chances that we can remove the equation completely later
    !Logic.exists(for (lc <- conj.arithConj.positiveEqs.elements)
                  yield (occursIn(lc, c) && containsEliminatedSymbols(lc)))
  }

  private def elimPositiveEqs(c : Term) : Unit = {
    val oriEqs = conj.arithConj.positiveEqs
    val remainingEqs = new ArrayBuffer[LinearCombination]
   
    for (lc <- oriEqs)
      if (occursIn(lc, c)) {        
        if (universalSymbols contains c) {
          c match {
            case c : ConstantTerm => {
              // then we can just ignore the equation; we describe how to compute
              // a witness for the eliminated constant c
              val modelFinder =
                new ModelFinder (ArithConj.conj(EquationConj(lc, order), order), c)
              universalElimination(c, modelFinder)
            }
            case _ : VariableTerm => // nothing
          }
        } else {
          // the equation can directly be moved to the constraint
          nonUniversalElimination(Conjunction.conj(NegEquationConj(lc, order),
                                                   order))
        }
      } else {
        remainingEqs += lc
      }

    conj = conj.updatePositiveEqs(oriEqs.updateEqsSubset(remainingEqs)(order))(order)
  }
  
  //////////////////////////////////////////////////////////////////////////////
  // Eliminated constants that occur in negative equations. The result are the
  // eliminated equations
    
  private def elimNegativeEqsU(c : Term) : NegEquationConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ConjunctEliminator.AC, universalSymbols contains c)
    ////////////////////////////////////////////////////////////////////////////
    val eqs = conj.arithConj.negativeEqs
    
    val (eliminatedEqs, remainingEqs) =
      Seqs.split(eqs, occursIn(_ : LinearCombination, c))
    conj = conj.updateNegativeEqs(eqs.updateEqsSubset(remainingEqs)(order))(order)

    // we give back the eliminated equations
    eqs.updateEqsSubset(eliminatedEqs)(order)
  }
  
  //////////////////////////////////////////////////////////////////////////////
  // Eliminated constants that occur in inequalities

  private def onesidedInEqsU(c : Term) : Boolean = {
    // the coefficient of the constant must have the same sign in all inequalities
    
    var signum : Int = 0
    val lcIt = conj.arithConj.inEqs.elements
    while (lcIt.hasNext) {
      val lc = lcIt.next
      val newSignum = (lc get c).signum
      if (newSignum != 0) {
        if (signum * newSignum == -1) return false
        signum = newSignum
      }
    }
    
    true
  }

  private def elimOnesidedInEqsU(c : Term, logger : ComputationLogger) : InEqConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ConjunctEliminator.AC, universalSymbols contains c)
    ////////////////////////////////////////////////////////////////////////////
    val inEqs = conj.arithConj.inEqs
    
    val (eliminatedInEqs, remainingInEqs) =
      Seqs.split(inEqs, occursIn(_ : LinearCombination, c))
    conj = conj.updateInEqs(inEqs.updateGeqZeroSubset(remainingInEqs, logger)
                                                     (order))(order)

    // we give back the eliminated inequalities
    InEqConj(eliminatedInEqs, order)
  }
    
  //////////////////////////////////////////////////////////////////////////////
  // Non-eliminated constants that occur in negative equations

  private def eliminableNegativeEqs(c : Term) : Boolean =
    // we only move equations to the constraints if no eliminated
    // constants occur in any of them
    Logic.forall(for (lc <- conj.arithConj.negativeEqs.elements)
                 yield (!occursIn(lc, c) || !containsEliminatedSymbols(lc)))

  private def elimNegativeEqs(c : Term) : Unit = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ConjunctEliminator.AC, !(universalSymbols contains c))
    ////////////////////////////////////////////////////////////////////////////

    val (constraintEqs, remainingEqs) =
      Seqs.split(conj.arithConj.negativeEqs, occursIn(_ : LinearCombination, c))
      
    nonUniversalElimination(Conjunction.disj(for (lc <- constraintEqs.elements)
                                             yield EquationConj(lc, order),
                  order))
    conj = conj.updateNegativeEqs(NegEquationConj(remainingEqs, order))(order)
  }  

  //////////////////////////////////////////////////////////////////////////////
  // Determine best possible Fourier-Motzkin application
  
  private def bestExactShadow(inEqs : Seq[LinearCombination]) : Option[Term] = {
    val candidates =
      FilterIt(eliminationCandidates(conj),
               (c:Term) =>
                 (universalSymbols contains c) &&
                 (inEqs exists (occursIn(_, c))) &&
                 !occursInPreds(c) && !occursInPositiveEqs(c) && !occursInNegativeEqs(c))
    
    Seqs.minOption(candidates, (c:Term) => countFMInferences(inEqs, c))
  }
  
  /**
   * Count how many Fourier-Motzkin inferences are necessary to eliminate
   * the given term (more precisely, the number of additional inequalities
   * required). If exact elimination is not possible, <code>None</code>
   * is returned
   */
  private def countFMInferences(inEqs : Seq[LinearCombination],
                                c : Term) : Option[Int] = {
    // we check that either all lower or all upper bounds have the leading
    // coefficient one; otherwise, Fourier-Motzkin is not precise
    var lowerNonUnit : Boolean = false
    var upperNonUnit : Boolean = false
    var lowerCount : Int = 0
    var upperCount : Int = 0
    
    val lcIt = inEqs.elements
    while (lcIt.hasNext) {
      val lc = lcIt.next
      if (!lc.isEmpty) {
        val coeff = lc get c
        coeff.signum match {
          case 0 => // nothing
          case 1 => {
            lowerCount = lowerCount + 1
            if (coeff > IdealInt.ONE) lowerNonUnit = true
          }
          case -1 => {
            upperCount = upperCount + 1
            if (coeff < IdealInt.MINUS_ONE) upperNonUnit = true
          }
        }
        if (lowerNonUnit && upperNonUnit) return None
      }
    }
    
    Some(lowerCount * upperCount - lowerCount - upperCount)
  }

  //////////////////////////////////////////////////////////////////////////////
  // The main loop

  protected def eliminationCandidates(conj : Conjunction) : Iterator[Term]
  
  def eliminate(logger : ComputationLogger) : Conjunction = {
  var oldconj = conj
  do {
    oldconj = conj

    for (c <- eliminationCandidates(conj)) // otherwise we cannot do anything
                                           if (!occursInPreds(c)) {
      (occursInPositiveEqs(c),
       occursInNegativeEqs(c),
       occursInInEqs(c),
       universalSymbols contains c) match {

      case (false, false, false, _) => // nothing
      
      case (true, false, false, true) if (eliminablePositiveEqs(c))
          => elimPositiveEqs(c)
   
      case (true, false, false, false)
        if (eliminablePositiveEqsNonU(c) && eliminablePositiveEqs(c))
          => elimPositiveEqs(c)
 
      case (false, true, false, false) if (eliminableNegativeEqs(c))
          => elimNegativeEqs(c)

      case (false, _, _, true) if (onesidedInEqsU(c))
          => {
               val eliminatedFor = ArithConj.conj(Array(elimNegativeEqsU(c),
                                                        elimOnesidedInEqsU(c, logger)),
                                                  order)
               c match {
                 case c : ConstantTerm =>
                   universalElimination(c, new ModelFinder (eliminatedFor, c))
                 case _ : VariableTerm => // nothing
               }
             }
    
      case _ => // nothing
        
      }
    }
    
    if (oldconj == conj) {
      // check for possible Fourier-Motzkin eliminations
      
      def exactShadow(inEqs : Seq[LinearCombination]) : Seq[LinearCombination] =
        bestExactShadow(inEqs) match {
          case None => inEqs
          case Some(c) => {
            val (eliminated, remaining) = InEqConj.exactShadow(c, inEqs, logger, order)
            ////////////////////////////////////////////////////////////////////
            Debug.assertInt(ConjunctEliminator.AC,
                            remaining forall (!occursIn(_, c)))
            ////////////////////////////////////////////////////////////////////
            c match {
              case c : ConstantTerm => {
                val eliminatedFor = ArithConj.conj(InEqConj(eliminated, order), order)
                universalElimination(c, new ModelFinder (eliminatedFor, c))
              }
              case _ : VariableTerm => // nothing
            }
            exactShadow(remaining)
          }
        }

      val newInEqs =
        conj.arithConj.inEqs.updateGeqZero(exactShadow(conj.arithConj.inEqs),
                                           logger)(order)
      conj = conj.updateInEqs(newInEqs)(order)
    }
    
  } while (oldconj != conj)

  conj
  }
    
}