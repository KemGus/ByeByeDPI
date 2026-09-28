package io.github.romanvht.byedpi.strategy

enum class DecisionKind { Run, Order, Skip, Seed, Try, Result, Stop, Network, Switch }

/** One step of the reasoning behind what the tester tried or switched to, and why. */
data class Decision(
    val time: Long = 0,
    val kind: DecisionKind = DecisionKind.Run,
    val title: String = "",
    val detail: String = "",
)

/** Bounded, append-only list of decisions; oldest entries fall off first. */
class DecisionLog(private val capacity: Int = 400, initial: List<Decision> = emptyList()) {
    private val items = ArrayDeque(initial.takeLast(capacity))

    fun add(decision: Decision) {
        items.addLast(decision)
        while (items.size > capacity) items.removeFirst()
    }

    fun snapshot(): List<Decision> = items.toList()

    fun clear() = items.clear()
}
