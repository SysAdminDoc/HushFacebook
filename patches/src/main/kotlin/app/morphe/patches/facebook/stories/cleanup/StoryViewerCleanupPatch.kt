/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.cleanup

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.misc.upsells.definesImmutableCopy
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
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
 * - On most stories the song is a pill under the name instead, with a "Try it" button on it (#380,
 *   que102's video, 11-05). 587 draws that row in StoryViewerAugmentChipGroupComponent (arm64
 *   LX/0HEi;). Its render first keeps the story's links of the types it supports, then walks them,
 *   reads each one's type (an enum, 587 LX/05Ad;, whose names are MUSIC, EFFECT, SPOTIFY and so on)
 *   and switches on its ordinal to build that chip, the switch's fall-through being the step to the
 *   next link. The patch asks the extension about the type right before the ordinal and, on a yes,
 *   takes that step, so the chip is never built. A tap on the pill opens a sheet listing the same
 *   links, StoryViewerMultipleAttributionLinksColumnComponent (585 LX/0cVh;, 587 LX/0V7E;), walked
 *   and hooked the same way. Until 2026-11-13 only that sheet was hooked, so the pill stayed. 586
 *   (LX/0dVe;) tests a boolean field of the column between the type's null check and the ordinal,
 *   and skips to the step when it's off.
 * - That row isn't what a story whose first credit is a song gets, though. The factory for the slot
 *   under the name (587 arm64 LX/0Bni;->A00) builds the chip row only when Facebook says so, and for
 *   a song first builds a pill of its own (LX/0CDf;, v7 LX/0CD7;): "artist · song" with the "Try it"
 *   button, whose spoken label "Playing <song> by <artist>" the subtitle builder (LX/0BlI;) writes.
 *   It names nothing itself. Its render is the only one in the app that keys its button's transition
 *   "pill_cta_button_transition_key", and it answers null itself for a song with no title, so the
 *   patch answers null first thing on a yes. Found on que102's "demo" highlight, 2026-11-13, where
 *   the pill stayed with the row and the sheet both hooked and neither one ever ran.
 * - The Subscribe pill under the name (LX/0b2T;) names nothing itself. Its click handler, a shared
 *   lambda class, casts it back from a field in the case that hands "SubscribeButtonComponent" to
 *   the subscribe call, so the pill is the nearest component cast before that string with the
 *   pill's three fields. Its render answers null itself for a card it's been told to skip.
 * - The "Add to story" card between other people's stories (#439, 586 arm64 LX/0CLR;) is one of the
 *   viewer's story list sources, each taking the session, flags and the list so far and answering
 *   the list to show. This one splices in a card it tags "ats_tile", or "camera_roll" for the one
 *   built from the gallery, and hands the list back unchanged when it has nothing to add. The patch
 *   gives that same answer first thing on a yes.
 * - The full screen "<Name>, continue the story" card with its Create story button (#521, 588 arm64
 *   LX/0BDx;, AddToStoryOverlayComponent drawing it from six or more recent gallery photos) is a
 *   bucket of its own, a StoryBucket whose getId falls back to "story-end-card-bucket". It's built
 *   from the server's buckets (LX/08I5;->A03), which the tray load and the sources both use, so no
 *   one source owns it. Every story list source (LX/0DFi;, v7 LX/0DFh;) runs only from the viewer's
 *   static list runner (LX/0B7A;->A02, v7 LX/0B7D;->A02), the one method opening the trace section
 *   "StoryviewerBucketDataController.processBucketData". It hands its list (p2) to each source in
 *   turn and keeps the answer in the same register, then comes out of the loop. Right there the list
 *   goes through the extension, which drops each bucket of the card's class, and back into an
 *   ImmutableList. Its own Kotlin null check on the list (index 76 in 588) runs right after, and the
 *   finally handler round the trace section never reads it.
 * - The cube turn between people's stories (#521, 588 arm64 LX/0BAx;, v7 LX/0BB0;) is one of the
 *   page positioners the stories pager (com.instagram...ReboundViewPager, a kept name) places pages
 *   through. Each extends a base (LX/0Com;, v7 LX/0Cop;) whose placer, the one method opening the
 *   trace section "BaseSimpleItemPositioner.positionItemAtOffset", takes the page, the pager, the
 *   page's offset and its position. The viewer (LX/0B7S;->A0B) picks the cube for two of a server
 *   setting's values, a plain slide or a 2D tilt for the others. The cube's own placer sets the
 *   page's camera distance, slides it by the pager's width times its offset, turns it by 90 degrees
 *   times the offset round its left or right edge (two small helpers calling setRotationY) and puts
 *   it back square once it's settled. Each setRotationY in the cube goes through the extension in the
 *   same registers, so with the switch on a page keeps the slide and loses the turn.
 * - The Follow button on a story from someone you don't follow (#543, 588 arm64 LX/0BKd;) is an older
 *   Litho component that names itself "FollowButtonComponent" in its constructor and keeps the
 *   session, the bucket, the card and whether you follow them. Its layout (A1B) answers null itself
 *   on your own stories, so the patch answers null first thing on a yes.
 * - The "Up next" card (#544, 588 arm64 LX/0aWp;, "StoryViewerExtensionForwardComponent", strings
 *   "Up next", "in %1$d", "View now") sits over the first story of the next people's stories and
 *   counts down. The story page (LX/0BAt;) builds it while a flag of its state (LX/0BBu;->A0V) is set,
 *   and its should-play check (A0S) holds the story still while it's set, so drawing nothing would
 *   leave a story that never plays. The card's dismiss (a static A0A) clears the flag on the state's
 *   builder (LX/0BBs;), and the page's initial state (A1I) is the one other method that writes it:
 *   true for an ExtendedProfileRegularStoryBucket when a config says so. That value goes through the
 *   extension, which answers false on a yes, as if the card had been dismissed before it showed.
 * - The bar under someone else's story (#542: repost, "Send message..." and the quick reactions) comes
 *   from one of two places the footer (LX/0BCj;) picks between by config. One is the component
 *   (LX/0BIv;) whose render builds "ReplyBarContainerSection" (LX/0BJ1;) inside a recycler keyed
 *   "reply_bar_buttons_container"; it answers null first thing on a yes. The other is the newer
 *   footer (LX/0W8I;), which builds the feedback bar (LX/0W8K;) and, itself, the quick reactions'
 *   "StoryViewerReactionBarSection" (LX/0BJ7;); it answers null first thing on a yes too. The
 *   feedback bar's render also answers null unless its companion's kept
 *   isFeedbackBarSupportedForBucket says yes, and that check says no first thing on a yes. A Page's
 *   story on 588 showed why the footer needs its own hook: the feedback bar was already empty there
 *   and the reactions stayed.
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
internal const val HIDE_CREATE_STORY_CARD = "$STORY_VIEWER_CLEANUP->hideCreateStoryCard()Z"
internal const val HIDE_SONG_PILL = "$STORY_VIEWER_CLEANUP->hideSongPill()Z"

/** What the song pill under the poster's name keys its "Try it" button's transition with. */
internal const val SONG_PILL_BUTTON = "pill_cta_button_transition_key"

private const val STORY_BUCKET = "Lcom/facebook/stories/model/StoryBucket;"
private const val STORY_CARD = "Lcom/facebook/stories/model/StoryCard;"

private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"

/** What the "Add to story" card's source tags it with: the plain card or one built from the gallery (#439). */
internal val CREATE_CARD_SOURCES = listOf("ats_tile", "camera_roll")

/** The row of credit pills under the poster's name, by the name it gives itself. */
internal const val CHIP_GROUP = "StoryViewerAugmentChipGroupComponent"

/** The list of credits in the sheet a tap on one of those pills opens, by the name it gives itself. */
internal const val LINKS_COLUMN = "StoryViewerMultipleAttributionLinksColumnComponent"

/** Every component that walks a story's credit links and builds one per type, the pill row first. */
internal val CREDIT_WALKERS = listOf(CHIP_GROUP, LINKS_COLUMN)

/** Two of the pill types' names, which the type enum's static initializer must hold. */
internal val PILL_TYPE_NAMES = listOf("MUSIC", "EFFECT")

/** What the Subscribe pill's click handler hands the subscribe call. */
internal const val SUBSCRIBE_CLICK = "SubscribeButtonComponent"

/** The Subscribe pill's fields: the session, the card and whether it shows on this card only. */
internal val SUBSCRIBE_FIELDS = listOf("Lcom/facebook/auth/usersession/FbUserSession;", "Lcom/facebook/stories/model/StoryCard;", "Z")

/** How far before the string the click handler casts the pill: 48 instructions in 585. */
private const val SUBSCRIBE_REACH = 80

/** What the Follow button on a story names itself in its constructor (#543). */
internal const val FOLLOW_BUTTON = "FollowButtonComponent"

/** The story Follow button's fields: the session, the person's stories, the card and whether you follow them. */
internal val FOLLOW_FIELDS = listOf(
    "Lcom/facebook/auth/usersession/FbUserSession;", "Lcom/facebook/stories/model/StoryBucket;",
    "Lcom/facebook/stories/model/StoryCard;", "Z",
)

internal const val HIDE_FOLLOW = "$STORY_VIEWER_CLEANUP->hideFollow()Z"

/** What the "Up next" card over the next people's stories names itself (#544). */
internal const val UP_NEXT_CARD = "StoryViewerExtensionForwardComponent"

internal const val SHOW_UP_NEXT = "$STORY_VIEWER_CLEANUP->showUpNext(Z)Z"

/** What the bar under someone else's story names itself in its constructor (#542). */
internal const val REPLY_BAR_SECTION = "ReplyBarContainerSection"

/**
 * What the row of quick reactions under a story names itself in its constructor (#542). The newer
 * footer builds it next to the feedback bar, outside the [REPLY_BAR_SECTION], which is how a Page's
 * story keeps its reactions when the feedback bar has nothing to show.
 */
internal const val REACTION_BAR_SECTION = "StoryViewerReactionBarSection"

/** The kept name of the check that decides whether a story gets the newer feedback bar (#542). */
internal const val FEEDBACK_BAR_CHECK = "isFeedbackBarSupportedForBucket"

internal const val HIDE_REPLY_BAR = "$STORY_VIEWER_CLEANUP->hideReplyBar()Z"

/**
 * Takes the song and effect credits off the line under the poster's name on a story, and the
 * suggested replies off the bottom, and can draw a progress bar along the bottom (#380) and slide
 * from one person's stories to the next without the cube turn (#521), and keep the time next to the
 * name on the stories that leave it out (#563).
 *
 * In the default selection with every switch off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val storyViewerCleanupPatch = patch(
    name = "Clean up the story viewer",
    description = "Switches that take the song and effect credits and the Subscribe and Follow buttons off a " +
        "story, the suggested replies or the whole reply bar off the bottom, the Up next card off the next " +
        "people's stories, and the cards between stories that ask you to make your own out of the viewer. Two more " +
        "add a thin progress bar along the bottom like the one on Reels and swap the cube turn between people's " +
        "stories for a plain slide. Another puts how long ago a story was posted next to the name on stories that " +
        "leave it out, like a Page's. All start off. Turn them on in Hushfacebook settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        requireStatusMethod("storyViewerCleanup")
        cleanUpStoryViewer()
        dropCreditPills()
        hideSongPill()
        hideSubscribePill()
        hideStoryFollow()
        skipUpNextCard()
        hideStoryReplyBar()
        hideCreateStoryCards()
        dropCreateCardsFromViewerList()
        followStoryProgress()
        flattenStoryCube()
        showStoryTime()
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
 * Where a credit walker's render sorts a pill by its type: the call at [read] answers the type
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
 * loop's step to its next item. Null unless there's exactly one. 586 (LX/0dVe;) reads a boolean
 * field between the null check and the ordinal and, when it's off, jumps back up to the step too.
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
        val (flag, gate) = code[i - 2] to code[i - 1]
        val gated = i >= 5 && flag.opcode == Opcode.IGET_BOOLEAN && gate.opcode == Opcode.IF_EQZ &&
            (gate as OneRegisterInstruction).registerA == (flag as TwoRegisterInstruction).registerA &&
            at[i - 1] + (gate as OffsetInstruction).codeOffset < at[i - 5]
        val first = if (gated) i - 5 else i - 3
        val (read, result, check) = Triple(code[first], code[first + 1], code[first + 2])
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
                at[j] + (jump as OffsetInstruction).codeOffset < at[first]
        }
        if (shaped && steps) PillSwitch(first, type, i, scratch, i + 3, readRef.returnType) else null
    }
    return found.singleOrNull()
}

/** Leaves the song and effect pills out of the row under the poster's name and the sheet a tap on one opens. */
internal fun BytecodePatchContext.dropCreditPills() = CREDIT_WALKERS.forEach { dropCredits(it) }

/**
 * Leaves the song and effect pills out of the component naming itself [name]: right before the
 * ordinal, the extension hears the pill's type, and on a yes the loop takes its step to the next
 * link. The switch register is free there, since the ordinal is about to fill it.
 */
internal fun BytecodePatchContext.dropCredits(name: String) {
    val (_, render) = component(name)
    val site = pillSwitch(render) ?: refuse("$name doesn't switch on one pill type's ordinal inside its loop")
    val enum = classDefByOrNull(site.typeClass)?.takeIf { it.superclass == "Ljava/lang/Enum;" }
        ?: refuse("$name's pill type ${site.typeClass} isn't an enum")
    val names = enum.methods.singleOrNull { it.name == "<clinit>" }
    if (names == null || PILL_TYPE_NAMES.any { !holdsString(names, it) }) {
        refuse("$name's pill type ${site.typeClass} doesn't name $PILL_TYPE_NAMES")
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

/**
 * Whether [classDef] is the song pill: a component built from a story's bucket and card, whose render
 * keys its button [SONG_PILL_BUTTON]. The chips of the row under the name key theirs the same way
 * (588 arm64 `LX/0X3v;`, 587 loaded the key from its string table), but the row builds those from a
 * chip's config first, and [DROP_CREDIT] already skips them there.
 */
internal fun isSongPill(classDef: ClassDef): Boolean {
    val render = renderOf(classDef) ?: return false
    return holdsString(render, SONG_PILL_BUTTON) && classDef.methods.any { method ->
        method.name == "<init>" && method.parameterTypes.map { it.toString() }.take(2) == listOf(STORY_BUCKET, STORY_CARD)
    }
}

/** Hooks the song pill's render to answer null, as it does itself for a song with no title. */
internal fun BytecodePatchContext.hideSongPill() {
    val pills = classDefByStrings(SONG_PILL_BUTTON, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter(::isSongPill)
    val pill = pills.singleOrNull() ?: refuse("expected one story song pill keying its button $SONG_PILL_BUTTON, found ${pills.size}")
    val render = renderOf(pill)!!
    mutableClassDefBy(pill.type).methods.single {
        it.name == render.name && it.returnType == render.returnType &&
            it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
    }.answerWhen(HIDE_SONG_PILL, "const/4 v0, 0x0")
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

/** Whether [classDef]'s no-argument constructor hands [name] to its superclass, the way an older Litho component names itself. */
internal fun namesItselfIn(classDef: ClassDef, name: String): Boolean = classDef.methods.any {
    it.name == "<init>" && it.parameterTypes.isEmpty() && holdsString(it, name)
}

/** Whether [classDef] is a story's Follow button: it names itself [FOLLOW_BUTTON] and keeps the [FOLLOW_FIELDS]. */
internal fun isStoryFollowButton(classDef: ClassDef): Boolean = namesItselfIn(classDef, FOLLOW_BUTTON) &&
    classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.sorted() == FOLLOW_FIELDS.sorted()

/** An older Litho component's layout: its one instance method taking the component context alone and answering an object. */
internal fun layoutOf(classDef: ClassDef): Method? = classDef.methods.singleOrNull {
    it.name != "<init>" && it.parameterTypes.size == 1 && it.returnType.startsWith("L") && it.implementation != null &&
        !AccessFlags.STATIC.isSet(it.accessFlags) && !AccessFlags.BRIDGE.isSet(it.accessFlags) &&
        !AccessFlags.SYNTHETIC.isSet(it.accessFlags)
}

/** [method]'s own copy in [classDef]'s mutable class. */
private fun BytecodePatchContext.mutableOf(type: String, method: Method): MutableMethod = mutableClassDefBy(type).methods.single {
    it.name == method.name && it.returnType == method.returnType &&
        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
}

/** Hooks the story Follow button's layout to answer null, as it does itself on your own stories (#543). */
internal fun BytecodePatchContext.hideStoryFollow() {
    val buttons = classDefByStrings(FOLLOW_BUTTON, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter(::isStoryFollowButton)
    val button = buttons.singleOrNull() ?: refuse("expected one story Follow button naming itself $FOLLOW_BUTTON, found ${buttons.size}")
    val layout = layoutOf(button) ?: refuse("the story Follow button ${button.type} has no one layout method")
    mutableOf(button.type, layout).answerWhen(HIDE_FOLLOW, "const/4 v0, 0x0")
}

/**
 * The flag the "Up next" [card] clears when it's dismissed: the one boolean field its static methods
 * write. The story's page reads it to decide whether to show the card and hold the story still.
 */
internal fun upNextFlag(card: ClassDef): FieldReference? = card.methods
    .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    .flatMap { method ->
        method.implementation!!.instructions.filter { it.opcode == Opcode.IPUT_BOOLEAN }
            .map { (it as ReferenceInstruction).reference as FieldReference }
    }.distinctBy { "${it.definingClass}->${it.name}" }.singleOrNull()

/** The indexes in [method] that write [flag]. */
internal fun flagWrites(method: Method, flag: FieldReference): List<Int> =
    method.implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, it) ->
        it.opcode == Opcode.IPUT_BOOLEAN && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
            field.definingClass == flag.definingClass && field.name == flag.name
        }
    }.map { it.index }

/**
 * Sends the value each first state of a story's page gives the "Up next" flag through the extension
 * (#544). The card's own class and the state's builder, which copies the flag from one state to the
 * next, are left alone; the one method left is the page's initial state.
 */
internal fun BytecodePatchContext.skipUpNextCard() {
    val (card, _) = component(UP_NEXT_CARD)
    val flag = upNextFlag(card) ?: refuse("expected the $UP_NEXT_CARD to clear one flag when it's dismissed")
    val setters = mutableListOf<Pair<String, Method>>()
    classDefForEachReferencing(types = listOf(flag.definingClass)) { classDef ->
        if (classDef.type == flag.definingClass || classDef.type == card.type) return@classDefForEachReferencing
        classDef.methods.filter { flagWrites(it, flag).isNotEmpty() }.forEach { setters += classDef.type to it }
    }
    val (type, setter) = setters.singleOrNull()
        ?: refuse("expected one method outside the card and the state's builder to set the Up next flag, found ${setters.size}")
    val method = mutableOf(type, setter)
    // The last write first, so the indexes above it still hold.
    for (at in flagWrites(method, flag).reversed()) {
        val register = (method.getInstruction(at) as TwoRegisterInstruction).registerA
        val call = if (register < 16) "invoke-static { v$register }, $SHOW_UP_NEXT"
        else "invoke-static/range { v$register .. v$register }, $SHOW_UP_NEXT"
        method.addInstructionsAtControlFlowLabel(
            at,
            """
                $call
                move-result v$register
            """,
        )
    }
}

/** Whether [method] makes a new [type]. */
internal fun createsInstance(method: Method, type: String): Boolean = method.implementation?.instructions?.any {
    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == type
} == true

/** The one section in this build that names itself [name] in its constructor. */
private fun BytecodePatchContext.namedSection(name: String): ClassDef {
    val sections = classDefByStrings(name, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter { namesItselfIn(it, name) }
    return sections.singleOrNull() ?: refuse("expected one section naming itself $name, found ${sections.size}")
}

/**
 * Hooks every bar under someone else's story (#542). The component whose render builds the
 * [REPLY_BAR_SECTION] and the newer footer, whose render builds the [REACTION_BAR_SECTION] itself,
 * answer null first thing on a yes. The check that decides whether a story gets the newer feedback
 * bar says no too, so that bar's render answers null wherever else it's drawn.
 */
internal fun BytecodePatchContext.hideStoryReplyBar() {
    val section = namedSection(REPLY_BAR_SECTION)
    val reactions = namedSection(REACTION_BAR_SECTION)
    val holders = mutableListOf<ClassDef>()
    val footers = mutableListOf<ClassDef>()
    val checks = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(section.type, reactions.type), names = listOf(FEEDBACK_BAR_CHECK)) { classDef ->
        renderOf(classDef)?.let { render ->
            if (createsInstance(render, section.type)) holders += classDef
            if (createsInstance(render, reactions.type)) footers += classDef
        }
        classDef.methods.forEach { method ->
            method.implementation?.instructions?.forEach { instruction ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                if (called != null && called.name == FEEDBACK_BAR_CHECK && called.returnType == "Z") checks += called.definingClass
            }
        }
    }
    val holder = holders.singleOrNull()
        ?: refuse("expected one component whose render builds the $REPLY_BAR_SECTION, found ${holders.size}")
    mutableOf(holder.type, renderOf(holder)!!).answerWhen(HIDE_REPLY_BAR, "const/4 v0, 0x0")
    val footer = footers.singleOrNull()
        ?: refuse("expected one component whose render builds the $REACTION_BAR_SECTION, found ${footers.size}")
    mutableOf(footer.type, renderOf(footer)!!).answerWhen(HIDE_REPLY_BAR, "const/4 v0, 0x0")

    val owner = checks.singleOrNull() ?: refuse("expected one class answering $FEEDBACK_BAR_CHECK, found ${checks.size}")
    val check = mutableClassDefBy(owner).methods.singleOrNull { it.name == FEEDBACK_BAR_CHECK && it.returnType == "Z" }
        ?: refuse("$owner has no one $FEEDBACK_BAR_CHECK")
    check.requireLocals(STORY_VIEWER_CLEANUP_PATCH, 1)
    check.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_REPLY_BAR
            move-result v0
            if-eqz v0, :facebooks_check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebooks_check", check.getInstruction(0)),
    )
}

/** The one method in [classes] answering a list from a list last, that tags its card with every one of [CREATE_CARD_SOURCES]. */
internal fun createCardSource(classes: Iterable<ClassDef>): Method? = classes.flatMap { it.methods }.filter {
    it.returnType == IMMUTABLE_LIST && it.parameterTypes.lastOrNull()?.toString() == IMMUTABLE_LIST &&
        CREATE_CARD_SOURCES.all { source -> holdsString(it, source) }
}.singleOrNull()

/** Hooks the "Add to story" card's source to hand back the list it was given, its own answer when it adds nothing. */
internal fun BytecodePatchContext.hideCreateStoryCards() {
    val found = classDefByStrings(CREATE_CARD_SOURCES[0], StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val source = createCardSource(found) ?: refuse("expected one story list source tagging its card $CREATE_CARD_SOURCES")
    val method = mutableClassDefBy(source.definingClass).methods.single {
        it.name == source.name && it.returnType == source.returnType &&
            it.parameterTypes.map(CharSequence::toString) == source.parameterTypes.map(CharSequence::toString)
    }
    method.requireLocals(STORY_VIEWER_CLEANUP_PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_CREATE_STORY_CARD
            move-result v0
            if-eqz v0, :facebooks_list
            return-object ${method.parameterRegister(method.parameterTypes.size - 1)}
        """,
        ExternalLabel("facebooks_list", method.getInstruction(0)),
    )
}

/** The trace section the viewer's list runner opens first thing: only that method names it. */
internal const val BUCKET_DATA_RUN = "StoryviewerBucketDataController.processBucketData"

/** The id the "continue the story" card's bucket answers when it has none of its own (#521). */
internal const val CREATE_CARD_BUCKET_ID = "story-end-card-bucket"

internal const val WITHOUT_CREATE_CARDS = "$STORY_VIEWER_CLEANUP->withoutCreateCards(Ljava/util/List;)Ljava/util/List;"
internal const val IS_CREATE_CARD = "$STORY_VIEWER_CLEANUP->isCreateCard(Ljava/lang/Object;)Z"

private const val LIST_COPY = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"
private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
private val CONDITIONALS = setOf(
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
)

/** Where the viewer's list runner is done with its sources: the instruction at [at], with the finished list in [register]. */
internal data class SourcesDone(val at: Int, val register: Int)

/** The one method in [classes] answering an ImmutableList that opens [BUCKET_DATA_RUN]. */
internal fun listRunner(classes: Iterable<ClassDef>): Method? = classes.flatMap { it.methods }.filter {
    it.returnType == IMMUTABLE_LIST && it.implementation != null && holdsString(it, BUCKET_DATA_RUN)
}.singleOrNull()

/** The last register an invoke names, or null. */
private fun Instruction.lastRegister(): Int? = when (this) {
    is RegisterRangeInstruction -> (startRegister + registerCount - 1).takeIf { registerCount > 0 }
    is FiveRegisterInstruction -> when (registerCount) {
        1 -> registerC
        2 -> registerD
        3 -> registerE
        4 -> registerF
        5 -> registerG
        else -> null
    }
    else -> null
}

/**
 * The one place in [runner] where a source is asked for the list (session, flags and the list so
 * far in, a list out), its answer goes back into the list's own register, and the loop steps back
 * up, with a branch before it coming out of the loop right after that step. Null unless there's
 * exactly one.
 */
internal fun sourcesDone(runner: Method): SourcesDone? {
    val code = runner.implementation?.instructions?.toList() ?: return null
    val at = code.runningFold(0) { offset, it -> offset + it.codeUnits }
    val found = (0 until code.size - 3).mapNotNull { i ->
        val ask = code[i]
        if (ask.opcode != Opcode.INVOKE_INTERFACE && ask.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@mapNotNull null
        val called = (ask as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        val types = called.parameterTypes.map(CharSequence::toString)
        if (called.returnType != IMMUTABLE_LIST || types.size != 3 || types.first() != USER_SESSION ||
            types.last() != IMMUTABLE_LIST
        ) return@mapNotNull null
        val list = ask.lastRegister() ?: return@mapNotNull null
        val kept = code[i + 1]
        if (kept.opcode != Opcode.MOVE_RESULT_OBJECT || (kept as OneRegisterInstruction).registerA != list) return@mapNotNull null
        val step = code[i + 2]
        if (step.opcode !in GOTOS || at[i + 2] + (step as OffsetInstruction).codeOffset > at[i]) return@mapNotNull null
        val out = (0 until i).any { j ->
            code[j].opcode in CONDITIONALS && at[j] + (code[j] as OffsetInstruction).codeOffset == at[i + 3]
        }
        if (out) SourcesDone(i + 3, list) else null
    }
    return found.singleOrNull()
}

/** Whether [classDef] is the "continue the story" card's bucket: a story bucket whose id falls back to [CREATE_CARD_BUCKET_ID]. */
internal fun isCreateCardBucket(classDef: ClassDef): Boolean = classDef.superclass == STORY_BUCKET &&
    classDef.methods.any {
        it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
            it.implementation != null && holdsString(it, CREATE_CARD_BUCKET_ID)
    }

/**
 * Where the runner comes out of its loop, its finished list goes through the extension and back
 * through Guava's copyOf in its own register. The code goes in at that instruction's control flow
 * label, so the loop's way out runs it.
 */
internal fun MutableMethod.dropCreateCardsAt(done: SourcesDone) {
    val list = "v${done.register}"
    addInstructionsAtControlFlowLabel(
        done.at,
        """
            invoke-static/range { $list .. $list }, $WITHOUT_CREATE_CARDS
            move-result-object $list
            invoke-static/range { $list .. $list }, $LIST_COPY
            move-result-object $list
        """,
    )
}

/**
 * Takes the cards asking you to make a story out of the viewer's list once every source has had its
 * turn (#521), whichever source or tray load put them there. The extension's question about a bucket
 * is filled in first, with an instance-of on the card's class.
 */
internal fun BytecodePatchContext.dropCreateCardsFromViewerList() {
    val runners = classDefByStrings(BUCKET_DATA_RUN, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val runner = listRunner(runners) ?: refuse("expected one story list runner opening $BUCKET_DATA_RUN")
    val done = sourcesDone(runner) ?: refuse("the story list runner doesn't hand its list round its sources in one loop")
    if (done.register > 255) refuse("the story list runner keeps its list in v${done.register}, past what move-result-object reaches")
    val cards = classDefByStrings(CREATE_CARD_BUCKET_ID, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }.filter(::isCreateCardBucket)
    val card = cards.singleOrNull()
        ?: refuse("expected one story bucket whose id falls back to $CREATE_CARD_BUCKET_ID, found ${cards.size}")
    if (classDefByOrNull(IMMUTABLE_LIST)?.let(::definesImmutableCopy) != true) refuse("$IMMUTABLE_LIST has no copyOf(Collection)")

    val question = mutableClassDefBy(STORY_VIEWER_CLEANUP).methods.singleOrNull {
        it.name == "isCreateCard" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("the extension has no isCreateCard(Object)")
    question.addInstructions(
        0,
        """
            instance-of p0, p0, ${card.type}
            return p0
        """,
    )

    mutableClassDefBy(runner.definingClass).methods.single {
        it.name == runner.name && it.returnType == runner.returnType &&
            it.parameterTypes.map(CharSequence::toString) == runner.parameterTypes.map(CharSequence::toString)
    }.dropCreateCardsAt(done)
}

/** The trace section the pager's base page positioner opens as it places a page: only that method names it (#521). */
internal const val ITEM_POSITIONER = "BaseSimpleItemPositioner.positionItemAtOffset"

internal const val CUBE_TURN = "$STORY_VIEWER_CLEANUP->cubeTurn(Landroid/view/View;F)V"

private const val VIEW = "Landroid/view/View;"

/**
 * The base page positioner's placer in [classes]: the one void method taking a page, its pager, its
 * offset and its position that opens [ITEM_POSITIONER]. Each positioner the pager can use overrides it.
 */
internal fun pagePlacer(classes: Iterable<ClassDef>): Method? = classes.flatMap { it.methods }.filter {
    val types = it.parameterTypes.map(CharSequence::toString)
    it.returnType == "V" && it.implementation != null && types.size == 4 && types[0] == VIEW &&
        types[2] == "F" && types[3] == "I" && holdsString(it, ITEM_POSITIONER)
}.singleOrNull()

/** Whether [instruction] calls `View.[name](float)`. */
private fun callsViewFloat(instruction: Instruction, name: String): Boolean {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val called = (instruction as ReferenceInstruction).reference as? MethodReference ?: return false
    return called.definingClass == VIEW && called.name == name && called.returnType == "V" &&
        called.parameterTypes.map(CharSequence::toString) == listOf("F")
}

/** Where [method] turns a page on its Y axis: the index of every `View.setRotationY` it calls. */
internal fun cubeTurns(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { callsViewFloat(code[it], "setRotationY") }
}

/**
 * Whether [classDef] is the cube among the positioners overriding [placer]: its own placer sets the
 * page's camera distance, which only a turn in 3D needs, and it turns pages on the Y axis.
 */
internal fun isCubePositioner(classDef: ClassDef, placer: Method): Boolean {
    if (classDef.superclass != placer.definingClass) return false
    val params = placer.parameterTypes.map(CharSequence::toString)
    val places = classDef.methods.any { method ->
        method.name == placer.name && method.parameterTypes.map(CharSequence::toString) == params &&
            method.implementation?.instructions?.any { callsViewFloat(it, "setCameraDistance") } == true
    }
    return places && classDef.methods.any { cubeTurns(it).isNotEmpty() }
}

/** Sends the `View.setRotationY` at [index] to [CUBE_TURN] instead, with the same page and angle registers. */
internal fun MutableMethod.turnThroughExtension(index: Int) {
    val call = when (val turn = getInstruction(index)) {
        is RegisterRangeInstruction -> {
            if (turn.registerCount != 2) refuse("the story cube's turn at $index doesn't name a page and an angle")
            "invoke-static/range { v${turn.startRegister} .. v${turn.startRegister + 1} }, $CUBE_TURN"
        }
        is FiveRegisterInstruction -> {
            if (turn.registerCount != 2) refuse("the story cube's turn at $index doesn't name a page and an angle")
            "invoke-static { v${turn.registerC}, v${turn.registerD} }, $CUBE_TURN"
        }
        else -> refuse("the story cube's turn at $index isn't a call")
    }
    replaceInstruction(index, call)
}

/**
 * Hands each turn the stories pager's cube positioner gives a page to the extension (#521), which
 * keeps it square to the screen with the flat slide switch on. How far a page slides, its pivot and
 * when it shows stay Facebook's, so a page that doesn't turn just slides.
 */
internal fun BytecodePatchContext.flattenStoryCube() {
    val holders = classDefByStrings(ITEM_POSITIONER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val placer = pagePlacer(holders) ?: refuse("expected one page positioner opening $ITEM_POSITIONER")
    val found = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(placer.definingClass)) { classDef ->
        if (isCubePositioner(classDef, placer)) found += classDef.type
    }
    val cube = found.singleOrNull()
        ?: refuse("expected one positioner under ${placer.definingClass} turning its pages like a cube, found ${found.size}")
    mutableClassDefBy(cube).methods.forEach { method ->
        cubeTurns(method).forEach { method.turnThroughExtension(it) }
    }
}
