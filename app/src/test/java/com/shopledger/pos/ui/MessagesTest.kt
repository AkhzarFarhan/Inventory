package com.shopledger.pos.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.MessageSeverity
import com.shopledger.pos.data.command.CommandException
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.i18n.AppLanguageStore
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.text.resolve
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        context.getSharedPreferences("shopledger_ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        AppErrorManager.dismiss()
    }

    @After
    fun tearDown() {
        AppErrorManager.showTechnicalDetails = { com.shopledger.pos.BuildConfig.DEBUG }
        AppErrorManager.dismiss()
        Locale.setDefault(savedLocale)
    }

    private fun samples(): List<CommandException> = listOf(
        CommandException.Validation(com.shopledger.pos.R.string.msg_qty_positive),
        CommandException.StaleEdit.item("Rice"),
        CommandException.PermissionDenied(com.shopledger.pos.R.string.msg_owner_only_cost),
        CommandException.ReturnExceedsSold("Rice", 2_500L),
        CommandException.NotFound(com.shopledger.pos.R.string.msg_not_found_item),
    )

    @Test
    fun everyCommandExceptionSubclassCarriesAResourceMessage() {
        val subclasses = CommandException::class.java.permittedSubclasses.orEmpty()
        assertEquals("Add the new subclass to samples()", subclasses.size, samples().map { it.javaClass }.toSet().size)
        samples().forEach { e ->
            assertTrue("${e.javaClass.simpleName} must carry UiText.Res", e.uiText is UiText.Res)
        }
    }

    @Test
    fun messagesUseTheNamesPassedIn_inBothLanguages() {
        for (lang in listOf(AppLanguage.ENGLISH, AppLanguage.HINDI)) {
            AppLanguageStore.set(context, lang)
            val ctx = AppLanguageStore.localizedContext(context)
            val stale = CommandException.StaleEdit.item("Basmati Rice").uiText.resolve(ctx)
            val ret = CommandException.ReturnExceedsSold("Basmati Rice", 2_500L).uiText.resolve(ctx)
            assertTrue("$lang stale: $stale", stale.contains("Basmati Rice"))
            assertTrue("$lang return: $ret", ret.contains("Basmati Rice") && ret.contains("2.5"))
            val party = CommandException.StaleEdit.party("Ramesh").uiText.resolve(ctx)
            assertTrue(party.contains("Ramesh"))
        }
    }

    @Test
    fun englishWordingIsPlain() {
        AppLanguageStore.set(context, AppLanguage.ENGLISH)
        val ctx = AppLanguageStore.localizedContext(context)
        assertEquals(
            "You can return at most 2.5 of Rice.",
            CommandException.ReturnExceedsSold("Rice", 2_500L).uiText.resolve(ctx),
        )
        samples().forEach {
            val text = it.uiText.resolve(ctx)
            assertTrue("too long: $text", text.length <= 95)
            assertFalse(text, text.contains("exception", ignoreCase = true) || text.contains("transaction", ignoreCase = true))
        }
    }

    @Test
    fun releaseBuildNeverShowsThrowableText() {
        AppErrorManager.showTechnicalDetails = { false }
        AppErrorManager.reportError(
            UiText.Res(com.shopledger.pos.R.string.err_sale_not_saved),
            UiText.Res(com.shopledger.pos.R.string.err_generic_detail),
            IllegalStateException("secret SQLite detail"),
        )
        val msg = AppErrorManager.activeMessage.value
        assertNotNull(msg)
        assertEquals(MessageSeverity.ERROR, msg!!.severity)
        assertTrue(msg.detail is UiText.Res)
        assertNull(msg.technical)
        assertFalse(msg.toString().contains("secret"))
    }

    @Test
    fun throwableOverloadUsesCommandMessageOrGenericInRelease() {
        AppErrorManager.showTechnicalDetails = { false }
        AppErrorManager.reportError(
            UiText.Res(com.shopledger.pos.R.string.err_sale_not_saved),
            CommandException.NotFound(com.shopledger.pos.R.string.msg_not_found_item),
        )
        assertEquals(UiText.Res(com.shopledger.pos.R.string.msg_not_found_item), AppErrorManager.activeMessage.value!!.detail)
        AppErrorManager.reportError(
            UiText.Res(com.shopledger.pos.R.string.err_sale_not_saved),
            RuntimeException("secret"),
        )
        val m = AppErrorManager.activeMessage.value!!
        assertEquals(UiText.Res(com.shopledger.pos.R.string.err_generic_detail), m.detail)
        assertNull(m.technical)
    }

    @Test
    fun debugBuildMayAttachDetails() {
        AppErrorManager.showTechnicalDetails = { true }
        AppErrorManager.reportError(
            UiText.Res(com.shopledger.pos.R.string.err_sale_not_saved),
            UiText.Res(com.shopledger.pos.R.string.err_generic_detail),
            IllegalStateException("db is locked"),
        )
        assertEquals("db is locked", AppErrorManager.activeMessage.value!!.technical)
    }

    @Test
    fun commandSourcesHaveNoLiteralMessages() {
        val base = System.getProperty("user.dir")
        val dir = listOf(File(base, "src/main/java/com/shopledger/pos/data"), File(base, "app/src/main/java/com/shopledger/pos/data"))
            .first { it.exists() }
        val literal = Regex("""CommandException\.\w+\(\s*"""")
        val offenders = dir.walkTopDown().filter { it.extension == "kt" }
            .filter { literal.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("Literal CommandException messages in $offenders", offenders.isEmpty())
    }
}
