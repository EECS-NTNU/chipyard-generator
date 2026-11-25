package aliasboom

import boom_baseline.v3.common.{WithNSmallBooms => WithBaselineBoom}
import boom_experimental.v3.common.{WithNSmallBooms => WithExperimentalBoom, WithTilePMU}
import chipyard.config.AbstractConfig
import org.chipsalliance.cde.config.Config

class System extends Config(
  new WithTilePMU() ++
  new WithExperimentalBoom(1) ++
  new WithBaselineBoom(1) ++
  new AbstractConfig
)
