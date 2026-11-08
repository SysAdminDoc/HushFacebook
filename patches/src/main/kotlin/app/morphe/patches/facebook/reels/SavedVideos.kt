/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * A video opened from Saved (#404). Read from 585 (arm64 and armv7) and its Android 9 build,
 * 2026-11-07; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The opens-elsewhere ask Watch again uses ([watchAgainAnchors], 585 LX/08Fc;'s A05) is the same
 *   one a video tap in Saved makes. Its first step names the video's channel through a sibling with
 *   the ask's parameters that returns a String (585 A02), and the String parameter "saved2_video_launcher"
 *   comes back as [SAVED_CHANNEL].
 * - The ask then says yes for that channel, which opens the video in the Reels viewer, whose next
 *   swipe is a suggested reel and not the next saved video. A no plays it in the video player.
 * - The hook sits right after the channel lands: it hands the channel to the extension, and returns
 *   no when the extension says to keep it in the player. Any other channel goes on untouched.
 */

internal const val SAVED_VIDEOS = "$EXTENSION_PACKAGE/reels/SavedVideos;"
internal const val STAYS_IN_PLAYER = "$SAVED_VIDEOS->staysInPlayer(Ljava/lang/String;)Z"

/** The channel Facebook gives a video opened from Saved. */
internal const val SAVED_CHANNEL = "vod_watchfeed_saved"

private const val PATCH = "Clean up Reels"

/** Where the ask names its channel: the call, and the register its move-result-object takes. */
internal data class ChannelCall(val call: Int, val register: Int)

/**
 * The call in [method], the opens-elsewhere ask [ask], to the sibling with the ask's parameters
 * that returns the channel, taken right after by a move-result-object. Null unless there's one.
 */
internal fun channelCall(method: Method, ask: MethodReference): ChannelCall? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val askParams = ask.parameterTypes.map(CharSequence::toString)
    val calls = code.indices.filter { at ->
        val reference = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        reference.definingClass == ask.definingClass && reference.returnType == "Ljava/lang/String;" &&
            reference.parameterTypes.map(CharSequence::toString) == askParams &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }
    val call = calls.singleOrNull() ?: return null
    return ChannelCall(call, (code[call + 1] as OneRegisterInstruction).registerA)
}

/** The opens-elsewhere ask's own code, from the class that defines it. */
internal fun BytecodePatchContext.askMethod(ask: MethodReference): MutableMethod {
    val params = ask.parameterTypes.map(CharSequence::toString)
    return mutableClassDefBy(ask.definingClass).methods.firstOrNull {
        it.name == ask.name && it.returnType == ask.returnType && it.parameterTypes.map(CharSequence::toString) == params
    } ?: throw PatchException("$PATCH: ${described(ask)} has no code in its class")
}

/**
 * A video opened from Saved plays in the video player instead of the Reels viewer when the switch is on
 * (#404). [ask] comes from [hookWatchAgainAsk], read before Watch again's own hook moves its taps.
 */
internal fun BytecodePatchContext.keepSavedVideosInPlayer(ask: MethodReference) {
    val method = askMethod(ask)
    val channel = channelCall(method, ask)
        ?: throw PatchException("$PATCH: ${described(ask)} names no channel through a sibling returning a String")
    val after = channel.call + 2
    val answer = method.freeLocalsAt(PATCH, after, 1, parameters = true).single()
    method.addInstructionsWithLabels(
        after,
        """
            invoke-static/range { v${channel.register} .. v${channel.register} }, $STAYS_IN_PLAYER
            move-result v$answer
            if-eqz v$answer, :facebooks_answer
            const/4 v$answer, 0x0
            return v$answer
        """,
        ExternalLabel("facebooks_answer", method.getInstruction(after)),
    )
}
