package selectfixture

import chisel3._

final case class CounterParams(width: Int = 11)

object Module {
  def create(params: CounterParams = CounterParams()): SelectedCounter = new SelectedCounter(params)
}

final class SelectedCounter(params: CounterParams) extends chisel3.Module {
  val value = IO(Output(UInt(params.width.W)))
  val counter = RegInit(0.U(params.width.W))
  counter := counter + 1.U
  value := counter
}
