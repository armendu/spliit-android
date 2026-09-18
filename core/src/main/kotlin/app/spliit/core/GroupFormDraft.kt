package app.spliit.core

import java.util.Locale
import java.util.UUID

// The group form's state, plus the validation the server would apply. Sibling to
// ExpenseFormDraft, and deliberately shaped like it — see the derived-validation-and-submission
// shape below.
//
// ── Uniqueness is case- and whitespace-insensitive ────────────────────────────────────────────
//
// Two participants named "Ana" and "ana " are the same mistake, not two different people, and a
// check that only catches byte-for-byte duplicates misses the one a real user actually makes —
// typing a name a second time with different capitalisation or a stray space. So a name is
// compared trimmed and case-folded, not as typed.
//
// The fold uses [Locale.ROOT], and the reason is the same one the currency code below is
// documented against: a participant list is shared state, edited from whichever device happens
// to be running the check right now, so two devices comparing the same two names must reach the
// same answer regardless of whose phone it is. Locale-invariant folding is what guarantees that.
//
// It is not chosen because it is simply "correct" — neither folding choice is, here. Verified on
// the JDK: under ROOT, "İsmail" (dotted capital I) and "ismail" do *not* collide, missing the
// Turkish reading of the pair; but "Ismail" — an ASCII "I", typed on any non-Turkish keyboard —
// and "ismail" *do*. Under a Turkish locale it is the other way around: the first pair collides
// and the second stops colliding, which is a new false negative for everyone not typing on a
// Turkish layout. ROOT is the choice that answers the same way on every device, and it is also
// the choice that gets the overwhelmingly common case — ASCII names, stray case, stray
// whitespace — right unconditionally. `GroupFormDraftTest` pins this on the İ/ı pair under a
// Turkish [Locale], because that pair is what actually distinguishes the two folding choices; an
// all-ASCII pair like "Ana"/"ana " collides identically under either one and proves nothing.
//
// ── A cleared currency code is sent as "" ─────────────────────────────────────────────────────
//
// `GroupFormValues.currencyCode` in :api is a plain, non-null `String` — not `String?` — which is
// exactly what makes this rule impossible to get wrong from here: there is no null to reach for.
// The reason it is typed that way is worth keeping, because it is not obvious from the type
// alone: the web app writes `""` for "no code" rather than omitting the key or sending a JSON
// null, and the web app, iOS and this app share one database, so the same action — dropping a
// group's ISO code — has to leave the same state whichever client performed it. See CLAUDE.md.

/** A group member as the group form edits one. */
public data class ParticipantFormDraft(
    /**
     * Local identity for this row, stable across edits so a screen can track it — including a
     * row for a participant that does not exist on the server yet, which has no [serverId] to
     * be tracked by.
     */
    public val id: String = UUID.randomUUID().toString(),
    /** The server-side participant ID, or null for a row this draft is creating. */
    public val serverId: String? = null,
    public val name: String = "",
)

/**
 * The group form's state, plus the validation the server would apply.
 *
 * Mirrors `groupFormSchema` in the web app for the rules that schema actually states; the two
 * beyond it — a participant with expenses cannot be removed, and how a cleared currency code is
 * spelled — come from server behaviour no schema states but every client has to honour. See the
 * notes at the top of this file. Immutable, like [ExpenseFormDraft]: a screen holds one and
 * replaces it wholesale.
 */
public data class GroupFormDraft(
    public val name: String = "",
    /** The group's description. `""` clears it — see [submission]. */
    public val information: String = "",
    /** A free-text symbol such as "$" or "CHF". The server does not interpret it. */
    public val currency: String = "$",
    /**
     * ISO-4217, or null for a group with only a free-text symbol — predating currency codes, or
     * a symbol the user chose to type themselves. Use [withCurrency] and [withCustomSymbol]
     * rather than setting this directly: picking a currency sets [currency] to match, and a code
     * that disagreed with the symbol beside every amount would be worse than no code at all.
     */
    public val currencyCode: String? = null,
    public val participants: List<ParticipantFormDraft> = emptyList(),
    /**
     * Server IDs of participants who appear on at least one expense — `participantsWithExpenses`
     * in the server's `groups.getDetails` response, and the only source of truth this draft has
     * for who [withParticipantRemoved] is not free to take out: removing one would orphan the
     * expenses that name them, silently, on whatever screen loads next.
     */
    public val participantsWithExpenses: Set<String> = emptySet(),
    public val locale: Locale = Locale.getDefault(),
) {

    // ---- currency -----------------------------------------------------------------------

    /**
     * True when the group carries only a symbol — predating ISO codes, or a symbol the user
     * chose to type themselves. The symbol is theirs to edit in that state and nobody else's.
     */
    public val usesCustomSymbol: Boolean get() = currencyCode.isNullOrBlank()

    /**
     * Picking a currency sets the symbol too: the code is what the amounts are actually in, and
     * a screen offering a symbol the code disagrees with would be showing two different answers
     * to the same question.
     */
    public fun withCurrency(currency: Currency): GroupFormDraft =
        copy(currencyCode = currency.code, currency = currency.symbol)

    /** Drops the ISO code and keeps the symbol, which is now the user's to type freely. */
    public fun withCustomSymbol(): GroupFormDraft = copy(currencyCode = null)

    // ---- participants ---------------------------------------------------------------------

    public fun withParticipantAdded(name: String = ""): GroupFormDraft =
        copy(participants = participants + ParticipantFormDraft(name = name))

    public fun withParticipantRenamed(id: String, name: String): GroupFormDraft =
        copy(participants = participants.map { if (it.id == id) it.copy(name = name) else it })

    /**
     * Whether the row for [id] could be removed right now — a screen uses this to grey out its
     * own remove control before the user ever taps it, rather than after.
     */
    public fun canRemoveParticipant(id: String): Boolean {
        val serverId = participants.firstOrNull { it.id == id }?.serverId ?: return true
        return serverId !in participantsWithExpenses
    }

    /**
     * Removes the row for [id], or **null** when that participant is on at least one expense.
     *
     * Null rather than the unchanged draft, deliberately: a same-type no-op is a return value a
     * call site can drop without the compiler ever objecting, and that is exactly what a stale
     * remove button, a race with another device's edit landing between render and tap, or a test
     * that forgot [canRemoveParticipant] would do — silently. A nullable return forces a branch.
     * [canRemoveParticipant] is still there for greying out a control before the user ever taps
     * it; this is what a screen falls back on when that check was skipped or went stale, and the
     * null is what lets it say *why* nothing happened rather than say nothing at all.
     */
    public fun withParticipantRemoved(id: String): GroupFormDraft? {
        if (!canRemoveParticipant(id)) return null
        return copy(participants = participants.filterNot { it.id == id })
    }

    /**
     * [participants], ordered the way the reader's language orders names rather than by Unicode
     * code point — see [localizedOrder]. What a screen actually lists.
     */
    public val sortedParticipants: List<ParticipantFormDraft>
        get() = participants.sortedWith(compareBy(localizedOrder(locale)) { it.name })

    // ---- validation -----------------------------------------------------------------------

    /** Which box on the form a [Problem] belongs against. */
    public enum class Field {
        NAME,
        PARTICIPANTS,
    }

    /**
     * One thing the server would refuse, and which field it belongs to — see
     * [ExpenseFormDraft.Problem], whose shape this matches.
     */
    public sealed interface Problem {
        public val field: Field

        /** Set for the problems that are one participant row's rather than the whole list's. */
        public val participantId: String? get() = null

        public data object NameRequired : Problem {
            override val field: Field get() = Field.NAME
        }

        public data object NoParticipants : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }

        public data class ParticipantNameRequired(override val participantId: String) : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }

        /**
         * Reported against every row that collides, not just the later one — a screen with two
         * offending fields should mark both rather than leave the reader hunting for the other.
         */
        public data class DuplicateParticipantName(override val participantId: String) : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }
    }

    public val problems: List<Problem>
        get() {
            val problems = mutableListOf<Problem>()

            if (name.trim().isEmpty()) problems += Problem.NameRequired
            if (participants.isEmpty()) problems += Problem.NoParticipants

            for (participant in participants) {
                if (participant.name.trim().isEmpty()) {
                    problems += Problem.ParticipantNameRequired(participant.id)
                }
            }

            // Grouped by the folded name so every colliding row is reported, not just the second
            // one to appear — see the note at the top of this file for why folding happens at
            // all, and why it uses [Locale.ROOT] rather than [locale].
            participants
                .filter { it.name.isNotBlank() }
                .groupBy { foldedName(it.name) }
                .values
                .filter { it.size > 1 }
                .forEach { duplicates ->
                    duplicates.forEach { problems += Problem.DuplicateParticipantName(it.id) }
                }

            return problems
        }

    public val isValid: Boolean get() = problems.isEmpty()

    /** The problems a given field should draw. */
    public fun problems(field: Field): List<Problem> = problems.filter { it.field == field }

    /** The problems belonging to one participant's row. */
    public fun problems(participantId: String): List<Problem> =
        problems.filter { it.participantId == participantId }

    // ---- submission -----------------------------------------------------------------------

    /** Everything needed to build a `GroupFormValues`, or null if the draft is not valid. */
    public fun submission(): GroupSubmission? {
        if (!isValid) return null
        return GroupSubmission(
            name = name.trim(),
            information = information.trim(),
            currency = currency.trim(),
            // "" for a dropped code, never a Kotlin null — see the note at the top of this file.
            currencyCode = currencyCode?.trim().orEmpty(),
            participants = participants.map {
                GroupSubmission.Participant(id = it.serverId, name = it.name.trim())
            },
        )
    }

    public companion object {
        /** A blank draft for a brand-new group. */
        public fun creating(locale: Locale = Locale.getDefault()): GroupFormDraft =
            GroupFormDraft(locale = locale)

        /**
         * A group loaded for editing.
         *
         * @param participantsWithExpenses IDs from the server's `participantsWithExpenses` — see
         *   the field of the same purpose on this class.
         */
        public fun editing(
            name: String,
            information: String,
            currency: String,
            currencyCode: String?,
            participants: List<Participant>,
            participantsWithExpenses: Set<String> = emptySet(),
            locale: Locale = Locale.getDefault(),
        ): GroupFormDraft = GroupFormDraft(
            name = name,
            information = information,
            currency = currency,
            currencyCode = currencyCode,
            participants = participants.map { ParticipantFormDraft(serverId = it.id, name = it.name) },
            participantsWithExpenses = participantsWithExpenses,
            locale = locale,
        )

        /** A name as it is compared for uniqueness — see the note at the top of this file. */
        private fun foldedName(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}

/**
 * Everything a `GroupFormValues` needs, in `:core`'s own types.
 *
 * Unlike [ExpenseSubmission], there is no unit boundary to cross here: every field is already
 * the wire's own shape, string for string, so `:app` copies this straight onto `GroupFormValues`
 * field for field rather than through a separate `Wire` type first.
 */
public data class GroupSubmission(
    public val name: String,
    public val information: String,
    public val currency: String,
    /** `""` for a group with no code — never a Kotlin null. See [GroupFormDraft]. */
    public val currencyCode: String,
    public val participants: List<Participant>,
) {
    /** A participant as the form sends one. */
    public data class Participant(
        /** Null creates a participant; non-null renames the one with this server ID. */
        public val id: String?,
        public val name: String,
    )
}
