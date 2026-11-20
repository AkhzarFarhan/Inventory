package com.shopledger.pos

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Surface
import com.shopledger.pos.ui.screens.stockin.StockInEntrySheet
import com.shopledger.pos.ui.screens.stockin.StockInEvent
import com.shopledger.pos.ui.screens.stockin.StockInReviewScreen
import com.shopledger.pos.ui.screens.stockin.StockInViewModel
import com.shopledger.pos.ui.theme.ConfirmDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.shopledger.pos.core.backup.AppRestarter
import com.shopledger.pos.core.backup.BackupException
import com.shopledger.pos.core.backup.BackupShare
import com.shopledger.pos.core.draft.ItemDraftManager
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.error.FloatingAppMessageHost
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.DatabaseHealth
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.PendingRestore
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.ui.InventoryFilterMode
import com.shopledger.pos.ui.MainViewModel
import com.shopledger.pos.ui.i18n.AppLanguageStore
import com.shopledger.pos.ui.navigation.AppScaffold
import com.shopledger.pos.ui.navigation.AppTab
import com.shopledger.pos.ui.screens.inventory.AddEditItemSheet
import com.shopledger.pos.ui.screens.inventory.CounterCartSheet
import com.shopledger.pos.ui.screens.inventory.ItemEditorMode
import com.shopledger.pos.ui.screens.inventory.OrderScanSheet
import com.shopledger.pos.ui.screens.inventory.QuickSellSheet
import com.shopledger.pos.ui.screens.items.ItemsHomeScreen
import com.shopledger.pos.ui.screens.khata.PartiesKhataScreen
import com.shopledger.pos.ui.screens.more.MoreScreen
import com.shopledger.pos.ui.screens.recovery.DatabaseRecoveryScreen
import com.shopledger.pos.ui.screens.sales.ReceiptShareDialog
import com.shopledger.pos.ui.screens.sales.SaleHistoryScreen
import com.shopledger.pos.ui.screens.settings.CloudSyncAndBackupSheet
import com.shopledger.pos.ui.theme.LpsCupertinoTheme
import com.shopledger.pos.ui.theme.ShopLedgerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as ShopLedgerApp
        MainViewModel.Factory(app.repository)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShopLedgerTheme {
                val health by DatabaseHealth.state.collectAsState()
                when (val current = health) {
                    is DatabaseHealth.State.Failed -> LpsCupertinoTheme { DatabaseRecoveryScreen(
                        reason = current.reason,
                        hasSafetyCopy = current.safetyCopy != null,
                        onShareSafetyCopy = {
                            current.safetyCopy?.let { BackupShare.share(this, it, "Send ShopLedger data file") }
                        },
                        onRetry = { AppRestarter.restart(this) },
                        latestSnapshotTime = remember { (application as ShopLedgerApp).backupManager.latestSnapshot()?.lastModified() },
                        onRestoreSnapshot = {
                            val app = application as ShopLedgerApp
                            stageRestore(app) {
                                val latest = app.backupManager.latestSnapshot() ?: throw BackupException("There is no automatic backup yet")
                                app.backupManager.stageRestoreFromSnapshot(latest)
                            }
                        }
                    ) }
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
    val activity = LocalContext.current as? Activity
    val filteredInventory by viewModel.filteredInventoryState.collectAsState()
    val allInventory by viewModel.allInventoryState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()
    val isSellerUnlocked by viewModel.isSellerUnlocked.collectAsState()
    val recentSearches by viewModel.recentSearchesState.collectAsState()
    val salesHistory by viewModel.salesHistoryState.collectAsState()
    val knownBuyers by viewModel.knownBuyersState.collectAsState()
    val carts by viewModel.cartsState.collectAsState()
    val activeCartSession by viewModel.activeCartSessionState.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()
    val activeReceipt by viewModel.activeReceiptForSharing.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCountState.collectAsState()
    val activeSellerName by SyncManager.sellerNameFlow.collectAsState()
    val savedItemDraft by ItemDraftManager.draftFlow.collectAsState()
    val parties by viewModel.partiesState.collectAsState()
    val context = LocalContext.current
    val stockInViewModel: StockInViewModel = viewModel(factory = StockInViewModel.Factory(app.repository))
    val stockInReview by stockInViewModel.state.collectAsState()
    var showStockInSheet by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardStockIn by remember { mutableStateOf(false) }
    LaunchedEffect(parties) { stockInViewModel.setSuppliers(parties) }
    LaunchedEffect(allInventory) { stockInViewModel.setInventory(allInventory) }
    LaunchedEffect(stockInReview?.saved) {
        val saved = stockInReview?.saved ?: return@LaunchedEffect
        val count = saved.createdItems + saved.restockedItems
        val added = context.resources.getQuantityString(R.plurals.stock_in_saved, count, count)
        val detail = if (saved.supplierDuePaise > 0L) {
            added + " " + context.getString(R.string.stock_in_saved_due, IndianCurrencyFormatter.formatPaise(saved.supplierDuePaise))
        } else added
        AppErrorManager.reportSuccess(context.getString(R.string.stock_in_saved_title), detail)
        stockInViewModel.close()
    }

    var currentTab by rememberSaveable { mutableStateOf(AppTab.ITEMS) }
    var activeEditorMode by remember { mutableStateOf<ItemEditorMode?>(null) }
    var quickSellTarget by remember { mutableStateOf<Triple<InventoryItemEntity, ItemVariantEntity, List<ItemVariantEntity>>?>(null) }
    var showCounterCartSheet by remember { mutableStateOf(false) }
    var showOrderScanSheet by remember { mutableStateOf(false) }
    var showCloudSyncSheet by remember { mutableStateOf(false) }
    var showOwnerPinDialog by remember { mutableStateOf(false) }
    var pinEntryInput by remember { mutableStateOf("") }
    val wrongPinTitle = stringResource(R.string.pin_wrong)

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

    val duePartyCount = parties.count { it.currentBalancePaise > 0L }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScaffold(
            selectedTab = currentTab,
            onTabSelected = { currentTab = it },
            badges = mapOf(AppTab.KHATA to duePartyCount),
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (currentTab) {
                    AppTab.ITEMS -> ItemsHomeScreen(
                        shopName = activeSellerName.ifBlank { stringResource(R.string.app_name) },
                        items = filteredInventory,
                        searchQuery = searchQuery,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        lowStockOnly = filterMode == InventoryFilterMode.LOW_STOCK_ONLY,
                        onLowStockOnlyChange = { low ->
                            viewModel.setFilterMode(
                                if (low) InventoryFilterMode.LOW_STOCK_ONLY else InventoryFilterMode.ALL_ALPHABETICAL
                            )
                        },
                        isSellerUnlocked = isSellerUnlocked,
                        onToggleSeller = requestToggleSeller,
                        cartLineCount = carts.sumOf { it.items.size },
                        onSell = { showCounterCartSheet = true },
                        onOpenItem = { item, variant, variants -> quickSellTarget = Triple(item, variant, variants) },
                        onEditItem = { group, variant ->
                            activeEditorMode = ItemEditorMode.ManageItem(group, variant.id)
                        },
                        onAddToCart = { item, variant -> viewModel.addOrUpdateCartItem(item, variant, 1.0) },
                        onCommitSearch = viewModel::commitSearchToHistory,
                        recentSearches = recentSearches,
                        onDeleteRecentSearch = viewModel::deleteSearchHistoryItem,
                        onClearRecentSearches = viewModel::clearAllSearchHistory,
                        draftName = savedItemDraft?.takeIf { it.hasContent }?.itemName,
                        onResumeDraft = { activeEditorMode = ItemEditorMode.AddNewItem },
                        onDiscardDraft = { ItemDraftManager.clearDraft(context) },
                        onStockIn = { showStockInSheet = true },
                        onShareLowStock = {
                            val orderText = buildString {
                                append(
                                    context.getString(
                                        R.string.reorder_list_title,
                                        activeSellerName.ifBlank { context.getString(R.string.app_name) },
                                    )
                                )
                                append("\n\n")
                                var count = 1
                                filteredInventory.forEach { group ->
                                    group.variants
                                        .filter { it.isLowStock || it.isOutOfStock }
                                        .forEach { v ->
                                            append(
                                                context.getString(
                                                    R.string.reorder_list_line,
                                                    count++,
                                                    group.item.displayName,
                                                    v.buildVariantSummary(),
                                                    IndianCurrencyFormatter.formatQuantity(v.stockQuantity),
                                                    v.unit,
                                                ) + "\n"
                                            )
                                        }
                                }
                            }
                            ReceiptShareManager.shareTextToWhatsApp(context, orderText)
                        },
                    )
                    AppTab.BILLS -> LpsCupertinoTheme {
                        SaleHistoryScreen(
                            sales = salesHistory,
                            isSellerUnlocked = isSellerUnlocked,
                            onToggleSellerUnlocked = requestToggleSeller,
                            onOpenReceiptShare = viewModel::openReceiptForHistoricalSale,
                            onReturnSale = viewModel::returnSaleLine
                        )
                    }
                    AppTab.KHATA -> LpsCupertinoTheme {
                        PartiesKhataScreen(
                            parties = parties,
                            onAddParty = viewModel::addOrUpdateParty,
                            onRecordPaymentReceived = viewModel::recordPartyPaymentReceived
                        )
                    }
                    AppTab.MORE -> MoreScreen(
                        isSellerUnlocked = isSellerUnlocked,
                        onToggleSeller = requestToggleSeller,
                        language = remember { AppLanguageStore.get(context) },
                        onLanguageSelected = { lang ->
                            if (lang != AppLanguageStore.get(context)) {
                                AppLanguageStore.set(context, lang)
                                activity?.recreate()
                            }
                        },
                        pendingSyncCount = pendingSyncCount,
                        loadDeviceCode = { withContext(Dispatchers.IO) { app.repository.localDeviceCode() } },
                        onSaveDeviceCode = { code -> withContext(Dispatchers.IO) { app.repository.setDeviceCode(code) } },
                        onOpenAccountSheet = { showCloudSyncSheet = true },
                    )
                }
            }
        }

        // The Stock In sheet stays composed (its camera/gallery launchers must survive rotation and tab changes).
        StockInEntrySheet(
            visible = showStockInSheet,
            isSellerUnlocked = isSellerUnlocked,
            onRequestUnlock = requestToggleSeller,
            onAddManually = {
                showStockInSheet = false
                activeEditorMode = ItemEditorMode.AddNewItem
            },
            onDismiss = { showStockInSheet = false },
            onScanned = { text, rawText, uri ->
                showStockInSheet = false
                stockInViewModel.openAsync(text, rawText, allInventory, uri)
            },
        )

        // A locked seller mode hides the review without losing it: unlock again and it is still there.
        stockInReview?.takeIf { isSellerUnlocked }?.let { review ->
            val requestClose = {
                if (!review.saving) {
                    if (review.rows.isEmpty()) stockInViewModel.close() else confirmDiscardStockIn = true
                }
            }
            BackHandler(onBack = requestClose)
            Surface(modifier = Modifier.fillMaxSize().zIndex(10f)) {
                StockInReviewScreen(
                    state = review,
                    onEvent = { event ->
                        when (event) {
                            StockInEvent.Close -> requestClose()
                            StockInEvent.Rescan -> {
                                stockInViewModel.close()
                                showStockInSheet = true
                            }
                            StockInEvent.AddManually -> {
                                stockInViewModel.close()
                                activeEditorMode = ItemEditorMode.AddNewItem
                            }
                            else -> stockInViewModel.onEvent(event)
                        }
                    },
                )
            }
            if (confirmDiscardStockIn) {
                ConfirmDialog(
                    title = stringResource(R.string.review_discard_title),
                    message = stringResource(R.string.review_discard_message),
                    confirmText = stringResource(R.string.review_discard_confirm),
                    destructive = true,
                    onConfirm = {
                        confirmDiscardStockIn = false
                        stockInViewModel.close()
                    },
                    onDismiss = { confirmDiscardStockIn = false },
                )
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

    // The sheets below keep their existing look until they are restyled.
    LpsCupertinoTheme {
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
                            .onFailure { AppErrorManager.reportError(context.getString(R.string.error_backup_export_failed), it) }
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
                loadDeviceCode = { withContext(Dispatchers.IO) { app.repository.localDeviceCode() } },
                onSaveDeviceCode = { code -> withContext(Dispatchers.IO) { app.repository.setDeviceCode(code) } },
                onDismiss = { showCloudSyncSheet = false }
            )
        }
    }

    // Owner PIN check before unlocking cost and profit
    if (showOwnerPinDialog) {
        AlertDialog(
            onDismissRequest = { showOwnerPinDialog = false },
            title = { Text(stringResource(R.string.pin_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pin_message), style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = pinEntryInput,
                        onValueChange = { ch ->
                            if (ch.all { it.isDigit() } && ch.length <= 6) pinEntryInput = ch
                        },
                        label = { Text(stringResource(R.string.pin_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (SyncManager.verifyOwnerPin(pinEntryInput.trim())) {
                            showOwnerPinDialog = false
                            pinEntryInput = ""
                            viewModel.toggleSellerUnlocked()
                        } else {
                            Toast.makeText(context, wrongPinTitle, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.pin_unlock), style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOwnerPinDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}
