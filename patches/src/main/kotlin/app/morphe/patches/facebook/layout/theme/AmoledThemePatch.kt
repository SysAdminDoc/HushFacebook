/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/amoledtheme/AmoledThemePatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireParameterIntact
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.settings.declaredInHierarchy
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.literalReads
import app.morphe.util.returnEarly
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import app.morphe.patches.facebook.feed.refresh.enumConstant
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import app.morphe.patches.facebook.misc.settings.settingsPatch

/** The largest value a channel can have and still count as a background. */
private const val MAX_CHANNEL = 0x2A

/**
 * The largest difference between the channels of a background. A grey has almost none. A dark green
 * or dark brown banner has much more, and it keeps its colour.
 */
private const val MAX_SPREAD = 8

/** The framework type of the one parameter FDS's dark check takes. */
private const val CONTEXT = "Landroid/content/Context;"

/** Opaque black, as the signed int that a colour holds. */
private const val BLACK = -0x1000000

/**
 * Facebook's text in dark mode, its PRIMARY_TEXT token on 577 and 580. A background colour has to
 * leave it readable on every surface AMOLED makes from that colour.
 */
private const val DARK_MODE_TEXT = 0xFFF2F4F7.toInt()

/** WCAG 2.2's AA contrast for text. */
internal const val TEXT_CONTRAST = 4.5

/**
 * How far above the background colour the lightest surface AMOLED makes sits: an input's fill from
 * the lightest raised grey, AmoledTheme's MAX_RAISED_CHANNEL (0x42) less its FILL_SHIFT (0x0D).
 */
internal const val LIGHTEST_STEP = 0x42 - 0x0D

/** Gets the resolved colour and its token. Gives the colour to draw. */
internal const val APPLY = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->apply(ILjava/lang/Object;)I"

/** The same for the Mig light scheme's colours, which only the Light background color row moves (#214). */
internal const val APPLY_LIGHT = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->applyLight(ILjava/lang/Object;)I"

/** Gets the status bar's colour and whether Facebook's theme is dark. Gives the colour to paint. */
internal const val STATUS_BAR = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->statusBar(IZ)I"

/** The same for the navigation bar. */
internal const val NAVIGATION_BAR = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->navigationBar(IZ)I"

/** The framework type of the window each bar painter takes. */
private const val WINDOW = "Landroid/view/Window;"

/** The framework call that turns a colour string, such as `"#FF252728"`, into a colour. */
internal const val PARSE_COLOR = "Landroid/graphics/Color;->parseColor(Ljava/lang/String;)I"

/** The extension call that replaces it. It has the same signature. */
internal const val PARSE_COLOR_DARK = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->parseColor(Ljava/lang/String;)I"

/** The classes of the extension. Route four skips them, because the replacement calls the original. */
internal const val EXTENSION_PACKAGE = "Lapp/morphe/extension/"

private const val AMOLED = "Lapp/morphe/extension/facebook/theme/AmoledTheme;"

/** Facebook's answer for whether its dark mode is on goes through this on its way back. */
internal const val DARK_MODE_ANSWER = "Lapp/morphe/extension/facebook/theme/DarkMode;->answer(Z)Z"

/** The framework calls that read a colour resource, which route two's rewrites reach code through. */
internal const val CONTEXT_GET_COLOR = "Landroid/content/Context;->getColor(I)I"
internal const val RESOURCES_GET_COLOR = "Landroid/content/res/Resources;->getColor(I)I"
internal const val RESOURCES_GET_THEMED_COLOR =
    "Landroid/content/res/Resources;->getColor(ILandroid/content/res/Resources\$Theme;)I"
internal const val TYPED_ARRAY_GET_COLOR = "Landroid/content/res/TypedArray;->getColor(II)I"

/**
 * The static method of [owner] that stands in for the framework call [framework]: same name and
 * parameters, with the object a virtual call was made on in front. `Color.parseColor` is static
 * itself and has nothing in front.
 */
internal fun standIn(owner: String, framework: String): String {
    val receiver = framework.substringBefore("->")
    val call = framework.substringAfter("->")
    return if (framework == PARSE_COLOR) {
        "$owner->$call"
    } else {
        "$owner->${call.substringBefore("(")}($receiver${call.substringAfter("(")}"
    }
}

/**
 * Where AMOLED sends each framework colour call: route four's `Color.parseColor`, and the reads of
 * a colour resource, which in light mode give a black route two wrote back as Facebook's colour.
 */
internal val AMOLED_COLOUR_CALLS: Map<String, String> =
    listOf(PARSE_COLOR, CONTEXT_GET_COLOR, RESOURCES_GET_COLOR, RESOURCES_GET_THEMED_COLOR, TYPED_ARRAY_GET_COLOR)
        .associateWith { standIn(AMOLED, it) }

/**
 * Route two's table for the extension, which the resource half writes and the bytecode half puts
 * in AmoledTheme.routeTwoColours: each colour resource it wrote black over, with Facebook's colour.
 */
internal var routeTwoRestores = ""

/**
 * How far apart a lighter background keeps Facebook's primary text and the secondary text it lifts:
 * a little under Material 3's dark scheme, whose secondary text (on-surface-variant, tone 80) sits
 * about 1.32:1 under its primary (on-surface, tone 90). It sets how light the surfaces may go.
 */
internal const val TEXT_HIERARCHY = 1.3

/**
 * The fewest steps above a lighter background its lightest surface may take, so a card, a popover
 * and an input still sit apart from the page and from each other.
 */
internal const val MIN_LIGHTEST_STEP = 8

/**
 * An unread notification's row on AMOLED: FDS's #192D88FF at AmoledTheme's NEW_NOTIFICATION_ALPHA
 * (issue #72). Text sits on it, over the page.
 */
internal const val UNREAD_ROW = 0x402D88FF

/** Facebook's dark cards (#333334), popovers (#3B3C3E) and an input's fill (#333334), as route one steps them. */
private val SURFACE_STEPS = listOf(0x333334 - 0x212121, 0x3B3C3E - 0x212121, 0x333334 - 0x0D0D0D)

/**
 * The colour the Background colour option asks for (issue #34): black when it's blank, or the
 * `#RRGGBB` given, `#FFRRGGBB` too, as a colour picker writes it. Null for anything else: a colour
 * that isn't opaque, or one too light for any [lightestStep] to keep text readable on it.
 */
internal fun backgroundColour(value: String?): Int? {
    val colour = parsedBackground(value) ?: return null
    return colour.takeIf { lightestStep(it) != null }
}

/** The opaque colour [value] gives, black when it's blank, before its contrast is judged. */
private fun parsedBackground(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#").orEmpty()
    if (hex.isEmpty()) return BLACK
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    if (hex.length == 8 && !hex.startsWith("ff", ignoreCase = true)) return null
    return (0xFF000000L or hex.takeLast(6).toLong(16)).toInt()
}

/**
 * Why the Background colour option refuses [value], in words the person patching can act on, or
 * null when it's taken.
 */
internal fun backgroundProblem(value: String?): String? {
    val hex = value?.trim()?.removePrefix("#").orEmpty()
    val looksLikeAColour = (hex.length == 6 || hex.length == 8) && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return when {
        !looksLikeAColour && hex.isNotEmpty() ->
            "Background color \"$value\" isn't a color. Type it as a six-digit color code (#RRGGBB), like #0D1117, or leave it blank for black."
        parsedBackground(value) == null ->
            "Background color $value is see-through. Type a solid six-digit color code (#RRGGBB), like #0D1117."
        backgroundColour(value) == null ->
            "Background color $value is too light for Facebook's text to stay readable. Pick a darker color. " +
                "Grays up to about $LIGHTEST_ADMITTED_GREY work."
        else -> null
    }
}

/** The lightest grey the option takes, for [backgroundProblem]'s advice. AmoledBackgroundOptionTest holds it. */
internal const val LIGHTEST_ADMITTED_GREY = "#535353"

/**
 * How far above [colour] the lightest surface AMOLED makes sits, which AmoledTheme.lightestStep
 * gets: [LIGHTEST_STEP] when Facebook's dark-mode text keeps 4.5:1 on every surface, as it always
 * has, so black and every colour taken before keep exactly the surfaces they had.
 *
 * A lighter colour gets the largest smaller step that leaves room for lifted secondary text: text
 * at 4.5:1 on the lightest surface, an unread row's tint included, while staying [TEXT_HIERARCHY]
 * under the primary text. The card, popover and input fill then have to stay in their order and
 * apart. Null when no step does all that: the colour is too light.
 */
internal fun lightestStep(colour: Int): Int? {
    if (contrast(DARK_MODE_TEXT, raised(colour, LIGHTEST_STEP)) >= TEXT_CONTRAST) return LIGHTEST_STEP
    val ceiling = (luminance(DARK_MODE_TEXT) + 0.05) / (TEXT_HIERARCHY * TEXT_CONTRAST) - 0.05
    val step = (LIGHTEST_STEP - 1 downTo MIN_LIGHTEST_STEP).firstOrNull { textSurface(colour, it) <= ceiling } ?: return null
    return step.takeIf { surfaces(colour, it).zipWithNext().all { (lower, upper) -> luminance(lower) < luminance(upper) } }
}

/**
 * The page and the surfaces route one makes on it with [lightest] as the lightest step, darkest
 * first: page, card, popover and input fill, each step scaled as AmoledTheme.scaled does it.
 */
internal fun surfaces(colour: Int, lightest: Int): List<Int> {
    fun scaled(step: Int): Int {
        fun channel(shift: Int) = ((step shr shift and 0xFF) * lightest + LIGHTEST_STEP / 2) / LIGHTEST_STEP shl shift
        return channel(16) or channel(8) or channel(0)
    }
    fun raisedBy(step: Int): Int {
        fun channel(shift: Int) = minOf(0xFF, (colour shr shift and 0xFF) + (step shr shift and 0xFF)) shl shift
        return BLACK or channel(16) or channel(8) or channel(0)
    }
    return listOf(colour) + SURFACE_STEPS.map { raisedBy(scaled(it)) }
}

/** The luminance of the lightest thing text sits on over [colour]: [lightest] above it, or an unread row's tint. */
internal fun textSurface(colour: Int, lightest: Int): Double =
    maxOf(luminance(raised(colour, lightest)), luminance(over(colour, UNREAD_ROW)))

/** [tint] drawn over the opaque [base], rounded as AmoledTheme.over does it. */
internal fun over(base: Int, tint: Int): Int {
    val alpha = tint ushr 24
    fun channel(shift: Int) = (((tint shr shift and 0xFF) * alpha + (base shr shift and 0xFF) * (0xFF - alpha) + 0x7F) / 0xFF) shl shift
    return BLACK or channel(16) or channel(8) or channel(0)
}

/** [colour] with [step] added to each channel, as far as white. The extension raises a card the same way. */
internal fun raised(colour: Int, step: Int): Int {
    fun channel(shift: Int) = minOf(0xFF, (colour shr shift and 0xFF) + step) shl shift
    return BLACK or channel(16) or channel(8) or channel(0)
}

/** WCAG 2.2's contrast ratio between two opaque colours. */
internal fun contrast(first: Int, second: Int): Double {
    val (light, dark) = listOf(luminance(first), luminance(second)).sortedDescending()
    return (light + 0.05) / (dark + 0.05)
}

internal fun luminance(colour: Int): Double {
    fun linear(shift: Int): Double {
        val channel = (colour shr shift and 0xFF) / 255.0
        return if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}

/**
 * Route two's new text for one colour resource: [written] (the background colour) over a dark grey,
 * or null to leave it. Facebook's own black stays black, as route three leaves a black literal.
 */
internal fun routeTwoValue(value: String, written: String): String? = when {
    !isDarkBackground(value) -> null
    argb(value) == BLACK -> value
    else -> written
}

/**
 * True for a dark grey, which is what a background uses. False for a dark colour with a hue, which
 * is a banner and keeps its colour. The extension holds the same rule for the colours it gets.
 */
private fun isDarkNeutral(red: Int, green: Int, blue: Int): Boolean {
    val high = maxOf(red, green, blue)
    return high <= MAX_CHANNEL && high - minOf(red, green, blue) <= MAX_SPREAD
}

private const val DEFAULT_COLORS = "res/values/colors.xml"
private const val NIGHT_COLORS = "res/values-night/colors.xml"

/**
 * A colour reaches the screen by four routes, and this patch covers all four with one rule.
 *
 * The first route is a resolver: a component asks the design system, and the bytecode half hooks
 * the four methods that answer. The second is a resource: a view reads a colour by id, so no int
 * passes a hook, and the resource half below rewrites it. The third is a literal written in code,
 * which the bytecode half rewrites in place, except where a method hands it to a system bar: the bar
 * painters' hooks decide those, since they ask Facebook's theme. The fourth is a string that the
 * server sends, which the app parses with `Color.parseColor`. The bytecode half sends each of those
 * calls through the extension.
 *
 * The Video tab stays dark in light mode, from the same dark style and the same colour resources as
 * dark mode, so the extension also asks Facebook's own answer for dark mode ([hookDarkModeAnswer]).
 * In light mode routes one and four leave Facebook's colour, and the code's reads of a colour
 * resource get back what route two wrote black over ([routeTwoTable], [rerouteColourCalls]).
 *
 * Routes two, three and four match on the **value** or on a framework call, with no class, method
 * or resource name.
 * Facebook strips resource names and renames its classes about every two weeks, so a name is not an
 * anchor here. A value of this exact shape is a colour and nothing else.
 */
private fun amoledThemeResourcePatch(background: () -> Int) = resourcePatch {
    // The FDS token names, for light mode's surfaces in the light style ([repointLightSurfaces]).
    dependsOn(fdsTokenNamesPatch)

    execute {
        val written = "#%08x".format(background())
        var changed = 0
        val rewritten = mutableMapOf<String, Int>()
        val rewrittenAtNight = mutableSetOf<String>()
        val references = mutableMapOf<String, String>()
        val wrote = mutableSetOf<String>()
        val wroteAtNight = mutableSetOf<String>()
        val literals = mutableMapOf<String, Int>()

        // Patcher 2.0 decodes only the files a patch asks for: an undecoded file does not exist yet.
        // Every configuration's colours and the default styles, for light mode's surfaces below.
        decode(DEFAULT_COLORS, NIGHT_COLORS, "res/values*/colors.xml", "res/values/style*.xml")
        listOf(DEFAULT_COLORS, NIGHT_COLORS)
            .filter { get(it, false).exists() }
            .forEach { path ->
                document(path).use { document ->
                    val colors = document.getElementsByTagName("color")
                    for (index in 0 until colors.length) {
                        val color = colors.item(index) as? Element ?: continue
                        val name = color.getAttribute("name")
                        val value = color.textContent.trim()
                        if (path == DEFAULT_COLORS && value.startsWith("@color/")) references[name] = value.removePrefix("@color/")
                        if (path == DEFAULT_COLORS) argb(value)?.let { literals[name] = it }
                        val text = routeTwoValue(value, written) ?: continue
                        if (path == DEFAULT_COLORS) rewritten[name] = checkNotNull(argb(value)) else rewrittenAtNight += name
                        if (argb(value) != BLACK) {
                            if (path == DEFAULT_COLORS) wrote += name else wroteAtNight += name
                        }
                        color.textContent = text
                        changed++
                    }
                }
            }

        check(changed > 0) { "No dark colour resource found, so the theme would stay grey" }

        // The page's colour as a resource of its own, which the token lookup hands a card with Black
        // cards on (#191). Added after the rewrites, so none of them reads it.
        if (get(DEFAULT_COLORS, false).exists()) document(DEFAULT_COLORS).use { addPageColour(it, written) }

        // Light mode's surfaces get resources of their own, for the Light background color row
        // (#214): Facebook's light style points them at whites its text and icons share.
        val lightSurfaces = sortedMapOf<String, Int>()
        if (get(DEFAULT_COLORS, false).exists()) {
            val colours = readOnly(get(DEFAULT_COLORS)).colourValues()
            val configured = get("res", false).listFiles().orEmpty()
                .filter { it.isDirectory && it.name != "values" && it.name.split('-').first() == "values" }
                .map { File(it, "colors.xml") }.filter { it.isFile }
                .flatMapTo(mutableSetOf()) { readOnly(it).colourValues().keys }
            get("res/values", false).listFiles().orEmpty()
                .filter { it.name.startsWith("style") && it.name.endsWith(".xml") }.sortedBy { it.name }
                .filter { fdsThemeStyles(readOnly(it), tokenAttributeNames) != null }
                .forEach { file ->
                    document("res/values/${file.name}").use { styles ->
                        lightSurfaces += repointLightSurfaces(styles, tokenAttributeNames, colours, configured)
                    }
                }
            if (lightSurfaces.isNotEmpty()) document(DEFAULT_COLORS).use { addLightSurfaceColours(it, lightSurfaces) }
        }
        lightSurfaceColours = lightSurfacesTable(lightSurfaces)

        routeTwoRestores = routeTwoTable(rewritten, rewrittenAtNight, references) { name ->
            resourceIds.getOrNull(ResourceType.COLOR, name) ?: decodedColourId(name)
        }
        check(routeTwoRestores.isNotEmpty()) {
            "No colour route two rewrote has a resource id, so light mode's Video tab would stay black"
        }

        writtenColours = writtenColoursTable(wrote, wroteAtNight, literals, references) { name ->
            resourceIds.getOrNull(ResourceType.COLOR, name) ?: decodedColourId(name)
        }
    }
}

/** The resources route two wrote its colour over, for AmoledResources.writtenColours ([writtenColoursTable]). */
internal var writtenColours = ""

/**
 * AmoledResources.writtenColours's table: each colour resource route two wrote its colour over
 * ([wrote] by default, [wroteAtNight] at night), as `"id=D[/p];..."` in hex and sorted by id. D is
 * `p` where the default colour is route two's, else the default colour Facebook keeps there, read
 * through one `@color/` reference. `/p` says the night one is route two's too. A resource whose
 * default colour can't be read that way is left out, since the Background color row's table must
 * give both. Facebook's own black isn't route two's, so it stays black there too.
 */
internal fun writtenColoursTable(
    wrote: Set<String>,
    wroteAtNight: Set<String>,
    literals: Map<String, Int>,
    references: Map<String, String>,
    id: (String) -> Long?,
): String = (wrote + wroteAtNight).mapNotNull { name ->
    val target = references[name]
    val default = when {
        name in wrote -> "p"
        literals[name] != null -> "%08x".format(literals.getValue(name))
        target != null && target in wrote -> "p"
        target != null && literals[target] != null -> "%08x".format(literals.getValue(target))
        else -> return@mapNotNull null
    }
    val night = if (name in wroteAtNight) "/p" else ""
    id(name)?.let { it to "$default$night" }
}.sortedBy { it.first }.joinToString(";") { (id, entry) -> "%x=%s".format(id, entry) }

/** The colour resource that holds the Background colour, AmoledTheme.PAGE_COLOUR. */
internal const val PAGE_COLOUR = "hushfacebook_amoled_page"

/** Adds [PAGE_COLOUR] with [colour] to a `colors.xml` [document]. */
internal fun addPageColour(document: Document, colour: String) {
    document.documentElement.appendChild(
        document.createElement("color").also {
            it.setAttribute("name", PAGE_COLOUR)
            it.textContent = colour
        },
    )
}

/**
 * The FDS tokens of the surfaces light mode's Light background color moves (#214): AmoledTheme's
 * BACKGROUND_TOKENS and LIGHT_BACKGROUND_TOKENS, which AmoledLightSurfacesTest holds this to.
 */
internal val LIGHT_SURFACE_TOKENS = setOf(
    "WASH", "SURFACE", "CARD", "ELEVATION", "BANNER", "PRIMARY_UI",
    "WEB_WASH", "FBLITE_WASH", "SURFACE_BACKGROUND", "BACKGROUND_SURFACE", "DEVICE_BACKGROUND", "BACKGROUND_DEEMPHASIZED",
    "CARD_BACKGROUND", "CARD_BACKGROUND_FLAT", "CARD_BACKGROUND_LEGACY_WEB", "BACKGROUND_CARD", "BACKGROUND_ELEVATION",
    "LIST_CELL_BACKGROUND", "ATTACHMENT_FOOTER_BACKGROUND", "ENTITY_HEADER_BACKGROUND",
    "COMMENT_BACKGROUND", "COMMENT_BACKGROUND_DEEMPHASIZED",
    "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED", "BOTTOM_SHEET_INSET_BACKGROUND", "POPOVER_BACKGROUND", "FADED_POPOVER_BACKGROUND",
    "NAV_BAR_BACKGROUND", "TAB_BAR_BACKGROUND", "BACKGROUND_BANNER", "BACKGROUND_PRIMARY_UI",
    "TEXT_INPUT_BAR_BACKGROUND", "SECONDARY_BUTTON_BACKGROUND", "SECONDARY_BUTTON_BACKGROUND_OPAQUE",
)

/** The start of each light surface's colour resource name, before the colour's six hex digits: AmoledResources.LIGHT_SURFACE. */
internal const val LIGHT_SURFACE = "hushfacebook_amoled_light_"

/** The colours light mode's surfaces got resources of their own for, as AmoledResources.lightSurfaces lists them. */
internal var lightSurfaceColours = ""

/** The lightest a channel of a white or light grey surface can be no less than: AmoledTheme.LIGHT_MIN_CHANNEL. */
private const val LIGHT_MIN_CHANNEL = 0xC0

/** An opaque white or light grey, as AmoledTheme.isLightNeutral reads one. */
internal fun isLightSurface(colour: Int): Boolean {
    if (colour ushr 24 != 0xFF) return false
    val channels = listOf(colour shr 16 and 0xFF, colour shr 8 and 0xFF, colour and 0xFF)
    return channels.min() >= LIGHT_MIN_CHANNEL && channels.max() - channels.min() <= MAX_SPREAD
}

/**
 * Light mode's half of route two, for the Light background color row (#214). Facebook's light FDS
 * style points its surfaces at the same white and light grey resources its text, icons and dividers
 * read, so a table giving those resources the row's colour would take the rest too. Each item of the
 * light style for a surface ([LIGHT_SURFACE_TOKENS]) whose colour is a white or light grey
 * ([isLightSurface]) points at a resource of its own instead, [LIGHT_SURFACE] and the colour's six
 * hex digits, holding the same colour: a blank row draws what Facebook drew, and AmoledResources'
 * table gives those resources the row's colour. Only an item the dark style sets too, so dark mode
 * and the Video tab never read one, and only a colour read through the default configuration alone,
 * none of whose resources [configured] (any other configuration's) gives a value of its own.
 * [colours] are the default colours by name. Answers each new resource's colour by name.
 */
internal fun repointLightSurfaces(
    styles: Document,
    tokens: Map<String, String>,
    colours: Map<String, String>,
    configured: Set<String>,
): Map<String, Int> {
    val (light, dark) = fdsThemeStyles(styles, tokens) ?: return emptyMap()
    val setInDark = dark.childElements().map { it.getAttribute("name") }.toSet()
    val added = sortedMapOf<String, Int>()
    for (item in light.childElements()) {
        val attribute = item.getAttribute("name")
        if (tokens[attribute] !in LIGHT_SURFACE_TOKENS || attribute !in setInDark) continue
        val colour = defaultColour(item.textContent, colours, configured) ?: continue
        if (!isLightSurface(colour)) continue
        val name = LIGHT_SURFACE + "%06x".format(colour and 0xFFFFFF)
        item.textContent = "@color/$name"
        added[name] = colour
    }
    return added
}

/** Adds each of [surfaces] ([repointLightSurfaces]) to a `colors.xml` [document], with its colour. */
internal fun addLightSurfaceColours(document: Document, surfaces: Map<String, Int>) {
    for ((name, colour) in surfaces) {
        document.documentElement.appendChild(
            document.createElement("color").also {
                it.setAttribute("name", name)
                it.textContent = "#%08x".format(colour)
            },
        )
    }
}

/** AmoledResources.lightSurfaces' list: each of [surfaces]' colours as six hex digits, sorted, `;` between. */
internal fun lightSurfacesTable(surfaces: Map<String, Int>): String =
    surfaces.values.map { "%06x".format(it and 0xFFFFFF) }.distinct().sorted().joinToString(";")

/**
 * Route two's table for AmoledTheme.routeTwoColours: each default colour it wrote black over, with
 * the colour Facebook had there, and each default colour that only points at one of those, as
 * `"id=colour;..."` in hex and sorted by id.
 *
 * Facebook keeps its dark palette in the default configuration: the dark style points at it, and so
 * does the Video tab, which stays dark in light mode. In light mode the extension reads a black from
 * one of these as Facebook's colour again. A colour route two also wrote over at night is left out,
 * since there a black could be the night one, and so is one that was black already.
 */
internal fun routeTwoTable(
    rewritten: Map<String, Int>,
    rewrittenAtNight: Set<String>,
    references: Map<String, String>,
    id: (String) -> Long?,
): String {
    val restorable = rewritten.filter { (name, colour) -> name !in rewrittenAtNight && colour != BLACK }
    val pointing = references.mapNotNull { (name, target) ->
        restorable[target]?.takeIf { name !in rewrittenAtNight }?.let { name to it }
    }
    return (restorable + pointing).mapNotNull { (name, colour) -> id(name)?.let { it to colour } }
        .sortedBy { it.first }
        .joinToString(";") { (id, colour) -> "%x=%08x".format(id, colour) }
}

/**
 * The id in the name the resource decoder gives a colour whose name Facebook stripped, such as
 * `color_0x7f0601f4` for 0x7f0601f4. The patcher's own id table only knows names the APK carries,
 * and Facebook's dark palette carries none.
 */
internal fun decodedColourId(name: String): Long? =
    Regex("color_0x([0-9a-f]{8})").matchEntire(name)?.groupValues?.get(1)?.toLong(16)

/**
 * A resource colour's value as an int, for `#rgb`, `#argb`, `#rrggbb` and `#aarrggbb`, or null for
 * anything else, a reference such as `@color/foo` included.
 */
internal fun argb(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length !in setOf(3, 4, 6, 8)) return null
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null

    // Widen #rgb and #argb to two digits for each channel.
    val wide = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
    return (if (wide.length == 6) "ff$wide" else wide).toLong(16).toInt()
}

/**
 * True when this resource value is an opaque dark grey. A reference such as `@color/foo` gives
 * false, and so does a translucent value, which is a scrim and not a background.
 */
private fun isDarkBackground(value: String): Boolean {
    val colour = argb(value) ?: return false
    if (colour ushr 24 != 0xFF) return false
    return isDarkNeutral((colour shr 16) and 0xFF, (colour shr 8) and 0xFF, colour and 0xFF)
}

@Suppress("unused")
val amoledThemePatch = bytecodePatch(
    name = "AMOLED black theme",
    description = "Makes Facebook's dark mode black, or a dark color you pick, instead of dark gray, which can " +
        "save battery on OLED screens. Cards and menus stay a shade lighter than the page. Turn on Black cards in " +
        "Hushfacebook settings > Appearance to make them black too, and Lines between posts there for a thin gray " +
        "line between posts in the feed. Light background color there gives light mode a soft color in place of " +
        "white. Turn on dark mode in Facebook first. It isn't " +
        "selected by default because undoing it means patching again. Turn on Expert mode in Manager to pick it.",
    default = false,
) {
    category("Theme")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Issue #34. Routes two and three write the colour into resources and code. It's what the
    // Background color row in Hushfacebook's settings starts from, and what a blank row goes back
    // to (#214).
    val backgroundOption by colorOption(
        key = "backgroundColour",
        default = "#000000",
        title = "Background color",
        description = "The color dark mode's backgrounds use, as a six-digit color code (#RRGGBB), like #0D1117. Leave it blank for " +
            "black. Cards and inputs get a slightly lighter shade. Grays up to about $LIGHTEST_ADMITTED_GREY " +
            "work, and lighter colors are refused so text stays readable.",
        required = false,
    ) { backgroundColour(it) != null }
    val background = {
        backgroundColour(backgroundOption)
            ?: throw PatchException(backgroundProblem(backgroundOption) ?: "Background color $backgroundOption can't be used")
    }

    dependsOn(amoledThemeResourcePatch(background))

    dependsOn(facebookExtensionPatch)

    execute {
        // Facebook's own answer for whether its dark mode is on, which every route asks: the Video
        // tab stays dark in light mode, from the same dark style and colour resources.
        hookDarkModeAnswer()

        // Route one. Four methods and six returns. Each hook keeps the body of the method and
        // sends the value through the extension before the method returns it.
        hookColourResolvers(mig = APPLY, fds = APPLY)
        // Mig's light scheme answers light mode's older screens, which the Light background color
        // row moves off white (#214). A build without it keeps the rest of the theme.
        try {
            hookLightSchemeResolver()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. Light background color leaves Mig's light screens white.")
        } catch (moved: IllegalStateException) {
            patchLog.warning("${moved.message}. Light background color leaves Mig's light screens white.")
        }
        fillBackgroundColour(background())
        // Issue #34: a lighter colour's surfaces step up less, and its text roles get lifted.
        fillLightestStep(checkNotNull(lightestStep(background())))

        // Data mode's banner on Flex carriers asks for a card's colour, so route one left it near
        // black across the black page (issue #86). The page's own colour goes there instead. A build
        // without the banner, or one that draws it another way, keeps the rest of the theme.
        try {
            hookFlexBanner()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the Data mode banner.")
        }

        // The system bars. A tab's bar colour can come from a resolver route one doesn't reach, or
        // be written in code for both themes, so the methods that paint the bars ask the extension
        // first, with Facebook's answer for whether the theme is dark (issue #22).
        val darkCheck = fdsDarkCheck()
        hookStatusBarColour(darkCheck)
        hookNavigationBarColour(darkCheck)

        // Route three. The palette tables, the top bar of the feed and each Litho component that
        // draws its own chrome all write a colour instead of asking for one, so no resolver and no
        // resource reaches them.
        // Each reads AmoledTheme's page colour, so the Background color row reaches it (#214).
        checkPageFields()
        check(blackenColourLiterals() > 0) { "No dark colour written in code, so the chrome would stay grey" }

        // The colours Facebook writes for its dark theme only, past route three's greys: the Create
        // story tile's #333334 stayed grey with Black cards on (#191). Material You reads the same
        // sites after this and puts its stand-in after AMOLED's, so it gets AMOLED's answer.
        if (readDarkBranchLiterals(darkCheck, AMOLED_DARK_BRANCH) == 0) {
            patchLog.warning("No colour written on Facebook's dark branch found, so Black cards leaves the Create story tile grey")
        }

        // The colour resource FDS's token lookup answers, which the code then reads itself: the band
        // behind the Menu's Log out button is a drawable of CARD_BACKGROUND_FLAT's (#191). Material
        // You hooks the same returns after this, so it notes the resource AMOLED answered.
        try {
            fdsTokenResourceLookup().hookTokenResourceReturns(TOKEN_RESOURCE_DARK)
        } catch (moved: IllegalStateException) {
            patchLog.warning("${moved.message}. Black cards leaves the band behind the Menu's Log out button grey.")
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. Black cards leaves the band behind the Menu's Log out button grey.")
        }

        // Route four. The server sends some colours as strings, and the app parses them with
        // Color.parseColor. Each of those calls goes to the extension instead, and so does each
        // read of a colour resource, where light mode gets back what route two wrote black over.
        val rerouted = rerouteColourCalls(AMOLED_COLOUR_CALLS)
        check(rerouted.getValue(PARSE_COLOR) > 0) { "No call to Color.parseColor found, so server colours would stay grey" }
        check(rerouted.getValue(CONTEXT_GET_COLOR) > 0) {
            "No call to Context.getColor found, so light mode's Video tab would keep route two's black"
        }
        check(routeTwoRestores.isNotEmpty()) { "Route two's table is empty, so light mode's Video tab would stay black" }
        fillRouteTwoTable(routeTwoRestores)

        // The Background color row (#214): AmoledTheme reads it as Facebook starts, right after the
        // extension gets its context, and the resources route two wrote over take it per activity.
        loadBackgroundRow()
        if (writtenColours.isEmpty()) {
            patchLog.warning("No colour route two wrote has a resource id, so the Background color row leaves layouts the patched colour")
        } else {
            fillWrittenColours(writtenColours)
        }
        // And light mode's surfaces, which take the Light background color row's colour the same way.
        if (lightSurfaceColours.isEmpty()) {
            patchLog.warning("No light surface got a resource of its own, so Light background color leaves layouts' surfaces white")
        } else {
            fillLightSurfaces(lightSurfaceColours)
        }

        // React Native screens such as Marketplace home, whose colours come from their JavaScript
        // as ints on props, past every route.
        hookReactColours()

        // The fill of each Bloks box and native template box, the server colours that say they're a
        // background: in light mode Settings' page and the Menu's profile card take the Light
        // background color (#214), where parseColor can't tell a white page from a white label.
        // Accent color hooks the same box builder.
        try {
            hookBloksFills(AMOLED_BLOKS_FILL)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. Light background color leaves server screens like Settings white.")
        }
        try {
            hookNativeTemplateBackgrounds()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. Light background color leaves native template cards like the Menu's profile card white.")
        }

        // The older comment row's time, whose slot drew blank on a black sheet (#138). Material You
        // calls the same hook and finds it in place.
        try {
            hookCommentTimeColour()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the comment time's colour check.")
        }

        // The ring between a post's overlapping reaction icons, painted white on a black page
        // (#147). Material You calls the same hook and finds it in place.
        try {
            hookReactionRing()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the reaction ring's cut.")
        }

        // The other facepile, the reactions token pile, which paints its discs in a colour resolved
        // on its own context (#147). Material You calls the same hook and finds it in place.
        try {
            hookReactionPile()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without the reactions token pile's page colour.")
        }

        // The gap between a list's rows, which shows the page and so goes black with it (#206).
        // Each Litho row paints it gray first as it draws, with Lines between posts on.
        try {
            hookFeedGaps()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The theme goes on without Lines between posts.")
        }

        enableStatus("amoledTheme")
    }
}

/** Gets the banner wrapper's colour after route one. Gives the colour to paint. */
internal const val FLEX_BANNER = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->flexBanner(I)I"

/** Gets a colour Facebook writes on its dark branch only, for Black cards (#191). Gives the colour to paint. */
internal const val AMOLED_DARK_BRANCH = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->darkBranch(I)I"

/** Gets a Bloks box's fill, for the Light background color row (#214). Gives the colour to fill with. */
internal const val AMOLED_BLOKS_FILL = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->bloksFill(I)I"

private const val COLOR_DRAWABLE_INIT = "Landroid/graphics/drawable/ColorDrawable;-><init>(I)V"

/**
 * Native Templates' backgrounds (#214). The Menu's profile card is a native template, whose box
 * hands its server colour to Litho's backgroundColor (584 `LX/3T3;->A1H(I)V`) and never reaches the
 * box builder ([hookBloksFills]). [NativeTemplateBuilderFingerprint]'s builder and the child builder
 * it calls with its own parameters (584 `LX/4Kd;->A00`) each set a background colour that way, and
 * each of those colours goes through [target] just before the call.
 *
 * 585 (arm64, read 2026-10-30) made the builder a static `LX/04d0;->A00(LX/01PR;LX/062H;LX/04e3;I)LX/03G2;`
 * that its instance entry calls with the node's kind as a trailing int. The child builder
 * `LX/04d2;->A00(LX/01PR;LX/062H;LX/04e3;)LX/03G2;` takes the rest, and backgroundColor is `LX/03G2;->A0e(I)V`.
 *
 * 586 (arm64, read 2026-11-09) made the builder an instance method again (`LX/04cP;->Him`), and its
 * child builder `LX/04cS;->A00` sets no background itself: it builds the style's component and hands
 * it to a static decorator, `LX/05HY;->A01(LX/03Ej;...)LX/03Ej;`, which sets it. So the statics a
 * child builder hands a component to and gets one back from are hooked too ([decoratorsOf]). The
 * decorator's other caller is the template's photo component, also a Native Template.
 */
internal fun BytecodePatchContext.hookNativeTemplateBackgrounds(target: String = AMOLED_BLOKS_FILL) {
    val builder = NativeTemplateBuilderFingerprint.method
    val parameters = builder.parameterTypes.map(CharSequence::toString)
    // Why: 585's builder takes the node's kind as a trailing int that its child builder doesn't take.
    val childShapes = setOf(parameters, parameters.dropLastWhile { it == "I" })
    val children = builder.implementation!!.instructions
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.returnType == builder.returnType && it.parameterTypes.map(CharSequence::toString) in childShapes }
        // A static builder can call itself, and it's hooked once already.
        .filterNot {
            it.definingClass == builder.definingClass && it.name == builder.name &&
                it.parameterTypes.map(CharSequence::toString) == parameters
        }
        .distinctBy { it.toString() }
        .map { child -> mutableMethodOf(child) }
    val decorators = children.flatMap { decoratorsOf(it, builder.returnType) }.distinctBy { it.toString() }
        .filterNot { decorator ->
            children.any {
                it.definingClass == decorator.definingClass && it.name == decorator.name &&
                    it.parameterTypes.map(CharSequence::toString) == decorator.parameterTypes.map(CharSequence::toString)
            }
        }
        .map { mutableMethodOf(it) }
    val hooked = (listOf(builder) + children + decorators).sumOf { hookBackgroundColours(it, target) }
    if (hooked == 0) throw PatchException("Native Templates' builders set no background colour")
}

/** The statics [method] hands a [component] to and gets one back from (586 `LX/05HY;->A01`). */
internal fun decoratorsOf(method: Method, component: String): List<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.returnType == component && it.parameterTypes.firstOrNull()?.toString() == component }
        .distinctBy { it.toString() }

private fun BytecodePatchContext.mutableMethodOf(call: MethodReference): MutableMethod =
    mutableClassDefBy(call.definingClass).methods.single { method ->
        method.name == call.name && method.returnType == call.returnType &&
            method.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
    }

/**
 * Sends each colour [method] hands Litho's backgroundColor ([isBackgroundColour]) through [target]
 * just before the call, at the call's label, so a jump to the call takes the colour through too.
 * Gives how many calls it hooked. Last first, as each insert moves the later indices.
 */
private fun BytecodePatchContext.hookBackgroundColours(method: MutableMethod, target: String): Int {
    val sites = method.implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        if (!isBackgroundColour(call)) null else index to (instruction as FiveRegisterInstruction).registerD
    }
    sites.asReversed().forEach { (index, colour) ->
        method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$colour .. v$colour }, $target
                move-result v$colour
            """,
        )
    }
    return sites.size
}

/**
 * Litho's backgroundColor (584 `LX/3T3;->A1H(I)V`, and `A1J` for the pressed state's): an `(I)V`
 * method of the builder, or a class above it, that wraps its int in a ColorDrawable.
 */
private fun BytecodePatchContext.isBackgroundColour(call: MethodReference): Boolean {
    if (call.returnType != "V" || call.parameterTypes.map(CharSequence::toString) != listOf("I")) return false
    val method = superclassChain(call.definingClass).firstNotNullOfOrNull { type ->
        classDefByOrNull(type)?.methods?.firstOrNull { it.name == call.name && it.parameterTypes.map(CharSequence::toString) == listOf("I") }
    } ?: return false
    return method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == COLOR_DRAWABLE_INIT
    } == true
}

/** The FDS token the Data mode banner paints its wrapper with. */
private const val CARD_BACKGROUND = "CARD_BACKGROUND"

/**
 * Issue #86: the Data mode banner of Facebook Flex ([FlexBannerFingerprint]) reads the
 * CARD_BACKGROUND constant of FDS's token enum, hands it to a (Context, token) resolver, and paints
 * the strip with the answer. That answer goes through [FLEX_BANNER] before the banner uses it. The
 * token is found by the constant's name in the enum's static initializer, and the call by its shape.
 */
internal fun BytecodePatchContext.hookFlexBanner() {
    val method = FlexBannerFingerprint.methodOrNull
        ?: throw PatchException("Facebook Flex's Data mode banner isn't in this build")
    val code = method.implementation!!.instructions.toList()
    val read = code.indexOfFirst { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.SGET_OBJECT && field != null && field.type == field.definingClass &&
            classDefByOrNull(field.definingClass)?.let { enumConstant(it, CARD_BACKGROUND) } == field.toString()
    }
    if (read < 0) throw PatchException("The Data mode banner reads no $CARD_BACKGROUND token")
    val token = (code[read] as OneRegisterInstruction).registerA
    val enum = ((code[read] as ReferenceInstruction).reference as FieldReference).type

    val call = (read + 1 until code.size).firstOrNull { index ->
        val reference = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode == Opcode.INVOKE_STATIC && reference != null && reference.returnType == "I" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, enum) &&
            (code[index] as? FiveRegisterInstruction)?.registerD == token
    } ?: throw PatchException("The Data mode banner hands its $CARD_BACKGROUND token to no (Context, token) resolver")
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException("The Data mode banner's resolver call isn't followed by its move-result")
    }
    val colour = (result as OneRegisterInstruction).registerA
    method.addInstructions(
        call + 2,
        """
            invoke-static/range { v$colour .. v$colour }, $FLEX_BANNER
            move-result v$colour
        """,
    )
}

/** Litho's host view. Each row Litho puts in a list is one, a LithoView, and draws through its dispatchDraw. */
internal const val COMPONENT_HOST = "Lcom/facebook/litho/ComponentHost;"

private const val CANVAS = "Landroid/graphics/Canvas;"

/** Gets each Litho host and its canvas once the host has drawn everything, for Lines between posts (#206). */
internal const val FEED_GAP_ROW = "Lapp/morphe/extension/facebook/theme/FeedGaps;->drawRow(Landroid/view/ViewGroup;$CANVAS)V"

/** Whether [method] is the host's own dispatchDraw(Canvas), which LithoView inherits. */
internal fun isHostDraw(method: Method) =
    method.name == "dispatchDraw" && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) &&
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null

/**
 * Issue #206: a feed row's content stops a few pixels above the row's bottom, and that strip shows
 * the row's own background, in the page's colour that AMOLED makes black. Litho draws a host's
 * drawables inside its dispatchDraw, around and after its children, so [FEED_GAP_ROW] runs before
 * each return, over all of it. The name is Android's, so Redex keeps it.
 */
internal fun BytecodePatchContext.hookFeedGaps() {
    val host = mutableClassDefByOrNull(COMPONENT_HOST) ?: throw PatchException("This build has no Litho $COMPONENT_HOST")
    val draws = host.methods.filter(::isHostDraw)
    val draw = draws.singleOrNull()
        ?: throw PatchException("Expected $COMPONENT_HOST to declare one dispatchDraw($CANVAS)V, found ${draws.size}")
    val code = draw.implementation!!.instructions.toList()
    // The host and the canvas are p0 and p1, next to each other, so the range form passes both
    // without borrowing a register, as long as the method never reuses either.
    val self = draw.implementation!!.registerCount - 2
    code.firstOrNull { writesRegister(it, self) || writesRegister(it, self + 1) }?.let {
        throw PatchException("$COMPONENT_HOST's dispatchDraw writes its host or canvas register with ${it.opcode.name}") // opcode-name-ok
    }
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) throw PatchException("$COMPONENT_HOST's dispatchDraw never returns")
    // At the label, so a branch to the return draws the line too. Last first, as each insert moves
    // the later indices.
    returns.asReversed().forEach { draw.addInstructionsAtControlFlowLabel(it, "invoke-static/range { p0 .. p1 }, $FEED_GAP_ROW") }
}

/**
 * Route one: the Mig dark scheme's resolver sends its colours to [mig], and the FDS resolvers send
 * theirs to [fds]: each resolver on FDSColors and the [fdsThemeResolver]. Both themes run it, AMOLED
 * from its execute block and Material You after it.
 */
internal fun BytecodePatchContext.hookColourResolvers(mig: String, fds: String) {
    DarkSchemeResolveFingerprint.method.hookColorReturns(tokenParameterIndex = 0, target = mig)
    hookFdsColorsResolvers(target = fds)
    fdsThemeResolver().hookColorReturns(tokenParameterIndex = 1, target = fds)
}

/**
 * The Mig light scheme's resolver sends its colours to [APPLY_LIGHT], which leaves them alone until
 * the Light background color row is set and Facebook is in light mode (#214). AMOLED only: Material
 * You's light scheme is its own.
 */
internal fun BytecodePatchContext.hookLightSchemeResolver() {
    LightSchemeResolveFingerprint.method.hookColorReturns(tokenParameterIndex = 0, target = APPLY_LIGHT)
}

/**
 * The resolver of the view code, which takes a Context and the FDS token. It has a Redex name, thus
 * its descriptor comes from the wrapper in `FdsColorScheme` that calls it. It hands both on to the
 * [fdsThemeResolver], and its class holds FDS's dark check.
 */
internal fun BytecodePatchContext.fdsViewResolver(): MutableMethod {
    val resolverCall = FdsSchemeResolveFingerprint.instructionMatches[1].instruction
    val resolver = (resolverCall as ReferenceInstruction).reference as MethodReference

    // If Facebook moves the resolver into the wrapper, this reaches 1 caller and not 894.
    check(resolver.definingClass.toString() != FDS_COLOR_SCHEME) {
        "The FDS colour resolver is now inside FdsColorScheme. Find the seam again."
    }

    return mutableClassDefBy(resolver.definingClass.toString()).methods.single {
        it.name == resolver.name &&
            it.returnType == "I" &&
            it.parameterTypes.map(CharSequence::toString) ==
            resolver.parameterTypes.map(CharSequence::toString)
    }
}

/**
 * The FDS theme resolver: the one method the [fdsViewResolver] calls that answers an int, taking the
 * same Context and token. The view resolver only checks the context and returns its answer, and
 * most of the view code calls it directly, the code that makes the system bars' own views on
 * Android 15 and newer included: the navigation bar is made with NAV_BAR_BACKGROUND's colour from
 * here (on 577, through a static that returns its answer). So route one hooks this and not the
 * view resolver, and every colour the view resolver gave still goes through the hook, once.
 */
internal fun BytecodePatchContext.fdsThemeResolver(): MutableMethod {
    val viewResolver = fdsViewResolver()
    val calls = viewResolver.implementation!!.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.returnType == "I" }
    val resolver = calls.singleOrNull() ?: throw PatchException(
        "The FDS view resolver calls ${calls.size} methods that answer an int, expected the theme resolver",
    )
    val parameters = viewResolver.parameterTypes.map(CharSequence::toString)
    if (resolver.parameterTypes.map(CharSequence::toString) != parameters) {
        throw PatchException("The FDS theme resolver $resolver doesn't take the view resolver's $parameters")
    }
    return mutableClassDefBy(resolver.definingClass.toString()).methods.single {
        it.name == resolver.name && it.returnType == "I" && it.parameterTypes.map(CharSequence::toString) == parameters
    }
}

/**
 * FDS's test for a dark theme, as a method descriptor: the one static method on the view resolver's
 * class that takes a Context and answers a boolean. It has a Redex name and no literal. The class's
 * own colour picker asks the same question before it takes a colour's `darkThemeColor`, and
 * SystemBarsController asks this method for the bar's icons.
 */
internal fun BytecodePatchContext.fdsDarkCheck(): String {
    val resolverClass = fdsViewResolver().definingClass
    val checks = classDefBy(resolverClass).methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)
    }
    val check = checks.singleOrNull() ?: throw PatchException(
        "The FDS view resolver's class has ${checks.size} static (Context) boolean methods, expected its one dark check",
    )
    return "${check.definingClass}->${check.name}($CONTEXT)Z"
}

/**
 * The method that paints the status bar: StatusBarUtil's one static `(Window, int)` method that
 * calls `Window.setStatusBarColor`. The other static `(Window, int)` method sets the bar's icons
 * and paints nothing.
 */
internal fun BytecodePatchContext.statusBarPainter(): MutableMethod {
    val painters = mutableClassDefBy(STATUS_BAR_UTIL).methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/Window;", "I") &&
            method.implementation?.instructions?.any {
                (it as? ReferenceInstruction)?.reference?.toString() == SET_STATUS_BAR_COLOR
            } == true
    }
    return painters.singleOrNull() ?: throw PatchException(
        "StatusBarUtil has ${painters.size} static (Window, int) methods that call setStatusBarColor, expected one",
    )
}

/**
 * The method that paints the navigation bar: [NavigationBarPainterFingerprint], which has to match
 * one method only.
 */
internal fun BytecodePatchContext.navigationBarPainter(): MutableMethod {
    val painters = NavigationBarPainterFingerprint.matchAllOrNull().orEmpty()
    return painters.singleOrNull()?.method ?: throw PatchException(
        "${painters.size} static (Activity, Window, int) methods call setNavigationBarColor, expected one",
    )
}

/**
 * Sends the colour of the status bar through the extension method [target], first thing in the
 * [statusBarPainter]. [target] takes the colour and whether the theme is dark, and gives the colour
 * to paint: AmoledTheme.statusBar here, MaterialYouTheme.statusBar when Material You is in the build
 * without AMOLED.
 *
 * On Android 15 and newer the framework ignores `setStatusBarColor` for Facebook's target SDK, so
 * that method paints a view behind the bar as well. It remembers the last colour per window and
 * skips a colour it already painted. The hook goes in before that cache, so what it remembers is
 * what the extension answered. The extension recolours only in the dark theme, [darkCheck]
 * answering for the window's context, because light mode asks the same tokens for the same dark
 * greys.
 */
internal fun BytecodePatchContext.hookStatusBarColour(darkCheck: String, target: String = STATUS_BAR) =
    statusBarPainter().hookBarColour("Status bar colour", window = 0, colour = 1, darkCheck, target)

/**
 * The same for the [navigationBarPainter], whose window is its second parameter and colour its last
 * parameter. [target] is AmoledTheme.navigationBar here, MaterialYouTheme.navigationBar when
 * Material You is in the build without AMOLED.
 */
internal fun BytecodePatchContext.hookNavigationBarColour(darkCheck: String, target: String = NAVIGATION_BAR) =
    navigationBarPainter().let { it.hookBarColour("Navigation bar colour", window = 1, colour = navigationBarColourIndex(it), darkCheck, target) }

/**
 * Puts the bar hook first in this painter: the colour in parameter [colour] goes through [target]
 * with [darkCheck]'s answer for the context of the window in parameter [window].
 *
 * `invoke` names its registers in four bits, so the window and the colour are copied down into two
 * locals first, and the answer goes back into the colour's own parameter register.
 */
private fun MutableMethod.hookBarColour(what: String, window: Int, colour: Int, darkCheck: String, target: String) {
    requireLocals(what, 2)
    addInstructions(
        0,
        """
            move-object/from16 v0, p$window
            invoke-virtual { v0 }, $WINDOW->getContext()Landroid/content/Context;
            move-result-object v0
            invoke-static { v0 }, $darkCheck
            move-result v0
            move/from16 v1, p$colour
            invoke-static { v1, v0 }, $target
            move-result p$colour
        """,
    )
}

/**
 * The methods that hand a colour to a system bar: each one that calls the [statusBarPainter], the
 * [navigationBarPainter] or a method of its class that passes a colour on to it, or makes the config
 * SystemBarsController applies, whose colour the controller hands to the status bar's painter.
 *
 * Route three leaves their colours as Facebook wrote them, for the painters' hooks to decide. The
 * Video tab keeps a dark surface in light mode as well, and writes its bars' #252728 into code for
 * both themes. A literal can't say which theme is on, and the hooks ask Facebook. A static helper
 * answering an int, whose answer a painter's caller hands straight to the painter, is one of them
 * too ([barColourHelpers]).
 *
 * 585, read 2026-10-30: apply is the static `A01(Window, LX/02Xv, SystemBarsController)` on arm64,
 * the receiver trailing, and still calls `StatusBarUtil.A0A`. The Video tab's #252728 left
 * VideoHomeRootFragment for `LX/0Ao9;->A00(LX/0DhY;Z)I`, which answers it or black; the fragment's
 * `A0K` and `A0P` hand that answer to `StatusBarUtil.A0A`, and `LX/0Ao9;->A02` builds the tab's
 * config from it.
 */
internal fun BytecodePatchContext.systemBarColourMethods(): (Method) -> Boolean {
    fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
    fun Method.calls(descriptors: Set<String>) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() in descriptors
    } == true

    val status = statusBarPainter().descriptor()
    val navigation = navigationBarPainter()
    val passesOn = classDefBy(navigation.definingClass).methods
        .filter { it.parameterTypes.lastOrNull()?.toString() == "I" && it.calls(setOf(navigation.descriptor())) }
    val painters = passesOn.map { it.descriptor() }.toSet() + navigation.descriptor() + status

    val applies = classDefBy(SYSTEM_BARS_CONTROLLER).methods.filter { method ->
        val types = method.parameterTypes.map(CharSequence::toString)
        // Why: 585 made apply static, (Window, config, SystemBarsController), the controller trailing.
        types.size >= 2 && types[0] == WINDOW && types[1] != SYSTEM_BARS_CONTROLLER &&
            types.drop(2).all { it == SYSTEM_BARS_CONTROLLER } && types.size <= 3 && method.calls(setOf(status))
    }
    val config = applies.singleOrNull()?.parameterTypes?.get(1)?.toString() ?: throw PatchException(
        "SystemBarsController has ${applies.size} (Window, config[, SystemBarsController]) methods that paint the status bar, expected one",
    )

    // Why: 585 moved the Video tab's bar grey into a static int helper the tab hands to the painter.
    val helpers = mutableSetOf<String>()
    classDefForEachReferencing(types = painters.map { it.substringBefore("->") }.distinct()) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filter { it.calls(painters) }.forEach { helpers += barColourHelpers(it, painters) }
    }

    return { method ->
        method.calls(painters) || method.descriptor() in helpers || method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == config
        } == true
    }
}

/**
 * The static methods answering an int that [method] calls and hands the answer of, as it is, to one
 * of [painters]: a bar colour Facebook keeps in a helper of its own, the way 585's Video tab does.
 * The sweep skips a helper for all its callers, so ColourResolverFixtureTest holds the helpers found
 * that write a dark grey to the Video tab's.
 */
internal fun barColourHelpers(method: Method, painters: Set<String>): Set<String> {
    val code = method.implementation?.instructions?.toList() ?: return emptySet()
    return code.indices.filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        (code[index].opcode == Opcode.INVOKE_STATIC || code[index].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call?.returnType == "I" && code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT &&
            method.literalReads(index + 1).any { use ->
                ((code[use] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() in painters
            }
    }.map { ((code[it] as ReferenceInstruction).reference as MethodReference).toString() }.toSet()
}

/**
 * Hooks every FDS colour resolver on `FDSColors`: each instance method answering an int that takes
 * the colour token and asks the Integer-answering source itself.
 *
 * <p>577 had two of them, one for Litho and one from a Context. 580 split the Litho one into a
 * thin wrapper and a second Context resolver, so a fingerprint per resolver matched the wrong one
 * or none. The token type is read off the source's own signature, the one static method answering
 * `Integer` from a Context, the token and a palette. 586 (arm64 `LX/02LL`, read 2026-11-09) merged
 * the two Context resolvers into one (`A0A`), and the Litho one (`A0B`) only returns that one's
 * answer, so one resolver is hooked there and every colour still passes it.
 */
internal fun BytecodePatchContext.hookFdsColorsResolvers(target: String) {
    val type = fdsColors()
    val colors = mutableClassDefBy(type)
    val tokenType = fdsColourSource(colors)!!.parameterTypes[1].toString()

    val resolvers = colors.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
            method.parameterTypes.any { it.toString() == tokenType } &&
            method.implementation?.instructions?.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference != null && reference.definingClass == type && reference.returnType == "Ljava/lang/Integer;"
            } == true
    }
    check(resolvers.isNotEmpty()) { "No FDSColors resolver asks the Integer colour source" }
    resolvers.forEach { method ->
        method.hookColorReturns(
            tokenParameterIndex = method.parameterTypes.indexOfFirst { it.toString() == tokenType },
            target = target,
        )
    }
}

/**
 * Where route three reads the page's colour (issue #214): AmoledTheme's fields, which hold the
 * Background colour option's colour until the Background color row puts another in as Facebook
 * starts. A colour Facebook keeps in a long reads the long one.
 */
internal const val PAGE_FIELD = "$AMOLED->PAGE:I"
internal const val PAGE_WIDE_FIELD = "$AMOLED->PAGE_WIDE:J"

/** Checks AmoledTheme has the static fields [blackenColourLiterals] reads, as Material You checks its own. */
internal fun BytecodePatchContext.checkPageFields() {
    val fields = classDefBy(AMOLED).fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) }.map { "${it.name}:${it.type}" }
    listOf(PAGE_FIELD, PAGE_WIDE_FIELD).map { it.substringAfter("->") }.forEach { field ->
        if (field !in fields) throw PatchException("$AMOLED has no static $field for route three to read")
    }
}

/**
 * Route three over the whole app: each dark grey written in code reads the page's colour instead
 * ([PAGE_FIELD]), except in the [systemBarColourMethods]. The sweep reads every class and rewrites
 * only the classes that hold one, which takes about 30 seconds. Answers how many it rewrote.
 *
 * The extension is skipped: its greys are its own, set a step above the page on purpose. Swept,
 * Lines between posts drew its #1D1E23 as black on black on the S22 (#206), and Black cards' button
 * fill, #262627, became the page's colour (#191).
 */
internal fun BytecodePatchContext.blackenColourLiterals(): Int {
    val handsToBar = systemBarColourMethods()
    val owners = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        if (classDef.methods.any { it.hasDarkColor() }) owners += classDef.type
    }
    return owners.sumOf { type ->
        mutableClassDefByOrNull(type)?.methods?.sumOf { if (handsToBar(it)) 0 else it.blackenDarkColors() } ?: 0
    }
}

/** True when this instruction writes an opaque dark grey. */
private fun Instruction.isDarkColor(): Boolean {
    if (this !is NarrowLiteralInstruction || this !is OneRegisterInstruction) return false
    if (narrowLiteral == BLACK || (narrowLiteral ushr 24) != 0xFF) return false
    return isDarkNeutral(
        (narrowLiteral shr 16) and 0xFF,
        (narrowLiteral shr 8) and 0xFF,
        narrowLiteral and 0xFF,
    )
}

/** Select owners and names cheaply before decoding a candidate's full overload descriptor. */
private class ColourCallTargets(val calls: Map<String, String>) {
    private val names = calls.keys.groupBy { it.substringBefore("->") }.mapValues { (_, signatures) ->
        signatures.map { it.substringAfter("->").substringBefore('(') }.toSet()
    }
    val owners get() = names.keys

    fun matching(instruction: Instruction): String? {
        // Moving a super call into the extension would dispatch back to the override.
        when (instruction.opcode) {
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> Unit
            else -> return null
        }
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
        if (reference.name !in (names[reference.definingClass] ?: return null)) return null
        return reference.toString().takeIf { it in calls }
    }
}

/**
 * Route four and route two's read back, over the whole app: each call to a key of [calls] goes to
 * the extension method it maps to instead. Answers how many calls it sent, for each key.
 *
 * The reference index skips classes that name none of the call owners. Only exact calls change. The
 * extension is skipped, because its methods make the framework calls themselves.
 */
internal fun BytecodePatchContext.rerouteColourCalls(calls: Map<String, String>): Map<String, Int> {
    if (calls.isEmpty()) return emptyMap()
    val targets = ColourCallTargets(calls)
    val owners = mutableSetOf<String>()
    classDefForEachReferencing(types = targets.owners) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        if (classDef.methods.any { method -> method.implementation?.instructions?.any { targets.matching(it) != null } == true }) {
            owners += classDef.type
        }
    }
    val counts = calls.keys.associateWith { 0 }.toMutableMap()
    owners.forEach { type -> mutableClassDefByOrNull(type)?.methods?.forEach { it.rerouteColourCalls(targets, counts) } }
    return counts
}

/**
 * Sends each call to a key of [calls] in this method to the extension method it maps to, and counts
 * it in [counts].
 *
 * The stand-in is static, takes the object a virtual call was made on as its first parameter and
 * has the call's own parameters after it, so it reads the same registers in the same order and the
 * `move-result` that follows stays correct. A call in the range form stays in the range form,
 * because its registers can be above v15.
 */
internal fun MutableMethod.rerouteColourCalls(calls: Map<String, String>, counts: MutableMap<String, Int>) {
    rerouteColourCalls(ColourCallTargets(calls), counts)
}

private fun MutableMethod.rerouteColourCalls(targets: ColourCallTargets, counts: MutableMap<String, Int>) {
    val sites = (implementation ?: return).instructions.withIndex()
        .mapNotNull { (index, instruction) -> targets.matching(instruction)?.let { Triple(index, instruction, it) } }

    sites.asReversed().forEach { (index, instruction, framework) ->
        val target = targets.calls.getValue(framework)
        val call = when (instruction) {
            is RegisterRangeInstruction -> {
                val last = instruction.startRegister + instruction.registerCount - 1
                "invoke-static/range { v${instruction.startRegister} .. v$last }, $target"
            }
            is FiveRegisterInstruction -> {
                val registers = listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG).take(instruction.registerCount)
                "invoke-static { ${registers.joinToString { "v$it" }} }, $target"
            }
            else -> error("$definingClass->$name: unexpected call form ${instruction.opcode}")
        }
        replaceInstruction(index, call)
        counts[framework] = counts.getValue(framework) + 1
    }
}

/** Puts the Background colour option's [colour] in AmoledTheme.backgroundColour, for routes one and four and the bars. */
internal fun BytecodePatchContext.fillBackgroundColour(colour: Int) {
    val stub = mutableClassDefBy(AMOLED).methods.singleOrNull {
        it.name == "backgroundColour" && it.parameterTypes.isEmpty() && it.returnType == "I"
    } ?: throw PatchException("AmoledTheme has no backgroundColour() for the Background colour option")
    stub.returnEarly(colour)
}

/**
 * Puts [step] in AmoledTheme.lightestStep, the step of the lightest surface over the Background
 * colour. The stub already answers [LIGHTEST_STEP], so black and the colours taken before #34's
 * lighter ones leave it as it is and the extension does exactly what it did.
 */
internal fun BytecodePatchContext.fillLightestStep(step: Int) {
    if (step == LIGHTEST_STEP) return
    val stub = mutableClassDefBy(AMOLED).methods.singleOrNull {
        it.name == "lightestStep" && it.parameterTypes.isEmpty() && it.returnType == "I"
    } ?: throw PatchException("AmoledTheme has no lightestStep() for a lighter Background colour")
    stub.returnEarly(step)
}

private const val AMOLED_RESOURCES = "Lapp/morphe/extension/facebook/theme/AmoledResources;"

/** Puts [table] ([writtenColoursTable]) in AmoledResources.writtenColours, for the Background color row. */
internal fun BytecodePatchContext.fillWrittenColours(table: String) {
    val stub = mutableClassDefBy(AMOLED_RESOURCES).methods.singleOrNull {
        it.name == "writtenColours" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("AmoledResources has no writtenColours() for the Background color row")
    stub.returnEarly(table)
}

/** Puts [table] ([lightSurfacesTable]) in AmoledResources.lightSurfaces, for the Light background color row. */
internal fun BytecodePatchContext.fillLightSurfaces(table: String) {
    val stub = mutableClassDefBy(AMOLED_RESOURCES).methods.singleOrNull {
        it.name == "lightSurfaces" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("AmoledResources has no lightSurfaces() for the Light background color row")
    stub.returnEarly(table)
}

/**
 * Calls AmoledTheme.load as Facebook starts ([callAsFacebookStarts]): the Background color row's
 * colour is in place before Facebook's own startup reads a colour.
 */
internal fun BytecodePatchContext.loadBackgroundRow() = callAsFacebookStarts("$AMOLED->load()V")

/**
 * Calls [target], a static method with no parameters, in the application's onCreate right after the
 * extension's setContext, which [facebookExtensionPatch] puts first: before Facebook's own startup,
 * which builds Home's feed on the application's resources, reads a colour.
 */
internal fun BytecodePatchContext.callAsFacebookStarts(target: String) {
    val setContext = "$EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V"
    val onCreate = declaredInHierarchy(FACEBOOK_APPLICATION, "onCreate")
    val index = onCreate.implementation!!.instructions.indexOfFirst {
        (it as? ReferenceInstruction)?.reference?.toString() == setContext
    }
    if (index < 0) throw PatchException("$FACEBOOK_APPLICATION.onCreate doesn't hand the extension its context")
    onCreate.addInstruction(index + 1, "invoke-static {}, $target")
}

/** Puts route two's [table] in AmoledTheme.routeTwoColours, for light mode to read colours back. */
internal fun BytecodePatchContext.fillRouteTwoTable(table: String) {
    val stub = mutableClassDefBy(AMOLED).methods.singleOrNull {
        it.name == "routeTwoColours" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("AmoledTheme has no routeTwoColours() for route two's table")
    stub.returnEarly(table)
}

/**
 * Sends each answer of Facebook's dark mode controller through [DARK_MODE_ANSWER], right before
 * [DarkModeFingerprint]'s method returns it. Facebook asks that method as each activity applies its
 * theme, and again after the setting or the system's night mode changes, so the extension always
 * has its latest answer. Both themes call this, and the second finds the hook already there.
 */
internal fun BytecodePatchContext.hookDarkModeAnswer() {
    val method = DarkModeFingerprint.method
    if (method.implementation!!.instructions.any { (it as? ReferenceInstruction)?.reference?.toString() == DARK_MODE_ANSWER }) {
        return
    }
    method.hookReturns("dark mode answer") { register -> "invoke-static { v$register }, $DARK_MODE_ANSWER" }
}

/** True when this method writes a dark grey. It only reads, thus it needs no proxy of the class. */
internal fun Method.hasDarkColor(): Boolean =
    implementation?.instructions?.any { it.isDarkColor() } == true

/**
 * Replaces each dark grey that this method writes with a read of the page's colour, into the same
 * register. Answers how many it replaced.
 *
 * A `const-wide/32` carries its value as a narrow literal too, so a colour kept in a long matches.
 * It reads [PAGE_WIDE_FIELD] with `sget-wide`: an int in its place leaves the long's upper register
 * unset, and ART rejects the whole class ("register v0 has type IntegerConstant but expected
 * Long (Low Half)", a static initializer on 580 with every patch on). The long holds the colour as
 * `const-wide/32` sign-extends it. A `const` and a `const-wide/32` have an 8-bit register, and so do
 * both reads, and none of them branches, so nothing around it moves, as with Material You's reads.
 */
internal fun MutableMethod.blackenDarkColors(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .filter { it.value.isDarkColor() }
        .map { Triple(it.index, (it.value as OneRegisterInstruction).registerA, it.value.opcode) }

    sites.asReversed().forEach { (index, register, opcode) ->
        val page = when (opcode) {
            Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32 -> "sget-wide v$register, $PAGE_WIDE_FIELD"
            else -> "sget v$register, $PAGE_FIELD"
        }
        replaceInstruction(index, page)
    }
    return sites.size
}

/**
 * Sends each `int` that this method returns through the extension method [target], which takes
 * the colour and the colour token and returns a colour. The token is in parameter
 * [tokenParameterIndex]; the first declared parameter is index 0.
 *
 * The hooks go in from last to first, because an insert moves every later index.
 *
 * Both registers get a test. `invoke-static` has 4-bit operands, thus a register above v15
 * assembles into something else and the patch applies but does nothing.
 *
 * A `return` must not be a branch target. The new instructions go in before it, thus a jump onto
 * the `return` would miss them and lose a colour without an error.
 *
 * The token has to still be in its parameter's register at every return. The hook reads it there,
 * at the end of the method, and Facebook's code reuses a parameter's register once it's done with
 * it: a resolver that did would hand the extension some other object as the token, which verifies
 * just the same and recolours by the wrong token.
 */
internal fun MutableMethod.hookColorReturns(tokenParameterIndex: Int, target: String) {
    val tokenRegister = parameterRegisterNumber(tokenParameterIndex)

    check(tokenRegister < 16) {
        "$definingClass->$name: token register v$tokenRegister is out of invoke-static range"
    }
    requireParameterIntact("Colour token hook", tokenParameterIndex, returnSites("int to recolour").map { it.first })

    hookReturns("int to recolour") { register -> "invoke-static { v$register, v$tokenRegister }, $target" }
}

/** Gets the resource FDS's token lookup answers, with the lookup's context and token. Gives the resource to read. */
internal const val TOKEN_RESOURCE_DARK = "$AMOLED->tokenResource(I${CONTEXT}Ljava/lang/Object;)I"

/**
 * Sends each resource id FDS's token lookup returns through [target], with the lookup's context
 * (parameter 0) and token (parameter 1), and returns what comes back. A card's resource and a
 * button's can be one resource, so this is where the token still tells them apart (#191).
 */
internal fun MutableMethod.hookTokenResourceReturns(target: String) {
    check(parameterTypes.firstOrNull()?.toString() == CONTEXT) { "$definingClass->$name doesn't take a Context first" }
    val context = parameterRegisterNumber(0)
    val token = parameterRegisterNumber(1)
    check(context < 16 && token < 16) { "$definingClass->$name: v$context or v$token is out of invoke-static range" }
    val reads = returnSites("resource id").map { it.first }
    requireParameterIntact("Token resource hook", 0, reads)
    requireParameterIntact("Token resource hook", 1, reads)

    hookReturns("resource id") { register -> "invoke-static { v$register, v$context, v$token }, $target" }
}

/**
 * Each `return` of this method, with the register it returns, checked for a hook before it: none
 * is a branch target, and each register fits `invoke-static`. [what] names what the method returns.
 */
private fun MutableMethod.returnSites(what: String): List<Pair<Int, Int>> {
    val implementation = checkNotNull(implementation) { "$definingClass->$name has no body" }
    val instructions = implementation.instructions.toList()

    val addresses = instructions.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOfAddress = addresses.withIndex().associate { (index, address) -> address to index }
    val branchTargets = instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction is OffsetInstruction) {
            indexOfAddress[addresses[index] + instruction.codeOffset]
        } else {
            null
        }
    }.toSet()

    val returns = instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }

    check(returns.isNotEmpty()) { "$definingClass->$name returns no $what" }
    returns.forEach { (index, register) ->
        check(register < 16) {
            "$definingClass->$name: return register v$register is out of invoke-static range"
        }
        check(index !in branchTargets) {
            "$definingClass->$name: the return at index $index is a branch target"
        }
    }
    return returns
}

/**
 * Puts [call] (made on the returned register) and a `move-result` into it before each `return` of
 * this method, so what it returns is what the call answers. The hooks go in from last to first,
 * because an insert moves every later index.
 */
private fun MutableMethod.hookReturns(what: String, call: (register: Int) -> String) {
    returnSites(what).asReversed().forEach { (index, register) ->
        addInstructions(
            index,
            """
                ${call(register)}
                move-result v$register
            """,
        )
    }
}
