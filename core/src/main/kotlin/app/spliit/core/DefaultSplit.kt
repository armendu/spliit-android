package app.spliit.core

import java.util.Locale

// How a group's expenses are usually divided, remembered so the next one starts there.
//
// A couple splitting 70/30 was retyping that on every expense — and, worse, an expense where
// somebody forgot to change the split off `EVENLY` was silently wrong rather than refused. This
// is the web app's "save as default splitting options", kept in a form this app can act on.
//
// **A saved split is the client's, not the server's.** `saveDefaultSplittingOptions` travels on
// every `groups.expenses.*` write, is validated by the zod schema, and is read by no procedure at
// all — the web app keeps the split it wants to remember in `localStorage`, under
// `<groupId>-defaultSplittingOptions`, and reads it back itself. Ours will live on the recent-
// group row (Part 8). A default set in a browser and one set on this phone are two different
// facts about the same group, and no amount of server work reconciles them — see CLAUDE.md.
//
// Three rules travel with it, each earning its place by being wrong the obvious way:
//
//  1. [SplitMode.BY_AMOUNT] keeps only its mode. Its shares are one receipt's own amounts; they
//     would not add up to the next expense's total, so seeding the next expense with them would
//     seed it with numbers that are wrong and look deliberate.
//
//  2. A split naming a participant the group no longer has is dropped whole, not trimmed. 70/30
//     with the 30 removed is a percentage split nobody chose and this app cannot save; falling
//     back to the group's plain default is a state the user can see and correct, where a trimmed
//     split would look chosen.
//
//  3. An even split of the *whole* group is stored as membership alone, with no shares at all.
//     Under [SplitMode.EVENLY] a share says nothing beyond who was in it, and "everybody" is the
//     one answer that should still be right after somebody joins — stored as a list of today's
//     names, a flatmate who moves in next month would be quietly left out of every expense from
//     then on. The other modes cannot do this safely: nobody joins a 50/30/20 split without
//     breaking it, so [SplitMode.BY_SHARES] and [SplitMode.BY_PERCENTAGE] always keep their
//     names, and a newcomer sits out until an expense actually includes them.

/**
 * How a group's expenses are usually divided, remembered on the client so the next expense
 * started in this group can begin there instead of blank.
 *
 * @property shares Everyone remembered, and what each was given, on the ×100 scale the protocol
 *   already stores share counts and percentages on — see [ExpenseSubmission.PaidFor.shares].
 *   Null under [SplitMode.BY_AMOUNT], and null for an even split of the whole group — see the
 *   rules at the top of this file for why each is nothing rather than something.
 */
public data class DefaultSplit(
    public val splitMode: SplitMode,
    public val shares: Map<String, Long>? = null,
) {

    /**
     * Whether this still describes a split of [participants].
     *
     * Only false when [shares] names somebody who has left — a fresh participant is not a
     * problem, they were simply never in it. Null [shares] (rule 1 or rule 3) always applies:
     * there is nothing in it that could go stale.
     */
    public fun appliesTo(participants: List<Participant>): Boolean {
        val shares = shares ?: return true
        val present = participants.map { it.id }.toSet()
        return shares.keys.all { it in present }
    }

    /**
     * This split, or the group's plain default when it no longer [appliesTo] [participants].
     *
     * The alternative — trimming the stale names out of [shares] — produces a split nobody
     * chose (rule 2). Degrading instead of throwing is what keeps this a place a new expense can
     * always start from, including the case every one of its participants has since left.
     */
    public fun orDefaultFor(participants: List<Participant>): DefaultSplit =
        if (appliesTo(participants)) this else DEFAULT

    /**
     * Seeds a new expense's participants from this split.
     *
     * With nothing remembered for somebody — including everybody, when [shares] is null — the
     * split covers them: that is rule 3 paying off, and it is also how a blank expense has always
     * started. Values come back at the same precision [ExpenseFormDraft] reads them at, so a
     * draft built from this list validates and totals exactly as the expense that was saved did.
     *
     * There is no group currency to seed [SplitMode.BY_AMOUNT] with here: [shares] is always null
     * under it (rule 1), so its participants come back at the "1" every row starts with, waiting
     * for the amounts this expense actually has.
     *
     * @param locale Used to spell a fractional share — a comma or a dot — so the text this seeds
     *   matches what [ExpenseFormDraft] will read it back with; whole shares need none.
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
                valueText = share?.let { hundredthsText(it, locale) } ?: "1",
            )
        }

    public companion object {
        /** What a group with nothing remembered — or nothing left that still applies — starts from. */
        public val DEFAULT: DefaultSplit = DefaultSplit(splitMode = SplitMode.EVENLY)

        /**
         * What to remember from an expense that has just been submitted.
         *
         * Built from [submission] rather than from the draft that produced it, because these
         * numbers have already passed validation: every share is a positive number, parsed, and —
         * under the modes that require it — adding up. A draft still being typed into offers no
         * such guarantee, and remembering an unfinished split would be remembering a mistake.
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

        /**
         * A ×100 share or percentage as a field shows it — whole numbers without a trailing
         * `.00`. The same rule [ExpenseFormDraft] reads existing expenses' shares with, kept here
         * rather than shared because that copy is `private`: this module's own `internal`
         * [ExpenseFormDraft.minorUnitsText] is what the two actually share.
         */
        private fun hundredthsText(shares: Long, locale: Locale): String =
            if (shares % 100L == 0L) {
                (shares / 100L).toString()
            } else {
                ExpenseFormDraft.minorUnitsText(shares, null, locale)
            }
    }
}
