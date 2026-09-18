package app.spliit.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.spliit.android.AppSettingsHolder
import app.spliit.android.data.DataStoreRecentGroupsStore
import app.spliit.android.data.DataStoreSettingsStore
import app.spliit.android.feature.expense.ExpenseFormMode
import app.spliit.android.feature.expense.ExpenseFormScreen
import app.spliit.android.feature.expense.ExpenseFormViewModel
import app.spliit.android.feature.group.GroupDetailScreen
import app.spliit.android.feature.group.GroupDetailViewModel
import app.spliit.android.feature.groups.AddGroupByUrlViewModel
import app.spliit.android.feature.groups.GroupFormMode
import app.spliit.android.feature.groups.GroupFormScreen
import app.spliit.android.feature.groups.GroupFormViewModel
import app.spliit.android.feature.groups.GroupsListScreen
import app.spliit.android.feature.groups.GroupsListViewModel
import app.spliit.android.feature.settings.SettingsScreen
import app.spliit.android.feature.settings.SettingsViewModel
import app.spliit.android.ui.design.EmptyState
import app.spliit.core.RecentGroupsStore

/**
 * The cycle-1 route table — spec §2's screen list, one route each. Part 10 fills in the groups
 * list and the group form; Part 12 turns the list into the dashboard; Part 13 replaces the
 * settings stub with the real screen. The currency and active-user pickers below remain stubs.
 */
object Routes {
    const val GROUPS = "groups"
    const val GROUP_FORM = "groups/form"
    const val GROUP_DETAIL = "groups/{groupId}"

    // Three ways into one screen — iOS's `createExpense`, `editExpense(id)` and
    // `settle(reimbursement)`. Separate patterns rather than one with optional arguments: a
    // settle-up carries three values a create has none of, and a route that can be missing most
    // of its arguments is a route whose mode is decided by which of them happen to be null.

    //
    // There is no edit route. Editing an expense is a sheet drawn over the group screen — see
    // ExpenseEditSheet — precisely so that the group screen is *not* dropped and rebuilt, which
    // is what made every edit re-read the whole expense list. A destination cannot do that.
    const val EXPENSE_FORM_CREATE = "groups/{groupId}/expenses/new"
    const val EXPENSE_FORM_SETTLE = "groups/{groupId}/settle/{from}/{to}/{amount}"
    const val SETTINGS = "settings"
    const val CURRENCY_PICKER = "settings/currency"
    const val ACTIVE_USER_PICKER = "groups/{groupId}/active-user"

    fun groupDetail(groupId: String) = "groups/$groupId"

    fun expenseFormCreate(groupId: String) = "groups/$groupId/expenses/new"

    /** @param amountMinorUnits the suggested payment, in the group's minor units. */
    fun expenseFormSettle(groupId: String, from: String, to: String, amountMinorUnits: Long) =
        "groups/$groupId/settle/${pathSegment(from)}/${pathSegment(to)}/$amountMinorUnits"

    /**
     * An ID as one segment of a route, escaped to the unreserved set.
     *
     * Spliit's IDs are nanoids, whose alphabet is already `A-Za-z0-9_-` — so in practice nothing
     * here is ever escaped. It is written anyway because *relying* on that is a decision nobody
     * would find again: an ID with a `/` in it, from an instance that generates them differently,
     * would otherwise split into two segments and match no route at all.
     *
     * Hand-rolled rather than `android.net.Uri.encode`, which is a framework method: it throws
     * "not mocked" under a JVM unit test, and the route table is exactly the kind of thing worth
     * testing without a device.
     */
    private fun pathSegment(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && char.code < 128 || char in "-._~") {
                append(char)
            } else {
                append('%').append("%02X".format(byte))
            }
        }
    }
}

@Composable
fun SpliitNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val recentGroupsStore: RecentGroupsStore = remember { DataStoreRecentGroupsStore(context) }

    NavHost(navController = navController, startDestination = Routes.GROUPS, modifier = modifier) {
        composable(Routes.GROUPS) {
            val viewModel: GroupsListViewModel = viewModel(
                factory = remember {
                    viewModelFactory { initializer { GroupsListViewModel(recentGroupsStore) } }
                },
            )
            // Adding by link is a sheet over this screen rather than a destination of its own —
            // see AddGroupByUrlSheet — so its ViewModel is scoped to this entry alongside the
            // dashboard's, and reset each time the sheet opens.
            val addGroupViewModel: AddGroupByUrlViewModel = viewModel(
                factory = remember {
                    viewModelFactory { initializer { AddGroupByUrlViewModel(recentGroupsStore) } }
                },
            )
            GroupsListScreen(
                viewModel = viewModel,
                addGroupViewModel = addGroupViewModel,
                onGroupClick = { navController.navigate(Routes.groupDetail(it.groupId)) },
                onCreateGroup = { navController.navigate(Routes.GROUP_FORM) },
                // The settings screen itself is Part 13; this route is already a wired stub.
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.GROUP_FORM) {
            val viewModel: GroupFormViewModel = viewModel(
                factory = remember {
                    viewModelFactory {
                        initializer {
                            GroupFormViewModel(
                                mode = GroupFormMode.CREATE,
                                groupId = null,
                                // The *current* default — Settings can have changed it since the
                                // app launched, and this factory re-runs fresh every time "Create
                                // group" is navigated to (unlike the groups list's own long-lived
                                // ViewModel), so reading the holder here is always up to date.
                                instanceBaseUrl = AppSettingsHolder.defaultInstanceBaseUrl,
                                recentGroupsStore = recentGroupsStore,
                            )
                        }
                    }
                },
            )
            GroupFormScreen(
                viewModel = viewModel,
                // Back to the list, where the group just created now shows up.
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                // The real picker is Part 13; this route is already a wired stub (below).
                onPickCurrency = { navController.navigate(Routes.CURRENCY_PICKER) },
            )
        }

        composable(Routes.GROUP_DETAIL) { backStackEntry ->
            // The route pattern's own "{groupId}" segment is what populates this — Navigation
            // Compose parses a path placeholder as a required String argument with no separate
            // declaration needed, unlike a typed or optional one.
            val groupId = checkNotNull(backStackEntry.arguments?.getString("groupId"))
            val viewModel: GroupDetailViewModel = viewModel(
                key = groupId,
                factory = remember(groupId) {
                    viewModelFactory { initializer { GroupDetailViewModel(groupId, recentGroupsStore) } }
                },
            )
            GroupDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddExpense = { navController.navigate(Routes.expenseFormCreate(groupId)) },
                // Editing is a sheet over this screen, not a destination — hence a ViewModel
                // handed in rather than a route navigated to. Scoped to this nav entry and keyed
                // on the expense, so it outlives the sheet: the undo offered after a delete is
                // still its to make once the sheet has gone.
                editViewModelFor = { expenseId ->
                    viewModel(
                        key = "edit/$groupId/$expenseId",
                        factory = remember(groupId, expenseId) {
                            viewModelFactory {
                                initializer {
                                    ExpenseFormViewModel(
                                        groupId = groupId,
                                        mode = ExpenseFormMode.Edit(expenseId),
                                        recentGroupsStore = recentGroupsStore,
                                    )
                                }
                            }
                        },
                    )
                },
                onSettle = { reimbursement ->
                    navController.navigate(
                        Routes.expenseFormSettle(
                            groupId = groupId,
                            from = reimbursement.from,
                            to = reimbursement.to,
                            amountMinorUnits = reimbursement.amount.toLong(),
                        ),
                    )
                },
            )
        }

        composable(Routes.EXPENSE_FORM_CREATE) { backStackEntry ->
            val groupId = checkNotNull(backStackEntry.arguments?.getString("groupId"))
            ExpenseForm(navController, recentGroupsStore, groupId, ExpenseFormMode.Create)
        }

        composable(Routes.EXPENSE_FORM_SETTLE) { backStackEntry ->
            val arguments = checkNotNull(backStackEntry.arguments)
            val groupId = checkNotNull(arguments.getString("groupId"))
            ExpenseForm(
                navController = navController,
                recentGroupsStore = recentGroupsStore,
                groupId = groupId,
                mode = ExpenseFormMode.Settle(
                    fromParticipantId = checkNotNull(arguments.getString("from")),
                    toParticipantId = checkNotNull(arguments.getString("to")),
                    // Minor units, so a Long — and parsed rather than declared a NavType, since
                    // navigation's own LongType would need the argument declared separately from
                    // the pattern for no gain over one toLongOrNull.
                    amountMinorUnits = arguments.getString("amount")?.toLongOrNull() ?: 0L,
                ),
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val viewModel: SettingsViewModel = viewModel(
                factory = remember {
                    viewModelFactory {
                        initializer { SettingsViewModel(DataStoreSettingsStore(context)) }
                    }
                },
            )
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.CURRENCY_PICKER) {
            Placeholder(icon = "CU", title = "Currency", part = "Part 13")
        }
        composable(Routes.ACTIVE_USER_PICKER) {
            Placeholder(icon = "AU", title = "Who are you?", part = "Part 13")
        }
    }
}

/**
 * The expense form, however it was reached.
 *
 * The ViewModel is keyed on the mode as well as the group: the same NavHost entry can be a
 * create one moment and an edit the next, and a ViewModel keyed on the group alone would be
 * handed back holding the previous expense.
 */
@Composable
private fun ExpenseForm(
    navController: NavHostController,
    recentGroupsStore: RecentGroupsStore,
    groupId: String,
    mode: ExpenseFormMode,
) {
    val viewModel: ExpenseFormViewModel = viewModel(
        key = "$groupId/$mode",
        factory = remember(groupId, mode) {
            viewModelFactory {
                initializer { ExpenseFormViewModel(groupId, mode, recentGroupsStore) }
            }
        },
    )
    // Back to the group, which reloads on its way into composition — so a saved, deleted or
    // undone expense is already reflected in both the list and the balances.
    ExpenseFormScreen(viewModel = viewModel, onClose = { navController.popBackStack() })
}

@Composable
private fun Placeholder(icon: String, title: String, part: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(icon = icon, title = title, description = "Arrives in $part.")
    }
}
