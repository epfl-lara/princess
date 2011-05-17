/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2011 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.parser;

import ap._
import ap.terfor.{ConstantTerm, OneTerm}
import ap.terfor.conjunctions.{Conjunction, Quantifier}
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.equations.{EquationConj, NegEquationConj}
import ap.terfor.inequalities.InEqConj
import ap.terfor.preds.{Predicate, Atom}
import ap.util.{Debug, Logic, PlainRange}
import ap.basetypes.IdealInt
import smtlib._
import smtlib.Absyn._

import scala.collection.mutable.{Stack, ArrayBuffer}

object SMTParser2InputAbsy {

  private val AC = Debug.AC_PARSER
  
  import Parser2InputAbsy._
  
  def warn(msg : String) : Unit = Console.withOut(Console.err) {
    println("Warning: " + msg)
  }
  
  /**
   * Parse starting at an arbitrarily specified entry point
   */
  private def parseWithEntry[T](input : java.io.Reader,
                                env : Environment,
                                entry : (parser) => T) : T = {
    val l = new Yylex(new CRRemover2 (input))
    val p = new parser(l)
    
    try { entry(p) } catch {
      case e : Exception =>
        throw new ParseException(
             "At line " + String.valueOf(l.line_num()) +
             ", near \"" + l.buff() + "\" :" +
             "     " + e.getMessage())
    }
  }

  /**
   * We currently only support the sorts bool and int
   * everything else is considered as integers
   */
  object Type extends Enumeration {
    val Bool, Int = Value
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  private val badStringChar = """[^a-zA-Z_0-9']""".r
  
  private def sanitise(s : String) : String =
    badStringChar.replaceAllIn(s, (m : scala.util.matching.Regex.Match) =>
                                       ('a' + (m.toString()(0) % 26)).toChar.toString)

  //////////////////////////////////////////////////////////////////////////////

  /** Implicit conversion so that we can get a Scala-like iterator from a
   * a Java list */
  import scala.collection.JavaConversions.{asScalaBuffer, asScalaIterator}

  private def asString(s : SymbolRef) : String = s match {
    case s : IdentifierRef     => asString(s.identifier_)
    case s : CastIdentifierRef => asString(s.identifier_)
  }
  
  private def asString(id : Identifier) : String = id match {
    case id : SymbolIdent =>
      asString(id.symbol_)
    case id : IndexIdent =>
      asString(id.symbol_) + "_" +
      ((id.listindexc_ map (_.asInstanceOf[Index].numeral_)) mkString "_")
  }
  
  private def asString(s : Symbol) : String = s match {
    case s : NormalSymbol => sanitise(s.normalsymbolt_)
    case s : QuotedSymbol => sanitise(s.quotedsymbolt_)
  }
  
  private def translateSort(s : Sort) : Type.Value = s match {
    case s : IdentSort => asString(s.identifier_) match {
      case "Int" => Type.Int
      case "Bool" => Type.Bool
      case id => {
        warn("treating sort " + (PrettyPrinter print s) + " as Int")
        Type.Int
      }
    }
    case s : CompositeSort => {
      warn("treating sort " + (PrettyPrinter print s) + " as Int")
      Type.Int
    }
  }
  
  private object PlainSymbol {
    def unapply(s : SymbolRef) : scala.Option[String] = s match {
      case s : IdentifierRef => s.identifier_ match {
        case id : SymbolIdent => id.symbol_ match {
          case s : NormalSymbol => Some(s.normalsymbolt_)
          case _ => None
        }
        case _ => None
      }
      case _ => None
    }
  }
}


class SMTParser2InputAbsy (_env : Environment) extends Parser2InputAbsy(_env) {
  
  import IExpression._
  import Parser2InputAbsy._
  import SMTParser2InputAbsy._
  
  /** Implicit conversion so that we can get a Scala-like iterator from a
    * a Java list */
  import scala.collection.JavaConversions.{asScalaBuffer, asScalaIterator}

  type GrammarExpression = Term

  //////////////////////////////////////////////////////////////////////////////

  def apply(input : java.io.Reader)
           : (IFormula, List[IInterpolantSpec], Signature) = {
    def entry(parser : smtlib.parser) = {
      val parseTree = parser.pScriptC
      parseTree match {
        case parseTree : Script => parseTree
        case _ => throw new ParseException("Input is not an SMT-LIB 2 file")
      }
    }
    
    apply(parseWithEntry(input, env, entry _))
  }

  private def apply(script : Script)
                   : (IFormula, List[IInterpolantSpec], Signature) = {
    var assumptions : IFormula = true
    
    for (cmd <- script.listcommand_) cmd match {
//      case cmd : SetLogicCommand =>
//      case cmd : SetOptionCommand =>
//      case cmd : SetInfoCommand =>
//      case cmd : SortDeclCommand =>
//      case cmd : SortDefCommand =>
//      case cmd : FunctionDefCommand =>
//      case cmd : PushCommand =>
//      case cmd : PopCommand =>
//      case cmd : AssertCommand =>
//      case cmd : CheckSatCommand =>
//      case cmd : ExitCommand =>

      case cmd : FunctionDeclCommand => {
        // Functions are always declared to have integer inputs and outputs
        val name = asString(cmd.symbol_)
        val args = cmd.mesorts_ match {
          case sorts : SomeSorts =>
            for (s <- sorts.listsort_.toList) yield translateSort(s)
          case _ : NoSorts =>
            List()
        }
        val res = translateSort(cmd.sort_)
        if (args.length > 0)
          // use a real function
          env.addFunction(new IFunction(name, args.length, false, false),
                          res == Type.Bool)
        else if (res == Type.Int)
          // use a constant
          env.addConstant(new ConstantTerm(name), Environment.NullaryFunction)
        else
          // use a nullary predicate (propositional variable)
          env.addPredicate(new Predicate(name, 0))
      }

      case cmd : AssertCommand => {
        val f = asFormula(translateTerm(cmd.term_, -1))
        assumptions = assumptions &&& f
      }

      case _ =>
        warn("ignoring " + (PrettyPrinter print cmd))
    }

    (!assumptions, List(), env.toSignature)
  }

  //////////////////////////////////////////////////////////////////////////////

  private def translateTerm(t : Term, polarity : Int)
                           : (IExpression, Type.Value) = t match {
    case t : smtlib.Absyn.ConstantTerm => t.specconstant_ match {
      case c : NumConstant => (i(IdealInt(c.numeral_)), Type.Int)
    }
    case t : NullaryTerm => symApp(t.symbolref_, List(), polarity)
    case t : FunctionTerm => symApp(t.symbolref_, t.listterm_, polarity)
  }

  private def symApp(sym : SymbolRef, args : Seq[Term], polarity : Int)
                    : (IExpression, Type.Value) = sym match {
    ////////////////////////////////////////////////////////////////////////////
    // Hardcoded operations on formulae
    case PlainSymbol("true") => {
      checkArgNum("true", 0, args)
      (i(true), Type.Bool)
    }
    case PlainSymbol("false") => {
      checkArgNum("false", 0, args)
      (i(false), Type.Bool)
    }

    case PlainSymbol("not") => {
      checkArgNum("not", 1, args)
      (!asFormula(translateTerm(args.head, -polarity)), Type.Bool)
    }
    
    case PlainSymbol("and") =>
      (connect(for (s <- flatten("and", args))
                 yield asFormula(translateTerm(s, polarity)),
               IBinJunctor.And),
       Type.Bool)
    
    case PlainSymbol("or") =>
      (connect(for (s <- flatten("or", args))
                 yield asFormula(translateTerm(s, polarity)),
               IBinJunctor.Or),
       Type.Bool)
    
    case PlainSymbol("=>") => {
      if (args.size == 0)
        throw new Parser2InputAbsy.TranslationException(
          "Operator \"=>\" has to be applied to at least one argument")

      (connect((for (a <- args dropRight 1) yield
                 asFormula(translateTerm(a, -polarity))) ++
               List(asFormula(translateTerm(args.last, polarity))),
               IBinJunctor.Or),
       Type.Bool)
    }
    
    case PlainSymbol("=") => {
      val transArgs = for (a <- args) yield translateTerm(a, 0)
      (if (transArgs forall (_._2 == Type.Bool))
         connect(for (a <- transArgs) yield asFormula(a),
                 IBinJunctor.Eqv)
       else
         connect(for (Seq(a, b) <- (transArgs map (asTerm _)) sliding 2)
                   yield (a === b),
                 IBinJunctor.And),
       Type.Bool)
    }
    
    ////////////////////////////////////////////////////////////////////////////
    // Hardcoded operations on terms

    case PlainSymbol("+") =>
      (sum(for (s <- flatten("+", args)) yield asTerm(translateTerm(s, 0))),
       Type.Int)
    
    case PlainSymbol("*") =>
      ((for (s <- flatten("*", args)) yield asTerm(translateTerm(s, 0)))
          reduceLeft (mult _),
       Type.Int)

    ////////////////////////////////////////////////////////////////////////////
    // Declared symbols from the environment
    case id => (env lookupSym asString(id)) match {
 /*     case Environment.Predicate(pred) => {
        if (pred.arity != 0)
          throw new Parser2InputAbsy.TranslationException(
              "Predicate " + pred +
              "  arguments: " + (args mkString ", "))
          
        IAtom(pred, args)
      } */
      
      case Environment.Function(fun, encodesBool) => {
        checkArgNum(PrettyPrinter print sym, fun.arity, args)
        (IFunApp(fun, for (a <- args) yield asTerm(translateTerm(a, 0))),
         if (encodesBool) Type.Bool else Type.Int)
      }

      case Environment.Constant(c, _) => (c, Type.Int)
      
      case Environment.Variable(i) => (v(i), Type.Int)
    }
        
  }
  
  private def flatten(op : String, args : Seq[Term]) : Seq[Term] =
    for (a <- args;
         b <- collectSubExpressions(a, {
                case PlainSymbol(`op`) => true
                case _ => false
              }, SMTConnective))
    yield b
  
  private object SMTConnective extends ASTConnective {
    def unapplySeq(t : Term) : scala.Option[Seq[Term]] = t match {
      case t : NullaryTerm => Some(List())
      case t : FunctionTerm => Some(t.listterm_.toList)
    }
  }

  private def asFormula(expr : (IExpression, Type.Value)) : IFormula = expr match {
    case (expr : IFormula, Type.Bool) =>
      expr
    case (expr : ITerm, Type.Bool) =>
      // then we assume that an integer encoding of boolean values was chosen
      expr === 0
    case (expr, _) =>
      throw new Parser2InputAbsy.TranslationException(
                   "Expected a formula, not " + expr)
  }

  private def asTerm(expr : (IExpression, Type.Value)) : ITerm = expr match {
    case (expr : ITerm, Type.Int) =>
      expr
    case (expr, _) =>
      throw new Parser2InputAbsy.TranslationException(
                   "Expected a term, not " + expr)
  }

  private def checkArgNum(op : String, expected : Int, args : Seq[Term]) : Unit =
    if (expected != args.size)
      throw new Parser2InputAbsy.TranslationException(
          "Operator \"" + op +
          "\" is applied to a wrong number of arguments: " +
          ((for (a <- args) yield (PrettyPrinter print a)) mkString ", "))
}