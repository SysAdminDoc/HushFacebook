/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.accounts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.superclassChain

/** Facebook's own login screen. A manifest component, so its name is never obfuscated. */
internal const val LOGIN_ACTIVITY = "Lcom/facebook/account/login/activity/SimpleLoginActivity;"

/**
 * The logged-out screen a phone with a saved account opens on: the account picker, and the form
 * behind "use another profile". A Bloks activity, so it has onStart and no onResume.
 */
internal const val LOGGED_OUT_ACTIVITY =
    "Lcom/facebook/bloks/facebook/loggedout/FbExperimentalLoggedOutBloksActivity;"

/** The activities a login starts in, each with the method it shows its first frame in. */
private val LOGIN_SCREENS = listOf(
    LOGIN_ACTIVITY to "onResume",
    LOGGED_OUT_ACTIVITY to "onStart",
)

/** Facebook's viewer context, which carries the signed-in session's token and id. */
internal const val VIEWER_CONTEXT = "Lcom/facebook/auth/viewercontext/ViewerContext;"

/** The extension's entry point for the login screen, handed the activity as it resumes. */
private const val LOGIN_IMPORT = "$EXTENSION_PACKAGE/accounts/LoginImport;->attach(Landroid/app/Activity;)V"

/** The extension's entry point for the session, handed each context as it is built. */
private const val CAPTURE = "$EXTENSION_PACKAGE/accounts/SessionCapture;->capture(Ljava/lang/Object;)V"

/**
 * Import and export account, in one patch: the settings rows, the login screen's button, and the
 * session the token comes from.
 *
 * <p>Export account, Copy token and Import account are rows of Hushfacebook's settings, and they
 * work on what the extension can reach on its own, cookies included. The token is the part that
 * isn't on disk: Facebook fetches it at sign-in and keeps it in the ViewerContext every request
 * carries, so the patch ends each of that context's constructors with a call that hands the
 * instance to the extension, which reads `mAuthToken`, `mUserId` and `mUsername` off it when an
 * account is exported.
 *
 * <p>The login screen is the one place a person with no account signed in can reach an import, and
 * the settings rows are behind the signed-in app. The patch hands SimpleLoginActivity to the
 * extension as it resumes; the extension adds one tagged button over the window's content the first
 * time and leaves it alone after that, and the button runs the same import as the settings row.
 *
 * <p>In the default selection: an account that can't be exported is an account that can't be
 * shared, and a phone that has been signed out has no other way in.
 */
@Suppress("unused")
val accountSharePatch = patch(
    name = "Import and export account",
    description = "Adds Export account, Copy token and Import account to Hushfacebook settings, and an "
        + "Import account button to Facebook's login screen, so an account saved from one phone signs "
        + "in on another without the password. Export writes the account's cookies and its access token.",
) {
    category("Settings")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        // The session first: a build that carries no ViewerContext can still export cookies, but a
        // bundle that promised a token and wrote none would be worse than one that says so here.
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

        // Then the login screens, which is where an import starts when nothing is signed in: the
        // sign-in form, and the logged-out account picker a phone with a saved account opens on.
        var hooked = 0
        for ((type, methodName) in LOGIN_SCREENS) {
            val screen = mutableClassDefByOrNull(type) ?: continue
            val shown = screen.methods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.isEmpty() && method.returnType == "V" &&
                    method.implementation != null
            } ?: continue
            // Before the activity's own work, so the button is in the window the first frame it draws.
            shown.addInstruction(0, "invoke-static {p0}, $LOGIN_IMPORT")
            hooked++
        }
        if (hooked == 0) {
            throw PatchException("No login screen of this build shows a frame in a method the patch knows: "
                + LOGIN_SCREENS.joinToString(", ") { it.first + "." + it.second + "()V" })
        }

        // The Bloks screen a logged-out phone opens on keeps its lifecycle to itself, so the
        // extension watches every screen the application opens and adds the button to the ones that
        // start a login. The call goes beside the context hand-over the extension patch already put
        // at the start of the application's onCreate.
        val application = superclassChain(FACEBOOK_APPLICATION)
            .mapNotNull { type -> mutableClassDefByOrNull(type) }
            .firstNotNullOfOrNull { classDef ->
                classDef.methods.singleOrNull { method ->
                    method.name == "onCreate" && method.parameterTypes.isEmpty() &&
                        method.returnType == "V" && method.implementation != null
                }
            }
        if (application != null) {
            val at = if (application.implementation!!.instructions.count() >= 2) 2 else 0
            application.addInstruction(
                at,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_PACKAGE/accounts/LoginImport;->start(Landroid/content/Context;)V",
            )
        }
    }
}
