package com.shopledger.pos.core.error

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class MessageSeverity {
    ERROR,
    WARNING,
    INFO,
    SUCCESS
}

data class AppFloatingMessage(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String,
    val severity: MessageSeverity = MessageSeverity.ERROR,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Production-grade central error & status reporter.
 * Surfaces all caught exceptions, coroutine failures, validation warnings,
 * and draft/sync notifications inside an in-app floating banner.
 */
object AppErrorManager {

    private const val TAG = "ShopLedgerPOS"
    private const val CRASH_PREFS_NAME = "shopledger_crash_prefs"
    private const val KEY_LAST_CRASH_TITLE = "last_crash_title"
    private const val KEY_LAST_CRASH_DETAIL = "last_crash_detail"

    private val _activeMessage = MutableStateFlow<AppFloatingMessage?>(null)
    val activeMessage: StateFlow<AppFloatingMessage?> = _activeMessage.asStateFlow()

    @Volatile
    private var crashHandlerInstalled = false

    fun reportError(
        title: String,
        throwable: Throwable? = null,
        customDetail: String? = null
    ) {
        val messageDetail = customDetail?.trim().takeUnless { it.isNullOrBlank() }
            ?: throwable?.localizedMessage?.trim().takeUnless { it.isNullOrBlank() }
            ?: throwable?.javaClass?.simpleName
            ?: "An unexpected error occurred. Please try again."

        if (throwable != null) {
            runCatching { Log.e(TAG, "$title: $messageDetail", throwable) }
        } else {
            runCatching { Log.e(TAG, "$title: $messageDetail") }
        }

        _activeMessage.value = AppFloatingMessage(
            title = title.ifBlank { "Error" },
            detail = messageDetail.take(240),
            severity = MessageSeverity.ERROR
        )
    }

    fun reportWarning(title: String, detail: String) {
        _activeMessage.value = AppFloatingMessage(
            title = title.ifBlank { "Attention" },
            detail = detail.trim().take(240),
            severity = MessageSeverity.WARNING
        )
    }

    fun reportInfo(title: String, detail: String) {
        _activeMessage.value = AppFloatingMessage(
            title = title.ifBlank { "Notice" },
            detail = detail.trim().take(240),
            severity = MessageSeverity.INFO
        )
    }

    fun reportSuccess(title: String, detail: String) {
        _activeMessage.value = AppFloatingMessage(
            title = title.ifBlank { "Done" },
            detail = detail.trim().take(240),
            severity = MessageSeverity.SUCCESS
        )
    }

    fun dismiss(id: String? = null) {
        val current = _activeMessage.value ?: return
        if (id == null || current.id == id) {
            _activeMessage.value = null
        }
    }

    fun exceptionHandler(operationName: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            reportError(operationName, throwable)
        }
    }

    fun installGlobalExceptionHandler(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val prefs = appContext.getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
            val prevDetail = prefs.getString(KEY_LAST_CRASH_DETAIL, "").orEmpty()
            if (prevDetail.isNotBlank()) {
                val prevTitle = prefs.getString(KEY_LAST_CRASH_TITLE, "Recovered from Unexpected Error")
                    .orEmpty()
                    .ifBlank { "Recovered from Unexpected Error" }
                prefs.edit().remove(KEY_LAST_CRASH_TITLE).remove(KEY_LAST_CRASH_DETAIL).apply()
                _activeMessage.value = AppFloatingMessage(
                    title = prevTitle,
                    detail = prevDetail.take(240),
                    severity = MessageSeverity.WARNING
                )
            }
        }

        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val prefs = appContext.getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
                val detail = throwable.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: throwable.javaClass.simpleName
                prefs.edit()
                    .putString(KEY_LAST_CRASH_TITLE, "Recovered After Crash (${thread.name})")
                    .putString(KEY_LAST_CRASH_DETAIL, detail.take(240))
                    .commit()
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
