package app.guardianmode.ui.help

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import app.guardianmode.core.EventType
import app.guardianmode.core.FamilyContact
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.Severity
import app.guardianmode.ui.Launch
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.components.GuardianPage
import app.guardianmode.ui.theme.Guardian
import app.guardianmode.ui.theme.GuardianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private enum class HelpStage { INTRO, PREVIEW, SENDING, SENT, FAILED }

/**
 * "Help me": the parent sees exactly what will be sent before anything leaves the phone.
 * Android shows its own screen-recording consent every single time.
 */
class HelpActivity : ComponentActivity() {
    private val container get() = (application as GuardianApp).container
    private var stage by mutableStateOf(HelpStage.INTRO)
    private var previewPath by mutableStateOf<String?>(null)
    private var note by mutableStateOf(NOTES.first())

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
            // Step out of the way so the picture shows what was on screen before Help.
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val state = container.store.current
            GuardianTheme(state.config.display) {
                HelpContent(state.config.primaryHelper, state.pairing != null)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_PREVIEW)?.let {
            previewPath = it
            stage = HelpStage.PREVIEW
        }
    }

    private fun takePicture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        consent.launch(manager.createScreenCaptureIntent())
    }

    private fun send() {
        val path = previewPath ?: return
        stage = HelpStage.SENDING
        lifecycleScope.launch {
            val id = UUID.randomUUID().toString()
            val bytes = withContext(Dispatchers.IO) { runCatching { File(path).readBytes() }.getOrNull() }
            val uploaded = bytes?.let { container.cloud.uploadHelpScreenshot(it, id) }
            if (uploaded == null) {
                stage = HelpStage.FAILED
                return@launch
            }
            container.store.recordShared(
                GuardianEvent(id, EventType.HELP_REQUESTED, System.currentTimeMillis(), Severity.URGENT, mapOf("path" to uploaded, "note" to note)),
            )
            container.cloud.flushOutbox()
            withContext(Dispatchers.IO) { File(path).delete() }
            stage = HelpStage.SENT
        }
    }

    private fun discard() {
        previewPath?.let { File(it).delete() }
        finish()
    }

    @Composable
    private fun HelpContent(helper: FamilyContact?, paired: Boolean) {
        val name = helper?.name ?: "your family"
        when (stage) {
            HelpStage.INTRO -> GuardianPage("Ask $name for help", onBack = ::finish) {
                Text(
                    if (paired) {
                        "We'll take a picture of your screen so $name can see what's confusing. You'll see the picture before it's sent."
                    } else if (helper != null) {
                        "You can call ${helper.name} for help."
                    } else {
                        "No one is set up to help yet. You can still call someone you trust, and they can help you add them in Guardian's settings."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (paired) BigButton("Take a picture of my screen", ::takePicture, icon = Icons.Rounded.CameraAlt)
                    if (helper != null) {
                        BigButton("Call ${helper.name}", { Launch.call(this@HelpActivity, helper.phone) }, icon = Icons.Rounded.Call, primary = !paired)
                    } else if (!paired) {
                        BigButton("Call someone", { Launch.dialer(this@HelpActivity) }, icon = Icons.Rounded.Call)
                    }
                }
            }
            HelpStage.PREVIEW -> GuardianPage("Send this to $name?", onBack = ::discard) {
                val bitmap = remember(previewPath) { previewPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
                if (bitmap != null) {
                    Image(
                        bitmap, contentDescription = "Picture of your screen",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, Guardian.colors.outline, RoundedCornerShape(20.dp)),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("Add a note", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                NOTES.forEach { option ->
                    FilterChip(
                        selected = note == option,
                        onClick = { note = option },
                        label = { Text(option, style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                BigButton("Send to $name", ::send, icon = Icons.AutoMirrored.Rounded.Send)
                Spacer(Modifier.height(12.dp))
                BigButton("Don't send", ::discard, primary = false)
            }
            HelpStage.SENDING -> GuardianPage("Sending...", onBack = null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            HelpStage.SENT -> GuardianPage("Sent", onBack = ::finish) {
                Text("$name will see your picture and your note.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(24.dp))
                if (helper != null) BigButton("Call ${helper.name} now", { Launch.call(this@HelpActivity, helper.phone) }, icon = Icons.Rounded.Call)
                Spacer(Modifier.height(12.dp))
                BigButton("Done", ::finish, primary = helper == null)
            }
            HelpStage.FAILED -> GuardianPage("It didn't send", onBack = ::discard) {
                Text("The phone may not be connected to the internet. You can try again, or call instead.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(24.dp))
                BigButton("Try again", ::send)
                Spacer(Modifier.height(12.dp))
                if (helper != null) BigButton("Call ${helper.name}", { Launch.call(this@HelpActivity, helper.phone) }, icon = Icons.Rounded.Call, primary = false)
            }
        }
    }

    companion object {
        private const val EXTRA_PREVIEW = "preview"
        val NOTES = listOf("I'm stuck", "Is this real?", "Please call me")

        fun previewIntent(context: Context, path: String): Intent =
            Intent(context, HelpActivity::class.java)
                .putExtra(EXTRA_PREVIEW, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
