package com.shopledger.pos.core.error

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.shopledger.pos.BuildConfig
import com.shopledger.pos.R
import com.shopledger.pos.ui.text.UiText
import java.util.UUID

enum class MessageSeverity {
    ERROR,
    WARNING,
    INFO,
    SUCCESS
}

data class AppFloatingMessage(
    val id: String = UUID.randomUUID().toString(),
    val title: UiText,
    val detail: UiText,
    val severity: MessageSeverity = MessageSeverity.ERROR,
    /** Throwable text for developers. Only ever set when [AppErrorManager.showTechnicalDetails] is true (debug builds). */
    val technical: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central reporter for the in-app floating banner. Titles and details are [UiText] (string resources), so they follow
 * the app language. Release builds never put throwable text on screen; it only goes to the log.
 */
object AppErrorManager {

    private const val TAG = "ShopLedgerPOS"
    private const val CRASH_PREFS_NAME = "shopledger_crash_prefs"
    private const val KEY_LAST_CRASH_DETAIL = "last_crash_detail"

    /** Whether technical (throwable) text may be shown. Debug builds only; tests inject their own. */
    @Volatile
    var showTechnicalDetails: () -> Boolean = { BuildConfig.DEBUG }

    private val _activeMessage = MutableStateFlow<AppFloatingMessage?>(null)
    val activeMessage: StateFlow<AppFloatingMessage?> = _activeMessage.asStateFlow()

    @Volatile
    private var crashHandlerInstalled = false

    private val genericDetail = UiText.Res(R.string.err_generic_detail)

    /** The user-facing detail for a failure: a command's own plain message, else [fallback]. */
    fun detailFor(cause: Throwable, fallback: UiText = genericDetail): UiText =
        (cause as? com.shopledger.pos.data.command.CommandException)?.uiText ?: fallback

    fun reportError(title: UiText, detail: UiText, cause: Throwable? = null) {
        runCatching { Log.e(TAG, "error: $title / $detail", cause) }
        val technical = if (cause != null && showTechnicalDetails()) {
            (cause.localizedMessage?.trim().takeUnless { it.isNullOrBlank() } ?: cause.javaClass.simpleName).take(240)
        } else null
        _activeMessage.value = AppFloatingMessage(title = title, detail = detail, severity = MessageSeverity.ERROR, technical = technical)
    }

    /** Failure with a cause: shows the command's plain message if it is one, otherwise [fallback]. */
    fun reportError(title: UiText, cause: Throwable, fallback: UiText = genericDetail) =
        reportError(title, detailFor(cause, fallback), cause)

    fun reportWarning(title: UiText, detail: UiText) {
        _activeMessage.value = AppFloatingMessage(title = title, detail = detail, severity = MessageSeverity.WARNING)
    }

    fun reportInfo(title: UiText, detail: UiText) {
        _activeMessage.value = AppFloatingMessage(title = title, detail = detail, severity = MessageSeverity.INFO)
    }

    fun reportSuccess(title: UiText, detail: UiText) {
        _activeMessage.value = AppFloatingMessage(title = title, detail = detail, severity = MessageSeverity.SUCCESS)
    }

    fun dismiss(id: String? = null) {
        val current = _activeMessage.value ?: return
        if (id == null || current.id == id) {
            _activeMessage.value = null
        }
    }

    fun exceptionHandler(title: UiText): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            reportError(title, throwable)
        }
    }

    fun installGlobalExceptionHandler(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val prefs = appContext.getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_LAST_CRASH_DETAIL)) {
                val technical = prefs.getString(KEY_LAST_CRASH_DETAIL, "").orEmpty()
                prefs.edit().remove(KEY_LAST_CRASH_DETAIL).apply()
                _activeMessage.value = AppFloatingMessage(
                    title = UiText.Res(R.string.err_recovered_title),
                    detail = UiText.Res(R.string.err_recovered_detail),
                    severity = MessageSeverity.WARNING,
                    technical = technical.takeIf { it.isNotBlank() && showTechnicalDetails() }?.take(240),
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
                    .putString(KEY_LAST_CRASH_DETAIL, "${thread.name}: ${detail.take(200)}")
                    .commit()
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
