package app.spliit.android.feature.groups

import app.spliit.core.GroupFormDraft
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GroupFormViewModelTest {

    private val server = MockWebServer()

    @BeforeEach
    fun startServer() = server.start()

    @AfterEach
    fun stopServer() = server.close()

    // ---- validation renders :core's own problems -------------------------------------------

    @Test
    fun `the group form renders core's per-field problems rather than its own`() {
        val store = FakeRecentGroupsStore()
        val viewModel = GroupFormViewModel(
            mode = GroupFormMode.CREATE,
            groupId = null,
            instanceBaseUrl = server.url("/").toString(),
            recentGroupsStore = store,
        )

        // A blank name and no participants are :core's own rules (GroupFormDraft.problems) — this
        // asserts the ViewModel surfaces exactly them, not a parallel validation of its own.
        val draft = viewModel.state.value.draft
        assertTrue(draft.problems(GroupFormDraft.Field.NAME).contains(GroupFormDraft.Problem.NameRequired))
        assertTrue(
            draft.problems(GroupFormDraft.Field.PARTICIPANTS).contains(GroupFormDraft.Problem.NoParticipants),
        )
        assertFalse(draft.isValid)
    }

    @Test
    fun `submit refuses an invalid draft without making a network call`() = runBlocking {
        val store = FakeRecentGroupsStore()
        val viewModel = GroupFormViewModel(
            mode = GroupFormMode.CREATE,
            groupId = null,
            instanceBaseUrl = server.url("/").toString(),
            recentGroupsStore = store,
        )

        val saved = viewModel.submit()

        assertFalse(saved)
        assertEquals(0, server.requestCount)
        assertTrue(viewModel.state.value.hasAttemptedSave, "a failed attempt should reveal problems")
    }

    // ---- create ------------------------------------------------------------------------------

    @Test
    fun `a valid create submits the group and remembers it locally`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okBody("""{"groupId":"new-group"}""")).build())
        val store = FakeRecentGroupsStore()
        val instance = server.url("/").toString()
        val viewModel = GroupFormViewModel(
            mode = GroupFormMode.CREATE,
            groupId = null,
            instanceBaseUrl = instance,
            recentGroupsStore = store,
        )
        viewModel.setName("Weekend in Lisbon")
        viewModel.addParticipant()
        val participantId = viewModel.state.value.draft.participants.single().id
        viewModel.renameParticipant(participantId, "Ana")

        val saved = viewModel.submit()

        assertTrue(saved)
        assertEquals("new-group", viewModel.state.value.savedGroupId)
        val stored = store.load().groups.single()
        assertEquals("new-group", stored.groupId)
        assertEquals(instance, stored.instanceBaseUrl)
    }

    // ---- refused participant removal ---------------------------------------------------------

    @Test
    fun `a refused participant removal surfaces a reason instead of doing nothing`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                okBody(
                    """{"group":{"id":"g1","name":"Weekend in Lisbon","currency":"€",""" +
                        """"createdAt":"2025-01-01T00:00:00.000Z","participants":[{"id":"p1","name":"Ana"}]},""" +
                        """"participantsWithExpenses":["p1"]}""",
                ),
            ).build(),
        )
        val store = FakeRecentGroupsStore()
        val viewModel = GroupFormViewModel(
            mode = GroupFormMode.EDIT,
            groupId = "g1",
            instanceBaseUrl = server.url("/").toString(),
            recentGroupsStore = store,
        )
        viewModel.refreshForEdit()
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(setOf("p1"), viewModel.state.value.draft.participantsWithExpenses)
        // The draft's own row id is a fresh local UUID, not the server's "p1" — canRemoveParticipant
        // and withParticipantRemoved both key off it, per GroupFormDraft.
        val localId = viewModel.state.value.draft.participants.single().id

        viewModel.removeParticipant(localId)

        // Not a silent no-op: the participant is still there, and why is explained.
        assertEquals(1, viewModel.state.value.draft.participants.size)
        val message = viewModel.state.value.blockedParticipantMessage
        assertNotNull(message)
        assertTrue(message!!.contains("Ana"), "expected the message to name who, got: $message")
    }

    @Test
    fun `removing a participant with no expenses is not refused`() {
        val store = FakeRecentGroupsStore()
        val viewModel = GroupFormViewModel(
            mode = GroupFormMode.CREATE,
            groupId = null,
            instanceBaseUrl = server.url("/").toString(),
            recentGroupsStore = store,
        )
        viewModel.addParticipant()
        val id = viewModel.state.value.draft.participants.single().id

        viewModel.removeParticipant(id)

        assertTrue(viewModel.state.value.draft.participants.isEmpty())
        assertEquals(null, viewModel.state.value.blockedParticipantMessage)
    }
}
