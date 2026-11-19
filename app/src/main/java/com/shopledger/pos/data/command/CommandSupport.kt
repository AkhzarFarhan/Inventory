package com.shopledger.pos.data.command

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

/** Typed, user-presentable failures. Throwing one inside a command rolls the transaction back. */
sealed class CommandException(message: String) : Exception(message) {
    class Validation(message: String) : CommandException(message)
    class StaleEdit(what: String) :
        CommandException("$what was changed on another screen or phone. Reload and try again.")
    class PermissionDenied(message: String) : CommandException(message)
    class ReturnExceedsSold(itemName: String, remainingMilli: Long) :
        CommandException("Cannot return more $itemName than was sold (still returnable: ${remainingMilli / 1000.0}).")
    class NotFound(what: String) : CommandException("$what not found")
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
