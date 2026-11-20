package com.shopledger.pos.ui.screens.recovery

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.BuildConfig
import com.shopledger.pos.R
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.PendingRestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun DatabaseRecoveryScreen(
    reason: String,
    hasSafetyCopy: Boolean,
    onShareSafetyCopy: () -> Unit,
    onRetry: () -> Unit,
    /** Modification time of the newest automatic backup, or null when there is none. */
    latestSnapshotTime: Long? = null,
    /** Stages the newest automatic backup and restarts. Returns null when under way, otherwise an error message. */
    onRestoreSnapshot: suspend () -> String? = { null }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val restorePending = remember { PendingRestore.isPending(context, LpsDatabase.DB_NAME) }
    val snapshotLabel = latestSnapshotTime?.let { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.recovery_title), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(12.dp))
        if (BuildConfig.DEBUG) Text(reason, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.recovery_body),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry, enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.action_retry)) }
        if (snapshotLabel != null && !restorePending) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { confirming = true }, enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.recovery_restore_button, snapshotLabel))
            }
        }
        if (hasSafetyCopy) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onShareSafetyCopy, enabled = !restoring, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.recovery_send_support))
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.recovery_restore_title)) },
            text = {
                Text(
                    stringResource(R.string.recovery_restore_body, snapshotLabel.orEmpty())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    restoring = true
                    scope.launch {
                        val error = try {
                            onRestoreSnapshot()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            context.getString(R.string.msg_backup_unreadable)
                        }
                        if (error != null) {
                            restoring = false
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.recovery_restore_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
