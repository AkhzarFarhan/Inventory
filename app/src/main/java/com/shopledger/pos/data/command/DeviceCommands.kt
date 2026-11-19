package com.shopledger.pos.data.command

import android.database.sqlite.SQLiteConstraintException
import com.shopledger.pos.data.local.LpsDatabase
import java.util.Locale

class DeviceCommands(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val writer = LedgerWriter(db, env)
    private val devices = db.deviceDao()

    suspend fun localDeviceCode(): String = writer.localDevice().deviceCode

    /** Sets the code used in receipt numbers (1-4 of A-Z / 0-9). Returns the cleaned code. */
    suspend fun setDeviceCode(actionId: String, rawCode: String): String =
        writer.runIdempotent(actionId, "SET_DEVICE_CODE", replay = { it }) {
            val code = rawCode.trim().uppercase(Locale.ROOT)
            if (code.isEmpty()) throw CommandException.Validation("Counter code cannot be blank")
            if (code.length > 4 || !code.all { it in 'A'..'Z' || it in '0'..'9' }) {
                throw CommandException.Validation("Counter code must be 1 to 4 letters or digits")
            }
            val device = writer.localDevice()
            if (device.deviceCode != code) {
                try {
                    devices.updateDeviceCode(device.deviceId, code)
                } catch (e: SQLiteConstraintException) {
                    throw CommandException.Validation("Another phone in this shop already uses code $code")
                }
            }
            code to code
        }
}
