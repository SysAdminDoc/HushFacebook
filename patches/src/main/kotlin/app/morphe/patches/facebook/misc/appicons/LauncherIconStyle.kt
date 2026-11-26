/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.onResources
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/*
 * The Icon style option of Hushfacebook settings (#278): Facebook's home screen icon white on
 * black or black on white, Hushfeed's two plain styles, or Hushfacebook's own logo in color, white
 * on black or black on white (#467). 584's icon, @mipmap/icon_katana, is an adaptive icon: a blue
 * shape behind a PNG of the white "f", with a 108dp vector of the same "f" as its themed-icon
 * (monochrome) layer. A plain style writes a solid color as the background and a copy of that
 * vector, filled with the style's color, as the foreground. The Hushfacebook style writes its blue
 * as the background, the H with its two shadows as the foreground, and the H alone as the
 * monochrome layer (HushfacebookLogo.kt). Its two plain versions draw that lone H in one color on
 * a solid background. All of it is written inline, which AdaptiveIconDrawable reads when a layer
 * names no drawable, so Facebook's own drawables stay as they are for everything else that draws
 * them. Android 13's themed icons keep working: the plain "f" styles leave the monochrome layer as it is.
 *
 * The Icon background option puts a color of your own behind whatever the style draws, and Icon
 * picture draws a PNG of your own instead of it (LauncherPicture.kt). A picture drops the themed
 * layer, which would show the style's own shape in its place.
 *
 * Every launcher alias in the manifest that shows the plain Facebook icon points at the same
 * mipmap, so the enabled one and the icon choices that reuse it all change. Facebook's other app
 * icons keep their own looks.
 */

private const val OPTION = "Hushfacebook settings, Icon style"
private const val ADAPTIVE_ICON = "adaptive-icon"
private const val VECTOR = "vector"
private const val MONOCHROME = "monochrome"
private const val FILL_COLOR = "android:fillColor"
private const val STROKE_COLOR = "android:strokeColor"

/**
 * The looks the option offers. [background] and [glyph] are null for Facebook's own icon. [glyph]
 * is the one color a plain style draws its shape in, Facebook's "f" or, with [logo], Hushfacebook's
 * H. Hushfacebook's colored logo has [logo] and no [glyph]: it draws its own colors.
 */
internal enum class LauncherIconStyle(
    val key: String,
    val label: String,
    val background: String?,
    val glyph: String?,
    val logo: Boolean = false,
) {
    FACEBOOK("facebook", "Facebook colors", null, null),
    HUSHFACEBOOK("hushfacebook", "Hushfacebook", HushfacebookLogo.BLUE, null, logo = true),
    HUSHFACEBOOK_WHITE_ON_BLACK("hushfacebook-white-on-black", "Hushfacebook white on black", "#ff000000", "#ffffffff", logo = true),
    HUSHFACEBOOK_BLACK_ON_WHITE("hushfacebook-black-on-white", "Hushfacebook black on white", "#ffffffff", "#ff000000", logo = true),
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
 * Restyles [icon] in place. A plain "f" style draws a solid [LauncherIconStyle.background] behind
 * a copy of [glyph], the themed-icon vector, with every path filled in [LauncherIconStyle.glyph],
 * and leaves the themed layer as it is. A [LauncherIconStyle.logo] style draws Hushfacebook's H
 * instead ([drawHushfacebookIcon]), and Facebook colors keeps Facebook's own layers. With a
 * [picture], a drawable reference, the foreground draws that instead of the style's shape and the
 * themed layer goes. With a [background] color, that's drawn behind in place of the style's. Every
 * check runs before the first change, so a refusal leaves [icon] as it was.
 */
internal fun restyleLauncherIcon(
    icon: Document,
    glyph: Document?,
    style: LauncherIconStyle,
    picture: String? = null,
    background: String? = null,
) {
    val root = icon.documentElement
    if (root?.tagName != ADAPTIVE_ICON) refuse("Facebook's icon isn't an adaptive icon")
    val back = root.layer("background") ?: refuse("Facebook's icon has no background layer")
    val front = root.layer("foreground") ?: refuse("Facebook's icon has no foreground layer")
    // The style's shape and themed layer, both built before anything changes.
    val shape = when {
        picture != null -> null
        style.logo -> logoForeground(icon, style)
        style.glyph != null -> facebookGlyph(icon, glyph, style.glyph)
        else -> null
    }
    val themed = if (picture == null && style.logo) logoMonochrome(icon) else null
    (background ?: style.background)?.let { color ->
        back.drawInline(icon.createElement("color").apply { setAttribute("android:color", color) })
    }
    if (picture != null) {
        while (front.firstChild != null) front.removeChild(front.firstChild)
        front.setAttribute("android:drawable", picture)
        // The launcher would draw the style's own shape from it, not the picture.
        root.layer(MONOCHROME)?.let { root.removeChild(it) }
        return
    }
    shape?.let { front.drawInline(it) }
    themed?.let { (root.layer(MONOCHROME) ?: icon.createElement(MONOCHROME).also { root.appendChild(it) }).drawInline(it) }
}

/**
 * A copy of [glyph], Facebook's themed-icon vector, with every path filled in [color]. Refuses
 * when [icon] has no themed layer or [glyph] isn't a vector with a path.
 */
private fun facebookGlyph(icon: Document, glyph: Document?, color: String): Element {
    monochromeReference(icon)
    val vector = glyph?.documentElement
    if (vector?.tagName != VECTOR) refuse("Facebook's themed-icon layer isn't a vector")
    val copy = icon.importNode(vector, true) as Element
    val paths = copy.getElementsByTagName("path").let { list -> (0 until list.length).map { list.item(it) as Element } }
    if (paths.isEmpty()) refuse("Facebook's themed-icon vector has no path")
    copy.removeAttribute("xmlns:android")
    for (path in paths) {
        path.setAttribute(FILL_COLOR, color)
        if (path.hasAttribute(STROKE_COLOR)) path.setAttribute(STROKE_COLOR, color)
    }
    return copy
}

/** Hushfacebook's logo as a foreground: the H with its two shadows, or the lone H in a plain style's color. */
private fun logoForeground(icon: Document, style: LauncherIconStyle): Element {
    val color = style.glyph ?: return icon.vector(HushfacebookLogo.FOREGROUND)
    return icon.vector(listOf(color to HushfacebookLogo.MONOCHROME), fillType = "evenOdd")
}

/** The H alone, slots cut out, as the themed layer. Themed icons tint the shape, so its fill color doesn't show. */
private fun logoMonochrome(icon: Document): Element =
    icon.vector(listOf("#ffffffff" to HushfacebookLogo.MONOCHROME), fillType = "evenOdd")

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
 * Draws Hushfacebook's logo into [icon] in place: the style's background, the H with its two
 * shadows as the foreground, or for a plain style the H alone in its color, and the H alone,
 * slots cut out, as the themed-icon layer, added when Facebook's icon has none. Every check runs
 * before the first change.
 */
internal fun drawHushfacebookIcon(icon: Document, style: LauncherIconStyle = LauncherIconStyle.HUSHFACEBOOK) {
    if (!style.logo) refuse("${style.label} isn't one of Hushfacebook's logos")
    restyleLauncherIcon(icon, null, style)
}

private fun Element.drawInline(drawable: Element) {
    removeAttribute("android:drawable")
    while (firstChild != null) removeChild(firstChild)
    appendChild(drawable)
}

/**
 * Restyles the launcher icon with the style [iconStyle] answers, the color [iconBackground]
 * answers behind it and the picture [iconPicture] names in place of its shape (#467). With
 * Facebook's own icon picked and the other two empty, it opens no file, so the resources stay byte
 * for byte as they were. The color and the picture are checked before anything is read.
 */
internal fun launcherIconPatch(
    iconStyle: () -> String?,
    iconBackground: () -> String? = { null },
    iconPicture: () -> String? = { null },
) = patch {
    // Built in a function, so the block is written here to carry this file's name (see PatchBodies.kt).
    execute {
        onResources {
            val style = pickedIconStyle(iconStyle()) ?: LauncherIconStyle.FACEBOOK
            val background = pickedIconBackground(iconBackground())
            val picture = launcherPicture(iconPicture())
            if (style == LauncherIconStyle.FACEBOOK && background == null && picture == null) return@onResources
            // Read with a plain parser: a document() closes by writing its file back.
            val read = { path: String -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(get(path)) }
            val icon = appResource(applicationIcon(read("AndroidManifest.xml")))
                ?: refuse("Facebook's icon isn't one of its own resources")
            // Patcher 2.0 decodes only the files a patch asks for: an undecoded file does not exist yet.
            val iconPath = singleXml(decode(resourceGlob(icon)), icon, ADAPTIVE_ICON, read)
            // Only a plain "f" style draws from Facebook's themed-icon vector.
            val vector = if (picture != null || style.logo || style.glyph == null) null else {
                val glyph = monochromeReference(read(iconPath))
                read(singleXml(decode(resourceGlob(glyph)), glyph, VECTOR, read))
            }
            val pictureFile = picture?.let {
                // Asked for, so one already in the APK shows up and is refused.
                decode("res/drawable*/$PICTURE_NAME.png")
                File(get("res"), "drawable-nodpi/$PICTURE_NAME.png").also { file ->
                    if (file.exists()) refuse("this Facebook already has res/drawable-nodpi/$PICTURE_NAME.png")
                }
            }
            val pictureReference = picture?.let { "@drawable/$PICTURE_NAME" }
            // A dry run on a throwaway copy first: a refusal leaves the icon file as it was.
            restyleLauncherIcon(read(iconPath), vector, style, pictureReference, background)
            // One unscaled file: the launcher scales the layer to its icon size on every screen, and
            // Morphe Manager patches on the phone, where there's no image library to resize it with.
            if (picture != null && pictureFile != null) pictureFile.apply { parentFile.mkdirs() }.writeBytes(picture.bytes)
            document(iconPath).use { restyleLauncherIcon(it, vector, style, pictureReference, background) }
            val drawn = if (picture != null) "your ${picture.side} px picture" else style.label.lowercase()
            patchLog.info("$OPTION: the home screen icon is $drawn" + (background?.let { " on $it" } ?: "") + " ($iconPath).")
        }
    }
}
