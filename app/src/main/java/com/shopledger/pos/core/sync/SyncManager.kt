package com.shopledger.pos.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.cipher.CipherMode
import com.shopledger.pos.core.cipher.CostCipherEngine
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.core.nlp.TradeTaxonomyRegistry
import com.shopledger.pos.data.local.LpsDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CloudSyncConfig(
    val firebaseProjectId: String,
    val firebaseApiKey: String,
    val webClientId: String = "",
    val shopCollectionId: String = "default_shop",
    val sellerWhatsAppNumber: String = "",
    val lastSyncedEpochMs: Long = 0L
) {
    val isConfigured: Boolean
        get() = firebaseProjectId.isNotBlank()
}

object SyncManager {
    private const val LEGACY_PREFS_NAME = "lps_hardware_sync_prefs"
    private const val PREFS_NAME = "shopledger_sync_prefs"
    private const val KEY_SHOP_ID = "shop_collection_id"
    private const val KEY_SELLER_NAME = "seller_name"
    private const val KEY_SELLER_WHATSAPP = "seller_whatsapp_number"
    private const val KEY_LAST_SYNC = "last_synced_epoch_ms"
    private const val KEY_VERTICAL = "shop_vertical"
    private const val KEY_CIPHER_MODE = "cipher_mode"
    private const val KEY_CIPHER_WORD_10 = "cipher_word_10"
    private const val KEY_CUSTOM_DIGIT_MAP = "custom_digit_map"
    private const val KEY_OWNER_PIN_HASH = "owner_pin_hash"
    private const val KEY_UPI_ID = "upi_id"
    private const val KEY_GSTIN = "gstin"
    private const val KEY_GST_ENABLED = "gst_enabled"
    private const val KEY_DEVICE_SHORT_CODE = "device_short_code"
    private const val KEY_SETUP_COMPLETED = "setup_completed"
    private const val SEED_PREFS_NAME = "lps_seed_prefs"
    private const val SEED_DONE_KEY = "catalog_seeded_v1"
    private val EXISTING_INSTALL_KEYS = listOf(
        KEY_SELLER_NAME, KEY_SHOP_ID, KEY_SELLER_WHATSAPP, KEY_LAST_SYNC, KEY_VERTICAL, KEY_CIPHER_MODE,
        KEY_CIPHER_WORD_10, KEY_CUSTOM_DIGIT_MAP, KEY_OWNER_PIN_HASH, KEY_UPI_ID, KEY_GSTIN, KEY_GST_ENABLED,
        KEY_DEVICE_SHORT_CODE,
    )
    private const val WORK_NAME = "shopledger_firestore_delta_sync"
    private const val CLOUD_SYNC_ENABLED = false

    // Built-in Firebase web client config from google-services.json
    private const val BUILTIN_PROJECT_ID = "ubiverse-app"
    private const val BUILTIN_API_KEY = "AIzaSyB2eAYFX0LJ6irFyg4S4j-ULfHkAl3W_uY"
    private const val BUILTIN_WEB_CLIENT_ID = "94340852366-9jsh9b504m6ahf0s694umo5b4pc7k773.apps.googleusercontent.com"

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncMessage = MutableStateFlow("Cloud Ready")
    val lastSyncMessage: StateFlow<String> = _lastSyncMessage.asStateFlow()

    private val _sellerNameFlow = MutableStateFlow("ShopLedger POS")
    val sellerNameFlow: StateFlow<String> = _sellerNameFlow.asStateFlow()

    private val _sellerWhatsAppFlow = MutableStateFlow("")
    val sellerWhatsAppFlow: StateFlow<String> = _sellerWhatsAppFlow.asStateFlow()

    private val _shopVerticalFlow = MutableStateFlow(ShopVertical.HARDWARE)
    val shopVerticalFlow: StateFlow<ShopVertical> = _shopVerticalFlow.asStateFlow()

    private val _cipherModeFlow = MutableStateFlow(CipherMode.CUSTOM_CODE)
    val cipherModeFlow: StateFlow<CipherMode> = _cipherModeFlow.asStateFlow()

    private val _cipherWord10Flow = MutableStateFlow(CostCipherEngine.DEFAULT_WORD_10)
    val cipherWord10Flow: StateFlow<String> = _cipherWord10Flow.asStateFlow()

    private val _customDigitMapFlow = MutableStateFlow(CostCipherEngine.defaultDigitMap())
    val customDigitMapFlow: StateFlow<Map<Char, String>> = _customDigitMapFlow.asStateFlow()

    private val _ownerPinHashFlow = MutableStateFlow("")
    val ownerPinHashFlow: StateFlow<String> = _ownerPinHashFlow.asStateFlow()

    private val _upiIdFlow = MutableStateFlow("")
    val upiIdFlow: StateFlow<String> = _upiIdFlow.asStateFlow()

    private val _gstinFlow = MutableStateFlow("")
    val gstinFlow: StateFlow<String> = _gstinFlow.asStateFlow()

    private val _gstEnabledFlow = MutableStateFlow(false)
    val gstEnabledFlow: StateFlow<Boolean> = _gstEnabledFlow.asStateFlow()

    private val _deviceShortCodeFlow = MutableStateFlow("")
    val deviceShortCodeFlow: StateFlow<String> = _deviceShortCodeFlow.asStateFlow()

    private val _isSetupCompletedFlow = MutableStateFlow(true)
    val isSetupCompletedFlow: StateFlow<Boolean> = _isSetupCompletedFlow.asStateFlow()

    private fun prefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.contains(KEY_SELLER_NAME)) {
            val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                p.edit().apply {
                    putString(KEY_SHOP_ID, legacy.getString(KEY_SHOP_ID, "default_shop"))
                    putString(KEY_SELLER_NAME, legacy.getString(KEY_SELLER_NAME, "ShopLedger POS"))
                    putString(KEY_SELLER_WHATSAPP, legacy.getString(KEY_SELLER_WHATSAPP, ""))
                    putLong(KEY_LAST_SYNC, legacy.getLong(KEY_LAST_SYNC, 0L))
                }.apply()
            }
        }
        return p
    }

    fun init(context: Context) {
        val p = prefs(context)
        _sellerNameFlow.value = getSellerName(context)
        _sellerWhatsAppFlow.value = getSellerWhatsAppNumber(context)

        val vertical = ShopVertical.fromNameOrDefault(p.getString(KEY_VERTICAL, ShopVertical.HARDWARE.name))
        _shopVerticalFlow.value = vertical
        TradeTaxonomyRegistry.setActiveVertical(vertical)

        val cipherMode = CipherMode.fromStoredName(p.getString(KEY_CIPHER_MODE, CipherMode.CUSTOM_CODE.name))
        val word10 = CostCipherEngine.normalizedWord10(p.getString(KEY_CIPHER_WORD_10, CostCipherEngine.DEFAULT_WORD_10).orEmpty())
        val serializedMap = p.getString(KEY_CUSTOM_DIGIT_MAP, "").orEmpty()
        val digitMap = if (serializedMap.isNotBlank()) {
            CostCipherEngine.parseDigitMap(serializedMap)
        } else {
            CostCipherEngine.word10ToDigitMap(word10)
        }
        _cipherModeFlow.value = cipherMode
        _cipherWord10Flow.value = CostCipherEngine.digitMapToWord10(digitMap)
        _customDigitMapFlow.value = digitMap
        CostCipherEngine.setActiveCustomDigitMap(cipherMode, digitMap)

        _ownerPinHashFlow.value = p.getString(KEY_OWNER_PIN_HASH, "").orEmpty()
        _upiIdFlow.value = p.getString(KEY_UPI_ID, "").orEmpty()
        _gstinFlow.value = p.getString(KEY_GSTIN, "").orEmpty()
        _gstEnabledFlow.value = p.getBoolean(KEY_GST_ENABLED, false)
        _deviceShortCodeFlow.value = p.getString(KEY_DEVICE_SHORT_CODE, "").orEmpty()
        _isSetupCompletedFlow.value = resolveSetupCompleted(context.applicationContext, p)
    }

    /**
     * First-run rule. An explicit stored flag always wins. Without one, the install is treated as an EXISTING
     * install (wizard never shown, sample data never seeded) when ANY earlier sign of use is present: a stored seller
     * name or any other shop setting, a legacy settings file, the starter-catalog "seeded" flag every earlier version
     * wrote on its first launch, or an existing database file. Only a completely untouched install is "fresh".
     * The decision (true for existing, false for fresh) is persisted at once so later changes cannot flip it.
     */
    private fun resolveSetupCompleted(appContext: Context, p: SharedPreferences): Boolean {
        if (p.contains(KEY_SETUP_COMPLETED)) return p.getBoolean(KEY_SETUP_COMPLETED, true)
        val existing = EXISTING_INSTALL_KEYS.any { p.contains(it) } ||
            appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).all.isNotEmpty() ||
            appContext.getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE).contains(SEED_DONE_KEY) ||
            appContext.getDatabasePath(LpsDatabase.DB_NAME).exists()
        // Persist BOTH outcomes: once the database file exists (created later in this very launch) the "fresh" signal is
        // gone, so a fresh install must be stored as "not completed" now or the next cold start would skip the wizard.
        p.edit().putBoolean(KEY_SETUP_COMPLETED, existing).commit() // synchronous: must be on disk before the DB file appears
        return existing
    }

    fun saveSetupCompleted(context: Context, completed: Boolean) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
        _isSetupCompletedFlow.value = completed
    }

    fun isSetupCompleted(): Boolean = _isSetupCompletedFlow.value

    fun getSellerName(context: Context): String =
        prefs(context).getString(KEY_SELLER_NAME, "ShopLedger POS")?.trim().orEmpty().ifBlank { "ShopLedger POS" }

    fun saveSellerName(context: Context, rawName: String) {
        val cleaned = rawName.trim().ifBlank { "ShopLedger POS" }
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

    fun saveShopVertical(context: Context, vertical: ShopVertical) {
        prefs(context).edit().putString(KEY_VERTICAL, vertical.name).apply()
        _shopVerticalFlow.value = vertical
        TradeTaxonomyRegistry.setActiveVertical(vertical)
    }

    fun getActiveVertical(): ShopVertical = _shopVerticalFlow.value

    fun saveCipherConfig(context: Context, mode: CipherMode, word10: String = _cipherWord10Flow.value) {
        val normalizedWord = CostCipherEngine.normalizedWord10(word10)
        val digitMap = CostCipherEngine.word10ToDigitMap(normalizedWord)
        prefs(context).edit()
            .putString(KEY_CIPHER_MODE, mode.name)
            .putString(KEY_CIPHER_WORD_10, normalizedWord)
            .putString(KEY_CUSTOM_DIGIT_MAP, CostCipherEngine.serializeDigitMap(digitMap))
            .apply()
        _cipherModeFlow.value = mode
        _cipherWord10Flow.value = normalizedWord
        _customDigitMapFlow.value = digitMap
        CostCipherEngine.setActiveCustomDigitMap(mode, digitMap)
    }

    fun saveCustomDigitMap(context: Context, mode: CipherMode, digitMap: Map<Char, String>) {
        val sanitized = CostCipherEngine.normalizeDigitMap(digitMap)
        val derivedWord = CostCipherEngine.digitMapToWord10(sanitized)
        prefs(context).edit()
            .putString(KEY_CIPHER_MODE, mode.name)
            .putString(KEY_CIPHER_WORD_10, derivedWord)
            .putString(KEY_CUSTOM_DIGIT_MAP, CostCipherEngine.serializeDigitMap(sanitized))
            .apply()
        _cipherModeFlow.value = mode
        _cipherWord10Flow.value = derivedWord
        _customDigitMapFlow.value = sanitized
        CostCipherEngine.setActiveCustomDigitMap(mode, sanitized)
    }

    fun saveOwnerPin(context: Context, rawPin: String) {
        val hash = if (rawPin.isBlank()) "" else CostCipherEngine.hashPin(rawPin)
        prefs(context).edit().putString(KEY_OWNER_PIN_HASH, hash).apply()
        _ownerPinHashFlow.value = hash
    }

    fun verifyOwnerPin(candidatePin: String): Boolean {
        return CostCipherEngine.verifyPin(candidatePin, _ownerPinHashFlow.value)
    }

    /** True only when the owner actually saved a shop name (the default placeholder is not a name). */
    fun hasStoredSellerName(context: Context): Boolean =
        prefs(context).getString(KEY_SELLER_NAME, "").orEmpty().isNotBlank()

    fun hasOwnerPin(): Boolean = _ownerPinHashFlow.value.isNotBlank()

    fun saveUpiId(context: Context, upiId: String) {
        val cleaned = upiId.trim()
        prefs(context).edit().putString(KEY_UPI_ID, cleaned).apply()
        _upiIdFlow.value = cleaned
    }

    fun getUpiId(context: Context): String =
        prefs(context).getString(KEY_UPI_ID, "")?.trim().orEmpty()

    fun saveGstin(context: Context, gstin: String) {
        val cleaned = gstin.trim().uppercase(Locale.ROOT)
        prefs(context).edit().putString(KEY_GSTIN, cleaned).apply()
        _gstinFlow.value = cleaned
    }

    fun getGstin(context: Context): String =
        prefs(context).getString(KEY_GSTIN, "")?.trim().orEmpty()

    fun saveGstEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GST_ENABLED, enabled).apply()
        _gstEnabledFlow.value = enabled
    }

    fun saveDeviceShortCode(context: Context, code: String) {
        val cleaned = code.trim().uppercase(Locale.ROOT).take(4)
        prefs(context).edit().putString(KEY_DEVICE_SHORT_CODE, cleaned).apply()
        _deviceShortCodeFlow.value = cleaned
    }

    fun getDeviceShortCode(context: Context): String =
        prefs(context).getString(KEY_DEVICE_SHORT_CODE, "")?.trim().orEmpty()

    fun getConfig(context: Context): CloudSyncConfig {
        val p = prefs(context)
        val signedInUser = GoogleAuthManager.currentUser.value
        val activeShopNamespace = signedInUser?.cloudAccountDocumentId
            ?: p.getString(KEY_SHOP_ID, "default_shop")?.trim().orEmpty().ifEmpty { "default_shop" }

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
        if (!CLOUD_SYNC_ENABLED) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            _lastSyncMessage.value = "Cloud sync coming soon"
            return
        }
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
