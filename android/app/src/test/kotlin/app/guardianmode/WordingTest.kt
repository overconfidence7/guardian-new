package app.guardianmode

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * qa/antigravity/cases/18-wording.md GM-COPY-001 and 14-privacy-security-policy.md GM-PRIV-024:
 * scans every string a parent can read (strings.xml, string literals in the UI code and the
 * notification, and the transparency text from core) for words the spec bans. Comments and
 * identifiers are skipped; only text inside quotes counts.
 */
class WordingTest {

    data class Literal(val file: String, val line: Int, val text: String)

    /** Gradle runs unit tests from android/app. */
    private val sources: List<File> = buildList {
        File("src/main/kotlin/app/guardianmode/ui").walkTopDown().filter { it.extension == "kt" }.forEach(::add)
        add(File("src/main/kotlin/app/guardianmode/protection/Notifications.kt"))
        File("../../core/src/main/kotlin/app/guardianmode/core/Transparency.kt").takeIf { it.exists() }?.let(::add)
    }

    private val stringsXml = File("src/main/res/values/strings.xml")

    /** String literals with their line numbers, outside comments. Templates are kept as typed. */
    private fun literals(file: File): List<Literal> {
        val text = file.readText()
        val out = mutableListOf<Literal>()
        var i = 0
        var line = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> { line++; i++ }
                text.startsWith("//", i) -> { while (i < text.length && text[i] != '\n') i++ }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    line += text.substring(i, end).count { it == '\n' }
                    i = end
                }
                c == '\'' -> { // char literal such as '"' or '\n'
                    val end = text.indexOf('\'', i + if (text.getOrNull(i + 1) == '\\') 3 else 2)
                    i = if (end < 0) i + 1 else end + 1
                }
                c == '"' -> {
                    val start = line
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                        if (text[i] == '\n') line++
                        sb.append(text[i]); i++
                    }
                    i++
                    out += Literal(file.path.removePrefix("../../"), start, sb.toString())
                }
                else -> i++
            }
        }
        return out
    }

    /** Text a person reads: has a space, or starts with a capital. Keys like "pause" or "home" don't count. */
    private fun isProse(s: String) = s.contains(' ') || (s.length > 1 && s[0].isUpperCase() && s.drop(1).any { it.isLowerCase() })

    private fun parentStrings(): List<Literal> {
        val fromKotlin = sources.flatMap(::literals).filter { isProse(it.text) }
        val fromXml = stringsXml.readLines().mapIndexedNotNull { idx, l ->
            Regex("<string name=\"[^\"]+\">(.*)</string>").find(l)?.let { Literal(stringsXml.path, idx + 1, it.groupValues[1]) }
        }
        return fromKotlin + fromXml
    }

    private fun hits(pattern: Regex) = parentStrings()
        .filter { pattern.containsMatchIn(it.text) }
        .map { "${it.file}:${it.line}  \"${it.text}\"" }

    @Test fun gm_copy_001_scannerSeesTheParentText() {
        // Guards the scanner itself: if these vanish, the paths or the parser broke.
        val all = parentStrings().map { it.text }
        assertWithMessage("scanned strings").that(all).contains("Hold on a minute.")
        assertWithMessage("scanned strings").that(all).contains("Guardian Mode is on")
        assertWithMessage("scanned strings").that(all.size).isGreaterThan(150)
    }

    /** S-08 named "scam" on the helper confirmation and "alert(s)" on the transparency screen; keep them out. */
    @Test fun gm_copy_001_noScamFraudDangerOrAlertInParentText() {
        val found = hits(Regex("(?i)\\b(scams?|scammers?|fraud\\w*|danger\\w*|alert\\w*)\\b"))
        assertWithMessage("Parent-facing strings using a banned word (docs/SPEC.md 2.9):\n" + found.joinToString("\n"))
            .that(found).isEmpty()
    }

    @Test fun gm_priv_024_noMonitorTrackOrSpyInParentText() {
        val found = hits(Regex("(?i)\\b(monitor\\w*|track(s|ed|ing)?|spy|spying|surveil\\w*)\\b|\\bwatch(es|ing)? (you|mom|dad)\\b"))
        assertWithMessage("Parent-facing strings that sound like surveillance (docs/POLICY.md 1.5):\n" + found.joinToString("\n"))
            .that(found).isEmpty()
    }
}
