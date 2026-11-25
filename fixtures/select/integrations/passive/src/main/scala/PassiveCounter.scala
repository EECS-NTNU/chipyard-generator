package selectfixture.integrations.passive

import chisel3._

class PassiveCounter extends chisel3.Module {
  val value = IO(Output(Bool()))
  value := RegNext(!value, false.B)
}
