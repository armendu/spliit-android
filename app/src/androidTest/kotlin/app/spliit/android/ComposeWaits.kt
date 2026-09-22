package app.spliit.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import app.spliit.android.ui.TestTags

/**
 * Bounded waits, because every wait in a UI suite has to be one: the iOS suite once turned a
 * missing element into a CI job that swiped for forty minutes. Generous by unit-test standards,
 * since each step is a real request over the emulator's NAT.
 */
private const val DEFAULT_TIMEOUT_MS = 10_000L

/** Any one of [tags], for a screen that shows one control or another depending on state. */
internal fun hasAnyTag(vararg tags: String): SemanticsMatcher =
    tags.map { hasTestTag(it) }.reduce { left, right -> left or right }

/**
 * Waits for whichever of [tags] turns up first. A helper rather than a bare call, because
 * Compose's own default timeout is **one second**, which a first round trip loses to, and the
 * failure then blames the assertion rather than the wait.
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
 * Whether a node is there *right now*, no waiting, no failure if it is not.
 *
 * Used for the "one control or the other" branch, where both outcomes are legitimate and
 * asserting either one would be wrong.
 */
internal fun SemanticsNodeInteractionsProvider.exists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

/**
 * The dashboard row for a group, whichever group it is: a row's tag carries a server-generated
 * ID that never reaches the UI readably. Every part of a row shares the prefix and two of them
 * are clickable, hence the exclusions rather than a prefix match plus `hasClickAction`.
 *
 * Matching the *name* instead does not work: clicking the `Text` does not dispatch to the row.
 */
internal fun isGroupRow(): SemanticsMatcher = SemanticsMatcher("is a groups-list row") { node ->
    val tag = node.config.getOrNull(SemanticsProperties.TestTag)
    tag != null &&
        tag.startsWith(GROUP_ROW_PREFIX) &&
        GROUP_ROW_PART_PREFIXES.none { tag.startsWith(it) }
}

// The prefixes are taken from the generators rather than written out again: a renamed tag would
// otherwise leave this matching nothing, which shows up as a timeout rather than as a rename.
private const val ANY_ID = "id"
private val GROUP_ROW_PREFIX = TestTags.groupsListRow(ANY_ID).removeSuffix(ANY_ID)
private val GROUP_ROW_PART_PREFIXES: List<String> = listOf(
    TestTags.groupsListRowName(ANY_ID),
    TestTags.groupsListRowParticipants(ANY_ID),
    TestTags.groupsListRowCreatedDate(ANY_ID),
    TestTags.groupsListRowMenuButton(ANY_ID),
    TestTags.groupsListRowStar(ANY_ID),
    TestTags.groupsListRowArchive(ANY_ID),
    TestTags.groupsListRowRemove(ANY_ID),
).map { it.removeSuffix(ANY_ID) }

/**
 * Waits for a node to go away. "Gone" is often the only signal a screen gives that an operation
 * finished: the create sheet dismisses itself on success, and waiting for what comes next
 * instead races that dismissal against the list reloading behind it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilGone(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) {
    waitUntilDoesNotExist(hasTestTag(tag), timeoutMillis)
}

/**
 * The tag of the participant row that currently has no name in it.
 *
 * Rows are tagged by **position** and the form sorts them by name, so a row moves the moment
 * somebody types into it. The symptom was not a wrong name but the create sheet never closing,
 * because the still-blank participant failed validation.
 */
internal fun ComposeTestRule.blankParticipantFieldTag(): String {
    val prefix = TestTags.groupFormParticipantField(0).removeSuffix("0")
    val match = SemanticsMatcher("is a participant field") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
    val blank = onAllNodes(match).fetchSemanticsNodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
    }
    return requireNonNull(blank?.config?.getOrNull(SemanticsProperties.TestTag)) {
        "No blank participant row on the form, every row already has a name."
    }
}

private fun requireNonNull(value: String?, message: () -> String): String =
    value ?: throw AssertionError(message())
