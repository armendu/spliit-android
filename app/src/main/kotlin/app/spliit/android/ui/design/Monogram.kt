package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.theme.SpliitTheme

object MonogramPalette {
    const val COUNT = 8

    // FNV-1a like iOS, not String.hashCode(), so a person keeps their colour across platforms.
    fun colorIndex(participantId: String): Int {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (byte in participantId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return Math.floorMod(hash, COUNT.toLong()).toInt()
    }

    fun initials(name: String): String =
        name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
}

@Composable
fun Monogram(
    name: String,
    participantId: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    testTag: String? = null,
) {
    val colorIndex = MonogramPalette.colorIndex(participantId)
    val color = SpliitTheme.colors.monogramPalette[colorIndex]
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape)
            .clearAndSetSemantics {
                if (testTag != null) this.testTag = testTag
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = MonogramPalette.initials(name),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
