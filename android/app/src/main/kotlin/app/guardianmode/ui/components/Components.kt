package app.guardianmode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.guardianmode.ui.theme.Guardian
import coil3.compose.AsyncImage

/** The one big button style the parent sees everywhere. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val minHeight = Guardian.dimens.touchTarget + 8.dp
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.primary,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor ?: MaterialTheme.colorScheme.onSurface),
            border = androidx.compose.foundation.BorderStroke(2.dp, contentColor ?: MaterialTheme.colorScheme.outline),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        ) { content() }
    }
}

/** A person's face, or their initials on a warm color when there is no photo. */
@Composable
fun Avatar(name: String, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    val palette = listOf(Color(0xFF1F5E57), Color(0xFFE07A5F), Color(0xFF6A5ACD), Color(0xFF3D7EA6), Color(0xFFB5838D), Color(0xFF7A9E3B))
    val color = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials.ifEmpty { "?" },
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/** A rounded white card used for grouped content. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(Guardian.dimens.tileCorner))
            .background(Guardian.colors.tileSurface)
            .border(1.dp, Guardian.colors.outline, RoundedCornerShape(Guardian.dimens.tileCorner))
            .padding(20.dp),
    ) { content() }
}

fun Modifier.asButton(): Modifier = semantics { role = Role.Button }
