/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.emoji

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf

internal const val PATCH = "Use the phone's emoji"
private const val EXTENSION_ROOT = "Lapp/morphe/extension/"
internal const val SYSTEM_EMOJI_TYPEFACE = "$EXTENSION_PACKAGE/emoji/SystemEmoji;->typeface()$TYPEFACE"
internal const val SKIP_REMOTE_EMOJI = "$EXTENSION_PACKAGE/emoji/SystemEmoji;->skipRemoteEmoji()Z"

/**
 * Facebook draws the emoji in its text with Meta's own emoji font, which it downloads and hands out
 * from one provider (see EmojiTypeface.kt). The provider asks the extension first thing and
 * answers with what it gets: the phone's default typeface while the switch is on, whose font
 * fallback ends in the phone's emoji font. Anything else, the switch off, a pause, settings not
 * ready yet or a failure in the extension, comes back as null and the provider's own code runs.
 *
 * A chat's big emoji is a picture Meta serves, drawn with the provider's typeface only until it
 * arrives. The maker of those pictures' addresses asks the extension first thing too, and while the
 * switch is on it answers no address, which Facebook takes as an emoji Meta has no picture of: the
 * chat keeps the emoji drawn with the provider's typeface. Otherwise the maker's own code runs.
 *
 * Reactions and stickers are pictures of their own, not text in a typeface, so nothing here
 * reaches them. Neither does androidx EmojiCompat, which Facebook starts with Google's downloadable
 * Noto font and which only fills in an emoji the phone's font doesn't have.
 */
@Suppress("unused")
val useSystemEmojiPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Use the phone's emoji",
    description = "Draws emoji with your phone's own emoji font instead of Meta's, so emoji in posts, comments " +
        "and chats match your keyboard. Reactions and stickers stay as they are. Starts off. Turn it on in " +
        "Hushfacebook settings > Appearance, where you can also pick an emoji font file, then restart Facebook.",
) {
    category("Theme")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val providers = classDefByStrings(FORCE_SYSTEM_EMOJI_FONT, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_ROOT) }
            .flatMap { owner -> owner.methods.filter(::isEmojiTypefaceProvider) }
        val provider = providers.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one emoji typeface provider, an instance method taking nothing and answering a " +
                "Typeface that holds \"$FORCE_SYSTEM_EMOJI_FONT\" and \"$EMOJI_TYPEFACE_PROVIDER\", " +
                "found ${providers.size}",
        )
        mutableClassDefBy(provider.definingClass).findMutableMethodOf(provider).answerPhoneEmojiFirst()

        val urlMakers = classDefByStrings(REMOTE_EMOJI_BASE, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_ROOT) }
            .flatMap { owner -> owner.methods.filter(::isRemoteEmojiUrlMaker) }
        val urlMaker = urlMakers.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one maker of emoji picture addresses, a static method answering a String from " +
                "(String, size, String, int) that holds \"$REMOTE_EMOJI_BASE\", found ${urlMakers.size}",
        )
        mutableClassDefBy(urlMaker.definingClass).findMutableMethodOf(urlMaker).answerNoPictureFirst()
        enableStatus("systemEmoji")
    }
}

/**
 * Asks the extension before the address maker's own first instruction and answers null, no
 * picture, when it says so. A false answer branches to that first instruction, so none of Facebook's
 * code is skipped. The answer goes through v0: at index 0 no local holds anything yet.
 */
internal fun MutableMethod.answerNoPictureFirst() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SKIP_REMOTE_EMOJI
            move-result v0
            if-eqz v0, :meta
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("meta", getInstruction(0)),
    )
}

/**
 * Asks the extension before the provider's own first instruction and returns its typeface when
 * it has one. A null answer branches to that first instruction, so none of Facebook's code is
 * skipped. The answer goes through v0: at index 0 no local holds anything yet.
 */
internal fun MutableMethod.answerPhoneEmojiFirst() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SYSTEM_EMOJI_TYPEFACE
            move-result-object v0
            if-eqz v0, :meta
            return-object v0
        """,
        ExternalLabel("meta", getInstruction(0)),
    )
}
