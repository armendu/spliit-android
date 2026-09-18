package app.spliit.android.feature.groups

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A slow, understated opacity breathe — the shape of content about to arrive, not a spinner that
 * tells the reader nothing about what's coming. Shared by [GroupsListScreen]'s row placeholders
 * and [GroupFormScreen]'s loading state so the one loop lives in one place.
 */
@Composable
internal fun rememberSkeletonColor(): Color {
    val transition = rememberInfiniteTransition(label = "skeleton_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton_alpha",
    )
    return MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
}

@Composable
internal fun SkeletonBlock(modifier: Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    val color = rememberSkeletonColor()
    Box(modifier.background(color, shape))
}
