/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Why a Menu page could open its header over the Menu it came from (#276), read from 585 on
 * 2026-11-05. The obfuscated names are for reviewers; the code finds each by a kept string and its
 * shape.
 *
 * The pages the Menu opens in place (Settings and privacy, Help and support, a folder of
 * shortcuts) are a stack Facebook keeps per Context (585 LX/0BTH;), and every Menu built in that
 * Activity shares it: the Menu tab Facebook builds ahead of time and the side Menu are two section
 * trees on one stack. Each root section's children build (LX/0BSt;->A0o, the one method holding
 * "BookmarksQuery") puts its section context in a weak reference the stack keeps, so the stack
 * remembers only the Menu built last. A push or a pop changes the header at once and then asks only
 * that context for updateState:BookmarksRootSection.bumpSubpanelGeneration, which builds the page
 * under the header. When the Menu built last was the one off screen (on 585, a Menu built in the
 * first seconds after a cold start with Hide the Stories tray on), the header on screen moved and
 * the Menu under it stayed until Facebook restarted.
 *
 * The children build tells the extension each binding, with the context's section tree, and every
 * push and pop asks the newest context of each other tree on the stack for the same update first,
 * through a helper the patch adds to the stack. Facebook's own update then goes where it always did,
 * so every Menu builds the page and the one on screen shows it.
 */

/** The string only the root section's children build holds: the name of the bookmarks query it wires up. */
internal const val ROOT_SECTION_QUERY = "BookmarksQuery"

/** The state update a push or a pop asks the bound Menu for. */
internal const val PAGE_UPDATE = "updateState:BookmarksRootSection.bumpSubpanelGeneration"

/** The kept static on the section context class that copies a context into a section tree, whose second parameter is the tree's type. */
internal const val TREE_SETTER = "withSectionTree"

/** The helper added to the stack that hands a binding to the extension. */
internal const val BOUND_HELPER = "hushfacebookMenuBound"

/** The helper added to the stack that asks every other Menu on it for the page update. */
internal const val OTHERS_HELPER = "hushfacebookAskOtherMenus"

internal const val SUBPAGES_EXTENSION = "Lapp/morphe/extension/facebook/menu/MenuSubpages;"

internal const val WEAK_REFERENCE = "Ljava/lang/ref/WeakReference;"
private const val CONTEXT = "Landroid/content/Context;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val SUBPAGES = "Keep Menu pages in step"

/**
 * The children build's binding of the page stack to the section context: the context's type, the
 * Context field the build reads off it, the id and the lookup that answer the stack for that
 * Context, the stack's type and its weak reference field, the index of the store into that field
 * and the registers holding the stack and the context there.
 */
internal data class SubpageBinding(
    val contextType: String,
    val contextField: FieldReference,
    val id: Int,
    val lookup: MethodReference,
    val stack: String,
    val reference: FieldReference,
    val store: Int,
    val stackRegister: Int,
    val contextRegister: Int,
)

/** The page update a push or a pop builds: the update's type and the literal its constructor takes, and the context's state update call. */
internal data class SubpageUpdate(val type: String, val kind: Int, val request: MethodReference)

/**
 * Everything the patch changes: the root section's children build and its binding, the context's
 * tree field, the update, and the stack's push and pop with the index of their read of the
 * reference.
 */
internal data class MenuSubpages(
    val build: Method,
    val binding: SubpageBinding,
    val tree: FieldReference,
    val update: SubpageUpdate,
    val pages: List<Pair<Method, Int>>,
)

/**
 * Keeps every Menu on the page stack building the page it opens. Nameless, so the patches whose
 * pages it keeps working carry it. Warns and leaves the Menu as Facebook built it when any part
 * isn't found once.
 */
internal val menuSubpagesPatch = bytecodePatch {
    execute {
        keepMenuSubpagesLive()
    }
}

/** What the patch changed, or null with a warning when Facebook moved any of it. */
internal fun BytecodePatchContext.keepMenuSubpagesLive(): MenuSubpages? {
    val found = menuSubpages() ?: return null
    val (build, binding, tree, update, pages) = found
    val stack = mutableClassDefBy(binding.stack)
    if (stack.methods.any { it.name == BOUND_HELPER || it.name == OTHERS_HELPER }) {
        return warn("${binding.stack} already has a method named $BOUND_HELPER or $OTHERS_HELPER")
    }
    stack.methods.add(
        staticMethod(binding.stack, BOUND_HELPER, listOf(binding.stack, binding.contextType), 3, boundSmali(tree)),
    )
    stack.methods.add(staticMethod(binding.stack, OTHERS_HELPER, listOf(binding.stack), 8, othersSmali(binding, update)))
    mutableClassDefBy(build.definingClass).findMutableMethodOf(build).addInstructions(
        binding.store + 1,
        "invoke-static { v${binding.stackRegister}, v${binding.contextRegister} }, " +
            "${binding.stack}->$BOUND_HELPER(${binding.stack}${binding.contextType})V",
    )
    pages.forEach { (page, read) ->
        val register = (page.implementation!!.instructions.elementAt(read) as TwoRegisterInstruction).registerB
        stack.findMutableMethodOf(page).addInstructionsAtControlFlowLabel(
            read,
            "invoke-static/range { v$register .. v$register }, ${binding.stack}->$OTHERS_HELPER(${binding.stack})V",
        )
    }
    return found
}

/** The parts the patch needs, or null with a warning. Changes nothing. */
internal fun BytecodePatchContext.menuSubpages(): MenuSubpages? {
    val roots = classDefByStrings(ROOT_SECTION_QUERY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
    val found = roots.mapNotNull(::rootBinding)
    val (build, binding) = found.singleOrNull() ?: return warn(
        "expected one Menu root section binding its page stack beside \"$ROOT_SECTION_QUERY\", found ${found.size}",
    )
    if (binding.stackRegister > 15 || binding.contextRegister > 15) {
        return warn("${build.definingClass}->${build.name} binds the stack in v${binding.stackRegister} and v${binding.contextRegister}, past v15")
    }
    val context = classDefByOrNull(binding.contextType) ?: return warn("${binding.contextType} isn't in the app")
    val tree = treeField(context) ?: return warn(
        "${context.type} has no one static $TREE_SETTER naming the tree type of one of its fields",
    )
    val stack = classDefByOrNull(binding.stack) ?: return warn("${binding.stack} isn't in the app")
    val pages = pageChanges(stack, binding.reference)
    if (pages.isEmpty()) return warn("${stack.type} has no push or pop reading its Menu once and asking for \"$PAGE_UPDATE\"")
    val updates = pages.map { (page, _) -> pageUpdate(page) }.distinct()
    if (updates.size != 1) return warn("${stack.type}'s push and pop build their page update ${updates.size} ways")
    val update = updates.single() ?: return warn("${stack.type}'s push and pop don't build \"$PAGE_UPDATE\" the way 585 does")
    return MenuSubpages(build, binding, tree, update, pages)
}

private fun warn(detail: String): Nothing? {
    patchLog.warning("$SUBPAGES: $detail. Menu pages open as Facebook opens them.")
    return null
}

/** Hands the stack (p0), the context (p1) and the context's tree to the extension. */
internal fun boundSmali(tree: FieldReference): String =
    """
        iget-object v0, p1, ${field(tree)}
        invoke-static { p0, p1, v0 }, $SUBPAGES_EXTENSION->bound($OBJECT$OBJECT$OBJECT)V
        return-void
    """.trimIndent()

/**
 * Asks each context the extension answers for the stack (p0), every Menu but the one the stack
 * remembers, for the page update the push and pop build. The context's call returns at once when
 * its section is gone.
 */
internal fun othersSmali(binding: SubpageBinding, update: SubpageUpdate): String =
    """
        iget-object v0, p0, ${field(binding.reference)}
        const/4 v1, 0x0
        if-eqz v0, :ask
        invoke-virtual { v0 }, Ljava/lang/ref/Reference;->get()$OBJECT
        move-result-object v1
        :ask
        invoke-static { p0, v1 }, $SUBPAGES_EXTENSION->others($OBJECT$OBJECT)[$OBJECT
        move-result-object v1
        array-length v2, v1
        const/4 v3, 0x0
        :next
        if-ge v3, v2, :done
        aget-object v4, v1, v3
        check-cast v4, ${binding.contextType}
        const/4 v0, 0x0
        new-array v0, v0, [$OBJECT
        const v5, ${update.kind}
        new-instance v6, ${update.type}
        invoke-direct { v6, v0, v5 }, ${update.type}-><init>([${OBJECT}I)V
        const-string v0, "$PAGE_UPDATE"
        invoke-virtual { v4, v6, v0 }, ${method(update.request)}
        add-int/lit8 v3, v3, 0x1
        goto :next
        :done
        return-void
    """.trimIndent()

private fun staticMethod(owner: String, name: String, parameters: List<String>, registers: Int, smali: String) =
    ImmutableMethod(
        owner,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(registers),
    ).toMutable().apply { addInstructions(0, smali) }

private fun field(reference: FieldReference) = "${reference.definingClass}->${reference.name}:${reference.type}"

private fun method(reference: MethodReference) =
    "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

/** The one children build of [root] holding [ROOT_SECTION_QUERY] that binds the stack, with its binding. */
internal fun rootBinding(root: ClassDef): Pair<Method, SubpageBinding>? = root.methods
    .filter { holds(it, ROOT_SECTION_QUERY) }
    .mapNotNull { build -> subpageBinding(build)?.let { build to it } }.singleOrNull()

/**
 * The binding in [build], an instance method taking only the section context: eight instructions in
 * a row reading the context's Context, answering the stack for it through a static (Context, int)
 * lookup, and storing a new WeakReference to the context in a WeakReference field of the stack.
 * Null unless that run comes once.
 */
internal fun subpageBinding(build: Method): SubpageBinding? {
    if (AccessFlags.STATIC.isSet(build.accessFlags) || build.parameterTypes.size != 1) return null
    val implementation = build.implementation ?: return null
    val code = implementation.instructions.toList()
    val context = implementation.registerCount - 1
    val contextType = build.parameterTypes.single().toString()
    val found = (0..code.size - 8).mapNotNull { at -> bindingAt(code, at, context, contextType) }
    return found.singleOrNull()
}

private fun bindingAt(code: List<Instruction>, at: Int, context: Int, contextType: String): SubpageBinding? {
    val read = code[at]
    val contextField = read.field() ?: return null
    if (read.opcode != Opcode.IGET_OBJECT || contextField.type != CONTEXT) return null
    read as TwoRegisterInstruction
    if (read.registerB != context) return null

    val id = code[at + 1]
    if (id.opcode !in CONSTANTS || id !is NarrowLiteralInstruction || id !is OneRegisterInstruction) return null

    val lookup = code[at + 2]
    val call = lookup.method() ?: return null
    if (lookup.opcode != Opcode.INVOKE_STATIC || call.returnType != OBJECT) return null
    if (call.parameterTypes.map(CharSequence::toString) != listOf(CONTEXT, "I")) return null
    lookup as FiveRegisterInstruction
    if (lookup.registerCount != 2 || lookup.registerC != read.registerA || lookup.registerD != id.registerA) return null

    val result = code[at + 3]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val stack = (result as OneRegisterInstruction).registerA
    val cast = code[at + 4]
    val stackType = ((cast as? ReferenceInstruction)?.reference as? TypeReference)?.type ?: return null
    if (cast.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != stack) return null

    val created = code[at + 5]
    val createdType = ((created as? ReferenceInstruction)?.reference as? TypeReference)?.type
    if (created.opcode != Opcode.NEW_INSTANCE || createdType != WEAK_REFERENCE) return null
    val weak = (created as OneRegisterInstruction).registerA
    val init = code[at + 6]
    val constructor = init.method() ?: return null
    if (init.opcode != Opcode.INVOKE_DIRECT || constructor.definingClass != WEAK_REFERENCE || constructor.name != "<init>") return null
    init as FiveRegisterInstruction
    if (init.registerCount != 2 || init.registerC != weak || init.registerD != context) return null

    val store = code[at + 7]
    val reference = store.field() ?: return null
    if (store.opcode != Opcode.IPUT_OBJECT || reference.type != WEAK_REFERENCE || reference.definingClass != stackType) return null
    store as TwoRegisterInstruction
    if (store.registerA != weak || store.registerB != stack) return null
    return SubpageBinding(contextType, contextField, id.narrowLiteral, call, stackType, reference, at + 7, stack, context)
}

/** The instance field of [context] whose type the kept static [TREE_SETTER] (context, tree) names. */
internal fun treeField(context: ClassDef): FieldReference? {
    val setter = context.methods.singleOrNull { method ->
        method.name == TREE_SETTER && AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == context.type
    } ?: return null
    val tree = setter.parameterTypes[1].toString()
    return context.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == tree }
}

/**
 * The stack's push and pop: its instance methods holding [PAGE_UPDATE] that read [reference] once,
 * each with the index of that read.
 */
internal fun pageChanges(stack: ClassDef, reference: FieldReference): List<Pair<Method, Int>> = stack.methods
    .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && holds(it, PAGE_UPDATE) }
    .mapNotNull { page ->
        val reads = page.implementation!!.instructions.withIndex()
            .filter { (_, instruction) -> instruction.opcode == Opcode.IGET_OBJECT && instruction.field() == reference }
        reads.singleOrNull()?.let { page to it.index }
    }

/**
 * How [page] builds its update, read back from the [PAGE_UPDATE] string: the next virtual call takes
 * the update and that string; before the string, a new update made from an Object[] and an int, the
 * int a constant set last before it. Null when it's built any other way.
 */
internal fun pageUpdate(page: Method): SubpageUpdate? {
    val code = page.implementation!!.instructions.toList()
    val asks = code.withIndex().filter { (_, instruction) -> instruction.string() == PAGE_UPDATE }
    val ask = asks.singleOrNull()?.index ?: return null
    val request = code.drop(ask + 1).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL }?.method() ?: return null
    val parameters = request.parameterTypes.map(CharSequence::toString)
    if (request.returnType != "V" || parameters.size != 2 || parameters[1] != STRING) return null
    val type = parameters[0]
    val built = (ask - 1 downTo 0).firstOrNull { at ->
        val call = code[at].method()
        code[at].opcode == Opcode.INVOKE_DIRECT && call?.definingClass == type && call.name == "<init>" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("[$OBJECT", "I")
    } ?: return null
    val kindRegister = (code[built] as FiveRegisterInstruction).registerE
    val set = (built - 1 downTo 0).map { code[it] }.firstOrNull { it is OneRegisterInstruction && it.registerA == kindRegister }
    if (set == null || set.opcode !in CONSTANTS || set !is NarrowLiteralInstruction) return null
    return SubpageUpdate(type, set.narrowLiteral, request)
}

private val CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun holds(method: Method, string: String) = method.implementation?.instructions?.any { it.string() == string } == true
