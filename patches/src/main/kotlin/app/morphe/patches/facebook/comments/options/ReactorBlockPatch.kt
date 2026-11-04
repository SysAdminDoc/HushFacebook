/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.sponsoredsearch.invokeRegisters
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireFreeAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Block button on reaction lists (#271 follow-up), read from 585 on 2026-11-04. The obfuscated
 * names are for reviewers; the code finds every spot by a kept name or a shape.
 *
 * Each row in the list of people who reacted to a post ends in one button. The row's state method
 * (585 LX/0N8j;->A1F) is the one place that reads the kept field `friendingChannel` straight off
 * the row props (LX/0Nli, the class that also holds the post's ViewPermalinkParams), and it builds
 * Facebook's shared friending button there (LX/0Bgr;->A00), stored into the state (LX/0NBF;->A00).
 * The row's render shows that shared button when it's there; otherwise it mounts the row's own
 * button (LX/0N9Y), a text view whose bind (A0z) labels it for the relation (Add friend, Message,
 * Confirm and the rest), sets its click, or hides it for the viewer and people who can't be added.
 *
 * Three hooks, all answering to one switch in the extension. The state store asks the extension
 * first and stores nothing with the switch on, so every row mounts its own button. The bind hands
 * the extension its component and view on entry (it reuses its parameter registers before it
 * ends), and on each return the extension relabels the view Block, unless Facebook hid it, and
 * takes its click, which opens the profile Block helper the comment button uses
 * (CommentBlockPatch). The Follow button (LX/0N6w) is another component and keeps Follow.
 */

private const val PATCH = "Comment sheet options (reaction list block button)"
private const val REACTOR_BLOCK_CLASS = "$EXTENSION_PACKAGE/comments/ReactorBlock;"
private const val SHARED_BUTTON_CALL = "$REACTOR_BLOCK_CLASS->sharedButton(Ljava/lang/Object;)Ljava/lang/Object;"
private const val BINDING_CALL = "$REACTOR_BLOCK_CLASS->binding(Ljava/lang/Object;Ljava/lang/Object;)V"
private const val BOUND_CALL =
    "$REACTOR_BLOCK_CLASS->bound(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)V"

/** The kept field the reaction row's state reads its friending location from. */
internal const val FRIENDING_CHANNEL = "friendingChannel"

private const val PERMALINK_PARAMS = "Lcom/facebook/ipc/feed/ViewPermalinkParams;"
private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val MOUNT_CONTENT = "onCreateMountContent"
private const val CLICK_LISTENER = "Landroid/view/View;->setOnClickListener(Landroid/view/View\$OnClickListener;)V"
private const val TEXT_VIEW = "Landroid/widget/TextView;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Where the reaction row's state stores Facebook's shared friending button. */
internal class ReactorRowState(
    val method: Method,
    val storeAt: Int,
    val register: Int,
    val button: String,
    val holder: String,
)

/**
 * The reaction row's state store among [methods]: right after a read of [FRIENDING_CHANNEL] off a
 * field of the row props (a class [classOf] shows holding a ViewPermalinkParams), the one static
 * call that takes the friending location, or a value made from it, and whose result goes straight
 * into a field of its own type. Refuses unless there's exactly one.
 */
internal fun reactorRowState(methods: List<Method>, classOf: (String) -> ClassDef?): ReactorRowState {
    val found = mutableListOf<ReactorRowState>()
    for (method in methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        for (at in 1 until code.size) {
            val channel = code[at].fieldRead() ?: continue
            if (channel.name != FRIENDING_CHANNEL || channel.type != "Ljava/lang/String;") continue
            val location = code[at - 1].fieldRead() ?: continue
            if (location.type != channel.definingClass) continue
            val props = classOf(location.definingClass) ?: continue
            if (props.fields.none { it.type == PERMALINK_PARAMS }) continue
            val takers = callsTaking(code, at)
            val stores = (at + 1 until code.size - 2).filter { call ->
                val target = code[call].callTarget() ?: return@filter false
                if (call !in takers || !target.returnType.startsWith("L")) return@filter false
                val result = code[call + 1]
                val store = code[call + 2]
                result.opcode == Opcode.MOVE_RESULT_OBJECT && store.opcode == Opcode.IPUT_OBJECT &&
                    (store as TwoRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA &&
                    ((store as ReferenceInstruction).reference as FieldReference).type == target.returnType
            }
            val call = stores.singleOrNull() ?: continue
            val register = (code[call + 1] as OneRegisterInstruction).registerA
            if (register > 255) refuse("the friending button sits in v$register")
            found += ReactorRowState(method, call + 2, register, code[call].callTarget()!!.returnType, location.definingClass)
        }
    }
    // Two reads of the location in one method can lead to the same store.
    val stores = found.distinctBy { it.method.descriptor() to it.storeAt }
    return stores.singleOrNull() ?: refuse("expected one reaction row state, found ${stores.size}")
}

/**
 * The indexes of the calls after [read] that take what it read, or what a call made from it
 * answered, in straight-line order: a move carries the value on, and any other write of a register
 * holding it drops it.
 */
private fun callsTaking(code: List<Instruction>, read: Int): Set<Int> {
    val held = mutableSetOf((code[read] as OneRegisterInstruction).registerA)
    val calls = mutableSetOf<Int>()
    var answered = false
    for (index in read + 1 until code.size) {
        val instruction = code[index]
        when (instruction.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                val move = instruction as TwoRegisterInstruction
                if (move.registerB in held) held += move.registerA else held -= move.registerA
            }
            Opcode.MOVE_RESULT_OBJECT -> {
                val register = (instruction as OneRegisterInstruction).registerA
                if (answered) held += register else held -= register
            }
            Opcode.CHECK_CAST -> Unit
            else -> if (instruction.opcode.setsRegister() && instruction is OneRegisterInstruction) {
                held -= instruction.registerA
            }
        }
        answered = false
        if (invokeRegisters(instruction).any { it in held }) {
            calls += index
            answered = true
        }
    }
    return calls
}

private fun Instruction.fieldRead(): FieldReference? =
    if (opcode == Opcode.IGET_OBJECT) (this as ReferenceInstruction).reference as? FieldReference else null

private fun Instruction.callTarget(): MethodReference? =
    if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) {
        (this as ReferenceInstruction).reference as? MethodReference
    } else {
        null
    }

/**
 * The reaction row's own button bind among [candidates]: the mounted component holding the row
 * props ([holder]) and a session, whose mount content is a text view, and its one (state, props,
 * mount content) bind that sets a click. Refuses unless there's exactly one and it has the
 * registers the hooks use.
 */
internal fun reactorButtonBind(candidates: List<ClassDef>, holder: String): Method {
    val buttons = candidates.filter { classDef ->
        !AccessFlags.ABSTRACT.isSet(classDef.accessFlags) &&
            classDef.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == holder } &&
            classDef.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SESSION } &&
            classDef.methods.any { it.name == MOUNT_CONTENT && it.mountsTextView() }
    }.distinctBy { it.type }
    val button = buttons.singleOrNull() ?: refuse("expected one reaction row button, found ${buttons.size}")
    val binds = button.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 3 &&
            method.parameterTypes.all { it.startsWith("L") } && method.parameterTypes[2].toString() == "Ljava/lang/Object;" &&
            method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == CLICK_LISTENER } == true
    }
    val bind = binds.singleOrNull() ?: refuse("expected one click bind in ${button.type}, found ${binds.size}")
    if (bind.localRegisterCount() < 4) refuse("${bind.name} has no registers for the hook")
    if (bind.implementation!!.instructions.none { it.opcode == Opcode.RETURN_VOID }) refuse("${bind.name} never returns")
    return bind
}

/** Whether this (Context) mount content method builds a view it sets up through TextView's own methods. */
private fun Method.mountsTextView(): Boolean =
    parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;") &&
        implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == TEXT_VIEW
        } == true

/**
 * The reaction list's block button hooks go in. Nameless, so Comment sheet options carries it and
 * its fixture tests run it on its own. Everything is found and checked before anything changes,
 * and a build where something isn't found keeps its reaction lists stock, with a warning, instead
 * of stopping Comment sheet options.
 */
internal val reactorBlockPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        try {
            addReactorBlock()
        } catch (failure: PatchException) {
            patchLog.warning("${failure.message}. The Block button on reaction lists does nothing in this build")
        }
    }
}

private fun BytecodePatchContext.addReactorBlock() {
    val methods = mutableListOf<Method>()
    classDefForEachReferencing(names = listOf(FRIENDING_CHANNEL)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        methods += classDef.methods
    }
    val state = reactorRowState(methods) { type -> classDefByOrNull(type) }
    val candidates = mutableListOf<ClassDef>()
    classDefForEachReferencing(types = listOf(state.holder)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        candidates += classDef
    }
    val bind = reactorButtonBind(candidates, state.holder)
    val block = blockHelper(
        blockHolders(BLOCK_SHEET_QUERY).flatMap { holder -> holder.methods.filter { holdsString(it, BLOCK_SHEET_QUERY) } },
        { type -> classDefByOrNull(type) },
        ::refuse,
    )
    hookReactorBlock(state, bind, block) { type -> mutableClassDefBy(type) }
}

/**
 * Puts the hooks into [state]'s method and [bind], [mutable] reading the class to change, once
 * every register they borrow is known to be free: the stored register after the store, v0 to v1 on
 * entry and v0 to v3 at each return.
 */
internal fun hookReactorBlock(state: ReactorRowState, bind: Method, block: BlockHelper, mutable: (String) -> ClassDef) {
    state.method.requireFreeAt(PATCH, state.storeAt + 1, listOf(state.register))
    val returns = bind.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    bind.requireFreeAt(PATCH, 0, listOf(0, 1))
    returns.forEach { bind.requireFreeAt(PATCH, it, (0..3).toList()) }

    val store = mutable(state.method.definingClass).methods.single { it.descriptor() == state.method.descriptor() } as MutableMethod
    store.addInstructions(
        state.storeAt,
        """
            invoke-static/range {v${state.register} .. v${state.register}}, $SHARED_BUTTON_CALL
            move-result-object v${state.register}
            check-cast v${state.register}, ${state.button}
        """,
    )

    val button = mutable(bind.definingClass).methods.single { it.descriptor() == bind.descriptor() } as MutableMethod
    // Each return first, the last one first, so the earlier indexes stay where they were.
    for (at in returns.reversed()) {
        button.addInstructionsAtControlFlowLabel(
            at,
            """
                const-class v0, ${state.holder}
                const-class v1, ${block.helper}
                const-class v2, ${block.profile}
                const-class v3, ${block.source}
                invoke-static {v0, v1, v2, v3}, $BOUND_CALL
            """,
        )
    }
    // The bind writes over its parameter registers, so the component and the view go over first.
    button.addInstructions(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p3
            invoke-static {v0, v1}, $BINDING_CALL
        """,
    )
}
