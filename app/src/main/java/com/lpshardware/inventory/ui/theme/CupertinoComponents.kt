package com.lpshardware.inventory.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Crisp 1.dp bordered iOS Light card with zero GPU shadow blur overhead for smooth low-RAM scrolling.
 */
@Composable
fun CupertinoCard(
    modifier: Modifier = Modifier,
    borderColor: Color = CupertinoColors.LightBorder,
    containerColor: Color = CupertinoColors.CardSurface,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Modern iOS Segmented Control pill bar.
 */
@Composable
fun CupertinoSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CupertinoColors.SegmentedTrackFill)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, title ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) CupertinoColors.CardSurface else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                0.5.dp,
                                CupertinoColors.Separator,
                                RoundedCornerShape(8.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) CupertinoColors.LabelPrimary else CupertinoColors.LabelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Semantic tinted badge pill (`+24% gain`, `Low Stock`, `Synced`, etc.).
 */
@Composable
fun SemanticPillBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Clean iOS-styled OutlinedTextField with automatic Sentence Capitalization by default.
 */
@Composable
fun CupertinoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, fontSize = 13.sp, color = CupertinoColors.LabelMuted) }
        } else null,
        singleLine = singleLine,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onNext = { onImeAction() },
            onSearch = { onImeAction() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CupertinoColors.CardSurface,
            unfocusedContainerColor = CupertinoColors.SecondarySurface,
            focusedBorderColor = CupertinoColors.SystemBlue,
            unfocusedBorderColor = CupertinoColors.Separator,
            focusedTextColor = CupertinoColors.LabelPrimary,
            unfocusedTextColor = CupertinoColors.LabelPrimary
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Right-edge iOS Contacts style A–Z Alphabet Scrubber bar for fast alphabetical jumping.
 */
@Composable
fun AlphabetScrubberBar(
    availableLetters: List<Char>,
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    val letters = remember { ('A'..'Z').toList() + '#' }
    var barHeightPx by remember { mutableIntStateOf(1) }

    Column(
        modifier = modifier
            .width(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(CupertinoColors.CardSurface.copy(alpha = 0.88f))
            .border(0.5.dp, CupertinoColors.Separator, RoundedCornerShape(11.dp))
            .padding(vertical = 4.dp)
            .onSizeChanged { barHeightPx = it.height.coerceAtLeast(1) }
            .pointerInput(availableLetters) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val idx = ((offset.y / barHeightPx) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.lastIndex)
                        val ch = letters[idx]
                        if (ch in availableLetters) {
                            onLetterSelected(ch)
                        }
                    },
                    onVerticalDrag = { change, _ ->
                        val idx = ((change.position.y / barHeightPx) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.lastIndex)
                        val ch = letters[idx]
                        if (ch in availableLetters) {
                            onLetterSelected(ch)
                        }
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        letters.forEach { letter ->
            val isActive = letter in availableLetters
            Text(
                text = letter.toString(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (isActive) CupertinoColors.SystemBlue else CupertinoColors.LabelMuted.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isActive) { onLetterSelected(letter) }
            )
        }
    }
}
