/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postmenu

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.executeBytecode

/** Why the post menu sheet hook stayed out of this build, or null when it went in. */
internal var postMenuSheetMissing: String? = null
    private set

/**
 * The post menu sheet's hook, shared by Post menu items (the rows it hides) and Keep the reel
 * speed (its Playback speed row on the first page, #473). The method that sorts a post menu into
 * the sheet's parts hands its holder to the extension's PostMenuItems, which runs each one's rule
 * only when it's in the build, so the build carries one hook whichever of them are in. See
 * PostMenuSheetAnchors.kt for how it's found.
 *
 * A build where the sheet moved goes on without the hook, so Keep the reel speed keeps the rest of
 * its work and its Playback speed row stays under More options. Post menu items has nothing else
 * to do, so it refuses then ([postMenuSheetMissing]).
 */
internal val postMenuSheetHookPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        postMenuSheetMissing = try {
            hookPostMenuSheet(findPostMenuSheet())
            null
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The post menu stays as Facebook sorts it.")
            moved.message
        }
    }
}
