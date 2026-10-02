package app.spliit.core

import java.util.Locale
import java.util.UUID

// Names compare trimmed and folded with Locale.ROOT so devices agree ("İsmail"/"ismail" don't collide).
// A cleared currency code is sent as "", never null, matching the web app.
public data class ParticipantFormDraft(
    public val id: String = UUID.randomUUID().toString(),
    public val serverId: String? = null,
    public val name: String = "",
)

public data class GroupFormDraft(
    public val name: String = "",
    public val information: String = "",
    public val currency: String = "€",
    public val currencyCode: String? = "EUR",
    public val participants: List<ParticipantFormDraft> = emptyList(),
    public val participantsWithExpenses: Set<String> = emptySet(),
    public val locale: Locale = Locale.getDefault(),
) {
    public val usesCustomSymbol: Boolean get() = currencyCode.isNullOrBlank()

    // Some locales' symbols exceed the server's limit (e.g. brx-IN); the code always fits.
    public fun withCurrency(currency: Currency): GroupFormDraft = copy(
        currencyCode = currency.code,
        currency = currency.symbol.takeIf { it.length <= CURRENCY_SYMBOL_MAX_LENGTH } ?: currency.code,
    )

    public fun withCustomSymbol(): GroupFormDraft = copy(currencyCode = null)

    public fun withParticipantAdded(name: String = ""): GroupFormDraft =
        copy(participants = participants + ParticipantFormDraft(name = name))

    public fun withParticipantRenamed(id: String, name: String): GroupFormDraft =
        copy(participants = participants.map { if (it.id == id) it.copy(name = name) else it })

    public fun canRemoveParticipant(id: String): Boolean {
        val serverId = participants.firstOrNull { it.id == id }?.serverId ?: return true
        return serverId !in participantsWithExpenses
    }

    public fun withParticipantRemoved(id: String): GroupFormDraft? {
        if (!canRemoveParticipant(id)) return null
        return copy(participants = participants.filterNot { it.id == id })
    }

    public enum class Field {
        NAME,
        CURRENCY,
        PARTICIPANTS,
    }

    public sealed interface Problem {
        public val field: Field

        public val participantId: String? get() = null

        public data object NameRequired : Problem {
            override val field: Field get() = Field.NAME
        }

        public data object NameTooShort : Problem {
            override val field: Field get() = Field.NAME
        }

        public data object NameTooLong : Problem {
            override val field: Field get() = Field.NAME
        }

        public data object CurrencySymbolRequired : Problem {
            override val field: Field get() = Field.CURRENCY
        }

        public data object CurrencySymbolTooLong : Problem {
            override val field: Field get() = Field.CURRENCY
        }

        public data object NoParticipants : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }

        public data class ParticipantNameRequired(override val participantId: String) : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }

        public data class ParticipantNameTooShort(override val participantId: String) : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }

        public data class ParticipantNameTooLong(override val participantId: String) : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }

        public data class DuplicateParticipantName(override val participantId: String) : Problem {
            override val field: Field get() = Field.PARTICIPANTS
        }
    }

    public val problems: List<Problem>
        get() {
            val problems = mutableListOf<Problem>()

            when (lengthIssue(name)) {
                LengthIssue.EMPTY -> problems += Problem.NameRequired
                LengthIssue.TOO_SHORT -> problems += Problem.NameTooShort
                LengthIssue.TOO_LONG -> problems += Problem.NameTooLong
                null -> {}
            }

            val symbol = currency.trim()
            if (symbol.isEmpty()) {
                problems += Problem.CurrencySymbolRequired
            } else if (symbol.length > CURRENCY_SYMBOL_MAX_LENGTH) {
                problems += Problem.CurrencySymbolTooLong
            }

            if (participants.isEmpty()) problems += Problem.NoParticipants

            for (participant in participants) {
                when (lengthIssue(participant.name)) {
                    LengthIssue.EMPTY -> problems += Problem.ParticipantNameRequired(participant.id)
                    LengthIssue.TOO_SHORT -> problems += Problem.ParticipantNameTooShort(participant.id)
                    LengthIssue.TOO_LONG -> problems += Problem.ParticipantNameTooLong(participant.id)
                    null -> {}
                }
            }

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

    public fun problems(field: Field): List<Problem> = problems.filter { it.field == field }

    public fun problems(participantId: String): List<Problem> =
        problems.filter { it.participantId == participantId }

    public fun submission(): GroupSubmission? {
        if (!isValid) return null
        return GroupSubmission(
            name = name.trim(),
            information = information.trim(),
            currency = currency.trim(),
            currencyCode = currencyCode?.trim().orEmpty(),
            participants = participants.map {
                GroupSubmission.Participant(id = it.serverId, name = it.name.trim())
            },
        )
    }

    public companion object {
        public fun creating(locale: Locale = Locale.getDefault()): GroupFormDraft =
            GroupFormDraft(locale = locale)

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
            // Sorted once here, never while editing, or rows move under the field being typed in.
            participants = participants
                .sortedWith(compareBy(localizedOrder(locale)) { it.name })
                .map { ParticipantFormDraft(serverId = it.id, name = it.name) },
            participantsWithExpenses = participantsWithExpenses,
            locale = locale,
        )

        // The server's groupFormSchema limits, measured on trimmed text.
        public const val NAME_MIN_LENGTH: Int = 2
        public const val NAME_MAX_LENGTH: Int = 50
        public const val CURRENCY_SYMBOL_MAX_LENGTH: Int = 5

        private fun foldedName(name: String): String = name.trim().lowercase(Locale.ROOT)

        private enum class LengthIssue { EMPTY, TOO_SHORT, TOO_LONG }

        private fun lengthIssue(name: String): LengthIssue? {
            val length = name.trim().length
            return when {
                length == 0 -> LengthIssue.EMPTY
                length < NAME_MIN_LENGTH -> LengthIssue.TOO_SHORT
                length > NAME_MAX_LENGTH -> LengthIssue.TOO_LONG
                else -> null
            }
        }
    }
}

public data class GroupSubmission(
    public val name: String,
    public val information: String,
    public val currency: String,
    public val currencyCode: String,
    public val participants: List<Participant>,
) {
    public data class Participant(
        public val id: String?,
        public val name: String,
    )
}
