package app.guardianmode.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.guardianmode.core.LockCode
import app.guardianmode.protection.UnlockResult
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One big box for typing the lock code, digits hidden. */
@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val focus = remember { FocusRequester() }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(Unit) { if (!inPreview) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it in '0'..'9' }.take(LockCode.MAX_LENGTH)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 12.sp, textAlign = TextAlign.Center, color = color),
        cursorBrush = SolidColor(color),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = label }
            .border(2.dp, color.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .heightIn(min = 80.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

fun unlockMessage(result: UnlockResult, zone: ZoneId = ZoneId.systemDefault()): String? = when (result) {
    UnlockResult.Unlocked -> null
    UnlockResult.Wrong -> "That's not the code. Try again."
    is UnlockResult.Wait -> {
        val at = Instant.ofEpochMilli(result.retryAt).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))
        "That's too many tries. You can try again at $at."
    }
}

/** Asks for the code and calls [onUnlocked] once it's right. */
@Composable
fun UnlockForm(
    prompt: String,
    tryUnlock: suspend (String) -> UnlockResult,
    onUnlocked: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onBackground,
    initialError: String? = null,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    val scope = rememberCoroutineScope()
    val submit = {
        if (code.length >= LockCode.MIN_LENGTH) {
            scope.launch {
                val result = tryUnlock(code)
                code = ""
                error = unlockMessage(result)
                if (result == UnlockResult.Unlocked) onUnlocked()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(prompt, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        CodeField(code, { code = it; error = null }, { submit() }, "Lock code", color = color)
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        BigButton("Unlock", { submit() })
    }
}
