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

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, StringBuilder, HashMap}
import scala.util.Random


/**
  * Created by PIC18F on 07.01.2015.
  */

trait MemBitsMaskKind
object MULTIPLE_RAM extends MemBitsMaskKind
object SINGLE_RAM extends MemBitsMaskKind

class VhdlBackend extends Backend with VhdlBase {
  var out: java.io.FileWriter = null
  var enumPackageName = "pkg_enum"
  var packageName = "pkg_scala2hdl"
  var outputFilePath: String = null
  var onlyStdLogicVectorTopLevelIo = false
  var memBitsMaskKind : MemBitsMaskKind = MULTIPLE_RAM

  val emitedComponent = mutable.Map[ComponentBuilder, ComponentBuilder]()
  val emitedComponentRef = mutable.Map[Component, Component]()

  reservedKeyWords ++= vhdlKeyWords

  override protected def elaborate[T <: Component](topLevel: T): BackendReport[T] = {
    val report = super.elaborate(topLevel)
    SpinalInfoPhase("Write VHDL")

    if (outputFilePath == null) outputFilePath = topLevel.definitionName + ".vhd"
    if (jsonReportPath == "") jsonReportPath = outputFilePath.replace(".vhd",".json")

    out = new java.io.FileWriter(outputFilePath)
    emitEnumPackage(out)
    emitPackage(out)

    for (c <- sortedComponents) {
      SpinalInfoPhase(s"${"  " * (1 + c.level)}emit ${c.definitionName}")
      compile(c)
    }

    out.flush();
    out.close();

    //  emitTestBench(topLevel :: Nil,topLevel.definitionName + "_tb")

    report
  }


  def compile(component: Component): Unit = {
    val text = emit(component)
    out.write(text)
  }

  class ComponentBuilder(val component: Component) {
    val parts = ArrayBuffer[(StringBuilder, Boolean)]()

    def newPart(mustMatch: Boolean): StringBuilder = {
      val builder = new mutable.StringBuilder
      parts += (builder -> mustMatch)
      builder
    }

    def result: String = {
      val ret = new mutable.StringBuilder
      parts.foreach(ret ++= _._1)
      ret.result()
    }

    var hash: Integer = null

    override def hashCode(): Int = {
      if (hash == null) {
        hash = parts.filter(_._2).foldLeft(0)(_ + _._1.result().hashCode())
      }
      hash
    }

    override def equals(obj: scala.Any): Boolean = {
      if (this.hashCode() != obj.hashCode()) return false //Colision into hashmap implementation don't check it XD
      obj match {
        case cb: ComponentBuilder => {
          for ((a, b) <- (parts, cb.parts).zipped) {
            if (a._2 || b._2) {
              if (a._1.result() != b._1.result()) {
                return false
              }
            }
          }
          return true;
        }
        case _ => return ???
      }
    }
  }

  case class WrappedStuff(originalName: String, newName: String)

  def ioStdLogicVectorWrapNames(): HashMap[BaseType, WrappedStuff] = {
    val map = HashMap[BaseType, WrappedStuff]()
    def wrap(that: BaseType): Unit = {
      val newName = that.getName() + "_wrappedName"
      map(that) = WrappedStuff(that.getName, newName)
      that.setName(newName)
    }
    for (e <- topLevel.getAllIo) e match {
      case e: UInt => wrap(e)
      case e: SInt => wrap(e)
      case _ =>
    }
    map
  }

  def ioStdLogicVectorRestoreNames(map: HashMap[BaseType, WrappedStuff]): Unit = {
    if (map != null) {
      for ((e, w) <- map) e.setName(w.originalName)
    }
  }

  def emit(component: Component): String = {
    val ret = new StringBuilder()
    val builder = new ComponentBuilder(component)

    emitLibrary(builder)
    emitEntity(component, builder)
    emitArchitecture(component, builder)

    val oldBuilder = emitedComponent.getOrElse(builder, null)
    if (oldBuilder == null) {
      emitedComponent += (builder -> builder)
      return builder.result
    } else {
      emitedComponentRef += (component -> oldBuilder.component)
      return s"\n--${component.definitionName} remplaced by ${oldBuilder.component.definitionName}\n\n"
    }
  }

  def getReEncodingFuntion(spinalEnum: SpinalEnum, source: SpinalEnumEncoding, target: SpinalEnumEncoding): String = {
    s"${spinalEnum.getName()}_${source.getName()}_to_${target.getName()}"
  }

  def getEnumToDebugFuntion(spinalEnum: SpinalEnum, source: SpinalEnumEncoding): String = {
    assert(!source.isNative)
    s"${spinalEnum.getName()}_${source.getName()}_to_debug"
  }

  def getEnumDebugType(spinalEnum: SpinalEnum): String = {
    s"${spinalEnum.getName()}_debug"
  }

  def emitEnumPackage(out: java.io.FileWriter): Unit = {
    val ret = new StringBuilder();
    ret ++=
      s"""library IEEE;
          |use IEEE.STD_LOGIC_1164.ALL;
          |use IEEE.NUMERIC_STD.all;
          |use ieee.math_real.all;
          |
               |package $enumPackageName is
          |""".stripMargin
    for (enumDef <- enums.keys) {
      ret ++= s"  type ${enumDef.getName()} is (${enumDef.values.map(_.getName()).reduce(_ + "," + _)});\n"
      ret ++= s"  type ${getEnumDebugType(enumDef)} is (${enumDef.values.foldLeft("XXX")((str, e) => str + "," + e.getName())});\n"
    }

    ret ++= "\n"
    for ((enumDef, encodings) <- enums) {
      val enumName = enumDef.getName()
      ret ++= s"  function pkg_mux (sel : std_logic;one : $enumName;zero : $enumName) return $enumName;\n"


      for (encoding <- encodings if !encoding.isNative) {
        val encodingName = encoding.getName()
        val bitCount = encoding.getWidth(enumDef)
        val vhdlEnumType = emitEnumType(enumDef, encoding)
        ret ++= s"  subtype $vhdlEnumType is std_logic_vector(${bitCount - 1} downto 0);\n"
        for (element <- enumDef.values) {
          ret ++= s"  constant ${emitEnumLiteral(element, encoding)} : $vhdlEnumType := ${idToBits(element, encoding)};\n"
        }
        ret ++= "\n"
        //ret ++= s"  function pkg_to${enumName}_debug (value : std_logic_vector) return $enumName;\n"
      }
      for (encoding <- encodings) {
        if (!encoding.isNative)
          ret ++= s"  function ${getEnumToDebugFuntion(enumDef, encoding)} (value : ${emitEnumType(enumDef, encoding)}) return ${getEnumDebugType(enumDef)};\n"
        else {
          ret ++= s"  function pkg_toStdLogicVector_${encoding.getName()} (value : $enumName) return std_logic_vector;\n"
          ret ++= s"  function pkg_to${enumName}_${encoding.getName()} (value : std_logic_vector(${encoding.getWidth(enumDef) - 1} downto 0)) return $enumName;\n"
        }

        for (targetEncoding <- encodings if targetEncoding != encoding && !(targetEncoding.isNative && encoding.isNative)) {
          ret ++= s"  function ${getReEncodingFuntion(enumDef, encoding, targetEncoding)} (that : ${emitEnumType(enumDef, encoding)}) return ${emitEnumType(enumDef, targetEncoding)};\n"
        }
      }
    }

    def idToBits[T <: SpinalEnum](enum: SpinalEnumElement[T], encoding: SpinalEnumEncoding): String = {
      val str = encoding.getValue(enum).toString(2)
      "\"" + ("0" * (encoding.getWidth(enum.parent) - str.length)) + str + "\""
    }

    //    val vecTypes = mutable.Set[Seq[Int]]()
    //    Node.walk(walkNodesDefautStack,node => node match{
    //      case node : VecBaseType[_] => {
    //        if(!vecTypes.contains(node.dims.toSeq)){
    //          var dimsVar = Seq[Int]()
    //          for(dim <- node.dims.reverseIterator){
    //            dimsVar = dim +: dimsVar
    //            if(!vecTypes.contains(dimsVar)){
    //              ret ++= s"  type ${emitVecType(node.baseType,dimsVar  )}} is array (3 downto 0) of std_ulogic;"
    //            }
    //          }
    //          ret ++= "asd\n"
    //          vecTypes += node.dims.toSeq
    //        }
    //      }
    //      case _ =>
    //    })


    ret ++= s"end $enumPackageName;\n\n"
    if (enums.size != 0) {
      ret ++= s"package body $enumPackageName is\n"
      for ((enumDef, encodings) <- enums) {
        val enumName = enumDef.getName()
        ret ++= s"  function pkg_mux (sel : std_logic;one : $enumName;zero : $enumName) return $enumName is\n"
        ret ++= "  begin\n"
        ret ++= "    if sel = '1' then\n"
        ret ++= "      return one;\n"
        ret ++= "    else\n"
        ret ++= "      return zero;\n"
        ret ++= "    end if;\n"
        ret ++= "  end pkg_mux;\n\n"


        //        ret ++= s"  function pkg_toStdLogicVector (value : $enumName) return std_logic_vector is\n"
        //        ret ++= "  begin\n"
        //        ret ++= "    case value is \n"
        //        for (e <- enumDef.values) {
        //          ret ++= s"      when ${e.getName()} => return ${idToBits(e.position)};\n"
        //        }
        //        ret ++= s"      when others => return ${idToBits(enumDef.values.head)};\n"
        //        ret ++= "    end case;\n"
        //        ret ++= "  end pkg_toStdLogicVector;\n\n"
        //
        //        ret ++= s"  function pkg_to$enumName (value : std_logic_vector) return $enumName is\n"
        //        ret ++= "  begin\n"
        //        ret ++= "    case to_integer(unsigned(value)) is \n"
        //        for (e <- enumDef.values) {
        //          ret ++= s"      when ${e.id} => return ${e.getName()};\n"
        //        }
        //        ret ++= s"      when others => return ${enumDef.values.head.getName()};\n"
        //        ret ++= "    end case;\n"
        //        ret ++= s"  end pkg_to$enumName;\n\n"


        for (encoding <- encodings) {
          if (!encoding.isNative)
            ret ++=
              s"""  function ${getEnumToDebugFuntion(enumDef, encoding)} (value : ${emitEnumType(enumDef, encoding)}) return ${getEnumDebugType(enumDef)} is
                  |  begin
                  |    case value is
                  |${
                {
                  for (e <- enumDef.values) yield s"      when ${emitEnumLiteral(e, encoding)} => return ${e.getName()};"
                }.reduce(_ + "\n" + _)
              }
                  |      when others => return XXX;
                  |    end case;
                  |  end;
                  |""".stripMargin
          else {
            ret ++=
              s"""  function pkg_to${enumName}_${encoding.getName()} (value : std_logic_vector(${encoding.getWidth(enumDef) - 1} downto 0)) return $enumName is
                  |  begin
                  |    case value is
                  |${
                {
                  for (e <- enumDef.values) yield s"      when ${idToBits(e, encoding)} => return ${e.getName()};"
                }.reduce(_ + "\n" + _)
              }
                  |      when others => return ${enumDef.values.head.getName()};
                  |    end case;
                  |  end;
                  |""".stripMargin

            ret ++=
              s"""  function pkg_toStdLogicVector_${encoding.getName()} (value : $enumName) return std_logic_vector is
                  |  begin
                  |    case value is
                  |${
                {
                  for (e <- enumDef.values) yield s"      when ${e.getName()} => return ${idToBits(e, encoding)};"
                }.reduce(_ + "\n" + _)
              }
                  |      when others => return ${idToBits(enumDef.values.head, encoding)};
                  |    end case;
                  |  end;
                  |""".stripMargin
          }



          for (targetEncoding <- encodings if targetEncoding != encoding) {
            ret ++= s"  function ${getReEncodingFuntion(enumDef, encoding, targetEncoding)} (that : ${emitEnumType(enumDef, encoding)}) return ${emitEnumType(enumDef, targetEncoding)} is\n"
            ret ++= "  begin\n"
            ret ++= "    case that is \n"
            for (e <- enumDef.values) {
              ret ++= s"      when ${emitEnumLiteral(e, encoding)} => return ${emitEnumLiteral(e, targetEncoding)};\n"
            }
            ret ++= s"      when others => return ${emitEnumLiteral(enumDef.values.head, targetEncoding)};\n"
            ret ++= "    end case;\n"
            ret ++= "  end;\n\n"
          }
        }
      }
      ret ++= s"end $enumPackageName;\n\n\n"
    }
    out.write(ret.result())
  }

  def emitPackage(out: java.io.FileWriter): Unit = {

    def pkgExtractBool(kind: String): Tuple2[String, String] = {
      val ret = new StringBuilder();
      (s"function pkg_extract (that : $kind; bitId : integer) return std_logic", {
        ret ++= "  begin\n"
        ret ++= "    return that(bitId);\n"
        ret ++= "  end pkg_extract;\n\n"
        ret.result()
      })
    }
    def pkgExtract(kind: String): Tuple2[String, String] = {
      val ret = new StringBuilder();
      (s"function pkg_extract (that : $kind; base : unsigned; size : integer) return $kind", {
        ret ++= "   constant elementCount : integer := (that'length-size)+1;\n"
        ret ++= s"   type tableType is array (0 to elementCount-1) of $kind(size-1 downto 0);\n"
        ret ++= "   variable table : tableType;\n"
        ret ++= "  begin\n"
        ret ++= "    for i in 0 to elementCount-1 loop\n"
        ret ++= "      table(i) := that(i + size - 1 downto i);\n"
        ret ++= "    end loop;\n"
        ret ++= "    return table(to_integer(base));\n"
        ret ++= "  end pkg_extract;\n\n"
        ret.result()
      })
    }


    def pkgCat(kind: String): Tuple2[String, String] = {
      val ret = new StringBuilder();
      (s"function pkg_cat (a : $kind; b : $kind) return $kind", {
        ret ++= s"    variable cat : $kind(a'length + b'length-1 downto 0);\n"
        ret ++= s"  begin\n"
        ret ++= s"    cat := a & b;\n"
        ret ++= s"    return cat;\n"
        ret ++= s"  end pkg_cat;\n\n"
        ret.result()
      })
    }


    val vectorTypes = "std_logic_vector" :: "unsigned" :: "signed" :: Nil
    val funcs = ArrayBuffer[Tuple2[String, String]]()
    vectorTypes.foreach(kind => {
      funcs += pkgExtractBool(kind)
      funcs += pkgExtract(kind)
      funcs += pkgCat(kind)
    })



    val ret = new StringBuilder();
    ret ++= s"library IEEE;\n"
    ret ++= "use ieee.std_logic_1164.all;\n"
    ret ++= "use ieee.numeric_std.all;\n"
    ret ++= "use ieee.math_real.all;\n"
    ret ++= "\n"
    ret ++= s"package $packageName is\n"
    ret ++= s"${funcs.map("  " + _._1 + ";\n").reduce(_ + _)}\n"
    ret ++= "\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : std_logic;zero : std_logic) return std_logic;\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : std_logic_vector;zero : std_logic_vector) return std_logic_vector;\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : unsigned;zero : unsigned) return unsigned;\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : signed;zero : signed) return signed;\n"
    ret ++= s"\n"
    ret ++= s"\n"
    ret ++= "  function pkg_toStdLogic (value : boolean) return std_logic;\n"
    ret ++= "  function pkg_toStdLogicVector (value : std_logic) return std_logic_vector;\n"
    ret ++= "  function pkg_toUnsigned(value : std_logic) return unsigned;\n"
    ret ++= "  function pkg_toSigned (value : std_logic) return signed;\n"
    ret ++= "  function pkg_stdLogicVector (lit : std_logic_vector) return std_logic_vector;\n"
    ret ++= "  function pkg_unsigned (lit : unsigned) return unsigned;\n"
    ret ++= "  function pkg_signed (lit : signed) return signed;\n"
    ret ++= "\n"
    ret ++= "  function pkg_resize (that : std_logic_vector; width : integer) return std_logic_vector;\n"
    ret ++= "  function pkg_resize (that : unsigned; width : integer) return unsigned;\n"
    ret ++= "  function pkg_resize (that : signed; width : integer) return signed;\n"
    ret ++= "\n"
    ret ++= "  function pkg_extract (that : std_logic_vector; high : integer; low : integer) return std_logic_vector;\n"
    ret ++= "  function pkg_extract (that : unsigned; high : integer; low : integer) return unsigned;\n"
    ret ++= "  function pkg_extract (that : signed; high : integer; low : integer) return signed;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftRight (that : std_logic_vector; size : natural) return std_logic_vector;\n"
    ret ++= "  function pkg_shiftRight (that : std_logic_vector; size : unsigned) return std_logic_vector;\n"
    ret ++= "  function pkg_shiftLeft (that : std_logic_vector; size : natural) return std_logic_vector;\n"
    ret ++= "  function pkg_shiftLeft (that : std_logic_vector; size : unsigned) return std_logic_vector;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftRight (that : unsigned; size : natural) return unsigned;\n"
    ret ++= "  function pkg_shiftRight (that : unsigned; size : unsigned) return unsigned;\n"
    ret ++= "  function pkg_shiftLeft (that : unsigned; size : natural) return unsigned;\n"
    ret ++= "  function pkg_shiftLeft (that : unsigned; size : unsigned) return unsigned;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftRight (that : signed; size : natural) return signed;\n"
    ret ++= "  function pkg_shiftRight (that : signed; size : unsigned) return signed;\n"
    ret ++= "  function pkg_shiftLeft (that : signed; size : natural) return signed;\n"
    ret ++= "  function pkg_shiftLeft (that : signed; size : unsigned) return signed;\n"
    ret ++= "\n"
    ret ++= "  function pkg_rotateLeft (that : std_logic_vector; size : unsigned) return std_logic_vector;\n"
    ret ++= s"end  $packageName;\n"
    ret ++= "\n"
    ret ++= s"package body $packageName is\n"
    ret ++= s"${funcs.map(f => "  " + f._1 + " is\n" + f._2 + "\n").reduce(_ + _)}"
    ret ++= "\n"
    ret ++= "  -- unsigned shifts\n"
    ret ++= "  function pkg_shiftRight (that : unsigned; size : natural) return unsigned is\n"
    ret ++= "  begin\n"
    ret ++= "    if size >= that'length then\n"
    ret ++= "      return \"\";\n"
    ret ++= "    else\n"
    ret ++= "      return shift_right(that,size)(that'high-size downto 0);\n"
    ret ++= "    end if;\n"
    ret ++= "  end pkg_shiftRight;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftRight (that : unsigned; size : unsigned) return unsigned is\n"
    ret ++= "  begin\n"
    ret ++= "    return shift_right(that,to_integer(size));\n"
    ret ++= "  end pkg_shiftRight;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftLeft (that : unsigned; size : natural) return unsigned is\n"
    ret ++= "  begin\n"
    ret ++= "    return shift_left(resize(that,that'length + size),size);\n"
    ret ++= "  end pkg_shiftLeft;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftLeft (that : unsigned; size : unsigned) return unsigned is\n"
    ret ++= "  begin\n"
    ret ++= "    return shift_left(resize(that,that'length + 2**size'length - 1),to_integer(size));\n"
    ret ++= "  end pkg_shiftLeft;\n"
    ret ++= "\n"
    ret ++= "\n"
    ret ++= "  -- std_logic_vector shifts\n"
    ret ++= "  function pkg_shiftRight (that : std_logic_vector; size : natural) return std_logic_vector is\n"
    ret ++= "  begin\n"
    ret ++= "    return std_logic_vector(pkg_shiftRight(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftRight;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftRight (that : std_logic_vector; size : unsigned) return std_logic_vector is\n"
    ret ++= "  begin\n"
    ret ++= "    return std_logic_vector(pkg_shiftRight(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftRight;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftLeft (that : std_logic_vector; size : natural) return std_logic_vector is\n"
    ret ++= "  begin\n"
    ret ++= "    return std_logic_vector(pkg_shiftLeft(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftLeft;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftLeft (that : std_logic_vector; size : unsigned) return std_logic_vector is\n"
    ret ++= "  begin\n"
    ret ++= "    return std_logic_vector(pkg_shiftLeft(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftLeft;\n"
    ret ++= "\n"
    ret ++= "  -- signed shifts\n"
    ret ++= "  function pkg_shiftRight (that : signed; size : natural) return signed is\n"
    ret ++= "  begin\n"
    ret ++= "    return signed(pkg_shiftRight(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftRight;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftRight (that : signed; size : unsigned) return signed is\n"
    ret ++= "  begin\n"
    ret ++= "    return shift_right(that,to_integer(size));\n"
    ret ++= "  end pkg_shiftRight;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftLeft (that : signed; size : natural) return signed is\n"
    ret ++= "  begin\n"
    ret ++= "    return signed(pkg_shiftLeft(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftLeft;\n"
    ret ++= "\n"
    ret ++= "  function pkg_shiftLeft (that : signed; size : unsigned) return signed is\n"
    ret ++= "  begin\n"
    ret ++= "    return signed(pkg_shiftLeft(unsigned(that),size));\n"
    ret ++= "  end pkg_shiftLeft;\n"
    ret ++= "\n"
    ret ++= "  function pkg_rotateLeft (that : std_logic_vector; size : unsigned) return std_logic_vector is\n"
    ret ++= "  begin\n"
    ret ++= "    return std_logic_vector(rotate_left(unsigned(that),to_integer(size)));\n"
    ret ++= "  end pkg_rotateLeft;\n"
    ret ++= "\n"
    ret ++= "  function pkg_extract (that : std_logic_vector; high : integer; low : integer) return std_logic_vector is\n"
    ret ++= "    variable temp : std_logic_vector(high-low downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    temp := that(high downto low);\n"
    ret ++= "    return temp;\n"
    ret ++= "  end pkg_extract;\n"
    ret ++= "\n"
    ret ++= "  function pkg_extract (that : unsigned; high : integer; low : integer) return unsigned is\n"
    ret ++= "    variable temp : unsigned(high-low downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    temp := that(high downto low);\n"
    ret ++= "    return temp;\n"
    ret ++= "  end pkg_extract;\n"
    ret ++= "\n"
    ret ++= "  function pkg_extract (that : signed; high : integer; low : integer) return signed is\n"
    ret ++= "    variable temp : signed(high-low downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    temp := that(high downto low);\n"
    ret ++= "    return temp;\n"
    ret ++= "  end pkg_extract;\n"
    ret ++= "\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : std_logic;zero : std_logic) return std_logic is\n"
    ret ++= "  begin\n"
    ret ++= "    if sel = '1' then\n"
    ret ++= "      return one;\n"
    ret ++= "    else\n"
    ret ++= "      return zero;\n"
    ret ++= "    end if;\n"
    ret ++= "  end pkg_mux;\n"
    ret ++= "\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : std_logic_vector;zero : std_logic_vector) return std_logic_vector is\n"
    ret ++= "    variable ret : std_logic_vector(zero'range);"
    ret ++= "  begin\n"
    ret ++= "    if sel = '1' then\n"
    ret ++= "      ret := one;\n"
    ret ++= "    else\n"
    ret ++= "      ret := zero;\n"
    ret ++= "    end if;\n"
    ret ++= "    return ret;"
    ret ++= "  end pkg_mux;\n"
    ret ++= "\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : unsigned;zero : unsigned) return unsigned is\n"
    ret ++= "    variable ret : unsigned(zero'range);"
    ret ++= "  begin\n"
    ret ++= "    if sel = '1' then\n"
    ret ++= "      ret := one;\n"
    ret ++= "    else\n"
    ret ++= "      ret := zero;\n"
    ret ++= "    end if;\n"
    ret ++= "    return ret;"
    ret ++= "  end pkg_mux;\n"
    ret ++= "\n"
    ret ++= "  function pkg_mux (sel : std_logic;one : signed;zero : signed) return signed is\n"
    ret ++= "    variable ret : signed(zero'range);"
    ret ++= "  begin\n"
    ret ++= "    if sel = '1' then\n"
    ret ++= "      ret := one;\n"
    ret ++= "    else\n"
    ret ++= "      ret := zero;\n"
    ret ++= "    end if;\n"
    ret ++= "    return ret;"
    ret ++= "  end pkg_mux;\n"
    ret ++= "\n"
    ret ++= "  function pkg_toStdLogic (value : boolean) return std_logic is\n"
    ret ++= "  begin\n"
    ret ++= "    if value = true then\n"
    ret ++= "      return '1';\n"
    ret ++= "    else\n"
    ret ++= "      return '0';\n"
    ret ++= "    end if;\n"
    ret ++= "  end pkg_toStdLogic;\n"
    ret ++= "\n"
    ret ++= "  function pkg_toStdLogicVector (value : std_logic) return std_logic_vector is\n"
    ret ++= "    variable ret : std_logic_vector(0 downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    ret(0) := value;\n"
    ret ++= "    return ret;\n"
    ret ++= "  end pkg_toStdLogicVector;\n"
    ret ++= "\n"
    ret ++= "  function pkg_toUnsigned (value : std_logic) return unsigned is\n"
    ret ++= "    variable ret : unsigned(0 downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    ret(0) := value;\n"
    ret ++= "    return ret;\n"
    ret ++= "  end pkg_toUnsigned;\n"
    ret ++= "\n"
    ret ++= "  function pkg_toSigned (value : std_logic) return signed is\n"
    ret ++= "    variable ret : signed(0 downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    ret(0) := value;\n"
    ret ++= "    return ret;\n"
    ret ++= "  end pkg_toSigned;\n"
    ret ++= "\n"
    ret ++= "  function pkg_stdLogicVector (lit : std_logic_vector) return std_logic_vector is\n"
    ret ++= "    variable ret : std_logic_vector(lit'length-1 downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    ret := lit;"
    ret ++= "    return ret;\n"
    ret ++= "  end pkg_stdLogicVector;\n"
    ret ++= "\n"
    ret ++= "  function pkg_unsigned (lit : unsigned) return unsigned is\n"
    ret ++= "    variable ret : unsigned(lit'length-1 downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    ret := lit;"
    ret ++= "    return ret;\n"
    ret ++= "  end pkg_unsigned;\n"
    ret ++= "\n"
    ret ++= "  function pkg_signed (lit : signed) return signed is\n"
    ret ++= "    variable ret : signed(lit'length-1 downto 0);\n"
    ret ++= "  begin\n"
    ret ++= "    ret := lit;"
    ret ++= "    return ret;\n"
    ret ++= "  end pkg_signed;\n"
    ret ++= "\n"
    ret ++= "  function pkg_resize (that : std_logic_vector; width : integer) return std_logic_vector is\n"
    ret ++= "  begin\n"
    ret ++= "    return std_logic_vector(resize(unsigned(that),width));\n"
    ret ++= "  end pkg_resize;\n"
    ret ++= "\n"
    ret ++=
      """
        |  function pkg_resize (that : unsigned; width : integer) return unsigned is
        |	  variable ret : unsigned(width-1 downto 0);
        |  begin
        |    if that'length = 0 then
        |       ret := (others => '0');
        |    else
        |       ret := resize(that,width);
        |    end if;
        |		return ret;
        |  end pkg_resize;
        | """.stripMargin


    ret ++=
      """
        |  function pkg_resize (that : signed; width : integer) return signed is
        |	  variable ret : signed(width-1 downto 0);
        |  begin
        |    if that'length = 0 then
        |       ret := (others => '0');
        |    else
        |       ret := resize(that,width);
        |    end if;
        |		return ret;
        |  end pkg_resize;
        | """.stripMargin
    ret ++= s"end $packageName;\n"
    ret ++= "\n"
    ret ++= "\n"

    out.write(ret.result())
  }

  def emitLibrary(builder: ComponentBuilder): Unit = {
    val ret = builder.newPart(true)
    emitLibrary(ret)
  }

  def emitLibrary(ret: StringBuilder): Unit = {
    ret ++= "library ieee;\n"
    ret ++= "use ieee.std_logic_1164.all;\n"
    ret ++= "use ieee.numeric_std.all;\n"
    ret ++= "\n"
    ret ++= s"library work;\n"
    ret ++= s"use work.$packageName.all;\n"
    ret ++= s"use work.all;\n"
    ret ++= s"use work.$enumPackageName.all;\n\n"
  }

  def emitEntityName(component: Component): Unit = {

  }

  def emitEntity(component: Component, builder: ComponentBuilder): Unit = {
    var ret = builder.newPart(false)
    ret ++= s"\nentity ${component.definitionName} is\n"
    ret = builder.newPart(true)
    ret ++= s"  port(\n"
    if (!(onlyStdLogicVectorTopLevelIo && component == topLevel)) {
      component.getOrdredNodeIo.foreach(baseType =>
        ret ++= s"    ${baseType.getName()} : ${emitDirection(baseType)} ${emitDataType(baseType)};\n"
      )
    } else {
      component.getOrdredNodeIo.foreach(baseType => {
        val originalType = emitDataType(baseType)
        val correctedType = originalType.replace("unsigned", "std_logic_vector").replace("signed", "std_logic_vector")
        ret ++= s"    ${baseType.getName()} : ${emitDirection(baseType)} ${correctedType};\n"
      }
      )
    }
    /*component.getOrdredNodeIo.foreach(baseType =>
      ret ++= s"    ${baseType.getName()} : ${emitDirection(baseType)} ${emitDataType(baseType)};\n"
    )*/
    ret.setCharAt(ret.size - 2, ' ')
    ret ++= s"  );\n"
    ret = builder.newPart(false)
    ret ++= s"end ${component.definitionName};\n"
    ret ++= s"\n"

  }


  def emitArchitecture(component: Component, builder: ComponentBuilder): Unit = {
    var ret = builder.newPart(false)
    val wrappedIo = if (onlyStdLogicVectorTopLevelIo && component == topLevel) ioStdLogicVectorWrapNames() else HashMap[BaseType, WrappedStuff]()
    ret ++= s"architecture arch of ${component.definitionName} is\n"
    ret = builder.newPart(true)
    emitBlackBoxComponents(component, ret)
    emitAttributesDef(component, ret)
    val enumDebugSignals = ArrayBuffer[SpinalEnumCraft[_]]()
    emitSignals(component, ret, enumDebugSignals)
    emitWrappedIoSignals(ret, wrappedIo)
    val retTemp = new StringBuilder
    retTemp ++= s"begin\n"
    emitComponentInstances(component, retTemp)
    emitAsyncronous(component, retTemp, ret)
    emitSyncronous(component, retTemp)
    emitWrappedIoConnection(retTemp, wrappedIo)
    emitDebug(component, retTemp, enumDebugSignals)
    retTemp ++= s"end arch;\n"
    retTemp ++= s"\n"

    ioStdLogicVectorRestoreNames(wrappedIo)

    ret ++= retTemp

  }

  def emitWrappedIoSignals(buff: StringBuilder, map: HashMap[BaseType, WrappedStuff]): Unit = {
    for ((e, w) <- map) {
      buff ++= s"  signal ${w.newName} : ${emitDataType(e)};\n"
    }
  }

  def emitWrappedIoConnection(buff: StringBuilder, map: HashMap[BaseType, WrappedStuff]): Unit = {
    for ((e, w) <- map) e.dir match {
      case spinal.core.in => e match {
        case e: UInt => buff ++= s"  ${w.newName} <= unsigned(${w.originalName});\n"
        case e: SInt => buff ++= s"  ${w.newName} <= signed(${w.originalName});\n"
      }
      case spinal.core.out => buff ++= s"  ${w.originalName} <= std_logic_vector(${w.newName});\n"
    }
  }

  def emitBlackBoxComponents(component: Component, ret: StringBuilder): Unit = {
    val emited = mutable.Set[String]()
    for (c <- component.kinds) c match {
      case blackBox: BlackBox => {
        if (!emited.contains(blackBox.definitionName)) {
          emited += blackBox.definitionName
          emitBlackBoxComponent(blackBox, ret)
        }
      }
      case _ =>
    }
  }

  def blackBoxRemplaceULogic(b: BlackBox, str: String): String = {
    if (b.isUsingULogic)
      str.replace("std_logic", "std_ulogic")
    else
      str
  }

  def emitBlackBoxComponent(component: BlackBox, ret: StringBuilder): Unit = {
    ret ++= s"\n  component ${component.definitionName} is\n"
    val genericFlat = component.getGeneric.flatten
    if (genericFlat.size != 0) {
      ret ++= s"    generic(\n"
      for ((name, e) <- genericFlat) {
        e match {
          case baseType: BaseType => ret ++= s"      ${emitReference(baseType)} : ${blackBoxRemplaceULogic(component, emitDataType(baseType, false))};\n"
          case s: String => ret ++= s"      $name : string;\n"
          case i: Int => ret ++= s"      $name : integer;\n"
          case d: Double => ret ++= s"      $name : real;\n"
          case b: Boolean => ret ++= s"      $name : boolean;\n"
          case b: STime => ret ++= s"      $name : time;\n"
        }
      }

      ret.setCharAt(ret.size - 2, ' ')
      ret ++= s"    );\n"
    }
    ret ++= s"    port(\n"
    component.nodes.foreach(_ match {
      case baseType: BaseType => {
        if (baseType.isIo) {
          ret ++= s"      ${baseType.getName()} : ${emitDirection(baseType)} ${blackBoxRemplaceULogic(component, emitDataType(baseType, false))};\n"
        }
      }
      case _ =>
    })
    ret.setCharAt(ret.size - 2, ' ')
    ret ++= s"    );\n"
    ret ++= s"  end component;\n"
    ret ++= s"  \n"
  }

  def emitAttributesDef(component: Component, ret: StringBuilder): Unit = {
    val map = mutable.Map[String, Attribute]()

    for (node <- component.nodes) {
      node match {
        case attributeReady: AttributeReady => {
          for (attribute <- attributeReady.attributes) {
            val mAttribute = map.getOrElseUpdate(attribute.getName, attribute)
            if (!mAttribute.sameType(attribute)) SpinalError(s"There is some attributes with different nature (${attribute} and ${mAttribute} at\n${node.component}})")
          }
        }
        case _ =>
      }
    }

    for (attribute <- map.values) {
      val typeString = attribute match {
        case _: AttributeString => "string"
        case _: AttributeFlag => "boolean"
      }
      ret ++= s"  attribute ${attribute.getName} : $typeString;\n"
    }

    ret ++= "\n"
  }

  def toSpinalEnumCraft[T <: SpinalEnum](that: Any) = that.asInstanceOf[SpinalEnumCraft[T]]

  def emitSignals(component: Component, ret: StringBuilder, enumDebugSignals: ArrayBuffer[SpinalEnumCraft[_]]): Unit = {
    for (node <- component.nodes) {
      node match {
        case signal: BaseType => {
          if (!signal.isIo) {
            ret ++= s"  signal ${emitReference(signal)} : ${emitDataType(signal)}"
            if (signal.hasTag(randomBoot)) {
              signal match {
                case b: Bool => ret ++= " := " + {
                  if (Random.nextBoolean()) "'1'" else "'0'"
                }
                case bv: BitVector => {
                  val rand = BigInt(bv.getWidth, Random).toString(2)
                  ret ++= " := \"" + "0" * (bv.getWidth - rand.length) + rand + "\""
                }
                case e: SpinalEnumCraft[_] => {
                  val vec = e.blueprint.values.toVector
                  val rand = vec(Random.nextInt(vec.size))
                  ret ++= " := " + emitEnumLiteral(rand, e.encoding)
                }
              }
            }
            ret ++= ";\n"
            if (signal.isInstanceOf[SpinalEnumCraft[_]]) {
              val craft = toSpinalEnumCraft(signal)
              if (!craft.encoding.isNative) {
                ret ++= s"  signal ${emitReference(signal)}_debug : ${getEnumDebugType(craft.blueprint)};\n"
                enumDebugSignals += toSpinalEnumCraft(signal)
              }
            }
          }


          emitAttributes(signal, "signal", ret)
        }

        case mem: Mem[_] => {
          //ret ++= emitSignal(mem, mem);
          var initAssignementBuilder = new StringBuilder()
          if (mem.initialContent != null) {
            initAssignementBuilder ++= " := ("
            val values = mem.initialContent.map(e => {
              e.hashCode()
            })

            var first = true
            for ((e, index) <- mem.initialContent.zipWithIndex) {
              if (!first)
                initAssignementBuilder ++= ","
              else
                first = false

              if ((index & 15) == 0) {
                initAssignementBuilder ++= "\n     "
              }

              val values = (e.flatten, mem._widths).zipped.map((e, width) => {
                e.getLiteral.getBitsStringOn(width)
              })

              initAssignementBuilder ++= "\"" + values.reduceLeft((l, r) => r + l) + "\""
            }

            initAssignementBuilder ++= ")"
          }

          val symbolWidth = mem.getMemSymbolWidth()
          val symbolCount = mem.getMemSymbolCount

          if(memBitsMaskKind == MULTIPLE_RAM && symbolCount != 1) {
            if(mem.initialContent != null) SpinalError("Memory with multiple symbol per line + initial contant are not suported currently")

            ret ++= s"  type ${emitReference(mem)}_type is array (0 to ${mem.wordCount - 1}) of std_logic_vector(${symbolWidth - 1} downto 0);\n"
            for(i <- 0 until symbolCount) {
              val postfix = "_symbol" + i
              ret ++= s"  signal ${emitReference(mem)}$postfix : ${emitDataType(mem)};\n"
              emitAttributes(mem, "signal", ret,postfix = postfix)
            }
          }else{
            ret ++= s"  type ${emitReference(mem)}_type is array (0 to ${mem.wordCount - 1}) of std_logic_vector(${mem.getWidth - 1} downto 0);\n"
            ret ++= s"  signal ${emitReference(mem)} : ${emitDataType(mem)}${initAssignementBuilder.toString()};\n"
            emitAttributes(mem, "signal", ret)
          }
        }
        case _ =>
      }


    }
  }



  def emitAttributes(node: Node, vhdlType: String, ret: StringBuilder,postfix : String = ""): Unit = {
    if (!node.isInstanceOf[AttributeReady]) return
    val attributeReady = node.asInstanceOf[AttributeReady]

    for (attribute <- attributeReady.attributes) {
      val value = attribute match {
        case attribute: AttributeString => "\"" + attribute.value + "\""
        case attribute: AttributeFlag => "true"
      }

      ret ++= s"  attribute ${attribute.getName} of ${emitReference(node)}: signal is $value;\n"
    }
  }


  def getSensitivity(nodes: Iterable[Node], includeNodes: Boolean): mutable.Set[Node] = {
    val sensitivity = mutable.Set[Node]()

    if (includeNodes)
      nodes.foreach(walk(_))
    else
      nodes.foreach(_.onEachInput(walk(_)))

    def walk(node: Node): Unit = {
      if (isReferenceable(node))
        sensitivity += node
      else
        node.onEachInput(walk(_))
    }

    sensitivity
  }

  def emitAsyncronous(component: Component, ret: StringBuilder, funcRet: StringBuilder): Unit = {

    var processCounter = 0
    class Process(val order: Int) {
      var sensitivity: mutable.Set[Node] = null
      val nodes = ArrayBuffer[Node]()
      val whens = ArrayBuffer[ConditionalContext]()
      var hasMultipleAssignment = false

      def genSensitivity: Unit = sensitivity = getSensitivity(nodes, false)


      def needProcessDef: Boolean = {
        if (!whens.isEmpty || nodes.size > 1) return true
        if (hasMultipleAssignment) {
          val ma: MultipleAssignmentNode = nodes(0).getInput(0).asInstanceOf[MultipleAssignmentNode]
          val assignedBits = new AssignedBits(nodes(0).getWidth)
          ma.onEachInput(_ match {
            case assign: AssignementNode => {
              val scope = assign.getScopeBits
              if (!AssignedBits.intersect(scope, assignedBits).isEmpty) return true
              assignedBits.add(scope)
            }
            case _ => return true
          })
        }
        return false
      }
    }

    val processSet = mutable.Set[Process]()
    val whenToProcess = mutable.Map[ConditionalContext, Process]()

    def move(to: Process, from: Process): Unit = {
      to.nodes ++= from.nodes
      to.whens ++= from.whens
      to.hasMultipleAssignment |= from.hasMultipleAssignment
      from.whens.foreach(whenToProcess(_) = to)
      processSet.remove(from)
    }

    val asyncSignals = component.nodes.filter(_ match {
      case signal: BaseType => (!signal.isDelay) && (!((signal.isIo && signal.isInput) || component.kindsOutputsBindings.contains(signal)))
      case _ => false
    })

    for (signal <- asyncSignals) {
      var process: Process = null
      var hasMultipleAssignment = false
      walk(signal.getInput(0))
      def walk(that: Node): Unit = {
        that match {
          case wn: WhenNode => {
            if (whenToProcess.contains(wn.w)) {
              val otherProcess = whenToProcess.get(wn.w).get
              if (process == null) {
                process = otherProcess
                otherProcess.nodes += signal
              } else if (process != otherProcess) {
                move(otherProcess, process)
                process = otherProcess
              }
            } else {
              if (process == null) {
                process = new Process(processCounter);
                processCounter += 1
                process.nodes += signal
                processSet += process
              }
              process.whens += wn.w
              whenToProcess += (wn.w -> process)
            }

            walk(wn.whenTrue)
            walk(wn.whenFalse)
          }
          case switchNode: SwitchNode => {
            if (whenToProcess.contains(switchNode.context)) {
              val otherProcess = whenToProcess.get(switchNode.context).get
              if (process == null) {
                process = otherProcess
                otherProcess.nodes += signal
              } else if (process != otherProcess) {
                move(otherProcess, process)
                process = otherProcess
              }
            } else {
              if (process == null) {
                process = new Process(processCounter);
                processCounter += 1
                process.nodes += signal
                processSet += process
              }
              process.whens += switchNode.context
              whenToProcess += (switchNode.context -> process)
            }

            switchNode.cases.foreach(n => walk(n.asInstanceOf[CaseNode].assignement))
          }
          case man: MultipleAssignmentNode => {
            man.onEachInput(walk(_))
            hasMultipleAssignment = true
          }
          case that => {
            if (process == null) {
              process = new Process(processCounter);
              processCounter += 1
              process.nodes += signal
              processSet += process
            }
          }
        }
      }

      process.hasMultipleAssignment |= hasMultipleAssignment
    }

    val processList = processSet.toList.sortWith(_.order < _.order)


    for (process <- processList if !process.needProcessDef) {
      for (node <- process.nodes) {
        emitAssignement(node, node.getInput(0), ret, "  ", "<=")
        //ret ++= s"  ${emitReference(node)} <= ${emitLogic(node.getInput(0))};\n"
      }
    }

    for (process <- processList if process.needProcessDef) {
      process.genSensitivity

      val context = new AssignementLevel
      for (node <- process.nodes) {
        context.walkWhenTree(node, node.getInput(0))
      }

      if (process.sensitivity.size != 0) {

        ret ++= s"  process(${process.sensitivity.toList.sortWith(_.instanceCounter < _.instanceCounter).map(emitReference(_)).reduceLeft(_ + "," + _)})\n"
        ret ++= "  begin\n"
        context.emitContext(ret, "    ", "<=")
        ret ++= "  end process;\n\n"
      } else {
        //emit func as logic
        assert(process.nodes.size == 1)
        for (node <- process.nodes) {
          val funcName = "zz_" + emitReference(node)
          funcRet ++= emitFuncDef(funcName, node, context)
          ret ++= s"  ${emitReference(node)} <= ${funcName};\n"
          //          ret ++= s"  ${emitReference(node)} <= ${emitLogic(node.getInput(0))};\n"
        }
      }
    }

  }


  def emitFuncDef(funcName: String, node: Node, context: AssignementLevel): StringBuilder = {
    val ret = new StringBuilder
    //context.emitContext(ret, "    ",":=")
    ret ++= s"  function $funcName return ${emitDataType(node, false)} is\n"
    ret ++= s"    variable ${emitReference(node)} : ${emitDataType(node, true)};\n"
    ret ++= s"  begin\n"
    context.emitContext(ret, "    ", ":=")
    ret ++= s"    return ${emitReference(node)};\n"
    ret ++= s"  end function;\n"
  }

  def operatorImplAsOperator(vhd: String)(op: Modifier): String = {
    op.getInputsCount match {
      case 1 => s"($vhd ${emitLogic(op.getInput(0))})"
      case 2 => {
        val temp = s"(${emitLogic(op.getInput(0))} $vhd ${emitLogic(op.getInput(1))})"
        if (opThatNeedBoolCast.contains(op.opName))
          return s"pkg_toStdLogic$temp"
        else
          return temp
      }
    }
  }

  def operatorImplAsFunction(vhd: String)(func: Modifier): String = {
    s"$vhd(${func.getInputs.map(emitLogic(_)).reduce(_ + "," + _)})"
  }

  def shiftRightByIntImpl(func: Modifier): String = {
    val node = func.asInstanceOf[Operator.BitVector.ShiftRightByInt]
    s"pkg_shiftRight(${emitLogic(node.input)},${node.shift})"
  }

  def shiftLeftByIntImpl(func: Modifier): String = {
    val node = func.asInstanceOf[Operator.BitVector.ShiftLeftByInt]
    s"pkg_shiftLeft(${emitLogic(node.input)},${node.shift})"
  }

  def resizeFunction(vhdlFunc : String)(func: Modifier): String = {
    val resize = func.asInstanceOf[Resize]
    func.getInput(0).getWidth match{
      case 0 => {
        func.getInput(0) match {
          case lit: BitsLiteral => {
            val bitString =  '"' + "0" * func.getWidth + '"'
            lit.kind match {
              case _: Bits => s"pkg_stdLogicVector($bitString)"
              case _: UInt => s"pkg_unsigned($bitString)"
              case _: SInt => s"pkg_signed($bitString)"
            }
          }
          case _ => s"pkg_resize(${emitLogic(resize.input)},${resize.size})"
        }
      }
      case _ => s"pkg_resize(${emitLogic(resize.input)},${resize.size})"
    }
  }


  def enumEgualsImpl(eguals: Boolean)(op: Modifier): String = {
    val (enumDef, encoding) = op.getInput(0) match {
      case craft: SpinalEnumCraft[_] => (craft.blueprint, craft.encoding)
      case literal: EnumLiteral[_] => (literal.enum.parent, literal.encoding)
    }
    encoding match {
      case `oneHot` => s"pkg_toStdLogic((${emitLogic(op.getInput(0))} and ${emitLogic(op.getInput(1))}) ${if (eguals) "/=" else "="} ${'"' + "0" * op.getInput(0).getWidth + '"'})"
      case _ => s"pkg_toStdLogic(${emitLogic(op.getInput(0))} ${if (eguals) "=" else "/="} ${emitLogic(op.getInput(1))})"
    }
  }


  def operatorImplAsBitsToEnum(func: Modifier): String = {
    val (enumDef: SpinalEnum, encoding) = func.asInstanceOf[CastBitsToEnum].enum match {
      case craft: SpinalEnumCraft[_] => (craft.blueprint, craft.encoding)
    }
    if (!encoding.isNative) {
      emitLogic(func.getInput(0))
    } else {
      s"pkg_to${enumDef.getName()}_${encoding.getName()}(${(emitLogic(func.getInput(0)))})"
    }
  }

  def operatorImplAsEnumToBits(func: Modifier): String = {
    val (enumDef, encoding) = func.getInput(0) match {
      case craft: SpinalEnumCraft[_] => (craft.blueprint, craft.encoding)
      case literal: EnumLiteral[_] => (literal.enum.parent, literal.encoding)
    }
    if (!encoding.isNative) {
      emitLogic(func.getInput(0))
    } else {
      s"pkg_toStdLogicVector_${encoding.getName()}(${(emitLogic(func.getInput(0)))})"
    }
  }

  def operatorImplAsEnumToEnum(func: Modifier): String = {
    val (enumDefSrc, encodingSrc) = func.getInput(0) match {
      case craft: SpinalEnumCraft[_] => (craft.blueprint, craft.encoding)
      case literal: EnumLiteral[_] => (literal.enum.parent, literal.encoding)
    }
    val enumCast = func.asInstanceOf[CastBitsToEnum]
    val (enumDefDst, encodingDst) = enumCast.enum match {
      case craft: SpinalEnumCraft[_] => (craft.blueprint, craft.encoding)
    }
    if (encodingDst.isNative && encodingSrc.isNative)
      emitLogic(func.getInput(0))
    else {
      val encoding = enumCast.getInput(0) match {
        case input: SpinalEnumCraft[_] => input.encoding
        case input: EnumLiteral[_] => input.encoding
      }
      s"${getReEncodingFuntion(enumCast.enum.blueprint.asInstanceOf[SpinalEnum], encoding, enumCast.enum.encoding)}(${func.getInputs.map(emitLogic(_)).reduce(_ + "," + _)})"
    }
  }

  val modifierImplMap = mutable.Map[String, Modifier => String]()


  //unsigned
  modifierImplMap.put("u+u", operatorImplAsOperator("+"))
  modifierImplMap.put("u-u", operatorImplAsOperator("-"))
  modifierImplMap.put("u*u", operatorImplAsOperator("*"))
  modifierImplMap.put("u/u", operatorImplAsOperator("/"))
  modifierImplMap.put("u%u", operatorImplAsOperator("rem"))

  modifierImplMap.put("u|u", operatorImplAsOperator("or"))
  modifierImplMap.put("u&u", operatorImplAsOperator("and"))
  modifierImplMap.put("u^u", operatorImplAsOperator("xor"))
  modifierImplMap.put("~u", operatorImplAsOperator("not"))

  modifierImplMap.put("u==u", operatorImplAsOperator("="))
  modifierImplMap.put("u!=u", operatorImplAsOperator("/="))
  modifierImplMap.put("u<u", operatorImplAsOperator("<"))
  modifierImplMap.put("u<=u", operatorImplAsOperator("<="))


  modifierImplMap.put("u>>i", shiftRightByIntImpl)
  modifierImplMap.put("u<<i", shiftLeftByIntImpl)
  modifierImplMap.put("u>>u", operatorImplAsFunction("pkg_shiftRight"))
  modifierImplMap.put("u<<u", operatorImplAsFunction("pkg_shiftLeft"))


  //signed
  modifierImplMap.put("s+s", operatorImplAsOperator("+"))
  modifierImplMap.put("s-s", operatorImplAsOperator("-"))
  modifierImplMap.put("s*s", operatorImplAsOperator("*"))
  modifierImplMap.put("s/s", operatorImplAsOperator("/"))
  modifierImplMap.put("s%s", operatorImplAsOperator("rem"))

  modifierImplMap.put("s|s", operatorImplAsOperator("or"))
  modifierImplMap.put("s&s", operatorImplAsOperator("and"))
  modifierImplMap.put("s^s", operatorImplAsOperator("xor"))
  modifierImplMap.put("~s", operatorImplAsOperator("not"))
  modifierImplMap.put("-s", operatorImplAsOperator("-"))

  modifierImplMap.put("s==s", operatorImplAsOperator("="))
  modifierImplMap.put("s!=s", operatorImplAsOperator("/="))
  modifierImplMap.put("s<s", operatorImplAsOperator("<"))
  modifierImplMap.put("s<=s", operatorImplAsOperator("<="))


  modifierImplMap.put("s>>i", shiftRightByIntImpl)
  modifierImplMap.put("s<<i", shiftLeftByIntImpl)
  modifierImplMap.put("s>>u", operatorImplAsFunction("pkg_shiftRight"))
  modifierImplMap.put("s<<u", operatorImplAsFunction("pkg_shiftLeft"))



  //bits
  modifierImplMap.put("b##b", operatorImplAsFunction("pkg_cat"))

  modifierImplMap.put("b|b", operatorImplAsOperator("or"))
  modifierImplMap.put("b&b", operatorImplAsOperator("and"))
  modifierImplMap.put("b^b", operatorImplAsOperator("xor"))
  modifierImplMap.put("~b", operatorImplAsOperator("not"))

  modifierImplMap.put("b==b", operatorImplAsOperator("="))
  modifierImplMap.put("b!=b", operatorImplAsOperator("/="))

  modifierImplMap.put("b>>i", shiftRightByIntImpl)
  modifierImplMap.put("b<<i", shiftLeftByIntImpl)
  modifierImplMap.put("b>>u", operatorImplAsFunction("pkg_shiftRight"))
  modifierImplMap.put("b<<u", operatorImplAsFunction("pkg_shiftLeft"))
  modifierImplMap.put("brotlu", operatorImplAsFunction("pkg_rotateLeft"))



  //bool
  modifierImplMap.put("B==B", operatorImplAsOperator("="))
  modifierImplMap.put("B!=B", operatorImplAsOperator("/="))


  modifierImplMap.put("!", operatorImplAsOperator("not"))
  modifierImplMap.put("&&", operatorImplAsOperator("and"))
  modifierImplMap.put("||", operatorImplAsOperator("or"))
  modifierImplMap.put("B^B", operatorImplAsOperator("xor"))


  //enum
  modifierImplMap.put("e==e", enumEgualsImpl(true))
  modifierImplMap.put("e!=e", enumEgualsImpl(false))

  //cast
  modifierImplMap.put("s->b", operatorImplAsFunction("std_logic_vector"))
  modifierImplMap.put("u->b", operatorImplAsFunction("std_logic_vector"))
  modifierImplMap.put("B->b", operatorImplAsFunction("pkg_toStdLogicVector"))
  modifierImplMap.put("e->b", operatorImplAsEnumToBits)

  modifierImplMap.put("b->s", operatorImplAsFunction("signed"))
  modifierImplMap.put("u->s", operatorImplAsFunction("signed"))

  modifierImplMap.put("b->u", operatorImplAsFunction("unsigned"))
  modifierImplMap.put("s->u", operatorImplAsFunction("unsigned"))

  modifierImplMap.put("b->e", operatorImplAsBitsToEnum)
  modifierImplMap.put("e->e", operatorImplAsEnumToEnum)


  //misc

  modifierImplMap.put("resize(s,i)", resizeFunction("pkg_signed"))
  modifierImplMap.put("resize(u,i)", resizeFunction("pkg_unsigned"))
  modifierImplMap.put("resize(b,i)", resizeFunction("pkg_stdLogicVector"))

  //Memo whenNode hardcode emitlogic
  modifierImplMap.put("mux(B,B,B)", operatorImplAsFunction("pkg_mux"))
  modifierImplMap.put("mux(B,b,b)", operatorImplAsFunction("pkg_mux"))
  modifierImplMap.put("mux(B,u,u)", operatorImplAsFunction("pkg_mux"))
  modifierImplMap.put("mux(B,s,s)", operatorImplAsFunction("pkg_mux"))
  modifierImplMap.put("mux(B,e,e)", operatorImplAsFunction("pkg_mux"))

  modifierImplMap.put("extract(b,i)", extractBoolFixed)
  modifierImplMap.put("extract(u,i)", extractBoolFixed)
  modifierImplMap.put("extract(s,i)", extractBoolFixed)

  modifierImplMap.put("extract(b,u)", extractBoolFloating)
  modifierImplMap.put("extract(u,u)", extractBoolFloating)
  modifierImplMap.put("extract(s,u)", extractBoolFloating)

  modifierImplMap.put("extract(b,i,i)", extractBitVectorFixed)
  modifierImplMap.put("extract(u,i,i)", extractBitVectorFixed)
  modifierImplMap.put("extract(s,i,i)", extractBitVectorFixed)

  modifierImplMap.put("extract(b,u,w)", extractBitVectorFloating)
  modifierImplMap.put("extract(u,u,w)", extractBitVectorFloating)
  modifierImplMap.put("extract(s,u,w)", extractBitVectorFloating)


  def extractBoolFixed(func: Modifier): String = {
    val that = func.asInstanceOf[ExtractBoolFixed]
    s"pkg_extract(${emitLogic(that.getBitVector)},${that.getBitId})"
  }

  def extractBoolFloating(func: Modifier): String = {
    val that = func.asInstanceOf[ExtractBoolFloating]
    s"pkg_extract(${emitLogic(that.getBitVector)},to_integer(${emitLogic(that.getBitId)}))"
  }

  def extractBitVectorFixed(func: Modifier): String = {
    val that = func.asInstanceOf[ExtractBitsVectorFixed]
    s"pkg_extract(${emitLogic(that.getBitVector)},${that.getHi},${that.getLo})"
  }

  def extractBitVectorFloating(func: Modifier): String = {
    val that = func.asInstanceOf[ExtractBitsVectorFloating]
    s"pkg_extract(${emitLogic(that.getBitVector)},${emitLogic(that.getOffset)},${that.getBitCount})"
  }


  def opThatNeedBoolCastGen(a: String, b: String): List[String] = {
    ("==" :: "!=" :: "<" :: "<=" :: Nil).map(a + _ + b)
  }

  val opThatNeedBoolCast = mutable.Set[String]()
  opThatNeedBoolCast ++= opThatNeedBoolCastGen("B", "B")
  opThatNeedBoolCast ++= opThatNeedBoolCastGen("b", "b")
  opThatNeedBoolCast ++= opThatNeedBoolCastGen("u", "u")
  opThatNeedBoolCast ++= opThatNeedBoolCastGen("s", "s")
  opThatNeedBoolCast ++= opThatNeedBoolCastGen("e", "e")


  def emitLogic(node: Node): String = node match {
    case baseType: BaseType => emitReference(baseType)
    case node: Modifier => modifierImplMap.getOrElse(node.opName, throw new Exception("can't find " + node.opName))(node)
    case lit: BitsLiteral => lit.kind match {
      case _: Bits => s"pkg_stdLogicVector(${'\"'}${lit.getBitsStringOn(lit.getWidth)}${'\"'})"
      case _: UInt => s"pkg_unsigned(${'\"'}${lit.getBitsStringOn(lit.getWidth)}${'\"'})"
      case _: SInt => s"pkg_signed(${'\"'}${lit.getBitsStringOn(lit.getWidth)}${'\"'})"
    }
    case lit: BitsAllToLiteral => lit.theConsumer match {
      case _: Bits => s"pkg_stdLogicVector(${'\"'}${lit.getBitsStringOn(lit.getWidth)}${'\"'})"
      case _: UInt => s"pkg_unsigned(${'\"'}${lit.getBitsStringOn(lit.getWidth)}${'\"'})"
      case _: SInt => s"pkg_signed(${'\"'}${lit.getBitsStringOn(lit.getWidth)}${'\"'})"
    }
    case lit: BoolLiteral => s"pkg_toStdLogic(${lit.value})"
  //  case lit: BoolLiteral => if(lit.value) "'1'" else "'0'" //Invalid VHDL when '1' = '1'
    case lit: EnumLiteral[_] => emitEnumLiteral(lit.enum, lit.encoding)
    case memRead: MemReadAsync => {
      if (memRead.writeToReadKind == dontCare) SpinalWarning(s"memReadAsync with dontCare is as writeFirst into VHDL")
      val symbolCount = memRead.getMem.getMemSymbolCount
      if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
        s"${emitReference(memRead.getMem)}(to_integer(${emitReference(memRead.getAddress)}))"
      else
        (0 until symbolCount).reverse.map(i => (s"${emitReference(memRead.getMem)}_symbol$i(to_integer(${emitReference(memRead.getAddress)}))")).reduce(_ + " & " + _)
    }
    case whenNode: WhenNode => s"pkg_mux(${whenNode.getInputs.map(emitLogic(_)).reduce(_ + "," + _)})" //Exeptional case with asyncrouns of literal
    case dc: DontCareNode => {
      dc.getBaseType match {
        case to: Bool => s"'-'"
        case to: BitVector => s"(${'"'}${"-" * dc.getWidth}${'"'})"
      }
    }

    case o => throw new Exception("Don't know how emit logic of " + o.getClass.getSimpleName)
  }

  def emitDebug(component: Component, ret: StringBuilder, enumDebugSignals: ArrayBuffer[SpinalEnumCraft[_]]): Unit = {
    for (signal <- enumDebugSignals) {
      ret ++= s"  ${emitReference(signal)}_debug <= ${getEnumToDebugFuntion(toSpinalEnumCraft(signal).blueprint, signal.encoding)}(${emitReference(signal)});\n"
    }
  }

  def emitSyncronous(component: Component, ret: StringBuilder): Unit = {
    val syncNodes = component.getDelays

    val clockDomainMap = mutable.Map[ClockDomain, ArrayBuffer[SyncNode]]()

    for (syncNode <- syncNodes) {
      clockDomainMap.getOrElseUpdate(syncNode.getClockDomain, new ArrayBuffer[SyncNode]()) += syncNode
    }

    for ((clockDomain, array) <- clockDomainMap) {
      val arrayWithReset = ArrayBuffer[SyncNode]()
      val arrayWithoutReset = ArrayBuffer[SyncNode]()

      for (syncNode <- array) {
        if (syncNode.isUsingReset) arrayWithReset += syncNode else arrayWithoutReset += syncNode
      }

      emitClockDomain(true)
      emitClockDomain(false)



      def emitClockDomain(withReset: Boolean): Unit = {
        val activeArray = if (withReset) arrayWithReset else arrayWithoutReset
        if (activeArray.size == 0) return;
        val clock = component.pulledDataCache.getOrElse(clockDomain.clock, throw new Exception("???")).asInstanceOf[Bool]
        val reset = if (null == clockDomain.reset || !withReset) null else component.pulledDataCache.getOrElse(clockDomain.reset, throw new Exception("???")).asInstanceOf[Bool]
        val clockEnable = if (null == clockDomain.clockEnable) null else component.pulledDataCache.getOrElse(clockDomain.clockEnable, throw new Exception("???")).asInstanceOf[Bool]
        val asyncReset = (null != reset) && clockDomain.config.resetKind == ASYNC
        val syncReset = (null != reset) && clockDomain.config.resetKind == SYNC
        var tabLevel = 1
        def tabStr = "  " * tabLevel
        def inc = {
          tabLevel = tabLevel + 1
        }
        def dec = {
          tabLevel = tabLevel - 1
        }

        val initialValueAssignement = new AssignementLevel
        val initialValues = ArrayBuffer[Node]()
        for (syncNode <- activeArray) syncNode match {
          case reg: Reg => {
            if (reg.hasInitialValue) {
              initialValueAssignement.walkWhenTree(reg.getOutputByConsumers, reg.getInitialValue)
              initialValues += reg.getInitialValue
            }
          }
          case _ =>
        }


        if (asyncReset) {
          val sensitivity = getSensitivity(initialValues, true)
          ret ++= s"${tabStr}process(${emitReference(clock)},${emitReference(reset)}${sensitivity.foldLeft("")(_ + "," + emitReference(_))})\n"
        } else {
          ret ++= s"${tabStr}process(${emitReference(clock)})\n"
        }

        ret ++= s"${tabStr}begin\n"
        inc
        if (asyncReset) {
          ret ++= s"${tabStr}if ${emitReference(reset)} = \'${if (clockDomain.config.resetActiveLevel == HIGH) 1 else 0}\' then\n";
          inc
          emitRegsInitialValue(initialValueAssignement, tabStr)
          dec
          ret ++= s"${tabStr}elsif ${emitClockEdge(clock, clockDomain.config.clockEdge)}"
          inc
        } else {
          ret ++= s"${tabStr}if ${emitClockEdge(clock, clockDomain.config.clockEdge)}"
          inc
        }
        if (clockEnable != null) {
          ret ++= s"${tabStr}if ${emitReference(clockEnable)} = \'${if (clockDomain.config.clockEnableActiveLevel == HIGH) 1 else 0}\' then\n"
          inc
        }
        if (syncReset) {
          ret ++= s"${tabStr}if ${emitReference(reset)} = \'${if (clockDomain.config.resetActiveLevel == HIGH) 1 else 0}\' then\n"
          inc
          emitRegsInitialValue(initialValueAssignement, tabStr)
          dec
          ret ++= s"${tabStr}else\n"
          inc
          emitRegsLogic(tabStr)
          dec
          ret ++= s"${tabStr}end if;\n"
          dec
        } else {
          emitRegsLogic(tabStr)
          dec
        }

        while (tabLevel != 1) {
          ret ++= s"${tabStr}end if;\n"
          dec
        }
        ret ++= s"${tabStr}end process;\n"
        dec
        ret ++= s"${tabStr}\n"


        def emitRegsInitialValue(assignementLevel: AssignementLevel, tab: String): Unit = {
          assignementLevel.emitContext(ret, tab, "<=")
        }


        def emitRegsLogic(tab: String): Unit = {


          val rootContext = new AssignementLevel

          for (syncNode <- activeArray) syncNode match {
            case reg: Reg => {
              val regSignal = reg.getOutputByConsumers
              if (!regSignal.isIo || !regSignal.isInput) {
                val in = reg.getDataInput
                if (in != reg)
                  rootContext.walkWhenTree(regSignal, in)
              }
            }
            case memWrite: MemWrite => {
              if (memWrite.useWriteEnable) {
                ret ++= s"${tab}if ${emitReference(memWrite.getEnable)} = '1' then\n"
                emitWrite(tab + "  ")
                ret ++= s"${tab}end if;\n"
              } else {
                emitWrite(tab)
              }

              def emitWrite(tab: String) = {
                val symbolCount = memWrite.getMem.getMemSymbolCount
                val bitPerSymbole = memWrite.getMem.getMemSymbolWidth()

                if(memWrite.getMask == null) {
                  if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
                    ret ++= s"$tab${emitReference(memWrite.getMem)}(to_integer(${emitReference(memWrite.getAddress)})) <= ${emitReference(memWrite.getData)};\n"
                  else
                    for(i <- 0 until symbolCount) {
                      val range = s"(${(i + 1) * bitPerSymbole - 1} downto ${i * bitPerSymbole})"
                      ret ++= s"$tab  ${emitReference(memWrite.getMem)}_symbol${i}(to_integer(${emitReference(memWrite.getAddress)})) <= ${emitReference(memWrite.getData)}$range;\n"
                    }
                }else{

                  val maskCount = memWrite.getMask.getWidth
                  for(i <- 0 until maskCount){
                    val range = s"(${(i+1)*bitPerSymbole-1} downto ${i*bitPerSymbole})"
                    ret ++= s"${tab}if ${emitReference(memWrite.getMask)}($i) = '1' then\n"
                    if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
                      ret ++= s"$tab  ${emitReference(memWrite.getMem)}(to_integer(${emitReference(memWrite.getAddress)}))$range <= ${emitReference(memWrite.getData)}$range;\n"
                    else
                      ret ++= s"$tab  ${emitReference(memWrite.getMem)}_symbol${i}(to_integer(${emitReference(memWrite.getAddress)})) <= ${emitReference(memWrite.getData)}$range;\n"

                    ret ++= s"${tab}end if;\n"
                  }
                }
              }
            }
            case memReadSync: MemReadSync => {
              //readFirst
              if (memReadSync.writeToReadKind == writeFirst) SpinalError(s"Can't translate a memReadSync with writeFirst into VHDL $memReadSync")
              if (memReadSync.writeToReadKind == dontCare) SpinalWarning(s"memReadSync with dontCare is as readFirst into VHDL $memReadSync")
              if (memReadSync.useReadEnable) {
                ret ++= s"${tab}if ${emitReference(memReadSync.getEnable)} = '1' then\n"
                emitRead(tab + "  ")
                ret ++= s"${tab}end if;\n"
              } else {
                emitRead(tab)
              }
              def emitRamRead() = {
                val symbolCount = memReadSync.getMem.getMemSymbolCount
                if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
                  s"${emitReference(memReadSync.getMem)}(to_integer(${emitReference(memReadSync.getAddress)}))"
                else
                  (0 until symbolCount).reverse.map(i => (s"${emitReference(memReadSync.getMem)}_symbol$i(to_integer(${emitReference(memReadSync.getAddress)}))")).reduce(_ + " & " + _)
              }
              def emitRead(tab: String) = ret ++= s"$tab${emitReference(memReadSync.consumers(0))} <= ${emitRamRead()};\n"

            }

            case memWrite: MemWriteOrRead_writePart => {
              val memReadSync = memWrite.readPart
              if (memReadSync.writeToReadKind == writeFirst) SpinalError(s"Can't translate a MemWriteOrRead with writeFirst into VHDL $memReadSync")
              if (memReadSync.writeToReadKind == dontCare) SpinalWarning(s"MemWriteOrRead with dontCare is as readFirst into VHDL $memReadSync")

              ret ++= s"${tab}if ${emitReference(memWrite.getChipSelect)} = '1' then\n"
              ret ++= s"${tab}  if ${emitReference(memWrite.getWriteEnable)} = '1' then\n"
              emitWrite(tab + "    ")
              ret ++= s"${tab}  end if;\n"
              if (memReadSync.component.nodes.contains(memReadSync))
                emitRead(tab + "  ")
              ret ++= s"${tab}end if;\n"

              def emitWrite(tab: String) = ret ++= s"$tab${emitReference(memWrite.getMem)}(to_integer(${emitReference(memWrite.getAddress)})) <= ${emitReference(memWrite.getData)};\n"
              def emitRead(tab: String) = ret ++= s"$tab${emitReference(memReadSync.consumers(0))} <= ${emitReference(memReadSync.getMem)}(to_integer(${emitReference(memReadSync.getAddress)}));\n"
            }
            case memWriteRead_readPart: MemWriteOrRead_readPart => {

            }
            case assertNode : AssertNode => {
              val cond = emitLogic(assertNode.cond)
              val message = if(assertNode.message != null) s"""report "${assertNode.message}" """ else ""
              val severity = "severity " +  (assertNode.severity match{
                case `NOTE`     => "NOTE"
                case `WARNING`  => "WARNING"
                case `ERROR`    => "ERROR"
                case `FAILURE`  => "FAILURE"
              })
              ret ++= s"${tab}assert $cond = '1' $message $severity;\n"
            }
          }
          rootContext.emitContext(ret, tab, "<=")
        }
      }
    }
  }

  def emitAssignement(to: Node, from: Node, ret: StringBuilder, tab: String, assignementKind: String): Unit = {
    from match {
      case from: AssignementNode => {
        from match {
          case assign: BitAssignmentFixed => ret ++= s"$tab${emitReference(to)}(${assign.getBitId}) ${assignementKind} ${emitLogic(assign.getInput)};\n"
          case assign: BitAssignmentFloating => ret ++= s"$tab${emitReference(to)}(to_integer(${emitLogic(assign.getBitId)})) ${assignementKind} ${emitLogic(assign.getInput)};\n"
          case assign: RangedAssignmentFixed => ret ++= s"$tab${emitReference(to)}(${assign.getHi} downto ${assign.getLo}) ${assignementKind} ${emitLogic(assign.getInput)};\n"
          case assign: RangedAssignmentFloating => ret ++= s"$tab${emitReference(to)}(${assign.getBitCount.value - 1} + to_integer(${emitLogic(assign.getOffset)}) downto to_integer(${emitLogic(assign.getOffset)})) ${assignementKind} ${emitLogic(assign.getInput)};\n"
        }
      }
      case man: MultipleAssignmentNode => {
        //For some case with asyncronous partial assignement
        man.onEachInput(assign => {
          emitAssignement(to, assign, ret, tab, assignementKind)
        })
      }
      case _ => ret ++= s"$tab${emitReference(to)} ${assignementKind} ${emitLogic(from)};\n"
    }
  }

  class ConditionalTree(val instanceCounter: Int)

  class WhenTree(val cond: Node, instanceCounter: Int) extends ConditionalTree(instanceCounter) {
    var whenTrue: AssignementLevel = new AssignementLevel
    var whenFalse: AssignementLevel = new AssignementLevel
  }

  class SwitchTree(instanceCounter: Int, context: SwitchContext) extends ConditionalTree(instanceCounter) {
    val cases = new Array[(Node, AssignementLevel)](context.caseCount)
  }

  class AssignementLevel {
    //map of precedent ConditionalTree , assignements      if no precedent ConditionalTree simple assignememnt => null
    val logicChunk = mutable.Map[ConditionalTree, ArrayBuffer[(Node, Node)]]()
    val conditionalTrees = mutable.Map[ConditionalContext, ConditionalTree]()

    def isEmpty = logicChunk.isEmpty && conditionalTrees.isEmpty

    def isNotEmpty = !isEmpty


    def walkWhenTree(root: Node, that: Node): Unit = {
      def getElements: Iterator[Node] = {
        if (that.isInstanceOf[MultipleAssignmentNode]) {
          return that.getInputs
        } else {
          return Iterator(that)
        }
      }

      var lastConditionalTree: ConditionalTree = null
      for (node <- getElements) {
        node match {
          case whenNode: WhenNode => {
            if (!whenNode.whenTrue.isInstanceOf[NoneNode]) {
              val whenTree = this.conditionalTrees.getOrElseUpdate(whenNode.w, new WhenTree(whenNode.cond, node.instanceCounter)).asInstanceOf[WhenTree]
              lastConditionalTree = whenTree
              whenTree.whenTrue.walkWhenTree(root, whenNode.whenTrue)
            }
            if (!whenNode.whenFalse.isInstanceOf[NoneNode]) {
              val whenTree = this.conditionalTrees.getOrElseUpdate(whenNode.w, new WhenTree(whenNode.cond, node.instanceCounter)).asInstanceOf[WhenTree]
              lastConditionalTree = whenTree
              whenTree.whenFalse.walkWhenTree(root, whenNode.whenFalse)
            }
          }
          case switchNode: SwitchNode => {
            val switchTree = this.conditionalTrees.getOrElseUpdate(switchNode.context, new SwitchTree(node.instanceCounter, switchNode.context)).asInstanceOf[SwitchTree]
            lastConditionalTree = switchTree
            switchNode.onEachInput(input => {
              val caseNode = input.asInstanceOf[CaseNode]
              val tmp = switchTree.cases(caseNode.context.id)
              var caseElement = if (tmp != null) tmp
              else {
                val tmp = (caseNode.cond -> new AssignementLevel)
                switchTree.cases(caseNode.context.id) = tmp
                tmp
              }
              caseElement._2.walkWhenTree(root, caseNode.assignement)
            })
          }
          case reg: Reg =>
          case _ => this.logicChunk.getOrElseUpdate(lastConditionalTree, new ArrayBuffer[(Node, Node)]) += new Tuple2(root, node)
        }
      }
    }


    def emitContext(ret: mutable.StringBuilder, tab: String, assignementKind: String, isElseIf: Boolean = false): Unit = {
      def emitLogicChunk(key: WhenTree): Unit = {
        if (this.logicChunk.contains(key)) {
          for ((to, from) <- this.logicChunk.get(key).get) {
            emitAssignement(to, from, ret, tab, assignementKind)
          }
        }
      }
      val firstTab = if (isElseIf) "" else tab

      emitLogicChunk(null)
      //OPT tolist.sort
      for (conditionalTree <- this.conditionalTrees.values.toList.sortWith(_.instanceCounter < _.instanceCounter)) conditionalTree match {
        case when: WhenTree => {
          def doTrue = when.whenTrue.isNotEmpty
          def doFalse = when.whenFalse.isNotEmpty

//          def isCondSwitchReady : Boolean = {
//            true
//          }

          val condLogic = emitLogic(when.cond)
          val condLogicCleaned = if(condLogic.startsWith("pkg_toStdLogic(")) condLogic.substring("pkg_toStdLogic(".length,condLogic.length-1) else condLogic + " = '1'"

          if (doTrue && !doFalse) {
            ret ++= s"${firstTab}if $condLogicCleaned then\n"
            when.whenTrue.emitContext(ret, tab + "  ", assignementKind)
            ret ++= s"${tab}end if;\n"
          } else /*if (doTrue && doFalse)*/ {
            ret ++= s"${firstTab}if $condLogicCleaned then\n"
            when.whenTrue.emitContext(ret, tab + "  ", assignementKind)
            val falseHead = if (when.whenFalse.logicChunk.isEmpty && when.whenFalse.conditionalTrees.size == 1) when.whenFalse.conditionalTrees.head._1 else null
            if (falseHead != null && falseHead.isInstanceOf[WhenContext] && falseHead.asInstanceOf[WhenContext].parentElseWhen != null) {
              ret ++= s"${tab}els"
              when.whenFalse.emitContext(ret, tab, assignementKind, true)
            } else {
              ret ++= s"${tab}else\n"
              when.whenFalse.emitContext(ret, tab + "  ", assignementKind)
              ret ++= s"${tab}end if;\n"
            }
          }
          emitLogicChunk(when)
        }
        case switchTree: SwitchTree => {
          for (caseElement <- switchTree.cases if caseElement != null) {
            val (cond, level) = caseElement
            ret ++= s"${tab}if ${emitLogic(cond)} = '1'  then\n"
            level.emitContext(ret, tab + "  ", assignementKind)
            ret ++= s"${tab}end if;\n"
          }
        }
      }
    }
  }

  def emitComponentInstances(component: Component, ret: StringBuilder): Unit = {
    for (kind <- component.kinds) {
      val isBB = kind.isInstanceOf[BlackBox]
      val isBBUsingULogic = isBB && kind.asInstanceOf[BlackBox].isUsingULogic
      val definitionString = if (isBB) kind.definitionName
      else s"entity work.${
        emitedComponentRef.getOrElse(kind, kind).definitionName
      }"
      ret ++= s"  ${
        kind.getName()
      } : $definitionString\n"


      def addULogicCast(bt: BaseType, io: String, logic: String, dir: IODirection): String = {

        if (isBBUsingULogic)
          if (dir == in) {
            bt match {
              case _: Bool => return s"      $io => std_ulogic($logic),\n"
              case _: Bits => return s"      $io => std_ulogic_vector($logic),\n"
              case _ => return s"      $io => $logic,\n"
            }
          } else if (dir == spinal.core.out) {
            bt match {
              case _: Bool => return s"      std_ulogic($io) => $logic,\n"
              case _: Bits => return s"      std_ulogic_vector($io) => $logic,\n"
              case _ => return s"      $io => $logic,\n"
            }
          } else SpinalError("???")

        else
          return s"      $io => $logic,\n"
      }

      if (kind.isInstanceOf[BlackBox]) {
        val bb = kind.asInstanceOf[BlackBox]
        val genericFlat = bb.getGeneric.flatten

        if (genericFlat.size != 0) {
          ret ++= s"    generic map(\n"


          for ((name, e) <- genericFlat) {
            e match {
              case baseType: BaseType => ret ++= addULogicCast(baseType, emitReference(baseType), emitLogic(baseType.getInput(0)), in)
              case s: String => ret ++= s"      ${name} => ${"\""}${s}${"\""},\n"
              case i: Int => ret ++= s"      ${name} => $i,\n"
              case d: Double => ret ++= s"      ${name} => $d,\n"
              case b: Boolean => ret ++= s"      ${name} => $b,\n"
              case t: STime => {
                val d = t.decompose
                ret ++= s"      ${name} => ${d._1} ${d._2},\n"
              }
            }
          }
          ret.setCharAt(ret.size - 2, ' ')
          ret ++= s"    )\n"
        }
      }
      ret ++= s"    port map (\n"
      for (data <- kind.getOrdredNodeIo) {
        if (data.isOutput) {
          val bind = component.kindsOutputsToBindings.getOrElse(data, null)
          if (bind != null) {
            ret ++= addULogicCast(data, emitReference(data), emitReference(bind), data.dir)
          }
        }
        else if (data.isInput)
          ret ++= addULogicCast(data, emitReference(data), emitReference(data.getInput(0)), data.dir)
      }
      ret.setCharAt(ret.size - 2, ' ')

      ret ++= s"    );"
      ret ++= s"\n"
    }
  }
}
