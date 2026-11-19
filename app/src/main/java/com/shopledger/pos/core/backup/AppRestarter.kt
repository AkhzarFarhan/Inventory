package com.shopledger.pos.core.backup

import android.content.Context
import android.content.Intent

/** Restarts the app process so every component reopens the (restored or repaired) database. */
object AppRestarter {
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
