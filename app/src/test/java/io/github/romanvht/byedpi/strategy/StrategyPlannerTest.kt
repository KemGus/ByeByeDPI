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
}
