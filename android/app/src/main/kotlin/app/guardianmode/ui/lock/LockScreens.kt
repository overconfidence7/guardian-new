package app.guardianmode.ui.lock

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.guardianmode.core.LockCode
import app.guardianmode.core.LockLevel
import app.guardianmode.protection.UnlockResult
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.components.CodeField
import app.guardianmode.ui.components.GuardianPage
import app.guardianmode.ui.components.UnlockForm
import app.guardianmode.ui.theme.Guardian

fun lockLevelName(level: LockLevel) = when (level) {
    LockLevel.SIMPLE -> "Simple"
    LockLevel.GUIDED -> "Guided"
    LockLevel.LOCKED -> "Locked"
}

fun lockLevelDescription(level: LockLevel) = when (level) {
    LockLevel.SIMPLE -> "A big, easy home screen. The rest of the phone works like normal. The code is only needed to change the lock or stop using Guardian Mode."
    LockLevel.GUIDED -> "Only the buttons your family set up. The app list, phone settings and protection switches need the code."
    LockLevel.LOCKED -> "Like Guided, and any other app (Settings included) is covered by a code screen. Calls always work, including 911."
}

/** Three big choices, one per lock level. */
@Composable
fun LockLevelPicker(selected: LockLevel, onSelect: (LockLevel) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LockLevel.entries.forEach { level ->
            val on = level == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Guardian.colors.tileSurface)
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Guardian.colors.outline, RoundedCornerShape(20.dp))
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(level) }
                    .padding(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (on) MaterialTheme.colorScheme.primary else Guardian.colors.muted,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lockLevelName(level), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(lockLevelDescription(level), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Pick a code, then type it again. */
@Composable
fun SetCodeForm(onSet: (String) -> Unit, buttonText: String = "Save this code") {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!confirming) {
            Text("Pick a code of 4 to 8 numbers.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            CodeField(first, { first = it; error = null }, {}, "New code")
            BigButton("Next", {
                if (LockCode.isValid(first)) {
                    confirming = true
                } else {
                    error = "The code needs at least 4 numbers."
                }
            })
        } else {
            Text("Type the same code again.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            CodeField(second, { second = it; error = null }, {}, "Same code again")
            BigButton(buttonText, {
                if (second == first) {
                    onSet(first)
                } else {
                    error = "Those didn't match. Start again."
                    first = ""
                    second = ""
                    confirming = false
                }
            })
        }
        error?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
        Text(
            "Write it down somewhere safe. If it's forgotten, the person who set up this phone can help.",
            style = MaterialTheme.typography.bodyMedium,
            color = Guardian.colors.muted,
        )
    }
}

@Composable
fun UnlockScreen(tryUnlock: suspend (String) -> UnlockResult, onUnlocked: () -> Unit, onBack: () -> Unit) {
    GuardianPage("Enter the code", onBack) {
        Spacer(Modifier.height(8.dp))
        UnlockForm(prompt = "This part of the phone needs the lock code.", tryUnlock = tryUnlock, onUnlocked = onUnlocked)
    }
}

@Composable
fun LockSettingsScreen(
    level: LockLevel,
    hasCode: Boolean,
    onLevel: (LockLevel) -> Unit,
    onSetCode: (String) -> Unit,
    onLockNow: () -> Unit,
    onBack: () -> Unit,
) {
    var changingCode by remember { mutableStateOf(!hasCode) }
    GuardianPage("Phone lock", onBack) {
        Text("How locked down should this phone be?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        LockLevelPicker(level, onLevel)
        Spacer(Modifier.height(28.dp))
        Text("Lock code", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        if (changingCode) {
            SetCodeForm(onSet = { onSetCode(it); changingCode = false })
        } else {
            BigButton("Change the code", { changingCode = true }, primary = false)
            Spacer(Modifier.height(12.dp))
            BigButton("Lock it again now", onLockNow)
            Text(
                "After the code is entered, the phone stays open for 10 minutes and then locks again by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = Guardian.colors.muted,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
