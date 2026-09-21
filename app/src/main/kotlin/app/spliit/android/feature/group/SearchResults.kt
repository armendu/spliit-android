package app.spliit.android.feature.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.DateHeader
import app.spliit.android.ui.design.EmptyState
import app.spliit.core.LoadState
import app.spliit.core.MoneyFormatter

/**
 * What the search field is currently answering, in place of the expense list.
 *
 * The results are their own list rather than a filter over the loaded pages: the server does the
 * matching — `groups.expenses.list` takes a case-insensitive `filter` on the title — so a search
 * covers the whole group, not only what has been paged in. They sit in the same date buckets the
 * expense list uses, so a result appears under the heading it would have had in the list it came
 * from.
 *
 * Three empty-ish states, and they are three different sentences: nothing typed yet, nothing
 * matched, and the request failed. Collapsing the first two is how "no expenses" ends up on
 * screen before anybody has asked anything.
 */
@Composable
internal fun SearchResults(
    search: SearchUiState,
    formatter: MoneyFormatter,
    onExpenseClick: (String) -> Unit,
) {
    val results = search.results
    when {
        results == null -> Centered {
            EmptyState(
                icon = "?",
                title = "Search this group",
                description = "Type part of an expense title. Matching is case-insensitive and " +
                    "covers every expense in the group, not just the ones already loaded.",
            )
        }

        results is LoadState.Loading -> Centered {
            Text(
                text = "Searching…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.SEARCH_LOADING),
            )
        }

        results is LoadState.Failed -> Centered {
            EmptyState(
                icon = "!",
                title = "Couldn't search",
                description = results.message ?: "Check your connection and try again.",
            )
        }

        results is LoadState.Loaded && results.value.isEmpty() -> Centered {
            EmptyState(
                icon = "∅",
                title = "No matches",
                description = "Nothing in this group has \"${search.query.trim()}\" in its title.",
            )
        }

        results is LoadState.Loaded -> {
            val sections = remember(results.value) { bucketExpenses(results.value) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(TestTags.SEARCH_RESULTS),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 88.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                for (section in sections) {
                    item(key = "search_header_${section.bucket}") {
                        DateHeader(
                            bucket = section.bucket,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(section.expenses, key = { "search_${it.id}" }) { expense ->
                        ExpenseRow(
                            expense = expense,
                            formatter = formatter,
                            onClick = { onExpenseClick(expense.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
