/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2016-2019 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.theories

import ap.parser._
import ap.basetypes.IdealInt
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.conjunctions.{Conjunction, ReduceWithConjunction,
                               NegatedConjunctions}
import ap.terfor.{TermOrder, TerForConvenience, Formula, OneTerm, Term}
import ap.terfor.preds.Atom
import ap.terfor.substitutions.{VariableShiftSubst, VariableSubst}
import ap.{SimpleAPI, PresburgerTools}
import SimpleAPI.ProverStatus
import ap.types.{Sort, ProxySort, MonoSortedIFunction, SortedPredicate,
                 SortedConstantTerm, MonoSortedPredicate}
import ap.proof.theoryPlugins.Plugin
import ap.proof.goal.Goal
import ap.parameters.{Param, ReducerSettings}
import ap.util.{Debug, UnionSet, LazyMappedSet, Combinatorics, Seqs}

import scala.collection.{Map => GMap}
import scala.collection.mutable.{HashMap => MHashMap, ArrayBuffer,
                                 HashSet => MHashSet, Map => MMap, Set => MSet,
                                 BitSet => MBitSet, ArrayStack,
                                 LinkedHashSet}
import scala.collection.{Set => GSet}

object ADT {

  private val AC = Debug.AC_ADT

  abstract sealed class CtorArgSort
  case class ADTSort(num : Int)     extends CtorArgSort
  case class OtherSort(sort : Sort) extends CtorArgSort

  case class CtorSignature(arguments : Seq[(String, CtorArgSort)],
                           result : ADTSort)

  class ADTException(m : String) extends Exception(m)

  //////////////////////////////////////////////////////////////////////////////

  private abstract sealed class ADTPred
  private case class ADTCtorPred  (totalNum : Int,
                                   sortNum : Int,
                                   ctorInSortNum : Int) extends ADTPred
  private case class ADTSelPred   (ctorNum : Int,
                                   selNum : Int,
                                   sortNum : Int) extends ADTPred
  private case class ADTCtorIdPred(sortNum : Int) extends ADTPred
  private case class ADTTermSizePred(sortNum : Int) extends ADTPred

  //////////////////////////////////////////////////////////////////////////////

  object TermMeasure extends Enumeration {
    val RelDepth, Size = Value
  }

  //////////////////////////////////////////////////////////////////////////////

  private def ctorTermDepth(t : ITerm) : Int = t match {
    case IFunApp(_, Seq()) => 1
    case IFunApp(_, args) =>  (args map ctorTermDepth).max + 1
    case _ =>                 0
  }

  private def depthSortedVectors(sorts : List[Sort]) : Stream[List[ITerm]] =
    sorts match {
      case List() => Stream(List())
      case List(s) => for (ind <- s.individuals) yield List(ind)
      case sorts => {
        def compTail(prefixes : List[List[ITerm]],
                     suffixes : List[Stream[ITerm]]) : Stream[List[ITerm]] = {
          // pick a minimum-depth term from the suffixes
          val depths =
            for (s <- suffixes.iterator; if !s.tail.isEmpty)
            yield ctorTermDepth(s.tail.head)

          if (depths.hasNext) {
            val minDepth = depths.min

            var chosenTerm : ITerm = null
            val (newPrefixes, components, newSuffixes) =
              (for ((p, s) <- prefixes zip suffixes) yield s match {
                 case s if chosenTerm == null &&
                           !s.tail.isEmpty &&
                           ctorTermDepth(s.tail.head) == minDepth => {
                   chosenTerm = s.tail.head
                   (chosenTerm :: p, List(chosenTerm), s.tail)
                 }
                 case s => (p, p, s)
               }).unzip3

            (Combinatorics cartesianProduct components).toStream #:::
              compTail(newPrefixes, newSuffixes)
          } else {
            Stream()
          }
        }
        
        val inds = for (s <- sorts) yield s.individuals
        (inds map (_.head)) #::
          compTail(for (terms <- inds) yield List(terms.head), inds)
      }
    }

  private def depthSortedInterl(terms : Stream[Stream[ITerm]],
                                currentDepth : Int) : Stream[ITerm] =
    if (terms.isEmpty) {
      Stream()
    } else {
      val ts = terms.head
      if (ts.isEmpty) {
        depthSortedInterl(terms.tail, currentDepth)
      } else if (ctorTermDepth(ts.head) == currentDepth) {
        ts.head #:: depthSortedInterl(ts.tail #:: terms.tail, currentDepth)
      } else {
        val (shortTerms, longTerms) = terms partition {
          ts => ts.isEmpty || ctorTermDepth(ts.head) == currentDepth
        }

        if (shortTerms.isEmpty)
          depthSortedInterl(longTerms, currentDepth + 1)
        else
          depthSortedInterl(shortTerms #::: longTerms, currentDepth)
      }
    }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Class representing the types/sorts defined by this ADT theory
   */
  class ADTProxySort(val sortNum : Int,
                     underlying : Sort,
                     val adtTheory : ADT) extends ProxySort(underlying) {

    override lazy val individuals : Stream[ITerm] =
      depthSortedInterl(
        for (ctorNum <- adtTheory.sortedGlobalCtorIdsPerSort(sortNum).toStream;
             f = adtTheory.constructors(ctorNum))
        yield (for (args <- depthSortedVectors(f.argSorts.toList))
               yield IFunApp(f, args)),
        1)

    override def decodeToTerm(
                   d : IdealInt,
                   assignment : GMap[(IdealInt, Sort), ITerm]) : Option[ITerm] =
      if (adtTheory.isEnum(sortNum)) {
        val index = d.intValueSafe
        val ctors = adtTheory.constructorsPerSort(sortNum)
        if (0 <= index && index < ctors.size)
          Some(IFunApp(ctors(index), List()))
        else
          None
      } else {
        assignment get ((d, this))
      }

    override def augmentModelTermSet(
                            model : Conjunction,
                            terms : MMap[(IdealInt, Sort), ITerm],
                            allTerms : Set[(IdealInt, Sort)],
                            definedTerms : MSet[(IdealInt, Sort)]) : Unit = {
      if (!adtTheory.isEnum(sortNum)) {
        val atoms =
          for (p <- adtTheory.constructorPreds;
               a <- model.predConj positiveLitsWithPred p)
          yield a

        var oldSize = -1
        while (oldSize < terms.size) {
          oldSize = terms.size

          for (a <- atoms) {
            //-BEGIN-ASSERTION-/////////////////////////////////////////////////
            Debug.assertInt(AC, a.constants.isEmpty && a.variables.isEmpty)
            //-END-ASSERTION-///////////////////////////////////////////////////
            val ADTCtorPred(ctorNum, sortNum, _) =
              adtTheory.adtPreds(a.pred)
            val ctor =
              adtTheory.constructors(ctorNum).asInstanceOf[MonoSortedIFunction]
            val key = (a.last.constant, ctor.resSort)
            if (!(terms contains key))
              getSubTerms(a.init, ctor.argSorts, terms) match {
                case Left(argTerms) =>
                  terms.put(key, IFunApp(ctor, argTerms))
                case Right(_) =>
                  definedTerms += key
              }
          }
        }
      }

      //-BEGIN-ASSERTION-///////////////////////////////////////////////////////
      Debug.assertPost(AC,
        // distinct indices have been mapped to distinct terms
        (for (((_, s), t) <- terms.iterator; if s == this)
         yield t).toSet.size ==
        (for (((ind, s), _) <- terms.iterator; if s == this)
         yield ind).toSet.size)
      //-END-ASSERTION-/////////////////////////////////////////////////////////
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * The ADT of Booleans, with truth values true, false as only constructors.
   * The ADT is a simple enumeration, and preprocessing will map true to value
   * 0, and false to value 1.
   */
  object BoolADT
         extends ADT(List("bool"),
                     List(("true",  CtorSignature(List(), ADTSort(0))),
                          ("false", CtorSignature(List(), ADTSort(0))))) {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertCtor(AC, isEnum(0) && cardinalities(0) == Some(IdealInt(2)))
    //-END-ASSERTION-///////////////////////////////////////////////////////////

    val Seq(boolSort)          = sorts
    val Seq(trueFun, falseFun) = constructors

    /**
     * Term representing the Boolean value true.
     */
    val True  = IFunApp(trueFun, List())

    /**
     * Term representing the Boolean value false.
     */
    val False = IFunApp(falseFun, List())
  }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Extractor recognising the constructors of any ADT theory.
   * The extractor will produce the adt, and the index of the constructor.
   */
  object Constructor {
    def unapply(fun : IFunction) : Option[(ADT, Int)] =
      (TheoryRegistry lookupSymbol fun) match {
        case Some(t : ADT) =>
          for (num <- t.constructors2Index get fun)
          yield (t, num)
        case _ => None
      }
  }

  /**
   * Extractor recognising the selectors of any ADT theory.
   * The extractor will produce the adt, the index of the constructor,
   * and the index of the selected constructor argument.
   */
  object Selector {
    def unapply(fun : IFunction) : Option[(ADT, Int, Int)] =
      (TheoryRegistry lookupSymbol fun) match {
        case Some(t : ADT) =>
          for ((num1, num2) <- t.selectors2Index get fun)
          yield (t, num1, num2)
        case _ => None
      }
  }

  /**
   * Extractor recognising the <code>X_ctor</code> functions of
   * any ADT theory.
   */
  object CtorId {
    def unapply(fun : IFunction) : Option[(ADT, Int)] =
      (TheoryRegistry lookupSymbol fun) match {
        case Some(t : ADT) => (t.ctorIds indexOf fun) match {
          case -1 => None
          case num => Some((t, num))
        }
        case _ => None
      }
  }

  /**
   * Extractor recognising the <code>X_size</code> functions of
   * any ADT theory.
   */
  object TermSize {
    def unapply(fun : IFunction) : Option[(ADT, Int)] =
      (TheoryRegistry lookupSymbol fun) match {
        case Some(t : ADT) if t.termSize != null =>
          (t.termSize indexOf fun) match {
            case -1 => None
            case num => Some((t, num))
          }
          case _ => None
      }
  }

}

////////////////////////////////////////////////////////////////////////////////

/**
 * Theory solver for algebraic data-types.
 */
class ADT (sortNames : Seq[String],
           ctorSignatures : Seq[(String, ADT.CtorSignature)],
           measure : ADT.TermMeasure.Value =
             ADT.TermMeasure.RelDepth) extends Theory {

  import ADT._
  import IExpression.Predicate

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(AC,
                   ctorSignatures forall {
                     case (_, sig) =>
                       ((sig.arguments map (_._2)) ++ List(sig.result)) forall {
                         case ADTSort(id)   => id >= 0 && id < sortNames.size
                         case _ : OtherSort => true
                       }
                   })
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  private val globalCtorIdsPerSort : IndexedSeq[Seq[Int]] = {
    val map =
      ctorSignatures.zipWithIndex groupBy {
        case ((_, CtorSignature(_, ADTSort(sortNum))), n) => sortNum
      }
    (for (i <- 0 until sortNames.size) yield (map get i) match {
       case Some(ctors) => ctors map (_._2)
       case None => List()
     }).toIndexedSeq
  }

  /**
   * Ctors for each sort, sorted by the number of arguments that are again
   * ADTs.
   */
  private val sortedGlobalCtorIdsPerSort : IndexedSeq[Seq[Int]] =
    for (ids <- globalCtorIdsPerSort) yield {
      ids sortBy {
        id => ctorSignatures(id)._2.arguments
                                .filter(_._2.isInstanceOf[ADTSort]).size
      }
    }

  //////////////////////////////////////////////////////////////////////////////
  // Compute cardinality of domains, to handle finite ADTs

  val cardinalities : Seq[Option[IdealInt]] = {
    val cardinalities = Array.fill[Option[IdealInt]](sortNames.size)(null)

    var changed = true
    while (changed) {
      changed = false
      for ((null, sortNum) <- cardinalities.iterator.zipWithIndex) {
        if (globalCtorIdsPerSort(sortNum) exists { ctorId =>
              val (_, sig) = ctorSignatures(ctorId)
              sig.arguments exists {
                case (_, ADTSort(num)) => cardinalities(num) == None
                case (_, OtherSort(s)) => s.cardinality == None
              }
            }) {
          cardinalities(sortNum) = None
          changed = true
        } else {
          val childrenCards =
            for (ctorId <- globalCtorIdsPerSort(sortNum);
                 sig = ctorSignatures(ctorId)._2) yield {
              for ((_, s) <- sig.arguments) yield s match {
                case ADTSort(num) => cardinalities(num)
                case OtherSort(sort) => sort.cardinality
              }
            }
          if (childrenCards forall { cards => !(cards contains null) }) {
            cardinalities(sortNum) = Some(
              (childrenCards map { cards => (cards map (_.get)).product }).sum)
            changed = true
          }
        }
      }
    }

    // all other cardinalities have to be infinite (None), due to cycles

    for (n <- 0 until sortNames.size)
      if (cardinalities(n) == null)
        cardinalities(n) = None

    cardinalities.toVector
  }

  //////////////////////////////////////////////////////////////////////////////
  // Enumerations

  val isEnum : IndexedSeq[Boolean] = {
    val isEnum = Array.fill[Boolean](sortNames.size)(true)

    for ((_, CtorSignature(args, ADTSort(num))) <- ctorSignatures)
      if (!args.isEmpty)
        isEnum(num) = false

    isEnum.toVector
  }

  //////////////////////////////////////////////////////////////////////////////
  // Compute the possible term sizes for each sort (special case of
  // Parikh images, following the procedure in Verma et al, CADE 2005)
  
  lazy val parikhSizeConstraints : IndexedSeq[Conjunction] = {
    import TerForConvenience._
    implicit val order = TermOrder.EMPTY

    for ((ctorIds, entrySort) <- globalCtorIdsPerSort.zipWithIndex) yield {
      if (ctorIds forall { id => ctorSignatures(id)._2.arguments forall {
            case (_, _ : ADTSort) => false
            case _ => true
          }}) {

        // flat datatype (including enums)
        conj(v(0) === 1)

      } else {

        // find out which datatypes are referenced by this one
        val referencedSorts = new MBitSet

        {
          val sortsTodo = new ArrayStack[Int]
          referencedSorts += entrySort
          sortsTodo push entrySort

          while (!sortsTodo.isEmpty) {
            val sort = sortsTodo.pop
            for (ctorId <- globalCtorIdsPerSort(sort))
              for ((_, ADTSort(refSort)) <- ctorSignatures(ctorId)._2.arguments)
                if (referencedSorts add refSort)
                  sortsTodo push refSort
          }
        }

        val referencedSortsList = referencedSorts.toList

        val productions =
          (for (sort <- referencedSorts.iterator;
                ctorId <- globalCtorIdsPerSort(sort).iterator;
                sig = ctorSignatures(ctorId)._2;
                relevantArgs =
                  for ((_, ADTSort(num)) <- sig.arguments) yield num)
           yield (sig.result.num, relevantArgs)).toList.distinct

        val (prodVars, zVars, sizeVar) = {
          val prodVars = for ((_, num) <- productions.zipWithIndex) yield v(num)
          var nextVar = prodVars.size
          val zVars = (for (sort <- referencedSorts.iterator) yield {
            val ind = nextVar
            nextVar = nextVar + 1
            sort -> v(ind)
          }).toMap
          (prodVars, zVars, v(nextVar))
        }

        // equations relating the production counters
        val prodEqs =
          for (sort <- referencedSortsList) yield {
            LinearCombination(
               (if (sort == entrySort)
                  Iterator((IdealInt.ONE, OneTerm))
                else
                  Iterator.empty) ++
               (for (((source, targets), prodVar) <-
                        productions.iterator zip prodVars.iterator;
                      mult = (targets count (_ == sort)) -
                             (if (source == sort) 1 else 0))
                yield (IdealInt(mult), prodVar)),
               order)
          }

        val sizeEq =
          LinearCombination(
            (for (v <- prodVars.iterator) yield (IdealInt.ONE, v)) ++
            Iterator((IdealInt.MINUS_ONE, sizeVar)),
            order)

        val entryZEq =
          zVars(entrySort) - 1

        val allEqs = eqZ(entryZEq :: sizeEq :: prodEqs)

        val prodNonNeg =
          prodVars >= 0
 
        val prodImps =
          (for (((source, _), prodVar) <-
                  productions.iterator zip prodVars.iterator;
                if source != entrySort)
           yield ((prodVar === 0) | (zVars(source) > 0))).toList

        val zImps =
          for (sort <- referencedSortsList; if sort != entrySort) yield {
            disjFor(Iterator(zVars(sort) === 0) ++
                    (for (((source, targets), prodVar) <-
                            productions.iterator zip prodVars.iterator;
                          if targets contains sort)
                     yield conj(zVars(sort) === zVars(source) + 1,
                                geqZ(List(prodVar - 1, zVars(source) - 1)))))
          }

        val matrix =
          conj(allEqs :: prodNonNeg :: prodImps ::: zImps)
        val rawConstraint =
          exists(prodVars.size + zVars.size, matrix)
        val constraint =
          PresburgerTools elimQuantifiersWithPreds rawConstraint

        constraint
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Identify sorts that only contain terms of a single size

  lazy val uniqueTermSize : IndexedSeq[Option[IdealInt]] =
    for (constraint <- parikhSizeConstraints) yield {
      if (constraint.arithConj.positiveEqs.size == 1)
        Some(-constraint.arithConj.positiveEqs.head.constant)
      else
        None
    }

  lazy val sizeLowerBound : IndexedSeq[IdealInt] =
    for (constraint <- parikhSizeConstraints) yield {
      if (constraint.arithConj.positiveEqs.size == 1) {
        -constraint.arithConj.positiveEqs.head.constant
      } else {
        val reducer = ReduceWithConjunction(Conjunction.TRUE, TermOrder.EMPTY)
        (for (n <- Iterator.iterate[IdealInt](IdealInt.ONE)(_ + IdealInt.ONE);
              if reducer(VariableSubst(0, List(LinearCombination(n)),
                                       TermOrder.EMPTY)(constraint)).isTrue)
         yield n).next
      }
    }

  //////////////////////////////////////////////////////////////////////////////

  val sorts : IndexedSeq[ADTProxySort] =
    (for (((sortName, card), sortNum) <-
            (sortNames zip cardinalities).zipWithIndex) yield
         new ADTProxySort(sortNum,
                          card match {
                            case None =>
                              Sort.Integer
                            case Some(card) =>
                              Sort.Interval(Some(0), Some(card - 1))
                          },
                          this) {
           override val name = sortName
         }).toIndexedSeq

  /**
   * Extractor to recognise sorts belonging to this ADT.
   */
  object SortNum {
    def unapply(s : Sort) : Option[Int] = s match {
      case s : ADTProxySort if s.adtTheory == ADT.this =>
        Some(s.sortNum)
      case _ =>
        None
    }
  }

  private val ctorArgSorts =
    for ((_, sig) <- ctorSignatures) yield
      for ((_, s) <- sig.arguments) yield s match {
        case ADTSort(num)    => sorts(num)
        case OtherSort(sort) => sort
      }

  private val nonEnumSorts : Set[Sort] =
    (for (sort <- sorts.iterator; if !isEnum(sort.sortNum)) yield sort).toSet

  //////////////////////////////////////////////////////////////////////////////

  /**
   * The constructors of the ADT
   */
  val constructors : IndexedSeq[MonoSortedIFunction] =
    (for (((name, sig), argSorts) <- ctorSignatures zip ctorArgSorts)
     yield new MonoSortedIFunction(name, argSorts, sorts(sig.result.num),
                                   true, false)).toIndexedSeq

  private val constructorsSet : Set[IFunction] = constructors.toSet

  private val constructors2Index : Map[IFunction, Int] =
    constructors.iterator.zipWithIndex.toMap

  /**
   * The selectors of the ADT
   */
  val selectors : IndexedSeq[Seq[MonoSortedIFunction]] =
    (for (((_, sig), argSorts) <- ctorSignatures zip ctorArgSorts) yield {
       for (((name, _), argSort) <- sig.arguments zip argSorts)
       yield new MonoSortedIFunction(name,
                                     List(sorts(sig.result.num)),
                                     argSort,
                                     true, false)
     }).toIndexedSeq

  private val selectors2Index : Map[IFunction, (Int, Int)] =
    (for ((sels, ind1) <- selectors.iterator.zipWithIndex;
          (sel, ind2) <- sels.iterator.zipWithIndex)
     yield (sel -> (ind1, ind2))).toMap

  /**
   * Function symbols representing the index of the head symbol of a
   * constructor term
   */
  val ctorIds : IndexedSeq[MonoSortedIFunction] =
    for (sort <- sorts)
    yield new MonoSortedIFunction(sort.name + "_ctor",
                                  List(sort), Sort.Integer,
                                  true, false)

  /**
   * Function symbols representing (relative) depth of constructor terms.
   * The symbols are only available for
   * <code>measure == ADT.TermMeasure.RelDepth</code>
   */
  val termDepth : IndexedSeq[MonoSortedIFunction] =
    if (measure == ADT.TermMeasure.RelDepth)
      for (sort <- sorts)
      yield new MonoSortedIFunction(sort.name + "_depth",
                                    List(sort), Sort.Integer,
                                    true, false)
    else
      null

  /**
   * Function symbols representing absolute size of constructor terms.
   * The symbols are only available for
   * <code>measure == ADT.TermMeasure.Size</code>
   */
  val termSize : IndexedSeq[MonoSortedIFunction] =
    if (measure == ADT.TermMeasure.Size)
      for (sort <- sorts)
      yield new MonoSortedIFunction(sort.name + "_size",
                                    List(sort), Sort.Integer,
                                    true, false)
    else
      null

  private val ctorId2PerSortId : IndexedSeq[Int] = {
    val adtCtorNums = Array.fill[Int](sortNames.size)(0)
    (for ((_, CtorSignature(_, ADTSort(sortNum))) <- ctorSignatures)
     yield {
       val id = adtCtorNums(sortNum)
       adtCtorNums(sortNum) = id + 1
       id
     }).toIndexedSeq
  }

  /**
   * Query the constructor type of a term; the given <code>id</code>
   * is the position of a constructor in the sequence
   * <code>ctorSignatures</code>.
   */
  def hasCtor(t : ITerm, id : Int) : IFormula = {
    import IExpression._
    val (_, CtorSignature(_, ADTSort(sortNum))) = ctorSignatures(id)

    ctorIds(sortNum)(t) === ctorId2PerSortId(id)
  }

  /**
   * Get the constructor number <code>ctorNum</code> of
   * sort <code>sortNum</code>.
   */
  def getCtorPerSort(sortNum : Int, ctorNum : Int) : MonoSortedIFunction =
    constructors(globalCtorIdsPerSort(sortNum)(ctorNum))

  //////////////////////////////////////////////////////////////////////////////

  override def evalFun(f : IFunApp) : Option[ITerm] = {
    val adt = this
    f match {
      case IFunApp(Constructor(`adt`, _), _) =>
        Some(f)
      case IFunApp(Selector(`adt`, ctorNum1, selNum),
                   Seq(IFunApp(Constructor(`adt`, ctorNum2), ctorArgs)))
        if ctorNum1 == ctorNum2 =>
        Some(ctorArgs(selNum))
      case IFunApp(CtorId(`adt`, sortNum),
                   Seq(IFunApp(Constructor(`adt`, ctorNum), _)))
        if ctorSignatures(ctorNum)._2.result.num == sortNum =>
        Some(ctorId2PerSortId(ctorNum))
      case IFunApp(TermSize(`adt`, _), Seq(t)) =>
        for (n <- ctorTermSize(t)) yield n
      case _ =>
        None
    }
  }

  /**
   * Compute the size (number of constructor occurrences) of
   * a constructor term; return <code>None</code> if parts of the term
   * are symbolic, and the size cannot be determined.
   */
  def ctorTermSize(f : ITerm) : Option[Int] = f match {
    case IFunApp(Constructor(adt, ctorNum), args) if adt == this => {
      val (_, sig) = ctorSignatures(ctorNum)
      Seqs.optionSum(
        Iterator(Some(1)) ++
        (for ((a, (_, ADTSort(_))) <- args.iterator zip sig.arguments.iterator)
         yield ctorTermSize(a)))
    }
    case _ =>
      None
  }

  //////////////////////////////////////////////////////////////////////////////

  val functions: Seq[ap.parser.IFunction] =
    constructors ++ selectors.flatten ++ ctorIds ++
    (measure match { case ADT.TermMeasure.RelDepth => termDepth;
                     case ADT.TermMeasure.Size =>     termSize })

  val (predicates, axioms, _, functionTranslation) =
    Theory.genAxioms(theoryFunctions = functions)

  val totalityAxioms = Conjunction.TRUE

  val functionPredicateMapping =
    functions zip (functions map functionTranslation)

  val functionalPredicates: Set[ap.parser.IExpression.Predicate] =
    predicates.toSet

  val predicateMatchConfig: ap.Signature.PredicateMatchConfig = Map()
  val triggerRelevantFunctions: Set[ap.parser.IFunction] = Set()

  //////////////////////////////////////////////////////////////////////////////

  val constructorPreds =
    constructors map functionTranslation

  private val constructorPredsSet = constructorPreds.toSet

  private val constructorsPerSort
              : IndexedSeq[IndexedSeq[MonoSortedIFunction]] = {
    val map =
      (constructors zip ctorSignatures) groupBy {
        case (_, (_, CtorSignature(_, ADTSort(sortNum)))) => sortNum
      }
    (for (i <- 0 until sortNames.size) yield (map get i) match {
       case Some(ctors) => (ctors map (_._1)).toIndexedSeq
       case None => Vector()
     }).toIndexedSeq
  }

  private val constructorPredsPerSort : IndexedSeq[Seq[Predicate]] = {
    val map =
      (constructorPreds zip ctorSignatures) groupBy {
        case (_, (_, CtorSignature(_, ADTSort(sortNum)))) => sortNum
      }
    (for (i <- 0 until sortNames.size) yield (map get i) match {
       case Some(ctors) => ctors map (_._1)
       case None => List()
     }).toIndexedSeq
  }

  val selectorPreds =
    for (sels <- selectors) yield {
      sels map functionTranslation
    }

  val ctorIdPreds =
    ctorIds map functionTranslation

  val termDepthPreds =
    if (measure == ADT.TermMeasure.RelDepth)
      termDepth map functionTranslation
    else
      null

  val termSizePreds =
    if (measure == ADT.TermMeasure.Size)
      termSize map functionTranslation
    else
      null

  private val termMeasurePreds =
    measure match {
      case ADT.TermMeasure.RelDepth => termDepthPreds
      case ADT.TermMeasure.Size     => termSizePreds
    }

  //////////////////////////////////////////////////////////////////////////////
  // Verify that all of the sorts are inhabited, and compute witness terms

  val witnesses : Seq[ITerm] = {
    val witnesses = Array.fill[ITerm](sortNames.size)(null)

    val sortedCtors = (constructors zip ctorSignatures) sortBy (_._1.arity)

    var changed = true
    while (changed) {
      changed = false
      for ((ctor, (_, CtorSignature(argSorts, ADTSort(resNum)))) <- sortedCtors)
        if (witnesses(resNum) == null &&
            (argSorts forall {
               case (_, ADTSort(n))    => witnesses(n) != null
               case (_, _ : OtherSort) => true
             })) {
          witnesses(resNum) =
            IFunApp(ctor, for (s <- argSorts) yield s match {
                            case (_, ADTSort(n))      => witnesses(n)
                            case (_, OtherSort(sort)) => sort.witness.get
                          })
          changed = true
        }
    }

    (witnesses indexOf null) match {
      case -1 => // nothing
      case n =>
        throw new ADTException("ADT " + sortNames(n) + " is uninhabited")
    }

    witnesses.toVector
  }

  //////////////////////////////////////////////////////////////////////////////

  override def toString =
    "ADT(" + (ctorSignatures map (_._1)).mkString(", ") + ")"

  //////////////////////////////////////////////////////////////////////////////

  private val adtPreds = new MHashMap[Predicate, ADTPred]
  private val adtCtorNums = Array.fill[Int](sortNames.size)(0)

  for ((p, i) <- ctorIdPreds.iterator.zipWithIndex)
    adtPreds.put(p, ADTCtorIdPred(i))

  for ((p, i) <- constructorPreds.iterator.zipWithIndex) {
    val (_, CtorSignature(_, ADTSort(sortNum))) = ctorSignatures(i)
    val ctorInSortNum = adtCtorNums(sortNum)
    adtCtorNums(sortNum) = ctorInSortNum + 1
    adtPreds.put(p, ADTCtorPred(i, sortNum, ctorInSortNum))
  }

  for ((preds, ctorNum) <- selectorPreds.iterator.zipWithIndex) {
    val (_, CtorSignature(_, ADTSort(sortNum))) = ctorSignatures(ctorNum)
    for ((p, selNum) <- preds.iterator.zipWithIndex)
      adtPreds.put(p, ADTSelPred(ctorNum, selNum, sortNum))
  }

  if (measure == ADT.TermMeasure.Size)
    for ((p, i) <- termSizePreds.iterator.zipWithIndex)
      adtPreds.put(p, ADTTermSizePred(i))

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Rewrite a formula prior to solving; e.g., add selector and tester
   * constraints
   */
  def rewriteADTFormula(f : Conjunction, order : TermOrder) : Conjunction = {
    implicit val _ = order
    preprocessHelp(f, false, Set())
  }

  override def preprocess(f : Conjunction,
                          order : TermOrder) : Conjunction = {
//println
//println("Preprocessing:")
//println(f)
    val after = rewriteADTFormula(f, order)
//println(" -> " + after)
    val reducerSettings =
      Param.FUNCTIONAL_PREDICATES.set(ReducerSettings.DEFAULT,
                                      functionalPredicates)
    val after2 = ReduceWithConjunction(Conjunction.TRUE,
                                       order,
                                       reducerSettings)(after)
//println(" -> " + after2)
//println
   after2
  }

  private def fullCtorConjunction(ctorNum : Int,
                                  sortNum : Int,
                                  ctorInSortNum : Int,
                                  arguments : Seq[LinearCombination],
                                  node : LinearCombination)
                                 (implicit order : TermOrder)
                               : (Seq[Atom],
                                  Seq[Formula]) = {
    import TerForConvenience._

    val ctorRel = constructorPreds(ctorNum)(arguments ++ List(node))
    val ctorId = ctorIdPreds(sortNum)(List(node, l(ctorInSortNum)))

    val (selectorRels, sortConstraints) =
      (for ((sel, arg) <-
              selectorPreds(ctorNum).iterator zip arguments.iterator)
       yield {
         val ssel = sel.asInstanceOf[MonoSortedPredicate]
         val argSort = ssel.argSorts.last
         (sel(List(node, arg)), argSort membershipConstraint arg)
       }).toList.unzip

    val measureRels = measure match {

      case ADT.TermMeasure.RelDepth => {
        val adtArgs =
          for ((arg, (_, ADTSort(sortN))) <-
                 arguments zip ctorSignatures(ctorNum)._2.arguments;
               if !isEnum(sortN))
          yield (arg, sortN)

        if (adtArgs.isEmpty) {
          List()
        } else {
          val subst = VariableShiftSubst(0, adtArgs.size + 1, order)
  
          val nodeDepth =
            termDepthPreds(sortNum)(List(subst(node), l(v(0))))
          val argDepths =
            for (((a, n), i) <- adtArgs.zipWithIndex)
            yield termDepthPreds(n)(List(subst(a), l(v(i+1))))
          val depthRels =
            for (i <- 1 to adtArgs.size) yield (v(i) < v(0))
          val matrix =
            conj(List(nodeDepth) ++ argDepths ++ depthRels)
          List(exists(adtArgs.size + 1, matrix))
        }
      }

      case ADT.TermMeasure.Size => {
        var sizeOffset = IdealInt.ONE

        val adtArgs =
          for ((arg, (_, ADTSort(sortN))) <-
                 arguments zip ctorSignatures(ctorNum)._2.arguments;
               if (uniqueTermSize(sortN) match {
                 case Some(size) => {
                   sizeOffset = sizeOffset + size
                   false
                 }
                 case None =>
                   true
               }))
          yield (arg, sortN)

        val subst = VariableShiftSubst(0, adtArgs.size, order)

        val sizePreds = new ArrayBuffer[Formula]
        val sizeTerms = new ArrayBuffer[(IdealInt, Term)]

        sizeTerms += ((sizeOffset, OneTerm))

        var varNum = 0
        for ((a, sn) <- adtArgs) {
          val va = l(v(varNum))
          varNum = varNum + 1

          sizePreds += termSizePreds(sn)(List(subst(a), va))
          sizePreds += (va >= sizeLowerBound(sn))
          sizeTerms += ((IdealInt.ONE, va))
        }

        val nodeSize = termSizePreds(sortNum)(
                                     List(subst(node),
                                     LinearCombination(sizeTerms, order)))

        val matrix = conj(List(nodeSize) ++ sizePreds)
        List(exists(adtArgs.size, matrix))
      }

      case _ =>
        List()
    }
            
    (List(ctorRel, ctorId) ++ selectorRels, measureRels ++ sortConstraints)
  }

  private def quanCtorConjunction(ctorNum : Int, node : LinearCombination)
                                 (implicit order : TermOrder) : Conjunction = {
    import TerForConvenience._

    val (_, CtorSignature(_, ADTSort(sortNum))) = ctorSignatures(ctorNum)
    val varNum = constructors(ctorNum).arity
    val shiftSubst = VariableShiftSubst(0, varNum, order)

    val (a, b) = fullCtorConjunction(
                           ctorNum,
                           sortNum,
                           ctorId2PerSortId(ctorNum),
                           for (i <- 0 until varNum) yield l(v(i)),
                           shiftSubst(node))

//    val sortConstraint = sorts(sortNum) membershipConstraint node
    exists(varNum, conj(a ++ b /* ++ List(shiftSubst(sortConstraint)) */))
  }

  private def quanCtorCases(sortNum : Int, node : LinearCombination)
                           (implicit order : TermOrder) : Seq[Conjunction] = {
    for (ctorNum <- sortedGlobalCtorIdsPerSort(sortNum))
    yield quanCtorConjunction(ctorNum, node)
  }

  private def ctorDisjunction(sortNum : Int,
                              node : LinearCombination,
                              id : LinearCombination)
                             (implicit order : TermOrder) : Conjunction = {
    import TerForConvenience._

    val sortConstraint = sorts(sortNum) membershipConstraint node

    val regularDisjuncts =
      for ((ctorNum, n) <- globalCtorIdsPerSort(sortNum)
                                          .iterator.zipWithIndex) yield {
        val ctorArgSorts = constructors(ctorNum).argSorts
        val varNum = ctorArgSorts.size
        val shiftSubst = VariableShiftSubst(0, varNum, order)

        val (a, b) = fullCtorConjunction(
                               ctorNum,
                               sortNum,
                               n,
                               for (i <- 0 until varNum) yield l(v(i)),
                               shiftSubst(node))

        existsSorted(ctorArgSorts,
                     conj(a ++ b ++
                          List(shiftSubst(id) === n,
                               shiftSubst(sortConstraint))))
      }

    disj(regularDisjuncts)
  }
  
  //////////////////////////////////////////////////////////////////////////////

  private def preprocessHelp(f : Conjunction,
                             negated : Boolean,
                             guardedNodes : GSet[LinearCombination])
                            (implicit order : TermOrder) : Conjunction = {
    import TerForConvenience._

    val quanNum = f.quans.size
    val shiftedGuardedNodes =
      if (quanNum == 0)
        guardedNodes
      else
        new LazyMappedSet(
          guardedNodes,
          VariableShiftSubst.upShifter[LinearCombination](quanNum, order),
          VariableShiftSubst.downShifter[LinearCombination](quanNum, order))

    val newGuardedNodes : Set[LinearCombination] =
      if (negated)
        (for (a <- f.predConj.positiveLits.iterator;
              b <- (adtPreds get a.pred) match {
                case Some(_ : ADTCtorPred) =>
                  Iterator single a.last
                case Some(_ : ADTCtorIdPred) =>
                  Iterator single a.head
                case _ =>
                  Iterator.empty
              })
         yield b).toSet
      else
        Set()

    val allGuardedNodes =
      if (newGuardedNodes.isEmpty)
        shiftedGuardedNodes
      else
        UnionSet(shiftedGuardedNodes, newGuardedNodes)

    val newNegConj =
      f.negatedConjs.update(for (c <- f.negatedConjs)
                              yield preprocessHelp(c, !negated,
                                                   allGuardedNodes),
                            order)

    if (negated) {
      val newConjuncts = new ArrayBuffer[Formula]

      val newPosLits =
        (for (a <- f.predConj.positiveLits.iterator;
              b <- (adtPreds get a.pred) match {

                case Some(ADTCtorPred(i, sortNum, ctorInSortNum)) =>
                  if (isEnum(sortNum)) {
                    // enumeration ctors are simply mapped to integers
                    newConjuncts += (a.last === ctorInSortNum)
                    Iterator.empty
                  } else {
                    val (atoms, fors) =
                      fullCtorConjunction(i, sortNum, ctorInSortNum,
                                          a dropRight 1, a.last)
                    newConjuncts ++= fors
                    atoms.iterator
                  }

                case Some(ADTCtorIdPred(sortNum)) =>
                  if (isEnum(sortNum)) {
                    // ids of enumeration ctors are the representing integers
                    // themselves
                    newConjuncts += (a.head === a.last)
                    Iterator.empty
                  } else {
// TODO: factor out common depth/size and sort constraints
                    newConjuncts += ctorDisjunction(sortNum, a.head, a.last)
                    Iterator single a
                  }

                case Some(ADTSelPred(ctorNum, selNum, sortNum)) => {
                  if (!(allGuardedNodes contains a.head)) {
                    // for completeness, we need to add a predicate about
                    // the possible constructors of the considered term
// TODO: factor out common depth/size predicates
                    newConjuncts += disj(quanCtorCases(sortNum, a.head))
                    newConjuncts += sorts(sortNum) membershipConstraint a.head
                  }

                  Iterator single a
                }

                case Some(ADTTermSizePred(sortNum)) => {
                  newConjuncts +=
                    VariableSubst(0, List(a.last), order)(
                                  parikhSizeConstraints(sortNum))

                  if (uniqueTermSize(sortNum).isEmpty)
                    Iterator single a
                  else
                    Iterator.empty
                }

                case None =>
                  Iterator single a

              })
         yield b).toVector

      val newPredConj =
        f.predConj.updateLits(newPosLits, f.predConj.negativeLits)

      if (newConjuncts.isEmpty) {
        Conjunction(f.quans, f.arithConj, newPredConj, newNegConj, order)
      } else {
        val quantifiedParts =
          PresburgerTools toPrenex conj(newConjuncts)
        val newQuanNum = quantifiedParts.quans.size

        val unquantifiedParts =
          VariableShiftSubst(0, newQuanNum, order)(
            Conjunction(List(), f.arithConj, newPredConj, newNegConj, order))

        Conjunction.quantify(
          quantifiedParts.quans ++ f.quans,
          conj(List(quantifiedParts unquantify newQuanNum, unquantifiedParts)),
          order)
      }

    } else { // !negated

      val newDisjuncts = new ArrayBuffer[Conjunction]

      val newNegLits =
        f.predConj.negativeLits filter { a =>
          if (adtPreds contains a.pred) {
            newDisjuncts += preprocessHelp(a, true, allGuardedNodes)
            false
          } else {
            // keep this literal
            true
          }
        }

      val newPredConj =
        f.predConj.updateLits(f.predConj.positiveLits, newNegLits)

      val finalNegConj =
        if (newDisjuncts.isEmpty)
          newNegConj
        else
          NegatedConjunctions(newNegConj ++ newDisjuncts, order)

      Conjunction(f.quans, f.arithConj, newPredConj, finalNegConj, order)
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  override def isSoundForSat(
         theories : Seq[Theory],
         config : Theory.SatSoundnessConfig.Value) : Boolean =
    Set(Theory.SatSoundnessConfig.Elementary,
        Theory.SatSoundnessConfig.Existential) contains config

  //////////////////////////////////////////////////////////////////////////////

  def plugin: Option[Plugin] =
    if (!nonEnumSorts.isEmpty && termSize != null) Some(new Plugin {
      // not used
      def generateAxioms(goal : Goal) : Option[(Conjunction, Conjunction)] =
        None

      override def handleGoal(goal : Goal) : Seq[Plugin.Action] =
        if (goalState(goal) == Plugin.GoalState.Final) {
          implicit val order = goal.order
          val predFacts = goal.facts.predConj

          lazy val ctorDefinedCons = {
            val ctorDefinedCons = new MHashSet[(LinearCombination, Sort)]

            for ((p, i) <- constructorPreds.iterator.zipWithIndex) {
              val (_, CtorSignature(_, ADTSort(sortNum))) = ctorSignatures(i)
              for (a <- predFacts positiveLitsWithPred p)
                ctorDefinedCons += ((a.last, sorts(sortNum)))
            }

            ctorDefinedCons
          }

//          println("Defined: " + ctorDefinedCons)

          // We only consider ADT terms with some size constraint for expansion
          val expCandidates
                : Iterator[(LinearCombination, LinearCombination, Sort)] =
            for ((s, sortNum) <- termSizePreds.iterator.zipWithIndex;
                 if !isEnum(sortNum);
                 sort = sorts(sortNum);
                 a <- (predFacts positiveLitsWithPred s).iterator;
                 lc = a.head;
                 if !(ctorDefinedCons contains ((lc, sort))))
            yield (lc, a.last, sort)

          if (expCandidates.hasNext) {
            import TerForConvenience._

            val (lc, _, sort) = Seqs.partialMinBy(expCandidates, {
              x:(LinearCombination, LinearCombination, Sort) => x._2
            })(LinearCombination.ValueOrdering)
            
            val sortNum = sort.asInstanceOf[ADTProxySort].sortNum

//            println("Expanding: " + lc + ", " + sort)

//            List(Plugin.SplitGoal(
//              for (c <- quanCtorCases(sortNum, lc))
//              yield List(Plugin.AddFormula(!(PresburgerTools toPrenex c)))))

            val assumptions : Seq[Formula] = sort.cardinality match {
              case Some(card) if !lc.isConstant =>
                List(lc >= 0, lc < card)
              case _ =>
                List()
            }

            List(Plugin.AxiomSplit(
              assumptions,
              for (c <- quanCtorCases(sortNum, lc))
                yield (PresburgerTools toPrenex c, List()),
              ADT.this))
          } else {
            List()
          }
        } else {
          List()
        }
    }) else {
    None
  }

  //////////////////////////////////////////////////////////////////////////////
  TheoryRegistry register this

}
