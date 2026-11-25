package counter

import chisel3._

class RevisionCounter extends Module {
  override def desiredName: String = "AliasCounterA"
  val value = IO(Output(UInt(8.W)))
  val count = RegInit(0.U(8.W))
  count := count + 1.U
  value := count
}
