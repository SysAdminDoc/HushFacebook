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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val CENTERED_FULLSCREEN = "$EXTENSION_PACKAGE/media/CenteredFullscreen;"
internal const val QUIET_STARTED = "$CENTERED_FULLSCREEN->started(Ljava/lang/Object;Z)V"
internal const val QUIET_LISTENING = "$CENTERED_FULLSCREEN->listening(Ljava/lang/Object;)V"
internal const val QUIET_FLAG_STUB = "quietFlag"
internal const val QUIET_CHANGED_STUB = "quietChanged"

/** What the quiet mode manager's setup logs when a player sets it up twice. */
internal const val QUIET_DOUBLE_SETUP = "OQMH was double initialized"

/** What the player's Hide chevron logs when a tap turns quiet mode on. */
internal const val QUIET_TAP_LOG = "quiet_mode_entered"

/** The name Redex left on Facebook's full screen player, the one a comment's video opens in. */
internal const val GROOT_FULLSCREEN = "GrootFullscreenVideoPlayer"

private const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val CONTEXT = "Landroid/content/Context;"
private const val INTEGER = "Ljava/lang/Integer;"

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

/** The manager's static (manager, Integer)V, which tells its listeners quiet mode changed and why. */
internal fun quietNotices(manager: ClassDef): List<Method> = manager.methods.filter {
    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
        it.parameterTypes.map(CharSequence::toString) == listOf(manager.type, INTEGER)
}

/** The manager's instance (listener)V that puts a listener in its list: the one calling List.add. */
internal fun quietListenerAdds(manager: ClassDef): List<Method> = manager.methods.filter { method ->
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 1 &&
        method.parameterTypes.first().startsWith("L") &&
        method.implementation?.instructions?.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            call != null && call.definingClass == "Ljava/util/List;" && call.name == "add" &&
                call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        } == true
}

/**
 * The reasons [classDef]'s Hide chevron hands [notice]: in each method that logs [QUIET_TAP_LOG],
 * the static Integer last read into the notice call's reason register, as "Class->field".
 */
internal fun tapReasons(classDef: ClassDef, notice: Method): List<String> = classDef.methods
    .filter { holdsString(it, QUIET_TAP_LOG) }
    .flatMap { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        code.indices.mapNotNull { index ->
            val call = code[index]
            val target = (call as? ReferenceInstruction)?.reference as? MethodReference
            if (call.opcode != Opcode.INVOKE_STATIC || target == null || target.definingClass != notice.definingClass ||
                target.name != notice.name ||
                target.parameterTypes.map(CharSequence::toString) != notice.parameterTypes.map(CharSequence::toString)
            ) return@mapNotNull null
            val register = (call as FiveRegisterInstruction).registerD
            val write = (index - 1 downTo 0).map(code::get).firstOrNull {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
            } ?: return@mapNotNull null
            val read = (write as? ReferenceInstruction)?.reference as? FieldReference
            if (write.opcode != Opcode.SGET_OBJECT || read == null || read.type != INTEGER) return@mapNotNull null
            "${read.definingClass}->${read.name}"
        }
    }.distinct()

/**
 * Right after Facebook's full screen player sets quiet mode up for a video (#242), the quiet mode
 * manager and the setup's last boolean, which keeps quiet mode off whichever way the phone is
 * held, go to the extension, which can start quiet mode on: the details and comments panel folded
 * away and the video centered. The extension's flag stub reads the manager's one AtomicBoolean,
 * the flag the setup and the player's chevron both set. The flag alone folds the panel but leaves
 * the video at the top, so the extension's notice stub then has the manager tell its listeners,
 * through its static (manager, Integer)V, with the reason the Hide chevron gives when it's tapped.
 * Not the orientation reason: the listener that slides the video to the middle skips that one,
 * since turning the phone lays everything out again anyway. The player's own component and the
 * comment bar read the flag before the setup and start listening after it, so the manager's
 * add-listener method hands the manager to the extension first, which has it tell its listeners
 * again once the new one is in.
 * Everything is found before anything changes.
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

    // How the manager tells its listeners, and the reason its orientation subscriber gives.
    val notices = quietNotices(manager)
    val notice = notices.singleOrNull()
        ?: refuse("expected one static (manager, Integer)V in ${manager.type}, found ${notices.size}")
    if (!AccessFlags.PUBLIC.isSet(notice.accessFlags)) refuse("${manager.type}->${notice.name} isn't public")
    val reasons = classDefByStrings(QUIET_TAP_LOG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { tapReasons(it, notice) }.distinct()
    val reason = reasons.singleOrNull()
        ?: refuse("expected the Hide chevron to give one reason, found ${reasons.size}")
    val reasonClass = classDefBy(reason.substringBefore("->"))
    val reasonField = reasonClass.fields.singleOrNull { it.name == reason.substringAfter("->") && it.type == INTEGER }
        ?: refuse("$reason isn't an Integer field")
    if (!AccessFlags.PUBLIC.isSet(reasonClass.accessFlags) || !AccessFlags.PUBLIC.isSet(reasonField.accessFlags)) {
        refuse("$reason isn't public")
    }
    val adds = quietListenerAdds(manager)
    val add = adds.singleOrNull()
        ?: refuse("expected one add-listener method in ${manager.type}, found ${adds.size}")
    val addCode = add.implementation ?: refuse("${manager.type}->${add.name} has no code")
    if (addCode.registerCount - 2 > 15) refuse("${manager.type}->${add.name}'s p0 is past v15")

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
    // Its second parameter only lends a register, for the reason and then the answer.
    val told = extension.methods.singleOrNull {
        it.name == QUIET_CHANGED_STUB && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
    } ?: refuse("$CENTERED_FULLSCREEN has no static $QUIET_CHANGED_STUB(Ljava/lang/Object;Ljava/lang/Object;)Z")
    extension.methods.singleOrNull {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == QUIET_LISTENING &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$CENTERED_FULLSCREEN has no static $QUIET_LISTENING")
    val mutable = mutableClassDefBy(player.type).findMutableMethodOf(method)
    check(mutable.getInstruction<Instruction>(index).opcode == call.opcode)
    val mutableAdd = mutableClassDefBy(manager.type).findMutableMethodOf(add)

    told.addInstructions(
        0,
        """
            check-cast p0, ${manager.type}
            sget-object p1, $reason:$INTEGER
            invoke-static { p0, p1 }, ${manager.type}->${notice.name}(${manager.type}$INTEGER)V
            const/4 p1, 0x1
            return p1
        """,
    )
    stub.addInstructions(
        0,
        """
            check-cast p0, ${manager.type}
            iget-object p0, p0, ${manager.type}->${flag.name}:$ATOMIC_BOOLEAN
            return-object p0
        """,
    )
    mutable.addInstruction(index + 1, "invoke-static { v$target, v$neverQuiet }, $QUIET_STARTED")
    mutableAdd.addInstruction(0, "invoke-static { p0 }, $QUIET_LISTENING")
}
