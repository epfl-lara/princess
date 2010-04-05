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

import ap.util.Debug
import ap.basetypes.{LeftistHeap, HeapCollector}
import ap.terfor.conjunctions.{Conjunction, Quantifier}

object TaskManager {
  
  private def AC = Debug.AC_GOAL
  
  private implicit def orderTask(thisTask : PrioritisedTask) : Ordered[PrioritisedTask] =
    new Ordered[PrioritisedTask] {
      def compare(thatTask : PrioritisedTask) : Int =
        (thisTask.priority compare thatTask.priority)
    }
  
  protected[goal] type TaskHeap = LeftistHeap[PrioritisedTask, TaskInfoCollector]
    
  //////////////////////////////////////////////////////////////////////////////
  
  private val EMPTY_HEAP : TaskHeap = LeftistHeap.EMPTY_HEAP(TaskInfoCollector.EMPTY)
    
  val EMPTY : TaskManager =
    new TaskManager (EMPTY_HEAP, EagerTaskManager.INITIAL)
  
}

/**
 * An immutable class (priority queue) for handling a set of tasks in a proof
 * goal. Currently, this is implemented using a sorted set, but it would be
 * better to use a real immutable queue (leftist heap?).
 *
 * TODO: So far, no real subsumption checks are performed
 */
class TaskManager private (// the regular tasks that have a priority
                           prioTasks : TaskManager.TaskHeap,
                           // Preprocessing tasks that can sneak in before
                           // regular tasks.
                           eagerTasks : EagerTaskManager) {

  def +(t : PrioritisedTask) = new TaskManager (prioTasks + t, eagerTasks)

  def ++ (elems: Iterable[PrioritisedTask]): TaskManager =
    this ++ elems.elements

  def ++ (elems: Iterator[PrioritisedTask]): TaskManager =
    if (elems.hasNext)
      new TaskManager (prioTasks insertIt elems, eagerTasks)
    else
      this

  def enqueue(elems: PrioritisedTask*): TaskManager = (this ++ elems.elements)

  /**
   * Remove the first task from the queue.
   */
  def removeFirst : TaskManager = {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertPre(TaskManager.AC, !isEmpty)
    //-END-ASSERTION-///////////////////////////////////////////////////////////

    nextEagerTask match {
      case None =>
        new TaskManager (prioTasks.deleteMin, eagerTasks afterTask prioTasks.findMin)
      case Some(task) =>
        new TaskManager (prioTasks, eagerTasks afterTask task)
    }
  }
  
  private val nextEagerTask : Option[EagerTask] =
    eagerTasks recommend prioTasks.findMinOption
  
  /** Returns the element with the smallest priority value in the queue,
   *  or throws an error if there is no element contained in the queue.
   *
   *  @return   the element with the smallest priority.
   */
  def max: Task = nextEagerTask getOrElse prioTasks.findMin

  /**
   * Compute information about the prioritised tasks (eager tasks are not
   * considered at this point)
   */
  def taskInfos : TaskInfoCollector = prioTasks.collector

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Update all <code>PrioritisedTask</code> stored by this managed, making
   * use of possibly new facts and information from the goal. The argument
   * <code>stopUpdating</code> can be used to tell at which point the updating
   * of tasks can be stopped, because some task or fact has been discovered that
   * can be used right away.
   */
  def updateTasks(goal : Goal,
                  stopUpdating : Task => Boolean) : TaskManager = {
    // TODO: make this more efficient by detecting more early whether updates
    // are meaningful
    
  //  print("" + prioTasks.size + " ... ")
 
    val newPrioTasks : TaskManager.TaskHeap = 
      try {
        val facts = new scala.collection.mutable.ArrayBuffer[Conjunction]
        
        val factCollector = (f : Conjunction) => if (f.isTrue)
                                                   throw TRUE_EXCEPTION
                                                 else
                                                   facts += f
        var foundFactsTask : Boolean = false
        
        def updateTask(prioTask : PrioritisedTask) : Iterator[PrioritisedTask] = {
          val res = prioTask.updateTask(goal, factCollector)
          if (res exists stopUpdating)
            foundFactsTask = true
          res.elements
        }
        
        val tasks = prioTasks.flatMap(updateTask _, (h) => foundFactsTask)
        if (facts.isEmpty)
          tasks
        else
          tasks ++ (goal formulaTasks Conjunction.disj(facts, goal.order))
      } catch {
        case TRUE_EXCEPTION =>
          TaskManager.EMPTY_HEAP ++ (goal formulaTasks Conjunction.TRUE)
      }
  //    println(newPrioTasks.size)
    
    new TaskManager (newPrioTasks, eagerTasks)
  }
   
  private object TRUE_EXCEPTION extends Exception
   
  //////////////////////////////////////////////////////////////////////////////

  def isEmpty : Boolean = prioTasks.isEmpty && nextEagerTask.isEmpty
    
  def prioritisedTasks : Iterable[PrioritisedTask] = prioTasks
  
  //////////////////////////////////////////////////////////////////////////////
/*
  def printSize(goal : Goal) = {
    print(prioTasks.size)
    print("\t")
    var num = 0
    var factsBefore = 0
    var factsAfter = 0
    for (t <- prioTasks.elements) {
      t match {
        case t : FormulaTask => {
          val newTasks = t updateTask goal
          num = num + newTasks.size
          factsAfter = factsAfter + (for (t <- newTasks; if (t.isInstanceOf[AddFactsTask])) yield t).size
        }
        case _ => num = num + 1
      }
      t match {
        case t : AddFactsTask => factsBefore = factsBefore + 1
        case _ => // nothing
      }
    }
    print(num)
    print("\t")
    print(factsBefore)
    print("\t")
    print(factsAfter)
    if (factsBefore != factsAfter || prioTasks.size != num)
      println("\t*")
    else
      println
  }
*/
  override def toString : String = {
    val strings =
      for (t <- nextEagerTask.elements ++
                prioTasks.sortedIterator.take(2)) yield t.toString

    "[" + (if (strings.hasNext)
             strings.reduceLeft((s1 : String, s2 : String) => s1 + ", " + s2)
           else
             "") + "]" +
    (if (prioTasks.size > 2)
      " (" + (prioTasks.size - 2) + " further tasks)"
     else
      "")
  }
  
}
