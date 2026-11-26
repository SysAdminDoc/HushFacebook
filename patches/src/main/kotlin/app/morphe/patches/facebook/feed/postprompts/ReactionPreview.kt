/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.layout.theme.INLINE_REACTIONS
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.reels.watchhistory.callRegisters
import app.morphe.util.RegisterLiveness
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The reaction preview under a post (#158). Read from 583, 2026-10-18, arm64 and armv7; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * Two drawers paint it, and both are made to return before painting while the switch is on. The
 * views keep their size and their click handlers, so a tap where the preview was still works.
 * - The stacked reaction faces at the end of the button row: the Litho component Facebook names
 *   FDSInlineReactions (arm64 LX/3Ec;) has one static factory that returns a Drawable subclass (arm64
 *   LX/3Ed;, the facepile), whose draw(Canvas) paints each face and the ring between them.
 * - The bling bar's reaction icons and count line: PillsBlingBarView, a view whose class name Facebook
 *   keeps. Its onDraw(Canvas) paints the segments and nothing else, as the background is View.draw's.
 * The button numbers are not here (they are the count builder's, PostCounts.kt).
 */

internal const val PILLS_BLING_BAR = "Lcom/facebook/feedback/reactions/ui/PillsBlingBarView;"
internal const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
internal const val CANVAS = "Landroid/graphics/Canvas;"
internal const val HIDE_REACTION_PREVIEW = "$POST_PROMPTS->hideReactionPreview()Z"

/** Whether [method] is `draw(Canvas)` or `onDraw(Canvas)` returning void. */
internal fun isDrawMethod(method: Method, name: String): Boolean =
    method.name == name && method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) &&
        method.implementation != null

/**
 * The types [component]'s static methods return that [isDrawable] accepts: the facepile its factory
 * builds.
 */
internal fun facepileTypes(component: ClassDef, isDrawable: (String) -> Boolean): List<String> =
    component.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
        .map { it.returnType }.filter(isDrawable).distinct()

/**
 * Asks the extension first thing and returns before anything is painted when it says so. At the
 * first instruction no local holds anything yet, so v0 is free there.
 */
internal fun MutableMethod.skipDrawWhen(ask: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            $ask
            move-result v0
            if-eqz v0, :facebooks_draw
            return-void
        """,
        ExternalLabel("facebooks_draw", getInstruction(0)),
    )
}

/**
 * Finds the facepile through FDSInlineReactions and the bling bar by its kept class name, and has
 * each draw method return early while the switch is on. Refuses unless each step finds one.
 */
internal fun BytecodePatchContext.hideReactionPreview() {
    val components = classDefByStrings(INLINE_REACTIONS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val component = components.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one component named $INLINE_REACTIONS, found ${components.size}",
    )
    val faces = facepileTypes(component) { type ->
        classDefByOrNull(type)?.let { it.superclass == DRAWABLE && it.methods.any { m -> isDrawMethod(m, "draw") } } == true
    }
    val facepile = faces.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one Drawable built by ${component.type}'s static factory, found ${faces.size}",
    )
    val bar = classDefByOrNull(PILLS_BLING_BAR) ?: throw PatchException("$PATCH: $PILLS_BLING_BAR isn't in this build")
    val barDraws = bar.methods.filter { isDrawMethod(it, "onDraw") }
    if (barDraws.size != 1) throw PatchException("$PATCH: expected one onDraw(Canvas) in ${bar.type}, found ${barDraws.size}")

    val factories = component.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == facepile }
    val factory = factories.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static method on ${component.type} returning $facepile, found ${factories.size}",
    )

    // Comments draw the same facepile, so it learns which surface it was built for before it can be asked.
    tagCommentFaces(component, classDefBy(facepile), factory)
    // The count beside a comment's faces asks with the same flag (#483).
    hideCommentCounts(component)
    mutableClassDefBy(facepile).methods.single { isDrawMethod(it, "draw") }.skipDrawWhen(facepileAsk(facepile))
    mutableClassDefBy(bar.type).methods.single { isDrawMethod(it, "onDraw") }
        .skipDrawWhen("invoke-static { }, $HIDE_REACTION_PREVIEW")
}

/*
 * The count beside the faces (#158). Read from 584 arm64, 2026-10-23. The one method naming
 * "feedback_header_button_container" (arm64 LX/Bdb;->render, the reactions summary at the start of
 * the button row) builds the summary's text component from two Strings, the text and what TalkBack
 * reads (arm64 LX/Bde;). Facebook's inline layout hands it String.valueOf(the count), the other
 * layout "Name and 55 others". Neither drawer above reaches it, and neither does Hide counts on
 * posts, whose builder only the buttons call (PostCounts.kt).
 */

internal const val FEEDBACK_HEADER = "feedback_header_button_container"
internal const val REACTION_COUNT = "$POST_PROMPTS->reactionCount(Ljava/lang/String;)Ljava/lang/String;"
private const val STRING = "Ljava/lang/String;"

/**
 * The index of the one constructor call in [render] that takes two Strings, the summary's text and
 * its description, or null unless there is exactly one. Null too unless the object it builds was
 * made by the instruction right before it and nothing reads the text's register after it, so the
 * extension's answer reaches that component and nothing else.
 */
internal fun reactionCountCall(render: Method): Int? {
    val code = render.implementation?.instructions?.toList() ?: return null
    val call = code.indices.filter { index ->
        val call = code[index]
        (call.opcode == Opcode.INVOKE_DIRECT || call.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            ((call as ReferenceInstruction).reference as? MethodReference)?.let {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING)
            } == true
    }.singleOrNull() ?: return null
    val made = code.getOrNull(call - 1)
    val type = ((code[call] as ReferenceInstruction).reference as MethodReference).definingClass
    if (made?.opcode != Opcode.NEW_INSTANCE || (made as ReferenceInstruction).reference.toString() != type) return null
    val text = code[call].callRegisters()[1]
    if (call + 1 < code.size && text in RegisterLiveness.of(render).liveInto(call + 1)) return null
    return call
}

/**
 * Hands the summary's text to the extension right before its component is built and keeps the
 * answer in the same register. The description, so what TalkBack reads, stays Facebook's.
 */
internal fun MutableMethod.askAboutCountAt(call: Int) {
    val text = getInstruction(call).callRegisters()[1]
    addInstructions(
        call,
        """
            invoke-static/range { v$text .. v$text }, $REACTION_COUNT
            move-result-object v$text
        """,
    )
}

/** Finds the summary's text component and has its text asked about. Refuses unless each step finds one. */
internal fun BytecodePatchContext.hideReactionCount() {
    val holders = classDefByStrings(FEEDBACK_HEADER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val renders = holders.flatMap { methodsHolding(it, FEEDBACK_HEADER) }
    val render = renders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method naming $FEEDBACK_HEADER, found ${renders.size}",
    )
    val call = reactionCountCall(render) ?: throw PatchException(
        "$PATCH: ${render.definingClass}->${render.name} doesn't build exactly one component from two Strings " +
            "right after making it, with the text read nowhere after",
    )
    mutableClassDefBy(render.definingClass).findMutableMethodOf(render).askAboutCountAt(call)
}
