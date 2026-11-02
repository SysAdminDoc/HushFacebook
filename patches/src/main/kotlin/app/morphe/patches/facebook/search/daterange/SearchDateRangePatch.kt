/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.daterange

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.reels.categoryNames
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PATCH = "Search date range"

/** The mutation name the filter screen reports a tap on a filter with, held by the method that handles the tap. */
internal const val TAP_FILTER = "choiceActionSheetTapFilter"

private const val SEARCH_DATE_RANGE = "$EXTENSION_PACKAGE/search/SearchDateRange;"
internal const val KIND = "$SEARCH_DATE_RANGE->kind(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val BOTTOM_SHEET = "$SEARCH_DATE_RANGE->bottomSheet(Z)Z"
internal const val CALLBACK =
    "$SEARCH_DATE_RANGE->callback(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val DAY_ROW = "$SEARCH_DATE_RANGE->dayRow(Landroid/view/View;)Landroid/view/View;"
internal const val DAYS = "$SEARCH_DATE_RANGE->days(Ljava/lang/String;)Ljava/lang/String;"
internal const val LABEL = "$SEARCH_DATE_RANGE->label(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;"
internal const val STATE_LABEL =
    "$SEARCH_DATE_RANGE->stateLabel(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
internal const val KEPT_STATES = "$SEARCH_DATE_RANGE->keptStates(Ljava/util/Map;Ljava/util/List;)Ljava/util/List;"

/** The state Facebook keeps for each filter a search applies, built from the filter's name, label, value and key. */
internal const val FILTER_STATE = "Lcom/facebook/search/results/filters/state/FilterPersistentState;"

/** The extension's view of a [FILTER_STATE], which the patch makes it implement. */
internal const val STATE_BRIDGE = "$EXTENSION_PACKAGE/search/SearchDateRange\$FilterState;"
internal const val STATE_NAME = "hushfacebookFilterName"
internal const val STATE_VALUE = "hushfacebookFilterValue"

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
internal const val COPY_OF = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

/** The range picker screen. */
internal const val PICKER = "Lcom/facebook/search/results/filters/ui/home/SearchResultsDateRangePickerMenuFragment;"

/**
 * The text Facebook writes a range's months with, held by the method that sends the range. It goes
 * into the filter's args, a JSON string, so its quotes carry backslashes.
 */
internal const val RANGE_MONTHS = "\\\"start_month\\\":\\\"%d-%02d\\\",\\\"end_month\\\":\\\"%d-%02d\\\""

private const val STRING = "Ljava/lang/String;"

/** The kinds a filter declares, as the enum's constants are named; the kind class holds all of them. */
internal val FILTER_KINDS = listOf("DATE_RANGE_PICKER", "MULTI_CHOICE", "TIME_PICKER")

internal const val MOBILE_CONFIG = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"

private const val OBJECT = "Ljava/lang/Object;"

/** How far after the kind may Facebook read the bottom sheet flag. */
internal const val FLAG_REACH = 12

/**
 * Search date range (#279).
 *
 * The filter screen is built from the kind each filter declares (585 arm64 enum `LX/0D33`:
 * MULTI_CHOICE, TIME_PICKER, DATE_RANGE_PICKER, ...). Facebook already ships the from and to month
 * picker for DATE_RANGE_PICKER (SearchResultsDateRangePickerMenuFragment, which sends `creation_time`
 * with `start_month` and `end_month` as YYYY-MM), but the Posted date filter, named `rp_creation_time`,
 * declares a list of years. The tap on a filter is handled by one method holding [TAP_FILTER]: it
 * reads the filter's kind, reads a server flag, and with the flag off sends TIME_PICKER and
 * DATE_RANGE_PICKER to the fragment screens while everything else goes to a bottom sheet that can
 * only show lists. After the kind is read, the extension is handed the filter and the kind and its
 * answer goes back into the same register; after the flag is read, it is handed the flag and its
 * answer goes back into that register. For the Posted date filter with the switch on, the kind
 * answers DATE_RANGE_PICKER and the flag answers off, so Facebook opens its own range screen.
 *
 * The kind call is found by shape: an interface call with no arguments, answering the enum that
 * holds all of [FILTER_KINDS], with the flag, a static mobile config call answering a boolean, read
 * within [FLAG_REACH] instructions after it and tested with if-nez. In the default selection with
 * its switch off, so nothing changes until it's turned on.
 *
 * The search tabs' "Filter results" sheet (585 `LX/0k33`, "search_filter_drilldown_level") hands the
 * tap handler its own callback (`LX/0k6D;->A07`), which shows the filter's choices inside the sheet.
 * Facebook reads the kind once before that, and a tap with a callback goes to the callback and returns,
 * so the hooked read never runs. That test is the if-eqz jumping straight to the hooked read, and the
 * extension is handed the filter, that first kind and the callback before it. For the date filter
 * with the switch on it answers no callback, and the tap carries on to the picker.
 *
 * Days: the picker's onCreateView hands back its LithoView, and the extension wraps it under a row
 * of From day and To day wheels. Its Apply (585 `LX/0kz3;->onClick`, one case of a shared click
 * handler) writes the months with [RANGE_MONTHS] and then the chip label, built by a static call
 * joining five Strings from the two ends a DateFormat wrote. After the months text lands, the
 * extension is handed it and adds `start_day` and `end_day`; after the label lands, it is handed
 * the label and that DateFormat and writes the ends as days.
 *
 * Kept: that label goes into a [FILTER_STATE] (585 `LX/0krc;->A02`), which Facebook keeps in a map of
 * each filter's states (585 `LX/0D0D;->A0A`) that the filter sheet's rows and the next search read.
 * The server's answer to a search (585 `LX/0DBA;->onSuccess`) clears that map and fills it from the
 * states it brings (585 `LX/0D0D;->A05`), and it never brings a range back, so the Date posted row
 * read "Any date" and a later filter change searched without the range. That method is found by
 * shape: it takes one ImmutableList, clears a HashMap of its own class first, casts to the state and
 * keys each state by its key or name field. First thing, the map and the incoming list go to the
 * extension, and a list it answers is copied into an ImmutableList and replaces the incoming one.
 * The extension tells a range from the state through [STATE_BRIDGE], which the state is made to
 * implement with two methods answering its name and value fields. Should the server ever send a
 * range back, it would come labelled with the server's text, so the state's constructor from four
 * Strings hands the name, label and value to the extension first thing too, and its answer
 * becomes the label.
 */
@Suppress("unused")
val searchDateRangePatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Search date range",
    description = "Adds a from and to date picker to the Date posted filter in post search, in place of the list of " +
        "years, so you can narrow results to a few days or a stretch of months. Starts off. Turn it on in Hushfacebook " +
        "settings > Search.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("searchDateRange")
        val holders = classDefByStrings(TAP_FILTER, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val found = kindReads(holders)
        val (tap, read) = found.singleOrNull()
            ?: throw PatchException(
                "$PATCH: expected one method holding \"$TAP_FILTER\" that reads a filter's kind and then the bottom " +
                    "sheet flag, found ${found.size}: ${found.joinToString { it.first.descriptor() }}",
            )
        val sheet = callbackTest(tap, read)
            ?: throw PatchException(
                "$PATCH: ${tap.descriptor()} has no test of the filter sheet's own callback jumping to its kind read",
            )
        val view = classDefByOrNull(PICKER)?.methods?.singleOrNull(::isPickerView)
            ?.let { picker -> pickerReturns(picker)?.let { picker to it } }
            ?: throw PatchException("$PATCH: $PICKER has no onCreateView handing back a view it can wrap")
        val writes = classDefByStrings(RANGE_MONTHS, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { holder -> methodsHolding(holder, RANGE_MONTHS) }
            .mapNotNull { sender -> rangeWrite(sender)?.let { sender to it } }
        val (sender, write) = writes.singleOrNull()
            ?: throw PatchException(
                "$PATCH: expected one method writing a range's months and then its label, found ${writes.size}: " +
                    writes.joinToString { it.first.descriptor() },
            )
        val state = classDefByOrNull(FILTER_STATE)?.methods?.singleOrNull(::isStateConstructor)
            ?: throw PatchException("$PATCH: $FILTER_STATE has no constructor from four Strings")
        val fields = stateFields(state)
            ?: throw PatchException("$PATCH: ${state.descriptor()} doesn't keep its name, label and value in fields")
        val resets = mutableListOf<Pair<Method, FieldReference>>()
        classDefForEachReferencing(types = listOf(FILTER_STATE)) { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
            classDef.methods.forEach { method -> statesReset(method, fields)?.let { resets += method to it } }
        }
        val (reset, kept) = resets.singleOrNull()
            ?: throw PatchException(
                "$PATCH: expected one method filling the filter states from the server's answer, found ${resets.size}: " +
                    resets.joinToString { it.first.descriptor() },
            )
        classDefByOrNull(IMMUTABLE_LIST)?.methods?.singleOrNull { it.descriptor() == COPY_OF && AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: throw PatchException("$PATCH: $IMMUTABLE_LIST has no static copyOf from a Collection")
        val method = mutableClassDefBy(tap.definingClass).methods.single { it.descriptor() == tap.descriptor() }
        // The last first: each hook moves every later instruction down.
        method.hookFlag(read)
        method.hookKind(read)
        method.hookCallback(sheet)
        mutableClassDefBy(view.first.definingClass).methods.single { it.descriptor() == view.first.descriptor() }
            .hookPickerView(view.second)
        mutableClassDefBy(sender.definingClass).methods.single { it.descriptor() == sender.descriptor() }
            .hookRangeWrite(write)
        mutableClassDefBy(FILTER_STATE).methods.single { it.descriptor() == state.descriptor() }.hookStateLabel()
        bridgeFilterState(fields)
        mutableClassDefBy(reset.definingClass).methods.single { it.descriptor() == reset.descriptor() }.hookStatesReset(kept)
        enableStatus("searchDateRange")
    }
}

/** Where a tap handler reads a filter's kind and then the bottom sheet flag. */
internal class KindRead(
    /** The index of the interface call that reads the kind. */
    val call: Int,
    /** The register the filter is in at that call. */
    val filter: Int,
    /** The register the kind lands in. */
    val kind: Int,
    /** The kind's enum type. */
    val kindType: String,
    /** The index of the flag's move-result. */
    val flagResult: Int,
    /** The register the flag lands in. */
    val flag: Int,
)

/**
 * Each method of [holders] holding [TAP_FILTER], with where it reads a filter's kind and then the flag.
 * A type Facebook doesn't define, such as String, is looked up as no class: as a patch class it threw
 * and stopped patching.
 */
internal fun BytecodePatchContext.kindReads(holders: List<ClassDef>) =
    holders.flatMap { holder -> methodsHolding(holder, TAP_FILTER) }
        .mapNotNull { method -> kindRead(method) { type -> classDefByOrNull(type) }?.let { method to it } }

/**
 * The one place [method] reads a filter's kind and then the flag, or null when there isn't exactly
 * one. [classOf] looks a class up by type.
 */
internal fun kindRead(method: Method, classOf: (String) -> ClassDef?): KindRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val reads = code.indices.mapNotNull { index ->
        val call = code[index]
        if (call.opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        val reference = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (reference.parameterTypes.isNotEmpty() || !reference.returnType.startsWith("L")) return@mapNotNull null
        val landing = code.getOrNull(index + 1)
        if (landing?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val kindClass = classOf(reference.returnType) ?: return@mapNotNull null
        if (!categoryNames(kindClass).containsAll(FILTER_KINDS)) return@mapNotNull null
        val flagCall = (index + 2..minOf(code.size - 3, index + FLAG_REACH)).firstOrNull { at ->
            val candidate = code[at]
            if (candidate.opcode != Opcode.INVOKE_STATIC) return@firstOrNull false
            val target = (candidate as ReferenceInstruction).reference as? MethodReference ?: return@firstOrNull false
            target.definingClass == MOBILE_CONFIG && target.returnType == "Z" &&
                target.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, "J") &&
                code[at + 1].opcode == Opcode.MOVE_RESULT && code[at + 2].opcode == Opcode.IF_NEZ &&
                (code[at + 1] as OneRegisterInstruction).registerA == (code[at + 2] as OneRegisterInstruction).registerA
        } ?: return@mapNotNull null
        val filter = (call as FiveRegisterInstruction).registerC
        val kind = (landing as OneRegisterInstruction).registerA
        val flag = (code[flagCall + 1] as OneRegisterInstruction).registerA
        if (maxOf(filter, kind, flag) > 15) return@mapNotNull null
        KindRead(index, filter, kind, reference.returnType, flagCall + 1, flag)
    }
    return reads.singleOrNull()
}

/** Where a tap handler tests for a filter sheet's own callback, which skips the kind read when there is one. */
internal class CallbackTest(
    /** The index of the if-eqz on the callback; with none it jumps to the kind read. */
    val test: Int,
    /** The register the filter is in. */
    val filter: Int,
    /** The register Facebook's first read of the kind landed in. */
    val kind: Int,
    /** The register the callback is in. */
    val callback: Int,
    /** The callback's type, as the field it was read from declares it. */
    val callbackType: String,
)

/**
 * The one if-eqz in [method] that jumps straight to [read]'s kind call, right after the same kind call
 * on the same filter, its move-result and its if-eqz, or null. The register it tests was last loaded
 * from an instance field, whose type the hook casts back to.
 */
internal fun callbackTest(method: Method, read: KindRead): CallbackTest? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val address = IntArray(code.size + 1)
    for (at in code.indices) address[at + 1] = address[at] + code[at].codeUnits
    val kindCall = (code[read.call] as ReferenceInstruction).reference
    val tests = (3 until read.call).mapNotNull { at ->
        val test = code[at]
        if (test.opcode != Opcode.IF_EQZ || address[at] + (test as OffsetInstruction).codeOffset != address[read.call]) {
            return@mapNotNull null
        }
        val first = code[at - 3]
        val landing = code[at - 2]
        val kindTest = code[at - 1]
        if (first.opcode != Opcode.INVOKE_INTERFACE || (first as ReferenceInstruction).reference != kindCall) return@mapNotNull null
        if ((first as FiveRegisterInstruction).registerC != read.filter) return@mapNotNull null
        if (landing.opcode != Opcode.MOVE_RESULT_OBJECT || kindTest.opcode != Opcode.IF_EQZ) return@mapNotNull null
        val kind = (landing as OneRegisterInstruction).registerA
        if ((kindTest as OneRegisterInstruction).registerA != kind) return@mapNotNull null
        val callback = (test as OneRegisterInstruction).registerA
        val load = (at - 4 downTo 0).firstOrNull { code[it].opcode == Opcode.IGET_OBJECT &&
            (code[it] as OneRegisterInstruction).registerA == callback } ?: return@mapNotNull null
        val field = (code[load] as ReferenceInstruction).reference as? FieldReference ?: return@mapNotNull null
        if (maxOf(read.filter, kind, callback) > 15 || callback == kind || callback == read.filter) return@mapNotNull null
        CallbackTest(at, read.filter, kind, callback, field.type)
    }
    return tests.singleOrNull()
}

/** Before the callback is tested, the extension is handed the filter, the first kind and the callback, and its answer goes back. */
internal fun MutableMethod.hookCallback(test: CallbackTest) {
    addInstructions(
        test.test,
        """
            invoke-static { v${test.filter}, v${test.kind}, v${test.callback} }, $CALLBACK
            move-result-object v${test.callback}
            check-cast v${test.callback}, ${test.callbackType}
        """,
    )
}

/** After the flag is read, the extension is handed it and its answer goes back into the register. */
internal fun MutableMethod.hookFlag(read: KindRead) {
    addInstructions(
        read.flagResult + 1,
        """
            invoke-static { v${read.flag} }, $BOTTOM_SHEET
            move-result v${read.flag}
        """,
    )
}

/** Whether [method] is the picker's onCreateView. */
internal fun isPickerView(method: Method) = method.name == "onCreateView" && method.returnType == "Landroid/view/View;" &&
    method.parameterTypes.map(CharSequence::toString) ==
    listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;", "Landroid/os/Bundle;")

/**
 * The indexes of [method]'s return-object instructions, or null when it has none, one hands back a
 * register past v15, or one is a branch target, which a hook put before it would be jumped past.
 */
internal fun pickerReturns(method: Method): List<Int>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val address = IntArray(code.size + 1)
    for (at in code.indices) address[at + 1] = address[at] + code[at].codeUnits
    val targets = code.indices.mapNotNull { at -> (code[at] as? OffsetInstruction)?.let { address[at] + it.codeOffset } }.toSet()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty() || returns.any { (code[it] as OneRegisterInstruction).registerA > 15 || address[it] in targets }) {
        return null
    }
    return returns
}

/** Before each return of the picker's view, the extension is handed the view and its answer is returned instead. */
internal fun MutableMethod.hookPickerView(returns: List<Int>) {
    val code = implementation!!.instructions.toList()
    for (at in returns.sortedDescending()) {
        val view = (code[at] as OneRegisterInstruction).registerA
        addInstructions(
            at,
            """
                invoke-static { v$view }, $DAY_ROW
                move-result-object v$view
            """,
        )
    }
}

/** Where a range's Apply writes its months and then its chip label. */
internal class RangeWrite(
    /** The index of the move-result taking the months text. */
    val monthsResult: Int,
    /** The register the months text lands in. */
    val months: Int,
    /** The index of the move-result taking the label. */
    val labelResult: Int,
    /** The register the label lands in. */
    val label: Int,
    /** The register holding the DateFormat the label's two ends were written with. */
    val format: Int,
)

/**
 * In [method], the [RANGE_MONTHS] text formatted by the static call right after it and taken by a
 * move-result-object, then the first static call joining five Strings into one, taken the same way,
 * with the DateFormat of the last format(Date) call before it still in its register. Null when any
 * part is missing or a register is past v15.
 */
internal fun rangeWrite(method: Method): RangeWrite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val text = code.indices.singleOrNull { at ->
        (code[at].opcode == Opcode.CONST_STRING || code[at].opcode == Opcode.CONST_STRING_JUMBO) &&
            ((code[at] as ReferenceInstruction).reference as? StringReference)?.string == RANGE_MONTHS
    } ?: return null
    val formatted = code.getOrNull(text + 1) ?: return null
    val formatter = (formatted as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (formatted.opcode != Opcode.INVOKE_STATIC || formatter.returnType != STRING ||
        (formatted as FiveRegisterInstruction).registerC != (code[text] as OneRegisterInstruction).registerA
    ) {
        return null
    }
    val monthsResult = text + 2
    if (code.getOrNull(monthsResult)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val months = (code[monthsResult] as OneRegisterInstruction).registerA
    val join = (monthsResult + 1 until code.size - 1).firstOrNull { at ->
        val call = code[at]
        val target = (call as? ReferenceInstruction)?.reference as? MethodReference
        call.opcode == Opcode.INVOKE_STATIC && target != null && target.returnType == STRING &&
            target.parameterTypes.size == 5 && target.parameterTypes.all { it.toString() == STRING }
    } ?: return null
    val labelResult = join + 1
    if (code[labelResult].opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val label = (code[labelResult] as OneRegisterInstruction).registerA
    val formatCall = (join - 1 downTo monthsResult).firstOrNull { at ->
        val target = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && target != null && target.definingClass == "Ljava/text/DateFormat;" &&
            target.name == "format" && target.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Date;")
    } ?: return null
    val format = (code[formatCall] as FiveRegisterInstruction).registerC
    // Nothing between that call and the label may write over the format's register.
    val overwritten = (formatCall + 1..labelResult).any { at ->
        val written = code[at] as? OneRegisterInstruction
        written != null && written.registerA == format && code[at].opcode in WRITES
    }
    if (overwritten || maxOf(months, label, format) > 15 || format == label) return null
    return RangeWrite(monthsResult, months, labelResult, label, format)
}

/** The opcodes that load a register a OneRegisterInstruction names. */
private val WRITES = setOf(
    Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE, Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO,
    Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16, Opcode.CONST_CLASS, Opcode.NEW_INSTANCE,
)

/**
 * After the label lands, the extension is handed it and the DateFormat; after the months text lands,
 * the extension is handed it. Each answer goes back into the same register.
 */
internal fun MutableMethod.hookRangeWrite(write: RangeWrite) {
    addInstructions(
        write.labelResult + 1,
        """
            invoke-static { v${write.label}, v${write.format} }, $LABEL
            move-result-object v${write.label}
        """,
    )
    addInstructions(
        write.monthsResult + 1,
        """
            invoke-static { v${write.months} }, $DAYS
            move-result-object v${write.months}
        """,
    )
}

/** After the kind is read, the extension is handed the filter and the kind and its answer goes back into the kind's register. */
internal fun MutableMethod.hookKind(read: KindRead) {
    addInstructions(
        read.call + 2,
        """
            invoke-static { v${read.filter}, v${read.kind} }, $KIND
            move-result-object v${read.kind}
            check-cast v${read.kind}, ${read.kindType}
        """,
    )
}

/** The [FILTER_STATE] fields its constructor keeps the filter's name, label, value and key in. */
internal class StateFields(
    val name: FieldReference,
    val label: FieldReference,
    val value: FieldReference,
    val key: FieldReference,
)

/**
 * The fields [constructor] stores its four Strings in, each a String field of [FILTER_STATE]
 * written from that parameter onto this, or null when one isn't exactly one field.
 */
internal fun stateFields(constructor: Method): StateFields? {
    val implementation = constructor.implementation ?: return null
    val code = implementation.instructions.toList()
    val self = implementation.registerCount - 5
    fun kept(parameter: Int) = code.filter { instruction ->
        instruction.opcode == Opcode.IPUT_OBJECT && (instruction as TwoRegisterInstruction).registerA == self + parameter &&
            instruction.registerB == self
    }.mapNotNull { ((it as ReferenceInstruction).reference as? FieldReference)?.takeIf { field ->
        field.definingClass == FILTER_STATE && field.type == STRING
    } }.singleOrNull()
    return StateFields(kept(1) ?: return null, kept(2) ?: return null, kept(3) ?: return null, kept(4) ?: return null)
}

/**
 * The HashMap field the filter states are kept in, when [method] is where the server's answer
 * fills it: not static, answering nothing, taking one ImmutableList, clearing that field of its own
 * class right after loading it, casting to [FILTER_STATE] and reading the state's key and name
 * fields. Null for anything else, or when the incoming list's register is out of reach of the
 * hook's invoke.
 */
internal fun statesReset(method: Method, fields: StateFields): FieldReference? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return null
    if (method.parameterTypes.map(CharSequence::toString) != listOf(IMMUTABLE_LIST)) return null
    val implementation = method.implementation ?: return null
    // The hook borrows v0 and passes it with the list, which sits in the last register.
    if (implementation.registerCount !in 3..16) return null
    val code = implementation.instructions.toList()
    val self = implementation.registerCount - 2
    val cleared = code.zipWithNext().firstNotNullOfOrNull { (load, clear) ->
        val field = (load as? ReferenceInstruction)?.reference as? FieldReference
        val call = (clear as? ReferenceInstruction)?.reference as? MethodReference
        field?.takeIf {
            load.opcode == Opcode.IGET_OBJECT && (load as TwoRegisterInstruction).registerB == self &&
                it.definingClass == method.definingClass && it.type == "Ljava/util/HashMap;" &&
                clear.opcode == Opcode.INVOKE_VIRTUAL && call?.name == "clear" &&
                (clear as FiveRegisterInstruction).registerC == load.registerA
        }
    } ?: return null
    fun reads(field: FieldReference) = code.any { instruction ->
        val read = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IGET_OBJECT && read != null && read.definingClass == FILTER_STATE &&
            read.name == field.name && read.type == field.type
    }
    val casts = code.any { instruction ->
        instruction.opcode == Opcode.CHECK_CAST && (instruction as ReferenceInstruction).reference.toString() == FILTER_STATE
    }
    return if (casts && reads(fields.key) && reads(fields.name)) cleared else null
}

private fun FieldReference.smali() = "$definingClass->$name:$type"

/**
 * First thing, the kept states and the incoming list go to the extension; a list it answers
 * becomes the incoming one, and with none Facebook's list goes on as it came.
 */
internal fun MutableMethod.hookStatesReset(kept: FieldReference) {
    addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${kept.smali()}
            invoke-static { v0, p1 }, $KEPT_STATES
            move-result-object v0
            if-eqz v0, :facebook
            invoke-static { v0 }, $COPY_OF
            move-result-object p1
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/** Makes [FILTER_STATE] a [STATE_BRIDGE], its two methods answering the state's name and value fields. */
internal fun BytecodePatchContext.bridgeFilterState(fields: StateFields) {
    val state = mutableClassDefBy(FILTER_STATE)
    if (STATE_BRIDGE !in state.interfaces) state.interfaces.add(STATE_BRIDGE)
    listOf(STATE_NAME to fields.name, STATE_VALUE to fields.value).forEach { (name, field) ->
        if (state.methods.any { it.name == name }) return@forEach
        state.methods.add(
            ImmutableMethod(
                FILTER_STATE, name, emptyList(), STRING, AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, ${field.smali()}
                        return-object v0
                    """,
                )
            },
        )
    }
}

/** The [FILTER_STATE] constructor taking the filter's name, label, value and key. */
internal fun isStateConstructor(method: Method) =
    method.name == "<init>" && method.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING, STRING, STRING)

/** First thing, the name, label and value go to the extension, and its answer becomes the label. */
internal fun MutableMethod.hookStateLabel() {
    addInstructions(
        0,
        """
            invoke-static { p1, p2, p3 }, $STATE_LABEL
            move-result-object p2
        """,
    )
}
