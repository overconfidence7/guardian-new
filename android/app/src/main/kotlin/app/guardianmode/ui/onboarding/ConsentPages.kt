package app.guardianmode.ui.onboarding

import app.guardianmode.protection.Capability
import app.guardianmode.protection.Features

/** One plain-language consent screen. The parent says yes or no to each feature group. */
data class ConsentPage(
    val key: String,
    val title: String,
    val intro: String,
    val familySees: String,
    val neverShared: String,
    val capabilities: List<Capability>,
)

object ConsentPages {
    val all = listOf(
        ConsentPage(
            key = Features.HOME,
            title = "A simpler home screen",
            intro = "Big buttons for the people and apps you use most. Your family can set it up for you from their own phone, and you'll see every change they make.",
            familySees = "The list of apps on this phone, so they can add buttons for you.",
            neverShared = "What you do inside your apps.",
            capabilities = listOf(Capability.HOME_APP, Capability.CALL_PHONE),
        ),
        ConsentPage(
            key = Features.PAUSE,
            title = "Pause before sending money",
            intro = "If you open a bank, payment, gift-card or crypto app during or right after a call from a number you don't know, Guardian asks you to pause and talk to family first. It also warns you about apps that let strangers control your phone.",
            familySees = "That a pause card appeared and which app it was for.",
            neverShared = "Anything you type or see inside your apps.",
            capabilities = listOf(Capability.USAGE_ACCESS, Capability.OVERLAY),
        ),
        ConsentPage(
            key = Features.CALLS,
            title = "Quiet unknown callers",
            intro = "Calls from numbers you don't know won't ring out loud. They can still leave a voicemail. Family, your contacts and any numbers your family adds always ring. If you're on a very long call with a number you don't know, your family gets a heads up.",
            familySees = "That a long call with an unknown number is happening.",
            neverShared = "Who called, their number, or what was said.",
            capabilities = listOf(Capability.CALL_SCREENING, Capability.PHONE_STATE, Capability.CONTACTS),
        ),
        ConsentPage(
            key = Features.CHECK_IN,
            title = "A daily \"I'm OK\"",
            intro = "Using your phone like normal lets your family know you're OK. If it hasn't been used by late morning, they get a note. You don't have to do anything.",
            familySees = "Whether your phone has been used today.",
            neverShared = "What you used it for or where you are.",
            capabilities = emptyList(),
        ),
        ConsentPage(
            key = Features.REMINDERS,
            title = "Reminders",
            intro = "Big, clear reminders for medicine and appointments that your family sets up for you.",
            familySees = "Only if they ask: whether you tapped Done.",
            neverShared = "Your other notifications.",
            capabilities = listOf(Capability.NOTIFICATIONS, Capability.EXACT_ALARMS, Capability.FULL_SCREEN),
        ),
        ConsentPage(
            key = Features.BATTERY,
            title = "Keep Guardian awake",
            intro = "Some phones put apps to sleep to save battery. If Guardian is asleep, it can't warn you about anything. Keeping it awake uses very little battery.",
            familySees = "Nothing.",
            neverShared = "Nothing is shared by this.",
            capabilities = listOf(Capability.BATTERY),
        ),
    )

    /** What to do on the Android screen that opens next, in the parent's words. */
    fun instruction(c: Capability): String = when (c) {
        Capability.HOME_APP -> "Choose Guardian Mode, then tap \"Set as default\"."
        Capability.USAGE_ACCESS -> "Find Guardian Mode in the list and turn its switch on. Then press Back."
        Capability.OVERLAY -> "Turn on \"Allow display over other apps\". Then press Back."
        Capability.CALL_SCREENING -> "Choose Guardian Mode, then tap \"Set as default\"."
        Capability.PHONE_STATE -> "Tap \"Allow\"."
        Capability.CONTACTS -> "Tap \"Allow\"."
        Capability.CALL_PHONE -> "Tap \"Allow\"."
        Capability.NOTIFICATIONS -> "Tap \"Allow\"."
        Capability.EXACT_ALARMS -> "Turn the switch on. Then press Back."
        Capability.FULL_SCREEN -> "Turn the switch on. Then press Back."
        Capability.BATTERY -> "Tap \"Allow\"."
    }
}
