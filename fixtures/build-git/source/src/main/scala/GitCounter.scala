package gitcounter

import chisel3._

class GitCounter extends Module {
  val value = IO(Output(UInt(7.W)))
  val count = RegInit(0.U(7.W))
  count := count + 3.U
  value := count
}
