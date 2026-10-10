/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Unlocks the app icons Facebook keeps for Facebook Plus on its App icon page, and keeps the pick
 * through an update. See UnlockAppIconsAnchors.kt for the two checks it answers and the reset it
 * skips, and the extension's AppIcons for when.
 *
 * In the default selection with its switch off: it changes what Facebook's own page offers, so
 * it's a choice to make. Everything is found before anything changes.
 */
@Suppress("unused")
val unlockAppIconsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Unlock app icons",
    description = "Lets you pick the app icons Facebook keeps for Facebook Plus on its App icon page, so your home " +
        "screen icon can be any of them. Starts off. Turn it on in Hushfacebook settings > Appearance.",
) {
    category("Theme")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val providers = classDefByStrings(PROVIDER_TAG, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter(::isBenefitProvider)
        val provider = providers.singleOrNull()
            ?: throw PatchException("$PATCH: expected one benefit provider, found ${providers.map { it.type }}")
        val checks = provider.methods.filter(::isBenefitCheck)
        val check = checks.singleOrNull()
            ?: throw PatchException("$PATCH: expected one benefit check in ${provider.type}, found ${checks.map { it.name }}")

        val looks = mutableListOf<Triple<String, Method, Int>>()
        for (classDef in classDefByStrings(BENEFIT, StringComparisonType.EQUALS).distinctBy { it.type }) {
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) continue
            for (method in classDef.methods) pickerBenefitLook(method)?.let { looks += Triple(classDef.type, method, it) }
        }
        val look = looks.singleOrNull()
            ?: throw PatchException("$PATCH: expected one App icon page look at $BENEFIT, found ${looks.map { it.first }}")

        val resets = mutableListOf<Triple<String, Method, Int>>()
        for (classDef in classDefByStrings(COMPONENT_MANAGER_KEY, StringComparisonType.EQUALS).distinctBy { it.type }) {
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) continue
            for (method in classDef.methods) componentStateCalls(method).forEach { resets += Triple(classDef.type, method, it) }
        }
        val reset = resets.singleOrNull()
            ?: throw PatchException("$PATCH: expected one component manager state call, found ${resets.map { "${it.first}->${it.second.name}" }}")

        mutableClassDefBy(provider.type).findMutableMethodOf(check).unlockFirst()
        mutableClassDefBy(look.first).findMutableMethodOf(look.second).passPickerBenefit(look.third)
        mutableClassDefBy(reset.first).findMutableMethodOf(reset.second).passComponentState(reset.third)
        enableStatus("unlockAppIcons")
    }
}
