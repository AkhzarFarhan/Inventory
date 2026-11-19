package com.shopledger.pos.ui.screens.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopledger.pos.ui.theme.CupertinoColors

@Composable
fun DatabaseRecoveryScreen(
    reason: String,
    hasSafetyCopy: Boolean,
    onShareSafetyCopy: () -> Unit,
    onRetry: () -> Unit
) {
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
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        if (hasSafetyCopy) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onShareSafetyCopy, modifier = Modifier.fillMaxWidth()) { Text("Send data file to support") }
        }
    }
}
