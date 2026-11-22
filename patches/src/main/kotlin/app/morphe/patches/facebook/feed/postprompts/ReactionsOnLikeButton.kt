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
 * again with the helper's own width setter, so three faces fit before the count.
 *
 * The faces at the end of the row are the facepile ReactionPreview.kt finds. Its draw already asks
 * the extension first, and now hands itself over too ([facepileAsk]), so the extension can hold it
 * back there and draw it on the button.
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
}
