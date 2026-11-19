package com.shopledger.pos.ui.screens.recovery

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.shopledger.pos.ui.theme.CupertinoColors
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
    val snapshotLabel = latestSnapshotTime?.let { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CupertinoColors.CardSurface)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("We couldn't open your shop data", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = CupertinoColors.LabelPrimary)
        Spacer(modifier = Modifier.height(12.dp))
        Text(reason, fontSize = 15.sp, color = CupertinoColors.LabelSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Nothing has been deleted. Install the latest app update if one is available, or send the data file to support so it can be recovered.",
            fontSize = 14.sp,
            color = CupertinoColors.LabelSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry, enabled = !restoring, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        if (snapshotLabel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { confirming = true }, enabled = !restoring, modifier = Modifier.fillMaxWidth()) {
                Text("Restore last automatic backup ($snapshotLabel)")
            }
        }
        if (hasSafetyCopy) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onShareSafetyCopy, enabled = !restoring, modifier = Modifier.fillMaxWidth()) {
                Text("Send data file to support")
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Restore the automatic backup?") },
            text = {
                Text(
                    "The data on this phone will be replaced by the automatic backup from $snapshotLabel. " +
                        "Anything entered since then will be lost."
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
                            "The backup could not be read"
                        }
                        if (error != null) {
                            restoring = false
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Restore", color = CupertinoColors.DestructiveRed) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}
