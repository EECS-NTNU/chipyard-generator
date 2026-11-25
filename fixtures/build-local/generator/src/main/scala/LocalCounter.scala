package localcounter

import chisel3._

class LocalCounter extends Module {
  val value = IO(Output(UInt(8.W)))
  val count = RegInit(0.U(8.W))
  count := count + 1.U
  value := count
}
