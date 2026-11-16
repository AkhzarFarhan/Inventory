package com.shopledger.pos.core.nlp

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configurable retail & wholesale shop verticals supported by ShopLedger POS.
 * Drives dynamic variant attribute field labels, default units, category presets,
 * and bilingual search synonym packs without mixing unrelated trade jargon.
 */
@Immutable
enum class ShopVertical(
    val displayName: String,
    val attr1Label: String,
    val attr1Placeholder: String,
    val attr2Label: String,
    val attr2Placeholder: String,
    val attr3Label: String,
    val attr3Placeholder: String,
    val defaultUnit: String,
    val searchHint: String
) {
    HARDWARE(
        displayName = "Hardware & Sanitary",
        attr1Label = "Size / Dimension",
        attr1Placeholder = "e.g. 4 inch, 100mm",
        attr2Label = "Material / Grade",
        attr2Placeholder = "e.g. SS 304, Brass",
        attr3Label = "Color / Finish",
        attr3Placeholder = "e.g. Matt Black, Chrome",
        defaultUnit = "pcs",
        searchHint = "Search items, sizes, Hinglish (e.g. 4\" kabza)..."
    ),
    KIRANA(
        displayName = "Kirana & Grocery",
        attr1Label = "Weight / Pack",
        attr1Placeholder = "e.g. 500 g, 1 kg, 1 ltr",
        attr2Label = "Brand / Quality",
        attr2Placeholder = "e.g. Tata, Fortune, Loose",
        attr3Label = "Variant / Grade",
        attr3Placeholder = "e.g. Classic, Premium",
        defaultUnit = "pkt",
        searchHint = "Search grocery, Hindi/weight (e.g. 1kg cheeni, atta)..."
    ),
    PHARMA(
        displayName = "Medical & Pharma",
        attr1Label = "Strength / Pack",
        attr1Placeholder = "e.g. 500mg, Strip of 10",
        attr2Label = "Company / Salt",
        attr2Placeholder = "e.g. Cipla, Paracetamol",
        attr3Label = "Batch / Expiry",
        attr3Placeholder = "e.g. B2409 • Exp 12/27",
        defaultUnit = "strip",
        searchHint = "Search medicines, salts, pack (e.g. para 500 tab)..."
    ),
    ELECTRONICS(
        displayName = "Mobile & Electronics",
        attr1Label = "Storage / Spec",
        attr1Placeholder = "e.g. 128 GB, 65W PD",
        attr2Label = "Brand / Model",
        attr2Placeholder = "e.g. Samsung, Type-C",
        attr3Label = "Color / Warranty",
        attr3Placeholder = "e.g. Black • 1 Yr Warranty",
        defaultUnit = "pcs",
        searchHint = "Search gadgets, cables, specs (e.g. 65w type c)..."
    ),
    GENERAL(
        displayName = "General Retail / Wholesale",
        attr1Label = "Variant / Size",
        attr1Placeholder = "e.g. Large, Pack of 6",
        attr2Label = "Brand / Type",
        attr2Placeholder = "e.g. Standard, Brand X",
        attr3Label = "Notes / Color",
        attr3Placeholder = "e.g. Assorted, Red",
        defaultUnit = "pcs",
        searchHint = "Search products, variants, or scan barcode..."
    );

    companion object {
        fun fromNameOrDefault(raw: String?): ShopVertical {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: HARDWARE
        }
    }
}

interface TradeVocabularyPack {
    val vertical: ShopVertical
    val phraseSynonyms: Map<String, String>
    val wordSynonyms: Map<String, String>
    val materialAliases: Map<String, String>
    val colorFinishAliases: Map<String, String>
    val nominalSizeEquivalents: Map<String, String>
    val conceptDisplayNames: Map<String, String>
    val defaultCategories: List<String>
    val defaultUnits: List<String>
}

object TradeTaxonomyRegistry {

    private val _activeVerticalFlow = MutableStateFlow(ShopVertical.HARDWARE)
    val activeVerticalFlow: StateFlow<ShopVertical> = _activeVerticalFlow.asStateFlow()

    fun setActiveVertical(vertical: ShopVertical) {
        _activeVerticalFlow.value = vertical
    }

    val HardwarePack: TradeVocabularyPack = object : TradeVocabularyPack {
        override val vertical = ShopVertical.HARDWARE

        override val phraseSynonyms: Map<String, String> = mapOf(
            "butt hinge" to "CONCEPT_HINGE",
            "door hinge" to "CONCEPT_HINGE",
            "parliament hinge" to "CONCEPT_PARLIAMENT_HINGE",
            "auto hinge" to "CONCEPT_AUTO_HINGE",
            "hydraulic hinge" to "CONCEPT_AUTO_HINGE",
            "concealed hinge" to "CONCEPT_AUTO_HINGE",
            "tower bolt" to "CONCEPT_TOWER_BOLT",
            "towerbolt" to "CONCEPT_TOWER_BOLT",
            "sliding bolt" to "CONCEPT_ALDROP",
            "door aldrop" to "CONCEPT_ALDROP",
            "mortise lock" to "CONCEPT_MORTISE_LOCK",
            "handle lock" to "CONCEPT_MORTISE_LOCK",
            "wood screw" to "CONCEPT_WOOD_SCREW",
            "drywall screw" to "CONCEPT_DRYWALL_SCREW",
            "gypsum screw" to "CONCEPT_DRYWALL_SCREW",
            "black screw" to "CONCEPT_DRYWALL_SCREW",
            "self tapping" to "CONCEPT_SELF_TAPPING_SCREW",
            "star screw" to "CONCEPT_SCREW",
            "wire nail" to "CONCEPT_NAIL",
            "wall plug" to "CONCEPT_WALL_PLUG",
            "rawlgut" to "CONCEPT_WALL_PLUG",
            "rawl plug" to "CONCEPT_WALL_PLUG",
            "bib cock" to "CONCEPT_BIB_COCK",
            "bibcock" to "CONCEPT_BIB_COCK",
            "pillar cock" to "CONCEPT_PILLAR_COCK",
            "angle valve" to "CONCEPT_ANGLE_VALVE",
            "angle cock" to "CONCEPT_ANGLE_VALVE",
            "ball valve" to "CONCEPT_BALL_VALVE",
            "gate valve" to "CONCEPT_GATE_VALVE",
            "teflon tape" to "CONCEPT_TEFLON_TAPE",
            "thread tape" to "CONCEPT_TEFLON_TAPE",
            "ptfe tape" to "CONCEPT_TEFLON_TAPE",
            "emery paper" to "CONCEPT_SANDPAPER",
            "sand paper" to "CONCEPT_SANDPAPER",
            "water paper" to "CONCEPT_SANDPAPER",
            "cutting wheel" to "CONCEPT_CUTTING_WHEEL",
            "cutter blade" to "CONCEPT_CUTTING_WHEEL",
            "grinding wheel" to "CONCEPT_GRINDING_WHEEL",
            "drill bit" to "CONCEPT_DRILL_BIT",
            "telescopic channel" to "CONCEPT_DRAWER_CHANNEL",
            "drawer channel" to "CONCEPT_DRAWER_CHANNEL",
            "drawer slide" to "CONCEPT_DRAWER_CHANNEL",
            "caster wheel" to "CONCEPT_CASTER_WHEEL",
            "castor wheel" to "CONCEPT_CASTER_WHEEL",
            "tmt bar" to "CONCEPT_TMT_BAR",
            "iron rod" to "CONCEPT_TMT_BAR",
            "combination plier" to "CONCEPT_PLIER",
            "nose plier" to "CONCEPT_NOSE_PLIER",
            "measuring tape" to "CONCEPT_MEASURING_TAPE",
            "inch tape" to "CONCEPT_MEASURING_TAPE",
            "inchtape" to "CONCEPT_MEASURING_TAPE"
        )

        override val wordSynonyms: Map<String, String> = mapOf(
            "kabza" to "CONCEPT_HINGE",
            "kabja" to "CONCEPT_HINGE",
            "kbza" to "CONCEPT_HINGE",
            "hinge" to "CONCEPT_HINGE",
            "hinges" to "CONCEPT_HINGE",
            "chatkani" to "CONCEPT_TOWER_BOLT",
            "chitkani" to "CONCEPT_TOWER_BOLT",
            "kundi" to "CONCEPT_TOWER_BOLT",
            "kundee" to "CONCEPT_TOWER_BOLT",
            "latch" to "CONCEPT_TOWER_BOLT",
            "aldrop" to "CONCEPT_ALDROP",
            "aldraf" to "CONCEPT_ALDROP",
            "aldrap" to "CONCEPT_ALDROP",
            "tala" to "CONCEPT_PADLOCK",
            "taala" to "CONCEPT_PADLOCK",
            "padlock" to "CONCEPT_PADLOCK",
            "lock" to "CONCEPT_LOCK",
            "locks" to "CONCEPT_LOCK",
            "handle" to "CONCEPT_HANDLE",
            "handles" to "CONCEPT_HANDLE",
            "hatha" to "CONCEPT_HANDLE",
            "muthiya" to "CONCEPT_HANDLE",
            "knob" to "CONCEPT_KNOB",
            "knobs" to "CONCEPT_KNOB",
            "stopper" to "CONCEPT_DOOR_STOPPER",
            "closer" to "CONCEPT_DOOR_CLOSER",
            "pech" to "CONCEPT_SCREW",
            "pach" to "CONCEPT_SCREW",
            "screw" to "CONCEPT_SCREW",
            "screws" to "CONCEPT_SCREW",
            "scrw" to "CONCEPT_SCREW",
            "kila" to "CONCEPT_NAIL",
            "keel" to "CONCEPT_NAIL",
            "kil" to "CONCEPT_NAIL",
            "nail" to "CONCEPT_NAIL",
            "nails" to "CONCEPT_NAIL",
            "nut" to "CONCEPT_NUT",
            "nuts" to "CONCEPT_NUT",
            "dibiri" to "CONCEPT_NUT",
            "dibri" to "CONCEPT_NUT",
            "bolt" to "CONCEPT_BOLT",
            "bolts" to "CONCEPT_BOLT",
            "washer" to "CONCEPT_WASHER",
            "washers" to "CONCEPT_WASHER",
            "vashar" to "CONCEPT_WASHER",
            "warshal" to "CONCEPT_WASHER",
            "gitti" to "CONCEPT_WALL_PLUG",
            "gittee" to "CONCEPT_WALL_PLUG",
            "fastener" to "CONCEPT_ANCHOR_FASTENER",
            "anchor" to "CONCEPT_ANCHOR_FASTENER",
            "toti" to "CONCEPT_BIB_COCK",
            "tonti" to "CONCEPT_BIB_COCK",
            "nal" to "CONCEPT_BIB_COCK",
            "tap" to "CONCEPT_BIB_COCK",
            "elbow" to "CONCEPT_ELBOW",
            "elbo" to "CONCEPT_ELBOW",
            "bend" to "CONCEPT_ELBOW",
            "tee" to "CONCEPT_TEE",
            "socket" to "CONCEPT_SOCKET",
            "coupler" to "CONCEPT_SOCKET",
            "coupling" to "CONCEPT_SOCKET",
            "reducer" to "CONCEPT_REDUCER",
            "union" to "CONCEPT_UNION",
            "nipple" to "CONCEPT_NIPPLE",
            "pipe" to "CONCEPT_PIPE",
            "pipes" to "CONCEPT_PIPE",
            "solvent" to "CONCEPT_SOLVENT_CEMENT",
            "teflon" to "CONCEPT_TEFLON_TAPE",
            "pana" to "CONCEPT_SPANNER",
            "paana" to "CONCEPT_SPANNER",
            "spanner" to "CONCEPT_SPANNER",
            "wrench" to "CONCEPT_SPANNER",
            "plas" to "CONCEPT_PLIER",
            "plaas" to "CONCEPT_PLIER",
            "plier" to "CONCEPT_PLIER",
            "pliers" to "CONCEPT_PLIER",
            "pechkas" to "CONCEPT_SCREWDRIVER",
            "screwdriver" to "CONCEPT_SCREWDRIVER",
            "tester" to "CONCEPT_TESTER",
            "hathodi" to "CONCEPT_HAMMER",
            "hathoda" to "CONCEPT_HAMMER",
            "hammer" to "CONCEPT_HAMMER",
            "regmal" to "CONCEPT_SANDPAPER",
            "regmar" to "CONCEPT_SANDPAPER",
            "sandpaper" to "CONCEPT_SANDPAPER",
            "fevicol" to "CONCEPT_ADHESIVE",
            "fevikol" to "CONCEPT_ADHESIVE",
            "adhesive" to "CONCEPT_ADHESIVE",
            "glue" to "CONCEPT_ADHESIVE",
            "araldite" to "CONCEPT_EPOXY",
            "mseal" to "CONCEPT_SEALANT",
            "silicon" to "CONCEPT_SILICONE",
            "silicone" to "CONCEPT_SILICONE",
            "sariya" to "CONCEPT_TMT_BAR",
            "tmt" to "CONCEPT_TMT_BAR",
            "tar" to "CONCEPT_WIRE",
            "taar" to "CONCEPT_WIRE",
            "wire" to "CONCEPT_WIRE",
            "jali" to "CONCEPT_MESH",
            "jaali" to "CONCEPT_MESH",
            "mesh" to "CONCEPT_MESH",
            "net" to "CONCEPT_MESH",
            "rassi" to "CONCEPT_ROPE",
            "rope" to "CONCEPT_ROPE",
            "brush" to "CONCEPT_BRUSH",
            "roller" to "CONCEPT_ROLLER",
            "putty" to "CONCEPT_PUTTY",
            "primer" to "CONCEPT_PRIMER",
            "paint" to "CONCEPT_PAINT",
            "thinner" to "CONCEPT_THINNER",
            "tarpin" to "CONCEPT_THINNER",
            "turpentine" to "CONCEPT_THINNER"
        )

        override val materialAliases: Map<String, String> = mapOf(
            "ss" to "SS",
            "s.s" to "SS",
            "s.s." to "SS",
            "stainless" to "SS",
            "ss304" to "SS 304",
            "304" to "SS 304",
            "ss202" to "SS 202",
            "202" to "SS 202",
            "ms" to "MS (Iron)",
            "m.s" to "MS (Iron)",
            "m.s." to "MS (Iron)",
            "iron" to "MS (Iron)",
            "loha" to "MS (Iron)",
            "lohe" to "MS (Iron)",
            "mild" to "MS (Iron)",
            "brass" to "Brass",
            "peetal" to "Brass",
            "pital" to "Brass",
            "gi" to "GI",
            "g.i" to "GI",
            "g.i." to "GI",
            "galvanized" to "GI",
            "aluminium" to "Aluminium",
            "aluminum" to "Aluminium",
            "almunium" to "Aluminium",
            "alu" to "Aluminium",
            "copper" to "Copper",
            "tamba" to "Copper",
            "pvc" to "PVC",
            "upvc" to "uPVC",
            "cpvc" to "CPVC",
            "hdpe" to "HDPE",
            "nylon" to "Nylon",
            "plastic" to "Plastic",
            "zinc" to "Zinc",
            "zamak" to "Zinc",
            "wood" to "Wooden",
            "wooden" to "Wooden",
            "lakdi" to "Wooden"
        )

        override val colorFinishAliases: Map<String, String> = mapOf(
            "black" to "Black",
            "kala" to "Black",
            "matte" to "Matt",
            "matt" to "Matt",
            "white" to "White",
            "safed" to "White",
            "ivory" to "Ivory",
            "cream" to "Ivory",
            "silver" to "Silver / Chrome",
            "chrome" to "Silver / Chrome",
            "cp" to "Silver / Chrome",
            "satin" to "Satin Nickel",
            "nickel" to "Satin Nickel",
            "antique" to "Antique Brass",
            "ab" to "Antique Brass",
            "gold" to "Gold",
            "golden" to "Gold",
            "rose" to "Rose Gold",
            "rosegold" to "Rose Gold",
            "rg" to "Rose Gold",
            "brown" to "Brown",
            "bhura" to "Brown",
            "grey" to "Grey",
            "gray" to "Grey",
            "red" to "Red",
            "lal" to "Red",
            "blue" to "Blue",
            "neela" to "Blue",
            "green" to "Green",
            "hara" to "Green",
            "yellow" to "Yellow",
            "peela" to "Yellow",
            "wenge" to "Wenge",
            "teak" to "Teak"
        )

        override val nominalSizeEquivalents: Map<String, String> = mapOf(
            "0.5in" to "1/2 inch (15mm)",
            "1/2in" to "1/2 inch (15mm)",
            "half_inch" to "1/2 inch (15mm)",
            "aadha_inch" to "1/2 inch (15mm)",
            "12mm" to "1/2 inch (12mm)",
            "15mm" to "1/2 inch (15mm)",
            "0.75in" to "3/4 inch (20mm)",
            "3/4in" to "3/4 inch (20mm)",
            "pauna_inch" to "3/4 inch (20mm)",
            "19mm" to "3/4 inch (20mm)",
            "20mm" to "3/4 inch (20mm)",
            "1in" to "1 inch (25mm)",
            "25mm" to "1 inch (25mm)",
            "1.25in" to "1.25 inch (32mm)",
            "1_1/4in" to "1.25 inch (32mm)",
            "sawa_inch" to "1.25 inch (32mm)",
            "32mm" to "1.25 inch (32mm)",
            "1.5in" to "1.5 inch (38mm)",
            "1_1/2in" to "1.5 inch (38mm)",
            "dedh_inch" to "1.5 inch (38mm)",
            "38mm" to "1.5 inch (38mm)",
            "40mm" to "1.5 inch (40mm)",
            "2in" to "2 inch (50mm)",
            "50mm" to "2 inch (50mm)",
            "2.5in" to "2.5 inch (65mm)",
            "2_1/2in" to "2.5 inch (65mm)",
            "dhai_inch" to "2.5 inch (65mm)",
            "65mm" to "2.5 inch (65mm)",
            "3in" to "3 inch (75mm)",
            "75mm" to "3 inch (75mm)",
            "4in" to "4 inch (100mm)",
            "100mm" to "4 inch (100mm)",
            "10cm" to "4 inch (100mm)",
            "5in" to "5 inch (125mm)",
            "125mm" to "5 inch (125mm)",
            "6in" to "6 inch (150mm)",
            "150mm" to "6 inch (150mm)",
            "8in" to "8 inch (200mm)",
            "200mm" to "8 inch (200mm)",
            "10in" to "10 inch (250mm)",
            "250mm" to "10 inch (250mm)",
            "12in" to "12 inch (300mm)",
            "300mm" to "12 inch (300mm)"
        )

        override val conceptDisplayNames: Map<String, String> = mapOf(
            "CONCEPT_HINGE" to "Butt Hinge (Kabza)",
            "CONCEPT_PARLIAMENT_HINGE" to "Parliament Hinge",
            "CONCEPT_AUTO_HINGE" to "Hydraulic Auto Hinge",
            "CONCEPT_TOWER_BOLT" to "Tower Bolt (Chatkani)",
            "CONCEPT_ALDROP" to "Door Aldrop",
            "CONCEPT_MORTISE_LOCK" to "Mortise Handle Lock",
            "CONCEPT_PADLOCK" to "Padlock (Tala)",
            "CONCEPT_WOOD_SCREW" to "Wood Screw",
            "CONCEPT_DRYWALL_SCREW" to "Drywall Screw (Black)",
            "CONCEPT_SELF_TAPPING_SCREW" to "Self Tapping Screw",
            "CONCEPT_SCREW" to "Screw (Pech)",
            "CONCEPT_NAIL" to "Wire Nail (Kila)",
            "CONCEPT_WALL_PLUG" to "Wall Plug (Gitti)",
            "CONCEPT_BIB_COCK" to "Bib Cock (Tap)",
            "CONCEPT_ANGLE_VALVE" to "Angle Valve",
            "CONCEPT_BALL_VALVE" to "Ball Valve",
            "CONCEPT_ELBOW" to "Pipe Elbow",
            "CONCEPT_TEE" to "Pipe Tee",
            "CONCEPT_SOCKET" to "Pipe Socket",
            "CONCEPT_TEFLON_TAPE" to "Teflon Tape",
            "CONCEPT_SANDPAPER" to "Emery Sandpaper (Regmal)",
            "CONCEPT_ADHESIVE" to "Wood Adhesive (Fevicol)",
            "CONCEPT_DRAWER_CHANNEL" to "Telescopic Drawer Channel"
        )

        override val defaultCategories: List<String> = listOf(
            "Door & Window Fittings",
            "Fasteners & Screws",
            "Plumbing & Sanitary",
            "Tools & Blades",
            "Paints & Adhesives",
            "Electrical & General"
        )

        override val defaultUnits: List<String> = listOf("pcs", "box", "kg", "mtr", "pkt", "set", "pair", "dzn")
    }

    val KiranaGroceryPack: TradeVocabularyPack = object : TradeVocabularyPack {
        override val vertical = ShopVertical.KIRANA

        override val phraseSynonyms: Map<String, String> = mapOf(
            "mustard oil" to "CONCEPT_COOKING_OIL",
            "sarso tel" to "CONCEPT_COOKING_OIL",
            "refined oil" to "CONCEPT_COOKING_OIL",
            "cooking oil" to "CONCEPT_COOKING_OIL",
            "wheat flour" to "CONCEPT_ATTA",
            "toor dal" to "CONCEPT_DAL",
            "arhar dal" to "CONCEPT_DAL",
            "moong dal" to "CONCEPT_DAL",
            "chana dal" to "CONCEPT_DAL",
            "washing powder" to "CONCEPT_DETERGENT",
            "tea powder" to "CONCEPT_TEA",
            "chai patti" to "CONCEPT_TEA"
        )

        override val wordSynonyms: Map<String, String> = mapOf(
            "cheeni" to "CONCEPT_SUGAR",
            "chini" to "CONCEPT_SUGAR",
            "shakkar" to "CONCEPT_SUGAR",
            "sugar" to "CONCEPT_SUGAR",
            "atta" to "CONCEPT_ATTA",
            "aata" to "CONCEPT_ATTA",
            "flour" to "CONCEPT_ATTA",
            "maida" to "CONCEPT_MAIDA",
            "besan" to "CONCEPT_BESAN",
            "suji" to "CONCEPT_SUJI",
            "rava" to "CONCEPT_SUJI",
            "chawal" to "CONCEPT_RICE",
            "rice" to "CONCEPT_RICE",
            "basmati" to "CONCEPT_RICE",
            "dal" to "CONCEPT_DAL",
            "daal" to "CONCEPT_DAL",
            "tel" to "CONCEPT_COOKING_OIL",
            "oil" to "CONCEPT_COOKING_OIL",
            "ghee" to "CONCEPT_GHEE",
            "namak" to "CONCEPT_SALT",
            "salt" to "CONCEPT_SALT",
            "sabun" to "CONCEPT_SOAP",
            "soap" to "CONCEPT_SOAP",
            "surf" to "CONCEPT_DETERGENT",
            "detergent" to "CONCEPT_DETERGENT",
            "chai" to "CONCEPT_TEA",
            "tea" to "CONCEPT_TEA",
            "masala" to "CONCEPT_SPICE",
            "haldi" to "CONCEPT_TURMERIC",
            "mirch" to "CONCEPT_CHILLI",
            "jeera" to "CONCEPT_CUMIN",
            "doodh" to "CONCEPT_MILK",
            "milk" to "CONCEPT_MILK",
            "biscuit" to "CONCEPT_BISCUIT"
        )

        override val materialAliases: Map<String, String> = emptyMap()
        override val colorFinishAliases: Map<String, String> = emptyMap()
        override val nominalSizeEquivalents: Map<String, String> = mapOf(
            "250g" to "250 g",
            "500g" to "500 g",
            "1kg" to "1 kg",
            "5kg" to "5 kg",
            "10kg" to "10 kg",
            "500ml" to "500 ml",
            "1ltr" to "1 ltr"
        )
        override val conceptDisplayNames: Map<String, String> = mapOf(
            "CONCEPT_SUGAR" to "Sugar (Cheeni)",
            "CONCEPT_ATTA" to "Wheat Flour (Atta)",
            "CONCEPT_RICE" to "Rice (Chawal)",
            "CONCEPT_DAL" to "Pulses (Dal)",
            "CONCEPT_COOKING_OIL" to "Cooking Oil (Tel)",
            "CONCEPT_SALT" to "Salt (Namak)",
            "CONCEPT_SOAP" to "Bath / Washing Soap",
            "CONCEPT_DETERGENT" to "Detergent Powder",
            "CONCEPT_TEA" to "Tea Leaves (Chai Patti)"
        )
        override val defaultCategories: List<String> = listOf(
            "Staples & Grains",
            "Oils & Ghee",
            "Spices & Masala",
            "Snacks & Biscuits",
            "Beverages & Tea",
            "Soaps & Household"
        )
        override val defaultUnits: List<String> = listOf("pkt", "kg", "g", "ltr", "ml", "pcs", "box", "dzn")
    }

    val PharmaMedicalPack: TradeVocabularyPack = object : TradeVocabularyPack {
        override val vertical = ShopVertical.PHARMA

        override val phraseSynonyms: Map<String, String> = mapOf(
            "cough syrup" to "CONCEPT_SYRUP",
            "eye drops" to "CONCEPT_DROPS",
            "ear drops" to "CONCEPT_DROPS",
            "pain killer" to "CONCEPT_ANALGESIC",
            "antacid gel" to "CONCEPT_ANTACID"
        )

        override val wordSynonyms: Map<String, String> = mapOf(
            "tab" to "CONCEPT_TABLET",
            "tablet" to "CONCEPT_TABLET",
            "tablets" to "CONCEPT_TABLET",
            "goli" to "CONCEPT_TABLET",
            "cap" to "CONCEPT_CAPSULE",
            "capsule" to "CONCEPT_CAPSULE",
            "capsules" to "CONCEPT_CAPSULE",
            "syr" to "CONCEPT_SYRUP",
            "syrup" to "CONCEPT_SYRUP",
            "inj" to "CONCEPT_INJECTION",
            "injection" to "CONCEPT_INJECTION",
            "cream" to "CONCEPT_OINTMENT",
            "ointment" to "CONCEPT_OINTMENT",
            "gel" to "CONCEPT_OINTMENT",
            "drops" to "CONCEPT_DROPS",
            "paracetamol" to "CONCEPT_PARACETAMOL",
            "pcm" to "CONCEPT_PARACETAMOL",
            "dolo" to "CONCEPT_PARACETAMOL",
            "ors" to "CONCEPT_ORS",
            "bandage" to "CONCEPT_DRESSING",
            "cotton" to "CONCEPT_DRESSING"
        )

        override val materialAliases: Map<String, String> = emptyMap()
        override val colorFinishAliases: Map<String, String> = emptyMap()
        override val nominalSizeEquivalents: Map<String, String> = mapOf(
            "100ml" to "100 ml",
            "200ml" to "200 ml"
        )
        override val conceptDisplayNames: Map<String, String> = mapOf(
            "CONCEPT_PARACETAMOL" to "Paracetamol Tablet",
            "CONCEPT_SYRUP" to "Oral Syrup",
            "CONCEPT_ORS" to "ORS Hydration Powder"
        )
        override val defaultCategories: List<String> = listOf(
            "Tablets & Capsules",
            "Syrups & Suspensions",
            "Ointments & Drops",
            "Injections & IV",
            "Surgical & Dressing",
            "OTC & Wellness"
        )
        override val defaultUnits: List<String> = listOf("strip", "bottle", "tube", "vial", "pcs", "box", "pkt")
    }

    val ElectronicsMobilePack: TradeVocabularyPack = object : TradeVocabularyPack {
        override val vertical = ShopVertical.ELECTRONICS

        override val phraseSynonyms: Map<String, String> = mapOf(
            "type c" to "CONCEPT_USB_C_CABLE",
            "type-c" to "CONCEPT_USB_C_CABLE",
            "data cable" to "CONCEPT_CABLE",
            "tempered glass" to "CONCEPT_SCREEN_GUARD",
            "screen guard" to "CONCEPT_SCREEN_GUARD",
            "power bank" to "CONCEPT_POWERBANK",
            "neck band" to "CONCEPT_EARPHONE"
        )

        override val wordSynonyms: Map<String, String> = mapOf(
            "charger" to "CONCEPT_CHARGER",
            "adapter" to "CONCEPT_CHARGER",
            "cable" to "CONCEPT_CABLE",
            "lead" to "CONCEPT_CABLE",
            "earphone" to "CONCEPT_EARPHONE",
            "earphones" to "CONCEPT_EARPHONE",
            "tws" to "CONCEPT_EARPHONE",
            "airpods" to "CONCEPT_EARPHONE",
            "buds" to "CONCEPT_EARPHONE",
            "glass" to "CONCEPT_SCREEN_GUARD",
            "tempered" to "CONCEPT_SCREEN_GUARD",
            "cover" to "CONCEPT_PHONE_CASE",
            "case" to "CONCEPT_PHONE_CASE",
            "backcover" to "CONCEPT_PHONE_CASE",
            "battery" to "CONCEPT_BATTERY",
            "powerbank" to "CONCEPT_POWERBANK",
            "pendrive" to "CONCEPT_MEMORY",
            "sdcard" to "CONCEPT_MEMORY"
        )

        override val materialAliases: Map<String, String> = emptyMap()
        override val colorFinishAliases: Map<String, String> = HardwarePack.colorFinishAliases
        override val nominalSizeEquivalents: Map<String, String> = emptyMap()
        override val conceptDisplayNames: Map<String, String> = mapOf(
            "CONCEPT_CHARGER" to "Fast Charger Adapter",
            "CONCEPT_USB_C_CABLE" to "USB Type-C Data Cable",
            "CONCEPT_SCREEN_GUARD" to "Tempered Screen Guard",
            "CONCEPT_EARPHONE" to "Wireless / Wired Earphones"
        )
        override val defaultCategories: List<String> = listOf(
            "Chargers & Adapters",
            "Data Cables",
            "Audio & Earphones",
            "Covers & Tempered Glass",
            "Storage & Memory",
            "Appliances & Electricals"
        )
        override val defaultUnits: List<String> = listOf("pcs", "box", "set", "pair", "pkt")
    }

    val GenericCleanPack: TradeVocabularyPack = object : TradeVocabularyPack {
        override val vertical = ShopVertical.GENERAL
        override val phraseSynonyms: Map<String, String> = emptyMap()
        override val wordSynonyms: Map<String, String> = emptyMap()
        override val materialAliases: Map<String, String> = emptyMap()
        override val colorFinishAliases: Map<String, String> = emptyMap()
        override val nominalSizeEquivalents: Map<String, String> = emptyMap()
        override val conceptDisplayNames: Map<String, String> = emptyMap()
        override val defaultCategories: List<String> = listOf(
            "General Merchandise",
            "Fast Moving Items",
            "Bulk & Wholesale",
            "Accessories",
            "Services & Miscellaneous"
        )
        override val defaultUnits: List<String> = listOf("pcs", "box", "pkt", "kg", "ltr", "set", "dzn")
    }

    fun getPack(vertical: ShopVertical = _activeVerticalFlow.value): TradeVocabularyPack {
        return when (vertical) {
            ShopVertical.HARDWARE -> HardwarePack
            ShopVertical.KIRANA -> KiranaGroceryPack
            ShopVertical.PHARMA -> PharmaMedicalPack
            ShopVertical.ELECTRONICS -> ElectronicsMobilePack
            ShopVertical.GENERAL -> GenericCleanPack
        }
    }
}

/**
 * Backward-compatible facade for Hardware trade vocabulary, delegating directly to [TradeTaxonomyRegistry.HardwarePack].
 */
object HardwareOntology {
    val phraseSynonyms: Map<String, String> get() = TradeTaxonomyRegistry.HardwarePack.phraseSynonyms
    val wordSynonyms: Map<String, String> get() = TradeTaxonomyRegistry.HardwarePack.wordSynonyms
    val materialAliases: Map<String, String> get() = TradeTaxonomyRegistry.HardwarePack.materialAliases
    val colorFinishAliases: Map<String, String> get() = TradeTaxonomyRegistry.HardwarePack.colorFinishAliases
    val nominalSizeEquivalents: Map<String, String> get() = TradeTaxonomyRegistry.HardwarePack.nominalSizeEquivalents

    val stopWords: Set<String> = setOf(
        "for", "with", "and", "of", "the", "in", "on", "to", "a", "an",
        "ka", "ki", "ke", "wala", "wali", "wale", "type", "heavy", "medium", "light", "size", "pcs", "piece"
    )
}
