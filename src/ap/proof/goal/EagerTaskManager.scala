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

package ap.proof.goal;


object EagerTaskManager {

  import WrappedFormulaTask.{unwrapReal, MaybeWrapped}
  
  private def unwrapRealOption(npt : Option[PrioritisedTask]) = npt match {
    case Some(WrappedFormulaTask(realTask, _)) => Some(realTask)
    case _ => npt
  }
  
  /**
   * Abstract superclass for the task managers that are currently used (to
   * factor out common functionality)
   */
  private abstract class DefaultEagerTaskManager(recommendedTask : Option[EagerTask])
                         extends EagerTaskManager {
    def recommend(npt : Option[PrioritisedTask]) = npt match {
      case None =>
        recommendedTask
      case Some(MaybeWrapped(t)) if (recommendationNecessary(t)) =>
        recommendedTask
      case _ =>
        None
    }

    protected def recommendationNecessary(t : Task) : Boolean
  }
  
  /**
   * It is unknown whether the facts of the current goal are normalised
   */
  private object NonNormalisedFacts
                 extends DefaultEagerTaskManager(Some(FactsNormalisationTask)) {
    def afterTask(task : Task) = unwrapReal(task) match {
      case FactsNormalisationTask => NormalisedFacts
      case _ =>                      NonNormalisedFacts
    }
    protected def recommendationNecessary(t : Task) = t match {
      case _ : BetaFormulaTask |
           _ : ExQuantifierTask |
           _ : DivisibilityTask |
           _ : LazyMatchTask |
           _ : BoundStrengthenTask => true
      case _ => false
    }
  }

  /**
   * It is known that <code>FactsNormalisationTask</code> has been applied, the
   * facts of the current goal are normalised
   */
  private object NormalisedFacts
                 extends DefaultEagerTaskManager(Some(UpdateTasksTask)) {
    def afterTask(task : Task) = unwrapReal(task) match {
      case _ : AddFactsTask =>   NonNormalisedFacts
      case UpdateTasksTask =>    NormalisedFactsAndTasks 
      case _ =>                  NormalisedFacts
    }
    protected def recommendationNecessary(t : Task) = t match {
      case _ : BetaFormulaTask |
           _ : ExQuantifierTask |
           _ : DivisibilityTask |
           _ : LazyMatchTask => true
      case _ => false
    }
  }

  /**
   * It is known that <code>FactsNormalisationTask</code> has been applied, the
   * facts of the current goal are normalised, and also
   * <code>UpdateTasksTask</code> has been applied so that all tasks are
   * normalised
   */
  private object NormalisedFactsAndTasks
                 extends DefaultEagerTaskManager(Some(EagerMatchTask)) {
    def afterTask(task : Task) = unwrapReal(task) match {
      case _ : AddFactsTask =>   NonNormalisedFacts
      case EagerMatchTask =>     MatchedEagerClauses
      case _ =>                  NormalisedFacts
    }
    protected def recommendationNecessary(t : Task) = t match {
      case _ : BetaFormulaTask |
           _ : ExQuantifierTask |
           _ : LazyMatchTask => true
      case _ => false
    }
  }

  /**
   * It is known that <code>FactsNormalisationTask</code> has been applied, the
   * facts of the current goal are normalised, and also
   * <code>UpdateTasksTask</code> has been applied so that all tasks are
   * normalised. In addition, eagerly matched clauses have been instantiated.
   */
  private object MatchedEagerClauses
                 extends DefaultEagerTaskManager(Some(EliminateFactsTask)) {
    def afterTask(task : Task) = unwrapReal(task) match {
      case FactsNormalisationTask | EliminateFactsTask => ReducedFacts
      case _ : AddFactsTask =>                            NonNormalisedFacts
      case _ : UpdateConstantFreedomTask =>               NormalisedFacts
      case _ =>                                           MatchedEagerClauses
    }
    
    override def recommend(npt : Option[PrioritisedTask]) = npt match {
      // it is not meaningful to apply <code>EliminateFactsTask<code> in this
      // special case
      case Some(WrappedFormulaTask(_ : BetaFormulaTask, simpTasks))
           if (simpTasks exists (!recommendationNecessary(_))) =>
        None
      case _ => super.recommend(npt)
    }

    protected def recommendationNecessary(t : Task) = t match {
      case _ : BetaFormulaTask |
           _ : ExQuantifierTask |
           _ : LazyMatchTask => true
      case _ => false
    }
  }
  
  /**
   * It is known that <code>FactsNormalisationTask</code> and
   * <code>EliminateFactsTask<code> have been applied, but it could have
   * happened that afterwards constants have disappeared from the tasks in the
   * queue, so that further eliminations might be possible
   */
  private object ProbablyReducedFacts
                 extends DefaultEagerTaskManager(Some(EliminateFactsTask)) {
    def afterTask(task : Task) = unwrapReal(task) match {
      case FactsNormalisationTask | EliminateFactsTask => ReducedFacts
      case _ : AddFactsTask =>                            NonNormalisedFacts
      case _ : UpdateConstantFreedomTask =>               NormalisedFacts
      // all other tasks could result in the disappearance of constants in
      // the task queue, which could make it necessary to apply
      // <code>EliminateFactsTask</code> again
      case _ =>                                           ProbablyReducedFacts
    }
    protected def recommendationNecessary(t : Task) = t match {
      case _ : ExQuantifierTask |
           _ : DivisibilityTask => true
      case _ => false
    }
  }

  /**
   * It is known that both <code>FactsNormalisationTask</code> and
   * <code>EliminateFactsTask<code> have been applied, the
   * facts of the current goal are normalised and unnecessary facts have been
   * removed (with respect to the current tasks)
   */
  private object ReducedFacts
                 extends DefaultEagerTaskManager(Some(OmegaTask)) {
    def afterTask(task : Task) = unwrapReal(task) match {
      case FactsNormalisationTask | EliminateFactsTask => ReducedFacts
      case _ : AddFactsTask =>                            NonNormalisedFacts
      case _ : UpdateConstantFreedomTask =>               NormalisedFacts
      case OmegaTask =>                                   Final
      // all other tasks could result in the disappearance of constants in
      // the task queue, which could make it necessary to apply
      // <code>EliminateFactsTask</code> again
      case _ =>                                           ProbablyReducedFacts
    }
    protected def recommendationNecessary(t : Task) = false
  }

  /**
   * The final state in where there is nothing left to do. This state can be
   * reached by applying the task <code>OmegaTask</code>. In case that actually an
   * equation was split, this will lead to <code>AddFactsTask</code>, and the
   * state will be left again immediately
   */
  private object Final extends EagerTaskManager {
    def afterTask(task : Task) = unwrapReal(task) match {
      case FactsNormalisationTask | EliminateFactsTask => ReducedFacts
      case OmegaTask =>                                   Final
      case _ : AddFactsTask =>                            NonNormalisedFacts
      case _ : UpdateConstantFreedomTask =>               NormalisedFacts
      // all other tasks could result in the disappearance of constants in
      // the task queue, which could make it necessary to apply
      // <code>EliminateFactsTask</code> again
      case _ =>                                           ProbablyReducedFacts
    }
    def recommend(npt : Option[PrioritisedTask]) = None    
  }
   
  /**
   * In the beginning, there are no facts, which are thus reduced
   */
  def INITIAL : EagerTaskManager = Final
}


/**
 * A class for tracking the application of tasks and recommending the
 * intermediate application of <code>EagerTask</code>s. This class is
 * implemented as a finite automaton to give recommendations based on the
 * history of task applications
 */
abstract class EagerTaskManager {

  /**
   * Tell the manager that a certain task was applied
   */
  def afterTask(task : Task) : EagerTaskManager

  /**
   * Obtain a recommendation from the manager, given the next
   * <code>PrioritisedTask</code> in the queue. If the queue is empty,
   * <code>None</code> should be given as argument
   */
  def recommend(nextPrioritisedTask : Option[PrioritisedTask]) : Option[EagerTask]
  
}