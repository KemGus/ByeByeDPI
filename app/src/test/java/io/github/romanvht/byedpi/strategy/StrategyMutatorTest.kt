package io.github.romanvht.byedpi.strategy

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyMutatorTest {
    private val seed = "-Qr -s3:5+sm -a1 -As -d1 -t8 -f-200 -Mh"

    @Test
    fun neverReturnsSeedOrDuplicates() {
        val result = StrategyMutator.candidates(seed, 5, Random(1))
        assertEquals(result.distinct(), result)
        assertFalse(seed in result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun leavesUnrelatedOptionsUntouched() {
        repeat(50) { round ->
            val mutated = StrategyMutator.mutate(seed, Random(round)) ?: return@repeat
            val tokens = mutated.split(" ")
            listOf("-Qr", "-a1", "-As", "-Mh").forEach { assertTrue(it in tokens) }
            assertEquals(seed.split(" ").size, tokens.size)
        }
    }

    @Test
    fun ttlStaysInRange() {
        repeat(100) { round ->
            val ttl = StrategyMutator.mutate("-t2", Random(round))!!.removePrefix("-t").toInt()
            assertTrue(ttl in 1..30)
        }
    }

    @Test
    fun nothingToMutate() {
        assertNull(StrategyMutator.mutate("-Qr -Mh -As", Random(0)))
        assertTrue(StrategyMutator.candidates("-Qr", 3, Random(0)).isEmpty())
    }

    @Test
    fun respectsExclusions() {
        val all = StrategyMutator.candidates("-t8", 20, Random(2))
        val filtered = StrategyMutator.candidates("-t8", 20, Random(2), exclude = all.toSet())
        assertTrue(filtered.none { it in all })
    }

    @Test
    fun describesWhatChanged() {
        assertEquals("-s3:5+sm → -s4:5+sm", StrategyMutator.describe("-Qr -s3:5+sm -a1", "-Qr -s4:5+sm -a1"))
        assertEquals("-a2", StrategyMutator.describe("-a1", "-a2").substringAfter("→ "))
    }
}
