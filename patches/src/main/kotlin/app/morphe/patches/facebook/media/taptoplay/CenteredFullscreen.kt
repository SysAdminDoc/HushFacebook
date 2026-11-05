/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val CENTERED_FULLSCREEN = "$EXTENSION_PACKAGE/media/CenteredFullscreen;"
internal const val QUIET_STARTED = "$CENTERED_FULLSCREEN->started(Ljava/lang/Object;Z)V"
internal const val QUIET_FLAG_STUB = "quietFlag"

/** What the quiet mode manager's setup logs when a player sets it up twice. */
internal const val QUIET_DOUBLE_SETUP = "OQMH was double initialized"

/** The name Redex left on Facebook's full screen player, the one a comment's video opens in. */
internal const val GROOT_FULLSCREEN = "GrootFullscreenVideoPlayer"

private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val CONTEXT = "Landroid/content/Context;"

/** The quiet mode manager's setup: an instance (Context, session, ..., Z)V holding its double-setup complaint. */
internal fun quietSetups(classDef: ClassDef): List<Method> = classDef.methods.filter {
    it.returnType == "V" && it.parameterTypes.size == 4 && it.parameterTypes.first().toString() == CONTEXT &&
        it.parameterTypes.last().toString() == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags) &&
        holdsString(it, QUIET_DOUBLE_SETUP)
}

/** The manager's instance AtomicBooleans. Its one is whether quiet mode is on. */
internal fun quietFlags(classDef: ClassDef): List<Field> = classDef.fields.filter {
    it.type == ATOMIC_BOOLEAN && !AccessFlags.STATIC.isSet(it.accessFlags)
}

/** Each call of [setup] in [classDef], as the calling method and the call's index. */
internal fun setupCalls(classDef: ClassDef, setup: Method): List<Pair<Method, Int>> = classDef.methods.flatMap { method ->
    method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call != null && call.definingClass == setup.definingClass && call.name == setup.name &&
            call.returnType == setup.returnType &&
            call.parameterTypes.map(CharSequence::toString) == setup.parameterTypes.map(CharSequence::toString)
    }?.map { method to it.index }.orEmpty()
}

/**
 * Right after Facebook's full screen player sets quiet mode up for a video (#242), the quiet mode
 * manager and the setup's last boolean, which keeps quiet mode off whichever way the phone is
 * held, go to the extension, which can start quiet mode on: the details and comments panel folded
 * away and the video centered. The extension's flag stub reads the manager's one AtomicBoolean,
 * the flag the setup and the player's chevron both set. Everything is found before anything
 * changes.
 */
internal fun BytecodePatchContext.hookCenteredFullscreen() {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: centered full screen videos: $detail")
    val setups = classDefByStrings(QUIET_DOUBLE_SETUP, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::quietSetups)
    val setup = setups.singleOrNull()
        ?: refuse("expected one quiet mode setup holding \"$QUIET_DOUBLE_SETUP\", found ${setups.size}")
    val manager = classDefBy(setup.definingClass)
    val flags = quietFlags(manager)
    val flag = flags.singleOrNull() ?: refuse("expected one AtomicBoolean in ${manager.type}, found ${flags.size}")
    // The stub runs in the extension's package.
    if (!AccessFlags.PUBLIC.isSet(manager.accessFlags)) refuse("${manager.type} isn't public")
    if (!AccessFlags.PUBLIC.isSet(flag.accessFlags)) refuse("${manager.type}->${flag.name} isn't public")

    // The player calls the manager's setup, so it names the manager's type.
    val players = mutableListOf<ClassDef>()
    classDefForEachReferencing(types = listOf(manager.type)) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES) && redexOriginalName(classDef) == GROOT_FULLSCREEN) players += classDef
    }
    val player = players.singleOrNull() ?: refuse("expected one $GROOT_FULLSCREEN, found ${players.size}")
    val calls = setupCalls(player, setup)
    val (method, index) = calls.singleOrNull()
        ?: refuse("expected $GROOT_FULLSCREEN to set quiet mode up once, found ${calls.size}")
    // invoke-virtual { manager, context, session, ..., neverQuiet }: the manager is C and the boolean G.
    val call = method.implementation!!.instructions.elementAt(index) as? FiveRegisterInstruction
        ?: refuse("$GROOT_FULLSCREEN's quiet mode setup isn't a plain invoke")
    if (call.registerCount != 5) refuse("the quiet mode setup passes ${call.registerCount} registers")
    val target = call.registerC
    val neverQuiet = call.registerG
    if (target > 15 || neverQuiet > 15) refuse("the quiet mode setup's registers v$target and v$neverQuiet are past v15")

    val extension = mutableClassDefBy(CENTERED_FULLSCREEN)
    val stub = extension.methods.singleOrNull {
        it.name == QUIET_FLAG_STUB && it.returnType == ATOMIC_BOOLEAN && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("$CENTERED_FULLSCREEN has no static $QUIET_FLAG_STUB(Ljava/lang/Object;)")
    stub.addInstructions(
        0,
        """
            check-cast p0, ${manager.type}
            iget-object p0, p0, ${manager.type}->${flag.name}:$ATOMIC_BOOLEAN
            return-object p0
        """,
    )
    val mutable = mutableClassDefBy(player.type).findMutableMethodOf(method)
    check(mutable.getInstruction<Instruction>(index).opcode == call.opcode)
    mutable.addInstruction(index + 1, "invoke-static { v$target, v$neverQuiet }, $QUIET_STARTED")
}
