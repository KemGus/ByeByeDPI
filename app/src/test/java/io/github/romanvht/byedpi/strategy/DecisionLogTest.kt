package io.github.romanvht.byedpi.strategy

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionLogTest {
    @Test
    fun dropsOldestWhenFull() {
        val log = DecisionLog(capacity = 3)
        (1..5).forEach { log.add(Decision(it.toLong(), DecisionKind.Result, "t$it")) }
        assertEquals(listOf("t3", "t4", "t5"), log.snapshot().map { it.title })
    }

    @Test
    fun clearEmptiesLog() {
        val log = DecisionLog(initial = listOf(Decision(1, DecisionKind.Run, "a")))
        log.clear()
        assertEquals(emptyList<Decision>(), log.snapshot())
    }
}
