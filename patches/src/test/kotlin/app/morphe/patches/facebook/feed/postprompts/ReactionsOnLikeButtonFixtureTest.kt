/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Show reactions on the Like button (#541) on every Facebook build the bundle declares: the Like
 * button's plugin has two look builders, every return of both goes through the helper the patch adds,
 * and the one renderer of a drawable icon sets a faces icon's width again right after its square size.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReactionsOnLikeButtonFixtureTest {
    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun ClassDef.same(method: Method) = methods.single { it.smali() == method.smali() }

    @Test
    fun `each declared build's Like button gets the faces`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classOf = { type: String -> FixtureDex.classes(bundle, setOf(type))[type] }
                val plugin = classOf(LIKE_BUTTON_PLUGIN)
                assertNotNull("$name: no $LIKE_BUTTON_PLUGIN", plugin)
                val found = likeLook(plugin!!, classOf) { fail("$name: $it") }!!
                assertEquals("$name: the plugin's look builders", 2, found.builders.size)
                // Negative control: the plugin has other static methods, which the builder check leaves out.
                assertTrue("$name: every static method passed for a builder, so the check proves nothing",
                    plugin.methods.count { AccessFlags.STATIC.isSet(it.accessFlags) } > found.builders.size)

                val field = "${found.drawableIconField.definingClass}->${found.drawableIconField.name}:${found.drawableIconField.type}"
                val renderers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == found.drawableIcon } }) { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { ref ->
                            "${ref.definingClass}->${ref.name}:${ref.type}" == field
                        }
                    } == true
                }.distinctBy { it.smali() }
                assertEquals("$name: methods reading the drawable icon's Drawable", 1, renderers.size)
                val renderer = renderers.single()
                val rendererClass = classOf(renderer.definingClass)!!
                // The class of the style helper that sizes the box, which the patch reads too.
                val sizers = mutableListOf<ClassDef>()
                val box = facesBox(rendererClass.same(renderer), { ref ->
                    classOf(ref.definingClass)?.also { sizers += it }?.methods?.firstOrNull { it.smali() == "$ref" }
                }) { fail("$name: $it") }!!

                // The litho footer's own look builder, the one other method that builds a look from a
                // reaction. The Meta AI, comment, share and repost buttons build looks too, with no
                // reaction, so they're the negative control.
                val builderNames = found.builders.map { it.smali() }.toSet()
                val sites = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == found.look.type } }) {
                    isLookSite(found, builderNames, it)
                }.distinctBy { it.smali() }
                assertEquals("$name: methods building the look from a reaction outside the plugin", 1, sites.size)
                val siteClass = classOf(sites.single().definingClass)!!
                val site = siteClass.same(sites.single())
                val built = looksBuilt(found, site) { fail("$name: $it") }!!
                assertTrue("$name: ${site.smali()} builds no look", built.isNotEmpty())

                val pool = (listOfNotNull(plugin, found.look, classOf(found.drawableIcon), rendererClass, siteClass) + sizers)
                    .distinctBy { it.type }
                val context = PatchContexts.of(pool)
                context.reactionsOnLikeButton()

                // The look helper: static, (look, reaction) -> look, asking whether to note the icon first.
                val patchedPlugin = context.mutableClassDefBy(LIKE_BUTTON_PLUGIN)
                val helper = patchedPlugin.methods.singleOrNull { it.name == FACES_HELPER }
                assertNotNull("$name: the plugin has no $FACES_HELPER", helper)
                assertTrue("$name: the look helper isn't static", AccessFlags.STATIC.isSet(helper!!.accessFlags))
                assertEquals("$name: the look helper's shape", facesHelperRef(found), helper.smali())
                val helperCalls = helper.implementation!!.instructions.mapNotNull { it.methodRef()?.toString() }
                assertEquals("$name: the look helper doesn't ask about the switch first", FACES_REMEMBERING, helperCalls.first())
                assertTrue("$name: the look helper never hands the icon over", FACES_REMEMBER in helperCalls)
                assertTrue("$name: the look helper never asks for the reaction's face", found.face.toString() in helperCalls)

                // The finished-look helper: static, (look) -> look, asking the extension for faces.
                val lookHelper = patchedPlugin.methods.singleOrNull { it.name == FACES_LOOK }
                assertNotNull("$name: the plugin has no $FACES_LOOK", lookHelper)
                assertTrue("$name: the finished-look helper isn't static", AccessFlags.STATIC.isSet(lookHelper!!.accessFlags))
                assertEquals("$name: the finished-look helper's shape", facesLookRef(found), lookHelper.smali())
                val lookCalls = lookHelper.implementation!!.instructions.mapNotNull { it.methodRef()?.toString() }
                assertEquals("$name: the finished-look helper doesn't ask for faces first", FACES_FOR, lookCalls.first())
                assertTrue("$name: the finished-look helper builds no drawable icon", found.drawableIconInit.toString() in lookCalls)

                // Each of the renderer's constructors taking a look swaps it first thing.
                val inits = rendererInits(found, rendererClass)
                assertTrue("$name: ${rendererClass.type} has no constructor taking a look", inits.isNotEmpty())
                for ((init, register) in inits) {
                    val stockInit = init.implementation!!.instructions.toList()
                    val patchedInit = context.mutableClassDefBy(rendererClass.type).same(init).implementation!!.instructions.toList()
                    assertEquals("$name: ${init.smali()}: two instructions added", stockInit.size + 2, patchedInit.size)
                    assertEquals("$name: ${init.smali()}: the look isn't swapped first", lookHelper.smali(),
                        patchedInit[0].methodRef()?.toString())
                    assertEquals("$name: ${init.smali()}: the swap reads another register", register,
                        (patchedInit[0] as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: ${init.smali()}: the swapped look isn't kept", register,
                        (patchedInit[1] as OneRegisterInstruction).registerA)
                    assertEquals("$name: ${init.smali()}: the rest is Facebook's", stockInit.map { it.opcode },
                        patchedInit.drop(2).map { it.opcode })
                }

                // Every return of both builders hands its look to the helper and returns the answer.
                for (builder in found.builders) {
                    val stock = builder.implementation!!.instructions.toList()
                    val patched = patchedPlugin.same(builder).implementation!!.instructions.toList()
                    val returns = stock.count { it.opcode == Opcode.RETURN_OBJECT }
                    assertTrue("$name: ${builder.smali()} returns nothing", returns > 0)
                    assertEquals("$name: ${builder.smali()}: two instructions per return", stock.size + 2 * returns, patched.size)
                    patched.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.forEach { (index, ret) ->
                        val answer = (ret as OneRegisterInstruction).registerA
                        assertEquals("$name: ${builder.smali()}: a return doesn't go through the helper",
                            helper.smali(), patched[index - 2].methodRef()?.toString())
                        assertEquals("$name: ${builder.smali()}: the helper's answer isn't what's returned",
                            answer, (patched[index - 1] as OneRegisterInstruction).registerA)
                    }
                }

                // The litho footer hands each look it builds to the helper right after the constructor,
                // with the reaction moved into a free register when it arrives past v15.
                val stockSite = site.implementation!!.instructions.toList()
                val patchedSite = context.mutableClassDefBy(site.definingClass).same(site).implementation!!.instructions.toList()
                val calls = patchedSite.indices.filter { patchedSite[it].methodRef()?.toString() == helper.smali() }
                assertEquals("$name: ${site.smali()}: looks handed to the helper", built.size, calls.size)
                for (at in calls) {
                    val init = if (patchedSite[at - 1].opcode == Opcode.MOVE_OBJECT_FROM16) at - 2 else at - 1
                    assertEquals("$name: ${site.smali()}: the helper doesn't follow the look's constructor",
                        found.lookInit.toString(), patchedSite[init].methodRef()?.toString())
                    assertEquals("$name: ${site.smali()}: the helper's answer isn't kept", Opcode.MOVE_RESULT_OBJECT,
                        patchedSite[at + 1].opcode)
                }
                assertEquals("$name: ${site.smali()}: instructions added",
                    stockSite.size + built.sumOf { if (it.held == it.reaction) 2 else 3 }, patchedSite.size)

                // The renderer asks whether the icon is faces right after the square size, and for faces
                // sets the width again with the size helper's own setter.
                val stockRenderer = renderer.implementation!!.instructions.toList()
                val patchedRenderer = context.mutableClassDefBy(renderer.definingClass).same(renderer)
                    .implementation!!.instructions.toList()
                assertEquals("$name: the renderer: seven instructions added", stockRenderer.size + 7, patchedRenderer.size)
                assertEquals("$name: the renderer: the size helper's result comes right before the question",
                    Opcode.MOVE_RESULT_OBJECT, patchedRenderer[box.at - 1].opcode)
                assertEquals("$name: the renderer asks whether the icon is faces", FACES_IS,
                    patchedRenderer[box.at].methodRef()?.toString())
                assertEquals("$name: the renderer asks for the faces' width", FACES_WIDTH,
                    patchedRenderer[box.at + 3].methodRef()?.toString())
                assertEquals("$name: the renderer sets the width with the size helper's setter", box.widthSetter.toString(),
                    patchedRenderer[box.at + 5].methodRef()?.toString())
                assertEquals("$name: the rest of the renderer is Facebook's", stockRenderer.map { it.opcode },
                    patchedRenderer.take(box.at).map { it.opcode } + patchedRenderer.drop(box.at + 7).map { it.opcode })

                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hooks`() {
        val type = LIKE_BUTTON_FACES
        val methods = ExtensionDex.classDef(type).methods
        for (wanted in listOf(FACES_REMEMBERING, FACES_REMEMBER, FACES_FOR, FACES_IS, FACES_WIDTH)) {
            val hook = methods.singleOrNull { it.smali() == wanted }
            assertNotNull("the extension has no $wanted", hook)
            assertTrue("$wanted isn't public and static", AccessFlags.STATIC.isSet(hook!!.accessFlags) &&
                AccessFlags.PUBLIC.isSet(hook.accessFlags))
        }
    }
}
