package rtlfixture

import chisel3._
import chisel3.util.HasBlackBoxResource

object Module {
  def create(): ByteTransform = chisel3.Module(new ByteTransform)
}

class ByteTransform extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val inputByte = Input(UInt(8.W))
    val outputByte = Output(UInt(8.W))
  })

  addResource("/vsrc/ByteTransform.sv")
}
