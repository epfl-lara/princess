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

import ap.proof.tree.{ProofTree, ProofTreeFactory}
import ap.parameters.Param
import ap.util.Debug

/**
 * Meta-Task for updating all tasks of a goal
 */
case object UpdateTasksTask extends EagerTask {

  private val AC = Debug.AC_GOAL

  def apply(goal : Goal, ptf : ProofTreeFactory) : ProofTree = {
    val oldTasks = goal.tasks
    
    // we might have to remove ourself from the task-manager
    val remTasks = if (oldTasks.max == this)
                     oldTasks.removeFirst
                   else
                     oldTasks
    
    def stopUpdating(task : Task) = task match {
      case _ : AddFactsTask => true
      case WrappedFormulaTask(_, simpTasks) => simpTasks exists {
        case _ : AddFactsTask => true
        case _ => false
      }
      case _ => false
    }
    
    val newTasks = remTasks.updateTasks(goal, stopUpdating _)
    
    ptf.updateGoal(newTasks, goal)
  }

}
