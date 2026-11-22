/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.executeBytecode
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val SANITIZE =
    "Lapp/morphe/extension/facebook/misc/LinkCleaner;->sanitizeShared(Ljava/lang/String;)Ljava/lang/String;"

/**
 * Takes Facebook's tracking tags off the links the app hands out when someone shares.
 *
 * Each method that adds a tag to a shared link has its answer passed through the extension on
 * the way out, at every return. With the switch off, paused, or before the settings are ready, the
 * extension hands the link back as it came, so Facebook's own code runs as it always did. The
 * methods keep doing everything else they do, their logging included. What changes is only the
 * link that leaves.
 *
 * Found by reading 580 and 577 (2026-09-25): ExternalShareTracker adds `mibextid` to Copy link and
 * to every share destination, and on some servers `extid`, a random id new on each share. Around
 * it, one appender adds `sfnsn` to WhatsApp shares, one `ref=share` to some stories, and the live
 * video dialog `sfnsn`. A group's share had a link builder of its own up to 587; since 588 it asks
 * the tracker like every other share. On 582, Copy link on a reel or a post adds `s` and `fs` after
 * the tracker has answered, in three methods, so the link each one labels is passed through as well.
 *
 * A second switch, off to start, answers the /share/ link itself (#98): with it on, the link a
 * share of a post hands out is the post's own address instead of the facebook.com/share/ link
 * Facebook made for that share. Its hook is ownPostLinkPatch's.
 */
@Suppress("unused")
val sanitizeSharingLinksPatch = patch(
    name = "Sanitize sharing links",
    description = "Takes Facebook's tracking tags off links you share or copy, so you share a clean link. A " +
        "facebook.com/share/ link is made for one share, so Facebook can still trace that kind back to you, and " +
        "a switch that starts off shares the post's own link in its place. On by default. Both are in " +
        "Hushfacebook settings > Links and sharing.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch, ownPostLinkPatch)

    executeBytecode {
        val tracker = mutableClassDefBy(ExternalShareTrackerFingerprint.method.definingClass)
        val trackerMethods = listOf(
            // The link a share hands out, with mibextid added for its source and destination.
            ExternalShareTrackerFingerprint.method,
            // The same for a /share/ link, and Send in Messenger calls it directly.
            shareLinkTracker(tracker.methods),
            // extid: a random id, new on every share, logged beside the link under the sharer.
            extidTracker(tracker.methods),
        )

        (trackerMethods + listOf(
            SfnsnAppenderFingerprint.method,
            RefShareAppenderFingerprint.method,
            LiveShareLinkFingerprint.method,
        )).forEach { it.sanitizeEveryReturn() }

        // Copy link on a reel or a post: the labels go on after the tracker has answered.
        val labelled = classDefByStrings(COPY_LABEL_END_KEY, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner ->
                val mutableOwner = mutableClassDefBy(owner.type)
                mutableOwner.methods.mapNotNull { method -> copyLabelSites(method).takeIf { it.isNotEmpty() }?.let { method to it } }
            }
        if (labelled.isEmpty()) {
            patchLog.warning("Sanitize sharing links: no link with Facebook's copy labels found. The patch goes on, " +
                "and Copy link on a reel may keep s and fs.")
        }
        labelled.forEach { (method, sites) -> method.sanitizeAfter(sites) }

        enableStatus("sanitizeSharingLinks")
    }
}

/** The copy labels Facebook adds to a link copied from a reel or a post: `s=yWDuG2&fs=e` on 582. */
internal const val COPY_LABEL_KEY = "s"
internal const val COPY_LABEL_END_KEY = "fs"

/**
 * Where [method] holds a link with Facebook's copy labels as a string: the move-result of the first
 * call after the labels that answers a String. Each place appends `s` through Uri.Builder and hands
 * `fs` to a helper Redex outlined, which on 582 answers the link (`ZEO.A0v`) or a Uri the next call
 * turns into one (`Gwp.A06`). The tracker answers before the labels go on, so its hook misses them.
 */
internal fun copyLabelSites(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices
        .filter { code[it].constString() == COPY_LABEL_END_KEY && appendsLabelBefore(code, it) }
        .mapNotNull { stringResultAfter(code, it) }
        // Two labels close together can end in the same string; it gets cleaned once.
        .distinct()
}

/** A `const-string "s"` and a Uri.Builder append among the three instructions before [at]. */
private fun appendsLabelBefore(code: List<Instruction>, at: Int): Boolean {
    val before = code.subList(maxOf(0, at - 3), at)
    return before.any { it.constString() == COPY_LABEL_KEY } && before.any {
        val called = (it as? ReferenceInstruction)?.reference as? MethodReference
        called?.definingClass == "Landroid/net/Uri\$Builder;" && called.name == "appendQueryParameter"
    }
}

/** The move-result of the first call after [from] that answers a String, with no branch on the way. */
private fun stringResultAfter(code: List<Instruction>, from: Int): Int? {
    for (index in from + 1 until minOf(code.size - 1, from + 7)) {
        val instruction = code[index]
        if (!instruction.opcode.canContinue() || instruction is OffsetInstruction) return null
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (called.returnType == "Ljava/lang/String;" && code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT) return index + 1
    }
    return null
}

private fun Instruction.constString(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO)
        ((this as ReferenceInstruction).reference as StringReference).string else null

/**
 * Sends the link each site's move-result holds through the extension, right after it. The hooks
 * go in from last to first, because an insert moves every later index, and a branch that lands
 * after a site never held the labelled link, so it skips the hook.
 */
internal fun MutableMethod.sanitizeAfter(sites: List<Int>) {
    val code = implementation?.instructions?.toList() ?: throw PatchException("$definingClass->$name has no body")
    sites.sortedDescending().forEach { site ->
        val register = (code[site] as OneRegisterInstruction).registerA
        addInstructions(
            site + 1,
            """
                invoke-static/range { v$register .. v$register }, $SANITIZE
                move-result-object v$register
            """,
        )
    }
}

/** ExternalShareTracker's public (session, Integer, String) method that returns a /share/ link. */
internal fun <T : Method> shareLinkTracker(methods: Iterable<T>): T = methods.filter {
    it.returnType == "Ljava/lang/String;" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(CharSequence::toString) == listOf(FB_USER_SESSION, "Ljava/lang/Integer;", "Ljava/lang/String;")
}.singleOrPatchException(
    "Sanitize sharing links: ExternalShareTracker's public (FbUserSession, Integer, String)String method for a /share/ link",
)

/** ExternalShareTracker's (?, session, String, String, String) method that adds extid to a link. */
internal fun <T : Method> extidTracker(methods: Iterable<T>): T = methods.filter {
    val parameters = it.parameterTypes.map(CharSequence::toString)
    it.returnType == "Ljava/lang/String;" && parameters.size == 5 &&
        parameters[1] == FB_USER_SESSION && parameters.drop(2).all { type -> type == "Ljava/lang/String;" }
}.singleOrPatchException(
    "Sanitize sharing links: ExternalShareTracker's (object, FbUserSession, String, String, String)String method that adds extid",
)

/**
 * Sends each link this method returns through the extension. The hooks go in from last to first,
 * because an insert moves every later index. Each goes in at the return's own control-flow label,
 * so a branch that jumped to the return runs it too, and the range form of the call takes a
 * register above v15.
 */
private fun MutableMethod.sanitizeEveryReturn() {
    val implementation = implementation ?: throw PatchException("$definingClass->$name has no body")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$definingClass->$name returns no link to sanitize")

    returns.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $SANITIZE
                move-result-object v$register
            """,
        )
    }
}
