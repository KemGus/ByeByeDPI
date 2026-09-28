package io.github.romanvht.byedpi.strategy

import kotlin.random.Random

/**
 * Produces small variations of a ByeDPI command line.
 *
 * Only options whose numeric argument is safe to nudge are touched: split-like options
 * (`-s -d -o -q -r`), the fake TTL (`-t`) and the fake offset (`-f`). Everything else is
 * copied verbatim, so a valid command stays valid.
 */
object StrategyMutator {
    private val positional = Regex("""^-([sdoqr])(-?\d+)((?::\d+){0,2})((?:\+[a-z]+)?)$""")
    private val ttl = Regex("""^-t(\d+)$""")
    private val fakeOffset = Regex("""^-f(-?\d+)$""")
    private val flagVariants = listOf("+s", "+sm", "+h", "+hm", "+e")
    private val swappable = listOf('s', 'd')

    fun candidates(seed: String, count: Int, random: Random, exclude: Set<String> = emptySet()): List<String> {
        val result = LinkedHashSet<String>()
        repeat(count * 6) {
            if (result.size >= count) return@repeat
            val candidate = mutate(seed, random)
            if (candidate != null && candidate != seed && candidate !in exclude) result.add(candidate)
        }
        return result.toList()
    }

    fun mutate(command: String, random: Random): String? {
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val editable = tokens.indices.filter { index ->
            val token = tokens[index]
            positional.matches(token) || ttl.matches(token) || fakeOffset.matches(token)
        }
        if (editable.isEmpty()) return null

        val index = editable[random.nextInt(editable.size)]
        val mutated = mutateToken(tokens[index], random) ?: return null
        return tokens.toMutableList().also { it[index] = mutated }.joinToString(" ")
    }

    /** Human readable difference between two commands, e.g. "-s3:5+sm → -s4:5+sm". */
    fun describe(seed: String, candidate: String): String {
        val before = seed.trim().split(Regex("\\s+"))
        val after = candidate.trim().split(Regex("\\s+"))
        val changes = before.zip(after).filter { it.first != it.second }.map { "${it.first} → ${it.second}" }
        return if (changes.isEmpty() || before.size != after.size) candidate else changes.joinToString(", ")
    }

    private fun mutateToken(token: String, random: Random): String? {
        positional.matchEntire(token)?.let { match ->
            val (kind, offset, repeat, flags) = match.destructured
            return when (random.nextInt(3)) {
                0 -> "-$kind${shift(offset.toInt(), random)}$repeat$flags"
                1 -> if (flags.isEmpty()) null else "-$kind$offset$repeat${pickOther(flagVariants, flags, random)}"
                else -> if (kind[0] in swappable) "-${pickOther(swappable, kind[0], random)}$offset$repeat$flags" else null
            }
        }
        ttl.matchEntire(token)?.let { return "-t${shift(it.groupValues[1].toInt(), random).coerceIn(1, 30)}" }
        fakeOffset.matchEntire(token)?.let { return "-f${shift(it.groupValues[1].toInt(), random)}" }
        return null
    }

    private fun shift(value: Int, random: Random): Int {
        val step = random.nextInt(1, 4) * if (random.nextBoolean()) 1 else -1
        return value + step
    }

    private fun <T> pickOther(options: List<T>, current: T, random: Random): T {
        val others = options.filter { it != current }
        return others[random.nextInt(others.size)]
    }
}
