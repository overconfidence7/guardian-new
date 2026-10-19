package app.guardianmode.ui.transparency

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.SharingItem
import app.guardianmode.core.Transparency
import app.guardianmode.data.ChangeItem
import app.guardianmode.data.HelperInfo
import app.guardianmode.ui.components.Avatar
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.components.GuardianPage
import app.guardianmode.ui.theme.Guardian
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TransparencyUi(
    val items: List<SharingItem>,
    val helpers: List<HelperInfo>,
    val shared: List<GuardianEvent>,
    val changes: List<ChangeItem>,
    val paused: Boolean,
    val pausedUntil: String?,
    val paired: Boolean,
)

interface TransparencyActions {
    fun onToggle(key: String, enabled: Boolean)
    fun onPauseHour()
    fun onResume()
    fun onRemoveHelper(helper: HelperInfo)
    fun onStopUsing()
    fun onBack()
}

private val TOGGLEABLE = setOf("pause", "remoteAccess", "longCall", "screening", "checkIn")

@Composable
fun TransparencyScreen(ui: TransparencyUi, actions: TransparencyActions, zone: ZoneId = ZoneId.systemDefault()) {
    var confirmRemove by remember { mutableStateOf<HelperInfo?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    val stamp = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", LocalConfiguration.current.locales[0])

    GuardianPage("What my family can see", actions::onBack) {
        Text(
            "Guardian Mode shares a few safety alerts with the people you chose. It never shares your messages, what's said on calls, or where you are. You can turn any of it off.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(20.dp))
        if (ui.paused) {
            BigButton("Turn protection back on", actions::onResume, icon = Icons.Rounded.PlayCircle)
            ui.pausedUntil?.let {
                Text("Paused until $it", style = MaterialTheme.typography.bodyMedium, color = Guardian.colors.muted, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            BigButton("Pause everything for 1 hour", actions::onPauseHour, icon = Icons.Rounded.PauseCircle, primary = false)
        }

        Section("Your helpers")
        if (ui.helpers.isEmpty()) {
            Text("No one yet.", style = MaterialTheme.typography.bodyLarge, color = Guardian.colors.muted)
        }
        ui.helpers.filterNot { it.pending }.forEach { helper ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(helper.name, helper.photoUrl, 56.dp)
                Spacer(Modifier.width(16.dp))
                Text(helper.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                TextButton(onClick = { confirmRemove = helper }) {
                    Text("Remove", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Section("What is shared")
        ui.items.forEach { item -> FeatureCard(item, onToggle = if (item.key in TOGGLEABLE) { on -> actions.onToggle(item.key, on) } else null) }

        Section("Never shared")
        Transparency.NEVER_COLLECTED.forEach { line ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, contentDescription = null, tint = Guardian.colors.calm, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }

        Section("Alerts your family received")
        if (ui.shared.isEmpty()) {
            Text("None so far.", style = MaterialTheme.typography.bodyLarge, color = Guardian.colors.muted)
        }
        ui.shared.forEach { event ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    Instant.ofEpochMilli(event.at).atZone(zone).format(stamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Guardian.colors.muted,
                )
                Text(EventText.forParent(event), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }

        if (ui.changes.isNotEmpty()) {
            Section("Recent changes by your family")
            ui.changes.forEach { change ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        Instant.ofEpochMilli(change.at).atZone(zone).format(stamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Guardian.colors.muted,
                    )
                    Text("${change.byName}: ${change.summary}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        Section("Stop using Guardian Mode")
        Text(
            "You can stop whenever you want. Your helpers will see that this phone has left the family.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        BigButton("Stop using Guardian Mode", { confirmStop = true }, primary = false)
        Spacer(Modifier.height(32.dp))
    }

    confirmRemove?.let { helper ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${helper.name}?", style = MaterialTheme.typography.headlineMedium) },
            text = { Text("${helper.name} will stop getting alerts and can't change your phone anymore.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { actions.onRemoveHelper(helper); confirmRemove = null }) { Text("Remove", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep", style = MaterialTheme.typography.labelLarge) } },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop using Guardian Mode?", style = MaterialTheme.typography.headlineMedium) },
            text = { Text("Your family will no longer get alerts or be able to help with this phone. You'll then choose a different home screen.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { actions.onStopUsing(); confirmStop = false }) { Text("Stop", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep using it", style = MaterialTheme.typography.labelLarge) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun FeatureCard(item: SharingItem, onToggle: ((Boolean) -> Unit)?) {
    val shape = RoundedCornerShape(Guardian.dimens.tileCorner)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(Guardian.colors.tileSurface)
            .border(1.dp, Guardian.colors.outline, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (onToggle != null) {
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Guardian.colors.calm),
                    modifier = Modifier.semantics {
                        contentDescription = item.title
                        stateDescription = if (item.enabled) "On" else "Off"
                    },
                )
            } else {
                Text(if (item.enabled) "On" else "Off", style = MaterialTheme.typography.labelLarge, color = Guardian.colors.muted)
            }
        }
        Text("Shared: ${item.whatIsShared}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("Never shared: ${item.whatIsNeverShared}", style = MaterialTheme.typography.bodyMedium, color = Guardian.colors.muted)
    }
}
