package app.spliit.android.feature.group

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.design.fabAndNavigationBarPadding

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
