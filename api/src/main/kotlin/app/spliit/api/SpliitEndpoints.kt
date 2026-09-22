package app.spliit.api

import kotlinx.serialization.Serializable

/**
 * The Spliit router, as [TrpcProcedure]s ready for [TrpcClient.call].
 *
 * ```kotlin
 * val response = client.call(SpliitEndpoints.groupsGet(groupId = "abc"))
 * ```
 *
 * Every response wrapper type lives here rather than in `Models.kt`, Part 4 owns the endpoint
 * surface, and Part 3 owns only what is inside these wrappers.
 */
public object SpliitEndpoints {

    // ---- Groups --------------------------------------------------------------------------

    @Serializable
    public data class GroupsListInput(public val groupIds: List<String>)

    @Serializable
    public data class GroupsListResponse(public val groups: List<GroupSummary>)

    /**
     * Fetches the groups behind a set of IDs.
     *
     * Deliberately not a no-input procedure: the server answers `BAD_REQUEST` when this is
     * called without an input object at all, so [groupIds] is a required parameter rather than
     * one defaulting to an empty list. Unknown IDs are silently absent from the result, which is
     * how a group deleted server-side shows up.
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

    /**
     * [participantId] is who the activity log should credit this change to, the *only* thing
     * that lets it say more than "Someone". It is optional on the server and defaults to null
     * here too, so a caller has to actively decide to leave it out rather than discover, later,
     * that it never set it. (Resolving who the actor actually is is Part 8's job; this only
     * carries the value.)
     */
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
    // Two procedure names exist in the wild. `groups.stats.overview` folded the whole totals
    // page, spending by month, by participant, by category, plus recurring subscriptions -
    // into one query, and upstream **deleted** the old `groups.stats.get` rather than keep it
    // beside the new name. Every published image is still, for now, one of the instances that
    // predates the rename: spliit.app runs ahead of what the self-hosted world installs. So
    // `statsGet` below is not dead code, and asking only `statsOverview` is exactly the mistake
    // that shipped a polite, wrong "this server has no totals" to everyone on spliit.app.
    //
    // Ask through the `TrpcClient.groupStats` extension at the bottom of this file, which knows
    // both names, rather than reaching for either procedure directly.

    @Serializable
    public data class GroupStatsInput(
        public val groupId: String,
        /** Whose spending and share to answer for. Left null, the server answers neither. */
        public val participantId: String? = null,
    )

    /**
     * The only endpoint that knows what anybody actually paid.
     *
     * `groups.balances.list` looks like it does and does not: its `paid` and `paidFor` are
     * derived from the suggested payments rather than from the expenses. These three figures are
     * summed over the expenses themselves.
     */
    @Serializable
    public data class GroupStatsResponse(
        /** Minor units. Negative when the group has taken in more than it has spent. */
        public val totalGroupSpendings: Int,
        /** Minor units, this participant's own spending. Absent when the request named nobody. */
        public val totalParticipantSpendings: Int? = null,
        /**
         * **The one amount in the API that is not an integer**, and it has to stay floating even
         * though today's server sends a whole number.
         *
         * Until the web app's *Shares* change, an evenly split expense was divided in
         * floating-point and the sum rounded to two decimals, a third of 42.50 across three
         * people summed to `1416.67`. An instance that predates the change still sends that.
         * Typing this as `Int` throws and takes the whole totals screen down with it, against a
         * real server, for a subset of self-hosted users. Round on the way to the display, never
         * on the way in. Absent when the request named nobody.
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
     * The group's spending described rather than merely totalled, `summary` on the overview
     * payload.
     *
     * [firstDate] and [lastDate] are plain `YYYY-MM-DD` strings on the wire, **not** superjson
     * `Date`s: the envelope's `meta.values` annotates neither, and declaring them `Instant` would
     * ask the contextual instant decoder to read a date-only string. They are dates without a
     * time and are carried as what they are.
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

    /**
     * One category's share of the group's spending.
     *
     * [grouping] rather than [name] is what picks the glyph, the same top-level key the web app
     * and [ExpenseCategory] already key on, a category the server has invented since this client
     * shipped still lands on a sensible icon rather than none.
     */
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
 * `groups.stats.overview` first, because that's the name the instance most people are on
 * answers to. An instance that predates the rename answers that with `NOT_FOUND` naming the
 * missing route, [TrpcServerError.isUnknownProcedure], which is the signal to ask the old
 * name instead. An instance that answers neither genuinely has no stats, and the second
 * `NOT_FOUND` propagates so a screen can say so rather than offer a retry that can never work.
 *
 * Which name an instance answers is deliberately not remembered anywhere. A [TrpcClient] is
 * built per request, so the answer would have to be cached per instance and invalidated the
 * moment a server is upgraded underneath it, for a return of, at most, one avoided 404, and
 * only on the instances that are already behind.
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
