/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the live video comment bar (#537) on every declared build: one view tags itself
 * [LIVE_COMPOSER_TAG] as it's built, one view's constructor casts a child to it, and every return of
 * that constructor hands the bar to the extension first. Read from 588 (2026-11-21): the comment box
 * is `LX/0RbN;`, the bar `LX/0RbO;`, and the bar's constructor has one return, which a branch reaches
 * too. None of those names is used here.
 */
class LiveCommentBarFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun referencesType(method: com.android.tools.smali.dexlib2.iface.Method, type: String) =
        method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == type } == true

    @Test
    fun `each declared build's live video bar hands itself over as it's built`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val composers = FixtureDex.classesHolding(bundle, LIVE_COMPOSER_TAG)
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }.filter(::isLiveComposer)
                assertEquals("$name: views tagging themselves $LIVE_COMPOSER_TAG", 1, composers.size)
                val composer = composers.single()
                // Every class naming the comment box's type goes in, the controllers casting it included,
                // so the patch's own search has to tell the bar from them.
                val naming = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == composer.type } }) {
                    referencesType(it, composer.type)
                }.map { it.definingClass }.toSet() - composer.type
                val classes = FixtureDex.classes(bundle, naming + composer.type)
                assertTrue("$name: fewer than two classes besides the box name it (${naming.size})", naming.size >= 2)
                val bars = classes.values.mapNotNull { classDef -> liveBarConstructor(classDef, composer.type)?.let { classDef } }
                    .filter { it.type != composer.type }
                assertEquals("$name: views whose constructor casts a child to the box", 1, bars.size)
                val bar = bars.single()
                val init = liveBarConstructor(bar, composer.type)!!
                val stock = init.implementation!!.instructions.toList()
                val returns = stock.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
                assertTrue("$name: the bar's constructor has no return", returns.isNotEmpty())

                val context = PatchContexts.of(classes.values.toList())
                assertEquals("$name: the patch found another bar", bar.type, context.liveBar { error("$name: $it") }!!.bar.type)
                context.hideLiveCommentBar("test")
                val patched = context.mutableClassDefBy(bar.type).methods
                    .single { it.name == init.name && it.parameterTypes == init.parameterTypes }
                    .implementation!!.instructions.toList()
                assertEquals("$name: one call per return", stock.size + returns.size, patched.size)
                val calls = patched.withIndex().filter { (_, instruction) ->
                    instruction.opcode == Opcode.INVOKE_STATIC &&
                        ((instruction as ReferenceInstruction).reference as MethodReference).let {
                            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                        } == LIVE_BAR_BUILT
                }.map { it.index }
                assertEquals("$name: calls to the extension", returns.size, calls.size)
                val self = init.implementation!!.registerCount - 4
                for (call in calls) {
                    assertEquals("$name: the call at $call isn't right before a return", Opcode.RETURN_VOID, patched[call + 1].opcode)
                    assertEquals("$name: the call at $call hands over something besides the bar", self,
                        (patched[call] as FiveRegisterInstruction).registerC)
                    val skipping = patched.withIndex().filter { (index, instruction) ->
                        index != call && (instruction as? BuilderOffsetInstruction)?.target?.location?.index == call + 1
                    }
                    assertTrue("$name: a branch goes past the call at $call: ${skipping.map { it.index }}", skipping.isEmpty())
                }
                // Positive control: a branch in Facebook's constructor reaches a return, and now reaches the call.
                assertTrue("$name: no branch reaches the call", patched.any { instruction ->
                    (instruction as? BuilderOffsetInstruction)?.target?.location?.index in calls
                })
                assertEquals("$name: the rest of the constructor is Facebook's", stock.map { it.opcode },
                    patched.filterIndexed { index, _ -> index !in calls }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val hook = ExtensionDex.classDef(LIVE_COMMENT_BAR).methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == LIVE_BAR_BUILT
        }
        assertTrue("the extension has no static $LIVE_BAR_BUILT", hook != null && AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
