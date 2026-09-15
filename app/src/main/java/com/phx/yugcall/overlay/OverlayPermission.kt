package com.phx.yugcall.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Wraps `SYSTEM_ALERT_WINDOW`, a special permission granted only through
 * Settings — there is no runtime request dialog for it.
 */
object OverlayPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${context.packageName}".toUri(),
    )
}
