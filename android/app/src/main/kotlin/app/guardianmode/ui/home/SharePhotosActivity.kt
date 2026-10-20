package app.guardianmode.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import app.guardianmode.ui.MainActivity
import app.guardianmode.ui.components.BigButton
import app.guardianmode.ui.components.GuardianPage
import app.guardianmode.ui.theme.GuardianTheme
import kotlinx.coroutines.launch

/** Receives "Share" from any photo app and puts the pictures on the home screen's photo card. */
class SharePhotosActivity : ComponentActivity() {

    private val container get() = (application as GuardianApp).container
    /** Null while copying, then how many pictures were added. */
    private var added by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = sharedUris(intent)
        lifecycleScope.launch { added = container.photos.import(uris) }
        setContent {
            GuardianTheme(container.store.current.config.display) {
                val count = added
                GuardianPage(if (count == null) "Adding your photos" else if (count > 0) "Added to your photo card" else "That didn't work", null) {
                    Spacer(Modifier.height(8.dp))
                    when {
                        count == null -> CircularProgressIndicator()
                        count > 0 -> Text(
                            if (count == 1) "The picture is on your home screen now." else "The $count pictures are on your home screen now.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        else -> Text(
                            "Those pictures couldn't be added. Try sharing them again.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    if (count != null) {
                        BigButton("Go to my home screen", ::goHome)
                        Spacer(Modifier.height(12.dp))
                        BigButton("Back to the other app", ::finish, primary = false)
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
