/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2011-2018 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.terfor

import ap.terfor.linearcombination.LinearCombination
import ap.terfor.preds.{Predicate, Atom}

/**
 * Enumeration to represent whether two terms cannot, may, or must have
 * the same value. The value <code>CannotDueToFreedom</code> expresses that
 * the free-constant heuristic tells that the terms can be assumed to be
 * different
 */
object AliasStatus extends Enumeration {
  val Cannot, CannotDueToFreedom, May, Must = Value
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Trait for classes providing term alias information.
 */
trait AliasChecker {

  /**
   * Check whether two terms have to be considered as potential
   * aliases, i.e., may have the same value.
   */
  def apply(a : LinearCombination, b : LinearCombination,
            includeCannotDueToFreedom : Boolean) : AliasStatus.Value

  /**
   * Find atoms within the sequence <code>atoms</code> that may
   * alias with atoms with the given <code>arguments</code>
   * as the first arguments.
   */
  def findMayAliases(atoms : Seq[Atom],
                     pred : Predicate,
                     arguments : Seq[LinearCombination],
                     includeCannotDueToFreedom : Boolean)
                   : Map[AliasStatus.Value, Seq[Atom]]

}