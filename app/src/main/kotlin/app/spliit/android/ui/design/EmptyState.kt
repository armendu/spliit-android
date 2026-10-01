package app.spliit.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.spliit.android.ui.TestTags
import app.spliit.android.ui.theme.SpliitTheme

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    @DrawableRes iconRes: Int? = null,
    description: String? = null,
    art: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                art()
            } else if (iconRes != null || !icon.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(SpliitTheme.colors.brandAccentSoft, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    } else {
                        Text(
                            text = icon.orEmpty(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.EMPTY_STATE_TITLE),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(TestTags.EMPTY_STATE_DESCRIPTION),
                )
            }
        }

        actions()
    }
}
