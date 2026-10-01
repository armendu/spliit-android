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

    public fun withCurrency(currency: Currency): GroupFormDraft =
        copy(currencyCode = currency.code, currency = currency.symbol)

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
        PARTICIPANTS,
    }

    public sealed interface Problem {
        public val field: Field

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

        private fun foldedName(name: String): String = name.trim().lowercase(Locale.ROOT)
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
