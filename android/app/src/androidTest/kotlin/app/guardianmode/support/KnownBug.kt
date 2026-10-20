package app.guardianmode.support

/**
 * A test for behavior the spec wants but the app doesn't do yet. Pull-request CI skips
 * these (runner argument notAnnotation); the daily run includes them, and
 * qa/daily/known-bugs.json says which bug each one waits on. When one starts passing,
 * remove the annotation and its known-bugs.json entry in the same change as the fix.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class KnownBug(val id: String)
