package localworkspace

import chisel3._
import chipyard.harness.{HarnessBinderFunction, HarnessBinders}
import chipyard.iobinders.UARTPort
import localcounter.LocalCounter
import org.chipsalliance.cde.config.Config

class WithCheckedCounter extends Config((site, here, up) => {
  case HarnessBinders =>
    val previous = up(HarnessBinders)
    val observe: HarnessBinderFunction = {
      case input @ (harness, _: UARTPort, _) =>
        withClockAndReset(harness.harnessBinderClock, harness.harnessBinderReset) {
          val counter = Module(new LocalCounter)
          val previousValue = RegNext(counter.value, 0.U)
          val running = RegNext(true.B, false.B)
          val reported = RegInit(false.B)
          when (running) {
            assert(counter.value === previousValue + 1.U)
          }
          when (!reported && counter.value === 16.U) {
            reported := true.B
            printf("M5 whole counter advanced correctly\n")
          }
        }
        previous(input)
    }
    observe.orElse(previous)
})

class System extends Config(new WithCheckedCounter ++ new chipyard.RocketConfig)
