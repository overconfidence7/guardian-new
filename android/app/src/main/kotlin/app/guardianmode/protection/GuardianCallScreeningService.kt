package app.guardianmode.protection

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.util.Log
import androidx.core.content.ContextCompat
import app.guardianmode.GuardianApp
import app.guardianmode.core.ScreeningVerdict

/**
 * Android asks this service about each incoming call before it rings. It must answer
 * within 5 seconds, so everything here is local and synchronous. The number is used to
 * decide and is never stored or sent anywhere.
 */
class GuardianCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val allow = CallResponse.Builder().build()
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, allow)
            return
        }
        val container = (application as GuardianApp).container
        val response = runCatching {
            val number = details.handle?.schemeSpecificPart
            val verificationFailed = Build.VERSION.SDK_INT >= 30 &&
                details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
            val decision = container.brain.screen(number, verificationFailed, isPhoneContact(number))
            if (container.store.current.isPaused(System.currentTimeMillis())) {
                allow
            } else {
                when (decision.verdict) {
                    ScreeningVerdict.ALLOW -> allow
                    ScreeningVerdict.SILENCE -> CallResponse.Builder().setSilenceCall(true).build()
                    ScreeningVerdict.SEND_TO_VOICEMAIL -> CallResponse.Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipNotification(true)
                        .setSkipCallLog(false)
                        .build()
                }
            }
        }.getOrElse {
            // Never block a call because Guardian had a problem.
            Log.e(TAG, "Screening failed; letting the call ring", it)
            allow
        }
        respondToCall(details, response)
    }

    private fun isPhoneContact(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "GuardianScreening"
    }
}
