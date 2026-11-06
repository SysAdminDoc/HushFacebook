/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The Fullscreen pill under a wide video in the Reels viewer (#394). Read from 585, 2026-11-06,
 * arm64; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The pill is a Litho component of its own (LX/0CNt;). Its click handler logs the tap as
 *   "full_screen_pill", the only place that literal appears in the app, and the viewer builds the
 *   component in one place, for a video wider than it is tall.
 * - Its render(ComponentContext) answers null on its own when the phone is sideways, so the pill
 *   isn't drawn over a video that's already full screen. The hook answers that same null first
 *   thing, and the space under the video stays black, as it is in landscape.
 */

internal const val HIDE_FULLSCREEN_PILL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideFullscreenPill()Z"

/** What the pill's click handler logs the tap as, a literal no other class holds. */
internal const val FULL_SCREEN_PILL = "full_screen_pill"

private const val PATCH = "Clean up Reels"

/** The render of [classDef] when it's a component with one, else null. */
internal fun fullscreenPillRender(classDef: ClassDef): Method? = classDef.methods.singleOrNull {
    it.name == "render" && it.parameterTypes.size == 1 && it.returnType.startsWith("L") && it.implementation != null
}

/** The pill's render asks the extension first thing and answers null when told to. */
internal fun BytecodePatchContext.hideFullscreenPill() {
    val owners = classDefByStrings(FULL_SCREEN_PILL, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { fullscreenPillRender(it) != null }
    val owner = owners.singleOrNull()
        ?: throw PatchException("$PATCH: expected one component holding \"$FULL_SCREEN_PILL\" with a render, found ${owners.size}")
    mutableClassDefBy(owner.type).methods.single {
        it.name == "render" && it.parameterTypes.size == 1 && it.returnType.startsWith("L") && it.implementation != null
    }.returnNullWhen(HIDE_FULLSCREEN_PILL)
}
