package app.spliit.android.ui.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    // Expected values ported from the iOS app's MonogramPaletteTests.swift.
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
