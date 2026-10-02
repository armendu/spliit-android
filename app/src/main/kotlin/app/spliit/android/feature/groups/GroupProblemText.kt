package app.spliit.android.feature.groups

import app.spliit.core.GroupFormDraft
import app.spliit.core.GroupFormDraft.Companion.CURRENCY_SYMBOL_MAX_LENGTH
import app.spliit.core.GroupFormDraft.Companion.NAME_MAX_LENGTH

internal fun GroupFormDraft.Problem.message(): String = when (this) {
    GroupFormDraft.Problem.NameRequired -> "A group needs a name."
    GroupFormDraft.Problem.NameTooShort -> "Give the group a name of at least two letters."
    GroupFormDraft.Problem.NameTooLong -> "Keep the group name to $NAME_MAX_LENGTH characters."
    GroupFormDraft.Problem.CurrencySymbolRequired -> "Type the symbol amounts are shown with."
    GroupFormDraft.Problem.CurrencySymbolTooLong ->
        "Keep the symbol to $CURRENCY_SYMBOL_MAX_LENGTH characters."
    GroupFormDraft.Problem.NoParticipants -> "A group needs at least one participant."
    is GroupFormDraft.Problem.ParticipantNameRequired -> "This participant needs a name."
    is GroupFormDraft.Problem.ParticipantNameTooShort -> "Use at least two letters."
    is GroupFormDraft.Problem.ParticipantNameTooLong -> "Keep the name to $NAME_MAX_LENGTH characters."
    is GroupFormDraft.Problem.DuplicateParticipantName -> "Two participants can't share a name."
}
