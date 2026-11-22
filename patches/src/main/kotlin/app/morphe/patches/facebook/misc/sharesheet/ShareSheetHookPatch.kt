/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.upsells.hookShareSheetItems
import app.morphe.util.executeBytecode

/**
 * The share sheet's item list hook, shared by Hide Meta upsells (its Threads switch) and Share
 * sheet items (the picked items). The method that picks a sheet's items answers through the
 * extension's ShareSheetItems, which runs each patch's rule only when that patch was picked, so the
 * build carries one hook whichever of them are in. See ShareSheetAnchors.kt for how it's found.
 */
internal val shareSheetHookPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        hookShareSheetItems()
    }
}
