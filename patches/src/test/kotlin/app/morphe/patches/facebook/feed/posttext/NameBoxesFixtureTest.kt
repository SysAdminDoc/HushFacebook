/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dark box behind story names and reel names (#490) on every Facebook build the bundle declares: the
 * stories row's name builders, an open story's title, and the post header a reel's author line makes.
 * Then the patch on them: right after a name goes into its text builder, a helper on that class hands
 * the extension's drawable to the builder as a background style, and the reel's header props are
 * marked as they're made. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class NameBoxesFixtureTest {
    /** Where one build draws its names: the classes, how many names each hands over, and the reel header's props. */
    private data class Hosts(
        val tray: String,
        val trayNames: Int,
        val openStory: Set<String>,
        val openStoryTitles: Int,
        val header: String,
        val props: String,
        val marks: Set<String>,
        val entries: Set<String>,
    )

    /** Each declared build's places, by ABI, read from the dex with the probe. */
    private val expected = mapOf(
        // 587, read 2026-11-16: `LX/03CI;->render` names two builders "profile_name", `LX/0C8e;` hands its
        // title to a container in two methods (`render` for a name alone, `A07` for a name over its line),
        // the title an open story shows (`LX/0C8Y;`, StoryViewerTitleComponent) wraps its name in one
        // style in `render`, and `LX/08j3;->A19` makes the header's `LX/03J1;` props as a `LX/09Y8;` or,
        // for a long name, a `LX/039q;`.
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to Hosts(
                "LX/03CI;", 2, setOf("LX/0C8e;", "LX/0C8Y;"), 3, "LX/09Y7;", "LX/03J1;", setOf("LX/09Y8;", "LX/039q;"),
                setOf("A08/p1", "A0A/p2"),
            ),
        ),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

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
    /** The register static call [instruction] hands its parameter [index] in, a wide parameter taking two. */
    private fun argumentRegister(instruction: Instruction, index: Int): Int {
        val types = ((instruction as ReferenceInstruction).reference as MethodReference).parameterTypes.map { it.toString() }
        val slot = types.take(index).let { before -> before.size + before.count { it == "J" || it == "D" } }
        return when (instruction) {
            is RegisterRangeInstruction -> instruction.startRegister + slot
            is FiveRegisterInstruction -> with(instruction) { listOf(registerC, registerD, registerE, registerF, registerG)[slot] }
            else -> error("$instruction isn't a call")
        }
    }

    private fun madeIn(classes: Collection<ClassDef>) =classes.flatMap { it.methods }.flatMap { method ->
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

    private fun BytecodePatchContext.method(of: Method) = mutableClassDefBy(of.definingClass).methods.single {
        it.name == of.name && it.parameterTypes == of.parameterTypes
    }

    /** Checks the helper [name] on [host]: it asks [ask], and a drawable goes on the builder as a background style. */
    private fun BytecodePatchContext.checkHelper(where: String, host: String, name: String, ask: String, builderType: String,
                                                style: BoxStyle) {
        val helper = mutableClassDefBy(host).methods.singleOrNull { it.name == name }
        assertNotNull("$where: no $name on $host", helper)
        assertEquals("$where: the helper takes the text builder", listOf(builderType), helper!!.parameterTypes.map { it.toString() })
        val code = helper.code()
        assertTrue("$where: the helper asks the extension", code[0].names(ask))
        assertEquals("$where: a null answer leaves the name alone", Opcode.IF_EQZ, code[2].opcode)
        assertTrue("$where: the empty style under the background", code[3].names(style.emptyStyle))
        assertTrue("$where: the background style is made", code[4].names(style.background))
        assertTrue("$where: the style goes on the builder", code[6].names(style.setter))
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)
    }

    /**
     * Checks the helper [name] on [host]: it takes a container's style and hands it back, with a background
     * made from [ask]'s drawable on top when there is one (the empty style standing in for a missing one).
     */
    private fun BytecodePatchContext.checkStyleHelper(where: String, host: String, name: String, ask: String, style: BoxStyle) {
        val styleType = style.background.substringAfterLast(')')
        val helper = mutableClassDefBy(host).methods.singleOrNull { it.name == name }
        assertNotNull("$where: no $name on $host", helper)
        assertEquals("$where: the helper takes the container's style", listOf(styleType), helper!!.parameterTypes.map { it.toString() })
        assertEquals("$where: the helper hands a style back", styleType, helper.returnType)
        val code = helper.code()
        assertTrue("$where: the helper asks the extension", code[0].names(ask))
        assertEquals("$where: a null answer leaves the style alone", Opcode.IF_EQZ, code[2].opcode)
        assertTrue("$where: the empty style stands in for a missing one", code[4].names(style.emptyStyle))
        assertTrue("$where: the background goes on the style", code[5].names(style.background))
        assertEquals(Opcode.RETURN_OBJECT, code.last().opcode)
    }

    /**
     * Checks [patched] is [original] with a call to [call] and its move-result right before each of
     * [places], the call handed that place's register and its answer put back there.
     */
    private fun checkBefore(where: String, original: List<Instruction>, patched: List<Instruction>, places: List<Place>, call: String) {
        assertEquals("$where: two instructions per hook", original.size + 2 * places.size, patched.size)
        val hooks = places.sortedBy { it.index }.flatMapIndexed { added, place ->
            val at = place.index + 2 * added
            val hook = patched[at]
            assertTrue("$where: the hook before $place calls $call", hook.names(call))
            assertEquals("$where: $place's register handed over", place.register to 1,
                (hook as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
            assertEquals("$where: the boxed style goes back in v${place.register}", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
            assertEquals("$where: the boxed style goes back in v${place.register}", place.register,
                (patched[at + 1] as OneRegisterInstruction).registerA)
            listOf(at, at + 1)
        }.toSet()
        assertEquals("$where: the rest is Facebook's", original.map { it.opcode },
            patched.filterIndexed { at, _ -> at !in hooks }.map { it.opcode })
    }

    /** Checks [patched] is [original] with one call to [call] right after each of [places], handed that place's register. */
    private fun checkAfter(where: String, original: List<Instruction>, patched: List<Instruction>, places: List<Place>, call: String) {
        assertEquals("$where: one instruction per hook", original.size + places.size, patched.size)
        val hooks = places.sortedBy { it.index }.mapIndexed { added, place ->
            val at = place.index + 1 + added
            val hook = patched[at]
            assertTrue("$where: the hook after $place calls $call", hook.names(call))
            assertEquals("$where: $place's register handed over", place.register to 1,
                (hook as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
            at
        }.toSet()
        assertEquals("$where: the rest is Facebook's", original.map { it.opcode },
            patched.filterIndexed { at, _ -> at !in hooks }.map { it.opcode })
    }

    private fun inBuild(context: BytecodePatchContext, name: String) =
        (context.mutableClassDefBy(CAPTION_BACKDROP).methods.single { it.name == name }.code()[0] as NarrowLiteralInstruction).narrowLiteral

    @Test
    fun `each declared build boxes the stories row's names, an open story's and a reel's through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                val extension = listOf(ExtensionDex.classDef(CAPTION_BACKDROP))
                val anchors = listOf(TRAY_TITLE_KEY, STORY_TITLE_SPEC, POST_HEADER_COMPONENT, REEL_AUTHOR_COMPONENT)
                    .flatMap { FixtureDex.classesHolding(bundle, it) }
                    .filter { !it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
                val made = FixtureDex.classes(bundle, madeIn(anchors.values) - anchors.keys)

                val tray = PatchContexts.of(anchors.values.toList() + extension).findTrayNames()
                val builderType = tray.text.definingClass
                val chain = hierarchy(bundle, builderType)
                val shaped = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && backgroundShaped(it)
                }
                val styles = FixtureDex.classes(bundle, shaped.map { it.returnType }.toSet())
                val emptyStyles = styles.values.flatMap { it.staticFields }.map { it.type }.filter { it.startsWith("LX/") }.toSet()
                val backgrounds = FixtureDex.classes(bundle, shaped.map { it.definingClass }.toSet() + emptyStyles)
                val classes = made + backgrounds + styles + chain + anchors

                val context = PatchContexts.of(classes.values.toList() + extension)
                val trayNames = context.findTrayNames()
                val openStory = context.findOpenStoryNames(trayNames.text)
                val reel = context.findReelNames(trayNames.text)
                val style = context.findBoxStyle(builderType)
                val trayHost = trayNames.places.map { it.method.definingClass }.toSet().single()
                val openStoryHosts = openStory.map { it.method.definingClass }.toSet()
                val header = reel.names.map { it.method.definingClass }.toSet().single()
                val marks = reel.marks.map { place ->
                    ((place.method.code()[place.index] as ReferenceInstruction).reference as MethodReference).definingClass
                }.toSet()
                val entries = reel.entries.map { (method, props) -> "${method.name}/p$props" }.toSet()
                expected[version]?.get(abi)?.let { hosts ->
                    assertEquals("$name: where the names are drawn", hosts, Hosts(
                        trayHost, trayNames.places.size, openStoryHosts, openStory.size, header, reel.props, marks, entries,
                    ))
                }
                assertTrue("$name: a story in the row has a name", trayNames.places.isNotEmpty())
                assertTrue("$name: an open story has a name", openStory.isNotEmpty())
                // An open story's title is boxed on the style its name is laid out with: the container its
                // lines go into, or the style the shown title wraps its name in. 587 on the S22 draws the
                // name through the wrapped title; the other one only fills a hidden neighbour card (#490).
                val styleType = style.background.substringAfterLast(')')
                openStory.forEach { place ->
                    val call = (place.method.code()[place.index] as ReferenceInstruction).reference as MethodReference
                    val types = call.parameterTypes.map { it.toString() }
                    if (types.firstOrNull() == styleType) {
                        assertTrue("$name: $place hands a container its lines", "Ljava/util/List;" in types)
                    } else {
                        assertEquals("$name: $place wraps the title's own component", place.method.returnType, types[0])
                        assertEquals("$name: $place wraps it in one style", 1, types.count { it == styleType })
                        assertEquals("$name: $place hands over that style", place.register,
                            argumentRegister(place.method.code()[place.index], types.indexOf(styleType)))
                    }
                }
                val wrapped = openStory.filter {
                    ((it.method.code()[it.index] as ReferenceInstruction).reference as MethodReference).parameterTypes[0].toString() != styleType
                }
                assertEquals("$name: one shown title wraps its name in a style", 1, wrapped.size)
                // Negative control: without the shown title's class there's nothing to box, so the patch says so.
                val shown = wrapped.single().method.definingClass
                assertThrows("$name: an open story's title was found without the title it shows", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it != shown }.values.toList() + extension).findOpenStoryNames(trayNames.text)
                }
                assertEquals("$name: each name method of the header names once", reel.entries.size, reel.names.size)
                assertTrue("$name: the reel author's props are marked", reel.marks.isNotEmpty())
                marks.forEach { assertEquals("$name: $it is the header's props", reel.props, classes[it]?.superclass) }

                // Negative controls: without Litho's background style no name is boxed, and without the
                // reel author's own props the header can't tell a reel from a feed post.
                val background = style.background.substringBefore("->")
                assertThrows("$name: a background style was found without its class", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it != background }.values.toList() + extension)
                        .addStoryNameBoxes(trayNames.text, trayNames.places, openStory)
                }
                assertThrows("$name: the reel's props were found without the author's subclasses", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it !in marks }.values.toList() + extension).findReelNames(trayNames.text)
                }

                val originals = (trayNames.places + openStory + reel.marks + reel.names).map { it.method }
                    .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
                    .associateWith { it.code() }
                context.addStoryNameBoxes(trayNames.text, trayNames.places, openStory)
                context.addReelNameBoxes(trayNames.text, reel)

                context.checkHelper(name, trayHost, STORY_NAME_HELPER, FOR_STORY_NAME, builderType, style)
                openStoryHosts.forEach { host ->
                    context.checkStyleHelper(name, host, STORY_TITLE_HELPER, FOR_STORY_NAME, style)
                    assertTrue("$name: an open story's names keep their builders as Facebook made them",
                        context.mutableClassDefBy(host).methods.none { it.name == STORY_NAME_HELPER })
                }
                context.checkHelper(name, header, REEL_NAME_HELPER, FOR_REEL_NAME, builderType, style)

                for ((method, original) in originals) {
                    val where = "$name: ${method.definingClass}->${method.name}"
                    var patched = context.method(method).code()
                    val entry = reel.entries.singleOrNull { it.first == method }
                    if (entry != null) {
                        // The header says whose props it builds from first thing.
                        val hook = patched[0]
                        assertTrue("$where starts by naming its props", hook.names(HEADER))
                        val mutable = context.method(method)
                        val ins = (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
                            method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                        assertEquals("$where hands over its props", mutable.implementation!!.registerCount - ins + entry.second,
                            (hook as RegisterRangeInstruction).startRegister)
                        patched = patched.drop(1)
                    }
                    val story = trayNames.places.filter { it.method == method }
                    val titles = openStory.filter { it.method == method }
                    val names = reel.names.filter { it.method == method }
                    val reelMarks = reel.marks.filter { it.method == method }
                    when {
                        story.isNotEmpty() -> checkAfter(where, original, patched, story,
                            "${method.definingClass}->$STORY_NAME_HELPER($builderType)V")
                        titles.isNotEmpty() -> checkBefore(where, original, patched, titles,
                            "${method.definingClass}->$STORY_TITLE_HELPER($styleType)$styleType")
                        names.isNotEmpty() -> checkAfter(where, original, patched, names, "$header->$REEL_NAME_HELPER($builderType)V")
                        else -> checkAfter(where, original, patched, reelMarks, REEL_AUTHOR)
                    }
                }
                assertEquals("$name: CaptionBackdrop.storyNamesInBuild() isn't switched on", 1, inBuild(context, "storyNamesInBuild"))
                assertEquals("$name: CaptionBackdrop.reelNamesInBuild() isn't switched on", 1, inBuild(context, "reelNamesInBuild"))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
