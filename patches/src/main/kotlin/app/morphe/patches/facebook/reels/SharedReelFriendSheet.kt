/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The sheet a reel shared by someone who isn't your friend opens over it, "[Name] shared this reel.
 * Add him as a friend to make sharing even easier", with Add friend and Not now (#486). Read from
 * 587, 2026-11-16, arm64 and armv7.
 *
 * - The sheet is a Bloks screen, FRIEND_SHEET_SCREEN, and the only method that names it is the
 *   run() of a Runnable Redex calls LinkSharingFriendingUpsellUtil$maybeShowFriendingUpsell$1
 *   (arm64 LX/0DrB;, armv7 LX/0DrQ;). The reel viewer's event subscriber posts it once a shared
 *   reel opens.
 * - run() asks a MobileConfig flag, names the upsell FRIEND_SHEET_UPSELL and opens the screen with
 *   the sender's id, the reel's id and "reels" as where it landed. Nothing else happens there.
 * - The hook asks the extension first thing and returns before any of that while the switch is on,
 *   so the reel plays with no sheet over it. Off, paused or failing, run() goes on as Facebook wrote it.
 */

internal const val HIDE_SHARED_REEL_FRIEND_SHEET = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideSharedReelFriendSheet()Z"

/** The Bloks screen of the sheet, a literal only the runnable that opens it holds. */
internal const val FRIEND_SHEET_SCREEN = "com.bloks.www.bloks.friending.link_sharing_upsell_bottomsheet"

/** The name the same runnable gives the upsell as it opens the sheet. */
internal const val FRIEND_SHEET_UPSELL = "LinkSharingFriendingUpsell"

private const val PATCH = "Clean up Reels"
private const val RUNNABLE = "Ljava/lang/Runnable;"

private fun isRun(method: Method) =
    method.name == "run" && method.returnType == "V" && method.parameterTypes.isEmpty() && method.implementation != null

/** The run() of [classDef] when it's a Runnable whose run() opens the sheet, else null. */
internal fun friendSheetRun(classDef: ClassDef): Method? {
    if (classDef.interfaces.none { it.toString() == RUNNABLE }) return null
    return classDef.methods.singleOrNull(::isRun)
        ?.takeIf { holdsString(it, FRIEND_SHEET_SCREEN) && holdsString(it, FRIEND_SHEET_UPSELL) }
}

/** The sheet's runnable asks the extension first thing and returns before it opens the sheet. */
internal fun BytecodePatchContext.hideSharedReelFriendSheet() {
    val owners = classDefByStrings(FRIEND_SHEET_SCREEN, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { friendSheetRun(it) != null }
    val owner = owners.singleOrNull()
        ?: throw PatchException("$PATCH: expected one Runnable that opens $FRIEND_SHEET_SCREEN, found ${owners.size}")
    mutableClassDefBy(owner.type).methods.single(::isRun).returnVoidWhen(HIDE_SHARED_REEL_FRIEND_SHEET)
}
