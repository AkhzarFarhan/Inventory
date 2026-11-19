package com.shopledger.pos.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.R
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.i18n.AppLanguageStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
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
}
