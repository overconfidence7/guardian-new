package app.guardianmode.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.guardianmode.core.TileKind
import app.guardianmode.ui.components.AppIcon
import app.guardianmode.ui.components.Avatar
import app.guardianmode.ui.theme.Guardian
import coil3.compose.AsyncImage
import java.time.ZonedDateTime

interface HomeActions {
    fun onTile(tile: TileUi)
    fun onPhoto()
    fun onShield()
    fun onAllApps()
    fun onReminders()
    fun onHelperDecision(uid: String, approve: Boolean)
}

@Composable
fun HomeScreen(ui: HomeUi, now: ZonedDateTime, actions: HomeActions, modifier: Modifier = Modifier) {
    val gutter = Guardian.dimens.gutter
    LazyVerticalGrid(
        columns = GridCells.Fixed(ui.columns),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Header(ui, actions, Modifier.statusBarsPadding().padding(top = 12.dp))
        }
        ui.pendingHelper?.let { helper ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                HelperRequest(helper.name, helper.photoUrl, onAllow = { actions.onHelperDecision(helper.uid, true) }, onDecline = { actions.onHelperDecision(helper.uid, false) })
            }
        }
        if (ui.showPhotoCard) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PhotoCard(ui, now, actions::onPhoto)
            }
        }
        ui.nextReminder?.let { (title, time) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ReminderStrip(title, time, actions::onReminders)
            }
        }
        items(ui.tiles, key = { it.id }) { tile ->
            Tile(tile, onClick = { actions.onTile(tile) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            AllAppsRow(actions::onAllApps, Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Header(ui: HomeUi, actions: HomeActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    ui.time,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Text(ui.date, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            ShieldChip(ui, actions::onShield)
        }
        Spacer(Modifier.height(4.dp))
        Text(ui.greeting, style = MaterialTheme.typography.bodyLarge, color = Guardian.colors.muted)
    }
}

@Composable
private fun ShieldChip(ui: HomeUi, onClick: () -> Unit) {
    val (label, color) = when {
        ui.paused -> "Paused" to Guardian.colors.muted
        ui.needsAttention -> "Check me" to Guardian.colors.accent
        else -> "Protected" to Guardian.colors.calm
    }
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .border(2.dp, color, RoundedCornerShape(50))
            .clickable(onClickLabel = "See what your family can see", role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "$label. What my family can see." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PhotoCard(ui: HomeUi, now: ZonedDateTime, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Guardian.dimens.tileCorner)
    val photo = ui.photo
    if (photo == null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Guardian.colors.accent, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                if (ui.paired) "Photos from your family will show up here." else "Welcome to your new home screen.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        return
    }
    val title = HomeModel.photoTitle(photo, now)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = "See family photos", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. ${photo.caption}" },
    ) {
        AsyncImage(
            model = photo.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (photo.caption.isNotBlank()) {
                    Text(photo.caption, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ReminderStrip(title: String, time: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Guardian.colors.pauseSurface)
            .clickable(onClickLabel = "Open reminders", role = Role.Button, onClick = onClick)
            .heightIn(min = Guardian.dimens.touchTarget)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Alarm, contentDescription = null, tint = Guardian.colors.onPauseSurface, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Coming up at $time", style = MaterialTheme.typography.labelMedium, color = Guardian.colors.onPauseSurface)
            Text(title, style = MaterialTheme.typography.titleMedium, color = Guardian.colors.onPauseSurface)
        }
    }
}

@Composable
fun Tile(tile: TileUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Guardian.dimens.tileCorner)
    val isPerson = tile.kind == TileKind.CALL_CONTACT
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clip(shape)
            .background(Guardian.colors.tileSurface)
            .border(1.dp, Guardian.colors.outline, shape)
            .clickable(onClickLabel = if (isPerson) "Call ${tile.label}" else "Open ${tile.label}", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = if (isPerson) "Call ${tile.label}" else tile.label }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isPerson) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(tile.label, tile.photoUrl, size = 84.dp)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Guardian.colors.calm),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        } else if (tile.kind == TileKind.APP && LocalInspectionMode.current.not() && AppIcon(tile.target, 72.dp)) {
            // The app's real icon is drawn by AppIcon.
        } else {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(iconBackground(tile.kind)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(tile.kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            tile.label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AllAppsRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClickLabel = "Show all my apps", role = Role.Button, onClick = onClick)
            .heightIn(min = Guardian.dimens.touchTarget)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("All my apps", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun iconFor(kind: TileKind): ImageVector = when (kind) {
    TileKind.PHONE -> Icons.Rounded.Call
    TileKind.MESSAGES -> Icons.AutoMirrored.Rounded.Chat
    TileKind.CAMERA -> Icons.Rounded.PhotoCamera
    TileKind.PHOTOS -> Icons.Rounded.PhotoLibrary
    TileKind.REMINDERS -> Icons.Rounded.Alarm
    TileKind.HELP -> Icons.Rounded.VolunteerActivism
    TileKind.ALL_APPS -> Icons.Rounded.Apps
    TileKind.APP -> Icons.Rounded.Lightbulb
    TileKind.CALL_CONTACT -> Icons.Rounded.Call
}

private fun iconBackground(kind: TileKind): Color = when (kind) {
    TileKind.PHONE -> Color(0xFF2E7D6B)
    TileKind.MESSAGES -> Color(0xFF3D7EA6)
    TileKind.CAMERA -> Color(0xFF5C5470)
    TileKind.PHOTOS -> Color(0xFFE07A5F)
    TileKind.REMINDERS -> Color(0xFFB7791F)
    TileKind.HELP -> Color(0xFF6A5ACD)
    else -> Color(0xFF1F5E57)
}

@Composable
private fun HelperRequest(name: String, photoUrl: String?, onAllow: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Guardian.dimens.tileCorner))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, photoUrl, 56.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                "$name would like to be one of your helpers.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Only say yes if you know $name and asked them to help.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(16.dp))
        app.guardianmode.ui.components.BigButton("Yes, $name can help", onAllow)
        Spacer(Modifier.height(10.dp))
        app.guardianmode.ui.components.BigButton("No", onDecline, primary = false, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
