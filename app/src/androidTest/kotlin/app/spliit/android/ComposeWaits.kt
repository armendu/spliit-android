package app.spliit.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Bounded waits, because every wait in a UI suite has to be one.
 *
 * CLAUDE.md keeps this on its list for a reason the iOS suite paid for: an unbounded loop turned
 * a missing element into a CI job that swiped for forty minutes and then failed anyway. Every
 * helper here takes a timeout, defaults to a modest one, and fails with the tag it was looking
 * for rather than with a generic timeout — the tag is the only thing that makes the failure
 * readable months later.
 *
 * The default is generous by unit-test standards and deliberately so: these run against a real
 * server over the emulator's NAT, where a first request that has to warm a connection is
 * routinely slower than every one after it.
 */
private const val DEFAULT_TIMEOUT_MS = 10_000L

/** Any one of [tags] — for a screen that shows one control or another depending on state. */
internal fun hasAnyTag(vararg tags: String): SemanticsMatcher =
    tags.map { hasTestTag(it) }.reduce { left, right -> left or right }

/**
 * Waits for whichever of [tags] turns up first.
 *
 * A helper rather than a bare `waitUntilAtLeastOneExists(hasAnyTag(…))` at the call site,
 * because Compose's own default timeout for that function is **one second** — short enough that
 * a first network round trip loses to it, and the resulting failure blames the assertion rather
 * than the wait. Going through here is what applies this file's timeout instead.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilAnyExists(
    vararg tags: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    waitUntilAtLeastOneExists(hasAnyTag(*tags), timeoutMillis)
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilExists(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) {
    waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis)
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilExists(
    matcher: SemanticsMatcher,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    waitUntilAtLeastOneExists(matcher, timeoutMillis)
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilTextExists(
    text: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis)
}

/**
 * Whether a node is there *right now* — no waiting, no failure if it is not.
 *
 * Used for the "one control or the other" branch, where both outcomes are legitimate and
 * asserting either one would be wrong.
 */
internal fun SemanticsNodeInteractionsProvider.exists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

/**
 * The dashboard row for a group, whichever group it is.
 *
 * A row's tag carries the server-generated group ID, which a test that *created* the group has
 * no way of knowing — the ID never reaches the UI in a readable form. Matching the prefix would
 * be enough if the row were the only thing tagged with it, but every part of a row is:
 * `groups_list_row_name_…`, `…_menu_…` and the rest all start the same way, and two of those are
 * clickable. Hence the explicit exclusion list rather than a prefix match plus `hasClickAction`.
 *
 * Finding the row by its *name* instead looks simpler and does not work: the name is drawn by a
 * `Text` inside the clickable row, and clicking the node that matches the text does not dispatch
 * to the row's own click handler.
 */
internal fun isGroupRow(): SemanticsMatcher {
    val prefix = "groups_list_row_"
    val parts = listOf("name_", "participants_", "created_", "menu_", "star_", "archive_", "remove_")
    return SemanticsMatcher("is a groups-list row") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith(prefix) && parts.none { tag.startsWith(prefix + it) }
    }
}

/**
 * Waits for a node to go away — the sheet closing, a spinner clearing.
 *
 * "Gone" is often the only signal an operation finished that a screen actually gives. Creating a
 * group is the case here: the sheet dismisses itself on success, and waiting for what comes
 * *next* instead races the dismissal animation against the list reloading behind it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilGone(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) {
    waitUntilDoesNotExist(hasTestTag(tag), timeoutMillis)
}

/**
 * The tag of the participant row that currently has no name in it.
 *
 * Participant rows are tagged by their **position**, and the form sorts them by name
 * (`GroupFormDraft.sortedParticipants`) — so a row moves the moment somebody types into it, and
 * a freshly added blank one lands wherever the empty string collates. Typing into
 * `participant_field_1` because it was the second row added therefore puts the text in whichever
 * row happens to be second *now*, which during this test was the row already holding "Ana".
 *
 * The visible symptom was not a wrong name: it was the create sheet never closing, because the
 * still-blank participant failed validation. Hence addressing the row by what is *in* it.
 */
internal fun ComposeTestRule.blankParticipantFieldTag(): String {
    val prefix = "group_form_participant_field_"
    val match = SemanticsMatcher("is a participant field") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
    val blank = onAllNodes(match).fetchSemanticsNodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
    }
    return requireNonNull(blank?.config?.getOrNull(SemanticsProperties.TestTag)) {
        "No blank participant row on the form — every row already has a name."
    }
}

private fun requireNonNull(value: String?, message: () -> String): String =
    value ?: throw AssertionError(message())
