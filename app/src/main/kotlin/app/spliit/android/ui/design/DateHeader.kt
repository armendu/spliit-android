package app.spliit.android.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.spliit.android.ui.TestTags
import app.spliit.core.DateBucket

/**
 * What to draw for each [DateBucket].
 *
 * Plain Kotlin rather than a string resource, so this mapping is testable on the JVM with no
 * device and no `Context`, the same reasoning `:core` follows throughout. French (deferred per
 * the spec's §9) becomes a resource lookup layered on top of this map when it arrives, not a
 * rewrite of it.
 */
object DateBucketText {
    fun of(bucket: DateBucket): String = when (bucket) {
        DateBucket.TODAY -> "Today"
        DateBucket.YESTERDAY -> "Yesterday"
        DateBucket.EARLIER_THIS_WEEK -> "This week"
        DateBucket.LAST_WEEK -> "Last week"
        DateBucket.EARLIER_THIS_MONTH -> "This month"
        DateBucket.LAST_MONTH -> "Last month"
        DateBucket.EARLIER_THIS_YEAR -> "This year"
        DateBucket.LAST_YEAR -> "Last year"
        DateBucket.OLDER -> "Older"
    }
}

/**
 * The heading above a run of expenses, "This week", "Last month", "Older", for `:core`'s
 * [DateBucket].
 *
 * Drawn upper-case and letter-spaced as a *typographic* treatment only, mirroring the iOS
 * header's own comment on this: capitals are a presentation choice, not a change to the words,
 * so the accessibility label stays [DateBucketText.of]'s natural-case string via an explicit
 * `contentDescription` rather than the upper-cased text a screen reader would otherwise spell
 * out.
 */
@Composable
fun DateHeader(bucket: DateBucket, modifier: Modifier = Modifier) {
    val title = DateBucketText.of(bucket)
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .testTag(TestTags.DATE_HEADER)
            .semantics { contentDescription = title },
    )
}
