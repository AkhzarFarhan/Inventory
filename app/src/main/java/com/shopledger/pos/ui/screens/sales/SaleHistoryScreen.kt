package com.shopledger.pos.ui.screens.sales

import com.shopledger.pos.ui.text.variantLabel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.cipher.CodedPurchasePriceBadge
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.share.ReceiptShareManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.ui.theme.ChipTone
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SegmentedChoice
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import com.shopledger.pos.ui.theme.StatusChip
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SaleHistoryScreen(
    sales: List<SaleRecordEntity>,
    isSellerUnlocked: Boolean,
    onToggleSellerUnlocked: () -> Unit,
    onOpenReceiptShare: (SaleRecordEntity) -> Unit,
    onReturnSale: (sale: SaleRecordEntity, qtyMilli: Long, actionId: String) -> Unit,
    modifier: Modifier = Modifier,
    invoiceHeaders: List<InvoiceHeaderEntity> = emptyList(),
    ledgerEntries: List<PartyTransactionEntity> = emptyList(),
) {
    val context = LocalContext.current
    var selectedPeriodIndex by remember { mutableIntStateOf(2) } // 0 = Today, 1 = This Month, 2 = All Time
    var saleToReturnConfirm by remember { mutableStateOf<SaleRecordEntity?>(null) }

    val nowCal = remember { Calendar.getInstance() }
    val currentYear = nowCal.get(Calendar.YEAR)
    val currentMonth = nowCal.get(Calendar.MONTH) + 1
    val currentDay = nowCal.get(Calendar.DAY_OF_MONTH)

    val galla = remember(invoiceHeaders, sales, ledgerEntries, selectedPeriodIndex, currentYear, currentMonth, currentDay) {
        computeGallaBreakdown(
            invoices = invoiceHeaders,
            salesFallback = sales,
            ledgerEntries = ledgerEntries,
            year = currentYear,
            month = currentMonth,
            day = currentDay,
            periodIndex = selectedPeriodIndex,
        )
    }

    val filteredSales = remember(sales, selectedPeriodIndex) {
        when (selectedPeriodIndex) {
            0 -> sales.filter { it.yearInt == currentYear && it.monthInt == currentMonth && it.dayInt == currentDay }
            1 -> sales.filter { it.yearInt == currentYear && it.monthInt == currentMonth }
            else -> sales
        }
    }

    val activeSales = remember(filteredSales) { filteredSales.filter { !it.isReturned } }
    val totalRevenuePaise = remember(activeSales) { activeSales.sumOf { it.netRevenuePaise } }
    val totalCostPaise = remember(activeSales) { activeSales.sumOf { it.netCostPaise } }
    val totalProfitPaise = totalRevenuePaise - totalCostPaise
    val avgGainPercent = remember(totalProfitPaise, totalCostPaise) {
        if (totalCostPaise > 0L) (totalProfitPaise.toDouble() / totalCostPaise.toDouble()) * 100.0 else 0.0
    }
    val avgMarginPercent = remember(totalProfitPaise, totalRevenuePaise) {
        if (totalRevenuePaise > 0L) (totalProfitPaise.toDouble() / totalRevenuePaise.toDouble()) * 100.0 else 0.0
    }

    // Group chronologically by Year -> Month
    val groupedByYearMonth = remember(filteredSales) {
        filteredSales.groupBy { it.yearInt }
            .toSortedMap(compareByDescending { it })
            .mapValues { (_, yearSales) ->
                yearSales.groupBy { it.monthInt }.toSortedMap(compareByDescending { it })
            }
    }

    val expandedYears = remember { mutableStateMapOf<Int, Boolean>() }
    val expandedMonths = remember { mutableStateMapOf<String, Boolean>() }
    val displayLocale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    val monthNames = remember(displayLocale) { DateFormatSymbols(displayLocale).months }
    val expandText = stringResource(R.string.bills_cd_expand)
    val collapseText = stringResource(R.string.bills_cd_collapse)

    Column(modifier = modifier.fillMaxSize()) {
        // Top controls & KPI summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
        ) {
            // Seller privacy banner (cost and profit hidden unless unlocked)
            Surface(
                onClick = onToggleSellerUnlocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.minTouch),
                shape = MaterialTheme.shapes.medium,
                color = if (isSellerUnlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isSellerUnlocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                ) {
                    Icon(
                        imageVector = if (isSellerUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(if (isSellerUnlocked) R.string.bills_seller_on else R.string.bills_seller_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Period filter
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.bills_period_today),
                    stringResource(R.string.bills_period_month),
                    stringResource(R.string.bills_period_all),
                ),
                selectedIndex = selectedPeriodIndex,
                onSelect = { selectedPeriodIndex = it }
            )

            // 3-column KPI summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
            ) {
                KpiMetricCard(
                    label = stringResource(R.string.bills_kpi_sales),
                    value = IndianCurrencyFormatter.formatPaise(totalRevenuePaise),
                    subBadge = pluralStringResource(R.plurals.bills_sold_count, activeSales.size, activeSales.size),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    label = stringResource(R.string.bills_kpi_margin),
                    value = if (isSellerUnlocked) {
                        IndianCurrencyFormatter.formatPaise(totalProfitPaise, showPlusSign = true)
                    } else {
                        "₹ ••••"
                    },
                    subBadge = if (isSellerUnlocked) {
                        stringResource(
                            R.string.bills_margin_pct,
                            IndianCurrencyFormatter.formatPercentage(avgMarginPercent, showPlus = false)
                        )
                    } else {
                        stringResource(R.string.bills_locked)
                    },
                    valueColor = if (isSellerUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    label = stringResource(R.string.bills_kpi_gain),
                    value = if (isSellerUnlocked) {
                        IndianCurrencyFormatter.formatPercentage(avgGainPercent)
                    } else {
                        "••• %"
                    },
                    subBadge = stringResource(if (isSellerUnlocked) R.string.bills_markup else R.string.bills_tap_unlock),
                    valueColor = if (isSellerUnlocked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            if ((invoiceHeaders.isNotEmpty() || ledgerEntries.isNotEmpty()) &&
                (galla.totalSalesCount > 0 || galla.totalJamaPaise > 0L)
            ) {
                val periodLabel = when (selectedPeriodIndex) {
                    0 -> stringResource(R.string.bills_period_today)
                    1 -> stringResource(R.string.bills_period_month)
                    else -> stringResource(R.string.bills_period_all)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingXs),
                        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.galla_title),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val sellerName = SyncManager.getSellerName(context)
                                        .ifBlank { context.getString(R.string.app_name) }
                                    val report = buildString {
                                        appendLine(context.getString(R.string.galla_report_heading, sellerName, periodLabel))
                                        appendLine(
                                            context.getString(
                                                R.string.galla_report_sales,
                                                IndianCurrencyFormatter.formatPaise(totalRevenuePaise),
                                                galla.totalSalesCount,
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.galla_report_cash,
                                                IndianCurrencyFormatter.formatPaise(galla.cashInDrawerPaise),
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.galla_report_upi,
                                                IndianCurrencyFormatter.formatPaise(galla.totalUpiPaise),
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.galla_report_udhar,
                                                IndianCurrencyFormatter.formatPaise(galla.udharGivenPaise),
                                            ),
                                        )
                                        if (galla.totalJamaPaise > 0L) {
                                            appendLine(
                                                context.getString(
                                                    R.string.galla_report_jama,
                                                    IndianCurrencyFormatter.formatPaise(galla.totalJamaPaise),
                                                ),
                                            )
                                        }
                                        if (isSellerUnlocked) {
                                            appendLine(
                                                context.getString(
                                                    R.string.galla_report_profit,
                                                    IndianCurrencyFormatter.formatPaise(totalProfitPaise),
                                                ),
                                            )
                                        }
                                    }.trimEnd()
                                    ReceiptShareManager.shareTextToWhatsApp(
                                        context = context,
                                        message = report,
                                        rawPhone = SyncManager.getSellerWhatsAppNumber(context),
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingS, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(ShopLedgerTokens.spacingXs))
                                Text(stringResource(R.string.galla_share_closing), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS),
                        ) {
                            GallaPill(
                                label = stringResource(R.string.galla_cash),
                                amountPaise = galla.cashInDrawerPaise,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            GallaPill(
                                label = stringResource(R.string.galla_upi),
                                amountPaise = galla.totalUpiPaise,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            GallaPill(
                                label = stringResource(R.string.galla_udhar),
                                amountPaise = galla.udharGivenPaise,
                                containerColor = if (galla.udharGivenPaise > 0L) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (galla.udharGivenPaise > 0L) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (galla.totalJamaPaise > 0L) {
                            Text(
                                text = stringResource(
                                    R.string.galla_includes_jama,
                                    IndianCurrencyFormatter.formatPaise(galla.totalJamaPaise),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (filteredSales.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.bills_empty_title),
                    message = stringResource(R.string.bills_empty_message),
                )
            }
        } else {
            // Multi-level collapsible list (Year -> Month -> sale cards)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingXs),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
                modifier = Modifier.fillMaxSize()
            ) {
                groupedByYearMonth.forEach { (year, monthsMap) ->
                    val isYearExpanded = expandedYears.getOrPut(year) { year == currentYear }
                    val yearSalesList = monthsMap.values.flatten().filter { !it.isReturned }
                    val yearRevenue = yearSalesList.sumOf { it.netRevenuePaise }
                    val yearProfit = yearSalesList.sumOf { it.netProfitPaise }

                    item(key = "year_$year") {
                        Surface(
                            onClick = { expandedYears[year] = !isYearExpanded },
                            modifier = Modifier.fillMaxWidth().heightIn(min = ShopLedgerTokens.minTouch),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.bills_year_header, yearSalesList.size, year, yearSalesList.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                                ) {
                                    Text(
                                        text = if (isSellerUnlocked) {
                                            "${IndianCurrencyFormatter.formatPaise(yearRevenue)} (${
                                                IndianCurrencyFormatter.formatPaise(yearProfit, showPlusSign = true)
                                            })"
                                        } else {
                                            IndianCurrencyFormatter.formatPaise(yearRevenue)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Icon(
                                        imageVector = if (isYearExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (isYearExpanded) collapseText else expandText,
                                    )
                                }
                            }
                        }
                    }

                    if (isYearExpanded) {
                        monthsMap.forEach { (month, monthSales) ->
                            val monthKey = "${year}_$month"
                            val isMonthExpanded = expandedMonths.getOrPut(monthKey) {
                                year == currentYear && month == currentMonth
                            }
                            val activeMonthSales = monthSales.filter { !it.isReturned }
                            val monthRevenue = activeMonthSales.sumOf { it.netRevenuePaise }
                            val monthProfit = activeMonthSales.sumOf { it.netProfitPaise }

                            item(key = "month_$monthKey") {
                                val monthName = monthNames.getOrNull(month - 1)?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.bills_month_fallback, month)
                                Surface(
                                    onClick = { expandedMonths[monthKey] = !isMonthExpanded },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = ShopLedgerTokens.spacingXs)
                                        .heightIn(min = ShopLedgerTokens.minTouch),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingM, vertical = ShopLedgerTokens.spacingS),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = pluralStringResource(R.plurals.bills_month_header, activeMonthSales.size, monthName, year, activeMonthSales.size),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs)
                                        ) {
                                            Text(
                                                text = if (isSellerUnlocked) {
                                                    IndianCurrencyFormatter.formatPaise(monthProfit, showPlusSign = true)
                                                } else {
                                                    IndianCurrencyFormatter.formatPaise(monthRevenue)
                                                },
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (isSellerUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Icon(
                                                imageVector = if (isMonthExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (isMonthExpanded) collapseText else expandText,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (isMonthExpanded) {
                                monthSales.forEach { sale ->
                                    item(key = "sale_${sale.id}") {
                                        SaleRecordCard(
                                            sale = sale,
                                            isSellerUnlocked = isSellerUnlocked,
                                            onShareReceipt = { onOpenReceiptShare(sale) },
                                            onReturnClick = { saleToReturnConfirm = sale },
                                            modifier = Modifier.padding(start = ShopLedgerTokens.spacingS)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    saleToReturnConfirm?.let { target ->
        val remainingMilli = (target.quantitySoldMilli - target.returnedMilliUnits).coerceAtLeast(0L)
        val returnActionId = remember(target.id) { UUID.randomUUID().toString() }
        var qtyText by remember(target.id) { mutableStateOf(java.math.BigDecimal.valueOf(remainingMilli, 3).stripTrailingZeros().toPlainString()) }
        val qtyMilli = qtyText.trim().toBigDecimalOrNull()?.let { bd -> runCatching { bd.movePointRight(3).longValueExact() }.getOrNull() }
        val qtyValid = qtyMilli != null && qtyMilli in 1L..remainingMilli
        AlertDialog(
            onDismissRequest = { saleToReturnConfirm = null },
            title = { Text(stringResource(R.string.bills_return_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.bills_return_message,
                            target.itemNameSnapshot,
                            target.variantSummarySnapshot.variantLabel(),
                            IndianCurrencyFormatter.formatMilliUnits(remainingMilli),
                            target.unitSnapshot
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(ShopLedgerTokens.spacingM))
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text(stringResource(R.string.bills_return_qty, target.unitSnapshot)) },
                        singleLine = true,
                        isError = !qtyValid,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = qtyValid,
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                    onClick = {
                        onReturnSale(target, qtyMilli!!, returnActionId)
                        saleToReturnConfirm = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.bills_return_confirm),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (qtyValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { saleToReturnConfirm = null },
                    modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                ) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

@Composable
private fun KpiMetricCard(
    label: String,
    value: String,
    subBadge: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingS, vertical = ShopLedgerTokens.spacingXs),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subBadge,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GallaPill(
    label: String,
    amountPaise: Long,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ShopLedgerTokens.spacingS, vertical = ShopLedgerTokens.spacingXs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = IndianCurrencyFormatter.formatPaise(amountPaise),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SaleRecordCard(
    sale: SaleRecordEntity,
    isSellerUnlocked: Boolean,
    onShareReceipt: () -> Unit,
    onReturnClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatted = remember(sale.soldAt) {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(sale.soldAt))
    }
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (sale.isReturned) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (sale.isReturned) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Column(
            modifier = Modifier.padding(ShopLedgerTokens.spacingM),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sale.itemNameSnapshot,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (sale.isReturned) mutedColor else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (sale.isReturned) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${sale.variantSummarySnapshot.variantLabel()} • #${sale.receiptNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor
                    )
                    Text(
                        text = buildString {
                            append(timeFormatted)
                            if (sale.buyerName.isNotBlank() || sale.buyerPhone.isNotBlank()) {
                                append(" • ")
                                append(listOf(sale.buyerName, sale.buyerPhone).filter { it.isNotBlank() }.joinToString(" "))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = IndianCurrencyFormatter.formatPaise(sale.totalRevenuePaise),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (sale.isReturned) mutedColor else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (sale.isReturned) TextDecoration.LineThrough else TextDecoration.None
                    )
                    Text(
                        text = "${IndianCurrencyFormatter.formatQuantity(sale.quantitySold)} ${sale.unitSnapshot} × ${
                            IndianCurrencyFormatter.formatPaise(sale.unitSellingPricePaise)
                        }",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor
                    )
                }
            }

            // Coded unit cost + profit / returned status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CodedPurchasePriceBadge(
                    purchasePricePaise = sale.unitPurchasePricePaise,
                    isSellerUnlocked = isSellerUnlocked,
                    glyphSize = 14.dp
                )
                Spacer(Modifier.width(ShopLedgerTokens.spacingS))

                if (sale.isReturned) {
                    StatusChip(text = stringResource(R.string.bills_returned), tone = ChipTone.DANGER)
                } else if (sale.returnedMilliUnits > 0L) {
                    StatusChip(
                        text = stringResource(
                            R.string.bills_returned_qty,
                            IndianCurrencyFormatter.formatMilliUnits(sale.returnedMilliUnits),
                            sale.unitSnapshot
                        ),
                        tone = ChipTone.DANGER
                    )
                } else if (isSellerUnlocked) {
                    val isProfitPositive = sale.totalProfitPaise >= 0L
                    StatusChip(
                        text = stringResource(
                            R.string.bills_profit_chip,
                            IndianCurrencyFormatter.formatPaise(sale.totalProfitPaise, showPlusSign = true),
                            IndianCurrencyFormatter.formatPercentage(sale.gainMarkupPercent),
                            IndianCurrencyFormatter.formatPercentage(sale.grossMarginPercent, showPlus = false)
                        ),
                        tone = if (isProfitPositive) ChipTone.SUCCESS else ChipTone.DANGER
                    )
                }
            }

            if (!sale.isReturned) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingS)
                ) {
                    SecondaryActionButton(
                        text = stringResource(R.string.bills_receipt_share),
                        icon = Icons.Default.Receipt,
                        onClick = onShareReceipt,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.bills_return),
                        icon = Icons.AutoMirrored.Filled.Undo,
                        onClick = onReturnClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
