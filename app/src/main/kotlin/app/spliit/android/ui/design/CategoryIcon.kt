package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.spliit.android.R
import app.spliit.android.ui.theme.SpliitTheme
import androidx.compose.ui.unit.dp

@Composable
fun CategoryIcon(
    grouping: String?,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val tint = SpliitTheme.colors.categoryGlyphs[grouping] ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(size * 0.24f))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(CategoryGlyphs.drawable(grouping)),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

object CategoryGlyphs {
    private val byGrouping = mapOf(
        "Entertainment" to R.drawable.ic_cat_entertainment,
        "Food and Drink" to R.drawable.ic_cat_food,
        "Home" to R.drawable.ic_cat_home,
        "Life" to R.drawable.ic_cat_life,
        "Transportation" to R.drawable.ic_cat_transportation,
        "Utilities" to R.drawable.ic_cat_utilities,
    )

    fun drawable(grouping: String?): Int =
        byGrouping[grouping] ?: R.drawable.ic_cat_uncategorized
}
