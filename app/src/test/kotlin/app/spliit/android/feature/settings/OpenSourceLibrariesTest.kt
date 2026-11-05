package app.spliit.android.feature.settings

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenSourceLibrariesTest {
    // Test runners only, never inside the APK.
    private val notShipped = listOf("org.junit.")

    @Test
    fun `every library in the catalogue is credited in Settings`() {
        val catalogue = File("../gradle/libs.versions.toml").readText()
        val modules = Regex("""module = "([^"]+)"""").findAll(catalogue).map { it.groupValues[1] }.toList()
        assertTrue(modules.isNotEmpty(), "found no modules in the catalogue")

        val uncredited = modules.filter { module ->
            notShipped.none { module.startsWith(it) } &&
                openSourceLibraries.none { library -> library.modules.any { module.startsWith(it) } }
        }

        assertEquals(emptyList<String>(), uncredited, "add these to openSourceLibraries")
    }
}
