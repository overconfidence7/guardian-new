package app.guardianmode

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import app.guardianmode.core.DeviceConfig
import app.guardianmode.data.DefaultConfig
import app.guardianmode.data.LocalState
import app.guardianmode.data.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Small helpers shared by the Compose and store tests. */
object UiChecks {
    /** Every piece of text and every content description on screen, across all windows (dialogs too). */
    fun SemanticsNodeInteractionsProvider.allText(): List<String> {
        val nodes = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        return nodes.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }

    /** Top edge of the first node showing [text], in root pixels, for checking the order of sections. */
    fun SemanticsNodeInteractionsProvider.topOf(text: String): Float =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(text))), useUnmergedTree = true)
            .fetchSemanticsNodes().first().positionInRoot.y

    /** Clickable nodes smaller than [minDp] in either direction, described for a failure message. */
    fun SemanticsNodeInteractionsProvider.smallTargets(density: Density, minDp: Float): List<String> =
        onAllNodes(hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).mapNotNull { node ->
            // The whole layout node, not boundsInRoot: bounds are clipped to what is scrolled into
            // view, and Material puts its 48 dp minimum touch size outside the semantics modifier.
            val w = node.layoutInfo.width / density.density
            val h = node.layoutInfo.height / density.density
            if (w + 0.5f < minDp || h + 0.5f < minDp) "${label(node)} (${w.toInt()}x${h.toInt()} dp)" else null
        }

    fun label(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsActions.OnClick)?.label
            ?: "node ${node.id}")

    /** Text nodes whose laid-out text doesn't fit the space it was given (cut off or ellipsized). */
    fun SemanticsNodeInteractionsProvider.clippedText(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .mapNotNull { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                // didOverflowWidth is also true for short text that simply doesn't fill its row,
                // so look at what was actually cut: lines past the edge, height, max lines, ellipsis.
                val clipped = results.any { r ->
                    r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
                        (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1)) ||
                        (0 until r.lineCount).any { l -> r.getLineRight(l) - r.getLineLeft(l) > r.size.width + 1f }
                }
                if (clipped) label(node) else null
            }

    /** Nodes with any text that reach past the right or left edge of the screen. */
    fun SemanticsNodeInteractionsProvider.offScreenSideways(widthPx: Float): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .filter { it.positionInRoot.x + it.size.width > widthPx + 1f || it.positionInRoot.x < -1f }
            .map { label(it) }

    /** Solo state with a configured family, as Track A's state-family.json would give. */
    fun familyState(config: DeviceConfig = Fixtures.config): LocalState =
        LocalState(config = config, onboardingDone = true)

    fun soloState(): LocalState = LocalState(config = DefaultConfig.config, onboardingDone = true)

    /**
     * A context whose private files live in [dir], so a test can open the app's real LocalStore
     * (same serializer, same corruption handler) without clashing with the one GuardianApp opened.
     */
    fun filesIn(base: Context, dir: File): Context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = dir.apply { mkdirs() }
    }

    /** The state file LocalStore uses under [dir]. */
    fun stateFile(dir: File) = File(dir, "datastore/guardian_state.json")

    /** Opens the app's LocalStore on [dir]; close it with [close] before opening the same file again. */
    fun openStore(base: Context, dir: File): Pair<LocalStore, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return LocalStore(filesIn(base, dir), scope) to scope
    }

    fun close(scope: CoroutineScope) = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
}
