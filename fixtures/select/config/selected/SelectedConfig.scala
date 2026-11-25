package selectfixture

import selectfixture.integrations.activate.{WithCounter => AddCounter}
import selectfixture.integrations.passive.PassiveCounter

object SelectedConfig {
  def config = new AddCounter ++ new chipyard.RocketConfig
}
