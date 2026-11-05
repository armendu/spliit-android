package app.spliit.android.feature.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InstanceAddressNormalizeTest {
    @Test
    fun `a bare host gets https and a trailing slash`() {
        assertEquals("https://spliit.app/", InstanceAddress.normalize("spliit.app"))
    }

    @Test
    fun `scheme, host and port are kept and lower-cased`() {
        assertEquals("http://10.0.2.2:3009/", InstanceAddress.normalize("HTTP://10.0.2.2:3009"))
    }

    @Test
    fun `a path prefix is part of the address`() {
        assertEquals("https://home.example.com/spliit/", InstanceAddress.normalize("home.example.com/spliit"))
    }

    @Test
    fun `a pasted group link gives the server, not the group`() {
        assertEquals("https://spliit.app/", InstanceAddress.normalize("https://spliit.app/groups/abc123/expenses"))
        assertEquals(
            "https://home.example.com/spliit/",
            InstanceAddress.normalize("https://home.example.com/spliit/groups/abc123"),
        )
    }

    @Test
    fun `a query or fragment is dropped`() {
        assertEquals("https://spliit.app/", InstanceAddress.normalize("https://spliit.app/?ref=share#top"))
    }

    @Test
    fun `text with no host is refused`() {
        assertNull(InstanceAddress.normalize(""))
        assertNull(InstanceAddress.normalize("   "))
        assertNull(InstanceAddress.normalize("https://"))
    }
}
