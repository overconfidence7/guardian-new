package app.guardianmode.core

/**
 * Lightweight phone-number helpers. Guardian Mode never needs full libphonenumber
 * parsing: it only has to decide whether two numbers are "the same line" and to
 * show a masked form to family members.
 */
object PhoneNumbers {

    /** Digits only, with a leading '+' kept when present. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * The significant tail used for matching. National numbers in most plans fit in
     * 10 digits, so comparing the last 10 avoids country-code and trunk-prefix
     * differences ("+1 555 123 4567" vs "(555) 123-4567" vs "15551234567").
     */
    fun matchKey(raw: String?): String {
        val digits = normalize(raw).trimStart('+')
        return if (digits.length > MATCH_DIGITS) digits.takeLast(MATCH_DIGITS) else digits
    }

    fun sameLine(a: String?, b: String?): Boolean {
        val ka = matchKey(a)
        val kb = matchKey(b)
        // Short codes and partial numbers must match exactly.
        return ka.length >= MIN_COMPARABLE && ka == kb
    }

    /** Masked form shared with family, e.g. "•••-•••-4567". Never the full number. */
    fun mask(raw: String?): String {
        val digits = normalize(raw).trimStart('+')
        if (digits.isEmpty()) return "Hidden number"
        if (digits.length <= 4) return "•".repeat(digits.length)
        return "•••-•••-" + digits.takeLast(4)
    }

    /** Human display for the parent's own screens (they may see their own call's number). */
    fun pretty(raw: String?): String {
        val digits = normalize(raw).trimStart('+')
        val national = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        return if (national.length == 10) {
            "(${national.substring(0, 3)}) ${national.substring(3, 6)}-${national.substring(6)}"
        } else {
            normalize(raw)
        }
    }

    private const val MATCH_DIGITS = 10
    private const val MIN_COMPARABLE = 3
}
