package com.shopledger.pos.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * Android's default corruption handling deletes the database file, which would wipe a shop's
 * books. This factory keeps a copy, flags the app unhealthy and refuses to continue instead.
 */
class QuarantiningOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                val path = db.path
                runCatching { db.close() }
                val stamp = System.currentTimeMillis()
                val kept = path?.let { p ->
                    listOf("", "-wal").mapNotNull { suffix ->
                        File(p + suffix).takeIf { it.exists() }?.copyTo(File("$p.corrupt-$stamp$suffix"), overwrite = true)
                    }.firstOrNull()
                }
                DatabaseHealth.markFailed("The data file is damaged. A copy was kept for recovery and nothing was deleted.", kept)
                throw IllegalStateException("Database corruption detected; file kept, not deleted")
            }
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(guarded)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build()
        )
    }
}
