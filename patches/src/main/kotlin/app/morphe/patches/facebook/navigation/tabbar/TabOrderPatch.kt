/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.tabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/**
 * Hands the tab bar's shown tabs to the extension's TabOrder before the builder keeps them (#341),
 * so they go in the order picked in Hide tabs' Tab order row. Everything that reads the shown list,
 * the bar, its pages, the start tab and the tab switches, reads the new order, and Facebook opens
 * on its first tab as it always opens on the list's first. With no order saved the extension hands
 * back the same list. The list goes back through ImmutableList.copyOf, which keeps an ImmutableList
 * as it is, since the field and its readers want an ImmutableList.
 */
internal val tabOrderPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val stores = mutableListOf<Pair<Method, Int>>()
        classDefForEach { classDef ->
            if (classDef.fields.none { it.type == NAVIGATION_CONFIG }) return@classDefForEach
            classDef.methods.forEach { method -> shownTabStore(method)?.let { stores += method to it } }
        }
        val builder = stores.singleOrNull()?.first ?: throw PatchException(
            "$TAB_BAR_FILTER: expected one method that keeps the tab bar's shown tabs, built from NavigationConfig's " +
                "list, found ${stores.size}.",
        )
        val copies = classDefByOrNull(IMMUTABLE_LIST)?.methods?.any {
            AccessFlags.STATIC.isSet(it.accessFlags) && "$IMMUTABLE_LIST->${it.name}(${it.parameterTypes.joinToString("")})" +
                it.returnType == IMMUTABLE_LIST_COPY_OF
        } == true
        if (!copies) throw PatchException("$TAB_BAR_FILTER: this build's ImmutableList has no copyOf(Collection).")

        val method = mutableClassDefBy(builder.definingClass).methods.single {
            it.name == builder.name && it.returnType == builder.returnType &&
                it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
        }
        // Found again in the method being changed, since the tab bar filter may have gone in first.
        val at = shownTabStore(method)!!
        val keep = method.implementation!!.instructions[at] as BuilderInstruction
        // Code jumping to the store itself would skip the order.
        if (keep.location.labels.isNotEmpty()) {
            throw PatchException(
                "$TAB_BAR_FILTER: ${builder.definingClass}->${builder.name} has a jump to where it keeps the shown tabs.",
            )
        }
        val list = (keep as TwoRegisterInstruction).registerA
        method.addInstructions(
            at,
            """
                invoke-static/range { v$list .. v$list }, $TAB_ORDER_CALL
                move-result-object v$list
                invoke-static/range { v$list .. v$list }, $IMMUTABLE_LIST_COPY_OF
                move-result-object v$list
            """,
        )
    }
}
