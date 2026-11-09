/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Hide the search pill hook (#173) on every Facebook build the bundle declares
 * (arm64 and armv7): one Litho component names FbShortsSearchElevatedCTAComponent and renders
 * search_pivot_view_tag, one static builder makes it and ends in Facebook's empty answer, and the
 * patch puts the extension's question in front of that builder. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SearchPillFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    @Test
    fun `each declared build's pill builder answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val named = FixtureDex.classesHolding(bundle, ELEVATED_CTA)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                // 585 moved the view tag into a string table the render asks, so the tables the
                // candidates call are read before the component is picked, the way the patch reads them.
                val tableOwners = named.flatMap { it.methods }.flatMap { method ->
                    method.implementation?.instructions?.toList().orEmpty().filter(::isStringTableCall)
                        .mapNotNull { ((it as ReferenceInstruction).reference as? MethodReference)?.definingClass }
                }.toSet()
                val tables = FixtureDex.classes(bundle, tableOwners)
                val resolve = { call: MethodReference -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val components = named.filter { isPillComponent(it, resolve) }
                assertEquals("$name: components that name and render the pill", 1, components.size)
                val component = components.single()

                // Positive control: the view tag the pill sets is named by the component's render method
                // (held, or asked of a string table), and a class that only names the component (the
                // decoy) doesn't qualify.
                val render = component.methods.single { it.name == "render" }
                assertTrue("$name: the component's render doesn't name $PIVOT_VIEW_TAG", namesString(render, PIVOT_VIEW_TAG, resolve))
                assertTrue("$name: nothing else is rejected, so the check proves nothing",
                    named.size >= 1 && named.all { it.type == component.type || !isPillComponent(it, resolve) })

                val builders = FixtureDex.methodsWhere(bundle, { true }) { isPillBuilder(it, component.type) }
                    .filter { !it.definingClass.startsWith("L$EXTENSION_PACKAGE") }
                assertEquals("$name: static builders of the pill", 1, builders.size)
                val builder = builders.single()
                val empty = emptyComponentCall(builder)
                assertNotNull("$name: the builder ends in no empty answer", empty)
                val owner = FixtureDex.classes(bundle, setOf(builder.definingClass)).getValue(builder.definingClass)

                val context = PatchContexts.of((listOf(component, owner) + tables.values).distinctBy { it.type })
                context.hideSearchPill()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == builder.name && it.returnType == builder.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }.code()
                val original = builder.code()
                assertEquals("$name: six instructions added", original.size + 6, patched.size)
                assertEquals("$name: the extension is asked first", HIDE_SEARCH_PILL,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: shape of the early answer",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC,
                        Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                    patched.take(6).map { it.opcode })
                assertEquals("$name: the early answer is Facebook's empty component", empty.toString(),
                    ((patched[3] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.drop(6).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build's comment sheet hands its search prompt to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val builders = FixtureDex.methodsWhere(bundle, { true }, ::isCommentPillBuilder)
                    .filter { !it.definingClass.startsWith("L$EXTENSION_PACKAGE") }
                assertEquals("$name: static builders holding $COMMENT_PIVOT", 1, builders.size)
                val builder = builders.single()
                val owner = FixtureDex.classes(bundle, setOf(builder.definingClass)).getValue(builder.definingClass)
                val callers = owner.methods.filter { callsBuilder(it, builder) }
                assertEquals("$name: callers of the comment pill builder", 1, callers.size)
                val caller = callers.single()

                // Positive control: another method of the class reads the prompt too and the caller
                // check turns it away, and the one caller tests the prompt for null after reading it.
                assertTrue("$name: nothing else reads the prompt, so the caller check proves nothing",
                    owner.methods.count { promptReads(it).isNotEmpty() } >= 2)
                val original = caller.code()
                val read = promptReads(caller).single()
                val register = (original[read] as TwoRegisterInstruction).registerA
                assertTrue("$name: the prompt isn't null-checked after it's read",
                    original.drop(read + 1).any { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == register })

                val context = PatchContexts.of(listOf(owner))
                context.hideCommentSearchPill()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == caller.name && it.parameterTypes.map(CharSequence::toString) ==
                        caller.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: three instructions added", original.size + 3, patched.size)
                assertEquals("$name: shape of the hook",
                    listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
                    patched.subList(read, read + 4).map { it.opcode })
                assertEquals("$name: the extension is asked", COMMENT_SEARCH_PROMPT,
                    ((patched[read + 1] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the answer goes back where the prompt was", register,
                    (patched[read + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: and is cast back to the prompt's type", SEARCH_PROMPT_PARAM,
                    ((patched[read + 3] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.take(read + 1).map { it.opcode } + patched.drop(read + 4).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The indexes some branch in [code] lands on. */
    private fun landings(code: List<Instruction>): Set<Int> {
        var address = 0
        val addresses = code.map { instruction -> address.also { address += instruction.codeUnits } }
        return code.withIndex().filter { it.value is OffsetInstruction }
            .map { addresses.indexOf(addresses[it.index] + (it.value as OffsetInstruction).codeOffset) }.toSet()
    }

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    @Test
    fun `each declared build's reel header hands its search bar and send button to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val headers = FixtureDex.methodsWhere(bundle, { true }, ::isSearchBarHeader)
                    .filter { !it.definingClass.startsWith("L$EXTENSION_PACKAGE") }
                assertTrue("$name: header builders holding $SEARCH_BAR and $SEARCH_BAR_SEND: ${headers.size}", headers.size <= 1)
                val jumped = headers.isNotEmpty()
                val (header, adds) = if (jumped) {
                    val header = headers.single()
                    header to listOf(SEARCH_BAR, SEARCH_BAR_SEND).map { literal ->
                        partAddAfter(header, literal) ?: throw AssertionError("$name: nothing adds the part built after $literal")
                    }
                } else {
                    // 586: each part's wrapper holds its own tag, and one method of their class asks
                    // both and adds each answer right away.
                    val wrappers = listOf(SEARCH_BAR, SEARCH_BAR_SEND).map { literal ->
                        val found = FixtureDex.methodsWhere(bundle, { true }) { isSearchBarPartWrapper(it, literal) }
                            .filter { !it.definingClass.startsWith("L$EXTENSION_PACKAGE") }
                        assertEquals("$name: static wrappers holding $literal", 1, found.size)
                        found.single()
                    }
                    val home = wrappers.first().definingClass
                    val callers = FixtureDex.classes(bundle, setOf(home)).getValue(home).methods
                        .filter { method -> wrappers.all { callsBuilder(method, it) } }
                    assertEquals("$name: methods asking both wrappers", 1, callers.size)
                    val caller = callers.single()
                    caller to wrappers.map {
                        partAddAfterCall(caller, it) ?: throw AssertionError("$name: nothing adds what ${it.name} answers")
                    }
                }
                val original = header.code()

                // Positive controls: both parts go in through one call, which skips a null part first
                // thing. Up to 585 Facebook jumps straight to each add, so a hook put in front would be passed by.
                val calls = adds.map { original[it].call }.toSet()
                assertEquals("$name: the bar and the button go in through different calls", 1, calls.size)
                val callee = (original[adds.first()] as ReferenceInstruction).reference as MethodReference
                val row = FixtureDex.classes(bundle, setOf(callee.definingClass)).getValue(callee.definingClass)
                val add = row.methods.single {
                    it.name == callee.name && it.parameterTypes.map(CharSequence::toString) ==
                        callee.parameterTypes.map(CharSequence::toString)
                }
                val first = add.code().first()
                assertEquals("$name: the add doesn't test its part first", Opcode.IF_EQZ, first.opcode)
                assertEquals("$name: the add's first test isn't of its part", add.implementation!!.registerCount - 1,
                    (first as OneRegisterInstruction).registerA)
                if (jumped) assertTrue("$name: no jump lands on the adds", adds.all { it in landings(original) })

                val owner = FixtureDex.classes(bundle, setOf(header.definingClass)).getValue(header.definingClass)
                val context = PatchContexts.of(listOf(owner))
                context.hideSearchBar()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == header.name && it.returnType == header.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == header.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: three instructions added per part", original.size + 6, patched.size)
                val hooks = adds.sorted().mapIndexed { n, at -> at + 3 * n }
                for (hook in hooks) {
                    assertEquals("$name: shape of the hook at $hook",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL),
                        patched.subList(hook, hook + 4).map { it.opcode })
                    assertEquals("$name: the extension is asked", SEARCH_BAR_PART, patched[hook].call)
                    assertEquals("$name: and Facebook's add follows", calls.single(), patched[hook + 3].call)
                }
                if (jumped) assertTrue("$name: Facebook's jumps don't reach the hooks", hooks.all { it in landings(patched) })
                val hookParts = hooks.flatMap { listOf(it, it + 1, it + 2) }.toSet()
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode },
                    patched.filterIndexed { index, _ -> index !in hookParts }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the search bar hook`() {
        val type = SEARCH_BAR_PART.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "searchBarPart" }
        assertNotNull("the extension has no ReelDeclutter.searchBarPart", hook)
        assertEquals("searchBarPart's shape", SEARCH_BAR_PART,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("searchBarPart isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    @Test
    fun `the extension has the comment prompt hook`() {
        val type = COMMENT_SEARCH_PROMPT.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "commentSearchPrompt" }
        assertNotNull("the extension has no ReelDeclutter.commentSearchPrompt", hook)
        assertEquals("commentSearchPrompt's shape", COMMENT_SEARCH_PROMPT,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("commentSearchPrompt isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_SEARCH_PILL.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideSearchPill" }
        assertNotNull("the extension has no ReelDeclutter.hideSearchPill", hook)
        assertEquals("hideSearchPill's shape", HIDE_SEARCH_PILL,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideSearchPill isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
