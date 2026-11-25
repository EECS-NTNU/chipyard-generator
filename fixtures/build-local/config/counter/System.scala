package localworkspace

import chisel3._
import chipyard.harness.{HarnessBinderFunction, HarnessBinders}
import chipyard.iobinders.UARTPort
import localcounter.LocalCounter
import org.chipsalliance.cde.config.Config

class WithLocalCounter extends Config((site, here, up) => {
  case HarnessBinders =>
    val previous = up(HarnessBinders)
    val observe: HarnessBinderFunction = {
      case input @ (harness, _: UARTPort, _) =>
        withClockAndReset(harness.harnessBinderClock, harness.harnessBinderReset) {
          val counter = Module(new LocalCounter)
          dontTouch(counter.value)
        }
        previous(input)
    }
    observe.orElse(previous)
})

class System extends Config(new WithLocalCounter ++ new chipyard.RocketConfig)
