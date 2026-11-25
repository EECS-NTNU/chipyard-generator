package selectfixture.integrations.activate

import chisel3._
import chipyard.harness.{HarnessBinderFunction, HarnessBinders}
import chipyard.iobinders.UARTPort
import org.chipsalliance.cde.config.Config
import selectfixture.integrations.leaf.Pulse

class WithCounter extends Config((site, here, up) => {
  case HarnessBinders =>
    val previous = up(HarnessBinders)
    val observe: HarnessBinderFunction = {
      case input @ (harness, _: UARTPort, _) =>
        withClockAndReset(harness.harnessBinderClock, harness.harnessBinderReset) {
          val counter = chisel3.Module(selectfixture.Module.create(selectfixture.CounterParams(Pulse.width)))
          dontTouch(counter.value)
        }
        previous(input)
    }
    observe.orElse(previous)
})
