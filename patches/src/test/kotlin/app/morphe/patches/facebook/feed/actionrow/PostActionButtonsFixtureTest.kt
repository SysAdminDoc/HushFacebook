/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.video.GRAPHQL_MEDIA
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post action buttons on every declared build: the row's type list returns through the Save hook,
 * and each list of items the row seals first meets the Download helper, which builds an item the
 * way Save's case does.
 *
 * Read from 584 (2026-10-27): the row is `LX/36P;`, its type list `A06`, the item builder `A01`
 * (type `LX/36Q;`, state `LX/36S;`), the row `A05`, sealing two lists (the Share group and the
 * whole row). 585 arm64 (2026-10-29) is `LX/03E8;` with the same method names; its type list loads no
 * string, so the row is found from the item builder. 587 arm64 (2026-11-12) has no item builder: the
 * row `LX/03jE;->A04` switches over the types itself and seals one list, with the range form, from
 * v18. None of those names is used here.
 */
class PostActionButtonsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    /**
     * Per build, the register of each list the row seals. Up to 586 it sealed the Share group and the
     * whole row from low registers; 587 seals the row alone, from a register past v15.
     */
    private val sealed = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf("arm64-v8a" to listOf(18), "armeabi-v7a" to listOf(18)),
    )

    private fun holds(method: com.android.tools.smali.dexlib2.iface.Method, string: String) =
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
        } == true

    @Test
    fun `Save joins the type list and Download joins each sealed list, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // The holders of "Save" in a static method that also has a static List method, the row's shape.
                val owners = FixtureDex.classesHolding(bundle, SAVE_TAG).filter { owner ->
                    owner.methods.any { AccessFlags.STATIC.isSet(it.accessFlags) && holds(it, SAVE_TAG) } &&
                        owner.methods.any { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == LIST }
                }
                val classes = FixtureDex.classes(bundle, owners.flatMap(::typesNamed).toSet() +
                    setOf(GRAPHQL_STORY, GRAPHQL_STORY_ATTACHMENT, GRAPHQL_MEDIA))
                val context = PatchContexts.of((owners + classes.values).distinctBy { it.type })

                val row = with(context) { actionRow() }
                val returns = row.typeReturns().size
                val seals = row.seals()
                assertEquals("$name: the registers of the lists the row seals",
                    sealed.getValue(version)[name.substringAfter("-$version-").substringBefore(".apkm")]
                        ?: throw AssertionError("$name has no pin"),
                    seals.map { it.list }.sorted())
                // Counted without NOPs: a payload's alignment NOP comes and goes as code moves (587's row).
                val typesBefore = row.types.implementation!!.instructions.count { it.opcode != Opcode.NOP }
                val itemsBefore = row.items.implementation!!.instructions.count { it.opcode != Opcode.NOP }

                with(context) {
                    hookTypes(row)
                    hookDownload(row)
                }
                val owner = context.mutableClassDefBy(row.owner)

                val types = owner.methods.single { it.name == row.types.name && it.parameterTypes.map(CharSequence::toString) == row.types.parameterTypes.map(CharSequence::toString) }
                val typesAfter = types.implementation!!.instructions.toList()
                assertEquals("$name: two instructions per return", typesBefore + 2 * returns, typesAfter.count { it.opcode != Opcode.NOP })
                typesAfter.indices.filter { typesAfter[it].opcode == Opcode.RETURN_OBJECT }.forEach { index ->
                    val list = (typesAfter[index] as OneRegisterInstruction).registerA
                    val call = typesAfter[index - 2]
                    assertEquals("$name: the list goes through the Save hook",
                        "${row.owner}->$TYPES_HELPER($LIST)$LIST", (call as ReferenceInstruction).reference.toString())
                    assertEquals("$name: its own register", list, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: and comes back", list, (typesAfter[index - 1] as OneRegisterInstruction).registerA)
                }

                val items = owner.methods.single { it.name == row.items.name && it.parameterTypes.map(CharSequence::toString) == row.items.parameterTypes.map(CharSequence::toString) }
                val itemsAfter = items.implementation!!.instructions.toList()
                // One call per sealed list, plus a copy of a list held above v15 into a low register.
                val copies = seals.count { it.list > 15 }
                assertEquals("$name: one call per sealed list", itemsBefore + seals.size + copies, itemsAfter.count { it.opcode != Opcode.NOP })
                val hooks = itemsAfter.indices.filter {
                    (itemsAfter[it] as? ReferenceInstruction)?.reference?.toString() ==
                        "${row.owner}->$DOWNLOAD_HELPER($LIST${row.state})V"
                }
                assertEquals("$name: each sealed list meets the Download helper", seals.size, hooks.size)
                for (hook in hooks) {
                    val wrap = itemsAfter[hook + 1]
                    val sealedList = wrap.namedRegisters().first()
                    // A list past v15 is sealed with the range form and reaches the helper through a low copy.
                    assertEquals("$name: right before the list is sealed",
                        if (sealedList > 15) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC, wrap.opcode)
                    val handed = (itemsAfter[hook] as FiveRegisterInstruction).registerC
                    if (sealedList <= 15) {
                        assertEquals("$name: the list it seals", sealedList, handed)
                        continue
                    }
                    val copy = itemsAfter[hook - 1]
                    assertEquals("$name: the list it seals, copied low", Opcode.MOVE_OBJECT_FROM16, copy.opcode)
                    assertEquals("$name: the list it seals, copied low", listOf(handed, sealedList),
                        listOf((copy as TwoRegisterInstruction).registerA, copy.registerB))
                }

                val helper = owner.methods.single { it.name == DOWNLOAD_HELPER }
                val code = helper.implementation!!.instructions.toList()
                val references = code.mapNotNull { (it as? ReferenceInstruction)?.reference }
                assertTrue("$name: asks the extension", references.any {
                    it is MethodReference && it.definingClass == POST_DOWNLOAD_BUTTON && it.name == "click"
                })
                val built = code.single { (it as? ReferenceInstruction)?.reference?.toString() == row.itemInit.toString() }
                assertEquals("$name: the item is built in one range call", Opcode.INVOKE_DIRECT_RANGE, built.opcode)
                assertEquals("$name: with every argument", row.itemInit.parameterTypes.size + 1,
                    (built as RegisterRangeInstruction).registerCount)
                assertTrue("$name: and joins the list", references.any { it.toString() == "$LIST->add($OBJECT)Z" })
                assertTrue("$name: tagged as ours", code.any {
                    (it as? ReferenceInstruction)?.reference?.let { reference ->
                        reference is com.android.tools.smali.dexlib2.iface.reference.StringReference &&
                            reference.string == DOWNLOAD_TAG
                    } == true
                })
                assertTrue("$name: with the post from the row's state", references.any { it.toString() == row.statePost })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** Every class [classDef]'s code names, so the context holds what the row's code builds and reads. */
    private fun typesNamed(classDef: ClassDef): Set<String> {
        val types = mutableSetOf<String>()
        for (method in classDef.methods) {
            types += method.parameterTypes.map(CharSequence::toString)
            for (instruction in method.implementation?.instructions ?: emptyList()) {
                when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                    is MethodReference -> {
                        types += reference.definingClass
                        types += reference.parameterTypes.map(CharSequence::toString)
                    }
                    is FieldReference -> {
                        types += reference.definingClass
                        types += reference.type
                    }
                    is TypeReference -> types += reference.type
                }
            }
        }
        return types.filter { it.startsWith("L") }.toSet()
    }
}
