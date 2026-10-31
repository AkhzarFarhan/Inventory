package com.lpshardware.inventory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpshardware.inventory.core.auth.GoogleAuthManager
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.ui.MainViewModel
import com.lpshardware.inventory.ui.screens.inventory.AddEditItemSheet
import com.lpshardware.inventory.ui.screens.inventory.CounterCartSheet
import com.lpshardware.inventory.ui.screens.inventory.InventoryScreen
import com.lpshardware.inventory.ui.screens.inventory.ItemEditorMode
import com.lpshardware.inventory.ui.screens.inventory.QuickSellSheet
import com.lpshardware.inventory.ui.screens.sales.ReceiptShareDialog
import com.lpshardware.inventory.ui.screens.sales.SaleHistoryScreen
import com.lpshardware.inventory.ui.screens.settings.CloudSyncAndBackupSheet
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.LpsCupertinoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as LpsHardwareApp
        MainViewModel.Factory(app.repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LpsCupertinoTheme {
                LpsHardwareMainApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun LpsHardwareMainApp(viewModel: MainViewModel) {
    val filteredInventory by viewModel.filteredInventoryState.collectAsState()
    val allInventory by viewModel.allInventoryState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()
    val isSellerUnlocked by viewModel.isSellerUnlocked.collectAsState()
    val recentSearches by viewModel.recentSearchesState.collectAsState()
    val frequentSearches by viewModel.frequentSearchesState.collectAsState()
    val salesHistory by viewModel.salesHistoryState.collectAsState()
    val knownBuyers by viewModel.knownBuyersState.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()
    val activeReceipt by viewModel.activeReceiptForSharing.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCountState.collectAsState()
    val signedInGoogleUser by GoogleAuthManager.currentUser.collectAsState()

    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var activeEditorMode by remember { mutableStateOf<ItemEditorMode?>(null) }
    var quickSellTarget by remember { mutableStateOf<Pair<InventoryItemEntity, ItemVariantEntity>?>(null) }
    var showCounterCartSheet by remember { mutableStateOf(false) }
    var prefilledCartBuyerName by remember { mutableStateOf("") }
    var prefilledCartBuyerPhone by remember { mutableStateOf("") }
    var showCloudSyncSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = CupertinoColors.SystemGroupedBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CupertinoColors.CardSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shop Name & Offline-First Sync Status Pill
                    Column {
                        Text(
                            text = "LPS Hardware",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CupertinoColors.LabelPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.clickable { showCloudSyncSheet = true }
                        ) {
                            Icon(
                                imageVector = if (pendingSyncCount == 0) Icons.Default.CloudDone else Icons.Default.CloudSync,
                                contentDescription = "Cloud Sync Status",
                                tint = if (pendingSyncCount == 0) CupertinoColors.EmeraldGreen else CupertinoColors.SystemBlue,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = buildString {
                                    val user = signedInGoogleUser
                                    if (user != null) {
                                        append(user.email.ifBlank { user.displayName })
                                        append(" • ")
                                    } else {
                                        append("Google Sign-In • ")
                                    }
                                    if (pendingSyncCount == 0) append("Cloud Synced")
                                    else append("$pendingSyncCount pending sync")
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CupertinoColors.LabelSecondary
                            )
                        }
                    }

                    // Right Actions: Counter Cart Button + Seller Privacy Unlock Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Counter Cart Pill
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (cartItems.isNotEmpty()) CupertinoColors.SystemBlue else CupertinoColors.SecondarySurface,
                            border = BorderStroke(
                                1.dp,
                                if (cartItems.isNotEmpty()) CupertinoColors.SystemBlue else CupertinoColors.Separator
                            ),
                            modifier = Modifier.clickable { showCounterCartSheet = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingCart,
                                    contentDescription = "Counter Cart",
                                    tint = if (cartItems.isNotEmpty()) Color.White else CupertinoColors.LabelPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Cart (${cartItems.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cartItems.isNotEmpty()) Color.White else CupertinoColors.LabelPrimary
                                )
                            }
                        }

                        // Seller Privacy Lock Toggle (Hidden by default per Requirement 3.1)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSellerUnlocked) CupertinoColors.EmeraldTint else CupertinoColors.CipherContainer,
                            border = BorderStroke(
                                1.dp,
                                if (isSellerUnlocked) CupertinoColors.EmeraldGreen.copy(alpha = 0.4f)
                                else CupertinoColors.CipherIndigo.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.clickable { viewModel.toggleSellerUnlocked() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSellerUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = "Seller Mode Toggle",
                                    tint = if (isSellerUnlocked) CupertinoColors.EmeraldGreen else CupertinoColors.CipherIndigo,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (isSellerUnlocked) "Seller" else "Safe",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSellerUnlocked) CupertinoColors.EmeraldGreen else CupertinoColors.CipherIndigo
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CupertinoColors.Separator)
                )
            }
        },
        bottomBar = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CupertinoColors.Separator)
                )
                NavigationBar(
                    containerColor = CupertinoColors.CardSurface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTabIndex == 0,
                        onClick = { currentTabIndex = 0 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (allInventory.isNotEmpty()) {
                                        Badge(
                                            containerColor = CupertinoColors.SystemBlueTint,
                                            contentColor = CupertinoColors.SystemBlue
                                        ) {
                                            Text(allInventory.size.toString(), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Inventory2, contentDescription = "Inventory")
                            }
                        },
                        label = {
                            Text(
                                text = "Inventory (A–Z)",
                                fontWeight = if (currentTabIndex == 0) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CupertinoColors.SystemBlue,
                            selectedTextColor = CupertinoColors.SystemBlue,
                            indicatorColor = CupertinoColors.SystemBlueTint,
                            unselectedIconColor = CupertinoColors.LabelMuted,
                            unselectedTextColor = CupertinoColors.LabelMuted
                        )
                    )

                    NavigationBarItem(
                        selected = currentTabIndex == 1,
                        onClick = { currentTabIndex = 1 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (salesHistory.isNotEmpty()) {
                                        Badge(
                                            containerColor = CupertinoColors.EmeraldTint,
                                            contentColor = CupertinoColors.EmeraldGreen
                                        ) {
                                            Text(
                                                salesHistory.count { !it.isReturned }.toString(),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "Sale History")
                            }
                        },
                        label = {
                            Text(
                                text = "Sales & Margin (₹)",
                                fontWeight = if (currentTabIndex == 1) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CupertinoColors.SystemBlue,
                            selectedTextColor = CupertinoColors.SystemBlue,
                            indicatorColor = CupertinoColors.SystemBlueTint,
                            unselectedIconColor = CupertinoColors.LabelMuted,
                            unselectedTextColor = CupertinoColors.LabelMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (currentTabIndex == 0) {
                InventoryScreen(
                    items = filteredInventory,
                    searchQuery = searchQuery,
                    filterMode = filterMode,
                    isSellerUnlocked = isSellerUnlocked,
                    recentSearches = recentSearches,
                    frequentSearches = frequentSearches,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onCommitSearch = viewModel::commitSearchToHistory,
                    onDeleteSearchEntry = viewModel::deleteSearchHistoryItem,
                    onClearAllSearches = viewModel::clearAllSearchHistory,
                    onFilterModeChange = viewModel::setFilterMode,
                    onAddNewItemClick = {
                        activeEditorMode = ItemEditorMode.AddNewItem
                    },
                    onAddVariantClick = { parentItem ->
                        activeEditorMode = ItemEditorMode.AddVariantToParent(parentItem)
                    },
                    onEditItemVariantClick = { item, variant ->
                        val parentCount = allInventory.firstOrNull { it.item.id == item.id }?.variants?.size ?: 1
                        activeEditorMode = ItemEditorMode.EditExisting(item, variant, parentCount)
                    },
                    onQuickSellClick = { item, variant ->
                        quickSellTarget = item to variant
                    },
                    onAddToCartClick = { item, variant ->
                        viewModel.addOrUpdateCartItem(item, variant, 1.0)
                    }
                )
            } else {
                SaleHistoryScreen(
                    sales = salesHistory,
                    isSellerUnlocked = isSellerUnlocked,
                    onToggleSellerUnlocked = viewModel::toggleSellerUnlocked,
                    onOpenReceiptShare = viewModel::openReceiptForHistoricalSale,
                    onReturnSale = viewModel::returnSaleAndRestoreStock
                )
            }
        }
    }

    // Add / Edit Item & Variant Sheet
    activeEditorMode?.let { mode ->
        AddEditItemSheet(
            mode = mode,
            onCheckDuplicate = viewModel::checkCandidateDuplicate,
            onSaveNewItem = viewModel::addNewItemWithVariant,
            onSaveVariantToParent = viewModel::addVariantToExistingItem,
            onUpdateExisting = viewModel::updateExistingItemAndVariant,
            onDeleteItemOrVariant = viewModel::deleteItemOrVariant,
            onSwitchToVariantMode = { matchedParent, _, _, _ ->
                activeEditorMode = ItemEditorMode.AddVariantToParent(matchedParent)
            },
            onSwitchToEditExistingVariant = { item, variant, count ->
                activeEditorMode = ItemEditorMode.EditExisting(item, variant, count)
            },
            onDismiss = { activeEditorMode = null }
        )
    }

    // 1-Tap Quick Sell Sheet
    quickSellTarget?.let { (item, variant) ->
        QuickSellSheet(
            item = item,
            variant = variant,
            isSellerUnlocked = isSellerUnlocked,
            knownBuyers = knownBuyers,
            onConfirmQuickSell = { lineItem, buyerName, buyerPhone ->
                viewModel.completeSaleAndPrepareReceipt(
                    itemsToSell = listOf(lineItem),
                    buyerName = buyerName,
                    buyerPhone = buyerPhone,
                    clearCounterCart = false
                )
            },
            onShareEstimatePhoto = { itemsToEstimate, buyerName, buyerPhone ->
                viewModel.prepareEstimateImageForSharing(
                    itemsToEstimate = itemsToEstimate,
                    buyerName = buyerName,
                    buyerPhone = buyerPhone
                )
            },
            onFillCartWithBuyerOrderAndOpenCart = { snapshots, buyerName, buyerPhone ->
                viewModel.fillCartFromBuyerHistory(snapshots)
                prefilledCartBuyerName = buyerName
                prefilledCartBuyerPhone = buyerPhone
                quickSellTarget = null
                showCounterCartSheet = true
            },
            onDismiss = { quickSellTarget = null }
        )
    }

    // Multi-Item Counter Cart Sheet
    if (showCounterCartSheet) {
        CounterCartSheet(
            cartItems = cartItems,
            isSellerUnlocked = isSellerUnlocked,
            knownBuyers = knownBuyers,
            initialBuyerName = prefilledCartBuyerName,
            initialBuyerPhone = prefilledCartBuyerPhone,
            onUpdateCartLine = viewModel::updateCartLineQuantityAndPrice,
            onRemoveCartLine = viewModel::removeCartLine,
            onClearCart = viewModel::clearCart,
            onFillCartFromBuyerOrder = viewModel::fillCartFromBuyerHistory,
            onShareEstimatePhoto = { itemsToEstimate, buyerName, buyerPhone ->
                viewModel.prepareEstimateImageForSharing(
                    itemsToEstimate = itemsToEstimate,
                    buyerName = buyerName,
                    buyerPhone = buyerPhone
                )
            },
            onCheckoutCart = { buyerName, buyerPhone ->
                viewModel.completeSaleAndPrepareReceipt(
                    itemsToSell = cartItems,
                    buyerName = buyerName,
                    buyerPhone = buyerPhone,
                    clearCounterCart = true
                )
                prefilledCartBuyerName = ""
                prefilledCartBuyerPhone = ""
            },
            onDismiss = { showCounterCartSheet = false }
        )
    }

    // Sale Receipt Image Preview & Direct WhatsApp Buyer Phone Share Dialog
    activeReceipt?.let { receipt ->
        ReceiptShareDialog(
            receipt = receipt,
            onDismiss = viewModel::dismissReceiptDialog
        )
    }

    // Cloud Sync, Cipher Legend & Backup Sheet
    if (showCloudSyncSheet) {
        CloudSyncAndBackupSheet(
            pendingSyncCount = pendingSyncCount,
            isSellerUnlocked = isSellerUnlocked,
            onExportBackupJson = viewModel::exportBackupJson,
            onImportBackupJson = viewModel::importBackupJson,
            onDismiss = { showCloudSyncSheet = false }
        )
    }
}
