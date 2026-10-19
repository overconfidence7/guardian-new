package app.guardianmode.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.guardianmode.cloud.InvitePreview
import app.guardianmode.protection.Capability
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.theme.Guardian

interface OnboardingActions {
    fun start()
    fun setupAlone()
    fun onCodeChange(value: String)
    fun scan()
    fun onScanned(raw: String)
    fun submitCode()
    fun confirm()
    fun decline()
    fun turnOn()
    fun requestNextMissing()
    fun notNow()
    fun nextPage()
    fun finishSetup()
}

@Composable
fun OnboardingFlow(
    stage: Stage,
    code: String,
    preview: InvitePreview?,
    busy: Boolean,
    error: String?,
    page: ConsentPage,
    pageNumber: Int,
    pageCount: Int,
    accepted: Boolean,
    refreshKey: Int,
    granted: (Capability) -> Boolean,
    actions: OnboardingActions,
) {
    when (stage) {
        Stage.WELCOME -> Welcome(actions)
        Stage.CODE -> CodeEntry(code, busy, error, actions)
        Stage.SCAN -> Scan(actions)
        Stage.CONFIRM -> Confirm(preview, busy, error, actions)
        Stage.CONSENT -> Consent(page, pageNumber, pageCount, accepted, refreshKey, granted, actions)
        Stage.DONE -> Done(actions)
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        content = content,
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Lead(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun Welcome(actions: OnboardingActions) {
    Page {
        Spacer(Modifier.height(40.dp))
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(32.dp))
        Title("Make your phone easier to use.")
        Spacer(Modifier.height(16.dp))
        Lead("Guardian Mode gives you a simpler home screen and lets your family help with your phone from wherever they are. You decide what gets turned on, and you can always see what your family can see.")
        Spacer(Modifier.height(40.dp))
        BigButton("Set up with my family", actions::start)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = actions::setupAlone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Set up on my own for now", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CodeEntry(code: String, busy: Boolean, error: String?, actions: OnboardingActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Page {
        Title("Enter your setup code")
        Spacer(Modifier.height(12.dp))
        Lead("Your family member can see a 6-letter code on their phone or computer.")
        Spacer(Modifier.height(28.dp))
        BasicTextField(
            value = code,
            onValueChange = actions::onCodeChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { actions.submitCode() }),
            modifier = Modifier.focusRequester(focus).semantics { contentDescription = "Setup code" },
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val ch = code.getOrNull(i)?.toString() ?: ""
                        val active = i == code.length
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(0.8f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Guardian.colors.tileSurface)
                                .border(if (active) 3.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else Guardian.colors.outline, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                ch,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
        )
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(28.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            BigButton("Continue", actions::submitCode)
            Spacer(Modifier.height(12.dp))
            BigButton("Scan the code instead", actions::scan, icon = Icons.Rounded.QrCodeScanner, primary = false)
        }
    }
}

@Composable
private fun Scan(actions: OnboardingActions) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) launcher.launch(Manifest.permission.CAMERA) }
    Page {
        Title("Point the camera at the code")
        Spacer(Modifier.height(12.dp))
        Lead("Hold the phone steady over the square code on your family member's screen.")
        Spacer(Modifier.height(24.dp))
        if (allowed) {
            QrScanner(
                onCode = actions::onScanned,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Guardian.dimens.tileCorner)),
            )
        } else {
            Lead("Guardian needs the camera to read the code. You can type the code instead.")
        }
        Spacer(Modifier.height(24.dp))
        BigButton("Type the code instead", { actions.onCodeChange(""); actions.start() }, primary = false)
    }
}

@Composable
private fun Confirm(preview: InvitePreview?, busy: Boolean, error: String?, actions: OnboardingActions) {
    val helper = preview?.helperName?.ifBlank { null } ?: "Your family member"
    Page {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Guardian.colors.accent, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Title("$helper would like to be your helper.")
        Spacer(Modifier.height(16.dp))
        Lead("Helpers can set up your home screen, send you photos, and get a note if something looks like a scam. Next you'll pick what to turn on, and you can remove a helper whenever you want.")
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(32.dp))
        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            BigButton("Yes, ${preview?.helperName?.ifBlank { null } ?: "they"} can help", actions::confirm)
            Spacer(Modifier.height(12.dp))
            BigButton("No thanks", actions::decline, primary = false)
        }
    }
}

@Composable
private fun Consent(
    page: ConsentPage,
    number: Int,
    count: Int,
    accepted: Boolean,
    refreshKey: Int,
    granted: (Capability) -> Boolean,
    actions: OnboardingActions,
) {
    val status = remember(page.key, refreshKey) { page.capabilities.associateWith(granted) }
    val missing = status.filterValues { !it }.keys
    Page {
        LinearProgressIndicator(
            progress = { number.toFloat() / count },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Guardian.colors.outline,
        )
        Spacer(Modifier.height(8.dp))
        Text("Step $number of $count", style = MaterialTheme.typography.labelMedium, color = Guardian.colors.muted)
        Spacer(Modifier.height(20.dp))
        Title(page.title)
        Spacer(Modifier.height(12.dp))
        Lead(page.intro)
        Spacer(Modifier.height(20.dp))
        InfoRow("Your family will see", page.familySees, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(10.dp))
        InfoRow("Never shared", page.neverShared, Guardian.colors.tileSurface, MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(28.dp))

        if (!accepted) {
            BigButton("Turn on", actions::turnOn)
            Spacer(Modifier.height(12.dp))
            BigButton("Not now", actions::notNow, primary = false)
        } else if (missing.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Guardian.colors.calm, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text("All set", style = MaterialTheme.typography.titleLarge, color = Guardian.colors.calm)
            }
            Spacer(Modifier.height(20.dp))
            BigButton("Next", actions::nextPage)
        } else {
            Text("Android will ask you to allow a few things.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            status.forEach { (cap, ok) ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (ok) "Done" else "Not yet",
                        tint = if (ok) Guardian.colors.calm else Guardian.colors.muted,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(cap.plainName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            Spacer(Modifier.height(16.dp))
            val next = missing.first()
            InfoRow("On the next screen", ConsentPages.instruction(next), Guardian.colors.pauseSurface, Guardian.colors.onPauseSurface)
            Spacer(Modifier.height(20.dp))
            BigButton("Continue", actions::requestNextMissing, icon = Icons.Rounded.Check)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = actions::nextPage, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Skip for now", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, text: String, background: Color, ink: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, Guardian.colors.outline, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ink)
    }
}

@Composable
private fun Done(actions: OnboardingActions) {
    Page {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(Guardian.colors.calm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(32.dp))
        Title("You're all set.")
        Spacer(Modifier.height(16.dp))
        Lead("Your new home screen is ready. Tap the shield at the top whenever you want to see what your family can see or change anything.")
        Spacer(Modifier.height(40.dp))
        BigButton("Go to my home screen", actions::finishSetup)
    }
}
