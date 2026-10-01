package app.spliit.core

import java.util.Locale

// A saved split is client-side only; no procedure reads saveDefaultSplittingOptions. Rules:
//  1. BY_AMOUNT keeps only its mode.
//  2. A split naming someone who left is dropped whole, not trimmed.
//  3. An even split of the whole group is stored as membership only, so new members are included.

public data class DefaultSplit(
    public val splitMode: SplitMode,
    public val shares: Map<String, Long>? = null,
) {
    public fun appliesTo(participants: List<Participant>): Boolean {
        val shares = shares ?: return true
        val present = participants.map { it.id }.toSet()
        return shares.keys.all { it in present }
    }

    public fun orDefaultFor(participants: List<Participant>): DefaultSplit =
        if (appliesTo(participants)) this else DEFAULT

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
        public val DEFAULT: DefaultSplit = DefaultSplit(splitMode = SplitMode.EVENLY)

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
