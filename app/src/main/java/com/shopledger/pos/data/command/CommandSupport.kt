package com.shopledger.pos.data.command

import androidx.annotation.StringRes
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.ui.text.UiText

enum class Role { OWNER, STAFF }

data class Actor(val uid: String = "", val role: Role = Role.OWNER)

/** Everything a command needs from the outside world; tests supply a deterministic fake. */
interface CommandEnvironment {
    fun shopId(): String
    fun actor(): Actor
    fun gstEnabled(): Boolean
    fun now(): Long
    fun newId(): String
    /** Called once after a command's transaction commits (or replays). */
    fun afterCommit()
}

/**
 * Typed, user-presentable failures. Throwing one inside a command rolls the transaction back.
 * [uiText] is the plain-language message shown to the shopkeeper (a string resource, so it follows the app language);
 * the exception's own `message` is only a short technical tag for logs.
 */
sealed class CommandException(val uiText: UiText) : Exception(technicalTag(uiText)) {
    class Validation(uiText: UiText) : CommandException(uiText) {
        constructor(@StringRes id: Int, vararg args: Any) : this(UiText.Res(id, args.toList()))
    }

    class StaleEdit(uiText: UiText) : CommandException(uiText) {
        companion object {
            fun item(name: String) = StaleEdit(UiText.Res(R.string.msg_stale_item, listOf(name)))
            fun party(name: String) = StaleEdit(UiText.Res(R.string.msg_stale_party, listOf(name)))
        }
    }

    class PermissionDenied(uiText: UiText) : CommandException(uiText) {
        constructor(@StringRes id: Int, vararg args: Any) : this(UiText.Res(id, args.toList()))
    }

    class ReturnExceedsSold(val itemName: String, val remainingMilli: Long) : CommandException(
        UiText.Res(R.string.msg_return_exceeds, listOf(IndianCurrencyFormatter.formatMilliUnits(remainingMilli), itemName)),
    )

    class NotFound(uiText: UiText) : CommandException(uiText) {
        constructor(@StringRes id: Int) : this(UiText.Res(id))
    }
}

/** Test hook: lets tests throw at named points inside a command to prove rollback. */
fun interface FaultInjector {
    fun at(step: String)

    companion object {
        val NONE = FaultInjector { }
    }
}

object OutboxDocType {
    const val INVOICE = "INVOICE"
    const val MOVEMENT = "MOVEMENT"
    const val LEDGER = "LEDGER"
    const val ITEM = "ITEM"
    const val VARIANT = "VARIANT"
    const val PARTY = "PARTY"
    const val SHOP = "SHOP"
}

object OutboxOp {
    const val UPSERT = "UPSERT"
    const val APPEND = "APPEND"
}

object MovementType {
    const val OPENING = "OPENING"
    const val SALE = "SALE"
    const val SALE_RETURN = "SALE_RETURN"
    const val PURCHASE = "PURCHASE"
    const val ADJUSTMENT = "ADJUSTMENT"
    const val CORRECTION = "CORRECTION"
}

object LedgerEntryType {
    const val OPENING = "OPENING"
    const val CREDIT_BILL = "CREDIT_BILL"
    const val PAYMENT_IN = "PAYMENT_IN"
    const val PAYMENT_OUT = "PAYMENT_OUT"
    const val CREDIT_NOTE = "CREDIT_NOTE"
    const val ADJUSTMENT = "ADJUSTMENT"
}

object CheckoutStep {
    const val NUMBER = "NUMBER"
    const val HEADER = "HEADER"
    const val LINES = "LINES"
    const val MOVEMENTS = "MOVEMENTS"
    const val LEDGER = "LEDGER"
    const val OUTBOX = "OUTBOX"
    val ALL = listOf(NUMBER, HEADER, LINES, MOVEMENTS, LEDGER, OUTBOX)
}

/** Short tag for logs: the resource id (hex) and its arguments, or the raw text. The exception class name is added by `toString()`. */
private fun technicalTag(uiText: UiText): String = when (uiText) {
    is UiText.Res -> "res=0x%08x".format(java.util.Locale.ROOT, uiText.id) + if (uiText.args.isEmpty()) "" else " args=" + uiText.args.joinToString(prefix = "[", postfix = "]") { it.toString().take(40) }
    is UiText.Raw -> uiText.text.take(120)
}
