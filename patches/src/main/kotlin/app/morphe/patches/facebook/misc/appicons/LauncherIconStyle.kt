/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.misc.extension.patchLog
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/*
 * The Icon style option of Hushfacebook settings (#278): Facebook's home screen icon white on
 * black or black on white, Hushfeed's two plain styles, or Hushfacebook's own logo (#467). 584's
 * icon, @mipmap/icon_katana, is an adaptive icon: a blue shape behind a PNG of the white "f", with
 * a 108dp vector of the same "f" as its themed-icon (monochrome) layer. A plain style writes a
 * solid color as the background and a copy of that vector, filled with the style's color, as the
 * foreground. The Hushfacebook style writes its blue as the background, the H with its two
 * shadows as the foreground, and the H alone as the monochrome layer (HushfacebookLogo.kt). All of
 * it is written inline, which AdaptiveIconDrawable reads when a layer names no drawable, so
 * Facebook's own drawables stay as they are for everything else that draws them. Android 13's
 * themed icons keep working: the plain styles leave the monochrome layer as it is.
 *
 * Every launcher alias in the manifest that shows the plain Facebook icon points at the same
 * mipmap, so the enabled one and the icon choices that reuse it all change. The Facebook Plus
 * icons Unlock app icons opens keep their own looks.
 */

private const val OPTION = "Hushfacebook settings, Icon style"
private const val ADAPTIVE_ICON = "adaptive-icon"
private const val VECTOR = "vector"
private const val FILL_COLOR = "android:fillColor"
private const val STROKE_COLOR = "android:strokeColor"

/**
 * The looks the option offers. [background] and [glyph] are null for Facebook's own icon, and
 * [glyph], the one color of a plain style's "f", is null for Hushfacebook's logo, which draws its own.
 */
internal enum class LauncherIconStyle(val key: String, val label: String, val background: String?, val glyph: String?) {
    FACEBOOK("facebook", "Facebook colors", null, null),
    HUSHFACEBOOK("hushfacebook", "Hushfacebook", HushfacebookLogo.BLUE, null),
    WHITE_ON_BLACK("white-on-black", "White on black", "#ff000000", "#ffffffff"),
    BLACK_ON_WHITE("black-on-white", "Black on white", "#ffffffff", "#ff000000"),
}

private fun refuse(reason: String): Nothing = throw PatchException("$OPTION: $reason. Nothing was changed.")

/** The picked style by key or label, or null for Facebook's own icon. Refuses a name that isn't a style. */
internal fun pickedIconStyle(raw: String?): LauncherIconStyle? {
    val key = raw?.trim().orEmpty()
    if (key.isEmpty()) return null
    val style = LauncherIconStyle.entries.firstOrNull { it.key.equals(key, ignoreCase = true) || it.label.equals(key, ignoreCase = true) }
        ?: refuse("\"$key\" isn't a style. Pick one of ${LauncherIconStyle.entries.joinToString { it.key }}")
    return style.takeIf { it != LauncherIconStyle.FACEBOOK }
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun Element.layer(tag: String): Element? = childElements().firstOrNull { it.tagName == tag }

/**
 * The application's icon, `@mipmap/icon_katana` on 584. A separate round icon would keep the old
 * look on launchers that prefer it, so the option refuses one instead.
 */
internal fun applicationIcon(manifest: Document): String {
    val application = manifest.getElementsByTagName("application").item(0) as? Element ?: refuse("Facebook's manifest has no application")
    val icon = application.getAttribute("android:icon").takeIf { it.isNotEmpty() } ?: refuse("Facebook's application has no icon")
    val round = application.getAttribute("android:roundIcon")
    if (round.isNotEmpty() && round != icon) refuse("Facebook has a separate round icon ($round)")
    return icon
}

/** `@mipmap/icon_katana` or `@com.facebook.katana:mipmap/icon_katana` as type and name, or null for a framework or theme reference. */
internal fun appResource(reference: String): Pair<String, String>? {
    val match = Regex("""@(?:([\w.]+):)?(\w+)/([\w.]+)""").matchEntire(reference.trim()) ?: return null
    if (match.groupValues[1] == "android") return null
    return match.groupValues[2] to match.groupValues[3]
}

/** The decoded glob that covers every configuration of [resource]. */
internal fun resourceGlob(resource: Pair<String, String>) = "res/${resource.first}*/${resource.second}.xml"

/**
 * The one decoded configuration of [resource] whose root is [root] (`res/mipmap-anydpi/icon_katana.xml`
 * for the icon on 584), read through [read].
 */
internal fun singleXml(decoded: List<String>, resource: Pair<String, String>, root: String, read: (String) -> Document): String {
    val (type, name) = resource
    val matches = decoded.filter { path ->
        val folder = path.substringBeforeLast('/', "")
        path.substringAfterLast('/') == "$name.xml" && (folder == "res/$type" || folder.startsWith("res/$type-")) &&
            runCatching { read(path).documentElement.tagName == root }.getOrDefault(false)
    }
    return matches.singleOrNull() ?: when {
        matches.isEmpty() -> refuse("Facebook's @$type/$name has no <$root> version")
        else -> refuse("Facebook's @$type/$name has ${matches.size} <$root> versions (${matches.joinToString()}), so it can't tell which one is drawn")
    }
}

/** The themed-icon vector the icon's monochrome layer names, or a refusal when it has none to reuse. */
internal fun monochromeReference(icon: Document): Pair<String, String> {
    val root = icon.documentElement
    if (root?.tagName != ADAPTIVE_ICON) refuse("Facebook's icon isn't an adaptive icon")
    val drawable = root.layer("monochrome")?.getAttribute("android:drawable").orEmpty()
    return appResource(drawable) ?: refuse("Facebook's icon has no themed-icon layer to draw the logo from")
}

/**
 * Restyles [icon] in place: a solid [LauncherIconStyle.background] behind a copy of [glyph], the
 * themed-icon vector, with every path filled in [LauncherIconStyle.glyph]. Every check runs
 * before the first change, so a refusal leaves [icon] as it was.
 */
internal fun restyleLauncherIcon(icon: Document, glyph: Document, style: LauncherIconStyle) {
    if (style == LauncherIconStyle.HUSHFACEBOOK) return drawHushfacebookIcon(icon)
    val background = style.background ?: refuse("${style.label} keeps Facebook's icon")
    val color = style.glyph ?: refuse("${style.label} keeps Facebook's icon")
    monochromeReference(icon)
    val root = icon.documentElement
    val back = root.layer("background") ?: refuse("Facebook's icon has no background layer")
    val front = root.layer("foreground") ?: refuse("Facebook's icon has no foreground layer")
    val vector = glyph.documentElement
    if (vector?.tagName != VECTOR) refuse("Facebook's themed-icon layer isn't a vector")
    val copy = icon.importNode(vector, true) as Element
    val paths = copy.getElementsByTagName("path").let { list -> (0 until list.length).map { list.item(it) as Element } }
    if (paths.isEmpty()) refuse("Facebook's themed-icon vector has no path")

    copy.removeAttribute("xmlns:android")
    for (path in paths) {
        path.setAttribute(FILL_COLOR, color)
        if (path.hasAttribute(STROKE_COLOR)) path.setAttribute(STROKE_COLOR, color)
    }
    back.drawInline(icon.createElement("color").apply { setAttribute("android:color", background) })
    front.drawInline(copy)
}

/** A 108 by 108 vector of [paths], fill color to path data, drawn back to front. */
private fun Document.vector(paths: List<Pair<String, String>>, fillType: String? = null): Element =
    createElement(VECTOR).apply {
        setAttribute("android:width", "108dp")
        setAttribute("android:height", "108dp")
        setAttribute("android:viewportWidth", "108")
        setAttribute("android:viewportHeight", "108")
        for ((color, data) in paths) {
            appendChild(
                createElement("path").apply {
                    setAttribute(FILL_COLOR, color)
                    fillType?.let { setAttribute("android:fillType", it) }
                    setAttribute("android:pathData", data)
                },
            )
        }
    }

/**
 * Draws Hushfacebook's logo into [icon] in place: its blue as the background, the H with its two
 * shadows as the foreground, and the H alone, slots cut out, as the themed-icon layer, added when
 * Facebook's icon has none. Every check runs before the first change.
 */
internal fun drawHushfacebookIcon(icon: Document) {
    val root = icon.documentElement
    if (root?.tagName != ADAPTIVE_ICON) refuse("Facebook's icon isn't an adaptive icon")
    val back = root.layer("background") ?: refuse("Facebook's icon has no background layer")
    val front = root.layer("foreground") ?: refuse("Facebook's icon has no foreground layer")
    val background = LauncherIconStyle.HUSHFACEBOOK.background ?: refuse("Hushfacebook has no background color")

    val mono = root.layer("monochrome") ?: icon.createElement("monochrome").also { root.appendChild(it) }
    back.drawInline(icon.createElement("color").apply { setAttribute("android:color", background) })
    front.drawInline(icon.vector(HushfacebookLogo.FOREGROUND))
    // Themed icons tint the layer's shape, so its one fill color doesn't show.
    mono.drawInline(icon.vector(listOf("#ffffffff" to HushfacebookLogo.MONOCHROME), fillType = "evenOdd"))
}

private fun Element.drawInline(drawable: Element) {
    removeAttribute("android:drawable")
    while (firstChild != null) removeChild(firstChild)
    appendChild(drawable)
}

/**
 * Restyles the launcher icon with the style [iconStyle] answers. With Facebook's own icon picked,
 * it opens no file, so the resources stay byte for byte as they were.
 */
internal fun launcherIconPatch(iconStyle: () -> String?) = resourcePatch {
    execute {
        val style = pickedIconStyle(iconStyle()) ?: return@execute
        // Read with a plain parser: a document() closes by writing its file back.
        val read = { path: String -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(get(path)) }
        val icon = appResource(applicationIcon(read("AndroidManifest.xml")))
            ?: refuse("Facebook's icon isn't one of its own resources")
        // Patcher 2.0 decodes only the files a patch asks for: an undecoded file does not exist yet.
        val iconPath = singleXml(decode(resourceGlob(icon)), icon, ADAPTIVE_ICON, read)
        if (style == LauncherIconStyle.HUSHFACEBOOK) {
            // Its own artwork: Facebook's themed-icon vector isn't needed. Dry run first, as below.
            drawHushfacebookIcon(read(iconPath))
            document(iconPath).use { drawHushfacebookIcon(it) }
            patchLog.info("$OPTION: the home screen icon is Hushfacebook's logo ($iconPath).")
            return@execute
        }
        val glyph = monochromeReference(read(iconPath))
        val glyphPath = singleXml(decode(resourceGlob(glyph)), glyph, VECTOR, read)
        val vector = read(glyphPath)
        // A dry run on a throwaway copy first: a refusal leaves the icon file as it was.
        restyleLauncherIcon(read(iconPath), vector, style)
        document(iconPath).use { restyleLauncherIcon(it, vector, style) }
        patchLog.info("$OPTION: the home screen icon is ${style.label.lowercase()} ($iconPath).")
    }
}
