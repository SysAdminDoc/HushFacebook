/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The "AI info" label on a reel made with AI (#574). Read from 588, 2026-11-24, arm64; the obfuscated
 * names here are for reviewers, the code never writes one down.
 *
 * - A reel's attribution models (its location, tagged people, sound and the rest) come from one
 *   method (LX/07r3;->A06(LX/09w7;)), which starts from the story's own list and adds a
 *   XFBFBShortsGenAITransparencyAttribution of its own, labeled "AI info" by default, when a server
 *   flag says the reel is made with AI. It adds a with-tags and a location attribution the same way.
 * - The label under the caption (built by LX/09QQ;->A1B) walks that list for the GenAI type, asks
 *   Facebook's check of whether to show it (the static holding "genai_transparency_label_not_shown",
 *   LX/08kw;->A0M), and builds the label component.
 * - The reel's info row (LX/0A1C;->A1B) and the sponsored info row (LX/0DWf;->A03) look the type up by
 *   name with a static find (LX/08kw;->A02), which walks the same list.
 * - So one filter at that method's returns takes the label out of all three. The chips under a reel
 *   are a list of their own, built by a different method, which never names the GenAI type.
 */

internal const val FILTER_AI_LABEL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->filterAiLabel(Ljava/lang/Object;)[Ljava/lang/Object;"

/** The attribution type Facebook draws as the "AI info" label. */
internal const val AI_LABEL_ATTRIBUTION = "XFBFBShortsGenAITransparencyAttribution"

/** The two other attributions the same method adds on its own, which tell it from the label's readers. */
internal val ADDED_ATTRIBUTIONS = listOf("XFBFBShortsWithTagsAttribution", "XFBFBShortsLocationAttribution")

private const val PATCH = "Clean up Reels"

/**
 * Whether [method] hands out a reel's attribution models: one argument that isn't a list in, an
 * ImmutableList out, and the GenAI type and both [ADDED_ATTRIBUTIONS] loaded. The label's readers
 * name the GenAI type alone, and the chip list builder takes the list as its argument.
 */
internal fun isAttributionList(method: Method): Boolean {
    if (method.returnType != IMMUTABLE_LIST || method.parameterTypes.size != 1) return false
    if (method.parameterTypes.single().toString() == IMMUTABLE_LIST) return false
    val loaded = method.implementation?.instructions
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet()
        ?: return false
    return AI_LABEL_ATTRIBUTION in loaded && loaded.containsAll(ADDED_ATTRIBUTIONS)
}

/** Every return of the attribution list goes through the extension's AI label filter. */
internal fun BytecodePatchContext.hideAiLabel() {
    val lists = classDefByStrings(AI_LABEL_ATTRIBUTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter(::isAttributionList).map { classDef.type to it } }
    val (owner, list) = lists.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method adding $AI_LABEL_ATTRIBUTION and ${ADDED_ATTRIBUTIONS.joinToString()} " +
            "to a reel's attributions, found ${lists.size}",
    )
    mutableClassDefBy(owner).methods.single {
        it.name == list.name && it.returnType == list.returnType &&
            it.parameterTypes.map(CharSequence::toString) == list.parameterTypes.map(CharSequence::toString)
    }.filterChipsBeforeEveryReturn(FILTER_AI_LABEL)
}
