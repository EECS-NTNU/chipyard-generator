package aliascounter

import chisel3._
import chipyard.harness.{HarnessBinderFunction, HarnessBinders}
import chipyard.iobinders.UARTPort
import org.chipsalliance.cde.config.Config

class CounterHarness(create: () => (Module, UInt)) extends Config((site, here, up) => {
  case HarnessBinders =>
    val previous = up(HarnessBinders)
    val observe: HarnessBinderFunction = {
      case input @ (harness, _: UARTPort, _) =>
        withClockAndReset(harness.harnessBinderClock, harness.harnessBinderReset) {
          val (_, value) = create()
          dontTouch(value)
        }
        previous(input)
    }
    observe.orElse(previous)
})
