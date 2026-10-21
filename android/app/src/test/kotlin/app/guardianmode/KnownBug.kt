package app.guardianmode

/**
 * JUnit category for a test of behavior the spec wants but the app doesn't do yet:
 * `@Category(KnownBug::class)`. Pull-request CI skips it; the daily run includes it, and
 * qa/daily/known-bugs.json says which bug it waits on. When it starts passing, drop the
 * category and the known-bugs.json entry in the same change as the fix.
 */
interface KnownBug
