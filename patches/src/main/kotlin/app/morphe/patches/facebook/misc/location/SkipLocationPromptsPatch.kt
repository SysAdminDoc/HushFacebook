/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Facebook's device location screen, read from 587 on 2026-11-17 (#498). The obfuscated names are
 * for reviewers; the code finds each by the kept class name and by shape.
 *
 * Facebook asks for your location through one activity, DeviceLocationSettingsOptInActivity, which
 * its link fb://device_location_optin opens with a source and an entry point. Its onActivityCreate
 * calls its parent's (LocationSettingsOptInActivityBase), then asks whether there's anything to ask
 * (A0H). With nothing to ask it closes through the parent's (Intent, boolean) method (A0E), which
 * sends Facebook's own "no" to whatever opened the screen, sets the result and finishes. Otherwise it
 * shows the pre-prompt page "To use Location Services, allow Facebook to access your location"
 * (A0I, a server-drawn page), Android's permission question or Google's location dialog.
 *
 * Right after the parent's onActivityCreate, the screen goes to the extension. A yes closes it
 * through that same (Intent, boolean) method with no intent and false, the way Facebook closes it
 * when there's nothing to ask, and returns before anything shows.
 */

internal const val PATCH = "Skip location prompts"

/** The device location screen, a kept class name. */
internal const val LOCATION_SCREEN = "Lcom/facebook/location/optin/DeviceLocationSettingsOptInActivity;"

internal const val SKIPS = "$EXTENSION_PACKAGE/misc/LocationPrompts;->skips(Landroid/app/Activity;)Z"

private const val INTENT = "Landroid/content/Intent;"
private const val BUNDLE = "Landroid/os/Bundle;"

/**
 * Where the hook goes in the screen's onActivityCreate and what it needs there.
 *
 * @property superCall the index of the call to the parent's onActivityCreate, on this screen.
 * @property close the (Intent, boolean) method the screen closes itself with when there's nothing to ask.
 * @property self the register holding the screen.
 */
internal class ScreenStart(val method: Method, val superCall: Int, val close: MethodReference, val self: Int)

/** onActivityCreate(Bundle), FbFragmentActivity's onCreate for its subclasses. A Method is a MethodReference too. */
private fun MethodReference.isActivityCreate() =
    name == "onActivityCreate" && returnType == "V" && parameterTypes.map { it.toString() } == listOf(BUNDLE)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Reads [screen]'s onActivityCreate. It refuses unless the method starts with the parent's
 * onActivityCreate on the screen itself, closes through exactly one (Intent, boolean) void method
 * of the screen or its parent, and has two registers of its own for the snippet.
 */
internal fun screenStart(screen: ClassDef): ScreenStart {
    val create = screen.methods.filter { it.isActivityCreate() }.singleOrNull()
        ?: refuse("$LOCATION_SCREEN has no onActivityCreate(Bundle) of its own")
    val implementation = create.implementation ?: refuse("the screen's onActivityCreate has no code")
    val code = implementation.instructions.toList()
    val self = implementation.registerCount - 2
    val first = code.firstOrNull()
    val parent = (first as? ReferenceInstruction)?.reference as? MethodReference
    if (first?.opcode != Opcode.INVOKE_SUPER || parent == null || !parent.isActivityCreate() ||
        parent.definingClass != screen.superclass || (first as FiveRegisterInstruction).registerC != self
    ) {
        refuse("the screen's onActivityCreate doesn't start with its parent's")
    }
    val closes = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { called ->
            called.returnType == "V" && called.parameterTypes.map { it.toString() } == listOf(INTENT, "Z") &&
                (called.definingClass == screen.type || called.definingClass == screen.superclass)
        }
        .distinctBy { it.toString() }
    val close = closes.singleOrNull()
        ?: refuse("expected one way for the screen to close itself, found ${closes.map { it.toString() }}")
    // v0 and v1 carry the answer and the close call's arguments; invoke-virtual names registers below 16.
    if (self < 2 || self > 15) refuse("the screen's onActivityCreate has no room for the hook (${implementation.registerCount} registers)")
    return ScreenStart(create, 0, close, self)
}

/**
 * Right after the parent's onActivityCreate, hands the screen to [SKIPS]. A yes closes it through
 * Facebook's own close with no intent and false and returns; a no goes on as the method was.
 */
internal fun BytecodePatchContext.hookLocationScreen() {
    val screen = classDefByOrNull(LOCATION_SCREEN) ?: refuse("this Facebook build has no $LOCATION_SCREEN")
    val start = screenStart(screen)
    val method = mutableClassDefBy(LOCATION_SCREEN).findMutableMethodOf(start.method)
    method.addInstructionsWithLabels(
        start.superCall + 1,
        """
            invoke-static { v${start.self} }, $SKIPS
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            const/4 v1, 0x0
            invoke-virtual { v${start.self}, v0, v1 }, ${start.close}
            return-void
        """,
        ExternalLabel("keep", method.getInstruction(start.superCall + 1)),
    )
}

@Suppress("unused")
val skipLocationPromptsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Skip location prompts",
    description = "Closes Facebook's screen asking you to allow Location Services as soon as it opens, as if you " +
        "tapped Skip, so it stops getting in your way. On by default. Turn it off in Hushfacebook settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        hookLocationScreen()
        enableStatus("locationPrompts")
    }
}
