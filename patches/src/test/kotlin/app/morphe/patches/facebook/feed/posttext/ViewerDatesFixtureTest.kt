/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dark box behind the date under a full-screen video's caption (#490) on every Facebook build the bundle
 * declares: the one method laying out a video's details (the caption builder's only caller), where it puts
 * the post's date into a list, and the call an open story's title wraps its name in a style with. Then the
 * patch on them: a helper on the details' class wraps the date in a style with the extension's box, and
 * each date goes through it right before its list takes it. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ViewerDatesFixtureTest {
    /** Where one build's details take a date, and the wrap and the value it's handed. */
    private data class Found(val dates: Set<String>, val call: String, val maker: String)

    /** Each declared build's places, by ABI, read from the dex with the probe. */
    private val expected = mapOf(
        // 588, read 2026-11-23: `LX/09Og;->A10` (the caption builder's only caller) makes the date as a
        // `LX/0HGL;` of the session and the time in v0/v1 and adds v8 to its column's list at 1477 and 1733.
        // It copies its context p1 into v3 as it starts. The open story's title wraps its name with
        // `LX/04eM;->A0R`, handed a value `LX/02H2;->A01` makes from the context.
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to Found(
                setOf("LX/09Og;->A10@1477/v8, scratch v0, context v3", "LX/09Og;->A10@1733/v8, scratch v0, context v3"),
                "LX/04eM;->A0R(LX/01I1;LX/01SY;LX/02H3;)LX/01OH;",
                "LX/02H2;->A01(LX/01Of;)LX/02H3;",
            ),
        ),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private val payloads = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

    /** [code] without the nop dexlib2 puts right before a switch or array payload to keep it aligned. */
    private fun unpadded(code: List<Instruction>) = code.filterIndexed { at, instruction ->
        instruction.opcode != Opcode.NOP || code.getOrNull(at + 1)?.opcode !in payloads
    }

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun captionBuilder(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) &&
        loadsString(method, CAPTION_COMPONENT) && loadsString(method, CAPTION_ROLE) && loadsString(method, CAPTION_DESCRIPTION)

    /** Every static method shaped like a background style anywhere in the app: a drawable and a style in, that style out. */
    private fun backgroundShaped(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == DRAWABLE &&
        method.parameterTypes[1].toString() == method.returnType && method.returnType.startsWith("LX/")

    /** [type] and its superclasses the bundle carries. */
    private fun hierarchy(bundle: File, type: String): Map<String, ClassDef> {
        val found = linkedMapOf<String, ClassDef>()
        var next: String? = type
        while (next != null && next !in found) {
            val classDef = FixtureDex.classes(bundle, setOf(next))[next] ?: break
            found[next] = classDef
            next = classDef.superclass
        }
        return found
    }

    /** The types [classes]' methods make with new-instance or construct. */
    private fun madeIn(classes: Collection<ClassDef>) = classes.flatMap { it.methods }.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { instruction ->
            when {
                instruction.opcode == Opcode.NEW_INSTANCE ->
                    ((instruction as ReferenceInstruction).reference as TypeReference).type
                instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE ->
                    ((instruction as ReferenceInstruction).reference as MethodReference).definingClass
                else -> null
            }
        }.orEmpty()
    }.filter { it.startsWith("LX/") }.toSet()

    /** The static calls [methods] make. */
    private fun staticCalls(methods: Collection<Method>) = methods.flatMap { method ->
        method.implementation?.instructions?.mapNotNull { instruction ->
            if (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) {
                (instruction as ReferenceInstruction).reference as MethodReference
            } else {
                null
            }
        }.orEmpty()
    }

    @Test
    fun `each declared build boxes the date under a full-screen video's caption through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                val extension = listOf(ExtensionDex.classDef(CAPTION_BACKDROP))

                // What the stories row and an open story's title are found from (as NameBoxesFixtureTest reads them).
                val anchors = listOf(TRAY_TITLE_KEY, STORY_TITLE_SPEC)
                    .flatMap { FixtureDex.classesHolding(bundle, it) }
                    .filter { !it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
                val made = FixtureDex.classes(bundle, madeIn(anchors.values) - anchors.keys)
                val tables = FixtureDex.classesHolding(bundle, TRAY_NAME_KEY)
                    .filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, TRAY_NAME_KEY) } }
                    .filterNot { it.type in anchors.keys }.associateBy { it.type }
                val tray = PatchContexts.of(anchors.values.toList() + tables.values + extension).findTrayNames()
                val builderType = tray.text.definingClass
                val chain = hierarchy(bundle, builderType)
                val shaped = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && (backgroundShaped(it) || captionBuilder(it))
                }
                val caption = shaped.filter(::captionBuilder).single()
                val backgroundStyles = shaped.filter(::backgroundShaped)
                val styles = FixtureDex.classes(bundle, backgroundStyles.map { it.returnType }.toSet())
                val emptyStyles = styles.values.flatMap { it.staticFields }.map { it.type }.filter { it.startsWith("LX/") }.toSet()
                val backgrounds = FixtureDex.classes(bundle, backgroundStyles.map { it.definingClass }.toSet() + emptyStyles)

                // The details of a full-screen video: whatever calls the caption builder, and the components it makes.
                val target = "${caption.definingClass}->${caption.name}(${caption.parameterTypes.joinToString("")})${caption.returnType}"
                val callers = FixtureDex.methodsWhere(bundle, { true }) { method ->
                    !method.definingClass.startsWith(EXTENSION_CLASSES) && method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                            "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" == target
                        } == true
                    } == true
                }
                val viewers = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet())
                val dateTypes = callers.flatMap { it.code() }.filter(::isConstructorCall)
                    .map { (it as ReferenceInstruction).reference as MethodReference }
                    .filter { it.parameterTypes.map { type -> type.toString() } == listOf(FB_USER_SESSION, "J") }
                    .map { it.definingClass }.toSet()
                val dateChain = dateTypes.flatMap { hierarchy(bundle, it).entries }.associate { it.key to it.value }

                // What the open story's title calls statically, and the hierarchies of what those give back.
                val base = made + backgrounds + styles + chain + tables + anchors
                val openStory = PatchContexts.of(base.values.toList() + extension).findOpenStoryNames(tray.text)
                val calls = staticCalls(openStory.map { it.method }.distinct())
                val callees = FixtureDex.classes(bundle, calls.map { it.definingClass }.filter { it.startsWith("LX/") }.toSet() - base.keys)
                val returned = calls.map { it.returnType }.filter { it.startsWith("LX/") }.toSet()
                    .flatMap { hierarchy(bundle, it).entries }.associate { it.key to it.value }
                val classes = callees + returned + dateChain + viewers + base

                val context = PatchContexts.of(classes.values.toList() + extension)
                val style = context.findBoxStyle(builderType)
                val styleType = style.background.substringAfterLast(')')
                val stories = context.findOpenStoryNames(tray.text)
                val wrap = context.findStyleWrap(stories, styleType)
                val dates = context.findViewerDates(caption, wrap)
                expected[version]?.get(abi)?.let { found ->
                    assertEquals("$name: where the details take a date and how it's wrapped", found,
                        Found(dates.map { it.toString() }.toSet(), wrap.call.toString(), wrap.maker.toString()))
                }
                assertEquals("$name: the wrap takes the style a background goes on", styleType, wrap.style)
                val viewer = dates.map { it.place.method }.distinct().single()
                assertEquals("$name: the details take the context the wrap's value is made from", 1,
                    viewer.parameterTypes.count { it.toString() == wrap.context })
                dates.forEach { date ->
                    assertTrue("$name: $date's scratch registers stay clear of its date", date.place.register !in listOf(date.scratch, date.scratch + 1))
                    assertTrue("$name: $date's list takes the date", viewer.code()[date.place.index].let {
                        ((it as ReferenceInstruction).reference as MethodReference).name == "add"
                    })
                }

                // Negative controls: without the date's own class the details make no date the patch
                // knows, and without the wrap's class an open story's title gives no wrap.
                assertThrows("$name: a date was found without its class", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it !in dateTypes }.values.toList() + extension).findViewerDates(caption, wrap)
                }
                val wrapOwner = wrap.call.definingClass
                assertThrows("$name: a wrap was found without its class", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it != wrapOwner }.values.toList() + extension).let {
                        it.findStyleWrap(it.findOpenStoryNames(tray.text), styleType)
                    }
                }

                val original = viewer.code()
                context.addViewerDateBoxes(dates, wrap, style)
                val host = viewer.definingClass

                // The helper asks the extension and, given a box, wraps the date in a style made from it.
                val helper = context.mutableClassDefBy(host).methods.singleOrNull { it.name == VIEWER_DATE_HELPER }
                assertNotNull("$name: no helper on $host", helper)
                assertEquals("$name: the helper takes the date and the context", listOf(wrap.component, wrap.context),
                    helper!!.parameterTypes.map { it.toString() })
                assertEquals("$name: the helper gives back a component", wrap.component, helper.returnType)
                val helperCode = helper.code()
                assertTrue("$name: the helper asks the extension", helperCode[0].names(FOR_VIEWER_DATE))
                assertEquals("$name: a null answer leaves the date alone", Opcode.IF_EQZ, helperCode[2].opcode)
                assertTrue("$name: the empty style under the background", helperCode[3].names(style.emptyStyle))
                assertTrue("$name: the background style is made", helperCode[4].names(style.background))
                assertTrue("$name: the wrap's value is made from the context", helperCode[6].names(wrap.maker.toString()))
                assertTrue("$name: the date is wrapped", helperCode[8].names(wrap.call.toString()))
                assertEquals(Opcode.RETURN_OBJECT, helperCode.last().opcode)

                // Each date goes through the helper right before its list takes it, and nothing else moves.
                val patched = context.mutableClassDefBy(host).methods.single {
                    it.name == viewer.name && it.parameterTypes == viewer.parameterTypes
                }.code()
                val before = unpadded(original)
                val after = unpadded(patched)
                assertEquals("$name: four instructions per date", before.size + 4 * dates.size, after.size)
                val hooks = dates.sortedBy { it.place.index }.flatMapIndexed { added, date ->
                    val at = date.place.index + 4 * added
                    assertEquals("$name: two moves, the call and its result before $date's list", listOf(
                        Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT,
                    ), (at until at + 4).map { patched[it].opcode })
                    assertEquals("$name: $date's date into the first scratch register", date.scratch to date.place.register,
                        (patched[at] as TwoRegisterInstruction).let { it.registerA to it.registerB })
                    assertEquals("$name: $date's context into the second", date.scratch + 1 to date.context,
                        (patched[at + 1] as TwoRegisterInstruction).let { it.registerA to it.registerB })
                    assertTrue("$name: $date goes through the helper",
                        patched[at + 2].names("$host->$VIEWER_DATE_HELPER(${wrap.component}${wrap.context})${wrap.component}"))
                    assertEquals("$name: both handed over", date.scratch to 2,
                        (patched[at + 2] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
                    assertEquals("$name: the wrapped date goes back in v${date.place.register}", Opcode.MOVE_RESULT_OBJECT to date.place.register,
                        patched[at + 3].opcode to (patched[at + 3] as OneRegisterInstruction).registerA)
                    assertEquals("$name: then its list takes it", (original[date.place.index] as ReferenceInstruction).reference.toString(),
                        (patched[at + 4] as ReferenceInstruction).reference.toString())
                    listOf(at, at + 1, at + 2, at + 3)
                }.toSet()
                assertEquals("$name: the rest is Facebook's", before.map { it.opcode },
                    unpadded(patched.filterIndexed { at, _ -> at !in hooks }).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
