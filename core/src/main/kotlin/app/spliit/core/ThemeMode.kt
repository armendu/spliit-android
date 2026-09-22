package app.spliit.core

/**
 * How the app decides between light and dark.
 *
 * DESIGN.md has no token for this because the choice doesn't exist on iOS: Apple's convention is
 * "an app follows the system," so the iOS SettingsView offers no picker at all, see its own
 * comment, which explicitly has no equivalent of [LIGHT]/[DARK]. Android carries no such
 * convention, and the user asked for the choice directly, so offering it here is a deliberate,
 * documented divergence from the port's source of truth, not a stray feature cycle 1 wasn't asked
 * to build.
 */
public enum class ThemeMode {
    /** What a fresh install does today, unchanged, see [app.spliit.core.AppSettings]'s default. */
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}
