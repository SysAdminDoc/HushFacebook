/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredsearch

import app.morphe.RepoFiles
import app.morphe.patches.facebook.search.COPY_OF
import app.morphe.patches.facebook.search.KEPT_MODULES
import app.morphe.patches.facebook.search.filterPageModulesFirst
import app.morphe.patches.facebook.search.hasPageModulesHook
import app.morphe.patches.facebook.search.pageConstructorOf
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.treeFieldKey
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parts of Hide sponsored search results that need no Facebook build: which classes and methods
 * the anchors take and turn down, how the role enum's fields are named, which parameter the module
 * list is, and the code the filter puts first in the page's constructor.
 */
class SearchResultsShapesTest {
    private val role = "Lfixture/Role;"
    private val module = "Lfixture/Module;"
    private val page = "Lfixture/Page;"
    private val extra = "Lfixture/Extra;"
    private val list = IMMUTABLE_LIST

    private fun method(
        definingClass: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        smali: String,
        static: Boolean = false,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            definingClass,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
                (if (name == "<init>" || name == "<clinit>") AccessFlags.CONSTRUCTOR.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun field(owner: String, name: String, type: String, static: Boolean = false) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null, null)

    private fun classDef(type: String, superclass: String, fields: List<ImmutableField>, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, fields, methods.toList())

    /**
     * An enum building [names] in its static initializer, the way Facebook's generated enums do:
     * name and ordinal into registers, the object built, copied, and the constructor called on the
     * copy. The constants in [fields] are stored in the field named beside them.
     */
    private fun roleEnum(
        names: List<String>,
        fields: Map<String, String> = emptyMap(),
        superclass: String = "Ljava/lang/Enum;",
        factory: Boolean = false,
    ): ClassDef {
        val smali = buildString {
            for ((ordinal, name) in names.withIndex()) {
                appendLine("const-string v1, \"$name\"")
                appendLine("const/16 v2, $ordinal")
                if (factory) {
                    // 577: a static factory of the enum's own, handed the name first.
                    appendLine("invoke-static { v1, v2 }, $role->A00(Ljava/lang/String;I)$role")
                    appendLine("move-result-object v0")
                    appendLine("move-object v3, v0")
                } else {
                    // 580: the object built, copied, and the constructor called on the copy.
                    appendLine("new-instance v0, $role")
                    appendLine("move-object v3, v0")
                    appendLine("invoke-direct { v3, v1, v2, v1 }, $role-><init>(Ljava/lang/String;ILjava/lang/String;)V")
                }
                fields.entries.firstOrNull { it.value == name }?.let { appendLine("sput-object v0, $role->${it.key}:$role") }
            }
            appendLine("return-void")
        }
        val clinit = method(role, "<clinit>", emptyList(), "V", 4, smali, static = true)
        return classDef(role, superclass, fields.keys.map { field(role, it, role, static = true) }, clinit)
    }

    private val adFields = mapOf("A0J" to "SEARCH_ADS", "A04" to "DEPENDENT_SEARCH_ADS", "A0E" to "LATE_DEPENDENT_SEARCH_ADS",
        "A0S" to "TOP_POSITION_SEARCH_ADS", "A0T" to "UNSET_OR_UNRECOGNIZED_ENUM_VALUE")

    @Test
    fun `the role enum is the enum that builds every ad role`() {
        val names = listOf("UNSET_OR_UNRECOGNIZED_ENUM_VALUE", "ENTITY_USER") + AD_ROLES
        assertTrue(isRoleEnum(roleEnum(names)))
        // Negative controls: one ad role missing, and the same names on a class that isn't an enum.
        assertFalse(isRoleEnum(roleEnum(names - "SEARCH_ADS_FLOATING_SEE_MORE")))
        assertFalse(isRoleEnum(roleEnum(names, superclass = "Ljava/lang/Object;")))
    }

    @Test
    fun `each enum field is named by the constructor call that built what it holds`() {
        val names = listOf("UNSET_OR_UNRECOGNIZED_ENUM_VALUE", "ENTITY_USER") + AD_ROLES
        assertEquals(adFields, enumConstantFields(roleEnum(names, adFields)))
        assertEquals("the factory form 577 uses", adFields, enumConstantFields(roleEnum(names, adFields, factory = true)))
        // A field stored from anything but a built constant isn't named.
        val odd = method(role, "<clinit>", emptyList(), "V", 2, """
            const-string v1, "SEARCH_ADS"
            sput-object v1, $role->A00:$role
            return-void
        """, static = true)
        assertEquals(emptyMap<String, String>(), enumConstantFields(classDef(role, "Ljava/lang/Enum;", emptyList(), odd)))
    }

    @Test
    fun `a page builder takes the session, the result and the search's context first`() {
        val parameters = listOf(USER_SESSION, GRAPHQL_RESULT, SEARCH_CONTEXT, "Lfixture/Logger;", "Ljava/lang/String;", "Z")
        fun builder(parameters: List<String>, returnType: String = page) =
            method("Lfixture/Builder;", "A01", parameters, returnType, 12, "const/4 v0, 0x0\nreturn-object v0")
        assertTrue(isPageBuilder(builder(parameters)))
        assertFalse(isPageBuilder(method("Lfixture/Builder;", "Dwv", parameters, "V", 12, "return-void")))
        assertFalse(isPageBuilder(builder(parameters, "Ljava/lang/Object;")))
        assertFalse(isPageBuilder(builder(parameters.reversed())))
        assertFalse(isPageBuilder(builder(parameters.take(2))))
    }

    private fun moduleConstructor(
        key: Int = treeFieldKey(RESULT_ROLE_FIELD),
        read: String = "invoke-virtual { p1, v0, v1 }, Lfixture/Tree;->getCachedEnum(ILjava/lang/Enum;)Ljava/lang/Enum;",
    ) = method(module, "<init>", listOf("Lfixture/Tree;"), "V", 4, """
        const v0, $key
        sget-object v1, $role->A0T:$role
        $read
        move-result-object v0
        check-cast v0, $role
        iput-object v0, p0, $module->A00:$role
        return-void
    """)

    private fun converter() = method(module, "A01", listOf(list), list, 1, "return-object p0", static = true)

    private fun adSet(vararg fields: String, set: Boolean = true) = method(module, "<clinit>", emptyList(), "V", 5, buildString {
        fields.forEachIndexed { index, name -> appendLine("sget-object v$index, $role->$name:$role") }
        if (set) appendLine("invoke-static { v0, v1, v2, v3 }, $IMMUTABLE_SET->A05(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)$IMMUTABLE_SET")
        appendLine("return-void")
    }, static = true)

    @Test
    fun `the module class holds one role, reads result_role and converts lists`() {
        val roleField = field(module, "A00", role)
        val good = classDef(module, "Ljava/lang/Object;", listOf(roleField), moduleConstructor(), converter())
        assertTrue(isModuleClass(good, role))
        assertEquals(listOf("A00"), roleFields(good, role).map { it.name })
        // Negative controls: no converter, two role fields, a constructor reading another field,
        // and a static field of the role, which is a constant rather than the module's own.
        assertFalse(isModuleClass(classDef(module, "Ljava/lang/Object;", listOf(roleField), moduleConstructor()), role))
        assertFalse(isModuleClass(classDef(module, "Ljava/lang/Object;", listOf(roleField, field(module, "A01", role)),
            moduleConstructor(), converter()), role))
        assertFalse(isModuleClass(classDef(module, "Ljava/lang/Object;", listOf(roleField),
            moduleConstructor(treeFieldKey("module_role")), converter()), role))
        assertFalse(isModuleClass(classDef(module, "Ljava/lang/Object;", listOf(field(module, "A00", role, static = true)),
            moduleConstructor(), converter()), role))
    }

    @Test
    fun `587's module reads result_role through a nullable or delegated enum read`() {
        val roleField = field(module, "A00", role)
        fun reads(read: String) = isModuleClass(classDef(module, "Ljava/lang/Object;", listOf(roleField),
            moduleConstructor(read = read), converter()), role)
        assertTrue(reads("invoke-virtual { p1, v0, v1 }, Lfixture/Tree;->getCachedNullableEnum(ILjava/lang/Enum;)Ljava/lang/Enum;"))
        assertTrue(reads("invoke-interface { p1, v0, v1 }, Lfixture/TreeReads;->Ey4(ILjava/lang/Enum;)Ljava/lang/Enum;"))
        // Negative controls: a virtual call of that shape with another name, and an interface read on a
        // JDK type, read no tree field.
        assertFalse(reads("invoke-virtual { p1, v0, v1 }, Lfixture/Tree;->Ey4(ILjava/lang/Enum;)Ljava/lang/Enum;"))
        assertFalse(reads("invoke-interface { p1, v0, v1 }, Ljava/util/function/Lookup;->get(ILjava/lang/Enum;)Ljava/lang/Enum;"))
    }

    @Test
    fun `the module's ad set is the ImmutableSet its static initializer builds from role constants`() {
        val names = mapOf("A0J" to "SEARCH_ADS", "A04" to "DEPENDENT_SEARCH_ADS", "A0E" to "LATE_DEPENDENT_SEARCH_ADS",
            "A0S" to "TOP_POSITION_SEARCH_ADS")
        fun owner(clinit: Method) = classDef(module, "Ljava/lang/Object;", emptyList(), clinit)
        assertEquals(FACEBOOK_AD_ROLES, adSetRoles(owner(adSet("A0J", "A04", "A0E", "A0S")), role, names))
        // A set built from other constants says so, a field the enum doesn't name shows as such,
        // and no set at all is nothing.
        assertEquals(setOf("SEARCH_ADS", "DEPENDENT_SEARCH_ADS", "LATE_DEPENDENT_SEARCH_ADS", "?A0X"),
            adSetRoles(owner(adSet("A0J", "A04", "A0E", "A0X")), role, names))
        assertEquals(emptySet<String>(), adSetRoles(owner(adSet("A0J", "A04", "A0E", "A0S", set = false)), role, names))
    }

    private val pageConstructor = method(page, "<init>", listOf(extra, list, list, "Ljava/lang/String;"), "V", 6, """
        invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
        return-void
    """)

    /** A builder handing the page constructor [first] and [second] as its two lists. */
    private fun builder(first: String, second: String) = method("Lfixture/Builder;", "A01",
        listOf(USER_SESSION, GRAPHQL_RESULT, SEARCH_CONTEXT, list), page, 12, """
        invoke-static { p3 }, $module->A01($list)$list
        move-result-object v1
        move-object v5, v1
        invoke-static { }, $list->of()$list
        move-result-object v2
        const/4 v4, 0x0
        const-string v6, "logging"
        new-instance v0, $page
        invoke-direct { v0, v4, $first, $second, v6 }, $page-><init>($extra$list${list}Ljava/lang/String;)V
        return-object v0
    """)

    @Test
    fun `the module list is the one parameter a builder fills with the converter's list`() {
        val converter = converter()
        assertEquals(setOf(1), moduleListParameters(listOf(builder("v5", "v2")), pageConstructor, converter))
        assertEquals(setOf(2), moduleListParameters(listOf(builder("v2", "v1")), pageConstructor, converter))
        assertEquals(setOf(1, 2), moduleListParameters(listOf(builder("v1", "v5")), pageConstructor, converter))
        // Negative control: a builder handing it only lists from elsewhere shows nothing.
        assertEquals(emptySet<Int>(), moduleListParameters(listOf(builder("v2", "v2")), pageConstructor, converter))
    }

    @Test
    fun `a register written after the converter's answer isn't its answer any more`() {
        val body = method("Lfixture/Builder;", "A01", listOf(list), page, 4, """
            invoke-static { p0 }, $module->A01($list)$list
            move-result-object v1
            const/4 v1, 0x0
            return-object v1
        """, static = true).implementation!!.instructions.toList()
        assertFalse(writtenBy(body, 3, 1, converter()))
        assertTrue(writtenBy(body, 2, 1, converter()))
    }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String {
        val call = (this as ReferenceInstruction).reference as MethodReference
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    /** A page constructor of the shape both search patches hand the shared hook: header, then the tail. */
    private val sharedPageParameters = listOf(extra, list, list, list, "Ljava/lang/String;", "Ljava/lang/String;",
        "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z")

    private fun sharedPageConstructor(registers: Int = 12) = method(page, "<init>", sharedPageParameters, "V", registers, """
        invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
        return-void
    """)

    @Test
    fun `the page has to be the shape the shared hook reads, with the modules where it reads them`() {
        requireSharedPageShape(sharedPageConstructor(), 1)
        // Negative controls: the module list somewhere else, and a constructor of another shape.
        val elsewhere = assertThrows(PatchException::class.java) { requireSharedPageShape(sharedPageConstructor(), 2) }
        assertTrue(elsewhere.message, elsewhere.message!!.contains("parameter 2"))
        val shape = assertThrows(PatchException::class.java) { requireSharedPageShape(pageConstructor, 1) }
        assertTrue(shape.message, shape.message!!.contains(PATCH))
    }

    /**
     * 585's short page constructor: an empty list and a null header filled in, then everything
     * handed to the full one with invoke-direct/range, or, with [toSuper], built on its own.
     */
    private fun shortPageConstructor(toSuper: Boolean = false) = method(page, "<init>",
        listOf(list, list, "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Z"), "V", 18,
        if (toSuper) """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            return-void
        """ else """
            const/4 v1, 0x0
            const/4 v10, 0x0
            invoke-static { }, $list->of()$list
            move-result-object v4
            move-object v0, v11
            move-object v2, v12
            move-object v3, v13
            move-object v5, v14
            move-object v6, v15
            move-object/from16 v7, v16
            move/from16 v9, v17
            move-object v8, v1
            invoke-direct/range { v0 .. v10 }, $page-><init>(${sharedPageParameters.joinToString("")})V
            return-void
        """)

    @Test
    fun `the page constructor is the page-shaped one, and any other has to hand its page to it`() {
        val full = sharedPageConstructor()
        assertTrue(full === pageConstructorOf(listOf(full), "Ljava/lang/Object;", PATCH))
        assertTrue(full === pageConstructorOf(listOf(shortPageConstructor(), full), "Ljava/lang/Object;", PATCH))
        // Negative controls: a second constructor building its page past the full one, two of the
        // page's shape, and none.
        val past = assertThrows(PatchException::class.java) {
            pageConstructorOf(listOf(full, shortPageConstructor(toSuper = true)), "Ljava/lang/Object;", PATCH)
        }
        assertTrue(past.message, past.message!!.contains("doesn't hand its page"))
        val two = assertThrows(PatchException::class.java) {
            pageConstructorOf(listOf(full, sharedPageConstructor()), "Ljava/lang/Object;", PATCH)
        }
        assertTrue(two.message, two.message!!.contains("found 2"))
        val none = assertThrows(PatchException::class.java) {
            pageConstructorOf(listOf(pageConstructor), "Ljava/lang/Object;", PATCH)
        }
        assertTrue(none.message, none.message!!.contains("found 0") && none.message!!.contains(PATCH))
    }

    @Test
    fun `the shared page hook goes in once, whichever search patch asks first`() {
        val constructor = MutableMethod(sharedPageConstructor())
        val own = constructor.body().size
        constructor.filterPageModulesFirst(PATCH)
        assertTrue(constructor.hasPageModulesHook())
        // this is v1, the header v2, the modules v3 and the page's name v7.
        assertEquals(KEPT_MODULES, constructor.body()[0].call())
        assertEquals(listOf(3, 7), listOf((constructor.body()[0] as FiveRegisterInstruction).registerC,
            (constructor.body()[0] as FiveRegisterInstruction).registerD))
        assertEquals(COPY_OF, constructor.body()[3].call())
        assertEquals(3, (constructor.body()[4] as OneRegisterInstruction).registerA)
        assertEquals(own + 5, constructor.body().size)
        // Hide Meta AI in search asking next finds it there and adds nothing.
        constructor.filterPageModulesFirst("Hide Meta AI in search")
        assertEquals("a second hook went in", own + 5, constructor.body().size)
        assertEquals(1, constructor.body().count { it.opcode == Opcode.INVOKE_STATIC && it.call() == KEPT_MODULES })
        assertFalse(MutableMethod(sharedPageConstructor()).hasPageModulesHook())
    }

    @Test
    fun `a page constructor with no local stops the patch, by its name`() {
        val tight = MutableMethod(sharedPageConstructor(registers = 11))
        val refusal = assertThrows(PatchException::class.java) { tight.filterPageModulesFirst(PATCH) }
        assertTrue(refusal.message, refusal.message!!.contains(PATCH))
        assertEquals(2, tight.body().size)
    }

    @Test
    fun `the patch requires the roles the extension hides, in the same order`() {
        val filter = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/ads/SearchAdFilter.java")
        val array = Regex("""AD_ROLES\s*=\s*\{([^}]*)\}""").find(filter.readText())
            ?: throw AssertionError("SearchAdFilter.java has no AD_ROLES array")
        val hidden = Regex(""""([A-Z_]+)"""").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(AD_ROLES, hidden)
        assertTrue("Facebook's own ad set is part of what's hidden", AD_ROLES.containsAll(FACEBOOK_AD_ROLES))
    }
}
