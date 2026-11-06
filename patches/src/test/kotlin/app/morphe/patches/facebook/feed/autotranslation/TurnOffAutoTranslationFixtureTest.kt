/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.autotranslation

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn off auto-translation's anchors on every Facebook build the bundle declares: one
 * GraphQLTranslatabilityType enum, one model getter of it reading translation_type, and in the reels
 * footer that records its caption translation request, two reads of the caption's flag, one of them
 * in that method, and in the comment body two reads of the comment's translation type. Then the patch
 * on those classes: the getter's answer goes through the extension and is cast back to the enum before
 * it returns, each flag read's answer goes through the extension before anything reads it, and each
 * comment type read's answer goes through it and is cast back, each as a range call over the one
 * register. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TurnOffAutoTranslationFixtureTest {
    private class Anchors(
        val enumClass: ClassDef,
        val models: List<ClassDef>,
        val footer: ClassDef,
        /** The classes of the static methods the footer's methods hand their work to (585 Android 9: its render's). */
        val outlined: Collection<ClassDef>,
        val commentEnum: ClassDef,
        val commentBodies: List<ClassDef>,
    )

    private fun anchors(bundle: java.io.File): Anchors {
        val name = bundle.name
        val enums = FixtureDex.classesHolding(bundle, AUTO_TRANSLATION)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.filter(::isTranslatabilityEnum)
        assertEquals("$name: GraphQLTranslatabilityType enums", 1, enums.size)
        val enumType = enums.single().type
        val models = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.superclass == BASE_MODEL && classDef.methods.any { isTranslationTypeGetter(it, enumType) }) {
                    models += ImmutableClassDef.of(classDef)
                }
            }
        }
        val footers = FixtureDex.classesHolding(bundle, CAPTION_TRIGGER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        assertEquals("$name: classes loading \"$CAPTION_TRIGGER\"", 1, footers.size)
        val commentEnums = FixtureDex.classesHolding(bundle, MACHINE_TRANSLATION)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.filter(::isCommentTranslationEnum)
        assertEquals("$name: comment translation type enums", 1, commentEnums.size)
        val bodies = FixtureDex.classesHolding(bundle, COMMENT_TOGGLE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        assertTrue("$name: no class loads \"$COMMENT_TOGGLE\"", bodies.isNotEmpty())
        val outlined = FixtureDex.classes(bundle, footers.single().methods.flatMap(::handedTo).toSet()).values
        return Anchors(enums.single(), models, footers.single(), outlined, commentEnums.single(), bodies)
    }

    /** The classes of the static methods [caller] calls that return what it returns and take its first parameter first. */
    private fun handedTo(caller: Method): List<String> {
        val first = caller.parameterTypes.firstOrNull()?.toString() ?: return emptyList()
        if (caller.returnType == "V") return emptyList()
        return caller.implementation?.instructions?.toList().orEmpty()
            .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .filter { it.returnType == caller.returnType && it.parameterTypes.firstOrNull()?.toString() == first }
            .map { it.definingClass }
            .filter { it != caller.definingClass }
    }

    private fun rangeCall(instruction: Instruction, target: String, register: Int, where: String) {
        assertEquals("$where: the hook call", Opcode.INVOKE_STATIC_RANGE, instruction.opcode)
        assertEquals("$where: the hook", target, ((instruction as ReferenceInstruction).reference).toString())
        assertEquals("$where: registers handed over", 1, (instruction as RegisterRangeInstruction).registerCount)
        assertEquals("$where: the register handed over", register, instruction.startRegister)
    }

    private fun patched(context: BytecodePatchContext, method: Method): Method =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }

    @Test
    fun `each declared build has the translation type getter, the reel footer's caption reads and the comment body's type reads`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val anchors = anchors(bundle)
                assertEquals("$name: models with a translation_type getter", 1, anchors.models.size)
                val context = PatchContexts.of(
                    anchors.models + anchors.enumClass + anchors.footer + anchors.outlined + anchors.commentEnum + anchors.commentBodies +
                        ExtensionDex.classDef(SETTINGS_STATUS),
                )
                val getter = context.findTranslationTypeGetter()
                val reads = context.findCaptionReads()
                assertEquals("$name: caption reads", 2, reads.size)
                // A read outside the footer sits in a static body one of its methods hands its work to.
                for (read in reads.filter { it.method.definingClass != anchors.footer.type }) {
                    assertTrue("$name: ${read.method} is static", AccessFlags.STATIC.isSet(read.method.accessFlags))
                    assertTrue("$name: ${read.method} is handed the footer's work",
                        anchors.footer.methods.any { read.method.definingClass in handedTo(it) })
                }
                val comments = context.findCommentTypeReads()
                assertEquals("$name: comment translation type reads", 2, comments.size)
                assertEquals("$name: the enum the comment body reads", anchors.commentEnum.type, comments.first().enumType)
                val getterBefore = getter.method.implementation!!.instructions.toList()
                val readsBefore = reads.associate { it.method to it.method.implementation!!.instructions.toList() }
                val commentsBefore = comments.associate { it.method to it.method.implementation!!.instructions.toList() }

                turnOffAutoTranslationPatch.execute(context)

                val getterCode = patched(context, getter.method).implementation!!.instructions.toList()
                val at = getter.returnIndex
                val where = "$name ${getter.method.definingClass}->${getter.method.name}"
                assertEquals("$where: three instructions in", getterBefore.size + 3, getterCode.size)
                rangeCall(getterCode[at], TRANSLATION_TYPE_HOOK, getter.register, where)
                assertEquals("$where: the answer", Opcode.MOVE_RESULT_OBJECT, getterCode[at + 1].opcode)
                assertEquals("$where: the answer's register", getter.register, (getterCode[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$where: cast back", Opcode.CHECK_CAST, getterCode[at + 2].opcode)
                assertEquals("$where: cast to the enum", getter.enumType, (getterCode[at + 2] as ReferenceInstruction).reference.toString())
                assertEquals("$where: still returns it", Opcode.RETURN_OBJECT, getterCode[at + 3].opcode)
                assertEquals("$where: returns the answer", getter.register, (getterCode[at + 3] as OneRegisterInstruction).registerA)

                // Two reads in two methods: each method gets one call, right after its read's answer.
                assertEquals("$name: caption reads in different methods", 2, reads.map { it.method }.distinct().size)
                for (read in reads) {
                    val code = patched(context, read.method).implementation!!.instructions.toList()
                    val readWhere = "$name ${read.method.definingClass}->${read.method.name}"
                    assertEquals("$readWhere: two instructions in", readsBefore.getValue(read.method).size + 2, code.size)
                    assertEquals("$readWhere: the flag's answer", Opcode.MOVE_RESULT, code[read.resultIndex].opcode)
                    rangeCall(code[read.resultIndex + 1], CAPTION_HOOK, read.register, readWhere)
                    assertEquals("$readWhere: the hook's answer", Opcode.MOVE_RESULT, code[read.resultIndex + 2].opcode)
                    assertEquals("$readWhere: lands where the flag did", read.register,
                        (code[read.resultIndex + 2] as OneRegisterInstruction).registerA)
                }

                // The comment body's two reads: each answer goes through the extension and is cast back.
                for (read in comments) {
                    val code = patched(context, read.method).implementation!!.instructions.toList()
                    val readWhere = "$name ${read.method.definingClass}->${read.method.name}"
                    assertEquals("$readWhere: three instructions in", commentsBefore.getValue(read.method).size + 3, code.size)
                    assertEquals("$readWhere: the type's answer", Opcode.MOVE_RESULT_OBJECT, code[read.resultIndex].opcode)
                    rangeCall(code[read.resultIndex + 1], COMMENT_HOOK, read.register, readWhere)
                    assertEquals("$readWhere: the hook's answer", Opcode.MOVE_RESULT_OBJECT, code[read.resultIndex + 2].opcode)
                    assertEquals("$readWhere: lands where the type did", read.register,
                        (code[read.resultIndex + 2] as OneRegisterInstruction).registerA)
                    assertEquals("$readWhere: cast back", Opcode.CHECK_CAST, code[read.resultIndex + 3].opcode)
                    assertEquals("$readWhere: cast to the enum", read.enumType,
                        (code[read.resultIndex + 3] as ReferenceInstruction).reference.toString())
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "autoTranslation" }
                assertEquals("$name: SettingsStatus.autoTranslation() isn't switched on", 1,
                    (status.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
