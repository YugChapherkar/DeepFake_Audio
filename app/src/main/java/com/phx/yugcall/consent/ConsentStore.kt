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
 * The "_v1" suffix lets a future rewrite of the disclaimer text invalidate
 * old acceptances by bumping to "_v2", without needing a schema migration.
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
        const val KEY_DISCLAIMER = "accepted_disclaimer_v1"
        const val KEY_OVERLAY = "accepted_overlay_consent_v1"
    }
}
