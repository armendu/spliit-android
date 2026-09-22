package app.spliit.android.ui.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A monogram colour that moves is worse than no colour at all, the whole point is learning to
 * recognise someone by it. These pin the two ways it could move: between launches (Swift's
 * `hashValue` is reseeded per process; `String.hashCode()` is stable per JVM run but not
 * specified across JVM versions), and between this app and the iOS one for the same ID.
 */
class MonogramPaletteTest {

    @Test
    fun `index is stable for the same id`() {
        val id = "clx1v9m2h0000abcd"
        val first = MonogramPalette.colorIndex(id)
        assertEquals(first, MonogramPalette.colorIndex(id))
        assertEquals(first, MonogramPalette.colorIndex(id))
    }

    @Test
    fun `index always lands inside the palette`() {
        for (seed in listOf("", "a", "clx1v9m2h0000abcd", "🙂", "x".repeat(500))) {
            val index = MonogramPalette.colorIndex(seed)
            assertTrue(index in 0 until MonogramPalette.COUNT)
        }
    }

    /**
     * Not just internal stability, the values themselves, ported directly from the iOS app's
     * checked-in expectations (`MonogramPaletteTests.swift`). The FNV-1a offset basis, prime and
     * fold here are byte-for-byte the same algorithm, so a participant ID hashes to the same
     * colour index on both platforms, verified against those numbers, not merely against
     * itself.
     */
    @Test
    fun `mapping matches the iOS app's recorded values`() {
        assertEquals(0, MonogramPalette.colorIndex("participant-1"))
        assertEquals(1, MonogramPalette.colorIndex("participant-2"))
        assertEquals(5, MonogramPalette.colorIndex("ana"))
        assertEquals(7, MonogramPalette.colorIndex("bruno"))
    }

    @Test
    fun `different participants mostly get different colours`() {
        val indices = (0 until 200).map { MonogramPalette.colorIndex("participant-$it") }.toSet()
        assertEquals(MonogramPalette.COUNT, indices.size)
    }

    @Test
    fun `initials come from the first two words`() {
        assertEquals("SC", MonogramPalette.initials("Sébastien Castiel"))
        assertEquals("J", MonogramPalette.initials("Jane"))
        assertEquals("AM", MonogramPalette.initials("ana maria silva"))
        assertEquals("PN", MonogramPalette.initials("  padded   name  "))
    }

    @Test
    fun `a nameless participant gets a blank chip, not a placeholder`() {
        assertEquals("", MonogramPalette.initials(""))
        assertEquals("", MonogramPalette.initials("   "))
    }
}
