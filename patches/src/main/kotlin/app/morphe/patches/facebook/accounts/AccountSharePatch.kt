/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.accounts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/** Facebook's viewer context, which carries the signed-in session's token and id. */
internal const val VIEWER_CONTEXT = "Lcom/facebook/auth/viewercontext/ViewerContext;"

/** The extension's entry point for the session, handed each context as it is built. */
private const val CAPTURE = "$EXTENSION_PACKAGE/accounts/SessionCapture;->capture(Ljava/lang/Object;)V"

/**
 * Export token: one row in Hushfacebook's settings, on the session the app is signed in with.
 *
 * <p>The token is the part that isn't on disk: Facebook fetches it at sign-in and keeps it in the
 * ViewerContext every request carries, so the patch ends each of that context's constructors with
 * a call that hands the instance to the extension, which reads `mAuthToken`, `mUserId` and
 * `mUsername` off it when the form is opened. The email and password never leave the app, because
 * the form doesn't read them: a token is what another tool asks for.
 */
@Suppress("unused")
val accountSharePatch = patch(
    name = "Export token",
    description = "Adds Export token to Hushfacebook settings: each signed-in account's access token, "
        + "in the open to copy or check, and saved as a small JSON file.",
) {
    category("Settings")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val context = mutableClassDefByOrNull(VIEWER_CONTEXT)
            ?: throw PatchException("$VIEWER_CONTEXT isn't in this build, so no token can be captured.")
        val constructors = context.methods.filter { it.name == "<init>" && it.implementation != null }
        if (constructors.isEmpty()) throw PatchException("$VIEWER_CONTEXT declares no constructor with a body.")
        for (constructor in constructors) {
            // Before the constructor returns, so every field it writes is already set. Passing the
            // instance before its super call would be an uninitialized reference.
            val last = constructor.implementation!!.instructions.count() - 1
            constructor.addInstruction(last, "invoke-static {p0}, $CAPTURE")
        }
    }
}
