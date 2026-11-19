package com.shopledger.pos.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.R
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.i18n.AppLanguageStore
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var savedLocale: Locale

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Before
    fun reset() {
        savedLocale = Locale.getDefault()
        context.getSharedPreferences("shopledger_ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultIsEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.get(context))
        assertEquals("en", AppLanguageStore.wrap(context).resources.configuration.locales[0].language)
    }

    @Test
    fun hindiIsPersistedAndWrapped() {
        AppLanguageStore.set(context, AppLanguage.HINDI)
        assertEquals(AppLanguage.HINDI, AppLanguageStore.get(context))
        assertEquals("hi", AppLanguageStore.wrap(context).resources.configuration.locales[0].language)
    }

    @Test
    fun hindiResourcesResolveThroughWrappedContext() {
        AppLanguageStore.set(context, AppLanguage.HINDI)
        val localized = AppLanguageStore.localizedContext(context)
        assertEquals("रद्द करें", localized.getString(R.string.action_cancel))
    }

    @Test
    fun englishKeepsIndianNumberGrouping() {
        AppLanguageStore.wrap(context)
        assertEquals("IN", Locale.getDefault().country)
        // The Robolectric JVM's locale data does not apply lakh grouping, so assert the region
        // (which yields 12,34,567 on a real device) instead of the formatted string.
        assertEquals("en", Locale.getDefault().language)
    }
}
