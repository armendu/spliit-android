package app.spliit.android.ui

/**
 * Every Compose `testTag` the app sets, in one place shared with Part 14's instrumented tests —
 * CLAUDE.md's testing table asks for exactly this, added in the same commit as what it tags.
 *
 * Every tag belongs on a **leaf** — the smallest independently addressable widget, such as one
 * `Text` or one [app.spliit.android.ui.design.Monogram] chip — and never on a `Row` / `Column` /
 * `Box` that exists purely to lay several of those out. A test wants to address "the amount in
 * this row" independently of "the row," so the container that arranges them must stay untagged.
 *
 * The iOS version of this file records why the boundary matters there: an identifier on a
 * container stamped every descendant with it and silently replaced the inner ones, so a
 * screen-level identifier erased every button beneath it. Compose's failure modes are different
 * but just as real — both of the following were hit while wiring this part's own components,
 * not assumed from documentation:
 *
 * 1. **`clearAndSetSemantics { }`** — used by [app.spliit.android.ui.design.Monogram] to hide its
 *    initials from screen readers, since the name it sits beside already says them — does not
 *    merely *merge* a node's semantics elsewhere; per its own KDoc it clears the semantics of
 *    "this modifier or its descendants" and substitutes exactly what its lambda sets. A
 *    `Modifier.testTag(...)` placed anywhere else on the same chain is discarded along with
 *    everything else, not merged away and still reachable — it is simply gone. The only way to
 *    keep a tag on such a node is to set it *inside* that lambda, via the `testTag` property on
 *    `SemanticsPropertyReceiver` (a different `testTag` from the `Modifier.testTag()` extension
 *    used everywhere else), which is what `Monogram` does.
 *
 * 2. **`mergeDescendants = true`** — set implicitly by `Modifier.clickable` and every M3 control
 *    built on it (`Button`, a clickable `Row`, …) — merges a subtree's semantics into one parent
 *    node for accessibility. Compose's test finders (`onNodeWithTag` included) query the *merged*
 *    tree by default, so a tag on a descendant of a clickable container is invisible to them
 *    unless the test opts in with `useUnmergedTree = true`. Part 11's expense row became such a
 *    container in Part 12, when tapping it began
 *    opening the expense, so Part 14 must reach for that flag rather than conclude the tag
 *    "isn't there."
 */
object TestTags {
    const val MONEY_AMOUNT = "money_amount"
    const val MONOGRAM = "monogram"
    const val DATE_HEADER = "date_header"
    const val EMPTY_STATE_TITLE = "empty_state_title"
    const val EMPTY_STATE_DESCRIPTION = "empty_state_description"

    // ---- groups list (Parts 10, 12) --------------------------------------------------------
    const val GROUPS_LIST_FAB = "groups_list_fab"
    const val GROUPS_LIST_RETRY_BUTTON = "groups_list_retry_button"
    const val GROUPS_LIST_EMPTY_CREATE_BUTTON = "groups_list_empty_create_button"
    const val GROUPS_LIST_EMPTY_ADD_BY_LINK_BUTTON = "groups_list_empty_add_by_link_button"
    const val GROUPS_LIST_SKELETON = "groups_list_skeleton"

    // The "+" menu and what it offers, plus the settings entry beside it (Part 12).
    const val GROUPS_LIST_ADD_MENU_BUTTON = "groups_list_add_menu_button"
    const val GROUPS_LIST_MENU_CREATE_GROUP = "groups_list_menu_create_group"
    const val GROUPS_LIST_MENU_ADD_BY_LINK = "groups_list_menu_add_by_link"
    const val GROUPS_LIST_SETTINGS_BUTTON = "groups_list_settings_button"
    const val GROUPS_LIST_ARCHIVED_TOGGLE = "groups_list_archived_toggle"
    const val GROUPS_LIST_UNREACHABLE_NOTE = "groups_list_unreachable_note"

    fun groupsListRow(groupId: String) = "groups_list_row_$groupId"
    fun groupsListRowName(groupId: String) = "groups_list_row_name_$groupId"
    fun groupsListRowParticipants(groupId: String) = "groups_list_row_participants_$groupId"
    fun groupsListRowCreatedDate(groupId: String) = "groups_list_row_created_$groupId"
    // No `groupsListRowInstance`: the row no longer names the server. It wrapped onto a second
    // line for the one fact most people never need — the group's own Information tab states it,
    // and the create sheet's Advanced section is where it is chosen.
    fun groupsListRowMenuButton(groupId: String) = "groups_list_row_menu_$groupId"
    fun groupsListRowStar(groupId: String) = "groups_list_row_star_$groupId"
    fun groupsListRowArchive(groupId: String) = "groups_list_row_archive_$groupId"
    fun groupsListRowRemove(groupId: String) = "groups_list_row_remove_$groupId"
    fun groupsListSectionHeader(label: String) = "groups_list_section_${label.lowercase()}"

    // ---- add group by link, the sheet (Parts 10, 12) ---------------------------------------
    const val ADD_GROUP_URL_FIELD = "add_group_url_field"
    const val ADD_GROUP_URL_ERROR = "add_group_url_error"
    const val ADD_GROUP_URL_SUBMIT = "add_group_url_submit"
    const val ADD_GROUP_URL_CANCEL = "add_group_url_cancel"

    // ---- group form (Part 10) --------------------------------------------------------------
    const val GROUP_FORM_NAME_FIELD = "group_form_name_field"
    const val GROUP_FORM_NAME_ERROR = "group_form_name_error"
    const val GROUP_FORM_CURRENCY_ROW = "group_form_currency_row"
    const val GROUP_FORM_CURRENCY_SEARCH_FIELD = "group_form_currency_search_field"
    const val GROUP_FORM_CUSTOM_SYMBOL_FIELD = "group_form_custom_symbol_field"
    const val GROUP_FORM_INFORMATION_FIELD = "group_form_information_field"

    /**
     * The disclosure that hides the server address when *creating* a group. Absent when editing
     * one — a group cannot move servers, so that form shows the address outright.
     */
    const val GROUP_FORM_ADVANCED_TOGGLE = "group_form_advanced_toggle"
    const val GROUP_FORM_SHEET = "group_form_sheet"
    const val GROUP_FORM_SERVER_FIELD = "group_form_server_field"
    const val GROUP_FORM_SERVER_ERROR = "group_form_server_error"
    const val GROUP_FORM_ADD_PARTICIPANT_BUTTON = "group_form_add_participant_button"
    const val GROUP_FORM_PARTICIPANTS_ERROR = "group_form_participants_error"
    const val GROUP_FORM_SAVE_BUTTON = "group_form_save_button"
    const val GROUP_FORM_CANCEL_BUTTON = "group_form_cancel_button"

    fun groupFormParticipantField(index: Int) = "group_form_participant_field_$index"
    fun groupFormParticipantError(index: Int) = "group_form_participant_error_$index"
    fun groupFormParticipantRemove(index: Int) = "group_form_participant_remove_$index"

    // ---- group detail (Part 11) ------------------------------------------------------------
    const val GROUP_DETAIL_BACK_BUTTON = "group_detail_back_button"
    const val GROUP_DETAIL_ADD_EXPENSE_FAB = "group_detail_add_expense_fab"

    /**
     * The same action as [GROUP_DETAIL_ADD_EXPENSE_FAB], as a top-app-bar icon.
     *
     * Only one of the two is on screen at a time — see
     * [app.spliit.android.feature.group.GroupDetailLayout]: the bottom-bar layout has no FAB,
     * because M3 dropped the docked-FAB pattern and one floating over a navigation bar covers
     * it. A test that wants "add an expense" has to ask for whichever the current layout draws.
     */
    const val GROUP_DETAIL_ADD_EXPENSE_ACTION = "group_detail_add_expense_action"
    const val GROUP_DETAIL_TAB_EXPENSES = "group_detail_tab_expenses"
    const val GROUP_DETAIL_TAB_BALANCES = "group_detail_tab_balances"
    const val GROUP_DETAIL_TAB_TOTALS = "group_detail_tab_totals"
    const val GROUP_DETAIL_TAB_INFORMATION = "group_detail_tab_information"
    const val GROUP_DETAIL_BOTTOM_BAR = "group_detail_bottom_bar"

    // Search — an app-bar action that expands into a field, not a fifth tab.
    const val GROUP_DETAIL_SEARCH_BUTTON = "group_detail_search_button"
    const val GROUP_DETAIL_SEARCH_FIELD = "group_detail_search_field"
    const val GROUP_DETAIL_SEARCH_CLOSE = "group_detail_search_close"
    const val SEARCH_RESULTS = "search_results"
    const val SEARCH_LOADING = "search_loading"

    // The overflow: the two actions that act on the group rather than on what is in it.
    const val GROUP_DETAIL_MENU_BUTTON = "group_detail_menu_button"
    const val GROUP_DETAIL_MENU_EDIT_GROUP = "group_detail_menu_edit_group"
    const val GROUP_DETAIL_MENU_SHARE_GROUP = "group_detail_menu_share_group"

    // ---- totals tab -------------------------------------------------------------------------
    const val TOTALS_SKELETON = "totals_skeleton"
    const val TOTALS_GROUP_AMOUNT = "totals_group_amount"
    const val TOTALS_YOUR_SPENDING_AMOUNT = "totals_your_spending_amount"
    const val TOTALS_YOUR_SHARE_AMOUNT = "totals_your_share_amount"
    const val TOTALS_YOU_BUTTON = "totals_you_button"

    fun totalsCategoryName(categoryId: Int) = "totals_category_name_$categoryId"
    fun totalsCategoryAmount(categoryId: Int) = "totals_category_amount_$categoryId"

    // ---- information tab --------------------------------------------------------------------
    const val INFORMATION_SKELETON = "information_skeleton"
    const val INFORMATION_NOTE = "information_note"
    const val INFORMATION_NOTE_EMPTY = "information_note_empty"
    const val INFORMATION_EDIT_NOTE_BUTTON = "information_edit_note_button"
    const val INFORMATION_CURRENCY = "information_currency"
    const val INFORMATION_CREATED = "information_created"
    const val INFORMATION_SERVER = "information_server"
    const val INFORMATION_ACTIVITY_BUTTON = "information_activity_button"
    const val INFORMATION_YOU_BUTTON = "information_you_button"

    fun informationParticipant(participantId: String) = "information_participant_$participantId"

    // ---- the activity log, a sheet over the group screen --------------------------------------
    const val ACTIVITY_LOG_SHEET = "activity_log_sheet"
    const val ACTIVITY_LOG_SKELETON = "activity_log_skeleton"
    const val ACTIVITY_LOG_RETRY_BUTTON = "activity_log_retry_button"
    const val ACTIVITY_LOG_LOAD_MORE = "activity_log_load_more"

    fun activityRow(activityId: String) = "activity_row_$activityId"
    fun activityRowSummary(activityId: String) = "activity_row_summary_$activityId"
    const val GROUP_DETAIL_RETRY_BUTTON = "group_detail_retry_button"
    const val GROUP_DETAIL_EXPENSES_SKELETON = "group_detail_expenses_skeleton"
    const val GROUP_DETAIL_LOAD_MORE = "group_detail_load_more"
    const val GROUP_DETAIL_YOU_BUTTON = "group_detail_you_button"
    const val GROUP_DETAIL_YOU_AMOUNT = "group_detail_you_amount"
    const val GROUP_DETAIL_SETTLED_LABEL = "group_detail_settled_label"
    const val ACTIVE_USER_PICKER_SHEET = "active_user_picker_sheet"
    const val ACTIVE_USER_PICKER_CLEAR = "active_user_picker_clear"

    fun expenseRow(expenseId: String) = "expense_row_$expenseId"
    fun expenseRowTitle(expenseId: String) = "expense_row_title_$expenseId"
    fun expenseRowAmount(expenseId: String) = "expense_row_amount_$expenseId"
    fun expenseRowPaidBy(expenseId: String) = "expense_row_paid_by_$expenseId"

    /** The second metadata line — who the expense was paid *for*. Absent when the server named
     *  nobody, so a test that asserts on it must allow for a row that has none. */
    fun expenseRowPaidFor(expenseId: String) = "expense_row_paid_for_$expenseId"

    // ---- expense form (Part 12) ------------------------------------------------------------
    const val EXPENSE_FORM_CLOSE_BUTTON = "expense_form_close_button"
    const val EXPENSE_FORM_SAVE_BUTTON = "expense_form_save_button"
    const val EXPENSE_FORM_AMOUNT_FIELD = "expense_form_amount_field"
    const val EXPENSE_FORM_CONVERTED_TOTAL = "expense_form_converted_total"
    const val EXPENSE_FORM_TITLE_FIELD = "expense_form_title_field"
    const val EXPENSE_FORM_DATE_BUTTON = "expense_form_date_button"
    const val EXPENSE_FORM_NOTES_FIELD = "expense_form_notes_field"
    const val EXPENSE_FORM_REIMBURSEMENT_TOGGLE = "expense_form_reimbursement_toggle"
    const val EXPENSE_FORM_RECURRENCE_BUTTON = "expense_form_recurrence_button"
    const val EXPENSE_FORM_SAVE_SPLIT_TOGGLE = "expense_form_save_split_toggle"
    const val EXPENSE_FORM_SELECT_ALL_BUTTON = "expense_form_select_all_button"
    const val EXPENSE_FORM_DELETE_BUTTON = "expense_form_delete_button"
    const val EXPENSE_FORM_RETRY_BUTTON = "expense_form_retry_button"
    const val EXPENSE_FORM_SKELETON = "expense_form_skeleton"
    const val EXPENSE_FORM_REMAINDER = "expense_form_remainder"
    const val EXPENSE_FORM_CURRENCY_BUTTON = "expense_form_currency_button"
    const val EXPENSE_FORM_ORIGINAL_AMOUNT_FIELD = "expense_form_original_amount_field"
    const val EXPENSE_FORM_CONVERSION_RATE_FIELD = "expense_form_conversion_rate_field"
    const val EXPENSE_FORM_CURRENCY_SEARCH_FIELD = "expense_form_currency_search_field"
    const val EXPENSE_FORM_DISCARD_DIALOG = "expense_form_discard_dialog"
    // Editing is a sheet over the group screen, not a destination — see ExpenseEditSheet.
    const val EXPENSE_EDIT_SHEET = "expense_edit_sheet"
    const val EXPENSE_FORM_DISCARD_CONFIRM = "expense_form_discard_confirm"

    /** One per [app.spliit.core.ExpenseFormDraft.Field], so a test can name the box it expects
     *  to be labelled rather than searching the screen for red text. */
    fun expenseFormError(field: String) = "expense_form_error_${field.lowercase()}"

    fun expenseFormCategoryChip(categoryId: Int) = "expense_form_category_chip_$categoryId"
    fun expenseFormPaidByChip(participantId: String) = "expense_form_paid_by_chip_$participantId"
    fun expenseFormSplitModeTab(mode: String) = "expense_form_split_mode_${mode.lowercase()}"
    fun expenseFormParticipantToggle(participantId: String) = "expense_form_participant_$participantId"
    fun expenseFormParticipantValue(participantId: String) = "expense_form_participant_value_$participantId"
    fun expenseFormParticipantAmount(participantId: String) = "expense_form_participant_amount_$participantId"
    fun expenseFormCurrencyOption(code: String) = "expense_form_currency_option_$code"

    fun groupFormCurrencyOption(code: String) = "group_form_currency_option_$code"

    fun balanceRowAmount(participantId: String) = "balance_row_amount_$participantId"
    fun reimbursementRow(index: Int) = "reimbursement_row_$index"
    fun activeUserPickerOption(participantId: String) = "active_user_picker_option_$participantId"

    // ---- settings (Part 13) ------------------------------------------------------------------
    const val SETTINGS_BACK_BUTTON = "settings_back_button"
    const val SETTINGS_INSTANCE_FIELD = "settings_instance_field"
    const val SETTINGS_INSTANCE_ERROR = "settings_instance_error"
    const val SETTINGS_INSTANCE_SAVE_BUTTON = "settings_instance_save_button"
    const val SETTINGS_INSTANCE_RESET_BUTTON = "settings_instance_reset_button"
    const val SETTINGS_ABOUT_VISIT_LINK = "settings_about_visit_link"
    const val SETTINGS_ABOUT_GITHUB_LINK = "settings_about_github_link"
    const val SETTINGS_FEEDBACK_REPORT_LINK = "settings_feedback_report_link"
    const val SETTINGS_VERSION_VALUE = "settings_version_value"

    fun settingsThemeOption(mode: String) = "settings_theme_option_${mode.lowercase()}"
}
