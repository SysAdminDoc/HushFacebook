/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.feedsound

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.layout.theme.TAB_BAR_CONTAINER
import app.morphe.patches.facebook.navigation.bottomtabbar.TabRestyleSite
import app.morphe.patches.facebook.navigation.bottomtabbar.tabRestyleSite
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf

internal const val OWN_TAB_SHOWN = "$FEED_SOUND->tabShown(Ljava/lang/Object;)V"

/**
 * The tab bar restyle, which Facebook runs each time a tab is picked, with the picked TabTag in a
 * parameter register (586 `LX/02LT;->A04`). Hide the tab bar on the Reels tab hooks the same method
 * (ReelsTabBarHidden.kt), and Keep feed videos muted finds it on its own so the Reels tab's sound
 * goes off again on the next tab whether or not that patch is in (#593). Throws when this build
 * doesn't have exactly one, or the tab's register gets written.
 */
internal fun BytecodePatchContext.feedSoundTabSite(): TabRestyleSite {
    val owners = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(TAB_BAR_CONTAINER)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (classDef.methods.any { tabRestyleSite(it) != null }) owners += classDef.type
    }
    val restyles = owners.flatMap { owner -> mutableClassDefBy(owner).methods.mapNotNull(::tabRestyleSite) }
    val restyle = restyles.singleOrNull()
        ?: throw PatchException("Expected one tab bar restyle storing a flag on $TAB_BAR_CONTAINER, found ${restyles.size}")
    if (restyle.method.implementation!!.instructions.any { writesRegister(it, restyle.tab) }) {
        throw PatchException("${restyle.method.definingClass}->${restyle.method.name} writes its tab's register")
    }
    return restyle
}

/** Hands the picked tab to FeedSound first thing in the restyle, before any branch. */
internal fun BytecodePatchContext.tellTabShown(site: TabRestyleSite) {
    mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method).addInstructions(
        0,
        """
            invoke-static/range { v${site.tab} .. v${site.tab} }, $OWN_TAB_SHOWN
        """,
    )
}
