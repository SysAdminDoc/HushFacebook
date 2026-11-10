/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
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
 * How Facebook picks the order of a post's comments, on the 577 and 580 builds.
 *
 * A comment sheet (the flyout a feed post, a video or a reel opens its comments in) gets its
 * comments from one GraphQL request, built from a FetchFeedbackParams, a class Redex keeps. Its
 * builder, a method that takes FetchFeedbackParams and the FbUserSession, turns the params' strings
 * into the request's variables, `feedback_id`, `focused_comment_id` and
 * `comment_rendering_intent_token` among them. The last one is the order, and it goes into the
 * request only when it isn't empty. The sheet's own FeedbackParams (kept too) calls it
 * commentRenderingIntentToken, and an ordinary sheet has none, so the request leaves the variable
 * out and Facebook's servers choose. Their answer names the order they chose
 * (`comment_rendering_instance.selected_intent.intent_token`), and the sheet's sort menu lists the
 * orders the post offers, each with its own `intent_token`.
 *
 * Picking an order in that menu runs the sheet's pick handler, the one method that takes the
 * FbUserSession and the picked token and fetches "UpdateCommentOrderType_FetchFeedbackQuery". It
 * copies the sheet's FeedbackParams with the picked token, and the copy's request goes out with it.
 * The sheet keeps its original params, though, so a refresh sends no order again, and reopening
 * the post starts over from Facebook's choice. That's the "Most relevant keeps coming back" people
 * report.
 *
 * A link to one comment (a notification about a reply, say) opens the sheet with a focused comment
 * id. Facebook's feedback controller keeps a separate request builder for that case, which puts the
 * comment's id into the request as `focused_comment_id`. A post's own page, where many
 * notifications land, is another request altogether (FetchSingleStoryParams), which carries the
 * order its link names, and this patch leaves it alone.
 *
 * The tokens are Facebook's own: RANKED_FILTERED_INTENT_V1 is Most relevant,
 * RECENT_ACTIVITY_INTENT_V1 is Newest and RANKED_UNFILTERED_CHRONOLOGICAL_REPLIES_INTENT_V1 is All
 * comments, the three facebook.com asks for too. Both builds carry all three among the default
 * values of their MobileConfig strings.
 *
 * So the patch changes the one place every such request passes: the params' constructor. First
 * thing there, the extension sees the order the request would carry, the post's feedback id and
 * the focused comment id, and answers the order to send. It keeps an order that's there (a pick in
 * the menu, a list that names its own, a restored request) and a link to a comment, and fills in
 * the chosen default only where Facebook's servers would have chosen. A second call, first thing
 * in the pick handler, tells the extension a pick is on its way, so the order picked for a post is
 * asked for again when that post's comments are fetched with none, until Facebook restarts.
 */
internal const val PATCH = "Default comment order"

/** The params of a comment sheet's request. Redex keeps the name. */
internal const val FETCH_FEEDBACK_PARAMS = "Lcom/facebook/api/ufiservices/common/FetchFeedbackParams;"

internal const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val STRING = "Ljava/lang/String;"

/** The request variables whose values the builder reads from the params, each a literal it keeps. */
internal const val ORDER_VARIABLE = "comment_rendering_intent_token"
internal const val FEEDBACK_ID_VARIABLE = "feedback_id"
internal const val FOCUSED_COMMENT_VARIABLE = "focused_comment_id"

/** The request a pick in the sheet's sort menu sends, which its pick handler names. */
internal const val PICK_QUERY = "UpdateCommentOrderType_FetchFeedbackQuery"

/** The extension's answer to the order a request would carry. */
internal const val REQUESTED_ORDER = "$EXTENSION_PACKAGE/comments/DefaultCommentOrder;->" +
    "requestedOrder(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

/** Tells the extension an order was picked in a comment sheet's menu. */
internal const val PICKED = "$EXTENSION_PACKAGE/comments/DefaultCommentOrder;->picked(Ljava/lang/String;)V"

/** How far above a variable's name the builder may read its value. Both builds read it 2 to 5 before. */
private const val READ_WINDOW = 6

/**
 * Where [code] loads [variable]'s name: a literal of its own, or the call asking a string table
 * [resolve] finds for it.
 */
private fun variableLoads(code: List<Instruction>, variable: String, resolve: (MethodReference) -> Method?): List<Int> =
    code.indices.filter { code[it].string == variable || tableStringAt(code, it, resolve) == variable }

/**
 * The string tables among the classes holding the request variables' names, for [requestFields]
 * and [storyOrderField]: 586's Android 9 build asks them for the names.
 */
internal fun BytecodePatchContext.variableTables(): (MethodReference) -> Method? {
    val holders = listOf(ORDER_VARIABLE, FEEDBACK_ID_VARIABLE, FOCUSED_COMMENT_VARIABLE)
        .flatMap { classDefByStrings(it, StringComparisonType.EQUALS) }
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
    return { call -> holders[call.definingClass]?.let { resolveStatic(it, call) } }
}

/** The params' fields the builder turns into the order, the feedback id and the focused comment id. */
internal data class RequestFields(val order: String, val feedbackId: String, val focusedComment: String)

/** The constructor's registers that hold those three when it stores them. */
internal data class RequestRegisters(val order: Int, val feedbackId: Int, val focusedComment: Int)

private val Instruction.string: String?
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private val Instruction.field: FieldReference?
    get() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference.key() = "$definingClass->$name:$type"

/** Whether [instruction] can write [register], a wide write counting for both halves. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    val opcode = instruction.opcode
    if (!opcode.setsRegister() || instruction !is OneRegisterInstruction) return false
    val first = instruction.registerA
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

/**
 * The String field of [owner] whose value the builder holds when it loads the variable's name at
 * [literal]: the nearest read of such a field in the few instructions above it, with nothing in
 * between writing the register it went to. Null when there's none.
 */
private fun fieldReadAbove(code: List<Instruction>, literal: Int, owner: String = FETCH_FEEDBACK_PARAMS): String? {
    for (index in literal - 1 downTo maxOf(0, literal - READ_WINDOW)) {
        val read = code[index]
        if (read.opcode != Opcode.IGET_OBJECT) continue
        val field = read.field ?: continue
        if (field.definingClass != owner || field.type != STRING) continue
        val register = (read as OneRegisterInstruction).registerA
        if (code.subList(index + 1, literal).any { writes(it, register) }) return null
        return field.key()
    }
    return null
}

/**
 * The fields [method] reads the order, the feedback id and the focused comment id from, when it's
 * the builder of a comment sheet's request. Null for any other method.
 *
 * The builder takes FetchFeedbackParams and loads each variable's name once, right after reading
 * the value from a String field of the params. The three fields differ. 586's Android 9 build asks
 * a string table for a name instead, which [resolve] finds ([variableLoads]).
 */
internal fun requestFields(method: Method, resolve: (MethodReference) -> Method? = { null }): RequestFields? {
    if (method.parameterTypes.none { it.toString() == FETCH_FEEDBACK_PARAMS }) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    fun readFor(variable: String): String? =
        variableLoads(code, variable, resolve).singleOrNull()?.let { fieldReadAbove(code, it) }
    val order = readFor(ORDER_VARIABLE) ?: return null
    val feedbackId = readFor(FEEDBACK_ID_VARIABLE) ?: return null
    val focusedComment = readFor(FOCUSED_COMMENT_VARIABLE) ?: return null
    if (setOf(order, feedbackId, focusedComment).size != 3) return null
    return RequestFields(order, feedbackId, focusedComment)
}

/** How many registers a parameter of this type takes. */
private fun CharSequence.width(): Int = if (toString() == "J" || toString() == "D") 2 else 1

/** The register past the locals, which holds `this` in an instance method. */
private fun Method.thisRegister(): Int {
    val parameters = parameterTypes.sumOf { it.width() }
    return implementation!!.registerCount - parameters - 1
}

/**
 * Where [constructor], one of the params' own, stores the three [fields]: the register each is
 * stored from, when it stores each once, into `this`, straight from one of its String parameters,
 * and nothing before the store writes that parameter's register. Null for any other method.
 */
internal fun requestRegisters(constructor: Method, fields: RequestFields): RequestRegisters? {
    if (constructor.name != "<init>" || constructor.definingClass != FETCH_FEEDBACK_PARAMS) return null
    if (AccessFlags.STATIC.isSet(constructor.accessFlags)) return null
    val code = constructor.implementation?.instructions?.toList() ?: return null
    val self = constructor.thisRegister()
    val stringParameters = constructor.stringParameterRegisters().keys
    fun storedFrom(field: String): Int? {
        val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field?.key() == field }
        val store = stores.singleOrNull() ?: return null
        val instruction = code[store] as TwoRegisterInstruction
        if (instruction.registerB != self) return null
        val register = instruction.registerA
        if (register !in stringParameters) return null
        if (code.subList(0, store).any { writes(it, register) }) return null
        return register
    }
    val order = storedFrom(fields.order) ?: return null
    val feedbackId = storedFrom(fields.feedbackId) ?: return null
    val focusedComment = storedFrom(fields.focusedComment) ?: return null
    if (setOf(order, feedbackId, focusedComment).size != 3) return null
    return RequestRegisters(order, feedbackId, focusedComment)
}

/** The v number of each of this instance method's String parameters, to the parameter's index from 0. */
private fun Method.stringParameterRegisters(): Map<Int, Int> {
    val registers = mutableMapOf<Int, Int>()
    var next = thisRegister() + 1
    parameterTypes.forEachIndexed { index, type ->
        if (type.toString() == STRING) registers[next] = index
        next += type.width()
    }
    return registers
}

/**
 * Whether [method] is a comment sheet's pick handler: an instance method returning nothing, taking
 * the FbUserSession and the picked token, that fetches [PICK_QUERY].
 */
internal fun isPickHandler(method: Method): Boolean =
    method.implementation != null && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        !AccessFlags.ABSTRACT.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map { it.toString() } == listOf(USER_SESSION, STRING) &&
        holdsString(method, PICK_QUERY)

/*
 * The sort menu, on the 582 build (#110). Asked for All comments up front, Facebook answered some
 * friends' posts with no comment at all, while their sheet showed comments in Facebook's order. So
 * All comments is picked in the sheet's menu instead, as a tap does.
 *
 * The comment sheet's section renderer is the one method holding both "fetch_comment_intent" and
 * "interranked_comment_order_pagination". When the answer's `selectable_intents` list has more than
 * one order, it builds the menu's header component, with the FbUserSession, the post's feedback
 * tree, a string and the Function1 a tap on a row calls with that row's token, and files it under
 * "OrderingModeSelector", a key Redex keeps in a static map of its own class, read right after.
 * First thing in that header's constructor, the extension gets the feedback tree and the Function1.
 *
 * 585 holds neither string in the renderer. It asks the answer's models for them through interface
 * getters, and the models answer by naming them: arm64's renderer `LX/09yb;->A0o` calls
 * `LX/0AhN;->AFx()` (implemented by `LX/0AvH;`, which asks the string table `LX/07kI;` for
 * "fetch_comment_intent") and `LX/0DtX;->DsC()` (implemented by `LX/0B0U;`, which holds
 * "interranked_comment_order_pagination"); armeabi-v7a's models are `LX/0AvK;->AFx` and
 * `LX/0B0X;->DsA`. It still builds the header (`LX/09xM;` on arm64) and reads the key's map
 * (`LX/09xN;->A00`, the only reader besides its class's initializer) four instructions below. So
 * the renderer is looked for among the classes naming the key's class, and a call to such a getter
 * stands in for each string ([interfaceGetters]).
 */

/** Strings the comment sheet's section renderer holds. */
internal const val INTENT_FLAG = "fetch_comment_intent"
internal const val INTERRANKED = "interranked_comment_order_pagination"

/** The key the sort menu's header is filed under, held by the class of the static map it's read from. */
internal const val SELECTOR_KEY = "OrderingModeSelector"

private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val MAP = "Ljava/util/Map;"

/** How far below the header's constructor the renderer reads the key's map. 582 reads it four after. */
private const val KEY_WINDOW = 8

/** The extension's look at a sort menu as it's built. */
internal const val SORT_MENU = "$EXTENSION_PACKAGE/comments/DefaultCommentOrder;->" +
    "sortMenu(Ljava/lang/Object;Ljava/lang/Object;)V"

/** Whether [reference] is the constructor of a sort menu's header: the session, the feedback, a string, the pick. */
internal fun isSortHeaderConstructor(reference: MethodReference): Boolean =
    reference.name == "<init>" && reference.parameterTypes.map { it.toString() }.let {
        it.size == 4 && it[0] == USER_SESSION && it[2] == STRING && it[3] == FUNCTION1
    }

/** The static map fields of [keyOwners], the classes holding [SELECTOR_KEY], as `class->name:type`. */
internal fun selectorKeyMaps(keyOwners: List<ClassDef>): Set<String> =
    keyOwners.flatMap { owner -> owner.staticFields.filter { it.type == MAP }.map { "${owner.type}->${it.name}:$MAP" } }
        .toSet()

/** Whether [method] could be a model's getter for a string: an instance method with a body, no parameters and an object answer. */
internal fun isSheetGetter(method: Method): Boolean =
    method.implementation != null && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.isEmpty() && method.returnType.startsWith("L")

private fun getterSignature(type: String, name: String, parameters: List<CharSequence>, returns: String) =
    "$type->$name(${parameters.joinToString("")})$returns"

/**
 * [getters], methods of [models] that name a string, as declared on each interface their class
 * implements, `interface->name(parameters)return`. 585's renderer reaches the strings only through
 * calls on those interfaces, so a call to one of these stands in for holding the string.
 */
internal fun interfaceGetters(models: Collection<ClassDef>, getters: Collection<Method>): Set<String> {
    val byType = models.associateBy { it.type }
    return getters.flatMapTo(mutableSetOf()) { getter ->
        (byType[getter.definingClass]?.interfaces ?: emptyList()).map { face ->
            getterSignature(face, getter.name, getter.parameterTypes, getter.returnType)
        }
    }
}

private fun callsOneOf(code: List<Instruction>, getters: Set<String>): Boolean = getters.isNotEmpty() && code.any {
    val call = (it as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
    getterSignature(call.definingClass, call.name, call.parameterTypes, call.returnType) in getters
}

/**
 * The constructor of the sort menu's header that [render] builds, when [render] is the comment
 * sheet's section renderer: it holds [INTENT_FLAG] and [INTERRANKED], or calls one of
 * [intentGetters] and one of [interrankedGetters] ([interfaceGetters]), and a constructor call
 * [isSortHeaderConstructor] matches, with a read of one of [keyMaps] at most [KEY_WINDOW]
 * instructions below it. Null when [render] isn't that renderer.
 */
internal fun sortHeaderIn(
    render: Method,
    keyMaps: Set<String>,
    intentGetters: Set<String> = emptySet(),
    interrankedGetters: Set<String> = emptySet(),
): MethodReference? {
    val code = render.implementation?.instructions?.toList() ?: return null
    val holds = holdsString(render, INTENT_FLAG) && holdsString(render, INTERRANKED)
    // 585's renderer asks its answer's models for both strings through interface getters instead of holding them.
    if (!holds && !(callsOneOf(code, intentGetters) && callsOneOf(code, interrankedGetters))) return null
    return code.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
            return@mapNotNull null
        }
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (!isSortHeaderConstructor(reference)) return@mapNotNull null
        val keyRead = code.subList(index + 1, minOf(code.size, index + 1 + KEY_WINDOW)).any {
            it.opcode == Opcode.SGET_OBJECT && it.field?.key() in keyMaps
        }
        if (keyRead) reference else null
    }.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }.singleOrNull()
}

/*
 * A post's own page, on the 583 build (#192). A link to a post, and many notifications, open the
 * post's page rather than a sheet, and its comments come with the story in one request, built from
 * a FetchSingleStoryParams (kept) by the method that takes it and the FbUserSession. That builder
 * writes `comment_rendering_intent_token` too, from one String field of those params and only
 * when it isn't empty, so a page opened from a link that names no order gets Facebook's choice,
 * whatever the sheets ask for. The params' long constructor stores that field from one of its many
 * String parameters, through one move on 583. The one method that turns a PermalinkParams (kept)
 * into the request builds the params with that constructor, the order the link names in that
 * parameter's place. The hook goes right before the call: the extension sees the order the page
 * would ask for and answers the one to send. A link to one comment opens a sheet, not the page, so
 * the page's request has no focused comment to keep.
 */

/** The params of a post page's request. Redex keeps the name. */
internal const val FETCH_SINGLE_STORY_PARAMS = "Lcom/facebook/api/story/FetchSingleStoryParams;"

/** What a link to a post carries. Redex keeps the name. */
internal const val PERMALINK_PARAMS = "Lcom/facebook/permalink/params/PermalinkParams;"

/** The extension's answer to the order a post page's request would carry. */
internal const val PAGE_ORDER = "$EXTENSION_PACKAGE/comments/DefaultCommentOrder;->pageOrder(Ljava/lang/String;)Ljava/lang/String;"

/** Where a post page's request is built from a link: the constructor call's index and the register the order is in. */
internal data class PageBuild(val method: Method, val index: Int, val order: Int)

/**
 * The field of the post page's params that [method] turns into the order, when it's that page's
 * request builder: it takes FetchSingleStoryParams, and each load of the variable's name (583 has
 * four, one per request shape) follows a read of the same String field of the params. Null for any
 * other method. A load is a literal, or a call asking a table [resolve] finds ([variableLoads]).
 */
internal fun storyOrderField(method: Method, resolve: (MethodReference) -> Method? = { null }): String? {
    if (method.parameterTypes.none { it.toString() == FETCH_SINGLE_STORY_PARAMS }) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val loads = variableLoads(code, ORDER_VARIABLE, resolve)
    if (loads.isEmpty()) return null
    val fields = loads.map { fieldReadAbove(code, it, FETCH_SINGLE_STORY_PARAMS) ?: return null }
    return fields.toSet().singleOrNull()
}

private val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Which of [constructor]'s parameters, counting from 0, it stores into [field]: when it's one of
 * the post page params' own, stores the field once, into `this`, from a String parameter straight
 * or through one move, and nothing before that writes the parameter's register. Null for any other
 * method.
 */
internal fun storyOrderParameter(constructor: Method, field: String): Int? {
    if (constructor.name != "<init>" || constructor.definingClass != FETCH_SINGLE_STORY_PARAMS) return null
    if (AccessFlags.STATIC.isSet(constructor.accessFlags)) return null
    val code = constructor.implementation?.instructions?.toList() ?: return null
    val parameters = constructor.stringParameterRegisters()
    val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field?.key() == field }
    val store = stores.singleOrNull() ?: return null
    val instruction = code[store] as TwoRegisterInstruction
    if (instruction.registerB != constructor.thisRegister()) return null
    var register = instruction.registerA
    var at = store
    if (register !in parameters) {
        val write = (at - 1 downTo 0).firstOrNull { writes(code[it], register) } ?: return null
        val move = code[write]
        if (move.opcode !in MOVES) return null
        register = (move as TwoRegisterInstruction).registerB
        at = write
    }
    val index = parameters[register] ?: return null
    if (code.subList(0, at).any { writes(it, register) }) return null
    return index
}

private fun MethodReference.sameAs(method: Method): Boolean =
    definingClass == method.definingClass && name == method.name &&
        parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }

/**
 * Where [method] builds the post page's params from a link, when it's the method taking
 * PermalinkParams that calls [constructor] once, the params' constructor whose parameter
 * [parameter] is the order: that call's index and the register holding the order there. Null for
 * any other method.
 */
internal fun pageBuildIn(method: Method, constructor: Method, parameter: Int): PageBuild? {
    if (method.parameterTypes.none { it.toString() == PERMALINK_PARAMS }) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val calls = code.withIndex().filter { (_, instruction) ->
        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.sameAs(constructor) == true
    }
    val (index, call) = calls.singleOrNull() ?: return null
    // Past `this`, then past the parameters before it.
    val offset = 1 + constructor.parameterTypes.take(parameter).sumOf { it.width() }
    val register = when (call) {
        is RegisterRangeInstruction -> call.startRegister + offset
        is FiveRegisterInstruction ->
            listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).getOrNull(offset)
                ?: return null
        else -> return null
    }
    return PageBuild(method, index, register)
}
