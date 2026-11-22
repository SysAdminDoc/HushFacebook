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
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
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
import java.io.File
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

    /** The classes holding [string], and where one is a string table holding it, the classes asking it for [string]. */
    private fun classesNaming(bundle: File, string: String): List<ClassDef> {
        val held = FixtureDex.classesHolding(bundle, string)
        val tables = held.filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, string) } }.associateBy { it.type }
        if (tables.isEmpty()) return held
        val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        val asking = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() in tables } }) {
            !isStringTable(it) && !holdsString(it, string) && namesString(it, string, resolve)
        }.map { it.definingClass }.toSet()
        return (held + FixtureDex.classes(bundle, asking).values).distinctBy { it.type }
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
                // 588's Android 9 build has the logger ask a string table for the ids.
                val loggerClasses = classesNaming(bundle, REEL_INTERESTED)
                val byType = loggerClasses.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> byType[call.definingClass]?.let { resolveStatic(it, call) } }
                val sheets = loggerClasses.flatMap { owner -> owner.methods.filter { !isStringTable(it) }.mapNotNull { reelSheetOf(it, resolve) } }
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
    fun `each declared build's reel sheet header asks the extension before drawing Interested and Not interested`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val loggerClasses = classesNaming(bundle, REEL_INTERESTED)
                val byType = loggerClasses.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> byType[call.definingClass]?.let { resolveStatic(it, call) } }
                val titles = loggerClasses.flatMap { owner -> owner.methods.filter { !isStringTable(it) && reelSheetOf(it, resolve) != null } }
                    .mapNotNull { preferenceTitlesOf(it) }.distinct()
                assertEquals("$name: the header's title holders the logger reads", 1, titles.size)
                val renders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == titles.single() } }) {
                    readsBothTitles(it, titles.single())
                }
                assertEquals("$name: renders reading both titles", 1, renders.size)
                val render = renders.single()
                val rowsAt = preferenceRowsAt(render)
                assertTrue("$name: ${render.definingClass}->render hands no two rows on", rowsAt != null)

                val pool = (loggerClasses + ExtensionDex.classDef(SETTINGS_STATUS)).associateByTo(mutableMapOf()) { it.type }
                pool += FixtureDex.classes(bundle, setOf(render.definingClass, titles.single()))
                val context = PatchContexts.of(pool.values)
                val header = context.findReelPreferenceHeader()
                assertEquals("$name: the patch finds the same render", "${render.definingClass}->render", "${header.render.definingClass}->${header.render.name}")
                context.hookReelPreferenceHeader(header)

                val patched = context.mutableClassDefBy(render.definingClass).methods
                    .single { readsBothTitles(it, titles.single()) }.code()
                val original = render.code()
                assertEquals("$name: the question and the row filter", original.size + 5 + 2, patched.size)
                assertEquals("$name: the render asks first", POST_MENU_REEL_PREFERENCES_HIDDEN, patched[0].called())
                assertEquals("$name: and draws nothing on a yes",
                    listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    patched.subList(1, 5).map { it.opcode })
                val filter = patched[rowsAt!! + 5]
                assertEquals("$name: the rows go through the extension", POST_MENU_REEL_PREFERENCE_ROWS, filter.called())
                val rows = (filter as RegisterRangeInstruction).startRegister
                assertEquals("$name: the rows come back in v$rows", listOf(Opcode.MOVE_RESULT_OBJECT, rows),
                    listOf(patched[rowsAt + 6].opcode, (patched[rowsAt + 6] as OneRegisterInstruction).registerA))
                assertEquals("$name: then go to their column", original[rowsAt].called(), patched[rowsAt + 7].called())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build's reel row builder asks the extension before building a row`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val loggerClasses = classesNaming(bundle, REEL_INTERESTED)
                val byType = loggerClasses.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> byType[call.definingClass]?.let { resolveStatic(it, call) } }
                val sheet = loggerClasses.flatMap { owner -> owner.methods.filter { !isStringTable(it) }.mapNotNull { reelSheetOf(it, resolve) } }
                    .distinctBy { "${it.rows}|${it.model}->${it.idMethod}" }.single()
                val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == sheet.model } }) {
                    buildsReelRow(it, sheet.model)
                }
                assertEquals("$name: methods building a row from a ${sheet.model}", 1, builders.size)
                val builder = builders.single()

                val pool = (loggerClasses + ExtensionDex.classDef(SETTINGS_STATUS)).associateByTo(mutableMapOf()) { it.type }
                pool += FixtureDex.classes(bundle, setOf(builder.definingClass, sheet.model))
                val context = PatchContexts.of(pool.values)
                val found = context.findReelRowBuilder(sheet)
                assertEquals("$name: the patch finds the same builder", "${builder.definingClass}->${builder.name}",
                    "${found.definingClass}->${found.name}")
                context.hookReelRowBuilder(found, sheet)

                val original = builder.code()
                val patched = context.mutableClassDefBy(builder.definingClass).method(builder).code()
                assertEquals("$name: the question in front", original.size + 8, patched.size)
                assertEquals("$name: the model goes first", Opcode.MOVE_OBJECT_FROM16, patched[0].opcode)
                assertEquals("$name: with the names the extension reads its id by", listOf(javaName(sheet.model), sheet.idMethod),
                    patched.subList(1, 3).map { it.text() })
                assertEquals("$name: the builder asks first", POST_MENU_REEL_ROW_HIDDEN, patched[3].called())
                assertEquals("$name: and builds no row on a yes",
                    listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    patched.subList(4, 8).map { it.opcode })
                assertEquals("$name: then builds as before", original.map { it.opcode }, patched.drop(8).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the reel sheet header's hooks`() {
        val methods = ExtensionDex.classDef(POST_MENU_ITEMS).methods
        assertEquals(POST_MENU_REEL_PREFERENCES_HIDDEN, methods.singleOrNull { it.name == "reelPreferencesHidden" }?.descriptor())
        assertEquals(POST_MENU_REEL_PREFERENCE_ROWS, methods.singleOrNull { it.name == "reelPreferenceRows" }?.descriptor())
        assertEquals(POST_MENU_REEL_ROW_HIDDEN, methods.singleOrNull { it.name == "reelRowHidden" }?.descriptor())
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
