package io.github.romanvht.byedpi.strategy

/**
 * What was measured for one command on one network. Score is the success percentage of the latest
 * run; `full` says it was measured on the whole site list rather than a quick-screen sample.
 */
data class ProfileEntry(var score: Int = 0, var testedAt: Long = 0, var full: Boolean = false)

data class NetworkProfile(
    var label: String = "",
    val entries: MutableMap<String, ProfileEntry> = mutableMapOf(),
    /** Sites no strategy got through, with the time they were last seen dead. */
    val deadSites: MutableMap<String, Long> = mutableMapOf(),
) {
    val best: Map.Entry<String, ProfileEntry>?
        get() = entries.maxByOrNull { it.value.score }?.takeIf { it.value.score > 0 }
}

/** Per-network test history: which commands worked where. Pure data, persistence lives elsewhere. */
class ProfileBook(val profiles: MutableMap<String, NetworkProfile> = mutableMapOf()) {

    fun record(networkKey: String, label: String, command: String, score: Int, now: Long, full: Boolean = true) {
        val profile = profiles.getOrPut(networkKey) { NetworkProfile(label) }
        profile.label = label
        // A quick screen is a rough guess and must not bury an earlier full measurement.
        if (!full && profile.entries[command]?.full == true) return
        profile.entries[command] = ProfileEntry(score, now, full)
        if (profile.entries.size > MAX_ENTRIES) {
            profile.entries.entries.sortedWith(compareBy({ it.value.score }, { it.value.testedAt }))
                .take(profile.entries.size - MAX_ENTRIES)
                .forEach { profile.entries.remove(it.key) }
        }
    }

    fun scores(networkKey: String): Map<String, Int> =
        profiles[networkKey]?.entries?.mapValues { it.value.score } ?: emptyMap()

    fun best(networkKey: String): String? = profiles[networkKey]?.best?.key

    /** Commands that worked in a full measurement here, best first. */
    fun fullWinners(networkKey: String): List<String> =
        profiles[networkKey]?.entries?.filter { it.value.full && it.value.score > 0 }
            ?.entries?.sortedByDescending { it.value.score }?.map { it.key } ?: emptyList()

    fun markDead(networkKey: String, label: String, sites: Collection<String>, now: Long) {
        val profile = profiles.getOrPut(networkKey) { NetworkProfile(label) }
        sites.forEach { profile.deadSites[it] = now }
    }

    /** Dead sites seen recently enough to trust; older entries may be a passing outage. */
    fun deadSites(networkKey: String, now: Long): Set<String> =
        profiles[networkKey]?.deadSites?.filter { now - it.value < DEAD_TTL_MS }?.keys ?: emptySet()

    companion object {
        const val MAX_ENTRIES = 200
        const val DEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
