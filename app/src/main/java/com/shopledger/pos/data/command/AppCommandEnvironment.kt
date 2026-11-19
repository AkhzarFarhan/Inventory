package com.shopledger.pos.data.command

import android.content.Context
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.sync.SyncManager
import java.util.UUID

/** Production environment. Roles arrive with staff accounts in sub-project 2; until then everyone is OWNER. */
class AppCommandEnvironment(private val context: Context) : CommandEnvironment {
    override fun shopId(): String = SyncManager.getConfig(context).shopCollectionId
    override fun actor(): Actor = Actor(uid = GoogleAuthManager.currentUser.value?.firebaseUid.orEmpty(), role = Role.OWNER)
    override fun gstEnabled(): Boolean = SyncManager.gstEnabledFlow.value
    override fun now(): Long = System.currentTimeMillis()
    override fun newId(): String = UUID.randomUUID().toString()
    override fun afterCommit() = SyncManager.scheduleDeltaSync(context)
}
