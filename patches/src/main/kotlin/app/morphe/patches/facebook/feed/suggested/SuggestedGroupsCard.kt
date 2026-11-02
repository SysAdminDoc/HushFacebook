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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/*
 * The "Suggested for you" groups card with "Discover more groups" under it on the Groups tab (#347).
 * Read from 585, 2026-11-02; the obfuscated names are for reviewers, the code never writes one down.
 *
 * Home's copy is a GroupsYouShouldJoinFeedUnit edge the feed filter drops. The Groups tab builds its
 * feed without that funnel, and no "Feed edge" line shows up there with Debug logging on, so the card
 * stayed. Both draw it with GroupsYouShouldJoinComponent (LX/0N8B;), whose layout (A17) casts its unit
 * to GroupsYouShouldJoinFeedUnit, and that cast's Kotlin message is in no other method. The layout
 * answers null itself when its props say there's nothing to show (const/4 v4 0, then return-object v4
 * after a check), so the hook goes first and gives the same null while the switch is on.
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
 * The layouts outside the extension holding [GROUPS_CARD_CAST]: instance methods taking one
 * argument and answering an object, with a null answer of their own.
 */
internal fun BytecodePatchContext.groupsCardLayouts(): List<Method> =
    classDefByStrings(GROUPS_CARD_CAST, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, GROUPS_CARD_CAST) }
        .filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
                method.returnType.startsWith("L") && groupsCardNullAnswer(method) != null
        }

/**
 * First thing in the suggested groups card's layout: a yes from [HIDES_GROUPS_CARD] answers null,
 * the layout Facebook gives a card with nothing to show. Refuses unless one layout fits.
 */
internal fun BytecodePatchContext.hookSuggestedGroupsCard() {
    val layouts = groupsCardLayouts()
    val layout = layouts.singleOrNull()
        ?: throw PatchException("expected one layout casting to GroupsYouShouldJoinFeedUnit with a null answer, found ${layouts.size}")
    mutableClassDefBy(layout.definingClass).findMutableMethodOf(layout).apply {
        requireLocals(PATCH, 1)
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HIDES_GROUPS_CARD
                move-result v0
                if-eqz v0, :lay_out
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("lay_out", getInstruction(0)),
        )
    }
}
