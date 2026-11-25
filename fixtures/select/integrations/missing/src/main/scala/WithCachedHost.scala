package selectfixture.integrations.missing

import gitcounter.GitCounter

object WithCachedHost {
  def fresh(): GitCounter = new GitCounter
}
