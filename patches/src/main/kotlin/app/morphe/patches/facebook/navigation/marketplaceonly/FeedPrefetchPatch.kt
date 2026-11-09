/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.marketplaceonly

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.navigation.tabbar.callRegisters
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val STARTUP_PREFETCH = "FeedAppInits\$friendlyFeedPrefetchOnAppInit\$1"
internal const val SCREEN_ON_PREFETCH = "ScreenOnFeedPrefetchOrchestrator\$onScreenOn\$1"
internal const val FRIENDLY_FETCHER = "Lcom/facebook/feed/freshfeed/csr/FriendlyFeedSupplementalFetcher;"
internal const val SKIP_FEED_PREFETCH = "$EXTENSION_PACKAGE/navigation/MarketplaceOnly;->skipFeedPrefetch()Z"
private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

internal data class PrefetchGuard(val method: Method, val index: Int, val scratch: Int)

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference
private val Instruction.literal: String?
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** The no-argument instance `run()V` of [owner] when it's a Runnable. */
private fun runOf(owner: ClassDef): Method? {
    if ("Ljava/lang/Runnable;" !in owner.interfaces) return null
    return owner.methods.singleOrNull {
        it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }
}

/**
 * Only these two named feed warm-ups are changed. Neither is a user request or a shared loader.
 * The startup task already returns without fetching when its AtomicBoolean is set. Screen-on
 * first consumes a pending flag with compareAndSet(true, false); its new return must follow that
 * cleanup, at the existing cancellation branch's target, before any prefetch is started. 586 moved
 * screen-on's body out of the task into a static dispatch on the orchestrator ([screenOnDispatch]),
 * so there the task itself has no guard.
 */
internal fun feedPrefetchGuard(owner: ClassDef): PrefetchGuard? {
    val name = redexOriginalName(owner)
    if (name != STARTUP_PREFETCH && name != SCREEN_ON_PREFETCH) return null
    val run = runOf(owner) ?: return null
    val body = run.implementation ?: return null
    val code = body.instructions.toList()
    if (code.isEmpty() || body.registerCount < 2) return null
    if (name == STARTUP_PREFETCH) {
        if (body.tryBlocks.isNotEmpty()) return null
        if (code.count { it.call?.let { call ->
                call.definingClass == FRIENDLY_FETCHER && call.returnType == "V" &&
                    call.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, "I")
            } == true } != 1) return null
        if (code.none { it.call?.toString() == "$ATOMIC_BOOLEAN->get()Z" }) return null
        return PrefetchGuard(run, 0, 0)
    }
    return screenOnGuard(run)
}

/**
 * The screen-on task's dispatch when its body lives elsewhere: the one static
 * `(FbUserSession, C, long)V` call on a class C its run makes (586: `LX/04je;->A00`, which the
 * delayed dispatch task calls too). Null for any other class.
 */
internal fun screenOnDispatch(owner: ClassDef): MethodReference? {
    if (redexOriginalName(owner) != SCREEN_ON_PREFETCH) return null
    val code = runOf(owner)?.implementation?.instructions?.toList() ?: return null
    return code.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { it.call }
        .filter { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, it.definingClass, "J") }
        .distinctBy { it.toString() }
        .singleOrNull()
}

/** The guard in [orchestrator]'s method [dispatch] names, or null when it isn't screen-on's body. */
internal fun dispatchGuard(orchestrator: ClassDef, dispatch: MethodReference): PrefetchGuard? {
    val method = orchestrator.methods.singleOrNull {
        it.name == dispatch.name && it.returnType == dispatch.returnType &&
            it.parameterTypes.map(CharSequence::toString) == dispatch.parameterTypes.map(CharSequence::toString)
    } ?: return null
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return null
    return screenOnGuard(method)
}

private val CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16, Opcode.CONST_WIDE_16,
    Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16, Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

/**
 * Screen-on's body in [method]: the pending flag consumed, then, past constants that include the
 * orchestrator's tag (586 adds a wide zero before it), a branch on the answer whose fall-through
 * logs the cancellation and returns right before its target. The target's first operation writes
 * the answer's register again, so the guard can borrow it.
 */
private fun screenOnGuard(method: Method): PrefetchGuard? {
    val body = method.implementation ?: return null
    val code = body.instructions.toList()
    if (code.none { it.literal == "main_feed" } || code.none { it.literal == "SUPPLEMENTAL_FETCH_SCREEN_ON" }) return null
    val cas = code.indices.singleOrNull { code[it].call?.toString() == "$ATOMIC_BOOLEAN->compareAndSet(ZZ)Z" }
        ?: return null
    if (cas < 2 || cas + 9 >= code.size) return null
    val args = code[cas].callRegisters()
    if (args.size != 3) return null
    // The scheduler's pending flag is consumed, not set or merely read.
    for ((instruction, register, value) in listOf(Triple(code[cas - 2], args[1], 1L), Triple(code[cas - 1], args[2], 0L))) {
        if ((instruction as? OneRegisterInstruction)?.registerA != register ||
            (instruction as? WideLiteralInstruction)?.wideLiteral != value) return null
    }
    if (code[cas + 1].opcode != Opcode.MOVE_RESULT) return null
    val result = (code[cas + 1] as OneRegisterInstruction).registerA
    val branchAt = (cas + 2 until minOf(code.size, cas + 6)).firstOrNull { code[it].opcode !in CONSTANTS } ?: return null
    val between = code.subList(cas + 2, branchAt)
    if (between.none { it.literal == "ScreenOnFeedPrefetchOrchestrator" }) return null
    if (between.any { (it as OneRegisterInstruction).registerA == result ||
            it.opcode.setsWideRegister() && it.registerA() + 1 == result }) return null
    val branch = code[branchAt]
    if (branch.opcode != Opcode.IF_NEZ || (branch as OneRegisterInstruction).registerA != result) return null
    val addresses = code.runningFold(0) { at, it -> at + it.codeUnits }
    val target = addresses.indexOf(addresses[branchAt] + (branch as OffsetInstruction).codeOffset)
    if (target <= branchAt + 1 || target + 1 >= code.size) return null
    if (code[target - 1].opcode != Opcode.RETURN_VOID ||
        code.subList(branchAt + 1, target).none { it.literal == "skip_foreground_cancelled" }) return null
    // The first original operation overwrites the scratch value on every continuing path.
    if (code[target].opcode != Opcode.INVOKE_STATIC || code[target].call?.returnType != "Z" ||
        code[target].callRegisters().isNotEmpty() || code[target + 1].opcode != Opcode.MOVE_RESULT ||
        (code[target + 1] as OneRegisterInstruction).registerA != result) return null
    val address = addresses[target]
    if (body.tryBlocks.any { address in it.startCodeAddress until it.startCodeAddress + it.codeUnitCount }) return null
    return PrefetchGuard(method, target, result)
}

private fun Instruction.registerA() = (this as OneRegisterInstruction).registerA

/** Hidden dependency: the single Marketplace switch owns these guards too. */
internal val marketplaceFeedPrefetchPatch = bytecodePatch {
    dependsOn(settingsPatch)
    execute {
        val found = mutableListOf<Pair<ClassDef, PrefetchGuard>>()
        val dispatches = mutableListOf<Pair<ClassDef, MethodReference>>()
        classDefForEach { owner ->
            feedPrefetchGuard(owner)?.let { found += owner to it }
            screenOnDispatch(owner)?.let { dispatches += owner to it }
        }
        if (found.none { redexOriginalName(it.first) == SCREEN_ON_PREFETCH }) {
            for ((task, dispatch) in dispatches) {
                val orchestrator = classDefByOrNull(dispatch.definingClass) ?: continue
                dispatchGuard(orchestrator, dispatch)?.let { found += task to it }
            }
        }
        for (name in listOf(STARTUP_PREFETCH, SCREEN_ON_PREFETCH)) {
            val (_, guard) = found.singleOrNull { redexOriginalName(it.first) == name }
                ?: throw PatchException("$PATCH: expected one verified feed prefetch task named $name.")
            mutableClassDefBy(guard.method.definingClass).methods.single {
                it.name == guard.method.name &&
                    it.parameterTypes.map(CharSequence::toString) == guard.method.parameterTypes.map(CharSequence::toString)
            }.guardFeedPrefetch(guard)
        }
    }
}

internal fun MutableMethod.guardFeedPrefetch(guard: PrefetchGuard) {
    requireLocals(PATCH, 1)
    addInstructionsAtControlFlowLabel(guard.index, """
        invoke-static { }, $SKIP_FEED_PREFETCH
        move-result v${guard.scratch}
        if-eqz v${guard.scratch}, :continue_prefetch
        return-void
        :continue_prefetch
        nop
    """)
}
