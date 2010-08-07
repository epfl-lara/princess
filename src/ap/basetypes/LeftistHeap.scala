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

package ap.basetypes;

import scala.collection.IterableLike
import scala.collection.mutable.ArrayBuffer

import ap.util.Debug

object LeftistHeap {

  private[basetypes] val AC = Debug.AC_BASE_TYPE

  def EMPTY_HEAP[T](implicit ord : Ordering[T])
                   : LeftistHeap[T, HeapCollector.None[T]] =
    new EmptyHeap[T, HeapCollector.None[T]](HeapCollector.NONE[T])

  def EMPTY_HEAP[T, HC <: HeapCollector[T, HC]]
                (emptyCollector : HC)(implicit ord : Ordering[T])
                : LeftistHeap[T, HC] =
    new EmptyHeap[T, HC](emptyCollector)    

  private[basetypes] def node[T, HC <: HeapCollector[T, HC]]
                             (element : T,
                              a : LeftistHeap[T, HC], b : LeftistHeap[T, HC],
                              empty : LeftistHeap[T, HC])
                             (implicit ord : Ordering[T]) : Node[T, HC] =
    if (a.rightHeight <= b.rightHeight)
      new Node (element, b, a, empty)
    else
      new Node (element, a, b, empty)
}

/**
 * This class implements the leftist heap, see &quot;Functional Data
 * Structures&quot; by Chris Okasaki
 */
abstract class LeftistHeap[T, HC <: HeapCollector[T, HC]]
                          (implicit ord : Ordering[T])
               extends Iterable[T]
               with IterableLike[T, LeftistHeap[T, HC]] {

   /**
    * @return true iff this heap is empty
    */
   def isEmpty : Boolean

   /**
    * Length of the right spine, i.e. the length of the path from the
    * root to rightmost leaf
    */
   def rightHeight : Int

   /**
    * @return the minimum element of this heap, or raise an exception iff this
    * heap is empty (<code>isEmpty()==true</code>)
    */
   def findMin : T

   /**
    * @return the minimum element of this heap, or <code>None> iff this
    * heap is empty (<code>isEmpty()==true</code>)
    */
   def findMinOption : Option[T] = if (isEmpty) None else Some(findMin)

   /**
    * Remove the minimum element from this heap, or raise an exception iff this
    * heap is empty (<code>isEmpty()==true</code>)
    * @return a heap that contains all elements of this heap except
    * for the minimum element
    */
   def deleteMin : LeftistHeap[T, HC]
   
   /**
    * Construct an empty heap
    */
   protected def empty : LeftistHeap[T, HC]
   
   /**
    * Add an element to this heap object
    * @param element The element to be added
    * @return a heap that contains all elements of this heap, and
    * additionally <code>element</code>
    */
   def insert(element : T) : LeftistHeap[T, HC]

   /**
    * Add multiple elements to this heap object. We keep this method protected,
    * because otherwise one could use it to insert heaps that are sorted
    * differently
    * @param h a heap containing the elements to be added
    * @return a heap that contains all elements of this heap, and
    * additionally all objects from <code>h</code>
    */
   protected[basetypes] def insertHeap(h : LeftistHeap[T, HC]) : LeftistHeap[T, HC]

   /**
    * Add multiple elements to this heap object
    * @param elements the elements to be added
    * @return a heap that contains all elements of this heap, and
    * additionally all objects from <code>elements</code>
    */
   def insertIt(elements : Iterator[T]) : LeftistHeap[T, HC] = {
        // Use bottom-up strategy to compose new heap in O(n)

        val s = new scala.collection.mutable.Stack[LeftistHeap[T, HC]]
        s.push(this)
        while (elements.hasNext) {
            var h : LeftistHeap[T, HC] = new Node(elements.next, empty, empty, empty)
            while (!s.isEmpty && h.size >= s.top.size) h = h.insertHeap(s.pop)
            s.push(h)
        }
        var res : LeftistHeap[T, HC] = s.pop
        while (!s.isEmpty) res = res.insertHeap(s.pop);
        res
   }

   /**
    * @return an iterator that returns all elements of this heap
    */
   def unsortedIterator : Iterator[T] = new UnsortedIterator (this)
     
   /**
    * @return an iterator that returns all elements of this heap in
    * increasing order
    */
   def sortedIterator : Iterator[T] = new SortedIterator (this)

   /**
    * Remove all elements of this heap which are <code>equal</code>
    * to <code>element</code>.
    * @return heap that has all occurrences of <code>element</code>
    * removed
    */
   def removeAll(element : T) : LeftistHeap[T, HC]

   /////////////////////////////////////////////////////////////////////////////
   
   def iterator : Iterator[T] = unsortedIterator
   
   def +(el : T) : LeftistHeap[T, HC] = this.insert(el)
   
   def ++(els : Iterator[T]) : LeftistHeap[T, HC] = this.insertIt(els)
   
   def ++(els : Iterable[T]) : LeftistHeap[T, HC] = this.insertIt(els.iterator)

   protected[this] override def newBuilder =
     (new ArrayBuffer[T]) mapResult {
       (vals : Iterable[T]) => empty ++ vals
     }

   /////////////////////////////////////////////////////////////////////////////

   /**
    * Apply a function <code>f</code> to all elements in this heap. The heap
    * traversal is skipped for a subheap if the the function <code>stop</code>
    * applied to this subheap returns <code>true</code> 
    */
   def flatMap(f : (T) => Iterator[T],
               stop : (LeftistHeap[T, HC]) => Boolean) : LeftistHeap[T, HC] = {
      val todo = new scala.collection.mutable.Stack[LeftistHeap[T, HC]]
      var res = empty

      def push(h : LeftistHeap[T, HC]) = if (!h.isEmpty) todo push h
      
      push(this)
      
      while (!todo.isEmpty) {
        val next = todo.pop
        
        if (stop(next)) {
          res = res insertHeap next
        } else {
          next match {
            case Node(data, left, right, _) => {
              res = res insertIt f(data)
              push(left)
              push(right)
            }
          }
        }
      }
      
      res
   }
     
   /////////////////////////////////////////////////////////////////////////////

   /**
    * Interface for collecting information about the elements stored in the heap
    */
   def collector : HC
     
   /////////////////////////////////////////////////////////////////////////////

   override def toString : String = {
      val it=this.unsortedIterator
      val str = new StringBuffer("[")
      while (it.hasNext) {
        str.append(""+it.next)
        if (it.hasNext) {
          str.append(",")
        }
      }        
      str.append("]")
      str.toString()
   }

}

 

class UnsortedIterator[A, HC <: HeapCollector[A, HC]] extends Iterator[A] {
  
  private val remainder = new scala.collection.mutable.Stack[LeftistHeap[A, HC]]

  def this(heap : LeftistHeap[A, HC]) = {
    this()
    push(heap)
  }

  private def push(heap : LeftistHeap[A, HC]) : Unit =
    if (!heap.isEmpty) remainder.push(heap)

  def hasNext : Boolean = !remainder.isEmpty

  def next : A = {
    remainder.pop match {
      case Node(data, left, right, _) => {
        // descend in right-first order, this helps to keep the stack small
        push ( left )
        push ( right )
        data
      }
    }
  }
}



/**
 * Class for iterating the elements of a heap in increasing order
 */
class SortedIterator[A, HC <: HeapCollector[A, HC]](var remainder : LeftistHeap[A, HC])
      extends Iterator[A] {

  def hasNext : Boolean = !remainder.isEmpty

  def next : A = {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertPre ( LeftistHeap.AC, !remainder.isEmpty)
    //-END-ASSERTION-///////////////////////////////////////////////////////////
     
    val res = remainder.findMin
    remainder = remainder.deleteMin
    res
  }
}



  /**
   * Use this class to construct new heaps
   */
  class EmptyHeap[T, HC <: HeapCollector[T, HC]](val collector : HC)
                 (implicit ord : Ordering[T])
        extends LeftistHeap[T, HC] {

    /**
     * Length of the right spine, i.e. the length of the path from the
     * root to rightmost leaf
     */
    val rightHeight : Int = 0

    /**
     * @return the number of elements this heap holds
     */
    override val size : Int = 0

    /**
     * @return true iff this heap is empty
     */
    override def isEmpty : Boolean = true

    /**
     * Construct an empty heap
     */
    protected def empty : LeftistHeap[T, HC] = this
      
    /**
     * Add an element to this heap object
     * @param element The element to be added
     * @return a heap that contains all elements of this heap, and
     * additionally <code>element</code>
     */
    def insert(element : T) : LeftistHeap[T, HC] =
      LeftistHeap.node (element, this, this, this)

    /**
     * Add multiple elements to this heap object
     * @param h a heap containing the elements to be added
     * @return a heap that contains all elements of this heap, and
     * additionally all objects from <code>h</code>
     */
    protected[basetypes] def insertHeap(h : LeftistHeap[T, HC]) : LeftistHeap[T, HC] = h

    /**
     * @return the minimum element of this heap, or null iff this heap
     * is empty (<code>isEmpty()==true</code>)
     */
    def findMin : Nothing = throw new NoSuchElementException

    /**
     * Remove the minimum element from this heap
     * @return a heap that contains all elements of this heap except
     * for the minimum element
     */
    def deleteMin : LeftistHeap[T, HC] = throw new NoSuchElementException

    /**
     * Remove all elements of this heap which are <code>equal</code>
     * to <code>element</code>.
     * @return heap that has all occurrences of <code>element</code>
     * removed
     */
    def removeAll(element : T) : LeftistHeap[T, HC] = this

  }



 
/**
 * Class for non-empty heaps. We also keep a reference to the empty heap to
 * avoid creating new objects (ugly ... there should really be explicit 
 * Node-classes for nodes with no or only one child)
 */
case class Node[T, HC <: HeapCollector[T, HC]]
               (data : T,
                left : LeftistHeap[T, HC], right : LeftistHeap[T, HC],
                emptyHeap : LeftistHeap[T, HC])
               (implicit ord : Ordering[T])
           extends LeftistHeap[T, HC] {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(LeftistHeap.AC,
                   // the heap property
                   (left.isEmpty || ord.lteq(data, left.findMin)) &&
                   (right.isEmpty || ord.lteq(data, right.findMin)) &&
                   // the property of a leftist heap
                   right.rightHeight <= left.rightHeight)
  //-END-ASSERTION-/////////////////////////////////////////////////////////////
   
  /**
   * Length of the right spine, i.e. the length of the path from the
   * root to rightmost leaf
   */
  val rightHeight : Int = right.rightHeight + 1
  
  override val size : Int = left.size + right.size + 1

  /**
   * @return true iff this heap is empty
   */
  override def isEmpty : Boolean = false

  /**
   * Interface for collecting information about the elements stored in the heap
   */
  lazy val collector : HC = left.collector + (data, right.collector)

  /**
   * Construct an empty heap
   */
  protected def empty : LeftistHeap[T, HC] = emptyHeap

  /**
   * Add an element to this heap object
   * @param element the element to be added
   * @return a heap that contains all elements of this heap, and
   * additionally <code>element</code>
   */
  def insert(element : T) : LeftistHeap[T, HC] =
    if (ord.lteq(element, data))
      LeftistHeap.node(element, this, empty, empty)
    else
      LeftistHeap.node(data, left, right.insert(element), empty)

  /**
   * Add multiple elements to this heap object
   * @param h a heap containing the elements to be added
   * @return a heap that contains all elements of this heap, and
   * additionally all objects from <code>h</code>
   */
  protected[basetypes] def insertHeap(h : LeftistHeap[T, HC]) : LeftistHeap[T, HC] = h match {
    case _ : EmptyHeap[T, HC] => this
    case Node(hdata, hleft, hright, _) =>
      if (ord.lteq(data, hdata))
        LeftistHeap.node(data, left, right.insertHeap(h), empty)
      else
        LeftistHeap.node(hdata, hleft, this.insertHeap(hright), empty)
  }

  /**
   * @return the minimum element of this heap, or null iff this heap
   * is empty (<code>isEmpty()==true</code>)
   */
  def findMin : T = data

  /**
   * Remove the minimum element from this heap
   * @return a heap that contains all elements of this heap except
   * for the minimum element
   */
  def deleteMin : LeftistHeap[T, HC] = left.insertHeap(right)

  /**
   * Remove all elements of this heap which are <code>equal</code>
   * to <code>element</code>.
   * @return heap that has all occurrences of <code>element</code>
   * removed
   */
  def removeAll(element : T) : LeftistHeap[T, HC] = {
    val c = ord.compare(data, element)

    if ( c > 0 ) {
      this
    } else {
      val newLeft = left.removeAll(element)
      val newRight = right.removeAll(element)
            
      if ( c == 0 && data == element ) {
        newLeft.insertHeap ( newRight )
      } else {
        LeftistHeap.node(data, newLeft, newRight, empty)
      }
    }
  }

}

