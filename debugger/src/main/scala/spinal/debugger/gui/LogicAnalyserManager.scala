package spinal.debugger.gui


import java.util.Calendar

import net.liftweb.json.DefaultFormats
import net.liftweb.json.JsonAST.JValue
import spinal.debugger.{LogicAnalyser, LogicAnalyserParameter, Probe}
import spinal.lib.BitAggregator

import scala.collection.mutable.ArrayBuffer
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.control._
import scalafx.scene.layout.GridPane
import scalafx.stage.Stage

/**
 * Created by PIC on 22.04.2015.
 */


class LogicAnalyserManager(address: Seq[Byte], hal: IBytePacketHal, report: JValue) extends PeripheralManager(address, hal) {
  implicit val formats = DefaultFormats ++ LogicAnalyser.jsonSerDes
  val hardwareParameters = report.extract[LogicAnalyserParameter]

  init
  def init: Unit = {
    createGui
  }

  var captureOffsetMemo = 0
  var triggerDelayGuiMemo = 0
  override def rx(packet: Seq[Byte]): Unit = {
    var i = 0
    for (p <- packet.map("%02X" format _)) {
      if (i % 16 == 15)
        println(p)
      else
        print(p + " ")
      i += 1
    }


    val outFile = new java.io.FileWriter("logicAnalyser.vcd")
    val ret = new StringBuilder()
    ret ++= "$date\n"
    ret ++= s"  ${Calendar.getInstance().getTime()}\n"
    ret ++= "$end\n"
    ret ++= "$version\n"
    ret ++= s"  0.1\n"
    ret ++= "$end\n"
    ret ++= "$comment\n"
    ret ++= s"  Generated by Spinal LogicAnalyser\n"
    ret ++= "$end\n"
    ret ++= "$timescale 1ps $end\n"

    var uid = 0
    def allocUid: Int = {
      uid += 1
      uid - 1
    }
    val probeTags = scala.collection.mutable.Map[Probe, String]()
    class Scope(name: String) {
      val probes = ArrayBuffer[Probe]()
      val subScopes = scala.collection.mutable.Map[String, Scope]()

      def insert(probe: Probe, probeScope: Iterator[String]): Unit = {
        if (probeScope.hasNext) {
          val subScope = probeScope.next()
          subScopes.getOrElseUpdate(subScope, new Scope(subScope)).insert(probe, probeScope)
        } else {
          probes += probe
        }
      }


      def emit(ret: StringBuilder, tab: String): Unit = {
        for (probe <- probes) {
          val tag = "p" + allocUid
          probeTags += (probe -> tag)
          ret ++= s"$tab${"$"}var wire ${probe.width} $tag ${probe.name} ${"$"}end\n"
        }

        for ((name, scope) <- subScopes) {
          ret ++= s"$tab${"$"}scope module ${name} ${"$"}end\n"
          scope.emit(ret, tab + "  ")
          ret ++= s"$tab${"$"}upscope ${"$"}end\n"
        }
      }
    }
    val root = new Scope(null)

    for (probe <- hardwareParameters.probes) {
      root.insert(probe, probe.scope.iterator)
    }
    ret ++= s"${"$"}var wire 1 tr trigger ${"$"}end\n"
    ret ++= s"${"$"}var wire 32 ti time ${"$"}end\n"
    root.emit(ret, "")
//    ret ++= "$dumpvars\n"
//    for (probe <- hardwareParameters.probes) {
//      ret ++= s"  b${"x"*probe.width} ${probeTags(probe)}\n"
//    }
//    ret ++= "$end\n"

    ret ++= "$enddefinitions $end\n"

    val bytePerSample = (hardwareParameters.probes.foldLeft(0)(_ + _.width)+7)/8

    val triggerAt = hardwareParameters.memAddressCount-hardwareParameters.zeroSampleLeftAfterTriggerWindow - triggerDelayGuiMemo - captureOffsetMemo

    var time = 0
    val packetIterator = packet.iterator
    var byteLeft = packet.length
    val lastMap = collection.mutable.Map[Probe,String]()
    while (byteLeft >= bytePerSample) {
      ret ++= s"#$time\n"
      ret ++= s"  b${String.format("%32s", Integer.toBinaryString(time-triggerAt)).replace(' ', '0')} ti\n"
      if(time == 0) ret ++= s"  b0 tr\n"
      if(time == triggerAt) ret ++= s"  b1 tr\n"
      if(time == triggerAt + 1) ret ++= s"  b0 tr\n"
      var bits = packetIterator.take(bytePerSample).map(byte => String.format("%8s", Integer.toBinaryString(byte.toInt & 0xFF)).replace(' ', '0')).reduceLeft((l, r) => r + l)
      var bitsPtr = 0
      for (probe <- hardwareParameters.probes) {
        val newValue = bits.substring(bits.length-bitsPtr-probe.width,bits.length-bitsPtr)
        if(newValue != lastMap.getOrElseUpdate(probe,"?"))
          ret ++= s"  b${newValue} ${probeTags(probe)}\n"
        lastMap(probe) = newValue
        bitsPtr += probe.width
      }
      for(i <- 0 until bytePerSample) packetIterator.next()
      byteLeft -= bytePerSample
      time+=1
    }



    outFile.write(ret.result())
    outFile.flush();
    outFile.close();


  }

  def createGui: Unit = {
    Platform.runLater {
      var dialogStage: Stage = null
      dialogStage = new Stage {

        title = "Logic Analyser"
        scene = new Scene {
          content = new GridPane {
            padding = Insets(10)
            hgap = 5
            vgap = 5


            val openGui = new Button {
              text = "Capture"
              maxWidth = Double.MaxValue
              onAction = handle {
                triggerDelayGui.getEditor.onActionProperty.get().handle(null)
                captureOffset.getEditor.onActionProperty.get().handle(null)

                triggerDelayGuiMemo = triggerDelayGui.getValue
                captureOffsetMemo = hardwareParameters.memAddressCount/2 +  captureOffset.getValue -1

                val aggregator = new BitAggregator
                aggregator.add(BigInt(LogicAnalyser.configsHeader), 8)
                aggregator.add(BigInt(triggerDelayGuiMemo), 32)
                aggregator.add(BigInt(captureOffsetMemo), hardwareParameters.memAddressWidth)
                println(aggregator)
                tx(aggregator.toBytes)


                aggregator.clear
                aggregator.add(BigInt(LogicAnalyser.waitTriggerHeader), 8)
                aggregator.add(BigInt(1), 1)
                println(aggregator)
                tx(aggregator.toBytes)
              }
            }


            val triggerDelayGui = new Spinner[Integer](0, 1000000000, 0) {
              prefWidth = 100
              editable = true
            }

            val captureOffset = new Spinner[Integer](-hardwareParameters.memAddressCount / 2 + 10, hardwareParameters.memAddressCount / 2 - 10, 0) {
              prefWidth = 100
              editable = true
            }

            add(triggerDelayGui, 0, 0)
            add(captureOffset, 0, 1)
            add(openGui, 0, 2, 1, 1)
          }
        }
      }

      dialogStage.show()
    }
  }
}


