/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.updates

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.iface.Method

private const val UPDATE_PROMPTS = "$EXTENSION_PACKAGE/updates/UpdatePrompts;"
private const val BLOCK_PROMOTION = "$UPDATE_PROMPTS->blockPromotion()Z"
private const val BLOCK_FORCE_SYNC = "$UPDATE_PROMPTS->blockForceSync()Z"
private const val BLOCK_VERSION_CEILING = "$UPDATE_PROMPTS->blockVersionCeiling(Ljava/lang/String;)Z"

/**
 * Turns off the update prompts Facebook's own code shows, and the one request it makes for an
 * update, on a build that can't install Meta's updates. See Fingerprints.kt for what each anchor
 * is. Every anchor has to be found: a half-applied patch would keep one of the prompts, so a
 * missing one stops the patch naming all of them.
 */
@Suppress("unused")
val stopUpdatePromptsPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Stop update prompts",
    description = "Stops Facebook asking you to update, since a patched app can't install Meta's updates anyway. " +
        "Meta App Manager's update prompts and chat prompts aimed at older versions go too. On by default. Turn " +
        "it off in Hushfacebook settings > Updates.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val missing = mutableListOf<String>()
        fun one(what: String, matches: List<Method>): Method? {
            if (matches.size == 1) return matches.single()
            missing += "$what (found ${matches.size})"
            return null
        }
        val cellular = one(
            "the update-over-cellular promotion filter",
            methodsHolding(LATEST_VERSION_AVAILABLE) { isPromotionFilter(it, LATEST_VERSION_AVAILABLE, CELLULAR_PROVIDER) },
        )
        val ownership = one(
            "the update-ownership promotion filter",
            methodsHolding(OWNERSHIP_NEEDED) { isPromotionFilter(it, OWNERSHIP_NEEDED, OWNERSHIP_PROVIDER) },
        )
        val forceSync = one("the App Manager force-sync push handler", methodsHolding(FORCE_SYNC_SUCCESS, ::isForceSyncHandler))
        val evaluator = one(
            "the chat promotion filter evaluator",
            classDefByOrNull(FILTER_DISPATCHER)?.methods?.filter(::isFilterEvaluator).orEmpty(),
        )
        if (missing.isNotEmpty()) {
            throw PatchException(
                "$PATCH: could not find " + missing.joinToString("; ") +
                    ". Applying the rest would leave that prompt in, so nothing was changed.",
            )
        }
        mutable(cellular!!).failFilterWhenBlocked()
        mutable(ownership!!).failFilterWhenBlocked()
        mutable(forceSync!!).skipWhenBlocked()
        mutable(evaluator!!).failVersionCeilingWhenBlocked()
        enableStatus("updatePrompts")
    }
}

/** Every method of a class loading exactly [literal] that [wanted] takes. */
private fun BytecodePatchContext.methodsHolding(literal: String, wanted: (Method) -> Boolean): List<Method> =
    classDefByStrings(literal, StringComparisonType.EQUALS).flatMap { classDef -> classDef.methods.filter(wanted) }

/** The mutable copy of [method], matched on its name and its parameters' descriptors. */
private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

/** A promotion filter answers "doesn't pass" first thing while the switch is on. */
internal fun MutableMethod.failFilterWhenBlocked() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $BLOCK_PROMOTION
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/** The push handler returns first thing while the switch is on, and the manager isn't asked. */
internal fun MutableMethod.skipWhenBlocked() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $BLOCK_FORCE_SYNC
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * The evaluator answers "evaluated and failed" for the version ceiling filter while the switch is
 * on, and runs Facebook's own code for every other filter. The filter's name is the first
 * parameter, passed as a range so its register may sit above v15.
 */
internal fun MutableMethod.failVersionCeilingWhenBlocked() {
    requireLocals(PATCH, 1)
    val filterName = parameterRegister(0)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $filterName .. $filterName }, $BLOCK_VERSION_CEILING
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, $FILTER_FAILED
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
