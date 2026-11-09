/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredsearch

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.search.COPY_OF
import app.morphe.patches.facebook.search.KEPT_MODULES
import app.morphe.patches.facebook.search.filterPageModulesFirst
import app.morphe.patches.facebook.search.pageConstructorOf
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of Hide sponsored search results on every declared Facebook build, found the way the
 * patch finds them: one role enum building every ad role, one module class whose own ad set is
 * Facebook's four, two page builders answering one page class, and one module list in its one
 * constructor, which the filter then goes first in with v0 and the list's own register.
 */
class SearchResultsFixtureTest {
    /**
     * Registers of the page constructor, the register of its module list, and how many constructors
     * the page class has (585 added a short one that handed its page to the full one, and 586 drops
     * it again), per build.
     */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to Triple(12, 3, 1),
    )

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return method.implementation!!.registerCount - self - method.parameterTypes.size
    }

    @Test
    fun `each declared build has one search page, one module class and one module list`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val enums = FixtureDex.classesHolding(bundle, "TOP_POSITION_SHOPPABLE_ADS").filter(::isRoleEnum)
                assertEquals("${bundle.name}: enums building every search ad role", 1, enums.size)
                val role = enums.single()
                val constants = enumConstantFields(role)
                assertTrue("${bundle.name}: the enum keeps its ad set's fields", constants.values.containsAll(FACEBOOK_AD_ROLES))

                val modules = mutableListOf<ClassDef>()
                val builderClasses = mutableListOf<ClassDef>()
                var builders = 0
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (isModuleClass(classDef, role.type)) modules += ImmutableClassDef.of(classDef)
                        val found = classDef.methods.count(::isPageBuilder)
                        if (found > 0) {
                            builders += found
                            builderClasses += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                assertEquals("${bundle.name}: search module classes", 1, modules.size)
                val module = modules.single()
                assertEquals("${bundle.name}: the module's own ad set", FACEBOOK_AD_ROLES, adSetRoles(module, role.type, constants))
                assertEquals("${bundle.name}: page builders", 2, builders)
                assertEquals("${bundle.name}: classes with page builders", 1, builderClasses.size)

                val page = builderClasses.flatMap { it.methods }.filter(::isPageBuilder).map { it.returnType }.toSet().single()
                val support = FixtureDex.classes(bundle, setOf(page, IMMUTABLE_LIST))
                assertEquals("${bundle.name}: the page or ImmutableList is missing", setOf(page, IMMUTABLE_LIST), support.keys)

                val context = PatchContexts.of(listOf(role, module) + builderClasses + support.values)
                val search = with(context) { searchPages() }
                assertEquals("${bundle.name}: the page", page, search.page)
                assertEquals("${bundle.name}: the module", module.type, search.module)
                assertEquals("${bundle.name}: the role enum", role.type, search.role)
                assertEquals("${bundle.name}: the module list is the constructor's first ImmutableList", 1, search.moduleList)

                // The shared page hook goes first in the page-shaped constructor, once, reading the
                // modules and the page's name in their own registers. Every other constructor hands
                // its page to that one (pageConstructorOf refuses the class otherwise).
                val pageClass = with(context) { mutableClassDefBy(page) }
                val (registers, list, constructors) = expected.getValue(version)
                assertEquals("${bundle.name}: the page's constructors", constructors, pageClass.methods.count { it.name == "<init>" })
                val constructor = pageConstructorOf(pageClass.methods, pageClass.superclass, PATCH)
                pageClass.methods.filter { it.name == "<init>" && it !== constructor }.forEach { other ->
                    val calls = other.implementation!!.instructions.mapNotNull {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { call -> call.name == "<init>" }
                    }
                    assertEquals("${bundle.name}: the short constructor calls only the full one",
                        listOf(constructor.parameterTypes.map(CharSequence::toString)),
                        calls.map { call -> call.parameterTypes.map(CharSequence::toString) })
                }
                assertEquals("${bundle.name}: constructor registers", registers, constructor.implementation!!.registerCount)
                assertTrue("${bundle.name}: the constructor has no local", locals(constructor) >= 1)
                requireSharedPageShape(constructor, search.moduleList)
                val own = constructor.implementation!!.instructions.count()
                constructor.filterPageModulesFirst(PATCH)
                constructor.filterPageModulesFirst("Hide Meta AI in search")
                val body = constructor.implementation!!.instructions.toList()
                assertEquals("${bundle.name}: one hook", own + 5, body.size)
                assertEquals(KEPT_MODULES, reference(body[0]))
                val call = body[0] as FiveRegisterInstruction
                assertEquals("${bundle.name}: the modules and the name", listOf(list, list + 4), listOf(call.registerC, call.registerD))
                assertEquals(Opcode.MOVE_RESULT_OBJECT, body[1].opcode)
                assertEquals(Opcode.IF_EQZ, body[2].opcode)
                assertEquals(COPY_OF, reference(body[3]))
                assertEquals(list, (body[4] as OneRegisterInstruction).registerA)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun reference(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): String {
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }
}
