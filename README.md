About SpinalHDL
============
SpinalHDL is a scala library that allows the user to describe his digital hardware and then generate the corresponding VHDL file.
## Advantages over VHDL/Verilog
- No restriction to the genericity of your hardware description by using Scala constructs
- No more endless wiring. Create and connect complex buses like AXI in one line.
- Evolving capabilities. Create your own buses definition and abstraction layer.
- Reduce code size by a high factor, especially for wiring. Allowing you to have a better visibility, more productivity and fewer headaches.
- Free and user friendly IDE. Thanks to scala world for auto-completion, error highlight, navigation shortcut and many others
- Extract information from your digital design and then generate files that contain information about some latency and addresses
- Bidirectional translation between any data type and bits. Useful to load a complex data structure from a CPU interface.
- Check for you that there is no combinational loop / latch
- Check that there is no user unintentional cross clock domain

Getting started
===============
## Links
- Documentation                  <br> http://spinalhdl.github.io/SpinalDoc/
- SBT base project               <br> https://github.com/SpinalHDL/SpinalBaseProject
- Presentation of the language   <br> https://github.com/SpinalHDL/SpinalDoc/tree/master/presentation/en (pptx has comments)

[![Join the chat at https://gitter.im/SpinalHDL/SpinalHDL](https://badges.gitter.im/SpinalHDL/SpinalHDL.svg)](https://gitter.im/SpinalHDL/SpinalHDL?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

## Basics
- VHDL backend : The digital hardware description is flushed into a synthesizable VHDL file
- Base Types : Bool, Bits, UInt, SInt, Enumeration
- Bundle : That allow you to describe a data structure with the possibility for each element to specify the direction (in,out). That is useful to describe bus
- Reg : Create a register signal
- Vec : That allow you to create an array of data
- Mem : Give the possibility to manipulate memory
- BlackBox : Allow you to instantiate a third party HDL component

## SBT

```scala
scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  "com.github.spinalhdl" % "spinalhdl-core_2.11" % "latest.release",
  "com.github.spinalhdl" % "spinalhdl-lib_2.11" % "latest.release"
)
```

## JAR

    https://oss.sonatype.org/content/groups/public/com/github/spinalhdl/spinalhdl-core_2.11/
    https://oss.sonatype.org/content/groups/public/com/github/spinalhdl/spinalhdl-lib_2.11/


Examples
===============
## Simple component

```scala
// spinal.core contain all basics (Bool, UInt, Bundle, Reg, Component, ..)
import spinal.core._  

//A simple component definition
class MyTopLevel extends Component {
  //Define some input/output. Bundle like a VHDL record or a verilog struct.
  val io = new Bundle {
    val a = in Bool
    val b = in Bool
    val c = out Bool
  }
  
  //Define some asynchronous logic
  io.c := io.a & io.b
}

//This is the main of the project. It create a instance of MyTopLevel and 
//call the SpinalHDL library to flush it into a VHDL file.
object MyMain {
  def main(args: Array[String]) {
    SpinalVhdl(new MyTopLevel)
  }
}
```

## Carry adder

```scala
import spinal.core._

class CarryAdder(size : Int) extends Component{
  val io = new Bundle{
    val a = in UInt(size bit)
    val b = in UInt(size bit)
    val result = out UInt(size bit)      //result = a + b
  }

  var c = False                   //Carry, like a VHDL variable
  for (i <- 0 until size) {
	//Create some intermediate value in the loop scope.
    val a = io.a(i)	 
    val b = io.b(i)  
	
	//The carry adder's asynchronous logic
    io.result(i) := a ^ b ^ c
    c = (a & b) | (a & c) | (b & c);    //variable assignment
  }
}


object CarryAdderProject {
  def main(args: Array[String]) {
    SpinalVhdl(new CarryAdder(4))
  }
}
```

## Counter with parity

```scala
class CounterWithParity(size : Int) extends Component{
  val io = new Bundle{
    val increment = in Bool
    val value = out UInt(size bit)
    val evenParity = out Bool
  }

  //Create a register of UInt(size-1 downto 0) 
  //init(0) force it to zero when a reset occur
  //In Spinal, you don't have to play with clock and reset signals each time
  //  you create register. You define a clock domain area and this is done.
  val counter = Reg(UInt(size bit)) init(0)
  when(io.increment){
    counter := counter + 1
  }
  
  //Get all bit of counter and then xor them together
  //toBools create a vector of Bool from each bit of counter
  //reduceLeft is a scala function that mix all bit together 
  //  from the left to the right
  io.evenParity := counter.toBools.reduceLeft(_ ^ _)   

  io.value := counter
}
```

## Interface / Custom data types / Multi clock system

```scala
// spinal.core contain all basics (Bool, UInt, Bundle, Reg, Component, ..)
import spinal.core._

// spinal.lib contain some abstraction built over the spinal.core like
//   utils          : log2up, toGray, fromGray, 
//                    majority vote, latency analysis (in cycle)
//   bus            : Flow      (valid, data)
//                    Stream (valid, ready, data)
//                    Flow or Stream of Fragment  ( .. , last, data),
//   abstraction    : counter, timeout,
//   implementation : stream fifo/arbiter/fork, UART controller
import spinal.lib._

//Define custom data types.
//You can use Bundle into Bundle
class MyDataType extends Bundle{
  val a = UInt(8 bit)
  val b = Bool
  val c = Vec(Bool,4) //Create a array of 4 Bool
}

class MultiClockTopLevel extends Component {
  val io = new Bundle {
    val clkA = in Bool
    val resetA = in Bool
    val clkB = in Bool
    val resetB = in Bool

    //Create stream interface (valid, ready, data) to transport MyDataType data
    val slaveInteface = slave Stream(new MyDataType)
    val masterInterface = master Stream(new MyDataType)
  }

  //Create clock domains from inputs clocks and resets
  val clockDomainA = ClockDomain(io.clkA,io.resetA)
  val clockDomainB = ClockDomain(io.clkB,io.resetB)

  //Create a fifo able to cross clock domain a stream of MyDataType
  val fifo = new StreamFifoCC(new MyDataType,16,clockDomainA,clockDomainB)
  fifo.io.push << io.slaveInteface    //Easy connection provided by Stream library
  fifo.io.pop >> io.masterInterface
}


object MultiClockTopLevel {
  def main(args: Array[String]) {
    SpinalVhdl(new MultiClockTopLevel)
  }
}
```

## Dual clock FIFO

```scala
import spinal.core._
import spinal.lib._

class StreamFifoCC[T <: Data](dataType: T, depth: Int, pushClockDomain: ClockDomain, popClockDomain: ClockDomain) extends Component {
  assert(isPow2(depth))
  assert(depth >= 2)

  val io = new Bundle {
    val push = slave Stream (dataType)
    val pop = master Stream (dataType)
	
    val pushOccupancy = out UInt (log2Up(depth) + 1 bit)
    val popOccupancy = out UInt (log2Up(depth) + 1 bit)
  }

  val ptrWidth = log2Up(depth) + 1
  
  //isFull and isEmpty take gray value as argument and return the corresponding state
  def isFull(a: Bits, b: Bits) = a(ptrWidth - 1, ptrWidth - 2) === ~b(ptrWidth - 1, ptrWidth - 2) && a(ptrWidth - 3, 0) === b(ptrWidth - 3, 0)
  def isEmpty(a: Bits, b: Bits) = a === b

  val ram = Mem(dataType, depth)

  val popToPushGray = Bits(ptrWidth bit)
  val pushToPopGray = Bits(ptrWidth bit)

  val pushLogic = new ClockingArea(pushClockDomain) {
	//Counter come from SpinalLib, 	
	//  It's a UInt register with some logic
    val pushPtr = Counter(depth << 1)	
	
	//RegNext is a way to create a register 
	//  that take the specified value each cycle
	//toGray is provided by the SpinalLib
    val pushPtrGray = RegNext(toGray(pushPtr.valueNext))
	
	//Spinal check that there is no unspecified cross clock domain
	//BufferCC is a simple 2 stage (default) register buffer from SpinalLib
	//  B"0" specify it initial value
    val popPtrGray = BufferCC(popToPushGray, B"0")
    val full = isFull(pushPtrGray, popPtrGray)

    io.push.ready := !full
	
	//fire is true when a transaction occur (valid && ready)
    when(io.push.fire) {  
      ram(pushPtr) := io.push.data
      pushPtr ++
    }
	
	//fromGray is provided by the SpinalLib
    io.pushOccupancy := pushPtr - fromGray(popPtrGray)
  }

  val popLogic = new ClockingArea(popClockDomain) {
    val popPtr = Counter(depth << 1)
    val popPtrGray = RegNext(toGray(popPtr.valueNext))
    val pushPtrGray = BufferCC(pushToPopGray, B"0")
    val empty = isEmpty(popPtrGray, pushPtrGray)

    io.pop.valid := !empty
    io.pop.data := ram.readSyncCC(popPtr.valueNext) //Cross clock domain synchronous read
    when(io.pop.fire) {
      popPtr ++
    }

    io.popOccupancy := fromGray(pushPtrGray) - popPtr
  }

  pushToPopGray := pushCC.pushPtrGray
  popToPushGray := popCC.popPtrGray
}
```

## Example of abstraction with bus

```scala

import spinal.core._
import spinal.lib._

//Define a data structure that is used as configuration of the LogicAnalyser
class LogicAnalyserConfig extends Bundle{
  val trigger = new Bundle{
    val delay = UInt(32 bit)
    ...
  }
  val logger = new Bundle{
    val samplesLeftAfterTrigger = UInt(8 bit)
    ...
  }
  ...
}


//Define the LogicAnalyser component
class LogicAnalyser extends Component {
  val io = new Bundle {
    ...
    //Flow is a very simple bus with the "valid" flag and "data".
    //Fragment is a data type that allow to transport packets with multiple fragment 
    //  => "last" flag and "fragment" data.
    //Flow Fragment(Bits(8 bit)) is a Flow bus that carry Fragment of 8 bit each
    //slavePort carry configuration of the LogicAnalyser
    val slavePort = slave Flow Fragment(Bits(8 bit))
    ...
  }
  ...

  //When the first fragment of one packet is 0x01, then take the next fragment 
  //  and save it as Bool into waitTrigger
  //In addition, the initial value of waitTrigger (register reset) is False
  val waitTrigger = io.slavePort filterHeader(0x01) toRegOf(Bool) init(False)
  
  //When the first fragment of one packet is 0x02, create a one cycle pulse on userTrigger
  val userTrigger = io.slavePort pulseOn(0x02)
  
  //When the first fragment of one packet is 0x0F, 
  //  load the configs data structure (LogicAnalyserConfig type)
  //The false argument mean that the configs register is allowed to 
  //  take intermediate value when it's unserialize from the slavePort.
  val configs = io.slavePort filterHeader(0x0F) toRegOf(new LogicAnalyserConfig,false)
  ... 
 }
```


## Create an custom bus, parametrized RGB data type,+ operator overloading, array of port management
```scala
import spinal.core._

//Define a Stream interface that use the AXI-like valid/ready handshake
//Transaction's data is a parameterizable type
//This Stream class is already defined into spinal.lib._   It's just to show as a example
case class Stream[T <: Data](dataType: T) extends Bundle with IMasterSlave {
  val valid = Bool
  val ready = Bool
  val data: T = cloneOf(dataType)

  //Equivalent to SystemVerilog modport
  override def asMaster(): this.type = {
    out(valid)
    in(ready)
    out(data)
    this
  }

  override def asSlave(): this.type = asMaster().flip //.flip reverse all signal direction
}

//Define a RGB color data type with parameterizable channel width
case class RGB(rWidth: Int, gWidth: Int, bWidth: Int) extends Bundle {
  val r = UInt(rWidth bit)
  val g = UInt(gWidth bit)
  val b = UInt(bWidth bit)

  //Define the + operator to allow the summation of 2 RGB
  def +(that: RGB): RGB = {
    val result = cloneOf(this)
    result.r := this.r + that.r
    result.g := this.g + that.g
    result.b := this.b + that.b
    result
  }
}

// Define a component that take "srcCount" slave Stream of RGB
// and product an "sumPort" that is the summation of all "srcPort" with the correct arbitration
case class StreamRgbAdder(rgbType: RGB, srcCount: Int) extends Component {
  val io = new Bundle {
    val srcPort = Vec(slave(Stream(rgbType)),srcCount)
    val sumPort = master(Stream(rgbType))
  }
  val transactionOccure = io.sumPort.valid && io.sumPort.ready
  io.sumPort.valid := io.srcPort.map(_.valid).reduce(_ && _)   //Take all srcPort.valid bits and "AND" them
  io.sumPort.data := io.srcPort.map(_.data).reduce(_ + _)      //Take all srcPort.data (RGB) and "SUM" them by using the overloaded + operator from RGB class
  io.srcPort.foreach(_.ready := transactionOccure)             //For each srcPort.ready assign the transactionOccure
}


def main(args: Array[String]) {
  SpinalVhdl(StreamRgbAdder(RGB(5, 6, 5), 4)) //Generate the VHDL for a 4 srcPort and a RGB config of 5,6,5 bits
}
```

## Why not Chisel ?
For many reason : http://spinalhdl.github.io/SpinalDoc/#what-are-the-differences-between-chisel-vs-spinal-

Other consideration
===============
Intellij scala plugin has some syntax highlight bug. As far i know, Intellij 14.1 is the best version. 

