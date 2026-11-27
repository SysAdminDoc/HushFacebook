/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.ads.audiencenetwork.REMOTE_APIS_SETUP
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.EXTENSION_ROOT
import app.morphe.patches.facebook.misc.settings.sendToStandIn
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.executeResources
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal const val ICON_PATCH = "Pick the app icon"

internal const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"

private const val APP_ICON = "$EXTENSION_PACKAGE/misc/AppIcon;"

private const val NAME = "android:name"

/** Facebook's launcher alias, the home screen icon until another one is picked (AppIcons.LAUNCHER in the extension). */
internal const val FACEBOOK_ALIAS = "com.facebook.katana.LoginActivity"

/** Each Hushfacebook icon's alias is Facebook's name, then this and the icon's key, as AppIcon in the extension reads it. */
internal const val HUSHFACEBOOK_ALIAS = "$FACEBOOK_ALIAS.hushfacebook_"

/**
 * One of Hushfacebook's home screen icons, by the key its alias and mipmap take (AppIconChoice in
 * the extension has the same keys), drawn the way the Icon style option of the same name draws it.
 */
internal class HushfacebookIcon(val key: String, val style: LauncherIconStyle) {
    val alias get() = "$HUSHFACEBOOK_ALIAS$key"
    val mipmap get() = "hushfacebook_icon_$key"
}

/** The icons, one per Icon style, in AppIconChoice's order. */
internal val HUSHFACEBOOK_ICONS = LauncherIconStyle.entries.map { HushfacebookIcon(it.key.replace('-', '_'), it) }

private fun refuse(why: String): Nothing = throw PatchException("$ICON_PATCH: $why")

/**
 * The PackageManager calls that turn components on and off, by name, with their parameters. AppIcon
 * has a static method of the same name for each, taking the manager first.
 */
internal val COMPONENT_CALLS = mapOf(
    "setComponentEnabledSetting" to "(Landroid/content/ComponentName;II)V",
    "setComponentEnabledSettings" to "(Ljava/util/List;)V",
)

/** The call this instruction makes when it's one of [COMPONENT_CALLS], or null. */
internal fun Instruction.componentCall(): MethodReference? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.definingClass != PACKAGE_MANAGER) return null
    val shape = COMPONENT_CALLS[call.name] ?: return null
    return call.takeIf { it.parameterTypes.joinToString("", "(", ")") + it.returnType == shape }
}

/** The AppIcon method that stands in for the call [name]: static, the manager first. */
internal fun componentStandIn(name: String): String =
    "$APP_ICON->$name${COMPONENT_CALLS.getValue(name).replaceFirst("(", "($PACKAGE_MANAGER")}"

private fun Method.makesComponentCall(): Boolean = implementation?.instructions?.any { it.componentCall() != null } == true

/**
 * Sends every one of Facebook's [COMPONENT_CALLS] to AppIcon, on the same registers, and answers how
 * many it sent. Facebook's component manager puts its launcher entry back on and every alias off on
 * some starts, from code that isn't the same in each build, so every call goes through the filter
 * rather than the ones known today. The extension's own calls stay as they are. Refuses when there
 * are none, since then the build sets its icon some other way, or when AppIcon hasn't the stand-ins.
 */
internal fun BytecodePatchContext.routeComponentCalls(): Int {
    val extension = (classDefByOrNull(APP_ICON)?.methods ?: emptySet())
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        .mapTo(HashSet()) { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    COMPONENT_CALLS.keys.map(::componentStandIn).firstOrNull { it !in extension }?.let { refuse("the extension has no public static $it") }
    val callers = mutableListOf<String>()
    classDefForEachReferencing(names = COMPONENT_CALLS.keys) { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEachReferencing
        // Disable Audience Network sends this task's calls through its own stand-in and refuses when it
        // finds none, whichever patch runs first. They only turn Audience Network's components on.
        if (classDef.type == REMOTE_APIS_SETUP) return@classDefForEachReferencing
        if (classDef.methods.any { it.makesComponentCall() }) callers += classDef.type
    }
    var sent = 0
    for (type in callers) {
        for (method in mutableClassDefBy(type).methods) {
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                instruction.componentCall()?.let { index to it }
            }.orEmpty()
            // From the end, though a stand-in is the same size as the call it replaces.
            for ((index, call) in sites.asReversed()) method.sendToStandIn(index, componentStandIn(call.name))
            sent += sites.size
        }
    }
    if (sent == 0) refuse("found no PackageManager.setComponentEnabledSetting call in Facebook")
    return sent
}

private fun Element.all(tag: String): List<Element> =
    getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

/** [name] as the manifest's [owner] package spells it in full. */
private fun fullName(name: String, owner: String) = if (name.startsWith(".")) owner + name else name

/**
 * Adds an alias per Hushfacebook icon to [manifest], each a copy of Facebook's launcher alias,
 * intent filters and meta-data included, so it opens the app the same way. Each is off until the
 * extension turns it on, shows its own icon, and carries the default-state meta-data Facebook's own
 * alternate icons carry. Refuses unless Facebook's alias is there once and no Hushfacebook alias is.
 */
internal fun addIconAliases(manifest: Document) {
    val root = manifest.documentElement
    val owner = root.getAttribute("package")
    val aliases = root.all("activity-alias")
    val names = aliases.map { fullName(it.getAttribute(NAME), owner) }
    val facebook = aliases.filterIndexed { index, _ -> names[index] == FACEBOOK_ALIAS }.singleOrNull()
        ?: refuse("expected one $FACEBOOK_ALIAS alias, found ${names.count { it == FACEBOOK_ALIAS }}")
    names.firstOrNull { it.startsWith(HUSHFACEBOOK_ALIAS) }?.let { refuse("the manifest already has $it") }
    var after: Element = facebook
    for (icon in HUSHFACEBOOK_ICONS) {
        val alias = facebook.cloneNode(true) as Element
        alias.setAttribute(NAME, icon.alias)
        alias.setAttribute("android:enabled", "false")
        alias.setAttribute("android:icon", "@mipmap/${icon.mipmap}")
        if (alias.hasAttribute("android:roundIcon")) alias.setAttribute("android:roundIcon", "@mipmap/${icon.mipmap}")
        val state = alias.all("meta-data").firstOrNull { it.getAttribute(NAME) == "default-state" }
        if (state != null) {
            state.setAttribute("android:value", "false")
        } else {
            val meta = manifest.createElement("meta-data")
            meta.setAttribute(NAME, "default-state")
            meta.setAttribute("android:value", "false")
            alias.insertBefore(meta, alias.firstChild)
        }
        after.parentNode.insertBefore(alias, after.nextSibling)
        after = alias
    }
}

private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

private fun Document.text(): String {
    val out = StringWriter()
    TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(out))
    return out.toString()
}

/**
 * Each icon's adaptive icon, by its mipmap's name: Facebook's own icon as [pristine], its file
 * before any Icon style drew over it, restyled the way the icon's style draws it from Facebook's
 * themed-icon vector [glyph]. Refusals carry the patch's name.
 */
internal fun iconFiles(pristine: String, glyph: Document?): Map<String, String> = HUSHFACEBOOK_ICONS.associate { icon ->
    val document = parse(pristine)
    try {
        restyleLauncherIcon(document, glyph, icon.style)
    } catch (failure: PatchException) {
        refuse("the ${icon.style.label} icon can't be drawn: ${failure.message}")
    }
    icon.mipmap to document.text()
}

/**
 * Adds the aliases and their icons, next to Facebook's adaptive icon. That icon is read as it was
 * before Icon style drew over it, so the Facebook choice keeps Facebook's look. Refuses before
 * anything changes when an icon the patch adds is already there.
 */
internal fun ResourcePatchContext.addAppIcons() {
    val read = { path: String -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(get(path)) }
    val icon = appResource(applicationIcon(read("AndroidManifest.xml"))) ?: refuse("Facebook's icon isn't one of its own resources")
    // Patcher 2.0 decodes only what a patch asks for. Hushfacebook's names are asked for so one
    // already in the APK shows up and is refused.
    val iconPath = singleXml(decode(resourceGlob(icon)), icon, ADAPTIVE_ICON, read)
    decode("res/mipmap*/hushfacebook_icon_*.xml")
    val pristine = PristineLauncherIcon.before(iconPath) { get(iconPath).readText() }
    val needsGlyph = HUSHFACEBOOK_ICONS.any { !it.style.logo && it.style.glyph != null }
    val glyph = if (!needsGlyph) null else {
        val reference = monochromeReference(parse(pristine))
        read(singleXml(decode(resourceGlob(reference)), reference, VECTOR, read))
    }
    val folder = File(iconPath).parentFile.name
    val files = iconFiles(pristine, glyph).mapKeys { (mipmap, _) -> "res/$folder/$mipmap.xml" }
    files.keys.firstOrNull { get(it).exists() }?.let { refuse("$it is already there") }
    document("AndroidManifest.xml").use { addIconAliases(it) }
    for ((path, xml) in files) get(path).apply { parentFile.mkdirs() }.writeText(xml)
}

/** The aliases and their icons. Depends on the settings patch, so Icon style has drawn first. */
private val appIconResourcesPatch = patch {
    dependsOn(settingsPatch)
    executeResources {
        addAppIcons()
    }
}

/**
 * A home screen icon picked in Hushfacebook settings (#467): the one Morphe drew, Facebook's own, or
 * any of the Icon style looks. Included in the default selection with the one Morphe drew picked, so
 * nothing changes until you pick another.
 *
 * Android shows one icon per enabled launcher activity, so the patch adds an alias per icon next to
 * Facebook's launcher alias, each a copy of it that's off, and the extension turns the picked one on
 * and Facebook's off when Facebook goes to the background, the way Facebook changes its own
 * alternate icons. Facebook's component manager puts its components back the way the manifest has
 * them on some starts, so every call Facebook makes to turn a component on or off goes through
 * AppIcon, which drops the ones that would undo the pick.
 */
@Suppress("unused")
val pickAppIconPatch = patch(
    // The README table check reads this literal; ICON_PATCH carries the same text for the messages.
    name = "Pick the app icon",
    description = "Lets you change the icon on your home screen without patching again. In Hushfacebook settings > " +
        "Appearance > App icon, pick the one you chose when patching, Facebook's own, Hushfacebook's logo in color, " +
        "white on black or black on white, or Facebook's \"f\" in white on black or black on white. The new icon " +
        "shows once you leave Facebook, and you may need to add it to your home screen again from the app drawer. " +
        "Until you pick one, you keep the icon you patched with.",
) {
    category("Theme")
    dependsOn(settingsPatch, facebookExtensionPatch, appIconResourcesPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("appIcon")
        routeComponentCalls()
        enableStatus("appIcon")
    }
}
