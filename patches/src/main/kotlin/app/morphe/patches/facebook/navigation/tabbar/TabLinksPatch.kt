/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.tabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.navigation.starttab.TAB_TAG
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The calls that let a page whose tab a switch took off the bar open on its own screen: one where
 * Facebook picks the tab a started page belongs to, one where a Friends link finds the Friends tab
 * and one where a target_tab_id link checks its tab is configured. See TabLinkAnchors.kt. Hide tabs
 * and Hide the Reels tab depend on it; Marketplace only doesn't, and the extension keeps the tabs
 * it drops switched to.
 */
internal val tabLinksPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        val lookups = mutableListOf<Pair<Method, TabHook>>()
        // A lookup answers a TabTag.
        classDefForEachReferencing(types = listOf(TAB_TAG)) { classDef ->
            classDef.methods.forEach { method -> launchedTabLookup(method)?.let { lookups += method to it } }
        }
        val (lookup, launched) = lookups.singleOrNull() ?: throw PatchException(
            "$TAB_LINKS: expected one method that picks the configured tab a started page belongs to by its " +
                "$EXTRA_LAUNCH_URI, found ${lookups.size}.",
        )

        val friendsHelper = classDefByOrNull(FRIENDS_URI_HELPER)
            ?: throw PatchException("$TAB_LINKS: this build has no $FRIENDS_URI_HELPER")
        val friends = friendsHelper.methods.mapNotNull { method -> friendsTabMatch(method)?.let { method to it } }
            .singleOrNull() ?: throw PatchException(
                "$TAB_LINKS: expected one method of $FRIENDS_URI_HELPER that turns a Friends link into a switch to the " +
                    "configured Friends tab.",
            )

        val mainHelper = classDefByOrNull(MAIN_TAB_URI_HELPER)
            ?: throw PatchException("$TAB_LINKS: this build has no $MAIN_TAB_URI_HELPER")
        val table: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
        val configured = mainHelper.methods.mapNotNull { method -> configuredTabCheck(method, table)?.let { method to it } }
            .singleOrNull() ?: throw PatchException(
                "$TAB_LINKS: expected one method of $MAIN_TAB_URI_HELPER that checks a $TARGET_TAB_ID link's tab " +
                    "against the configured tabs.",
            )

        mutableClassDefBy(lookup.definingClass).findMutableMethodOf(lookup).askExtensionForTab(launched, LAUNCHED_TAB)
        mutableClassDefBy(FRIENDS_URI_HELPER).findMutableMethodOf(friends.first).askExtensionForTab(friends.second, FRIENDS_TAB)
        mutableClassDefBy(MAIN_TAB_URI_HELPER).findMutableMethodOf(configured.first).askExtensionIfConfigured(configured.second)
    }
}

/** Hands the tab in [hook]'s register to [extension] in front of [hook]'s instruction, and keeps the answer, cast back to TabTag. */
internal fun MutableMethod.askExtensionForTab(hook: TabHook, extension: String) {
    // invoke-static names its registers in four bits each.
    if (hook.tab > 15) throw PatchException("$TAB_LINKS: $definingClass->$name keeps the tab past v15: v${hook.tab}.")
    addInstructions(
        hook.at,
        """
            invoke-static { v${hook.tab} }, $extension
            move-result-object v${hook.tab}
            check-cast v${hook.tab}, $TAB_TAG
        """,
    )
}

/** Hands the configured list's answer and the tab to the extension after the answer is kept, and keeps the extension's answer instead. */
internal fun MutableMethod.askExtensionIfConfigured(check: ConfiguredCheck) {
    if (check.answer > 15 || check.tab > 15) {
        throw PatchException("$TAB_LINKS: $definingClass->$name keeps the answer or the tab past v15: $check.")
    }
    addInstructions(
        check.at,
        """
            invoke-static { v${check.answer}, v${check.tab} }, $CONFIGURES_TAB
            move-result v${check.answer}
        """,
    )
}
