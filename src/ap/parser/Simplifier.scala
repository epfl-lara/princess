/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2015 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.parser

import ap.basetypes.IdealInt
import ap.terfor.conjunctions.Quantifier

import IBinJunctor._
import IIntRelation._
import IExpression._
import Quantifier._

import scala.collection.mutable.ArrayBuffer

/**
 * Class to simplify input formulas using various rewritings
 */
class Simplifier(SPLITTING_LIMIT : Int = 20) {

  /**
   * Transformation to negation normal form
   */
  private def toNNF(expr : IExpression) : IExpression = expr match {
    case INot(INot(f))                  => f
    case INot(IBinFormula(And, f1, f2)) => (!f1) | !f2
    case INot(IBinFormula(Or, f1, f2))  => (!f1) & !f2
    case INot(IBinFormula(Eqv, f1, f2)) => (!f1) <=> f2
    case INot(IQuantified(q, f))        => IQuantified(q.dual, !f)
    case INot(IBoolLit(v))              => !v
    case INot(IIntFormula(IIntRelation.GeqZero, t)) => t < 0
    case _                              => expr
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Simple mini-scoping, pushing down all quantifiers as far as possible
   */
  private def miniScope(expr : IExpression) : IExpression = expr match {
    case IQuantified(ALL, IBinFormula(And, f1, f2)) => all(f1) & all(f2)
    case IQuantified(EX, IBinFormula(Or, f1, f2)) => ex(f1) | ex(f2)

    case IQuantified(ALL, f@IBinFormula(Or, f1, f2)) => {
      if (!ContainsSymbol(f1, IVariable(0)))
        VariableShiftVisitor(f1, 1, -1) | all(f2)
      else if (!ContainsSymbol(f2, IVariable(0)))
        all(f1) | VariableShiftVisitor(f2, 1, -1)
      else if (splitNum < SPLITTING_LIMIT) f match {
        case AndSplitter(f1, f2) => {
          splitNum = splitNum + 1
          all(f1) & all(f2)
        }
        case _ => expr
      } else
        expr
    }
                                        
    case IQuantified(EX, f@IBinFormula(And, f1, f2)) => {
      if (!ContainsSymbol(f1, IVariable(0)))
        VariableShiftVisitor(f1, 1, -1) & ex(f2)
      else if (!ContainsSymbol(f2, IVariable(0)))
        ex(f1) & VariableShiftVisitor(f2, 1, -1)
      else if (splitNum < SPLITTING_LIMIT) f match {
        case OrSplitter(f1, f2) => {
          splitNum = splitNum + 1
          ex(f1) | ex(f2)
        }
        case _ => expr
      } else
        expr
    }
      
    case IQuantified(_, t)
      if (!ContainsSymbol(t, IVariable(0))) =>
        VariableShiftVisitor(t, 1, -1)

    case _ => expr
  }
  
  /**
   * Keep a counter how often splitting was applied, to prevent exponential
   * blow-up of the formula
   */
  private var splitNum = 0

  private class FormulaSplitter(SplitOp : IBinJunctor.Value,
                                DistributedOp : IBinJunctor.Value) {
    def unapply(f : IFormula) : Option[(IFormula, IFormula)] = f match {
      case IBinFormula(SplitOp, f1, f2) => Some(f1, f2)
      case IBinFormula(DistributedOp, f1, f2) =>
        (for ((f11, f12) <- unapply(f1))
           yield (IBinFormula(DistributedOp, f11, f2),
                  IBinFormula(DistributedOp, f12, f2))) orElse
        (for ((f21, f22) <- unapply(f2))
           yield (IBinFormula(DistributedOp, f1, f21),
                  IBinFormula(DistributedOp, f1, f22)))
      case _ => None
    }
  }

  private val AndSplitter = new FormulaSplitter (IBinJunctor.And, IBinJunctor.Or)
  private val OrSplitter =  new FormulaSplitter (IBinJunctor.Or, IBinJunctor.And)
  
  //////////////////////////////////////////////////////////////////////////////

  private def iteSplitter(t : ITerm)
                         : Option[(IFormula, ITerm, ITerm)] = t match {
    case IFunApp(fun, args) =>
      for ((cond, args1, args2) <- iteSeqSplitter(args))
      yield (cond, IFunApp(fun, args1), IFunApp(fun, args2))

    case IPlus(left, right) =>
      (for ((cond, l1, l2) <- iteSplitter(left))
       yield (cond, IPlus(l1, right), IPlus(l2, right))) orElse
      (for ((cond, r1, r2) <- iteSplitter(right))
       yield (cond, IPlus(left, r1), IPlus(left, r2)))

    case ITimes(coeff, subT) =>
      for ((cond, t1, t2) <- iteSplitter(subT))
      yield (cond, ITimes(coeff, t1), ITimes(coeff, t2))

    case _ => None
  }

  private def iteSeqSplitter(terms : Iterable[ITerm])
                        : Option[(IFormula, Seq[ITerm], Seq[ITerm])] = {
    val prefix = new ArrayBuffer[ITerm]
    val it = terms.iterator
    while (it.hasNext) {
      val a = it.next
      iteSplitter(a) match {
        case Some((cond, a1, a2)) => {
          val prefixList = prefix.toList
          val suffix = it.toList
          return Some((cond,
                       prefixList ++ List(a1) ++ suffix,
                       prefixList ++ List(a2) ++ suffix))
        }
        case None =>
          prefix += a
      }
    }

    None
  }

  /**
   * Split if-then-else expressions within literals
   */
  private def splitITEs(expr : IExpression) : IExpression = expr match {
    case IAtom(p, args) =>
      iteSeqSplitter(args) match {
        case Some((cond, args1, args2)) =>
          (cond & IAtom(p, args1)) | (!cond & IAtom(p, args2))
        case None =>
          expr
      }

    case IIntFormula(op, t) =>
      iteSplitter(t) match {
        case Some((cond, t1, t2)) =>
          (cond & IIntFormula(op, t1)) | (!cond & IIntFormula(op, t2))
        case None =>
          expr
      }

    case _ => expr
  }

  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Eliminate quantifiers of the form <code>ALL _0 = t ==> ...</code> and
   * <code>EX _0 = t & ...</code>
   */
  private def elimQuantifier(expr : IExpression) : IExpression = expr match {
    case IQuantified(q, f) => findDefinition(f, 0, q == ALL) match {
      
      case NoDefFound => {
        // handle some cases of obviously invalid formulae
        val VarSum = SymbolSum(IVariable(0))
        expr match {
          case IQuantified(ALL, IIntFormula(EqZero, VarSum(c, _)))
            if (!c.isZero) => false
          case IQuantified(EX, INot(IIntFormula(EqZero, VarSum(c, _))))
            if (!c.isZero) => false
          case _ => expr
        }
      }
      
      case GoodDef(t) => VariableSubstVisitor(f, (List(t), -1))
      
      case DefRequiresShifting => {
        // we need to pull out quantifiers first
        var prenexF = PullUpQuantifiers.visit(f, q)
        
        // check how many inner quantifiers we have
        var cont = true
        var quanNum = 0
        while (cont) prenexF match {
          case IQuantified(`q`, f) => {
            prenexF = f
            quanNum = quanNum + 1
          }
          case _ =>
            cont = false
        }
        
        // permute the variables (the quantifier to be eliminated will be the
        // innermost one)
        val shifts = IVarShift(List.fill(quanNum)(1) ::: List(-quanNum), 0)
        val shiftedF = VariablePermVisitor(prenexF, shifts)
        
        val substitutedF = findDefinition(shiftedF, 0, q == ALL) match {
          case GoodDef(t) => VariableSubstVisitor(shiftedF, (List(t), -1))
          case _ => { assert(false); null }
        }

        quan(Array.fill(quanNum)(q), substitutedF)
      }
    }
    case _ => expr
  }
  
  private abstract sealed class Def
  
  private case object NoDefFound extends Def
  /** A defining equation was found */
  private case class  GoodDef(t : ITerm) extends Def
  /** A defining equation was found, but contains bound variables that have to
      be moved first*/
  private case object DefRequiresShifting extends Def
  
  private def findDefinition(f : IFormula,
                             varIndex : Int, universal : Boolean) : Def =
    f match {
      case IQuantified(q, subF)
        if (q == Quantifier(universal)) =>
          findDefinition(subF, varIndex + 1, universal)
      case IBinFormula(j, f1, f2)
        if (j == (if (universal) Or else And)) =>
          findDefinition(f1, varIndex, universal) match {
            case d : GoodDef         => d
            case NoDefFound          => findDefinition(f2, varIndex, universal)
            case DefRequiresShifting =>
              findDefinition(f2, varIndex, universal) match {
                case d : GoodDef => d
                case _ => DefRequiresShifting
              }
          }

      case INot(eq @ IIntFormula(EqZero, _)) if (universal) =>
        findDefinition(eq, varIndex, false)

      case _ => {
        // check for equations that represent definitions
        val VarIndexSum = SymbolSum(IVariable(varIndex))
        
        f match {
          case IIntFormula(EqZero, VarIndexSum(coeff, t))
            if ((coeff == IdealInt.ONE || coeff == IdealInt.MINUS_ONE) &&
                !universal) =>
              if (allIndexesLargerThan(t, varIndex))
                GoodDef(VariableShiftVisitor(t *** (-coeff),
                                             varIndex + 1, -varIndex - 1))
              else
                DefRequiresShifting
        
          case _ => NoDefFound
        }
      }
    }
  
  private def allIndexesLargerThan(t : IExpression, limit : Int) : Boolean =
    !ContainsSymbol(t, (x:IExpression) => x match {
      case IVariable(index) => index <= limit
      case _ => false
    })
  
  /**
   * Pull up universal or existential quantifiers as far as possible. This can
   * be necessary to eliminate a quantifier
   */
  private object PullUpQuantifiers
                 extends CollectingVisitor[Quantifier, IFormula] {
  
    override def preVisit(f : IExpression,
                          Quan : Quantifier) : PreVisitResult = f match {
      // we only descend below propositional connectives and quantifiers of
      // the specified type
      case IBinFormula(And | Or, _, _) | IQuantified(Quan, _) => KeepArg
      case f : IFormula => ShortCutResult(f)
    }

    def postVisit(f : IExpression, Quan : Quantifier,
                  subres : Seq[IFormula]) : IFormula = f match {
      case f @ IBinFormula(And | Or, _, _) => {
        var Seq(left, right) = subres
        
        var cont = true
        var quanNum = 0
        while (cont) (left, right) match {
          case (IQuantified(Quan, l), IQuantified(Quan, r)) => {
            quanNum = quanNum + 1
            left = l
            right = r
          }
          case (IQuantified(Quan, l), _) => {
            quanNum = quanNum + 1
            left = l
            right = VariableShiftVisitor(right, 0, 1)
          }
          case (_, IQuantified(Quan, r)) => {
            quanNum = quanNum + 1
            left = VariableShiftVisitor(left, 0, 1)
            right = r
          }
          case _ =>
            cont = false
        }
        
        quan(Array.fill(quanNum)(Quan), f update List(left, right))
      }
        
      case f @ IQuantified(Quan, _) => f update subres
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Do some boolean simplifications, as well as elimination of trivial
   * equations and inequalities of the form <code>t - t = 0</code>,
   * <code>t - t >= 0</code>
   */
  private def elimSimpleLiterals(expr : IExpression) : IExpression = expr match {
    case IBinFormula(And, IBoolLit(true), f) => f
    case IBinFormula(And, f, IBoolLit(true)) => f
    case IBinFormula(And, IBoolLit(false), f) => false
    case IBinFormula(And, f, IBoolLit(false)) => false
    
    case IBinFormula(Or, IBoolLit(true), f) => true
    case IBinFormula(Or, f, IBoolLit(true)) => true
    case IBinFormula(Or, IBoolLit(false), f) => f
    case IBinFormula(Or, f, IBoolLit(false)) => f
    
    // Simplification of if-then-else expressions
    case ITermITE(IBoolLit(true), t, _) => t
    case ITermITE(IBoolLit(false), _, t) => t
    case IFormulaITE(IBoolLit(true), f, _) => f
    case IFormulaITE(IBoolLit(false), _, f) => f

    // Simplification of linear combinations
    case ITimes(IdealInt.ONE, t) => t
    case ITimes(IdealInt.ZERO, t) => 0
    case ITimes(c1, IIntLit(c2)) => c1 * c2
    case ITimes(c1, ITimes(c2, t)) => t * (c1 * c2)
    
    case ITimes(c, IPlus(t1, t2)) => (t1 * c) + (t2 * c)
    
    case IPlus(IIntLit(IdealInt.ZERO), t) => t
    case IPlus(t, IIntLit(IdealInt.ZERO)) => t
    case IPlus(IIntLit(c1), IIntLit(c2)) => c1 + c2
    case IPlus(IIntLit(c1), IPlus(IIntLit(c2), t3)) => (c1 + c2) + t3
    
    // turn everything into right-associative form
    case IPlus(IPlus(t1, t2), t3) => t1 + (t2 + t3)
    
    // move literals to the beginning of a term
    case IPlus(t1, t2 : IIntLit) => t2 + t1
    case IPlus(t1, IPlus(t2 : IIntLit, t3)) => t2 + (t1 + t3)
    
    case SimplifiableSum(t) => t
    
    case IIntFormula(EqZero, IIntLit(v)) => v.isZero
    case IIntFormula(GeqZero, IIntLit(v)) => (v.signum >= 0)
      
    case _ => expr
  }
  
  private object SimplifiableSum {
    def unapply(t : IPlus) : Option[ITerm] = t match {
      case IPlus(ITimes(c1, t1), t2) => {
        val (coeff, rem) = extractTerm(t1, t2)
        if (rem eq t2) None else Some(t1 * (c1 + coeff) + rem)
      }
      case IPlus(t1, t2) => {
        val (coeff, rem) = extractTerm(t1, t2)
        if (rem eq t2) None else Some(t1 * (coeff + 1) + rem)
      }
    }

    private def extractTerm(searchedTerm : ITerm,
                            in : ITerm) : (IdealInt, ITerm) = in match {
      case `searchedTerm` => (IdealInt.ONE, 0)
      case ITimes(c, `searchedTerm`) => (c, 0)
      case IPlus(in1, in2) => {
        val (c1, rem1) = extractTerm(searchedTerm, in1)
        val (c2, rem2) = extractTerm(searchedTerm, in2)
        (c1 + c2, in update List(rem1, rem2))
      }
      case _ => (0, in)
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Hook for subclasses
   */
  protected def furtherSimplifications(expr : IExpression) = expr
  
  private val defaultRewritings =
    Array(toNNF _, elimSimpleLiterals _, elimQuantifier _, miniScope _,
          splitITEs _, furtherSimplifications _)
  
  /**
   * Perform various kinds of simplification to the given formula, in particular
   * mini-scoping and eliminate of simple kinds of quantifiers
   */
  def apply(expr : IFormula) : IFormula = {
    import Rewriter._
    splitNum = 0
    rewrite(expr, combineRewritings(defaultRewritings)).asInstanceOf[IFormula]
  }
  
}
