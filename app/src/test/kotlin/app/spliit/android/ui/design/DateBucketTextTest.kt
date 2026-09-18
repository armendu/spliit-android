package app.spliit.android.ui.design

import app.spliit.core.DateBucket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DateBucketTextTest {

    @Test
    fun `every bucket has its own text`() {
        val expected = mapOf(
            DateBucket.TODAY to "Today",
            DateBucket.YESTERDAY to "Yesterday",
            DateBucket.EARLIER_THIS_WEEK to "This week",
            DateBucket.LAST_WEEK to "Last week",
            DateBucket.EARLIER_THIS_MONTH to "This month",
            DateBucket.LAST_MONTH to "Last month",
            DateBucket.EARLIER_THIS_YEAR to "This year",
            DateBucket.LAST_YEAR to "Last year",
            DateBucket.OLDER to "Older",
        )
        expected.forEach { (bucket, text) -> assertEquals(text, DateBucketText.of(bucket)) }
        // Guards the table above itself going stale: if DateBucket ever grows a case this test
        // doesn't list, this fails loudly instead of silently checking eight cases out of nine.
        assertEquals(DateBucket.entries.size, expected.size)
    }
}
