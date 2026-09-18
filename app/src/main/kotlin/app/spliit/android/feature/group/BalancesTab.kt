package app.spliit.android.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.feature.groups.SkeletonBlock
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.EmptyState
import app.spliit.android.ui.design.Money
import app.spliit.android.ui.design.MoneySign
import app.spliit.android.ui.design.MoneySize
import app.spliit.android.ui.design.Monogram
import app.spliit.api.Participant
import app.spliit.api.Reimbursement
import app.spliit.core.LoadState
import app.spliit.core.MoneyFormatter
import kotlin.math.abs

/**
 * The balances tab: the active participant's own standing leads, then every participant's
 * balance, then the suggested payments that would settle the group.
 *
 * Both [GroupDetailUiState.group] (for currency and the participant roster) and
 * [GroupDetailUiState.balances] have to be in before any of this can be drawn — a participant
 * list without balances is a row of zeroes that reads as "everyone is settled up", which is
 * exactly the wrong thing to show while the real answer is still on the wire.
 */
@Composable
internal fun BalancesTab(
    state: GroupDetailUiState,
    formatter: MoneyFormatter,
    onRetry: () -> Unit,
    onIdentify: () -> Unit,
    onSettle: (Reimbursement) -> Unit,
) {
    val groupState = state.group
    val balancesState = state.balances

    when {
        groupState is LoadState.Loading || balancesState is LoadState.Loading -> BalancesSkeleton()

        groupState is LoadState.Failed || balancesState is LoadState.Failed -> {
            val message = (groupState as? LoadState.Failed)?.message
                ?: (balancesState as? LoadState.Failed)?.message
            CenteredScroll {
                EmptyState(
                    icon = "!",
                    title = "Couldn't load the balances",
                    description = message ?: "Check your connection and try again.",
                ) {
                    Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.GROUP_DETAIL_RETRY_BUTTON)) {
                        Text("Retry")
                    }
                }
            }
        }

        else -> {
            val group = (groupState as LoadState.Loaded).value
            val balances = (balancesState as LoadState.Loaded).value
            BalancesContent(
                state = state,
                participants = group.participants,
                balances = balances.balances,
                reimbursements = balances.reimbursements,
                formatter = formatter,
                onIdentify = onIdentify,
                onSettle = onSettle,
            )
        }
    }
}

@Composable
private fun BalancesContent(
    state: GroupDetailUiState,
    participants: List<Participant>,
    balances: Map<String, Long>,
    reimbursements: List<Reimbursement>,
    formatter: MoneyFormatter,
    onIdentify: () -> Unit,
    onSettle: (Reimbursement) -> Unit,
) {
    val you = participants.firstOrNull { it.id == state.activeParticipantId }
    val yourBalance = state.yourBalanceMinorUnits()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        // The FAB clearance, plus the navigation-bar inset the app now draws behind — see
        // ExpensesTab for the reasoning; the two lists have to agree or one of them hides a row.
        contentPadding = PaddingValues(
            bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item(key = "you") {
            YouSection(you = you, yourBalance = yourBalance, formatter = formatter, onIdentify = onIdentify)
            SectionDivider()
        }

        item(key = "balances_header") { SectionHeader("Balances") }
        items(participants, key = { "balance_${it.id}" }) { participant ->
            BalanceRow(
                participant = participant,
                balanceMinorUnits = balances[participant.id] ?: 0L,
                isYou = participant.id == state.activeParticipantId,
                formatter = formatter,
            )
        }
        item(key = "balances_footer") {
            Text(
                text = "Who is up and who is down, once every expense has been counted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            SectionDivider()
        }

        item(key = "reimbursements_header") { SectionHeader("Suggested payments") }
        if (reimbursements.isNotEmpty()) {
            item(key = "reimbursements_hint") {
                Text(
                    // Spliit has no "mark as paid" — settling up is recorded as a reimbursement
                    // expense, which is what tapping one of these rows opens prefilled.
                    text = "Tap one to record it as a payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (reimbursements.isEmpty()) {
            item(key = "settled") {
                Text(
                    text = "Everyone is settled up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .testTag(TestTags.GROUP_DETAIL_SETTLED_LABEL),
                )
            }
        } else {
            reimbursements.forEachIndexed { index, reimbursement ->
                item(key = "reimbursement_$index") {
                    ReimbursementRow(
                        index = index,
                        reimbursement = reimbursement,
                        from = participants.firstOrNull { it.id == reimbursement.from },
                        to = participants.firstOrNull { it.id == reimbursement.to },
                        formatter = formatter,
                        onClick = { onSettle(reimbursement) },
                    )
                }
            }
        }
        item(key = "bottom_spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun YouSection(you: Participant?, yourBalance: Long?, formatter: MoneyFormatter, onIdentify: () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        SectionHeader("You")

        if (you != null && yourBalance != null) {
            Text(
                text = direction(yourBalance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // Unsigned: the caption above already carries the direction — DESIGN.md §3.
            Money(
                value = formatter.format(abs(yourBalance)),
                size = MoneySize.HERO,
                sign = MoneySign.forBalance(yourBalance),
                testTag = TestTags.GROUP_DETAIL_YOU_AMOUNT,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onIdentify)
                .testTag(TestTags.GROUP_DETAIL_YOU_BUTTON)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (you != null) {
                Monogram(name = you.name, participantId = you.id, size = 28.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = you?.name ?: "Say who you are",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        if (you == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pick yourself once and this group is read from where you stand: your " +
                    "own balance first, and your name already filled in on a new expense.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BalanceRow(
    participant: Participant,
    balanceMinorUnits: Long,
    isYou: Boolean,
    formatter: MoneyFormatter,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Monogram(name = participant.name, participantId = participant.id, size = 32.dp)
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(participant.name, style = MaterialTheme.typography.bodyLarge)
            if (isYou) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "You",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Money(
            value = formatter.format(balanceMinorUnits),
            size = MoneySize.ROW,
            sign = MoneySign.forBalance(balanceMinorUnits),
            testTag = TestTags.balanceRowAmount(participant.id),
        )
    }
}

@Composable
private fun ReimbursementRow(
    index: Int,
    reimbursement: Reimbursement,
    from: Participant?,
    to: Participant?,
    formatter: MoneyFormatter,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(TestTags.reimbursementRow(index))
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Monogram(name = from?.name.orEmpty(), participantId = reimbursement.from, size = 24.dp)
        Text(
            text = "→",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Monogram(name = to?.name.orEmpty(), participantId = reimbursement.to, size = 24.dp)

        Text(
            text = "${from?.name ?: "—"} owes ${to?.name ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )

        // Signed is redundant here — a suggested payment never carries the negative case — but
        // it is still a fact with a direction, so this is not drawn with MoneySign.NONE the way
        // an ordinary expense amount is.
        Money(value = formatter.format(reimbursement.amount.toLong()), size = MoneySize.LEAD)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun direction(balanceMinorUnits: Long): String = when {
    balanceMinorUnits > 0 -> "You are owed"
    balanceMinorUnits < 0 -> "You owe"
    else -> "You're settled up"
}

@Composable
private fun CenteredScroll(content: @Composable () -> Unit) {
    Box(
        // Same FAB clearance as the expense list — see ExpensesTab.
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 88.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun BalancesSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SkeletonBlock(Modifier.width(80.dp).height(14.dp))
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(Modifier.width(160.dp).height(36.dp))
        Spacer(Modifier.height(24.dp))
        repeat(3) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(32.dp), shape = CircleShape)
                SkeletonBlock(Modifier.width(120.dp).height(14.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
