package com.shopledger.pos

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.shopledger.pos.core.backup.AppRestarter
import com.shopledger.pos.core.backup.BackupException
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.PendingRestore
import com.shopledger.pos.core.backup.BackupShare
import com.shopledger.pos.data.local.DatabaseHealth
import com.shopledger.pos.ui.screens.recovery.DatabaseRecoveryScreen
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.ui.MainViewModel
import com.shopledger.pos.ui.screens.inventory.AddEditItemSheet
import com.shopledger.pos.ui.screens.inventory.CounterCartSheet
import com.shopledger.pos.ui.screens.inventory.InventoryScreen
import com.shopledger.pos.ui.screens.inventory.ItemEditorMode
import com.shopledger.pos.ui.screens.inventory.OrderScanSheet
import com.shopledger.pos.ui.screens.inventory.QuickSellSheet
import com.shopledger.pos.ui.screens.khata.PartiesKhataScreen
import com.shopledger.pos.ui.screens.sales.ReceiptShareDialog
import com.shopledger.pos.ui.screens.sales.SaleHistoryScreen
import com.shopledger.pos.ui.screens.settings.CloudSyncAndBackupSheet
import com.shopledger.pos.ui.theme.CupertinoColors
import com.shopledger.pos.ui.theme.CupertinoTextField
import com.shopledger.pos.ui.theme.LpsCupertinoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as ShopLedgerApp
        MainViewModel.Factory(app.repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LpsCupertinoTheme {
                val health by DatabaseHealth.state.collectAsState()
                when (val current = health) {
                    is DatabaseHealth.State.Failed -> DatabaseRecoveryScreen(
                        reason = current.reason,
                        hasSafetyCopy = current.safetyCopy != null,
                        onShareSafetyCopy = {
                            current.safetyCopy?.let { BackupShare.share(this, it, "Send ShopLedger data file") }
                        },
                        onRetry = { AppRestarter.restart(this) }
                    )
                    DatabaseHealth.State.Ok -> LpsHardwareMainApp(viewModel = viewModel)
                }
            }
        }
    }
}

/**
 * Stages a restore off the main thread and out of reach of cancellation, then restarts the app from inside that same
 * non-cancellable section: once a backup is staged the restart always follows, whatever happens to the UI (rotation,
 * closing the sheet). Returns null when the restart is under way (the process ends), otherwise a message for the user;
 * staging is undone in every failure case so a staged file can never be applied later without the user's restart.
 */
private suspend fun stageRestore(app: ShopLedgerApp, stage: () -> Int): String? =
    withContext(NonCancellable + Dispatchers.IO) {
        val dbName = LpsDatabase.DB_NAME
        var staged = false
        try {
            stage()
            staged = true
            PendingRestore.armStaged(app, dbName)
            if (!AppRestarter.restart(app)) {
                PendingRestore.discardStaged(app, dbName)
                "The restore did not start because the app could not restart. Your data is unchanged."
            } else {
                null
            }
        } catch (e: BackupException) {
            runCatching { PendingRestore.discardStaged(app, dbName) }
            e.message ?: "The backup could not be read"
        } catch (e: Throwable) {
            runCatching { PendingRestore.discardStaged(app, dbName) }
            if (e is CancellationException) throw e
            if (staged) "The restore could not start. Please open the app again." else "The backup could not be read"
        }
    }

@Composable
fun LpsHardwareMainApp(viewModel: MainViewModel) {
    val app = LocalContext.current.applicationContext as ShopLedgerApp
    val filteredInventory by viewModel.filteredInventoryState.collectAsState()
    val allInventory by viewModel.allInventoryState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()
    val isSellerUnlocked by viewModel.isSellerUnlocked.collectAsState()
    val recentSearches by viewModel.recentSearchesState.collectAsState()
    val frequentSearches by viewModel.frequentSearchesState.collectAsState()
    val salesHistory by viewModel.salesHistoryState.collectAsState()
    val knownBuyers by viewModel.knownBuyersState.collectAsState()
    val carts by viewModel.cartsState.collectAsState()
    val activeCartSession by viewModel.activeCartSessionState.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()
    val activeReceipt by viewModel.activeReceiptForSharing.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCountState.collectAsState()
    val signedInGoogleUser by GoogleAuthManager.currentUser.collectAsState()
    val activeSellerName by SyncManager.sellerNameFlow.collectAsState()
    val parties by viewModel.partiesState.collectAsState()
    val context = LocalContext.current

    var currentTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var activeEditorMode by remember { mutableStateOf<ItemEditorMode?>(null) }
    var quickSellTarget by remember { mutableStateOf<Triple<InventoryItemEntity, ItemVariantEntity, List<ItemVariantEntity>>?>(null) }
    var showCounterCartSheet by remember { mutableStateOf(false) }
    var showOrderScanSheet by remember { mutableStateOf(false) }
    var showCloudSyncSheet by remember { mutableStateOf(false) }
    var showOwnerPinDialog by remember { mutableStateOf(false) }
    var pinEntryInput by remember { mutableStateOf("") }

    val requestToggleSeller = {
        if (isSellerUnlocked) {
            viewModel.toggleSellerUnlocked()
        } else if (SyncManager.hasOwnerPin()) {
            pinEntryInput = ""
            showOwnerPinDialog = true
        } else {
            viewModel.toggleSellerUnlocked()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                            text = activeSellerName.ifBlank { "ShopLedger POS" },
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
                                val totalItemsAcrossCarts = carts.sumOf { it.items.size }
                                val cartBadgeText = if (carts.size > 1) {
                                    "${carts.size} Carts ($totalItemsAcrossCarts)"
                                } else {
                                    "${activeCartSession.label} (${cartItems.size})"
                                }
                                Text(
                                    text = cartBadgeText,
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
                            modifier = Modifier.clickable { requestToggleSeller() }
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

                    val duePartyCount = parties.count { it.currentBalancePaise > 0L }
                    NavigationBarItem(
                        selected = currentTabIndex == 2,
                        onClick = { currentTabIndex = 2 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (duePartyCount > 0) {
                                        Badge(
                                            containerColor = CupertinoColors.DestructiveTint,
                                            contentColor = CupertinoColors.DestructiveRed
                                        ) {
                                            Text(
                                                duePartyCount.toString(),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Khata (Udhar)")
                            }
                        },
                        label = {
                            Text(
                                text = "Khata (Udhar)",
                                fontWeight = if (currentTabIndex == 2) FontWeight.Bold else FontWeight.Medium
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
            when (currentTabIndex) {
                0 -> {
                    InventoryScreen(
                        items = filteredInventory,
                        searchQuery = searchQuery,
                        filterMode = filterMode,
                        isSellerUnlocked = isSellerUnlocked,
                        recentSearches = recentSearches,
                        frequentSearches = frequentSearches,
                        carts = carts,
                        activeCartId = activeCartSession.cartId,
                        activeCartItems = cartItems,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onCommitSearch = viewModel::commitSearchToHistory,
                        onDeleteSearchEntry = viewModel::deleteSearchHistoryItem,
                        onClearAllSearches = viewModel::clearAllSearchHistory,
                        onFilterModeChange = viewModel::setFilterMode,
                        onAddNewItemClick = {
                            activeEditorMode = ItemEditorMode.AddNewItem
                        },
                        onEditItemClick = { itemWithVariants, variant ->
                            activeEditorMode = ItemEditorMode.ManageItem(itemWithVariants, variant.id)
                        },
                        onQuickSellClick = { item, variant, variants ->
                            quickSellTarget = Triple(item, variant, variants)
                        },
                        onAddToCartClick = { item, variant, delta ->
                            viewModel.addOrUpdateCartItem(item, variant, delta)
                        },
                        onSwitchCart = viewModel::switchActiveCart,
                        onCreateNewCart = { viewModel.createNewCart() },
                        onCloseCart = viewModel::closeCart,
                        onOpenCartSheet = { showCounterCartSheet = true },
                        onOpenOrderScanner = { showOrderScanSheet = true }
                    )
                }
                1 -> {
                    SaleHistoryScreen(
                        sales = salesHistory,
                        isSellerUnlocked = isSellerUnlocked,
                        onToggleSellerUnlocked = requestToggleSeller,
                        onOpenReceiptShare = viewModel::openReceiptForHistoricalSale,
                        onReturnSale = viewModel::returnSaleLine
                    )
                }
                else -> {
                    PartiesKhataScreen(
                        parties = parties,
                        onAddParty = viewModel::addOrUpdateParty,
                        onRecordPaymentReceived = viewModel::recordPartyPaymentReceived
                    )
                }
            }
        }
    }

        FloatingAppMessageHost(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .zIndex(20f)
        )
    }

    val allExistingCategories = remember(allInventory) {
        allInventory.map { it.item.category }.filter { it.isNotBlank() }.distinct()
    }

    // Add / Edit Item & Variant Sheet
    activeEditorMode?.let { mode ->
        AddEditItemSheet(
            mode = mode,
            existingCategories = allExistingCategories,
            onCheckDuplicate = viewModel::checkCandidateDuplicate,
            onSaveNewItemWithVariants = viewModel::addNewItemWithVariants,
            onSaveVariantToParent = viewModel::addVariantToExistingItem,
            onUpdateExisting = viewModel::updateExistingItemAndVariant,
            onDeleteItemOrVariant = viewModel::deleteItemOrVariant,
            onSwitchToManageItem = { matchedParent, initialVariantId, isAddingNew, pSize, pMat, pCol ->
                activeEditorMode = ItemEditorMode.ManageItem(
                    itemWithVariants = matchedParent,
                    initialVariantId = initialVariantId,
                    startInAddVariantMode = isAddingNew,
                    prefillSize = pSize,
                    prefillMaterial = pMat,
                    prefillColor = pCol
                )
            },
            onDismiss = { activeEditorMode = null }
        )
    }

    // 1-Tap Quick Sell Sheet
    quickSellTarget?.let { (item, variant, allVariants) ->
        QuickSellSheet(
            item = item,
            variant = variant,
            allVariants = allVariants,
            isSellerUnlocked = isSellerUnlocked,
            knownBuyers = knownBuyers,
            onConfirmQuickSell = { lineItem, buyerName, buyerPhone, paymentMode, discountPaise, amountPaidPaise ->
                viewModel.completeSaleAndPrepareReceipt(
                    itemsToSell = listOf(lineItem),
                    buyerName = buyerName,
                    buyerPhone = buyerPhone,
                    clearCounterCart = false,
                    paymentMode = paymentMode,
                    discountPaise = discountPaise,
                    amountPaidPaise = amountPaidPaise
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
                viewModel.updateActiveCartBuyer(buyerName, buyerPhone)
                quickSellTarget = null
                showCounterCartSheet = true
            },
            onDismiss = { quickSellTarget = null }
        )
    }

    // Multi-Item & Multi-Session Counter Cart Sheet
    if (showCounterCartSheet) {
        CounterCartSheet(
            cartItems = cartItems,
            isSellerUnlocked = isSellerUnlocked,
            knownBuyers = knownBuyers,
            carts = carts,
            activeCartId = activeCartSession.cartId,
            initialBuyerName = activeCartSession.buyerName,
            initialBuyerPhone = activeCartSession.buyerPhone,
            onSwitchCart = viewModel::switchActiveCart,
            onCreateNewCart = { viewModel.createNewCart() },
            onCloseCart = viewModel::closeCart,
            onActiveCartBuyerChange = viewModel::updateActiveCartBuyer,
            onUpdateCartLine = viewModel::updateCartLineQuantityAndPrice,
            onRemoveCartLine = viewModel::removeCartLine,
            onRemoveMultipleCartLines = viewModel::removeMultipleCartLines,
            onClearCart = viewModel::clearCart,
            onFillCartFromBuyerOrder = viewModel::fillCartFromBuyerHistory,
            onOpenOrderScanner = { showOrderScanSheet = true },
            onShareEstimatePhoto = { itemsToEstimate, buyerName, buyerPhone ->
                viewModel.prepareEstimateImageForSharing(
                    itemsToEstimate = itemsToEstimate,
                    buyerName = buyerName,
                    buyerPhone = buyerPhone
                )
            },
            onCheckoutCart = { buyerName, buyerPhone, paymentMode, discountPaise, amountPaidPaise ->
                viewModel.completeSaleAndPrepareReceipt(
                    itemsToSell = cartItems,
                    buyerName = buyerName,
                    buyerPhone = buyerPhone,
                    clearCounterCart = true,
                    paymentMode = paymentMode,
                    discountPaise = discountPaise,
                    amountPaidPaise = amountPaidPaise
                )
            },
            onDismiss = { showCounterCartSheet = false }
        )
    }

    // Unbundled On-Device Order List / Receipt Scanner Sheet
    if (showOrderScanSheet) {
        OrderScanSheet(
            inventory = allInventory,
            activeCartLabel = activeCartSession.label,
            onAddScannedItemsAndOpenCart = { scannedLines ->
                viewModel.batchAddItemsToActiveCart(scannedLines)
                showOrderScanSheet = false
                showCounterCartSheet = true
            },
            onDismiss = { showOrderScanSheet = false }
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
            onExportBackupFile = {
                withContext(Dispatchers.IO) {
                    runCatching { app.backupManager.exportShareableBackup() }
                        .onFailure { AppErrorManager.reportError("Backup Export Failed", it) }
                        .getOrNull()
                }
            },
            onRestoreBackupFile = { uri ->
                stageRestore(app) {
                    val stream = app.contentResolver.openInputStream(uri) ?: throw BackupException("That file could not be opened")
                    stream.use { app.backupManager.stageRestoreFromShareable(it) }
                }
            },
            latestSnapshotTime = { app.backupManager.latestSnapshot()?.lastModified() },
            onRestoreLatestSnapshot = {
                stageRestore(app) {
                    val latest = app.backupManager.latestSnapshot() ?: throw BackupException("There is no automatic backup yet")
                    app.backupManager.stageRestoreFromSnapshot(latest)
                }
            },
            onImportBackupJson = viewModel::importBackupJson,
            onDismiss = { showCloudSyncSheet = false }
        )
    }

    // Owner PIN Lock Check Dialog before unlocking Cost & Margin Mode
    if (showOwnerPinDialog) {
        AlertDialog(
            onDismissRequest = { showOwnerPinDialog = false },
            containerColor = CupertinoColors.CardSurface,
            title = {
                Text(
                    text = "Enter Owner PIN",
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter your secret 4–6 digit PIN to unlock purchase prices and profit margins.",
                        fontSize = 13.sp,
                        color = CupertinoColors.LabelSecondary
                    )
                    CupertinoTextField(
                        value = pinEntryInput,
                        onValueChange = { ch ->
                            if (ch.all { it.isDigit() } && ch.length <= 6) {
                                pinEntryInput = ch
                            }
                        },
                        label = "Owner PIN",
                        placeholder = "4–6 digits",
                        keyboardType = KeyboardType.NumberPassword
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (SyncManager.verifyOwnerPin(pinEntryInput.trim())) {
                            showOwnerPinDialog = false
                            pinEntryInput = ""
                            viewModel.toggleSellerUnlocked()
                        } else {
                            AppErrorManager.reportError(
                                title = "Wrong Owner PIN",
                                customDetail = "Entered PIN did not match. Please try again."
                            )
                            Toast.makeText(context, "Incorrect Owner PIN", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CupertinoColors.EmeraldGreen)
                ) {
                    Text("Unlock", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOwnerPinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
