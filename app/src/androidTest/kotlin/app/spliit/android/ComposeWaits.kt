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

// Every wait is bounded. Compose's default is one second, too short for a real round trip.
private const val DEFAULT_TIMEOUT_MS = 10_000L

internal fun hasAnyTag(vararg tags: String): SemanticsMatcher =
    tags.map { hasTestTag(it) }.reduce { left, right -> left or right }

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

internal fun SemanticsNodeInteractionsProvider.exists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

// Clicking the row's Text doesn't reach the row, so this matches the row's tag, not its name.
internal fun isGroupRow(): SemanticsMatcher = SemanticsMatcher("is a groups-list row") { node ->
    val tag = node.config.getOrNull(SemanticsProperties.TestTag)
    tag != null &&
        tag.startsWith(GROUP_ROW_PREFIX) &&
        GROUP_ROW_PART_PREFIXES.none { tag.startsWith(it) }
}

// Prefixes come from the generators, so a renamed tag fails loudly rather than timing out.
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

@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.waitUntilGone(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) {
    waitUntilDoesNotExist(hasTestTag(tag), timeoutMillis)
}

// Rows are tagged by position, so this finds the row by its content instead.
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
