package com.phx.yugcall.consent

import android.content.Context
import androidx.core.content.edit

/**
 * Tracks acceptance of this app's two disclaimers: the general one shown
 * before any analysis feature is reachable, and the call-protection-specific
 * one shown before the overlay — drawing over other apps, plus recording
 * consent law — is ever enabled. Kept as two separate flags because agreeing
 * to one is not agreeing to the other.
 *
 * The version suffix lets a rewrite of the disclaimer text invalidate old
 * acceptances without a schema migration. v2: the v1 copy claimed the overlay
 * works over speakerphone, which measurement on a Pixel 6a disproved, so v1
 * acceptances don't cover what users are now told.
 */
class ConsentStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("voxshield_consent", Context.MODE_PRIVATE)

    var hasAcceptedDisclaimer: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER, false)
        set(value) = prefs.edit { putBoolean(KEY_DISCLAIMER, value) }

    var hasAcceptedOverlayConsent: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit { putBoolean(KEY_OVERLAY, value) }

    private companion object {
        const val KEY_DISCLAIMER = "accepted_disclaimer_v2"
        const val KEY_OVERLAY = "accepted_overlay_consent_v2"
    }
}
