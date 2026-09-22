package app.spliit.android.feature.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.design.CategoryGlyphs
import app.spliit.android.ui.design.Money
import app.spliit.android.ui.design.MoneySize
import app.spliit.android.ui.design.Monogram
import app.spliit.api.ExpenseCategory
import app.spliit.api.Participant
import app.spliit.core.Currencies
import app.spliit.core.ExpenseFormDraft
import app.spliit.core.MoneyFormatter
import app.spliit.core.SplitMode
import app.spliit.android.ui.design.FieldShape

/**
 * The oversized, centred amount entry from DESIGN.md §4. Under a conversion the total is not
 * typed but derived from the rate, so the field becomes a read-out: an editable field bound to
 * [ExpenseFormDraft.amountText] would show a number the expense does not have.
 */
@Composable
internal fun AmountEntry(
    draft: ExpenseFormDraft,
    formatter: MoneyFormatter,
    onAmountChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatter.currencySymbol.ifBlank { draft.groupCurrencyCode.orEmpty() },
            style = MaterialTheme.typography.headlineMedium,
            // `primary`, not grey (DESIGN.md §4): the symbol says which money this is and the
            // figure says how much. In one grey the symbol reads as decoration and gets skipped,
            // on the one screen where the currency is a real question.
            //
            // The figure stays `on-surface`. An expense *row*'s amount takes `primary` and this
            // one does not: a tinted value being edited reads as a state, not a value.
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))

        if (draft.conversionRequired) {
            val total = draft.amountMinorUnits
            Money(
                value = if (total == null) "-" else formatter.formatPlain(total),
                size = MoneySize.HERO,
                testTag = TestTags.EXPENSE_FORM_CONVERTED_TOTAL,
            )
        } else {
            OutlinedTextField(
                value = draft.amountText,
                onValueChange = onAmountChange,
                placeholder = {
                    Text(
                        text = formatter.formatPlain(0L),
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Start,
                    )
                },
                textStyle = MaterialTheme.typography.displaySmall.copy(
                    // DESIGN.md §2: tabular figures on every amount, the entry field included -
                    // without them the caret walks as the digits change.
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Bold,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                shape = FieldShape,
                modifier = Modifier
                    .width(IntrinsicAmountWidth)
                    .testTag(TestTags.EXPENSE_FORM_AMOUNT_FIELD),
            )
        }
    }
}

/** Wide enough for a five-figure amount at the display size, and it scrolls within itself after. */
private val IntrinsicAmountWidth = 220.dp

// ---- who paid --------------------------------------------------------------------------------

@Composable
internal fun PaidByChips(
    participants: List<Participant>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(participants, key = { it.id }) { participant ->
            val isSelected = participant.id == selectedId
            SelectableChip(
                isSelected = isSelected,
                onClick = { onSelect(participant.id) },
                testTag = TestTags.expenseFormPaidByChip(participant.id),
            ) {
                Monogram(name = participant.name, participantId = participant.id, size = 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(participant.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

// ---- category --------------------------------------------------------------------------------

/**
 * Every category the server offers, as a scrolling strip of DESIGN.md §4 chips. In the order
 * `categories.list` sends them, which is grouped: sorting alphabetically would scatter each
 * grouping's members and cost the glyphs their only visual grouping.
 */
@Composable
internal fun CategoryChips(
    categories: List<ExpenseCategory>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            SelectableChip(
                isSelected = category.id == selectedId,
                onClick = { onSelect(category.id) },
                testTag = TestTags.expenseFormCategoryChip(category.id),
            ) {
                Icon(
                    painter = painterResource(CategoryGlyphs.drawable(category.grouping)),
                    // The chip's own label names the category; a second reading of it here would
                    // be the same word twice to a screen reader.
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(category.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** DESIGN.md §4's chip: 32dp, full radius, `surface-container` idle, `primary` when chosen. */
@Composable
private fun SelectableChip(
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val foreground = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

// ---- the split -------------------------------------------------------------------------------

/** How the expense divides, and what that comes to per person. The figures are
 *  [ExpenseFormDraft.splitAmounts], which is what gets stored, not an approximation. */
@Composable
internal fun SplitSection(
    state: ExpenseFormUiState,
    draft: ExpenseFormDraft,
    formatter: MoneyFormatter,
    currencySymbol: String,
    onSplitMode: (SplitMode) -> Unit,
    onIncluded: (String, Boolean) -> Unit,
    onAllIncluded: (Boolean) -> Unit,
    onShareText: (String, String) -> Unit,
    onSaveSplitAsDefault: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Paid for",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // One control, not two: with everyone already in, "select all" has nothing left to do.
        TextButton(
            onClick = { onAllIncluded(!draft.allParticipantsIncluded) },
            modifier = Modifier.testTag(TestTags.EXPENSE_FORM_SELECT_ALL_BUTTON),
        ) {
            Text(if (draft.allParticipantsIncluded) "Select none" else "Select all")
        }
    }

    SplitModeTabs(selected = draft.splitMode, onSelect = onSplitMode)
    Spacer(Modifier.height(8.dp))

    // Keyed by participant so a row's amount is read off the same apportionment the submission
    // will carry, rather than recomputed per row.
    val amounts = remember(draft) { draft.splitAmounts().associate { it.participantId to it.amount } }

    for (participant in draft.participants) {
        ParticipantSplitRow(
            draft = draft,
            participantId = participant.id,
            name = participant.name,
            isIncluded = participant.isIncluded,
            valueText = participant.valueText,
            amountMinorUnits = amounts[participant.id],
            formatter = formatter,
            currencySymbol = currencySymbol,
            problems = state.problems(participant.id),
            onIncluded = onIncluded,
            onShareText = onShareText,
        )
    }

    FieldProblems(state, ExpenseFormDraft.Field.PAID_FOR, formatter)

    // Before a save has been attempted this is a running tally rather than a complaint, showing
    // what is left to allocate is what stops a refused save being the first news of it.
    val remainder = draft.remainderText(formatter)
    Spacer(Modifier.height(4.dp))
    Text(
        text = remainder ?: draft.splitMode.explanation(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TestTags.EXPENSE_FORM_REMAINDER),
    )

    if (draft.isSplitWorthRemembering) {
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = "Save as the group's usual split",
            description = "The next expense in this group starts divided this way.",
            checked = draft.saveSplitAsDefault,
            onCheckedChange = onSaveSplitAsDefault,
            testTag = TestTags.EXPENSE_FORM_SAVE_SPLIT_TOGGLE,
        )
    }
}

/**
 * Choosing one of four ways to divide the expense, on Material's segmented buttons. Not tabs,
 * which navigate between views. Labels stay short enough to survive the largest system font:
 * shorten the label rather than truncating it or changing control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitModeTabs(selected: SplitMode, onSelect: (SplitMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SplitMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = SplitMode.entries.size),
                modifier = Modifier.testTag(TestTags.expenseFormSplitModeTab(mode.name)),
            ) {
                Text(mode.title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ParticipantSplitRow(
    draft: ExpenseFormDraft,
    participantId: String,
    name: String,
    isIncluded: Boolean,
    valueText: String,
    amountMinorUnits: Long?,
    formatter: MoneyFormatter,
    currencySymbol: String,
    problems: List<ExpenseFormDraft.Problem>,
    onIncluded: (String, Boolean) -> Unit,
    onShareText: (String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isIncluded,
                onCheckedChange = { onIncluded(participantId, it) },
                modifier = Modifier.testTag(TestTags.expenseFormParticipantToggle(participantId)),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (isIncluded && draft.splitMode != SplitMode.EVENLY) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { onShareText(participantId, it) },
                    singleLine = true,
                    isError = problems.isNotEmpty(),
                    suffix = {
                        Text(
                            text = draft.splitMode.unitLabel(currencySymbol),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFeatureSettings = "tnum",
                        textAlign = TextAlign.End,
                    ),
                    shape = FieldShape,
                    modifier = Modifier
                        .width(128.dp)
                        // Position alone says whose share this is, and position is exactly what a
                        // screen reader flattens away.
                        .semanticsLabel("$name's share")
                        .testTag(TestTags.expenseFormParticipantValue(participantId)),
                )
                Spacer(Modifier.width(8.dp))
            }

            if (isIncluded) {
                Money(
                    value = formatter.format(amountMinorUnits ?: 0L),
                    size = MoneySize.SUPPORT,
                    testTag = TestTags.expenseFormParticipantAmount(participantId),
                )
            }
        }
        for (problem in problems) {
            Text(
                text = problem.message(formatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

// ---- currency conversion ---------------------------------------------------------------------

/**
 * What the expense was paid in, and at what rate when that is not the group's currency. The two
 * amounts are on different scales; this draws each at its own currency's precision.
 */
@Composable
internal fun CurrencySection(
    state: ExpenseFormUiState,
    draft: ExpenseFormDraft,
    formatter: MoneyFormatter,
    onPickCurrency: () -> Unit,
    onOriginalAmount: (String) -> Unit,
    onRate: (String) -> Unit,
) {
    SectionHeader("Currency")
    val paidIn = draft.originalCurrencyCode?.let { Currencies.named(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onPickCurrency)
            .testTag(TestTags.EXPENSE_FORM_CURRENCY_BUTTON)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Paid in", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = paidIn?.name ?: draft.originalCurrencyCode.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (!draft.conversionRequired) return

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = draft.originalAmountText,
        onValueChange = onOriginalAmount,
        label = { Text("Amount paid") },
        suffix = { Text(paidIn?.symbol ?: draft.originalCurrencyCode.orEmpty()) },
        singleLine = true,
        isError = state.problems(ExpenseFormDraft.Field.ORIGINAL_AMOUNT).isNotEmpty(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        shape = FieldShape,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.EXPENSE_FORM_ORIGINAL_AMOUNT_FIELD),
    )
    FieldProblems(state, ExpenseFormDraft.Field.ORIGINAL_AMOUNT, formatter)

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = draft.conversionRateText,
        onValueChange = onRate,
        label = { Text("Exchange rate") },
        supportingText = {
            Text("1 ${draft.originalCurrencyCode} in ${draft.groupCurrencyCode}.")
        },
        singleLine = true,
        isError = state.problems(ExpenseFormDraft.Field.CONVERSION_RATE).isNotEmpty(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        shape = FieldShape,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.EXPENSE_FORM_CONVERSION_RATE_FIELD),
    )
    FieldProblems(state, ExpenseFormDraft.Field.CONVERSION_RATE, formatter)
}

// ---- odds and ends ---------------------------------------------------------------------------

@Composable
internal fun SwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        )
    }
}

/** How often the expense repeats. Only the four cadences this client knows are offered; a
 *  fifth from a newer instance is kept and shown by its raw name. */
@Composable
internal fun RecurrenceRow(rule: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val known = linkedMapOf(
        ExpenseFormDraft.NO_RECURRENCE to "Doesn't repeat",
        "DAILY" to "Every day",
        "WEEKLY" to "Every week",
        "MONTHLY" to "Every month",
    )
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { expanded = true }
                .testTag(TestTags.EXPENSE_FORM_RECURRENCE_BUTTON)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Repeats", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = known[rule] ?: rule,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, label) in known) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A label for a control that means nothing on its own. `semantics`, not
 *  `clearAndSetSemantics`, which would take the field's value and tag with it. */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }
