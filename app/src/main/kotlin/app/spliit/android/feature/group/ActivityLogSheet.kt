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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import app.spliit.android.ui.design.LoadMoreFooter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityLogSheet(
    state: GroupDetailUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val participants = (state.group as? LoadState.Loaded)?.value?.participants.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
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
                            page = activities.value,
                            isLoadingMore = state.isLoadingMoreActivities,
                            loadMoreFailed = state.loadMoreActivitiesFailed,
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

internal data class ActivitySection(val bucket: DateBucket, val activities: List<Activity>)

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
    page: ActivitiesPage,
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
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
        if (page.hasMore) {
            item(key = "load_more") {
                LoadMoreFooter(
                    cursor = page.nextCursor,
                    isLoading = isLoadingMore,
                    failed = loadMoreFailed,
                    onLoadMore = onLoadMore,
                    testTag = TestTags.ACTIVITY_LOG_LOAD_MORE,
                )
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

private fun glyphOf(type: ActivityType): Int = when (type) {
    ActivityType.CreateExpense -> R.drawable.ic_plus
    ActivityType.UpdateExpense -> R.drawable.ic_edit
    ActivityType.DeleteExpense -> R.drawable.ic_trash
    ActivityType.UpdateGroup -> R.drawable.ic_settings
    ActivityType.ScheduleGroupDeletion -> R.drawable.ic_trash
    ActivityType.RestoreGroup -> R.drawable.ic_history
    is ActivityType.Unknown -> R.drawable.ic_tab_information
}

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
        ActivityType.ScheduleGroupDeletion -> "$who scheduled the group for deletion"
        ActivityType.RestoreGroup -> "$who restored the group"
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
