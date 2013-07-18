/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2013 Philipp Ruemmer <ph_r@gmx.net>
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

package ap

import ap.terfor.{ConstantTerm, TermOrder}
import ap.terfor.preds.Predicate

object Signature {
  object PredicateMatchStatus extends Enumeration {
    val Positive, Negative, None = Value
  }
  
  type PredicateMatchConfig = Map[Predicate, PredicateMatchStatus.Value]
  
  //////////////////////////////////////////////////////////////////////////////

  def apply(universalConstants : Set[ConstantTerm],
            existentialConstants : Set[ConstantTerm],
            nullaryFunctions : Set[ConstantTerm],
            order : TermOrder) =
    new Signature(universalConstants, existentialConstants, nullaryFunctions,
                  Map(), order)

  def apply(universalConstants : Set[ConstantTerm],
            existentialConstants : Set[ConstantTerm],
            nullaryFunctions : Set[ConstantTerm],
            predicateMatchConfig : PredicateMatchConfig,
            order : TermOrder) =
    new Signature(universalConstants, existentialConstants, nullaryFunctions,
                  predicateMatchConfig, order)
}

/**
 * Helper class for storing the sets of declared constants (of various kinds)
 * and functions, together with the chosen <code>TermOrder</code>.
 */
class Signature private (val universalConstants : Set[ConstantTerm],
                         val existentialConstants : Set[ConstantTerm],
                         val nullaryFunctions : Set[ConstantTerm],
                         val predicateMatchConfig : Signature.PredicateMatchConfig,
                         val order : TermOrder) {
  def updateOrder(newOrder : TermOrder) =
    new Signature(universalConstants, existentialConstants,
                  nullaryFunctions, predicateMatchConfig, newOrder)
}
