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

import scala.collection.mutable.ArrayBuffer

/**
  * Created by PIC18F on 02.02.2015.
  */
trait MemWriteToReadKind {

}

object writeFirst extends MemWriteToReadKind {
  override def toString: String = "writeFirst"
}

object readFirst extends MemWriteToReadKind {
  override def toString: String = "readFirst"
}

object dontCare extends MemWriteToReadKind {
  override def toString: String = "dontCare"
}

object Mem {
  def apply[T <: Data](wordType: T, wordCount: Int) = new Mem(wordType, wordCount)
  def apply[T <: Data](wordType: T, initialContent: Seq[T]) = new Mem(wordType, initialContent.length) init (initialContent)
  def apply[T <: Data](initialContent: Seq[T]) = new Mem(initialContent(0), initialContent.length) init (initialContent)
}

class MemWritePayload[T <: Data](dataType: T, addressWidth: Int) extends Bundle {
  val data = dataType.clone
  val address = UInt(addressWidth bit)
}

class Mem[T <: Data](_wordType: T, val wordCount: Int) extends NodeWithVariableInputsCount  with AttributeReady with Nameable {
  var forceMemToBlackboxTranslation = false
  val _widths = wordType.flatten.map(t => t.getWidth).toVector //Force to fix width of each wire

  def wordType: T = _wordType.clone

  override def calcWidth: Int = _wordType.flatten.map(_.calcWidth).reduceLeft(_ + _)

  def addressWidth = log2Up(wordCount)

  def setAsBlackBox(): this.type = {
    forceMemToBlackboxTranslation = true
    this
  }

  var initialContent: Seq[T] = null

  def init(initialContant: Seq[T]): this.type = {
    assert(initialContant.length == wordCount, s"The initial content if the rom doesn't fit with it word count, ${initialContant.length} vs $wordCount " + this.getScalaLocationLong)
    this.initialContent = initialContant
    this
  }

  def apply(address: UInt): T = {
    val ret = readAsync(address)

    ret.compositeAssign = new Assignable {
      override def assignFromImpl(that: AnyRef, conservative: Boolean): Unit = {
        assert(!conservative)
        write(address, that.asInstanceOf[T])
      }
    }
    ret
  }

  def addressType = UInt(addressWidth bit)

  def addressTypeAt(initialValue: BigInt) = U(initialValue, addressWidth bit)

  def readAsync(address: UInt, writeToReadKind: MemWriteToReadKind = dontCare): T = {
    val readBits = Bits(wordType.getBitsWidth bit)
    val readWord = wordType.clone()
    val addressBuffer = UInt(addressWidth bit).dontSimplifyIt()
    addressBuffer := address
    val readPort = new MemReadAsync(this, addressBuffer, readBits, writeToReadKind)
    readPort.compositeTagReady = readWord

    readBits.input = readPort
    readWord.assignFromBits(readBits)

    readWord
  }

  def readSync(address: UInt, enable: Bool = True, writeToReadKind: MemWriteToReadKind = dontCare, crossClock: Boolean = false): T = {
    val readBits = Bits(wordType.getBitsWidth bit)
    val readWord = wordType.clone()

    val addressBuffer = UInt(addressWidth bit).dontSimplifyIt()
    addressBuffer := address
    val readPort = new MemReadSync(this, address, addressBuffer, readBits, enable.dontSimplifyIt(), writeToReadKind, ClockDomain.current)
    readPort.compositeTagReady = readWord
    if (crossClock)
      readPort.addTag(crossClockDomain)

    readBits.input = readPort
    readWord.assignFromBits(readBits)

    readWord
  }

  def readSyncCC(address: UInt, enable: Bool = True, writeToReadKind: MemWriteToReadKind = dontCare): T = {
    readSync(address, enable, writeToReadKind, true)
  }

  def write(address: UInt, data: T, mask: Bits = null): Unit = {
    /*assert(mask == null, "Mem write mask currently not implemented by Spinal. You can either create a blackbox " +
      "or instantiate multiple memory instead")*/
    val addressBuffer = UInt(addressWidth bit).dontSimplifyIt()
    addressBuffer := address
    val dataBuffer = Bits(getWidth bit).dontSimplifyIt()
    dataBuffer := data.asBits

    val maskBuffer = if (mask != null) {
      val ret = Bits().dontSimplifyIt()
      ret := mask
      ret
    } else {
      null
    }


    val writePort = new MemWrite(this, address, addressBuffer, dataBuffer, maskBuffer, when.getWhensCond(this).dontSimplifyIt(), ClockDomain.current)
    inputs += writePort
  }

  // ASIC friendly single port ram
  def writeOrReadSync(address: UInt, writeData: T, chipSelect: Bool, writeEnable: Bool, writeToReadKind: MemWriteToReadKind = dontCare, crossClock: Boolean = false): T = {
    val addressBuffer = UInt(addressWidth bit).dontSimplifyIt()
    addressBuffer := address
    chipSelect.dontSimplifyIt()
    writeEnable.dontSimplifyIt()


    val dataBuffer = Bits(getWidth bit).dontSimplifyIt()
    dataBuffer := writeData.asBits
    val writePort = new MemWriteOrRead_writePart(this, addressBuffer, dataBuffer, chipSelect, writeEnable, ClockDomain.current)
    inputs += writePort


    val readBits = Bits(wordType.getBitsWidth bit)
    val readWord = wordType.clone()
    val readPort = new MemWriteOrRead_readPart(this, addressBuffer, readBits, chipSelect, writeEnable, writeToReadKind, ClockDomain.current)
    readPort.compositeTagReady = readWord
    readBits.input = readPort
    readWord.assignFromBits(readBits)
    if (crossClock)
      readPort.addTag(crossClockDomain)


    writePort.readPart = readPort;
    readPort.writePart = writePort

    readWord
  }

  override def addAttribute(attribute: Attribute): this.type = {
    attributes += attribute
    this
  }


  private[core] def getMemSymbolWidth() : Int = {
    var symbolWidth = getWidth
    var symbolWidthSet = false
    this.onEachInput(_ match{
      case port : MemWrite => {
        if(port.getMask != null){
          val portSymbolWidth = getWidth/port.getMask.getWidth
          if(symbolWidthSet){
            if(symbolWidth != portSymbolWidth) SpinalError(s"Mem with different asspect ratio at\n${this.getScalaLocationLong}")
          }else{
            symbolWidth = portSymbolWidth
            symbolWidthSet = true
          }
        }
      }
      case _ =>
    })
    symbolWidth
  }
  private[core] def getMemSymbolCount() : Int = getWidth/getMemSymbolWidth
}

class MemReadAsync(mem_ : Mem[_], address_ : UInt, data: Bits, val writeToReadKind: MemWriteToReadKind) extends Node {
  if (writeToReadKind == readFirst) SpinalError("readFirst mode for asynchronous read is not allowed")

  var address : Node = address_
  var mem     : Mem[_] = mem_

  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {
    doThat(address,0)
    doThat(mem,1)
  }
  override def onEachInput(doThat: (Node) => Unit): Unit = {
    doThat(address)
    doThat(mem)
  }

  override def setInput(id: Int, node: Node): Unit = id match{
    case 0 => address = node
    case 1 => mem = node.asInstanceOf[Mem[_]]
  }

  override def getInputsCount: Int = 2
  override def getInputs: Iterator[Node] = Iterator(address,mem)
  override def getInput(id: Int): Node = id match{
    case 0 => address
    case 1 => mem
  }


  def getData = data
  def getAddress = address.asInstanceOf[UInt]
  def getMem = mem.asInstanceOf[Mem[_]]
  override def calcWidth: Int = getMem.getWidth
}


object MemReadSync {
  def getAddressId: Int = 3
  def getEnableId: Int = 4
}

class MemReadSync(mem_ : Mem[_], val originalAddress: UInt, address_ : UInt, data: Bits, enable_ : Bool, val writeToReadKind: MemWriteToReadKind, clockDomain: ClockDomain) extends SyncNode(clockDomain) {
  var address : Node = address_
  var readEnable  : Node = enable_
  var mem     : Mem[_] = mem_

  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {
    doThat(clock,0)
    doThat(enable,1)
    doThat(reset,2)
    doThat(address,3)
    doThat(readEnable,4)
    doThat(mem,5)
  }
  override def onEachInput(doThat: (Node) => Unit): Unit = {
    doThat(clock)
    doThat(enable)
    doThat(reset)
    doThat(address)
    doThat(readEnable)
    doThat(mem)
  }

  override def setInput(id: Int, node: Node): Unit = id match{
    case 0 => clock = node
    case 1 => enable = node
    case 2 => reset = node
    case 3 => address = node
    case 4 => readEnable = node
    case 5 => mem = node.asInstanceOf[Mem[_]]
  }

  override def getInputsCount: Int = 6
  override def getInputs: Iterator[Node] = Iterator(clock,enable,reset,address,readEnable,mem)
  override def getInput(id: Int): Node = id match{
    case 0 => clock
    case 1 => enable
    case 2 => reset
    case 3 => address
    case 4 => readEnable
    case 5 => mem
  }



  override def getSynchronousInputs: ArrayBuffer[Node] = super.getSynchronousInputs ++= getMem :: getAddress :: getEnable :: Nil

  override def isUsingReset: Boolean = false

  def getData = data

  def getMem = mem

  def getAddress = address.asInstanceOf[UInt]
  def getEnable = readEnable.asInstanceOf[Bool]

  override def calcWidth: Int = getMem.calcWidth

  def useReadEnable: Boolean = {
    val lit = getEnable.getLiteral[BoolLiteral]
    return lit == null || lit.value == false
  }

  def sameAddressThan(write: MemWrite): Unit = {
    //Used by backed to symplify
    this.setInput(MemReadSync.getAddressId,write.getAddress)
  }

  //  override def normalizeInputs: Unit = {
  //    Misc.normalizeResize(this, MemReadSync.getAddressId, getMem.addressWidth)
  //  }

}


object MemWrite {
  def getAddressId: Int = 3
  def getDataId: Int = 4
  def getMaskId: Int = 5
  def getEnableId: Int = 6
}

class MemWrite(mem: Mem[_], val originalAddress: UInt, address_ : UInt, data_ : Bits, mask_ : Bits, enable_ : Bool, clockDomain: ClockDomain) extends SyncNode(clockDomain) {
  var address : Node  = address_
  var data     : Node = data_
  var mask     : Node = (if (mask_ != null) mask_ else NoneNode())
  var writeEnable  : Node  = enable_

  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {
    doThat(clock,0)
    doThat(enable,1)
    doThat(reset,2)
    doThat(address,3)
    doThat(data,4)
    doThat(mask,5)
    doThat(writeEnable,6)
  }

  override def onEachInput(doThat: (Node) => Unit): Unit = {
    doThat(clock)
    doThat(enable)
    doThat(reset)
    doThat(address)
    doThat(data)
    doThat(mask)
    doThat(writeEnable)
  }

  override def setInput(id: Int, node: Node): Unit = id match{
    case 0 => clock = node
    case 1 => enable = node
    case 2 => reset = node
    case 3 => address = node
    case 4 => data = node
    case 5 => mask = node
    case 6 => writeEnable = node
  }

  override def getInputsCount: Int = 7
  override def getInputs: Iterator[Node] = Iterator(clock,enable,reset,address,data,mask,writeEnable)
  override def getInput(id: Int): Node = id match{
    case 0 => clock
    case 1 => enable
    case 2 => reset
    case 3 => address
    case 4 => data
    case 5 => mask
    case 6 => writeEnable
  }



  override def getSynchronousInputs: ArrayBuffer[Node] = super.getSynchronousInputs ++= getAddress :: getData :: getEnable :: getInput(MemWrite.getMaskId) :: Nil

  override def isUsingReset: Boolean = false

  def getMem = mem
  def getAddress = address.asInstanceOf[UInt]
  def getData = data.asInstanceOf[Bits]
  def getEnable = writeEnable.asInstanceOf[Bool]

  def getMask: Bits = {
    if (mask.isInstanceOf[Bits])
      mask.asInstanceOf[Bits]
    else
      null
  }


  override def calcWidth: Int = getMem.calcWidth

  def useWriteEnable: Boolean = {
    val lit = getEnable.getLiteral[BoolLiteral]
    return lit == null || lit.value == false
  }

  override private[core] def checkInferedWidth: String = {
    if(getMask != null && getData.getWidth % getMask.getWidth != 0)
      return s"Memory write_data_width % write_data_mask_width != 0 at\n${this.getScalaLocationLong}"
    else
      null
  }
}

object MemWriteOrRead_writePart {
  def getAddressId: Int = 3

  def getDataId: Int = 4

  def getChipSelectId: Int = 5

  def getWriteEnableId: Int = 6
}

class MemWriteOrRead_writePart(mem: Mem[_], address_ : UInt, data_ : Bits, chipSelect_ : Bool, writeEnable_ : Bool, clockDomain: ClockDomain) extends SyncNode(clockDomain) {
  var address : Node  = address_
  var data     : Node = data_
  var chipSelect   : Node = chipSelect_
  var writeEnable  : Node  = writeEnable_

  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {
    doThat(clock,0)
    doThat(enable,1)
    doThat(reset,2)
    doThat(address,3)
    doThat(data,4)
    doThat(chipSelect,5)
    doThat(writeEnable,6)
  }

  override def onEachInput(doThat: (Node) => Unit): Unit = {
    doThat(clock)
    doThat(enable)
    doThat(reset)
    doThat(address)
    doThat(data)
    doThat(chipSelect)
    doThat(writeEnable)
  }

  override def setInput(id: Int, node: Node): Unit = id match{
    case 0 => clock = node
    case 1 => enable = node
    case 2 => reset = node
    case 3 => address = node
    case 4 => data = node
    case 5 => chipSelect = node
    case 6 => writeEnable = node
  }

  override def getInputsCount: Int = 7
  override def getInputs: Iterator[Node] = Iterator(clock,enable,reset,address,data,chipSelect,writeEnable)
  override def getInput(id: Int): Node = id match{
    case 0 => clock
    case 1 => enable
    case 2 => reset
    case 3 => address
    case 4 => data
    case 5 => chipSelect
    case 6 => writeEnable
  }

  var readPart: MemWriteOrRead_readPart = null

  override def getSynchronousInputs: ArrayBuffer[Node] = super.getSynchronousInputs ++= getAddress :: getData :: getChipSelect :: getWriteEnable :: Nil

  override def isUsingReset: Boolean = false

  def getMem = mem
  def getAddress = address.asInstanceOf[UInt]
  def getData = data.asInstanceOf[Bits]
  def getChipSelect = chipSelect.asInstanceOf[Bool]
  def getWriteEnable = writeEnable.asInstanceOf[Bool]
  override def calcWidth: Int = getMem.calcWidth

  //  def useWriteEnable: Boolean = {
  //    val lit = getEnable.getLiteral[BoolLiteral]
  //    return lit == null || lit.value == false
  //  }
}


object MemWriteOrRead_readPart {
  def getAddressId: Int = 3

  def getChipSelectId: Int = 4

  def getWriteEnableId: Int = 5
}

class MemWriteOrRead_readPart(mem_ : Mem[_], address_ : UInt, data_ : Bits, chipSelect_ : Bool, writeEnable_ : Bool, val writeToReadKind: MemWriteToReadKind, clockDomain: ClockDomain) extends SyncNode(clockDomain) {

  var address : Node  = address_
  var chipSelect     : Node = chipSelect_
  var writeEnable   : Node = writeEnable_
  var mem  : Mem[_]  = mem_

  override def onEachInput(doThat: (Node, Int) => Unit): Unit = {
    doThat(clock,0)
    doThat(enable,1)
    doThat(reset,2)
    doThat(address,3)
    doThat(chipSelect,4)
    doThat(writeEnable,5)
    doThat(mem,6)
  }

  override def onEachInput(doThat: (Node) => Unit): Unit = {
    doThat(clock)
    doThat(enable)
    doThat(reset)
    doThat(address)
    doThat(chipSelect)
    doThat(writeEnable)
    doThat(mem)
  }

  override def setInput(id: Int, node: Node): Unit = id match{
    case 0 => clock = node
    case 1 => enable = node
    case 2 => reset = node
    case 3 => address = node
    case 4 => chipSelect = node
    case 5 => writeEnable = node
    case 6 => mem = node.asInstanceOf[Mem[_]]
  }

  override def getInputsCount: Int = 7
  override def getInputs: Iterator[Node] = Iterator(clock,enable,reset,address,chipSelect,writeEnable,mem)
  override def getInput(id: Int): Node = id match{
    case 0 => clock
    case 1 => enable
    case 2 => reset
    case 3 => address
    case 4 => chipSelect
    case 5 => writeEnable
    case 6 => mem
  }



  var writePart: MemWriteOrRead_writePart = null

  override def getSynchronousInputs: ArrayBuffer[Node] = super.getSynchronousInputs ++= getMem :: getAddress :: getChipSelect :: getWriteEnable :: Nil

  override def isUsingReset: Boolean = false

  def getData = data_

  def getMem = mem
  def getAddress = address.asInstanceOf[UInt]
  def getChipSelect = chipSelect.asInstanceOf[Bool]
  def getWriteEnable = writeEnable.asInstanceOf[Bool]

  override def calcWidth: Int = getMem.calcWidth

  //  def useReadEnable: Boolean = {
  //    val lit = getEnable.getLiteral[BoolLiteral]
  //    return lit == null || lit.value == false
  //  }

}


//object MemReadOrWrite {
//  def getEnableId: Int = 3
//  def getWriteElseReadId: Int = 4
//  def getAddressId: Int = 5
//  def getWriteDataId: Int = 6
//
//}
//
//class MemWriteOrReadSync(mem: Mem[_], address: UInt, writeData: Bits, enable: Bool, writeElseRead: Bool, clockDomain: ClockDomain) extends SyncNode(clockDomain) {
//  inputs += enable
//  inputs += writeElseRead
//  inputs += address
//  inputs += writeData
//
//
//  override def getSynchronousInputs: ArrayBuffer[Node] = super.getSynchronousInputs ++= getAddress :: getWriteData :: getWriteEnable :: getMem :: getReadEnable :: Nil
//  override def isUsingReset: Boolean = false
//
//  def getMem = mem
//  def getAddress = getInput(MemReadOrWrite.getAddressId).asInstanceOf[UInt]
//  def getWriteData = getInput(MemReadOrWrite.getWriteDataId).asInstanceOf[Bits]
//  def getEnable = getInput(MemReadOrWrite.getEnableId).asInstanceOf[Bool]
//  def getWriteOrRead = getInput(MemReadOrWrite.getWriteElseReadId).asInstanceOf[Bool]
//
//  override def calcWidth: Int = getMem.calcWidth
//
//  def useWriteEnable: Boolean = {
//    val lit = getWriteEnable.getLiteral[BoolLiteral]
//    return lit == null || lit.value == false
//  }
//}


