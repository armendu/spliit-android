package app.spliit.android.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.spliit.android.R

/** A field showing the current choice; tapping it opens whatever picks the next one. */
@Composable
fun SelectField(
    label: String,
    value: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(90f),
                )
            },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            shape = FieldShape,
        )
        // A read-only text field swallows taps, so the click goes on an overlay.
        Box(
            Modifier
                .matchParentSize()
                .clip(FieldShape)
                .semantics { contentDescription = "$label, $value" }
                .clickable(role = Role.DropdownList, onClick = onClick)
                .testTag(testTag),
        )
    }
}
