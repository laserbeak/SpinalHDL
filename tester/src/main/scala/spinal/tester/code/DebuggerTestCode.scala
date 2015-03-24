/* SpinalHDL
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

package spinal.tester.code


import spinal.core._
import spinal.lib._
import spinal.debugger._
import spinal.lib.com.uart._


object DebuggerTestCode {


  class TopLevel extends Component {

    val io = new Bundle {
      val input = in UInt (4 bit)
      val output = out UInt (4 bit)

      val uart = master(new Uart)
    }

    val subComponentA = new SubComponentA
    subComponentA.io.input := io.input
    io.output := subComponentA.io.output



    val uartCtrl = new UartCtrl()
    uartCtrl.io.clockDivider := 100
    uartCtrl.io.config.dataLength := 7
    uartCtrl.io.config.parity := UartParityType.eParityNone
    uartCtrl.io.config.stop := UartStopType.eStop1bit
    uartCtrl.io.uart <> io.uart

    val logicAnalyserParameter = new LogicAnalyserParameter
    logicAnalyserParameter.probe(subComponentA.internalA)
    logicAnalyserParameter.probe(subComponentA.internalB)

    val logicAnalyser = new LogicAnalyser(logicAnalyserParameter)



    //uartCtrl.io.read >> logicAnalyser.io.packetSlave
    //uartCtrl.io.write << logicAnalyser.io.packetMaster
  }



  class SubComponentA extends Component{
    val io = new Bundle {
      val input = in UInt (4 bit)
      val output = out UInt (4 bit)
    }

    val internalA = RegInit(u"4x0")
    internalA := internalA + io.input

    val internalB = RegNext(internalA)

    io.output := internalA
  }

  def main(args: Array[String]) {
    println("START")
    SpinalVhdl(new TopLevel)
    println("DONE")
  }

}
