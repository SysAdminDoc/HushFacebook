/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.PayloadInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.Locale
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Route two for the FDS styles.
 *
 * A view Facebook inflates from its own compiled layouts, like the Find friends button on an empty
 * feed, takes its colours from theme attributes the FDS dark style sets, and that style points them
 * at colour resources in the default configuration. Facebook keeps its dark palette there, since the
 * Video tab uses the dark style in light mode too, so no colour call passes a hook and none of those
 * colours is a night one route two rewrites.
 *
 * So the resource half writes a night copy of the dark style, and of each style under it that sets a
 * token of its own, with each item for a token and colour FDS_DARK or FDS_SHARED lists pointing at a
 * palette colour instead, at the listed colour's lightness and alpha, the lightness route one's
 * runtime hooks give it (the hue is the nearest system tone's, where route one blends the two tones
 * either side). On Android 12 and newer that palette colour is a colour state list, which some of
 * Facebook's code can't read: it resolves the attribute and takes `TypedValue.data` as the colour, and
 * gets the file's name. Those tokens' items take the nearest system tone itself instead, as the night
 * colours do, or stay when none is near. Night resources are only read while Facebook's dark mode is
 * on, so light mode, and the Video tab in light mode with it, keeps the default styles as they are. A
 * colour the tables don't list for its token (the logo's blue, a map's), black, white and any colour
 * that's neither a grey nor one of Facebook's blues stay as Facebook has them.
 */

/** MaterialYouTheme.FDS_DARK, which the parity test holds to the extension's. */
internal const val FDS_DARK_TOKENS = "ACCENT=1D85FC;ACCENT_DEEMPHASIZED=331D85FC,192D88FF;ACTIVE_DOT=E2E5E9;" +
    "ATTACHMENT_FOOTER_BACKGROUND=F2F4F7;BLUE_LINK=5AA7FF,3E93F8;" +
    "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED=252728;BOTTOM_SHEET_HANDLE=6F7276;" +
    "BOTTOM_SHEET_INSET_BACKGROUND=333334;CARD_BACKGROUND=333334;CARD_BACKGROUND_FLAT=333334;" +
    "CARD_BACKGROUND_LEGACY_WEB=252728;CARD_BORDER=333334;CLIENT_BOTTOM_SHEET_PRESSED=3B3C3E;" +
    "COMMENT_BACKGROUND=333334;COMMENT_THREADING_LINES=46484B;DISABLED_BUTTON_BACKGROUND=333334;" +
    "DISABLED_ICON=6F7276;DISABLED_TEXT=6F7276,505255;DIVIDER=65686C,505255;DOT_BADGE_BLUE=1D85FC;" +
    "ENTITY_HEADER_BACKGROUND=252728;FBLITE_ACCENT_ON_BACKGROUND=1D85FC;FBLITE_STRONG_SECONDARY=D0D3D7;" +
    "FBLITE_TEXT_INPUT_INACTIVE_INNER_BORDER=6F7276;FBLITE_WASH=080809;FEED_GAP_VERTICAL=101011;" +
    "HOSTED_VIEW_SELECTED_STATE=191D85FC;INACTIVE_DOT=84878B;LIST_CELL_BACKGROUND=252728;" +
    "META_ICON=B0B3B8;META_TEXT=B0B3B8;NAV_BAR_BACKGROUND=252728;NAV_BAR_ICON=E8EAEE;NAV_BAR_TEXT=E8EAEE;" +
    "NEW_NOTIFICATION_BACKGROUND=192D88FF;PLACEHOLDER_ICON=B0B3B8,84878B;PLACEHOLDER_TEXT=B0B3B8,84878B;" +
    "POPOVER_BACKGROUND=3B3C3E,3E4042;PRIMARY_BUTTON_TEXT=252728;" +
    "PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND=331D85FC,262D88FF;PRIMARY_DEEMPHASIZED_BUTTON_ICON=75B6FF;" +
    "PRIMARY_DEEMPHASIZED_BUTTON_TEXT=75B6FF,0866FF;PRIMARY_ICON=F2F4F7;PRIMARY_TEXT=F2F4F7;" +
    "PROGRESS_RING_DISABLED_FOREGROUND=6F7276;REACTION_LIKE=3E93F8;SECONDARY_BUTTON_BACKGROUND=333334;" +
    "SECONDARY_BUTTON_BACKGROUND_FLOATING=46484B;SECONDARY_BUTTON_BACKGROUND_OPAQUE=46484B;" +
    "SECONDARY_BUTTON_ICON=F2F4F7;SECONDARY_BUTTON_TEXT=F2F4F7;SECONDARY_ICON=B0B3B8,A1A4A9;" +
    "SECONDARY_TEXT=B0B3B8,A1A4A9;SURFACE_BACKGROUND=252728;" +
    "SWITCH_CHECKED_BACKGROUND_COLOR_ANDROID=ADD5FF;SWITCH_CHECKED_HANDLE_FILL_COLOR_ANDROID=0866FF;" +
    "SWITCH_DISABLED_HANDLE_FILL_COLOR=6F7276;SWITCH_UNCHECKED_BACKGROUND_COLOR=6F7276;" +
    "TAB_BAR_ACTIVE_ICON=F2F4F7;TAB_BAR_BACKGROUND=252728;TAB_BAR_INACTIVE_ICON=F2F4F7;" +
    "TEXT_HIGHLIGHT=721D85FC;TEXT_INPUT_ACTIVE_INNER_BORDER=1D85FC;" +
    "TEXT_INPUT_ACTIVE_OUTER_BORDER=331D85FC;TEXT_INPUT_ACTIVE_TEXT=3E93F8;" +
    "TEXT_INPUT_BAR_BACKGROUND=333334;TEXT_INPUT_BAR_BACKGROUND_ON_DEEMPHASIZED=333334;" +
    "TEXT_INPUT_INACTIVE_INNER_BORDER=5C5E62;TOGGLE_ACTIVE_BACKGROUND=1D85FC,0866FF;TOOLTIP_TEXT=080809;" +
    "UFI_TRAY_ICON_BUTTON_BACKGROUND=46484B;VOICE_SWITCHER_BACKGROUND=46484B;WASH=101011,1C1C1D;" +
    "WEB_WASH=1C1C1D"

/** MaterialYouTheme.FDS_SHARED, held to the extension's the same way. */
internal const val FDS_SHARED_TOKENS = "ACCENT=0866FF;BLUE_BADGE=0866FF;CURSOR=0866FF;DECORATIVE_ICON_BLUE=0064D1;" +
    "DISABLED_BUTTON_BACKGROUND_GROWTH=5AA7FF;NOTIFICATION_CIRCLE_BLUE=1D85FC;" +
    "PRIMARY_BUTTON_BACKGROUND=0866FF;PRIMARY_BUTTON_PRESSED_BACKGROUND=3E93F8;" +
    "PROGRESS_RING_BLUE_BACKGROUND=330866FF;PROGRESS_RING_BLUE_FOREGROUND=0866FF;STEPPER_ACTIVE=0866FF;" +
    "STORY_UNSEEN=0866FF;SWITCH_CHECKED_BACKGROUND_COLOR_IOS=0866FF;VERIFIED_BADGE=0866FF"

/**
 * Each token either table lists, with every colour listed for it: six hex digits an opaque colour,
 * eight a colour with its alpha first, as MaterialYouTheme.parseTokens reads them.
 */
internal fun listedTokenColours(): Map<String, Set<Int>> {
    val listed = mutableMapOf<String, MutableSet<Int>>()
    for (table in listOf(FDS_DARK_TOKENS, FDS_SHARED_TOKENS)) {
        for (entry in table.split(";")) {
            val (token, colours) = entry.split("=")
            for (hex in colours.split(",")) {
                val rgb = hex.toLong(16).toInt()
                listed.getOrPut(token) { mutableSetOf() } += if (hex.length == 8) rgb else rgb or -0x1000000
            }
        }
    }
    return listed
}

/** A token set by more of the FDS styles' items than this is one of Facebook's full themes. */
private const val FULL_THEME_ITEMS = 300

/**
 * Each FDS token by the item name the resource decoder gives its theme attribute, such as
 * `attr_0x7f0405bd` for PRIMARY_BUTTON_BACKGROUND on 580. The bytecode half reads them off the token
 * enum for the resource half, since Facebook strips the attributes' names.
 */
internal var tokenAttributeNames: Map<String, String> = emptyMap()

/**
 * The FDS tokens some of Facebook's code reads as a plain colour, which [dataReadTokens] finds. Their
 * night style items keep a plain colour instead of a state list.
 */
internal var plainTokens: Set<String> = emptySet()

/** The token enum FDSColors resolves, and its constants. */
private fun BytecodePatchContext.fdsTokens(): Pair<ClassDef, TokenConstants> {
    val source = classDefBy(FDS_COLORS).methods.singleOrNull { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Integer;" &&
            method.parameterTypes.size == 3 && method.parameterTypes[0].toString() == "Landroid/content/Context;"
    } ?: error("FDSColors has no single Integer colour source taking a Context, a token and a palette")
    val tokenType = source.parameterTypes[1].toString()
    val tokenClass = classDefBy(tokenType)
    return tokenClass to tokenConstants(tokenClass.methods.single { it.name == "<clinit>" }, tokenType)
}

/**
 * Reads [tokenAttributeNames] out of the token enum FDSColors resolves. On its own for Accent
 * color, which needs the names and not [plainTokens]' scan of the whole app.
 */
internal val fdsTokenNamesPatch = bytecodePatch {
    execute {
        tokenAttributeNames = fdsTokens().second.attributes.entries
            .associate { (token, attribute) -> "attr_0x%08x".format(attribute) to token }
        check(tokenAttributeNames.size > FULL_THEME_ITEMS) {
            "The FDS token enum has too few constants with a theme attribute, so no style item can be matched"
        }
    }
}

/** Reads [tokenAttributeNames] and [plainTokens] out of the token enum FDSColors resolves. */
internal val fdsTokenAttributesPatch = bytecodePatch {
    dependsOn(fdsTokenNamesPatch)

    execute {
        val (tokenClass, constants) = fdsTokens()
        plainTokens = dataReadTokens({ visit -> classDefForEach { visit(it) } }, constants, tokenAttributeField(tokenClass)).tokens
    }
}

/**
 * The token enum's constants: each one's theme attribute by its name, and its name by the static
 * field holding it, for the token enum [type].
 */
internal class TokenConstants(val type: String, val attributes: Map<String, Int>, val fields: Map<String, String>)

/** A token constant being built in the enum's initializer, told apart from the others by identity. */
private class BuiltToken

/** Each constant of the token enum, by name, with the theme attribute it passes. */
internal fun tokenAttributes(initializer: Method, tokenType: String): Map<String, Int> =
    tokenConstants(initializer, tokenType).attributes

/**
 * Follows constants and moves through registers to each constructor call, which takes the name, the
 * ordinal, the theme attribute, a fallback colour and a colour resource, and on to the static field
 * each built constant goes in.
 */
internal fun tokenConstants(initializer: Method, tokenType: String): TokenConstants {
    val registers = mutableMapOf<Int, Any?>()
    val tokens = mutableMapOf<String, Int>()
    val built = mutableMapOf<BuiltToken, String>()
    val fields = mutableMapOf<String, String>()
    for (instruction in initializer.implementation!!.instructions) {
        val opcode = instruction.opcode
        when {
            instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction &&
                opcode in setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16) ->
                registers[instruction.registerA] = instruction.narrowLiteral
            opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO ->
                registers[(instruction as OneRegisterInstruction).registerA] =
                    ((instruction as ReferenceInstruction).reference as StringReference).string
            opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) ->
                registers[(instruction as TwoRegisterInstruction).registerA] = registers[instruction.registerB]
            (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                ((instruction as ReferenceInstruction).reference as? MethodReference)
                    ?.let { it.name == "<init>" && it.definingClass == tokenType } == true -> {
                val arguments = when (instruction) {
                    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
                    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD,
                        instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                    else -> error("unexpected call form $opcode")
                }
                val name = registers[arguments[1]] as? String ?: continue
                (registers[arguments[3]] as? Int)?.let { tokens[name] = it }
                (registers[arguments[0]] as? BuiltToken)?.let { built[it] = name }
            }
            opcode == Opcode.NEW_INSTANCE && (instruction as ReferenceInstruction).reference.toString() == tokenType ->
                registers[(instruction as OneRegisterInstruction).registerA] = BuiltToken()
            opcode == Opcode.SPUT_OBJECT -> {
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                val name = (registers[(instruction as OneRegisterInstruction).registerA] as? BuiltToken)?.let { built[it] }
                if (field.definingClass == tokenType && name != null) fields[field.name] = name
            }
            instruction is OneRegisterInstruction -> registers.remove(instruction.registerA)
        }
    }
    return TokenConstants(tokenType, tokens, fields)
}

/** The token enum's field holding each constant's theme attribute, which its constructor stores. */
internal fun tokenAttributeField(tokenClass: ClassDef): String {
    val fields = tokenClass.methods.filter { it.name == "<init>" && it.parameterTypes.size > 2 }.flatMap { constructor ->
        val attribute = constructor.parameterRegisterNumber(2)
        constructor.implementation!!.instructions
            .filter { it.opcode == Opcode.IPUT && (it as TwoRegisterInstruction).registerA == attribute }
            .map { (it as ReferenceInstruction).reference.toString() }
    }.toSet()
    return fields.singleOrNull() ?: error("The FDS token enum keeps its theme attribute in ${fields.size} fields, not one")
}

private const val RESOLVE_ATTRIBUTE = "Landroid/content/res/Resources\$Theme;->resolveAttribute(ILandroid/util/TypedValue;Z)Z"
private const val TYPED_VALUE = "Landroid/util/TypedValue;"

/** Where a register's value came from, as far as a look back up the method goes. */
private sealed interface Origin {
    data class Literal(val value: Int) : Origin
    data class Token(val field: String) : Origin
    data class Parameter(val index: Int) : Origin
    object Other : Origin
}

/** How a method that calls `resolveAttribute` reads the `TypedValue` back. */
private enum class Read { DATA, TYPE, NEITHER }

/**
 * What [dataReadTokens] found: the tokens some code reads as `TypedValue.data` without looking at
 * `TypedValue.type`, each with the methods that do ([readers]); the tokens that reach `resolveAttribute` in a method that reads `type` for fewer
 * calls than it makes, or reads neither field and so hands the value on ([unchecked], each with the
 * methods); and what the scan can't follow ([unresolved]): a call whose attribute it can't trace, in a
 * method that reads `data` unchecked or only partly checked, as `method@instruction`, and a helper no
 * call it found reaches, as `method: no caller` (a call through a subclass or an interface names
 * another class). The last two are for a person to look at: the fixture test pins them.
 */
internal class DataReadScan(
    val readers: Map<String, Set<String>>,
    val unchecked: Map<String, Set<String>>,
    val unresolved: Set<String>,
) {
    /** The tokens [readers] lists, each with the methods reading it. */
    val tokens: Set<String> get() = readers.keys
}

/**
 * The FDS tokens Facebook's code resolves with `Theme.resolveAttribute` and then reads as
 * `TypedValue.data` without looking at `TypedValue.type`. A night style item pointing at a colour
 * state list resolves to the file's name there, not a colour, so these tokens' items keep a plain
 * colour. Every call is looked at: each value the attribute register can hold there (the last write
 * on each path to the call, so both arms of a branch) is a token's literal, a token constant's
 * [attributeField], or a parameter, which makes the method a helper whose callers are looked at the
 * same way, a token constant passed in included, and a helper's helpers after them until no new one
 * turns up. [forEachClass] walks every class, once per round.
 */
internal fun dataReadTokens(
    forEachClass: ((ClassDef) -> Unit) -> Unit,
    constants: TokenConstants,
    attributeField: String,
): DataReadScan {
    val byAttribute = constants.attributes.entries.associate { (name, attribute) -> attribute to name }
    val readers = sortedMapOf<String, MutableSet<String>>()
    val unchecked = sortedMapOf<String, MutableSet<String>>()
    val unresolved = sortedSetOf<String>()
    val helpers = mutableMapOf<String, MutableSet<Pair<Int, Read>>>()
    var fresh = mutableMapOf<String, MutableSet<Pair<Int, Read>>>()

    fun note(origins: Set<Origin>, read: Read, method: String, at: Int) {
        for (origin in origins) {
            val name = when (origin) {
                is Origin.Literal -> byAttribute[origin.value]
                is Origin.Token -> constants.fields[origin.field]
                is Origin.Parameter -> {
                    if (helpers.getOrPut(method) { mutableSetOf() }.add(origin.index to read)) {
                        fresh.getOrPut(method) { mutableSetOf() } += origin.index to read
                    }
                    null
                }
                Origin.Other -> {
                    if (read != Read.TYPE) unresolved += "$method@$at"
                    null
                }
            } ?: continue
            (if (read == Read.DATA) readers else unchecked).getOrPut(name) { sortedSetOf() } += method
        }
    }

    forEachClass { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@forEachClass
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions ?: continue
            val calls = instructions.count { (it as? ReferenceInstruction)?.reference?.toString() == RESOLVE_ATTRIBUTE }
            if (calls == 0) continue
            val read = reads(instructions, calls)
            if (read == Read.TYPE) continue
            val flow = Flow(method)
            val key = method.key()
            for ((index, instruction) in flow.instructions.withIndex()) {
                if ((instruction as? ReferenceInstruction)?.reference?.toString() != RESOLVE_ATTRIBUTE) continue
                note(origins(flow, index, instruction.arguments()[1], constants, attributeField), read, key, index)
            }
        }
    }

    val called = mutableSetOf<String>()
    while (fresh.isNotEmpty()) {
        val round = fresh
        fresh = mutableMapOf()
        val roundTypes = round.keys.map { it.substringBefore("->") }.toSet()
        forEachClass { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@forEachClass
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                var flow: Flow? = null
                for ((index, instruction) in instructions.withIndex()) {
                    val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (target.definingClass !in roundTypes) continue
                    val uses = round[target.key()] ?: continue
                    called += target.key()
                    val caller = flow ?: Flow(method).also { flow = it }
                    val static = instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE
                    for ((parameter, read) in uses) {
                        val slot = (if (static) 0 else 1) +
                            target.parameterTypes.take(parameter).sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                        note(origins(caller, index, instruction.arguments()[slot], constants, attributeField), read, method.key(), index)
                    }
                }
            }
        }
    }
    for (helper in helpers.keys - called) unresolved += "$helper: no caller"
    return DataReadScan(readers, unchecked, unresolved)
}

/**
 * [Read.DATA] when [instructions] read `TypedValue.data` and never `type`; [Read.TYPE] when they
 * read `type` at least once for each of their [calls] to `resolveAttribute`; otherwise, a method
 * that reads `type` for only some calls or reads neither field, [Read.NEITHER].
 */
private fun reads(instructions: Iterable<Instruction>, calls: Int): Read {
    var data = false
    var type = 0
    for (instruction in instructions) {
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue
        if (field.definingClass != TYPED_VALUE) continue
        when (field.name) {
            "type" -> type++
            "data" -> data = true
        }
    }
    return when {
        type == 0 && data -> Read.DATA
        type >= calls -> Read.TYPE
        else -> Read.NEITHER
    }
}

private fun MethodReference.key(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (0 until registerCount).map { startRegister + it }
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> error("unexpected call form $opcode")
}

/**
 * [method]'s instructions and, for each, the ones control can come to it from: the one above when
 * that one carries on, every branch and switch case aimed at it, and for an exception handler, what
 * comes before each instruction its try block covers that can throw. -1 stands for the method's start.
 */
private class Flow(val method: Method) {
    val instructions: List<Instruction> = method.implementation!!.instructions.toList()
    val predecessors: Array<MutableList<Int>> = Array(instructions.size) { mutableListOf() }

    init {
        val addresses = IntArray(instructions.size)
        var address = 0
        for ((index, instruction) in instructions.withIndex()) {
            addresses[index] = address
            address += instruction.codeUnits
        }
        val byAddress = addresses.withIndex().associate { (index, start) -> start to index }
        if (instructions.isNotEmpty()) predecessors[0] += -1
        for ((index, instruction) in instructions.withIndex()) {
            if (instruction is PayloadInstruction) continue
            if (instruction.opcode.canContinue() && index + 1 < instructions.size) predecessors[index + 1] += index
            if (instruction !is OffsetInstruction || instruction.opcode == Opcode.FILL_ARRAY_DATA) continue
            val aimed = byAddress.getValue(addresses[index] + instruction.codeOffset)
            val payload = instructions[aimed]
            if (payload is SwitchPayload) {
                for (case in payload.switchElements) predecessors[byAddress.getValue(addresses[index] + case.offset)] += index
            } else {
                predecessors[aimed] += index
            }
        }
        val handlerPredecessors = mutableMapOf<Int, MutableSet<Int>>()
        for (tryBlock in method.implementation!!.tryBlocks) {
            val covered = instructions.indices.filter {
                addresses[it] >= tryBlock.startCodeAddress && addresses[it] < tryBlock.startCodeAddress + tryBlock.codeUnitCount &&
                    instructions[it].opcode.canThrow()
            }
            for (handler in tryBlock.exceptionHandlers) {
                handlerPredecessors.getOrPut(byAddress.getValue(handler.handlerCodeAddress)) { linkedSetOf() } +=
                    covered.flatMap { predecessors[it] }
            }
        }
        for ((handler, from) in handlerPredecessors) predecessors[handler] += from
    }
}

/**
 * Every value that can be in [register] at instruction [at]: what the last write to it on each
 * path to [at] put there (a literal, a token constant's attribute read off its static field or off
 * a parameter, one of the method's parameters, or something else), or the parameter in it on a path
 * from the method's start that never writes it.
 */
private fun origins(
    flow: Flow,
    at: Int,
    register: Int,
    constants: TokenConstants,
    attributeField: String,
    seen: MutableSet<Long> = hashSetOf(),
): Set<Origin> {
    if (!seen.add(at.toLong() shl 32 or register.toLong())) return emptySet()
    val found = linkedSetOf<Origin>()
    val visited = BooleanArray(flow.instructions.size)
    val pending = ArrayDeque(flow.predecessors[at])
    while (pending.isNotEmpty()) {
        val index = pending.removeLast()
        if (index < 0) {
            found += flow.method.parameterTypes.indices.firstOrNull { flow.method.parameterRegisterNumber(it) == register }
                ?.let { Origin.Parameter(it) } ?: Origin.Other
            continue
        }
        if (visited[index]) continue
        visited[index] = true
        val instruction = flow.instructions[index]
        val target = if (instruction.opcode.setsRegister()) (instruction as OneRegisterInstruction).registerA else -1
        val wide = instruction.opcode.setsWideRegister() && target + 1 == register
        if (target != register && !wide) {
            pending += flow.predecessors[index]
            continue
        }
        found += when {
            wide -> setOf(Origin.Other)
            instruction is NarrowLiteralInstruction -> setOf(Origin.Literal(instruction.narrowLiteral))
            instruction.opcode == Opcode.IGET && (instruction as ReferenceInstruction).reference.toString() == attributeField ->
                origins(flow, index, (instruction as TwoRegisterInstruction).registerB, constants, attributeField, seen)
                    .map { if (it is Origin.Token || it is Origin.Parameter) it else Origin.Other }
            instruction.opcode == Opcode.SGET_OBJECT -> {
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                setOf(if (field.definingClass == constants.type && field.type == constants.type) Origin.Token(field.name) else Origin.Other)
            }
            instruction.opcode in MOVES ->
                origins(flow, index, (instruction as TwoRegisterInstruction).registerB, constants, attributeField, seen)
            else -> setOf(Origin.Other)
        }
    }
    return found
}

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT,
    Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** A style element's parent by name, or null when it names none. */
private fun Element.parentName(): String? = getAttribute("parent").substringAfter('/', "").ifEmpty { null }

/**
 * The FDS dark style and every style under it, the dark one first, out of one of the decoder's
 * default style files, or nothing when [styles] doesn't hold Facebook's light and dark FDS styles.
 * The light one names no parent, the dark one names the light one, and each sets more than
 * [FULL_THEME_ITEMS] of the tokens [tokens] names, as MaterialYouTokenFixtureTest finds them.
 */
internal fun darkFdsStyles(styles: Document, tokens: Map<String, String>): List<Element> {
    val all = styles.documentElement.childElements().filter { it.tagName.startsWith("style") }
    fun fullTheme(style: Element) = style.childElements().count { it.getAttribute("name") in tokens } > FULL_THEME_ITEMS
    val light = all.singleOrNull { it.parentName() == null && fullTheme(it) } ?: return emptyList()
    val dark = all.singleOrNull { it.parentName() == light.getAttribute("name") && fullTheme(it) } ?: return emptyList()

    val family = mutableListOf(dark)
    val names = mutableSetOf(dark.getAttribute("name"))
    do {
        val next = all.filter { it.getAttribute("name") !in names && it.parentName() in names }
        family += next
        next.forEach { names += it.getAttribute("name") }
    } while (next.isNotEmpty())
    return family
}

/**
 * The palette colour a night style item points at: the family, the listed colour's L* rounded to a
 * whole number, and its alpha. The fixed palette's steps are tones 0 to 100, so a blue at L* 56 sits
 * between two of them; both of these land on its lightness instead.
 */
internal data class NightShade(val accent: Boolean, val lightness: Int, val alpha: Int) {
    /** The colour resource's name, one per family, lightness and alpha. */
    val name: String
        get() = "hushfacebook_you_" + (if (accent) "accent" else "neutral") + "_l$lightness" +
            (if (alpha == 0xFF) "" else "_a%02x".format(alpha))

    /**
     * Android 12 and newer: a colour state list in `res/color-night-v31` that moves the system tone
     * nearest [lightness] to it with `android:lStar`, keeping that tone's hue and chroma, and gives it
     * [alpha]. The base is never tone 0 or 100, whose hue is lost.
     */
    val stateList: String
        get() {
            val base = NightTone(accent, TONES.filter { it in 10..95 }.minBy { abs(it - lightness) }).systemColor
            val alphaAttribute = if (alpha == 0xFF) "" else " android:alpha=\"%.4f\"".format(Locale.ROOT, alpha / 255.0)
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <item android:color=\"$base\" android:lStar=\"$lightness.0\"$alphaAttribute />\n" +
                "</selector>\n"
        }

    /** Android 11: the fixed palette's family at [lightness], between its two nearest steps in CIELAB, with [alpha]. */
    val fallback: String
        get() = "#%02x%06x".format(alpha, fixedPaletteColour(accent, lightness.toDouble()) and 0xFFFFFF)
}

/**
 * The shade a listed style colour takes, or null to leave it: a grey takes the neutral family and one
 * of Facebook's blues the accent, at any lightness and alpha. Black, white, a clear colour and any
 * other hue stay as they are.
 */
internal fun nightShade(colour: Int): NightShade? {
    val alpha = colour ushr 24
    val rgb = colour and 0xFFFFFF
    if (alpha == 0 || rgb == 0 || rgb == 0xFFFFFF) return null
    val r = rgb shr 16
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    val accent = when {
        maxOf(r, g, b) - minOf(r, g, b) <= 10 -> false
        isFacebookBlue(r, g, b) -> true
        else -> return null
    }
    return NightShade(accent, lstar(r, g, b).roundToInt(), alpha)
}

/**
 * TonePalette.sameLightness on the fixed palette: the family's colour at L* [lightness], its a* and
 * b* taken between the two steps either side of it.
 */
internal fun fixedPaletteColour(accent: Boolean, lightness: Double): Int {
    val steps = FALLBACK_PALETTE.split(";")[if (accent) 0 else 1].trim().split(" ")
        .map { lab(it.toInt(16)) }.sortedBy { it[0] }
    var k = 0
    while (k < steps.size - 2 && lightness > steps[k + 1][0]) k++
    val (low, high) = steps[k] to steps[k + 1]
    val span = high[0] - low[0]
    val w = if (span <= 0) 0.0 else ((lightness - low[0]) / span).coerceIn(0.0, 1.0)
    return fromLab(lightness, low[1] + (high[1] - low[1]) * w, low[2] + (high[2] - low[2]) * w)
}

private fun linear(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

// D65 white, as sRGB defines it, the same as TonePalette's.
private const val XN = 0.95047
private const val ZN = 1.08883

private fun labF(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16) / 116

private fun labInverse(f: Double): Double {
    val cube = f * f * f
    return if (cube > 216.0 / 24389.0) cube else (116 * f - 16) * 27.0 / 24389.0
}

private fun lab(rgb: Int): DoubleArray {
    val r = linear((rgb shr 16) and 0xFF)
    val g = linear((rgb shr 8) and 0xFF)
    val b = linear(rgb and 0xFF)
    val fx = labF((0.4124 * r + 0.3576 * g + 0.1805 * b) / XN)
    val fy = labF(0.2126 * r + 0.7152 * g + 0.0722 * b)
    val fz = labF((0.0193 * r + 0.1192 * g + 0.9505 * b) / ZN)
    return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

private fun fromLab(l: Double, a: Double, b: Double): Int {
    val fy = (l + 16) / 116
    val x = labInverse(fy + a / 500) * XN
    val y = labInverse(fy)
    val z = labInverse(fy - b / 200) * ZN
    fun channel(linear: Double): Int {
        val c = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055
        return (c.coerceIn(0.0, 1.0) * 255).roundToInt()
    }
    return -0x1000000 or (channel(3.2406 * x - 1.5372 * y - 0.4986 * z) shl 16) or
        (channel(-0.9689 * x + 1.8758 * y + 0.0415 * z) shl 8) or channel(0.0557 * x - 0.2040 * y + 1.0570 * z)
}

/**
 * An item's colour as the default configuration has it, following `@color/` references through
 * [colours], or null for anything else: a theme attribute, a colour Facebook gives a night value of
 * its own (route two's, or Facebook's own choice at night), a loop.
 */
private fun defaultColour(value: String, colours: Map<String, String>, nightColours: Set<String>, depth: Int = 0): Int? {
    val text = value.trim()
    if (text.startsWith("#")) return argb(text)
    if (!text.startsWith("@color/") || depth > 4) return null
    val name = text.removePrefix("@color/")
    if (name in nightColours) return null
    return defaultColour(colours[name] ?: return null, colours, nightColours, depth + 1)
}

/**
 * The plain palette colour a night style item for one of [plainTokens] points at: a system tone
 * itself, as route two gives the night colours.
 */
private val NightTone.name: String
    get() = "hushfacebook_you_" + (if (accent) "accent" else "neutral") + "_$tone"

/**
 * Writes into [night] a copy of each style in [family] with an item to change, its items for a
 * listed token and colour pointing at that colour's [NightShade], and adds each shade once to
 * [nightColours] (the fixed palette, for Android 11) and [stateLists] (the wallpaper's, as the
 * `res/color-night-v31` file for each name). An item for one of [plain], the tokens some code reads
 * as a plain colour, takes the colour's [nightTone] instead, a colour in [nightColours] and a system
 * colour in [nightV31Colours], or stays when no tone is near. [colours] are the default colours by
 * name and [nightColourNames] the ones with a night value. A style [night] already has is left to
 * Facebook. Answers how many items it pointed elsewhere.
 */
internal fun writeNightStyles(
    family: List<Element>,
    colours: Map<String, String>,
    nightColourNames: Set<String>,
    tokens: Map<String, String>,
    night: Document,
    nightColours: Document,
    nightV31Colours: Document,
    stateLists: MutableMap<String, String>,
    plain: Set<String>,
): Int {
    val listed = listedTokenColours()
    val present = night.documentElement.childElements().map { it.getAttribute("name") }.toSet()
    val written = nightColours.documentElement.childElements().map { it.getAttribute("name") }.toMutableSet()
    val writtenV31 = nightV31Colours.documentElement.childElements().map { it.getAttribute("name") }.toMutableSet()

    var changed = 0
    for (style in family) {
        if (style.getAttribute("name") in present) continue
        val names = style.childElements().mapIndexedNotNull { index, item ->
            val token = tokens[item.getAttribute("name")] ?: return@mapIndexedNotNull null
            val colour = defaultColour(item.textContent, colours, nightColourNames) ?: return@mapIndexedNotNull null
            if (colour !in listed[token].orEmpty()) return@mapIndexedNotNull null
            val name = if (token in plain) {
                nightTone("#%08x".format(colour))?.also { tone ->
                    if (written.add(tone.name)) nightColours.documentElement.appendChild(nightColours.colour(tone.name, tone.fallback))
                    if (writtenV31.add(tone.name)) {
                        nightV31Colours.documentElement.appendChild(nightV31Colours.colour(tone.name, tone.systemColor))
                    }
                }?.name
            } else {
                nightShade(colour)?.also { shade ->
                    if (written.add(shade.name)) nightColours.documentElement.appendChild(nightColours.colour(shade.name, shade.fallback))
                    stateLists.getOrPut(shade.name) { shade.stateList }
                }?.name
            }
            name?.let { index to it }
        }.toMap()
        if (names.isEmpty()) continue

        val copy = night.importNode(style, true) as Element
        val items = copy.childElements()
        for ((index, name) in names) items[index].textContent = "@color/$name"
        night.documentElement.appendChild(copy)
        changed += names.size
    }
    return changed
}

private fun Document.colour(name: String, value: String): Element =
    createElement("color").also {
        it.setAttribute("name", name)
        it.textContent = value
    }
