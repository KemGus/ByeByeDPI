package io.github.romanvht.byedpi.strategy

object StrategyPlanner {
    /** Sites to look at before a strategy with zero successes is abandoned. */
    const val PROBE_SITES = 12

    /**
     * Known winners first (best score first), then commands never tried on this network in their
     * original order, then commands that already scored zero here.
     */
    fun order(commands: List<String>, scores: Map<String, Int>): List<String> {
        val (tested, untested) = commands.distinct().partition { it in scores }
        val (working, dead) = tested.partition { scores.getValue(it) > 0 }
        return working.sortedByDescending { scores.getValue(it) } + untested + dead
    }

    /** A strategy that got nothing through `PROBE_SITES` sites is not going to recover on the rest. */
    fun isHopeless(checked: Int, succeeded: Int): Boolean = checked >= PROBE_SITES && succeeded == 0

    /** Best commands to mutate next: highest scoring first, ignoring anything that scored zero. */
    fun seeds(results: Map<String, Int>, limit: Int): List<String> =
        results.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(limit).map { it.key }
}
