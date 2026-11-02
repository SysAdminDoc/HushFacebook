/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow status call Only reels from accounts I follow reads, found in every declared Facebook
 * build the way Clean up Reels finds it: one enum naming both kept statuses, one instance method
 * answering it for a reel's model and a boolean, and the one instance its class keeps. Facebook's own
 * code has to call it and compare the answer with the can-follow constant, the stub the patch fills
 * has to assemble with it using its parameter registers only, and everything the stub names has to
 * be public, since the extension calls it from another package. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FollowedReelsFixtureTest {
    /** The enums naming both kept statuses, each with the field its can-follow constant is kept in. */
    private fun statusEnum(classDef: ClassDef): String? {
        if (classDef.superclass != "Ljava/lang/Enum;") return null
        val constants = classDef.methods.singleOrNull { it.name == "<clinit>" } ?: return null
        if (!holdsString(constants, FOLLOWED) || !holdsString(constants, CAN_FOLLOW)) return null
        var named: String? = null
        for (instruction in constants.implementation?.instructions ?: return null) {
            val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
            if (reference is StringReference) named = reference.string
            if (instruction.opcode == Opcode.SPUT_OBJECT && named == CAN_FOLLOW &&
                (reference as FieldReference).type == classDef.type
            ) return "${classDef.type}->${reference.name}:${reference.type}"
        }
        return null
    }

    @Test
    fun `every declared build has one follow status call, read by Facebook's own Follow button`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name

                val enums = mutableMapOf<String, String>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) statusEnum(classDef)?.let { enums[classDef.type] = it }
                }
                assertEquals("$name: enums naming $CAN_FOLLOW and $FOLLOWED", 1, enums.size)
                val (status, canFollow) = enums.entries.single()

                // Every method of the patch's shape, and every call compared with the can-follow constant.
                val calls = mutableSetOf<String>()
                val compared = mutableSetOf<String>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type.startsWith(EXTENSION_PACKAGE)) continue
                        for (method in classDef.methods) {
                            val parameters = method.parameterTypes.map(CharSequence::toString)
                            if (!AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == status &&
                                parameters.size == 2 && parameters[0].startsWith("L") &&
                                !parameters[0].startsWith("Ljava/") && parameters[1] == "Z"
                            ) calls += "${method.definingClass}->${method.name}(${parameters.joinToString("")})$status"

                            val instructions = method.implementation?.instructions ?: continue
                            val invoked = mutableListOf<String>()
                            var readsCanFollow = false
                            for (instruction in instructions) {
                                val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
                                if (reference is MethodReference && reference.returnType == status) invoked += reference.toString()
                                if (instruction.opcode == Opcode.SGET_OBJECT && reference.toString() == canFollow) readsCanFollow = true
                            }
                            if (readsCanFollow) compared += invoked
                        }
                    }
                }
                assertEquals("$name: methods answering $status for a reel and a boolean", 1, calls.size)
                val call = calls.single()
                assertTrue("$name: no Facebook method compares $call with $canFollow", call in compared)

                // The patch finds the same call and instance, and the stub names only public members.
                val owner = call.substringBefore("->")
                val model = call.substringAfter("(").substringBefore(";") + ";"
                val classes = FixtureDex.classes(bundle, setOf(status, owner, model))
                assertEquals("$name: the call's classes", 3, classes.size)
                val context = PatchContexts.of(classes.values + ExtensionDex.classDef(FOLLOW_STATUS_STUB_CLASS))
                val found = with(context) { followStatus() }
                assertEquals("$name: the call the patch finds", call, found.call)
                assertEquals("$name: the model the patch passes", model, found.model)

                val ownerClass = classes.getValue(owner)
                val instance = ownerClass.staticFields.single { it.type == owner }
                assertEquals("$name: the instance the patch calls it on", "$owner->${instance.name}:$owner", found.instance)
                val method = ownerClass.methods.single { it.name == found.method.name && it.returnType == status && it.parameterTypes.size == 2 }
                for ((what, flags) in listOf(
                    "the call's class" to ownerClass.accessFlags,
                    "the call" to method.accessFlags,
                    "the instance field" to instance.accessFlags,
                    "the reel model" to classes.getValue(model).accessFlags,
                    "the status enum" to classes.getValue(status).accessFlags,
                )) assertTrue("$name: $what isn't public", AccessFlags.PUBLIC.isSet(flags))

                with(context) { fillFollowStatusStub(found) }
                val stub = with(context) { mutableClassDefBy(FOLLOW_STATUS_STUB_CLASS) }.methods
                    .single { it.name == FOLLOW_STATUS_STUB && AccessFlags.STATIC.isSet(it.accessFlags) }
                val first = stub.implementation!!.registerCount - 3
                val body = stub.implementation!!.instructions.toList()
                assertEquals(
                    "$name: the stub's reads",
                    listOf(Opcode.CHECK_CAST, Opcode.SGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                    body.take(5).map { it.opcode },
                )
                assertEquals(model, ((body[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals(found.instance, ((body[1] as ReferenceInstruction).reference as FieldReference).let {
                    "${it.definingClass}->${it.name}:${it.type}"
                })
                val invoke = body[2] as FiveRegisterInstruction
                assertEquals(call, ((body[2] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals(
                    "$name: the stub's registers",
                    listOf(first, first + 1, first + 1, first, first + 2, first, first),
                    listOf(
                        (body[0] as OneRegisterInstruction).registerA, (body[1] as OneRegisterInstruction).registerA,
                        invoke.registerC, invoke.registerD, invoke.registerE,
                        (body[3] as OneRegisterInstruction).registerA, (body[4] as OneRegisterInstruction).registerA,
                    ),
                )
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
