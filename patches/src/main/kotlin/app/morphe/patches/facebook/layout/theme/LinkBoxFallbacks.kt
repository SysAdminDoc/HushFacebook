/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The link box under a Home link post stayed #333334 with Material You on (#37 leftover). Read from
 * 582, 2026-10-14; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * Every reader of LINK_BOX_COLOUR_CONFIG already sends the config's parse to parseLinkBox. When the
 * config's string is empty, four of them take their dark colour from somewhere the palette never saw:
 *  - FDSPostLinkAttachment's render (arm64 LX/Vzq;->render) unboxes a colour a parent hands it as a
 *    prop (LX/Vzq;->A04, an optional holding an Integer);
 *  - two renders and the attachment footer (LX/TUn;->render, LX/4sz;->A0E, LX/8MA;->render) ask a
 *    second config for a grey, an enum whose constants are DEFAULT, GRAY_10 (#1C1C1D) and GRAY_25
 *    (#3B3C3E), and unbox its Integer;
 *  - LX/8MA;->render, when that grey is DEFAULT, parses a colour string from the attachment's own
 *    GraphQL model, and then hands light and dark colours to FDS's chooser (LX/24i;->A07), which
 *    answers the dark one when Facebook's own dark check says yes.
 * The first two land in the register the config's parse does, so they take the same route: the
 * dark-branch stand-in. The chooser is FDS's own, nineteen more callers use it (FDSMediaCard's
 * background, the reactors' faces, the account list), and the colour it answers on its dark side is
 * one Facebook picked for its dark theme only, the same as a colour written on that branch.
 */

private const val CONTEXT = "Landroid/content/Context;"

private const val STRING_LENGTH = "Ljava/lang/String;->length()I"

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.register(): Int = (this as OneRegisterInstruction).registerA

/** The index each branch at [index] jumps to, or null for one that leaves the method's code. */
private fun jumpTarget(instructions: List<Instruction>, index: Int): Int? {
    val addresses = instructions.runningFold(0) { address, it -> address + it.codeUnits }
    val target = addresses[index] + (instructions[index] as OffsetInstruction).codeOffset
    return addresses.indexOf(target).takeIf { it in instructions.indices }
}

/**
 * The indexes of the `move-result`s on [method]'s empty-config fall-through that give the link box its
 * dark colour: after `if-nez` on the config string's length, up to the first instruction anything
 * jumps to or the first `return`, `goto` or `throw`, each int answer moved into the register the
 * config's parse ([PARSE_LINK_BOX]) answers into. Empty for a method [hookLinkBoxColour] hasn't
 * hooked. One already handed to [DARK_BRANCH] isn't listed again.
 *
 * A reader whose empty string jumps away (`if-lez` or `if-eqz` on the length) mostly lands on the
 * token's colour route one themes, which stays. 586's attachment footer (arm64 `LX/09Ce;->A00`) jumps
 * instead to a block of its own, after a `return`, that unboxes the second config's grey, so a block
 * like that, which only this branch reaches, gives its unboxings ([unboxesColour]) the same way.
 */
internal fun linkBoxFallbacks(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val literal = code.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral in LINK_BOX_COLOUR_CONFIGS }
    if (literal < 0) return emptyList()
    val parse = code.indices.singleOrNull { code[it].referenceText() == PARSE_LINK_BOX } ?: return emptyList()
    if (code.getOrNull(parse + 1)?.opcode != Opcode.MOVE_RESULT) return emptyList()
    val colour = code[parse + 1].register()

    val text = (literal + 1 until code.size).firstOrNull { code[it].opcode == Opcode.MOVE_RESULT_OBJECT } ?: return emptyList()
    val string = code[text].register()
    val length = (text + 1 until code.size).firstOrNull { index ->
        code[index].referenceText() == STRING_LENGTH && (code[index] as? FiveRegisterInstruction)?.registerC == string
    } ?: return emptyList()
    val branch = length + 2
    val branches = setOf(Opcode.IF_NEZ, Opcode.IF_LEZ, Opcode.IF_EQZ)
    if (code.getOrNull(length + 1)?.opcode != Opcode.MOVE_RESULT || code.getOrNull(branch)?.opcode !in branches ||
        code[branch].register() != code[length + 1].register()
    ) {
        return emptyList()
    }

    val targets = branchTargets(code)
    val leaves = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.THROW)
    val jumped = code[branch].opcode != Opcode.IF_NEZ
    val start = if (jumped) {
        val block = jumpTarget(code, branch) ?: return emptyList()
        if (code.getOrNull(block - 1)?.opcode !in leaves || jumpsInto(code, block) != 1) return emptyList()
        block
    } else {
        branch + 1
    }
    // The jumped-to block's first instruction is the branch's own target.
    val scan = if (jumped) start + 1 else start
    val end = (scan until code.size).firstOrNull { it in targets || code[it].opcode in leaves } ?: code.size
    return (start until end).filter { index ->
        val before = code.getOrNull(index - 1) ?: return@filter false
        val call = (before as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode == Opcode.MOVE_RESULT && code[index].register() == colour &&
            call != null && call.returnType == "I" && call.toString() != DARK_BRANCH &&
            code.getOrNull(index + 1)?.referenceText() != DARK_BRANCH &&
            (!jumped || unboxesColour(before.opcode, call))
    }
}

/**
 * Whether [call] unboxes a colour: an Integer's `intValue`, as the second config's grey is, or a
 * static (Object) int call, as a parent's colour prop was.
 */
private fun unboxesColour(opcode: Opcode, call: MethodReference): Boolean =
    (call.name == "intValue" && call.definingClass in setOf("Ljava/lang/Number;", "Ljava/lang/Integer;")) ||
        ((opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;"))

/** How many branches and switch cases in [instructions] land on [index]. */
private fun jumpsInto(instructions: List<Instruction>, index: Int): Int {
    val addresses = instructions.runningFold(0) { address, it -> address + it.codeUnits }
    val target = addresses[index]
    return instructions.withIndex().sumOf { (at, instruction) ->
        when {
            instruction !is OffsetInstruction -> 0
            instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH -> {
                val payload = instructions[addresses.indexOf(addresses[at] + instruction.codeOffset)] as SwitchPayload
                payload.switchElements.count { addresses[at] + it.offset == target }
            }
            else -> if (addresses[at] + instruction.codeOffset == target) 1 else 0
        }
    }
}

/**
 * Hands each link box colour [linkBoxFallbacks] finds to [DARK_BRANCH] on its register, right after
 * the `move-result`. Where the next instruction is one something jumps to, the jump still lands on it
 * and skips the stand-in, so only the empty-config path takes it. Runs after [hookLinkBoxColour].
 * Answers how many it changed.
 */
internal fun BytecodePatchContext.hookLinkBoxFallbacks(): Int {
    val owners = mutableSetOf<String>()
    // Only a method hookLinkBoxColour sent to PARSE_LINK_BOX has fallbacks to find (#236).
    classDefForEachReferencing(names = listOf(PARSE_LINK_BOX.substringAfter("->").substringBefore('('))) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        if (classDef.methods.any { linkBoxFallbacks(it).isNotEmpty() }) owners += classDef.type
    }
    return owners.sumOf { type -> mutableClassDefBy(type).methods.sumOf { it.recolourLinkBoxFallbacks() } }
}

/** [hookLinkBoxFallbacks] for one method. */
internal fun MutableMethod.recolourLinkBoxFallbacks(): Int {
    val sites = linkBoxFallbacks(this)
    sites.asReversed().forEach { index ->
        val register = implementation!!.instructions[index].register()
        addInstructions(
            index + 1,
            """
                invoke-static/range { v$register .. v$register }, $DARK_BRANCH
                move-result v$register
            """,
        )
    }
    return sites.size
}

/** FDS's light or dark chooser: the one instance `(Context, int, int) int` method on [methods]' class. */
internal fun darkPicker(methods: Iterable<Method>): Method {
    val found = methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, "I", "I") && it.implementation != null
    }
    return found.singleOrNull()
        ?: throw PatchException("FDS's resolver class has ${found.size} instance (Context, int, int) int methods, expected its chooser")
}

/**
 * The index of the chooser's `return` of its dark colour: the instruction right after
 * `invoke {..}, a (Context) boolean check; move-result vR; if-eqz vR, :light`, returning the last
 * parameter, while `:light` returns the one before it. Refuses any other shape, and a return
 * something jumps to, which wouldn't only run on the dark side.
 */
internal fun darkPickReturn(method: Method): Int {
    val code = method.implementation!!.instructions.toList()
    val dark = method.implementation!!.registerCount - 1
    val light = dark - 1
    val targets = branchTargets(code)
    val found = (0 until code.size - 3).filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        val result = code[index + 1]
        val branch = code[index + 2]
        val answer = code[index + 3]
        call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) &&
            result.opcode == Opcode.MOVE_RESULT && branch.opcode == Opcode.IF_EQZ && branch.register() == result.register() &&
            answer.opcode == Opcode.RETURN && answer.register() == dark && index + 3 !in targets &&
            jumpTarget(code, index + 2)?.let { code[it].opcode == Opcode.RETURN && code[it].register() == light } == true
    }
    return found.singleOrNull()?.plus(3)
        ?: throw PatchException("${method.definingClass}->${method.name} doesn't answer its dark colour the way FDS's chooser did")
}

/**
 * Hands the colour FDS's chooser answers on its dark side to [DARK_BRANCH]: a grey Facebook's dark
 * theme lists takes the palette's neutral, and the light side stays Facebook's. The chooser lives on
 * the class of [darkCheck], FDS's view resolver. A second call finds the stand-in in place.
 */
internal fun BytecodePatchContext.hookDarkPick(darkCheck: String) {
    val resolver = mutableClassDefBy(darkCheck.substringBefore("->"))
    val picker = darkPicker(resolver.methods)
    val method = resolver.methods.single { it.name == picker.name && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, "I", "I") }
    if (method.implementation!!.instructions.any { it.referenceText() == DARK_BRANCH }) return
    val answer = darkPickReturn(method)
    val register = method.implementation!!.registerCount - 1
    method.addInstructions(
        answer,
        """
            invoke-static/range { v$register .. v$register }, $DARK_BRANCH
            move-result v$register
        """,
    )
}
