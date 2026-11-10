/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.layout.theme.INLINE_REACTIONS
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.watchhistory.callRegisters
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide the reaction preview on posts hook (#158) on every Facebook build the
 * bundle declares (arm64 and armv7): FDSInlineReactions has one static factory returning a Drawable
 * subclass with a draw(Canvas), PillsBlingBarView has one onDraw(Canvas), and the patch puts the
 * extension's question in front of both. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class ReactionPreviewFixtureTest {
    private fun ClassDef.method(name: String) =
        methods.single { isDrawMethod(it, name) }

    @Test
    fun `each declared build's two reaction drawers answer through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val components = FixtureDex.classesHolding(bundle, INLINE_REACTIONS)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                assertEquals("$name: components named $INLINE_REACTIONS", 1, components.size)
                val component = components.single()

                // The factory's candidates are every type a static method returns; only one is a Drawable.
                val candidates = facepileTypes(component) { true }
                assertTrue("$name: the component has no static factory", candidates.isNotEmpty())
                val loaded = FixtureDex.classes(bundle, candidates.toSet())
                val faces = facepileTypes(component) { type ->
                    loaded[type]?.let { it.superclass == DRAWABLE && it.methods.any { m -> isDrawMethod(m, "draw") } } == true
                }
                assertEquals("$name: Drawables built by the component's static factory", 1, faces.size)
                val facepile = FixtureDex.classes(bundle, setOf(faces.single())).getValue(faces.single())
                // Negative control: a type the factory returns that isn't a Drawable is not taken.
                assertTrue("$name: nothing was rejected, so the Drawable check proves nothing",
                    candidates.size == 1 || candidates.any { it != facepile.type })

                val bar = FixtureDex.classes(bundle, setOf(PILLS_BLING_BAR))[PILLS_BLING_BAR]
                assertNotNull("$name: no $PILLS_BLING_BAR", bar)
                assertEquals("$name: onDraw(Canvas) methods on the bling bar", 1, bar!!.methods.count { isDrawMethod(it, "onDraw") })

                // The comment component, and the methods that call the factory (a negative control: not all pair it with a component).
                val commentClasses = FixtureDex.classesHolding(bundle, COMMENT_REACTION_KEY).filter(::isCommentComponent)
                assertEquals("$name: classes holding the comment component's strings", 1, commentClasses.size)
                val comment = commentClasses.single()
                val factory = component.methods.single {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == facepile.type
                }
                val factoryRef = ImmutableMethodReference(component.type, factory.name,
                    factory.parameterTypes.map(CharSequence::toString), factory.returnType)
                val usesFactory = { dex: DexBackedDexFile ->
                    dex.methodSection.any { it.definingClass == component.type && it.name == factory.name }
                }
                val everyCaller = FixtureDex.methodsWhere(bundle, usesFactory) { method ->
                    method.definingClass != component.type && method.implementation?.instructions?.any {
                        (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                            ((it as ReferenceInstruction).reference as? MethodReference)?.let { ref ->
                                ref.definingClass == component.type && ref.name == factory.name
                            } == true
                    } == true
                }
                val pairedCallers = everyCaller.filter { faceCaller(it, factoryRef, component.type) != null }
                assertEquals("$name: methods pairing the factory's Drawable with a component", 1, pairedCallers.size)
                assertTrue("$name: no other caller was rejected, so the pairing check proves nothing",
                    everyCaller.size > pairedCallers.size)
                val callerType = pairedCallers.single().definingClass
                val callerClass = FixtureDex.classes(bundle, setOf(callerType)).getValue(callerType)

                // The newer comment row's button: of every class building FDSInlineReactions (a negative
                // control: there are several), the one a method taking a GraphQLComment returns.
                val buildsComponent = { dex: DexBackedDexFile ->
                    dex.methodSection.any { it.definingClass == component.type && it.name == "<init>" }
                }
                val builderTypes = FixtureDex.methodsWhere(bundle, buildsComponent) {
                    it.definingClass != component.type && it.definingClass != comment.type &&
                        componentConstructions(it, component.type).isNotEmpty()
                }.map { it.definingClass }.toSet()
                val rowMakers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == GRAPHQL_COMMENT } }) {
                    takesComment(it) && it.returnType in builderTypes
                }
                val rowTypes = rowMakers.map { it.returnType }.toSet()
                assertEquals("$name: FDSInlineReactions builders a method taking a GraphQLComment returns ($rowTypes)", 1, rowTypes.size)
                assertTrue("$name: no other builder was rejected, so the GraphQLComment check proves nothing", builderTypes.size > 1)
                val rowType = rowTypes.single()
                val rowClasses = FixtureDex.classes(bundle, setOf(rowType) + rowMakers.map { it.definingClass }).values

                val context = PatchContexts.of(listOf(component, facepile, bar, comment, callerClass) + rowClasses)
                context.hideReactionPreview()

                // The bling bar asks the post question; the facepile asks with its own flag.
                val barPatched = context.mutableClassDefBy(bar.type).methods.single { isDrawMethod(it, "onDraw") }
                    .implementation!!.instructions.toList()
                val barBefore = bar.method("onDraw").implementation!!.instructions.toList()
                assertEquals("$name: bling bar: four instructions added", barBefore.size + 4, barPatched.size)
                assertEquals("$name: bling bar: the extension is asked first", HIDE_REACTION_PREVIEW,
                    ((barPatched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: bling bar: shape of the early return",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    barPatched.take(4).map { it.opcode })
                assertEquals("$name: bling bar: the rest is Facebook's", barBefore.map { it.opcode },
                    barPatched.drop(4).map { it.opcode })

                val faceBefore = facepile.method("draw").implementation!!.instructions.toList()
                val facePatched = context.mutableClassDefBy(facepile.type).methods.single { isDrawMethod(it, "draw") }
                    .implementation!!.instructions.toList()
                assertEquals("$name: facepile: five instructions added", faceBefore.size + 5, facePatched.size)
                assertEquals("$name: facepile: reads its own flag, asks the extension, returns early",
                    listOf(Opcode.IGET_BOOLEAN, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    facePatched.take(5).map { it.opcode })
                assertEquals("$name: facepile: the flag read", "${facepile.type}->$COMMENT_FLAG:Z",
                    ((facePatched[0] as ReferenceInstruction).reference as FieldReference).toString())
                assertEquals("$name: facepile: the extension is asked with the flag", HIDE_FACEPILE,
                    ((facePatched[1] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: facepile: the rest is Facebook's", faceBefore.map { it.opcode },
                    facePatched.drop(5).map { it.opcode })

                // The flags and their helpers.
                for ((type, helper) in listOf(
                    component.type to COMMENT_MARK, component.type to COMMENT_STASH, facepile.type to COMMENT_COPY,
                )) {
                    val patchedClass = context.mutableClassDefBy(type)
                    assertTrue("$name: $type has no $COMMENT_FLAG (instance: ${patchedClass.instanceFields.map { "${it.name}:${it.type}" }}, " +
                        "all: ${patchedClass.fields.map { "${it.name}:${it.type}" }})",
                        patchedClass.instanceFields.any { it.name == COMMENT_FLAG && it.type == "Z" })
                    assertEquals("$name: $type's $helper", 1,
                        patchedClass.methods.count { it.name == helper && AccessFlags.STATIC.isSet(it.accessFlags) })
                }

                // The component's flag is set right after each FDSInlineReactions the comment component
                // and the comment row's button build.
                for (marked in listOf(comment.type, rowType)) {
                    var marks = 0
                    var constructions = 0
                    for (method in context.mutableClassDefBy(marked).methods) {
                        val code = method.implementation?.instructions?.toList() ?: continue
                        code.forEachIndexed { index, instruction ->
                            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                            if ((instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                                ref.name == "<init>" && ref.definingClass == component.type) {
                                constructions++
                                val nextRef = (code[index + 1] as? ReferenceInstruction)?.reference as? MethodReference
                                if (nextRef?.name == COMMENT_MARK) marks++
                            }
                        }
                    }
                    assertTrue("$name: $marked builds no FDSInlineReactions", constructions > 0)
                    assertEquals("$name: $marked's constructions followed by the mark", constructions, marks)
                }

                // The lambda leaves the flag right before it reads the component, from the register the
                // read takes the component from, and takes it back onto the Drawable right after getting
                // it. 585 reuses both registers in between, so a copy from the component's register there
                // hands the Drawable where the component belongs and the class fails the verifier.
                val paired = faceCaller(pairedCallers.single(), factoryRef, component.type)!!
                val stock = pairedCallers.single().implementation!!.instructions.toList()
                val lambda = context.mutableClassDefBy(callerClass.type).methods.single {
                    it.name == pairedCallers.single().name && it.parameterTypes == pairedCallers.single().parameterTypes
                }.implementation!!.instructions.toList()
                assertEquals("$name: two calls added to the lambda", stock.size + 2, lambda.size)
                val stash = lambda[paired.read]
                assertEquals("$name: the stash sits where the component read was",
                    "${component.type}->$COMMENT_STASH(${component.type})V",
                    ((stash as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the stash is handed the read's object register",
                    (stock[paired.read] as TwoRegisterInstruction).registerB, (stash as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the component read follows the stash", stock[paired.read].opcode, lambda[paired.read + 1].opcode)
                val copyAt = lambda.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == COMMENT_COPY
                }
                assertTrue("$name: the lambda never copies the flag", copyAt > paired.read)
                assertEquals("$name: the copy follows the Drawable's move-result-object", Opcode.MOVE_RESULT_OBJECT,
                    lambda[copyAt - 1].opcode)
                assertEquals("$name: the copy is handed the Drawable's register",
                    (lambda[copyAt - 1] as OneRegisterInstruction).registerA,
                    (lambda[copyAt] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the copy takes the Drawable alone",
                    "${facepile.type}->$COMMENT_COPY(${facepile.type})V",
                    ((lambda[copyAt] as ReferenceInstruction).reference as MethodReference).toString())
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `each declared build's reaction count text goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, FEEDBACK_HEADER)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val renders = holders.flatMap { methodsHolding(it, FEEDBACK_HEADER) }
                assertEquals("$name: methods naming $FEEDBACK_HEADER", 1, renders.size)
                val render = renders.single()
                val code = render.implementation!!.instructions.toList()

                val call = reactionCountCall(render)
                assertNotNull("$name: ${render.definingClass} builds no one component from two Strings", call)
                val init = (code[call!!] as ReferenceInstruction).reference as MethodReference
                // Negative control: the summary builds other components, and their constructors aren't taken.
                assertTrue("$name: no other constructor was rejected, so the two-String check proves nothing",
                    code.count { (it as? ReferenceInstruction)?.reference.let { r -> r is MethodReference && r.name == "<init>" } } > 1)
                assertEquals("$name: the component is made right before its constructor", Opcode.NEW_INSTANCE, code[call - 1].opcode)
                assertEquals("$name: of the constructor's type", init.definingClass,
                    (code[call - 1] as ReferenceInstruction).reference.toString())
                // Every path into the constructor comes through the new-instance, so the hook in between runs on each.
                val flow = ControlFlow.of(render)
                val into = code.indices.filter { call in flow.normal[it] || call in flow.exceptional[it] }
                assertEquals("$name: only the new-instance leads into the constructor", listOf(call - 1), into)
                val text = code[call].callRegisters()[1]

                val owner = FixtureDex.classes(bundle, setOf(render.definingClass)).getValue(render.definingClass)
                val context = PatchContexts.of(listOf(owner))
                context.hideReactionCount()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == render.name && it.parameterTypes == render.parameterTypes
                }.implementation!!.instructions.toList()
                assertEquals("$name: two instructions added", code.size + 2, patched.size)
                val asks = patched[call]
                assertEquals("$name: the extension is asked", REACTION_COUNT,
                    ((asks as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: with the text and nothing else", listOf(text), asks.callRegisters())
                assertEquals("$name: its answer is kept", Opcode.MOVE_RESULT_OBJECT, patched[call + 1].opcode)
                assertEquals("$name: in the text's register", text, (patched[call + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the constructor follows with the same registers", code[call].callRegisters(),
                    patched[call + 2].callRegisters())
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode },
                    patched.filterIndexed { index, _ -> index != call && index != call + 1 }.map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the count hook`() {
        val type = REACTION_COUNT.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == REACTION_COUNT
        }
        assertNotNull("the extension has no $REACTION_COUNT", hook)
        assertTrue("$REACTION_COUNT isn't static", AccessFlags.STATIC.isSet(hook!!.accessFlags))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_REACTION_PREVIEW.substringBefore("->")
        val hooks = ExtensionDex.classDef(type).methods.filter { it.name == "hideReactionPreview" }
        for (wanted in listOf(HIDE_REACTION_PREVIEW, HIDE_FACEPILE)) {
            val hook = hooks.singleOrNull {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == wanted
            }
            assertNotNull("the extension has no $wanted (found ${hooks.map { it.parameterTypes }})", hook)
            assertTrue("$wanted isn't static", AccessFlags.STATIC.isSet(hook!!.accessFlags))
        }
    }
}
