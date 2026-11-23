/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.downloads.reel.SIDEBAR
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.gesture.COMMENT_TAP
import app.morphe.patches.facebook.reels.gesture.isCommentClick
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * The Comment button down a reel's right side, on a reel whose comments are turned off (#299). Read
 * from 584 arm64; obfuscated names here are for reviewers, the code never writes one down.
 *
 * - Both places that build the button ask the reel's feedback tree (TreeJNI) for
 *   "can_viewer_comment" by its field key, the name's String.hashCode(), and build nothing for a no:
 *   the UDD sidebar's own builder (LX/AF0;->A01, the one method creating the class whose click
 *   handler holds "tap_comment_button") and the shared per-type factory (LX/93I;->A06, see
 *   ReelSaveButton.kt).
 * - 585 (arm64 and armeabi-v7a) keeps the builder in the button's own class: an instance method (arm64
 *   LX/09eL;->A01, next to the click handler A00) reads both keys, and nothing outside the class news
 *   the class up and reads a key. The shared factory is the same shape (arm64 LX/09eO;->A06).
 * - A server flag (LX/73v;->A0O) swaps the key for a newer field of the same meaning, 0x4a100fb9, in
 *   both. The builder reads one or the other into one register before the same read, which is how
 *   the patch learns the second key without its name.
 * - Every read of either key in the two methods gets its answer handed to the extension right after
 *   the move-result. A branch landing past a read (the builder's own "yes" path) keeps landing past
 *   the hook. The comment sheet makes its own check before it offers the composer.
 * - 588 (arm64, read 2026-11-23) has both methods ask one static helper first (LX/09HZ;->A08, called
 *   from nowhere else). It reads the same two keys and also says yes when a server flag shows the
 *   button on a reel that already has comments. A no from it sends both callers past the button
 *   whatever their own read said, so its reads get the same hook ([sharedCommentChecks]).
 */

internal const val SHOW_COMMENT_BUTTON = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->showsCommentButton(Z)Z"
internal val CAN_VIEWER_COMMENT = "can_viewer_comment".hashCode()

private const val PATCH = "Clean up Reels"
private const val TREE = "Lcom/facebook/graphservice/tree/TreeJNI;"
private val CONSTANTS = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)

/** A TreeJNI.getBooleanValue(I)Z call at [invoke], its result moved into [result], asking for one of [keys]. */
internal data class BooleanRead(val invoke: Int, val result: Int, val keys: Set<Int>)

/**
 * Every getBooleanValue(int) read on a TreeJNI in [method] whose key is a constant on every way in,
 * with the register its move-result fills.
 */
internal fun treeBooleanReads(method: Method): List<BooleanRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        val call = code[at] as? FiveRegisterInstruction ?: return@mapNotNull null
        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (call.opcode != Opcode.INVOKE_VIRTUAL || reference.definingClass != TREE || reference.name != "getBooleanValue" ||
            reference.returnType != "Z" || reference.parameterTypes.map(CharSequence::toString) != listOf("I") ||
            call.registerCount != 2
        ) return@mapNotNull null
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
            ?: return@mapNotNull null
        val writers = method.writersReaching(at, call.registerD)
        if (writers.isEmpty() || writers.any { it < 0 || code[it].opcode !in CONSTANTS }) return@mapNotNull null
        BooleanRead(at, result.registerA, writers.map { (code[it] as NarrowLiteralInstruction).narrowLiteral }.toSet())
    }
}

/** The other key [builder] reads in the same register as [CAN_VIEWER_COMMENT], or null unless there's exactly one. */
internal fun alternateCommentKey(builder: Method): Int? =
    treeBooleanReads(builder).filter { CAN_VIEWER_COMMENT in it.keys }
        .flatMap { it.keys - CAN_VIEWER_COMMENT }.distinct().singleOrNull()

/**
 * Whether [method] builds the Comment button of class [button]: it either news the class up or is a
 * method of the class itself, and it reads the comment key and one other into one register
 * ([alternateCommentKey]). The button's own click handler and constructor read no tree.
 */
internal fun buildsCommentButton(method: Method, button: String): Boolean =
    (method.definingClass == button || method.implementation?.instructions?.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == button
    } == true) && alternateCommentKey(method) != null

/** The reads in [method] of either comment key, plain and swapped. */
internal fun commentGateReads(method: Method, alternate: Int): List<BooleanRead> =
    treeBooleanReads(method).filter { read -> read.keys.all { it == CAN_VIEWER_COMMENT || it == alternate } }

/** The static boolean methods [method] calls. */
private fun staticChecks(method: Method): Set<MethodReference> =
    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
            instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && it.returnType == "Z"
        }
    }.toSet()

/**
 * The static yes/no helpers that [builder] and [factory] both call and that read either comment key
 * themselves, resolved through [classOf]. None before 588; there, the one that decides the button.
 */
internal fun sharedCommentChecks(builder: Method, factory: Method, alternate: Int, classOf: (String) -> ClassDef?): List<Method> {
    val shared = staticChecks(builder).map(MethodReference::toString).toSet()
    return staticChecks(factory).filter { it.toString() in shared }.mapNotNull { call ->
        classOf(call.definingClass)?.methods?.firstOrNull { MethodUtil.methodSignaturesMatch(it, call) }
    }.filter { helper -> commentGateReads(helper, alternate).any { CAN_VIEWER_COMMENT in it.keys } }
}

/**
 * Hands each of the two sidebar builders' comment checks, and those of any helper both of them ask
 * ([sharedCommentChecks]), to the extension, which answers yes while the switch is on. Refuses unless the comment button's builder and the shared factory are each
 * found once, the second key is learned, and each method reads both keys.
 */
internal fun BytecodePatchContext.showReelCommentButton() {
    val buttons = classDefByStrings(COMMENT_TAP, StringComparisonType.EQUALS).filter { owner ->
        owner.methods.count { isCommentClick(it, owner) } == 1
    }
    val buttonClass = buttons.singleOrNull()
        ?: throw PatchException("$PATCH: expected one Comment button class with a click handler holding \"$COMMENT_TAP\", found ${buttons.size}")
    val button = buttonClass.type
    // 585 moved the builder into the button's own class; before, a method elsewhere created it.
    val builders = buttonClass.methods.filterTo(mutableListOf()) { buildsCommentButton(it, button) }
    classDefForEachReferencing(types = listOf(button)) { classDef ->
        if (classDef.type == button || classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(builders) { method -> buildsCommentButton(method, button) }
    }
    val builder = builders.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method building the Comment button $button, found ${builders.size}")
    val factories = methodsNaming(WHATSAPP_BUTTON_ID) { holdsString(it, SIDEBAR) && holdsString(it, SHORTS_SIDEBAR) }
    val factory = factories.singleOrNull()
        ?: throw PatchException("$PATCH: expected one shared sidebar factory naming $WHATSAPP_BUTTON_ID, found ${factories.size}")
    val alternate = alternateCommentKey(builder)
        ?: throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} doesn't read one other key where it reads can_viewer_comment")
    val helpers = sharedCommentChecks(builder, factory, alternate) { classDefByOrNull(it) }

    for (found in listOf(builder, factory) + helpers) {
        val method = mutableClassDefBy(found.definingClass).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameterTypes.map(CharSequence::toString)
        }
        val reads = commentGateReads(method, alternate)
        if (reads.none { CAN_VIEWER_COMMENT in it.keys } || reads.none { alternate in it.keys }) {
            throw PatchException("$PATCH: ${method.definingClass}->${method.name} doesn't read both comment keys")
        }
        reads.sortedByDescending { it.invoke }.forEach { read ->
            method.addInstructions(
                read.invoke + 2,
                """
                    invoke-static/range { v${read.result} .. v${read.result} }, $SHOW_COMMENT_BUTTON
                    move-result v${read.result}
                """,
            )
        }
    }
}
