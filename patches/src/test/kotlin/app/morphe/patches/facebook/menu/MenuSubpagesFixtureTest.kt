/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #276 on every Facebook bundle of every declared build: the Menu's root section binds its page
 * stack to its section context once, in its children build, which now tells the extension each
 * binding, and the stack's push and pop ask every other Menu on it for the page first. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MenuSubpagesFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Collection<Method>.like(method: Method) = single {
        it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    @Test
    fun `each declared bundle asks every Menu on the page stack for the page`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
        val checked = mutableMapOf<String, String>()
        for (bundle in bundles) {
            val name = bundle.name
            val holding = FixtureDex.classesHolding(bundle, ROOT_SECTION_QUERY)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
            val roots = holding.mapNotNull(::rootBinding)
            assertEquals("$name: root sections binding their page stack", 1, roots.size)
            val (build, binding) = roots.single()
            val others = FixtureDex.classes(bundle, setOf(binding.contextType, binding.stack))
            assertEquals("$name: the context and the stack classes", 2, others.size)
            val context = PatchContexts.of((holding + others.values).distinctBy { it.type })

            val found = context.menuSubpages()
            assertNotNull("$name: the parts the patch needs", found)
            found!!
            // Positive controls: Facebook's push and pop, each asking for the page update with one literal.
            assertEquals("$name: the stack's push and pop", 2, found.pages.size)
            assertEquals("$name: the tree field's owner", binding.contextType, found.tree.definingClass)
            val stackBefore = others.getValue(binding.stack)
            val pagesBefore = found.pages.map { (page, read) -> page.code() to read }
            val buildBefore = build.code()

            assertEquals("$name: what the patch changed", found, context.keepMenuSubpagesLive())
            val stack = context.mutableClassDefBy(binding.stack)
            assertEquals("$name: two helpers added to the stack", stackBefore.methods.count() + 2, stack.methods.size)
            assertTrue("$name: the helpers are there",
                stack.methods.map { it.name }.containsAll(listOf(BOUND_HELPER, OTHERS_HELPER)))

            val patchedBuild = context.mutableClassDefBy(build.definingClass).methods.like(build).code()
            assertEquals("$name: one instruction added to the children build", buildBefore.size + 1, patchedBuild.size)
            val bound = patchedBuild[binding.store + 1]
            assertEquals("$name: the binding goes to the helper right after the store",
                listOf(Opcode.INVOKE_STATIC, BOUND_HELPER), listOf(bound.opcode, bound.method()?.name))
            bound as FiveRegisterInstruction
            assertEquals("$name: with the stack and the context",
                listOf(binding.stackRegister, binding.contextRegister), listOf(bound.registerC, bound.registerD))
            assertEquals("$name: the store is Facebook's", binding.reference, patchedBuild[binding.store].field())

            for ((index, page) in found.pages.withIndex()) {
                val (code, read) = pagesBefore[index]
                val patched = stack.methods.like(page.first)
                val patchedCode = patched.code()
                assertEquals("$name: one instruction added to ${page.first.name}", code.size + 1, patchedCode.size)
                val ask = patchedCode[read]
                assertEquals("$name: ${page.first.name} asks the other Menus before it reads its own",
                    listOf(Opcode.INVOKE_STATIC_RANGE, OTHERS_HELPER), listOf(ask.opcode, ask.method()?.name))
                val stackRegister = (code[read] as TwoRegisterInstruction).registerB
                assertEquals("$name: with the stack", stackRegister, (ask as RegisterRangeInstruction).startRegister)
                assertEquals("$name: then Facebook's read", binding.reference, patchedCode[read + 1].field())
                // The jump past the header callback lands on the call, so a page change with no callback asks too.
                val landing = patched.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
                    .count { it.target.location.index == read }
                assertTrue("$name: a jump in ${page.first.name} lands on the call", landing > 0)
            }
            checked[name] = "${build.definingClass}->${build.name}, stack ${binding.stack}, update ${found.update}"
        }
        assertEquals("a declared bundle went unchecked: $checked", bundles.map { it.name }.toSet(), checked.keys)
        assertTrue("no declared build has a fixture bundle", checked.isNotEmpty())
    }
}
