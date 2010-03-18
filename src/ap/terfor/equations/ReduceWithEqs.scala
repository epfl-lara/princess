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

package ap.terfor.equations;

import ap.terfor.linearcombination.{LinearCombination, LCBlender}
import ap.terfor.inequalities.InEqConj
import ap.terfor.arithconj.ArithConj
import ap.terfor.preds.{Atom, PredConj}
import ap.terfor.substitutions.VariableShiftSubst
import ap.basetypes.IdealInt
import ap.util.{Debug, Logic, Seqs, UnionMap, LazyMappedMap}

import scala.collection.mutable.{Buffer, ArrayBuffer}

/**
 * Reduce a term (currently: a linear combination) by rewriting with equations.
 * The equations have to be given in form of a mapping from atomic terms
 * (constants or variables) to linear combinations
 */
object ReduceWithEqs {

  private val AC = Debug.AC_PROPAGATION

  def apply(eqs : scala.collection.Map[Term, LinearCombination], order : TermOrder)
                                                  : ReduceWithEqs =
    new ReduceWithEqs (eqs, order)

  def apply(eqs : EquationConj, order : TermOrder) : ReduceWithEqs = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(AC, eqs isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    ReduceWithEqs(eqs.toMap, order)
  }
}

/**
 * Reduce a term (currently: a linear combination) by rewriting with equations.
 * The equations have to be given in form of a mapping from atomic terms
 * (constants or variables) to linear combinations
 */
class ReduceWithEqs private (equations : scala.collection.Map[Term, LinearCombination],
                             order : TermOrder) {

  //////////////////////////////////////////////////////////////////////////////
  Debug.assertCtor(ReduceWithEqs.AC,
                   Logic.forall(for (t <- equations.keys)
                                yield (t.isInstanceOf[ConstantTerm] ||
                                       t.isInstanceOf[VariableTerm])))
  //////////////////////////////////////////////////////////////////////////////

  def isEmpty : Boolean = equations.isEmpty
  
  def addEquations(furtherEqs : scala.collection.Map[Term, LinearCombination])
                                     : ReduceWithEqs = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC,
                    Seqs.disjoint(equations.keySet, furtherEqs.keySet))
    ////////////////////////////////////////////////////////////////////////////
    
    if (furtherEqs.isEmpty)
      this
    else
      ReduceWithEqs(UnionMap(equations, furtherEqs), order)
  }

  /**
   * Create a <code>ReduceWithEqs</code> that can be used underneath
   * <code>num</code> binders. The conversion of de Brujin-variables is done on
   * the fly, which should give a good performance when the resulting
   * <code>ReduceWithEqs</code> is not applied too often (TODO: caching)
   */
  def passQuantifiers(num : Int) : ReduceWithEqs =
    ReduceWithEqs(new LazyMappedMap(
                        equations,
                        VariableShiftSubst.upShifter[Term](num, order),
                        VariableShiftSubst.downShifter[Term](num, order),
                        VariableShiftSubst.upShifter[LinearCombination](num, order)),
                  order)

  def apply(lc : LinearCombination) : LinearCombination = apply(lc, null)

  def apply(lc : LinearCombination,
            terms : Buffer[(IdealInt, LinearCombination)]) : LinearCombination = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, lc isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////

    val blender = new LCBlender (order)
    blender += (IdealInt.ONE, lc)
    val changed = runBlender(blender, terms)
    
    if (changed) blender.result else lc
  }

  /**
   * Run the <code>blender</code> and add linear combinations from
   * <code>eqs</code> whenever it is possible to reduce monomials.
   * <code>true</code> is returned in case any reduction was performed. If the
   * argument <code>terms</code> is given a non-null value, then all terms and
   * coefficients given to the blender will be logged
   */
  private def runBlender(blender : LCBlender,
                         terms : Buffer[(IdealInt, LinearCombination)]) : Boolean = {
    var changed : Boolean = false
    while (blender.hasNext) {
      val (nextCoeff, nextTerm) = blender.peekNext
                 
      (equations get nextTerm) match {
      case Some(eq) => {
        ////////////////////////////////////////////////////////////////////////
        Debug.assertPre(ReduceWithEqs.AC,
                        eq.leadingTerm == nextTerm && (eq isSortedBy order))
        ////////////////////////////////////////////////////////////////////////
                       
        val (quot, rem) = (nextCoeff reduceAbs eq.leadingCoeff)
        if (rem != nextCoeff) {
          blender += (-quot, eq)
          if (terms != null)
            terms += (-quot, eq)
          changed = true
        }
                   
        if (blender.hasNext && (blender.peekNext _2) == nextTerm) blender.next
      }

      case None => blender.next
      }
    }
    
    changed
  }

  /**
   * Run the <code>blender</code> and add linear combinations from
   * <code>eqs</code> whenever it is possible to reduce monomials.
   * <code>true</code> is returned in case any reduction was performed.
   */
  private def runBlender(blender : LCBlender) : Boolean = runBlender(blender, null)

  /**
   * Same as <code>apply(lc:LinearCombination)</code>, but also multiply
   * <cocde>lc</code> with integers in case this allows to eliminate the leading
   * term (pseudo-division). It is ensured that the resulting
   * <code>LinearCombination</code> has a positive leading coefficient
   */
  def pseudoReduce(lc : LinearCombination) : LinearCombination = {
    var curLC = apply(lc)
    
    while (!curLC.isZero && (equations contains curLC.leadingTerm)) {
      val eq = equations(curLC.leadingTerm)
      val curLCCoeff = curLC.leadingCoeff
      val eqCoeff = eq.leadingCoeff
      
      //////////////////////////////////////////////////////////////////////////
      Debug.assertInt(ReduceWithEqs.AC,
                      eq.leadingTerm == curLC.leadingTerm &&
                      !(eqCoeff divides curLCCoeff))
      //////////////////////////////////////////////////////////////////////////

      val lcGcd = curLCCoeff gcd eqCoeff
      
      val blender = new LCBlender (order)
      blender ++= Array((eqCoeff / lcGcd, curLC), (-curLCCoeff / lcGcd, eq))
      runBlender(blender)
                        
      //////////////////////////////////////////////////////////////////////////
      Debug.assertInt(ReduceWithEqs.AC,
                      { val res = blender.result
                        res.isZero ||
                          order.compare(res.leadingTerm, curLC.leadingTerm) < 0 })
      //////////////////////////////////////////////////////////////////////////

      curLC = blender.result
    }
    
    if (curLC.isZero) {
      LinearCombination.ZERO
    } else if (curLC.leadingCoeff.signum < 0) {
      // when the leading coefficient of the <code>LinearCombination</code> is
      // made positive, it might be possible to apply further reductions
      val blender = new LCBlender (order)
      blender += (IdealInt.MINUS_ONE, curLC)
      runBlender(blender)
      blender.result
    } else {
      curLC
    }
  }

  /**
   * Reduce the given linear combination and ensure that the leading coefficient
   * is non-negative. All terms added to <code>lc</code> are added to the buffer
   * <code>terms</code> (with respect to the positive, non-negated
   * <code>lc</code>).
   */
  private def reduceAndMakePositive(lc : LinearCombination,
                                    terms : Buffer[(IdealInt, LinearCombination)])
              : LinearCombination = {
    val curLC = apply(lc, terms)
    
    if (curLC.isZero) {
      LinearCombination.ZERO
    } else if (curLC.leadingCoeff.signum < 0) {
      // when the leading coefficient of the <code>LinearCombination</code> is
      // made positive, it might be possible to apply further reductions
      val negTerms = if (terms == null)
                       null
                     else
                       new ArrayBuffer[(IdealInt, LinearCombination)]

      val blender = new LCBlender (order)
      blender += (IdealInt.MINUS_ONE, curLC)
      runBlender(blender, negTerms)
      
      if (terms != null)
        terms ++= (for ((c, t) <- negTerms.elements) yield (-c, t))
      blender.result
    } else {
      curLC
    }
  }
  
  private lazy val keySet = equations.keySet
  
  private def reductionPossible(t : TerFor) : Boolean = {
    val keys = keySet
    !Seqs.disjoint(t.constants.asInstanceOf[Set[Term]], keys) ||
    !Seqs.disjoint(t.variables.asInstanceOf[Set[Term]], keys)
  }
  
  def apply(conj : EquationConj) : EquationConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////

    val res = if (reductionPossible(conj))
                conj.updateEqs(EquationConj(conj.elements, this, order))(order)
              else
                conj

    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPost(ReduceWithEqs.AC, isCompletelyReduced(res))
    ////////////////////////////////////////////////////////////////////////////
    res
  }

  //////////////////////////////////////////////////////////////////////////////
  // Some helper functions for the computation logging
  
  private def createTermBuffer(logger : ComputationLogger)
                              : ArrayBuffer[(IdealInt, LinearCombination)] =
    if (logger.isLogging)
      new ArrayBuffer[(IdealInt, LinearCombination)]
    else
      null
    
  private def logReduction(terms : ArrayBuffer[(IdealInt, LinearCombination)],
                           oriLC : LinearCombination, newLC : LinearCombination,
                           formulaCtor : (LinearCombination) => Formula,
                           logger : ComputationLogger,
                           order : TermOrder) : LinearCombination = {
    if (terms != null && !terms.isEmpty) {
      val oriFor = ArithConj.conj(formulaCtor(oriLC), order)
      val newFor = ArithConj.conj(formulaCtor(newLC), order)
      if (!newFor.isTrue)
        logger.reduceArithFormula(terms, oriFor, newFor, order)
      terms.clear
    }
    newLC
  }

  //////////////////////////////////////////////////////////////////////////////

  def apply(conj : NegEquationConj) : NegEquationConj =
    apply(conj, ComputationLogger.NonLogger)

  def apply(conj : NegEquationConj, logger : ComputationLogger) : NegEquationConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    
    val terms = createTermBuffer(logger)
    val res = if (reductionPossible(conj))
                conj.updateEqs(for (lc <- conj) yield {
                                 logReduction(terms, lc,
                                              reduceAndMakePositive(lc, terms),
                                              NegEquationConj(_, order),
                                              logger, order)
                               })(order)
              else
                conj
    
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPost(ReduceWithEqs.AC, isCompletelyReduced(res))
    ////////////////////////////////////////////////////////////////////////////
    res
  }

  def apply(conj : InEqConj) : InEqConj = apply(conj, ComputationLogger.NonLogger)

  def apply(conj : InEqConj, logger : ComputationLogger) : InEqConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    
    val terms = createTermBuffer(logger)
    val res = if (reductionPossible(conj))
                conj.updateGeqZero(for (lc <- conj) yield {
                                     logReduction(terms, lc,
                                                  apply(lc, terms),
                                                  InEqConj(_, order),
                                                  logger, order)
                                   }, logger)(order)
              else
                conj

    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPost(ReduceWithEqs.AC, isCompletelyReduced(res))
    ////////////////////////////////////////////////////////////////////////////
    res
  }

  private def apply(a : Atom, positive : Boolean,
                    logger : ComputationLogger) : Atom = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, a isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    
    val res =
      if (a.isEmpty) {
        // no arguments
        a
        
      } else if (logger.isLogging) {
        
        val argModifiers = new ArrayBuffer[Seq[(IdealInt, LinearCombination)]]
        val terms = createTermBuffer(logger)
        var changed = false

        val newArgs = for (lc <- a) yield {
          val newArg = apply(lc, terms)
          
          argModifiers += terms.toArray
          if (!terms.isEmpty)
            changed = true
          terms.clear
          
          newArg
        }
        
        if (changed) {
          val newAtom = a.updateArgs(newArgs)(order)
          val oldLit = if (positive)
                         PredConj(List(a), List(), order)
                       else
                         PredConj(List(), List(a), order)
          val newLit = if (positive)
                         PredConj(List(newAtom), List(), order)
                       else
                         PredConj(List(), List(newAtom), order)
          logger.reducePredFormula(argModifiers, oldLit, newLit, order)
          newAtom
        } else {
          a
        }
        
      } else {
        a.updateArgs(for (lc <- a) yield apply(lc))(order)
      }

    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPost(ReduceWithEqs.AC, isCompletelyReduced(res))
    ////////////////////////////////////////////////////////////////////////////
    res
  }

  def apply(conj : PredConj) : PredConj = apply(conj, ComputationLogger.NonLogger)

  def apply(conj : PredConj, logger : ComputationLogger) : PredConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    if (reductionPossible(conj)) {
      conj.updateLits(for (a <- conj.positiveLits) yield apply(a, true, logger),
                      for (a <- conj.negativeLits) yield apply(a, false, logger),
                      logger
                     )(order)
    } else {
      conj
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  private def isCompletelyReduced(lcs : Iterable[LinearCombination]) : Boolean =
    Logic.forall(for (lc <- lcs.elements) yield
                 Logic.forall(for ((c, t) <- lc.elements) yield (
                                equations.get(t) match {
                                case Some(eq) => c isAbsMinMod eq.leadingCoeff
                                case None => true
                                })))
  //////////////////////////////////////////////////////////////////////////////
    
  // pseudo-reduction on conjunctions of inequations
  // (disabled for the time being, it is not clear whether this
  // is a good idea)
  private def applyXX(conj : NegEquationConj) : NegEquationConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithEqs.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    
    val res = NegEquationConj(for (lc <- conj.elements)
                              yield pseudoReduce(lc), order)
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPost(ReduceWithEqs.AC,
      res.isFalse ||
      Logic.forall(for (lc <- res.elements) yield
                   (!(equations contains lc.leadingTerm) &&
                    Logic.forall(for ((c, t) <- lc.elements) yield (
                                   equations.get(t) match {
                                   case Some(eq) => c isAbsMinMod eq.leadingCoeff
                                   case None => true
                                   })))))
    ////////////////////////////////////////////////////////////////////////////
    res
  }
  
}