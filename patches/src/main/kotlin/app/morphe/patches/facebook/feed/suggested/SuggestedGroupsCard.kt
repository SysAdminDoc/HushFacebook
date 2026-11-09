/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/*
 * The "Suggested for you" groups card with "Discover more groups" under it on the Groups tab (#347).
 * Read from 585, 2026-11-02; the obfuscated names are for reviewers, the code never writes one down.
 *
 * Home's copy is a GroupsYouShouldJoinFeedUnit edge the feed filter drops. The Groups tab builds its
 * feed without that funnel, and no "Feed edge" line shows up there with Debug logging on, so the card
 * stayed. Both draw it with GroupsYouShouldJoinComponent (LX/0N8B;), whose layout (A17) casts its unit
 * to GroupsYouShouldJoinFeedUnit, and that cast's Kotlin message is in no other method (586 drops the
 * message, so its layout, LX/0TIb;->A1E, is found by the component's GROUPS_CARD_SPEC name). The layout
 * answers null itself when its props say there's nothing to show (const/4 v4 0, then return-object v4
 * after a check), so the hook goes first and gives the same null while the switch is on.
 *
 * The Groups tab also closes its feed with an ENGAGE_END_OF_FEED block (LX/0k8w;, read 2026-11-06),
 * and that block draws its "Suggested for you" row with a family of its own that never reaches
 * GroupsYouShouldJoinComponent: GroupsTabDiscoverSuggestionHScroll (LX/0k3c;, the header with its X,
 * the row of groups and the link under it) and GroupsTabDiscoverSuggestionRowUnitComponent (LX/0k2l;,
 * one suggested group on its own). Both are Litho renders that answer null when there's nothing to
 * show, so each gets the same guard, asking hideDiscoverRow so a diagnostic tells the two paths apart.
 * The block's scroll section only ever picks the row on that surface, so the list variant
 * (GroupsTabDiscoverSuggestionListSection) is left alone.
 */

private const val PATCH = "Hide suggested and promoted posts"

/** The message of the layout's cast of its unit, which only the suggested groups card's layout holds. */
internal const val GROUPS_CARD_CAST =
    "null cannot be cast to non-null type com.facebook.graphql.model.GroupsYouShouldJoinFeedUnit"

internal const val HIDES_GROUPS_CARD = "$EXTENSION_PACKAGE/feed/SuggestedGroupsCard;->hide()Z"

private val BRANCHES = setOf(
    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
    Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
)

/**
 * The index of the `return-object` in [method] that answers null on Facebook's own: a `const/4` of 0
 * into a register, then only branches, then that register returned. Null when there's none, which
 * would mean null was never a layout this method gives.
 */
internal fun groupsCardNullAnswer(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    for (index in code.indices) {
        val zero = code[index]
        if (zero.opcode != Opcode.CONST_4 || (zero as NarrowLiteralInstruction).narrowLiteral != 0) continue
        val register = (zero as OneRegisterInstruction).registerA
        val returned = (index + 1 until minOf(code.size, index + 4)).firstOrNull { code[it].opcode == Opcode.RETURN_OBJECT }
            ?: continue
        if ((code[returned] as OneRegisterInstruction).registerA != register) continue
        if ((index + 1 until returned).all { code[it].opcode in BRANCHES }) return returned
    }
    return null
}

/**
 * The name the card's component gives its CallerContext in its static initializer. 586 strips the
 * cast's Kotlin message and tests the unit through a merged instance-of helper
 * (`LX/04Ah;->A00(ILjava/lang/Object;)Z` on arm64), so the component's own name is what's left.
 */
internal const val GROUPS_CARD_SPEC = "GroupsYouShouldJoinComponentSpec"

/**
 * The layouts outside the extension holding [GROUPS_CARD_CAST], or on a class naming itself
 * [GROUPS_CARD_SPEC]: instance methods taking one argument and answering an object, with a null
 * answer of their own.
 */
internal fun BytecodePatchContext.groupsCardLayouts(): List<Method> {
    fun layout(method: Method) = !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
        method.returnType.startsWith("L") && groupsCardNullAnswer(method) != null
    val casting = classDefByStrings(GROUPS_CARD_CAST, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, GROUPS_CARD_CAST) }
    val named = classDefByStrings(GROUPS_CARD_SPEC, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { it.methods }
    return (casting + named).filter(::layout).distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}${it.returnType}" }
}

/**
 * First thing in the suggested groups card's layout: a yes from [HIDES_GROUPS_CARD] answers null,
 * the layout Facebook gives a card with nothing to show. Refuses unless one layout fits.
 */
internal fun BytecodePatchContext.hookSuggestedGroupsCard() {
    val layouts = groupsCardLayouts()
    val layout = layouts.singleOrNull()
        ?: throw PatchException("expected one layout casting to GroupsYouShouldJoinFeedUnit with a null answer, found ${layouts.size}")
    answerNullOnYes(layout, HIDES_GROUPS_CARD)
}

/** The names the Groups tab's own suggestion layouts give themselves, one layout each. */
internal val GROUPS_TAB_SUGGESTIONS = listOf(
    "GroupsTabDiscoverSuggestionHScroll",
    "GroupsTabDiscoverSuggestionRowUnitComponentSpec",
)

internal const val HIDES_GROUPS_TAB_SUGGESTIONS = "$EXTENSION_PACKAGE/feed/SuggestedGroupsCard;->hideDiscoverRow()Z"

private val ZERO_CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

private fun Instruction.writes(register: Int): Boolean {
    if (this !is OneRegisterInstruction) return false
    return (opcode.setsRegister() && registerA == register) || (opcode.setsWideRegister() && registerA + 1 == register)
}

/**
 * The register [method] returns null in on its own: one a `return-object` hands back whose last
 * write in the method is a constant 0. Null when there's none, which would mean null was never a
 * layout this method gives.
 */
internal fun nullAnswerRegister(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.filter { it.opcode == Opcode.RETURN_OBJECT }
        .map { (it as OneRegisterInstruction).registerA }
        .distinct()
        .firstOrNull { register ->
            val last = code.lastOrNull { it.writes(register) }
            last != null && last.opcode in ZERO_CONSTANTS && (last as NarrowLiteralInstruction).narrowLiteral == 0
        }
}

/**
 * The layouts outside the extension naming themselves [name]: instance methods taking one argument
 * and answering an object, with a null answer of their own.
 */
internal fun BytecodePatchContext.groupsTabSuggestionLayouts(name: String): List<Method> =
    classDefByStrings(name, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, name) }
        .filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
                method.returnType.startsWith("L") && nullAnswerRegister(method) != null
        }

/**
 * First thing in each of the Groups tab's own suggestion layouts: a yes from
 * [HIDES_GROUPS_TAB_SUGGESTIONS] answers null. Hooks every one it finds once, then refuses naming
 * any it couldn't.
 */
internal fun BytecodePatchContext.hookGroupsTabSuggestions() {
    val missing = GROUPS_TAB_SUGGESTIONS.filter { name ->
        val layout = groupsTabSuggestionLayouts(name).singleOrNull() ?: return@filter true
        answerNullOnYes(layout, HIDES_GROUPS_TAB_SUGGESTIONS)
        false
    }
    if (missing.isNotEmpty()) {
        throw PatchException("expected one layout with a null answer naming each of ${missing.joinToString()}")
    }
}

private fun BytecodePatchContext.answerNullOnYes(layout: Method, hook: String) {
    mutableClassDefBy(layout.definingClass).findMutableMethodOf(layout).apply {
        requireLocals(PATCH, 1)
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $hook
                move-result v0
                if-eqz v0, :lay_out
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("lay_out", getInstruction(0)),
        )
    }
}
