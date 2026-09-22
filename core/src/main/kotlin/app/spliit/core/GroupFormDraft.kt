package app.spliit.core

import java.util.Locale
import java.util.UUID

// The group form's state, plus the validation the server would apply.
//
// Participant names are compared trimmed and case-folded, so "Ana" and "ana " are one mistake
// rather than two people. The fold uses Locale.ROOT so two devices editing one list agree.
// Verified: "Ismail"/"ismail" collide under ROOT, "İsmail"/"ismail" do not, and Turkish swaps
// which pair does. GroupFormDraftTest pins that pair, since an ASCII pair proves nothing.
//
// A cleared currency code is sent as `""`, never null: the web app writes `""` and all three
// clients share one database, so dropping a code must leave the same state whoever did it.
/** A group member as the group form edits one. */
public data class ParticipantFormDraft(
    /**
     * Local identity for this row, stable across edits so a screen can track it, including a
     * row for a participant that does not exist on the server yet, which has no [serverId] to
     * be tracked by.
     */
    public val id: String = UUID.randomUUID().toString(),
    /** The server-side participant ID, or null for a row this draft is creating. */
    public val serverId: String? = null,
    public val name: String = "",
)

/**
 * The group form's state, plus the validation the server would apply. Mirrors the web app's
 * `groupFormSchema`, plus two rules no schema states: a participant with expenses cannot be
 * removed, and a cleared currency code is `""`. Immutable.
 */
public data class GroupFormDraft(
    public val name: String = "",
    /** The group's description. `""` clears it, see [submission]. */
    public val information: String = "",
    /** A free-text symbol such as "$" or "CHF". The server does not interpret it. */
    public val currency: String = "$",
    /** ISO-4217, or null for a free-text symbol. Set via [withCurrency] / [withCustomSymbol],
     *  which keep [currency] in step: a code disagreeing with the symbol is worse than none. */
    public val currencyCode: String? = null,
    public val participants: List<ParticipantFormDraft> = emptyList(),
    /** Participants who appear on at least one expense, from `groups.getDetails`. Removing one
     *  would silently orphan the expenses naming them. */
    public val participantsWithExpenses: Set<String> = emptySet(),
    public val locale: Locale = Locale.getDefault(),
) {

    // ---- currency -----------------------------------------------------------------------

    /**
     * True when the group carries only a symbol, predating ISO codes, or a symbol the user
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
     * Whether the row for [id] could be removed right now, a screen uses this to grey out its
     * own remove control before the user ever taps it, rather than after.
     */
    public fun canRemoveParticipant(id: String): Boolean {
        val serverId = participants.firstOrNull { it.id == id }?.serverId ?: return true
        return serverId !in participantsWithExpenses
    }

    /**
     * Removes the row for [id], or null when that participant is on an expense. Null rather than
     * an unchanged draft, which a caller could drop silently. [canRemoveParticipant] greys the
     * control out first; this catches the case where that check went stale.
     */
    public fun withParticipantRemoved(id: String): GroupFormDraft? {
        if (!canRemoveParticipant(id)) return null
        return copy(participants = participants.filterNot { it.id == id })
    }

    /**
     * [participants], ordered the way the reader's language orders names rather than by Unicode
     * code point, see [localizedOrder]. What a screen actually lists.
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
     * One thing the server would refuse, and which field it belongs to, see
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
         * Reported against every row that collides, not just the later one, a screen with two
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
            // one to appear, see the note at the top of this file for why folding happens at
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
            // "" for a dropped code, never a Kotlin null, see the note at the top of this file.
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
         * @param participantsWithExpenses IDs from the server's `participantsWithExpenses`, see
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

        /** A name as it is compared for uniqueness, see the note at the top of this file. */
        private fun foldedName(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}

/** Everything a `GroupFormValues` needs, in `:core`'s types. No unit boundary to cross, unlike
 *  [ExpenseSubmission], so `:app` copies it straight across. */
public data class GroupSubmission(
    public val name: String,
    public val information: String,
    public val currency: String,
    /** `""` for a group with no code, never a Kotlin null. See [GroupFormDraft]. */
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
