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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.spliit.android.ui.design.EmptyState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.spliit.android.ui.design.Monogram
import app.spliit.core.LoadState

/** DESIGN.md §3: "Cards and surfaces" are 16dp radius, `surface-container-lowest` with a 1dp
 *  `border-subtle` (M3's `outline`) hairline — Level 1, the one step up from the bare canvas. */
private val CardShape = RoundedCornerShape(16.dp)

/**
 * The home screen: the groups this phone remembers, in three sections, with participant counts
 * fetched from the instance each one lives on.
 *
 * The list itself is local — Spliit has no accounts — so it renders from the stored snapshot
 * immediately and the server detail fills in; a server that cannot be reached costs its own
 * groups their detail, never the whole screen (see [GroupsListViewModel]).
 *
 * Everything that can be done *to* a group lives in one menu per row, reached by a long press or
 * by the row's own overflow button. A swipe was the other option and is what iOS uses; two
 * affordances for three actions, one of them irreversible, is more surface than this screen
 * needs, and a menu is the Android idiom for "the things this row can do" — it also names the
 * actions instead of asking anyone to learn a colour.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    viewModel: GroupsListViewModel,
    addGroupViewModel: AddGroupByUrlViewModel,
    onGroupClick: (GroupListItem) -> Unit,
    onCreateGroup: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val pendingRemoval by viewModel.pendingRemoval.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isAddMenuOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Reruns whenever this screen is freshly composed — including on the way back from creating a
    // group, when the ViewModel itself (scoped to the nav entry, so it survives) would otherwise
    // keep showing what it loaded before. See GroupsListViewModel.load's own note.
    LaunchedEffect(Unit) { viewModel.load() }

    // The snackbar is shown for exactly as long as the removal is still undoable and not a moment
    // longer: when the ViewModel's window closes it clears `pendingRemoval`, this effect is
    // cancelled, and the snackbar goes with it. Racing a snackbar duration against that window
    // would leave either an offer that no longer works or a window with nothing offering it.
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
                // The one screen that is the app itself rather than a group, an expense or a
                // form — the wordmark sits where the title would, fixed-size so the bar (a fixed
                // height) never clips it as text scales. "Spliit" is still what a screen reader
                // hears, via the wordmark's own text.
                title = { SpliitWordmark() },
                actions = {
                    // Three entry points, each one tap, none of them the same instruction twice:
                    // the FAB creates, this joins a group somebody sent, and the overflow holds
                    // what is not a daily action. "Create group" deliberately does NOT appear in
                    // a menu as well as on the FAB — the empty state already taught that lesson.
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
                        // iOS offers "Add by QR code" too. It needs the camera and a barcode
                        // decoder, and cycle 1 defers both — an item that opens nothing is worse
                        // than one that is not there, so it is left out rather than disabled.
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
            // Only once there is a list to sit beside. The empty state already leads with
            // "Create group" as its one action, and a FAB saying the same thing two inches
            // below it is the same instruction twice, competing with itself. Where there is a
            // list, the FAB is the shortcut to the thing most people opened this screen to do;
            // the "+" menu above is where the full set lives, including the one that needs a
            // link somebody sent them.
            val dashboard = (state as? LoadState.Loaded)?.value
            if (dashboard != null && !dashboard.isEmpty) {
                // DESIGN.md §4: "FAB — extended pill, primary on on-primary." M3's own FAB
                // default (primaryContainer/onPrimaryContainer) is a baseline tone this theme
                // never tunes — see Color.kt: only primary itself is seeded from the accent — so
                // both colours are given explicitly, the same pair the empty state's own "Create
                // group" button draws in, rather than left to read as two different brand
                // colours.
                ExtendedFloatingActionButton(
                    onClick = onCreateGroup,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = {
                        Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    },
                    text = { Text("Create group") },
                    modifier = Modifier.testTag(TestTags.GROUPS_LIST_FAB),
                )
            }
        },
    ) { contentPadding ->
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 220, easing = EaseInOut),
            // Top only — the list below adds the navigation-bar inset to its own content padding
            // so it scrolls under the bar rather than stopping above it. See ExpensesTab.
            modifier = Modifier.padding(top = contentPadding.calculateTopPadding()).fillMaxSize(),
            label = "groups_list_content",
        ) { current ->
            when (current) {
                is LoadState.Loading -> GroupsListSkeleton()

                is LoadState.Failed -> CenteredScroll {
                    EmptyState(
                        icon = "!",
                        title = "Couldn't load your groups",
                        description = current.message ?: "Check your connection and try again.",
                    ) {
                        Button(
                            onClick = viewModel::retry,
                            modifier = Modifier.testTag(TestTags.GROUPS_LIST_RETRY_BUTTON),
                        ) {
                            Text("Retry")
                        }
                    }
                }

                is LoadState.Loaded -> if (current.value.isEmpty) {
                    CenteredScroll {
                        EmptyState(
                            icon = "",
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
                                    onClick = onCreateGroup,
                                    modifier = Modifier.testTag(TestTags.GROUPS_LIST_EMPTY_CREATE_BUTTON),
                                ) {
                                    Text("Create group")
                                }
                                // The other half of the same decision — somebody else made the
                                // group and this is how you get into it — rather than a second
                                // thing to weigh up, so it is the quieter of the two.
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

    if (isAddSheetOpen) {
        AddGroupByUrlSheet(
            viewModel = addGroupViewModel,
            sheetState = sheetState,
            onAdded = {
                isAddSheetOpen = false
                addGroupViewModel.reset()
                // The group is in the stored list now; the dashboard is what shows it.
                viewModel.load()
            },
            onDismiss = {
                isAddSheetOpen = false
                addGroupViewModel.reset()
            },
        )
    }
}

/** Centres short content vertically, and falls back to scrolling it when it doesn't fit — the
 *  largest accessibility text sizes can make a title, a description and a button taller than the
 *  screen, and an empty state whose only action has fallen off the bottom is worse than none. */
@Composable
private fun CenteredScroll(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
    // Archived groups are collapsed by default: they are on the list precisely because somebody
    // asked them to stop taking up room, and the header still says how many there are.
    var isArchivedExpanded by rememberSaveable { mutableStateOf(false) }

    // Each group is its own card on the canvas, as the design's group cards are. The decoration
    // belongs to the row and not to the LazyColumn: painted on the column it covers the whole
    // scrolling viewport, so one group drew a card the full height of the screen.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // The extended FAB and then the navigation bar sit over the foot of this list, and the
        // last group card is the one somebody scrolled down to reach.
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
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

        // A header only where there is something under it: a list with nothing starred and
        // nothing archived looks exactly as it did before either existed.
        groupSection("Starred", dashboard.starred, onGroupClick, onSetStarred, onSetArchived, onRemove)
        groupSection(
            // Named only when it is not the whole list — a lone "Recent groups" header over
            // everything on the screen labels nothing.
            if (dashboard.starred.isEmpty() && dashboard.archived.isEmpty()) null else "Recent groups",
            dashboard.recent,
            onGroupClick,
            onSetStarred,
            onSetArchived,
            onRemove,
        )

        if (dashboard.archived.isNotEmpty()) {
            item(key = "archived-header") {
                SectionHeader(
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
        item(key = "header-$label") { SectionHeader(label = label) }
    }
    items(groups, key = { it.groupId }) { group ->
        GroupRow(group, onGroupClick, onSetStarred, onSetArchived, onRemove)
    }
}

/** DESIGN.md §2: `label-sm` is the bucket-header style, and §3 asks for more space above a
 *  heading than below it — a heading belongs to what follows. */
@Composable
private fun SectionHeader(label: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(TestTags.groupsListSectionHeader(label)),
        )
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

            // The same menu the long press opens. A long press alone is an action nobody can see,
            // and these three are the only way to star, put away or remove a group.
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
                // "Remove" on its own sounds like it deletes the group for everybody. It only
                // takes it off this list — but with no account and no other record, the link is
                // the only way back, which is why this one is offered with an undo.
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

/**
 * The row's metadata line: icon-led pairs, wrapping rather than truncating — iOS's own `GroupRow`
 * draws the same two pairs with `Label(_, systemImage:)`, wrapped by an `AdaptiveHStack`. At the
 * largest accessibility text sizes three pairs will not fit on one line, and a truncated
 * participant count is worse than a row that grows a second line, so [FlowRow] rather than a
 * fixed [Row].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupMetadataRow(group: GroupListItem) {
    // The *current* default, not the build constant — a group created before Settings changed the
    // default instance still reads as "on the default" here, which is correct: this compares
    // against where a group would be created today, and Settings changing does not move a group
    // already on the list (see AppSettingsHolder).
    val showsInstance = group.instanceBaseUrl != AppSettingsHolder.defaultInstanceBaseUrl

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
                // The visible text is abbreviated ("14 Sep 2026") and leans on the calendar icon
                // for "this is a date" context. The icon itself is contentDescription = null (see
                // IconTextPair), so a screen reader has none of that context unless the text
                // supplies it — hence a fuller, prefixed string here rather than the visible one.
                contentDescription = createdDateContentDescription(createdAt),
            )
        }
        if (showsInstance) {
            Text(
                text = InstanceAddress.displayName(group.instanceBaseUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.groupsListRowInstance(group.groupId)),
            )
        }
    }
}

/**
 * One glyph-and-caption pair — the participant count or the created date.
 *
 * The icon is decorative: [Icon]'s own `contentDescription` is always null, because the text right
 * next to it already says what it is — a screen reader hearing "image, 3 participants, image, 14
 * September 2026" says the same thing twice for no benefit. Sized to the caption text (14dp against
 * `bodySmall`'s ~12sp) and aligned to its *baseline* rather than centred in the row: centring
 * against a taller sibling in the same [FlowRow] line is what makes a small icon look like it is
 * floating, where baseline alignment ties it to the one piece of text it belongs to.
 */
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

/** DESIGN.md has no rule for this row specifically; `FormatStyle.MEDIUM` matches the abbreviated
 *  date ExpensesTab's own row caption already draws ("14 Sep 2026"), so a date reads the same way
 *  everywhere in this app rather than picking a fresh format per screen. */
private val createdDateFormatterMedium: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val createdDateFormatterLong: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

private fun createdDateText(createdAt: Instant): String =
    createdAt.atZone(ZoneId.systemDefault()).toLocalDate().format(createdDateFormatterMedium)

/** The fuller, unabbreviated form for the icon-less accessible string — see [IconTextPair]. */
private fun createdDateContentDescription(createdAt: Instant): String =
    "Created " + createdAt.atZone(ZoneId.systemDefault()).toLocalDate().format(createdDateFormatterLong)

/** An instance that did not answer costs its own groups a count, not their row — so the count is
 *  the thing that goes missing, and the note above the list says which server owes it. */
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

// ---- loading ------------------------------------------------------------------------------

/** The shape of the rows about to arrive, not a spinner that tells the reader nothing about
 *  what's coming. */
@Composable
private fun GroupsListSkeleton() {
    // A skeleton is only worth having if it is the shape of what replaces it: same cards, same
    // spacing, same row metrics, so the list settles into place instead of jumping.
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

/**
 * Spliit has no bespoke wordmark asset yet — the launcher icon's own comment says the same. This
 * is a text stand-in, fixed at a fixed visual size (via `LocalDensity`, not `sp`) so it never
 * grows past the app bar's own fixed height, and can be replaced with real mark art without
 * touching the call site, the same way [app.spliit.android.ui.design.CategoryIcon] documents for
 * its own placeholder.
 */
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
