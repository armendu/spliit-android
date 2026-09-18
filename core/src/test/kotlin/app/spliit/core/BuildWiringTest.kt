package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Proves the JVM test wiring in :core actually runs. See the twin in :api for why this exists.
 */
class BuildWiringTest {
    @Test
    fun `junit platform runs tests in core`() {
        assertEquals(4, 2 + 2)
    }
}
