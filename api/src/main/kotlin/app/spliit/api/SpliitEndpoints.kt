package app.spliit.api

import kotlinx.serialization.Serializable

public object SpliitEndpoints {
    @Serializable
    public data class GroupsListInput(public val groupIds: List<String>)

    @Serializable
    public data class GroupsListResponse(public val groups: List<GroupSummary>)

    // Needs an input object. Unknown IDs are silently absent (a server-side deletion).
    public fun groupsList(groupIds: List<String>): TrpcProcedure<GroupsListInput, GroupsListResponse> =
        TrpcProcedure.query(
            "groups.list",
            GroupsListInput(groupIds),
            GroupsListInput.serializer(),
            GroupsListResponse.serializer(),
        )

    @Serializable
    public data class GroupIdInput(public val groupId: String)

    @Serializable
    public data class GroupResponse(public val group: Group?)

    public fun groupsGet(groupId: String): TrpcProcedure<GroupIdInput, GroupResponse> =
        TrpcProcedure.query(
            "groups.get",
            GroupIdInput(groupId),
            GroupIdInput.serializer(),
            GroupResponse.serializer(),
        )

    @Serializable
    public data class GroupDetailsResponse(
        public val group: Group,
        public val participantsWithExpenses: List<String>,
    )

    public fun groupsGetDetails(groupId: String): TrpcProcedure<GroupIdInput, GroupDetailsResponse> =
        TrpcProcedure.query(
            "groups.getDetails",
            GroupIdInput(groupId),
            GroupIdInput.serializer(),
            GroupDetailsResponse.serializer(),
        )

    @Serializable
    public data class CreateGroupInput(public val groupFormValues: GroupFormValues)

    @Serializable
    public data class CreateGroupResponse(public val groupId: String)

    public fun groupsCreate(values: GroupFormValues): TrpcProcedure<CreateGroupInput, CreateGroupResponse> =
        TrpcProcedure.mutation(
            "groups.create",
            CreateGroupInput(values),
            CreateGroupInput.serializer(),
            CreateGroupResponse.serializer(),
        )

    @Serializable
    public data class UpdateGroupInput(
        public val groupId: String,
        public val groupFormValues: GroupFormValues,
        public val participantId: String? = null,
    )

    // participantId is the only way the activity log can name who did it.
    public fun groupsUpdate(
        groupId: String,
        values: GroupFormValues,
        participantId: String? = null,
    ): TrpcProcedure<UpdateGroupInput, TrpcVoid> =
        TrpcProcedure.mutation(
            "groups.update",
            UpdateGroupInput(groupId, values, participantId),
            UpdateGroupInput.serializer(),
            TrpcVoid.serializer(),
        )

    @Serializable
    public data class ExpensesListInput(
        public val groupId: String,
        public val cursor: Int? = null,
        public val limit: Int? = null,
        public val filter: String? = null,
    )

    @Serializable
    public data class ExpensesListResponse(
        public val expenses: List<ExpenseListItem>,
        public val hasMore: Boolean,
        public val nextCursor: Int,
    )

    public fun expensesList(
        groupId: String,
        cursor: Int? = null,
        limit: Int? = null,
        filter: String? = null,
    ): TrpcProcedure<ExpensesListInput, ExpensesListResponse> =
        TrpcProcedure.query(
            "groups.expenses.list",
            ExpensesListInput(groupId, cursor, limit, filter),
            ExpensesListInput.serializer(),
            ExpensesListResponse.serializer(),
        )

    @Serializable
    public data class ExpenseIdInput(public val groupId: String, public val expenseId: String)

    @Serializable
    public data class ExpenseResponse(public val expense: ExpenseDetails)

    public fun expensesGet(groupId: String, expenseId: String): TrpcProcedure<ExpenseIdInput, ExpenseResponse> =
        TrpcProcedure.query(
            "groups.expenses.get",
            ExpenseIdInput(groupId, expenseId),
            ExpenseIdInput.serializer(),
            ExpenseResponse.serializer(),
        )

    @Serializable
    public data class CreateExpenseInput(
        public val groupId: String,
        public val expenseFormValues: ExpenseFormValues,
        public val participantId: String? = null,
    )

    @Serializable
    public data class ExpenseIdResponse(public val expenseId: String)

    public fun expensesCreate(
        groupId: String,
        values: ExpenseFormValues,
        participantId: String? = null,
    ): TrpcProcedure<CreateExpenseInput, ExpenseIdResponse> =
        TrpcProcedure.mutation(
            "groups.expenses.create",
            CreateExpenseInput(groupId, values, participantId),
            CreateExpenseInput.serializer(),
            ExpenseIdResponse.serializer(),
        )

    @Serializable
    public data class UpdateExpenseInput(
        public val groupId: String,
        public val expenseId: String,
        public val expenseFormValues: ExpenseFormValues,
        public val participantId: String? = null,
    )

    public fun expensesUpdate(
        groupId: String,
        expenseId: String,
        values: ExpenseFormValues,
        participantId: String? = null,
    ): TrpcProcedure<UpdateExpenseInput, ExpenseIdResponse> =
        TrpcProcedure.mutation(
            "groups.expenses.update",
            UpdateExpenseInput(groupId, expenseId, values, participantId),
            UpdateExpenseInput.serializer(),
            ExpenseIdResponse.serializer(),
        )

    @Serializable
    public data class DeleteExpenseInput(
        public val groupId: String,
        public val expenseId: String,
        public val participantId: String? = null,
    )

    public fun expensesDelete(
        groupId: String,
        expenseId: String,
        participantId: String? = null,
    ): TrpcProcedure<DeleteExpenseInput, TrpcVoid> =
        TrpcProcedure.mutation(
            "groups.expenses.delete",
            DeleteExpenseInput(groupId, expenseId, participantId),
            DeleteExpenseInput.serializer(),
            TrpcVoid.serializer(),
        )

    @Serializable
    public data class BalancesResponse(
        // A participant with no activity is absent, not zero.
        public val balances: Map<String, Balance>,
        public val reimbursements: List<Reimbursement>,
    )

    public fun balancesList(groupId: String): TrpcProcedure<GroupIdInput, BalancesResponse> =
        TrpcProcedure.query(
            "groups.balances.list",
            GroupIdInput(groupId),
            GroupIdInput.serializer(),
            BalancesResponse.serializer(),
        )

    // Older instances only have groups.stats.get. Call through TrpcClient.groupStats, which tries both.

    @Serializable
    public data class GroupStatsInput(
        public val groupId: String,
        public val participantId: String? = null,
    )

    @Serializable
    public data class GroupStatsResponse(
        public val totalGroupSpendings: Int,
        public val totalParticipantSpendings: Int? = null,
        // Not an integer on older instances (e.g. 1416.67). Round for display; never type as Int.
        public val totalParticipantShare: Double? = null,
        public val summary: StatsSummary? = null,
        public val categories: List<CategoryTotal>? = null,
    )

    // firstDate and lastDate are plain YYYY-MM-DD strings, not superjson Dates.
    @Serializable
    public data class StatsSummary(
        public val expenseCount: Int? = null,
        public val totalSpending: Int? = null,
        public val averageExpense: Int? = null,
        public val largestExpense: LargestExpense? = null,
        public val firstDate: String? = null,
        public val lastDate: String? = null,
    )

    @Serializable
    public data class LargestExpense(
        public val title: String? = null,
        public val amount: Int? = null,
    )

    @Serializable
    public data class CategoryTotal(
        public val categoryId: Int,
        public val grouping: String? = null,
        public val name: String? = null,
        public val total: Int,
    )

    public fun statsOverview(
        groupId: String,
        participantId: String? = null,
    ): TrpcProcedure<GroupStatsInput, GroupStatsResponse> =
        TrpcProcedure.query(
            "groups.stats.overview",
            GroupStatsInput(groupId, participantId),
            GroupStatsInput.serializer(),
            GroupStatsResponse.serializer(),
        )

    public fun statsGet(
        groupId: String,
        participantId: String? = null,
    ): TrpcProcedure<GroupStatsInput, GroupStatsResponse> =
        TrpcProcedure.query(
            "groups.stats.get",
            GroupStatsInput(groupId, participantId),
            GroupStatsInput.serializer(),
            GroupStatsResponse.serializer(),
        )

    @Serializable
    public data class ActivitiesListInput(
        public val groupId: String,
        public val cursor: Int? = null,
        public val limit: Int? = null,
    )

    @Serializable
    public data class ActivitiesListResponse(
        public val activities: List<Activity>,
        public val hasMore: Boolean,
        public val nextCursor: Int,
    )

    public fun activitiesList(
        groupId: String,
        cursor: Int? = null,
        limit: Int? = null,
    ): TrpcProcedure<ActivitiesListInput, ActivitiesListResponse> =
        TrpcProcedure.query(
            "groups.activities.list",
            ActivitiesListInput(groupId, cursor, limit),
            ActivitiesListInput.serializer(),
            ActivitiesListResponse.serializer(),
        )

    @Serializable
    public data class CategoriesResponse(public val categories: List<ExpenseCategory>)

    public fun categoriesList(): TrpcProcedure<NoInput, CategoriesResponse> =
        TrpcProcedure.query("categories.list", CategoriesResponse.serializer())
}

// Tries groups.stats.overview, then groups.stats.get; NOT_FOUND from both propagates.
public suspend fun TrpcClient.groupStats(
    groupId: String,
    participantId: String? = null,
): SpliitEndpoints.GroupStatsResponse =
    try {
        call(SpliitEndpoints.statsOverview(groupId, participantId))
    } catch (error: TrpcServerError) {
        if (!error.isUnknownProcedure) throw error
        call(SpliitEndpoints.statsGet(groupId, participantId))
    }
