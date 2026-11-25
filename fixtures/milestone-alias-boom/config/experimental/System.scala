package aliasboom

import boom_experimental.v3.common.{WithNSmallBooms, WithTilePMU}
import chipyard.config.AbstractConfig
import org.chipsalliance.cde.config.Config

class System extends Config(new WithTilePMU() ++ new WithNSmallBooms(1) ++ new AbstractConfig)
