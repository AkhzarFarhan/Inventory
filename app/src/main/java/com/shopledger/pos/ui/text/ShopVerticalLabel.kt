package com.shopledger.pos.ui.text

import androidx.annotation.StringRes
import com.shopledger.pos.R
import com.shopledger.pos.core.nlp.ShopVertical

/** Translated shop-type name for [ShopVertical]; the enum's own displayName stays English. */
@StringRes
internal fun ShopVertical.labelRes(): Int = when (this) {
    ShopVertical.HARDWARE -> R.string.vertical_hardware
    ShopVertical.KIRANA -> R.string.vertical_kirana
    ShopVertical.PHARMA -> R.string.vertical_pharma
    ShopVertical.ELECTRONICS -> R.string.vertical_electronics
    ShopVertical.GENERAL -> R.string.vertical_general
}
