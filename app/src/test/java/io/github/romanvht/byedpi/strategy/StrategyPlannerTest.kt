package io.github.romanvht.byedpi.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyPlannerTest {
    @Test
    fun ordersWinnersThenUntestedThenDead() {
        val commands = listOf("dead", "new1", "ok", "great", "new2")
        val scores = mapOf("dead" to 0, "ok" to 40, "great" to 90)
        assertEquals(listOf("great", "ok", "new1", "new2", "dead"), StrategyPlanner.order(commands, scores))
    }

    @Test
    fun keepsOriginalOrderWithoutHistory() {
        val commands = listOf("b", "a", "c")
        assertEquals(commands, StrategyPlanner.order(commands, emptyMap()))
    }

    @Test
    fun dropsDuplicates() {
        assertEquals(listOf("a", "b"), StrategyPlanner.order(listOf("a", "b", "a"), emptyMap()))
    }

    @Test
    fun hopelessOnlyAfterProbeWithNoSuccess() {
        assertFalse(StrategyPlanner.isHopeless(StrategyPlanner.PROBE_SITES - 1, 0))
        assertTrue(StrategyPlanner.isHopeless(StrategyPlanner.PROBE_SITES, 0))
        assertFalse(StrategyPlanner.isHopeless(StrategyPlanner.PROBE_SITES, 1))
    }

    @Test
    fun seedsSkipZeroScores() {
        val results = mapOf("a" to 0, "b" to 10, "c" to 70, "d" to 30)
        assertEquals(listOf("c", "d"), StrategyPlanner.seeds(results, 2))
    }

    @Test
    fun sampleKeepsShortListsAndSpreadsLongOnes() {
        val short = listOf("a", "b")
        assertEquals(short, StrategyPlanner.sample(short, 5))
        val sites = (0 until 100).map { "s$it" }
        val sample = StrategyPlanner.sample(sites, 10)
        assertEquals(10, sample.distinct().size)
        assertEquals("s0", sample.first())
        assertTrue(sample.last() == "s90")
    }

    @Test
    fun promoteCountIsBounded() {
        assertEquals(3, StrategyPlanner.promoteCount(3))
        assertEquals(5, StrategyPlanner.promoteCount(20))
        assertEquals(12, StrategyPlanner.promoteCount(60))
    }

    @Test
    fun averageRoundsHalfUp() {
        assertEquals(86, StrategyPlanner.average(89, 82))
        assertEquals(50, StrategyPlanner.average(50, 50))
    }

    @Test
    fun informativeSitesKeepsOnlyAFewOpenOnes() {
        val all = (1..40).map { "s$it" }
        val open = (1..20).map { "s$it" }.toSet()
        val picked = StrategyPlanner.informativeSites(all, open, dead = setOf("s30"))
        assertEquals(20 - 1 + StrategyPlanner.CANARY_SITES, picked.size)
        assertFalse("s30" in picked)
        assertEquals(StrategyPlanner.CANARY_SITES, picked.count { it in open })
    }

    @Test
    fun informativeSitesFallsBackWhenTooFewRemain() {
        val all = (1..12).map { "s$it" }
        val picked = StrategyPlanner.informativeSites(all, open = all.toSet(), dead = setOf("s1"))
        assertEquals(all - "s1", picked)
    }

    @Test
    fun deadSitesNeedEnoughStrategiesAndNoSuccess() {
        val results = List(5) { mapOf("a" to 0, "b" to if (it == 2) 1 else 0) }
        assertEquals(setOf("a"), StrategyPlanner.deadSites(results))
        assertEquals(emptySet<String>(), StrategyPlanner.deadSites(results.take(4)))
    }
}
