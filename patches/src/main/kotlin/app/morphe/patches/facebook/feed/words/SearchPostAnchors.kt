/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.ads.sponsoredsearch.SearchPages
import app.morphe.patches.facebook.ads.sponsoredsearch.requireSharedPageShape
import app.morphe.patches.facebook.ads.sponsoredsearch.searchPages
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.search.filterPageModulesFirst
import app.morphe.patches.facebook.search.pageConstructorOf
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * TimelineWords' three stubs for search results (#458): whether an item is a search module, its
 * story, and the words its card shows.
 */
internal const val IS_SEARCH_MODULE_STUB = "isSearchModule"
internal const val SEARCH_STORY_STUB = "searchStory"
internal const val SEARCH_POST_TEXT_STUB = "searchPostText"

/** The key Facebook's search results card draws each result under (588 arm64: LX/0E5I's render). */
internal const val SEARCH_CARD_KEY = "search-result-"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * How Facebook's search results card reads a post's words off its module (588 arm64): the module's
 * tree field [tree] (LX/0E2p's A0B) holds one of [views] (LX/0E5u or LX/0E5v, each answering the
 * post through AOX), then [post] (Haf), [node] (Aux) and [text] (getText) lead to the words.
 */
internal class PostTextInTree(
    val tree: FieldReference,
    val views: List<MethodReference>,
    val post: MethodReference,
    val node: MethodReference,
    val text: MethodReference,
)

/** What the patch reads off a page of search results: the page Hide sponsored search results finds, and the module's post. */
internal class SearchPosts(
    val pages: SearchPages,
    val story: Field,
    val text: PostTextInTree,
)

/**
 * Search's Posts tab and the posts mixed into All aren't drawn by the story component: each comes
 * as a module of a search results page carrying its post (588 arm64: LX/0E2p), which the page draws
 * itself. The page, its module class and the page constructor are the ones Hide sponsored search
 * results finds (SearchResultAnchors.kt), held to the same evidence. An ad's module carries its
 * story in its one public GraphQLStory field. Every other post comes with that field empty, and its
 * card reads the words off the module's tree instead ([postTextInTree]). Throws when any of it has
 * moved.
 */
internal fun BytecodePatchContext.searchPosts(): SearchPosts {
    val pages = searchPages()
    val module = classDefBy(pages.module)
    val story = searchStoryField(module)
    val text = postTextInTree(module)
    val page = classDefBy(pages.page)
    requireSharedPageShape(pageConstructorOf(page.methods, page.superclass, PATCH), pages.moduleList)
    return SearchPosts(pages, story, text)
}

/** The search module's one instance field holding a GraphQLStory, written by one of its constructors and public. */
internal fun searchStoryField(module: ClassDef): Field {
    val fields = module.fields.filter { it.type == GRAPHQL_STORY && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val field = fields.singleOrNull() ?: throw PatchException(
        "$PATCH: ${module.type}, the search module, has ${fields.size} fields of GraphQLStory, expected one",
    )
    val written = module.methods.filter { it.name == "<init>" }.any { constructor ->
        constructor.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == module.type && it.name == field.name && it.type == GRAPHQL_STORY
                }
        } == true
    }
    if (!written) throw PatchException("$PATCH: no constructor of ${module.type} writes its GraphQLStory ${field.name}")
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) {
        throw PatchException("$PATCH: ${module.type}->${field.name}, the search module's post, isn't public, so the extension can't read it")
    }
    return field
}

/** The object register an interface call with no arguments is made on. */
private fun receiver(instruction: Instruction): Int? = when (instruction) {
    is FiveRegisterInstruction -> instruction.registerC
    is RegisterRangeInstruction -> instruction.startRegister
    else -> null
}

private fun isInterfaceCall(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_INTERFACE || instruction.opcode == Opcode.INVOKE_INTERFACE_RANGE

private fun isStaticCall(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE

private fun parametersOf(method: MethodReference) = method.parameterTypes.map { it.toString() }

/**
 * The interface calls with no arguments [method] makes right after casting their object to the
 * interface they call: how the card asks each kind of view it may be handed for its post.
 */
private fun castThenCalled(method: Method): List<MethodReference> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.withIndex().mapNotNull { (index, instruction) ->
        if (!isInterfaceCall(instruction)) return@mapNotNull null
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        if (call.parameterTypes.isNotEmpty()) return@mapNotNull null
        val cast = instructions.getOrNull(index - 1) ?: return@mapNotNull null
        if (cast.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != receiver(instruction)) {
            return@mapNotNull null
        }
        call.takeIf { ((cast as ReferenceInstruction).reference as TypeReference).type == it.definingClass }
    }.distinctBy { it.toString() }
}

/** Throws unless [type] is a public class of the app, which the extension may cast to and call. */
private fun BytecodePatchContext.requirePublic(type: String, what: String) {
    val classDef = classDefByOrNull(type) ?: throw PatchException("$PATCH: $type, $what, isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) {
        throw PatchException("$PATCH: $type, $what, isn't public, so the extension can't read through it")
    }
}

/**
 * How [module]'s card reads its post's words, the way Facebook's search results card does (588
 * arm64: LX/0E5I, built in LX/0E4N's render with the module's A0B). The card is the one class whose
 * draw loads [SEARCH_CARD_KEY], casts its object to each view it knows and asks it for the post,
 * then hands that to a static builder that reads its words ([wordsReadBy]). The tree is the one
 * field of [module] the patched app hands a card's constructor as its one Object, which has to be
 * public. Every type the extension casts to or calls through has to be public too. Throws when any
 * of it has moved.
 */
internal fun BytecodePatchContext.postTextInTree(module: ClassDef): PostTextInTree {
    class Route(val card: String, val views: List<MethodReference>, val read: List<MethodReference>)

    val routes = classDefByStrings(SEARCH_CARD_KEY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { card -> card.methods.filter { holdsString(it, SEARCH_CARD_KEY) } }
        .flatMap { draw ->
            val asked = castThenCalled(draw)
            val answers = asked.map { it.returnType }.toSet()
            // The card hands the post to more than one static call (588: 0E65's A00 builds the
            // body, 0E5I's A0A reads something else), so the builder is the one that reads words.
            draw.implementation!!.instructions.filter(::isStaticCall)
                .map { (it as ReferenceInstruction).reference as MethodReference }
                .filter { parametersOf(it).size == 1 && parametersOf(it).single() in answers }
                .distinctBy { it.toString() }
                .mapNotNull { builder ->
                    val read = wordsReadBy(builder) ?: return@mapNotNull null
                    Route(draw.definingClass, asked.filter { it.returnType == parametersOf(builder).single() }, read)
                }
        }
    val route = routes.singleOrNull() ?: throw PatchException(
        "$PATCH: ${routes.size} search results cards hand a view's post to a builder that reads its words, expected one",
    )
    val (post, node, text) = route.read

    val tree = cardTreeField(module, route.card)
    route.views.forEach { requirePublic(it.definingClass, "a view the search card reads") }
    requirePublic(post.definingClass, "the post the search card reads")
    requirePublic(post.returnType, "the search post's node")
    requirePublic(node.returnType, "the search post's text holder")
    return PostTextInTree(tree, route.views, post, node, text)
}

/**
 * How [builder] reads the words of the post it's handed, its one parameter: its first interface
 * call asks the post for its node, the next asks the node for its text holder, and it reads
 * getText off that holder. The three calls, or null when [builder] reads none of that.
 */
private fun BytecodePatchContext.wordsReadBy(builder: MethodReference): List<MethodReference>? {
    val calls = classDefByOrNull(builder.definingClass)?.methods?.singleOrNull {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map { type -> type.toString() } == parametersOf(builder)
    }?.implementation?.instructions?.filter(::isInterfaceCall)
        ?.map { (it as ReferenceInstruction).reference as MethodReference } ?: return null
    val post = calls.firstOrNull()
        ?.takeIf { it.definingClass == parametersOf(builder).single() && it.parameterTypes.isEmpty() } ?: return null
    val node = calls.getOrNull(1)
        ?.takeIf { it.definingClass == post.returnType && it.parameterTypes.isEmpty() } ?: return null
    val text = calls.firstOrNull {
        it.definingClass == node.returnType && it.name == "getText" && it.returnType == STRING && it.parameterTypes.isEmpty()
    } ?: return null
    return listOf(post, node, text)
}

/**
 * The field of [module] the app hands [card]'s constructor as its one Object parameter: the module's
 * tree. Each card built is traced back from that argument through moves to the field it was read
 * from. Exactly one field of [module] may come out, and it has to be public.
 */
private fun BytecodePatchContext.cardTreeField(module: ClassDef, card: String): FieldReference {
    val trees = mutableSetOf<String>()
    var found: FieldReference? = null
    classDefForEachReferencing(types = listOf(card)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue
            for ((index, instruction) in instructions.withIndex()) {
                if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) continue
                val call = (instruction as ReferenceInstruction).reference as MethodReference
                if (call.definingClass != card || call.name != "<init>") continue
                val parameters = parametersOf(call)
                if (parameters.count { it == OBJECT } != 1) continue
                val slot = 1 + parameters.takeWhile { it != OBJECT }.sumOf { if (it == "J" || it == "D") 2 else 1 }
                val register = argumentRegister(instruction, slot) ?: continue
                val field = fieldReadInto(instructions, index, register) ?: continue
                if (field.definingClass != module.type || field.type != OBJECT) continue
                trees += field.name
                found = field
            }
        }
    }
    val tree = found?.takeIf { trees.size == 1 } ?: throw PatchException(
        "$PATCH: the search card is built with ${trees.size} of ${module.type}'s Object fields, expected one",
    )
    val declared = module.fields.singleOrNull { it.name == tree.name && it.type == OBJECT }
        ?: throw PatchException("$PATCH: ${module.type} doesn't declare ${tree.name}, the tree its card is built with")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) {
        throw PatchException("$PATCH: ${module.type}->${tree.name}, the search post's tree, isn't public, so the extension can't read it")
    }
    return tree
}

/** The register a call hands its argument in [slot] (the object being 0), or null when it has none there. */
private fun argumentRegister(call: Instruction, slot: Int): Int? = when (call) {
    is RegisterRangeInstruction -> if (slot < call.registerCount) call.startRegister + slot else null
    is FiveRegisterInstruction -> if (slot >= call.registerCount) null else when (slot) {
        0 -> call.registerC
        1 -> call.registerD
        2 -> call.registerE
        3 -> call.registerF
        4 -> call.registerG
        else -> null
    }
    else -> null
}

/**
 * The field whose iget-object last wrote [register] before [index] in [instructions], followed back
 * through object moves, or null when something else wrote it.
 */
private fun fieldReadInto(instructions: List<Instruction>, index: Int, register: Int): FieldReference? {
    var wanted = register
    for (at in index - 1 downTo 0) {
        val instruction = instructions[at]
        if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != wanted) continue
        when (instruction.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                wanted = (instruction as TwoRegisterInstruction).registerB
            Opcode.IGET_OBJECT -> return (instruction as ReferenceInstruction).reference as FieldReference
            else -> return null
        }
    }
    return null
}

/** One call of [method] as smali names it. */
private fun call(method: MethodReference) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

/**
 * The body of TimelineWords' searchPostText for [module]: the module's tree, cast to whichever view
 * it is, asked for the post, its node, its text holder and the words, the way the card reads them.
 * The words, "" when the post has none, or null when the tree isn't a view the card knows or
 * holds no post. Uses v0, v1 and the parameter.
 */
internal fun searchPostTextSmali(module: String, read: PostTextInTree): String = buildString {
    appendLine("check-cast p0, $module")
    appendLine("iget-object v0, p0, $module->${read.tree.name}:$OBJECT")
    read.views.forEachIndexed { index, view ->
        appendLine("instance-of v1, v0, ${view.definingClass}")
        appendLine("if-eqz v1, :not_view_$index")
        appendLine("check-cast v0, ${view.definingClass}")
        appendLine("invoke-interface { v0 }, ${call(view)}")
        appendLine("move-result-object v0")
        appendLine("goto :viewed")
        appendLine(":not_view_$index")
    }
    appendLine("goto :none")
    appendLine(":viewed")
    for (step in listOf(read.post, read.node)) {
        appendLine("if-eqz v0, :none")
        appendLine("invoke-interface { v0 }, ${call(step)}")
        appendLine("move-result-object v0")
    }
    appendLine("if-eqz v0, :none")
    appendLine("invoke-interface { v0 }, ${call(read.text)}")
    appendLine("move-result-object v0")
    appendLine("if-nez v0, :words")
    appendLine("const-string v0, \"\"")
    appendLine(":words")
    appendLine("return-object v0")
    appendLine(":none")
    appendLine("const/4 v0, 0x0")
    appendLine("return-object v0")
}

/**
 * Fills TimelineWords' three stubs from [search] and puts the shared page hook first in the page
 * constructor, which the extension then runs Hide posts by words' filter in. The hook goes in once
 * whichever search patches ask for it. The first two stubs use only their parameter register. D8
 * builds searchPostText in two registers, so it's rebuilt with a third to test the view in.
 */
internal fun BytecodePatchContext.judgeSearchPosts(search: SearchPosts) {
    val words = mutableClassDefBy(TIMELINE_WORDS_CLASS)
    fun stub(name: String, returnType: String) = words.methods.singleOrNull {
        it.name == name && it.returnType == returnType && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map { type -> type.toString() } == listOf(OBJECT)
    } ?: throw PatchException("$TIMELINE_WORDS_CLASS has no static $returnType $name(Object)")

    val module = search.pages.module
    stub(IS_SEARCH_MODULE_STUB, "Z").addInstructions(
        0,
        """
            instance-of p0, p0, $module
            return p0
        """,
    )
    stub(SEARCH_STORY_STUB, OBJECT).addInstructions(
        0,
        """
            check-cast p0, $module
            iget-object p0, p0, $module->${search.story.name}:$GRAPHQL_STORY
            return-object p0
        """,
    )
    val stubbed = stub(SEARCH_POST_TEXT_STUB, STRING)
    val text = ImmutableMethod(
        TIMELINE_WORDS_CLASS, stubbed.name, stubbed.parameters, stubbed.returnType, stubbed.accessFlags, null, null,
        MutableMethodImplementation(3),
    ).toMutable()
    if (!words.methods.remove(stubbed)) throw PatchException("$TIMELINE_WORDS_CLASS->$SEARCH_POST_TEXT_STUB couldn't be replaced")
    words.methods.add(text)
    text.addInstructionsWithLabels(0, searchPostTextSmali(module, search.text))

    val page = mutableClassDefBy(search.pages.page)
    pageConstructorOf(page.methods, page.superclass, PATCH).filterPageModulesFirst(PATCH)
}
