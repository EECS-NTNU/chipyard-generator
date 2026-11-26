package wrapperconsumer

import chisel3._
import chisel3.util.Cat
import chipyard.harness.{HarnessBinderFunction, HarnessBinders}
import chipyard.iobinders.UARTPort
import org.chipsalliance.cde.config.Config

class WithCheckedWrapper extends Config((site, here, up) => {
  case HarnessBinders =>
    val previous = up(HarnessBinders)
    val observe: HarnessBinderFunction = {
      case input @ (harness, _: UARTPort, _) =>
        withClockAndReset(harness.harnessBinderClock, harness.harnessBinderReset) {
          val transform = rtlfixture.Module.create()
          val value = RegInit(0.U(8.W))
          val done = RegInit(false.B)
          transform.io.inputByte := value
          when (!done) {
            val expected = Cat(value(3, 0), value(7, 4)) ^ "hA5".U
            assert(transform.io.outputByte === expected)
            value := value + 1.U
            when (value === 255.U) {
              done := true.B
              printf("Wrapper checked all 256 inputs\n")
            }
          }
        }
        previous(input)
    }
    observe.orElse(previous)
})

class System extends Config(new WithCheckedWrapper ++ new chipyard.RocketConfig)
