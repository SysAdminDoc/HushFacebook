/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val LOCAL_COUNTS_PATCH = "Local profile counts"

/** The profile screen's class. Facebook keeps its name, so it's looked up by name. */
internal const val PROFILE_SCREEN = "Lcom/facebook/timeline/fragment/ProfileFragment;"

internal const val LOCAL_COUNTS_OPENED = "$EXTENSION_PACKAGE/profile/LocalProfileCounts;->opened(Ljava/lang/Object;)V"

/** The extension method every string a Bloks node answers with goes through. */
internal const val LOCAL_COUNTS_TEXT =
    "$EXTENSION_PACKAGE/profile/LocalProfileCounts;->text(Ljava/lang/String;)Ljava/lang/String;"

private const val NODE_STRING = "getString(I)Ljava/lang/String;"

/**
 * Lets you type your own friends, followers and following counts for your own profile, shown on this
 * phone only. The top of a profile is laid out with Bloks, so a count is a string a Bloks node
 * answers with. Every return of the node classes' string getter goes through the extension's
 * LocalProfileCounts, and the profile screen tells it when one opens so it can tell whose profile is
 * on top.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val localProfileCountsPatch = bytecodePatch(
    name = "Local profile counts",
    description = "Shows friends, followers and following counts you type yourself on your own profile, " +
        "on this phone only. Other profiles and Facebook's real numbers aren't touched. Starts off. " +
        "Turn it on in Hushfacebook settings > News feed.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("localProfileCounts")
        val getters = stringGetters(bloksNodes())
        val screen = profileScreenCreate()
        getters.forEach { getter ->
            mutableClassDefBy(getter.definingClass).methods.single { it.name == getter.name && isStringGetter(it) }
                .passStrings()
        }
        mutableClassDefBy(PROFILE_SCREEN).methods.single { it.name == screen.name && isScreenCreate(it) }.reportOpened()
        enableStatus("localProfileCounts")
    }
}

/** Whether [method] is a node's `getString(I)String`: an instance method with a body. */
internal fun isStringGetter(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" == NODE_STRING

/** [nodes]' string getters, one per class. */
internal fun stringGetters(nodes: List<ClassDef>): List<Method> =
    nodes.map { node ->
        node.methods.singleOrNull(::isStringGetter)
            ?: throw PatchException("$LOCAL_COUNTS_PATCH: Bloks node ${node.type} has no single $NODE_STRING")
    }

internal fun isScreenCreate(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.name == "onFragmentCreate" && method.parameterTypes.map(Any::toString) == listOf("Landroid/os/Bundle;")

/** The profile screen's `onFragmentCreate(Bundle)`. */
internal fun BytecodePatchContext.profileScreenCreate(): Method {
    val classDef = classDefByOrNull(PROFILE_SCREEN)
        ?: throw PatchException("$LOCAL_COUNTS_PATCH: this build has no $PROFILE_SCREEN")
    return classDef.methods.singleOrNull(::isScreenCreate)
        ?: throw PatchException("$LOCAL_COUNTS_PATCH: $PROFILE_SCREEN has no single onFragmentCreate(Bundle)")
}

/**
 * Hands each string the getter returns to the extension and returns what it answers. The call goes
 * in at the return's own label, so a branch that jumps straight to the return goes through it too.
 */
internal fun MutableMethod.passStrings() {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$LOCAL_COUNTS_PATCH: $definingClass->$name never returns")
    for (index in returns.asReversed()) {
        val text = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$text .. v$text }, $LOCAL_COUNTS_TEXT
                move-result-object v$text
            """,
        )
    }
}

/** Tells the extension which screen is being created, before anything else in the method runs. */
internal fun MutableMethod.reportOpened() {
    addInstructions(
        0,
        "invoke-static/range { p0 .. p0 }, $LOCAL_COUNTS_OPENED",
    )
}
