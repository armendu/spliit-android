package app.spliit.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.spliit.core.RecentGroupsStore

object Routes {
    const val GROUPS = "groups"

    const val GROUP_EDIT = "groups/{groupId}/edit"
    const val GROUP_DETAIL = "groups/{groupId}"

    const val EXPENSE_FORM_CREATE = "groups/{groupId}/expenses/new"
    const val EXPENSE_FORM_SETTLE = "groups/{groupId}/settle/{from}/{to}/{amount}"
    const val SETTINGS = "settings"

    fun groupDetail(groupId: String) = "groups/$groupId"

    fun groupEdit(groupId: String) = "groups/$groupId/edit"

    fun expenseFormCreate(groupId: String) = "groups/$groupId/expenses/new"

    const val RESULT_EXPENSES_CHANGED = "result_expenses_changed"
    const val RESULT_GROUP_CHANGED = "result_group_changed"

    fun expenseFormSettle(groupId: String, from: String, to: String, amountMinorUnits: Long) =
        "groups/$groupId/settle/${pathSegment(from)}/${pathSegment(to)}/$amountMinorUnits"

    // Hand-rolled: Uri.encode throws "not mocked" in JVM tests.
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
            val addGroupViewModel: AddGroupByUrlViewModel = viewModel(
                factory = remember {
                    viewModelFactory { initializer { AddGroupByUrlViewModel(recentGroupsStore) } }
                },
            )
            val createGroupViewModel: GroupFormViewModel = viewModel(
                factory = remember {
                    viewModelFactory {
                        initializer {
                            GroupFormViewModel(
                                mode = GroupFormMode.CREATE,
                                groupId = null,
                                instanceBaseUrl = AppSettingsHolder.defaultInstanceBaseUrl,
                                recentGroupsStore = recentGroupsStore,
                            )
                        }
                    }
                },
            )
            GroupsListScreen(
                viewModel = viewModel,
                addGroupViewModel = addGroupViewModel,
                createGroupViewModel = createGroupViewModel,
                onGroupClick = { navController.navigate(Routes.groupDetail(it.groupId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.GROUP_EDIT) { backStackEntry ->
            val groupId = checkNotNull(backStackEntry.arguments?.getString("groupId"))
            val viewModel: GroupFormViewModel = viewModel(
                key = groupId,
                factory = remember(groupId) {
                    viewModelFactory {
                        initializer {
                            GroupFormViewModel(
                                mode = GroupFormMode.EDIT,
                                groupId = groupId,
                                instanceBaseUrl = AppSettingsHolder.defaultInstanceBaseUrl,
                                recentGroupsStore = recentGroupsStore,
                            )
                        }
                    }
                },
            )
            GroupFormScreen(
                viewModel = viewModel,
                onSaved = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(Routes.RESULT_GROUP_CHANGED, true)
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(Routes.GROUP_DETAIL) { backStackEntry ->
            val groupId = checkNotNull(backStackEntry.arguments?.getString("groupId"))
            val viewModel: GroupDetailViewModel = viewModel(
                key = groupId,
                factory = remember(groupId) {
                    viewModelFactory { initializer { GroupDetailViewModel(groupId, recentGroupsStore) } }
                },
            )
            val savedStateHandle = backStackEntry.savedStateHandle
            val expensesChanged by savedStateHandle
                .getStateFlow(Routes.RESULT_EXPENSES_CHANGED, false)
                .collectAsState()
            LaunchedEffect(expensesChanged) {
                if (!expensesChanged) return@LaunchedEffect
                savedStateHandle[Routes.RESULT_EXPENSES_CHANGED] = false
                viewModel.reloadAfterExpenseChange()
            }
            val groupChanged by savedStateHandle
                .getStateFlow(Routes.RESULT_GROUP_CHANGED, false)
                .collectAsState()
            LaunchedEffect(groupChanged) {
                if (!groupChanged) return@LaunchedEffect
                savedStateHandle[Routes.RESULT_GROUP_CHANGED] = false
                viewModel.groupEdited()
            }

            GroupDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddExpense = { navController.navigate(Routes.expenseFormCreate(groupId)) },
                onEditGroup = { navController.navigate(Routes.groupEdit(groupId)) },
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
    }
}

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
    ExpenseFormScreen(
        viewModel = viewModel,
        onClose = {
            val current = viewModel.state.value
            if (current.savedExpenseId != null || current.deleted != null) {
                navController.previousBackStackEntry
                    ?.savedStateHandle?.set(Routes.RESULT_EXPENSES_CHANGED, true)
            }
            navController.popBackStack()
        },
    )
}
