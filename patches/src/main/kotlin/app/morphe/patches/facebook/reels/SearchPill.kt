/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Hide the search pill under a reel's caption (#173). Read from 583, 2026-10-17, arm64 and armv7;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The pill is a Litho component Facebook names FbShortsSearchElevatedCTAComponent (arm64
 *   LX/dG9;). Its static initializer holds that name and its render method holds the
 *   search_pivot_view_tag literal. A second class (arm64 LX/bCo;) holds the same name in its
 *   initializer, but it has no render and no view tag.
 * - One static builder makes the component (arm64 LX/Ux1;->A01): it is the only method that does
 *   new-instance of it. Facebook's own early exit, taken when the query text is missing, is its last
 *   three instructions: a no-argument static call, its move-result-object and the return. That call
 *   builds the empty component every caller already copes with, so the switch answers with it.
 * - The builder's callers are the reel footer's render methods (arm64 LX/AQ0; and LX/AQ1;).
 *
 * 585 (arm64 and armeabi-v7a): the component (arm64 LX/0cbu;, v7a LX/0cbL;) still names itself in its
 *   initializer and a decoy (arm64 LX/0dTN;) still has no render, but render no longer holds the view
 *   tag: it asks a string table for it (arm64 LX/0Xst;->A00, index 0x3e9). The tag is read through the
 *   table, and the builder (arm64 LX/0dLG;->A01) is found by the component as before.
 *
 * The same switch takes the search pill off the top of a reel's comments (#197). Read from 583,
 * 2026-10-20, arm64:
 *
 * - One static builder makes that pill (arm64 LX/A8a;->A01) and it's the only method holding the
 *   fb_video_comment_search_pivot literal. It has no empty answer: it always returns the pill.
 * - Its one caller is the comment sheet's section builder (arm64 LX/A8a;->A2b), in the same class.
 *   It reads a SearchPromptParam field once, near its start, and builds the pill row only when that
 *   prompt isn't null (and only for the two reels surfaces, FB_SHORTS_VIEWER and
 *   FB_SHORTS_PROFILE_VIDEO_CHAINING). A null prompt is Facebook's own path past the whole row, and
 *   the register isn't read again after it, so the hook answers the prompt or null right there.
 *
 * And the search bar over a reel opened from a profile, with the send button that runs its
 * suggested query (#201). Read from 583, 2026-10-21, arm64:
 *
 * - One static builder makes that header row (arm64 LX/UXK;->A00): the back button, then the bar
 *   (LX/U1g;), then the send button (LX/ALf;). It's the only method holding the fb_video_ghost_bar
 *   and fb_video_ghost_bar_send literals, the view tags of the logging wrapper each part may get.
 * - Each part goes into the row through the first virtual call after its literal (arm64
 *   LX/32h;->A3b), which takes one component and skips a null one: Facebook's own answer for a part
 *   it didn't build. A missing query and a failed wrapper jump straight to that call, so the hook
 *   takes the call's place, keeping those jumps, and the call follows with the extension's answer.
 *
 * 586 (2026-11-08, arm64) splits that header (LX/09vG;->A1E, a render now): each part's logging
 * wrapper is a static method of its own holding the part's view tag (A09 builds and wraps the bar,
 * A07 wraps the send button it's handed), answering the part wrapped or as it came. The header adds
 * each answer to its row right after asking, through one call that still skips a null part
 * (LX/038y;->A3D), so the hook takes that call's place the same way.
 */

internal const val ELEVATED_CTA = "FbShortsSearchElevatedCTAComponent"
internal const val PIVOT_VIEW_TAG = "search_pivot_view_tag"
internal const val HIDE_SEARCH_PILL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideSearchPill()Z"
internal const val COMMENT_PIVOT = "fb_video_comment_search_pivot"
internal const val SEARCH_PROMPT_PARAM = "Lcom/facebook/ufiservices/flyout/params/SearchPromptParam;"
internal const val COMMENT_SEARCH_PROMPT =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->commentSearchPrompt(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val SEARCH_BAR = "fb_video_ghost_bar"
internal const val SEARCH_BAR_SEND = "fb_video_ghost_bar_send"
internal const val SEARCH_BAR_PART =
    "$EXTENSION_PACKAGE/reels/ReelDeclutter;->searchBarPart(Ljava/lang/Object;)Ljava/lang/Object;"

private const val PATCH = "Clean up Reels"

/**
 * Whether [classDef] is the pill component: its initializer names it and its render holds the view
 * tag, loaded itself or asked of a string table that [resolve] finds (585 moved the tag into one).
 */
internal fun isPillComponent(classDef: ClassDef, resolve: (MethodReference) -> Method? = { null }): Boolean =
    classDef.methods.any { it.name == "<clinit>" && namesString(it, ELEVATED_CTA, resolve) } &&
        classDef.methods.any { it.name == "render" && namesString(it, PIVOT_VIEW_TAG, resolve) }

/**
 * Facebook's empty answer in [builder]: the no-argument static call that ends it, as its last three
 * instructions (call, move-result-object, return-object). Null when the method doesn't end that way.
 */
internal fun emptyComponentCall(builder: Method): MethodReference? {
    val code = builder.implementation?.instructions?.toList() ?: return null
    if (code.size < 3) return null
    val (call, result, ret) = code.takeLast(3)
    if (call.opcode != Opcode.INVOKE_STATIC || result.opcode != Opcode.MOVE_RESULT_OBJECT || ret.opcode != Opcode.RETURN_OBJECT) return null
    val reference = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return reference.takeIf { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
}

/** Whether [method] is a static builder that does new-instance of [component] and ends in the empty answer. */
internal fun isPillBuilder(method: Method, component: String): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == component
        } == true && emptyComponentCall(method) != null

/** The invoke-static operand the hook writes for [this] call. */
internal fun MethodReference.asCall(): String = "$definingClass->$name()$returnType"

/**
 * Asks the extension first thing and answers Facebook's own empty component when it says so. At
 * the first instruction no local holds anything yet, so v0 is free there.
 */
internal fun MutableMethod.hidePillWhen(hook: String, empty: MethodReference) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_pill
            invoke-static { }, ${empty.asCall()}
            move-result-object v0
            return-object v0
        """,
        ExternalLabel("facebooks_pill", getInstruction(0)),
    )
}

/**
 * Finds the pill component by its kept literals, then the one builder of it, and has the builder
 * answer Facebook's empty component while the switch is on. Refuses unless each step finds one.
 */
internal fun BytecodePatchContext.hideSearchPill() {
    // The tag may sit in a string table, so the classes naming the component read the tables they call.
    val resolve = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val components = classDefByStrings(ELEVATED_CTA, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.filter { isPillComponent(it, resolve) }.distinctBy { it.type }
    val component = components.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one component naming $ELEVATED_CTA that renders $PIVOT_VIEW_TAG, found ${components.size}",
    )
    val builders = mutableListOf<Pair<String, Method>>()
    // A builder makes the component.
    classDefForEachReferencing(types = listOf(component.type)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE) || classDef.type == component.type) return@classDefForEachReferencing
        classDef.methods.filter { isPillBuilder(it, component.type) }.forEach { builders += classDef.type to it }
    }
    val (owner, builder) = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static builder of ${component.type} ending in an empty component, found ${builders.size}",
    )
    val empty = emptyComponentCall(builder)!!
    val method = mutableClassDefBy(owner).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
    }
    method.hidePillWhen(HIDE_SEARCH_PILL, empty)
}

/** Whether [method] is the static builder of the pill over a reel's comments: it holds [COMMENT_PIVOT]. */
internal fun isCommentPillBuilder(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") && holdsString(method, COMMENT_PIVOT)

/** Whether [this] instruction calls [target]. */
internal fun Instruction.isCallTo(target: Method): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.definingClass == target.definingClass && reference.name == target.name &&
        reference.returnType == target.returnType &&
        reference.parameterTypes.map(CharSequence::toString) == target.parameterTypes.map(CharSequence::toString)
}

/** Whether [method] calls [builder]. */
internal fun callsBuilder(method: Method, builder: Method): Boolean =
    method.implementation?.instructions?.any { it.isCallTo(builder) } == true

/** The indexes of [method]'s iget-object reads of a [SEARCH_PROMPT_PARAM] field. */
internal fun promptReads(method: Method): List<Int> =
    method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as? FieldReference)?.type == SEARCH_PROMPT_PARAM
    }?.map { it.index } ?: emptyList()

/**
 * Finds the pill builder over a reel's comments by its kept literal, then its one caller in the same
 * class, and puts the extension's answer in the register the caller reads the search prompt into.
 * Refuses unless each step finds exactly one.
 */
internal fun BytecodePatchContext.hideCommentSearchPill() {
    val builders = classDefByStrings(COMMENT_PIVOT, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter(::isCommentPillBuilder) }
    val builder = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static builder holding $COMMENT_PIVOT, found ${builders.size}",
    )
    val owner = mutableClassDefBy(builder.definingClass)
    val callers = owner.methods.filter { callsBuilder(it, builder) }
    val caller = callers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one caller of the comment pill builder in ${owner.type}, found ${callers.size}",
    )
    val reads = promptReads(caller)
    val index = reads.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one read of a search prompt in the comment sheet builder, found ${reads.size}",
    )
    val register = (caller.getInstruction(index) as TwoRegisterInstruction).registerA
    if (register > 255) throw PatchException("$PATCH: the search prompt sits in v$register, past what check-cast takes")
    caller.addInstructions(
        index + 1,
        """
            invoke-static/range { v$register .. v$register }, $COMMENT_SEARCH_PROMPT
            move-result-object v$register
            check-cast v$register, $SEARCH_PROMPT_PARAM
        """,
    )
}

/** Whether [method] is the static builder of a reel's header with the search bar: it holds both of the bar's view tags. */
internal fun isSearchBarHeader(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
        holdsString(method, SEARCH_BAR) && holdsString(method, SEARCH_BAR_SEND)

/**
 * The index of the call that puts the part [header] builds after [literal] into its row: the first
 * invoke-virtual past the literal, which has to take one argument of the header's own return type
 * and return nothing. Null when there's no such call.
 */
internal fun partAddAfter(header: Method, literal: String): Int? {
    val code = header.implementation?.instructions?.toList() ?: return null
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == literal }
    if (named < 0) return null
    val index = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.INVOKE_VIRTUAL } ?: return null
    val call = (code[index] as ReferenceInstruction).reference as MethodReference
    val takesAPart = call.returnType == "V" && call.parameterTypes.map(CharSequence::toString) == listOf(header.returnType)
    return index.takeIf { takesAPart }
}

/** Whether [method] wraps one part of the bar on its own (586): static, holding [literal] and answering an object. */
internal fun isSearchBarPartWrapper(method: Method, literal: String): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") && holdsString(method, literal)

/**
 * The index of the call that puts what [wrapper] answers into [caller]'s row: the one call to
 * [wrapper], its move-result-object, then an invoke-virtual taking that register alone, typed as
 * the wrapper's answer, and returning nothing. Null when [caller] isn't shaped that way.
 */
internal fun partAddAfterCall(caller: Method, wrapper: Method): Int? {
    val code = caller.implementation?.instructions?.toList() ?: return null
    val at = code.indices.singleOrNull { code[it].isCallTo(wrapper) } ?: return null
    val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction ?: return null
    val add = code.getOrNull(at + 2)?.takeIf { it.opcode == Opcode.INVOKE_VIRTUAL } as? FiveRegisterInstruction ?: return null
    val call = (add as ReferenceInstruction).reference as MethodReference
    val takesThePart = call.returnType == "V" && call.parameterTypes.map(CharSequence::toString) == listOf(wrapper.returnType) &&
        add.registerCount == 2 && add.registerD == result.registerA
    return (at + 2).takeIf { takesThePart }
}

/**
 * Has the call at [index] hand its part to the extension and add what comes back. The hook takes the
 * call's own place, so the jumps Facebook aims at the call reach the hook, and the call follows it.
 */
internal fun MutableMethod.handPartFirst(index: Int) {
    val add = getInstruction<FiveRegisterInstruction>(index)
    val call = (add as ReferenceInstruction).reference as MethodReference
    val (row, part) = add.registerC to add.registerD
    if (row > 15 || part > 15) throw PatchException("$PATCH: the row or the part sits in v$row or v$part, past what the hook's calls take")
    replaceInstruction(index, "invoke-static { v$part }, $SEARCH_BAR_PART")
    addInstructions(
        index + 1,
        """
            move-result-object v$part
            check-cast v$part, ${call.parameterTypes.single()}
            invoke-virtual { v$row, v$part }, $call
        """,
    )
}

/**
 * Finds the header builder by the bar's two view tags, then the call that adds each part, and has
 * each part go through the extension on its way in. Without a method holding both tags, takes
 * 586's shape ([hideSearchBarParts]). Refuses unless each step finds exactly one.
 */
internal fun BytecodePatchContext.hideSearchBar() {
    val headers = classDefByStrings(SEARCH_BAR_SEND, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { classDef -> classDef.methods.filter(::isSearchBarHeader) }
    if (headers.isEmpty()) return hideSearchBarParts()
    val header = headers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one header builder holding $SEARCH_BAR and $SEARCH_BAR_SEND, found ${headers.size}",
    )
    val method = mutableClassDefBy(header.definingClass).methods.single {
        it.name == header.name && it.returnType == header.returnType &&
            it.parameterTypes.map(CharSequence::toString) == header.parameterTypes.map(CharSequence::toString)
    }
    for (literal in listOf(SEARCH_BAR, SEARCH_BAR_SEND)) {
        val index = partAddAfter(method, literal) ?: throw PatchException(
            "$PATCH: the header builder adds nothing it built after $literal with one component call",
        )
        method.handPartFirst(index)
    }
}

/**
 * 586's header: finds each part's wrapper by its view tag, then the one method of their class that
 * asks both, and has each answer go through the extension on its way into the row. Refuses unless
 * each step finds exactly one.
 */
internal fun BytecodePatchContext.hideSearchBarParts() {
    val wrappers = listOf(SEARCH_BAR, SEARCH_BAR_SEND).map { literal ->
        val found = classDefByStrings(literal, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
            .flatMap { classDef -> classDef.methods.filter { isSearchBarPartWrapper(it, literal) } }
        found.singleOrNull() ?: throw PatchException(
            "$PATCH: no header builder holds $SEARCH_BAR and $SEARCH_BAR_SEND, and $literal is in ${found.size} static wrappers, not one",
        )
    }
    val owner = mutableClassDefBy(wrappers.first().definingClass)
    val callers = owner.methods.filter { method -> wrappers.all { callsBuilder(method, it) } }
    val caller = callers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method of ${owner.type} asking both of the bar's wrappers, found ${callers.size}",
    )
    for (wrapper in wrappers) {
        val index = partAddAfterCall(caller, wrapper) ?: throw PatchException(
            "$PATCH: the header doesn't add what ${wrapper.name} answers with one component call right after asking",
        )
        caller.handPartFirst(index)
    }
}
