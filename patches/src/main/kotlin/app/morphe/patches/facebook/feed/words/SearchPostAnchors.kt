/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.sponsoredsearch.SearchPages
import app.morphe.patches.facebook.ads.sponsoredsearch.requireSharedPageShape
import app.morphe.patches.facebook.ads.sponsoredsearch.searchPages
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.search.filterPageModulesFirst
import app.morphe.patches.facebook.search.pageConstructorOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** TimelineWords' two stubs for search results (#458): whether an item is a search module, and its post. */
internal const val IS_SEARCH_MODULE_STUB = "isSearchModule"
internal const val SEARCH_STORY_STUB = "searchStory"

/** What the patch reads off a page of search results: the page Hide sponsored search results finds, and the module's post. */
internal class SearchPosts(
    val pages: SearchPages,
    val story: Field,
)

/**
 * Search's Posts tab and the posts mixed into All aren't drawn by the story component: each comes
 * as a module of a search results page carrying its post (588 arm64: LX/0E2p's A01), which the
 * page draws itself. The page, its module class and the page constructor are the ones Hide
 * sponsored search results finds (SearchResultAnchors.kt), held to the same evidence, and the
 * module's post is its one instance field of GraphQLStory, which a constructor writes and the
 * extension reads, so it has to be public. Throws when any of it has moved.
 */
internal fun BytecodePatchContext.searchPosts(): SearchPosts {
    val pages = searchPages()
    val module = classDefBy(pages.module)
    val story = searchStoryField(module)
    val page = classDefBy(pages.page)
    requireSharedPageShape(pageConstructorOf(page.methods, page.superclass, PATCH), pages.moduleList)
    return SearchPosts(pages, story)
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

/**
 * Fills TimelineWords' two stubs from [search] and puts the shared page hook first in the page
 * constructor, which the extension then runs Hide posts by words' filter in. The hook goes in once
 * whichever search patches ask for it. Each stub uses only its parameter register.
 */
internal fun BytecodePatchContext.judgeSearchPosts(search: SearchPosts) {
    val words = mutableClassDefBy(TIMELINE_WORDS_CLASS)
    fun stub(name: String, returnType: String) = words.methods.singleOrNull {
        it.name == name && it.returnType == returnType && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$TIMELINE_WORDS_CLASS has no static $returnType $name(Object)")

    val module = search.pages.module
    stub(IS_SEARCH_MODULE_STUB, "Z").addInstructions(
        0,
        """
            instance-of p0, p0, $module
            return p0
        """,
    )
    stub(SEARCH_STORY_STUB, "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, $module
            iget-object p0, p0, $module->${search.story.name}:$GRAPHQL_STORY
            return-object p0
        """,
    )
    val page = mutableClassDefBy(search.pages.page)
    pageConstructorOf(page.methods, page.superclass, PATCH).filterPageModulesFirst(PATCH)
}
