/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postdates

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import app.morphe.util.firstAfterRewrite
import app.morphe.util.firstHolding
import app.morphe.util.rewrittenReads
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Keep post dates"

/** What the post header's subtitle logs its choice of the rotating subtitle under, right after it makes it. */
internal const val CYCLING_LOG = "in_cycling_experiment"

/** A second name the same render logs, on the rotating subtitle's path. Together they name one method. */
internal const val CYCLING_COUNT_LOG = "subtitle_cycling_text_count"

internal const val POST_DATES = "Lapp/morphe/extension/facebook/feed/PostDates;"
internal const val CYCLING = "$POST_DATES->cycling(Z)Z"

/** How the log writes the choice: String.valueOf of the boolean it just got. */
internal const val VALUE_OF_BOOLEAN = "Ljava/lang/String;->valueOf(Z)Ljava/lang/String;"

/**
 * The line under the poster's name keeps the post's date (issue #40). Facebook's newer post header
 * (580 `LX/2wH;->render`, 577 `LX/312;->render`, both naming themselves FDSPostHeaderSubtitle) can
 * draw that line two ways. Most posts get one line, the date and who can see the post. With
 * `in_cycling_experiment` on, a post whose subtitle plugins gave it several texts gets a component
 * that rotates them instead, fitted into what's left of a width measured beforehand, and on the
 * reporter's phone the line went blank half a second after the date showed, with Hushfacebook
 * paused too. The render works the choice out, logs it as `in_cycling_experiment`, and only then
 * branches on it. Right after the call that answers it, the answer goes through the extension,
 * which turns a yes into a no while the switch is on, so the header takes its one-line path.
 */
@Suppress("unused")
val keepPostDatesPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep post dates",
    description = "Keeps the date under the poster's name, so you can always tell how old a post is. Facebook's " +
        "newer header can swap it for rotating details, and on some phones the line goes blank. On by default. " +
        "Turn it off in Hushfacebook settings > News feed. The same page can leave the song out of that line " +
        "and make the time bold.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val choice = findCyclingChoice()
        mutableClassDefBy(choice.method.definingClass).findMutableMethodOf(choice.method).hookCyclingChoice(choice)
        addSubtitlePieces { patchLog.warning(it) }
        enableStatus("postDates")
    }
}

internal fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Hands the choice to the extension at the log's own label, so a path that jumps to the log goes
 * through the hook too, and puts the extension's answer back in the register the branch reads. A
 * range call takes any register, where a plain one would need it to fit in four bits.
 */
internal fun MutableMethod.hookCyclingChoice(choice: CyclingChoice) {
    addInstructionsAtControlFlowLabel(
        choice.logIndex,
        """
            invoke-static/range { v${choice.register} .. v${choice.register} }, $CYCLING
            move-result v${choice.register}
        """,
    )
}

/** The post header's choice of the rotating subtitle: its method, where the log loads its name, and the choice's register. */
internal class CyclingChoice(val method: Method, val logIndex: Int, val register: Int)

private fun stringOf(instruction: Instruction): String? =
    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * Reads the choice out of [method], which loads [CYCLING_LOG] and [CYCLING_COUNT_LOG]. Refuses
 * unless the log's name is loaded once, right after a call's boolean answer and right before
 * String.valueOf of that same register (a plain call while the register fits in four bits, a range
 * call past them), and unless some way from the log with nothing thrown reaches an if-eqz on that
 * register and, on every way, into the handlers of what can throw too, the first one reads the
 * answer itself rather than a value written over it: the branch between the rotating subtitle and
 * the one line.
 */
internal fun cyclingChoice(method: Method): CyclingChoice {
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val logs = code.indices.filter { stringOf(code[it]) == CYCLING_LOG }
    val index = logs.singleOrNull() ?: refuse("expected one load of \"$CYCLING_LOG\" in $where, found ${logs.size}")
    val answer = code.getOrNull(index - 1)
    if (answer?.opcode != Opcode.MOVE_RESULT) {
        refuse("\"$CYCLING_LOG\" in $where doesn't come right after a call's boolean answer")
    }
    val register = (answer as OneRegisterInstruction).registerA
    val written = code.getOrNull(index + 1)
    val writesTheAnswer = when (written?.opcode) {
        Opcode.INVOKE_STATIC -> (written as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == register }
        Opcode.INVOKE_STATIC_RANGE -> (written as RegisterRangeInstruction).let { it.registerCount == 1 && it.startRegister == register }
        else -> false
    }
    if (!writesTheAnswer || (written as ReferenceInstruction).reference.toString() != VALUE_OF_BOOLEAN) {
        refuse("the log of \"$CYCLING_LOG\" in $where doesn't write v$register")
    }
    // On every way from the log, the first branch on the register has to read this answer. One
    // after something wrote it again tests another value, and a hook there would only change what
    // the log says. A branch past the answer's own decides something else: 577 reuses v12 as an
    // iterator further on. Both walks go into a handler only from what can throw, so a branch in a
    // handler nothing can reach counts for neither. The branch between the two subtitles has to be
    // on a way the header takes when nothing throws, so one that only a handler leads to isn't it.
    fun branchesOn(instruction: Instruction) =
        instruction.opcode == Opcode.IF_EQZ && (instruction as OneRegisterInstruction).registerA == register
    if (method.firstHolding(index - 1, ::branchesOn, handlers = false).isEmpty()) {
        if (method.firstHolding(index - 1, ::branchesOn).isNotEmpty()) {
            refuse("in $where the log of \"$CYCLING_LOG\" leads to a branch on v$register only through a catch handler")
        }
        if (method.rewrittenReads(index - 1).any { branchesOn(code[it]) }) {
            refuse("v$register is written again in $where before the branch on it after the log of \"$CYCLING_LOG\"")
        }
        refuse("nothing in $where branches on v$register after the log of \"$CYCLING_LOG\"")
    }
    val unanswered = method.firstAfterRewrite(index - 1, ::branchesOn)
    if (unanswered.isNotEmpty()) {
        refuse("in $where some ways from the log of \"$CYCLING_LOG\" write v$register again before the branch on it at $unanswered")
    }
    return CyclingChoice(method, index, register)
}

/** The one method that loads both [CYCLING_LOG] and [CYCLING_COUNT_LOG], read as the choice. Changes nothing. */
internal fun BytecodePatchContext.findCyclingChoice(): CyclingChoice {
    val methods = classDefByStrings(CYCLING_LOG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter { holdsString(it, CYCLING_LOG) && holdsString(it, CYCLING_COUNT_LOG) } }
    val method = methods.singleOrNull()
        ?: refuse("expected one method loading \"$CYCLING_LOG\" and \"$CYCLING_COUNT_LOG\", found ${methods.size}")
    return cyclingChoice(method)
}
