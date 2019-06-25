/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2018 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * Princess is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princess.  If not, see <http://www.gnu.org/licenses/>.
 */

package ap.util;

import scala.collection.mutable.{ArrayStack => Stack, HashMap, ArrayBuilder}
import scala.util.Sorting

import java.lang.Thread

/**
 * Object for measuring the time needed by the various tasks, methods, etc.
 * The object, in particular, supports nested operations that call each other
 * and correctly measures the time spent in each of the operations.
 *
 * The current implementation of the timer is not thread-safe;
 * if we detect that the class is used from multiple threads
 * we disable time measurements altogether. Calls are still counted.
 */
object Timer {

  private var startTime : Long = _
  private val runningOps = new Stack[String]

  // accumulated time spent in each operation
  private val accumulatedTimes = new HashMap[String, Long] {
    override def default(op : String) : Long = 0
  }
  // number of call to each operation
  private val callCounters = new HashMap[String, Int] {
    override def default(op : String) : Int = 0
  }
  
  private def addTime : Unit = {
    val now = System.nanoTime
    if (!runningOps.isEmpty) {
      val op = runningOps.top
      accumulatedTimes += (op -> (accumulatedTimes(op) + now - startTime))
    }
    startTime = now
  }
  
  private var lastThread : Thread = null
  private var timerDisabled = false

  def measure[A](op : String)(comp : => A) : A =
    if (timerDisabled) {

      synchronized {
        callCounters += (op -> (callCounters(op) + 1))
      }
      comp

    } else {

      if (lastThread == null) {
        lastThread = Thread.currentThread
      } else if (!(lastThread eq Thread.currentThread)) {
        Console.err.println(
          "Warning: disabling ap.util.Timer due to multi-threading")
        timerDisabled = true
        return measure(op)(comp)
      }

      addTime
      callCounters += (op -> (callCounters(op) + 1))
      runningOps push op
    
      try {
        comp
      } finally {
        if (!timerDisabled) {
          addTime
          runningOps.pop
        }
      }

    }
  
  def reset : Unit = {
    accumulatedTimes.clear
    callCounters.clear
    runningOps.clear
    lastThread    = null
    timerDisabled = false
  }
  
  override def toString : String = {
    val resBuf = ArrayBuilder.make[(String, Int, Long)]

    for ((op, time) <- accumulatedTimes)
      resBuf += ((op, callCounters(op), if (timerDisabled) 0l else time))

    val resAr = resBuf.result
    Sorting.stableSort(resAr)

    val table =
    (for ((op, count, time) <- resAr) yield {
      var paddedOp = op
      // HACK: for some reason, the <code>RichString.format</code> method does
      // not work
      while (paddedOp.size < 40)
        paddedOp = paddedOp + " "
      
      val timeInMS = time.toDouble / 1000000.0
          
      (paddedOp + "\t" + count + "\t" + timeInMS + "ms")
    }) mkString "\n"
    
    val totalTime =
      if (timerDisabled) 0l else accumulatedTimes.valuesIterator.foldLeft(0l)(_ + _)
    val totalTimeInMS =
      totalTime.toDouble / 1000000.0
    
    val totalCalls = callCounters.valuesIterator.foldLeft(0)(_ + _)
    
    val total = "Total: " + totalCalls + ", " + totalTimeInMS + "ms"
    
    table + "\n" + total
  }
  
}
