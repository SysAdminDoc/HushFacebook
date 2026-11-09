/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.quality

import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Default playback quality hooks, found by kept names only (read from 577 and 580, 2026-09-29).
 * The obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * - Facebook's quality menu ("Quality settings" in a reel's menu, the gear menu's quality sheet)
 *   calls the playing FbGrootPlayer's setter with the picked label, AUTO or one of the video's own
 *   labels such as 720p. It goes through the Hero player and HeroManager to the one method holding
 *   "HeroServicePlayer.setCustomQualityInternal" (580 LX/6l6;->A16, 577 LX/7sb;->A12), which hands
 *   it to its DASH format evaluator's custom-quality setter, the one (String)V call there (580
 *   LX/4UV;->A06, 577 LX/503;->A05). The evaluator's constructor takes an
 *   AbrContextAwareConfiguration, a kept name.
 * - The setter looks for the label among the evaluator's tracks, its one array field (580 A0J of
 *   LX/7sk;, 577 A0J of LX/7qV;), reading each track's label through the one static method taking a
 *   track (580 LX/7sj;->A00, 577 LX/7qU;->A00) and that answer's String field (A0A on both), and
 *   keeps the matching track's id, or none, which is what AUTO or a missing label comes to. The
 *   evaluator's choice of a track then plays that track while it has one, and chooses by bandwidth
 *   while it hasn't.
 * - The same choice, the first time it runs, hands the setter the evaluator's preselected label, the
 *   String field it reads right before calling it (A0K on both), then sets that field to null. The
 *   constructor fills it in from the play request's preselected label, which on both builds comes
 *   from VideoPlayerParams' preselectedVideoQualityLabel, and nothing in Facebook sets that. The
 *   menu's own pick is stored in FbSharedPreferences under
 *   quality_selector/user_selected_quality_label_key_new, but only the menu reads it back, to tick
 *   that row, so a pick stays with the video it was made on.
 * - Prefetching and offline saves use the evaluator's other choosers, which never read the
 *   preselected label, so only playback sees what the patch leaves there.
 * - Where a video plays (read from 577, 580 and 581, 2026-10-07): the evaluator keeps the
 *   AbrContextAwareConfiguration it's built with in its one field of that type (A06 on all three),
 *   stored by its constructor before the hook runs. That configuration's kept playbackPreferences
 *   (581 LX/5A5;, 580 LX/4UK;, 577 LX/4zu;) carries the player origin and sub-origin Facebook
 *   names each player with, and the configuration's own constructor reads both to decide isStory:
 *   the origin is the String it compares with "fb_stories" (A07 on 581 and 580, A06 on 577), the
 *   sub-origin the one it compares with "fb_shorts_viewer" and "fb_shorts_native_in_feed_unit"
 *   (A08 on 581 and 580, A07 on 577). The extension sorts reels and stories by those two names.
 *   582 (read 2026-10-10) keeps the shape, playbackPreferences LX/3wX; with the origin in A07 and
 *   the sub-origin in A08, but asks a string table (arm64 LX/192;->A00) for both sub-origin names.
 * - 585 (read 2026-10-29, arm64 and v7a) inlines the custom-quality setter into the method holding
 *   "HeroServicePlayer.setCustomQualityInternal" (LX/04RP;->A1V on arm64), so the evaluator (LX/05R1;)
 *   is found from the track array that method reads. The label getter is a static call taking a track
 *   and answering the String itself, with no info class between. The first choice (Bfa) reads the
 *   preselected label (A0M) and compares it with the tracks' labels through String.equals, so the
 *   hook sits right after that read instead of in a setter.
 * - 586 (read 2026-11-08, arm64 and v7a) calls the setter again (LX/05wF;->A1T calls LX/05gB;->A05),
 *   so the tracks and the label getter are read in that setter, which again reads the label through
 *   an info class (LX/08yb;->A00, then A0A). The first choice (Bhh) reads the preselected label (A0M)
 *   twice, once for its null check and once more right before handing it to the setter, so the hook
 *   sits after the second read, as on 585, and the setter itself stays as it is.
 */

internal const val QUALITY_CHOICE = "$EXTENSION_PACKAGE/media/QualityChoice;"
internal const val EVALUATOR_BUILT = "$QUALITY_CHOICE->evaluatorBuilt(Ljava/lang/Object;)V"
internal const val CUSTOM_QUALITY = "$QUALITY_CHOICE->customQuality(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"
internal const val PRESELECTED_STUB = "preselectedLabel"
internal const val PRESELECT_STUB = "preselectLabel"
internal const val FORMATS_STUB = "trackFormats"
internal const val LABEL_STUB = "formatLabel"
internal const val ORIGIN_STUB = "playOrigin"
internal const val SUB_ORIGIN_STUB = "playSubOrigin"

internal const val SET_CUSTOM_QUALITY = "HeroServicePlayer.setCustomQualityInternal"
internal const val ABR_CONFIGURATION = "Lcom/facebook/exoplayer/formatevaluator/configuration/AbrContextAwareConfiguration;"
internal const val PLAYBACK_PREFERENCES = "playbackPreferences"

/** What the configuration compares the player origin with to call a video a story. */
internal const val STORIES_ORIGIN = "fb_stories"

/** What the configuration compares the player sub-origin with to call a video a reel. */
internal const val REELS_SUB_ORIGIN = "fb_shorts_viewer"
private const val STRING = "Ljava/lang/String;"

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.field: FieldReference?
    get() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

/**
 * The array fields [method] reads, each once: in the method holding the custom-quality trace, the
 * evaluator's tracks. 585 inlined the evaluator's custom-quality setter into that method.
 */
internal fun trackArrayReads(method: Method): List<FieldReference> = method.code()
    .filter { it.opcode == Opcode.IGET_OBJECT }
    .mapNotNull { it.field }
    .filter { it.type.startsWith("[L") }
    .distinctBy { it.toString() }

/** Whether [evaluator]'s constructor takes an AbrContextAwareConfiguration. */
internal fun takesAbrConfiguration(evaluator: ClassDef): Boolean = evaluator.methods.any { method ->
    method.name == "<init>" && method.parameterTypes.any { it.toString() == ABR_CONFIGURATION }
}

/**
 * The custom-quality setters [method] calls: each (String)V virtual call to a class [lookup] finds
 * and that is built with an AbrContextAwareConfiguration, as that class's own method. One on 586,
 * where the method holding the custom-quality trace calls the evaluator's setter; none on 585,
 * which inlined it there.
 */
internal fun customQualitySetters(method: Method, lookup: (String) -> ClassDef?): List<Method> =
    method.code()
        .filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        .mapNotNull { it.call?.takeIf { call -> call.parameters() == listOf(STRING) && call.returnType == "V" } }
        .distinctBy { it.toString() }
        .mapNotNull { call ->
            val owner = lookup(call.definingClass)?.takeIf(::takesAbrConfiguration) ?: return@mapNotNull null
            owner.methods.singleOrNull {
                it.name == call.name && it.parameters() == call.parameters() && it.returnType == "V" && it.implementation != null
            }
        }

/** The registers a call hands over, its receiver first. */
private fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether [instruction] calls [target], by class, name and parameters. */
private fun calls(instruction: Instruction, target: MethodReference): Boolean = instruction.call?.let {
    it.definingClass == target.definingClass && it.name == target.name && it.parameters() == target.parameters()
} == true

/**
 * How [method] reads a track's label: a static call taking one [format] that answers the String
 * itself (the field is null, 585), or an object with a String field read right after (580 to 584).
 * One pair per place it does.
 */
internal fun labelReads(method: Method, format: String): List<Pair<MethodReference, FieldReference?>> {
    val code = method.code()
    return code.indices.mapNotNull { index ->
        val call = code[index].call?.takeIf {
            code[index].opcode == Opcode.INVOKE_STATIC && it.parameters() == listOf(format) && it.returnType.startsWith("L")
        } ?: return@mapNotNull null
        if (call.returnType == STRING) return@mapNotNull call to null
        val read = code.drop(index + 1).take(3).firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.field
            ?.takeIf { it.definingClass == call.returnType && it.type == STRING } ?: return@mapNotNull null
        call to read
    }
}

/** A read of the preselected label in [method] at [index], right before the first choice compares it with the tracks' labels. */
internal class PreselectedRead(val method: Method, val index: Int, val field: FieldReference)

/**
 * The String fields of [evaluator] that its constructor fills in and one of its own methods reads
 * into a register it then compares, with String.equals, to a track's label (a call to [labelOf]),
 * or hands, on the evaluator it read it from, to [setter], the custom-quality setter that compares
 * it: the preselected label its first choice looks for among the tracks. 585 inlined the setter
 * there, 586 calls it.
 */
internal fun preselectedReads(
    evaluator: ClassDef,
    constructor: Method,
    labelOf: MethodReference,
    setter: MethodReference? = null,
): List<PreselectedRead> {
    val filled = constructor.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.field }
        .filter { it.definingClass == evaluator.type && it.type == STRING }.map { it.name }.toSet()
    return evaluator.methods.filter { it.name != "<init>" && it.implementation != null }.flatMap { method ->
        val code = method.code()
        val readsLabels = code.any { instruction ->
            (instruction.opcode == Opcode.INVOKE_STATIC && calls(instruction, labelOf)) || (setter != null && calls(instruction, setter))
        }
        if (!readsLabels) return@flatMap emptyList()
        code.indices.mapNotNull { index ->
            val field = code[index].takeIf { it.opcode == Opcode.IGET_OBJECT }?.field
                ?.takeIf { it.definingClass == evaluator.type && it.type == STRING && it.name in filled } ?: return@mapNotNull null
            val (label, owner) = (code[index] as TwoRegisterInstruction).let { it.registerA to it.registerB }
            val window = code.drop(index + 1).take(14)
            val compare = window.indexOfFirst {
                (it.opcode == Opcode.INVOKE_VIRTUAL && it.call?.let { call -> call.definingClass == STRING && call.name == "equals" } == true &&
                    (it as FiveRegisterInstruction).registerC == label) ||
                    (setter != null && calls(it, setter) && it.callRegisters() == listOf(owner, label))
            }
            if (compare < 0 || window.take(compare).any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == label }) {
                return@mapNotNull null
            }
            PreselectedRead(method, index, field)
        }
    }
}

/**
 * The String fields of [preferences] that [configuration]'s constructors read and compare, ignoring
 * case, with [literal]: the player origin for "fb_stories", the sub-origin for "fb_shorts_viewer".
 * The literal is a const-string, or the answer of a string table [resolve] finds, moved into a
 * register: 582 asks a table for both sub-origin names ([tableStringAt]). The field is the last read
 * into the register the comparison takes besides the literal's.
 */
internal fun originReads(
    configuration: ClassDef,
    preferences: String,
    literal: String,
    resolve: (MethodReference) -> Method?,
): List<FieldReference> =
    configuration.methods.filter { it.name == "<init>" }.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { index ->
            val string = ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string
                ?: code[index].takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { tableStringAt(code, index - 1, resolve) }
            if (string != literal) return@mapNotNull null
            val literalRegister = (code[index] as OneRegisterInstruction).registerA
            val compare = code.drop(index + 1).take(3).firstOrNull {
                it.call?.let { call -> call.definingClass == STRING && call.name == "equalsIgnoreCase" } == true
            } as? FiveRegisterInstruction ?: return@mapNotNull null
            val other = listOf(compare.registerC, compare.registerD).filter { it != literalRegister }.singleOrNull()
                ?: return@mapNotNull null
            val read = code.subList(0, index).lastOrNull {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == other
            }?.takeIf { it.opcode == Opcode.IGET_OBJECT } ?: return@mapNotNull null
            read.field?.takeIf { it.definingClass == preferences && it.type == STRING }
        }
    }.distinctBy { it.toString() }
