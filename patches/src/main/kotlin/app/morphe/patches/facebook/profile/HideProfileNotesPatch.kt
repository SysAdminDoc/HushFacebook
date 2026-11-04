/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.profile

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val PROFILE_NOTES_PATCH = "Hide profile notes"

/** The extension method every child list goes through. */
internal const val PROFILE_NOTES_CHILDREN =
    "$EXTENSION_PACKAGE/profile/ProfileNotes;->children(Ljava/util/List;)Ljava/util/List;"

internal const val SPARSE_ARRAY = "Landroid/util/SparseArray;"
internal const val LIST = "Ljava/util/List;"
internal const val SINGLETON_LIST = "Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;"

/**
 * The accessors the Bloks node class implements under their own names, from the interface the
 * Bloks interpreter reads attributes through. No other class in the app has all six.
 */
internal val NODE_ACCESSORS = setOf(
    "getBoolean(IZ)Z",
    "getId()Ljava/lang/String;",
    "getInt(II)I",
    "getLong(IJ)J",
    "getString(I)Ljava/lang/String;",
    "put(ILjava/lang/Object;)V",
)

/**
 * Hides the note bubble on profile pictures. Facebook lays the top of a profile out with Bloks, and
 * the bubble is a Bloks node whose own attributes name the note actions. Every list of children
 * the node class hands out goes through the extension's ProfileNotes, which leaves those nodes out
 * while the switch is on.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val hideProfileNotesPatch = bytecodePatch(
    name = "Hide profile notes",
    description = "Hides the note bubble on profile pictures, the Drop a thought prompt on your own profile " +
        "included. Starts off. Turn it on in Hushfacebook settings > News feed.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("profileNotes")
        val getters = childGetters(bloksNodes())
        getters.forEach { getter ->
            mutableClassDefBy(getter.definingClass).methods.single {
                it.name == getter.name && isChildGetter(it)
            }.filterChildren()
        }
        enableStatus("profileNotes")
    }
}

/** Whether [classDef] is the Bloks node: its attributes in an instance SparseArray, and every node accessor. */
internal fun isBloksNode(classDef: ClassDef): Boolean {
    if (AccessFlags.ABSTRACT.isSet(classDef.accessFlags) || AccessFlags.INTERFACE.isSet(classDef.accessFlags)) return false
    if (classDef.fields.none { it.type == SPARSE_ARRAY && !AccessFlags.STATIC.isSet(it.accessFlags) }) return false
    val signatures = classDef.methods.filter { it.implementation != null }.mapTo(mutableSetOf()) { signature(it) }
    return signatures.containsAll(NODE_ACCESSORS)
}

private fun signature(method: Method): String =
    "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

/**
 * The Bloks node classes. 584 has two: the node the interpreter builds and edits, and a lighter
 * one it reads straight from a parsed layout. Both hand out the same children.
 */
internal fun BytecodePatchContext.bloksNodes(): List<ClassDef> {
    val found = mutableListOf<ClassDef>()
    // By the SparseArray field's type, which the index keeps for declarations too (#216).
    classDefForEachReferencing(types = listOf(SPARSE_ARRAY)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (isBloksNode(classDef)) found += classDef
    }
    if (found.isEmpty()) {
        throw PatchException(
            "$PROFILE_NOTES_PATCH: found no Bloks node class (an instance SparseArray and ${NODE_ACCESSORS.joinToString()})",
        )
    }
    return found
}

/** Whether [method] hands out a node's children for an attribute key: an instance method taking an int, answering a List. */
internal fun isChildGetter(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.returnType == LIST && method.parameterTypes.map(Any::toString) == listOf("I")

/**
 * [nodes]' child getters, every one of them, so no path to a child list skips the hook. Each node
 * class has some, and the main node's layout getter wraps a lone child in
 * `Collections.singletonList`, which is the evidence these are the children Bloks lays out and not
 * some other list.
 */
internal fun childGetters(nodes: List<ClassDef>): List<Method> {
    val getters = nodes.flatMap { node ->
        node.methods.filter(::isChildGetter).ifEmpty {
            throw PatchException("$PROFILE_NOTES_PATCH: Bloks node ${node.type} has no (I)List child getter")
        }
    }
    if (getters.none(::wrapsALoneChild)) {
        throw PatchException(
            "$PROFILE_NOTES_PATCH: no Bloks node getter wraps a lone child in singletonList, found " +
                getters.joinToString { "${it.definingClass}->${it.name}" },
        )
    }
    return getters
}

internal fun wrapsALoneChild(method: Method): Boolean =
    method.implementation!!.instructions.any {
        it.opcode == Opcode.INVOKE_STATIC && (it as ReferenceInstruction).reference.toString() == SINGLETON_LIST
    }

/**
 * Hands each list the getter returns to the extension and returns what it answers. The call goes in
 * at the return's own label, so a branch that jumps straight to the return goes through it too.
 */
internal fun MutableMethod.filterChildren() {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$PROFILE_NOTES_PATCH: $definingClass->$name never returns")
    for (index in returns.asReversed()) {
        val list = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$list .. v$list }, $PROFILE_NOTES_CHILDREN
                move-result-object v$list
            """,
        )
    }
}
