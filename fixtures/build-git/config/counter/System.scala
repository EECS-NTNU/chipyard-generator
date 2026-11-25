package gitworkspace

import chisel3._
import chipyard.harness.{HarnessBinderFunction, HarnessBinders}
import chipyard.iobinders.UARTPort
import gitcounter.GitCounter
import org.chipsalliance.cde.config.Config

class WithGitCounter extends Config((site, here, up) => {
  case HarnessBinders =>
    val previous = up(HarnessBinders)
    val observe: HarnessBinderFunction = {
      case input @ (harness, _: UARTPort, _) =>
        withClockAndReset(harness.harnessBinderClock, harness.harnessBinderReset) {
          val counter = Module(new GitCounter)
          dontTouch(counter.value)
        }
        previous(input)
    }
    observe.orElse(previous)
})

class System extends Config(new WithGitCounter ++ new chipyard.RocketConfig)
