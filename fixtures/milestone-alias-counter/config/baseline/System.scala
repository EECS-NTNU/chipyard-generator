package aliascounter

import chisel3.Module
import counter_baseline.RevisionCounter
import org.chipsalliance.cde.config.Config

class System extends Config(new CounterHarness(() => {
  val counter = Module(new RevisionCounter)
  (counter, counter.value)
}) ++ new chipyard.RocketConfig)
