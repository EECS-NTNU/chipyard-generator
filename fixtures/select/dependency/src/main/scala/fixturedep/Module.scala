package fixturedep

import chisel3._

object Module {
  def create(): RawModule = new FixtureProbe
}

final class FixtureProbe extends RawModule {
  val connected = IO(Output(Bool()))
  connected := true.B
}
