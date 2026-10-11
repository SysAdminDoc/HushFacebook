/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.coexist.clonedPackagePatch
import app.morphe.patches.facebook.media.hdr.playbackFormatEvidencePatch
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.resignedtrust.fbnsSignersPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef

internal const val ENTRY = "$EXTENSION_PACKAGE/settings/SettingsEntry;"

/** The activity the manifest's launcher alias targets. A manifest name is never obfuscated. */
internal const val MAIN_TAB_ACTIVITY = "Lcom/facebook/katana/activity/FbMainTabActivity;"

/**
 * Why this APK can't be patched at all, said before the settings patch changes anything, or null
 * when nothing here stands in the way. [hasApplication] says the APK's readable dex carries
 * FacebookApplication, and [mainTab] is the main tab activity as it carries it, or null.
 *
 * Meta's Facebook builds for Android 9 (arm64-v8a) and Android 8 (armeabi-v7a) ship one startup
 * dex, 4,420 classes in 580.0.0.51.74 for Android 9, and pack the rest of the code into a compressed
 * Superpack archive, `assets/secondary-program-dex-jars/store-0.dex.spo`, which the patcher can't
 * read (checked 2026-09-26: 0 of 26 patches apply). The application class is in the startup dex and
 * the main tab activity isn't, so without this the settings patch stopped saying no class of the
 * activity's hierarchy declares onCreate, which named the symptom and not the build.
 */
internal fun compressedCodeRefusal(hasApplication: Boolean, mainTab: ClassDef?): String? {
    if (!hasApplication) return null
    if (mainTab != null && mainTab.methods.any { it.implementation != null }) return null
    return "This Facebook build has FacebookApplication but no readable code for FbMainTabActivity, the screen " +
        "Facebook opens on. That's what a Facebook build for Android 9 or older looks like. Meta packs all but " +
        "its startup code into a compressed archive (assets/secondary-program-dex-jars/store-0.dex.spo) that " +
        "the patcher can't read, so none of Hushfacebook's patches can apply. Hushfacebook supports Android 11 " +
        "and newer. Patch the (arm64-v8a) (Android 11+) build of Facebook instead."
}

/**
 * The first class in [type]'s hierarchy, as far as the APK carries it, that declares this
 * method with a body.
 */
internal fun BytecodePatchContext.declaredInHierarchy(
    type: String,
    name: String,
    vararg parameters: String,
): MutableMethod = superclassChain(type)
    .mapNotNull { mutableClassDefByOrNull(it) }
    .firstNotNullOfOrNull { classDef ->
        classDef.methods.singleOrNull { method ->
            method.name == name && method.returnType == "V" && method.implementation != null &&
                method.parameterTypes.map { it.toString() } == parameters.toList()
        }
    } ?: throw PatchException("No class of $type's hierarchy declares $name(${parameters.joinToString("")})V")

/**
 * Makes the Hushfacebook screen reachable two ways. Inside Facebook, a long press on the Facebook
 * logo at the top of the home feed opens it. From the home screen, a long-press shortcut on
 * Facebook's launcher icon opens Facebook with an extra, the activity reports it, and the screen
 * opens over the next Facebook activity to resume. Every name used here is a manifest component,
 * a framework override or call, or a class name and trace string Facebook keeps. Nothing is added
 * to the manifest.
 */
@Suppress("unused")
val settingsPatch = bytecodePatch(
    name = "Hushfacebook settings",
    description = "Adds Hushfacebook settings to Facebook, where you turn features on or off, pause Hushfacebook " +
        "and back up your choices. To open them, long-press the Facebook logo at the top of your feed or " +
        "Facebook's app icon. Other patches need it, so keep it selected.",
    default = true,
) {
    category("Settings")
    dependsOn(facebookExtensionPatch)
    // Every patch depends on this one, so a copy renamed with Clone app works whatever is picked,
    // and the patches Morphe runs before Clone app bring it in early enough. See ClonedPackage.kt.
    dependsOn(clonedPackagePatch)
    // With Debug logging on, a report names the video formats Facebook's decoders were set up
    // with, so it says whether a video was HDR whatever was picked. See PlaybackFormatEvidencePatch.kt.
    dependsOn(playbackFormatEvidencePatch)
    // A re-signed build's push service rejects Facebook and restarts itself in a loop unless its
    // signer read gets the original certificate, whatever was picked. See FbnsSignersPatch.kt.
    dependsOn(fbnsSignersPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // A build whose code the patcher can't read is refused first, naming the build it most
        // likely is, before anything in it changes.
        compressedCodeRefusal(classDefByOrNull(FACEBOOK_APPLICATION) != null, classDefByOrNull(MAIN_TAB_ACTIVITY))
            ?.let { throw PatchException(it) }

        // Before each return of the application's onCreate, after Facebook's own startup: the
        // application overrides registerActivityLifecycleCallbacks, and the override is only safe
        // to call once Facebook has set itself up.
        val applicationOnCreate = declaredInHierarchy(FACEBOOK_APPLICATION, "onCreate")
        val returns = applicationOnCreate.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
        if (returns.isEmpty()) throw PatchException("$FACEBOOK_APPLICATION.onCreate never returns")
        returns.asReversed().forEach { index ->
            applicationOnCreate.addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $ENTRY->onApplicationCreate(Landroid/content/Context;)V",
            )
        }

        declaredInHierarchy(MAIN_TAB_ACTIVITY, "onCreate", "Landroid/os/Bundle;").addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $ENTRY->onActivityCreate(Landroid/app/Activity;)V",
        )
        declaredInHierarchy(MAIN_TAB_ACTIVITY, "onNewIntent", "Landroid/content/Intent;").addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $ENTRY->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V",
        )

        // Facebook pushes its own shortcuts at rank 0 whenever it posts some notifications, and the
        // newest push goes first, so the shortcut above ended up last, where a launcher that shows
        // only a few cut it off. Each of those calls now goes through the extension, which puts it
        // back in front afterwards. Framework names only, which the obfuscator keeps.
        rerouteShortcutCalls()

        // Some launchers have no shortcut menu at all (#2), so there's a way in from inside
        // Facebook too: the call that gives the feed's Facebook logo its touch listener goes
        // through the extension, which gives the logo a long press that opens the screen.
        hookLogoLongPress()
    }
}
