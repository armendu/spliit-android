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

/**
 * Picks the colour and the letters for a participant's monogram.
 *
 * Colour is a stable hash of the participant's **ID**, not their position in a list, DESIGN.md
 * §2: position changes when somebody joins or leaves; the colour a person "is" should not.
 */
object MonogramPalette {
    /** How many colours the palette holds, see [app.spliit.android.ui.theme.MonogramColors]. */
    const val COUNT = 8

    /**
     * FNV-1a, 64-bit, the same offset basis, prime and fold-to-range as the iOS app's
     * `MonogramPalette.index(for:)`, ported on purpose rather than reinvented: the same
     * participant ID then lands on the same colour index on both platforms, verified against
     * the iOS suite's own checked-in expectations in this file's test.
     *
     * Deliberately not `String.hashCode()`: that is stable only within one JVM run, not across
     * JVM versions or platforms, so a participant's monogram could reshuffle on a JDK update -
     * see CLAUDE.md's list of things that bite silently, and this palette's whole reason to
     * exist is a colour that does *not* move.
     */
    fun colorIndex(participantId: String): Int {
        // Kotlin's hex Long literals are range-checked against the *signed* range, unlike
        // Java's, so the offset basis, which sets the top bit, has to arrive via an unsigned
        // literal and a bit-pattern-preserving conversion rather than as a plain `0x...L`.
        var hash = 0xcbf29ce484222325uL.toLong()
        for (byte in participantId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L // the FNV-1a 64-bit prime
        }
        // Long's `%` keeps the sign of the dividend, which a raw hash can be; floorMod always
        // lands in [0, COUNT).
        return Math.floorMod(hash, COUNT.toLong()).toInt()
    }

    /**
     * The one or two letters drawn inside the chip: the first letter of up to the first two
     * words. Empty for a nameless participant, a blank chip is a quieter failure than a
     * placeholder glyph that reads as an error.
     */
    fun initials(name: String): String =
        name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
}

/**
 * Someone's initials, on a colour that is theirs.
 *
 * Spliit has no accounts and no avatars, so a participant has only ever been a name in a row. A
 * monogram gives the eye something to find: the same person reads as the same colour in the
 * balances, the expense list and a suggested payment, on every device.
 *
 * @param testTag Applied *inside* [clearAndSetSemantics], not chained onto this [Box] via the
 *   ordinary `Modifier.testTag` extension. `clearAndSetSemantics`'s own contract is that it
 *   clears the semantics of "this modifier or its descendants" and substitutes exactly what its
 *   lambda sets, a tag placed anywhere else on this chain would be discarded along with the
 *   name text's implicit semantics, not merged elsewhere and still reachable. See
 *   [app.spliit.android.ui.TestTags] for where this was found.
 */
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
            // The name this sits beside already says it in text, see Money's own note on not
            // splitting a label into two things a screen reader announces separately. Left
            // alone, the initials Text would repeat it a second time.
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
