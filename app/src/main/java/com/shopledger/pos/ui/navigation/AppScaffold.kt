package com.shopledger.pos.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.shopledger.pos.R

enum class AppTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    ITEMS(R.string.tab_items, Icons.Filled.Inventory2),
    BILLS(R.string.tab_bills, Icons.AutoMirrored.Filled.ReceiptLong),
    KHATA(R.string.tab_khata, Icons.Filled.AccountBalanceWallet),
    MORE(R.string.tab_more, Icons.Filled.MoreHoriz),
}

/** Bottom-tab shell: Items, Bills, Khata, More. [badges] shows a count on a tab (hidden when 0 or missing). */
@Composable
fun AppScaffold(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<AppTab, Int> = emptyMap(),
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    val badge = badges[tab] ?: 0
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
        content = content,
    )
}
