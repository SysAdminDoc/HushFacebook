/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

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
internal const val SHOWS_TIME_BESIDE_LABEL = "$STORY_VIEWER_CLEANUP->showsTimeBesideLabel()Z"
internal const val WITH_STORY_TIME = "$STORY_VIEWER_CLEANUP->withTime(Ljava/lang/CharSequence;Ljava/lang/String;)Ljava/lang/CharSequence;"
/** The method the patch gives the story line builder, formatting a card's time as its time path does. */
internal const val STORY_TIME_HELPER = "hushStoryTime"
internal const val SHOWS_TIME_IN_SUBTITLE = "$STORY_VIEWER_CLEANUP->showsTimeInSubtitle()Z"
internal const val SUBTITLE_WITH_TIME = "$STORY_VIEWER_CLEANUP->subtitleWithTime(Ljava/lang/CharSequence;Ljava/lang/String;)Ljava/lang/CharSequence;"
/** The name Facebook logs the subtitle beside a story's name under, which the patch finds it by. */
internal const val STORY_SUBTITLE = "StoryViewerSubtitleComponent"
/** The methods the patch gives the subtitle: one formatting a card's time, one putting it in. */
internal const val SUBTITLE_TIME_HELPER = "hushSubtitleTime"
internal const val SUBTITLE_TIME_ADDER = "hushAddTime"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val SPANNABLE_BUILDER = "Landroid/text/SpannableStringBuilder;"
private const val TIMESTAMP = "getTimestamp"
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
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
    showTimeBesideLabel(check)
    showTimeInSubtitle(check)
}

/**
 * What the story line builder's own time path reads to put in the time: the lazy formatter field, its
 * getter, the formatter's call taking a session and the posting time, and the session's getter.
 */
internal data class StoryTimeSource(
    val formatterField: FieldReference,
    val lazyGetter: MethodReference,
    val format: MethodReference,
    val sessionGetter: MethodReference,
)

private fun Instruction.methodCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * Every place in [line] that puts in the time the way the builder's own path does: the lazy formatter
 * read off [builderType]'s field and cast, a session from a one-argument static getter, the card's
 * getTimestamp() and the formatter's (session, time) call answering a String.
 */
internal fun storyTimeSources(line: Method, builderType: String): List<StoryTimeSource> {
    val code = line.implementation?.instructions?.toList() ?: return emptyList()
    val found = mutableListOf<StoryTimeSource>()
    for (at in code.indices) {
        val format = code[at].methodCall() ?: continue
        if (code[at].opcode != Opcode.INVOKE_VIRTUAL || format.returnType != STRING ||
            format.parameterTypes.map(CharSequence::toString) != listOf(FB_USER_SESSION, "J")
        ) continue
        val stamp = code.getOrNull(at - 2)?.methodCall()
        if (stamp?.definingClass != TIME_CARD || stamp.name != TIMESTAMP || code[at - 1].opcode != Opcode.MOVE_RESULT_WIDE) continue
        val session = code.getOrNull(at - 4)?.takeIf { it.opcode in STATIC_CALLS }?.methodCall() ?: continue
        if (session.returnType != FB_USER_SESSION || session.parameterTypes.size != 1) continue
        val cast = at - 5
        if (code.getOrNull(cast)?.opcode != Opcode.CHECK_CAST ||
            (code[cast] as ReferenceInstruction).reference.toString() != format.definingClass
        ) continue
        val lazy = code.getOrNull(cast - 2)?.takeIf { it.opcode in STATIC_CALLS }?.methodCall() ?: continue
        val field = code.getOrNull(cast - 3)?.takeIf { it.opcode == Opcode.IGET_OBJECT }
            ?.let { (it as ReferenceInstruction).reference as FieldReference } ?: continue
        if (field.definingClass != builderType || lazy.returnType != OBJECT ||
            lazy.parameterTypes.map(CharSequence::toString) != listOf(field.type)
        ) continue
        found += StoryTimeSource(field, lazy, format, session)
    }
    return found
}

/**
 * The places in [line] that build a story's attachment label (an icon and a line of text, from
 * [label], one of the builder's own methods taking a card) and keep the result: each as (the call's
 * index, the builder's register, the card's register, the label's register).
 */
internal fun labelSites(line: Method, label: Method): List<List<Int>> {
    val code = line.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val call = code[at].methodCall()
        code[at].opcode == Opcode.INVOKE_DIRECT && call != null && call.definingClass == label.definingClass &&
            call.name == label.name && call.parameterTypes.map(CharSequence::toString) == label.parameterTypes.map(CharSequence::toString) &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }.map { at ->
        val call = code[at] as FiveRegisterInstruction
        listOf(at, call.registerC, call.registerE, (code[at + 1] as OneRegisterInstruction).registerA)
    }
}

/** Whether [method] is one of the story line builder's own methods building a label from a card. */
internal fun isStoryLabel(method: Method, builderType: String): Boolean =
    method.definingClass == builderType && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.returnType == SPANNABLE_BUILDER && method.parameterTypes.size == 2 &&
        method.parameterTypes[1].toString() == TIME_CARD

/**
 * A story with an attachment label (#563, "Sometimes working" on 0.25.0): for the buckets it doesn't
 * number on its own, the builder makes the line the label alone when the card has an attachment, and
 * returns before its time path (LX/0BEg;->A01 in A0B, read from 588). The patch gives the builder a
 * method of its own that formats the card's time the way its time path does, and right after the
 * label is built, with the switch on, the line becomes the label and the time with Facebook's dot.
 */
/** The story line builder: the one method of [check]'s class asking [check]. */
private fun ClassDef.storyLine(check: Method): Method {
    val askers = methods.filter { method ->
        method.implementation?.instructions?.any {
            val call = it.methodCall()
            call != null && call.definingClass == type && call.name == check.name &&
                call.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
        } == true
    }
    return askers.singleOrNull() ?: refuse("expected one story line builder asking the time check, found ${askers.size}")
}

/** How the story line builder [line] puts in the time, refusing unless it does so one way. */
private fun storyTimeSource(line: Method, builderType: String): StoryTimeSource {
    val sources = storyTimeSources(line, builderType)
    if (sources.isEmpty()) refuse("the story line builder never puts in the time the way it used to")
    if (sources.distinctBy { "${it.formatterField} ${it.lazyGetter} ${it.format}" }.size != 1) {
        refuse("the story line builder formats the time ${sources.distinctBy { it.format.toString() }.size} ways")
    }
    return sources.groupBy { it.sessionGetter.toString() }.maxBy { it.value.size }.value.first()
}

internal fun BytecodePatchContext.showTimeBesideLabel(check: Method) {
    val builderType = check.definingClass
    val builder = mutableClassDefBy(builderType)
    val line = builder.storyLine(check)
    val labels = builder.methods.filter { isStoryLabel(it, builderType) && labelSites(line, it).isNotEmpty() }
    val label = labels.singleOrNull() ?: refuse("expected one attachment label the line builder keeps, found ${labels.size}")
    val sites = labelSites(line, label)
    val (at, builderRegister, cardRegister, labelRegister) = sites.singleOrNull()
        ?: refuse("expected the line builder to build the attachment label once, found ${sites.size}")
    val source = storyTimeSource(line, builderType)

    ImmutableMethod(
        builderType,
        STORY_TIME_HELPER,
        listOf(ImmutableMethodParameter(builderType, null, null), ImmutableMethodParameter(TIME_CARD, null, null)),
        STRING,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(6),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                iget-object v0, p0, ${source.formatterField}
                invoke-static { v0 }, ${source.lazyGetter}
                move-result-object v0
                check-cast v0, ${source.format.definingClass}
                iget-object v1, p0, ${sessionField(builder, source)}
                invoke-static { v1 }, ${source.sessionGetter}
                move-result-object v1
                invoke-virtual { p1 }, $TIME_CARD->$TIMESTAMP()J
                move-result-wide v2
                invoke-virtual { v0, v1, v2, v3 }, ${source.format}
                move-result-object v0
                return-object v0
            """,
        )
        builder.methods.add(this)
    }

    val method = builder.methods.single {
        it.name == line.name && it.returnType == line.returnType &&
            it.parameterTypes.map(CharSequence::toString) == line.parameterTypes.map(CharSequence::toString)
    }
    val temp = try {
        method.findFreeRegister(at + 2, builderRegister, cardRegister, labelRegister)
    } catch (failure: IllegalArgumentException) {
        refuse("${line.definingClass}->${line.name} has no free register after the attachment label")
    }
    if (listOf(temp, builderRegister, cardRegister, labelRegister).any { it > 15 }) {
        refuse("${line.definingClass}->${line.name} keeps the attachment label past v15")
    }
    method.addInstructionsWithLabels(
        at + 2,
        """
            invoke-static { }, $SHOWS_TIME_BESIDE_LABEL
            move-result v$temp
            if-eqz v$temp, :facebooks_line
            invoke-static { v$builderRegister, v$cardRegister }, $builderType->$STORY_TIME_HELPER($builderType$TIME_CARD)$STRING
            move-result-object v$temp
            invoke-static { v$labelRegister, v$temp }, $WITH_STORY_TIME
            move-result-object v$labelRegister
        """,
        ExternalLabel("facebooks_line", method.getInstruction(at + 2)),
    )
}

/** The builder's one field of the type [source]'s session getter takes. */
private fun sessionField(builder: ClassDef, source: StoryTimeSource): String {
    val type = source.sessionGetter.parameterTypes.single().toString()
    val fields = builder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
    val field = fields.singleOrNull() ?: refuse("expected the story line builder to keep one $type, found ${fields.size}")
    return "${field.definingClass}->${field.name}:${field.type}"
}

/**
 * Where the lazy formatter [field] holds comes from: the static call taking a number that [builder]'s
 * constructor keeps in it, right after loading that number. As (the call, the number).
 */
internal fun formatterLazy(builder: ClassDef, field: FieldReference): Pair<MethodReference, Int>? {
    for (init in builder.methods.filter { it.name == "<init>" }) {
        val code = init.implementation?.instructions?.toList() ?: continue
        for (at in 3 until code.size) {
            val kept = (code[at] as? ReferenceInstruction)?.reference as? FieldReference ?: continue
            if (code[at].opcode != Opcode.IPUT_OBJECT || kept.definingClass != field.definingClass ||
                kept.name != field.name || kept.type != field.type
            ) continue
            val value = (code[at] as TwoRegisterInstruction).registerA
            if (code[at - 1].opcode != Opcode.MOVE_RESULT_OBJECT || (code[at - 1] as OneRegisterInstruction).registerA != value) continue
            val call = code[at - 2].takeIf { it.opcode == Opcode.INVOKE_STATIC }?.methodCall() ?: continue
            if (call.parameterTypes.map(CharSequence::toString) != listOf("I") || call.returnType != field.type) continue
            val number = (code[at - 2] as FiveRegisterInstruction).registerC
            val load = code[at - 3] as? NarrowLiteralInstruction ?: continue
            if ((code[at - 3] as OneRegisterInstruction).registerA != number) continue
            return call to load.narrowLiteral
        }
    }
    return null
}

/**
 * What the subtitle component's constructor keeps of what it's handed: the session, the card, the
 * first text, what a screen reader says for the text shown last, and the second text, each a field.
 */
internal data class SubtitleParts(
    val session: FieldReference,
    val card: FieldReference,
    val text: FieldReference,
    val description: FieldReference,
    val secondText: FieldReference,
)

/**
 * The fields [init] keeps its session, card and first three CharSequences in, each straight from its
 * own parameter, or null unless it takes one session, one card and at least three CharSequences.
 */
internal fun subtitleParts(init: Method): SubtitleParts? {
    val types = init.parameterTypes.map(CharSequence::toString)
    if (types.count { it == FB_USER_SESSION } != 1 || types.count { it == TIME_CARD } != 1) return null
    val texts = types.indices.filter { types[it] == CHAR_SEQUENCE }
    if (texts.size < 3) return null
    val code = init.implementation?.instructions?.toList() ?: return null
    val self = init.localRegisterCount()
    fun kept(index: Int): FieldReference? {
        val register = init.parameterRegisterNumber(index)
        val puts = code.filter {
            it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == register && it.registerB == self
        }.map { (it as ReferenceInstruction).reference as FieldReference }
        return puts.singleOrNull()?.takeIf { it.definingClass == init.definingClass }
    }
    return SubtitleParts(
        kept(types.indexOf(FB_USER_SESSION)) ?: return null,
        kept(types.indexOf(TIME_CARD)) ?: return null,
        kept(texts[0])?.takeIf { it.type == CHAR_SEQUENCE } ?: return null,
        kept(texts[1])?.takeIf { it.type == CHAR_SEQUENCE } ?: return null,
        kept(texts[2])?.takeIf { it.type == CHAR_SEQUENCE } ?: return null,
    )
}

/**
 * A story whose header shows a subtitle beside the name in place of the line (#563, "Sometimes
 * working" on 0.25.0): a shared post's original poster ("NDTV" on NDTV's story), "View full reel", a
 * link. On an S22 the header row returned Facebook's attribution subtitle for those, which never puts
 * in the time, and the line with the time went unread. Every such subtitle is one component, named
 * StoryViewerSubtitleComponent for Facebook's logging (LX/0DqT; in 588, built by the attribution
 * subtitle LX/0DsG;, its rotating twin LX/0W8V; and one more). Its constructor keeps the card, the
 * session and up to two texts, the second shown after the first, plus what a screen reader says for
 * each, the one for the text shown last being the second CharSequence it's handed (read from 588).
 *
 * The patch gives the component a method of its own formatting the card's time with the formatter the
 * line builder's constructor gets from Facebook's injector (the same lazy LX/0DsG; makes as it
 * renders), and one that, with the switch on, puts the time after the text shown last and its screen
 * reader text. The constructor calls that last, before it returns.
 */
internal fun BytecodePatchContext.showTimeInSubtitle(check: Method) {
    val builderType = check.definingClass
    val builder = classDefBy(builderType)
    val source = storyTimeSource(builder.storyLine(check), builderType)
    val (factory, number) = formatterLazy(builder, source.formatterField)
        ?: refuse("the story line builder's constructor never makes its time formatter from a number")

    val holders = classDefByStrings(STORY_SUBTITLE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val holder = holders.singleOrNull() ?: refuse("expected one story subtitle component, found ${holders.size}")
    val subtitle = mutableClassDefBy(holder.type)
    val inits = subtitle.methods.filter { it.name == "<init>" && subtitleParts(it) != null }
    val init = inits.singleOrNull() ?: refuse("expected one story subtitle constructor keeping a card and its texts, found ${inits.size}")
    val parts = subtitleParts(init)!!
    val ends = init.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    val end = ends.singleOrNull() ?: refuse("expected the story subtitle constructor to return once, found ${ends.size}")

    ImmutableMethod(
        subtitle.type,
        SUBTITLE_TIME_HELPER,
        listOf(ImmutableMethodParameter(FB_USER_SESSION, null, null), ImmutableMethodParameter(TIME_CARD, null, null)),
        STRING,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(5),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                const v0, $number
                invoke-static { v0 }, $factory
                move-result-object v0
                invoke-static { v0 }, ${source.lazyGetter}
                move-result-object v0
                check-cast v0, ${source.format.definingClass}
                invoke-virtual { p1 }, $TIME_CARD->$TIMESTAMP()J
                move-result-wide v1
                invoke-virtual { v0, p0, v1, v2 }, ${source.format}
                move-result-object v0
                return-object v0
            """,
        )
        subtitle.methods.add(this)
    }

    ImmutableMethod(
        subtitle.type,
        SUBTITLE_TIME_ADDER,
        emptyList(),
        "V",
        AccessFlags.PRIVATE.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $SHOWS_TIME_IN_SUBTITLE
                move-result v0
                if-eqz v0, :facebooks_subtitle
                iget-object v0, p0, ${parts.session}
                iget-object v1, p0, ${parts.card}
                invoke-static { v0, v1 }, ${subtitle.type}->$SUBTITLE_TIME_HELPER($FB_USER_SESSION$TIME_CARD)$STRING
                move-result-object v0
                iget-object v1, p0, ${parts.secondText}
                if-eqz v1, :first_text
                invoke-static { v1, v0 }, $SUBTITLE_WITH_TIME
                move-result-object v1
                iput-object v1, p0, ${parts.secondText}
                goto :description
                :first_text
                iget-object v1, p0, ${parts.text}
                invoke-static { v1, v0 }, $SUBTITLE_WITH_TIME
                move-result-object v1
                iput-object v1, p0, ${parts.text}
                :description
                iget-object v1, p0, ${parts.description}
                invoke-static { v1, v0 }, $SUBTITLE_WITH_TIME
                move-result-object v1
                iput-object v1, p0, ${parts.description}
                :facebooks_subtitle
                return-void
            """,
        )
        subtitle.methods.add(this)
    }

    val method = subtitle.methods.single {
        it.name == init.name && it.parameterTypes.map(CharSequence::toString) == init.parameterTypes.map(CharSequence::toString)
    }
    val self = method.localRegisterCount()
    if (self > 15) refuse("${init.definingClass}-><init> keeps itself past v15")
    method.addInstructionsAtControlFlowLabel(end, "invoke-direct { v$self }, ${subtitle.type}->$SUBTITLE_TIME_ADDER()V")
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
