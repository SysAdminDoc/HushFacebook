/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
 * ComposerRowColourFixtureTest holds the composer row's way to this call on each declared build. 585
 * asks the token's colour instead (`LX/02It;->A00` down to FDSColors' `LX/02Jm;->A0B`) and makes the
 * ColorDrawable itself, so route one themes the row there and the test holds that way.
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

/**
 * Facebook's own entries stay as they are, since light mode reads them. The only thing added is the
 * palette the night style items point at, under names light mode never asks for: a theme made at night
 * keeps those references when the phone leaves night, and Android throws on one that then finds no
 * value (issue #323), so these names can't have a night qualifier.
 */
private const val DEFAULT_COLORS = "res/values/colors.xml"
private const val DEFAULT_VALUES = "res/values"

/** The palette's system colours for Android 12 and newer, with no night qualifier for the same reason. */
private const val V31_COLORS = "res/values-v31/colors.xml"

/** The night style items' colour state lists for Android 12 and newer, one file per [NightShade]. */
private const val V31_STATE_LISTS = "res/color-v31"

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

/**
 * The patch's own colours in [res], a decoded resource folder, with a night value and no default one.
 * A theme made at night that names one makes Android throw once the phone leaves night (issue #323).
 */
internal fun ownNightOnlyColours(res: File): Set<String> {
    val defaults = File(res, "values/colors.xml").takeIf { it.isFile }?.let { readOnly(it).colourValues().keys }.orEmpty()
    return nightValuedColours(res).filterTo(sortedSetOf()) { it.startsWith("hushfacebook_") && it !in defaults }
}

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
        // Patcher 2.0 decodes only the files a patch asks for: an undecoded file does not exist yet, so
        // the state list check below needs its folder decoded too.
        decode("res/values*/colors.xml", "res/values*/style*.xml", V31_STATE_LISTS)
        check(get(NIGHT_COLORS, false).exists()) { "Facebook has no night colours, so text would keep its grey" }
        for (path in listOf(NIGHT_V31_COLORS, V31_COLORS)) {
            val dynamic = get(path, false)
            if (!dynamic.exists()) {
                dynamic.parentFile.mkdirs()
                dynamic.writeText(EMPTY_RESOURCES)
            }
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
        // The tokens whose items keep a plain colour: read as TypedValue.data, or reached from a
        // styleable array's integer read through the styles' attribute aliases (issue #154).
        val plain = plainTokens + styleablePlainTokens(styleFiles.map(::readOnly), tokenAttributeNames, integerReadAttributeIds)
        var restyled = 0
        val stateLists = sortedMapOf<String, String>()
        for (file in styleFiles) {
            val family = darkFdsStyles(readOnly(file), tokenAttributeNames)
            if (family.isEmpty()) continue
            val nightStyles = "$NIGHT_VALUES/${file.name}"
            get(nightStyles, false).let { if (!it.exists()) it.writeText(EMPTY_RESOURCES) }
            restyled += document(nightStyles).use { night ->
                document(DEFAULT_COLORS).use { paletteColours ->
                    document(V31_COLORS).use { paletteV31Colours ->
                        writeNightStyles(family, colours, nightColourNames, tokenAttributeNames, night, paletteColours,
                            paletteV31Colours, stateLists, plain)
                    }
                }
            }
        }
        check(restyled > 0) {
            "No FDS dark style item takes a palette colour, so views Facebook inflates from its layouts would keep its blue"
        }
        for ((name, stateList) in stateLists) {
            val file = get("$V31_STATE_LISTS/$name.xml", false)
            check(!file.exists()) { "Facebook already has a colour state list named $name" }
            file.parentFile.mkdirs()
            file.writeText(stateList)
        }
        val nightOnly = ownNightOnlyColours(get("res", false))
        check(nightOnly.isEmpty()) { "These colours have only a night value, so leaving night would crash Facebook: $nightOnly" }
    }
}

@Suppress("unused")
val materialYouThemePatch = bytecodePatch(
    name = "Material You theme",
    description = "Gives Facebook the colors of your wallpaper on Android 12 and newer, so it matches your " +
        "phone. Dark mode takes them everywhere, and light mode on its grays, blues and selected tab. It isn't " +
        "selected by default because undoing it means patching again. Turn on Expert mode in Manager to pick it.",
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
        check(hookLinkBoxColour(darkCheck) > 0) { "No parse of the link box's colour found, so link posts would keep a grey box" }

        // What a link box takes when that config's string is empty (#37 leftover): a colour a parent
        // hands FDS's link attachment, a second config's grey, and FDS's light or dark chooser, which
        // the footer's box goes through with a colour from the attachment's model.
        if (hookLinkBoxFallbacks() == 0) {
            patchLog.warning("No link box fallback colour found. Link boxes still take the palette when MobileConfig sends their colour.")
        }
        try {
            hookDarkPick(darkCheck)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without FDS's light or dark chooser.")
        }

        // Bloks screens such as the Menu's profile card (issue #37): the server sends each colour as
        // a light and a dark hex string, and the screen's model parses the one Bloks' theme picks.
        check(hookBloksColourPairs() > 0) { "No parse of a Bloks colour pair found, so the Menu's profile card would stay grey" }

        // Bloks bottom sheets such as the account switcher (issue #37): the sheet's dark colour comes
        // with the screen as half of a light and dark pair, a grey no FDS token gives.
        try {
            hookBloksSheetBackground()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without Bloks sheets' backgrounds.")
        }

        // The colour resource behind a token, which FDS's token lookup answers and the code then reads
        // with getColor or as a drawable, like the Menu's Log out row (issue #37). Route two's reads
        // above go by the token once the extension knows it.
        fdsTokenResourceLookup().hookColorReturns(tokenParameterIndex = 1, target = TOKEN_RESOURCE)

        // The comment list's rows (issue #37): a theme attribute resolved into a TypedValue and read as
        // its `data`, which SURFACE_BACKGROUND's night item (#252728) reaches without a colour call.
        check(readColourData() > 0) { "No TypedValue.data read found, so rows that read a theme colour that way would stay grey" }

        // Route three. AMOLED, when it went first, has blackened all but one of these, which is why
        // finding none is fine then.
        val read = readSurfaceLiterals()
        check(read > 0 || amoledParsers > 0) { "No dark surface written in code, so the chrome would stay grey" }

        // Route three for the colours written right after Facebook's own dark check says yes, like
        // the stories tray's Create story tile (issue #37). After the surfaces, so a surface there is
        // a field read.
        check(readDarkBranchLiterals(darkCheck) > 0) {
            "No colour written on Facebook's dark branch found, so the Create story tile would stay grey"
        }

        // React Native screens such as Marketplace home. With AMOLED in the build its call put
        // the hooks in already, and ReactColours runs both themes.
        hookReactColours()

        // The older comment row's time (#138). With AMOLED in the build its call put the hook in
        // already, and CommentTimeColour runs both themes.
        try {
            hookCommentTimeColour()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the comment time's colour check.")
        }

        // The ring between a post's overlapping reaction icons (#147). With AMOLED in the build its
        // call put the hook in already, and ReactionRing runs both themes.
        try {
            hookReactionRing()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the reaction ring's cut.")
        }

        // The reactions token pile's discs (#147). With AMOLED in the build its call put the hook in
        // already, and ReactionRing runs both themes.
        try {
            hookReactionPile()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the reactions token pile's page colour.")
        }
    }
}

/**
 * The MobileConfig colour the box under a link post's picture takes in Facebook's dark mode (issue
 * #37). On 583 ten methods read it, one for each kind of link attachment: the attachment footer's
 * (arm64 `LX/4vd;->A0E` and `A0J`), FDSPostLinkAttachment's render (`LX/S3r;->render`) and seven
 * more renders. In dark mode each reads this config's string first and parses it when it isn't
 * empty, and only then falls back to a grey a second config picks or to CARD_BACKGROUND_FLAT's
 * token, which route one already themes. The string came out #333334, a grey light mode draws too,
 * so route four's parser leaves it. The fallbacks that route one doesn't reach are in
 * LinkBoxFallbacks.kt. Facebook renumbers MobileConfig ids between builds: 582 had it at
 * `0x103074c000203a8`, 583 at `0x1030747000203a7` and 584 at `0x1030741000203a6` (584's
 * `LX/5BP;->A00` and renders read it through `MobileConfigUnsafeContext.A0H`, the String reader,
 * which 583 had as `A0C`). 585 has it at the id below on both ABIs, read 2026-10-30 by nine methods
 * that each parse its string: eight renders (arm64 `LX/08d5;->render`, `LX/0Iqd;->render`,
 * `LX/0N8x;->A17` and five more) and the attachment footer's `LX/05X5;->A0B(FbUserSession, LX/01PR, Z)I`.
 */
internal const val LINK_BOX_COLOUR_CONFIG = 0x1030736000203aaL

/**
 * Every id the declared builds give the link box's colour config. Only declared builds' ids belong
 * here: a newer build can hand a dropped build's id to another config.
 */
internal val LINK_BOX_COLOUR_CONFIGS: Set<Long> = setOf(LINK_BOX_COLOUR_CONFIG)

/** Where the parse of [LINK_BOX_COLOUR_CONFIG]'s string goes instead of route four's parser. */
internal const val PARSE_LINK_BOX = "$MATERIAL_YOU->parseLinkBox(Ljava/lang/String;)I"

/**
 * Whether [method] asks [darkCheck]: it calls it, or calls a short static method answering a
 * boolean that does. 583's armeabi-v7a link attachment asks the second way, through a wrapper that
 * reads the context off its argument and hands it on, so the check still runs and is hooked.
 */
internal fun BytecodePatchContext.asksDarkCheck(method: Method, darkCheck: String): Boolean =
    method.implementation?.instructions?.any { instruction ->
        if (instruction.referenceText() == darkCheck) return@any true
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call.returnType == "Z" &&
            classDefByOrNull(call.definingClass)?.methods?.any { wrapper ->
                wrapper.name == call.name &&
                    wrapper.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                    wrapper.implementation?.instructions?.toList()?.let { body ->
                        body.size <= 6 && body.any { it.referenceText() == darkCheck }
                    } == true
            } == true
    } == true

/** Whether [method] loads one of the [LINK_BOX_COLOUR_CONFIGS]. */
internal fun readsLinkBoxColour(method: Method): Boolean =
    method.implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral in LINK_BOX_COLOUR_CONFIGS } == true

/**
 * In each method that reads [LINK_BOX_COLOUR_CONFIG], sends its one call to route four's parser to
 * [PARSE_LINK_BOX] on the same register. Runs after route four, so the call is already Material
 * You's stand-in, AMOLED's included. Answers how many it changed. A method that reads the config
 * with no parser call, or more than one, is refused: the parse to change would be a guess. So is
 * one that never asks [darkCheck], Facebook's own dark check ([asksDarkCheck]): the extension takes
 * the parse for a dark-mode colour from before Facebook's first answer on, which only that check
 * makes true.
 */
internal fun BytecodePatchContext.hookLinkBoxColour(darkCheck: String): Int {
    val parser = standIn(MATERIAL_YOU, PARSE_COLOR)
    val owners = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        if (classDef.methods.any(::readsLinkBoxColour)) owners += classDef.type
    }
    var changed = 0
    for (type in owners) {
        for (method in mutableClassDefBy(type).methods.filter(::readsLinkBoxColour)) {
            check(asksDarkCheck(method, darkCheck)) {
                "${method.definingClass}->${method.name} reads the link box's colour without asking $darkCheck"
            }
            method.sendOneParse(parser, PARSE_LINK_BOX, "the link box's colour")
            changed++
        }
    }
    return changed
}

/**
 * Sends this method's one call to route four's [parser] to [target] on the same register. A method
 * with no such call, or more than one, is refused: the parse to change would be a guess. [what] names
 * the colour for the refusal.
 */
private fun MutableMethod.sendOneParse(parser: String, target: String, what: String) {
    val parses = implementation!!.instructions.withIndex().filter { it.value.referenceText() == parser }
    check(parses.size == 1) { "$definingClass->$name reads $what with ${parses.size} calls to $parser" }
    val (index, call) = parses.single()
    val text = when (call) {
        is RegisterRangeInstruction -> call.startRegister
        is FiveRegisterInstruction -> call.registerC
        else -> error("$definingClass->$name: unexpected call form ${call.opcode}")
    }
    replaceInstruction(index, "invoke-static/range { v$text .. v$text }, $target")
}

/** Where the parse of a Bloks colour pair goes instead of route four's parser (issue #37). */
internal const val PARSE_BLOKS = "$MATERIAL_YOU->parseBloks(Ljava/lang/String;)I"

/** What a Bloks model logs, around the two keys, for a colour pair it can't parse. */
internal const val BLOKS_PAIR_LIGHT_KEY = " for Color with keyLight: "
internal const val BLOKS_PAIR_DARK_KEY = " and keyDark: "

/** An instance (theme, light key, dark key, fallback) method answering an int, as a Bloks colour pair reader is. */
private fun Method.hasColourPairShape(): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    return !AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" && parameters.size == 4 &&
        parameters[0].startsWith("L") && parameters.drop(1) == listOf("I", "I", "I")
}

/**
 * The Bloks model that logs a colour pair it can't parse, with both keys (582
 * `LX/1Vp;->BAJ(LX/cKD;III)I`): the one colour pair reader holding both of the log's strings.
 */
internal fun isLoggingBloksColourPair(method: Method): Boolean {
    if (!method.hasColourPairShape()) return false
    val strings = method.implementation?.instructions
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
        .orEmpty()
    return BLOKS_PAIR_LIGHT_KEY in strings && BLOKS_PAIR_DARK_KEY in strings
}

/**
 * Whether a colour pair reader's whole body, NOPs aside, is `return` of its last parameter, the
 * fallback: a model with no colour of its own, which picks nothing and parses nothing.
 */
internal fun answersFallbackOnly(method: Method): Boolean {
    val implementation = method.implementation ?: return false
    val code = implementation.instructions.filter { it.opcode != Opcode.NOP }
    return code.size == 1 && code.single().opcode == Opcode.RETURN &&
        (code.single() as OneRegisterInstruction).registerA == implementation.registerCount - 1
}

/**
 * Bloks' colour pairs (issue #37). A Bloks screen gets each colour from the server as a light and a
 * dark hex string, and the screen's model answers one through an interface method (582
 * `LX/fra;->BAJ`, in the models `LX/1Vp` and `LX/4TS`). Each model asks Bloks' theme whether it's
 * dark, takes that key's string, or the light one when there is no dark one, and parses it. The
 * Menu's profile card at the top is #333334 this way, a grey light mode draws too, so route four's
 * parser leaves it. In each model the one call to that parser goes to [PARSE_BLOKS] on the same
 * register. Runs after route four, so the call is already Material You's stand-in, AMOLED's included.
 * The model that logs a pair it can't parse ([isLoggingBloksColourPair]) names the interface, and the
 * interface names the other models. One that parses more than once or never is refused, and so is
 * one that doesn't ask the theme, unless it only answers its fallback ([answersFallbackOnly]).
 * Answers how many it changed.
 *
 * 585, read 2026-10-30: the interface is arm64 `LX/062H;->CPv(LX/0i13;III)I`, the logging model
 * `LX/01JW`, the sparse one `LX/05CD`, and two more (`LX/07Dt`, `LX/07Du`) only `return p4`.
 */
internal fun BytecodePatchContext.hookBloksColourPairs(): Int {
    val parser = standIn(MATERIAL_YOU, PARSE_COLOR)
    val logging = mutableListOf<Method>()
    classDefForEachReferencing(strings = listOf(BLOKS_PAIR_LIGHT_KEY)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(logging, ::isLoggingBloksColourPair)
    }
    val anchor = logging.singleOrNull()
        ?: error("Found ${logging.size} Bloks colour pair readers that log both keys, expected one")
    val accessor = classDefBy(anchor.definingClass).interfaces.singleOrNull { type ->
        classDefByOrNull(type)?.methods?.any { it.sameAs(anchor) } == true
    } ?: error("${anchor.definingClass}->${anchor.name} implements no interface that declares it")
    val theme = anchor.parameterTypes.first().toString()
    var changed = 0
    classDefForEachReferencing(types = listOf(accessor)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE) || accessor !in classDef.interfaces) return@classDefForEachReferencing
        // Check the interface before decoding methods or making a class mutable. Same-shaped
        // methods on unrelated models are common, but must not be rewritten or materialized.
        if (classDef.methods.none { it.hasColourPairShape() && it.implementation != null }) return@classDefForEachReferencing
        val model = mutableClassDefBy(classDef.type)
        val method = model.methods.singleOrNull { it.sameAs(anchor) && it.implementation != null } ?: return@classDefForEachReferencing
        // Why: 585 adds models with no colours of their own, whose reader only answers the fallback.
        if (answersFallbackOnly(method)) return@classDefForEachReferencing
        check(method.implementation!!.instructions.any { it.asksDark(theme) }) {
            "${method.definingClass}->${method.name} picks a Bloks colour without asking $theme whether it's dark"
        }
        method.sendOneParse(parser, PARSE_BLOKS, "a Bloks colour pair")
        changed++
    }
    return changed
}

/** Where FDS's token lookup sends each colour resource it answers, with the token (issue #37). */
internal const val TOKEN_RESOURCE = "$MATERIAL_YOU->tokenResource(ILjava/lang/Object;)I"

private const val GET_RESOURCE_ID = "Landroid/content/res/TypedArray;->getResourceId(II)I"

/**
 * FDS's token lookup (582 `LX/24i;->A02(Landroid/content/Context;LX/23Y;)I`): the static (Context,
 * token) method on the [fdsViewResolver]'s class that answers the colour resource the token's theme
 * attribute points at. It caches what a helper reads with `TypedArray.getResourceId` (582
 * `LX/24s;->A01`), which tells it from the resolver beside it, which answers a colour.
 */
internal fun BytecodePatchContext.fdsTokenResourceLookup(): MutableMethod {
    val viewResolver = fdsViewResolver()
    val parameters = viewResolver.parameterTypes.map(CharSequence::toString)
    val lookups = mutableClassDefBy(viewResolver.definingClass).methods.filter { method ->
        isTokenResourceLookup(method, parameters) { call -> readsResourceId(call) }
    }
    return lookups.singleOrNull()
        ?: error("The FDS view resolver's class has ${lookups.size} token resource lookups, expected one")
}

/**
 * Whether [method] is a static method taking [parameters] and answering an int that calls another
 * such method, one that [readsResourceId].
 */
internal fun isTokenResourceLookup(method: Method, parameters: List<String>, readsResourceId: (MethodReference) -> Boolean): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "I") return false
    if (method.parameterTypes.map(CharSequence::toString) != parameters) return false
    return method.implementation?.instructions?.any { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call != null && call.returnType == "I" && call.parameterTypes.map(CharSequence::toString) == parameters &&
            readsResourceId(call)
    } == true
}

/** Whether the method [call] names reads a resource id with `TypedArray.getResourceId` itself. */
internal fun BytecodePatchContext.readsResourceId(call: MethodReference): Boolean =
    classDefByOrNull(call.definingClass)?.methods?.any { method ->
        method.name == call.name &&
            method.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
            method.implementation?.instructions?.any { it.referenceText() == GET_RESOURCE_ID } == true
    } == true

/** Whether this calls a no-argument [theme] method answering a boolean: Bloks asking its theme whether it's dark. */
private fun Instruction.asksDark(theme: String): Boolean {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.definingClass == theme && call.returnType == "Z" && call.parameterTypes.isEmpty()
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

/** Where route three sends a colour Facebook writes on its own dark branch (issue #37). */
internal const val DARK_BRANCH = "$MATERIAL_YOU->darkBranch(I)I"

/**
 * Route three for the colours Facebook writes into its code for its dark theme only (issue #37): an
 * opaque colour `const` that is the first instruction once [darkCheck] has said yes, as in
 * `invoke-static {..}, darkCheck; move-result vR; if-eqz vR, :light; const vX, colour`. Each goes
 * through [target] into the same register right after the `const`. The light branch jumps past
 * it, and so does any jump to what follows the `const`, since a label stays on the instruction it
 * was on. A `const` something else jumps to is left, as it doesn't only run on the dark branch. On
 * 582 the Create story tile at the start of the stories tray is two of these (`LX/3Nj;->invoke`, the
 * AddToStoryTileSplitCardComponentSpec, and `LX/374;->render`), #333334, which isn't one of
 * [SURFACE_FIELDS]. Answers how many it changed.
 *
 * AMOLED's Black cards reads the same sites through [AMOLED_DARK_BRANCH] (issue #191), and its call
 * always answers first: a later call goes in after AMOLED's, and AMOLED's goes in next to the
 * `const`. So a card's grey Black cards gave the page's colour reaches [DARK_BRANCH] as that colour.
 */
internal fun BytecodePatchContext.readDarkBranchLiterals(darkCheck: String, target: String = DARK_BRANCH): Int {
    val owners = mutableSetOf<String>()
    classDefForEachReferencing(names = listOf(darkCheck.memberName())) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        if (classDef.methods.any { it.darkBranchLiterals(darkCheck).isNotEmpty() }) owners += classDef.type
    }
    return owners.sumOf { type -> mutableClassDefBy(type).methods.sumOf { it.readDarkBranchLiterals(darkCheck, target) } }
}

/** The member name in a `Lclass;->name(...)` or `Lclass;->name:type` reference. */
private fun String.memberName(): String = substringAfter("->").substringBefore('(').substringBefore(':')

/** Whether this calls [method], a reference as text. The name goes first, so most calls are never written out (#236). */
private fun Instruction.calls(method: String): Boolean {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return call.name == method.memberName() && call.toString() == method
}

/** The indexes of this method's colour `const`s on [darkCheck]'s dark branch, for [readDarkBranchLiterals]. */
internal fun Method.darkBranchLiterals(darkCheck: String): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    if (instructions.none { it.calls(darkCheck) }) return emptyList()
    val targets = branchTargets(instructions)
    return (0 until instructions.size - 3).filter { index ->
        val result = instructions[index + 1]
        val branch = instructions[index + 2]
        val literal = instructions[index + 3]
        instructions[index].calls(darkCheck) &&
            result.opcode == Opcode.MOVE_RESULT && branch.opcode == Opcode.IF_EQZ &&
            (result as OneRegisterInstruction).registerA == (branch as OneRegisterInstruction).registerA &&
            literal.opcode == Opcode.CONST && ((literal as NarrowLiteralInstruction).narrowLiteral ushr 24) == 0xFF &&
            index + 3 !in targets
    }.map { it + 3 }
}

/** [readDarkBranchLiterals] for one method. `move-result` takes any register a `const` does. */
internal fun MutableMethod.readDarkBranchLiterals(darkCheck: String, target: String = DARK_BRANCH): Int {
    val sites = darkBranchLiterals(darkCheck)
    sites.asReversed().forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        val amoledFirst = getInstruction<Instruction>(index + 1).referenceText() == AMOLED_DARK_BRANCH
        addInstructions(
            if (amoledFirst) index + 3 else index + 1,
            """
                invoke-static/range { v$register .. v$register }, $target
                move-result v$register
            """,
        )
    }
    return sites.size
}

/** The indexes some branch or switch case of a method jumps to. */
internal fun branchTargets(instructions: List<Instruction>): Set<Int> {
    val addresses = instructions.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOfAddress = addresses.withIndex().associate { (index, address) -> address to index }
    val targets = mutableSetOf<Int>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction !is OffsetInstruction) return@forEachIndexed
        val target = addresses[index] + instruction.codeOffset
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = instructions[indexOfAddress.getValue(target)] as SwitchPayload
            payload.switchElements.forEach { element -> indexOfAddress[addresses[index] + element.offset]?.let(targets::add) }
        } else {
            indexOfAddress[target]?.let(targets::add)
        }
    }
    return targets
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
    classDefForEachReferencing(types = listOf(TYPED_VALUE_DATA.substringBefore("->"))) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        if (classDef.methods.any { method -> method.implementation?.instructions?.any { it.isColourDataRead() } == true }) {
            owners += classDef.type
        }
    }
    return owners.sumOf { type -> mutableClassDefByOrNull(type)?.methods?.sumOf { it.readColourData() } ?: 0 }
}

private fun Instruction.isColourDataRead(): Boolean =
    opcode == Opcode.IGET && ((this as ReferenceInstruction).reference as? FieldReference)?.name == "data" &&
        referenceText() == TYPED_VALUE_DATA

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
