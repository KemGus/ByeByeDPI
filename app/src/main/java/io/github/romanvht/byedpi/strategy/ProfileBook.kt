package io.github.romanvht.byedpi.strategy

/** What was measured for one command on one network. Score is the success percentage of the latest run. */
data class ProfileEntry(var score: Int = 0, var testedAt: Long = 0)

data class NetworkProfile(
    var label: String = "",
    val entries: MutableMap<String, ProfileEntry> = mutableMapOf(),
) {
    val best: Map.Entry<String, ProfileEntry>?
        get() = entries.maxByOrNull { it.value.score }?.takeIf { it.value.score > 0 }
}

/** Per-network test history: which commands worked where. Pure data, persistence lives elsewhere. */
class ProfileBook(val profiles: MutableMap<String, NetworkProfile> = mutableMapOf()) {

    fun record(networkKey: String, label: String, command: String, score: Int, now: Long) {
        val profile = profiles.getOrPut(networkKey) { NetworkProfile(label) }
        profile.label = label
        profile.entries[command] = ProfileEntry(score, now)
        if (profile.entries.size > MAX_ENTRIES) {
            profile.entries.entries.sortedWith(compareBy({ it.value.score }, { it.value.testedAt }))
                .take(profile.entries.size - MAX_ENTRIES)
                .forEach { profile.entries.remove(it.key) }
        }
    }

    fun scores(networkKey: String): Map<String, Int> =
        profiles[networkKey]?.entries?.mapValues { it.value.score } ?: emptyMap()

    fun best(networkKey: String): String? = profiles[networkKey]?.best?.key

    companion object {
        const val MAX_ENTRIES = 200
    }
}
