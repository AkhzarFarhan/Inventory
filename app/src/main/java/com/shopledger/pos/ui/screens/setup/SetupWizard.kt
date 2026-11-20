package com.shopledger.pos.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.shopledger.pos.R
import com.shopledger.pos.core.nlp.ShopVertical
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.theme.PrimaryActionButton
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import androidx.compose.ui.unit.dp

enum class SetupStep { NAME, TYPE, PIN, START }

/**
 * Wizard state. [firstRun] adds the sample-data step; [pinAvailable] is false when "Run setup again" is used while an
 * owner PIN exists and seller mode is locked (the PIN can then only be changed after the normal PIN unlock).
 */
data class SetupState(
    val step: SetupStep = SetupStep.NAME,
    val shopName: String = "",
    val vertical: ShopVertical = ShopVertical.HARDWARE,
    val pin: String = "",
    val confirmPin: String = "",
    val firstRun: Boolean = true,
    val pinAvailable: Boolean = true,
    val language: AppLanguage = AppLanguage.ENGLISH,
) {
    val steps: List<SetupStep>
        get() = buildList {
            add(SetupStep.NAME)
            add(SetupStep.TYPE)
            if (pinAvailable) add(SetupStep.PIN)
            if (firstRun) add(SetupStep.START)
        }
    val stepIndex: Int get() = steps.indexOf(step).coerceAtLeast(0)
    val isLastStep: Boolean get() = stepIndex == steps.lastIndex
    val nameValid: Boolean get() = shopName.isNotBlank()
    val pinFilled: Boolean get() = pin.isNotEmpty() || confirmPin.isNotEmpty()
    val pinValid: Boolean get() = pin.length in PIN_MIN..PIN_MAX
    val pinMismatch: Boolean get() = confirmPin.isNotEmpty() && confirmPin != pin
    val pinReady: Boolean get() = pinValid && confirmPin == pin
    /** Enough to leave the current step with Next. */
    val canAdvance: Boolean
        get() = when (step) {
            SetupStep.NAME -> nameValid
            SetupStep.TYPE -> true
            SetupStep.PIN -> pinReady
            SetupStep.START -> false
        }
}

const val PIN_MIN = 4
const val PIN_MAX = 6

sealed interface SetupEvent {
    data class NameChanged(val name: String) : SetupEvent
    data class LanguageSelected(val language: AppLanguage) : SetupEvent
    data class VerticalSelected(val vertical: ShopVertical) : SetupEvent
    data class PinChanged(val pin: String) : SetupEvent
    data class ConfirmPinChanged(val pin: String) : SetupEvent
    data object Next : SetupEvent
    data object Back : SetupEvent
    data object SkipPin : SetupEvent
    /** Leaves a re-run from its first step. */
    data object Cancel : SetupEvent
    /** Finished: save everything. [pin] is null when the owner skipped it (or could not change it). */
    data class Finish(val name: String, val vertical: ShopVertical, val pin: String?, val sampleData: Boolean) : SetupEvent
}

/** Internal navigation and field edits; Finish/Cancel/LanguageSelected are handled by the host. */
fun reduceSetup(state: SetupState, event: SetupEvent): SetupState = when (event) {
    is SetupEvent.NameChanged -> state.copy(shopName = event.name)
    is SetupEvent.VerticalSelected -> state.copy(vertical = event.vertical)
    is SetupEvent.PinChanged -> state.copy(pin = event.pin.filter { it in '0'..'9' }.take(PIN_MAX))
    is SetupEvent.ConfirmPinChanged -> state.copy(confirmPin = event.pin.filter { it in '0'..'9' }.take(PIN_MAX))
    SetupEvent.Next -> if (state.canAdvance && !state.isLastStep) state.copy(step = state.steps[state.stepIndex + 1]) else state
    SetupEvent.SkipPin ->
        if (state.step == SetupStep.PIN && !state.isLastStep) {
            state.copy(pin = "", confirmPin = "", step = state.steps[state.stepIndex + 1])
        } else {
            state.copy(pin = "", confirmPin = "")
        }
    SetupEvent.Back -> if (state.stepIndex > 0) state.copy(step = state.steps[state.stepIndex - 1]) else state
    is SetupEvent.LanguageSelected, SetupEvent.Cancel, is SetupEvent.Finish -> state
}

private val SetupStateSaver = listSaver<SetupState, Any>(
    save = {
        // The PIN fields are deliberately NOT saved: the saved-instance Bundle must never hold the raw PIN.
        listOf(it.step.name, it.shopName, it.vertical.name, it.firstRun, it.pinAvailable, it.language.tag)
    },
    restore = {
        SetupState(
            step = SetupStep.valueOf(it[0] as String),
            shopName = it[1] as String,
            vertical = ShopVertical.fromNameOrDefault(it[2] as String),
            firstRun = it[3] as Boolean,
            pinAvailable = it[4] as Boolean,
            language = AppLanguage.fromTag(it[5] as String),
        )
    },
)

/**
 * Stateful host: keeps the wizard in [rememberSaveable] so rotation and the language switch (which recreates the
 * Activity) resume on the same step with the typed values intact.
 *
 * [onLanguageSelected] persists the language and recreates; [onFinish] and [onCancel] are the exits.
 */
@Composable
fun SetupWizardHost(
    firstRun: Boolean,
    initialName: String,
    initialVertical: ShopVertical,
    pinAvailable: Boolean,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onFinish: (SetupEvent.Finish) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
) {
    var state by rememberSaveable(stateSaver = SetupStateSaver) {
        mutableStateOf(
            SetupState(
                shopName = initialName,
                vertical = initialVertical,
                firstRun = firstRun,
                pinAvailable = pinAvailable,
                language = language,
            ),
        )
    }
    SetupWizard(
        state = state.copy(language = language),
        saving = saving,
        modifier = modifier,
        onEvent = { event ->
            when (event) {
                is SetupEvent.LanguageSelected -> onLanguageSelected(event.language)
                is SetupEvent.Finish -> onFinish(event)
                SetupEvent.Cancel -> onCancel()
                else -> state = reduceSetup(state, event)
            }
        },
    )
}

@Composable
fun SetupWizard(
    state: SetupState,
    onEvent: (SetupEvent) -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
) {
    val canGoBack = state.stepIndex > 0 || !state.firstRun
    // System back: step back; on the first step a re-run closes, a first run keeps the system default (leave the app).
    BackHandler(enabled = canGoBack) {
        onEvent(if (state.stepIndex > 0) SetupEvent.Back else SetupEvent.Cancel)
    }
    val finish = { sample: Boolean, withPin: Boolean ->
        if (!saving) onEvent(
            SetupEvent.Finish(
                name = state.shopName.trim(),
                vertical = state.vertical,
                pin = state.pin.takeIf { withPin && state.pinAvailable && state.pinReady },
                sampleData = sample,
            ),
        )
    }
    val progressText = stringResource(R.string.setup_progress, state.stepIndex + 1, state.steps.size)

    Column(modifier = modifier.fillMaxSize().padding(ShopLedgerTokens.spacingL)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (canGoBack) {
                TextButton(
                    onClick = { onEvent(if (state.stepIndex > 0) SetupEvent.Back else SetupEvent.Cancel) },
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) { Text(stringResource(R.string.setup_back), style = MaterialTheme.typography.labelLarge) }
            }
            Text(
                progressText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = ShopLedgerTokens.spacingS),
            )
        }
        LinearProgressIndicator(
            progress = { (state.stepIndex + 1f) / state.steps.size },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ShopLedgerTokens.spacingS)
                .clearAndSetSemantics { },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            when (state.step) {
                SetupStep.NAME -> NameStep(state, onEvent)
                SetupStep.TYPE -> TypeStep(state, onEvent)
                SetupStep.PIN -> PinStep(state, onEvent)
                SetupStep.START -> StartStep()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS), modifier = Modifier.padding(top = ShopLedgerTokens.spacingM)) {
            when (state.step) {
                SetupStep.START -> {
                    PrimaryActionButton(
                        text = stringResource(if (saving) R.string.setup_saving else R.string.setup_start_sample),
                        enabled = !saving,
                        onClick = { finish(true, true) },
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.setup_start_empty),
                        enabled = !saving,
                        onClick = { finish(false, true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SetupStep.PIN -> {
                    PrimaryActionButton(
                        text = stringResource(if (saving) R.string.setup_saving else if (state.isLastStep) R.string.setup_finish else R.string.setup_next),
                        enabled = state.pinReady && !saving,
                        onClick = { if (state.isLastStep) finish(false, true) else onEvent(SetupEvent.Next) },
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.setup_skip),
                        enabled = !saving,
                        onClick = { if (state.isLastStep) finish(false, false) else onEvent(SetupEvent.SkipPin) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> PrimaryActionButton(
                    text = stringResource(if (saving) R.string.setup_saving else if (state.isLastStep) R.string.setup_finish else R.string.setup_next),
                    enabled = state.canAdvance && !saving,
                    onClick = { if (state.isLastStep) finish(false, true) else onEvent(SetupEvent.Next) },
                )
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, hint: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NameStep(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    StepTitle(stringResource(R.string.setup_name_title), stringResource(R.string.setup_name_hint))
    OutlinedTextField(
        value = state.shopName,
        onValueChange = { onEvent(SetupEvent.NameChanged(it)) },
        label = { Text(stringResource(R.string.shop_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        listOf(
            AppLanguage.ENGLISH to stringResource(R.string.language_english),
            AppLanguage.HINDI to stringResource(R.string.language_hindi),
        ).forEach { (lang, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ShopLedgerTokens.minTouch)
                    .selectable(
                        selected = lang == state.language,
                        role = Role.RadioButton,
                        onClick = { if (lang != state.language) onEvent(SetupEvent.LanguageSelected(lang)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = lang == state.language, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = ShopLedgerTokens.spacingM))
            }
        }
    }
}

@StringRes
private fun ShopVertical.labelRes(): Int = when (this) {
    ShopVertical.HARDWARE -> R.string.vertical_hardware
    ShopVertical.KIRANA -> R.string.vertical_kirana
    ShopVertical.PHARMA -> R.string.vertical_pharma
    ShopVertical.ELECTRONICS -> R.string.vertical_electronics
    ShopVertical.GENERAL -> R.string.vertical_general
}

@Composable
private fun TypeStep(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    StepTitle(stringResource(R.string.setup_type_title), stringResource(R.string.setup_type_hint))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)) {
        ShopVertical.entries.forEach { vertical ->
            val selected = vertical == state.vertical
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ShopLedgerTokens.primaryActionHeight)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onEvent(SetupEvent.VerticalSelected(vertical)) }),
            ) {
                Row(
                    Modifier.padding(ShopLedgerTokens.spacingL).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        stringResource(vertical.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = ShopLedgerTokens.spacingM),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinStep(state: SetupState, onEvent: (SetupEvent) -> Unit) {
    StepTitle(stringResource(R.string.setup_pin_title), stringResource(R.string.setup_pin_hint))
    OutlinedTextField(
        value = state.pin,
        onValueChange = { onEvent(SetupEvent.PinChanged(it)) },
        label = { Text(stringResource(R.string.pin_label)) },
        placeholder = { Text(stringResource(R.string.owner_pin_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.confirmPin,
        onValueChange = { onEvent(SetupEvent.ConfirmPinChanged(it)) },
        label = { Text(stringResource(R.string.setup_pin_confirm)) },
        singleLine = true,
        isError = state.pinMismatch,
        supportingText = if (state.pinMismatch) {
            { Text(stringResource(R.string.setup_pin_mismatch)) }
        } else if (state.pinFilled && !state.pinValid) {
            { Text(stringResource(R.string.setup_pin_too_short)) }
        } else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StartStep() {
    StepTitle(stringResource(R.string.setup_start_title), stringResource(R.string.setup_start_hint))
    Text(
        stringResource(R.string.setup_restore_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
