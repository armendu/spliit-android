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

/**
 * The one path that touches everything: create a group on a real server, find it on the
 * dashboard, open it, and look at each of its four tabs.
 *
 * **It creates its own group rather than reading a seeded one**, whose IDs change on every seed,
 * and doing so exercises the half of the app a fixture skips: the form, the mutation, the
 * recent-groups write and the read back. The name carries a timestamp because the instance is
 * shared, so two runs must not collide.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CreateGroupFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun aGroupCreatedHereCanBeOpenedAndEveryTabDrawsSomething() {
        val name = "E2E ${System.currentTimeMillis()}"

        // The dashboard offers creation in two places depending on whether it is empty. The FAB
        // is the one that is always there once anything has been added, and on a fresh install
        // the empty state's button is. Take whichever this device happens to show.
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

        // Two participants, because a one-person group has no split to make. Both fussy bits
        // were found on a device: "Add participant" ends up under the keyboard, so it is scrolled
        // to first, and the row is found by being empty, because the form re-sorts as you type.
        for (participant in listOf("Ana", "Bruno")) {
            rule.onNodeWithTag(TestTags.GROUP_FORM_ADD_PARTICIPANT_BUTTON)
                .performScrollTo()
                .performClick()
            rule.waitForIdle()
            // By name-of-what-is-in-it, not by position, see blankParticipantFieldTag.
            val field = rule.blankParticipantFieldTag()
            rule.onNodeWithTag(field).performScrollTo().performTextInput(participant)
            rule.waitForIdle()
        }

        rule.onNodeWithTag(TestTags.GROUP_FORM_SAVE_BUTTON).performClick()

        // Two waits, in this order, and both are load-bearing. The sheet dismisses itself only
        // once the server has answered, so its disappearance is the signal creation succeeded.
        // Waiting on the group's *name* first is worse than useless: the sheet still shows it in
        // its own field, so that wait is satisfied while the dashboard behind is still empty.
        rule.waitUntilGone(TestTags.GROUP_FORM_SHEET, timeoutMillis = 20_000)

        // This group's row, not any row: a leftover from an earlier run would satisfy a wait on
        // `isGroupRow()` the instant the sheet closed, and the click below would then race the
        // dashboard reloading.
        val row = isGroupRow() and hasText(name, substring = true)
        rule.waitUntilExists(row, timeoutMillis = 20_000)

        // Matched by name, not by being the only row: `adb install -r` keeps the app's data, so
        // anything left by a half-failed run is still on the dashboard and would be matched
        // first. Every assertion after that point would have been about the wrong group.
        rule.onNode(row).performClick()

        // Each tab is opened, not merely present: three of the four fetch lazily, so a tab that
        // threw on its first load would otherwise go unnoticed.
        rule.waitUntilExists(TestTags.GROUP_DETAIL_TAB_EXPENSES, timeoutMillis = 20_000)
        for (tag in listOf(
            TestTags.GROUP_DETAIL_TAB_BALANCES,
            TestTags.GROUP_DETAIL_TAB_TOTALS,
            TestTags.GROUP_DETAIL_TAB_INFORMATION,
        )) {
            rule.onNodeWithTag(tag).performClick()
            rule.waitForIdle()
        }

        // Information is the last one opened above, and it names the participants this test
        // typed, read back by a different procedure from the one that wrote them.
        rule.waitUntilTextExists("Ana")
        // `assertExists`, not `assertIsDisplayed`: on a short screen the second name is below
        // the fold, and what this checks is that it round-tripped, not that it is on screen.
        rule.onNodeWithText("Bruno").assertExists()
    }
}
