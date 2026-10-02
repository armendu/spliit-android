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

private fun draft(locale: Locale = AMERICAN): GroupFormDraft =
    GroupFormDraft.creating(locale = locale)
        .copy(name = "Lisbon Trip")
        .withParticipantAdded("Ana")
        .withParticipantAdded("Bruno")
        .withParticipantAdded("Chloé")

class GroupFormDraftTest {
    @Test
    fun `a name is required`() {
        val problems = draft().copy(name = "   ").problems

        assertTrue(problems.contains(GroupFormDraft.Problem.NameRequired))
        assertEquals(GroupFormDraft.Field.NAME, problems.single().field)
    }

    @Test
    fun `a group name must be 2 to 50 characters once trimmed`() {
        assertEquals(listOf(GroupFormDraft.Problem.NameTooShort), draft().copy(name = "  A  ").problems)
        assertTrue(draft().copy(name = "Ab").isValid)
        assertTrue(draft().copy(name = "x".repeat(50)).isValid)
        assertEquals(listOf(GroupFormDraft.Problem.NameTooLong), draft().copy(name = "x".repeat(51)).problems)
    }

    @Test
    fun `a participant name must be 2 to 50 characters once trimmed`() {
        val short = draft().withParticipantAdded(" J ")
        val long = draft().withParticipantAdded("x".repeat(51))
        val longest = draft().withParticipantAdded("x".repeat(50))

        assertEquals(
            listOf(GroupFormDraft.Problem.ParticipantNameTooShort(short.participants.last().id)),
            short.problems,
        )
        assertEquals(
            listOf(GroupFormDraft.Problem.ParticipantNameTooLong(long.participants.last().id)),
            long.problems,
        )
        assertTrue(longest.isValid)
    }

    @Test
    fun `a custom currency symbol must be 1 to 5 characters once trimmed`() {
        val custom = draft().withCustomSymbol()

        assertEquals(listOf(GroupFormDraft.Problem.CurrencySymbolRequired), custom.copy(currency = "  ").problems)
        assertEquals(listOf(GroupFormDraft.Problem.CurrencySymbolTooLong), custom.copy(currency = "ABCDEF").problems)
        assertTrue(custom.copy(currency = "CHF").isValid)
        assertEquals(GroupFormDraft.Field.CURRENCY, GroupFormDraft.Problem.CurrencySymbolTooLong.field)
    }

    @Test
    fun `a picked currency whose symbol is too long for the server falls back to its code`() {
        val picked = draft().withCurrency(Currency("AED", "UAE Dirham", "ए.इ.दि", 2))

        assertEquals("AED", picked.currency)
        assertEquals("AED", picked.currencyCode)
        assertTrue(picked.isValid)
    }

    @Test
    fun `a complete draft is valid`() {
        assertTrue(draft().isValid, "Unexpected problems: ${draft().problems}")
    }

    @Test
    fun `an empty participant name is rejected`() {
        val withBlank = draft().withParticipantAdded("   ")
        val added = withBlank.participants.last()

        val problems = withBlank.problems
        assertTrue(problems.contains(GroupFormDraft.Problem.ParticipantNameRequired(added.id)))
        assertEquals(GroupFormDraft.Field.PARTICIPANTS, problems.single().field)
        assertFalse(withBlank.isValid)
    }

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

    // "İsmail"/"ismail" collide only under Turkish folding; an ASCII pair can't tell ROOT from the default.
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

    @Test
    fun `a newly added participant can always be removed`() {
        val withNewcomer = draft().withParticipantAdded("Dimitri")
        val newcomer = withNewcomer.participants.last()

        assertTrue(withNewcomer.canRemoveParticipant(newcomer.id))
        val afterRemoval = withNewcomer.withParticipantRemoved(newcomer.id)
        assertNotNull(afterRemoval)
        assertEquals(3, afterRemoval!!.participants.size)
    }

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
    fun `a fresh draft is counted in euros`() {
        val fresh = GroupFormDraft.creating(locale = AMERICAN)

        assertEquals("EUR", fresh.currencyCode)
        assertEquals("€", fresh.currency)
        assertFalse(fresh.usesCustomSymbol)
    }

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

    @Test
    fun `a loaded group lists its participants with a collator, not code-point order`() {
        val frenchDraft = GroupFormDraft.editing(
            name = "Trip",
            information = "",
            currency = "€",
            currencyCode = "EUR",
            participants = listOf(Participant("z", "Zoé"), Participant("e", "Émile"), Participant("a", "Amir")),
            locale = FRENCH,
        )

        assertEquals(listOf("Amir", "Émile", "Zoé"), frenchDraft.participants.map { it.name })
    }

    @Test
    fun `typing a name never moves a row`() {
        val draft = GroupFormDraft.creating(locale = AMERICAN)
            .withParticipantAdded("Zoe")
            .withParticipantAdded("")
        val order = draft.participants.map { it.id }

        val typed = draft.withParticipantRenamed(order[1], "Amir")

        assertEquals(order, typed.participants.map { it.id })
        assertEquals(listOf("Zoe", "Amir"), typed.participants.map { it.name })
    }
}
