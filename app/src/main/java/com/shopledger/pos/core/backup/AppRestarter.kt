package com.shopledger.pos.core.backup

import android.content.Context
import android.content.Intent

/** Restarts the app process so every component reopens the (restored or repaired) database. */
object AppRestarter {
    /** Returns false (and does nothing) when the app has no launch intent; otherwise does not return. */
    fun restart(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
        return true
    }
}
