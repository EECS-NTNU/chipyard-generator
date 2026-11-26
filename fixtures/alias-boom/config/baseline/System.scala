package aliasboom

import boom_baseline.v3.common.{BoomTileAttachParams, WithNSmallBooms}
import chipyard.config.AbstractConfig
import freechips.rocketchip.subsystem.{InSubsystem, TilesLocated}
import org.chipsalliance.cde.config.Config

class AssertPrePMUBoom extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) =>
    val tiles = up(TilesLocated(InSubsystem), site)
    require(tiles.exists {
      case boom: BoomTileAttachParams => !boom.tileParams.productElementNames.contains("pmu")
      case _ => false
    }, "Expected a BOOM tile from the revision before TilePMU support.")
    tiles
})

class System extends Config(new AssertPrePMUBoom ++ new WithNSmallBooms(1) ++ new AbstractConfig)
