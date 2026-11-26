package btbfixture

import btbfixture.integrations.boom.WithFixtureBTB
import org.chipsalliance.cde.config.Config

class System extends Config(new WithFixtureBTB ++ new chipyard.RocketConfig)
