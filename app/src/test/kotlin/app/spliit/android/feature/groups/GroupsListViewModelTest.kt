package app.spliit.android.feature.groups

import app.spliit.core.LoadState
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.RefreshLimiter
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

private fun summaryJson(id: String, name: String, participants: Int) =
    """{"id":"$id","name":"$name","currency":"$","createdAt":"2025-01-01T00:00:00.000Z","_count":{"participants":$participants}}"""

private fun group(id: String, instanceBaseUrl: String, lastOpenedAt: Instant): RecentGroup = RecentGroup(
    groupId = id,
    instanceBaseUrl = instanceBaseUrl,
    groupName = "local name for $id",
    lastOpenedAt = lastOpenedAt,
    updatedAt = lastOpenedAt,
)

private val LoadState<GroupsDashboard>.dashboard: GroupsDashboard
    get() = (this as LoadState.Loaded).value

class GroupsListViewModelTest {

    private val server = MockWebServer()

    @BeforeEach
    fun startServer() = server.start()

    @AfterEach
    fun stopServer() = server.close()

    @Test
    fun `stored ids are loaded and their summaries fetched`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(
                groups = listOf(
                    group("g1", instance, Instant.parse("2025-01-01T00:00:00Z")),
                    group("g2", instance, Instant.parse("2025-01-02T00:00:00Z")),
                ),
            ),
        )
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody(
                    """{"groups":[${summaryJson("g1", "Weekend in Lisbon", 3)},${summaryJson("g2", "Flat 3B", 2)}]}""",
                ),
            ).build(),
        )
        val viewModel = GroupsListViewModel(store)

        viewModel.refresh()

        val recent = viewModel.state.value.dashboard.recent
        assertEquals(2, recent.size)
        // g2 was opened more recently, so it sorts first.
        assertEquals("g2", recent[0].groupId)
        assertEquals("Flat 3B", recent[0].name)
        assertEquals(2, recent[0].participantCount)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), recent[0].createdAt)
        assertEquals("g1", recent[1].groupId)
        assertEquals(3, recent[1].participantCount)
    }

    @Test
    fun `a group the server omits drops out silently rather than erroring`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(
                groups = listOf(
                    group("g1", instance, Instant.parse("2025-01-01T00:00:00Z")),
                    group("deleted-group", instance, Instant.parse("2025-01-02T00:00:00Z")),
                ),
            ),
        )
        // The server only answers for g1, deleted-group is silently absent, exactly as a real
        // groups.list response omits an id it no longer recognises.
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(okBody("""{"groups":[${summaryJson("g1", "Weekend in Lisbon", 3)}]}"""))
                .build(),
        )
        val viewModel = GroupsListViewModel(store)

        viewModel.refresh()

        assertEquals(listOf("g1"), viewModel.state.value.dashboard.recent.map { it.groupId })
    }

    @Test
    fun `a group whose instance is unreachable keeps its row, its local name and no count`() = runBlocking {
        val deadUrl = deadInstance()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(groups = listOf(group("g1", deadUrl, Instant.parse("2025-01-01T00:00:00Z")))),
        )
        val viewModel = GroupsListViewModel(store)

        viewModel.refresh()

        // Not Failed: the names are local, and a screen that replaced them with an error would
        // cost somebody the only route back to a group they do have.
        val dashboard = viewModel.state.value.dashboard
        val row = dashboard.recent.single()
        assertEquals("local name for g1", row.name)
        assertNull(row.participantCount, "an unanswered instance costs the count, not the row")
        assertNull(row.createdAt, "an unanswered instance costs the created date the same way")
        assertEquals(1, dashboard.unreachableInstances.size)
    }

    @Test
    fun `one unreachable instance does not cost another instance's groups their detail`() = runBlocking {
        val liveUrl = server.url("/").toString()
        val deadUrl = deadInstance()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(
                groups = listOf(
                    group("live", liveUrl, Instant.parse("2025-01-02T00:00:00Z")),
                    group("dead", deadUrl, Instant.parse("2025-01-01T00:00:00Z")),
                ),
            ),
        )
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(okBody("""{"groups":[${summaryJson("live", "Weekend in Lisbon", 4)}]}"""))
                .build(),
        )
        val viewModel = GroupsListViewModel(store)

        viewModel.refresh()

        val rows = viewModel.state.value.dashboard.recent.associateBy { it.groupId }
        assertEquals(4, rows.getValue("live").participantCount)
        assertNull(rows.getValue("dead").participantCount)
        // One request each: groups.list only ever answers for the server it was sent to.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an unreadable stored list is the one failure with nothing left to draw`() = runBlocking {
        val store = object : app.spliit.core.RecentGroupsStore {
            override suspend fun load(): RecentGroupsSnapshot = throw IllegalStateException("corrupt")
            override suspend fun save(snapshot: RecentGroupsSnapshot) = Unit
        }
        val viewModel = GroupsListViewModel(store)

        viewModel.refresh()

        assertTrue(viewModel.state.value is LoadState.Failed, "expected Failed, got ${viewModel.state.value}")
    }

    @Test
    fun `an empty stored list is a genuine empty Loaded, not Failed`() = runBlocking {
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot())
        val viewModel = GroupsListViewModel(store)

        viewModel.refresh()

        assertTrue(viewModel.state.value.dashboard.isEmpty)
    }

    @Test
    fun `opening a group stamps lastOpenedAt without needing a server round trip`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(groups = listOf(group("g1", instance, Instant.parse("2025-01-01T00:00:00Z")))),
        )
        val viewModel = GroupsListViewModel(store)

        viewModel.markOpened("g1")

        val row = store.load().groups.single()
        assertTrue(row.lastOpenedAt!!.isAfter(Instant.parse("2025-01-01T00:00:00Z")))
    }

    // ---- sections ---------------------------------------------------------------------------

    @Test
    fun `starring moves a group into the starred section, and archiving out of both`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(
                groups = listOf(
                    group("g1", instance, Instant.parse("2025-01-01T00:00:00Z")),
                    group("g2", instance, Instant.parse("2025-01-02T00:00:00Z")),
                ),
            ),
        )
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody("""{"groups":[${summaryJson("g1", "Lisbon", 3)},${summaryJson("g2", "Flat 3B", 2)}]}"""),
            ).build(),
        )
        val viewModel = GroupsListViewModel(store)
        viewModel.refresh()

        viewModel.markStarred("g1", isStarred = true)

        var dashboard = viewModel.state.value.dashboard
        assertEquals(listOf("g1"), dashboard.starred.map { it.groupId })
        assertEquals(listOf("g2"), dashboard.recent.map { it.groupId })
        // Redrawn from what was already fetched: starring changes nothing a server knows.
        assertEquals(1, server.requestCount)
        assertEquals(3, dashboard.starred.single().participantCount)

        viewModel.markArchived("g1", isArchived = true)

        dashboard = viewModel.state.value.dashboard
        assertTrue(dashboard.starred.isEmpty())
        assertEquals(listOf("g1"), dashboard.archived.map { it.groupId })
        assertTrue(store.load().groups.single { it.groupId == "g1" }.isArchived)
    }

    // ---- removal, and taking it back --------------------------------------------------------

    @Test
    fun `a removed group leaves the list at once but is only forgotten when the window closes`() = runBlocking {
        val store = storeWithOneGroup()
        val viewModel = GroupsListViewModel(store)
        viewModel.refresh()

        viewModel.beginRemoval("g1")

        assertTrue(viewModel.state.value.dashboard.isEmpty, "the row goes as soon as it is removed")
        assertEquals("g1", viewModel.pendingRemoval.value?.groupId)
        // The irreversible half has not happened: a group is reachable only by its link, so the
        // `forget` waits out the undo window.
        assertEquals(listOf("g1"), store.load().groups.map { it.groupId })
        assertTrue(store.load().tombstones.isEmpty())
    }

    @Test
    fun `undo puts the group back and never writes the forget`() = runBlocking {
        val store = storeWithOneGroup()
        val viewModel = GroupsListViewModel(store)
        viewModel.refresh()
        viewModel.beginRemoval("g1")

        viewModel.undoRemoval()

        assertEquals(listOf("g1"), viewModel.state.value.dashboard.recent.map { it.groupId })
        assertNull(viewModel.pendingRemoval.value)
        assertEquals(listOf("g1"), store.load().groups.map { it.groupId })
        assertTrue(store.load().tombstones.isEmpty(), "an undone removal must not leave a tombstone")
    }

    @Test
    fun `committing the removal forgets the group and leaves a tombstone`() = runBlocking {
        val store = storeWithOneGroup()
        val viewModel = GroupsListViewModel(store)
        viewModel.refresh()
        viewModel.beginRemoval("g1")

        viewModel.commitPendingRemoval()

        assertTrue(store.load().groups.isEmpty())
        assertTrue(store.load().tombstones.containsKey("g1"), "forget must leave a tombstone")
        assertNull(viewModel.pendingRemoval.value)
        assertTrue(viewModel.state.value.dashboard.isEmpty)
    }

    private fun storeWithOneGroup(): FakeRecentGroupsStore {
        val instance = server.url("/").toString()
        server.enqueue(
            MockResponse.Builder().code(200)
                .body(okBody("""{"groups":[${summaryJson("g1", "Lisbon", 3)}]}"""))
                .build(),
        )
        return FakeRecentGroupsStore(
            RecentGroupsSnapshot(groups = listOf(group("g1", instance, Instant.parse("2025-01-01T00:00:00Z")))),
        )
    }

    /** A port nothing is listening on, a real network failure, the same shape TrpcClientTest
     *  pins for TrpcClientError.Network. */
    private fun deadInstance(): String {
        val deadServer = MockWebServer()
        deadServer.start()
        val url = deadServer.url("/").toString()
        deadServer.close()
        return url
    }

    // ---- rate limiting ----------------------------------------------------------------------

    /** A clock the test moves by hand, so none of this waits for real time to pass. */
    private class FakeClock(var millis: Long = 0) {
        operator fun invoke(): Long = millis
    }

    @Test
    fun `retry is rate-limited, load is not`() = runBlocking {
        // This screen has no pull gesture, so the button beside a failure is the only thing a
        // user can repeat, and it is the most expensive thing in the app when they do: one
        // request per stored group. `load` runs once per composition and is left alone.
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(groups = listOf(group("g1", instance, Instant.parse("2025-01-01T00:00:00Z")))),
        )
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) =
                MockResponse.Builder().code(200)
                    .body(okBody("""{"groups":[${summaryJson("g1", "Lisbon", 3)}]}"""))
                    .build()
        }
        val clock = FakeClock()
        val viewModel = GroupsListViewModel(
            recentGroupsStore = store,
            refreshLimiter = RefreshLimiter(5.seconds, clock::invoke),
        )

        viewModel.refresh()
        val afterLoad = server.requestCount

        viewModel.retryNow()
        val afterFirstRetry = server.requestCount
        assertTrue(afterFirstRetry > afterLoad, "the first retry should have fetched")

        clock.millis = 1_000
        viewModel.retryNow()
        assertEquals(afterFirstRetry, server.requestCount, "a retry inside the window fetches nothing")

        clock.millis = 10_000
        viewModel.retryNow()
        assertTrue(server.requestCount > afterFirstRetry, "a retry after the window should fetch")

        // `load` is the composition path and must never be dropped, however recently the button
        // was tapped.
        val beforeLoad = server.requestCount
        viewModel.refresh()
        assertTrue(server.requestCount > beforeLoad, "load must not be rate-limited")
    }

}
