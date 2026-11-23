package com.shopledger.pos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.shopledger.pos.ui.text.UiText
import com.shopledger.pos.ui.screens.stockin.StockInEntrySheet
import com.shopledger.pos.ui.screens.stockin.StockInEvent
import com.shopledger.pos.ui.screens.stockin.StockInReviewScreen
import com.shopledger.pos.ui.screens.stockin.StockInViewModel
import com.shopledger.pos.ui.text.orStandard
import com.shopledger.pos.ui.theme.ConfirmDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.shopledger.pos.ui.InventoryFilterMode
import com.shopledger.pos.ui.MainViewModel
import com.shopledger.pos.ui.displayLabel
import com.shopledger.pos.ui.i18n.AppLanguage
import com.shopledger.pos.ui.i18n.AppLanguageStore
import com.shopledger.pos.ui.navigation.AppScaffold
import com.shopledger.pos.ui.navigation.AppTab
import com.shopledger.pos.ui.screens.inventory.AddEditItemSheet
import com.shopledger.pos.ui.screens.checkout.CheckoutRoute
import com.shopledger.pos.ui.screens.inventory.ItemEditorMode
import com.shopledger.pos.ui.screens.inventory.OrderScanSheet
import com.shopledger.pos.ui.screens.items.AddToCartEvent
import com.shopledger.pos.ui.screens.items.AddToCartSheet
import com.shopledger.pos.ui.screens.items.AddToCartState
import com.shopledger.pos.ui.screens.items.AddToCartVariantRow
import com.shopledger.pos.ui.screens.items.ItemsHomeScreen
import com.shopledger.pos.ui.screens.items.statusOf
import com.shopledger.pos.ui.screens.items.variantStockText
import com.shopledger.pos.ui.text.variantLabel
import com.shopledger.pos.data.local.dao.LastPurchaseInfo
import com.shopledger.pos.data.local.entity.PurchaseBillLineEntity
import com.shopledger.pos.ui.screens.khata.DistributorDetailScreen
import com.shopledger.pos.ui.screens.khata.DistributorDetailState
import com.shopledger.pos.ui.screens.khata.DistributorEvent
import com.shopledger.pos.ui.screens.khata.KhataTab
import com.shopledger.pos.ui.screens.khata.PayDistributorDialog
import com.shopledger.pos.ui.screens.khata.PurchaseBillSheet
import com.shopledger.pos.ui.screens.more.MoreScreen
import com.shopledger.pos.ui.screens.recovery.DatabaseRecoveryScreen
import com.shopledger.pos.ui.screens.setup.SetupEvent
import com.shopledger.pos.ui.screens.setup.SetupWizardHost
import com.shopledger.pos.ui.screens.sales.ReceiptShareDialog
import com.shopledger.pos.ui.screens.sales.SaleHistoryScreen
import com.shopledger.pos.ui.screens.settings.CloudSyncAndBackupSheet
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
                    is DatabaseHealth.State.Failed -> DatabaseRecoveryScreen(
                        reason = current.reason,
                        hasSafetyCopy = current.safetyCopy != null,
                        onShareSafetyCopy = {
                            current.safetyCopy?.let { BackupShare.share(this, it, getString(R.string.share_data_file_chooser)) }
                        },
                        onRetry = { AppRestarter.restart(this) },
                        latestSnapshotTime = remember { (application as ShopLedgerApp).backupManager.latestSnapshot()?.lastModified() },
                        onRestoreSnapshot = {
                            val app = application as ShopLedgerApp
                            stageRestore(app) {
                                val latest = app.backupManager.latestSnapshot() ?: throw BackupException("There is no automatic backup yet", userMessage = R.string.msg_backup_none)
                                app.backupManager.stageRestoreFromSnapshot(latest)
                            }
                        }
                    )
                    DatabaseHealth.State.Ok -> {
                        val setupDone by SyncManager.isSetupCompletedFlow.collectAsState()
                        if (setupDone) LpsHardwareMainApp(viewModel = viewModel) else FirstRunSetup(viewModel)
                    }
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
        val text = AppLanguageStore.localizedContext(app)
        // Plain message for the shopkeeper; the technical reason only rides along in debug builds.
        fun plain(@androidx.annotation.StringRes id: Int, cause: Throwable?): String =
            text.getString(id) + if (BuildConfig.DEBUG && cause?.message != null) " (${cause.message})" else ""
        var staged = false
        try {
            stage()
            staged = true
            PendingRestore.armStaged(app, dbName)
            if (!AppRestarter.restart(app)) {
                PendingRestore.discardStaged(app, dbName)
                text.getString(R.string.msg_restore_no_restart)
            } else {
                null
            }
        } catch (e: BackupException) {
            runCatching { PendingRestore.discardStaged(app, dbName) }
            plain(e.userMessage, e)
        } catch (e: Throwable) {
            runCatching { PendingRestore.discardStaged(app, dbName) }
            if (e is CancellationException) throw e
            plain(if (staged) R.string.msg_restore_not_started else R.string.msg_backup_unreadable, e)
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
    val carts by viewModel.cartsState.collectAsState()
    val activeCartId by viewModel.activeCartIdState.collectAsState()
    val activeCartSession by viewModel.activeCartSessionState.collectAsState()
    val activeReceipt by viewModel.activeReceiptForSharing.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCountState.collectAsState()
    val activeSellerName by SyncManager.sellerNameFlow.collectAsState()
    val savedItemDraft by ItemDraftManager.draftFlow.collectAsState()
    val parties by viewModel.partiesState.collectAsState()
    val invoiceHeaders by viewModel.invoiceHeadersState.collectAsState()
    val partyLedgerEntries by viewModel.partyLedgerEntriesState.collectAsState()
    val purchaseBills by viewModel.purchaseBillsState.collectAsState()
    val availableCategories by viewModel.availableCategoriesState.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val topSoldOnly by viewModel.topSoldOnly.collectAsState()
    val topSoldItemIds by viewModel.topSoldItemIdsState.collectAsState()
    val context = LocalContext.current
    val activeCartLabel = activeCartSession.displayLabel(context)
    val activeCartQtyByVariant = remember(activeCartSession.items) {
        activeCartSession.items.associate { it.variantId to it.quantity }
    }
    val stockInViewModel: StockInViewModel = viewModel(factory = StockInViewModel.Factory(app.repository))
    val stockInReview by stockInViewModel.state.collectAsState()
    var showStockInSheet by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardStockIn by remember { mutableStateOf(false) }
    val stockInReading by stockInViewModel.reading.collectAsState()
    // Once a review is open the entry sheet has done its job.
    LaunchedEffect(stockInReview != null) { if (stockInReview != null) showStockInSheet = false }
    LaunchedEffect(parties) { stockInViewModel.setSuppliers(parties) }
    LaunchedEffect(allInventory) { stockInViewModel.setInventory(allInventory) }
    LaunchedEffect(stockInReview?.saved) {
        val saved = stockInReview?.saved ?: return@LaunchedEffect
        val count = saved.createdItems + saved.restockedItems
        val added = context.resources.getQuantityString(R.plurals.stock_in_saved, count, count)
        val detail = if (saved.supplierDuePaise > 0L) {
            added + " " + context.getString(R.string.stock_in_saved_due, IndianCurrencyFormatter.formatPaise(saved.supplierDuePaise))
        } else added
        AppErrorManager.reportSuccess(UiText.Res(R.string.stock_in_saved_title), UiText.Raw(detail))
        stockInViewModel.close()
    }

    var currentTab by rememberSaveable { mutableStateOf(AppTab.ITEMS) }
    var activeEditorMode by remember { mutableStateOf<ItemEditorMode?>(null) }
    var addSheetItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCheckout by rememberSaveable { mutableStateOf(false) }
    var showSetupAgain by rememberSaveable { mutableStateOf(false) }
    var showOrderScanSheet by remember { mutableStateOf(false) }
    var showCloudSyncSheet by remember { mutableStateOf(false) }
    var openDistributorId by rememberSaveable { mutableStateOf<String?>(null) }
    var payDistributorId by rememberSaveable { mutableStateOf<String?>(null) }
    var openPurchaseBillId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(isSellerUnlocked) {
        if (!isSellerUnlocked) {
            openDistributorId = null
            payDistributorId = null
            openPurchaseBillId = null
        }
    }
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

    val duePartyCount = parties.count { it.partyType != "SUPPLIER" && it.currentBalancePaise > 0L }

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
                        carts = carts.map {
                            com.shopledger.pos.ui.screens.cart.CartChip(
                                cartId = it.cartId,
                                label = it.displayLabel(context),
                                lineCount = it.items.size,
                                subtotalPaise = it.items.sumOf { line -> line.lineRevenuePaise },
                            )
                        },
                        activeCartId = activeCartId,
                        onSwitchCart = viewModel::switchActiveCart,
                        onNewCart = {
                            if (viewModel.createNewCart() == null) AppErrorManager.reportInfo(
                                UiText.Res(R.string.cart_limit_title),
                                UiText.Res(R.string.cart_limit_message, listOf(com.shopledger.pos.ui.MAX_OPEN_CARTS)),
                            )
                        },
                        categories = availableCategories,
                        selectedCategory = selectedCategory,
                        onSelectCategory = viewModel::setSelectedCategory,
                        topSoldOnly = topSoldOnly,
                        onTopSoldOnlyChange = viewModel::setTopSoldOnly,
                        hasTopSold = topSoldItemIds.isNotEmpty(),
                        onRenameCart = viewModel::renameCart,
                        onCloseCart = viewModel::closeCart,
                        onSell = { showCheckout = true },
                        onOpenItem = { item, _, _ -> addSheetItemId = item.id },
                        onEditItem = { group, variant ->
                            if (!isSellerUnlocked) requestToggleSeller()
                            else activeEditorMode = ItemEditorMode.ManageItem(group, variant.id)
                        },
                        onAddToCart = { item, variant ->
                            viewModel.addOrUpdateCartItem(item, variant, 1.0)
                            AppErrorManager.reportSuccess(
                                UiText.Res(R.string.cart_added_title),
                                UiText.Res(R.string.cart_added_message, listOf(item.displayName, activeCartLabel)),
                            )
                        },
                        activeCartQtyByVariant = activeCartQtyByVariant,
                        onUpdateCartQty = { item, variant, newQty ->
                            val existing = activeCartSession.items.firstOrNull { it.variantId == variant.id }
                            when {
                                newQty <= 0.0 -> viewModel.removeCartLine(variant.id)
                                existing != null -> viewModel.updateCartLineQuantityAndPrice(
                                    variant.id,
                                    newQty,
                                    existing.customSellingPricePaise,
                                )
                                else -> viewModel.addOrUpdateCartItem(item, variant, newQty)
                            }
                        },
                        onCommitSearch = viewModel::commitSearchToHistory,
                        recentSearches = recentSearches,
                        onDeleteRecentSearch = viewModel::deleteSearchHistoryItem,
                        onClearRecentSearches = viewModel::clearAllSearchHistory,
                        draftName = savedItemDraft?.takeIf { it.hasContent }?.itemName,
                        onResumeDraft = {
                            if (!isSellerUnlocked) requestToggleSeller()
                            else activeEditorMode = ItemEditorMode.AddNewItem
                        },
                        onDiscardDraft = { ItemDraftManager.clearDraft(context) },
                        onStockIn = { showStockInSheet = true },
                        // A review kept while seller mode is locked must stay findable: tap to unlock and continue.
                        resumeStockInRows = stockInReview?.rows?.size?.takeIf { !isSellerUnlocked },
                        onResumeStockIn = requestToggleSeller,
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
                                                    v.buildVariantSummary().orStandard(context.getString(R.string.item_form_standard)),
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
                    AppTab.BILLS -> SaleHistoryScreen(
                        sales = salesHistory,
                        isSellerUnlocked = isSellerUnlocked,
                        onToggleSellerUnlocked = requestToggleSeller,
                        onOpenReceiptShare = viewModel::openReceiptForHistoricalSale,
                        onReturnSale = viewModel::returnSaleLine,
                        invoiceHeaders = invoiceHeaders,
                        ledgerEntries = partyLedgerEntries,
                    )
                    AppTab.KHATA -> KhataTab(
                        parties = parties,
                        ledgerEntries = partyLedgerEntries,
                        purchaseBills = purchaseBills,
                        isSellerUnlocked = isSellerUnlocked,
                        onRequestUnlock = requestToggleSeller,
                        onAddParty = viewModel::addOrUpdateParty,
                        onRecordPaymentReceived = viewModel::recordPartyPaymentReceived,
                        onAddDistributor = viewModel::addDistributor,
                        onPayDistributor = viewModel::recordPaymentToDistributor,
                        onOpenDistributor = { openDistributorId = it },
                    )
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
                        onRunSetupAgain = { showSetupAgain = true },
                        setupIncludesPin = !SyncManager.hasOwnerPin() || isSellerUnlocked,
                    )
                }
            }
        }

        // The Stock In sheet stays composed (its camera/gallery launchers must survive rotation and tab changes).
        StockInEntrySheet(
            visible = showStockInSheet,
            isSellerUnlocked = isSellerUnlocked,
            onRequestUnlock = requestToggleSeller,
            onManualBill = {
                stockInViewModel.openManual()
                showStockInSheet = false
            },
            onAddManually = {
                if (!isSellerUnlocked) requestToggleSeller()
                else {
                    showStockInSheet = false
                    activeEditorMode = ItemEditorMode.AddNewItem
                }
            },
            onDismiss = { showStockInSheet = false },
            reading = stockInReading,
            hasUnsavedReview = stockInReview != null,
            onDiscardReview = { stockInViewModel.close() },
            onPhotos = { uris -> stockInViewModel.readPhotos(context, uris) },
            onBitmap = { bitmap -> stockInViewModel.readBitmap(context, bitmap) },
        )

        openDistributorId?.takeIf { isSellerUnlocked }?.let { distId ->
            val party = parties.firstOrNull { it.id == distId }
            if (party != null) {
                val distLedger by remember(distId) { viewModel.ledgerForParty(distId) }.collectAsState(initial = emptyList())
                val distBills by remember(distId) { viewModel.billsForSupplier(distId) }.collectAsState(initial = emptyList())
                val distItems by remember(distId) { viewModel.itemsForSupplier(distId) }.collectAsState(initial = emptyList())
                BackHandler { openDistributorId = null }
                Surface(modifier = Modifier.fillMaxSize().zIndex(9f)) {
                    DistributorDetailScreen(
                        state = DistributorDetailState(
                            party = party,
                            ledger = distLedger,
                            bills = distBills,
                            items = distItems,
                        ),
                        onEvent = { event ->
                            when (event) {
                                DistributorEvent.Close -> openDistributorId = null
                                DistributorEvent.Pay -> payDistributorId = party.id
                                is DistributorEvent.OpenBill -> openPurchaseBillId = event.purchaseId
                                DistributorEvent.Call -> {
                                    if (party.phone.isNotBlank()) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${party.phone}")))
                                        }
                                    }
                                }
                                DistributorEvent.WhatsApp -> {
                                    val shopName = activeSellerName.ifBlank { context.getString(R.string.app_name) }
                                    val owed = IndianCurrencyFormatter.formatPaise((-party.currentBalancePaise).coerceAtLeast(0L))
                                    val msg = context.getString(R.string.distributor_whatsapp_text, party.name, shopName, owed)
                                    ReceiptShareManager.shareTextToWhatsApp(context, msg, party.phone)
                                }
                            }
                        },
                    )
                }
            }
        }

        payDistributorId?.takeIf { isSellerUnlocked }?.let { id ->
            parties.firstOrNull { it.id == id }?.let { target ->
                PayDistributorDialog(
                    party = target,
                    onConfirm = { amount, mode, note ->
                        viewModel.recordPaymentToDistributor(target.id, amount, mode, note)
                        payDistributorId = null
                    },
                    onDismiss = { payDistributorId = null },
                )
            }
        }

        openPurchaseBillId?.takeIf { isSellerUnlocked }?.let { billId ->
            purchaseBills.firstOrNull { it.id == billId }?.let { bill ->
                var lines by remember(billId) { mutableStateOf<List<PurchaseBillLineEntity>>(emptyList()) }
                LaunchedEffect(billId) { lines = viewModel.purchaseLines(billId) }
                PurchaseBillSheet(
                    bill = bill,
                    lines = lines,
                    onDismiss = { openPurchaseBillId = null },
                )
            }
        }

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

        // "Run setup again" from More: no sample-data step; the PIN step is offered only when no PIN exists or seller mode
        // is unlocked (the same gate the Security card uses), and skipping it never clears an existing PIN.
        if (showSetupAgain) {
            Surface(modifier = Modifier.fillMaxSize().zIndex(11f)) {
                SetupWizardHost(
                    firstRun = false,
                    initialName = activeSellerName.takeIf { SyncManager.hasStoredSellerName(context) }.orEmpty(),
                    initialVertical = SyncManager.getActiveVertical(),
                    pinAvailable = !SyncManager.hasOwnerPin() || isSellerUnlocked,
                    language = remember { AppLanguageStore.get(context) },
                    onLanguageSelected = { lang -> switchLanguage(context, activity, lang) },
                    onFinish = { done ->
                        saveSetupChoices(context, done)
                        showSetupAgain = false
                    },
                    onCancel = { showSetupAgain = false },
                )
            }
        }

        // One-screen checkout: a full-screen destination above the tabs (Sell button, "open cart", scanned order list).
        if (showCheckout) {
            BackHandler(onBack = { showCheckout = false })
            Surface(modifier = Modifier.fillMaxSize().zIndex(9f)) {
                CheckoutRoute(
                    viewModel = viewModel,
                    onClose = { showCheckout = false },
                    onOpenOrderScanner = { showOrderScanSheet = true },
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

    run {
        // Add / Edit Item & Variant Sheet
        activeEditorMode?.let { mode ->
            val managedVariantId = (mode as? ItemEditorMode.ManageItem)?.let { m ->
                if (m.startInAddVariantMode) null else m.initialVariantId ?: m.itemWithVariants.variants.firstOrNull()?.id
            }
            val lastPurchase by produceState<LastPurchaseInfo?>(initialValue = null, key1 = managedVariantId) {
                value = managedVariantId?.let { viewModel.lastPurchaseForSku(it) }
            }
            AddEditItemSheet(
                mode = mode,
                existingCategories = allExistingCategories,
                isSellerUnlocked = isSellerUnlocked,
                lastPurchase = lastPurchase,
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

        addSheetItemId?.let { itemId ->
            val group = allInventory.firstOrNull { it.item.id == itemId }
            if (group == null) {
                addSheetItemId = null
                return@let
            }
            val session = activeCartSession
            val state = AddToCartState(
                itemName = group.item.displayName,
                cartLabel = session.displayLabel(context),
                variants = group.variants.map { v ->
                    AddToCartVariantRow(
                        variantId = v.id,
                        label = v.buildVariantSummary().variantLabel(),
                        pricePaise = v.sellingPricePaise,
                        purchasePricePaise = v.purchasePricePaise,
                        stockText = variantStockText(v),
                        status = statusOf(v),
                        qtyInCart = session.items.firstOrNull { it.variantId == v.id }?.quantity ?: 0.0,
                        unit = v.unit,
                    )
                },
                cartLineCount = session.items.size,
                cartTotalPaise = session.items.sumOf { it.lineRevenuePaise },
                isSellerUnlocked = isSellerUnlocked,
            )
            AddToCartSheet(
                state = state,
                onDismiss = { addSheetItemId = null },
                onEvent = { e ->
                    when (e) {
                        is AddToCartEvent.Add -> group.variants.firstOrNull { it.id == e.variantId }
                            ?.let { viewModel.addOrUpdateCartItem(group.item, it, 1.0) }
                        is AddToCartEvent.SetQty -> {
                            val line = session.items.firstOrNull { it.variantId == e.variantId }
                            when {
                                e.qty <= 0.0 -> viewModel.removeCartLine(e.variantId)
                                line != null -> viewModel.updateCartLineQuantityAndPrice(
                                    e.variantId,
                                    e.qty,
                                    line.customSellingPricePaise,
                                )
                                else -> group.variants.firstOrNull { it.id == e.variantId }
                                    ?.let { viewModel.addOrUpdateCartItem(group.item, it, e.qty) }
                            }
                        }
                        AddToCartEvent.GoToCart -> {
                            addSheetItemId = null
                            showCheckout = true
                        }
                        AddToCartEvent.Done -> addSheetItemId = null
                    }
                },
            )
        }

        // Unbundled On-Device Order List / Receipt Scanner Sheet
        if (showOrderScanSheet) {
            OrderScanSheet(
                inventory = allInventory,
                activeCartLabel = activeCartSession.displayLabel(context),
                onAddScannedItemsAndOpenCart = { scannedLines ->
                    viewModel.batchAddItemsToActiveCart(scannedLines)
                    showOrderScanSheet = false
                    showCheckout = true
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
                            .onFailure { AppErrorManager.reportError(UiText.Res(R.string.error_backup_export_failed), UiText.Res(R.string.err_backup_export_detail), it) }
                            .getOrNull()
                    }
                },
                onRestoreBackupFile = { uri ->
                    stageRestore(app) {
                        val stream = app.contentResolver.openInputStream(uri) ?: throw BackupException("That file could not be opened", userMessage = R.string.msg_backup_unreadable)
                        stream.use { app.backupManager.stageRestoreFromShareable(it) }
                    }
                },
                latestSnapshotTime = { app.backupManager.latestSnapshot()?.lastModified() },
                onRestoreLatestSnapshot = {
                    stageRestore(app) {
                        val latest = app.backupManager.latestSnapshot() ?: throw BackupException("There is no automatic backup yet", userMessage = R.string.msg_backup_none)
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

/** First launch of a fresh install: the wizard replaces the main UI until it finishes (sample items are decided here). */
@Composable
private fun FirstRunSetup(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val saving by viewModel.setupSaving.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            SetupWizardHost(
                firstRun = true,
                initialName = "",
                initialVertical = SyncManager.getActiveVertical(),
                pinAvailable = true,
                language = remember { AppLanguageStore.get(context) },
                onLanguageSelected = { lang -> switchLanguage(context, activity, lang) },
                saving = saving,
                onFinish = { done ->
                    viewModel.finishSetup(done.sampleData) {
                        saveSetupChoices(context, done)
                        SyncManager.saveSetupCompleted(context, true)
                    }
                },
                onCancel = {},
            )
        }
        // Setup failures (disk full, database error) must be visible; the wizard replaces the main UI that normally hosts this.
        FloatingAppMessageHost(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .zIndex(20f)
        )
    }
}

private fun switchLanguage(context: Context, activity: Activity?, lang: AppLanguage) {
    if (lang != AppLanguageStore.get(context)) {
        AppLanguageStore.set(context, lang)
        activity?.recreate()
    }
}

/** Saves through the same SyncManager calls the Settings screens use (the PIN is hashed by saveOwnerPin). */
private fun saveSetupChoices(context: Context, done: SetupEvent.Finish) {
    SyncManager.saveSellerName(context, done.name)
    SyncManager.saveShopVertical(context, done.vertical)
    done.pin?.let { SyncManager.saveOwnerPin(context, it) }
}
