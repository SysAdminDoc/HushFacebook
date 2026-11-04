/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * What Morphe's Clone app patch leaves for this bundle to finish, so a renamed copy of Facebook
 * installs and starts beside the Facebook it was cloned from (#16).
 *
 * Clone app renames the package in its finalize block. With Update permissions on it renames each
 * permission Facebook declares, com.facebook.katana.X to <package>.X and any other name to
 * <package>_<name>, and the first <uses-permission> of each, but not the components that require
 * one: they went on naming permissions only the stock app declares. With it off, which is its
 * default, the declarations keep the stock names, and Android refuses the clone beside a Facebook
 * signed with another key (INSTALL_FAILED_DUPLICATE_PERMISSION). With Update providers on it
 * renames the provider authorities the same way. With it off they stay the stock app's, and Android
 * refuses a second app claiming one (INSTALL_FAILED_CONFLICTING_PROVIDER). And Facebook's code
 * spells some of its own authorities out, content://com.facebook.katana.ClientMessagePushDedupInfoProvider/mutestatus
 * among them, so a clone started up by deleting through the stock app's provider, which turned it
 * away, and with Update permissions on Android refused it before that.
 *
 * Morphe executes the patches it's given sorted by name, each after its dependencies, and runs the
 * finalize blocks in the reverse order, so this one's finalize sees Clone app's work only when it
 * executed before "Clone app". Every patch depends on the settings patch, which depends on this,
 * and the default selection has patches whose names sort before "Clone app" (ClonedPackageOrderTest
 * holds both). A selection without any of them leaves the manifest as Clone app wrote it. The code
 * half doesn't depend on the order: the extension reads the package at run time.
 *
 * There's no way to make this run after "Clone app" whatever the reader picks: Morphe sorts and
 * finalizes by what got selected, "Clone app" comes from a different bundle this one can't depend
 * on, and a patch has no way to ask what else was selected. So a selection holding only patches
 * whose names sort after "Clone app" (ClonedPackageOrderTest.aSelectionOfOnlyLaterNamedPatchesRunsCloneAppFirst
 * shows one) leaves [followRenamedPackage] running before the rename, with nothing to follow yet.
 * It can't tell that apart from an ordinary install either, so it says what to add at fine level
 * instead of warning on every build that was never a clone.
 */

/** The call each of Facebook's own authority literals goes through, into the register it was loaded into. */
internal const val AUTHORITY_CALL =
    "$EXTENSION_PACKAGE/coexist/OwnAuthorities;->name(Ljava/lang/String;)Ljava/lang/String;"

internal const val CONTENT = "content://"

private fun Document.elements(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

/** Facebook's own provider authorities: every entry of a provider's list that's under [packageName]. */
internal fun Document.ownAuthorities(packageName: String): Set<String> = elements("provider")
    .flatMap { it.getAttribute("android:authorities").split(';') }
    .map { it.trim() }
    .filter { it.startsWith("$packageName.") }
    .toSet()

/** Facebook's own permissions: every one it declares under [packageName]. */
internal fun Document.ownPermissions(packageName: String): Set<String> = elements("permission")
    .map { it.getAttribute("android:name") }
    .filter { it.startsWith("$packageName.") }
    .toSet()

/**
 * What following a renamed package moved: mentions of a permission, authorities, and declarations
 * Clone app left under the stock package.
 */
internal data class Followed(val permissions: Int, val authorities: Int, val declarations: Int = 0)

/**
 * After something renamed the package away from [originalPackage]: moves every permission still
 * declared under one of [stockPermissions] under the new package, which is where Clone app's Update
 * permissions would have put it, points every mention of a permission this manifest no longer
 * declares at the renamed declaration, and moves every authority still in [stockAuthorities] under
 * the new package, which is where its Update providers would have put it. Answers what it moved, or
 * null when the package is still [originalPackage], and then it changes nothing but a fine message,
 * since this could be an ordinary install as easily as a clone whose "Clone app" hasn't finalized yet.
 *
 * Facebook's five own permissions are signature permissions, so a clone that still declares one
 * under the stock name can't install beside Facebook signed with another key, Meta's own included
 * (INSTALL_FAILED_DUPLICATE_PERMISSION, #60). Its code names two of them after the running package
 * (the cross-process broadcast manager's and AndroidX's not-exported receiver's), so in a clone
 * those only work once the declarations moved too.
 */
internal fun Document.followRenamedPackage(
    originalPackage: String,
    stockAuthorities: Set<String>,
    stockPermissions: Set<String>,
): Followed? {
    val renamedTo = documentElement.getAttribute("package")
    if (renamedTo.isEmpty() || renamedTo == originalPackage) {
        patchLog.fine(
            "The package is still $originalPackage, so there's nothing to follow yet. If you're patching " +
                "with Clone app and the clone still doesn't start beside the app it came from, also select a " +
                "patch whose name sorts before \"Clone app\", such as Block ad telemetry, which is on by " +
                "default: Morphe finalizes in reverse name order, and this step has to run after Clone app's " +
                "to see the renamed manifest.",
        )
        return null
    }

    fun movedUnder(name: String) = renamedTo + name.removePrefix(originalPackage)
    var declarations = 0
    for (declaration in elements("permission")) {
        val name = declaration.getAttribute("android:name")
        if (name !in stockPermissions) continue
        declaration.setAttribute("android:name", movedUnder(name))
        declarations++
    }

    val declared = elements("permission").map { it.getAttribute("android:name") }.toSet()
    fun cloneDeclarationOf(name: String): String? = listOfNotNull(
        movedUnder(name).takeIf { name.startsWith("$originalPackage.") },
        "${renamedTo}_$name",
    ).firstOrNull { it in declared }

    var permissions = 0
    for (element in elements("*")) {
        val attributes = element.attributes
        for (at in 0 until attributes.length) {
            val attribute = attributes.item(at) as? Attr ?: continue
            if (element.tagName == "permission" && attribute.nodeName == "android:name") continue
            if (attribute.value in declared) continue
            val clone = cloneDeclarationOf(attribute.value) ?: continue
            attribute.value = clone
            permissions++
        }
    }

    var authorities = 0
    for (provider in elements("provider")) {
        val entries = provider.getAttribute("android:authorities").split(';')
        val moved = entries.map { entry ->
            val authority = entry.trim()
            if (authority !in stockAuthorities) return@map entry
            authorities++
            entry.replace(authority, movedUnder(authority))
        }
        if (moved != entries) provider.setAttribute("android:authorities", moved.joinToString(";"))
    }
    return Followed(permissions, authorities, declarations)
}

/** The authority [this] names when it's one of [authorities] or a content:// address on one, else null. */
internal fun String.ownAuthority(authorities: Set<String>): String? {
    if (this in authorities) return this
    if (!startsWith(CONTENT)) return null
    return removePrefix(CONTENT).takeWhile { it != '/' && it != '?' && it != '#' }.takeIf { it in authorities }
}

/**
 * Hands every literal of Facebook's code naming one of its own [authorities] to the extension,
 * which answers the running app's. Answers how many it handed over. Every one is under
 * [packageName], which the string index finds with a prefix, and the extension is left alone.
 */
internal fun BytecodePatchContext.routeOwnAuthorities(packageName: String, authorities: Set<String>): Int {
    val owners = listOf("$packageName.", "$CONTENT$packageName.")
        .flatMap { classDefByStrings(it, StringComparisonType.STARTS_WITH) }
        .map { it.type }
        .filterNot { it.startsWith(EXTENSION_ROOT) }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods
            .filter { method -> method.implementation?.instructions?.any { it.loadedString()?.ownAuthority(authorities) != null } == true }
            .sumOf { it.routeLiterals(AUTHORITY_CALL, "Clone support", "an authority") { literal -> literal.ownAuthority(authorities) != null } }
    }
}

/*
 * Other authorities aren't spelled out anywhere: Facebook puts them together from BuildConstants'
 * name for the Facebook app, "com.facebook.katana", and a suffix, BuildConstants.A04() +
 * ".provider.CacheProvider" in 584 (or a pattern, "content://%s.provider.AttributionIdProvider").
 * In a clone that named the stock app's provider, whose permission the clone doesn't hold, and
 * Android closed the clone as soon as its full-screen viewer (ImmersiveActivity) resumed and asked
 * that provider for a cached value (#16).
 * Wherever such a name is put together, the result goes through the extension, which answers the
 * running app's authority. A suffix after anything else is left alone: the phone id requests put
 * it after each Meta app's package, and the platform and photos providers take the package from
 * Facebook's injector.
 */

internal const val BUILD_CONSTANTS = "Lcom/facebook/common/build/BuildConstants;"

private const val STRING = "Ljava/lang/String;"

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

private val FILLED_ARRAYS = setOf(Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE)

/** Whether [this] names one of [authorities] after [packageName], or with [packageName] in place of its %s. */
internal fun String.buildsOwnAuthority(packageName: String, authorities: Set<String>): Boolean =
    if (contains("%s")) replace("%s", packageName).ownAuthority(authorities) != null else (packageName + this) in authorities

/** The registers a call or filled array passes, in order, or null for anything else. */
private fun Instruction.passedRegisters(): List<Int>? = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> null
}

/**
 * Whether what [register] holds as the instruction at [index] starts is always a String one of
 * BuildConstants' methods answers, or (with [arrays]) an array filled with nothing else.
 */
private fun Method.holdsBuildConstantsName(code: List<Instruction>, index: Int, register: Int, arrays: Boolean = true): Boolean {
    val writers = writersReaching(index, register)
    return writers.isNotEmpty() && writers.all { at ->
        if (code.getOrNull(at)?.opcode != Opcode.MOVE_RESULT_OBJECT) return@all false
        val made = code.getOrNull(at - 1) ?: return@all false
        when (made.opcode) {
            in STATIC_CALLS -> ((made as ReferenceInstruction).reference as? MethodReference)
                ?.let { it.definingClass == BUILD_CONSTANTS && it.returnType == STRING && it.parameterTypes.isEmpty() } == true
            in FILLED_ARRAYS -> arrays && made.passedRegisters()!!.all { holdsBuildConstantsName(code, at - 1, it, arrays = false) }
            else -> false
        }
    }
}

/**
 * Where [this] puts one of [authorities] together from BuildConstants' name: the index of each
 * move-result-object taking the String a static call answers from a suffix literal
 * ([buildsOwnAuthority]) and BuildConstants' names alone ([holdsBuildConstantsName]). The call goes
 * right after it. A spot something jumps onto is left out, since the jump would pass the call.
 */
internal fun Method.builtAuthorityResults(packageName: String, authorities: Set<String>): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    val suffixes = code.indices.filter { code[it].loadedString()?.buildsOwnAuthority(packageName, authorities) == true }
    if (suffixes.isEmpty()) return emptyList()
    val targets = branchTargets(code)
    return suffixes.flatMap { at ->
        val suffix = (code[at] as OneRegisterInstruction).registerA
        readsAfter(at, suffix).filter { call ->
            val built = code.getOrNull(call + 1)
            val arguments = code[call].passedRegisters()
            code[call].opcode in STATIC_CALLS &&
                ((code[call] as ReferenceInstruction).reference as? MethodReference)?.returnType == STRING &&
                built?.opcode == Opcode.MOVE_RESULT_OBJECT && (built as OneRegisterInstruction).registerA <= 255 &&
                call + 2 !in targets &&
                arguments != null && arguments.count { it == suffix } == 1 &&
                arguments.filter { it != suffix }.let { names -> names.isNotEmpty() && names.all { holdsBuildConstantsName(code, call, it) } }
        }.map { it + 1 }
    }.distinct().sorted()
}

/**
 * Hands every authority Facebook's code puts together from BuildConstants' name
 * ([builtAuthorityResults]) to the extension, which answers the running app's. Answers how many it
 * handed over. The classes come from the string index by the literals that put one together
 * ([buildsOwnAuthority]): each suffix of an authority under [packageName], alone or after a %s, and
 * any content:// address starting with a %s and a dot, which may go on with a path. A search by
 * suffix read the whole index once per authority (#216); these read it at most once. The extension
 * is left alone.
 */
internal fun BytecodePatchContext.routeBuiltAuthorities(packageName: String, authorities: Set<String>): Int {
    val suffixes = authorities.filter { it.startsWith("$packageName.") }.map { it.removePrefix(packageName) }
    val owners = suffixes.flatMap { suffix -> listOf(suffix, "%s$suffix") }
        .flatMap { classDefByStrings(it, StringComparisonType.EQUALS) }
        .plus(classDefByStrings("$CONTENT%s.", StringComparisonType.STARTS_WITH))
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .distinctBy { it.type }
        .filter { owner -> owner.methods.any { it.builtAuthorityResults(packageName, authorities).isNotEmpty() } }
        .map { it.type }
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method ->
            val results = method.builtAuthorityResults(packageName, authorities)
            results.asReversed().forEach { at ->
                val register = method.getInstruction<OneRegisterInstruction>(at).registerA
                method.addInstructions(
                    at + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $AUTHORITY_CALL
                        move-result-object v$register
                    """,
                )
            }
            results.size
        }
    }
}

/*
 * Facebook's rich notification templates build their RemoteViews with the package spelled out,
 * new RemoteViews("com.facebook.katana", layout), 14 times in 582. RemoteViews takes its layouts
 * from that package, so in a clone a notification either named an app that isn't installed, which
 * throws, or the Facebook it was cloned from, whose layout ids aren't the clone's, and Android
 * refused it as a bad notification and closed the clone (#36). Each of those loads is handed to the
 * extension, which answers the running package.
 */

/** The call Facebook's package name goes through where it's loaded only to build RemoteViews. */
internal const val PACKAGE_CALL =
    "$EXTENSION_PACKAGE/coexist/OwnAuthorities;->packageName(Ljava/lang/String;)Ljava/lang/String;"

internal const val REMOTE_VIEWS_INIT = "Landroid/widget/RemoteViews;-><init>(Ljava/lang/String;I)V"

/** Whether [instruction] builds a RemoteViews from the package name in [register] and reads it for nothing else. */
private fun buildsRemoteViewsOf(instruction: Instruction, register: Int): Boolean {
    if (instruction.opcode != Opcode.INVOKE_DIRECT) return false
    if (((instruction as ReferenceInstruction).reference as? MethodReference)?.toString() != REMOTE_VIEWS_INIT) return false
    val call = instruction as FiveRegisterInstruction
    return call.registerD == register && call.registerC != register && call.registerE != register
}

/**
 * Where [this] loads [packageName] as a literal only to build RemoteViews with it: the index of each
 * const-string whose value reaches nothing but RemoteViews(String, int)'s package before its register
 * is written again. A load something jumps in right after is left out, since the jump would pass
 * the call that goes there, and so is a value anything else reads.
 */
internal fun Method.remoteViewsPackageLoads(packageName: String): List<Int> =
    packageLoadsReadOnlyBy(packageName, ::buildsRemoteViewsOf)

/*
 * The rest of the bare package name (#36 leftovers). Where it means this app, a clone has to get
 * its own: Firebase's notification code (FirebaseMessagingService, a kept name) sets the intent a
 * notification's tap sends to the package, launches the package when that finds nothing, and reads
 * the package's meta-data for the notification's icon, colour and channel, all of them
 * context.getPackageName() in Google's source that Facebook's build spelled out; and a chat
 * notification's intent is set to the package. In a clone those reach the stock app or nothing.
 *
 * The same goes for the other places that can only mean the running app. An ApplicationInfo or an
 * icon of the package: Firebase's own-app meta-data ("Couldn't get own application info"), the
 * build revision Facebook reads from its manifest's meta-data, and AppInfoCore. An explicit intent
 * to one of its own activities: ProxyAuth, which a login deep link, the consent deep link and the
 * device-login module open by setClassName(package, class). The package Xiaomi's launcher badge is
 * set for. And a resource lookup: Morphe renames the resource table with the package, so in a clone
 * getIdentifier(name, type, "com.facebook.katana") finds nothing, which left Firebase's
 * notification text, icon and sound unresolved. A load a resource lookup reads is handed over
 * whole, because whatever else reads that same name builds from the same table: Firebase's sound
 * goes on to make android.resource://<package>/raw/<name> out of it.
 *
 * Left alone, because there the name means Meta's Facebook app: getPackageInfo reads of its
 * version or signatures (the in-app browser's FB4A version check among them), String.equals of a
 * caller's or a target's package against it (FBNS push's "am I FB4A" check among them, which push
 * in a clone has to stay native for), the launch of Facebook after a check that it or its in-house
 * build is installed (Horizon's link launcher), the pick of whichever Facebook app is installed to
 * open a link, the installer reads of Facebook for attribution and Google Play's overlay, the sender
 * name Facebook puts in an extra for another app (Play's install overlay callerId, WhatsApp's status
 * share, Meta's family-app FOA_EVENT_SENDER broadcast; 585 census, 2026-11-02), and
 * Messenger's warm-start hint (ThreadSettingsActivity), which checks that Facebook is installed and
 * then sends its WarmStartBroadcastReceiver a broadcast. A load that anything else reads, such as
 * that install check or a ComponentName, stays as it is. SharedPermissionsFixtureTest holds each
 * declared build's list of both.
 */

internal const val SET_PACKAGE = "Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;"
internal const val SET_CLASS_NAME =
    "Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"
internal const val LAUNCH_INTENT =
    "Landroid/content/pm/PackageManager;->getLaunchIntentForPackage(Ljava/lang/String;)Landroid/content/Intent;"
internal const val APPLICATION_INFO =
    "Landroid/content/pm/PackageManager;->getApplicationInfo(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;"
internal const val APPLICATION_ICON =
    "Landroid/content/pm/PackageManager;->getApplicationIcon(Ljava/lang/String;)Landroid/graphics/drawable/Drawable;"
internal const val PROVIDER_CALL =
    "Landroid/content/ContentResolver;->call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;"
internal const val RESOURCE_ID = "Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I"
internal const val REDEX_RESOURCE_ID =
    "Lcom/facebook/resources/compat/RedexResourcesCompat;->getIdentifier(Landroid/content/res/Resources;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I"
internal const val FIREBASE_MESSAGING = "Lcom/google/firebase/messaging/FirebaseMessagingService;"

/** Each call that names this app to Android wherever it's made, with the argument the name goes in (0 is the receiver). */
private val OWN_PACKAGE_ARGUMENTS = mapOf(
    SET_PACKAGE to 1,
    SET_CLASS_NAME to 1,
    APPLICATION_INFO to 1,
    APPLICATION_ICON to 1,
    PROVIDER_CALL to 3,
)

/** Each resource lookup, with the argument its package goes in. */
private val RESOURCE_PACKAGE_ARGUMENTS = mapOf(RESOURCE_ID to 3, REDEX_RESOURCE_ID to 3)

/** The launch intent Firebase's notification code asks for when nothing else opens. */
private val FIREBASE_PACKAGE_ARGUMENTS = mapOf(LAUNCH_INTENT to 1)

private val CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC_RANGE)

/** Whether [instruction] calls one of [calls] with [register] as the argument named there and as no other. */
private fun passesOnlyAs(instruction: Instruction, register: Int, calls: Map<String, Int>): Boolean {
    if (instruction.opcode !in CALLS) return false
    val called = ((instruction as ReferenceInstruction).reference as? MethodReference)?.toString() ?: return false
    val position = calls[called] ?: return false
    val arguments = instruction.passedRegisters() ?: return false
    return arguments.getOrNull(position) == register && arguments.count { it == register } == 1
}

/**
 * Whether [instruction] hands the package name in [register] to a call that means this app, and
 * reads it for nothing else: one of [OWN_PACKAGE_ARGUMENTS] anywhere, and in Firebase's notification
 * code ([firebase]) a launch intent for it as well.
 */
private fun callsOwnPackage(instruction: Instruction, register: Int, firebase: Boolean): Boolean =
    passesOnlyAs(instruction, register, OWN_PACKAGE_ARGUMENTS) ||
        (firebase && passesOnlyAs(instruction, register, FIREBASE_PACKAGE_ARGUMENTS))

/**
 * Where [this] loads [packageName] only to name this app to Android ([callsOwnPackage]), apart from
 * the failure log in that call's catch block, or to look up one of its resources, whatever else
 * reads it then.
 */
internal fun Method.ownPackageCallLoads(packageName: String): List<Int> {
    val firebase = definingClass == FIREBASE_MESSAGING
    return packageLoadsReadBy(packageName) { readers, code, register ->
        val calls = readers.filter { callsOwnPackage(code[it], register, firebase) }
        calls.isNotEmpty() && (readers - calls.toSet()).all { it in failureHandlerCode(calls, code) } ||
            readers.any { passesOnlyAs(code[it], register, RESOURCE_PACKAGE_ARGUMENTS) }
    }
}

/**
 * The instructions of the catch blocks guarding any of [calls], from each handler's start to the
 * first return, throw or goto. 585's AppInfoCore hands the package it asked about to its "Package
 * not found" log there, which may as well name the running app.
 */
private fun Method.failureHandlerCode(calls: List<Int>, code: List<Instruction>): Set<Int> {
    val tries = implementation?.tryBlocks.orEmpty()
    if (tries.isEmpty()) return emptySet()
    val addresses = code.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOfAddress = addresses.withIndex().associate { (index, address) -> address to index }
    val ends = setOf(
        Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_VOID, Opcode.RETURN_WIDE,
        Opcode.THROW, Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32,
    )
    val inside = mutableSetOf<Int>()
    for (block in tries) {
        val guarded = block.startCodeAddress until block.startCodeAddress + block.codeUnitCount
        if (calls.none { addresses[it] in guarded }) continue
        for (handler in block.exceptionHandlers) {
            var at = indexOfAddress[handler.handlerCodeAddress] ?: continue
            while (at < code.size) {
                inside += at
                if (code[at].opcode in ends) break
                at++
            }
        }
    }
    return inside
}

/**
 * The index of each const-string of [packageName] in [this] whose value only [reads] reads before
 * its register is written again. A load a branch or switch case lands right after is left out, since
 * the jump would pass the call that goes there.
 */
private fun Method.packageLoadsReadOnlyBy(packageName: String, reads: (Instruction, Int) -> Boolean): List<Int> =
    packageLoadsReadBy(packageName) { readers, code, register -> readers.all { reads(code[it], register) } }

/**
 * The index of each const-string of [packageName] in [this] that something reads before its
 * register is written again and whose readers [take] accepts. A load a branch or switch case lands
 * right after is left out, since the jump would pass the call that goes there.
 */
private fun Method.packageLoadsReadBy(
    packageName: String,
    take: (readers: List<Int>, code: List<Instruction>, register: Int) -> Boolean,
): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    val loads = code.indices.filter { code[it].loadedString() == packageName }
    if (loads.isEmpty()) return emptyList()
    val targets = branchTargets(code)
    return loads.filter { at ->
        val register = (code[at] as OneRegisterInstruction).registerA
        if (at + 1 in targets || register > 255) return@filter false
        val readers = readsAfter(at, register)
        readers.isNotEmpty() && take(readers, code, register)
    }
}

/**
 * Hands every load of [packageName] that only builds RemoteViews to the extension, which answers
 * the running app's package. Answers how many it handed over. The extension is left alone.
 */
internal fun BytecodePatchContext.routeRemoteViewsPackage(packageName: String): Int =
    routePackageLoads(packageName) { it.remoteViewsPackageLoads(packageName) }

/**
 * Hands every load of [packageName] that names this app to Android or looks up its resources
 * ([ownPackageCallLoads]) to the extension, which answers the running app's package. Answers how
 * many it handed over.
 */
internal fun BytecodePatchContext.routeOwnPackageCalls(packageName: String): Int =
    routePackageLoads(packageName) { it.ownPackageCallLoads(packageName) }

/*
 * The in-app browser's child process connection answers getPackageName() for the package its
 * renderer service (SandboxedProcessService) lives in. Redex put Facebook's literal where
 * context.getPackageName() was and left the call with its answer unused, so a clone's in-app
 * browser bound the stock app's renderers, or none when the stock app isn't installed. Meta's app
 * table, which answers the package from a switch among nine others and asks Android nothing, keeps
 * the literal.
 */

/**
 * Where [this], a getPackageName() override, answers [packageName] as the running app's: its only
 * string load, which nothing but the return reads, in a method that still calls
 * Context.getPackageName() for the answer Redex replaced.
 */
internal fun Method.ownPackageNameAnswers(packageName: String): List<Int> {
    if (name != "getPackageName" || parameterTypes.isNotEmpty() || returnType != "Ljava/lang/String;") return emptyList()
    val code = implementation?.instructions?.toList() ?: return emptyList()
    if (code.count { it.loadedString() != null } != 1) return emptyList()
    if (code.none { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == GET_PACKAGE_NAME }) {
        return emptyList()
    }
    return packageLoadsReadOnlyBy(packageName) { instruction, register ->
        instruction.opcode == Opcode.RETURN_OBJECT && (instruction as OneRegisterInstruction).registerA == register
    }
}

/**
 * Hands the package each getPackageName() override answers for the running app
 * ([ownPackageNameAnswers]) to the extension. Answers how many it handed over.
 */
internal fun BytecodePatchContext.routeOwnPackageNameAnswers(packageName: String): Int =
    routePackageLoads(packageName) { it.ownPackageNameAnswers(packageName) }

/** Hands each load [loadsOf] picks out to the extension, right after it. The extension is left alone. */
private fun BytecodePatchContext.routePackageLoads(packageName: String, loadsOf: (Method) -> List<Int>): Int {
    val owners = classDefByStrings(packageName, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .filter { owner -> owner.methods.any { loadsOf(it).isNotEmpty() } }
        .map { it.type }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method ->
            val loads = loadsOf(method)
            loads.asReversed().forEach { at ->
                val register = method.getInstruction<OneRegisterInstruction>(at).registerA
                method.addInstructions(
                    at + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $PACKAGE_CALL
                        move-result-object v$register
                    """,
                )
            }
            loads.size
        }
    }
}

/*
 * Facebook's encrypted chat backup (MibEbAutoRestoreHandler) builds a helper whose constructor
 * looks the running package up among Messenger's and Facebook's names (com.facebook.orca, its debug
 * build, com.facebook.katana and com.facebook.wakizashi) to pick its preferences file, and throws
 * NoSuchElementException when none matches. A clone's package matches none, so it crashed as soon as
 * chats opened (#113). The package name each such lookup reads is handed to the extension, which
 * answers Facebook's in a clone. Only methods that also name Messenger's debug build count, which in
 * 582 is that constructor alone (the other method naming it is a table that reads no package).
 */

/** The call the running package goes through where Facebook looks itself up among Meta's apps. */
internal const val AS_FACEBOOK_CALL =
    "$EXTENSION_PACKAGE/coexist/OwnAuthorities;->asFacebook(Ljava/lang/String;)Ljava/lang/String;"

internal const val GET_PACKAGE_NAME = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"

/** The name only Meta's own app lookups carry: Messenger's in-house build. */
internal const val MESSENGER_DEBUG = "com.facebook.orca.debug"

/**
 * Where [this] looks the running package up among Meta's apps: for each getPackageName() whose
 * result it moves into a register, the index right after that move, which is where the call goes.
 * A method that doesn't name [MESSENGER_DEBUG] has none, and a spot something jumps onto is left out,
 * since the jump would pass the call.
 */
internal fun Method.metaAppLookupReads(): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    if (code.none { it.loadedString() == MESSENGER_DEBUG }) return emptyList()
    val targets = branchTargets(code)
    return code.indices.filter { at ->
        val call = code[at]
        val moved = code.getOrNull(at + 1) ?: return@filter false
        call.opcode == Opcode.INVOKE_VIRTUAL &&
            ((call as ReferenceInstruction).reference as? MethodReference)?.toString() == GET_PACKAGE_NAME &&
            moved.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (moved as OneRegisterInstruction).registerA <= 255 &&
            at + 2 !in targets
    }.map { it + 2 }
}

/**
 * Hands the running package to the extension wherever Facebook looks itself up among Meta's apps
 * ([metaAppLookupReads]). Answers how many it handed over. The extension is left alone.
 */
internal fun BytecodePatchContext.routeMetaAppLookups(): Int {
    val owners = classDefByStrings(MESSENGER_DEBUG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .filter { owner -> owner.methods.any { it.metaAppLookupReads().isNotEmpty() } }
        .map { it.type }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method ->
            val reads = method.metaAppLookupReads()
            reads.asReversed().forEach { at ->
                val register = method.getInstruction<OneRegisterInstruction>(at - 1).registerA
                method.addInstructions(
                    at,
                    """
                        invoke-static/range { v$register .. v$register }, $AS_FACEBOOK_CALL
                        move-result-object v$register
                    """,
                )
            }
            reads.size
        }
    }
}

/** The stock app's own authorities and permissions, read before anything renames the package. */
private var stockAuthorities: Set<String> = emptySet()
private var stockPermissions: Set<String> = emptySet()

private val clonedPackageManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use {
            stockAuthorities = it.ownAuthorities(packageMetadata.packageName)
            stockPermissions = it.ownPermissions(packageMetadata.packageName)
        }
    }

    finalize {
        // The stock list is read before any patch runs, so the providers patches add since (Search
        // photo's) are read here, still under the stock package until this moves them.
        val followed = document("AndroidManifest.xml").use {
            it.followRenamedPackage(packageMetadata.packageName, stockAuthorities + it.ownAuthorities(packageMetadata.packageName), stockPermissions)
        } ?: return@finalize
        patchLog.info(
            "The package was renamed, so ${followed.declarations} permission declaration(s) still under the old name " +
                "moved under the new one, ${followed.permissions} mention(s) of a permission now name the renamed " +
                "declaration, and ${followed.authorities} provider authorities moved under the new name.",
        )
    }
}

/**
 * Makes a copy renamed with Morphe's Clone app its own app: its manifest names only permissions it
 * declares and authorities it owns, and its code reaches its own providers. On an install nobody
 * renamed, the manifest is left as it was and the extension hands every authority back unchanged.
 */
internal val clonedPackagePatch = bytecodePatch {
    dependsOn(clonedPackageManifestPatch, facebookExtensionPatch)

    execute {
        routeOwnAuthorities(packageMetadata.packageName, stockAuthorities)
        routeBuiltAuthorities(packageMetadata.packageName, stockAuthorities)
        routeRemoteViewsPackage(packageMetadata.packageName)
        routeOwnPackageCalls(packageMetadata.packageName)
        routeOwnPackageNameAnswers(packageMetadata.packageName)
        if (routeMetaAppLookups() == 0) {
            patchLog.warning(
                "Found no place where Facebook looks itself up among Meta's apps, so a copy made with Clone app " +
                    "may still close when its chats open.",
            )
        }
    }
}
