/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The badges next to a commenter's name, Top fan, Subscriber and the like, read from 587 on
 * 2026-11-16 (#485). The obfuscated names are for reviewers; the code finds each spot by the names
 * Facebook keeps as strings.
 *
 * A comment's title row is drawn by plugins that a socket picks one by one. The socket keeps its
 * own name as a string ("CommentTitleSocket", 587 arm64 LX/0Cw1;->A08, armv7 LX/0CvT;->A08), loops
 * over plugin numbers, and for each one switches to an arm that loads the plugin's class name
 * (number 7, IdentityBadgeV2Plugin, the badge after the name) before asking a static check in the
 * same class, `(..., int) -> boolean`, with that number last (LX/0Cw1;->A0A). A no skips the plugin.
 * The older badge over the name has its own socket, "CommentMetaTitleSocket" (LX/0Cud;->A0I, armv7
 * LX/0Cu5;->A0I), whose number 0 is IdentityBadgePlugin, reached through an if-eqz on the number,
 * and its check is LX/0Cud;->A0M. The hook goes first in each check: on the badge's number it asks
 * the extension and answers no on a yes, so the socket leaves the badge out and draws the rest of
 * the row as before.
 */

/** The socket that draws the row with a commenter's name, by the name it keeps. */
internal const val TITLE_SOCKET = "CommentTitleSocket"

/** The badge after a commenter's name, Top fan or Subscriber, as the title socket names it. */
internal const val IDENTITY_BADGE = "com.facebook.feedback.comments.plugins.commenttitle.identitybadgev2.IdentityBadgeV2Plugin"

/** The socket that draws the line over a commenter's name, by the name it keeps. */
internal const val META_TITLE_SOCKET = "CommentMetaTitleSocket"

/** The older badge over a commenter's name, as that socket names it. */
internal const val META_IDENTITY_BADGE =
    "com.facebook.feedback.comments.plugins.commenttitle.metatitle.identitybadge.IdentityBadgePlugin"

/** Each socket with a badge, and the badge plugin it names. */
internal val COMMENTER_BADGES = listOf(TITLE_SOCKET to IDENTITY_BADGE, META_TITLE_SOCKET to META_IDENTITY_BADGE)

internal const val HIDES_COMMENTER_BADGE = "$COMMENT_SHEET_OPTIONS->hidesCommenterBadge()Z"

/** A socket's check of whether a plugin applies, and the number the socket gives the badge. */
internal class BadgeCheck(val check: Method, val number: Int)

private val ARM_ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)

/** The last register an invoke passes, in either form. */
private fun lastArgument(instruction: Instruction): Int? = when (instruction) {
    is RegisterRangeInstruction -> (instruction.startRegister + instruction.registerCount - 1).takeIf { instruction.registerCount > 0 }
    is FiveRegisterInstruction -> when (instruction.registerCount) {
        1 -> instruction.registerC
        2 -> instruction.registerD
        3 -> instruction.registerE
        4 -> instruction.registerF
        5 -> instruction.registerG
        else -> null
    }
    else -> null
}

/** Whether the straight run of [code] from [start], up to its first branch or exit, loads [string]. */
private fun armLoads(code: List<Instruction>, start: Int, string: String): Boolean {
    for (instruction in code.drop(start)) {
        if (((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == string) return true
        if (instruction is OffsetInstruction || instruction.opcode in ARM_ENDS) return false
    }
    return false
}

/**
 * The numbers a switch or an if-eqz on [register] in [method] sends to an arm loading [plugin]:
 * each switch key whose arm loads it, and 0 when an if-eqz's target does.
 */
private fun pluginNumbers(method: Method, register: Int, plugin: String): Set<Int> {
    val code = method.implementation?.instructions?.toList() ?: emptyList()
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    fun at(address: Int) = addresses.indexOf(address).takeIf { it in code.indices }
    val numbers = mutableSetOf<Int>()
    code.forEachIndexed { index, instruction ->
        if (instruction !is OneRegisterInstruction || instruction.registerA != register) return@forEachIndexed
        when (instruction.opcode) {
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                val payload = at(addresses[index] + (instruction as OffsetInstruction).codeOffset)?.let { code[it] }
                    as? SwitchPayload ?: return@forEachIndexed
                payload.switchElements.forEach { element ->
                    val arm = at(addresses[index] + element.offset)
                    if (arm != null && armLoads(code, arm, plugin)) numbers += element.key
                }
            }
            Opcode.IF_EQZ -> {
                val arm = at(addresses[index] + (instruction as OffsetInstruction).codeOffset)
                if (arm != null && armLoads(code, arm, plugin)) numbers += 0
            }
            else -> {}
        }
    }
    return numbers
}

/** Whether [check] opens on its last register with a switch holding [number], or an if-z when [number] is 0. */
private fun opensOn(check: Method, number: Int): Boolean {
    val last = check.implementation!!.registerCount - 1
    val opening = check.implementation!!.instructions.firstOrNull() as? OneRegisterInstruction ?: return false
    if (opening.registerA != last) return false
    return when (opening.opcode) {
        Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
            val code = check.implementation!!.instructions.toList()
            val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val payload = code.getOrNull(addresses.indexOf((opening as OffsetInstruction).codeOffset)) as? SwitchPayload
            payload?.switchElements?.any { it.key == number } == true
        }
        Opcode.IF_EQZ, Opcode.IF_NEZ -> number == 0
        else -> false
    }
}

/**
 * The check of a socket that draws a badge, from [holders], the classes loading [socket]: the one
 * method naming [socket] and [plugin], the one static `(..., int) -> boolean` method of its own
 * class it calls, always with the same register last, and the one number its switch or if-eqz on
 * that register sends to the arm loading [plugin]. The check has to open on that number. A build
 * where this moved keeps the rest of the patch, with that badge left showing and a warning saying
 * why.
 */
internal fun commenterBadgeCheck(holders: List<ClassDef>, socket: String, plugin: String): BadgeCheck? {
    fun skip(why: String): BadgeCheck? {
        patchLog.warning("Comment sheet options: $why. Hide badges like Top fan on comments leaves that badge in this build")
        return null
    }
    val renders = holders.flatMap { methodsHolding(it, socket) }.filter { holdsString(it, plugin) }.distinctBy { it.descriptor() }
    val render = renders.singleOrNull() ?: return skip("expected one method naming $socket and $plugin, found ${renders.size}")
    val owner = holders.first { it.type == render.definingClass }
    val code = render.implementation!!.instructions.toList()
    val calls = code.mapNotNull { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (called.definingClass != owner.type || called.returnType != "Z") return@mapNotNull null
        if (called.parameterTypes.lastOrNull()?.toString() != "I") return@mapNotNull null
        val method = owner.methods.singleOrNull { it.descriptor() == called.descriptor() } ?: return@mapNotNull null
        if (!AccessFlags.STATIC.isSet(method.accessFlags)) return@mapNotNull null
        method to lastArgument(instruction)
    }
    val check = calls.map { it.first }.distinctBy { it.descriptor() }.singleOrNull()
        ?: return skip("expected one plugin check called from ${render.descriptor()}, found ${calls.map { it.first.descriptor() }.distinct().size}")
    val register = calls.map { it.second }.distinct().singleOrNull()
        ?: return skip("${render.descriptor()} hands ${check.descriptor()} its number from more than one register")
    val numbers = pluginNumbers(render, register, plugin)
    val number = numbers.singleOrNull() ?: return skip("expected one number for $plugin in ${render.descriptor()}, found $numbers")
    if (number !in -128..127) return skip("$plugin's number $number doesn't fit the hook")
    if (check.localRegisterCount() < 1) return skip("${check.descriptor()} has no local register for the hook")
    if (check.implementation!!.registerCount > 256) return skip("${check.descriptor()}'s number sits past v255")
    if (!opensOn(check, number)) return skip("${check.descriptor()} doesn't open on the plugin's number")
    return BadgeCheck(check, number)
}

/** First thing in a badge socket's check: on the badge's [number], answer no while the extension says so. */
internal fun MutableMethod.holdCommenterBadge(number: Int) {
    val last = implementation!!.registerCount - 1
    addInstructionsWithLabels(
        0,
        """
            rsub-int/lit8 v0, v$last, $number
            if-nez v0, :check
            invoke-static {}, $HIDES_COMMENTER_BADGE
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}
