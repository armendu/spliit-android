package app.spliit.android.ui.design

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.sp
import java.util.Locale as JavaLocale

// Six file-private copies of this chrome existed and had drifted: headers 4dp apart between
// sibling tabs, dividers 4dp apart. One definition each, so the next change moves all of them.

/** The heading above a run of rows. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/**
 * A form's heading, drawn in capitals.
 *
 * The capitals are typographic, not a change to the words, so the natural-case string stays as
 * the accessibility label rather than being spelled out letter by letter. Same reasoning as
 * [DateHeader]. The locale is read through Compose's own [Locale.current] rather than
 * `java.util.Locale.getDefault()`, which is not observable: the latter would leave a heading in
 * the old language until something else happened to recompose it.
 */
@Composable
fun FormSectionHeader(text: String, topSpace: Dp = 24.dp) {
    Spacer(Modifier.height(topSpace))
    Text(
        text = text.uppercase(JavaLocale.forLanguageTag(Locale.current.toLanguageTag())),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = text },
    )
    Spacer(Modifier.height(8.dp))
}

/** The hairline between two sections. */
@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** The small print under a section, explaining where a figure came from. */
@Composable
fun Footnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
