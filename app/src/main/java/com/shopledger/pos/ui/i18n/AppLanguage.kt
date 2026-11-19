package com.shopledger.pos.ui.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    HINDI("hi");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: ENGLISH
    }
}

object AppLanguageStore {
    private const val PREFS = "shopledger_ui_prefs"
    private const val KEY = "app_language"

    fun get(context: Context): AppLanguage =
        AppLanguage.fromTag(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language.tag).apply()
    }

    /** Returns a context whose resources use the saved app language. */
    fun wrap(base: Context): Context {
        val locale = Locale(get(base).tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** For non-Activity code (services, notifications) that resolves strings from the application context. */
    fun localizedContext(appContext: Context): Context = wrap(appContext)
}
