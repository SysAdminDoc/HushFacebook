/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The block button on reaction lists (#271) on every Facebook build the bundle declares. The row
 * state is the one method reading the kept friendingChannel off the row props right before it
 * stores Facebook's shared friending button, the row's own button is the mounted component holding
 * those props and a session, and the block helper is the comment button's. Nothing here names an
 * obfuscated class. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReactorBlockFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** How many branches of [code], switch cases included, land on a return-void. */
    private fun branchesToReturn(code: List<Instruction>): Int {
        val at = mutableMapOf<Int, Instruction>()
        val addresses = IntArray(code.size)
        var address = 0
        for ((i, instruction) in code.withIndex()) {
            addresses[i] = address
            at[address] = instruction
            address += instruction.codeUnits
        }
        val switches = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)
        // A switch's case offsets count from the switch, not from its payload.
        val switchAt = code.withIndex()
            .filter { it.value.opcode in switches }
            .associate { (i, instruction) -> addresses[i] + (instruction as OffsetInstruction).codeOffset to addresses[i] }
        var count = 0
        for ((i, instruction) in code.withIndex()) {
            if (instruction is SwitchPayload) {
                val from = switchAt[addresses[i]] ?: continue
                count += instruction.switchElements.count { at[from + it.offset]?.opcode == Opcode.RETURN_VOID }
            } else if (instruction is OffsetInstruction && instruction.opcode !in switches) {
                if (at[addresses[i] + instruction.codeOffset]?.opcode == Opcode.RETURN_VOID) count++
            }
        }
        return count
    }

    /**
     * The instance fields of the row props [holder], its superclasses' included, whose type (or a
     * supertype of it) has a public getId and getName answering a String: where ReactorBlock reads
     * the row's person. Types are fetched a level at a time, so the bundle is walked a few times only.
     */
    private fun personFields(bundle: File, holder: String): List<String> {
        val known = mutableMapOf<String, ClassDef?>()
        fun load(types: Set<String>) {
            val wanted = types - known.keys
            if (wanted.isEmpty()) return
            val found = FixtureDex.classes(bundle, wanted)
            for (type in wanted) known[type] = found[type]
        }
        fun ClassDef.supertypes() = listOfNotNull(superclass) + interfaces
        val fields = mutableListOf<Field>()
        var at: String? = holder
        while (at != null) {
            load(setOf(at))
            val classDef = known[at] ?: break
            fields += classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type.startsWith("L") }
            at = classDef.superclass
        }
        var pending = fields.map { it.type }.toSet()
        while (pending.isNotEmpty()) {
            load(pending)
            pending = pending.mapNotNull { known[it] }.flatMap { it.supertypes() }.filter { it !in known }.toSet()
        }
        fun answers(type: String, method: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
            if (!seen.add(type)) return false
            val classDef = known[type] ?: return false
            return classDef.methods.any {
                it.name == method && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
            } || classDef.supertypes().any { answers(it, method, seen) }
        }
        return fields.filter { answers(it.type, "getId") && answers(it.type, "getName") }.map { "${it.name}:${it.type}" }
    }

    private class Found(val state: ReactorRowState, val bind: Method, val block: BlockHelper, val classes: List<ClassDef>)

    private fun find(bundle: File): Found {
        val seen = mutableMapOf<String, ClassDef?>()
        val classOf = { type: String ->
            seen.getOrPut(type) { FixtureDex.classes(bundle, setOf(type)).values.singleOrNull() }
        }
        val methods = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == FRIENDING_CHANNEL } }) { method ->
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == FRIENDING_CHANNEL
            } == true
        }
        val state = reactorRowState(methods, classOf)
        val candidates = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == state.holder }) return@forEach
            for (classDef in dex.classes) {
                if (!classDef.type.startsWith(EXTENSION_CLASSES) && classDef.fields.any { it.type == state.holder }) {
                    candidates += ImmutableClassDef.of(classDef)
                }
            }
        }
        val bind = reactorButtonBind(candidates, state.holder)
        val helpers = FixtureDex.classesHolding(bundle, BLOCK_SHEET_QUERY)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { owner -> owner.methods.filter { holdsString(it, BLOCK_SHEET_QUERY) } }
        val block = blockHelper(helpers, classOf) { detail -> throw AssertionError("${bundle.name}: $detail") }
        val owners = listOf(state.method.definingClass, bind.definingClass).distinct().mapNotNull(classOf)
        return Found(state, bind, block, owners)
    }

    @Test
    fun `each declared build's reaction row button is found and hooked`() = bundles { bundle ->
        val name = bundle.name
        val found = find(bundle)
        val state = found.state
        val storeBefore = state.method.code()
        val bindBefore = found.bind.code()

        assertEquals("$name: the store is a field write", Opcode.IPUT_OBJECT, storeBefore[state.storeAt].opcode)
        assertEquals("$name: of the call's result", Opcode.MOVE_RESULT_OBJECT, storeBefore[state.storeAt - 1].opcode)
        assertEquals("$name: into a field of the button's type",
            state.button, ((storeBefore[state.storeAt] as ReferenceInstruction).reference as FieldReference).type)
        val people = personFields(bundle, state.holder)
        assertEquals("$name: the row props hold one person to read, found $people", 1, people.size)
        assertTrue("$name: the bind sets the button's tap, so it's the row's own button",
            bindBefore.any { it.reference() == "$VIEW->setOnClickListener(Landroid/view/View\$OnClickListener;)V" })
        assertTrue("$name: and hides it on rows Facebook offers none for",
            bindBefore.any { it.reference() == "$VIEW->setVisibility(I)V" })
        val returns = bindBefore.count { it.opcode == Opcode.RETURN_VOID }
        assertTrue("$name: some branch of the bind ends on a return", branchesToReturn(bindBefore) > 0)

        val context = PatchContexts.of(found.classes)
        hookReactorBlock(state, found.bind, found.block) { type -> context.mutableClassDefBy(type) }

        val store = context.mutableClassDefBy(state.method.definingClass).methods.single { it.descriptor() == state.method.descriptor() }.code()
        assertEquals("$name: the store gains three instructions", storeBefore.size + 3, store.size)
        assertEquals(
            "$name: the button goes to the extension, comes back and is cast before the store",
            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IPUT_OBJECT),
            store.subList(state.storeAt, state.storeAt + 4).map { it.opcode },
        )
        assertEquals("$name: the extension is asked", "$CLASS->sharedButton(Ljava/lang/Object;)Ljava/lang/Object;", store[state.storeAt].reference())
        assertEquals("$name: the cast", state.button, store[state.storeAt + 2].reference())
        assertTrue("$name: Facebook's state stays in order",
            storeBefore.map { it.opcode } == store.filterIndexed { i, _ -> i !in state.storeAt until state.storeAt + 3 }.map { it.opcode })

        val bind = context.mutableClassDefBy(found.bind.definingClass).methods.single { it.descriptor() == found.bind.descriptor() }
        val code = bind.code()
        assertEquals("$name: the bind gains three instructions and five at each return", bindBefore.size + 3 + 5 * returns, code.size)
        assertEquals(
            "$name: the component and the view go to the extension first",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
            code.take(3).map { it.opcode },
        )
        val registers = bind.implementation!!.registerCount
        assertEquals("$name: the component is this", registers - 4, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$name: the view is the last parameter", registers - 1, (code[1] as TwoRegisterInstruction).registerB)
        assertEquals("$name: the binding hook", "$CLASS->binding(Ljava/lang/Object;Ljava/lang/Object;)V", code[2].reference())
        val ends = code.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        assertEquals("$name: every return stays", returns, ends.size)
        for (end in ends) {
            assertEquals(
                "$name: the four classes, then the extension, before each return",
                listOf(state.holder, found.block.helper, found.block.profile, found.block.source,
                    "$CLASS->bound(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)V"),
                code.subList(end - 5, end).map { it.reference() },
            )
        }
        assertEquals("$name: no branch skips the hook to land on a return", 0, branchesToReturn(code))
    }

    @Test
    fun `a build with no reaction row or button refuses`() {
        assertThrows(PatchException::class.java) { reactorRowState(emptyList()) { null } }
        assertThrows(PatchException::class.java) { reactorButtonBind(emptyList(), "Lcom/example/Props;") }
    }

    @Test
    fun `the extension has the hooks`() {
        val methods = ExtensionDex.classDef(CLASS).methods.associateBy { it.name }
        fun signature(name: String) = methods[name]?.let { "(" + it.parameterTypes.joinToString("") + ")" + it.returnType }
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", signature("sharedButton"))
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)V", signature("binding"))
        assertEquals("(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)V", signature("bound"))
    }

    private companion object {
        const val CLASS = "Lapp/morphe/extension/facebook/comments/ReactorBlock;"
        const val VIEW = "Landroid/view/View;"
    }
}
