package app.guardianmode.ui.cards

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.guardianmode.core.AppCategory
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.FamilyContact
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.theme.Guardian
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface CardActions {
    fun callFamily(phone: String)
    fun continueAnyway()
    fun goHome()
    fun removeApp()
    fun reminderDone()
    fun snooze()
    fun dismiss()
}

@Composable
fun CardContent(request: CardRequest, config: DeviceConfig, actions: CardActions) {
    val helper = config.primaryHelper
    when (request.kind) {
        CardKind.PAUSE -> PauseCard(request, helper, actions)
        CardKind.REMOTE_ACCESS -> RemoteAccessCard(request, helper, actions)
        CardKind.SAFE_WORD -> SafeWordCard(config, actions)
        CardKind.REMINDER -> ReminderCard(request, actions)
    }
}

@Composable
private fun CardFrame(
    icon: ImageVector,
    iconTint: Color,
    background: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(background)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun Headline(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics {
            heading()
            liveRegion = LiveRegionMode.Assertive
        },
    )
}

@Composable
private fun Body(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Callout(text: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .padding(18.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

private fun whatTheyAskFor(category: AppCategory?) = when (category) {
    AppCategory.GIFT_CARD -> "buy gift cards"
    AppCategory.CRYPTO -> "send crypto or Bitcoin"
    AppCategory.BANK -> "move money from your bank"
    AppCategory.PAYMENT -> "send money"
    else -> "send money"
}

@Composable
fun PauseCard(request: CardRequest, helper: FamilyContact?, actions: CardActions) {
    val ink = Guardian.colors.onPauseSurface
    CardFrame(Icons.Rounded.PanTool, ink, Guardian.colors.pauseSurface) {
        Headline("Hold on a minute.", ink)
        Spacer(Modifier.height(16.dp))
        Body("Did someone on the phone ask you to open ${request.appLabel}?", ink)
        Spacer(Modifier.height(20.dp))
        Callout(
            buildString {
                if (request.duringCall) append("It's OK to hang up. ")
                append("Real banks, police, Medicare and tech companies never ask you to ${whatTheyAskFor(request.category)}.")
            },
            ink,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (helper != null) {
                BigButton("Call ${helper.name} first", { actions.callFamily(helper.phone) }, icon = Icons.Rounded.Call)
            }
            BigButton("I'm fine, continue", actions::continueAnyway, primary = helper == null, contentColor = if (helper != null) ink else null)
            TextButton(onClick = actions::goHome, modifier = Modifier.fillMaxWidth()) {
                Text("Go to my home screen", style = MaterialTheme.typography.labelLarge, color = ink)
            }
        }
    }
}

@Composable
fun RemoteAccessCard(request: CardRequest, helper: FamilyContact?, actions: CardActions) {
    val ink = Guardian.colors.onPauseSurface
    CardFrame(Icons.Rounded.PhonelinkLock, ink, Guardian.colors.pauseSurface) {
        Headline(
            if (request.knownRemoteAccessApp) "${request.appLabel} can let someone control your phone." else "${request.appLabel} asks to control your screen.",
            ink,
        )
        Spacer(Modifier.height(16.dp))
        Body(
            "Real companies like Microsoft, Apple, Amazon or your bank never ask you to install an app like this over the phone.",
            ink,
        )
        Spacer(Modifier.height(20.dp))
        Callout("If someone on the phone asked you to install it, it's OK to hang up.", ink)
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (helper != null) {
                BigButton("Call ${helper.name}", { actions.callFamily(helper.phone) }, icon = Icons.Rounded.Call)
            }
            BigButton("Remove ${request.appLabel}", actions::removeApp, icon = Icons.Rounded.Delete, primary = helper == null, contentColor = if (helper != null) ink else null)
            TextButton(onClick = actions::dismiss, modifier = Modifier.fillMaxWidth()) {
                Text("I trust this app", style = MaterialTheme.typography.labelLarge, color = ink)
            }
        }
    }
}

@Composable
fun SafeWordCard(config: DeviceConfig, actions: CardActions) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    var reveal by remember { mutableStateOf(false) }
    val word = config.protection.safeWord.word
    CardFrame(Icons.Rounded.Favorite, Guardian.colors.accent, MaterialTheme.colorScheme.primaryContainer) {
        Headline("Did the caller say they're family?", ink)
        Spacer(Modifier.height(16.dp))
        Body(
            "If anyone said they're a grandchild or relative in trouble, ask for your family safe word before doing anything. Your real family will know it.",
            ink,
        )
        if (word.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            AnimatedVisibility(reveal) {
                Callout("Your safe word is \"$word\"", ink)
            }
            if (!reveal) {
                BigButton("Show our safe word", { reveal = true }, primary = false, contentColor = ink)
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            config.family.filter { it.phone.isNotBlank() }.take(2).forEach { person ->
                BigButton("Call ${person.name}", { actions.callFamily(person.phone) }, icon = Icons.Rounded.Call)
            }
            BigButton("Everything's fine", actions::dismiss, primary = false, contentColor = ink)
        }
    }
}

@Composable
fun ReminderCard(request: CardRequest, actions: CardActions) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(request.reminderAt, locale) {
        Instant.ofEpochMilli(request.reminderAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", locale))
    }
    CardFrame(Icons.Rounded.Alarm, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) {
        Text(time, style = MaterialTheme.typography.titleLarge, color = ink)
        Spacer(Modifier.height(8.dp))
        Headline(request.reminderTitle, ink)
        if (request.reminderDetail.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Body(request.reminderDetail, ink)
        }
        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton("Done", actions::reminderDone, icon = Icons.Rounded.Check)
            BigButton("Remind me in 10 minutes", actions::snooze, icon = Icons.Rounded.Snooze, primary = false, contentColor = ink)
        }
    }
}
