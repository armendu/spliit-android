package app.spliit.core

import java.util.Locale

// How a group's expenses are usually divided, remembered so the next one starts there.
//
// A saved split is the client's: `saveDefaultSplittingOptions` is sent, validated, and read by no
// procedure. The web app keeps its own in localStorage; ours lives on the recent-group row.
//
// Three rules, each wrong the obvious way:
//
//  1. BY_AMOUNT keeps only its mode. Its shares are one receipt's amounts and will not add up to
//     the next expense, so seeding with them seeds numbers that look deliberate and are not.
//  2. A split naming a participant who has left is dropped whole, not trimmed. 70/30 minus the
//     30 is a split nobody chose; the group default is visible and correctable.
//  3. An even split of the whole group is stored as membership alone, so a flatmate who moves in
//     next month is included rather than silently left out. The other modes keep their names:
//     nobody joins a 50/30/20 without breaking it.

/**
 * How a group's expenses are usually divided, so the next one can start there instead of blank.
 *
 * @property shares Everyone remembered and what each was given, on the protocol's ×100 scale.
 *   Null under [SplitMode.BY_AMOUNT] and for an even split of the whole group, see rules 1 and 3.
 */
public data class DefaultSplit(
    public val splitMode: SplitMode,
    public val shares: Map<String, Long>? = null,
) {

    /**
     * Whether this still describes a split of [participants]. False only when [shares] names
     * somebody who has left; a new participant was simply never in it. Null shares always apply.
     */
    public fun appliesTo(participants: List<Participant>): Boolean {
        val shares = shares ?: return true
        val present = participants.map { it.id }.toSet()
        return shares.keys.all { it in present }
    }

    /**
     * This split, or the group's plain default once it no longer [appliesTo] [participants].
     * Trimming the stale names instead would produce a split nobody chose (rule 2).
     */
    public fun orDefaultFor(participants: List<Participant>): DefaultSplit =
        if (appliesTo(participants)) this else DEFAULT

    /**
     * Seeds a new expense's participants from this split. Anyone not remembered is covered,
     * which is rule 3 paying off. Under BY_AMOUNT [shares] is always null (rule 1), so rows come
     * back at the "1" a blank expense starts with.
     *
     * @param locale Spells a fractional share with the right separator, so [ExpenseFormDraft]
     *   reads back what this wrote.
     */
    public fun apply(
        participants: List<Participant>,
        locale: Locale = Locale.getDefault(),
    ): List<ParticipantShareDraft> =
        participants.map { participant ->
            val share = shares?.get(participant.id)
            ParticipantShareDraft(
                id = participant.id,
                name = participant.name,
                isIncluded = shares == null || share != null,
                valueText = share?.let { ExpenseFormDraft.hundredthsText(it, locale) } ?: "1",
            )
        }

    public companion object {
        /** What a group with nothing remembered, or nothing left that still applies, starts from. */
        public val DEFAULT: DefaultSplit = DefaultSplit(splitMode = SplitMode.EVENLY)

        /**
         * What to remember from an expense just submitted. Built from [submission], not the
         * draft: these numbers have passed validation, and a draft being typed into has not.
         */
        public fun remembering(
            submission: ExpenseSubmission,
            participants: List<Participant>,
        ): DefaultSplit {
            val paidFor = submission.paidFor.map { it.participant }.toSet()
            val isWholeGroupEvenly = submission.splitMode == SplitMode.EVENLY &&
                paidFor == participants.map { it.id }.toSet()

            val shares = if (submission.splitMode == SplitMode.BY_AMOUNT || isWholeGroupEvenly) {
                null
            } else {
                submission.paidFor.associate { it.participant to it.shares }
            }
            return DefaultSplit(splitMode = submission.splitMode, shares = shares)
        }

    }
}
