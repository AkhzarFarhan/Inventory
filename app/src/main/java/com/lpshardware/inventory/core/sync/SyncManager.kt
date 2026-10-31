package com.lpshardware.inventory.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lpshardware.inventory.core.auth.GoogleAuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

data class CloudSyncConfig(
    val firebaseProjectId: String,
    val firebaseApiKey: String,
    val webClientId: String = "",
    val shopCollectionId: String = "lps_hardware",
    val sellerWhatsAppNumber: String = "",
    val lastSyncedEpochMs: Long = 0L
) {
    val isConfigured: Boolean
        get() = firebaseProjectId.isNotBlank()
}

object SyncManager {
    private const val PREFS_NAME = "lps_hardware_sync_prefs"
    private const val KEY_SHOP_ID = "shop_collection_id"
    private const val KEY_SELLER_NAME = "seller_name"
    private const val KEY_SELLER_WHATSAPP = "seller_whatsapp_number"
    private const val KEY_LAST_SYNC = "last_synced_epoch_ms"
    private const val WORK_NAME = "lps_firestore_delta_sync"

    // Built-in credentials from C:\GitHub\Inventory\app\google-services.json (package: com.lpshardware.inventory)
    private const val BUILTIN_PROJECT_ID = "ubiverse-app"
    private const val BUILTIN_API_KEY = "AIzaSyB2eAYFX0LJ6irFyg4S4j-ULfHkAl3W_uY"
    private const val BUILTIN_WEB_CLIENT_ID = "94340852366-9jsh9b504m6ahf0s694umo5b4pc7k773.apps.googleusercontent.com"

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncMessage = MutableStateFlow("Cloud Ready")
    val lastSyncMessage: StateFlow<String> = _lastSyncMessage.asStateFlow()

    private val _sellerNameFlow = MutableStateFlow("LPS Hardware")
    val sellerNameFlow: StateFlow<String> = _sellerNameFlow.asStateFlow()

    private val _sellerWhatsAppFlow = MutableStateFlow("")
    val sellerWhatsAppFlow: StateFlow<String> = _sellerWhatsAppFlow.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        _sellerNameFlow.value = getSellerName(context)
        _sellerWhatsAppFlow.value = getSellerWhatsAppNumber(context)
    }

    fun getSellerName(context: Context): String =
        prefs(context).getString(KEY_SELLER_NAME, "LPS Hardware")?.trim().orEmpty().ifBlank { "LPS Hardware" }

    fun saveSellerName(context: Context, rawName: String) {
        val cleaned = rawName.trim().ifBlank { "LPS Hardware" }
        prefs(context).edit().putString(KEY_SELLER_NAME, cleaned).apply()
        _sellerNameFlow.value = cleaned
    }

    fun getSellerWhatsAppNumber(context: Context): String =
        prefs(context).getString(KEY_SELLER_WHATSAPP, "")?.trim().orEmpty()

    fun saveSellerWhatsAppNumber(context: Context, rawPhone: String) {
        val cleaned = rawPhone.trim()
        prefs(context).edit().putString(KEY_SELLER_WHATSAPP, cleaned).apply()
        _sellerWhatsAppFlow.value = cleaned
    }

    fun getConfig(context: Context): CloudSyncConfig {
        val p = prefs(context)
        val signedInUser = GoogleAuthManager.currentUser.value
        val activeShopNamespace = signedInUser?.cloudAccountDocumentId
            ?: p.getString(KEY_SHOP_ID, "lps_hardware")?.trim().orEmpty().ifEmpty { "lps_hardware" }

        return CloudSyncConfig(
            firebaseProjectId = BUILTIN_PROJECT_ID,
            firebaseApiKey = BUILTIN_API_KEY,
            webClientId = BUILTIN_WEB_CLIENT_ID,
            shopCollectionId = activeShopNamespace,
            sellerWhatsAppNumber = p.getString(KEY_SELLER_WHATSAPP, "")?.trim().orEmpty(),
            lastSyncedEpochMs = p.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    /**
     * Called immediately after the user signs in with Google on a device.
     * Binds their cloud collection key and triggers an immediate bidirectional Cloud Pull + Push.
     */
    fun onUserSignedInTriggerFullSync(context: Context, userAccountCollectionKey: String) {
        prefs(context).edit()
            .putString(KEY_SHOP_ID, userAccountCollectionKey)
            .putLong(KEY_LAST_SYNC, 0L)
            .apply()
        scheduleDeltaSync(context)
    }

    fun updateLastSyncTimestamp(context: Context, timestampMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, timestampMs).apply()
    }

    internal fun setSyncState(syncing: Boolean, message: String) {
        _isSyncing.value = syncing
        _lastSyncMessage.value = message
    }

    /**
     * Enqueues a low-RAM background WorkManager task that runs automatically as soon as
     * internet connectivity (`NetworkType.CONNECTED`) is available or restored.
     */
    fun scheduleDeltaSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FirestoreDeltaSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
