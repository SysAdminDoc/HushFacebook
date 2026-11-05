/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The song line under the poster's name on a story, and the suggested replies above the reply box
 * (#380). Read from 585, 2026-11-04, arm64; the obfuscated names here are for reviewers, the code
 * never writes one down.
 *
 * - Every Litho component of the story viewer names itself in its static initializer (a const-string
 *   handed to CallerContext), and each is a Kotlin component with one render method that takes the
 *   component scope and answers what to draw. A null answer draws nothing, and Facebook gives one
 *   itself: the reply suggestions' render returns null when they're turned off for a story.
 * - The header's subtitle chooser (arm64 LX/0CVu;->A00) answers the plain subtitle, or for a story
 *   with a song, effect or other credit one of two components. With one credit it builds
 *   StoryViewerInspirationAttributionLinkSubtitleComponent (LX/0CoV;), which shows the credit in
 *   place of the plain subtitle, so its render answers null and the line goes. With more it builds
 *   StoryViewerInspirationAttributionLinkSubtitleRotatingAnimationComponent (LX/0b2l;), which turns
 *   between the plain subtitle and the credits. Its constructor keeps the plain subtitle from its
 *   first argument in the one field of render's own type, so its render answers that field and the
 *   plain subtitle stays put. The chooser also builds it for a story Facebook marks trending or
 *   suggested, and render tests a flag for each right before it loads the "TRENDING" or "SUGGESTED"
 *   label. With either flag set the render is left alone, so the label isn't lost with the credits.
 * - The suggested replies are ContextualReplyArtifactComponent (LX/0CZx;), the one component that
 *   builds the chip row (ContextualReplyOnViewerComponent, LX/0Ca2;). Its render answers null.
 * - The chooser isn't hooked: its registers are reused for other types further on, so an early
 *   answer there could fail verification. Each component's render is hooked first thing instead.
 * - On most stories the song is a pill under the name instead (#380, que102's video, 11-05), drawn by
 *   StoryViewerMultipleAttributionLinksColumnComponent (LX/0cVh;). Its render walks the story's
 *   links, reads each one's type (an enum, LX/05KM;, whose names are MUSIC, EFFECT, SPOTIFY and so
 *   on) and switches on its ordinal to build that pill, the switch's fall-through being the step to
 *   the next link. The patch asks the extension about the type right before the ordinal and, on a
 *   yes, takes that step, so the pill is never built.
 * - The Subscribe pill under the name (LX/0b2T;) names nothing itself. Its click handler, a shared
 *   lambda class, casts it back from a field in the case that hands "SubscribeButtonComponent" to
 *   the subscribe call, so the pill is the nearest component cast before that string with the
 *   pill's three fields. Its render answers null itself for a card it's been told to skip.
 */

internal const val STORY_VIEWER_CLEANUP_PATCH = "Clean up the story viewer"

/** The names the components give themselves. */
internal const val CREDIT_LINE = "StoryViewerInspirationAttributionLinkSubtitleComponent"
internal const val ROTATING_CREDIT_LINE = "StoryViewerInspirationAttributionLinkSubtitleRotatingAnimationComponent"
internal const val REPLY_SUGGESTIONS = "ContextualReplyArtifactComponent"

/** The labels the turning credit line shows for a story Facebook marks, each behind a flag of its own. */
internal val STORY_LABELS = listOf("TRENDING", "SUGGESTED")

/** How far before a label's string render tests its flag: 6 and 7 instructions in 585. */
private const val LABEL_REACH = 12

internal const val STORY_VIEWER_CLEANUP = "$EXTENSION_PACKAGE/stories/StoryViewerCleanup;"
internal const val HIDE_CREDITS = "$STORY_VIEWER_CLEANUP->hideCredits()Z"
internal const val HIDE_REPLY_SUGGESTIONS = "$STORY_VIEWER_CLEANUP->hideReplySuggestions()Z"
internal const val DROP_CREDIT = "$STORY_VIEWER_CLEANUP->dropCredit(Ljava/lang/Enum;)Z"
internal const val HIDE_SUBSCRIBE = "$STORY_VIEWER_CLEANUP->hideSubscribe()Z"

/** The column of credit pills under the poster's name, by the name it gives itself. */
internal const val LINKS_COLUMN = "StoryViewerMultipleAttributionLinksColumnComponent"

/** Two of the pill types' names, which the type enum's static initializer must hold. */
internal val PILL_TYPE_NAMES = listOf("MUSIC", "EFFECT")

/** What the Subscribe pill's click handler hands the subscribe call. */
internal const val SUBSCRIBE_CLICK = "SubscribeButtonComponent"

/** The Subscribe pill's fields: the session, the card and whether it shows on this card only. */
internal val SUBSCRIBE_FIELDS = listOf("Lcom/facebook/auth/usersession/FbUserSession;", "Lcom/facebook/stories/model/StoryCard;", "Z")

/** How far before the string the click handler casts the pill: 48 instructions in 585. */
private const val SUBSCRIBE_REACH = 80

/**
 * Takes the song and effect credits off the line under the poster's name on a story, and the
 * suggested replies off the bottom, and can draw a progress bar along the bottom (#380).
 *
 * In the default selection with every switch off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val storyViewerCleanupPatch = bytecodePatch(
    name = "Clean up the story viewer",
    description = "Switches that take the song and effect credits and the Subscribe button off a story, and the " +
        "suggested replies off the bottom while the reply box stays, plus one that adds a thin progress bar along the " +
        "bottom like the one on Reels. All start off. Turn them on in Hushfacebook settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("storyViewerCleanup")
        cleanUpStoryViewer()
        dropCreditPills()
        hideSubscribePill()
        followStoryProgress()
        enableStatus("storyViewerCleanup")
    }
}

internal fun refuse(detail: String): Nothing = throw PatchException("$STORY_VIEWER_CLEANUP_PATCH: $detail")

/** [classDef]'s render: one instance method by that name, taking the scope and answering an object. */
internal fun renderOf(classDef: ClassDef): Method? = classDef.methods.singleOrNull {
    it.name == "render" && it.parameterTypes.size == 1 && it.returnType.startsWith("L") &&
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
}

/** Whether [classDef] is the component that names itself [name] in its static initializer and has a render. */
internal fun isNamedComponent(classDef: ClassDef, name: String): Boolean =
    classDef.methods.any { it.name == "<clinit>" && holdsString(it, name) } && renderOf(classDef) != null

/**
 * The field [component] keeps its plain subtitle in: the one instance field of render's own type,
 * which its constructor fills from its first argument. Null when that isn't so.
 */
internal fun plainSubtitleField(component: ClassDef): Field? {
    val render = renderOf(component) ?: return null
    val field = component.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == render.returnType }
        .singleOrNull() ?: return null
    val init = component.methods.singleOrNull { it.name == "<init>" } ?: return null
    if (init.parameterTypes.firstOrNull()?.toString() != render.returnType) return null
    val code = init.implementation ?: return null
    // this, then a register per argument and a second for each long or double.
    val words = 1 + init.parameterTypes.size + init.parameterTypes.count { it.toString() == "J" || it.toString() == "D" }
    val firstArgument = code.registerCount - words + 1
    val keeps = code.instructions.any {
        val reference = (it as? ReferenceInstruction)?.reference as? FieldReference
        it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == firstArgument &&
            reference != null && reference.definingClass == component.type && reference.name == field.name &&
            reference.type == field.type
    }
    return field.takeIf { keeps }
}

/**
 * The flags [component]'s render tests before it shows each of [STORY_LABELS], in that order: the
 * last boolean of its own it reads in the few instructions before loading the label. Null unless
 * every label has one and they're all different.
 */
internal fun labelFlags(component: ClassDef): List<FieldReference>? {
    val code = renderOf(component)?.implementation?.instructions?.toList() ?: return null
    val flags = STORY_LABELS.map { label ->
        val at = code.indexOfFirst {
            (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((it as ReferenceInstruction).reference as StringReference).string == label
        }
        if (at < 0) return null
        code.subList(maxOf(0, at - LABEL_REACH), at).lastOrNull {
            it.opcode == Opcode.IGET_BOOLEAN && ((it as ReferenceInstruction).reference as FieldReference).definingClass == component.type
        }?.let { (it as ReferenceInstruction).reference as FieldReference } ?: return null
    }
    return flags.takeIf { found -> found.map { it.name }.distinct().size == found.size }
}

/** The one component class naming itself [name], with its render. Refuses unless there's exactly one. */
private fun BytecodePatchContext.component(name: String): Pair<ClassDef, MutableMethod> {
    val found = classDefByStrings(name, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { isNamedComponent(it, name) }
    val component = found.singleOrNull() ?: refuse("expected one component naming itself $name with a render, found ${found.size}")
    val render = renderOf(component)!!
    return component to mutableClassDefBy(component.type).methods.single {
        it.name == render.name && it.returnType == render.returnType &&
            it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
    }
}

/**
 * Asks [hook] first thing in [render] and, when it says so, answers what [answer] loads into v0.
 * [answer] can branch to :facebooks_render to leave the render alone after all. At the first
 * instruction no local holds anything yet, so the first [locals] are free there.
 */
private fun MutableMethod.answerWhen(hook: String, answer: String, locals: Int = 1) {
    requireLocals(STORY_VIEWER_CLEANUP_PATCH, locals)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_render
            $answer
            return-object v0
        """,
        ExternalLabel("facebooks_render", getInstruction(0)),
    )
}

/** Hooks the three components' renders. */
internal fun BytecodePatchContext.cleanUpStoryViewer() {
    val (_, creditLine) = component(CREDIT_LINE)
    creditLine.answerWhen(HIDE_CREDITS, "const/4 v0, 0x0")

    val (rotating, rotatingRender) = component(ROTATING_CREDIT_LINE)
    val plain = plainSubtitleField(rotating)
        ?: refuse("$ROTATING_CREDIT_LINE keeps no plain subtitle from its first argument in a field of render's type")
    val flags = labelFlags(rotating)
        ?: refuse("$ROTATING_CREDIT_LINE tests no flag of its own before each of its labels $STORY_LABELS")
    // p0 sits past v15 in a render this size, where iget can't reach, so it's moved down first.
    val unmarked = flags.joinToString("\n") {
        """
            iget-boolean v1, v0, ${it.definingClass}->${it.name}:${it.type}
            if-nez v1, :facebooks_render
        """
    }
    rotatingRender.answerWhen(
        HIDE_CREDITS,
        """
            move-object/from16 v0, p0
            $unmarked
            iget-object v0, v0, ${rotating.type}->${plain.name}:${plain.type}
        """,
        locals = 2,
    )

    val (_, suggestions) = component(REPLY_SUGGESTIONS)
    suggestions.answerWhen(HIDE_REPLY_SUGGESTIONS, "const/4 v0, 0x0")
}

/**
 * Where the links column's render sorts a pill by its type: the call at [read] answers the type
 * into [type], a null check follows, then the ordinal at [ordinal] goes into [scratch] for the
 * switch, whose fall-through at [next] is the step to the next link. [typeClass] is the enum.
 */
internal data class PillSwitch(val read: Int, val type: Int, val ordinal: Int, val scratch: Int, val next: Int, val typeClass: String)

private val INVOKES = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL_RANGE)

/** The one register an invoke of one register names, or null. */
private fun Instruction.soleRegister(): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister.takeIf { registerCount == 1 }
    is FiveRegisterInstruction -> registerC.takeIf { registerCount == 1 }
    else -> null
}

/**
 * The one place in [render] where a no-argument call answers an object, a null check on it
 * follows, and its `Enum.ordinal()` feeds a switch whose fall-through soon jumps back up, the
 * loop's step to its next item. Null unless there's exactly one.
 */
internal fun pillSwitch(render: Method): PillSwitch? {
    val code = render.implementation?.instructions?.toList() ?: return null
    val at = code.runningFold(0) { offset, it -> offset + it.codeUnits }
    val found = (3 until code.size - 3).mapNotNull { i ->
        val ordinal = code[i]
        val ordinalRef = (ordinal as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (ordinal.opcode != Opcode.INVOKE_VIRTUAL && ordinal.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
        if (ordinalRef.definingClass != "Ljava/lang/Enum;" || ordinalRef.name != "ordinal") return@mapNotNull null
        val type = ordinal.soleRegister() ?: return@mapNotNull null
        val (read, result, check) = Triple(code[i - 3], code[i - 2], code[i - 1])
        val readRef = (read as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        val (moved, switch) = code[i + 1] to code[i + 2]
        val scratch = (moved as? OneRegisterInstruction)?.registerA ?: return@mapNotNull null
        val shaped = read.opcode in INVOKES && readRef.parameterTypes.isEmpty() && readRef.returnType.startsWith("L") &&
            result.opcode == Opcode.MOVE_RESULT_OBJECT && (result as OneRegisterInstruction).registerA == type &&
            check.opcode == Opcode.IF_EQZ && (check as OneRegisterInstruction).registerA == type &&
            moved.opcode == Opcode.MOVE_RESULT && scratch <= 15 &&
            (switch.opcode == Opcode.SPARSE_SWITCH || switch.opcode == Opcode.PACKED_SWITCH) &&
            (switch as OneRegisterInstruction).registerA == scratch
        // The step: a jump back above the read, within the few instructions after the switch.
        val steps = (i + 3 until minOf(code.size, i + 7)).any { j ->
            val jump = code[j]
            (jump.opcode == Opcode.GOTO || jump.opcode == Opcode.GOTO_16 || jump.opcode == Opcode.GOTO_32) &&
                at[j] + (jump as OffsetInstruction).codeOffset < at[i - 3]
        }
        if (shaped && steps) PillSwitch(i - 3, type, i, scratch, i + 3, readRef.returnType) else null
    }
    return found.singleOrNull()
}

/**
 * Leaves the song and effect pills out of the column under the poster's name: right before the
 * ordinal, the extension hears the pill's type, and on a yes the loop takes its step to the next
 * link. The switch register is free there, since the ordinal is about to fill it.
 */
internal fun BytecodePatchContext.dropCreditPills() {
    val (_, render) = component(LINKS_COLUMN)
    val site = pillSwitch(render) ?: refuse("$LINKS_COLUMN doesn't switch on one pill type's ordinal inside its loop")
    val enum = classDefByOrNull(site.typeClass)?.takeIf { it.superclass == "Ljava/lang/Enum;" }
        ?: refuse("$LINKS_COLUMN's pill type ${site.typeClass} isn't an enum")
    val names = enum.methods.singleOrNull { it.name == "<clinit>" }
    if (names == null || PILL_TYPE_NAMES.any { !holdsString(names, it) }) {
        refuse("$LINKS_COLUMN's pill type ${site.typeClass} doesn't name $PILL_TYPE_NAMES")
    }
    render.addInstructionsWithLabels(
        site.ordinal,
        """
            invoke-static/range { v${site.type} .. v${site.type} }, $DROP_CREDIT
            move-result v${site.scratch}
            if-nez v${site.scratch}, :next_pill
        """,
        ExternalLabel("next_pill", render.getInstruction(site.next)),
    )
}

/** Whether [classDef] is a component with the Subscribe pill's three instance fields and a render. */
internal fun isSubscribePill(classDef: ClassDef): Boolean = renderOf(classDef) != null &&
    classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.sorted() == SUBSCRIBE_FIELDS.sorted()

/**
 * The types the Subscribe pill's click handler in [holder] casts its component to: for each
 * [SUBSCRIBE_CLICK] string, the nearest check-cast before it to a class [lookup] finds to be the pill.
 */
internal fun subscribePills(holder: ClassDef, lookup: (String) -> ClassDef?): Set<String> =
    holder.methods.filter { holdsString(it, SUBSCRIBE_CLICK) }.flatMap { method ->
        val code = method.implementation!!.instructions.toList()
        code.indices.filter {
            (code[it].opcode == Opcode.CONST_STRING || code[it].opcode == Opcode.CONST_STRING_JUMBO) &&
                ((code[it] as ReferenceInstruction).reference as StringReference).string == SUBSCRIBE_CLICK
        }.mapNotNull { at ->
            (at - 1 downTo maxOf(0, at - SUBSCRIBE_REACH)).asSequence()
                .filter { code[it].opcode == Opcode.CHECK_CAST }
                .map { ((code[it] as ReferenceInstruction).reference as TypeReference).type }
                .firstOrNull { type -> lookup(type)?.let(::isSubscribePill) == true }
        }
    }.toSet()

/** Hooks the Subscribe pill's render to answer null, as it does itself for a card it skips. */
internal fun BytecodePatchContext.hideSubscribePill() {
    val pills = classDefByStrings(SUBSCRIBE_CLICK, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { subscribePills(it) { type -> classDefByOrNull(type) } }.toSet()
    val pill = pills.singleOrNull() ?: refuse("expected one Subscribe pill cast before $SUBSCRIBE_CLICK, found ${pills.size}")
    val render = renderOf(classDefByOrNull(pill)!!)!!
    mutableClassDefBy(pill).methods.single {
        it.name == render.name && it.returnType == render.returnType &&
            it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
    }.answerWhen(HIDE_SUBSCRIBE, "const/4 v0, 0x0")
}
