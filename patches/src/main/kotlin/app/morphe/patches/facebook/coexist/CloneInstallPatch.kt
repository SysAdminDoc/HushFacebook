/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 * Ported from https://github.com/SysAdminDoc/HushMessenger (its Clone install under another package name),
 * whose package rename is adapted from https://github.com/rushiranpise/morphe-patches,
 * patches/src/main/kotlin/app/template/patches/messenger/misc/ChangePackageNamePatch.kt at
 * e3bb3af54e13ecfac60eb8bf9bdf287330f0529f. GPL-3.0. Modified for Hushfacebook, 2026.
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * A second Facebook under another package name (#417), for a phone where Facebook came
 * preinstalled as a system app: a patched copy with Facebook's own name and another key won't
 * install over it. Morphe's Clone app renames the package too, but it's a separate patch whose
 * finalize order this bundle can't control (see ClonedPackage.kt), so this one does the whole job
 * in one place: the manifest moves to the new name here, and the code half (the extension reading
 * the running package where Facebook names its own providers, intents and preferences) is the one
 * clonedPackagePatch already puts in every build.
 *
 * Facebook's own manifest differs from Messenger's in two ways the port handles. Its task
 * affinities include ":mhe", which Android completes with the package itself, and "" for no
 * affinity, so both are left as they are. And it declares the shared account type
 * com.facebook.auth.login, which stays: Meta's apps find each other's sign-in through it.
 */

internal const val CLONE_PATCH_NAME = "Clone install under another package name"
internal const val CLONE_DEFAULT_PACKAGE = "com.facebook.katana.hush"
internal const val CLONE_DEFAULT_LABEL = "Facebook Clone"

/** The string resource the clone's app name and launcher entries point at. */
internal const val CLONE_LABEL = "hushfacebook_clone_label"

private const val ORIGINAL = AppCompatibilities.FACEBOOK_PACKAGE
private const val CLONE_STRINGS = "res/values/strings.xml"

/** Install beside Meta's apps' prefix for the permissions every patched Meta app declares. */
private const val SHARED_PREFIX = RENAMED_PREFIX

/** Facebook itself and the Meta apps it shares accounts with. A clone can't take any of their names. */
private val reservedPackages = setOf(
    ORIGINAL, "com.facebook.orca", "com.facebook.lite", "com.facebook.mlite", "com.facebook.wakizashi",
    "com.instagram.android", "com.instagram.barcelona", "com.whatsapp", "com.whatsapp.w4b",
)
private val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
private val labelPattern = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._()+!,-]{0,39}$")

internal fun isClonePackage(name: String?) = name != null && name.length <= 150 && packagePattern.matches(name) &&
    reservedPackages.none { it.equals(name, ignoreCase = true) }

internal fun isCloneLabel(label: String?) = label != null && labelPattern.matches(label) && label.trimEnd() == label

private fun fail(detail: String): Nothing =
    throw PatchException("$CLONE_PATCH_NAME: $detail. Start with an unmodified supported APK.")

/** Facebook's own name or one of its own dotted names, moved under [newPackage]. Null for anything else. */
internal fun cloneName(value: String, newPackage: String): String? = when {
    value == ORIGINAL -> newPackage
    value.startsWith("$ORIGINAL.") -> newPackage + value.removePrefix(ORIGINAL)
    else -> null
}

private fun Document.all(tag: String) = getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

private fun Element.children(tag: String) = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

/** What the rename changed, for the tests and the patch log. */
internal data class CloneManifest(
    val permissions: Map<String, String>,
    val authorities: Map<String, String>,
    val affinities: Int,
    val pushCategories: Int,
    val labels: Int,
)

private val permissionAttributes = listOf("android:permission", "android:readPermission", "android:writePermission")
private val permissionTags = setOf("permission", "uses-permission", "uses-permission-sdk-23")

/**
 * Moves everything in the manifest that is named after Facebook's package to [newPackage], so the
 * copy installs beside Facebook without a duplicate permission, provider authority or task. Class
 * names stay as they are: they name DEX classes, and the launcher aliases keep the names Facebook's
 * own icon switch looks up. The labels point at [CLONE_LABEL], which the caller writes.
 */
internal fun Document.renameForClone(newPackage: String, label: String): CloneManifest {
    if (!isClonePackage(newPackage)) fail("$newPackage can't be used as a package name")
    if (!isCloneLabel(label)) fail("\"$label\" can't be used as an app name")
    val manifest = documentElement
    if (manifest.tagName != "manifest") fail("the manifest isn't Facebook's")
    val current = manifest.getAttribute("package")
    if (current != ORIGINAL) {
        fail("the package is already $current. Pick either Clone app or this patch, not both")
    }
    val application = all("application").singleOrNull() ?: fail("expected one application")
    if (application.getAttribute("android:label").isEmpty()) fail("Facebook's application has no label")
    if ((all("activity") + all("activity-alias")).none { it.isLauncherEntry() }) fail("Facebook's manifest has no launcher entry")

    // Facebook's own permissions move. The two every patched Meta app shares must already carry the
    // shared names, which two apps signed with the same key can both declare.
    val declared = all("permission").map { it.getAttribute("android:name") }
    val permissions = declared.mapNotNull { name -> cloneName(name, newPackage)?.let { name to it } }.toMap()
    val stuck = declared.filter { it !in permissions && !it.startsWith(SHARED_PREFIX) }
    if (stuck.isNotEmpty()) fail("Facebook declares ${stuck.joinToString()} under a name another app can own")
    if (permissions.isEmpty()) fail("Facebook declares no permissions of its own")

    // Providers under <queries> only ask for another app's authority, so only declared ones move.
    val original = mutableListOf<String>()
    val authorities = linkedMapOf<String, String>()
    val providers = application.children("provider").associateWith { provider ->
        val names = provider.getAttribute("android:authorities").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) fail("a provider has no authority")
        val moved = names.map { name -> cloneName(name, newPackage) ?: fail("provider authority $name isn't Facebook's own") }
        original += names
        names.zip(moved).forEach { (from, to) -> authorities[from] = to }
        moved.joinToString(";")
    }
    if (authorities.isEmpty() || original.toSet().size != original.size || authorities.values.toSet().size != authorities.size ||
        authorities.values.any { it in authorities.keys }
    ) fail("provider authorities would collide")

    // Everything above can stop the patch. Nothing below can, so a failure leaves the manifest as it was.
    providers.forEach { (provider, moved) -> provider.setAttribute("android:authorities", moved) }
    val everything = all("*")
    for (element in everything) {
        val attributes = permissionAttributes + if (element.tagName in permissionTags) listOf("android:name") else emptyList()
        for (attribute in attributes) permissions[element.getAttribute(attribute)]?.let { element.setAttribute(attribute, it) }
    }

    // Android puts screens with the same affinity in the same task, even across apps. An empty
    // affinity means none, and one starting with ':' gets the package put in front by Android.
    var affinities = 0
    for (element in everything) {
        if (!element.hasAttribute("android:taskAffinity")) continue
        val affinity = element.getAttribute("android:taskAffinity")
        if (affinity.isEmpty() || affinity.startsWith(":")) continue
        element.setAttribute("android:taskAffinity", cloneName(affinity, newPackage) ?: "$newPackage.$affinity")
        affinities++
    }

    // Push services address an app by a category equal to its package.
    var pushCategories = 0
    for (filter in all("intent-filter")) for (category in filter.children("category")) {
        if (category.getAttribute("android:name") == ORIGINAL) {
            category.setAttribute("android:name", newPackage)
            pushCategories++
        }
    }
    for (metadata in all("meta-data")) if (metadata.getAttribute("android:value") == ORIGINAL) metadata.setAttribute("android:value", newPackage)
    // The copy asks to see itself, not Facebook, if a build ever lists Facebook among the apps it asks to see.
    for (queries in all("queries")) for (query in queries.children("package")) {
        if (query.getAttribute("android:name") == ORIGINAL) query.setAttribute("android:name", newPackage)
    }

    // The labels point at a string resource, since some launchers show text written straight into
    // the manifest under the stock name. The same rule as App name, so a picked App name gives way.
    val labels = labelWith("@string/$CLONE_LABEL")
    manifest.setAttribute("package", newPackage)
    return CloneManifest(permissions, authorities, affinities, pushCategories, labels)
}

/*
 * The one place in Facebook's code a clone breaks that the shared clone support doesn't cover:
 * the check whether an intent opens one of Facebook's own screens. It compares the intent's
 * component package with Facebook's name, which a clone's own components never carry, so its
 * internal links were taken for outside ones. The component's package goes through the extension,
 * which answers Facebook's name for the running clone and leaves every other name alone.
 */

/** The log line Facebook writes when an intent should start one of its own screens instead. */
internal const val INTERNAL_ACTIVITY_MARKER = "fix:shall_start_internal_activity_instead"

private const val COMPONENT_PACKAGE = "Landroid/content/ComponentName;->getPackageName()Ljava/lang/String;"
private const val NAME_EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/**
 * Whether the write at [writer] loads Facebook's name: as a constant, or as the answer of a string
 * pool (a static lookup by an int), which is how 585 has it.
 */
private fun List<Instruction>.loadsFacebooksName(writer: Int): Boolean {
    val code = this
    val load = code[writer]
    if (load.loadedString() == ORIGINAL) return true
    if (load.opcode != Opcode.MOVE_RESULT_OBJECT || writer == 0) return false
    val lookup = code[writer - 1]
    val pool = (lookup as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return (lookup.opcode == Opcode.INVOKE_STATIC || lookup.opcode == Opcode.INVOKE_STATIC_RANGE) &&
        pool.returnType == "Ljava/lang/String;" && pool.parameterTypes.map { it.toString() } == listOf("I")
}

/**
 * Where [this] compares an intent component's package with Facebook's name, only in a method that
 * writes [INTERNAL_ACTIVITY_MARKER]: for each such String.equals, the index right after the move
 * that takes getPackageName()'s answer, which is where the call goes, and that move's register.
 * The component's package must be read by the comparison alone, and nothing may jump onto the
 * spot, since the jump would pass the call.
 */
internal fun Method.ownComponentChecks(): List<Pair<Int, Int>> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    if (code.none { it.loadedString() == INTERNAL_ACTIVITY_MARKER }) return emptyList()
    val targets = branchTargets(code)
    return code.indices.mapNotNull { at ->
        val call = code[at]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || ((call as ReferenceInstruction).reference as? MethodReference)?.toString() != NAME_EQUALS) {
            return@mapNotNull null
        }
        val sides = (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) }
        if (sides[0] == sides[1]) return@mapNotNull null
        val writers = sides.map { writersReaching(at, it).singleOrNull() ?: return@mapNotNull null }
        val component = writers.indices.singleOrNull { side ->
            val writer = writers[side]
            writer >= 1 && code[writer].opcode == Opcode.MOVE_RESULT_OBJECT &&
                code[writer - 1].opcode == Opcode.INVOKE_VIRTUAL &&
                ((code[writer - 1] as ReferenceInstruction).reference as? MethodReference)?.toString() == COMPONENT_PACKAGE
        } ?: return@mapNotNull null
        val other = writers[1 - component]
        if (other < 0 || !code.loadsFacebooksName(other)) return@mapNotNull null
        val writer = writers[component]
        val register = (code[writer] as OneRegisterInstruction).registerA
        if (register > 255 || writer + 1 in targets || readsAfter(writer, register) != listOf(at)) return@mapNotNull null
        writer + 1 to register
    }
}

/** Sends the component's package through [AS_FACEBOOK_CALL] at every [ownComponentChecks] site. Answers how many. */
internal fun BytecodePatchContext.routeOwnComponentChecks(): Int {
    val owners = classDefByStrings(INTERNAL_ACTIVITY_MARKER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .filter { owner -> owner.methods.any { it.ownComponentChecks().isNotEmpty() } }
        .map { it.type }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method ->
            val checks = method.ownComponentChecks()
            checks.sortedByDescending { it.first }.forEach { (at, register) ->
                method.addInstructions(
                    at,
                    """
                        invoke-static/range { v$register .. v$register }, $AS_FACEBOOK_CALL
                        move-result-object v$register
                    """,
                )
            }
            checks.size
        }
    }
}

/** Whether App name already sends the label of the task description at [at] through [TASK_LABEL]. */
internal fun Method.taskAlreadyLabelled(at: Int): Boolean {
    val code = implementation?.instructions?.toList() ?: return false
    return ((code.getOrNull(at - 2) as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == TASK_LABEL
}

/**
 * The recent apps label, the way App name puts it on the task, so the clone's task reads the
 * clone's name. Each holder is read again as patched so far, so a site App name already hooked
 * is seen at its new index and left alone. Answers how many it hooked.
 */
internal fun BytecodePatchContext.labelCloneTasks(): Int {
    val holders = mutableListOf<String>()
    classDefForEach { if (!it.type.startsWith(EXTENSION_ROOT) && taskLabels(it).isNotEmpty()) holders += it.type }
    var hooked = 0
    for (type in holders.distinct()) {
        val holder = mutableClassDefBy(type)
        val open = taskLabels(holder).filterNot { it.method.taskAlreadyLabelled(it.at) }
        // Highest index first, so the ones before it in the same method stay where they were.
        for (label in open.sortedByDescending { it.at }) {
            holder.findMutableMethodOf(label.method).labelsTaskWithAppName(label.at, label.label)
            hooked++
        }
    }
    return hooked
}

private class CloneRequest(val packageName: String, val label: String)

private var cloneRequest: CloneRequest? = null

/** Hands the checked name and label to the manifest rename, which runs once every other patch has executed. */
internal fun requestClone(packageName: String, label: String) {
    if (!isClonePackage(packageName)) fail("$packageName can't be used as a package name")
    if (!isCloneLabel(label)) fail("\"$label\" can't be used as an app name")
    cloneRequest = CloneRequest(packageName, label)
}

internal val cloneResources = resourcePatch {
    execute {
        cloneRequest = null
    }
    // After every other patch has added its providers and permission names (Search photo's provider among them).
    finalize {
        val request = cloneRequest ?: return@finalize
        cloneRequest = null
        // App name may have decoded it already. Decoding it again would read it back from the APK.
        if (!get(CLONE_STRINGS, false).exists()) decode(CLONE_STRINGS)
        if (!get(CLONE_STRINGS, false).exists()) fail("Facebook has no $CLONE_STRINGS to hold the app name")
        val renamed = document("AndroidManifest.xml").use { it.renameForClone(request.packageName, request.label) }
        document(CLONE_STRINGS).use { it.putAppName(request.label, CLONE_LABEL) }
        patchLog.info(
            "$CLONE_PATCH_NAME: installs as ${request.packageName}, called \"${request.label}\". Moved " +
                "${renamed.permissions.size} permissions, ${renamed.authorities.size} provider authorities, " +
                "${renamed.affinities} task affinities and ${renamed.pushCategories} push categories.",
        )
    }
}

@Suppress("unused")
val cloneInstallPatch = bytecodePatch(
    name = "Clone install under another package name",
    description = "Installs a second copy of Facebook beside the first under its own package name and app name, " +
        "for a phone where Facebook came preinstalled and a patched one won't install over it. Hushfacebook " +
        "settings open in the copy the same way. It isn't selected by default because push notifications may " +
        "not reach the copy, and you only need it for a second Facebook. Turn on Expert mode in Manager to pick " +
        "it. Works as soon as you patch it in, with no switch.",
    default = false,
) {
    category("Fixes")
    dependsOn(settingsPatch, installBesideMetaAppsPatch, cloneResources, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    val packageName by stringOption(
        key = "clonePackageName",
        default = CLONE_DEFAULT_PACKAGE,
        title = "Package name",
        description = "The copy's unique app ID, like com.facebook.katana.hush. Use words joined by dots, each " +
            "starting with a letter. It can't match Facebook or another Meta app.",
        required = true,
    ) { isClonePackage(it) }

    val appLabel by stringOption(
        key = "cloneAppName",
        default = CLONE_DEFAULT_LABEL,
        title = "App name",
        description = "The name shown under the copy's home screen icon. Up to 40 characters, starting with a letter or number.",
        required = true,
    ) { isCloneLabel(it) }

    execute {
        val target = packageName?.takeIf(::isClonePackage) ?: fail("$packageName can't be used as a package name")
        val label = appLabel?.takeIf(::isCloneLabel) ?: fail("\"$appLabel\" can't be used as an app name")
        requireStatusMethod("cloneInstall")
        if (routeOwnComponentChecks() == 0) {
            patchLog.warning(
                "$CLONE_PATCH_NAME: found no place where Facebook checks that an intent opens one of its own " +
                    "screens, so some links inside the copy may open as if they came from outside.",
            )
        }
        labelCloneTasks()
        enableStatus("cloneInstall")
        requestClone(target, label)
    }
}
