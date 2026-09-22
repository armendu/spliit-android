package app.spliit.android.feature.groups

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AddGroupByUrlViewModelTest {

    private val server = MockWebServer()

    @BeforeEach
    fun startServer() = server.start()

    @AfterEach
    fun stopServer() = server.close()

    @Test
    fun `a url that is not a group link is rejected`() = runBlocking {
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store)
        viewModel.setUrlText("not a group link")

        val added = viewModel.submit()

        assertFalse(added)
        assertEquals(0, server.requestCount, "no request should be made for text that isn't a link")
        assertNotNull(viewModel.state.value.problem)
        assertTrue(store.load().groups.isEmpty())
    }

    @Test
    fun `a group that does not exist is not stored`() = runBlocking {
        val instance = server.url("/").toString()
        server.enqueue(MockResponse.Builder().code(200).body(okBody("""{"group":null}""")).build())
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store)
        viewModel.setUrlText("${instance}groups/does-not-exist")

        val added = viewModel.submit()

        assertFalse(added)
        assertNotNull(viewModel.state.value.problem)
        assertTrue(store.load().groups.isEmpty(), "a group the server denies must never be stored")
    }

    @Test
    fun `a self-hosted group is verified, stored, and remembers its instance`() = runBlocking {
        val instance = server.url("/").toString()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody(
                    """{"group":{"id":"g1","name":"Weekend in Lisbon","currency":"€",""" +
                        """"createdAt":"2025-01-01T00:00:00.000Z","participants":[]}}""",
                ),
            ).build(),
        )
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store)
        viewModel.setUrlText("${instance}groups/g1")

        val added = viewModel.submit()

        assertTrue(added)
        assertEquals("g1", viewModel.state.value.addedGroupId)
        val stored = store.load().groups.single()
        assertEquals("g1", stored.groupId)
        assertEquals(instance, stored.instanceBaseUrl)
        assertEquals("Weekend in Lisbon", stored.groupName)
    }

    @Test
    fun `a bare id falls back to the default instance`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody(
                    """{"group":{"id":"g1","name":"Flat 3B","currency":"$",""" +
                        """"createdAt":"2025-01-01T00:00:00.000Z","participants":[]}}""",
                ),
            ).build(),
        )
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store, defaultInstanceBaseUrl = { instance })
        viewModel.setUrlText("g1")

        val added = viewModel.submit()

        assertTrue(added)
        assertEquals(instance, store.load().groups.single().instanceBaseUrl)
    }

    @Test
    fun `a real group id, hyphens and all, is a bare id and not a hostname`() = runBlocking {
        // The shape people actually paste out of an address bar: a nanoid, which may carry
        // hyphens and underscores. Prefixing "https://" onto it, which is right for a
        // scheme-less URL, would make the ID the hostname and look up a server that isn't there.
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody(
                    """{"group":{"id":"ZaeM1TyDC-5K8D-PFkICV","name":"Flat 3B","currency":"$",""" +
                        """"createdAt":"2025-01-01T00:00:00.000Z","participants":[]}}""",
                ),
            ).build(),
        )
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store, defaultInstanceBaseUrl = { instance })
        viewModel.setUrlText("  ZaeM1TyDC-5K8D-PFkICV  ")

        assertTrue(viewModel.submit())
        assertEquals("ZaeM1TyDC-5K8D-PFkICV", store.load().groups.single().groupId)
    }

    @Test
    fun `a link to a group that isn't there names the instance it looked on`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okBody("""{"group":null}""")).build())
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store, defaultInstanceBaseUrl = { instance })
        viewModel.setUrlText("nope")

        assertFalse(viewModel.submit())
        // "No group with that link exists" on its own leaves out the half that is actionable:
        // which server was asked. A bare ID is looked up on the default instance, which may not
        // be the one the group is on.
        val problem = viewModel.state.value.problem
        assertNotNull(problem)
        assertTrue(
            problem!!.contains(InstanceAddress.displayName(instance)),
            "the error must name the instance, was: $problem",
        )
    }

    @Test
    fun `the state carries the default instance, which is what the placeholder is built from`() {
        val viewModel = AddGroupByUrlViewModel(FakeRecentGroupsStore(), defaultInstanceBaseUrl = { "https://home.example.com/" })

        assertEquals("https://home.example.com/", viewModel.state.value.defaultInstanceBaseUrl)
    }

    @Test
    fun `reset clears the field, the error and the added flag`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore()
        val viewModel = AddGroupByUrlViewModel(store, defaultInstanceBaseUrl = { instance })
        viewModel.setUrlText("not a group link")
        viewModel.submit()

        viewModel.reset()

        // The sheet is opened against one long-lived ViewModel, so a stale error, or a stale
        // addedGroupId, which would close it the moment it appeared, must not survive a reopen.
        assertEquals("", viewModel.state.value.urlText)
        assertNull(viewModel.state.value.problem)
        assertNull(viewModel.state.value.addedGroupId)
        assertEquals(instance, viewModel.state.value.defaultInstanceBaseUrl)
    }

    @Test
    fun `a group that could not be stored is reported rather than silently dropped`() = runBlocking {
        // Cheaper than the create path, the user still has the link they pasted, but saying
        // "added" for a row that did not store sends them to a list without it.
        val store = FakeRecentGroupsStore(failSaves = true)
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody("""{"group":{"id":"g1","name":"Lisbon","information":null,"currency":"\u20ac",
                    "currencyCode":"EUR","createdAt":"2025-01-01T00:00:00.000Z","participants":[]}}"""),
            ).build(),
        )
        val viewModel = AddGroupByUrlViewModel(recentGroupsStore = store)
        viewModel.setUrlText("${server.url("/")}groups/g1")

        val added = viewModel.submit()

        assertFalse(added)
        assertNull(viewModel.state.value.addedGroupId)
        assertNotNull(viewModel.state.value.problem)
    }

}
