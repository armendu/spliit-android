package app.spliit.android.feature.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GroupLinkTest {

    @Test
    fun `a spliit app group URL parses to its id`() {
        val link = GroupLink.parse("https://spliit.app/groups/abc123")

        assertEquals("abc123", link?.groupId)
        assertEquals("https://spliit.app/", link?.instanceBaseUrl)
    }

    @Test
    fun `the web app's share link, with a trailing expenses segment, parses the same way`() {
        val link = GroupLink.parse("https://spliit.app/groups/abc123/expenses?ref=share")

        assertEquals("abc123", link?.groupId)
        assertEquals("https://spliit.app/", link?.instanceBaseUrl)
    }

    @Test
    fun `a self-hosted URL parses, and the instance is remembered`() {
        val link = GroupLink.parse("http://10.0.2.2:3009/groups/xyz")

        assertEquals("xyz", link?.groupId)
        assertEquals("http://10.0.2.2:3009/", link?.instanceBaseUrl)
    }

    @Test
    fun `a self-hosted instance under a path prefix keeps the prefix as part of the instance`() {
        val link = GroupLink.parse("https://home.example.com/spliit/groups/abc")

        assertEquals("abc", link?.groupId)
        assertEquals("https://home.example.com/spliit/", link?.instanceBaseUrl)
    }

    @Test
    fun `a URL missing the groups segment is rejected`() {
        assertNull(GroupLink.parse("https://spliit.app/"))
        assertNull(GroupLink.parse("https://spliit.app/about"))
    }

    @Test
    fun `garbage text is rejected`() {
        assertNull(GroupLink.parse("not a url at all"))
        assertNull(GroupLink.parse(""))
        assertNull(GroupLink.parse("   "))
    }

    @Test
    fun `a bare id pasted from an address bar names no instance`() {
        val link = GroupLink.parse("abc123")

        assertEquals("abc123", link?.groupId)
        assertNull(link?.instanceBaseUrl)
    }

    @Test
    fun `a scheme-less address with a path is still recognised`() {
        val link = GroupLink.parse("spliit.example.com/groups/abc")

        assertEquals("abc", link?.groupId)
        assertEquals("https://spliit.example.com/", link?.instanceBaseUrl)
    }

    @Test
    fun `a real group id keeps its hyphens instead of being read as a hostname`() {
        // Spliit's IDs are nanoids, so a pasted one can carry hyphens and underscores and still
        // be a bare ID rather than an address. The scheme is only ever assumed for text with a
        // slash in it, which is what keeps this from becoming "https://ZaeM1TyDC-5K8D-PFkICV".
        val link = GroupLink.parse("ZaeM1TyDC-5K8D-PFkICV")

        assertEquals("ZaeM1TyDC-5K8D-PFkICV", link?.groupId)
        assertNull(link?.instanceBaseUrl)
    }

    @Test
    fun `surrounding whitespace is trimmed off a pasted id`() {
        assertEquals("abc123", GroupLink.parse("  abc123\n")?.groupId)
    }

    @Test
    fun `scheme and host are lower-cased but the group id is not`() {
        val link = GroupLink.parse("HTTPS://Spliit.App/groups/AbC123")

        assertEquals("AbC123", link?.groupId)
        assertEquals("https://spliit.app/", link?.instanceBaseUrl)
    }
}
