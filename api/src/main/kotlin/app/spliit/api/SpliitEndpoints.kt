package app.spliit.api

import kotlinx.serialization.Serializable

/**
 * The Spliit router, as [TrpcProcedure]s ready for [TrpcClient.call].
 *
 * ```kotlin
 * val response = client.call(SpliitEndpoints.groupsGet(groupId = "abc"))
 * ```
 *
 * Response wrappers live here; `Models.kt` owns what is inside them.
 */
public object SpliitEndpoints {

    // ---- Groups --------------------------------------------------------------------------

    @Serializable
    public data class GroupsListInput(public val groupIds: List<String>)

    @Serializable
    public data class GroupsListResponse(public val groups: List<GroupSummary>)

    /**
     * Fetches the groups behind a set of IDs. Not a no-input procedure: the server answers
     * `BAD_REQUEST` without an input object. Unknown IDs are silently absent, which is how a
     * server-side deletion shows up.
     */
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

    /** Null [GroupResponse.group] means no group has this ID, not an error. */
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
        /** Participants who appear on at least one expense, and so cannot be removed. */
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

    /** [participantId] is who the activity log credits, and the only thing that lets it say
     *  more than "Someone". Optional on the server, so it is easy to forget. */
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

    // ---- Expenses ------------------------------------------------------------------------

    @Serializable
    public data class ExpensesListInput(
        public val groupId: String,
        public val cursor: Int? = null,
        public val limit: Int? = null,
        /** Case-insensitive substring match on the expense title. */
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

    /** See [groupsUpdate] for why [participantId] is explicit rather than merely optional. */
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

    /** See [groupsUpdate] for why [participantId] is explicit rather than merely optional. */
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

    /**
     * Deliberately not [ExpenseIdInput]: `groups.expenses.get` is a query and has no
     * `participantId` to give, and one input shared by a read and a write would carry a field
     * that means nothing on half its call sites.
     */
    @Serializable
    public data class DeleteExpenseInput(
        public val groupId: String,
        public val expenseId: String,
        public val participantId: String? = null,
    )

    /** See [groupsUpdate] for why [participantId] is explicit rather than merely optional. */
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

    // ---- Balances ------------------------------------------------------------------------

    @Serializable
    public data class BalancesResponse(
        /** Keyed by participant ID. A participant with no activity is absent, not zero. */
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

    // ---- Stats -----------------------------------------------------------------------------
    //
    // Two procedure names exist in the wild: upstream deleted `groups.stats.get` when it added
    // `groups.stats.overview`, and published images still predate the rename. `statsGet` is
    // therefore not dead code, and asking only the new name is what shipped a wrong "this server
    // has no totals" to everyone on spliit.app.
    //
    // Ask through the `TrpcClient.groupStats` extension below, which knows both names.

    @Serializable
    public data class GroupStatsInput(
        public val groupId: String,
        /** Whose spending and share to answer for. Left null, the server answers neither. */
        public val participantId: String? = null,
    )

    /** The only endpoint that knows what anybody actually paid. `groups.balances.list` looks
     *  like it does: its `paid`/`paidFor` come from suggested payments, not expenses. */
    @Serializable
    public data class GroupStatsResponse(
        /** Minor units. Negative when the group has taken in more than it has spent. */
        public val totalGroupSpendings: Int,
        /** Minor units, this participant's own spending. Absent when the request named nobody. */
        public val totalParticipantSpendings: Int? = null,
        /**
         * The one amount in the API that is not an integer, and it stays floating even though
         * today's server sends a whole number: instances predating the *Shares* change send
         * `1416.67`. Typed as `Int` this throws and takes the totals screen with it. Round on
         * the way to the display, never on the way in.
         */
        public val totalParticipantShare: Double? = null,
        /**
         * What the group's spending is made of, beyond its sum. Null on any instance still
         * answering the removed `groups.stats.get`, which carried the three figures above and
         * nothing else, which is the whole reason every field below this line is nullable.
         */
        public val summary: StatsSummary? = null,
        /** Spending per category, largest first, as the server folds it. Null on an older
         *  instance, see [summary]. */
        public val categories: List<CategoryTotal>? = null,
    )

    /**
     * The group's spending described rather than totalled. [firstDate] and [lastDate] are plain
     * `YYYY-MM-DD` strings, not superjson `Date`s: nothing annotates them, and `Instant` would
     * ask the contextual decoder to read a date-only string.
     */
    @Serializable
    public data class StatsSummary(
        public val expenseCount: Int? = null,
        /** Minor units. The same figure as [GroupStatsResponse.totalGroupSpendings]. */
        public val totalSpending: Int? = null,
        /** Minor units, already divided by the server, never divide this again. */
        public val averageExpense: Int? = null,
        public val largestExpense: LargestExpense? = null,
        public val firstDate: String? = null,
        public val lastDate: String? = null,
    )

    @Serializable
    public data class LargestExpense(
        public val title: String? = null,
        /** Minor units. */
        public val amount: Int? = null,
    )

    /** One category's share of the group's spending. [grouping], not [name], picks the glyph,
     *  so a category invented after this shipped still lands on a sensible icon. */
    @Serializable
    public data class CategoryTotal(
        public val categoryId: Int,
        public val grouping: String? = null,
        public val name: String? = null,
        /** Minor units. Negative for a category that netted out as income. */
        public val total: Int,
    )

    /**
     * The current name for the totals payload. Takes an optional `from`/`to` date range on the
     * server; omitting it means the whole history, which is the only question this app asks, so
     * there is nothing here to plumb a range through for.
     */
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

    /**
     * The **removed** name. Kept because self-hosted instances still run it, see the note above
     * this section. Prefer `TrpcClient.groupStats`, which tries [statsOverview] first and falls
     * back to this.
     */
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

    // ---- Activity --------------------------------------------------------------------------

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

    // ---- Categories ------------------------------------------------------------------------

    @Serializable
    public data class CategoriesResponse(public val categories: List<ExpenseCategory>)

    /** Takes no input at all, [TrpcClient] omits the `input` query parameter entirely for this one. */
    public fun categoriesList(): TrpcProcedure<NoInput, CategoriesResponse> =
        TrpcProcedure.query("categories.list", CategoriesResponse.serializer())
}

/**
 * The group's totals, from whichever of the two stats procedures this instance answers.
 *
 * `groups.stats.overview` first. An older instance answers `NOT_FOUND` naming the missing route
 * ([TrpcServerError.isUnknownProcedure]), which is the signal to ask the old name. One that
 * answers neither has no stats, and the second `NOT_FOUND` propagates so a screen can say so
 * rather than retry forever.
 *
 * Which name an instance answers is not cached: a [TrpcClient] is per request, and the cache
 * would need invalidating on every server upgrade to save one 404.
 */
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
