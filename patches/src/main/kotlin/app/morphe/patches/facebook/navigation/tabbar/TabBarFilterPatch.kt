/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.tabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method

/**
 * The one call Facebook's tab bar list builder makes to the extension's TabBarFilter, right after
 * Facebook's own hidden-tab set answers for each configured tab. See TabBarAnchors.kt for how the
 * tab bar is built. Marketplace only and Hide the Reels tab both depend on it, so the builder
 * carries one call whichever of them are selected; each switches its own rule on in the extension.
 */
internal val tabBarFilterPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val filters = mutableListOf<Pair<Method, TabFilter>>()
        classDefForEachReferencing(types = listOf(NAVIGATION_CONFIG)) { classDef ->
            if (classDef.fields.none { it.type == NAVIGATION_CONFIG }) return@classDefForEachReferencing
            classDef.methods.forEach { method -> shownTabFilter(method)?.let { filters += method to it } }
        }
        val (builder, filter) = filters.singleOrNull() ?: throw PatchException(
            "$TAB_BAR_FILTER: expected one method that builds the tab bar's shown tabs from NavigationConfig's list, " +
                "asking the hidden-tab set about each, found ${filters.size}.",
        )
        mutableClassDefBy(builder.definingClass).methods.single {
            it.name == builder.name && it.returnType == builder.returnType &&
                it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
        }.askExtensionAfterHiddenSet(filter)
    }
}

/**
 * Hands the hidden set's answer, kept at [filter]'s `move-result`, to the extension with the tab,
 * the configured list and the set, and keeps the extension's answer in the same register before
 * the branch that skips the tab reads it. Nothing may jump to that branch, since code arriving
 * there would skip the call.
 */
internal fun MutableMethod.askExtensionAfterHiddenSet(filter: TabFilter) {
    val branch = implementation!!.instructions[filter.result + 1] as BuilderInstruction
    if (branch.location.labels.isNotEmpty()) {
        throw PatchException("$TAB_BAR_FILTER: $definingClass->$name has a jump to the branch that skips a hidden tab.")
    }
    // invoke-static names four registers in four bits each; the patched builds use v0 to v7.
    val registers = listOf(filter.answer, filter.tab, filter.configured, filter.hidden)
    if (registers.any { it > 15 }) {
        throw PatchException("$TAB_BAR_FILTER: $definingClass->$name keeps the tab, the list or the set past v15: $registers.")
    }
    addInstructions(
        filter.result + 1,
        """
            invoke-static { ${registers.joinToString { "v$it" }} }, $HIDES_TAB
            move-result v${filter.answer}
        """,
    )
}
