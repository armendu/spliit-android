package app.spliit.android.feature.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the app calls a server, wherever it says it.
 *
 * This had no suite, which is how a second answer grew: the Information tab had its own
 * `serverDisplayName` returning the host alone, so the same instance was "10.0.2.2" on one
 * screen and "10.0.2.2:3009" on another, and a self-hosted instance under a path prefix was
 * named as a different server entirely.
 */
class InstanceAddressDisplayNameTest {

    @Test
    fun `a plain host is just the host`() {
        assertEquals("spliit.app", InstanceAddress.displayName("https://spliit.app/"))
    }

    @Test
    fun `a port is part of which server this is`() {
        // The project's own e2e instance. Dropping the port names a server that is not the one
        // the group lives on.
        assertEquals("10.0.2.2:3009", InstanceAddress.displayName("http://10.0.2.2:3009/"))
    }

    @Test
    fun `a path prefix is part of it too`() {
        // A shape GroupLink explicitly supports: one host serving Spliit under a sub-path.
        assertEquals(
            "home.example.com/spliit",
            InstanceAddress.displayName("https://home.example.com/spliit/"),
        )
    }

    @Test
    fun `a port and a prefix together`() {
        assertEquals(
            "home.example.com:8443/spliit",
            InstanceAddress.displayName("https://home.example.com:8443/spliit/"),
        )
    }

    @Test
    fun `something that is not a URL is handed back unchanged`() {
        assertEquals("not a url", InstanceAddress.displayName("not a url"))
    }
}
