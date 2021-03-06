/*
 * SpinalHDL
 * Copyright (c) Dolu, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package spinal.core

import spinal.core.Operator.BitVector.{ShiftLeftByUInt, ShiftLeftByInt, ShiftRightByUInt, ShiftRightByInt}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


object SymplifyNode {
  def replaceNode(it: Node, by: Node): Unit = {
    for (consumer <- it.consumers) {
      for (i <- 0 until consumer.getInputsCount) {
        if (consumer.getInput(i) == it) {
          consumer.setInput(i,by)
          by.consumers += consumer
        }
      }
    }
  }

  def replaceNodeInput(it: Node,inId : Int,by : Node): Unit ={
    it.getInput(inId).consumers -= it
    it.setInput(inId,by)
    by.consumers += it
  }

  def replaceNode(it: Node, by: Int): Unit = {
    replaceNode(it, it.getInput(by))
  }

  def none(node: Node): Unit = {}

  def binaryPartition(node: Node): (Node, Node) = {
    if (node.getInput(0).getWidth == 0) {
      return (node.getInput(0), node.getInput(1))
    }
    if (node.getInput(1).getWidth == 0) {
      return (node.getInput(1), node.getInput(0))
    }
    return null
  }

  def binaryTakeOther(node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if (w0 == 0) {
      replaceNode(node, 1)
    } else if (w1 == 0) {
      replaceNode(node, 0)
    }
  }

  def binaryUIntSmaller(node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if (w0 == 0 && w1 == 0) {
      Component.push(node.component)
      replaceNode(node, False)
      Component.pop(node.component)
    } else if (w0 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,0,U(0,w1 bit))
      Component.pop(node.component)
    } else if (w1 == 0) {
      Component.push(node.component)
      replaceNode(node, False)
      Component.pop(node.component)
    }
  }

  def binaryUIntSmallerOrEgual(node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if (w0 == 0 && w1 == 0) {
      Component.push(node.component)
      replaceNode(node, True)
      Component.pop(node.component)
    } else if (w0 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,0,U(0,w1 bit))
      Component.pop(node.component)
    } else if (w1 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,1,U(0,w0 bit))
      Component.pop(node.component)
    }
  }

  def binarySIntSmaller(node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if (w0 == 0 && w1 == 0) {
      Component.push(node.component)
      replaceNode(node, False)
      Component.pop(node.component)
    } else if (w0 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,0,S(0,w1 bit))
      Component.pop(node.component)
    } else if (w1 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,1,S(0,w0 bit))
      Component.pop(node.component)
    }
  }

  def binarySIntSmallerOrEgual(node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if (w0 == 0 && w1 == 0) {
      Component.push(node.component)
      replaceNode(node, True)
      Component.pop(node.component)
    } else if (w0 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,0,S(0,w1 bit))
      Component.pop(node.component)
    } else if (w1 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,1,S(0,w0 bit))
      Component.pop(node.component)
    }
  }


  def binaryMinus(zeroFactory: (BigInt, BitCount) => Node)(node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if(w1 == 0) {
      replaceNode(node,0)
    } else if (w0 == 0) {
      Component.push(node.component)
      replaceNodeInput(node,0,zeroFactory(0,w1 bit))
      Component.pop(node.component)
    }
  }

  def binaryInductZeroWithOtherWidth(zeroFactory: (BigInt, BitCount) => Node)(node: Node): Unit = {
    val partition = binaryPartition(node)
    if (partition != null) {
      Component.push(node.component)
      replaceNode(node, zeroFactory(0, partition._2.getWidth bit))
      Component.pop(node.component)
    }
  }


  def unsignedDivImpl(node: Node) : Unit = {
    //TODO
  }
  def unsignedModImpl(node: Node) : Unit = {
    //TODO
  }
  def signedDivImpl(node: Node) : Unit = {
    //TODO
  }
  def signedModImpl(node: Node) : Unit = {
    //TODO
  }


  def resizeImpl2(zeroFactory: (BigInt, BitCount) => Node,node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    if (w0 == 0) {
      Component.push(node.component)
      replaceNode(node, zeroFactory(0, node.getWidth bit))
      Component.pop(node.component)
    }
  }

  def shiftRightImpl(node: ShiftRightByUInt): Unit = {
    if (node.right.getWidth == 0) {
      Component.push(node.component)
      replaceNode(node, 0)
      Component.pop(node.component)
    }
  }

  def shiftRightImpl(node: ShiftRightByInt): Unit = {
    if (node.shift == 0) {
      Component.push(node.component)
      replaceNode(node, 0)
      Component.pop(node.component)
    }
  }

  def shiftLeftImpl(zeroFactory: (BigInt, BitCount) => Node,node: ShiftLeftByUInt): Unit = {
    if (node.left.getWidth == 0) {
      Component.push(node.component)
      replaceNode(node, zeroFactory(0, node.getWidth bit))
      Component.pop(node.component)
    } else if (node.right.getWidth == 0) {
      Component.push(node.component)
      replaceNode(node, 0)
      Component.pop(node.component)
    }
  }

  def shiftLeftImpl(zeroFactory: (BigInt, BitCount) => Node,node: ShiftLeftByInt): Unit = {
    if (node.input.getWidth == 0) {
      Component.push(node.component)
      replaceNode(node, zeroFactory(0, node.getWidth bit))
      Component.pop(node.component)
    } else if (node.shift == 0) {
      Component.push(node.component)
      replaceNode(node, 0)
      Component.pop(node.component)
    }
  }

  def rotateImpl(zeroFactory: (BigInt, BitCount) => Node,node: Node): Unit = {
    val w0 = node.getInput(0).getWidth
    val w1 = node.getInput(1).getWidth
    if (w0 == 0) {
      Component.push(node.component)
      replaceNode(node, zeroFactory(0, node.getWidth bit))
      Component.pop(node.component)
    } else if (w1 == 0) {
      Component.push(node.component)
      replaceNode(node, 0)
      Component.pop(node.component)
    }
  }


  def multiplexerImpl(node: Node): Unit = {
//    val w0 = node.getInput(1).getWidth
//    val w1 = node.getInput(2).getWidth
//    if (w0 == 0) {
//      replaceNode(node, 2)
//    } else if (w1 == 0) {
//      replaceNode(node, 1)
//    }
  }


  def binaryThatIfBoth(thatFactory: => Node)(node: Node): Unit = {
    if (node.getInput(0).getWidth == 0 && node.getInput(1).getWidth == 0) {
      Component.push(node.component)
      replaceNode(node, thatFactory)
      Component.pop(node.component)
    }
  }


  def unaryShortCut(node: Node): Unit = {
    if (node.getInput(0).getWidth == 0) {
      replaceNode(node, 0)
    }
  }
  def unaryZero(node: Node): Unit = {
    if (node.getInput(0).getWidth == 0) {
      Component.push(node.component)
      replaceNode(node, U(0, 0 bit))
      Component.pop(node.component)
    }
  }
}

object InputNormalize {
  def none(node: Node): Unit = {

  }

  def bitVectoreAssignement(parent : Node,inputId : Int,targetWidth : Int): Unit ={
    val input = parent.getInput(inputId)
    if(input == null) return
      input match{
      case bitVector : BitVector => {
        bitVector.getInput(0) match{
          case lit : BitsLiteral if (! lit.hasSpecifiedBitCount) =>
            Misc.normalizeResize(parent, inputId, Math.max(lit.minimalValueBitWidth,targetWidth)) //Allow resize on direct literal with unfixed values
          case _ =>
            if(input.hasTag(tagAutoResize))
              Misc.normalizeResize(parent, inputId, targetWidth)
        }
      }
      case _ =>
    }
  }

  def regImpl(node: Node): Unit = {
    val targetWidth = node.getWidth
    InputNormalize.bitVectoreAssignement(node, RegS.getDataInputId, targetWidth)
    //Misc.normalizeResize(node, RegS.getDataInputId, targetWidth)
    if (node.asInstanceOf[Reg].isUsingReset) InputNormalize.bitVectoreAssignement(node, RegS.getInitialValueId, targetWidth)
  }

  def memReadImpl(node: Node): Unit = {
    //not here
    //Misc.normalizeResize(node, MemReadSync.getAddressId, node.asInstanceOf[].addressWidth)
  }

  def memWriteImpl(node: Node): Unit = {
    Misc.normalizeResize(node, MemWrite.getDataId, node.getWidth)
    Misc.normalizeResize(node, MemWrite.getAddressId, node.asInstanceOf[Mem[_]].addressWidth)
  }

  def enumImpl[T <: SpinalEnum](node : Node) : Unit = {
    val left = node.getInput(0).asInstanceOf[SpinalEnumCraft[T]]
    val right = node.getInput(1).asInstanceOf[SpinalEnumCraft[T]]
    if(left.encoding != right.encoding){
      val (that,ref,thatId) = if(left.getWidth > right.getWidth) (left,right,0) else  (right,left,1)
      Component.push(that.component)
      val newOne = ref.clone.asInstanceOf[SpinalEnumCraft[T]]
      newOne.assignFromAnotherEncoding(that)
      node.setInput(thatId,newOne)
      Component.pop(that.component)
    }
  }

  def nodeWidth(node: Node): Unit = {
    val targetWidth = node.getWidth
    for (i <- 0 until node.getInputsCount)
      Misc.normalizeResize(node, i, targetWidth)
  }

  def inputWidthMax(node: Node): Unit = {
    val targetWidth = Math.max(node.getInput(0).getWidth, node.getInput(1).getWidth)
    for (i <- 0 until node.getInputsCount)
      Misc.normalizeResize(node, i, targetWidth)
  }
}

object WidthInfer {
  def multipleAssignmentNodeWidth(node: Node): Int = {
    node.getInputs.foldLeft(-1)((best, n) => Math.max(best, if (n != null && !n.isInstanceOf[Reg]) n.getWidth else -1))
  }

  def inputMaxWidth(node: Node): Int = {
    node.getInputs.foldLeft(-1)((best, n) => Math.max(best, if (n != null) n.getWidth else -1))
  }

  def multiplexImpl(node: Node): Int = {
    Math.max(node.getInput(1).getWidth, node.getInput(2).getWidth)
  }

  def regImpl(node: Node): Int = {
    val dataIn = node.getInput(RegS.getDataInputId)
    val init = node.getInput(RegS.getInitialValueId)

    math.max(if (dataIn != node) dataIn.getWidth else -1, if (node.asInstanceOf[Reg].isUsingReset) init.getWidth else -1)
  }

  def cumulateInputWidth(node: Node): Int = {
    node.getInputs.foldLeft(0)((old, n) => old + Math.max(0, n.getWidth))
  }


  def input0Width(node: Node): Int = {
    node.getInput(0).getWidth
  }

  def shiftLeftWidth(node: Node): Int = node.getInput(0).getWidth + node.getInput(1).asInstanceOf[MinMaxProvider].maxValue.toInt
  def shiftRightWidth(node: Node): Int = Math.max(0, node.getInput(0).getWidth - node.getInput(1).asInstanceOf[MinMaxProvider].minValue.toInt)


  def oneWidth(node: Node): Int = 1

}

object Node{

  def walk(starts: Seq[Node],walker: (Node, (Node) => Unit) => Unit): Unit = {
    val targetAlgoId = GlobalData.get.allocateAlgoId()
    val pendingNodes = mutable.Stack[Node]()

    def addNodeToStack(node: Node): Unit = {
      if(node != null && node.component != null && node.algoId != targetAlgoId) {
        pendingNodes.push(node)
        node.algoId = targetAlgoId
      }
    }

    starts.foreach(addNodeToStack(_))
    while (!pendingNodes.isEmpty) {
      walker(pendingNodes.pop, addNodeToStack)
    }

  }


  def walk(starts: Seq[Node],walker: (Node) => Unit): Unit = {
    walk(starts,(node,push) => {
      walker(node)
      node.onEachInput(push(_))
    })
  }
}


abstract class NodeWithVariableInputsCount extends Node{
  val inputs = new ArrayBuffer[Node](4)

  override def getInputsCount = inputs.length
  override def getInput(id : Int) : Node = inputs(id)
  override def setInput(id : Int,node : Node) : Unit = inputs(id) = node

  override def getInputs : Iterator[Node] = inputs.iterator

  override def onEachInput(doThat : (Node,Int) => Unit) : Unit = {
    var idx = getInputsCount
    while(idx != 0){
      idx -= 1
      doThat(getInput(idx),idx)
    }
  }

  override def onEachInput(doThat : (Node) => Unit) : Unit = {
    var idx = getInputsCount
    while(idx != 0){
      idx -= 1
      doThat(getInput(idx))
    }
  }
}

abstract class NodeWithoutInputs extends Node{
  override def getInput(id: Int): Node = ???
  override def getInputs: Iterator[Node] = Iterator()
  override def getInputsCount: Int = 0
  override def onEachInput(doThat: (Node) => Unit): Unit = {}
  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {}
  override def setInput(id: Int, node: Node): Unit = ???
}

abstract class Node extends ContextUser with ScalaLocated with SpinalTagReady with GlobalDataUser {
  val consumers = new ArrayBuffer[Node](4)

  def getInputsCount : Int
  def getInput(id : Int) : Node
  def setInput(id : Int,node : Node) : Unit

  def getInputs : Iterator[Node]
  def onEachInput(doThat : (Node,Int) => Unit) : Unit
  def onEachInput(doThat : (Node) => Unit) : Unit

  private[core] var algoId = 0
  private[core] var widthWhenNotInferred = -1

  def getWidth: Int = {
    if (globalData.nodeAreInferringWidth) {
      inferredWidth
    } else {
      val isFirst = globalData.nodeGetWidthWalkedSet.isEmpty
      if (globalData.nodeGetWidthWalkedSet.contains(this))
        SpinalError(s"Can't calculate width of $this when design is in construction phase")

      globalData.nodeGetWidthWalkedSet += this
      var temp: Int = 0;
      if (isFirst) {
        try {
          temp = calcWidth
        } catch {
          case e: Exception => {
            globalData.nodeGetWidthWalkedSet.clear()
            throw e
          }
        }
      } else {
        temp = calcWidth
      }

      if (temp == -1) {
        globalData.nodeGetWidthWalkedSet.clear()
        SpinalError(s"Can't infer width because of unspecified width on ${this.getScalaLocationLong}")
      }

      globalData.nodeGetWidthWalkedSet -= this

      if (isFirst) globalData.nodeGetWidthWalkedSet.clear()
      if (widthWhenNotInferred != -1 && widthWhenNotInferred != temp) SpinalError(s"getWidth result differ from last call $getScalaLocationLong")
      widthWhenNotInferred = temp
      temp
    }
  }

  private[core] def calcWidth: Int


  private[core] def inferWidth: Boolean = {
    val newWidth: Int = calcWidth
    if (newWidth == -1) {
      return true
    } else if (newWidth != inferredWidth) {
      inferredWidth = newWidth
      return true;
    } else {
      return false
    }
  }


  private[core] var inferredWidth = -1

  private[core] def checkInferedWidth: String = null

  private[core] def normalizeInputs: Unit = {}

  def simplifyNode: Unit = {}



  private[core] def getOutToInUsage(inputId : Int,outHi : Int, outLo : Int) : (Int,Int)= (getInput(inputId).getWidth-1,0)
  private[core] def getClassIdentifier: String = this.getClass.getSimpleName

  private[core] def isInBlackBoxTree = component.isInBlackBoxTree

  private[core] def nonRecursiveToString(): String = {
    toString()
  }

  override def toString(): String = s"${super.toString()}[$getWidth bit]"
}

object NoneNode {
  def apply() = new NoneNode
}

class NoneNode extends NodeWithoutInputs {
  override def calcWidth: Int = 0

  override private[core] def getOutToInUsage(inputId: Int, outHi: Int, outLo: Int): (Int, Int) = (-1,0)
}



abstract class DontCareNode extends NodeWithoutInputs{
  def getBaseType : BaseType
}

class DontCareNodeInfered(target : BaseType) extends DontCareNode {
  override def calcWidth: Int = target.getWidth
  override def getBaseType: BaseType = target
}
class DontCareNodeFixed(target : BaseType,fixedWidth : Int) extends DontCareNode {
  override def calcWidth: Int = fixedWidth
  override def getBaseType: BaseType = target
}



trait AssignementTreePart{
  def setAssignementContext(id : Int,that : Throwable) : Unit
  def getAssignementContext(id : Int) : Throwable
}