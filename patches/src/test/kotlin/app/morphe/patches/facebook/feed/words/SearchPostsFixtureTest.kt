/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.words

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.sponsoredsearch.IMMUTABLE_LIST
import app.morphe.patches.facebook.ads.sponsoredsearch.isModuleClass
import app.morphe.patches.facebook.ads.sponsoredsearch.isPageBuilder
import app.morphe.patches.facebook.ads.sponsoredsearch.isRoleEnum
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.search.KEPT_MODULES
import app.morphe.patches.facebook.search.filterPageModulesFirst
import app.morphe.patches.facebook.search.hasPageModulesHook
import app.morphe.patches.facebook.search.pageConstructorOf
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide posts by words in search results (#458), on every declared Facebook build: the search page
 * and module Hide sponsored search results finds, the module's one public GraphQLStory field, the
 * two TimelineWords stubs filled with the module class and that field, and the shared page hook
 * first in the page constructor once, however many search patches ask for it.
 */
class SearchPostsFixtureTest {
    /** A stub of TimelineWords as the extension ships it: one Object parameter and a stock answer. */
    private fun stub(name: String, returnType: String, smali: String): Method = MutableMethod(
        ImmutableMethod(
            TIMELINE_WORDS_CLASS,
            name,
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, smali) }

    private fun timelineWords(): ClassDef = ImmutableClassDef(
        TIMELINE_WORDS_CLASS, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
        null, null, null, emptyList(),
        listOf(
            stub(IS_SEARCH_MODULE_STUB, "Z", "const/4 v0, 0x0\nreturn v0"),
            stub(SEARCH_STORY_STUB, "Ljava/lang/Object;", "const/4 v0, 0x0\nreturn-object v0"),
        ),
    )

    @Test
    fun `each declared build finds the search module's post and filters the page once`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val role = FixtureDex.classesHolding(bundle, "TOP_POSITION_SHOPPABLE_ADS").filter(::isRoleEnum).single()
                val modules = mutableListOf<ClassDef>()
                val builderClasses = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (isModuleClass(classDef, role.type)) modules += ImmutableClassDef.of(classDef)
                        if (classDef.methods.any(::isPageBuilder)) builderClasses += ImmutableClassDef.of(classDef)
                    }
                }
                val module = modules.single()
                val page = builderClasses.flatMap { it.methods }.filter(::isPageBuilder).map { it.returnType }.toSet().single()
                val support = FixtureDex.classes(bundle, setOf(page, IMMUTABLE_LIST, GRAPHQL_STORY))
                assertEquals("$name: a support class is missing", setOf(page, IMMUTABLE_LIST, GRAPHQL_STORY), support.keys)

                val context = PatchContexts.of(listOf(role, module, timelineWords()) + builderClasses + support.values)
                val found = with(context) { searchPosts() }
                assertEquals("$name: the module", module.type, found.pages.module)
                assertEquals("$name: the post's field holds a story", GRAPHQL_STORY, found.story.type)
                assertEquals("$name: the module's only story field",
                    module.fields.filter { it.type == GRAPHQL_STORY }.map { it.name }, listOf(found.story.name))
                assertTrue("$name: the extension can read it", AccessFlags.PUBLIC.isSet(found.story.accessFlags))

                with(context) { judgeSearchPosts(found) }
                val words = with(context) { mutableClassDefBy(TIMELINE_WORDS_CLASS) }
                val isModule = words.methods.single { it.name == IS_SEARCH_MODULE_STUB }.implementation!!.instructions.toList()
                assertEquals(name, listOf(Opcode.INSTANCE_OF, Opcode.RETURN), isModule.take(2).map { it.opcode })
                assertEquals(name, module.type, ((isModule[0] as ReferenceInstruction).reference as TypeReference).type)
                val story = words.methods.single { it.name == SEARCH_STORY_STUB }.implementation!!.instructions.toList()
                assertEquals(name, listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), story.take(3).map { it.opcode })
                val read = (story[1] as ReferenceInstruction).reference as FieldReference
                assertEquals(name, listOf(module.type, found.story.name, GRAPHQL_STORY), listOf(read.definingClass, read.name, read.type))

                val pageClass = with(context) { mutableClassDefBy(page) }
                val constructor = pageConstructorOf(pageClass.methods, pageClass.superclass, PATCH)
                assertTrue("$name: the page hook went in", constructor.hasPageModulesHook())
                val first = constructor.implementation!!.instructions.first()
                val call = (first as ReferenceInstruction).reference as MethodReference
                assertEquals(name, KEPT_MODULES,
                    "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
                val size = constructor.implementation!!.instructions.count()
                constructor.filterPageModulesFirst("Hide sponsored search results")
                constructor.filterPageModulesFirst("Hide Meta AI in search")
                assertEquals("$name: one hook for every search patch", size, constructor.implementation!!.instructions.count())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
