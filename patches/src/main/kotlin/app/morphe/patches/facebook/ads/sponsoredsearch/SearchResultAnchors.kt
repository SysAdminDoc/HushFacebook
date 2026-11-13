/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredsearch

import app.morphe.patches.facebook.feed.treeFieldKey
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where a page of Facebook's search results comes in, and how Facebook itself tells an ad module
 * from the rest, on the 577 and 580 builds.
 *
 * A search results page (SERP) is a list of modules, one per block on screen: the people, the
 * posts, the pages, the videos, and the ads. Each module is built from a `SearchCombinedResultsEdge`
 * tree, and its constructor reads the edge's `result_role` (key 0xdffbfb58, the Java hashCode of
 * the name) with `getCachedEnum` into an enum field, falling back to the node's `module_role`. That
 * enum is the server's GraphQLGraphSearchResultRole, 274 constants on both builds whose names the
 * obfuscator keeps. The module class's own static initializer builds an ImmutableSet of four of
 * them, SEARCH_ADS, DEPENDENT_SEARCH_ADS, LATE_DEPENDENT_SEARCH_ADS and TOP_POSITION_SEARCH_ADS,
 * and its boolean method asks that set about the same field: that is Facebook's own "this module
 * is an ad". The enum has five more roles named after ads beside them (the Marketplace and
 * shoppable ones, the ads section's header and its floating See more), and nothing organic carries
 * any of the nine.
 *
 * Every page a search fetch builds goes through one class: a GraphQL result becomes a page in the
 * two builders that take (FbUserSession, GraphQLResult, SearchResultsMutableContext, ...), and the
 * Meta AI answer and the empty page are built the same way. Its one constructor takes the page's
 * module list and two lists of trees (tabs and filters) as ImmutableLists, then strings and flags.
 * The results list on screen is filled from the pages, so a module left out of the page's list
 * never reaches it, the same way Facebook's own module list converter already leaves some out.
 *
 * Read from 577 and 580 (2026-09-27): the page is `LX/BWZ;` on 580 and `LX/BXG;` on 577, the module
 * `LX/BVq;` and `LX/BW4;`, the role enum `LX/CKf;` and `LX/eii;`, the role field `A00` on both, and
 * the module list the page constructor's first ImmutableList parameter on both (the builders hand it
 * what the module converter, `A01`, returns). None of those names is used here.
 */
internal const val PATCH = "Hide sponsored search results"

internal const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val GRAPHQL_RESULT = "Lcom/facebook/graphql/executor/GraphQLResult;"
internal const val SEARCH_CONTEXT = "Lcom/facebook/search/results/model/SearchResultsMutableContext;"
internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
internal const val IMMUTABLE_SET = "Lcom/google/common/collect/ImmutableSet;"
private const val ENUM = "Ljava/lang/Enum;"

/** The edge field the module's role is read from. */
internal const val RESULT_ROLE_FIELD = "result_role"

/**
 * The four roles the module class's own ad set holds, the evidence the rest rests on. A build
 * whose set holds other roles, or fewer, stops the patch.
 */
internal val FACEBOOK_AD_ROLES = setOf(
    "SEARCH_ADS",
    "DEPENDENT_SEARCH_ADS",
    "LATE_DEPENDENT_SEARCH_ADS",
    "TOP_POSITION_SEARCH_ADS",
)

/**
 * Every role the extension hides, as its `SearchAdFilter.AD_ROLES` names them: Facebook's ad set
 * first, then the roles named after ads beside it. SearchAdRolesTest holds the two lists together,
 * and the patch holds the role enum to every one of them.
 */
internal val AD_ROLES = listOf(
    "SEARCH_ADS",
    "DEPENDENT_SEARCH_ADS",
    "LATE_DEPENDENT_SEARCH_ADS",
    "TOP_POSITION_SEARCH_ADS",
    "TOP_POSITION_SHOPPABLE_ADS",
    "MARKETPLACE_SEARCH_ADS",
    "MARKETPLACE_BOOSTED_LISTING_SEARCH_ADS",
    "SEARCH_ADS_DISCOVERY_HEADER",
    "SEARCH_ADS_FLOATING_SEE_MORE",
)

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun isStatic(accessFlags: Int) = AccessFlags.STATIC.isSet(accessFlags)

/** The names [enumClass]'s static initializer loads as strings. */
internal fun enumStrings(enumClass: ClassDef): Set<String> =
    enumClass.methods.filter { it.name == "<clinit>" }.flatMap { method -> method.body().mapNotNull { it.string() } }.toSet()

/** Whether [classDef] is the search result role enum: an enum that builds every one of [AD_ROLES]. */
internal fun isRoleEnum(classDef: ClassDef): Boolean =
    classDef.superclass == ENUM && enumStrings(classDef).containsAll(AD_ROLES)

/**
 * The constant name each static field of [enumClass] holds, read from its static initializer. 580
 * builds each constant with `new-instance` and an `invoke-direct` of the enum's constructor, whose
 * second register holds the name. 577 calls a static factory of the enum's own with the name
 * first, and stores what it answers. Moves between registers are followed; a field set any other
 * way isn't listed.
 */
internal fun enumConstantFields(enumClass: ClassDef): Map<String, String> {
    val type = enumClass.type
    val strings = mutableMapOf<Int, String>()
    val objects = mutableMapOf<Int, Int>()
    val names = mutableMapOf<Int, String>()
    val fields = mutableMapOf<String, String>()
    var next = 0
    // The name a factory call was just handed, for the move-result-object after it.
    var factoryName: String? = null
    for (instruction in enumClass.methods.filter { it.name == "<clinit>" }.flatMap { it.body() }) {
        val pending = factoryName
        factoryName = null
        when (instruction.opcode) {
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                val call = instruction.methodReference() ?: continue
                if (call.definingClass != type || call.returnType != type) continue
                factoryName = invokeRegisters(instruction).firstNotNullOfOrNull { strings[it] }
            }
            Opcode.MOVE_RESULT_OBJECT -> {
                val register = (instruction as OneRegisterInstruction).registerA
                objects.remove(register)
                strings.remove(register)
                if (pending != null) {
                    objects[register] = next
                    names[next++] = pending
                }
            }
            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> {
                val register = (instruction as OneRegisterInstruction).registerA
                strings[register] = instruction.string()!!
                objects.remove(register)
            }
            Opcode.NEW_INSTANCE -> {
                val register = (instruction as OneRegisterInstruction).registerA
                objects.remove(register)
                strings.remove(register)
                if (((instruction as ReferenceInstruction).reference as TypeReference).type == type) objects[register] = next++
            }
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                val move = instruction as TwoRegisterInstruction
                objects.remove(move.registerA)
                strings.remove(move.registerA)
                objects[move.registerB]?.let { objects[move.registerA] = it }
                strings[move.registerB]?.let { strings[move.registerA] = it }
            }
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> {
                val call = instruction.methodReference() ?: continue
                if (call.definingClass != type || call.name != "<init>") continue
                val registers = invokeRegisters(instruction)
                val built = objects[registers.getOrNull(0)] ?: continue
                strings[registers.getOrNull(1)]?.let { names[built] = it }
            }
            Opcode.SPUT_OBJECT -> {
                val field = instruction.fieldReference() ?: continue
                if (field.definingClass != type || field.type != type) continue
                val built = objects[(instruction as OneRegisterInstruction).registerA] ?: continue
                names[built]?.let { fields[field.name] = it }
            }
            else -> if (instruction.opcode.setsRegister() && instruction is OneRegisterInstruction) {
                objects.remove(instruction.registerA)
                strings.remove(instruction.registerA)
            }
        }
    }
    return fields
}

/** The registers an invoke names, in order: the five-register form's or the range's. */
internal fun invokeRegisters(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

/**
 * Whether [method] builds a search results page from a GraphQL result: it takes the user session,
 * the result and the search's mutable context first, and answers an object. The type it answers
 * is the page.
 */
internal fun isPageBuilder(method: Method): Boolean {
    val parameters = method.parameters()
    return method.implementation != null && parameters.size >= 3 &&
        parameters.subList(0, 3) == listOf(USER_SESSION, GRAPHQL_RESULT, SEARCH_CONTEXT) &&
        method.returnType.startsWith("L") && !method.returnType.startsWith("Ljava/")
}

/** The tree readers answering an enum field by its key and a fallback constant. */
private val ENUM_READERS = setOf("getCachedEnum", "getCachedNullableEnum")

/**
 * Whether [constructor] reads the edge's [RESULT_ROLE_FIELD] with an enum reader, as the module's
 * does: `getCachedEnum` up to 586, `getCachedNullableEnum` from 587 (arm64 `LX/0E8U;-><init>`), or
 * the same (int, Enum) read asked of a tree interface, the form 587's delegating trees read through.
 */
internal fun readsResultRole(constructor: Method): Boolean {
    val body = constructor.body()
    return constructor.name == "<init>" &&
        body.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == treeFieldKey(RESULT_ROLE_FIELD) } &&
        body.any { instruction ->
            val call = instruction.methodReference() ?: return@any false
            call.parameterTypes.map { it.toString() } == listOf("I", ENUM) && (call.name in ENUM_READERS ||
                (instruction.opcode == Opcode.INVOKE_INTERFACE && call.returnType == ENUM &&
                    !call.definingClass.startsWith("Ljava/") && !call.definingClass.startsWith("Landroid/")))
        }
}

/** The instance fields of [module] that hold a [role]. The patch wants exactly one. */
internal fun roleFields(module: ClassDef, role: String): List<Field> =
    module.fields.filter { it.type == role && !isStatic(it.accessFlags) }

/** The static `(ImmutableList)ImmutableList` methods [module] declares: its module list converter. */
internal fun moduleConverters(module: ClassDef): List<Method> = module.methods.filter {
    isStatic(it.accessFlags) && it.implementation != null && it.returnType == IMMUTABLE_LIST &&
        it.parameters() == listOf(IMMUTABLE_LIST)
}

/**
 * Whether [classDef] is a search result module of [role]: one instance field holding the role, a
 * constructor that reads [RESULT_ROLE_FIELD], and a module list converter.
 */
internal fun isModuleClass(classDef: ClassDef, role: String): Boolean =
    roleFields(classDef, role).size == 1 && classDef.methods.any(::readsResultRole) && moduleConverters(classDef).isNotEmpty()

/**
 * The role names [module]'s static initializer reads out of [role] to build an ImmutableSet with,
 * given [constants], the enum's field-to-name map. Empty when it builds no set.
 */
internal fun adSetRoles(module: ClassDef, role: String, constants: Map<String, String>): Set<String> {
    val body = module.methods.filter { it.name == "<clinit>" }.flatMap { it.body() }
    if (body.none { it.methodReference()?.definingClass == IMMUTABLE_SET }) return emptySet()
    return body.filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { it.fieldReference() }
        .filter { it.definingClass == role && it.type == role }
        .map { constants[it.name] ?: "?${it.name}" }
        .toSet()
}

/** Whether [call] names a constructor of [page]. */
private fun isConstructorCall(call: MethodReference?, page: String) = call != null && call.definingClass == page && call.name == "<init>"

/**
 * The declared parameters of [page]'s constructor [pageConstructor] that a builder among [builders]
 * fills with what [converter] answers: the module list. The last write of the argument's register
 * before the constructor call is followed back through moves to a `move-result-object`, and the
 * call right before that must be the converter. A build where no builder shows it, or where two
 * parameters are filled that way, has no one module list to filter.
 */
internal fun moduleListParameters(builders: List<Method>, pageConstructor: Method, converter: Method): Set<Int> {
    val page = pageConstructor.definingClass
    val parameters = pageConstructor.parameters()
    val found = mutableSetOf<Int>()
    for (builder in builders) {
        val body = builder.body()
        for ((index, instruction) in body.withIndex()) {
            if (!isConstructorCall(instruction.methodReference(), page)) continue
            val call = instruction.methodReference()!!
            if (call.parameterTypes.map { it.toString() } != parameters) continue
            val registers = invokeRegisters(instruction)
            // Register 0 is the new page; the declared parameters follow, one register each for the
            // objects this constructor takes (a wide one would take two, and it has none to reach).
            var register = 1
            for ((parameter, type) in parameters.withIndex()) {
                if (type == IMMUTABLE_LIST && registers.getOrNull(register)?.let { writtenBy(body, index, it, converter) } == true) {
                    found += parameter
                }
                register += if (type == "J" || type == "D") 2 else 1
            }
        }
    }
    return found
}

/**
 * Whether the value in [register] at instruction [before] of [body] is what [converter] answered,
 * by the instructions before it in order: moves are followed back to their source, a
 * `move-result-object` must follow a call to [converter], and any other write says no.
 */
internal fun writtenBy(body: List<Instruction>, before: Int, register: Int, converter: Method): Boolean {
    var wanted = register
    for (index in before - 1 downTo 0) {
        val instruction = body[index]
        when (instruction.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                val move = instruction as TwoRegisterInstruction
                if (move.registerA == wanted) wanted = move.registerB
            }
            Opcode.MOVE_RESULT_OBJECT -> if ((instruction as OneRegisterInstruction).registerA == wanted) {
                val call = body.getOrNull(index - 1)?.methodReference() ?: return false
                return call.definingClass == converter.definingClass && call.name == converter.name &&
                    call.returnType == converter.returnType && call.parameterTypes.map { it.toString() } == converter.parameters()
            }
            else -> if (instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == wanted) {
                return false
            }
        }
    }
    return false
}
