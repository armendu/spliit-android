package app.spliit.android.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// DESIGN.md §3 states these once as a table. The code used to state them eleven times, as
// file-private vals, which is how the two FABs ended up different greens: a value repeated is a
// value that drifts. One definition each.

/** Bottom sheets: 24dp top corners, square at the bottom where the screen edge is. */
val SheetShape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Text fields and anything the size of one. */
val FieldShape: Shape = RoundedCornerShape(12.dp)

/** Cards and list rows. */
val CardShape: Shape = RoundedCornerShape(16.dp)
