/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.executeResources

/** The manifest half: both shared permissions renamed wherever the manifest names one. */
private val renameSharedPermissionsPatch = patch {
    executeResources {
        document("AndroidManifest.xml").use { it.renameSharedPermissions() }
    }
}

/**
 * Lets Meta's own apps install beside a re-signed Facebook. See SharedPermissions.kt for which
 * permissions collide and why they're renamed rather than taken out.
 *
 * The code half follows the manifest: Facebook's code names FB_APP_COMMUNICATION when it registers
 * a receiver only its family may reach and when it sends a broadcast only its family may receive
 * (the cross-app broadcast manager, the MQTT service's receiver, the zero-rating switch, the login
 * state broadcast and Profilo's trace control, six places on 577 and 580). Left alone they'd name a
 * permission this build no longer holds, and those broadcasts would stop reaching Facebook itself.
 *
 * Letting a same-key Messenger, Messenger Lite or Facebook Lite sign in through this Facebook is
 * Restore screens on re-signed builds' job, not this one's: that patch hooks the one method Facebook
 * reads a package's signers through, and the same-key answer it gives a family caller there needs
 * nothing from this patch. A same-key Facebook and Messenger pair installs fine without this patch
 * too, since both declare Facebook's stock shared permission names under the same signer; this patch
 * only keeps a Meta-signed Messenger, Facebook Lite, Business Suite or Workplace from colliding with
 * a re-signed Facebook's copy of those names.
 *
 * No switch: a manifest can't change at run time, so the patch stays in while paused.
 */
@Suppress("unused")
val installBesideMetaAppsPatch = patch(
    name = "Install beside Meta's apps",
    description = "Lets the official Messenger, Facebook Lite, Business Suite and Workplace install next to your " +
        "patched Facebook. Without it, Android refuses them because they share two permissions with Facebook. " +
        "Root Mount installs don't need it. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(renameSharedPermissionsPatch, facebookExtensionPatch)

    executeBytecode {
        routeSharedLiterals()
        enableStatus("installBesideMetaApps")
    }
}
