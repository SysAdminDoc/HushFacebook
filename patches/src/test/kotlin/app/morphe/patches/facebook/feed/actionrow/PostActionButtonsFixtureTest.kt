/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.video.GRAPHQL_MEDIA
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
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
 * and the list the row collects its items in meets the Download helper, which builds an item the
 * way Save's case does.
 *
 * Read from 584 (2026-10-27): the row is `LX/36P;`, its type list `A06`, the item builder `A01`
 * (type `LX/36Q;`, state `LX/36S;`), the row `A05`, sealing two lists (the Share group and the
 * whole row). 585 arm64 (2026-10-29) is `LX/03E8;` with the same method names; its type list loads no
 * string, so the row is found from the item builder. 587 arm64 (2026-11-12) has no item builder: the
 * row `LX/03jE;->A04` switches over the types itself and seals one list, with the range form, from
 * v18. 588 (2026-11-18, `LX/039B;->A04` on both ABIs) collects (type, item) pairs in v16 and reuses
 * the state's register before it seals anything, so the hook moved to the end of that walk. 588's
 * store-less build (`LX/03AA;->A01`, read 2026-11-19) collects them in v20 through an outlined helper
 * (`LX/01CB;->A1P`) that makes the pair and adds it. None of those names is used here.
 */
class PostActionButtonsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    /**
     * Per build, the register of the list the row collects its items in, past v15 on 588.
     */
    private val collected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to 16, "armeabi-v7a" to 16, "arm64-v8a-minapi28-storeless" to 20,
        ),
    )

    private fun holds(method: com.android.tools.smali.dexlib2.iface.Method, string: String) =
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
        } == true

    @Test
    fun `Save joins the type list and Download joins the row's items, on each declared build`() {
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
                val end = row.rowEnd { call -> context.classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
                assertEquals("$name: the register of the list the row collects its items in",
                    collected.getValue(version)[name.substringAfter("-$version-").substringBefore(".apkm")]
                        ?: throw AssertionError("$name has no pin"),
                    end.list)
                val stock = row.items.implementation!!.instructions.toList()
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
                // One call, plus a copy of a list held above v15 into a low register.
                val copies = if (end.list > 15) 1 else 0
                assertEquals("$name: one call on the row's list", itemsBefore + 1 + copies, itemsAfter.count { it.opcode != Opcode.NOP })
                val hook = itemsAfter.indices.single {
                    (itemsAfter[it] as? ReferenceInstruction)?.reference?.toString() ==
                        "${row.owner}->$DOWNLOAD_HELPER($LIST${row.state})V"
                }
                val call = itemsAfter[hook] as FiveRegisterInstruction
                assertEquals("$name: with the row's state", end.state, call.registerD)
                if (end.list > 15) {
                    val copy = itemsAfter[hook - 1]
                    assertEquals("$name: the row's list, copied low", Opcode.MOVE_OBJECT_FROM16, copy.opcode)
                    assertEquals("$name: the row's list, copied low", listOf(call.registerC, end.list),
                        listOf((copy as TwoRegisterInstruction).registerA, copy.registerB))
                } else {
                    assertEquals("$name: the row's list", end.list, call.registerC)
                }
                assertEquals("$name: the hook sits where the walk ends", end.index, hook - copies)
                assertEquals("$name: the walk's exit lands on it", hook - copies,
                    (itemsAfter[end.head + 2] as BuilderOffsetInstruction).target.location.index)
                assertEquals("$name: Facebook's code goes on after it", stock[end.index].opcode, itemsAfter[hook + 1].opcode)

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
                assertTrue("$name: as a pair like Facebook's", references.any { it.toString() == end.pair.toString() })
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

    /** #540: the wrapper around a post's row asks first, at its check of the row, and on a yes skips its tap. */
    @Test
    fun `each declared build's post row wrapper asks before it takes a tap`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val launchers = FixtureDex.classesHolding(bundle, FLYOUT_LAUNCHER)
                    .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }.filter(::isFlyoutLauncher)
                assertEquals("$name: components naming themselves $FLYOUT_LAUNCHER", 1, launchers.size)
                val launcher = launchers.single()
                val layouts = launcher.methods.filter { rowTapStretch(it) != null }
                assertEquals("$name: layouts checking the row before keying it", 1, layouts.size)
                val layout = layouts.single()
                val stretch = rowTapStretch(layout)!!
                val stock = layout.implementation!!.instructions.toList()
                // Positive control: the tap is made in the stretch the hook skips. The click event names the
                // component, and the layout loads that name nowhere else.
                val names = stock.withIndex().filter {
                    ((it.value as? ReferenceInstruction)?.reference as? StringReference)?.string == FLYOUT_LAUNCHER
                }.map { it.index }
                assertTrue("$name: the layout's tap isn't between its check and its key ($names)",
                    names.isNotEmpty() && names.all { it in stretch.check until stretch.key })

                val context = PatchContexts.of(listOf(launcher))
                context.ignoreRowTaps()
                val patched = context.mutableClassDefBy(launcher.type).methods
                    .single { it.name == layout.name && it.parameterTypes == layout.parameterTypes }
                    .implementation!!.instructions.toList()
                val at = stretch.check
                assertEquals("$name: the hook sits at the check", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
                    patched.subList(at, at + 3).map { it.opcode })
                assertEquals("$name: the hook asks the switch", IGNORE_ROW_TAPS,
                    ((patched[at] as ReferenceInstruction).reference as MethodReference).let {
                        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                    })
                assertEquals("$name: the answer lands in the check's register", stretch.register,
                    (patched[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: a yes goes to the key", stretch.key + 3,
                    (patched[at + 2] as BuilderOffsetInstruction).target.location.index)
                assertEquals("$name: the key is still Facebook's", FEEDBACK_WRAPPER_KEY,
                    ((patched[stretch.key + 3] as ReferenceInstruction).reference as StringReference).string)
                val skipping = patched.withIndex().filter { (index, instruction) ->
                    index != at + 2 && (instruction as? BuilderOffsetInstruction)?.target?.location?.index == at + 3
                }
                assertTrue("$name: a branch goes past the hook to the check: ${skipping.map { it.index }}", skipping.isEmpty())
                assertEquals("$name: the rest of the layout is Facebook's", stock.map { it.opcode },
                    (patched.take(at) + patched.drop(at + 3)).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val hook = ExtensionDex.classDef(POST_ROW_TAPS).methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == IGNORE_ROW_TAPS
        }
        assertTrue("the extension has no static $IGNORE_ROW_TAPS",
            hook != null && AccessFlags.STATIC.isSet(hook.accessFlags))
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
