package app.spliit.android.feature.groups

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.AppSettingsHolder
import app.spliit.android.R
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.CapsLabel
import app.spliit.android.ui.design.fabAndNavigationBarPadding
import app.spliit.android.ui.design.EmptyState
import app.spliit.android.ui.design.SpliitFab
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.spliit.android.ui.design.Monogram
import app.spliit.core.LoadState
import app.spliit.android.ui.design.CenteredScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.spliit.android.ui.design.CardShape
import app.spliit.android.ui.design.LoadFailure
import app.spliit.android.ui.design.SkeletonBlock

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    viewModel: GroupsListViewModel,
    addGroupViewModel: AddGroupByUrlViewModel,
    createGroupViewModel: GroupFormViewModel,
    onGroupClick: (GroupListItem) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val pendingRemoval by viewModel.pendingRemoval.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isAddMenuOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isCreateSheetOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val createSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val openCreateSheet = {
        createGroupViewModel.resetForCreate(AppSettingsHolder.defaultInstanceBaseUrl)
        isCreateSheetOpen = true
    }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(pendingRemoval) {
        val pending = pendingRemoval ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Removed ${pending.groupName}",
            actionLabel = "Undo",
            withDismissAction = false,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoval()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { SpliitWordmark() },
                actions = {
                    IconButton(
                        onClick = {
                            addGroupViewModel.reset()
                            isAddSheetOpen = true
                        },
                        modifier = Modifier
                            .semantics { contentDescription = "Add a group by link" }
                            .testTag(TestTags.GROUPS_LIST_MENU_ADD_BY_LINK),
                    ) {
                        Icon(painterResource(R.drawable.ic_link), contentDescription = null)
                    }
                    Box {
                        IconButton(
                            onClick = { isAddMenuOpen = true },
                            modifier = Modifier
                                .semantics { contentDescription = "More" }
                                .testTag(TestTags.GROUPS_LIST_ADD_MENU_BUTTON),
                        ) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
                        }
                        DropdownMenu(expanded = isAddMenuOpen, onDismissRequest = { isAddMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_settings), contentDescription = null)
                                },
                                onClick = {
                                    isAddMenuOpen = false
                                    onOpenSettings()
                                },
                                modifier = Modifier.testTag(TestTags.GROUPS_LIST_SETTINGS_BUTTON),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            val dashboard = (state as? LoadState.Loaded)?.value
            if (dashboard != null && !dashboard.isEmpty) {
                SpliitFab(
                    icon = R.drawable.ic_plus,
                    contentDescription = "Create group",
                    onClick = openCreateSheet,
                    testTag = TestTags.GROUPS_LIST_FAB,
                )
            }
        },
    ) { contentPadding ->
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 220, easing = EaseInOut),
            modifier = Modifier.padding(top = contentPadding.calculateTopPadding()).fillMaxSize(),
            label = "groups_list_content",
        ) { current ->
            when (current) {
                is LoadState.Loading -> GroupsListSkeleton()

                is LoadState.Failed -> CenteredScroll {
                    LoadFailure(
                        title = "Couldn't load your groups",
                        message = current.message,
                        retryTestTag = TestTags.GROUPS_LIST_RETRY_BUTTON,
                        onRetry = viewModel::retry,
                    )
                }

                is LoadState.Loaded -> if (current.value.isEmpty) {
                    CenteredScroll {
                        EmptyState(
                            title = "A trip. A flat. Dinner with friends.",
                            description = "Create a group and Spliit keeps track of who paid, " +
                                "so nobody has to run the numbers.",
                            art = {
                                Image(
                                    painter = painterResource(R.drawable.ic_spliit_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp),
                                )
                            },
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Button(
                                    onClick = openCreateSheet,
                                    modifier = Modifier.testTag(TestTags.GROUPS_LIST_EMPTY_CREATE_BUTTON),
                                ) {
                                    Text("Create group")
                                }
                                TextButton(
                                    onClick = {
                                        addGroupViewModel.reset()
                                        isAddSheetOpen = true
                                    },
                                    modifier = Modifier.testTag(TestTags.GROUPS_LIST_EMPTY_ADD_BY_LINK_BUTTON),
                                ) {
                                    Text("Add by link")
                                }
                            }
                        }
                    }
                } else {
                    GroupsList(
                        dashboard = current.value,
                        onGroupClick = { item ->
                            viewModel.onGroupOpened(item.groupId)
                            onGroupClick(item)
                        },
                        onSetStarred = viewModel::setStarred,
                        onSetArchived = viewModel::setArchived,
                        onRemove = viewModel::removeGroup,
                    )
                }
            }
        }
    }

    if (isCreateSheetOpen) {
        CreateGroupSheet(
            viewModel = createGroupViewModel,
            sheetState = createSheetState,
            onCreated = {
                isCreateSheetOpen = false
                viewModel.load()
            },
            onDismiss = { isCreateSheetOpen = false },
        )
    }

    if (isAddSheetOpen) {
        AddGroupByUrlSheet(
            viewModel = addGroupViewModel,
            sheetState = sheetState,
            onAdded = {
                isAddSheetOpen = false
                addGroupViewModel.reset()
                viewModel.load()
            },
            onDismiss = {
                isAddSheetOpen = false
                addGroupViewModel.reset()
            },
        )
    }
}

@Composable
private fun GroupsList(
    dashboard: GroupsDashboard,
    onGroupClick: (GroupListItem) -> Unit,
    onSetStarred: (String, Boolean) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    var isArchivedExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = fabAndNavigationBarPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dashboard.unreachableInstances.isNotEmpty()) {
            item(key = "unreachable") {
                Text(
                    text = unreachableMessage(dashboard.unreachableInstances),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.GROUPS_LIST_UNREACHABLE_NOTE),
                )
            }
        }

        groupSection("Starred", dashboard.starred, onGroupClick, onSetStarred, onSetArchived, onRemove)
        groupSection(
            if (dashboard.starred.isEmpty() && dashboard.archived.isEmpty()) null else "Recent groups",
            dashboard.recent,
            onGroupClick,
            onSetStarred,
            onSetArchived,
            onRemove,
        )

        if (dashboard.archived.isNotEmpty()) {
            item(key = "archived-header") {
                GroupsSectionHeader(
                    label = "Archived · ${dashboard.archived.size}",
                    action = if (isArchivedExpanded) "Hide" else "Show",
                    onAction = { isArchivedExpanded = !isArchivedExpanded },
                )
            }
            if (isArchivedExpanded) {
                items(dashboard.archived, key = { it.groupId }) { group ->
                    GroupRow(group, onGroupClick, onSetStarred, onSetArchived, onRemove)
                }
            }
        }
    }
}

private fun LazyListScope.groupSection(
    label: String?,
    groups: List<GroupListItem>,
    onGroupClick: (GroupListItem) -> Unit,
    onSetStarred: (String, Boolean) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (groups.isEmpty()) return
    if (label != null) {
        item(key = "header-$label") { GroupsSectionHeader(label = label) }
    }
    items(groups, key = { it.groupId }) { group ->
        GroupRow(group, onGroupClick, onSetStarred, onSetArchived, onRemove)
    }
}

@Composable
private fun GroupsSectionHeader(label: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel(label, Modifier.weight(1f).testTag(TestTags.groupsListSectionHeader(label)))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.testTag(TestTags.GROUPS_LIST_ARCHIVED_TOGGLE)) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    group: GroupListItem,
    onGroupClick: (GroupListItem) -> Unit,
    onSetStarred: (String, Boolean) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
                .combinedClickable(
                    onClick = { onGroupClick(group) },
                    onLongClick = { isMenuOpen = true },
                )
                .testTag(TestTags.groupsListRow(group.groupId))
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Monogram(name = group.name, participantId = group.groupId, size = 40.dp)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(TestTags.groupsListRowName(group.groupId)),
                )
                Spacer(Modifier.height(4.dp))
                GroupMetadataRow(group)
            }

            IconButton(
                onClick = { isMenuOpen = true },
                modifier = Modifier
                    .semantics { contentDescription = "Actions for ${group.name}" }
                    .testTag(TestTags.groupsListRowMenuButton(group.groupId)),
            ) {
                Text("⋮", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (group.isStarred) "Unstar" else "Star") },
                onClick = {
                    isMenuOpen = false
                    onSetStarred(group.groupId, !group.isStarred)
                },
                modifier = Modifier.testTag(TestTags.groupsListRowStar(group.groupId)),
            )
            DropdownMenuItem(
                text = { Text(if (group.isArchived) "Unarchive" else "Archive") },
                onClick = {
                    isMenuOpen = false
                    onSetArchived(group.groupId, !group.isArchived)
                },
                modifier = Modifier.testTag(TestTags.groupsListRowArchive(group.groupId)),
            )
            DropdownMenuItem(
                text = { Text("Remove from this list") },
                onClick = {
                    isMenuOpen = false
                    onRemove(group.groupId)
                },
                modifier = Modifier.testTag(TestTags.groupsListRowRemove(group.groupId)),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupMetadataRow(group: GroupListItem) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconTextPair(
            icon = R.drawable.ic_people,
            text = participantsLabel(group.participantCount),
            testTag = TestTags.groupsListRowParticipants(group.groupId),
        )
        val createdAt = group.createdAt
        if (createdAt != null) {
            IconTextPair(
                icon = R.drawable.ic_calendar,
                text = createdDateText(createdAt),
                testTag = TestTags.groupsListRowCreatedDate(group.groupId),
                contentDescription = createdDateContentDescription(createdAt),
            )
        }
    }
}

@Composable
private fun IconTextPair(icon: Int, text: String, testTag: String, contentDescription: String? = null) {
    Row {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp).alignByBaseline(),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .alignByBaseline()
                .testTag(testTag)
                .let { base ->
                    if (contentDescription != null) {
                        base.semantics { this.contentDescription = contentDescription }
                    } else {
                        base
                    }
                },
        )
    }
}

private val createdDateFormatterMedium: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val createdDateFormatterLong: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

private fun createdDateText(createdAt: Instant): String =
    createdAt.atZone(ZoneId.systemDefault()).toLocalDate().format(createdDateFormatterMedium)

private fun createdDateContentDescription(createdAt: Instant): String =
    "Created " + createdAt.atZone(ZoneId.systemDefault()).toLocalDate().format(createdDateFormatterLong)

private fun participantsLabel(count: Int?): String = when (count) {
    null -> "…"
    1 -> "1 participant"
    else -> "$count participants"
}

private fun unreachableMessage(instances: List<String>): String {
    val names = when (instances.size) {
        1 -> instances.single()
        2 -> "${instances[0]} and ${instances[1]}"
        else -> instances.dropLast(1).joinToString(", ") + " and " + instances.last()
    }
    return "Couldn't reach $names, so some group details may be out of date."
}

@Composable
private fun GroupsListSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TestTags.GROUPS_LIST_SKELETON),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            GroupRowSkeleton()
        }
    }
}

@Composable
private fun GroupRowSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBlock(Modifier.size(40.dp), shape = CircleShape)
        Column {
            SkeletonBlock(Modifier.width(140.dp).height(16.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBlock(Modifier.width(90.dp).height(12.dp))
        }
    }
}

@Composable
private fun SpliitWordmark() {
    val fixedSize = with(LocalDensity.current) { 20.dp.toSp() }
    Text(
        text = "Spliit",
        fontSize = fixedSize,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}
