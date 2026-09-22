package app.spliit.android.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.design.SkeletonBlock
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.CategoryIcon
import app.spliit.android.ui.design.EmptyState
import app.spliit.android.ui.design.Money
import app.spliit.android.ui.design.MoneySize
import app.spliit.api.Participant
import app.spliit.api.SpliitEndpoints
import app.spliit.core.LoadState
import app.spliit.core.MoneyFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import app.spliit.android.ui.design.CenteredScroll
import app.spliit.android.ui.design.LoadFailure
import app.spliit.android.ui.design.Footnote
import app.spliit.android.ui.design.SectionDivider
import app.spliit.android.ui.design.SectionHeader

/**
 * What the group has spent, and how much of it is yours. The group's own total leads, then
 * "You", then where the money went: here the personal figure answers the second half of a
 * question the group has already answered.
 *
 * **Reimbursements are excluded from every figure**, said once rather than per number. **Every
 * bar is measured against the group's total**, so their lengths compare straight down the page.
 */
@Composable
internal fun TotalsTab(
    state: GroupDetailUiState,
    formatter: MoneyFormatter,
    onRetry: () -> Unit,
    onRetryStats: () -> Unit,
    onIdentify: () -> Unit,
) {
    val groupState = state.group

    when {
        // Not a failure and not a retry: an instance that has never heard of either procedure
        // will not have heard of them a second time either. There is no button.
        state.statsUnavailable -> CenteredScroll {
            EmptyState(
                icon = "%",
                title = "No totals on this server",
                description = "This Spliit instance doesn't offer them. Everything else in the " +
                    "group works as usual.",
            )
        }

        // The group counts as loading here too: its currency is what these are drawn in, and a
        // number in no currency is not a number worth showing.
        groupState is LoadState.Loading || state.stats is LoadState.Loading -> TotalsSkeleton()

        groupState is LoadState.Failed || state.stats is LoadState.Failed -> {
            val groupFailed = groupState is LoadState.Failed
            val message = (groupState as? LoadState.Failed)?.message
                ?: (state.stats as? LoadState.Failed)?.message
            CenteredScroll {
                LoadFailure(
                    // The group can arrive and its totals still fail, so name whichever is missing.
                    title = if (groupFailed) "Couldn't load this group" else "Couldn't load the totals",
                    message = message,
                    retryTestTag = TestTags.GROUP_DETAIL_RETRY_BUTTON,
                    onRetry = if (groupFailed) onRetry else onRetryStats,
                )
            }
        }

        else -> {
            val group = (groupState as LoadState.Loaded).value
            TotalsContent(
                stats = (state.stats as LoadState.Loaded).value,
                you = group.participants.firstOrNull { it.id == state.activeParticipantId },
                formatter = formatter,
                onIdentify = onIdentify,
            )
        }
    }
}

@Composable
private fun TotalsContent(
    stats: StatsInfo,
    you: Participant?,
    formatter: MoneyFormatter,
    onIdentify: () -> Unit,
) {
    GroupTabList {
        item(key = "group_header") {
            Spacer(Modifier.height(16.dp))
            SectionHeader("The group")
        }
        item(key = "group_total") {
            Figure(
                // A group that has taken in more than it spent is not "spending", the caption
                // carries the direction so the amount below it can stay unsigned.
                label = if (stats.totalGroupSpendings < 0) "Total group earnings" else "Total group spending",
                minorUnits = abs(stats.totalGroupSpendings),
                formatter = formatter,
                size = MoneySize.HERO,
                amountTestTag = TestTags.TOTALS_GROUP_AMOUNT,
            )
        }
        stats.summary?.let { summary ->
            item(key = "group_summary") { SummaryLines(summary, formatter) }
        }
        item(key = "group_footer") {
            Footnote("Settling up is not spending, so reimbursements are left out of every figure here.")
            SectionDivider()
        }

        item(key = "you_header") { SectionHeader("You") }
        if (you != null && stats.yourSpendings != null) {
            item(key = "you_spending") {
                Figure(
                    label = if (stats.yourSpendings < 0) "Your earnings" else "Your spending",
                    minorUnits = abs(stats.yourSpendings),
                    formatter = formatter,
                    size = MoneySize.LEAD,
                    amountTestTag = TestTags.TOTALS_YOUR_SPENDING_AMOUNT,
                    fractionOf = stats.totalGroupSpendings,
                    fractionValue = stats.yourSpendings,
                )
            }
        }
        if (you != null && stats.yourShareMinorUnits != null) {
            item(key = "you_share") {
                Figure(
                    label = "Your share",
                    minorUnits = abs(stats.yourShareMinorUnits),
                    formatter = formatter,
                    size = MoneySize.LEAD,
                    amountTestTag = TestTags.TOTALS_YOUR_SHARE_AMOUNT,
                    fractionOf = stats.totalGroupSpendings,
                    fractionValue = stats.yourShareMinorUnits,
                )
            }
        }
        item(key = "you_button") {
            IdentityRow(
                you = you,
                testTag = TestTags.TOTALS_YOU_BUTTON,
                onIdentify = onIdentify,
                modifier = Modifier.padding(top = 8.dp),
            )
            Footnote(
                if (you == null) {
                    "Pick yourself and this tab also says what you have paid, and what your " +
                        "share of the group came to."
                } else {
                    "What you paid, against what was spent on you. The difference is your balance."
                },
            )
            SectionDivider()
        }

        // A group with no expenses has no breakdown to draw, and an instance answering the
        // removed procedure sends none, neither is a failure, and neither gets a heading.
        if (stats.categories.isNotEmpty()) {
            item(key = "categories_header") { SectionHeader("By category") }
            items(
                count = stats.categories.size,
                key = { index -> "category_${stats.categories[index].categoryId}" },
            ) { index ->
                CategoryRow(
                    category = stats.categories[index],
                    groupTotal = stats.totalGroupSpendings,
                    formatter = formatter,
                )
            }
            item(key = "categories_footer") {
                Footnote(
                    "Every bar on this screen is a slice of the same total, so the lengths " +
                        "compare straight down the page.",
                )
            }
        }
        item(key = "bottom_spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * A caption, the amount under it, and how big a slice of the group's total it is.
 *
 * Unsigned: the caption already says which way it goes, and "Total group earnings −€40.00" says
 * it twice while contradicting itself. A total has no direction, so no ledger colour either.
 */
@Composable
private fun Figure(
    label: String,
    minorUnits: Long,
    formatter: MoneyFormatter,
    size: MoneySize,
    amountTestTag: String,
    fractionOf: Long? = null,
    fractionValue: Long? = null,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Money(value = formatter.format(minorUnits), size = size, testTag = amountTestTag)

        val slice = if (fractionOf != null && fractionValue != null) {
            groupSlice(fractionValue, fractionOf)
        } else {
            null
        }
        if (slice != null) {
            Spacer(Modifier.height(8.dp))
            ShareBar(slice)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${percentText(slice)} of the group",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the new overview payload knows that the sum alone does not. Absent on an instance still
 *  answering the removed `groups.stats.get`, in which case nothing is drawn here. */
@Composable
private fun SummaryLines(summary: SpliitEndpoints.StatsSummary, formatter: MoneyFormatter) {
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        summary.expenseCount?.let { count ->
            val average = summary.averageExpense
            SummaryLine(
                label = if (count == 1) "1 expense" else "$count expenses",
                // Already divided by the server. Dividing anything by 100 here is exactly the
                // bug CLAUDE.md names; this figure is minor units like every other.
                value = average?.let { "average ${formatter.format(it.toLong())}" },
            )
        }
        summary.largestExpense?.let { largest ->
            val amount = largest.amount ?: return@let
            SummaryLine(label = "Largest", value = "${largest.title.orEmpty()} · ${formatter.format(amount.toLong())}")
        }
        val range = dateRangeText(summary.firstDate, summary.lastDate)
        if (range != null) SummaryLine(label = "From", value = range)
    }
}

@Composable
private fun SummaryLine(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}


/**
 * One category, its spend, and how much of the group that is. Laid out like a balance row,
 * because it is the same shape of statement and the tabs sit next to each other. The percentage
 * is not written out, a dozen down a list is noise, but a screen reader still gets it.
 */
@Composable
private fun CategoryRow(
    category: SpliitEndpoints.CategoryTotal,
    groupTotal: Long,
    formatter: MoneyFormatter,
) {
    val slice = groupSlice(category.total.toLong(), groupTotal)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryIcon(grouping = category.grouping, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = category.name ?: category.grouping ?: "Uncategorized",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.totalsCategoryName(category.categoryId))
                        .semantics {
                            if (slice != null) contentDescription = "${percentText(slice)} of the group"
                        },
                )
                Spacer(Modifier.width(8.dp))
                // Signed, unlike the figures above: nothing here states a direction, so the
                // minus has to. A total is not a direction, so it takes no colour either.
                Money(
                    value = formatter.format(category.total.toLong()),
                    size = MoneySize.ROW,
                    testTag = TestTags.totalsCategoryAmount(category.categoryId),
                )
            }
            if (slice != null) {
                Spacer(Modifier.height(6.dp))
                ShareBar(slice)
            }
        }
    }
}

/**
 * A slice of the group's spending, drawn as a length. A [Layout] rather than
 * `fillMaxWidth(fraction)` so a slice of zero still draws a sliver of track, and so the fill can
 * never round wider than the track it sits in.
 */
@Composable
private fun ShareBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // The percentage beside every one of these already says what the length says.
            .clearAndSetSemantics {},
    ) {
        Layout(
            content = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val width = (constraints.maxWidth * fraction).toInt().coerceIn(
                if (fraction > 0f) 2 else 0,
                constraints.maxWidth,
            )
            val placeable = measurables.first().measure(
                constraints.copy(minWidth = width, maxWidth = width),
            )
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
        }
    }
}

/**
 * How much of the group's spending a figure is, or null when there is no answer: a group that
 * has spent nothing has no slices. Clamped, because neither end is impossible, a group netting
 * out below what one person paid would put a bar past its own track.
 */
private fun groupSlice(value: Long, groupTotal: Long): Float? {
    if (groupTotal <= 0L) return null
    return (value.toDouble() / groupTotal.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

private fun percentText(fraction: Float): String = "${Math.round(fraction * 100)}%"

private val summaryDateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * "11 Aug 2025 – 14 Sept 2026", from the summary's two ISO date strings. Parsed rather than
 * printed, since a date a person reads belongs in their locale's order; anything that does not
 * parse is dropped, because the range is a nicety and a wrong date is worse than none.
 */
private fun dateRangeText(firstDate: String?, lastDate: String?): String? {
    val first = firstDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val last = lastDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val start = first.format(summaryDateFormatter)
    val end = last.format(summaryDateFormatter)
    return if (start == end) start else "$start – $end"
}


@Composable
private fun TotalsSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TestTags.TOTALS_SKELETON),
    ) {
        SkeletonBlock(Modifier.width(90.dp).height(14.dp))
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(Modifier.width(180.dp).height(36.dp))
        Spacer(Modifier.height(28.dp))
        repeat(4) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(32.dp))
                Column {
                    SkeletonBlock(Modifier.width(140.dp).height(14.dp))
                    Spacer(Modifier.height(6.dp))
                    SkeletonBlock(Modifier.width(200.dp).height(6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
