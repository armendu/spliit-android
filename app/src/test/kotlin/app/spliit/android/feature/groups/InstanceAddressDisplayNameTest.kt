package app.spliit.android.feature.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstanceAddressDisplayNameTest {
    @Test
    fun `a plain host is just the host`() {
        assertEquals("spliit.app", InstanceAddress.displayName("https://spliit.app/"))
    }

    @Test
    fun `a port is part of which server this is`() {
        assertEquals("10.0.2.2:3009", InstanceAddress.displayName("http://10.0.2.2:3009/"))
    }

    @Test
    fun `a path prefix is part of it too`() {
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
