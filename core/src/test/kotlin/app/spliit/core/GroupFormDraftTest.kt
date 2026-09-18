package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

private val AMERICAN = Locale.of("en", "US")
private val FRENCH = Locale.of("fr", "FR")
private val TURKISH = Locale.of("tr", "TR")

/** A draft with a name and three participants — the state a screen reaches once typed into. */
private fun draft(locale: Locale = AMERICAN): GroupFormDraft =
    GroupFormDraft.creating(locale = locale)
        .copy(name = "Lisbon Trip")
        .withParticipantAdded("Ana")
        .withParticipantAdded("Bruno")
        .withParticipantAdded("Chloé")

class GroupFormDraftTest {

    // ---- name ------------------------------------------------------------------------

    @Test
    fun `a name is required`() {
        val problems = draft().copy(name = "   ").problems

        assertTrue(problems.contains(GroupFormDraft.Problem.NameRequired))
        assertEquals(GroupFormDraft.Field.NAME, problems.single().field)
    }

    @Test
    fun `a complete draft is valid`() {
        assertTrue(draft().isValid, "Unexpected problems: ${draft().problems}")
    }

    // ---- participant names -------------------------------------------------------------

    @Test
    fun `an empty participant name is rejected`() {
        val withBlank = draft().withParticipantAdded("   ")
        val added = withBlank.participants.last()

        val problems = withBlank.problems
        assertTrue(problems.contains(GroupFormDraft.Problem.ParticipantNameRequired(added.id)))
        assertEquals(GroupFormDraft.Field.PARTICIPANTS, problems.single().field)
        assertFalse(withBlank.isValid)
    }

    /**
     * Pinning the case/whitespace decision: uniqueness is checked trimmed and case-folded, so
     * "Ana" and "ana " collide even though neither is a byte-for-byte repeat of the other — the
     * real mistake a person actually makes when they add someone twice.
     */
    @Test
    fun `duplicate participant names are rejected, ignoring case and surrounding whitespace`() {
        val withDuplicate = GroupFormDraft.creating(locale = AMERICAN)
            .copy(name = "Trip")
            .withParticipantAdded("Ana")
            .withParticipantAdded("ana ")

        val (first, second) = withDuplicate.participants

        val problems = withDuplicate.problems
        assertTrue(problems.contains(GroupFormDraft.Problem.DuplicateParticipantName(first.id)))
        assertTrue(problems.contains(GroupFormDraft.Problem.DuplicateParticipantName(second.id)))
        assertFalse(withDuplicate.isValid)
    }

    /**
     * The pair that actually distinguishes [Locale.ROOT] folding from the reader's own locale.
     * An ASCII pair like "Ana"/"ana " collides identically under either choice and proves
     * nothing about which one is in use; "İsmail" (dotted capital I) and "ismail" collide only
     * under Turkish folding, and *not* under ROOT — verified against the JDK directly, not
     * assumed. Running this under a Turkish [Locale] and still seeing no collision is what pins
     * the fold as locale-invariant rather than reading the device's own language, which is the
     * actual reason ROOT was chosen — see the note at the top of [GroupFormDraft].
     */
    @Test
    fun `a Turkish dotted-I name does not collide with its ASCII form, even under a Turkish locale`() {
        val withTurkishPair = GroupFormDraft.creating(locale = TURKISH)
            .copy(name = "Trip")
            .withParticipantAdded("İsmail")
            .withParticipantAdded("ismail")

        val problems = withTurkishPair.problems
        assertTrue(
            problems.none { it is GroupFormDraft.Problem.DuplicateParticipantName },
            "Expected no collision under ROOT folding, found $problems",
        )
        assertTrue(withTurkishPair.isValid, "Unexpected problems: $problems")
    }

    /** [problems] recomputes from current state, so a rename into a collision is caught too. */
    @Test
    fun `renaming a participant into a collision with an existing name is rejected`() {
        val before = draft()
        val bruno = before.participants[1]

        val renamed = before.withParticipantRenamed(bruno.id, " ANA")

        val ana = renamed.participants.first { it.name == "Ana" }
        assertTrue(renamed.problems.contains(GroupFormDraft.Problem.DuplicateParticipantName(ana.id)))
        assertTrue(renamed.problems.contains(GroupFormDraft.Problem.DuplicateParticipantName(bruno.id)))
        assertFalse(renamed.isValid)
    }

    @Test
    fun `distinct participant names are accepted`() {
        assertTrue(draft().isValid, "Unexpected problems: ${draft().problems}")
    }

    @Test
    fun `a validation failure names the field and the participant it belongs to`() {
        val withBlank = draft().withParticipantAdded("")
        val added = withBlank.participants.last()

        val problems = withBlank.problems(added.id)
        assertEquals(listOf(GroupFormDraft.Problem.ParticipantNameRequired(added.id)), problems)
        assertTrue(withBlank.problems(GroupFormDraft.Field.NAME).isEmpty())
    }

    // ---- removing a participant with expenses ------------------------------------------

    @Test
    fun `a participant with an expense cannot be removed`() {
        val existing = GroupFormDraft.editing(
            name = "Lisbon Trip",
            information = "",
            currency = "€",
            currencyCode = "EUR",
            participants = listOf(Participant("ana", "Ana"), Participant("bruno", "Bruno")),
            participantsWithExpenses = setOf("ana"),
            locale = AMERICAN,
        )
        val anaId = existing.participants.first { it.serverId == "ana" }.id

        assertFalse(existing.canRemoveParticipant(anaId))

        // Null, not the unchanged draft: a same-type no-op is a return value a call site could
        // drop without noticing, which is exactly the bug this signature exists to rule out.
        assertNull(existing.withParticipantRemoved(anaId))
    }

    @Test
    fun `a participant with no expenses can be removed`() {
        val existing = GroupFormDraft.editing(
            name = "Lisbon Trip",
            information = "",
            currency = "€",
            currencyCode = "EUR",
            participants = listOf(Participant("ana", "Ana"), Participant("bruno", "Bruno")),
            participantsWithExpenses = setOf("ana"),
            locale = AMERICAN,
        )
        val brunoId = existing.participants.first { it.serverId == "bruno" }.id

        assertTrue(existing.canRemoveParticipant(brunoId))

        val afterRemoval = existing.withParticipantRemoved(brunoId)
        assertNotNull(afterRemoval)
        assertEquals(1, afterRemoval!!.participants.size)
        assertEquals("ana", afterRemoval.participants.single().serverId)
    }

    /** A participant this edit is creating has no server ID at all, and so no expenses either. */
    @Test
    fun `a newly added participant can always be removed`() {
        val withNewcomer = draft().withParticipantAdded("Dimitri")
        val newcomer = withNewcomer.participants.last()

        assertTrue(withNewcomer.canRemoveParticipant(newcomer.id))
        val afterRemoval = withNewcomer.withParticipantRemoved(newcomer.id)
        assertNotNull(afterRemoval)
        assertEquals(3, afterRemoval!!.participants.size)
    }

    /**
     * Removing the last participant is allowed by [withParticipantRemoved] itself — it is a
     * refusal about expense history, not about the group staying non-empty — but the resulting
     * draft is correctly invalid via [Problem.NoParticipants].
     */
    @Test
    fun `removing a group's only participant is allowed, but leaves the draft invalid`() {
        val single = GroupFormDraft.editing(
            name = "Solo Trip",
            information = "",
            currency = "$",
            currencyCode = "USD",
            participants = listOf(Participant("ana", "Ana")),
            participantsWithExpenses = emptySet(),
            locale = AMERICAN,
        )
        val anaId = single.participants.single().id

        assertTrue(single.canRemoveParticipant(anaId))
        val afterRemoval = single.withParticipantRemoved(anaId)

        assertNotNull(afterRemoval)
        assertTrue(afterRemoval!!.participants.isEmpty())
        assertTrue(afterRemoval.problems.contains(GroupFormDraft.Problem.NoParticipants))
        assertFalse(afterRemoval.isValid)
    }

    // ---- currency ------------------------------------------------------------------------

    /**
     * `GroupFormValues.currencyCode` is a non-null `String` in :api — there is no null to send
     * even by mistake — but the value has to actually be `""`, not, say, left as whatever the
     * symbol-only state's Kotlin `null` would stringify to.
     */
    @Test
    fun `a cleared currency code is sent as empty text, not null or omitted`() {
        val submission = draft()
            .withCurrency(Currency(code = "EUR", name = "Euro", symbol = "€", minorUnitDigits = 2))
            .withCustomSymbol()
            .submission()

        assertNotNull(submission)
        assertEquals("", submission!!.currencyCode)
    }

    @Test
    fun `picking a currency sets both the code and the symbol`() {
        val withEuro = draft().withCurrency(Currency("EUR", "Euro", "€", 2))

        assertEquals("EUR", withEuro.currencyCode)
        assertEquals("€", withEuro.currency)
        assertFalse(withEuro.usesCustomSymbol)
    }

    @Test
    fun `a fresh draft has no currency code and is treated as a custom symbol`() {
        assertTrue(GroupFormDraft.creating().usesCustomSymbol)
        assertNull(GroupFormDraft.creating().currencyCode)
    }

    // ---- editing round-trips everything, ids included ------------------------------------

    @Test
    fun `editing round-trips every field, ids included`() {
        val original = listOf(Participant("ana", "Ana"), Participant("bruno", "Bruno"))

        val editing = GroupFormDraft.editing(
            name = "Lisbon Trip",
            information = "Long weekend",
            currency = "€",
            currencyCode = "EUR",
            participants = original,
            participantsWithExpenses = setOf("ana"),
            locale = AMERICAN,
        )

        assertEquals("Lisbon Trip", editing.name)
        assertEquals("Long weekend", editing.information)
        assertEquals("€", editing.currency)
        assertEquals("EUR", editing.currencyCode)
        assertEquals(listOf("ana", "bruno"), editing.participants.map { it.serverId })
        assertEquals(listOf("Ana", "Bruno"), editing.participants.map { it.name })

        val submission = editing.submission()
        assertNotNull(submission)
        assertEquals("Lisbon Trip", submission!!.name)
        assertEquals("Long weekend", submission.information)
        assertEquals("€", submission.currency)
        assertEquals("EUR", submission.currencyCode)
        assertEquals(
            listOf("ana" to "Ana", "bruno" to "Bruno"),
            submission.participants.map { it.id to it.name },
        )
    }

    /** A newly typed participant has no server ID — this is how the server is told to create one. */
    @Test
    fun `a new participant is submitted with a null id`() {
        val submission = draft().submission()

        assertNotNull(submission)
        assertTrue(submission!!.participants.all { it.id == null })
    }

    @Test
    fun `an invalid draft produces no submission`() {
        assertNull(draft().copy(name = "").submission())
        assertNotNull(draft().submission())
    }

    @Test
    fun `renaming a participant keeps its identity`() {
        val before = draft()
        val original = before.participants.first()

        val renamed = before.withParticipantRenamed(original.id, "Anaïs")

        assertEquals("Anaïs", renamed.participants.first().name)
        assertEquals(original.id, renamed.participants.first().id)
        assertEquals(original.serverId, renamed.participants.first().serverId)
    }

    // ---- sorting ---------------------------------------------------------------------------

    /**
     * A French reader's collation puts an accented name in its alphabetic place rather than
     * after every plain-ASCII one — the difference `String.compareTo`, which compares Unicode
     * code points, cannot see.
     */
    @Test
    fun `participant lists sort with a collator, not code-point order`() {
        val frenchDraft = GroupFormDraft.creating(locale = FRENCH)
            .copy(name = "Trip")
            .withParticipantAdded("Zoé")
            .withParticipantAdded("Émile")
            .withParticipantAdded("Amir")

        val collatedOrder = frenchDraft.sortedParticipants.map { it.name }

        // What a French reader expects: Amir, Émile, Zoé — an accented initial sorted where it
        // sounds, not stranded after every plain-ASCII name the way code-point order puts it.
        assertEquals(listOf("Amir", "Émile", "Zoé"), collatedOrder)
    }
}
