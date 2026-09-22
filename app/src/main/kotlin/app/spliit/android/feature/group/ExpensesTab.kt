package app.spliit.android.feature.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spliit.android.feature.groups.SkeletonBlock
import app.spliit.android.R
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.fabAndNavigationBarPadding
import app.spliit.android.ui.design.CategoryIcon
import app.spliit.android.ui.design.DateHeader
import app.spliit.android.ui.design.EmptyState
import app.spliit.android.ui.design.Money
import app.spliit.android.ui.design.MoneySign
import app.spliit.android.ui.design.MoneySize
import app.spliit.api.ExpenseListItem
import app.spliit.core.LoadState
import app.spliit.core.MoneyFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.spliit.android.ui.design.CenteredScroll

/**
 * The expenses tab: everything `groups.expenses.list` has answered so far, under date-bucket
 * headers, with a sentinel row at the bottom that pages in the next batch.
 *
 * Driven off two independent [LoadState]s, the group (for its currency) and the expenses
 * themselves, because they answer at different times: see [GroupDetailViewModel.refresh].
 */
@Composable
internal fun ExpensesTab(
    groupState: LoadState<GroupInfo>,
    expensesState: LoadState<ExpensesPage>,
    isLoadingMore: Boolean,
    formatter: MoneyFormatter,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onExpenseClick: (String) -> Unit,
) {
    when {
        // The group not having arrived yet costs the expense list its currency, so it is treated
        // as still loading here too rather than drawing amounts in the wrong scale for a moment.
        groupState is LoadState.Loading || expensesState is LoadState.Loading ->
            ExpensesSkeleton()

        groupState is LoadState.Failed || expensesState is LoadState.Failed -> {
            val message = (groupState as? LoadState.Failed)?.message
                ?: (expensesState as? LoadState.Failed)?.message
            CenteredScroll {
                EmptyState(
                    icon = "!",
                    title = "Couldn't load the expenses",
                    description = message ?: "Check your connection and try again.",
                ) {
                    Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.GROUP_DETAIL_RETRY_BUTTON)) {
                        Text("Retry")
                    }
                }
            }
        }

        else -> {
            val page = (expensesState as LoadState.Loaded).value
            if (page.expenses.isEmpty()) {
                CenteredScroll {
                    EmptyState(
                        iconRes = R.drawable.ic_tab_expenses,
                        title = "No expenses yet",
                        description = "Add the first expense and Spliit will work out who owes what.",
                    )
                }
            } else {
                val sections = remember(page.expenses) { bucketExpenses(page.expenses) }
                ExpensesList(
                    sections = sections,
                    hasMore = page.hasMore,
                    isLoadingMore = isLoadingMore,
                    formatter = formatter,
                    onLoadMore = onLoadMore,
                    onExpenseClick = onExpenseClick,
                )
            }
        }
    }
}

@Composable
private fun ExpensesList(
    sections: List<ExpenseSection>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    formatter: MoneyFormatter,
    onLoadMore: () -> Unit,
    onExpenseClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // The extended FAB floats over the bottom of this list, so the last row needs room to
        // scroll clear of it, 16dp margin + 56dp FAB + 16dp breathing space. Without it the
        // final expense of a group is permanently half-covered, which is exactly the row someone
        // scrolled down to read.
        //
        // Plus the navigation-bar inset, *on top of* that clearance rather than instead of it:
        // the app draws behind the bar (MainActivity), so the list scrolls under it and the last
        // row would otherwise end up beneath the gesture handle.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = fabAndNavigationBarPadding(),
        ),
    ) {
        for (section in sections) {
            item(key = "header_${section.bucket}") {
                DateHeader(bucket = section.bucket, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
            items(section.expenses, key = { it.id }) { expense ->
                ExpenseRow(
                    expense = expense,
                    formatter = formatter,
                    onClick = { onExpenseClick(expense.id) },
                )
            }
        }
        if (hasMore) {
            item(key = "load_more") {
                // Entering composition is the trigger, the same shape as the iOS list's own
                // `.task` on its trailing row, ported to Compose's LaunchedEffect.
                LaunchedEffect(Unit) { onLoadMore() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .testTag(TestTags.GROUP_DETAIL_LOAD_MORE),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMore) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
internal fun ExpenseRow(
    expense: ExpenseListItem,
    formatter: MoneyFormatter,
    onClick: () -> Unit,
) {
    Row(
        // The whole row is the target, not the title within it: a 56dp row that only responds
        // where the text happens to end is a row most taps miss. Note the tags beneath this one
        // are now inside a merging clickable, TestTags' own note 2, so Part 14's finders need
        // `useUnmergedTree = true` to reach them.
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(TestTags.expenseRow(expense.id))
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryIcon(grouping = expense.category?.grouping, size = 40.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = expense.title,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (expense.isReimbursement) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TestTags.expenseRowTitle(expense.id)),
            )
            Spacer(Modifier.height(2.dp))
            // Two facts, two lines. Run together as "Paid by Ana for Ana, Bruno and Chloé" the
            // *second* one truncates first at row width, and that is the half somebody is
            // checking. The payer takes the smaller label style and the split takes body, so the
            // extra line reads as a subordinate detail rather than adding a third equal-weight
            // line to every row in the list.
            //
            // Merged, because two Texts in a column are two fragments to a screen reader. The
            // pair announces the one sentence it used to be.
            Column(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibleSplitDescription(expense)
                    },
            ) {
                Text(
                    text = "Paid by ${expense.paidBy.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TestTags.expenseRowPaidBy(expense.id)),
                )
                val forWhom = paidForDescription(expense)
                if (forWhom != null) {
                    Text(
                        text = "For $forWhom",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TestTags.expenseRowPaidFor(expense.id)),
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            // `primary`, not the surrounding text colour, DESIGN.md §4. An expense amount still
            // carries no *direction*, and never takes the ledger axis; the brand green is a
            // different token doing a different job, which is "this is the number you came to
            // read". See MoneySign.EXPENSE for what that trade costs.
            Money(
                value = formatter.format(expense.amount.toLong()),
                size = MoneySize.ROW,
                sign = MoneySign.EXPENSE,
                isReimbursement = expense.isReimbursement,
                testTag = TestTags.expenseRowAmount(expense.id),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = captionText(expense),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Bruno and Chidi", who an expense was paid *for*, or null when the server named nobody.
 *
 * Just the list. The "Paid by …" half is its own line now (see [ExpenseRow]), and the full
 * breakdown is one tap away on the expense form.
 */
internal fun paidForDescription(expense: ExpenseListItem): String? {
    val names = expense.paidFor.map { it.participant.name }
    return when (names.size) {
        0 -> null
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/** The two lines as the one sentence they used to be, for a screen reader, which would
 *  otherwise hear "Paid by Ana" and "For Bruno and Chidi" as unrelated fragments. */
internal fun accessibleSplitDescription(expense: ExpenseListItem): String {
    val forWhom = paidForDescription(expense)
    return if (forWhom == null) {
        "Paid by ${expense.paidBy.name}"
    } else {
        "Paid by ${expense.paidBy.name} for $forWhom"
    }
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun captionText(expense: ExpenseListItem): String {
    val date = expense.expenseDate.atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
    // The web app's paperclip, without an icon font to draw it with, a count says the same
    // thing a glyph would, and needs no tint of its own to render correctly.
    return if (expense.documentCount > 0) "$date · ${expense.documentCount} attached" else date
}


@Composable
private fun ExpensesSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TestTags.GROUP_DETAIL_EXPENSES_SKELETON),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(40.dp))
                Column {
                    SkeletonBlock(Modifier.width(160.dp).height(16.dp))
                    Spacer(Modifier.height(6.dp))
                    SkeletonBlock(Modifier.width(100.dp).height(12.dp))
                }
            }
        }
    }
}
