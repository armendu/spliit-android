package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val SheetShape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

val FieldShape: Shape = RoundedCornerShape(12.dp)

val CardShape: Shape = RoundedCornerShape(16.dp)

// Clipped so a clickable row inside keeps its ripple within the rounded corners.
@Composable
fun Modifier.outlinedCard(): Modifier = this
    .clip(CardShape)
    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)

@Composable
fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
