package app.guardianmode.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap

/** The installed app's own icon, so the parent recognises it. Returns false if it can't be loaded. */
@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier): Boolean {
    val context = LocalContext.current
    val bitmap: ImageBitmap? = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier.size(size))
    return bitmap != null
}
