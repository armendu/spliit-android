package app.spliit.android.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spliit.core.Currencies


/**
 * Every ISO currency the platform knows, filtered as you type. Shared by the expense and group
 * forms, which differ only in these two parameters.
 *
 * @param promotedCode Pinned above the list, hidden once the search box has text, where it would
 *   be a duplicate hit.
 * @param onUseCustomSymbol An escape hatch for a group counted in a bare symbol with no ISO code,
 *   which the web app's default `$` is. The expense form passes null: no code, no minor units.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyPickerSheet(
    title: String,
    selectedCode: String?,
    promotedCode: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    tag: (String) -> String,
    searchFieldTag: String,
    promotedSuffix: String = "",
    onUseCustomSymbol: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val all = remember { Currencies.all() }
    val promoted = promotedCode?.let { Currencies.named(it) }
    val matches = remember(query, all) {
        if (query.isBlank()) {
            all
        } else {
            all.filter {
                it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search currencies") },
                singleLine = true,
                shape = FieldShape,
                modifier = Modifier.fillMaxWidth().testTag(searchFieldTag),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (onUseCustomSymbol != null && query.isBlank()) {
                    item(key = "custom_symbol") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onUseCustomSymbol)
                                .testTag(tag("custom"))
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Type a symbol instead",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                if (promoted != null && query.isBlank()) {
                    item(key = "promoted_${promoted.code}") {
                        CurrencyRow(
                            code = promoted.code,
                            name = promoted.name + promotedSuffix,
                            isSelected = selectedCode.equals(promoted.code, ignoreCase = true),
                            onClick = { onSelect(promoted.code) },
                            tag = tag(promoted.code),
                        )
                    }
                }
                items(matches, key = { it.code }) { currency ->
                    CurrencyRow(
                        code = currency.code,
                        name = currency.name,
                        isSelected = selectedCode.equals(currency.code, ignoreCase = true),
                        onClick = { onSelect(currency.code) },
                        tag = tag(currency.code),
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrencyRow(
    code: String,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
