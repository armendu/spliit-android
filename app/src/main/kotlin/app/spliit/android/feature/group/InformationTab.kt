package app.spliit.android.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.R
import app.spliit.android.feature.groups.SkeletonBlock
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.fabAndNavigationBarPadding
import app.spliit.android.ui.design.EmptyState
import app.spliit.android.ui.design.Monogram
import app.spliit.core.LoadState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.spliit.android.ui.design.CenteredScroll

/**
 * What a group *is*, beside what it costs: the note it keeps for its participants, who those
 * participants are, the couple of facts that were otherwise only visible from inside the editor,
 * the way to the activity log, and the "who are you?" question.
 *
 * The web app's version of this tab is the note and nothing else. That reads differently on a
 * phone, where a tab is one of four places a thumb can reach and most groups never fill the note
 * in, the tab would be empty for exactly the people who went looking. Naming the participants is
 * the cheapest thing that earns the slot, and this is the only screen in the app that lists them
 * outside the editor.
 *
 * The note has no editor of its own and should not grow one: it is a field on the group, and the
 * group form is where the group's fields are edited. The button here opens that form.
 *
 * Everything on this tab is the group, so unlike the expense and balance tabs there is no second
 * request to wait on.
 */
@Composable
internal fun InformationTab(
    groupState: LoadState<GroupInfo>,
    activeParticipantId: String?,
    onRetry: () -> Unit,
    onEditGroup: () -> Unit,
    onOpenActivity: () -> Unit,
    onIdentify: () -> Unit,
) {
    when (groupState) {
        is LoadState.Loading -> InformationSkeleton()

        is LoadState.Failed -> CenteredScroll {
            EmptyState(
                icon = "!",
                title = "Couldn't load this group",
                description = groupState.message ?: "Check your connection and try again.",
            ) {
                Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.GROUP_DETAIL_RETRY_BUTTON)) {
                    Text("Retry")
                }
            }
        }

        is LoadState.Loaded -> InformationContent(
            group = groupState.value,
            activeParticipantId = activeParticipantId,
            onEditGroup = onEditGroup,
            onOpenActivity = onOpenActivity,
            onIdentify = onIdentify,
        )
    }
}

@Composable
private fun InformationContent(
    group: GroupInfo,
    activeParticipantId: String?,
    onEditGroup: () -> Unit,
    onOpenActivity: () -> Unit,
    onIdentify: () -> Unit,
) {
    // The server stores whatever was typed, and a note of three spaces is not a note, it would
    // draw a blank row where the empty state belongs.
    val note = group.information?.trim().orEmpty().takeIf { it.isNotEmpty() }
    val you = group.participants.firstOrNull { it.id == activeParticipantId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            bottom = fabAndNavigationBarPadding(),
        ),
    ) {
        item(key = "note_header") {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Information")
        }
        item(key = "note") {
            Text(
                text = note ?: "No information yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (note == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.testTag(
                    if (note == null) TestTags.INFORMATION_NOTE_EMPTY else TestTags.INFORMATION_NOTE,
                ),
            )
            TextButton(
                onClick = onEditGroup,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag(TestTags.INFORMATION_EDIT_NOTE_BUTTON),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (note == null) "Add information" else "Edit information")
            }
            Footnote(
                "Anything the group should know, where you are staying, how the split works, " +
                    "a link. Everyone who opens the group sees it.",
            )
            SectionDivider()
        }

        item(key = "participants_header") { SectionHeader("Participants") }
        items(group.participants, key = { "participant_${it.id}" }) { participant ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Monogram(name = participant.name, participantId = participant.id, size = 28.dp)
                Text(
                    text = participant.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TestTags.informationParticipant(participant.id)),
                )
            }
        }
        item(key = "participants_footer") {
            Footnote("Add or remove participants in the group settings.")
            SectionDivider()
        }

        item(key = "details_header") { SectionHeader("Details") }
        item(key = "details") {
            // The symbol with the ISO code beside it: "$" alone does not say whether the group
            // counts in dollars, pesos or something else again.
            DetailRow(
                label = "Currency",
                value = group.currencyCode?.takeIf { it.isNotEmpty() }
                    ?.let { "${group.currencySymbol} ($it)" }
                    ?: group.currencySymbol,
                testTag = TestTags.INFORMATION_CURRENCY,
            )
            DetailRow(
                label = "Created",
                value = createdFormatter.format(group.createdAt.atZone(ZoneId.systemDefault())),
                testTag = TestTags.INFORMATION_CREATED,
            )
            // Which server this group is on. A fact about the group rather than about the app -
            // and the one the share link is built from.
            DetailRow(
                label = "Server",
                value = serverDisplayName(group.instanceBaseUrl),
                testTag = TestTags.INFORMATION_SERVER,
            )
            SectionDivider()
        }

        item(key = "activity") {
            NavigationRow(
                icon = R.drawable.ic_history,
                title = "Activity",
                onClick = onOpenActivity,
                testTag = TestTags.INFORMATION_ACTIVITY_BUTTON,
            )
            Footnote("Everything that has happened in this group, and who did it.")
            SectionDivider()
        }

        item(key = "you") {
            SectionHeader("You")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = onIdentify)
                    .testTag(TestTags.INFORMATION_YOU_BUTTON)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (you != null) {
                    Monogram(name = you.name, participantId = you.id, size = 28.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = you?.name ?: "Not set",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Footnote(
                "Which participant you are, on this phone. It decides whose balance the " +
                    "balances tab leads with, and who a new expense is paid by.",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, testTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun NavigationRow(icon: Int, title: String, onClick: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private val createdFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** The host, which is what distinguishes one instance from another, a full base URL in a
 *  right-aligned detail row is mostly scheme and slashes. */
private fun serverDisplayName(instanceBaseUrl: String): String =
    runCatching { java.net.URI(instanceBaseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: instanceBaseUrl

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
private fun Footnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}


@Composable
private fun InformationSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(TestTags.INFORMATION_SKELETON),
    ) {
        SkeletonBlock(Modifier.width(110.dp).height(14.dp))
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(Modifier.fillMaxWidth().height(16.dp))
        Spacer(Modifier.height(8.dp))
        SkeletonBlock(Modifier.width(220.dp).height(16.dp))
        Spacer(Modifier.height(28.dp))
        repeat(3) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(28.dp), shape = CircleShape)
                SkeletonBlock(Modifier.width(120.dp).height(14.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
