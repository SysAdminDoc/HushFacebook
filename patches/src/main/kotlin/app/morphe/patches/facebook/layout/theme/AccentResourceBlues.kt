/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.returnEarly
import java.io.File
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * Route five of Accent color: the colour resources behind the FDS tokens.
 *
 * A view Facebook inflates from layout XML reads its colours inside the framework: the profile's bio
 * link takes textColorLink, which Facebook's themes point at the BLUE_LINK attribute and so at
 * #0064D1's resource, and the Add to story button's fill is a drawable that names #0866FF's resource
 * itself. The extension (AccentResources) gives each activity's resources a table that answers for
 * those colour resources with the accent. This finds them: the colour resources Facebook's styles give
 * an FDS token's attribute, when they hold one of Facebook's blues, with the tokens each stands for.
 * The extension keeps the ones behind an accent token and turns the others' colours back to
 * Facebook's on the way through route one, so a badge drawn in code stays blue.
 *
 * Facebook strips the names of its colours and attributes, so this goes by what the styles point at
 * and by value, as route two does.
 */

private const val ACCENT_RESOURCES = "Lapp/morphe/extension/facebook/theme/AccentResources;"

/** Route five's table for AccentResources.resourceBlues, made by the resource half. */
internal var accentResourceBlues = ""

/** A night configuration the table answers for as well: `night`, or `night-v8` as aapt2 writes it. */
private fun isNightValues(folder: String) = folder.split('-').drop(1).let { it == listOf("night") || it == listOf("night", "v8") }

/**
 * Route five's table: each colour resource an item of [styles] gives an FDS token's attribute (named
 * in [tokens], `attr_0x…` to the token), when its default colour in [colours] is one of Facebook's
 * blues, as `id=colour[/night]:TOKEN,TOKEN;…` in hex, sorted by id and with sorted tokens. The night
 * value comes from [nightColours] when it has one there and that's a blue too. A colour another
 * configuration gives its own value ([otherConfigured]) is left out, since the table's entry wouldn't
 * answer there. [id] gives a colour's resource id by its name, or null.
 */
internal fun resourceBluesTable(
    styles: List<Document>,
    colours: Map<String, String>,
    nightColours: Map<String, String>,
    otherConfigured: Set<String>,
    tokens: Map<String, String>,
    id: (String) -> Long?,
): String {
    val drawnFor = sortedMapOf<String, MutableSet<String>>()
    for (styleFile in styles) {
        val items = styleFile.getElementsByTagName("item")
        for (index in 0 until items.length) {
            val item = items.item(index) as? Element ?: continue
            val token = tokens[item.getAttribute("name")] ?: continue
            val value = item.textContent.trim()
            if (!value.startsWith("@color/")) continue
            drawnFor.getOrPut(value.removePrefix("@color/")) { sortedSetOf() } += token
        }
    }
    return drawnFor.mapNotNull { (name, forTokens) ->
        if (name in otherConfigured) return@mapNotNull null
        val colour = blue(colours[name], colours, colours) ?: return@mapNotNull null
        val night = nightColours[name]?.let { blue(it, nightColours, colours) }
        val resourceId = id(name) ?: return@mapNotNull null
        resourceId to "%x=%08x".format(resourceId, colour) + (night?.let { "/%08x".format(it) } ?: "") +
            ":" + forTokens.joinToString(",")
    }.sortedBy { it.first }.joinToString(";") { it.second }
}

/**
 * Facebook's own pale blues in light mode (a selected chip, the deemphasized accent, a new
 * notification's row), which [isFacebookBlue] finds too grey. AccentColor.FACEBOOK_TINTS in the
 * extension is the same list.
 */
internal val FACEBOOK_TINTS = intArrayOf(0xDDEDFE, 0xE7F3FF, 0xEBF5FF)

/**
 * A colour value as an int when it's one of Facebook's blues or [FACEBOOK_TINTS], following `@color/`
 * references through [first], then [fallback], or null for anything else.
 */
private fun blue(value: String?, first: Map<String, String>, fallback: Map<String, String>, depth: Int = 0): Int? {
    val text = value?.trim() ?: return null
    if (text.startsWith("@color/")) {
        if (depth > 4) return null
        val name = text.removePrefix("@color/")
        return blue(first[name] ?: fallback[name], first, fallback, depth + 1)
    }
    val colour = argb(text) ?: return null
    return colour.takeIf { isFacebookBlue((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) || (it and 0xFFFFFF) in FACEBOOK_TINTS }
}

/**
 * Finds route five's table. After the token names, which come off the token enum. A build where no
 * style gives a token one of Facebook's blues keeps routes one to four; it only says so.
 */
internal val accentResourcePatch = resourcePatch {
    dependsOn(fdsTokenNamesPatch)

    execute {
        val folders = get("res", false).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.split('-').first() == "values" }
        fun coloursIn(folder: File) = File(folder, "colors.xml").takeIf { it.isFile }?.let { readOnly(it).colourValues() }.orEmpty()

        val colours = folders.singleOrNull { it.name == "values" }?.let(::coloursIn).orEmpty()
        val nightColours = folders.filter { isNightValues(it.name) }.fold(emptyMap<String, String>()) { all, folder -> all + coloursIn(folder) }
        val otherConfigured = folders.filter { it.name != "values" && !isNightValues(it.name) }
            .flatMapTo(mutableSetOf()) { coloursIn(it).keys }
        val styles = get("res/values", false).listFiles().orEmpty()
            .filter { it.name.startsWith("style") && it.name.endsWith(".xml") }.sortedBy { it.name }.map(::readOnly)

        accentResourceBlues = resourceBluesTable(styles, colours, nightColours, otherConfigured, tokenAttributeNames) { name ->
            resourceIds.getOrNull(ResourceType.COLOR, name) ?: decodedColourId(name)
        }
        if (accentResourceBlues.isEmpty()) {
            patchLog.info("Accent color: no style gives a token one of Facebook's blue colours, so views inflated from layouts keep Facebook's blue.")
        }
    }
}

/** Puts route five's [table] in AccentResources.resourceBlues. An empty table leaves it null. */
internal fun BytecodePatchContext.fillResourceBlues(table: String) {
    if (table.isEmpty()) return
    val stub = mutableClassDefBy(ACCENT_RESOURCES).methods.singleOrNull {
        it.name == "resourceBlues" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("AccentResources has no resourceBlues() for route five's table")
    stub.returnEarly(table)
}
