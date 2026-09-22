package app.spliit.android.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.design.FieldShape
import app.spliit.android.ui.design.Monogram
import app.spliit.android.ui.design.fabAndNavigationBarPadding
import app.spliit.api.Participant

/**
 * The scrolling body of a group tab.
 *
 * Balances, Totals and Information are siblings under one FAB and one navigation bar, so their
 * margins and bottom clearance have to agree or one of them hides its last row.
 */
@Composable
fun GroupTabList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = fabAndNavigationBarPadding()),
        content = content,
    )
}

/**
 * The row that opens the "who are you?" picker, on each of the three tabs that offers it.
 *
 * It was written out three times and had already drifted: two tabs said "Say who you are" and
 * the third said "Not set", for the same control under the same heading.
 */
@Composable
fun IdentityRow(
    you: Participant?,
    testTag: String,
    onIdentify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onIdentify)
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (you != null) {
            Monogram(name = you.name, participantId = you.id, size = 28.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = you?.name ?: "Say who you are",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}
