package app.spliit.android.feature.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.R
import app.spliit.android.ui.design.SkeletonBlock
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.DateHeader
import app.spliit.android.ui.design.EmptyState
import app.spliit.api.Activity
import app.spliit.api.ActivityType
import app.spliit.api.Participant
import app.spliit.core.DateBucket
import app.spliit.core.LoadState
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.spliit.android.ui.design.SheetShape
import app.spliit.android.ui.design.LoadFailure


/**
 * Everything that has happened in this group, and who did it. The only view that reads *history*
 * rather than state, so the only one showing lines about things that no longer exist.
 *
 * **A row can be opened when the server sent an `expense` beside it, not when it has an
 * `expenseId`**: the ID survives the deletion, the object does not.
 *
 * A change made by someone who never said who they were reads "Someone", accurately: every
 * mutating procedure takes an optional `participantId` and none requires one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityLogSheet(
    state: GroupDetailUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // A log is a list to read, not a form to fill in, so it opens at full height rather than at
    // a partially-expanded anchor there would be nothing useful to see at.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val participants = (state.group as? LoadState.Loaded)?.value?.participants.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        // Handed to the content instead, so the last row pads clear of the gesture bar rather
        // than the sheet consuming the inset and leaving it nothing to apply.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag(TestTags.ACTIVITY_LOG_SHEET),
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding()) {
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            when (val activities = state.activities) {
                is LoadState.Loading -> ActivitySkeleton(Modifier.weight(1f))

                is LoadState.Failed -> Box(
                    modifier = Modifier.weight(1f).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadFailure(
                        title = "Couldn't load the activity",
                        message = activities.message,
                        retryTestTag = TestTags.ACTIVITY_LOG_RETRY_BUTTON,
                        onRetry = onRetry,
                    )
                }

                is LoadState.Loaded -> {
                    // The *describable* rows, not the raw list: a log made entirely of entries
                    // this version has no sentence for has nothing to draw, and an empty state
                    // reads better than a blank one.
                    val sections = remember(activities.value.activities) {
                        bucketActivities(activities.value.activities)
                    }
                    if (sections.isEmpty()) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = "~",
                                title = "No activity yet",
                                description = "Adding, editing or deleting an expense is " +
                                    "recorded here, and so is a change to the group itself.",
                            )
                        }
                    } else {
                        ActivityList(
                            sections = sections,
                            participants = participants,
                            hasMore = activities.value.hasMore,
                            isLoadingMore = state.isLoadingMoreActivities,
                            onLoadMore = onLoadMore,
                            onOpenExpense = onOpenExpense,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One bucket's worth of activity, newest bucket first, the same [DateBucket] scheme the
 *  expense list uses, which `:core` wrote for both. */
internal data class ActivitySection(val bucket: DateBucket, val activities: List<Activity>)

/**
 * Buckets the log, dropping anything this version cannot describe: there is no honest sentence
 * for it, and a missing line still reads correctly where "something happened" does not.
 */
internal fun bucketActivities(
    activities: List<Activity>,
    clock: Clock = Clock.systemDefaultZone(),
): List<ActivitySection> {
    val byBucket = LinkedHashMap<DateBucket, MutableList<Activity>>()
    for (activity in activities) {
        if (!activity.activityType.isRecognised) continue
        val bucket = DateBucket.of(activity.time, clock)
        byBucket.getOrPut(bucket) { mutableListOf() }.add(activity)
    }
    return byBucket.entries.sortedBy { it.key.ordinal }.map { ActivitySection(it.key, it.value) }
}

@Composable
private fun ActivityList(
    sections: List<ActivitySection>,
    participants: List<Participant>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenExpense: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val namesById = remember(participants) { participants.associate { it.id to it.name } }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        for (section in sections) {
            item(key = "header_${section.bucket}") {
                DateHeader(bucket = section.bucket, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
            items(section.activities, key = { it.id }) { activity ->
                ActivityRow(
                    activity = activity,
                    participantName = activity.participantId?.let { namesById[it] },
                    onOpenExpense = onOpenExpense,
                )
            }
        }
        if (hasMore) {
            item(key = "load_more") {
                LaunchedEffect(Unit) { onLoadMore() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .testTag(TestTags.ACTIVITY_LOG_LOAD_MORE),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMore) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: Activity,
    participantName: String?,
    onOpenExpense: (String) -> Unit,
) {
    // The expense object beside the row, not the ID, see this file's own header. A row about the
    // group's own settings has neither, and is text that stays text.
    val expenseId = activity.expenseId?.takeIf { activity.expenseStillExists }
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    Row(
        modifier = (if (expenseId != null) base.clickable { onOpenExpense(expenseId) } else base)
            .testTag(TestTags.activityRow(activity.id))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(glyphOf(activity.activityType)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // A fixed column, so the sentences line up down the list rather than shifting with
            // the width of each glyph.
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summaryOf(activity, participantName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TestTags.activityRowSummary(activity.id)),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = timeFormatter.format(activity.time.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The same glyphs the app uses for these actions elsewhere. */
private fun glyphOf(type: ActivityType): Int = when (type) {
    ActivityType.CreateExpense -> R.drawable.ic_plus
    ActivityType.UpdateExpense -> R.drawable.ic_edit
    ActivityType.DeleteExpense -> R.drawable.ic_trash
    ActivityType.UpdateGroup -> R.drawable.ic_settings
    // Unreachable: bucketActivities drops anything unrecognised before a row is drawn. A `when`
    // over a sealed interface still has to be exhaustive, and a glyph is a better answer here
    // than a crash if that ever stops being true.
    is ActivityType.Unknown -> R.drawable.ic_tab_information
}

/**
 * One line of prose for one recorded change.
 *
 * The title comes from the activity's own `data` column, the expense's title **as it was** when
 * this was recorded, not from the expense as it is now. Renaming an expense leaves the old name
 * on the line describing its creation, which is the point of a log.
 */
internal fun summaryOf(activity: Activity, participantName: String?): String {
    val who = participantName ?: "Someone"
    val title = activity.title?.takeIf { it.isNotBlank() }
    return when (activity.activityType) {
        ActivityType.CreateExpense ->
            if (title != null) "$who created \"$title\"" else "$who created an expense"
        ActivityType.UpdateExpense ->
            if (title != null) "$who updated \"$title\"" else "$who updated an expense"
        ActivityType.DeleteExpense ->
            if (title != null) "$who deleted \"$title\"" else "$who deleted an expense"
        ActivityType.UpdateGroup -> "$who changed the group settings"
        is ActivityType.Unknown -> "$who changed something"
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun ActivitySkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TestTags.ACTIVITY_LOG_SKELETON),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(18.dp))
                Column {
                    SkeletonBlock(Modifier.width(200.dp).height(14.dp))
                    Spacer(Modifier.height(6.dp))
                    SkeletonBlock(Modifier.width(120.dp).height(11.dp))
                }
            }
        }
    }
}
