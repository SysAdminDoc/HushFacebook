/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postmenu

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.upsells.isEnumNaming
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post menu items on every Facebook build the bundle declares: the sheet hook finds the one method
 * that sorts a post menu, adds the helper that hands its holder to the extension with the names
 * read from the build, calls it on the returned register in front of each return, and the patch
 * turns on its own status and nobody else's. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class PostMenuItemsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.text() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun ClassDef.method(like: Method): Method = methods.single {
        it.name == like.name && it.returnType == like.returnType &&
            it.parameterTypes.map(CharSequence::toString) == like.parameterTypes.map(CharSequence::toString)
    }

    @Test
    fun `each declared build's post menu sorter hands its holder to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val kinds = FixtureDex.classesHolding(bundle, SEE_MORE).filter { isEnumNaming(it, POST_MENU_KINDS) }
                assertEquals("$name: post menu kind enums", 1, kinds.size)
                val kind = kinds.single().type
                val sorters = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == kind } }) {
                    isPostMenuSorter(it, kind)
                }
                assertEquals("$name: post menu sorters", 1, sorters.size)
                val sorter = sorters.single()
                val found = FixtureDex.classes(bundle, setOf(sorter.definingClass, sorter.returnType))
                val holder = found.getValue(sorter.returnType)
                val holderLists = holder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/util/List;" }
                    .map { it.name }

                val pool = (kinds + found.values + ExtensionDex.classDef(SETTINGS_STATUS)).associateBy { it.type }.values
                val context = PatchContexts.of(pool)
                // execute() runs only the patch it's called on, so the shared hook goes first.
                postMenuSheetHookPatch.execute(context)
                postMenuItemsPatch.execute(context)

                val owner = context.mutableClassDefBy(sorter.definingClass)
                val helper = owner.methods.single { it.name == ARRANGE_HELPER }
                assertTrue("$name: the helper isn't static", AccessFlags.STATIC.isSet(helper.accessFlags))
                assertEquals("$name: the helper's shape", listOf("Ljava/lang/Object;"), helper.parameterTypes.map { it.toString() })
                val handed = helper.code().mapNotNull { it.text() }
                assertEquals("$name: the helper's names", 3, handed.size)
                val (metadata, kindField, firstPage) = handed
                val metaClass = FixtureDex.classes(bundle, setOf("L${metadata.replace('.', '/')};")).values.singleOrNull()
                assertTrue("$name: $metadata isn't a class of this build", metaClass != null)
                assertEquals("$name: $metadata.$kindField isn't the kind", kind,
                    metaClass!!.fields.singleOrNull { it.name == kindField }?.type)
                assertTrue("$name: $firstPage isn't one of ${holder.type}'s lists", firstPage in holderLists)
                assertEquals("$name: the helper calls the extension", POST_MENU_ARRANGE,
                    helper.code().single { it.opcode == Opcode.INVOKE_STATIC }.called())

                val original = sorter.code()
                val patched = owner.method(sorter).code()
                val returns = original.count { it.opcode == Opcode.RETURN_OBJECT }
                assertEquals("$name: one call in front of each of the sorter's returns", original.size + returns, patched.size)
                patched.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.forEach { (at, ret) ->
                    val answer = (ret as OneRegisterInstruction).registerA
                    val call = patched[at - 1]
                    assertEquals("$name: the helper before return v$answer",
                        "${sorter.definingClass}->$ARRANGE_HELPER(Ljava/lang/Object;)V", call.called())
                    assertEquals("$name: the helper handed v$answer", listOf(answer, 1),
                        listOf((call as RegisterRangeInstruction).startRegister, call.registerCount))
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods
                fun answer(method: String) = (status.single { it.name == method }.code()[0] as NarrowLiteralInstruction).narrowLiteral
                assertEquals("$name: SettingsStatus.postMenuItems() isn't switched on", 1, answer("postMenuItems"))
                assertEquals("$name: the sheet hook switched on Share sheet items", 0, answer("shareSheetItems"))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build's reel More sheet hands every section's rows to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // The post menu's classes, so the patch gets past its own sheet to the reel one.
                val kind = FixtureDex.classesHolding(bundle, SEE_MORE).single { isEnumNaming(it, POST_MENU_KINDS) }
                val sorter = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == kind.type } }) {
                    isPostMenuSorter(it, kind.type)
                }.single()
                val loggerClasses = FixtureDex.classesHolding(bundle, REEL_INTERESTED)
                val sheets = loggerClasses.flatMap { it.methods.mapNotNull(::reelSheetOf) }
                    .distinctBy { "${it.rows}|${it.model}->${it.idMethod}" }
                assertEquals("$name: reel More sheet loggers", 1, sheets.size)
                val sheet = sheets.single()
                assertEquals("$name: the rows method's shape", "()Ljava/util/List;",
                    "(${sheet.rows.parameterTypes.joinToString("")})${sheet.rows.returnType}")

                val pool = (listOf(kind) + loggerClasses + ExtensionDex.classDef(SETTINGS_STATUS)).associateByTo(mutableMapOf()) { it.type }
                pool += FixtureDex.classes(bundle, setOf(sorter.definingClass, sorter.returnType, sheet.rows.definingClass))
                var model: String? = sheet.model
                while (model != null && model.startsWith("LX/")) {
                    val classDef = FixtureDex.classes(bundle, setOf(model)).values.singleOrNull() ?: break
                    pool[model] = classDef
                    model = classDef.superclass
                }
                val context = PatchContexts.of(pool.values)
                postMenuSheetHookPatch.execute(context)
                postMenuItemsPatch.execute(context)

                val owner = context.mutableClassDefBy(sheet.rows.definingClass)
                val helper = owner.methods.single { it.name == REEL_ROWS_HELPER }
                assertTrue("$name: the reel helper isn't static", AccessFlags.STATIC.isSet(helper.accessFlags))
                assertEquals("$name: the reel helper's names", listOf(javaName(sheet.model), sheet.idMethod),
                    helper.code().mapNotNull { it.text() })
                assertEquals("$name: the reel helper calls the extension", POST_MENU_REEL_ROWS,
                    helper.code().single { it.opcode == Opcode.INVOKE_STATIC }.called())

                val original = FixtureDex.classes(bundle, setOf(sheet.rows.definingClass)).getValue(sheet.rows.definingClass)
                    .methods.single { it.name == sheet.rows.name && it.parameterTypes.isEmpty() }.code()
                val patched = owner.methods.single { it.name == sheet.rows.name && it.parameterTypes.isEmpty() }.code()
                val returns = original.count { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$name: the rows method has no returns", returns > 0)
                assertEquals("$name: a call and a move in front of each return", original.size + 2 * returns, patched.size)
                patched.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.forEach { (at, ret) ->
                    val answer = (ret as OneRegisterInstruction).registerA
                    val call = patched[at - 2]
                    assertEquals("$name: the reel helper before return v$answer",
                        "${owner.type}->$REEL_ROWS_HELPER(Ljava/util/List;)Ljava/util/List;", call.called())
                    assertEquals("$name: the reel helper handed v$answer", listOf(answer, 1),
                        listOf((call as RegisterRangeInstruction).startRegister, call.registerCount))
                    val move = patched[at - 1]
                    assertEquals("$name: the answer goes back in v$answer", listOf(Opcode.MOVE_RESULT_OBJECT, answer),
                        listOf(move.opcode, (move as OneRegisterInstruction).registerA))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the reel sheet's hook`() {
        val hook = ExtensionDex.classDef(POST_MENU_ITEMS).methods.singleOrNull { it.name == "reelRows" }
        assertTrue("the extension has no PostMenuItems.reelRows", hook != null)
        assertEquals("reelRows' shape", POST_MENU_REEL_ROWS, hook!!.descriptor())
        assertTrue("reelRows isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    @Test
    fun `the extension has the sheet's hook`() {
        val hook = ExtensionDex.classDef(POST_MENU_ITEMS).methods.singleOrNull { it.name == "arrange" }
        assertTrue("the extension has no PostMenuItems.arrange", hook != null)
        assertEquals("arrange's shape", POST_MENU_ARRANGE, hook!!.descriptor())
        assertTrue("arrange isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
