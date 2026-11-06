/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.story

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download any story's save action, found in every Facebook build the bundle declares the way the
 * patch finds it: one method among the types the story viewer's "More" menu creates reports
 * "save_story_attempted" (a literal on 577 and 580, a string table entry since 581), its class holds
 * one context and one story, and one menu method creates it. The control is the same search
 * without the menu: since 581 the composer's InspirationSaveButtonController names the event in a
 * method of the same shape, which is why the search starts at the menu. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SaveStoryActionFixtureTest {
    @Test
    fun `every declared build has one save action among the story menu's types, created by one menu method`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val menu = FixtureDex.classes(bundle, setOf(STORY_VIEWER_MORE_MENU)).getValue(STORY_VIEWER_MORE_MENU)
                val created = FixtureDex.classes(bundle, typesCreated(menu))
                val tables = FixtureDex.classes(bundle, tableTypes(created.values))
                val resolve = { call: MethodReference -> tables[call.definingClass]?.let { resolveStatic(it, call) } }

                val actions = created.values.flatMap { owner -> owner.methods.filter { isSaveStoryAction(it, resolve) } }
                assertEquals("$name: save actions among the menu's types: ${actions.map { "${it.definingClass}->${it.name}" }}",
                    1, actions.size)
                val action = created.getValue(actions.single().definingClass)
                for (type in listOf("Landroid/content/Context;", STORY_CARD)) {
                    assertEquals("$name: ${action.type}'s fields of $type", 1, action.fields.count { it.type == type })
                }
                val builders = menu.methods.filter { method ->
                    method.implementation?.instructions?.toList().orEmpty().any {
                        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == action.type
                    }
                }
                assertEquals("$name: menu methods creating ${action.type}", 1, builders.size)
                checked[version] = "${action.type} from ${builders.single().name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * Save photo (#38) is built where the menu builder adds its Save item: the item's range
     * constructor takes the action first and the list's add follows it, then the builder returns.
     * The title the photo item gets is the constructor's first String, stored in a field the story
     * menu reads first and draws when it's set, skipping the item's string resource.
     */
    @Test
    fun `every declared build adds its save item where a titled copy can follow, and the menu draws that title`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val menu = FixtureDex.classes(bundle, setOf(STORY_VIEWER_MORE_MENU)).getValue(STORY_VIEWER_MORE_MENU)
                val created = FixtureDex.classes(bundle, typesCreated(menu))
                val tables = FixtureDex.classes(bundle, tableTypes(created.values))
                val resolve = { call: MethodReference -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val action = created.getValue(created.values.flatMap { owner -> owner.methods.filter { isSaveStoryAction(it, resolve) } }
                    .single().definingClass)
                val builder = menu.methods.single { method ->
                    method.implementation?.instructions?.toList().orEmpty().any {
                        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == action.type
                    }
                }

                var site = saveItemSite(builder, action)
                val parameters = site.init.parameterTypes.map { it.toString() }
                val title = parameters.indexOf("Ljava/lang/String;")
                assertEquals("$name: the title register", site.itemRegister + 1 + title, site.titleRegister)
                assertEquals("$name: the item's arguments", site.itemRegister + parameters.size, site.lastRegister)

                // The constructor stores the title parameter in a field, and the menu reads that
                // field and branches on it being set before anything else of the item.
                val item = FixtureDex.classes(bundle, setOf(site.init.definingClass)).getValue(site.init.definingClass)
                val constructor = item.methods.single { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == parameters }
                val code = constructor.implementation!!
                val titleParameter = code.registerCount - parameters.size + title
                val field = code.instructions.firstNotNullOfOrNull { stored ->
                    if (stored.opcode != Opcode.IPUT_OBJECT || (stored as TwoRegisterInstruction).registerA != titleParameter) null
                    else ((stored as ReferenceInstruction).reference as FieldReference)
                }
                assertTrue("$name: ${item.type}'s constructor doesn't store its title", field != null)
                val drawn = menu.methods.any { method ->
                    val instructions = method.implementation?.instructions?.toList().orEmpty()
                    instructions.withIndex().any { (index, read) ->
                        read.opcode == Opcode.IGET_OBJECT && ((read as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == field!!.definingClass && it.name == field.name
                        } && instructions.getOrNull(index + 1)?.let { branch ->
                            branch.opcode == Opcode.IF_NEZ && (branch as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA
                        } == true
                    }
                }
                assertTrue("$name: the story menu never draws ${field!!.name} in place of the item's resource", drawn)

                // The patch's own edit, run over the build's classes: right after Save is added, a
                // titled copy is built from the same arguments and added, and with no title the
                // branch lands on the builder's return.
                val context = PatchContexts.of(listOf(menu, action, item))
                val patchedBuilder = context.mutableClassDefBy(STORY_VIEWER_MORE_MENU).methods
                    .single { MethodUtil.methodSignaturesMatch(it, builder) }
                val primarySite = site
                val helper = addStorySaveTitle(patchedBuilder, action, action.fields.single { it.type == STORY_CARD }.name)
                context.mutableClassDefBy(action.type).methods.add(helper)
                site = saveItemSite(patchedBuilder, action)
                assertEquals("$name: the native constructor and add are retained", primarySite.addIndex + 2, site.addIndex)
                val titled = patchedBuilder.implementation!!.instructions.toList()
                val ask = titled[primarySite.initIndex] as FiveRegisterInstruction
                assertEquals("$name: primary title reads action and original title, never the uninitialized item",
                    listOf(2, site.actionRegister, site.titleRegister), listOf(ask.registerCount, ask.registerC, ask.registerD))
                assertEquals("$name: primary title result goes back to the title argument", site.titleRegister,
                    (titled[primarySite.initIndex + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the item's constructor follows the title", site.init.toString(),
                    ((titled[primarySite.initIndex + 2] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: a cross-class bridge must be public static", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    helper.accessFlags)
                assertEquals("$name: the bridge belongs to the card field's owner", action.type, helper.definingClass)
                assertEquals("$name: the bridge reads its own story-card field", action.type,
                    ((helper.implementation!!.instructions.single { it.opcode == Opcode.IGET_OBJECT } as ReferenceInstruction)
                        .reference as FieldReference).definingClass)
                addSavePhotoItem(patchedBuilder, action, action.fields.single { it.type == STORY_CARD }.name)
                val patched = patchedBuilder.implementation!!.instructions.toList()
                assertEquals("$name: what follows Save's add", listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
                    Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT_RANGE,
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
                    patched.drop(site.addIndex + 1).map { it.opcode })
                val asked = patched[site.addIndex + 2] as FiveRegisterInstruction
                assertEquals("$name: the title comes from the card", listOf(1, site.titleRegister),
                    listOf(asked.registerCount, asked.registerC))
                assertEquals("$name: StoryPhoto.menuTitle", "menuTitle", ((asked as ReferenceInstruction).reference as MethodReference).name)
                val skip = patched[site.addIndex + 4] as BuilderOffsetInstruction
                assertEquals("$name: with no title the branch lands on the return", patched.size - 1, skip.target.location.index)
                val copy = patched[site.addIndex + 6] as RegisterRangeInstruction
                assertEquals("$name: the copy is built like Save", listOf(site.init.toString(),
                    site.itemRegister, site.lastRegister - site.itemRegister + 1),
                    listOf(((copy as ReferenceInstruction).reference as MethodReference).toString(),
                        copy.startRegister, copy.registerCount))
                val added = patched[site.addIndex + 8] as FiveRegisterInstruction
                assertEquals("$name: and added to the same list", listOf(site.listRegister, site.itemRegister),
                    listOf(added.registerC, added.registerD))
                checked[version] = "${site.init.definingClass} titled by ${field.name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /** The control: on each declared build a method the menu doesn't create has the action's shape and names the event too. */
    @Test
    fun `the composer's save button names the event in the same shape, outside the menu's types`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
        assertTrue("no declared build's fixture to check the control on", bundles.isNotEmpty())
        for (bundle in bundles) {
            val composer = "Lcom/facebook/inspiration/saving/InspirationSaveButtonController;"
            val owner = FixtureDex.classes(bundle, setOf(composer)).getValue(composer)
            val tables = FixtureDex.classes(bundle, tableTypes(listOf(owner)))
            val resolve = { call: MethodReference -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
            assertTrue("${bundle.name}: $composer has no method shaped like the save action",
                owner.methods.any { isSaveStoryAction(it, resolve) })
            val menu = FixtureDex.classes(bundle, setOf(STORY_VIEWER_MORE_MENU)).getValue(STORY_VIEWER_MORE_MENU)
            assertTrue("${bundle.name}: the story menu creates $composer", composer !in typesCreated(menu))
        }
    }

    /** The string tables [owners]' methods call. */
    private fun tableTypes(owners: Collection<ClassDef>): Set<String> = owners.flatMap { owner ->
        owner.methods.flatMap { method -> method.implementation?.instructions?.toList().orEmpty().filter(::isStringTableCall) }
    }.mapNotNull { ((it as ReferenceInstruction).reference as? MethodReference)?.definingClass }.toSet()
}
