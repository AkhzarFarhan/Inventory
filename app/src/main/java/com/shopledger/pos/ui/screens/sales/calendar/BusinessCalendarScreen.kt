package com.shopledger.pos.ui.screens.sales.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shopledger.pos.R
import com.shopledger.pos.core.format.IndianCurrencyFormatter
import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.ui.theme.EmptyState
import com.shopledger.pos.ui.theme.SecondaryActionButton
import com.shopledger.pos.ui.theme.SectionCard
import com.shopledger.pos.ui.theme.ShopLedgerTokens
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BusinessCalendarScreen(
    invoices: List<InvoiceHeaderEntity>,
    ledgerEntries: List<PartyTransactionEntity>,
    purchaseBills: List<PurchaseBillEntity>,
    isSellerUnlocked: Boolean,
    onOpenPurchaseBill: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialYear: Int? = null,
    initialMonth: Int? = null,
) {
    val today = remember { CalendarParts.of(System.currentTimeMillis()) }
    var year by rememberSaveable { mutableIntStateOf(initialYear ?: today.year) }
    var month by rememberSaveable { mutableIntStateOf(initialMonth ?: today.month) }
    var selectedDay by rememberSaveable { mutableStateOf<Int?>(null) }

    val isCurrentOrFuture = year > today.year || (year == today.year && month >= today.month)
    val isNotCurrentMonth = year != today.year || month != today.month

    val summary = remember(year, month, invoices, ledgerEntries, purchaseBills) {
        computeMonthSummary(year, month, invoices, ledgerEntries, purchaseBills)
    }
    val gridWeeks = remember(year, month) { monthGridCells(year, month).chunked(7) }
    val monthTitle = remember(year, month) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    }
    val weekdays = remember {
        val symbols = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
        (Calendar.SUNDAY..Calendar.SATURDAY).map { symbols[it] }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
        verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (month == 1) {
                            year--
                            month = 12
                        } else {
                            month--
                        }
                        selectedDay = null
                    },
                    modifier = Modifier.size(ShopLedgerTokens.minTouch),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.calendar_prev_month),
                    )
                }

                Text(
                    text = monthTitle,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (isNotCurrentMonth) {
                    TextButton(
                        onClick = {
                            year = today.year
                            month = today.month
                            selectedDay = null
                        },
                        modifier = Modifier.heightIn(min = ShopLedgerTokens.minTouch),
                    ) {
                        Text(stringResource(R.string.calendar_today), style = MaterialTheme.typography.labelLarge)
                    }
                }

                IconButton(
                    onClick = {
                        if (month == 12) {
                            year++
                            month = 1
                        } else {
                            month++
                        }
                        selectedDay = null
                    },
                    enabled = !isCurrentOrFuture,
                    modifier = Modifier.size(ShopLedgerTokens.minTouch),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.calendar_next_month),
                    )
                }
            }
        }

        item(key = "totals") {
            val total = summary.total
            SectionCard(title = stringResource(R.string.calendar_month_total)) {
                CalendarAmountRow(
                    label = stringResource(R.string.calendar_sale),
                    amount = IndianCurrencyFormatter.formatPaise(total.salesPaise),
                )
                CalendarAmountRow(
                    label = stringResource(R.string.calendar_nagad),
                    amount = IndianCurrencyFormatter.formatPaise(total.cashSalesPaise),
                )
                CalendarAmountRow(
                    label = stringResource(R.string.calendar_upi),
                    amount = IndianCurrencyFormatter.formatPaise(total.upiSalesPaise),
                )
                CalendarAmountRow(
                    label = stringResource(R.string.calendar_udhaar_given),
                    amount = IndianCurrencyFormatter.formatPaise(total.udhaarGivenPaise),
                )
                CalendarAmountRow(
                    label = stringResource(R.string.calendar_udhaar_collected),
                    amount = IndianCurrencyFormatter.formatPaise(total.udhaarCollectedPaise),
                )
                if (isSellerUnlocked) {
                    HorizontalDivider()
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_stock_in),
                        amount = IndianCurrencyFormatter.formatPaise(total.stockInPaise),
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_stock_in_udhaar),
                        amount = IndianCurrencyFormatter.formatPaise(total.stockInOnUdhaarPaise),
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_paid_distributors),
                        amount = IndianCurrencyFormatter.formatPaise(total.paidToDistributorsPaise),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.calendar_locked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "grid") {
            Column(verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    weekdays.forEach { dayName ->
                        Text(
                            text = dayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                gridWeeks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        week.forEach { day ->
                            if (day == null) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(0.8f)
                                        .heightIn(min = 56.dp),
                                )
                            } else {
                                val daySummary = summary.days[day] ?: DaySummary(day = day)
                                val isToday = year == today.year && month == today.month && day == today.day
                                val isSelected = selectedDay == day
                                val cd = stringResource(
                                    R.string.calendar_day_cd,
                                    day,
                                    IndianCurrencyFormatter.formatPaise(daySummary.salesPaise),
                                    IndianCurrencyFormatter.formatPaise(daySummary.udhaarGivenPaise),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(0.8f)
                                        .heightIn(min = 56.dp)
                                        .testTag("calendar_day_$day")
                                        .clip(MaterialTheme.shapes.small)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                        )
                                        .clickable { selectedDay = day }
                                        .semantics(mergeDescendants = true) { contentDescription = cd }
                                        .padding(4.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Box(
                                            modifier = if (isToday) {
                                                Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            } else {
                                                Modifier
                                            },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = day.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isToday) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                            )
                                        }

                                        if (daySummary.salesPaise != 0L) {
                                            Text(
                                                text = formatCompactRupees(daySummary.salesPaise),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (daySummary.udhaarGivenPaise > 0L) {
                                                LegendDot(MaterialTheme.colorScheme.tertiary)
                                            }
                                            if (daySummary.udhaarCollectedPaise > 0L) {
                                                LegendDot(MaterialTheme.colorScheme.secondary)
                                            }
                                            if (isSellerUnlocked && daySummary.stockInBillCount > 0) {
                                                LegendDot(MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "legend") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
                verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
            ) {
                LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.calendar_legend_udhaar))
                LegendItem(MaterialTheme.colorScheme.secondary, stringResource(R.string.calendar_legend_jama))
                if (isSellerUnlocked) {
                    LegendItem(MaterialTheme.colorScheme.error, stringResource(R.string.calendar_legend_stock))
                }
            }
        }
    }

    selectedDay?.let { day ->
        val daySummary = summary.days[day] ?: DaySummary(day = day)
        val dayPurchaseBills = remember(year, month, day, purchaseBills) {
            purchaseBills.filter { it.yearInt == year && it.monthInt == month && it.dayInt == day }
        }
        DayDetailsSheet(
            year = year,
            month = month,
            day = day,
            summary = daySummary,
            purchaseBills = dayPurchaseBills,
            isSellerUnlocked = isSellerUnlocked,
            onOpenPurchaseBill = { id ->
                selectedDay = null
                onOpenPurchaseBill(id)
            },
            onDismiss = { selectedDay = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailsSheet(
    year: Int,
    month: Int,
    day: Int,
    summary: DaySummary,
    purchaseBills: List<PurchaseBillEntity>,
    isSellerUnlocked: Boolean,
    onOpenPurchaseBill: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fullDate = remember(year, month, day) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(cal.time)
    }
    val hasCustomerActivity = summary.saleCount > 0 || summary.salesPaise != 0L ||
        summary.udhaarGivenPaise != 0L || summary.udhaarCollectedPaise != 0L
    val isDayEmpty = if (isSellerUnlocked) summary.isEmpty else !hasCustomerActivity

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ShopLedgerTokens.spacingL, vertical = ShopLedgerTokens.spacingS),
            verticalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingM),
        ) {
            Text(text = fullDate, style = MaterialTheme.typography.titleLarge)

            if (isDayEmpty) {
                EmptyState(
                    title = stringResource(R.string.calendar_day_empty_title),
                    message = stringResource(R.string.calendar_day_empty_message),
                )
                if (!isSellerUnlocked) {
                    Text(
                        text = stringResource(R.string.calendar_locked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SectionCard(title = stringResource(R.string.calendar_section_sales)) {
                    Text(
                        text = pluralStringResource(R.plurals.calendar_bills_count, summary.saleCount, summary.saleCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_sale),
                        amount = IndianCurrencyFormatter.formatPaise(summary.salesPaise),
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_nagad),
                        amount = IndianCurrencyFormatter.formatPaise(summary.cashSalesPaise),
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_upi),
                        amount = IndianCurrencyFormatter.formatPaise(summary.upiSalesPaise),
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_udhaar_given),
                        amount = IndianCurrencyFormatter.formatPaise(summary.udhaarGivenPaise),
                    )
                }

                SectionCard(title = stringResource(R.string.calendar_section_collected)) {
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_nagad),
                        amount = IndianCurrencyFormatter.formatPaise(summary.udhaarCollectedCashPaise),
                    )
                    CalendarAmountRow(
                        label = stringResource(R.string.calendar_upi),
                        amount = IndianCurrencyFormatter.formatPaise(summary.udhaarCollectedUpiPaise),
                    )
                }

                if (isSellerUnlocked) {
                    SectionCard(title = stringResource(R.string.calendar_section_stock)) {
                        CalendarAmountRow(
                            label = stringResource(R.string.calendar_stock_in),
                            amount = IndianCurrencyFormatter.formatPaise(summary.stockInPaise),
                        )
                        CalendarAmountRow(
                            label = stringResource(R.string.calendar_stock_in_udhaar),
                            amount = IndianCurrencyFormatter.formatPaise(summary.stockInOnUdhaarPaise),
                        )
                        CalendarAmountRow(
                            label = stringResource(R.string.calendar_paid_distributors),
                            amount = IndianCurrencyFormatter.formatPaise(summary.paidToDistributorsPaise),
                        )
                        if (summary.paidToDistributorsPaise > 0L) {
                            Text(
                                text = stringResource(R.string.calendar_nagad) + " " +
                                    IndianCurrencyFormatter.formatPaise(summary.paidToDistributorsCashPaise) +
                                    " · " + stringResource(R.string.calendar_upi) + " " +
                                    IndianCurrencyFormatter.formatPaise(summary.paidToDistributorsUpiPaise),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (purchaseBills.isNotEmpty()) {
                            HorizontalDivider()
                            purchaseBills.forEach { bill ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = ShopLedgerTokens.minTouch)
                                        .clickable { onOpenPurchaseBill(bill.id) }
                                        .padding(vertical = ShopLedgerTokens.spacingXs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = ShopLedgerTokens.spacingS)) {
                                        Text(
                                            text = bill.supplierNameSnapshot.ifBlank { stringResource(R.string.distributor_unknown) },
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (bill.billNumber.isNotBlank()) {
                                            Text(
                                                text = bill.billNumber,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Text(
                                        text = IndianCurrencyFormatter.formatPaise(bill.totalPaise),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.calendar_locked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SecondaryActionButton(
                text = stringResource(R.string.review_cd_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CalendarAmountRow(label: String, amount: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = ShopLedgerTokens.spacingS),
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShopLedgerTokens.spacingXs),
    ) {
        LegendDot(color)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
