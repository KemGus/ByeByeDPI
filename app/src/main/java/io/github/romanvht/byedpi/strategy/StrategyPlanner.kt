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

    /** Evenly spread sample so every part of an ordered site list is represented. */
    fun sample(sites: List<String>, limit: Int): List<String> =
        if (sites.size <= limit) sites else List(limit) { sites[it * sites.size / limit] }

    /** How many strategies get the full site list after a quick screen. */
    fun promoteCount(total: Int): Int = maxOf(5, total / 5).coerceAtMost(total)

    /** Two measurements of the same strategy, rounded half up. */
    fun average(first: Int, second: Int): Int = (first + second + 1) / 2

    /** Open sites kept as a sanity check that a strategy does not break normal traffic. */
    const val CANARY_SITES = 5
    private const val MIN_SITES = 10

    /**
     * Sites worth measuring: skip ones reachable without any bypass and ones known to be dead here,
     * but keep a few open ones so a strategy that breaks normal traffic still shows up.
     */
    fun informativeSites(all: List<String>, open: Set<String>, dead: Set<String>): List<String> {
        val blocked = all.filter { it !in open && it !in dead }
        val canaries = sample(all.filter { it in open && it !in dead }, CANARY_SITES).toSet()
        val picked = all.filter { it in blocked || it in canaries }
        return if (picked.size >= MIN_SITES) picked else all.filter { it !in dead }.ifEmpty { all }
    }

    /** Sites that not a single one of enough measured strategies got through. */
    fun deadSites(results: List<Map<String, Int>>, minStrategies: Int = 5): Set<String> {
        if (results.size < minStrategies) return emptySet()
        return results.flatMap { it.keys }.filter { site -> results.all { (it[site] ?: 0) == 0 } }.toSet()
    }
}
