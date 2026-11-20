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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide who reacted under posts hooks (#483) on every Facebook build the bundle
 * declares (arm64 and armv7): the whole app has one model and one builder for the server-written
 * reactor row, whose class has one config check of the expected shape among its static boolean
 * methods; one gate for the app-built row naming who reacted, which calls one sentence builder; and
 * the social sentence plugin has one check of the expected shape among its other boolean methods
 * (beside its one object builder on 588's Android 9 build).
 * The compact social context plugin, which also named people, is gone since 588, and a build bringing
 * it back fails here so its check gets hooked again. After the hook each check asks the extension
 * first and answers false (the builder null) when told to. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReactorNamesFixtureTest {
    private val droppedCompactPlugin =
        "Lcom/facebook/feedback/reactions/plugins/blingbar/compactsocialcontext/CompactSocialContextBlingBarReactionCountPlugin;"

    /**
     * Per build, the check classes that keep no boolean method besides the check. 588's Android 9
     * build leaves the social sentence plugin with its check (`A00(LX/07fF;ZZ)Z`) and one object
     * builder (`A01`), read 2026-11-20, so there the shape is told apart from that builder alone.
     */
    private val soleBooleanChecks = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to emptySet<String>(),
            "armeabi-v7a" to emptySet<String>(),
            "arm64-v8a-minapi28-storeless" to setOf(SOCIAL_SENTENCE),
        ),
    )

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Method.sameAs(other: Method) = name == other.name && parameterTypes == other.parameterTypes

    private fun Instruction.buildsSentence(): Boolean {
        val types = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.parameterTypes
            ?.map(CharSequence::toString) ?: return false
        return types.size == 6 && types[0] == "Lcom/facebook/auth/usersession/FbUserSession;" && types[2] == REACTOR_FEEDBACK &&
            types[3] == "Ljava/lang/String;" && types[4] == "Z" && types[5] == "Z"
    }

    @Test
    fun `each declared build's reactor checks ask the extension first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // The whole app, so a second method of the gate's shape would show up here.
                val gates = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == REACTOR_FEEDBACK } }) {
                    isReactorRowGate(it)
                }
                assertEquals("$name: the row's gates", 1, gates.size)
                val gateType = gates.single().definingClass
                // Negative control: the sentence builder has other callers (the row's own render among
                // them), and the gate's shape leaves them out.
                val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == REACTOR_FEEDBACK } }) {
                    it.body().any { instruction -> instruction.buildsSentence() }
                }
                assertTrue("$name: the sentence builder has no other caller, so the gate's shape proves nothing",
                    builders.any { !it.sameAs(gates.single()) || it.definingClass != gates.single().definingClass })

                val builderType = gates.single().body().first { it.buildsSentence() }
                    .let { ((it as ReferenceInstruction).reference as MethodReference).definingClass }

                // The server-written row: its model and its builder, each once in the whole app.
                val models = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == REACTOR_FEEDBACK } }) {
                    isServerReactorRowModelConstructor(it)
                }
                assertEquals("$name: the server row's models", 1, models.size)
                val modelType = models.single().definingClass
                val rowBuilders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it.toString() == modelType } }) {
                    isServerReactorRowBuilder(it, modelType)
                }
                assertEquals("$name: the server row's builders", 1, rowBuilders.size)
                val rowType = rowBuilders.single().definingClass

                assertTrue("$name: the compact line's plugin is back, so its check needs the hook again",
                    FixtureDex.classes(bundle, setOf(droppedCompactPlugin)).isEmpty())
                val wanted = setOf(gateType, builderType, SOCIAL_SENTENCE, modelType, rowType)
                val classes = FixtureDex.classes(bundle, wanted)
                assertEquals("$name: the gate's and builders' classes, the row model and the plugin", wanted, classes.keys)
                val context = PatchContexts.of(classes.values.toList())

                val serverCheck = with(context) { findServerReactorRowCheck() }
                assertEquals("$name: the server row's check sits with its builder", rowType, serverCheck.definingClass)
                assertTrue("$name: the server row's check is static", AccessFlags.STATIC.isSet(serverCheck.accessFlags))
                // Negative control: the row's class has another boolean method the shape leaves out (the
                // count check the builder uses, a static, and the gate; 588's Android 9 build has only the gate).
                assertTrue("$name: the server row's class has no other boolean method, so the shape proves nothing",
                    classes.getValue(rowType).methods.any {
                        it.returnType == "Z" && it.implementation != null && !it.sameAs(serverCheck)
                    })

                val checks = with(context) { findReactorChecks() }
                val builder = with(context) { findReactorSentenceBuilder(checks[0]) }
                assertEquals("$name: the builder is the gate's call", builderType, builder.definingClass)
                // Negative control: the builder's class has other object-answering methods (the bling bar's entry among them).
                val otherBuilders = classes.getValue(builderType).methods
                    .filter { it.returnType.startsWith("L") && it.implementation != null && !it.sameAs(builder) }
                assertTrue("$name: the builder's class has no other object method, so the pick proves nothing",
                    otherBuilders.isNotEmpty())
                assertEquals("$name: two checks", 2, checks.size)
                assertTrue("$name: the row's gate first", checks[0].sameAs(gates.single()) && checks[0].definingClass == gateType)
                assertEquals("$name: the sentence's check", SOCIAL_SENTENCE, checks[1].definingClass)
                assertTrue("$name: the row's gate isn't static", !AccessFlags.STATIC.isSet(checks[0].accessFlags))
                assertTrue("$name: the sentence check is static", AccessFlags.STATIC.isSet(checks[1].accessFlags))
                // Negative control: each class has another boolean method the shape leaves out, or
                // where a build keeps none ([soleBooleanChecks]), another method of its own.
                val sole = soleBooleanChecks[version]?.get(name.substringAfter("-$version-").substringBefore(".apkm"))
                    ?: throw AssertionError("$name has no pin")
                for (check in checks) {
                    val others = classes.getValue(check.definingClass).methods
                        .filter { it.implementation != null && !it.sameAs(check) && it.name != "<init>" && it.name != "<clinit>" }
                    assertEquals("$name: ${check.definingClass} keeps another boolean method",
                        check.definingClass !in sole, others.any { it.returnType == "Z" })
                    assertTrue("$name: ${check.definingClass} has no other method, so the shape proves nothing",
                        others.isNotEmpty())
                }

                with(context) { hideReactorNames() }
                val rowClass = context.mutableClassDefBy(rowType)
                for (other in rowClass.methods.filter { !it.sameAs(serverCheck) }) {
                    assertTrue("$name: ${other.name} in the server row's class was hooked too",
                        other.body().none { it.called() == HIDE_REACTOR_NAMES })
                }
                for (check in listOf(serverCheck) + checks) {
                    val hooked = context.mutableClassDefBy(check.definingClass).methods.single { it.sameAs(check) }.body()
                    val where = "$name ${check.definingClass.substringAfterLast('/')}"
                    assertEquals("$where: asks first", HIDE_REACTOR_NAMES, hooked[0].called())
                    assertEquals("$where: reads the answer", Opcode.MOVE_RESULT, hooked[1].opcode)
                    assertEquals("$where: tests the answer", Opcode.IF_EQZ, hooked[2].opcode)
                    assertEquals("$where: loads false", Opcode.CONST_4, hooked[3].opcode)
                    assertEquals("$where: and answers it when told to", Opcode.RETURN, hooked[4].opcode)
                    assertEquals("$where: the rest is Facebook's", check.body().map { it.opcode }, hooked.drop(5).map { it.opcode })
                }
                val builderClass = context.mutableClassDefBy(builderType)
                val hookedBuilder = builderClass.methods.single { it.sameAs(builder) }.body()
                assertEquals("$name builder: asks first", HIDE_REACTOR_NAMES, hookedBuilder[0].called())
                assertEquals("$name builder: reads the answer", Opcode.MOVE_RESULT, hookedBuilder[1].opcode)
                assertEquals("$name builder: tests the answer", Opcode.IF_EQZ, hookedBuilder[2].opcode)
                assertEquals("$name builder: loads null", Opcode.CONST_4, hookedBuilder[3].opcode)
                assertEquals("$name builder: and answers it when told to", Opcode.RETURN_OBJECT, hookedBuilder[4].opcode)
                assertEquals("$name builder: the rest is Facebook's",
                    builder.body().map { it.opcode }, hookedBuilder.drop(5).map { it.opcode })
                for (other in builderClass.methods.filter { !it.sameAs(builder) }) {
                    assertTrue("$name: ${other.name} in the builder's class was hooked too",
                        other.body().none { it.called() == HIDE_REACTOR_NAMES })
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_REACTOR_NAMES.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideReactorNames" }
        assertNotNull("the extension has no PostPrompts.hideReactorNames", hook)
        assertEquals("hideReactorNames' shape", HIDE_REACTOR_NAMES,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideReactorNames isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
