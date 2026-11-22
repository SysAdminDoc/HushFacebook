/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.composer

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Field

/**
 * Facebook's text boxes offer people to tag only after `@`. See TagSuggestionAnchors.kt for how
 * the box decides to look people up for a plain word, and the extension's TagSuggestions for when
 * it keeps Facebook's answer.
 *
 * In the default selection with its switch off: it takes away something Facebook does on purpose,
 * so turning it on is the choice.
 */
@Suppress("unused")
val tagSuggestionsOnlyAfterAtPatch = patch(
    name = "Tag suggestions only after @",
    description = "Stops Facebook offering people to tag while you type ordinary words in posts and comments, so " +
        "names don't pop up in your way. Typing @ still brings up the list. Starts off. Turn it on in " +
        "Hushfacebook settings > Comments and writing.",
) {
    category("Comments")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val boxes = classDefByStrings(COMMON_WORDS_FAILURE, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter(::isMentionFiltering) }
        val filtering = boxes.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one performFiltering(CharSequence, int) holding \"$COMMON_WORDS_FAILURE\", " +
                "found ${boxes.size}",
        )
        val method = mutableClassDefBy(filtering.definingClass).findMutableMethodOf(filtering)
        val gate = wordGate(method) ?: throw PatchException(
            "$PATCH: ${method.definingClass}->performFiltering has no single check that returns for a word " +
                "without @ on its behaviour's flag, before the lookup",
        )
        val behaviour = classDefByOrNull(gate.flagField.definingClass) ?: throw PatchException(
            "$PATCH: this build has no ${gate.flagField.definingClass}, the class of the flag the check reads",
        )
        val box = boxField(behaviour, method.definingClass) ?: throw PatchException(
            "$PATCH: ${behaviour.type} has no single field holding the ${method.definingClass} it belongs to",
        )
        method.askExtensionAtWordGate(gate, box)
        enableStatus("tagSuggestions")
    }
}

/**
 * Right after the flag's read, hands the flag and the box (read from the behaviour into a local
 * nothing uses there) to the extension, and keeps its answer in the flag's register for the
 * `if-eqz` that follows. True skips the lookup the way a set flag does.
 */
internal fun MutableMethod.askExtensionAtWordGate(gate: WordGate, box: Field) {
    // iget-object and invoke-static name their registers in four bits each. The flag's own
    // iget-boolean names the flag and the behaviour in four bits too, and the borrowed local is
    // picked from v0 to v15.
    val at = gate.flagRead + 1
    val held = freeLocalsAt(PATCH, at, 1).single()
    addInstructions(
        at,
        """
            iget-object v$held, v${gate.behaviour}, ${box.definingClass}->${box.name}:${box.type}
            invoke-static { v${gate.flag}, v$held }, $SKIPS_WORD
            move-result v${gate.flag}
        """,
    )
}
