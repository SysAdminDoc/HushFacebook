/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where the clean-up switches of Facebook's own Chats hook, on 582. Chats is
 * `messaginginblue`'s InboxActivity, the same list the Get Messenger card sits in (see
 * MessengerCardAnchors.kt).
 *
 * The row of tiles above the chats, friends' notes and who's active now, lives in one immutable
 * state object that Chats rebuilds on every result: a note result, an active-now result, a story
 * result. Its constructor takes the builder, null-checks the builder's tile list with the Kotlin
 * parameter name `activeNowTiles` (kept, because the check carries it as a literal) and stores the
 * list. The component that draws the row and the section builder both read
 * that field, so the patch hands the constructor an empty list while the switch is on and nothing
 * downstream has a tile to draw. That empties the list the row is built from: friends' notes and
 * who's active now. Your own "Your note" tile isn't in that list (seen on the 582 emulator): on 583
 * the section merges it back in after the state is built, so emptying the list alone left the row
 * with that one tile at full height. The state class and its builder are Redex names (`EB8` and
 * `EB7` on 582, renamed every build); only the literal and the constructor's shape find them.
 *
 * So the section that draws the row is gated too. Its render names itself `ActiveNowSection` and
 * keys its tiles `active_now_tiles_key`, and when the server's thresholds aren't met, or the row is
 * set to hide while it has no tiles, it takes one early way out that builds an empty result and
 * returns it. The patch jumps to that same way out first thing while the switch is on, so the
 * whole row goes, your own note and the row's height with it, the way Facebook drops it itself.
 *
 * Chats also builds a group of top banners, each its own plugin answering a show question. Two of
 * them are promotions: the one asking you to turn on notifications (its builder tags the banner
 * `mib-notification-push-upsell-banner`) and the progressive diode card (`inbox_progressive_diode_top_view`).
 * Each tag is loaded by one builder in one plugin class, which also carries the show question the
 * Get Messenger card hook uses. The patch has each of them answer no while the switch is on, and
 * the list takes the next banner that says yes, as it does without them.
 *
 * The search row at the top of the chat list holds the search box and, when Facebook's server says
 * so, a Meta AI button. On 582 one component builds both: it tags the search box
 * `search_bar_text_input`, then builds the button with a tap action that opens Meta AI from the
 * inbox (the action reports `meta_ai_icon_clicked` and calls the kept MetaAIThreadViewLauncher),
 * checks the built button for null and adds it to the row's children. The patch hands that null
 * check nothing while the switch is on, so the row is built with the search box alone.
 *
 * Tapping the search box opens Chats' search screen (the people picker), whose row has a Meta AI
 * button of its own, built differently: the row sets a flag to 1 when it's in search mode and
 * nothing is typed, tests it once to shorten the search box for the button and again to build the
 * button. Its tap action opens Meta AI with the entry `messaging_inbox_in_blue:people_picker`
 * right after reading the row's own CallerContext, which names the row's class. The patch hands
 * the flag to the extension right after it's set, so with the switch on neither test sees it.
 *
 * What 585 looks like: the notes row's empty way out gets its object from a no-argument static
 * factory (arm64 `LX/0Duf;->A0a()` in `LX/0G5V;->A0o`, v7a `LX/0G5S;->A0o`) instead of
 * `new-instance` plus `<init>()`. The search screen's Meta AI tap is case 0xe of a merged lambda
 * (arm64 `LX/0b3G;->invoke`, v7a `LX/0b2w;->invoke`, switching on its `$t` int) that asks the
 * string table for the entry and reads no CallerContext; the case's only static read before the
 * entry is the row's AtomicBoolean (`LX/0YjZ;->A0G`), and the row's render (`LX/0YjZ;->render`)
 * builds that lambda with the literal 0xe behind the same flag (v24, 0 then 1, tested twice).
 *
 * Not hooked, and why: Messenger's inbox ad filter and quick-promotion kill switches are literals
 * that don't exist in Facebook's dex, so there is no inbox ad or quick-promotion plugin to anchor,
 * and no literal ties Meta AI's chat list entry or the AI sticker tab to one method.
 */
internal const val CHAT_LIST_PATCH = "Clean up Facebook's chat list"

/** The Kotlin parameter name the tile state's constructor checks its list against. */
internal const val NOTES_TILES = "activeNowTiles"

/** The tags the two promotion banners' builders load. */
internal const val PUSH_UPSELL_TAG = "mib-notification-push-upsell-banner"
internal const val DIODE_TAG = "inbox_progressive_diode_top_view"
internal val PROMOTION_TAGS = listOf(PUSH_UPSELL_TAG, DIODE_TAG)

internal const val IMMUTABLE_LIST_TYPE = "Lcom/google/common/collect/ImmutableList;"

/** Where the tile list is stored: the constructor, the iput-object's index and the register holding the list. */
internal class NotesTrayStore(val method: Method, val store: Int, val list: Int)

/**
 * The tile state's constructor, when [method] is it: a constructor of one parameter that loads
 * `activeNowTiles`, passes a register and that string to a call, and then stores that register
 * into an ImmutableList field of its own class. Null for any other method.
 */
internal fun notesTrayStore(owner: ClassDef, method: Method): NotesTrayStore? {
    if (method.name != "<init>" || method.parameterTypes.size != 1 || !holdsString(method, NOTES_TILES)) return null
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val load = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NOTES_TILES
    }
    if (load < 0) return null
    val text = (code[load] as OneRegisterInstruction).registerA
    val check = (load + 1 until code.size).firstOrNull { at ->
        val call = code[at] as? FiveRegisterInstruction
        call != null && call.opcode == Opcode.INVOKE_STATIC && call.registerCount == 2 && call.registerD == text
    } ?: return null
    val list = (code[check] as FiveRegisterInstruction).registerC
    val store = (check + 1 until code.size).firstOrNull { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field != null && field.definingClass == owner.type &&
            field.type == IMMUTABLE_LIST_TYPE && (code[at] as OneRegisterInstruction).registerA == list
    } ?: return null
    return NotesTrayStore(method, store, list)
}

/** What the search row's Meta AI tap reports. */
internal const val META_AI_ICON_CLICKED = "meta_ai_icon_clicked"

/** The tag the search row gives its search box. */
internal const val SEARCH_BAR_TAG = "search_bar_text_input"

/** Kept name. What every Meta AI chat opens through. */
internal const val META_AI_LAUNCHER = "Lcom/facebook/messaginginblue/aibot/launcher/MetaAIThreadViewLauncher;"

/** Whether [owner] opens Meta AI from the search row: one of its methods reports the tap and calls the launcher. */
internal fun opensMetaAiFromSearchRow(owner: ClassDef): Boolean = owner.methods.any { method ->
    holdsString(method, META_AI_ICON_CLICKED) && method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == META_AI_LAUNCHER
    } == true
}

/**
 * Where the search row's builder gets its Meta AI button: the `move-result-object` at [result] in
 * [method] puts the built button in [register], as the builder's own return type [type].
 *
 * On 584 Redex outlined the build, the null check and the add into one static [helper]
 * (`LX/19e;->A1D` on arm64). Then [result] is the row's call to it, [register] holds the button's
 * builder it's handed, and [answer] is a local free across that call for the hook's answer.
 */
internal class MetaAiButtonSite(
    val method: Method,
    val result: Int,
    val register: Int,
    val type: String,
    val helper: Method? = null,
    val answer: Int? = null,
)

/** What the search row's button build takes: nothing, or on 583's 32-bit build the row's CallerContext. */
private val BUILD_PARAMETERS = listOf(emptyList<String>(), listOf(CALLER_CONTEXT))

/**
 * The Meta AI button site of [method] when it's the search row's builder: it tags its search box
 * [SEARCH_BAR_TAG] and creates one tap action of the [openers] types. The first build after that,
 * a call taking nothing (or a CallerContext) and answering the builder's own return type, puts the button in a
 * register that is checked for null and then added to the row's children. On 584 that first build
 * is a call to the static helper [outlinedBuild] finds through [resolve], handed the button's
 * builder. Null for any other method.
 */
internal fun metaAiButtonSite(
    method: Method,
    openers: Set<String>,
    resolve: (MethodReference) -> Method? = { null },
): MetaAiButtonSite? {
    if (!holdsString(method, SEARCH_BAR_TAG)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val created = code.indices.filter { at ->
        code[at].opcode == Opcode.NEW_INSTANCE && ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type in openers
    }
    if (created.size != 1) return null
    val build = (created.single() + 1 until code.size).firstOrNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.returnType == method.returnType &&
            call.parameterTypes.map { it.toString() } in BUILD_PARAMETERS ||
            outlinedBuild(code[at], method.returnType, resolve) != null
    } ?: return null
    outlinedBuild(code[build], method.returnType, resolve)?.let { helper ->
        val builder = (code[build] as FiveRegisterInstruction).registerD
        val answer = method.freeLocalsAt(CHAT_LIST_PATCH, build, 1, listOf(build + 1), highest = 255).single()
        return MetaAiButtonSite(method, build, builder, method.returnType, helper, answer)
    }
    val result = code.getOrNull(build + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val register = (result as OneRegisterInstruction).registerA
    val check = code.getOrNull(build + 2)
    if (check?.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != register) return null
    val add = code.getOrNull(build + 3)
    val adds = (add as? ReferenceInstruction)?.reference as? MethodReference
    if (add !is FiveRegisterInstruction || adds?.name != "add" || !adds.definingClass.startsWith("Ljava/util/") ||
        add.registerCount != 2 || add.registerD != register) return null
    return MetaAiButtonSite(method, build + 1, register, method.returnType)
}

/**
 * The static helper [instruction] calls, found through [resolve], when it's 584's outlined button
 * build: a void over a CallerContext, a builder and a `java.util` collection whose whole body
 * builds the builder's [returnType] with that CallerContext, checks it for null and adds it to the
 * collection. Null for any other instruction.
 */
private fun outlinedBuild(instruction: Instruction, returnType: String, resolve: (MethodReference) -> Method?): Method? {
    if (instruction.opcode != Opcode.INVOKE_STATIC) return null
    val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
    val types = call.parameterTypes.map { it.toString() }
    if (call.returnType != "V" || types.size != 3 || types[0] != CALLER_CONTEXT || !types[2].startsWith("Ljava/util/")) {
        return null
    }
    val helper = resolve(call) ?: return null
    val body = helper.implementation?.instructions?.toList() ?: return null
    if (body.size != 5) return null
    // A static method's three parameters are its last three registers.
    val context = helper.implementation!!.registerCount - 3
    val build = body[0]
    val builds = (build as? ReferenceInstruction)?.reference as? MethodReference
    if (build.opcode != Opcode.INVOKE_VIRTUAL || builds == null || builds.returnType != returnType ||
        builds.parameterTypes.map { it.toString() } != listOf(CALLER_CONTEXT) ||
        (build as FiveRegisterInstruction).registerCount != 2 || build.registerC != context + 1 || build.registerD != context
    ) return null
    val result = body[1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val button = (result as OneRegisterInstruction).registerA
    val check = body[2]
    val add = body[3]
    val adds = (add as? ReferenceInstruction)?.reference as? MethodReference
    if (check.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != button ||
        (check as OffsetInstruction).codeOffset != check.codeUnits + add.codeUnits
    ) return null
    if (add !is FiveRegisterInstruction || add.opcode != Opcode.INVOKE_VIRTUAL || adds?.name != "add" ||
        !adds.definingClass.startsWith("Ljava/util/") || add.registerCount != 2 || add.registerC != context + 2 ||
        add.registerD != button
    ) return null
    return helper.takeIf { body[4].opcode == Opcode.RETURN_VOID }
}

/** What the Chats search screen's Meta AI tap names as where the chat was opened from. */
internal const val PEOPLE_PICKER_ENTRY = "messaging_inbox_in_blue:people_picker"

/** Kept name. Each Litho component keeps its own as a static field. */
internal const val CALLER_CONTEXT = "Lcom/facebook/common/callercontext/CallerContext;"

/** Whether [method] calls Meta AI's launcher, as the search screen's Meta AI tap does. */
internal fun callsMetaAiLauncher(method: Method): Boolean = method.implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == META_AI_LAUNCHER
} == true

/**
 * The search screen's Meta AI tap: the [row] class it names and, when the tap is one case of a merged
 * lambda that switches on its own int field (585), the [case] the row builds it with. Null on a tap
 * of its own (584).
 */
internal class PickerTap(val row: String, val case: Int?)

/**
 * The Chats search screen's Meta AI tap in [owner]: a method that calls the launcher and loads
 * [PEOPLE_PICKER_ENTRY], or since 583's 64-bit build asks a string table [resolve] finds for it
 * ([tableStringAt]), with the row's own static CallerContext read in the same straight run of code
 * just before the entry. 585 merges the tap into a lambda that switches on its own int field, and the
 * tap's case reads no CallerContext, only another static of the row (an AtomicBoolean on 585), so
 * there the row is the class of the last static object read in the case before the entry. Null when
 * [owner] holds no such tap, or more than one.
 */
internal fun pickerTapOf(owner: ClassDef, resolve: (MethodReference) -> Method? = { null }): PickerTap? {
    val taps = owner.methods.flatMap { method ->
        if (!callsMetaAiLauncher(method)) return@flatMap emptyList()
        val code = method.implementation!!.instructions.toList()
        code.indices.filter {
            ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == PEOPLE_PICKER_ENTRY ||
                tableStringAt(code, it, resolve) == PEOPLE_PICKER_ENTRY
        }.map { load ->
            val case = mergedCaseAt(method, code, load)
            val row = callerContextBefore(code, load) ?: case?.let { rowStaticBefore(code, load, it.second, owner.type) }
            row?.let { PickerTap(it, case?.first) }
        }
    }
    return taps.singleOrNull()
}

/** The class of the Chats search screen's row, named by the Meta AI tap in [owner] ([pickerTapOf]). */
internal fun pickerRowOf(owner: ClassDef, resolve: (MethodReference) -> Method? = { null }): String? =
    pickerTapOf(owner, resolve)?.row

/** The class whose static CallerContext is read before [at] with no jump, return or throw between, or null. */
private fun callerContextBefore(code: List<Instruction>, at: Int): String? {
    for (before in at - 1 downTo 0) {
        val instruction = code[before]
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode == Opcode.SGET_OBJECT && field?.type == CALLER_CONTEXT) return field.definingClass
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) return null
    }
    return null
}

/**
 * The class (other than [self]) whose static object field is read last before [at], from [start]
 * on with no jump, return or throw between, or null.
 */
private fun rowStaticBefore(code: List<Instruction>, at: Int, start: Int, self: String): String? {
    for (before in at - 1 downTo start) {
        val instruction = code[before]
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode == Opcode.SGET_OBJECT && field != null && field.definingClass != self) return field.definingClass
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) return null
    }
    return null
}

/**
 * When [method] is a merged lambda's body, a switch on an int field of its own class, the case key
 * whose code holds [at] and the index that case starts at. Null for any other method, or when [at]
 * comes before the switch's first case.
 */
private fun mergedCaseAt(method: Method, code: List<Instruction>, at: Int): Pair<Int, Int>? {
    val switch = code.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    if (switch !in 0 until at) return null
    val selector = (code[switch] as OneRegisterInstruction).registerA
    // Why: the merged lambda picks its case from the `$t`-style int it was built with, read off itself.
    val read = (switch - 1 downTo 0).firstOrNull { code[it].writesRegister(selector) } ?: return null
    val field = (code[read] as? ReferenceInstruction)?.reference as? FieldReference
    if (code[read].opcode != Opcode.IGET || field?.definingClass != method.definingClass || field.type != "I") return null
    val address = IntArray(code.size + 1)
    for (index in code.indices) address[index + 1] = address[index] + code[index].codeUnits
    val payloadAt = address[switch] + (code[switch] as OffsetInstruction).codeOffset
    val payload = code.getOrNull(address.indexOf(payloadAt)) as? SwitchPayload ?: return null
    return payload.switchElements.mapNotNull { element ->
        val target = address.indexOf(address[switch] + element.offset)
        if (target in 0..at) element.key to target else null
    }.maxByOrNull { it.second }
}

private fun Instruction.writesRegister(register: Int): Boolean =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA == register

/**
 * Where the search screen's row decides to show its Meta AI button: [set] in [method] is the one
 * instruction putting 1 in [register], the flag the row tests once to give the search box an end
 * margin and again at [test] before it builds the button.
 */
internal class PickerButtonGate(val method: Method, val set: Int, val test: Int, val register: Int)

/**
 * The Meta AI gate of [method] when it's the search screen row's render: it creates one tap action
 * of type [opener], the last `if-eqz` before that jumps past it tests the flag, no jump lands on that
 * test, and exactly one literal 1 is put in that register before it. With a [case] (585's merged
 * lambda, see [PickerTap]) only the tap actions built with that literal case count. Null for any
 * other method.
 */
internal fun pickerButtonGate(method: Method, opener: String, case: Int? = null): PickerButtonGate? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val created = code.indices.filter { at ->
        code[at].opcode == Opcode.NEW_INSTANCE && ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type == opener &&
            (case == null || builtWithCase(code, at, opener, case))
    }
    if (created.size != 1) return null
    val tap = created.single()
    val address = IntArray(code.size + 1)
    for (at in code.indices) address[at + 1] = address[at] + code[at].codeUnits
    val gate = (tap - 1 downTo 0).firstOrNull { at ->
        code[at].opcode == Opcode.IF_EQZ && address[at] + (code[at] as OffsetInstruction).codeOffset > address[tap]
    } ?: return null
    val lands = code.indices.any { at ->
        val jump = code[at] as? OffsetInstruction
        jump != null && address[at] + jump.codeOffset == address[gate]
    }
    if (lands) return null
    val register = (code[gate] as OneRegisterInstruction).registerA
    val sets = (0 until gate).filter { at ->
        code[at].opcode in LITERAL_CONSTS && (code[at] as OneRegisterInstruction).registerA == register &&
            (code[at] as NarrowLiteralInstruction).narrowLiteral == 1
    }
    return sets.singleOrNull()?.let { PickerButtonGate(method, it, gate, register) }
}

private val LITERAL_CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/**
 * Whether the object made at [made] is handed to an [opener] constructor taking the case int first,
 * and that int's last write before the call is the literal [case].
 */
private fun builtWithCase(code: List<Instruction>, made: Int, opener: String, case: Int): Boolean {
    val self = (code[made] as OneRegisterInstruction).registerA
    val init = (made + 1 until code.size).firstOrNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode in CONSTRUCTOR_CALLS && call?.definingClass == opener && call.name == "<init>"
    } ?: return false
    val call = (code[init] as ReferenceInstruction).reference as MethodReference
    if (call.parameterTypes.firstOrNull()?.toString() != "I") return false
    val (receiver, selector) = when (val invoke = code[init]) {
        is FiveRegisterInstruction -> invoke.registerC to invoke.registerD
        is RegisterRangeInstruction -> invoke.startRegister to invoke.startRegister + 1
        else -> return false
    }
    if (receiver != self) return false
    var write = (init - 1 downTo 0).firstOrNull { code[it].writesRegister(selector) } ?: return false
    // 585's Android 9 build (LX/0a8Y;->render) copies the case in with a move/from16 from a register
    // set to the literal at the top of the method: follow that one copy back to its literal.
    if (code[write].opcode in INT_MOVES) {
        val source = (code[write] as TwoRegisterInstruction).registerB
        write = (write - 1 downTo 0).firstOrNull { code[it].writesRegister(source) } ?: return false
    }
    return code[write].opcode in LITERAL_CONSTS && (code[write] as NarrowLiteralInstruction).narrowLiteral == case
}

private val INT_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

private val CONSTRUCTOR_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

/** The tile state constructor of [owner], or null when it isn't the state class. */
internal fun notesTrayOf(owner: ClassDef): NotesTrayStore? =
    owner.methods.mapNotNull { notesTrayStore(owner, it) }.singleOrNull()

/** What the section drawing the notes row names itself, and the key it keeps its tiles under. */
internal const val NOTES_SECTION = "ActiveNowSection"
internal const val NOTES_SECTION_KEY = "active_now_tiles_key"

/** Where the notes row's render leaves with nothing to draw: the `new-instance` at [exit] in [method]. */
internal class NotesRowExit(val method: Method, val exit: Int)

/**
 * Facebook's own empty way out of the notes row, when [method] is the section's render: an
 * instance method of one parameter that loads [NOTES_SECTION] and [NOTES_SECTION_KEY], where a jump
 * lands on the first run of `new-instance` B, `B.<init>()` (or a static factory answering B, see
 * [emptyReturnAt]), an `iget-object` of B's field of the render's return type from that object and
 * `return-object` of it. Null for any other method.
 */
internal fun notesRowExit(method: Method): NotesRowExit? {
    if (method.parameterTypes.size != 1 || !method.isInstanceWithBody()) return null
    if (!holdsString(method, NOTES_SECTION) || !holdsString(method, NOTES_SECTION_KEY)) return null
    val code = method.implementation!!.instructions.toList()
    val exit = (0 until code.size - 3).firstOrNull { emptyReturnAt(code, it, method.returnType) } ?: return null
    val address = IntArray(code.size + 1)
    for (at in code.indices) address[at + 1] = address[at] + code[at].codeUnits
    val lands = code.indices.any { at ->
        val jump = code[at] as? OffsetInstruction
        jump != null && code[at].opcode !in PAYLOAD_JUMPS && address[at] + jump.codeOffset == address[exit]
    }
    // A handler reached from the hook's call or the new jump would see locals stock code had set by
    // then, so a render with a try block over its first instruction or its way out is refused.
    val guarded = method.implementation!!.tryBlocks.any { block ->
        val covered = block.startCodeAddress until block.startCodeAddress + block.codeUnitCount
        0 in covered || (exit..exit + 3).any { address[it] in covered }
    }
    return if (lands && !guarded) NotesRowExit(method, exit) else null
}

private val PAYLOAD_JUMPS = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA)

/**
 * The notes section's field holding the ThreadListParams of the inbox it draws for, the one
 * instance field of [owner] of that type, or null. Dating's Matches screen draws its new matches
 * with the same section under Dating's own inbox style (585: `A09`, whose style the render compares
 * to "mib_style_gemstone"), so the hook reads it to leave that row alone (#337).
 */
internal fun notesSectionInbox(owner: ClassDef): FieldReference? =
    owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == THREAD_LIST_PARAMS }.singleOrNull()

/**
 * Whether [at] starts a new object of no arguments whose field of type [returns] is read and
 * returned. The object is made with `new-instance` and its `<init>()`, or (585, read 2026-10-29,
 * arm64 `LX/0Duf;->A0a()` in `LX/0G5V;->A0o`) by a static factory of no arguments Redex outlined
 * them into, its answer moved into a register. Both take two instructions, so the way out stays four.
 */
private fun emptyReturnAt(code: List<Instruction>, at: Int, returns: String): Boolean {
    val (type, self) = newObjectAt(code, at) ?: factoryObjectAt(code, at) ?: return false
    val read = code[at + 2] as? TwoRegisterInstruction
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference
    if (read?.opcode != Opcode.IGET_OBJECT || read.registerB != self || field == null ||
        field.definingClass != type || field.type != returns) return false
    val back = code[at + 3]
    return back.opcode == Opcode.RETURN_OBJECT && (back as OneRegisterInstruction).registerA == read.registerA
}

/** The type and register of the object `new-instance` at [at] makes and `<init>()` at the next builds. */
private fun newObjectAt(code: List<Instruction>, at: Int): Pair<String, Int>? {
    val made = code[at]
    if (made.opcode != Opcode.NEW_INSTANCE) return null
    val type = ((made as ReferenceInstruction).reference as? TypeReference)?.type ?: return null
    val self = (made as OneRegisterInstruction).registerA
    val init = code[at + 1] as? FiveRegisterInstruction
    val built = (init as? ReferenceInstruction)?.reference as? MethodReference
    if (init?.opcode != Opcode.INVOKE_DIRECT || init.registerCount != 1 || init.registerC != self || built == null ||
        built.definingClass != type || built.name != "<init>" || built.parameterTypes.isNotEmpty()) return null
    return type to self
}

/** The type and register of the object a static factory of no arguments at [at] answers, moved at the next. */
private fun factoryObjectAt(code: List<Instruction>, at: Int): Pair<String, Int>? {
    val call = code[at]
    if (call.opcode != Opcode.INVOKE_STATIC || (call as FiveRegisterInstruction).registerCount != 0) return null
    val factory = (call as ReferenceInstruction).reference as? MethodReference ?: return null
    if (factory.parameterTypes.isNotEmpty() || !factory.returnType.startsWith("L")) return null
    val moved = code[at + 1]
    if (moved.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return factory.returnType to (moved as OneRegisterInstruction).registerA
}

private fun Method.isInstanceWithBody(): Boolean =
    implementation != null && !AccessFlags.STATIC.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)

/** A banner plugin's builder: an instance method taking the list's `ThreadListParams` that loads [tag]. */
internal fun buildsBanner(method: Method, tag: String): Boolean =
    method.isInstanceWithBody() && method.returnType.startsWith("L") &&
        THREAD_LIST_PARAMS in method.parameterTypes.map { it.toString() } && holdsString(method, tag)

/**
 * The show question of the plugin that builds the banner tagged [tag], when [owner] is that
 * plugin: one builder loading the tag and one show question. Null for any other class.
 */
internal fun bannerQuestion(owner: ClassDef, tag: String): Method? {
    if (owner.methods.count { buildsBanner(it, tag) } != 1) return null
    return owner.methods.filter(::isShowQuestion).singleOrNull()
}
