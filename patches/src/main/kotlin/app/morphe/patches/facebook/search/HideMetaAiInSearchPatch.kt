/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.reels.callsMethod
import app.morphe.patches.facebook.feed.reels.categoryNames
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Takes the Meta AI Facebook adds by itself out of search: the answer on top of the results, the
 * Meta AI modules and prompts among them, and the suggestions its server sets to open in Meta AI.
 * See MetaAiSearchAnchors.kt for where each one is decided, and the extension's MetaAiSearch for
 * what it answers.
 *
 * Three hooks, each at Facebook's own decision: first thing in the answer question (answer no),
 * first thing in the constructor of a page of results (hand the extension the page's modules, keep
 * its list or take a copy without the Meta AI ones; the hook is shared with Hide sponsored search
 * results, see SearchResultsPageHook.kt), and where the suggestion parser stores a
 * suggestion's open-in-Meta-AI flag (store the extension's answer). The Meta AI button and the
 * results page's own Meta AI tab go around all three, so they still open Meta AI.
 *
 * On in the default selection, and its switch starts on: it only takes out Meta's own AI modules
 * and routes, never a person, group, page or post, and off or paused Facebook answers for itself.
 */
@Suppress("unused")
val hideMetaAiInSearchPatch = patch(
    name = "Hide Meta AI in search",
    description = "Removes the Meta AI answer and Ask Meta AI prompts from search results, and stops suggestions " +
        "from sending your search to Meta AI. You get plain results for people, groups, Pages and posts. On by " +
        "default. Turn it off in Hushfacebook settings > Search.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val answerBuilder = answerPageBuilder()
        val role = resultRoleEnum()

        // One walk over the whole APK finds the answer builder's callers and the result module.
        val callers = mutableListOf<Method>()
        val modules = mutableListOf<ClassDef>()
        classDefForEachReferencing(types = listOf(answerBuilder.definingClass, role)) { classDef ->
            for (method in classDef.methods) {
                if (callsMethod(method, answerBuilder)) callers += method
                if (isModuleConstructor(method, role)) modules += classDef
            }
        }

        val question = answerQuestionOf(callers, answerBuilder)
        val module = resultModule(modules, role)
        val accessor = roleAccessors(module, role).singleOrNull() ?: throw PatchException(
            "$PATCH: ${module.type} has ${roleAccessors(module, role).size} public accessors of its $role role, expected one",
        )
        // 585's builders call a short page constructor, which pageCall reads to reach the full one.
        val constructors = { call: MethodReference ->
            classDefByOrNull(call.definingClass)?.methods?.firstOrNull { isConstructor(it, call) }
        }
        val page = answerPageConstructor(answerBuilder, module.type, constructors) ?: throw PatchException(
            "$PATCH: ${answerBuilder.definingClass}->${answerBuilder.name} no longer builds its page from a list of " +
                "${module.type}'s result modules",
        )
        val tabPages = classDefByStrings(META_AI_TAB_PAGE, StringComparisonType.EQUALS)
            .flatMap { methodsHolding(it, META_AI_TAB_PAGE) }
            .filter { namesPage(it, page, META_AI_TAB_PAGE, constructors) }
        if (tabPages.size != 1) {
            throw PatchException(
                "$PATCH: expected one method building the Meta AI tab's page named \"$META_AI_TAB_PAGE\", found " +
                    "${tabPages.size}, so the tab's own answer couldn't be told apart",
            )
        }
        if (!hasCollectionCopy(classDefBy(IMMUTABLE_LIST))) {
            throw PatchException("$PATCH: ImmutableList has no public static copyOf(Collection) in this build")
        }
        val routeStore = suggestionRouteStore()

        mutableClassDefBy(question.definingClass).methods.single { isAnswerQuestion(it, question) }
            .answerNoWhileMetaAiHidden()
        fillStoryModelStub(META_AI_SEARCH, ROLE_STUB, accessor)
        // Every other constructor of the page class must hand its page to this one, or a page skips the hook.
        val pageClass = mutableClassDefBy(page.definingClass)
        val pageConstructor = pageConstructorOf(pageClass.methods, pageClass.superclass, PATCH)
        if (!isConstructor(pageConstructor, page)) {
            throw PatchException("$PATCH: the answer page builder's page constructor isn't ${page.definingClass}'s full one")
        }
        pageConstructor.filterPageModulesFirst(PATCH)
        val (parser, store) = routeStore
        mutableClassDefBy(parser.definingClass).methods.single {
            it.name == parser.name && it.returnType == parser.returnType &&
                it.parameterTypes.map(CharSequence::toString) == parser.parameterTypes.map(CharSequence::toString)
        }.askBeforeStoringRoute(store)
        enableStatus("metaAiSearch")
    }
}

/** The one method loading [ANSWER_PAGE] with the answer page builder's shape. */
private fun BytecodePatchContext.answerPageBuilder(): Method {
    val builders = classDefByStrings(ANSWER_PAGE, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, ANSWER_PAGE) }
        .filter(::isAnswerPageBuilder)
    return builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one (FbUserSession, SearchResultsMutableContext, loader) method building the \"$ANSWER_PAGE\" " +
            "page, found ${builders.size}",
    )
}

/** The result role enum, held to every role the extension leaves out and to the answer's own role. */
private fun BytecodePatchContext.resultRoleEnum(): String {
    val parsers = classDefByStrings(ROLE_PARSE_FAILURE, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, ROLE_PARSE_FAILURE) }
    val types = parsers.mapNotNull(::roleEnumType).distinct()
    val role = types.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the method logging \"$ROLE_PARSE_FAILURE\" to load one class, found " +
            types.joinToString().ifEmpty { "none" },
    )
    val roleClass = classDefByOrNull(role) ?: throw PatchException("$PATCH: the result role enum $role isn't in this APK")
    if (roleClass.superclass != "Ljava/lang/Enum;") throw PatchException("$PATCH: $role, the result role, isn't an enum")
    val missing = HIDDEN_ROLES.filterNot(categoryNames(roleClass)::contains)
    if (missing.isNotEmpty()) {
        throw PatchException("$PATCH: the result role enum has no ${missing.joinToString()}, so its Meta AI modules can't be told apart")
    }
    return role
}

/** The question every caller of the answer builder asks first, held to being one question in one method. */
private fun BytecodePatchContext.answerQuestionOf(callers: List<Method>, builder: Method): MethodReference {
    if (callers.isEmpty()) {
        throw PatchException("$PATCH: nothing calls ${builder.definingClass}->${builder.name}, the \"$ANSWER_PAGE\" builder")
    }
    val questions = callers.map { caller ->
        answerQuestion(caller, builder) ?: throw PatchException(
            "$PATCH: ${caller.definingClass}->${caller.name} builds the \"$ANSWER_PAGE\" page without first asking one " +
                "(FbUserSession, SearchResultsMutableContext) question that skips it",
        )
    }
    val distinct = questions.distinctBy { "${it.definingClass}->${it.name}" }
    val question = distinct.singleOrNull() ?: throw PatchException(
        "$PATCH: the \"$ANSWER_PAGE\" builder's callers ask ${distinct.size} different questions: " +
            distinct.joinToString { "${it.definingClass}->${it.name}" },
    )
    if (classDefByOrNull(question.definingClass)?.methods?.any { isAnswerQuestion(it, question) } != true) {
        throw PatchException("$PATCH: ${question.definingClass}->${question.name}, the answer question, has no body in this APK")
    }
    return question
}

/** The one public class whose constructor reads a result edge's role, the class the extension casts a module to. */
private fun resultModule(modules: List<ClassDef>, role: String): ClassDef {
    val distinct = modules.distinctBy { it.type }
    val module = distinct.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class whose constructor reads result_role as a $role, found " +
            distinct.joinToString { it.type }.ifEmpty { "none" },
    )
    if (!AccessFlags.PUBLIC.isSet(module.accessFlags)) {
        throw PatchException("$PATCH: ${module.type}, the result module, isn't public, so the extension can't read its role")
    }
    return module
}

/**
 * The suggestion parser and where it stores the route flag, held to the evidence: the results router
 * asks the spec one boolean first, GraphSearchQuerySpecImpl answers it from one field, its
 * constructor copies that field from one builder field, and the parser stores that builder field
 * from the server's tree (on 585 through the suggestion model's own accessor) in exactly one place.
 */
private fun BytecodePatchContext.suggestionRouteStore(): Pair<Method, RouteStore> {
    val questions = classDefByStrings(META_AI_TAB, StringComparisonType.EQUALS)
        .flatMap { classDef -> classDef.methods.mapNotNull(::routeQuestion) }
        .distinctBy { it.name }
    val question = questions.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one results router loading \"$META_AI_TAB\" that asks the query spec a boolean first, found " +
            questions.size,
    )
    val impl = classDefBy(QUERY_SPEC_IMPL)
    val field = routeField(impl, question) ?: throw PatchException(
        "$PATCH: GraphSearchQuerySpecImpl no longer answers ${question.name}() from one boolean field",
    )
    val builderField: FieldReference = builderRouteField(impl, field) ?: throw PatchException(
        "$PATCH: GraphSearchQuerySpecImpl's constructors don't copy ${field.name} from one builder field",
    )
    val tables = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val parsers = classDefByStrings(KEYWORD_TYPE_FAILURE, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, KEYWORD_TYPE_FAILURE) }
        .filter { isSuggestionParser(it, tables) }
    val parser = parsers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one suggestion parser logging \"$KEYWORD_TYPE_FAILURE\", found ${parsers.size}",
    )
    val stores = routeStores(parser, builderField)
    val store = stores.singleOrNull() ?: throw PatchException(
        "$PATCH: ${parser.definingClass}->${parser.name} stores the route flag from the server's tree in " +
            "${stores.size} places, expected one",
    )
    return parser to store
}

/**
 * First thing in the answer question: ask the extension, and answer no when it says the answer goes.
 * Otherwise Facebook's own question runs from its first instruction.
 */
internal fun MutableMethod.answerNoWhileMetaAiHidden() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_ANSWER
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * Between the `move-result` that keeps a suggestion's route flag and the `iput-boolean` that stores
 * it, hand the flag to the extension and keep its answer in the same register. Nothing may jump to
 * the store, since code arriving there would skip the call.
 */
internal fun MutableMethod.askBeforeStoringRoute(store: RouteStore) {
    val put = implementation!!.instructions[store.store] as BuilderInstruction
    if (put.location.labels.isNotEmpty()) {
        throw PatchException("$PATCH: $definingClass->$name has a jump to where it stores a suggestion's route flag")
    }
    if (store.register > 15) {
        throw PatchException("$PATCH: $definingClass->$name keeps the route flag in v${store.register}, past v15")
    }
    addInstructions(
        store.store,
        """
            invoke-static { v${store.register} }, $OPENS_META_AI
            move-result v${store.register}
        """,
    )
}
