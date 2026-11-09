/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where the Upgrades and Also from Meta sections of Facebook's Menu come from, on the 577 and 580
 * builds.
 *
 * The Menu (the bookmarks screen, opened from the Menu tab or from the Profile tab's corner) is a
 * list of Litho sections under a root section. Every group below the shortcuts is typed by one
 * enum, kept under its own names: FEATURED, PROFILE, PRODUCTS, HELP, SETTINGS, LOGOUT,
 * PRODUCTS_FROM_FACEBOOK, COMMUNITY_RESOURCES, UPSELL and a dozen more. For each group the root
 * adds two sections. One is the native section Facebook draws itself, with the group's collapsible
 * header ("Upgrades, header. Section is expanded.", the "%1$s, header" string its header component
 * formats) and its rows. The other carries whatever the server sends to go above the group, in its
 * place or below it (positions ABOVE_NATIVE, REPLACE_NATIVE and BELOW_NATIVE). Upgrades is the
 * UPSELL group, whose builder tags its section `upsell_bookmark_section_test_key`, and Also from
 * Meta is PRODUCTS_FROM_FACEBOOK, tagged `products_from_facebook_test_key`. Their titles and cards
 * come from the server, so nothing in the app spells them out.
 *
 * The native section's children builder is the one method in either build that loads
 * `upsell_bookmarks_key`; it reads its group from its model through the one call in it that
 * answers the enum. The server section's children builder is the one that loads all three
 * positions, and it reads its group from a field of its own. Both hand back an empty list of
 * children on some paths of their own, which is what the hooks hand back for a hidden group. The
 * classes and every member are Redex names (the native section is `9KE` on 580 and `8o2` on 577),
 * so they're found by those strings and by the enum's kept names.
 */
internal const val PATCH = "Hide Menu promotions"

/** The native section's children builder tags the Upgrades group's list with this key. */
internal const val NATIVE_SECTION_KEY = "upsell_bookmarks_key"

/** Where the server's part of a group goes, loaded by its section's children builder. */
internal val SERVER_POSITIONS = listOf("ABOVE_NATIVE", "REPLACE_NATIVE", "BELOW_NATIVE")

/** The enum names of the two groups the patch hides. */
internal const val UPGRADES = "UPSELL"
internal const val ALSO_FROM_META = "PRODUCTS_FROM_FACEBOOK"

/**
 * Names the Menu's group enum carries. The two hidden groups, and the ones the Menu keeps, so an
 * enum that only happens to name the first two isn't taken for it.
 */
internal val SECTION_NAMES = listOf(UPGRADES, ALSO_FROM_META, "COMMUNITY_RESOURCES", "HELP", "SETTINGS", "LOGOUT")

/** The builder tags of the two groups' native sections, set where the root adds each group. */
internal const val UPGRADES_TEST_KEY = "upsell_bookmark_section_test_key"
internal const val ALSO_FROM_META_TEST_KEY = "products_from_facebook_test_key"

private fun Method.isInstanceWithBody(): Boolean =
    implementation != null && !AccessFlags.STATIC.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)

/** A section's children builder: an instance method taking the section context and handing back an object. */
private fun isChildrenBuilder(method: Method): Boolean =
    method.isInstanceWithBody() && method.parameterTypes.size == 1 && method.returnType.startsWith("L")

/**
 * The native group section's children builder: loads [NATIVE_SECTION_KEY]. Up to 581 that's the
 * section's own builder over the section context. 582's section (LX/cTm;->A2F) hands the context
 * and each of its fields to a singleton's method (LX/cTw;->A01, 16 parameters, context first),
 * which holds the body, so an instance method taking an object first and handing one back counts.
 */
internal fun isNativeSectionChildren(method: Method): Boolean =
    method.isInstanceWithBody() && method.parameterTypes.firstOrNull()?.startsWith("L") == true &&
        method.returnType.startsWith("L") && holdsString(method, NATIVE_SECTION_KEY)

/** The server group section's children builder: loads each of [SERVER_POSITIONS]. */
internal fun isServerSectionChildren(method: Method): Boolean =
    isChildrenBuilder(method) && SERVER_POSITIONS.all { holdsString(method, it) }

/**
 * Where a method puts its section's group: the instruction that writes it, the register it goes to
 * and the group enum's type.
 */
internal data class GroupRead(val index: Int, val register: Int, val type: String)

private fun Method.instructionList(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/**
 * The native section's group: the `move-result-object` after the one call in [method] that answers
 * a type [isGroupEnum] takes. Null unless there's exactly one such call and its answer is kept.
 */
internal fun nativeGroupRead(method: Method, isGroupEnum: (String) -> Boolean): GroupRead? {
    val instructions = method.instructionList()
    val calls = instructions.indices.filter { index ->
        val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[index].opcode.name.startsWith("invoke-") && call != null && isGroupEnum(call.returnType)
    }
    val call = calls.singleOrNull() ?: return null
    val result = instructions.getOrNull(call + 1) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val type = (((instructions[call] as ReferenceInstruction).reference) as MethodReference).returnType
    return GroupRead(call + 1, (result as OneRegisterInstruction).registerA, type)
}

/**
 * The server section's group: the one `iget-object` in [method] of a field its own class declares
 * with a type [isGroupEnum] takes. Null unless there's exactly one.
 */
internal fun serverGroupRead(method: Method, isGroupEnum: (String) -> Boolean): GroupRead? {
    val instructions = method.instructionList()
    val reads = instructions.indices.filter { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.IGET_OBJECT && field != null &&
            field.definingClass == method.definingClass && isGroupEnum(field.type)
    }
    val read = reads.singleOrNull() ?: return null
    val field = (instructions[read] as ReferenceInstruction).reference as FieldReference
    return GroupRead(read, (instructions[read] as OneRegisterInstruction).registerA, field.type)
}

/** Where the native section reads whether its group is folded: the instruction and the register it goes to. */
internal data class FoldRead(val index: Int, val register: Int)

/**
 * Whether the native section's group is folded: the one `iget-boolean` before [group] of a field of
 * a class [method] casts to there, its own class aside, as long as nothing writes its register and
 * no branch lands between it and [group]. On 584 that's the section's state (`LX/Nt0;`, cast from
 * its component's state field) and its A01, which a tap on the header flips (issue #224). While
 * it's set the section answers its header alone. 586 (`LX/0D4P;->A0s` arm64) reads `LX/0a1g;->A01`
 * into v0, copies it at once with `move/from16 v23, v0` and reuses v0 before its group, so when the
 * read's register doesn't last, a single plain move that is its only use is followed to the copy.
 * Null unless there's exactly one.
 */
internal fun foldRead(method: Method, group: GroupRead): FoldRead? {
    val instructions = method.instructionList()
    val before = instructions.take(group.index)
    val cast = before.filter { it.opcode == Opcode.CHECK_CAST }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet() - method.definingClass
    val reads = before.indices.filter { index ->
        val field = (before[index] as? ReferenceInstruction)?.reference as? FieldReference
        before[index].opcode == Opcode.IGET_BOOLEAN && field != null && field.definingClass in cast
    }
    val read = reads.singleOrNull() ?: return null
    val targets = branchTargets(instructions)
    fun lasting(at: Int): FoldRead? {
        val register = (instructions[at] as OneRegisterInstruction).registerA
        val between = (at + 1)..group.index
        if (between.any { writesRegister(instructions[it], register) || it in targets }) return null
        return FoldRead(at, register)
    }
    return lasting(read) ?: method.literalReads(read).singleOrNull()?.takeIf { copy ->
        copy < group.index && instructions[copy].opcode in PLAIN_MOVES &&
            (instructions[copy] as TwoRegisterInstruction).registerB == (instructions[read] as OneRegisterInstruction).registerA &&
            (read + 1..copy).none { it in targets }
    }?.let(::lasting)
}

/** Where the native section reads whether its group starts open: the instruction and the register it goes to. */
internal data class StartRead(val index: Int, val register: Int)

/**
 * Whether the native section's group starts open, which only its header goes by: the one
 * `iget-boolean` before [group] of a field of [method]'s own class whose value is read once, after
 * [group], by an `xor-int/lit8 vX, it, 0x1` that an `iput-boolean vX` right after stores into
 * another class. The header takes that as whether it starts collapsed, seeds its chevron and its
 * "Section is collapsed" label from it and flips them itself on a tap, so a fold kept open (issue
 * #224) needs it too, or the header points down over open rows. On 584 that's `LX/Lmh;->A09`
 * (the section's state starts folded when it's false), stored as the header's `LX/Lmm;->A07`. 585
 * (`LX/0Lf0;->A0o` arm64) reads `A09` into v0 and copies it at once with `move/from16 v24, v0`
 * before v0 is reused, then negates v24 into the header's `LX/0IqZ;->A07`; there the flag is the
 * copy, so [StartRead] names the move and v24. Null unless there's exactly one, nothing writes the
 * register it ends up in and no branch lands before [group].
 */
internal fun startRead(method: Method, group: GroupRead): StartRead? {
    val instructions = method.instructionList()
    val targets = branchTargets(instructions)
    val starts = (0 until group.index).mapNotNull { read ->
        val field = (instructions[read] as? ReferenceInstruction)?.reference as? FieldReference
        if (instructions[read].opcode != Opcode.IGET_BOOLEAN || field?.definingClass != method.definingClass) {
            return@mapNotNull null
        }
        var at = read
        var use = method.literalReads(read).singleOrNull() ?: return@mapNotNull null
        // 585 copies the flag into a high register first, so a single plain move is followed to the copy.
        if (instructions[use].opcode in PLAIN_MOVES && use < group.index &&
            (instructions[use] as TwoRegisterInstruction).registerB == (instructions[read] as OneRegisterInstruction).registerA
        ) {
            if ((read + 1 until use).any { it in targets }) return@mapNotNull null
            at = use
            use = method.literalReads(at).singleOrNull() ?: return@mapNotNull null
        }
        val register = (instructions[at] as OneRegisterInstruction).registerA
        val between = (at + 1)..group.index
        if (between.any { writesRegister(instructions[it], register) || it in targets }) return@mapNotNull null
        if (use > group.index && seedsAnother(method, instructions, use, register)) StartRead(at, register) else null
    }
    return starts.singleOrNull()
}

private val PLAIN_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/** Whether [index] negates [register] into a register the next instruction stores into a boolean of another class. */
private fun seedsAnother(method: Method, instructions: List<Instruction>, index: Int, register: Int): Boolean {
    val negate = instructions[index]
    if (negate.opcode != Opcode.XOR_INT_LIT8 || (negate as TwoRegisterInstruction).registerB != register) return false
    if ((negate as NarrowLiteralInstruction).narrowLiteral != 1) return false
    val store = instructions.getOrNull(index + 1) ?: return false
    val field = (store as? ReferenceInstruction)?.reference as? FieldReference ?: return false
    return store.opcode == Opcode.IPUT_BOOLEAN && (store as TwoRegisterInstruction).registerA == negate.registerA &&
        field.definingClass != method.definingClass
}

/**
 * The children list builder a section hands its children back through: the class [method] creates
 * with a no-argument constructor, and the field of that class, of [method]'s return type, it reads
 * the finished list from.
 */
internal data class ChildrenList(val builder: String, val children: String)

private val DIRECT_INVOKES = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
private val STATIC_INVOKES = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/**
 * [method]'s [ChildrenList], or null unless exactly one field of a list builder it makes is read
 * for its return type. It makes one by creating a class and constructing it with no arguments, or,
 * as 582's LX/cTw;->A01 does through LX/Gwo;->A0d, by calling a static method [resolve] finds whose
 * whole body is just that ([makesPlain]).
 */
internal fun childrenList(method: Method, resolve: (MethodReference) -> Method? = { null }): ChildrenList? {
    val instructions = method.instructionList()
    val created = instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet()
    val constructed = instructions.filter { it.opcode in DIRECT_INVOKES }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.name == "<init>" && it.parameterTypes.isEmpty() && it.definingClass in created }
        .map { it.definingClass }
        .toSet()
    val factoryMade = instructions.filter { it.opcode in STATIC_INVOKES }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
        .toSet()
        .filter { call -> resolve(call)?.let { makesPlain(it, call.returnType) } == true }
        .map { it.returnType }
    val made = constructed + factoryMade
    val fields = instructions.filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        .filter { it.definingClass in made && it.type == method.returnType }
        .map { "${it.definingClass}->${it.name}:${it.type}" }
        .toSet()
    val field = fields.singleOrNull() ?: return null
    return ChildrenList(field.substringBefore("->"), field)
}

/** Whether [factory]'s whole body creates a [type], constructs it with no arguments and hands it back. */
private fun makesPlain(factory: Method, type: String): Boolean {
    val code = factory.instructionList()
    if (code.size != 3 || code[0].opcode != Opcode.NEW_INSTANCE || code[1].opcode !in DIRECT_INVOKES ||
        code[2].opcode != Opcode.RETURN_OBJECT
    ) {
        return false
    }
    val init = (code[1] as ReferenceInstruction).reference as? MethodReference ?: return false
    return ((code[0] as ReferenceInstruction).reference as TypeReference).type == type &&
        init.definingClass == type && init.name == "<init>" && init.parameterTypes.isEmpty()
}
