/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.hdr

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Which view Facebook's video player draws into (#308). Read from 585, 2026-10-30, arm64; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * - One method decides whether a player must use a SurfaceView (LX/08eE;->A00, virtual, five
 *   arguments, answering Z). It loads "force_texture_view_for_blur" and answers no when that key is
 *   set, answers yes for the reels viewer ("fb_shorts_viewer") and a server list of origins, and
 *   ends in four returns, three of them jump targets.
 * - Seven callers ask it. Two branch on the answer straight into the player's own TextureView
 *   preference, an AtomicBoolean field of the player (LX/05gS;->A2N): attach (A0R, logged as
 *   "FbGrootPlayer.attachPlayerViewInternal") hands it to the attach helper (A0Q), which stores it back
 *   and swaps the player's view controller to match, and set-up (A1E) builds the TextureView
 *   controller (LX/07s2;) when it's yes and the SurfaceView one (LX/081t; and its kin) otherwise,
 *   right after "FbGrootPlayer.createGrootSurfaceController". The other five use the answer for the
 *   video effects list and the player's config, so they read it as the TextureView it now is.
 * - The decision's third argument is the player's origin (PlayerOrigin, never written in the body).
 *   See-through bars (#250) needs it: the S22's decision says yes for the feed origin from the
 *   server list, so the hook hands each answer over with the origin, and the extension keeps the
 *   reels viewer's yes.
 */

internal const val FORCE_TEXTURE_FOR_BLUR = "force_texture_view_for_blur"
internal const val TEXTURE_VIDEO = "$EXTENSION_PACKAGE/media/TextureVideo;"
internal const val OWN_SURFACE_VIEW = "$TEXTURE_VIDEO->surfaceView(ZLjava/lang/Object;)Z"
internal const val PLAYER_ORIGIN = "Lcom/facebook/video/common/playerorigin/PlayerOrigin;"
internal const val OWN_PREFER_TEXTURE_VIEW = "$TEXTURE_VIDEO->preferTextureView(Z)Z"
internal const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"

private const val PATCH = "Turn off HDR brightness"
private val RETURNS = setOf(Opcode.RETURN)
private val INVOKES = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC,
    Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

/** Whether [method] is the player's must-use-a-SurfaceView decision: it answers Z and loads [FORCE_TEXTURE_FOR_BLUR]. */
internal fun isSurfaceDecision(method: Method): Boolean =
    method.returnType == "Z" && holdsString(method, FORCE_TEXTURE_FOR_BLUR)

/** The one surface decision ([isSurfaceDecision]). Throws, naming how many it found, when it isn't one. */
internal fun BytecodePatchContext.surfaceDecision(): Method {
    val found = classDefByStrings(FORCE_TEXTURE_FOR_BLUR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter(::isSurfaceDecision) }
        // Facebook's names repeat across overloads, so the parameters tell them apart.
        .distinctBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})" }
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one surface decision loading \"$FORCE_TEXTURE_FOR_BLUR\", found ${found.size}")
}

/** The index and register of each `return` in [decision]. */
internal fun surfaceAnswers(decision: Method): List<Pair<Int, Int>> =
    decision.implementation!!.instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode in RETURNS }
        .map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }

/** The register [decision] receives its [PLAYER_ORIGIN] argument in, or null when it takes none. */
internal fun originRegister(decision: Method): Int? {
    val parameters = decision.parameterTypes.map(CharSequence::toString)
    val at = parameters.indexOf(PLAYER_ORIGIN).takeIf { it >= 0 } ?: return null
    // The arguments fill the last registers in order (`this`, when there is one, comes before them).
    val sizes = parameters.map { if (it == "J" || it == "D") 2 else 1 }
    return decision.implementation!!.registerCount - sizes.sum() + sizes.take(at).sum()
}

/**
 * A read of the player's TextureView preference right where a call of the decision sends a no:
 * the `move-result` at [at] - 1 in [method] took the field's `get()` into [register].
 */
internal data class PreferenceRead(val method: Method, val at: Int, val register: Int, val field: String)

/** The registers [instruction] passes, in order. */
private fun passed(instruction: Instruction): List<Int> = when (instruction) {
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    else -> emptyList()
}

private fun Instruction.calls(method: Method): Boolean {
    if (opcode !in INVOKES) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.definingClass == method.definingClass && call.name == method.name && call.returnType == method.returnType &&
        call.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
}

/**
 * The preference reads in [method]: each call of [decision] whose answer goes into a register an
 * `if-eqz` tests next, where the no lands on an AtomicBoolean field's `get()` taken into a register.
 */
internal fun preferenceReads(method: Method, decision: Method): List<PreferenceRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val reads = mutableListOf<PreferenceRead>()
    for (site in code.indices) {
        if (!code[site].calls(decision)) continue
        val answer = code.getOrNull(site + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: continue
        val test = code.getOrNull(site + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } ?: continue
        if ((test as OneRegisterInstruction).registerA != answer.registerA) continue
        val no = addresses.indexOf(addresses[site + 2] + (test as OffsetInstruction).codeOffset)
        val holder = code.getOrNull(no)?.takeIf { it.opcode == Opcode.IGET_OBJECT } ?: continue
        val field = (holder as ReferenceInstruction).reference as FieldReference
        if (field.type != ATOMIC_BOOLEAN) continue
        val get = code.getOrNull(no + 1) ?: continue
        val getCall = (get as? ReferenceInstruction)?.reference as? MethodReference
        if (get.opcode != Opcode.INVOKE_VIRTUAL || getCall?.definingClass != ATOMIC_BOOLEAN || getCall.name != "get" ||
            passed(get) != listOf((holder as OneRegisterInstruction).registerA)
        ) continue
        val result = code.getOrNull(no + 2)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: continue
        reads += PreferenceRead(method, no + 3, result.registerA, field.toString())
    }
    return reads
}

/** What the TextureView switch hooks: the decision, its origin's register and the preference reads behind its no. */
internal class TextureHooks(val decision: Method, val origin: Int, val reads: List<PreferenceRead>)

/**
 * The decision ([surfaceDecision]), its origin's register ([originRegister]) and every preference
 * read behind its no ([preferenceReads]) in the classes that reference the decision's class. Throws
 * when no read is found: the player then picks its view some other way, and turning the decision
 * alone wouldn't reach the TextureView. Throws too when the decision takes no origin, writes over
 * it, or keeps it or an answer past v15, where the hook's call can't name it.
 */
internal fun BytecodePatchContext.textureHooks(): TextureHooks {
    val decision = surfaceDecision()
    val origin = originRegister(decision) ?: throw PatchException("$PATCH: the surface decision takes no player origin")
    if (decision.implementation!!.instructions.any { writesRegister(it, origin) }) {
        throw PatchException("$PATCH: the surface decision writes over its player origin in v$origin")
    }
    if (origin > 15 || surfaceAnswers(decision).any { it.second > 15 }) {
        throw PatchException("$PATCH: the surface decision's origin or an answer sits past v15")
    }
    val reads = mutableListOf<PreferenceRead>()
    classDefForEachReferencing(types = listOf(decision.definingClass)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.forEach { reads += preferenceReads(it, decision) }
    }
    if (reads.isEmpty()) {
        throw PatchException("$PATCH: found no TextureView preference read behind the surface decision's no")
    }
    if (reads.any { it.register > 255 }) throw PatchException("$PATCH: a TextureView preference read sits past v255")
    return TextureHooks(decision, origin, reads)
}

/**
 * Each answer of the decision goes through the extension's surfaceView with the player's origin, at
 * its `return` with any jump to it kept, and each preference read through preferTextureView right
 * after its `move-result`, where only the read leads. Answers how many places it hooked.
 */
internal fun BytecodePatchContext.hookTextureVideo(hooks: TextureHooks): Int {
    val decision = mutableClassDefBy(hooks.decision.definingClass).findMutableMethodOf(hooks.decision)
    val answers = surfaceAnswers(hooks.decision)
    answers.asReversed().forEach { (index, register) ->
        decision.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$register, v${hooks.origin} }, $OWN_SURFACE_VIEW
                move-result v$register
            """,
        )
    }
    for ((method, inMethod) in hooks.reads.groupBy { it.method }) {
        val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        inMethod.sortedByDescending { it.at }.forEach { read ->
            mutable.addInstructions(
                read.at,
                """
                    invoke-static/range { v${read.register} .. v${read.register} }, $OWN_PREFER_TEXTURE_VIEW
                    move-result v${read.register}
                """,
            )
        }
    }
    return answers.size + hooks.reads.size
}
