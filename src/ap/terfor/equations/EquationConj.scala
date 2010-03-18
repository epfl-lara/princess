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

import scala.collection.mutable.{PriorityQueue, ArrayBuffer}

import ap.terfor.linearcombination.LinearCombination
import ap.basetypes.IdealInt
import ap.util.{Debug, Logic, FilterIt, Seqs}

object EquationConj {
  
  val AC = Debug.AC_EQUATIONS

  /**
   * Create an equation conjunction from an arbitrary set of equations
   * (left-hand sides).
   */
  def apply(lhss : Iterator[LinearCombination],
            logger : ComputationLogger,
            order : TermOrder) : EquationConj =
    apply(lhss,
          ReduceWithEqs(Map.empty.asInstanceOf[Map[Term, LinearCombination]], order),
          logger,
          order)

  def apply(lhss : Iterator[LinearCombination], order : TermOrder) : EquationConj =
    apply(lhss, ComputationLogger.NonLogger, order)

  /**
   * Create an equation conjunction from an arbitrary set of equations
   * (left-hand sides), module another set of equations
   */
  def apply(lhss : Iterator[LinearCombination],
            modEquations : ReduceWithEqs,
            logger : ComputationLogger,
            order : TermOrder) : EquationConj =
    try {
      new RowSolver(lhss, modEquations, logger, order).result
    } catch {
      case `UNSATISFIABLE_CONJUNCTION_EXCEPTION` => FALSE
    }

  def apply(lhss : Iterator[LinearCombination],
            modEquations : ReduceWithEqs,
            order : TermOrder) : EquationConj =
    apply(lhss, modEquations, ComputationLogger.NonLogger, order)

  /**
   * Create an equation conjunction from an arbitrary set of equations
   * (left-hand sides).
   */
  def apply(lhss : Iterable[LinearCombination], order : TermOrder) : EquationConj =
    lhss match {
      case lhss : Collection[LinearCombination] if (lhss.isEmpty) =>
        TRUE
      case lhss : Collection[LinearCombination] if (lhss.size == 1) =>
        apply(lhss.elements.next, order)
      case _ => 
        apply(lhss.elements,
              ReduceWithEqs(Map.empty.asInstanceOf[Map[Term, LinearCombination]],
                            order),
              order)
    }

  def apply(lhss : Iterable[LinearCombination],
            modEquations : ReduceWithEqs,
            order : TermOrder) : EquationConj =
    apply(lhss.elements, modEquations, order)

  def apply(lhs : LinearCombination, order : TermOrder) : EquationConj =
    if (lhs.isZero)
      TRUE
    else if (lhs.isNonZero)
      FALSE
    else
      new EquationConj(Array(lhs.makePrimitiveAndPositive), order)
    
  /**
   * Create an equation conjunction from a canonised, reduced and sorted set
   * of equations (left-hand sides).
   */
  def createFromReducedSeq(lhss : Seq[LinearCombination], order : TermOrder)
                                                          : EquationConj =
    new EquationConj(lhss.toArray, order)
  
  val TRUE : EquationConj = new EquationConj (Array(), TermOrder.EMPTY)
  
  val FALSE : EquationConj = new EquationConj (Array(LinearCombination.ONE),
                                               TermOrder.EMPTY)
                                               
  /**
   * Compute the conjunction of a number of systems of equations.
   * TODO: This could be optimised much more.
   */
  def conj(conjs : Iterator[EquationConj],
           logger : ComputationLogger,
           order : TermOrder) : EquationConj =
    Formula.conj(conjs, TRUE, (nonTrivialConjs:RandomAccessSeq[EquationConj]) => {
                   /////////////////////////////////////////////////////////////
                   Debug.assertPre(AC, Logic.forall(for (c <- nonTrivialConjs.elements)
                                                    yield (c isSortedBy order)))
                   /////////////////////////////////////////////////////////////
                   apply(for (c <- nonTrivialConjs.elements; lhs <- c.elements)
                           yield lhs,
                         logger,
                         order)
                 } )

  def conj(conjs : Iterable[EquationConj], logger : ComputationLogger,
           order : TermOrder) : EquationConj =
    conj(conjs.elements, logger, order)

  def conj(conjs : Iterator[EquationConj], order : TermOrder) : EquationConj =
    conj(conjs, ComputationLogger.NonLogger, order)

  def conj(conjs : Iterable[EquationConj], order : TermOrder) : EquationConj =
    conj(conjs.elements, ComputationLogger.NonLogger, order)
  
}

/**
 * The class for representing conjunctions of equations, i.e., of systems of
 * equations. Systems of equations are always implicitly canonised and reduced
 * by means of row operations, i.e., it is ensured that the leading terms of
 * two equations are always distinct, and that no equation can be made smaller
 * in the used <code>TermOrder</code> by adding multiples of other equations.
 * This is not a complete method for deciding the satisfiability of a system, it
 * is also necessary to perform column operations. Column operations are not
 * applied implicitly, however.
 */
class EquationConj private (_lhss : Array[LinearCombination],
                            _order : TermOrder)
      extends EquationSet(_lhss, _order) with SortedWithOrder[EquationConj] {

  //////////////////////////////////////////////////////////////////////////////
  // no two equations must have the same leading term (otherwise the conjunction
  // of equations is not properly normalised)
  Debug.assertCtor(EquationConj.AC,
                   Logic.forall(0, this.size - 1,
                                (i:Int) => order.compare(this(i).leadingTerm,
                                                         this(i+1).leadingTerm) > 0))
  //////////////////////////////////////////////////////////////////////////////

  def sortBy(newOrder : TermOrder) : EquationConj = {
    if (isSortedBy(newOrder))
      this
    else
      EquationConj(for (lc <- this.elements) yield lc.sortBy(newOrder),
                   newOrder)
  }

  //////////////////////////////////////////////////////////////////////////////

  lazy val toMap : scala.collection.Map[Term, LinearCombination] = {
    val res = new scala.collection.mutable.HashMap[Term, LinearCombination]
    res ++= (for (lc <- this.elements) yield (lc.leadingTerm, lc))
    res
  }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Update the equations of this conjunction; if nothing has changed,
   * <code>this</code> is returned
   */
  def updateEqs(newEqs : Seq[LinearCombination])(implicit newOrder : TermOrder)
               : EquationConj =
    if (Seqs.subSeq(newEqs.elements, this.elements)) {
      if (newEqs.size == this.size)
        this
      else
        new EquationConj (newEqs.toArray, newOrder)
    } else {
      EquationConj(newEqs, newOrder)
    }
  
  /**
   * Update the equations of this conjunction under the assumption that the
   * new equations form a subset of the old equations
   */
  def updateEqsSubset(newEqs : Seq[LinearCombination])(implicit newOrder : TermOrder)
                     : EquationConj = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(NegEquationConj.AC, Seqs.subSeq(newEqs.elements, this.elements))
    ////////////////////////////////////////////////////////////////////////////
    if (newEqs.size == this.size)
      this
    else
      new EquationConj (newEqs.toArray, newOrder)
  }

  //////////////////////////////////////////////////////////////////////////////

  def isTrue : Boolean = this.isEmpty

  /**
   * The only allowed case of obviously unsatisfiable systems of equations is
   * the one of a single equation that has a non-zero literal lhs
   */
  def isFalse : Boolean = (!isEmpty && this(0).isNonZero)

  /**
   * Create the negation of at most one equation
   */
  def negate : NegEquationConj = {
    if (this.isTrue) {
      NegEquationConj.FALSE
    } else {
      //////////////////////////////////////////////////////////////////////////
      Debug.assertPre(EquationConj.AC, this.size == 1)
      //////////////////////////////////////////////////////////////////////////
      NegEquationConj(this(0), order)
    }
  }
  
  protected val relationString : String = "="

  //////////////////////////////////////////////////////////////////////////////
  
  override def equals(that : Any) : Boolean = that match {
    case that : EquationConj => super.equals(that)
    case _ => false
  }

  override def hashCode = (super.hashCode + 26473671)
}


private object UNSATISFIABLE_CONJUNCTION_EXCEPTION extends Exception

/**
 * Class for solving/reducing a conjunction of equations using row operations
 * (adding/subtracting linear combinations from each other). If it is, during the
 * process of reduction, detected that the conjunction of equations is
 * unsatisfiable, the exception <code>UNSATISFIABLE_CONJUNCTION_EXCEPTION</code>
 * is raised
 */
private class RowSolver(lhss : Iterator[LinearCombination],
                        modEquations : ReduceWithEqs,
                        logger : ComputationLogger,
                        order : TermOrder) {

  /**
   * The left-hand sides are sorted by the leading term and worked off in this
   * order
   */
  private implicit def orderTodo(thisLC : LinearCombination)
                                                : Ordered[LinearCombination] =
    new Ordered[LinearCombination] {
            def compare(thatLC : LinearCombination) : Int =
        order.compare(thisLC.leadingTerm, thatLC.leadingTerm)
    }

  /**
   * The queue holding the left-hand sides that we still need to canonise
   * (there might be several linear combinations with the same leading term).
   * The queue is ordered by <code>orderTodo</code>
   */
  private val nonCanonLhss = new PriorityQueue[LinearCombination]

  private def checkNonZero(lhs : LinearCombination) : Unit =
    if (lhs.isNonZero) {
      logger.ceScope.finish(LinearCombination.ONE)
      throw UNSATISFIABLE_CONJUNCTION_EXCEPTION
    }
  
  /**
   * Add a further lhs to the todo-queue.
   * <code>UNSATISFIABLE_CONJUNCTION_EXCEPTION</code> is thrown
   * if it is detected that the conjunction of equations is unsatisfiable
   */
  private def addNonCanon(rawlhs : LinearCombination) : Unit = {
    // TODO: add logger
    val lhs = modEquations.pseudoReduce(rawlhs)
    if (!lhs.isZero) {
      checkNonZero(lhs)
      val primLhs = lhs.makePrimitiveAndPositive
      logger.ceScope.finish(primLhs)
      nonCanonLhss += primLhs
    }
  }
  
  /**
   * The buffer holding the canonical (but non-reduced) rows of the system
   * of equations, in descending order. It is guaranteed that the linear
   * combinations have pairwise distinct leading terms
   */
  private val nonRedLhss = new ArrayBuffer[LinearCombination]
   
  /**
   * Add a further canonical non-reduced lhs
   * <code>UNSATISFIABLE_CONJUNCTION_EXCEPTION</code> is thrown
   * if it is detected that the conjunction of equations is unsatisfiable
   */
  private def addNonReduced(lhs : LinearCombination) : Unit = {
    if (!lhs.isZero) {
      checkNonZero(lhs)
      val primLhs = lhs.makePrimitiveAndPositive
      //////////////////////////////////////////////////////////////////////////
      Debug.assertInt(EquationConj.AC,
                      nonRedLhss.isEmpty ||
                      order.compare(nonRedLhss.last.leadingTerm,
                                    primLhs.leadingTerm) > 0)
      //////////////////////////////////////////////////////////////////////////
      logger.ceScope.finish(primLhs)
      nonRedLhss += primLhs
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // The first phase: deriving a canonical set of linear combinations from the
  // input. In the resulting set, the leading terms of linear combinations are
  // pairwise distinct
   
  for (lhs <- lhss) {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(EquationConj.AC, lhs isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////    
    addNonCanon(lhs)
  }

  /**
   * Handle a set of linear combinations with the same leading term. The results
   * are put into the methods <code>addNonReduced</code>,
   * <code>addNonCanon</code>
   */
  private def canonise(currentLhss : ArrayBuffer[LinearCombination]) : Unit = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(EquationConj.AC, !currentLhss.isEmpty)
    ////////////////////////////////////////////////////////////////////////////

    if (currentLhss.size == 1)
      addNonReduced(currentLhss(0))
    else
      canoniseMultiple(currentLhss)
  }

  private def canoniseMultiple(currentLhss : ArrayBuffer[LinearCombination])
                                                                    : Unit = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(EquationConj.AC, currentLhss.size > 1)
    ////////////////////////////////////////////////////////////////////////////
    
    val (gcd, factors) =
      IdealInt.gcdAndCofactors(for (lc <- currentLhss) yield lc.leadingCoeff)
    val gcdLhs =
      LinearCombination.sum(factors.elements zip currentLhss.elements, order)

    if (logger.isLogging && Seqs.count(factors, (f:IdealInt) => !f.isZero) > 1) {
      val terms = for ((f, e) <- factors.toList zip currentLhss.toList; if !f.isZero)
                    yield (f, e)
      logger.ceScope.start((terms, order)) {
        addNonReduced(gcdLhs)
      }
    } else {
      addNonReduced(gcdLhs)
    }
        
    for (lc <- currentLhss) {
      // TODO: is it possible to leave out some of the produced linear
      // combinations?
      
      val combination = Array((IdealInt.ONE, lc), (-(lc.leadingCoeff / gcd), gcdLhs))
      val rem = LinearCombination.sum(combination, order)
      //////////////////////////////////////////////////////////////////////////
      Debug.assertInt(EquationConj.AC,
                      rem.isZero ||
                      order.compare(rem.leadingTerm,
                                    currentLhss(0).leadingTerm) < 0)
      //////////////////////////////////////////////////////////////////////////
      logger.ceScope.start((combination, order)) {
        addNonCanon(rem)
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////
    
  {
    // the main loop for canonising
    
    val currentLhss = new ArrayBuffer[LinearCombination]
    while (!nonCanonLhss.isEmpty) {
      val firstLhs = nonCanonLhss.dequeue
      val leadingTerm = firstLhs.leadingTerm
      currentLhss += firstLhs
    
      while (!nonCanonLhss.isEmpty &&
             nonCanonLhss.max.leadingTerm == leadingTerm)
        currentLhss += nonCanonLhss.dequeue

      canonise(currentLhss)
      currentLhss.clear
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // The second phase: ensure that each equation is reduced relatively to the
  // other equations. This won't change the number or the leading term of
  // left-hand sides. We start with the smallest linear combination and proceed
  // to the bigger ones

  /**
   * The buffer holding the reduced rows of the system
   * of equations, in descending order. It is guaranteed that the linear
   * combinations have pairwise distinct leading terms
   */
  private val redLhss = new Array[LinearCombination] (nonRedLhss.size)

  /**
   * The index of the next row to reduce
   */
  private var eqIndex : Int = nonRedLhss.size - 1
  
  /**
   * Add a further canonical non-reduced lhs
   * <code>UNSATISFIABLE_CONJUNCTION_EXCEPTION</code> is thrown
   * if it is detected that the conjunction of equations is unsatisfiable
   */
  private def addReduced(lhs : LinearCombination) : LinearCombination = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertInt(EquationConj.AC,
                    !lhs.isZero && eqIndex >= 0 && eqIndex < nonRedLhss.size)
    ////////////////////////////////////////////////////////////////////////////

    checkNonZero(lhs)
    val primLhs = lhs.makePrimitiveAndPositive
    
    //////////////////////////////////////////////////////////////////////////
    Debug.assertInt(EquationConj.AC,
                    eqIndex == redLhss.size - 1 ||
                      order.compare(redLhss(eqIndex+1).leadingTerm,
                                    primLhs.leadingTerm) < 0)
    //////////////////////////////////////////////////////////////////////////
    
    logger.ceScope.finish(primLhs)
    redLhss(eqIndex) = primLhs
    eqIndex = eqIndex - 1
    
    primLhs
  }

  //////////////////////////////////////////////////////////////////////////////

  {
    // The main loop for reducing the linear combinations. We continously build
    // a map that contains all the smaller rows that are already reduced, which
    // is used as input for <code>ReduceWithEqs</code>
    
    val lhsMap = new scala.collection.mutable.HashMap[Term, LinearCombination]
    
    // When logging computations, we have to extract the precise terms added
    // by the reducer
    val reducerTerms =
      if (logger.isLogging) new ArrayBuffer[(IdealInt, LinearCombination)] else null
    
    while (eqIndex >= 0) {
      val nextToReduce = nonRedLhss(eqIndex)
      val reduced = modEquations.addEquations(lhsMap)(nextToReduce, reducerTerms)
      val primAndReduced =
        if (logger.isLogging && reducerTerms.size > 0) {
          reducerTerms += (IdealInt.ONE, nextToReduce)
          logger.ceScope.start((reducerTerms, order)) { addReduced(reduced) }
        } else {
          addReduced(reduced)
        }
      lhsMap += (primAndReduced.leadingTerm -> primAndReduced)
      
      if (reducerTerms != null)
        reducerTerms.clear
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  val result = EquationConj.createFromReducedSeq(redLhss, order)
}