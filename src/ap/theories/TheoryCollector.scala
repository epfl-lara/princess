/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2013 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.theories

import ap.parser._

import scala.collection.mutable.{HashSet => MHashSet}

/**
 * Class to find out which theories where used in a given set
 * of formulae/expressions
 */
class TheoryCollector extends CollectingVisitor[Unit, Unit]
                      with    Cloneable {

  private val symbolsSeen  = new MHashSet[AnyRef]
  private val theoriesSeen = new MHashSet[Theory]

  private var theoriesList : List[Theory] = List()
  private var theoriesDiff : List[Theory] = List()

  def theories    = theoriesList

  def reset       = (theoriesDiff = List())
  def newTheories = theoriesDiff

  override def clone : TheoryCollector = {
    val res = new TheoryCollector
    
    res.symbolsSeen  ++= this.symbolsSeen
    res.theoriesSeen ++= this.theoriesSeen
    res.theoriesList =   this.theoriesList
    res.theoriesDiff =   this.theoriesDiff
    
    res
  }

  //////////////////////////////////////////////////////////////////////////////

  def apply(expr : IExpression) : Unit =
    this.visitWithoutResult(expr, {})

  def postVisit(t : IExpression, arg : Unit,
                subres : Seq[Unit]) : Unit = t match {
    case IFunApp(f, _) if (!(symbolsSeen contains f)) => {
      symbolsSeen += f
      for (t <- TheoryRegistry lookupSymbol f)
        if (theoriesSeen add t) {
          theoriesList = t :: theoriesList
          theoriesDiff = t :: theoriesDiff
        }
    }
    case IAtom(p, _) if (!(symbolsSeen contains p)) => {
      symbolsSeen += p
      for (t <- TheoryRegistry lookupSymbol p)
        if (theoriesSeen add t) {
          theoriesList = t :: theoriesList
          theoriesDiff = t :: theoriesDiff
        }
    }
    case _ => // nothing
  }
  

}