package app.spliit.android.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.spliit.android.ui.TestTags
import app.spliit.core.DateBucket

/**
 * What to draw for each [DateBucket]. Plain Kotlin rather than a string resource, so it is
 * testable on the JVM with no `Context`; a translation layers a lookup on top of this map.
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

/** The heading above a run of expenses, for `:core`'s [DateBucket]. */
@Composable
fun DateHeader(bucket: DateBucket, modifier: Modifier = Modifier) {
    CapsLabel(DateBucketText.of(bucket), modifier.testTag(TestTags.DATE_HEADER))
}
