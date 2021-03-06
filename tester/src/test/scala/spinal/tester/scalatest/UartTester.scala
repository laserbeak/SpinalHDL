package spinal.tester.scalatest

import spinal.core._
import spinal.lib.com.uart._

class UartTester extends Component {
  val io = new Bundle {
    val uart = new UartCtrlIo(UartCtrlGenerics())
  }
  val uartCtrl = new UartCtrl(UartCtrlGenerics())
  io.uart <> uartCtrl.io
}



class UartTesterBoot extends SpinalTesterBase {
  override def getName: String = "UartTester"
  override def createToplevel: Component = new UartTester
}