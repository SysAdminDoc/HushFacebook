/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

internal const val MATERIAL_YOU = "Lapp/morphe/extension/facebook/theme/MaterialYouTheme;"

/** Route one for FDS: the colour, the token, and back comes the colour to draw. */
private const val FDS = "$MATERIAL_YOU->fds(ILjava/lang/Object;)I"

/** Route one for the Mig dark scheme, which only answers for dark mode. */
private const val MIG = "$MATERIAL_YOU->mig(ILjava/lang/Object;)I"

/**
 * A colour resource read as a drawable. Litho resolves a token's theme attribute to its resource and
 * asks for the drawable this way, so the feed's composer row draws SURFACE_BACKGROUND's #252728 from
 * the resource table (issue #37). AMOLED has no stand-in for it: route two already wrote black there.
 * ComposerRowColourFixtureTest holds the composer row's way to this call on each declared build.
 */
internal const val CONTEXT_GET_DRAWABLE = "Landroid/content/Context;->getDrawable(I)Landroid/graphics/drawable/Drawable;"

/**
 * The same read through `Resources`, which androidx's `ResourcesCompat` and a Litho resource lookup that
 * doesn't go through a `Context` use. A colour resource comes back as a ColorDrawable of its colour,
 * so these answer through the same stand-in as [CONTEXT_GET_DRAWABLE].
 */
internal const val RESOURCES_GET_DRAWABLE = "Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
internal const val RESOURCES_GET_THEMED_DRAWABLE =
    "Landroid/content/res/Resources;->getDrawable(ILandroid/content/res/Resources\$Theme;)Landroid/graphics/drawable/Drawable;"

/** A theme attribute's value read as `TypedValue.data`, which none of the colour calls above see (issue #37). */
internal const val TYPED_VALUE_DATA = "Landroid/util/TypedValue;->data:I"

/** What a data read becomes: the value, whole, so the extension can see its type. */
internal const val COLOUR_DATA = "$MATERIAL_YOU->colourData(Landroid/util/TypedValue;)I"

/**
 * Where Material You sends each framework colour call, route four's `Color.parseColor`, the reads
 * of a colour resource and a theme attribute's `TypedArray.getColor`, and each of AMOLED's stand-ins
 * for them when AMOLED went first. Litho reads a token's attribute that last way (581
 * `LX/1Mx;->A04`), and SURFACE_BACKGROUND's night item keeps #252728 since some code reads it as a
 * plain colour, so the comment list's rows drew it (issue #37).
 */
internal val YOU_COLOUR_CALLS: Map<String, String> =
    listOf(PARSE_COLOR, CONTEXT_GET_COLOR, RESOURCES_GET_COLOR, RESOURCES_GET_THEMED_COLOR, TYPED_ARRAY_GET_COLOR).flatMap { framework ->
        listOf(framework, AMOLED_COLOUR_CALLS.getValue(framework)).map { it to standIn(MATERIAL_YOU, framework) }
    }.toMap() + listOf(CONTEXT_GET_DRAWABLE, RESOURCES_GET_DRAWABLE, RESOURCES_GET_THEMED_DRAWABLE)
        .associateWith { standIn(MATERIAL_YOU, it) }

/** The status bar: the colour and FDS's dark check. Runs AMOLED's own first when AMOLED is in the build. */
internal const val STATUS_BAR_YOU = "$MATERIAL_YOU->statusBar(IZ)I"

/** The same for the navigation bar. */
internal const val NAVIGATION_BAR_YOU = "$MATERIAL_YOU->navigationBar(IZ)I"

/**
 * Sends the status bar's colour through the extension, first thing in the method that paints it,
 * with [darkCheck]'s answer for the window, like AMOLED's hook. With AMOLED in the build its call
 * is already there, and it goes to the extension's instead, which runs AMOLED's first: same
 * signature, same registers, so the `move-result` after it stays right and the bar's colour goes
 * through one hook in AMOLED-then-Material You order, as route four's parser does.
 */
internal fun BytecodePatchContext.hookMaterialYouStatusBar(darkCheck: String) {
    if (!statusBarPainter().takeOverCall(STATUS_BAR, STATUS_BAR_YOU)) hookStatusBarColour(darkCheck, STATUS_BAR_YOU)
}

/** The same for the navigation bar's painter. */
internal fun BytecodePatchContext.hookMaterialYouNavigationBar(darkCheck: String) {
    if (!navigationBarPainter().takeOverCall(NAVIGATION_BAR, NAVIGATION_BAR_YOU)) {
        hookNavigationBarColour(darkCheck, NAVIGATION_BAR_YOU)
    }
}

/** Sends this method's call to AMOLED's [amoled] to [you] instead, on the same registers. False when there's none. */
private fun MutableMethod.takeOverCall(amoled: String, you: String): Boolean {
    val index = implementation!!.instructions.indexOfFirst { it.referenceText() == amoled }
    if (index < 0) return false

    val call = getInstruction<FiveRegisterInstruction>(index)
    replaceInstruction(index, "invoke-static { v${call.registerC}, v${call.registerD} }, $you")
    return true
}

/**
 * Route three: the dark surfaces Facebook writes into its code, each read from the extension field
 * of the same name instead. None is a light-theme colour in 577 or 580. MaterialYouTheme.SURFACES
 * holds the same six, which MaterialYouParityTest checks.
 */
internal val SURFACE_FIELDS: Map<Int, String> =
    listOf(0x101011, 0x18191A, 0x1C1C1D, 0x242526, 0x252728, 0x3E4042)
        .associate { rgb -> (rgb or -0x1000000) to "DARK_%06X".format(rgb) }

/**
 * The palette Android 11 gets, darkest tone first: the same text as TonePalette.FALLBACK, which
 * MaterialYouParityTest checks. Route two writes it into the night colours as the fallback.
 */
internal const val FALLBACK_PALETTE =
    "000000 00184A 192E60 324578 4A5C92 6375AC 7C8FC8 97AAE4 B3C5FF DBE1FF EEF0FF FEFBFF FFFFFF;" +
        "000000 1A1B21 2F3036 46464C 5D5E64 76767D 909097 ABAAB1 C6C6CD E3E2E9 F1F0F7 FEFBFF FFFFFF;" +
        "000000 191B23 2E3038 45464F 5C5E67 757680 8F909A AAAAB4 C5C6D0 E2E2EC F0F0FA FEFBFF FFFFFF"

/** The tone of each step of a palette family, darkest first. */
internal val TONES = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

/** Android's resource names for those steps: system_*_1000 is tone 0, system_*_0 is tone 100. */
private val SYSTEM_STEPS = listOf(1000, 900, 800, 700, 600, 500, 400, 300, 200, 100, 50, 10, 0)

/**
 * A night colour moves to a system tone only when one is this close to its lightness, so no
 * contrast on the screens that read it moves by more than a little.
 */
private const val MAX_TONE_DISTANCE = 3.0

/** The palette family and tone a night colour becomes, and its lightness before. */
internal data class NightTone(val accent: Boolean, val tone: Int) {
    /** The framework colour for API 31 and newer. */
    val systemColor: String
        get() = "@android:color/system_" + (if (accent) "accent1_" else "neutral1_") + SYSTEM_STEPS[TONES.indexOf(tone)]

    /** The same step of the fixed palette, for Android 11. */
    val fallback: String
        get() = "#ff" + FALLBACK_PALETTE.split(";")[if (accent) 0 else 1].trim().split(" ")[TONES.indexOf(tone)].lowercase()
}

/**
 * The tone a night colour resource takes, or null to leave it. A grey takes the neutral family and
 * one of Facebook's blues the accent, at the tone nearest its lightness, and only when that tone is
 * within [MAX_TONE_DISTANCE]. Black, white, a translucent colour or any other hue stays as it is.
 */
internal fun nightTone(value: String): NightTone? {
    val color = parseOpaque(value) ?: return null
    if (color == -0x1000000 || color == -1) return null
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val accent = when {
        maxOf(r, g, b) - minOf(r, g, b) <= 10 -> false
        isFacebookBlue(r, g, b) -> true
        else -> return null
    }
    val lightness = lstar(r, g, b)
    val tone = TONES.minBy { abs(it - lightness) }
    return if (abs(tone - lightness) <= MAX_TONE_DISTANCE) NightTone(accent, tone) else null
}

/** An opaque `#rrggbb` or `#ffrrggbb`, as an int, or null for anything else. */
private fun parseOpaque(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return when (hex.length) {
        6 -> hex.toLong(16).toInt() or -0x1000000
        8 -> if (hex.take(2).lowercase() == "ff") hex.toLong(16).toInt() else null
        else -> null
    }
}

/** MaterialYouTheme.isFacebookBlue: an HSV hue from 200 to 225 degrees, clearly coloured. */
internal fun isFacebookBlue(r: Int, g: Int, b: Int): Boolean {
    val delta = b - minOf(r, g)
    if (b < r || b < g || b < 77 || delta * 4 < b) return false
    val turn = 60 * (r - g)
    return turn >= -40 * delta && turn <= -15 * delta
}

/** CIE L* of an sRGB colour. */
internal fun lstar(r: Int, g: Int, b: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val y = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    return if (y <= 216.0 / 24389.0) y * 24389.0 / 27.0 else 116.0 * cbrt(y) - 16.0
}

/**
 * Route two for the night colours: each one [nightTone] takes gets the fixed palette's colour in
 * [night], which Android 11 reads, and the wallpaper palette's in [nightV31], which Android 12 and
 * newer prefer. A colour Facebook already gives a value of its own for Android 12 and newer is left
 * alone, and the default (light) colours are never opened. Answers how many it changed.
 */
internal fun recolourNightColours(night: Document, nightV31: Document): Int {
    val resources = nightV31.documentElement
    val present = mutableSetOf<String>()
    val existing = resources.getElementsByTagName("color")
    for (index in 0 until existing.length) {
        (existing.item(index) as? Element)?.let { present += it.getAttribute("name") }
    }

    var changed = 0
    val colors = night.getElementsByTagName("color")
    for (index in 0 until colors.length) {
        val color = colors.item(index) as? Element ?: continue
        val tone = nightTone(color.textContent) ?: continue
        val name = color.getAttribute("name")
        if (name in present) continue
        color.textContent = tone.fallback
        resources.appendChild(
            nightV31.createElement("color").also {
                it.setAttribute("name", name)
                it.textContent = tone.systemColor
            },
        )
        changed++
    }
    return changed
}

private const val NIGHT_COLORS = "res/values-night/colors.xml"
private const val NIGHT_V31_COLORS = "res/values-night-v31/colors.xml"
private const val NIGHT_VALUES = "res/values-night"

/** The night style items' colour state lists for Android 12 and newer, one file per [NightShade]. */
private const val NIGHT_V31_STATE_LISTS = "res/color-night-v31"

/** Read, never written: light mode's colours and styles stay as Facebook has them. */
private const val DEFAULT_COLORS = "res/values/colors.xml"
private const val DEFAULT_VALUES = "res/values"

private const val EMPTY_RESOURCES = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n"

/** A decoded resource file parsed for reading only, so nothing writes it back. */
internal fun readOnly(file: File): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

/**
 * The colours given a night value in [res], a decoded resource folder: every values folder with a
 * night qualifier, values-night-v31 or values-land-night as well as values-night. A colour Facebook
 * gives its own night value in any of them is its choice at night, so route two leaves it alone.
 */
internal fun nightValuedColours(res: File): Set<String> =
    res.listFiles().orEmpty()
        .filter { folder -> folder.isDirectory && folder.name.split('-').let { it.first() == "values" && "night" in it } }
        .map { File(it, "colors.xml") }.filter { it.isFile }
        .flatMapTo(sortedSetOf()) { readOnly(it).colourValues().keys }

/** Each colour in a decoded colours file, by name, with its value as written. */
internal fun Document.colourValues(): Map<String, String> {
    val colors = getElementsByTagName("color")
    return (0 until colors.length).mapNotNull { colors.item(it) as? Element }
        .associate { it.getAttribute("name") to it.textContent.trim() }
}

private val materialYouResourcePatch = resourcePatch {
    dependsOn(fdsTokenAttributesPatch)

    // After every patch's own work, so AMOLED's black has gone in first and stays: black is no
    // tone this recolours.
    finalize {
        check(get(NIGHT_COLORS, false).exists()) { "Facebook has no night colours, so text would keep its grey" }
        val dynamic = get(NIGHT_V31_COLORS, false)
        if (!dynamic.exists()) {
            dynamic.parentFile.mkdirs()
            dynamic.writeText(EMPTY_RESOURCES)
        }
        val nightColourNames = nightValuedColours(get("res", false))
        val changed = document(NIGHT_COLORS).use { night ->
            document(NIGHT_V31_COLORS).use { nightV31 -> recolourNightColours(night, nightV31) }
        }
        check(changed > 0) { "No night colour is near a palette tone, so text would keep its grey" }

        // Route two for the FDS styles (MaterialYouStyles.kt): a night copy of the dark style.
        val colours = readOnly(get(DEFAULT_COLORS)).colourValues()
        val styleFiles = get(DEFAULT_VALUES).listFiles().orEmpty()
            .filter { it.name.startsWith("style") && it.name.endsWith(".xml") }.sortedBy { it.name }
        var restyled = 0
        val stateLists = sortedMapOf<String, String>()
        for (file in styleFiles) {
            val family = darkFdsStyles(readOnly(file), tokenAttributeNames)
            if (family.isEmpty()) continue
            val nightStyles = "$NIGHT_VALUES/${file.name}"
            get(nightStyles, false).let { if (!it.exists()) it.writeText(EMPTY_RESOURCES) }
            restyled += document(nightStyles).use { night ->
                document(NIGHT_COLORS).use { nightColours ->
                    document(NIGHT_V31_COLORS).use { nightV31Colours ->
                        writeNightStyles(family, colours, nightColourNames, tokenAttributeNames, night, nightColours,
                            nightV31Colours, stateLists, plainTokens)
                    }
                }
            }
        }
        check(restyled > 0) {
            "No FDS dark style item takes a palette colour, so views Facebook inflates from its layouts would keep its blue"
        }
        for ((name, stateList) in stateLists) {
            val file = get("$NIGHT_V31_STATE_LISTS/$name.xml", false)
            check(!file.exists()) { "Facebook already has a colour state list named $name" }
            file.parentFile.mkdirs()
            file.writeText(stateList)
        }
    }
}

@Suppress("unused")
val materialYouThemePatch = bytecodePatch(
    name = "Material You theme",
    description = "Gives Facebook's dark mode the colors of your wallpaper on Android 12 and newer, so it " +
        "matches your phone. Turn on dark mode in Facebook first. It isn't selected by default because undoing " +
        "it means patching again. Turn on Expert mode in Manager to pick it.",
    default = false,
) {
    category("Theme")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(materialYouResourcePatch)

    dependsOn(facebookExtensionPatch)

    execute {
        enableStatus("materialYouTheme")
    }

    // After every patch's execute, so with AMOLED in the build its hooks and rewrites are already
    // there: each hook here goes after AMOLED's and gets AMOLED's colour, and AMOLED's black is no
    // dark-theme colour this recolours.
    finalize {
        // The selected tab's reads are found first: a build without them fails before any hook
        // below goes in.
        val recolourSelectedTab = selectedTabColourHook()

        // Facebook's own answer for whether its dark mode is on, unless AMOLED has hooked it already.
        hookDarkModeAnswer()

        // Route one: the Mig dark scheme, the FDSColors resolvers and the view code's theme resolver.
        hookColourResolvers(mig = MIG, fds = FDS)

        // The system bars, which a tab can colour from a token none of route one's rules knows as
        // dark (issue #22 for AMOLED), or from a colour it writes in code for both themes.
        val darkCheck = fdsDarkCheck()
        hookMaterialYouStatusBar(darkCheck)
        hookMaterialYouNavigationBar(darkCheck)

        // The selected tab in Facebook's tab bar, its icon and the line over it, which Facebook's
        // dark theme draws near white: the palette's accent, as HushMessenger's selected tab (#65).
        recolourSelectedTab()

        // Route four, and the reads of a colour resource, where Facebook's dark palette reaches the
        // Video tab's bottom bar. AMOLED, when it went first, has sent every call to its own
        // stand-in, and the extension's stand-in calls AMOLED's when AMOLED is in the build.
        val rerouted = rerouteColourCalls(YOU_COLOUR_CALLS)
        val amoledParsers = rerouted.getValue(PARSE_COLOR_DARK)
        check(rerouted.getValue(PARSE_COLOR) + amoledParsers > 0) {
            "No call to Color.parseColor found, so server colours would stay grey"
        }
        check(rerouted.getValue(CONTEXT_GET_COLOR) + rerouted.getValue(AMOLED_COLOUR_CALLS.getValue(CONTEXT_GET_COLOR)) > 0) {
            "No call to Context.getColor found, so the Video tab's bottom bar would stay grey"
        }
        check(rerouted.getValue(CONTEXT_GET_DRAWABLE) > 0) {
            "No call to Context.getDrawable found, so the feed's composer row would stay grey"
        }
        check(rerouted.getValue(TYPED_ARRAY_GET_COLOR) + rerouted.getValue(AMOLED_COLOUR_CALLS.getValue(TYPED_ARRAY_GET_COLOR)) > 0) {
            "No call to TypedArray.getColor found, so the comment list's rows would stay grey"
        }

        // The box under a link post's picture (issue #37), whose dark-mode colour MobileConfig sends.
        check(hookLinkBoxColour() > 0) { "No parse of the link box's colour found, so link posts would keep a grey box" }

        // The comment list's rows (issue #37): a theme attribute resolved into a TypedValue and read as
        // its `data`, which SURFACE_BACKGROUND's night item (#252728) reaches without a colour call.
        check(readColourData() > 0) { "No TypedValue.data read found, so rows that read a theme colour that way would stay grey" }

        // Route three. AMOLED, when it went first, has blackened all but one of these, which is why
        // finding none is fine then.
        val read = readSurfaceLiterals()
        check(read > 0 || amoledParsers > 0) { "No dark surface written in code, so the chrome would stay grey" }

        // React Native screens such as Marketplace home. With AMOLED in the build its call put
        // the hooks in already, and ReactColours runs both themes.
        hookReactColours()
    }
}

/**
 * The MobileConfig colour the box under a link post's picture takes in Facebook's dark mode (issue
 * #37). On 582 ten methods read it, one for each kind of link attachment: the attachment footer's
 * (arm64 `LX/4sz;->A0E` and `A0J`), FDSPostLinkAttachment's render (`LX/Vzq;->render`) and seven
 * more renders. In dark mode each reads this config's string first and parses it when it isn't
 * empty, and only then falls back to a grey a second config picks or to CARD_BACKGROUND_FLAT's
 * token, which route one already themes. The string came out #333334, a grey light mode draws too,
 * so route four's parser leaves it.
 */
internal const val LINK_BOX_COLOUR_CONFIG = 0x103074c000203a8L

/** Where the parse of [LINK_BOX_COLOUR_CONFIG]'s string goes instead of route four's parser. */
internal const val PARSE_LINK_BOX = "$MATERIAL_YOU->parseLinkBox(Ljava/lang/String;)I"

/** Whether [method] loads [LINK_BOX_COLOUR_CONFIG]. */
internal fun readsLinkBoxColour(method: Method): Boolean =
    method.implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == LINK_BOX_COLOUR_CONFIG } == true

/**
 * In each method that reads [LINK_BOX_COLOUR_CONFIG], sends its one call to route four's parser to
 * [PARSE_LINK_BOX] on the same register. Runs after route four, so the call is already Material
 * You's stand-in, AMOLED's included. Answers how many it changed. A method that reads the config
 * with no parser call, or more than one, is refused: the parse to change would be a guess.
 */
internal fun BytecodePatchContext.hookLinkBoxColour(): Int {
    val parser = standIn(MATERIAL_YOU, PARSE_COLOR)
    val owners = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        if (classDef.methods.any(::readsLinkBoxColour)) owners += classDef.type
    }
    var changed = 0
    for (type in owners) {
        for (method in mutableClassDefBy(type).methods.filter(::readsLinkBoxColour)) {
            val parses = method.implementation!!.instructions.withIndex().filter { it.value.referenceText() == parser }
            check(parses.size == 1) {
                "${method.definingClass}->${method.name} reads the link box's colour with ${parses.size} calls to $parser"
            }
            val (index, call) = parses.single()
            val text = when (call) {
                is RegisterRangeInstruction -> call.startRegister
                is FiveRegisterInstruction -> call.registerC
                else -> error("${method.definingClass}->${method.name}: unexpected call form ${call.opcode}")
            }
            method.replaceInstruction(index, "invoke-static/range { v$text .. v$text }, $PARSE_LINK_BOX")
            changed++
        }
    }
    return changed
}

/**
 * Route three over the whole app: each dark surface written in code is read from the extension field
 * of the same name instead, except in the [systemBarColourMethods], whose colours the bar hooks
 * decide. Answers how many it replaced.
 */
internal fun BytecodePatchContext.readSurfaceLiterals(): Int {
    val handsToBar = systemBarColourMethods()
    val fields = classDefBy(MATERIAL_YOU).fields
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "I" }
        .map { it.name }
        .toSet()
    SURFACE_FIELDS.values.forEach { field ->
        check(field in fields) { "$MATERIAL_YOU has no static int $field for route three to read" }
    }
    val owners = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        if (classDef.methods.any { it.writesSurface() }) owners += classDef.type
    }
    return owners.sumOf { type ->
        mutableClassDefByOrNull(type)?.methods?.sumOf { if (handsToBar(it)) 0 else it.readSurfaceFields() } ?: 0
    }
}

/**
 * Every `iget vA, vB, TypedValue.data` in the app becomes `colourData(vB)` into vA, so a theme
 * attribute read as a plain colour takes the palette like the colour calls do. The extension looks
 * at the value's type and only answers for a colour. The TypedValue register is read before the
 * result is written, so `iget v0, v0` stays right. `iget` only names registers up to v15, which is
 * all the short call form takes too. Answers how many it replaced.
 */
internal fun BytecodePatchContext.readColourData(): Int {
    val owners = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        if (classDef.methods.any { method -> method.implementation?.instructions?.any { it.isColourDataRead() } == true }) {
            owners += classDef.type
        }
    }
    return owners.sumOf { type -> mutableClassDefByOrNull(type)?.methods?.sumOf { it.readColourData() } ?: 0 }
}

private fun Instruction.isColourDataRead(): Boolean =
    opcode == Opcode.IGET && referenceText() == TYPED_VALUE_DATA

/** [readColourData] for one method. */
internal fun MutableMethod.readColourData(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex().filter { it.value.isColourDataRead() }
        .map { it.index to it.value as TwoRegisterInstruction }
    sites.asReversed().forEach { (index, read) ->
        replaceInstruction(index, "invoke-static { v${read.registerB} }, $COLOUR_DATA")
        addInstruction(index + 1, "move-result v${read.registerA}")
    }
    return sites.size
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

/** True for a `const` of one of the surfaces. A `const-wide` keeps its value: a long isn't read from an int field. */
private fun Instruction.isSurface(): Boolean =
    opcode == Opcode.CONST && this is NarrowLiteralInstruction && narrowLiteral in SURFACE_FIELDS

private fun Method.writesSurface(): Boolean = implementation?.instructions?.any { it.isSurface() } == true

/**
 * Replaces each `const vX, <surface>` in this method with a read of the extension field for that
 * surface, into the same register. A `const` has an 8-bit register and so does `sget`, and neither
 * branches, so nothing around it moves. Answers how many it replaced.
 */
internal fun MutableMethod.readSurfaceFields(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .filter { it.value.isSurface() }
        .map { Triple(it.index, (it.value as OneRegisterInstruction).registerA, (it.value as NarrowLiteralInstruction).narrowLiteral) }

    sites.asReversed().forEach { (index, register, value) ->
        replaceInstruction(index, "sget v$register, $MATERIAL_YOU->${SURFACE_FIELDS.getValue(value)}:I")
    }
    return sites.size
}
