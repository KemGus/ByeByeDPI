package io.github.romanvht.byedpi.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileBookTest {
    @Test
    fun keepsNetworksSeparate() {
        val book = ProfileBook()
        book.record("wifi:home", "home", "-s1", 80, 1)
        book.record("cell:25001", "mobile", "-d1", 60, 2)
        assertEquals("-s1", book.best("wifi:home"))
        assertEquals("-d1", book.best("cell:25001"))
        assertNull(book.best("wifi:work"))
    }

    @Test
    fun latestResultReplacesOlderOne() {
        val book = ProfileBook()
        book.record("n", "n", "-s1", 80, 1)
        book.record("n", "n", "-s1", 0, 2)
        assertNull(book.best("n"))
        assertEquals(mapOf("-s1" to 0), book.scores("n"))
    }

    @Test
    fun trimsLowestScoresWhenFull() {
        val book = ProfileBook()
        repeat(ProfileBook.MAX_ENTRIES + 10) { book.record("n", "n", "-s$it", it % 100, it.toLong()) }
        assertEquals(ProfileBook.MAX_ENTRIES, book.scores("n").size)
        assertEquals(99, book.profiles.getValue("n").best!!.value.score)
    }
}
