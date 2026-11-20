/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postdates

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.posttext.methodsWithString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** What the song's piece of the post subtitle logs as it hands its text over (588 `StoryAttributionPlugin->A01`). */
internal const val SONG_LOG = "fb_creation_feed_music_attribution_impression"

/** What the time's piece of the post subtitle throws with when the post has no time text (588 `LX/02oy;->A02`, `LX/02xj;->A00`). */
internal const val TIME_MISSING = "TimeInfoPlugin: timeInfo is null, call isNeeded() first"

internal const val HIDE_SONG = "$POST_DATES->hideSong()Z"
internal const val BOLD_TIME = "$POST_DATES->boldTime(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

/**
 * The song's piece of the post subtitle (#533): the method that reads which song a post plays out of
 * the subtitle's model, the one the piece's own text method calls with that model. The piece asks it
 * whether it's needed too, and with no song it isn't, so the line under the name closes up.
 */
internal fun BytecodePatchContext.findSongLookup(): Method {
    val texts = methodsWithString(SONG_LOG).filter { it.returnType == CHAR_SEQUENCE && it.parameterTypes.size == 1 }
    val text = texts.singleOrNull() ?: refuse("expected one subtitle text method logging \"$SONG_LOG\", found ${texts.size}")
    val owner = text.definingClass
    val lookups = text.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as MethodReference).takeIf {
            it.definingClass == owner && it.parameterTypes.size == 1 && it.returnType.startsWith("L") &&
                it.returnType != CHAR_SEQUENCE && it.returnType != "Ljava/lang/String;"
        }
    }.map { "${it.name}${it.parameterTypes}${it.returnType}" to it }.toMap().values
    val lookup = lookups.singleOrNull() ?: refuse("expected $owner->${text.name} to look its song up one way, found ${lookups.size}")
    val method = classDefByOrNull(owner)!!.methods.singleOrNull {
        it.name == lookup.name && it.parameterTypes == lookup.parameterTypes && it.returnType == lookup.returnType
    } ?: refuse("$owner has no ${lookup.name} to look a post's song up with")
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null) refuse("$owner->${method.name} isn't a static method with code")
    if (method.localRegisterCount() < 1) refuse("$owner->${method.name} has no register of its own to answer with")
    return method
}

/** Answers "no song" from the start of [lookup] while the switch is on; otherwise its code runs as it was. */
internal fun BytecodePatchContext.hideSongs(lookup: Method) {
    val mutable = mutableClassDefBy(lookup.definingClass).findMutableMethodOf(lookup)
    mutable.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_SONG
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("keep", mutable.getInstruction(0)),
    )
}

/**
 * The post's time text (#533): the subtitle model's text field that the time's piece reads and checks
 * right before it throws [TIME_MISSING]. Each method throwing it has to read one such field, the read
 * nearest before the throw's message followed by a branch on what it read, and all of them the same.
 */
internal fun BytecodePatchContext.findTimeText(): FieldReference {
    val holders = methodsWithString(TIME_MISSING)
    if (holders.isEmpty()) refuse("nothing throws \"$TIME_MISSING\"")
    val fields = holders.map { method ->
        val code = method.implementation!!.instructions.toList()
        val message = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == TIME_MISSING }
        val read = (message - 1 downTo 1).firstOrNull { index ->
            val read = code[index - 1]
            val branch = code[index]
            read.opcode == Opcode.IGET_OBJECT && ((read as ReferenceInstruction).reference as FieldReference).type == CHAR_SEQUENCE &&
                (branch.opcode == Opcode.IF_EQZ || branch.opcode == Opcode.IF_NEZ) &&
                (branch as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA
        } ?: refuse("${method.definingClass}->${method.name} doesn't check a time text before \"$TIME_MISSING\"")
        val field = (code[read - 1] as ReferenceInstruction).reference as FieldReference
        "${field.definingClass}->${field.name}:${field.type}" to field
    }.toMap()
    return fields.values.singleOrNull() ?: refuse("the methods throwing \"$TIME_MISSING\" read ${fields.size} time texts: ${fields.keys}")
}

/** Puts the time text through the extension right before each write of [time] in its class, at the write's own label. */
internal fun BytecodePatchContext.boldTimes(time: FieldReference) {
    val owner = classDefByOrNull(time.definingClass) ?: refuse("no ${time.definingClass} to hold the time text")
    val writes = owner.methods.flatMap { method ->
        method.implementation?.instructions?.withIndex()?.filter { (_, it) ->
            it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                field.definingClass == time.definingClass && field.name == time.name && field.type == time.type
            }
        }?.map { method to it }.orEmpty()
    }
    if (writes.isEmpty()) refuse("nothing in ${time.definingClass} writes ${time.name}")
    writes.groupBy({ it.first }, { it.second }).forEach { (method, at) ->
        val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        at.sortedByDescending { it.index }.forEach { (index, write) ->
            val register = (write as TwoRegisterInstruction).registerA
            mutable.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $BOLD_TIME
                    move-result-object v$register
                """,
            )
        }
    }
}

/** The song and the time under a post's name (#533), each its own switch's; one that can't be found leaves the rest. */
internal fun BytecodePatchContext.addSubtitlePieces(warn: (String) -> Unit) {
    val extension = classDefByOrNull(POST_DATES)?.methods?.map { it.name }?.toSet().orEmpty()
    try {
        if ("songsInBuild" !in extension) refuse("the extension has no $POST_DATES->songsInBuild()")
        hideSongs(findSongLookup())
        mutableClassDefBy(POST_DATES).methods.single { it.name == "songsInBuild" }.returnEarly(true)
    } catch (missing: PatchException) {
        warn("${missing.message}, so the song under a post's name can't be hidden in this build")
    }
    try {
        if ("timesInBuild" !in extension) refuse("the extension has no $POST_DATES->timesInBuild()")
        boldTimes(findTimeText())
        mutableClassDefBy(POST_DATES).methods.single { it.name == "timesInBuild" }.returnEarly(true)
    } catch (missing: PatchException) {
        warn("${missing.message}, so a post's time can't be bold in this build")
    }
}
