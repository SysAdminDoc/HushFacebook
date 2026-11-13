/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.music

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * How long a song on a photo story plays, read from 584 (#156).
 *
 * The Story composer asks one static method how long the music clip may be, handing it the
 * user session and the composer's state, each time a song is picked or its clip is moved. For a
 * video it answers from the video and the Story length setting. For a photo it answers 30000 when
 * the photo is a feed post shared to a Story, else the trimmed video's length when there is one,
 * else a MobileConfig value read as a double when Facebook's test gives one above zero, else
 * 15000:
 *
 *     invoke-static {vConfig, vIdHi, vIdLo}, MobileConfigUnsafeContext->A00(Object, J)D
 *     move-result-wide vD
 *     double-to-int vLength, vD
 *     if-lez vLength, :fifteen
 *     return vLength
 *   :fifteen
 *     const/16 vLength, 0x3a98
 *     return vLength
 *
 * The music picker cuts the song to that length, and the photo story turns into a video as long
 * as its song. The patch asks the extension right before both of those returns, and while the
 * switch is on it answers at least 30000, the length Facebook already gives a shared feed post.
 */

internal const val STORY_MUSIC_PATCH = "Longer music on photo stories"

internal const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val FEED_ATTACHMENT_CONFIG = "Lcom/facebook/ipc/inspiration/config/FeedAttachmentConfig;"
internal const val CLIP_LENGTH_CALL = "$EXTENSION_PACKAGE/stories/StoryMusic;->clipLength(I)I"

/** Facebook's own clip length for a song on a photo, and the one it gives a shared feed post. */
internal const val PHOTO_CLIP_MS = 15000
internal const val FEED_POST_CLIP_MS = 30000

/** The most instructions between the double-to-int and its return: the check that it's above zero. */
private const val RETURN_REACH = 2

private fun refuse(detail: String): Nothing = throw PatchException("$STORY_MUSIC_PATCH: $detail")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

/** The cheap half of [isClipLength]: static, (FbUserSession, state) to int, with code. */
private fun hasClipLengthShape(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "I" &&
        method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == USER_SESSION

private fun Instruction.literal(): Int? = (this as? NarrowLiteralInstruction)?.narrowLiteral

/**
 * Whether [method] is the composer's music clip length: [hasClipLengthShape], asks the composer's
 * configuration for its FeedAttachmentConfig, and holds both of Facebook's photo lengths.
 */
internal fun isClipLength(method: Method): Boolean {
    if (!hasClipLengthShape(method)) return false
    val code = method.code()
    return code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == FEED_ATTACHMENT_CONFIG } &&
        code.any { it.opcode == Opcode.CONST_16 && it.literal() == FEED_POST_CLIP_MS } &&
        code.any { it.opcode == Opcode.CONST_16 && it.literal() == PHOTO_CLIP_MS }
}

/** The two returns of a photo's clip length: [fifteen] after its 15000, [config] after the MobileConfig value. */
internal class ClipReturns(val method: Method, val fifteen: Int, val config: Int)

/**
 * Where [this] returns 15000 and where it returns its MobileConfig value, or a refusal naming what moved.
 * [classOf] resolves a static helper the MobileConfig read may go through: 587's 32-bit build reads
 * the length with a utility class's `(Object, J)D` that casts the config and asks it
 * (`LX/03gq;->A00` there), which only counts when its body can be read.
 */
internal fun Method.clipReturns(classOf: ((String) -> ClassDef?)? = null): ClipReturns {
    val code = code()
    val fifteens = code.indices.filter { at ->
        val load = code[at]
        val next = code.getOrNull(at + 1)
        load.opcode == Opcode.CONST_16 && load.literal() == PHOTO_CLIP_MS && next?.opcode == Opcode.RETURN &&
            (next as OneRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
    }
    val fifteen = fifteens.singleOrNull()?.plus(1) ?: refuse("expected one return of $PHOTO_CLIP_MS in $name, found ${fifteens.size}")

    val configs = code.indices.mapNotNull { at ->
        if (code[at].opcode != Opcode.DOUBLE_TO_INT || at < 2) return@mapNotNull null
        if (code[at - 1].opcode != Opcode.MOVE_RESULT_WIDE || !readsMobileConfig(code[at - 2], "D", classOf)) return@mapNotNull null
        val length = (code[at] as TwoRegisterInstruction).registerA
        (at + 1..minOf(at + RETURN_REACH, code.size - 1)).firstOrNull { index ->
            code[index].opcode == Opcode.RETURN && (code[index] as OneRegisterInstruction).registerA == length
        }
    }
    val config = configs.singleOrNull() ?: refuse("expected one return of a MobileConfig length in $name, found ${configs.size}")
    if (config > fifteen) refuse("the MobileConfig length in $name returns after the $PHOTO_CLIP_MS")
    return ClipReturns(this, fifteen, config)
}

/** The composer's music clip length and its two photo returns, found before anything changes. */
internal fun BytecodePatchContext.findClipReturns(): ClipReturns {
    val methods = mutableListOf<Method>()
    // By the config the clip length asks for (#216).
    classDefForEachReferencing(types = listOf(FEED_ATTACHMENT_CONFIG)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(methods, ::isClipLength)
    }
    val method = methods.singleOrNull()
        ?: refuse("expected one static (FbUserSession, state) clip length asking for a FeedAttachmentConfig, found " +
            methods.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" })
    return method.clipReturns { type -> classDefByOrNull(type) }
}

/**
 * Hands each photo length to the extension right before it returns. The later return goes first,
 * so the earlier index still points where it did. At the return's own label, so a branch onto the
 * return goes through the ask too.
 */
internal fun MutableMethod.askBeforeClipReturns(returns: ClipReturns) {
    for (index in listOf(returns.fifteen, returns.config).sortedDescending()) {
        val length = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$length .. v$length }, $CLIP_LENGTH_CALL
                move-result v$length
            """,
        )
    }
}

/**
 * Songs on a photo story play for up to 30 seconds. See the notes above for where, and the
 * extension's StoryMusic for when.
 *
 * In Morphe Manager's default selection with the switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val longerStoryMusicPatch = bytecodePatch(
    name = "Longer music on photo stories",
    description = "A song you add to a photo for your Story plays for up to 30 seconds instead of 15. Starts off. " +
        "Turn it on in Hushfacebook settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("storyMusicLength")
        val returns = findClipReturns()
        mutableClassDefBy(returns.method.definingClass).findMutableMethodOf(returns.method).askBeforeClipReturns(returns)
        enableStatus("storyMusicLength")
    }
}
