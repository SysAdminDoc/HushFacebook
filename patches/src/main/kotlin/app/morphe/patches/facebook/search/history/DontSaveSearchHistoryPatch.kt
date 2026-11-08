/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.history

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.analytics.analyticsEventsPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Don't save search history"

/** The name of the mutation that adds a search to your history, held by the method that sends it. */
internal const val ADD_RECENT_SEARCH = "FB4ASearchTypeaheadAddRecentSearch"

private const val STRING = "Ljava/lang/String;"

internal const val SEARCH_HISTORY = "$EXTENSION_PACKAGE/search/SearchHistory;"
internal const val SKIP_SAVE = "$SEARCH_HISTORY->skipSave(Z)Z"
internal const val KEEP_RECENT = "$SEARCH_HISTORY->keepRecent(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)V"

/** What the extension's analytics event question asks Don't save search history about each event. */
internal const val KEEP_VISIT_OUT = "$SEARCH_HISTORY->keepVisitOut(Ljava/lang/String;)Z"

/** The analytics event a profile sends as it opens, which Facebook turns into "You visited". */
internal const val PROFILE_VISIT = "profile_engagement"

private const val MAP = "Ljava/util/Map;"
private const val MAP_PUT = "$MAP->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/** How far before the put the load of its map may sit. */
internal const val MAP_LOAD_REACH = 4

/**
 * Don't save search history (#196).
 *
 * A search goes into your history through one method, the one holding [ADD_RECENT_SEARCH] (584
 * arm64 `LX/9Ng;->A03(GraphSearchQuery, 7cK, 9Ni, String, String, String, Z, Z)String`). It answers
 * with a String and takes two booleans last, and the first of them is Facebook's own "don't save
 * this one": on the new path it skips the mutation and answers null, and on the older path, which
 * also keeps the search in the app's local list, it skips that list's logging and then the mutation
 * the same way. The search bar's typeahead and the keyword search both call it, and some of their
 * callers already pass true. First thing in it, the extension is handed that boolean and its answer
 * goes back into the same register, so with the switch on every search is one Facebook doesn't save.
 *
 * The older path puts the search at the top of the Recent list first, a map field of the same
 * class that the search screen reads until Facebook restarts, and it does that whatever the boolean
 * says (S22, 583, 2026-10-20: a kept-out search led Recent until a force-stop). That one put goes
 * to the extension instead, with the same registers, which leaves the list as it was while the
 * switch is on. A build without that put still gets the history kept clean, and the patch log says
 * Recent may show a search until a restart.
 *
 * A page or profile opened from a search lands in the same history as "You visited", and no search
 * call makes that entry: Facebook builds it from the [PROFILE_VISIT] analytics event the profile
 * sends as it opens (#221, 584 on an emulator, 2026-10-23: dropping that one event kept the visit
 * out, dropping every search event didn't). The event carries no sign of where the profile was
 * opened from, so it's kept out wherever it's sent while the switch is on. Every analytics event is
 * made by one factory, which [analyticsEventsPatch] asks the extension about first thing, and a
 * true sends Facebook's own "not logged" event back, which the profile's code already handles. A
 * build without that factory still keeps searches out, and the patch log says a visit may still show.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val dontSaveSearchHistoryPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Don't save search history",
    description = "Stops Facebook from adding what you search for, and the pages and profiles you open from a " +
        "search, to your search history, so there's nothing to clear by hand. Searches already there stay until " +
        "you delete them. Starts off. Turn it on in Hushfacebook settings > Search.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch, analyticsEventsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("searchHistory")
        val holders = classDefByStrings(ADD_RECENT_SEARCH, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val save = recentSearchSave(holders)
        val skip = skipRegister(save)
        val recentPut = recentListPut(save)

        val method = mutableClassDefBy(save.definingClass).methods.single { it.descriptor() == save.descriptor() }
        // The put first: asking first moves every later instruction down two.
        if (recentPut != null) {
            method.keepRecentAt(recentPut)
        } else {
            patchLog.warning(
                "$PATCH: ${save.descriptor()} has no one put of the Recent list in a map of its own class, so a " +
                    "search can still show in Recent until Facebook restarts. Your history is still kept clean.",
            )
        }
        method.askFirst(skip)
        enableStatus("searchHistory")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The method among [holders] that adds a search to your history: the one holding
 * [ADD_RECENT_SEARCH] that answers with a String and takes two booleans last.
 */
internal fun recentSearchSave(holders: List<ClassDef>): Method {
    val found = holders.flatMap { methodsHolding(it, ADD_RECENT_SEARCH) }
        .filter { method ->
            method.returnType == STRING && method.parameterTypes.size >= 2 &&
                method.parameterTypes.takeLast(2).map(CharSequence::toString) == listOf("Z", "Z")
        }
        .distinctBy { it.descriptor() }
    return found.singleOrNull()
        ?: refuse(
            "expected one method holding \"$ADD_RECENT_SEARCH\" that answers with a String and takes two booleans " +
                "last, found ${found.size}: ${found.joinToString { it.descriptor() }}",
        )
}

private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

/**
 * The register [save]'s first of its two last booleans arrives in, Facebook's "don't save this
 * one". The parameters fill the last registers, after `this`, so the first one sits as far from
 * the end as the parameters are wide. Refused unless [save] tests it with `if-nez` and never writes
 * it, so every test sees what the caller passed.
 */
internal fun skipRegister(save: Method): Int {
    val types = save.parameterTypes.map(CharSequence::toString)
    val code = save.implementation ?: refuse("${save.descriptor()} has no code")
    val register = code.registerCount - types.sumOf(::width) + types.dropLast(2).sumOf(::width)
    val instructions = code.instructions.toList()
    if (instructions.none { it.opcode == Opcode.IF_NEZ && (it as OneRegisterInstruction).registerA == register }) {
        refuse("${save.descriptor()} never tests v$register, its first of two last booleans, with if-nez")
    }
    val written = instructions.any { instruction ->
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: return@any false
        instruction.opcode.setsRegister() &&
            (target == register || (instruction.opcode.setsWideRegister() && target + 1 == register))
    }
    if (written) refuse("${save.descriptor()} writes v$register, so a test there may not see what the caller passed")
    return register
}

/**
 * Where [save] puts the Recent list into its own class's map: the one `Map.put` whose map register
 * was last loaded, at most [MAP_LOAD_REACH] instructions before, from a Map field of [save]'s class,
 * with no jump or handler landing in between, and whose answer nothing reads. Null unless there's
 * exactly one.
 */
internal fun recentListPut(save: Method): Int? {
    val instructions = save.implementation?.instructions?.toList() ?: return null
    val flow = ControlFlow.of(save)
    val landings = instructions.indices.flatMap { from -> flow.normal[from].filter { it != from + 1 } + flow.exceptional[from] }.toSet()
    val puts = instructions.indices.filter { index ->
        val put = instructions[index]
        if (put.opcode != Opcode.INVOKE_INTERFACE && put.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@filter false
        if ((put as ReferenceInstruction).reference.toString() != MAP_PUT) return@filter false
        if (instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) return@filter false
        val map = callRegisters(put).first()
        val load = (index - 1 downTo maxOf(0, index - MAP_LOAD_REACH)).firstOrNull { before ->
            val instruction = instructions[before]
            val target = (instruction as? OneRegisterInstruction)?.registerA
            instruction.opcode.setsRegister() && target != null &&
                (target == map || (instruction.opcode.setsWideRegister() && target + 1 == map))
        } ?: return@filter false
        // Code reaching the put by a jump past the load may hold another map in that register.
        if ((load + 1..index).any { it in landings }) return@filter false
        val field = (instructions[load] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[load].opcode == Opcode.IGET_OBJECT && field != null &&
            field.definingClass == save.definingClass && field.type == MAP
    }
    return puts.singleOrNull()
}

/** The registers a call hands over, in order, for both call forms. */
internal fun callRegisters(call: Instruction): List<Int> = when (call) {
    is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
    is FiveRegisterInstruction ->
        listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    else -> emptyList()
}

/**
 * The Recent list's put at [index] goes to the extension instead, with the same three registers
 * in the same call form, since only a range call reaches past v15.
 */
internal fun MutableMethod.keepRecentAt(index: Int) {
    val put = getInstruction(index)
    val registers = callRegisters(put)
    if (registers.size != 3) refuse("the Recent list's put hands ${registers.size} registers, not 3")
    val call = if (put is RegisterRangeInstruction) {
        "invoke-static/range { v${registers.first()} .. v${registers.last()} }"
    } else {
        "invoke-static { ${registers.joinToString { "v$it" }} }"
    }
    replaceInstruction(index, "$call, $KEEP_RECENT")
}

/**
 * First thing in the method that saves a search: hand the extension Facebook's "don't save this
 * one" in [register] and keep its answer there. The call takes it as a range, so any register
 * number works.
 */
internal fun MutableMethod.askFirst(register: Int) {
    addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $SKIP_SAVE
            move-result v$register
        """,
    )
}
