package chipyard.evaluation

import bismo.BitSerialMatMulParams
import fpgatidbits.PlatformWrapper.PlatformWrapperParams
import freechips.rocketchip.rocket.WithNBigCores
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule

private object BismoMemory extends PlatformWrapperParams {
  val numMemPorts = 1
  val platformName = "Chipyard"
  val memAddrBits = 32
  val memDataBits = 64
  val memIDBits = 1
  val memMetaBits = 1
  val sameIDInOrder = true
  val coherentMem = true
  val typicalMemLatencyCycles = 50
  val burstBeats = 1
}

class WithBismoRoCC(opcodes: OpcodeSet, rows: Int, columns: Int, common: Int, entries: Int)
    extends Config((site, here, up) => {
      case BuildRoCC => up(BuildRoCC) :+ { (p: Parameters) =>
        val accelerator = new BitSerialMatMulParams(
          dpaDimLHS = rows,
          dpaDimRHS = columns,
          dpaDimCommon = common,
          lhsEntriesPerMem = entries,
          rhsEntriesPerMem = entries,
          mrp = BismoMemory.toMemReqParams()
        )
        LazyModule(new BismoRoCC(opcodes, accelerator, BismoMemory)(p))
      }
    })

class BismoRocketConfig extends Config(
  new WithBismoRoCC(OpcodeSet.custom0, rows = 2, columns = 2, common = 64, entries = 64) ++
  new WithNBigCores(1) ++
  new chipyard.config.AbstractConfig
)

class WideBismoRocketConfig extends Config(
  new WithBismoRoCC(OpcodeSet.custom0, rows = 4, columns = 2, common = 64, entries = 64) ++
  new WithNBigCores(1) ++
  new chipyard.config.AbstractConfig
)
