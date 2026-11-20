package com.shopledger.pos.ui

import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.R
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.screens.setup.SetupEvent
import com.shopledger.pos.ui.screens.setup.SetupState
import com.shopledger.pos.ui.screens.setup.SetupStep
import com.shopledger.pos.ui.screens.setup.SetupWizard
import com.shopledger.pos.ui.screens.setup.SetupWizardHost
import com.shopledger.pos.ui.screens.setup.reduceSetup
import com.shopledger.pos.ui.theme.ShopLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h640dp")
@RunWith(AndroidJUnit4::class)
class SetupWizardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg a: Any) = context.getString(id, *a)

    private fun host(
        firstRun: Boolean = true,
        pinAvailable: Boolean = true,
        initialName: String = "",
        onFinish: (SetupEvent.Finish) -> Unit = {},
        onCancel: () -> Unit = {},
        onLanguage: (AppLanguage) -> Unit = {},
    ) {
        compose.setContent {
            ShopLedgerTheme {
                SetupWizardHost(
                    firstRun = firstRun,
                    initialName = initialName,
                    initialVertical = ShopVertical.HARDWARE,
                    pinAvailable = pinAvailable,
                    language = AppLanguage.ENGLISH,
                    onLanguageSelected = onLanguage,
                    onFinish = onFinish,
                    onCancel = onCancel,
                )
            }
        }
    }

    private fun typeName(name: String = "Sharma Hardware") {
        compose.onNodeWithText(str(R.string.shop_name)).performTextInput(name)
    }

    private fun next() = compose.onNodeWithText(str(R.string.setup_next)).performClick()

    @Test
    fun nextIsDisabledUntilShopNameEntered() {
        host()
        compose.onNodeWithText(str(R.string.setup_next)).assertIsNotEnabled()
        typeName()
        compose.onNodeWithText(str(R.string.setup_next)).assertIsEnabled()
    }

    @Test
    fun blankNameKeepsNextDisabled() {
        host()
        typeName("   ")
        compose.onNodeWithText(str(R.string.setup_next)).assertIsNotEnabled()
    }

    @Test
    fun progressShowsStepOfFour() {
        host()
        compose.onNodeWithText(str(R.string.setup_progress, 1, 4)).assertIsDisplayed()
        typeName()
        next()
        compose.onNodeWithText(str(R.string.setup_progress, 2, 4)).assertIsDisplayed()
    }

    @Test
    fun skipPinGoesToStartStep() {
        host()
        typeName()
        next()
        next()
        compose.onNodeWithText(str(R.string.setup_pin_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.setup_skip)).performClick()
        compose.onNodeWithText(str(R.string.setup_start_title)).assertIsDisplayed()
    }

    @Test
    fun mismatchedPinConfirmShowsErrorAndBlocksNext() {
        host()
        typeName()
        next()
        next()
        compose.onNodeWithText(str(R.string.pin_label)).performTextInput("1234")
        compose.onNodeWithText(str(R.string.setup_pin_confirm)).performTextInput("1235")
        compose.onNodeWithText(str(R.string.setup_pin_mismatch)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.setup_next)).assertIsNotEnabled()
    }

    @Test
    fun shortPinIsRejected() {
        host()
        typeName()
        next()
        next()
        compose.onNodeWithText(str(R.string.pin_label)).performTextInput("123")
        compose.onNodeWithText(str(R.string.setup_pin_confirm)).performTextInput("123")
        compose.onNodeWithText(str(R.string.setup_next)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.setup_pin_too_short)).assertIsDisplayed()
    }

    @Test
    fun finishWithSampleItemsEmitsAllChoices() {
        var finished: SetupEvent.Finish? = null
        host(onFinish = { finished = it })
        typeName("  Sharma Hardware  ")
        next()
        compose.onNodeWithText(str(R.string.vertical_kirana)).performClick()
        next()
        compose.onNodeWithText(str(R.string.pin_label)).performTextInput("4321")
        compose.onNodeWithText(str(R.string.setup_pin_confirm)).performTextInput("4321")
        next()
        compose.onNodeWithText(str(R.string.setup_start_sample)).performClick()
        assertEquals(SetupEvent.Finish("Sharma Hardware", ShopVertical.KIRANA, "4321", true), finished)
    }

    @Test
    fun skipPinThenStartEmptyEmitsNullPinAndNoSampleData() {
        var finished: SetupEvent.Finish? = null
        host(onFinish = { finished = it })
        typeName()
        next()
        next()
        compose.onNodeWithText(str(R.string.setup_skip)).performClick()
        compose.onNodeWithText(str(R.string.setup_start_empty)).performClick()
        assertEquals(SetupEvent.Finish("Sharma Hardware", ShopVertical.HARDWARE, null, false), finished)
    }

    @Test
    fun backReturnsToThePreviousStepKeepingTheName() {
        host()
        typeName()
        next()
        compose.onNodeWithText(str(R.string.setup_back)).performClick()
        compose.onNodeWithText("Sharma Hardware").assertIsDisplayed()
    }

    @Test
    fun rerunHasNoSampleDataStepAndFinishesAtThePinStep() {
        var finished: SetupEvent.Finish? = null
        host(firstRun = false, initialName = "Old Name", onFinish = { finished = it })
        compose.onNodeWithText(str(R.string.setup_progress, 1, 3)).assertIsDisplayed()
        next()
        next()
        compose.onNodeWithText(str(R.string.setup_skip)).performClick()
        assertEquals(SetupEvent.Finish("Old Name", ShopVertical.HARDWARE, null, false), finished)
    }

    @Test
    fun rerunWithLockedPinHasNoPinStepAndNeverEmitsAPin() {
        var finished: SetupEvent.Finish? = null
        host(firstRun = false, pinAvailable = false, initialName = "Old Name", onFinish = { finished = it })
        compose.onNodeWithText(str(R.string.setup_progress, 1, 2)).assertIsDisplayed()
        next()
        compose.onNodeWithText(str(R.string.setup_finish)).performClick()
        assertNull(finished?.pin)
        assertFalse(finished?.sampleData ?: true)
    }

    @Test
    fun rerunBackOnFirstStepCancels() {
        var cancelled = false
        host(firstRun = false, initialName = "Old", onCancel = { cancelled = true })
        compose.onNodeWithText(str(R.string.setup_back)).performClick()
        assertTrue(cancelled)
    }

    @Test
    fun firstRunHasNoBackOnTheFirstStep() {
        host()
        compose.onNodeWithText(str(R.string.setup_back)).assertDoesNotExist()
    }

    @Test
    fun choosingAnotherLanguageIsReportedToTheHost() {
        var chosen: AppLanguage? = null
        host(onLanguage = { chosen = it })
        compose.onNodeWithText(str(R.string.language_hindi)).performClick()
        assertEquals(AppLanguage.HINDI, chosen)
    }

    @Test
    fun typedNameAndStepSurviveActivityRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ShopLedgerTheme {
                SetupWizardHost(
                    firstRun = true, initialName = "", initialVertical = ShopVertical.HARDWARE, pinAvailable = true,
                    language = AppLanguage.ENGLISH, onLanguageSelected = {}, onFinish = {}, onCancel = {},
                )
            }
        }
        typeName("Sharma Hardware")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Sharma Hardware").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.setup_progress, 1, 4)).assertIsDisplayed()
    }

    @Test
    fun savingDisablesTheFinishButtons() {
        compose.setContent {
            ShopLedgerTheme {
                SetupWizard(
                    state = SetupState(step = SetupStep.START, shopName = "Shop"),
                    onEvent = {},
                    saving = true,
                )
            }
        }
        compose.onNodeWithText(str(R.string.setup_saving)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.setup_start_sample)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.setup_restore_hint)).assertIsDisplayed()
    }

    @Test
    fun pinIsNeverWrittenToTheSavedInstanceState() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            ShopLedgerTheme {
                SetupWizardHost(
                    firstRun = true, initialName = "", initialVertical = ShopVertical.HARDWARE, pinAvailable = true,
                    language = AppLanguage.ENGLISH, onLanguageSelected = {}, onFinish = {}, onCancel = {},
                )
            }
        }
        typeName()
        next(); next()
        compose.onNodeWithText(str(R.string.pin_label)).performTextInput("1234")
        compose.onNodeWithText(str(R.string.setup_pin_confirm)).performTextInput("1234")
        compose.onNodeWithText(str(R.string.setup_next)).assertIsEnabled()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(str(R.string.setup_pin_confirm)).assertIsDisplayed()
        // The step survives, but the typed PIN was not saved, so Next needs it typed again.
        compose.onNodeWithText(str(R.string.setup_next)).assertIsNotEnabled()
    }

    @Test
    fun primaryActionIsTallEnoughAt360dp() {
        host()
        typeName()
        compose.onNodeWithText(str(R.string.setup_next)).assertHeightIsAtLeast(56.dp)
        next()
        compose.onNodeWithText(str(R.string.vertical_kirana)).assertIsDisplayed()
    }

    @Test
    fun reducerKeepsPinToDigitsAndCapsAtSix() {
        val s = reduceSetup(SetupState(step = SetupStep.PIN), SetupEvent.PinChanged("12a34567"))
        assertEquals("123456", s.pin)
    }

    @Test
    fun statelessWizardRendersGivenStep() {
        compose.setContent {
            ShopLedgerTheme { SetupWizard(state = SetupState(step = SetupStep.START, shopName = "X"), onEvent = {}) }
        }
        compose.onNodeWithText(str(R.string.setup_start_sample)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.setup_start_empty)).assertIsDisplayed()
    }
}
