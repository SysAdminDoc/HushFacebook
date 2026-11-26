/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */

package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.aidetected.isModelListAccessor
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.modelAccessors
import app.morphe.patches.facebook.feed.words.GRAPHQL_FEEDBACK
import app.morphe.patches.facebook.feed.words.feedbackAccessors
import app.morphe.patches.facebook.feed.words.hasPublicIntReader
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.reels.watchhistory.callRegisters
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Show reactions on the Like button (#541). Read from 588 arm64, 2026-11-21; the obfuscated names
 * here are for reviewers, the code never writes one down.
 *
 * The feed's Like button takes its look from LikeButtonFeedUFIFooterItemPlugin, a class name
 * Facebook keeps. Two static methods there (A01, A02) build it from the viewer's reaction
 * (LX/031o;) and the button's text: a small data class (LX/03Ax;) of a style, the text's color, the
 * icon and the text. The icon is a thumb glyph (LX/03Av;) or, for a reaction Facebook has a picture
 * of, a drawable icon (LX/0CXI;, built from a Drawable and an int) made from the reaction's face
 * (LX/031o;->A05()). The litho footer builds the same look itself (LX/09Hj;->A00). Every look those
 * three build goes through a helper the patch adds to the plugin, which hands the extension the
 * look's icon and the face the button stands for: the drawable icon's own, else the reaction's.
 *
 * Those looks carry the word Like, not the count. The footer's renderer (LX/03jn;->render) keeps
 * them as state and only puts the count in later, rebuilding the look around the same icon. So the
 * swap happens where every finished look ends up, the button renderer's constructor (LX/03lJ;, whose
 * A0C is the one method reading the drawable icon's Drawable). A second helper there asks the
 * extension about the look's icon and text, and for a Like icon with a count returns a copy whose
 * icon is a drawable icon holding the extension's faces. The Comment and Share buttons' icons were
 * never handed over, so they keep theirs.
 *
 * LX/03lJ;->A0C draws a drawable icon as an image fitted into a square box that a style helper
 * sizes (LX/03i3;->A0U, which sets the width and then the height). Right after that helper the
 * patch asks the extension whether the Drawable is its faces, and for faces sets the box's width
 * again with the helper's own width setter, so the post's faces fit before the count.
 *
 * The faces at the end of the row are the facepile ReactionPreview.kt finds. Its draw already asks
 * the extension first, and now hands itself over too ([facepileAsk]), so the extension can hold it
 * back there and draw it on the button.
 *
 * That box fits the post's faces (read from 588, 2026-11-24). The feed's Like item builder
 * (LX/039B;->A04) calls the plugin's look builders and reads its post as a GraphQLStory out of an
 * Object field of its props parameter (LX/02df;->A01). First thing there, the patch hands that
 * field to the extension, which counts the post's top reactions somebody used: GraphQLStory's
 * feedback, the feedback's top_reactions (a TopReactionsConnection, A08()), its edges (TopReactionsEdge,
 * LX/04I4;->A0i()) and each edge's reaction_count, as Facebook's facepile picks them
 * (LX/03ls;->A01). The three accessors are found by field key and type tag and fill the
 * extension's stubs. Every return of the builder clears the count again.
 *
 * Until a row's faces turn up, the button shows Facebook's own thumb (read from 588, 2026-11-25).
 * The renderer's icon method (LX/03lJ;->A0C) draws a thumb glyph (LX/03Av;: the glyph, its variant
 * and a color) as a Litho icon, not a Drawable, so a third helper on the plugin draws it the way
 * Facebook's comment pin does: a glyph drawer (LX/0DbN;->A00, a Context, the glyph, a size, the
 * variant and a color) at 20 dp. The color is the glyph's own int (LX/0CPz;), the color of its token
 * (LX/03lR;) or, with none, the token the renderer falls back to (LX/01yx;->A39), each token read
 * through Facebook's color of a token for a Context (LX/0206;->A01, the one marked deprecated). The
 * extension's glyph stub calls that helper.
 */

internal const val LIKE_BUTTON_PLUGIN =
    "Lcom/facebook/feedback/feedufi/plugins/footeritem/likebutton/LikeButtonFeedUFIFooterItemPlugin;"
internal const val LIKE_BUTTON_FACES = "$EXTENSION_PACKAGE/feed/LikeButtonFaces;"
internal const val FACES_REMEMBERING = "$LIKE_BUTTON_FACES->remembering()Z"
internal const val FACES_REMEMBER = "$LIKE_BUTTON_FACES->remember(Ljava/lang/Object;$DRAWABLE)V"
internal const val FACES_FOR = "$LIKE_BUTTON_FACES->facesFor(Ljava/lang/Object;Ljava/lang/String;)$DRAWABLE"
internal const val FACES_IS = "$LIKE_BUTTON_FACES->isFaces($DRAWABLE)Z"
internal const val FACES_WIDTH = "$LIKE_BUTTON_FACES->widthBits(${DRAWABLE}F)J"
/** The plugin's helper that hands the extension a Like look's icon. */
internal const val FACES_HELPER = "hushLikeFaces"
/** The plugin's helper that swaps a finished look's icon for faces. */
internal const val FACES_LOOK = "hushLikeFacesLook"
private const val CONTEXT = "Landroid/content/Context;"
private const val TEXT = "Ljava/lang/String;"
private const val SCALE_TYPE = "Landroid/widget/ImageView\$ScaleType;"
private const val WHAT = "Show reactions on the Like button does nothing in this build"
internal const val FACES_STORY = "$LIKE_BUTTON_FACES->pendingStory(Ljava/lang/Object;)V"
internal const val FACES_CLEAR = "$LIKE_BUTTON_FACES->clearPending()V"
/** The extension's stubs a post's faces are counted through. */
internal const val FACES_FEEDBACK_STUB = "feedback"
internal const val FACES_TOP_STUB = "topReactions"
internal const val FACES_EDGES_STUB = "reactionEdges"
internal const val TOP_REACTIONS_FIELD = "top_reactions"
internal const val TOP_REACTIONS_TYPE = "TopReactionsConnection"
internal const val EDGES_FIELD = "edges"
internal const val TOP_REACTION_EDGE_TYPE = "TopReactionsEdge"
private const val OBJECT = "Ljava/lang/Object;"
private const val WIDE_BOX = "Show reactions on the Like button keeps a box for two faces"
private val RETURNS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT)
internal const val FACES_GLYPH = "$LIKE_BUTTON_FACES->glyph(Landroid/content/Context;Ljava/lang/Object;)$DRAWABLE"
/** The plugin's helper that draws a thumb glyph for the extension. */
internal const val GLYPH_HELPER = "hushLikeGlyph"
/** The size the helper draws a glyph at, the button's own: Facebook's glyph sizes keep their names. */
internal const val GLYPH_SIZE = "SIZE_20"
internal const val DEPRECATED = "Lkotlin/Deprecated;"
private const val LIKE_FACE = "Show reactions on the Like button shows the Like face until a row's faces turn up"

/** What the Like button's plugin builds its look from, read off its own code. */
internal class LikeLook(
    /** The plugin's static methods that build the look, every return of which goes through the helper. */
    val builders: List<Method>,
    /** The look: a style, the text's color, the icon and the text. */
    val look: ClassDef,
    val lookInit: MethodReference,
    /** The look's field for each of its constructor's parameters, in their order; the last is the text. */
    val lookFields: List<FieldReference>,
    /** Which constructor parameter is the icon. */
    val iconSlot: Int,
    /** The viewer's reaction and its face. */
    val reaction: String,
    val face: MethodReference,
    /** The drawable icon, its constructor and its Drawable. */
    val drawableIcon: String,
    val drawableIconInit: MethodReference,
    val drawableIconField: FieldReference,
)

private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.params(): List<String> = parameterTypes.map(CharSequence::toString)

private fun isStatic(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags)

/** Whether [method] is one of the plugin's look builders: (Context, session, the plugin, a reaction, the text, flags...). */
internal fun isLookBuilder(method: Method): Boolean {
    val params = method.parameterTypes.map(CharSequence::toString)
    return isStatic(method) && params.size >= 5 && params[0] == CONTEXT && params[2] == LIKE_BUTTON_PLUGIN &&
        params[4] == TEXT && method.returnType.startsWith("L") && method.implementation != null
}

/** The [LikeLook], or null with why in [why]. [classOf] loads a type, null when it isn't in the build. */
internal fun likeLook(plugin: ClassDef, classOf: (String) -> ClassDef?, why: (String) -> Unit): LikeLook? {
    val builders = plugin.methods.filter(::isLookBuilder)
    val looks = builders.map { it.returnType }.distinct()
    val lookType = looks.singleOrNull() ?: return null.also { why("expected the plugin's look builders to return one type, found $looks") }
    val reactions = builders.map { it.parameterTypes[3].toString() }.distinct()
    val reaction = reactions.singleOrNull() ?: return null.also { why("expected one reaction type in the look builders, found $reactions") }
    val calls = builders.flatMap { builder -> builder.implementation!!.instructions.mapNotNull { it.methodRef()?.let { ref -> it.opcode to ref } } }
    val inits = calls.filter { (op, ref) -> op == Opcode.INVOKE_DIRECT && ref.name == "<init>" && ref.definingClass == lookType && ref.params().lastOrNull() == TEXT }
        .map { it.second }.distinctBy { it.toString() }
    val lookInit = inits.singleOrNull() ?: return null.also { why("expected one constructor of $lookType ending in a String, found ${inits.size}") }
    // The look helper rebuilds the look in one invoke and keeps its other fields in v3 to v5.
    if (lookInit.params().size !in 2..4) return null.also { why("$lookInit takes ${lookInit.params().size} values, not 2 to 4") }
    val faces = calls.filter { (op, ref) -> op == Opcode.INVOKE_VIRTUAL && ref.definingClass == reaction && ref.params().isEmpty() && ref.returnType == DRAWABLE }
        .map { it.second }.distinctBy { it.toString() }
    val face = faces.singleOrNull() ?: return null.also { why("expected one Drawable getter on $reaction, found ${faces.size}") }
    val iconInits = calls.filter { (op, ref) -> op == Opcode.INVOKE_DIRECT && ref.name == "<init>" && ref.params() == listOf(DRAWABLE, "I") }
        .map { it.second }.distinctBy { it.toString() }
    val drawableIconInit = iconInits.singleOrNull() ?: return null.also { why("expected one icon built from a Drawable and an int, found ${iconInits.size}") }
    val drawableIcon = drawableIconInit.definingClass
    val iconClass = classOf(drawableIcon) ?: return null.also { why("$drawableIcon isn't in this build") }
    val iconSlot = lookInit.params().indexOf(iconClass.superclass)
    if (iconSlot < 0) return null.also { why("$lookInit takes no ${iconClass.superclass}") }
    val iconFields = iconClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == DRAWABLE }
    val drawableIconField = iconFields.singleOrNull() ?: return null.also { why("expected one Drawable field on $drawableIcon, found ${iconFields.size}") }
    val look = classOf(lookType) ?: return null.also { why("$lookType isn't in this build") }
    val instanceFields = look.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
    val lookFields = lookInit.params().map { type ->
        instanceFields.filter { it.type == type }.singleOrNull()
            ?: return null.also { why("expected one $type field on $lookType, found ${instanceFields.count { it.type == type }}") }
    }
    return LikeLook(builders, look, lookInit, lookFields, iconSlot, reaction, face, drawableIcon, drawableIconInit, drawableIconField)
}

private fun Method.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.smali() = "$definingClass->$name:$type"

/** The look helper's descriptor. */
internal fun facesHelperRef(found: LikeLook) = "$LIKE_BUTTON_PLUGIN->$FACES_HELPER(${found.look.type}${found.reaction})${found.look.type}"

/** The finished-look helper's descriptor. */
internal fun facesLookRef(found: LikeLook) = "$LIKE_BUTTON_PLUGIN->$FACES_LOOK(${found.look.type})${found.look.type}"

/**
 * The look helper added to the plugin: (look, reaction) -> the same look. With the switch on, hands
 * the extension the look's icon and the face the button stands for: the drawable icon's own, else
 * the reaction's.
 */
internal fun facesHelper(found: LikeLook) = ImmutableMethod(
    LIKE_BUTTON_PLUGIN,
    FACES_HELPER,
    listOf(ImmutableMethodParameter(found.look.type, null, null), ImmutableMethodParameter(found.reaction, null, null)),
    found.look.type,
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(5),
).toMutable().apply {
    addInstructionsWithLabels(
        0,
        """
            if-eqz p0, :keep
            invoke-static { }, $FACES_REMEMBERING
            move-result v0
            if-eqz v0, :keep
            iget-object v0, p0, ${found.lookFields[found.iconSlot].smali()}
            if-eqz v0, :keep
            const/4 v1, 0x0
            instance-of v2, v0, ${found.drawableIcon}
            if-eqz v2, :reaction
            move-object v2, v0
            check-cast v2, ${found.drawableIcon}
            iget-object v1, v2, ${found.drawableIconField.smali()}
            :reaction
            if-nez v1, :remember
            if-eqz p1, :remember
            invoke-virtual { p1 }, ${found.face}
            move-result-object v1
            :remember
            invoke-static { v0, v1 }, $FACES_REMEMBER
            :keep
            return-object p0
        """,
    )
}

/**
 * The finished-look helper added to the plugin: (look) -> look. Asks the extension about the look's
 * icon and text, and given faces back, returns a copy of the look with a drawable icon of them.
 * Anything else returns the look it was given.
 */
internal fun facesLook(found: LikeLook) = ImmutableMethod(
    LIKE_BUTTON_PLUGIN,
    FACES_LOOK,
    listOf(ImmutableMethodParameter(found.look.type, null, null)),
    found.look.type,
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(7),
).toMutable().apply {
    // v3 to v5 hold the look's other fields, in the constructor's order, around v2's new icon.
    var next = 3
    val reads = StringBuilder()
    val args = found.lookFields.mapIndexed { slot, field ->
        if (slot == found.iconSlot) "v2" else "v${next++}".also { reads.append("iget-object $it, p0, ${field.smali()}\n") }
    }
    addInstructionsWithLabels(
        0,
        """
            if-eqz p0, :keep
            iget-object v0, p0, ${found.lookFields[found.iconSlot].smali()}
            iget-object v1, p0, ${found.lookFields.last().smali()}
            invoke-static { v0, v1 }, $FACES_FOR
            move-result-object v1
            if-eqz v1, :keep
            new-instance v2, ${found.drawableIcon}
            const/16 v3, 0x541
            invoke-direct { v2, v1, v3 }, ${found.drawableIconInit}
            $reads
            new-instance v0, ${found.look.type}
            invoke-direct { v0, ${args.joinToString(", ")} }, ${found.lookInit}
            return-object v0
            :keep
            return-object p0
        """,
    )
}

/** The register a method's parameter [index] arrives in (an instance method's this comes before them). */
private fun parameterRegister(method: Method, index: Int): Int {
    val sizes = method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return method.implementation!!.registerCount - sizes.sum() + sizes.take(index).sum()
}

/**
 * Every return of [builder] goes through the look helper with the builder's own reaction, unless
 * the builder writes the reaction's register (then it's left alone and the reason logged).
 */
private fun BytecodePatchContext.facesAtReturns(found: LikeLook, builder: Method, warn: (String) -> Unit) {
    val reaction = parameterRegister(builder, 3)
    val code = builder.implementation!!.instructions.toList()
    if (code.any { writesRegister(it, reaction) }) return warn("${builder.smali()} writes its reaction register")
    val method = mutableClassDefBy(LIKE_BUTTON_PLUGIN).findMutableMethodOf(builder)
    val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }.sortedDescending()
    for (index in returns) {
        val answer = (code[index] as OneRegisterInstruction).registerA
        if (maxOf(answer, reaction) > 15) return warn("${builder.smali()} returns from a register past v15")
        method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$answer, v$reaction }, ${facesHelperRef(found)}
                move-result-object v$answer
            """,
        )
    }
}

/**
 * Whether [method] builds the Like button's look from a reaction outside the plugin, as the feed's
 * litho footer does: it takes the reaction and calls the look's constructor. [builders] and the
 * helpers are left out.
 */
internal fun isLookSite(found: LikeLook, builders: Set<String>, method: Method): Boolean =
    method.name != FACES_HELPER && method.name != FACES_LOOK && method.smali() !in builders &&
        method.parameterTypes.any { it.toString() == found.reaction } &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.INVOKE_DIRECT && it.methodRef()?.toString() == found.lookInit.toString()
        } == true

/** One look built at a [isLookSite] method, and the registers the look helper's call reads there. */
internal class LookBuilt(val at: Int, val look: Int, val reaction: Int, val held: Int)

/**
 * Every look [site] builds, or null with why in [why]: the look comes from the constructor's
 * registers, and the reaction from its parameter, moved into a register that's free right after
 * the constructor when it arrives past v15.
 */
internal fun looksBuilt(found: LikeLook, site: Method, why: (String) -> Unit): List<LookBuilt>? {
    val reaction = parameterRegister(site, site.parameterTypes.indexOfFirst { it.toString() == found.reaction })
    val code = site.implementation!!.instructions.toList()
    val liveness = RegisterLiveness.of(site)
    val flow = ControlFlow.of(site)
    return code.indices.filter {
        code[it].opcode == Opcode.INVOKE_DIRECT && code[it].methodRef()?.toString() == found.lookInit.toString()
    }.map { at ->
        // 588's footer reuses the reaction's register for a later call, after the look is built.
        if (writtenBefore(flow, at, reaction)) return null.also { why("${site.smali()} writes its reaction register before it builds the look") }
        val look = code[at].callRegisters().first()
        if (look > 15) return null.also { why("${site.smali()} builds its look past v15") }
        val held = if (reaction <= 15) reaction else (0..15).firstOrNull { it != look && it !in liveness.liveInto(at + 1) }
            ?: return null.also { why("no free register after ${site.smali()} builds its look") }
        LookBuilt(at, look, reaction, held)
    }
}

/** Whether any instruction that can run before instruction [at] writes [register]. */
private fun writtenBefore(flow: ControlFlow, at: Int, register: Int): Boolean {
    val before = Array(flow.instructions.size) { mutableListOf<Int>() }
    for (index in flow.instructions.indices) {
        (flow.normal[index] + flow.exceptional[index]).forEach { before[it] += index }
    }
    val seen = BooleanArray(flow.instructions.size)
    val next = ArrayDeque(before[at])
    while (next.isNotEmpty()) {
        val index = next.removeLast()
        if (seen[index]) continue
        seen[index] = true
        if (writesRegister(flow.instructions[index], register)) return true
        next += before[index]
    }
    return false
}

/**
 * Each look [site] builds goes through the look helper right after its constructor. Code that jumps
 * past the constructor keeps its label on the instruction after it, so it never runs the call.
 */
private fun BytecodePatchContext.facesAfterLooks(found: LikeLook, site: Method, built: List<LookBuilt>) {
    val method = mutableClassDefBy(site.definingClass).findMutableMethodOf(site)
    for (look in built.sortedByDescending { it.at }) {
        val load = if (look.held == look.reaction) "" else "move-object/from16 v${look.held}, v${look.reaction}"
        method.addInstructions(
            look.at + 1,
            """
                $load
                invoke-static { v${look.look}, v${look.held} }, ${facesHelperRef(found)}
                move-result-object v${look.look}
            """,
        )
    }
}

/** The button renderer's constructors that take a look, with the register the look arrives in. */
internal fun rendererInits(found: LikeLook, renderer: ClassDef): List<Pair<Method, Int>> =
    renderer.methods.filter { it.name == "<init>" && it.implementation != null }.mapNotNull { init ->
        val slot = init.parameterTypes.indexOfFirst { it.toString() == found.look.type }
        if (slot < 0) null else init to parameterRegister(init, slot)
    }

/** Each of the button renderer's constructors swaps its look for the finished-look helper's first thing. */
private fun BytecodePatchContext.facesAtRenderers(found: LikeLook, inits: List<Pair<Method, Int>>) {
    for ((init, look) in inits) {
        mutableClassDefBy(init.definingClass).findMutableMethodOf(init).addInstructions(
            0,
            """
                invoke-static/range { v$look .. v$look }, ${facesLookRef(found)}
                move-result-object v$look
            """,
        )
    }
}

/** Where the button's renderer sizes a drawable icon's box, and what it needs to widen it. */
internal class FacesBox(
    val renderer: Method,
    /** The instruction after the style helper's result, where the width is set again. */
    val at: Int,
    val drawable: Int,
    val size: Int,
    val style: Int,
    /** A free wide register pair, its first register. */
    val spare: Int,
    val widthSetter: MethodReference,
)

/** The [FacesBox] in [renderer], or null with why in [why]. [methodOf] finds a method's body. */
internal fun facesBox(renderer: Method, methodOf: (MethodReference) -> Method?, why: (String) -> Unit): FacesBox? {
    val code = renderer.implementation!!.instructions.toList()
    val images = code.indices.filter { i ->
        code[i].opcode == Opcode.INVOKE_DIRECT && code[i].methodRef()?.let {
            it.name == "<init>" && it.params().size == 4 && it.params()[0] == DRAWABLE && it.params()[1] == SCALE_TYPE && it.params()[3] == "Z"
        } == true
    }
    val image = images.singleOrNull() ?: return null.also { why("expected one image built from a Drawable and a scale type, found ${images.size}") }
    val styleType = code[image].methodRef()!!.params()[2]
    val (_, drawable, _, style) = code[image].callRegisters()
    val sizers = (0 until image).filter { i ->
        code[i].opcode == Opcode.INVOKE_STATIC && code[i].methodRef()?.let {
            it.params() == listOf(styleType, "F") && it.returnType == styleType
        } == true && code.getOrNull(i + 1)?.let { it.opcode == Opcode.MOVE_RESULT_OBJECT && (it as OneRegisterInstruction).registerA == style } == true
    }
    val sizer = sizers.singleOrNull() ?: return null.also { why("expected one square size for the image's style, found ${sizers.size}") }
    val size = code[sizer].callRegisters()[1]
    val helper = methodOf(code[sizer].methodRef()!!) ?: return null.also { why("${code[sizer].methodRef()} has no body here") }
    val setters = helper.implementation!!.instructions.mapNotNull { it.methodRef() }
        .filter { it.params() == listOf(styleType, "J") && it.returnType == styleType }
    val widthSetter = setters.firstOrNull() ?: return null.also { why("${helper.smali()} sets no dimension") }
    val at = sizer + 2
    val live = RegisterLiveness.of(renderer).liveInto(at)
    val taken = setOf(drawable, style)
    val spare = (0 until 15).firstOrNull { t ->
        t != size && t !in taken && t + 1 !in taken && t !in live && t + 1 !in live
    } ?: return null.also { why("no free register pair where ${renderer.smali()} sizes the image") }
    if (maxOf(drawable, size, style) > 15) return null.also { why("${renderer.smali()} sizes its image past v15") }
    return FacesBox(renderer, at, drawable, size, style, spare, widthSetter)
}

/** Sets a faces icon's box width again, right after the renderer's square size. */
private fun BytecodePatchContext.widenFaces(box: FacesBox) {
    val method = mutableClassDefBy(box.renderer.definingClass).findMutableMethodOf(box.renderer)
    method.addInstructionsWithLabels(
        box.at,
        """
            invoke-static { v${box.drawable} }, $FACES_IS
            move-result v${box.spare}
            if-eqz v${box.spare}, :facebooks
            invoke-static { v${box.drawable}, v${box.size} }, $FACES_WIDTH
            move-result-wide v${box.spare}
            invoke-static { v${box.style}, v${box.spare}, v${box.spare + 1} }, ${box.widthSetter}
            move-result-object v${box.style}
        """,
        ExternalLabel("facebooks", method.getInstruction(box.at)),
    )
}

/** Where a feed post's Like item builder reads its post: the register of the parameter holding it, and its field. */
internal class StorySource(val builder: Method, val holder: Int, val story: FieldReference)

/**
 * The [StorySource] of [method], or null: a method outside the plugin that calls the plugin's look
 * builders and reads a GraphQLStory out of one Object field of a parameter's class, with a register
 * of its own to read it into first thing.
 */
internal fun storySource(found: LikeLook, method: Method): StorySource? {
    if (method.definingClass == LIKE_BUTTON_PLUGIN) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val builders = found.builders.map { it.smali() }.toSet()
    if (code.none { it.methodRef()?.let { ref -> "$ref" in builders } == true }) return null
    val params = method.parameterTypes.map(CharSequence::toString)
    val fields = code.indices.mapNotNull { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        if (field == null || code[at].opcode != Opcode.IGET_OBJECT || field.type != OBJECT) return@mapNotNull null
        if (params.count { it == field.definingClass } != 1) return@mapNotNull null
        val casts = code.subList(at + 1, minOf(code.size, at + 4)).any {
            it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as? TypeReference)?.type == GRAPHQL_STORY
        }
        if (casts) field else null
    }.distinctBy { it.smali() }
    val story = fields.singleOrNull() ?: return null
    val incoming = params.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (isStatic(method)) 0 else 1
    if (method.implementation!!.registerCount <= incoming) return null
    return StorySource(method, parameterRegister(method, params.indexOf(story.definingClass)), story)
}

/** The three accessors a post's faces are counted through. */
internal class FaceAccessors(val feedback: Method, val topReactions: Method, val edges: Method)

/**
 * GraphQLStory's feedback, the feedback's top_reactions and their edges, each the one accessor of
 * its field key and type tag, or null with why in [why]. [classOf] loads a type.
 */
internal fun faceAccessors(classOf: (String) -> ClassDef?, why: (String) -> Unit): FaceAccessors? {
    val story = classOf(GRAPHQL_STORY) ?: return null.also { why("GraphQLStory isn't in this build") }
    val feedbacks = feedbackAccessors(story)
    val feedback = feedbacks.singleOrNull() ?: return null.also { why("expected one feedback accessor on GraphQLStory, found ${feedbacks.size}") }
    val feedbackClass = classOf(GRAPHQL_FEEDBACK) ?: return null.also { why("GraphQLFeedback isn't in this build") }
    val tops = modelAccessors(feedbackClass, TOP_REACTIONS_FIELD, TOP_REACTIONS_TYPE)
    val top = tops.singleOrNull() ?: return null.also { why("expected one $TOP_REACTIONS_FIELD accessor on GraphQLFeedback, found ${tops.size}") }
    val connection = classOf(top.returnType) ?: return null.also { why("${top.returnType} isn't in this build") }
    val lists = connection.methods.filter { isModelListAccessor(it, connection.type, EDGES_FIELD, TOP_REACTION_EDGE_TYPE) }
    val edges = lists.singleOrNull() ?: return null.also { why("expected one $EDGES_FIELD accessor on ${connection.type}, found ${lists.size}") }
    val treeModel = classOf(BASE_MODEL_WITH_TREE) ?: return null.also { why("BaseModelWithTree isn't in this build") }
    if (!hasPublicIntReader(treeModel)) return null.also { why("BaseModelWithTree has no public getCachedInt(int)") }
    return FaceAccessors(feedback, top, edges)
}

/**
 * The builder hands the extension its post first thing, read into v0 (a register of its own, not
 * yet in use) from the holder parameter's field, and clears it again at every return.
 */
private fun BytecodePatchContext.storyFirst(source: StorySource) {
    val method = mutableClassDefBy(source.builder.definingClass).findMutableMethodOf(source.builder)
    val returns = method.implementation!!.instructions.withIndex().filter { it.value.opcode in RETURNS }.map { it.index }
    for (index in returns.sortedDescending()) method.addInstructionsAtControlFlowLabel(index, "invoke-static { }, $FACES_CLEAR")
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, v${source.holder}
            if-eqz v0, :read
            iget-object v0, v0, ${source.story.smali()}
            :read
            invoke-static { v0 }, $FACES_STORY
        """,
    )
}

/**
 * Sizes the faces' box to the post's faces: the stubs filled and each [StorySource] patched, or
 * nothing with why logged, which keeps the box wide enough for two.
 */
private fun BytecodePatchContext.facesCounted(found: LikeLook) {
    val warn = { why: String -> patchLog.warning("$PATCH: $why. $WIDE_BOX") }
    val sources = mutableListOf<StorySource>()
    classDefForEachReferencing(types = listOf(LIKE_BUTTON_PLUGIN)) { classDef ->
        classDef.methods.mapNotNullTo(sources) { storySource(found, it) }
    }
    if (sources.isEmpty()) return warn("no Like item builder reads its post")
    val accessors = faceAccessors(::classDefByOrNull, warn) ?: return
    fillStoryModelStub(LIKE_BUTTON_FACES, FACES_FEEDBACK_STUB, accessors.feedback)
    fillStoryModelStub(LIKE_BUTTON_FACES, FACES_TOP_STUB, accessors.topReactions)
    fillStoryModelStub(LIKE_BUTTON_FACES, FACES_EDGES_STUB, accessors.edges)
    for (source in sources.distinctBy { it.builder.smali() }) storyFirst(source)
}

private fun ClassDef.instanceFields() = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun isPublic(flags: Int) = AccessFlags.PUBLIC.isSet(flags)

/** The thumb glyph and its color's two kinds, as [glyphParts] reads them. */
internal class GlyphParts(
    val glyph: ClassDef,
    /** The glyph's color, of an abstract type both kinds extend. */
    val color: FieldReference,
    /** The glyph's two other fields, the glyph itself and its variant, in either order. */
    val others: List<FieldReference>,
    /** The int of the kind of color that is one. */
    val intColor: FieldReference,
    /** The token of the kind of color that names one. */
    val tokenColor: FieldReference,
)

/**
 * The thumb glyph's parts, read off [renderer], the renderer's icon method: of the classes it checks
 * an icon against, the one that's an icon like the drawable icon and holds three objects; the field
 * of it whose type other checked classes extend, its color; and those kinds of color, one holding an
 * int and one a token. Null with why in [why]. [classOf] loads a type, null when it isn't in the build.
 */
internal fun glyphParts(found: LikeLook, renderer: Method, classOf: (String) -> ClassDef?, why: (String) -> Unit): GlyphParts? {
    val iconSuper = classOf(found.drawableIcon)?.superclass ?: return null.also { why("${found.drawableIcon} isn't in this build") }
    val checked = renderer.implementation!!.instructions.filter { it.opcode == Opcode.INSTANCE_OF }
        .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }.distinct()
        .filter { it != found.drawableIcon }.mapNotNull(classOf)
    val glyphs = checked.filter { kind ->
        kind.superclass == iconSuper && kind.instanceFields().let { fields -> fields.size == 3 && fields.all { it.type.startsWith("L") } }
    }
    val glyph = glyphs.singleOrNull() ?: return null.also { why("expected one glyph among the icons ${renderer.smali()} checks for, found ${glyphs.size}") }
    val colors = glyph.instanceFields().filter { field -> checked.any { it.superclass == field.type } }
    val color = colors.singleOrNull() ?: return null.also { why("expected one color field on ${glyph.type}, found ${colors.size}") }
    val kinds = checked.filter { it.superclass == color.type }
    val held = kinds.mapNotNull { it.instanceFields().singleOrNull() }
    val ints = held.filter { it.type == "I" }
    val tokens = held.filter { it.type.startsWith("L") }
    val intColor = ints.singleOrNull() ?: return null.also { why("expected one kind of ${color.type} holding an int, found ${ints.size}") }
    val tokenColor = tokens.singleOrNull() ?: return null.also { why("expected one kind of ${color.type} holding a token, found ${tokens.size}") }
    val shut = (kinds + glyph).filter { kind -> !isPublic(kind.accessFlags) || kind.instanceFields().any { !isPublic(it.accessFlags) } }
    if (shut.isNotEmpty()) return null.also { why("${shut.map { it.type }} isn't public") }
    return GlyphParts(glyph, color, glyph.instanceFields().filter { it.name != color.name }, intColor, tokenColor)
}

/**
 * The token a glyph with no color of its own is drawn in: the first [token] the renderer's render
 * method loads, where it reads the glyph's color. That's the one method of [rendererClass] calling
 * [renderer] that loads a token at all (588's other caller only passes sizes on). Null with why in [why].
 */
internal fun defaultToken(rendererClass: ClassDef, renderer: Method, token: String, why: (String) -> Unit): FieldReference? {
    fun firstToken(method: Method) = method.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { instruction.opcode == Opcode.SGET_OBJECT && it.type == token && it.definingClass == token }
    }
    val callers = rendererClass.methods.filter { method ->
        method.implementation?.instructions?.any { it.methodRef()?.toString() == renderer.smali() } == true
    }
    val loading = callers.mapNotNull { firstToken(it) }
    return loading.singleOrNull()
        ?: null.also { why("expected one method calling ${renderer.smali()} that loads a $token, found ${loading.size} of ${callers.size}") }
}

/** Whether [method] is Facebook's color of a [token] for a Context: public, static, (Context, token) -> int, and marked deprecated. */
internal fun isTokenColor(method: Method, token: String) =
    isStatic(method) && isPublic(method.accessFlags) && method.returnType == "I" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, token) &&
        method.annotations.any { it.type == DEPRECATED }

/**
 * Whether [method] draws a glyph as a Drawable: public, on an object, taking a Context, one of the
 * glyph's two other fields, a size, the other one and a color, in that order.
 */
internal fun isGlyphDrawer(method: Method, parts: GlyphParts): Boolean {
    val params = method.parameterTypes.map(CharSequence::toString)
    return !isStatic(method) && isPublic(method.accessFlags) && method.returnType == DRAWABLE && params.size == 5 &&
        params[0] == CONTEXT && params[4] == "I" && params[1] != params[3] &&
        setOf(params[1], params[3]) == parts.others.map { it.type }.toSet()
}

/** Whether [classDef] is public with a public constructor taking nothing, so the helper can make one. */
internal fun hasPlainInit(classDef: ClassDef) = isPublic(classDef.accessFlags) &&
    classDef.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() && isPublic(it.accessFlags) }

/** Whether [size] is an enum with a constant named [GLYPH_SIZE], which the helper asks it for by name. */
internal fun hasGlyphSize(size: ClassDef?) = size != null && AccessFlags.ENUM.isSet(size.accessFlags) &&
    size.methods.any { method ->
        method.name == "<clinit>" && method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == GLYPH_SIZE
        } == true
    }

/** The glyph helper's descriptor. */
internal fun glyphHelperRef() = "$LIKE_BUTTON_PLUGIN->$GLYPH_HELPER($CONTEXT$OBJECT)$DRAWABLE"

/**
 * The glyph helper added to the plugin: (Context, icon) -> Drawable. A thumb glyph comes back drawn
 * by [drawer] at [GLYPH_SIZE] in its color: its own int, its token's color, or [default]'s when it
 * has none, each token through [resolver]. Any other icon gets null.
 */
internal fun glyphHelper(parts: GlyphParts, default: FieldReference, resolver: Method, drawer: Method) = ImmutableMethod(
    LIKE_BUTTON_PLUGIN,
    GLYPH_HELPER,
    listOf(ImmutableMethodParameter(CONTEXT, null, null), ImmutableMethodParameter(OBJECT, null, null)),
    DRAWABLE,
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(8),
).toMutable().apply {
    val params = drawer.parameterTypes.map(CharSequence::toString)
    val glyphField = parts.others.single { it.type == params[1] }
    val variantField = parts.others.single { it.type == params[3] }
    val size = params[2]
    // v0 to v5 end up as the drawer's call: the drawer, the Context, the glyph, the size, the variant
    // and the color.
    addInstructionsWithLabels(
        0,
        """
            instance-of v0, p1, ${parts.glyph.type}
            if-eqz v0, :none
            move-object v1, p1
            check-cast v1, ${parts.glyph.type}
            iget-object v2, v1, ${parts.color.smali()}
            instance-of v0, v2, ${parts.intColor.definingClass}
            if-eqz v0, :token
            check-cast v2, ${parts.intColor.definingClass}
            iget v5, v2, ${parts.intColor.smali()}
            goto :draw
            :token
            sget-object v0, ${default.smali()}
            instance-of v3, v2, ${parts.tokenColor.definingClass}
            if-eqz v3, :resolve
            check-cast v2, ${parts.tokenColor.definingClass}
            iget-object v3, v2, ${parts.tokenColor.smali()}
            if-eqz v3, :resolve
            move-object v0, v3
            :resolve
            invoke-static { p0, v0 }, ${resolver.smali()}
            move-result v5
            :draw
            iget-object v2, v1, ${glyphField.smali()}
            iget-object v4, v1, ${variantField.smali()}
            const-class v3, $size
            const-string v0, "$GLYPH_SIZE"
            invoke-static { v3, v0 }, Ljava/lang/Enum;->valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
            move-result-object v3
            check-cast v3, $size
            new-instance v0, ${drawer.definingClass}
            invoke-direct { v0 }, ${drawer.definingClass}-><init>()V
            move-object v1, p0
            invoke-virtual/range { v0 .. v5 }, ${drawer.smali()}
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
}

/**
 * The thumb the extension shows until a row has faces: the glyph helper on the plugin and the
 * extension's glyph stub calling it, or nothing with why logged, which keeps the Like face there.
 */
private fun BytecodePatchContext.glyphDrawn(found: LikeLook, renderer: Method) {
    val warn = { why: String -> patchLog.warning("$PATCH: $why. $LIKE_FACE") }
    val parts = glyphParts(found, renderer, ::classDefByOrNull, warn) ?: return
    val token = parts.tokenColor.type
    val default = defaultToken(classDefByOrNull(renderer.definingClass)!!, renderer, token, warn) ?: return
    if (classDefByOrNull(token)?.fields?.any { it.name == default.name && isPublic(it.accessFlags) } != true) {
        return warn("${default.smali()} isn't public")
    }
    val resolvers = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(token)) { classDef ->
        if (isPublic(classDef.accessFlags)) classDef.methods.filterTo(resolvers) { isTokenColor(it, token) }
    }
    val resolver = resolvers.distinctBy { it.smali() }.singleOrNull()
        ?: return warn("expected one deprecated color of a $token for a Context, found ${resolvers.size}")
    val drawers = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(parts.others.first().type)) { classDef ->
        if (hasPlainInit(classDef)) classDef.methods.filterTo(drawers) { isGlyphDrawer(it, parts) }
    }
    val drawer = drawers.distinctBy { it.smali() }.singleOrNull()
        ?: return warn("expected one class drawing a ${parts.glyph.type} as a Drawable, found ${drawers.size}")
    val size = drawer.parameterTypes[2].toString()
    if (!hasGlyphSize(classDefByOrNull(size))) return warn("$size has no $GLYPH_SIZE")
    mutableClassDefBy(LIKE_BUTTON_PLUGIN).methods.add(glyphHelper(parts, default, resolver, drawer))
    val stub = mutableClassDefBy(LIKE_BUTTON_FACES).methods.singleOrNull { it.smali() == FACES_GLYPH }
        ?: return warn("the extension has no $FACES_GLYPH")
    stub.addInstructions(
        0,
        """
            invoke-static { p0, p1 }, ${glyphHelperRef()}
            move-result-object p0
            return-object p0
        """,
    )
}

/**
 * Show reactions on the Like button: the helpers and their calls in the plugin and the litho
 * footer, the swap in the button renderer's constructors, and the wider box in its drawing. A
 * build where any step finds something else gets none of it, and the reason is logged; the rest of
 * the patch is unaffected.
 */
internal fun BytecodePatchContext.reactionsOnLikeButton() {
    val warn = { why: String -> patchLog.warning("$PATCH: $why. $WHAT") }
    val plugin = classDefByOrNull(LIKE_BUTTON_PLUGIN) ?: return warn("$LIKE_BUTTON_PLUGIN isn't in this build")
    val found = likeLook(plugin, ::classDefByOrNull, warn) ?: return
    var renderers = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(found.drawableIcon)) { classDef ->
        classDef.methods.filterTo(renderers) { method ->
            method.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.smali() == found.drawableIconField.smali()
            } == true
        }
    }
    renderers = renderers.distinctBy { it.smali() }.toMutableList()
    val renderer = renderers.singleOrNull() ?: return warn("expected one method reading ${found.drawableIconField.smali()}, found ${renderers.size}")
    val box = facesBox(renderer, { ref ->
        classDefByOrNull(ref.definingClass)?.methods?.firstOrNull { it.name == ref.name && it.smali() == "$ref" }
    }, warn) ?: return
    val inits = rendererInits(found, classDefByOrNull(renderer.definingClass)!!)
    if (inits.isEmpty()) return warn("${renderer.definingClass} has no constructor taking ${found.look.type}")
    // The feed's litho footer builds its Like button's look itself, without the plugin's builders.
    val builders = found.builders.map { it.smali() }.toSet()
    val sites = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(found.look.type)) { classDef ->
        classDef.methods.filterTo(sites) { isLookSite(found, builders, it) }
    }
    val built = sites.distinctBy { it.smali() }.mapNotNull { site -> looksBuilt(found, site, warn)?.let { site to it } }
    if (built.isEmpty()) warn("no look is built from a reaction outside the plugin, so the litho footer's Like button keeps its thumb")
    mutableClassDefBy(LIKE_BUTTON_PLUGIN).methods.apply {
        add(facesHelper(found))
        add(facesLook(found))
    }
    for (builder in found.builders) facesAtReturns(found, builder, warn)
    for ((site, looks) in built) facesAfterLooks(found, site, looks)
    facesAtRenderers(found, inits)
    widenFaces(box)
    facesCounted(found)
    glyphDrawn(found, renderer)
}
