package com.lpshardware.inventory.ui.screens.sales

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lpshardware.inventory.core.cipher.CodedPurchasePriceBadge
import com.lpshardware.inventory.core.format.IndianCurrencyFormatter
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.ui.theme.CupertinoCard
import com.lpshardware.inventory.ui.theme.CupertinoColors
import com.lpshardware.inventory.ui.theme.CupertinoSegmentedControl
import com.lpshardware.inventory.ui.theme.SemanticPillBadge
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SaleHistoryScreen(
    sales: List<SaleRecordEntity>,
    isSellerUnlocked: Boolean,
    onToggleSellerUnlocked: () -> Unit,
    onOpenReceiptShare: (SaleRecordEntity) -> Unit,
    onReturnSale: (SaleRecordEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPeriodIndex by remember { mutableIntStateOf(2) } // 0 = Today, 1 = This Month, 2 = All Time
    var saleToReturnConfirm by remember { mutableStateOf<SaleRecordEntity?>(null) }

    val nowCal = remember { Calendar.getInstance() }
    val currentYear = nowCal.get(Calendar.YEAR)
    val currentMonth = nowCal.get(Calendar.MONTH) + 1
    val currentDay = nowCal.get(Calendar.DAY_OF_MONTH)

    val filteredSales = remember(sales, selectedPeriodIndex) {
        when (selectedPeriodIndex) {
            0 -> sales.filter { it.yearInt == currentYear && it.monthInt == currentMonth && it.dayInt == currentDay }
            1 -> sales.filter { it.yearInt == currentYear && it.monthInt == currentMonth }
            else -> sales
        }
    }

    val activeSales = remember(filteredSales) { filteredSales.filter { !it.isReturned } }
    val totalRevenuePaise = remember(activeSales) { activeSales.sumOf { it.totalRevenuePaise } }
    val totalCostPaise = remember(activeSales) { activeSales.sumOf { it.totalCostPaise } }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CupertinoColors.SystemGroupedBackground)
    ) {
        // Top Controls & KPI Dashboard Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CupertinoColors.CardSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Seller Privacy Banner (Hidden by default per Requirement 3.1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSellerUnlocked) CupertinoColors.EmeraldTint
                        else CupertinoColors.CipherContainer
                    )
                    .border(
                        1.dp,
                        if (isSellerUnlocked) CupertinoColors.EmeraldGreen.copy(alpha = 0.35f)
                        else CupertinoColors.CipherIndigo.copy(alpha = 0.25f),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onToggleSellerUnlocked() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSellerUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (isSellerUnlocked) CupertinoColors.EmeraldGreen else CupertinoColors.CipherIndigo,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isSellerUnlocked) {
                            "Seller Mode Active: Margins & % Gains Visible (Tap to Hide)"
                        } else {
                            "Buyer Safe Mode: Margins Hidden by Default (Tap Seller Unlock)"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSellerUnlocked) CupertinoColors.EmeraldGreen else CupertinoColors.CipherIndigo
                    )
                }
            }

            // Period Segmented Control
            CupertinoSegmentedControl(
                options = listOf("Today", "This Month", "All Time"),
                selectedIndex = selectedPeriodIndex,
                onSelect = { selectedPeriodIndex = it }
            )

            // 3-Column KPI Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KpiMetricCard(
                    label = "Total Sales",
                    value = IndianCurrencyFormatter.formatPaise(totalRevenuePaise),
                    subBadge = "${activeSales.size} sold",
                    valueColor = CupertinoColors.LabelPrimary,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    label = "Net Margin (₹)",
                    value = if (isSellerUnlocked) {
                        IndianCurrencyFormatter.formatPaise(totalProfitPaise, showPlusSign = true)
                    } else {
                        "₹ ••••"
                    },
                    subBadge = if (isSellerUnlocked) {
                        "${IndianCurrencyFormatter.formatPercentage(avgMarginPercent, showPlus = false)} margin"
                    } else {
                        "Locked"
                    },
                    valueColor = if (isSellerUnlocked) CupertinoColors.EmeraldGreen else CupertinoColors.LabelMuted,
                    modifier = Modifier.weight(1f)
                )
                KpiMetricCard(
                    label = "Gain on Cost (%)",
                    value = if (isSellerUnlocked) {
                        IndianCurrencyFormatter.formatPercentage(avgGainPercent)
                    } else {
                        "••• %"
                    },
                    subBadge = if (isSellerUnlocked) "Markup %" else "Tap Unlock",
                    valueColor = if (isSellerUnlocked) CupertinoColors.SystemBlue else CupertinoColors.LabelMuted,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CupertinoColors.Separator)
        )

        if (filteredSales.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No sales recorded in this period",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = CupertinoColors.LabelPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sell an item from the Inventory tab to track ₹ margins, % gains, and share WhatsApp receipts.",
                    fontSize = 13.sp,
                    color = CupertinoColors.LabelSecondary
                )
            }
        } else {
            // Compact Multi-Level Hierarchical & Collapsible List (Year -> Month -> Day Item Cards)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                groupedByYearMonth.forEach { (year, monthsMap) ->
                    val isYearExpanded = expandedYears.getOrPut(year) { year == currentYear }
                    val yearSalesList = monthsMap.values.flatten().filter { !it.isReturned }
                    val yearRevenue = yearSalesList.sumOf { it.totalRevenuePaise }
                    val yearProfit = yearSalesList.sumOf { it.totalProfitPaise }

                    item(key = "year_$year") {
                        // Level 1: Year Tinted Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CupertinoColors.SystemBlue.copy(alpha = 0.10f))
                                .border(1.dp, CupertinoColors.SystemBlue.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .clickable { expandedYears[year] = !isYearExpanded }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Year $year • ${yearSalesList.size} sales",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CupertinoColors.SystemBlue
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (isSellerUnlocked) {
                                        "${IndianCurrencyFormatter.formatPaise(yearRevenue)} (${
                                            IndianCurrencyFormatter.formatPaise(yearProfit, showPlusSign = true)
                                        })"
                                    } else {
                                        IndianCurrencyFormatter.formatPaise(yearRevenue)
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CupertinoColors.LabelPrimary
                                )
                                Icon(
                                    imageVector = if (isYearExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = CupertinoColors.SystemBlue
                                )
                            }
                        }
                    }

                    if (isYearExpanded) {
                        monthsMap.forEach { (month, monthSales) ->
                            val monthKey = "${year}_$month"
                            val isMonthExpanded = expandedMonths.getOrPut(monthKey) {
                                year == currentYear && month == currentMonth
                            }
                            val monthName = DateFormatSymbols(Locale.ENGLISH).months.getOrElse(month - 1) { "Month $month" }
                            val activeMonthSales = monthSales.filter { !it.isReturned }
                            val monthRevenue = activeMonthSales.sumOf { it.totalRevenuePaise }
                            val monthProfit = activeMonthSales.sumOf { it.totalProfitPaise }

                            item(key = "month_$monthKey") {
                                // Level 2: Month Header with Left Vertical Stripe (4.dp start margin)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(CupertinoColors.CardSurface)
                                        .border(1.dp, CupertinoColors.Separator, RoundedCornerShape(10.dp))
                                        .clickable { expandedMonths[monthKey] = !isMonthExpanded }
                                        .padding(end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .height(40.dp)
                                                .background(CupertinoColors.SystemBlue)
                                        )
                                        Text(
                                            text = "$monthName $year • ${activeMonthSales.size} entries",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CupertinoColors.LabelPrimary
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isSellerUnlocked) {
                                                IndianCurrencyFormatter.formatPaise(monthProfit, showPlusSign = true)
                                            } else {
                                                IndianCurrencyFormatter.formatPaise(monthRevenue)
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSellerUnlocked) CupertinoColors.EmeraldGreen else CupertinoColors.LabelSecondary
                                        )
                                        Icon(
                                            imageVector = if (isMonthExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = CupertinoColors.LabelSecondary
                                        )
                                    }
                                }
                            }

                            if (isMonthExpanded) {
                                monthSales.forEach { sale ->
                                    item(key = "sale_${sale.id}") {
                                        // Level 3: Day Sale Item Card (8.dp start margin)
                                        SaleRecordCard(
                                            sale = sale,
                                            isSellerUnlocked = isSellerUnlocked,
                                            onShareReceipt = { onOpenReceiptShare(sale) },
                                            onReturnClick = { saleToReturnConfirm = sale },
                                            modifier = Modifier.padding(start = 8.dp)
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
        AlertDialog(
            onDismissRequest = { saleToReturnConfirm = null },
            title = { Text("Return Sale (Wapsi)?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Mark \"${target.itemNameSnapshot} (${target.variantSummarySnapshot})\" as returned and restore ${
                        IndianCurrencyFormatter.formatQuantity(target.quantitySold)
                    } ${target.unitSnapshot} back into LPS Hardware inventory?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReturnSale(target)
                        saleToReturnConfirm = null
                    }
                ) {
                    Text(
                        text = "Confirm Return & Restore Stock",
                        color = CupertinoColors.DestructiveRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { saleToReturnConfirm = null }) {
                    Text("Cancel")
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
        shape = RoundedCornerShape(12.dp),
        color = CupertinoColors.SecondarySurface,
        border = BorderStroke(1.dp, CupertinoColors.Separator)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoColors.LabelMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subBadge,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = CupertinoColors.LabelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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

    CupertinoCard(
        modifier = modifier,
        containerColor = if (sale.isReturned) CupertinoColors.SecondarySurface else CupertinoColors.CardSurface,
        borderColor = if (sale.isReturned) CupertinoColors.DestructiveRed.copy(alpha = 0.25f) else CupertinoColors.LightBorder,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sale.itemNameSnapshot,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (sale.isReturned) CupertinoColors.LabelMuted else CupertinoColors.LabelPrimary,
                    textDecoration = if (sale.isReturned) TextDecoration.LineThrough else TextDecoration.None
                )
                Text(
                    text = "${sale.variantSummarySnapshot} • #${sale.receiptNumber}",
                    fontSize = 11.sp,
                    color = CupertinoColors.LabelSecondary
                )
                Text(
                    text = buildString {
                        append(timeFormatted)
                        if (sale.buyerName.isNotBlank() || sale.buyerPhone.isNotBlank()) {
                            append(" • ")
                            append(listOf(sale.buyerName, sale.buyerPhone).filter { it.isNotBlank() }.joinToString(" "))
                        }
                    },
                    fontSize = 11.sp,
                    color = CupertinoColors.LabelMuted
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = IndianCurrencyFormatter.formatPaise(sale.totalRevenuePaise),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (sale.isReturned) CupertinoColors.LabelMuted else CupertinoColors.LabelPrimary,
                    textDecoration = if (sale.isReturned) TextDecoration.LineThrough else TextDecoration.None
                )
                Text(
                    text = "${IndianCurrencyFormatter.formatQuantity(sale.quantitySold)} ${sale.unitSnapshot} × ${
                        IndianCurrencyFormatter.formatPaise(sale.unitSellingPricePaise)
                    }",
                    fontSize = 11.sp,
                    color = CupertinoColors.LabelSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Coded Unit Cost + Profit Margin / Gain % Row
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

            if (sale.isReturned) {
                SemanticPillBadge(
                    text = "Returned (Wapsi)",
                    containerColor = CupertinoColors.DestructiveTint,
                    contentColor = CupertinoColors.DestructiveRed
                )
            } else if (isSellerUnlocked) {
                val isProfitPositive = sale.totalProfitPaise >= 0L
                SemanticPillBadge(
                    text = "${
                        IndianCurrencyFormatter.formatPaise(sale.totalProfitPaise, showPlusSign = true)
                    } (${IndianCurrencyFormatter.formatPercentage(sale.gainMarkupPercent)} gain • ${
                        IndianCurrencyFormatter.formatPercentage(sale.grossMarginPercent, showPlus = false)
                    } margin)",
                    containerColor = if (isProfitPositive) CupertinoColors.EmeraldTint else CupertinoColors.DestructiveTint,
                    contentColor = if (isProfitPositive) CupertinoColors.EmeraldGreen else CupertinoColors.DestructiveRed
                )
            } else {
                SemanticPillBadge(
                    text = "Margin Hidden",
                    containerColor = CupertinoColors.SecondarySurface,
                    contentColor = CupertinoColors.LabelMuted
                )
            }
        }

        if (!sale.isReturned) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShareReceipt,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CupertinoColors.WhatsAppGreen.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Receipt,
                        contentDescription = null,
                        tint = CupertinoColors.WhatsAppGreen,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Receipt / WhatsApp",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoColors.WhatsAppGreen
                    )
                }

                OutlinedButton(
                    onClick = onReturnClick,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CupertinoColors.Separator),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(0.9f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = null,
                        tint = CupertinoColors.LabelSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Return (Wapsi)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CupertinoColors.LabelPrimary
                    )
                }
            }
        }
    }
}
