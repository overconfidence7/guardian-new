package app.guardianmode.ui.home

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.guardianmode.core.ReminderOccurrence
import app.guardianmode.data.PhotoImporter
import app.guardianmode.data.PhotoItem
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.components.AppIcon
import app.guardianmode.ui.components.GuardianPage
import app.guardianmode.ui.theme.Guardian
import coil3.compose.AsyncImage
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class AppEntry(val packageName: String, val label: String)

/** Only the apps the family allowed, as big rows. Phone settings is always reachable. */
@Composable
fun AllAppsScreen(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onPhoneSettings: () -> Unit, onBack: () -> Unit) {
    GuardianPage("All my apps", onBack) {
        if (apps.isEmpty()) {
            Text(
                "Your family hasn't added any apps yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = Guardian.colors.muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        apps.forEach { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Guardian.colors.tileSurface)
                    .border(1.dp, Guardian.colors.outline, RoundedCornerShape(20.dp))
                    .clickable(onClickLabel = "Open ${app.label}", role = Role.Button) { onOpen(app) }
                    .heightIn(min = 80.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName, 52.dp)
                Spacer(Modifier.width(16.dp))
                Text(app.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Open phone settings", role = Role.Button, onClick = onPhoneSettings)
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = Guardian.colors.muted, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Text("Phone settings", style = MaterialTheme.typography.titleMedium, color = Guardian.colors.muted)
        }
    }
}

@Composable
fun PhotosScreen(
    photos: List<PhotoItem>,
    now: ZonedDateTime,
    onBack: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onRemove: ((PhotoItem) -> Unit)? = null,
) {
    GuardianPage("Family photos", onBack) {
        if (photos.isEmpty()) {
            Text(
                "When your family sends photos, you'll find them here.",
                style = MaterialTheme.typography.bodyLarge,
                color = Guardian.colors.muted,
            )
        }
        if (onAdd != null) {
            Spacer(Modifier.height(12.dp))
            BigButton("Add photos from this phone", onAdd, icon = Icons.Rounded.AddPhotoAlternate, primary = false)
            Text(
                "You can also tap Share in Google Photos, Amazon Photos or your gallery and pick \"Add to photo card\".",
                style = MaterialTheme.typography.bodyMedium,
                color = Guardian.colors.muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }
        photos.sortedByDescending { it.createdAt }.forEach { photo ->
            val title = HomeModel.photoTitle(photo, now)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$title. ${photo.caption}" },
            ) {
                AsyncImage(
                    model = photo.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(Guardian.dimens.tileCorner))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                if (photo.caption.isNotBlank()) {
                    Text(photo.caption, style = MaterialTheme.typography.bodyLarge, color = Guardian.colors.muted)
                }
                if (onRemove != null && PhotoImporter.isLocal(photo)) {
                    TextButton(onClick = { onRemove(photo) }) {
                        Text("Remove this photo", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun RemindersScreen(today: List<ReminderOccurrence>, done: Set<String>, onBack: () -> Unit) {
    val timeFormat = DateTimeFormatter.ofPattern("h:mm a", LocalConfiguration.current.locales[0])
    GuardianPage("Today's reminders", onBack) {
        if (today.isEmpty()) {
            Text("Nothing else today.", style = MaterialTheme.typography.bodyLarge, color = Guardian.colors.muted)
        }
        today.forEach { occ ->
            val key = "${occ.reminder.id}@${occ.at.toInstant().toEpochMilli()}"
            val isDone = key in done
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Guardian.colors.tileSurface)
                    .border(1.dp, Guardian.colors.outline, RoundedCornerShape(20.dp))
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (isDone) Guardian.colors.calm else Guardian.colors.pauseSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isDone) Icons.Rounded.CheckCircle else Icons.Rounded.Alarm,
                        contentDescription = if (isDone) "Done" else null,
                        tint = if (isDone) MaterialTheme.colorScheme.onPrimary else Guardian.colors.onPauseSurface,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(occ.at.format(timeFormat), style = MaterialTheme.typography.labelMedium, color = Guardian.colors.muted)
                    Text(occ.reminder.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (occ.reminder.detail.isNotBlank()) {
                        Text(occ.reminder.detail, style = MaterialTheme.typography.bodyMedium, color = Guardian.colors.muted)
                    }
                }
            }
        }
    }
}
