package app.spliit.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.spliit.android.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Creates its own, timestamped group: the server is shared and seeded IDs change.
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CreateGroupFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun aGroupCreatedHereCanBeOpenedAndEveryTabDrawsSomething() {
        val name = "E2E ${System.currentTimeMillis()}"

        rule.waitUntilAnyExists(
            TestTags.GROUPS_LIST_FAB,
            TestTags.GROUPS_LIST_EMPTY_CREATE_BUTTON,
            timeoutMillis = 20_000,
        )
        if (rule.exists(TestTags.GROUPS_LIST_FAB)) {
            rule.onNodeWithTag(TestTags.GROUPS_LIST_FAB).performClick()
        } else {
            rule.onNodeWithTag(TestTags.GROUPS_LIST_EMPTY_CREATE_BUTTON).performClick()
        }

        rule.waitUntilExists(TestTags.GROUP_FORM_SHEET)
        rule.onNodeWithTag(TestTags.GROUP_FORM_NAME_FIELD).performTextInput(name)

        // "Add participant" can end up under the keyboard, so scroll to it first.
        for (participant in listOf("Ana", "Bruno")) {
            rule.onNodeWithTag(TestTags.GROUP_FORM_ADD_PARTICIPANT_BUTTON)
                .performScrollTo()
                .performClick()
            rule.waitForIdle()
            val field = rule.blankParticipantFieldTag()
            rule.onNodeWithTag(field).performScrollTo().performTextInput(participant)
            rule.waitForIdle()
        }

        rule.onNodeWithTag(TestTags.GROUP_FORM_SAVE_BUTTON).performClick()

        // Wait for the sheet to close first: its own field still shows the name, so waiting on the name passes too early.
        rule.waitUntilGone(TestTags.GROUP_FORM_SHEET, timeoutMillis = 20_000)

        val row = isGroupRow() and hasText(name, substring = true)
        rule.waitUntilExists(row, timeoutMillis = 20_000)

        // Match by name: `adb install -r` keeps rows from earlier runs.
        rule.onNode(row).performClick()

        // Open each tab: three load lazily, so a failing first load would otherwise go unnoticed.
        rule.waitUntilExists(TestTags.GROUP_DETAIL_TAB_EXPENSES, timeoutMillis = 20_000)
        for (tag in listOf(
            TestTags.GROUP_DETAIL_TAB_BALANCES,
            TestTags.GROUP_DETAIL_TAB_TOTALS,
            TestTags.GROUP_DETAIL_TAB_INFORMATION,
        )) {
            rule.onNodeWithTag(tag).performClick()
            rule.waitForIdle()
        }

        rule.waitUntilTextExists("Ana")
        // assertExists, not assertIsDisplayed: the name may be below the fold.
        rule.onNodeWithText("Bruno").assertExists()
    }
}
