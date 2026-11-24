/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * How long ago a story was posted, next to the poster's name (#563). Read from 588, 2026-11-24,
 * arm64; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - Every story header builds the line next to the name in one builder (LX/0BEg;->A0B): the header
 *   of people's and Pages' stories (LX/0BEC;), the one for public stories (LX/0CXx;) and the ads'
 *   top bar (LX/0azh;).
 * - Twice that builder asks a check of its own (LX/0BEg;->A0D(StoryBucket, StoryCard)Z) whether to
 *   leave the time out, and only when it answers no does it put in the relative time ("3h", from
 *   LX/0BEs;->A04). The check says yes for the buckets numbered 15, 33, 34 and 35, for 38 unless the
 *   card is of two kinds, and for a few story kinds, which is how a Page's story shows no time.
 * - The check is the one instance method taking a StoryBucket and a StoryCard and answering a boolean
 *   that compares StoryBucket's getBucketType() (a kept name) with 34 and 35. Each answer it returns
 *   goes through the extension, which turns a yes into a no with the switch on.
 * - That alone wasn't enough on an S22 (National Geographic's photo story, bucket type 24): with the
 *   check answering no, the builder ended with "10h" in its line field, and the header still showed
 *   the name alone. The header's title row (LX/0BED;->A00, static, handed the builder) returns before
 *   it reads the builder's line when none of the builder's special flags is set (LX/0BEg;->A0C) and
 *   a chain of card checks passes, which is the Page story's case. That `return-object` sits right
 *   before the row's reads of the builder's text fields, and only the row's other paths go on to
 *   read the line from there. Right before the return the row asks the extension about the line,
 *   and goes on to read it as a friend's story does when the switch is on and the line isn't empty.
 *   Nothing past the reads takes a register the return's other paths leave unset (read from 588).
 */

internal const val HIDE_STORY_TIME = "$STORY_VIEWER_CLEANUP->hideTime(Z)Z"
internal const val SHOWS_TIME_LINE = "$STORY_VIEWER_CLEANUP->showsTimeLine(Ljava/lang/CharSequence;)Z"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val TIME_BUCKET = "Lcom/facebook/stories/model/StoryBucket;"
private const val TIME_CARD = "Lcom/facebook/stories/model/StoryCard;"
private const val BUCKET_TYPE = "getBucketType"
/** Two of the bucket numbers the check leaves the time out for. */
private val TIMELESS_BUCKETS = setOf(0x22, 0x23)

/** Whether [method] is the check the story line builder asks before it puts in the time. */
internal fun isStoryTimeCheck(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z") return false
    if (method.parameterTypes.map(CharSequence::toString) != listOf(TIME_BUCKET, TIME_CARD)) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val literals = code.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.toSet()
    val bucketTypes = code.count {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        call?.definingClass == TIME_BUCKET && call.name == BUCKET_TYPE && call.returnType == "I"
    }
    return literals.containsAll(TIMELESS_BUCKETS) && bucketTypes >= TIMELESS_BUCKETS.size
}

/** The check's answers, each with the register it returns. */
internal fun timeAnswers(check: Method): List<Pair<Int, Int>> =
    check.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }
        .map { (index, answer) -> index to (answer as OneRegisterInstruction).registerA }

/** Every answer of the time check goes through the extension, which may turn a yes into a no. */
internal fun BytecodePatchContext.showStoryTime() {
    val checks = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(TIME_BUCKET)) { classDef ->
        classDef.methods.filterTo(checks, ::isStoryTimeCheck)
    }
    val found = checks.distinctBy { "${it.definingClass}->${it.name}" }
    val check = found.singleOrNull()
        ?: refuse("expected one check of a story bucket's type that leaves the time out, found ${found.size}")
    val answers = timeAnswers(check)
    if (answers.isEmpty()) refuse("${check.definingClass}->${check.name} never answers")
    answers.firstOrNull { it.second > 15 }?.let { refuse("${check.definingClass}->${check.name} answers from v${it.second}") }
    val method = mutableClassDefBy(check.definingClass).methods.single {
        it.name == check.name && it.returnType == check.returnType &&
            it.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
    }
    for ((index, answer) in answers.sortedByDescending { it.first }) {
        method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$answer }, $HIDE_STORY_TIME
                move-result v$answer
            """,
        )
    }
    showTimeLine(check.definingClass)
}

/**
 * A place in [method] where the story header row gives up on the line before it reads it: a
 * `return-object` right before a run of `iget-object` reads, off one register, of [builderType]'s
 * fields, one of them a CharSequence (the line). Each as (the return's index, the builder's register,
 * the CharSequence field).
 */
internal fun timeLineReturns(method: Method, builderType: String): List<Triple<Int, Int, FieldReference>> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val found = mutableListOf<Triple<Int, Int, FieldReference>>()
    for (at in code.indices) {
        if (code[at].opcode != Opcode.RETURN_OBJECT) continue
        var builder: Int? = null
        var line: FieldReference? = null
        var next = at + 1
        while (next < code.size && code[next].opcode == Opcode.IGET_OBJECT) {
            val read = code[next] as TwoRegisterInstruction
            val field = (code[next] as ReferenceInstruction).reference as FieldReference
            if (field.definingClass != builderType || (builder != null && read.registerB != builder)) break
            builder = read.registerB
            if (field.type == CHAR_SEQUENCE && line == null) line = field
            next++
        }
        if (builder != null && line != null) found += Triple(at, builder, line)
    }
    return found
}

/**
 * The header row's line (#563, see the notes above). With the check answering no the builder puts the
 * time in its line, but for a Page's story the row returns before it reads that line. Right before that
 * return the row asks the extension about the line, and goes on to read it, as it does for a friend's
 * story, when the extension says so. Refuses unless one static method taking the builder has one such place.
 */
internal fun BytecodePatchContext.showTimeLine(builderType: String) {
    val sites = mutableListOf<Pair<Method, Triple<Int, Int, FieldReference>>>()
    classDefForEachReferencing(types = listOf(builderType)) { classDef ->
        if (classDef.type == builderType) return@classDefForEachReferencing
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
                method.parameterTypes.any { it.toString() == builderType }
        }.forEach { method -> timeLineReturns(method, builderType).mapTo(sites) { method to it } }
    }
    val found = sites.distinctBy { (method, site) -> "${method.definingClass}->${method.name}${method.parameterTypes}@${site.first}" }
    val (row, site) = found.singleOrNull()
        ?: refuse("expected one header row returning before it reads the story line, found ${found.size}")
    val (at, builder, line) = site
    val method = mutableClassDefBy(row.definingClass).methods.single {
        it.name == row.name && it.returnType == row.returnType &&
            it.parameterTypes.map(CharSequence::toString) == row.parameterTypes.map(CharSequence::toString)
    }
    val answer = (method.getInstruction(at) as OneRegisterInstruction).registerA
    val temp = try {
        method.findFreeRegister(at + 1, builder, answer)
    } catch (failure: IllegalArgumentException) {
        refuse("${row.definingClass}->${row.name} has no free register where it reads the line")
    }
    if (temp > 15 || builder > 15) refuse("${row.definingClass}->${row.name} reads the line from v$builder, past v15")
    method.addInstructionsAtControlFlowLabel(
        at,
        """
            iget-object v$temp, v$builder, $line
            invoke-static { v$temp }, $SHOWS_TIME_LINE
            move-result v$temp
            if-eqz v$temp, :facebooks_return
            goto :reads_line
            :facebooks_return
            nop
        """,
        ExternalLabel("reads_line", method.getInstruction(at + 1)),
    )
}
