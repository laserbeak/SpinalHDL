package spinal.lib.cpu.riscv.impl.extension

import spinal.core._
import spinal.lib.cpu.riscv.impl._
import spinal.lib.cpu.riscv.impl.Utils._
import spinal.lib._
import spinal.lib.bus.avalon._

/**
 * Created by PIC32F_USER on 12/04/2016.
 */


object DebugExtension{
  def getAvalonMMConfig = AvalonMMConfig.fixed(
    addressWidth = 8,
    dataWidth = 32).copy(
      useByteEnable = false,
      addressUnits = words
    )

  def avalonToDebugBus(avalon: AvalonMMBus,debug : DebugExtensionBus): Unit ={
    assert(avalon.c == getAvalonMMConfig)
    debug.cmd.valid := avalon.read || avalon.write
    debug.cmd.wr := avalon.write
    debug.cmd.address := avalon.address
    debug.cmd.data := avalon.writeData
    avalon.waitRequestn := True

    avalon.readData := debug.rsp.data
  }
}

case class DebugExtensionCmd() extends Bundle{
  val wr = Bool
  val address = UInt(8 bit)
  val data = Bits(32 bit)
}
case class DebugExtensionRsp() extends Bundle{
  val data = Bits(32 bit)
}

case class DebugExtensionBus() extends Bundle with IMasterSlave{
  val cmd = Stream(DebugExtensionCmd())
  val rsp = DebugExtensionRsp() //One cycle latency

  override def asMaster(): DebugExtensionBus.this.type = {
    master(cmd)
    in(rsp)
    this
  }
}

case class DebugExtensionIo() extends Bundle with IMasterSlave{
  val bus = DebugExtensionBus()
  val resetOut = Bool
  
  override def asMaster(): this.type = {
    master(bus)
    in(resetOut)
    this
  }
}

class DebugExtension(val clockDomain: ClockDomain) extends CoreExtension{
  var io : DebugExtensionIo = null
  override def applyIt(core: Core): Area = clockDomain(new Area{ //Can't use Clocking area because of scala error
    io = slave(DebugExtensionIo())

    val busReadDataReg = Reg(Bits(32 bit))
    io.bus.cmd.ready := True
    io.bus.rsp.data := busReadDataReg

    val readRegFileReg = RegNext(False)
    val resetIt = RegInit(False)
    val haltIt = RegInit(False)
    val flushIt = RegNext(False)
    val stepIt = RegInit(False)

      val isPipActive = RegNext(RegNext(core.iCmd.valid) || (core.fetch.pendingPrefetch =/= 0) ||  core.decode.inInst.valid ||  core.execute0.inInst.valid ||  core.execute1.inInst.valid || core.writeBack.inInst.valid)
    val isPipBusy = isPipActive || RegNext(isPipActive)
    val isInBreakpoint = core.writeBack.inInst.valid && isMyTag(core.writeBack.inInst.ctrl)


    when(io.bus.cmd.valid) {
      when(io.bus.cmd.address.msb){//access special register else regfile
        switch(io.bus.cmd.address(io.bus.cmd.address.high-1 downto 0)) {
          is(0){
            when(io.bus.cmd.wr){
              flushIt := io.bus.cmd.data(2)
              stepIt := io.bus.cmd.data(4)
              when(io.bus.cmd.data(16)){
                resetIt := True
              }
              when(io.bus.cmd.data(17)){
                haltIt := True
              }
              when(io.bus.cmd.data(24)){
                resetIt := False
              }
              when(io.bus.cmd.data(25)){
                haltIt := False
              }
            } otherwise{
              busReadDataReg(0) := resetIt
              busReadDataReg(1) := haltIt
              busReadDataReg(2) := isPipBusy
              busReadDataReg(3) := isInBreakpoint
              busReadDataReg(4) := stepIt
              busReadDataReg(5) := core.prefetch.inc
            }
          }
          is(1){
            when(io.bus.cmd.wr){
              core.prefetch.pc := io.bus.cmd.data.asUInt
              core.prefetch.inc := False
            } otherwise{
              when(isInBreakpoint){
                busReadDataReg := core.writeBack.inInst.pc.asBits
              } otherwise{
                busReadDataReg := core.prefetch.pc.asBits
              }
            }
          }
          is(2){
            when(io.bus.cmd.wr){
              val injectedInstructionSent = RegNext(core.decode.inInst.fire) init(False)
              core.decode.inInst.valid.getDrivingReg := !injectedInstructionSent
              core.decode.inInst.instruction.getDrivingReg := io.bus.cmd.data
              io.bus.cmd.ready := injectedInstructionSent
            }
          }
        }
      } otherwise{
        when(io.bus.cmd.wr){
          core.writeBack.regFileWrite.valid := True
          core.writeBack.regFileWrite.address := io.bus.cmd.address(core.writeBack.regFileWrite.address.range)
          core.writeBack.regFileWrite.data := io.bus.cmd.data
        } otherwise {
          core.decode.regFileReadAddress0 := io.bus.cmd.address(core.writeBack.regFileWrite.address.range)
          core.c.regFileReadyKind match{
            case `async` => busReadDataReg := core.decode.src0
            case `sync` => readRegFileReg := True
          }
        }
      }
    }

    //Keep the execution pipeline empty after break instruction
    when(core.execute1.inInst.valid && isMyTag(core.execute1.inInst.ctrl)){
      core.execute0.halt := True
    }

    when(isInBreakpoint){
      core.execute0.halt := True
      core.writeBack.halt := True
    }

    when(flushIt) {
      core.writeBack.flush := True
    }

    when(readRegFileReg){
      io.bus.rsp.data := core.decode.src0
    }

    when(haltIt){
      core.prefetch.halt := True
    }

    when(stepIt && core.iCmd.fire){
      haltIt := True
    }

    io.resetOut := RegNext(resetIt)
  })

  override def needTag: Boolean = true

  override def getName: String = "DebugExtension"

  override def instructionCtrlExtension(instruction: Bits, ctrl: InstructionCtrl): Unit = {
    when(instruction === 0x00100073){
      applyTag(ctrl)
    }
  }
}