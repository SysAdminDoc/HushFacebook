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
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.tableIndexAt
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
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
        val lines: Set<String>,
        val look: String,
        val feed: String,
        val feedNames: Set<String>,
        val feedLines: Set<String>,
        val feedLook: String,
    )

    /** Each declared build's places, by ABI, read from the dex with the probe. */
    private val expected = mapOf(
        // 588, read 2026-11-18: `LX/03rh;->render` names two builders "profile_name", `LX/0BF0;` hands its
        // title to a container in two methods (`render` and `A0A`), the title an open story shows
        // (`LX/0BEu;`, StoryViewerTitleComponent) wraps its name in one style in `render`, and
        // `LX/08yM;->A1B` makes the header's `LX/03GS;` props as a `LX/09BT;` or a `LX/02pb;`. Read
        // 2026-11-20 (#533): `A0B` and `A0D` read the props' name line count into v11 and v8, and the
        // header keeps its look (DEFAULT, ON_MEDIA and the rest) in `A02`. Feed posts build their names in
        // `LX/02uV;->A04` instead (props in p2, the header in p1, its look in `A03`): the name goes into the
        // builder at 58 and the line count into v10 at 74, and with tracing on the same happens in case 1
        // of `LX/03Gn;->invoke` (the setter `A1s` at 70, the line count at 85).
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to Hosts(
                "LX/03rh;", 2, setOf("LX/0BEu;", "LX/0BF0;"), 3, "LX/09BS;", "LX/03GS;", setOf("LX/09BT;", "LX/02pb;"),
                setOf("A0B/p1", "A0D/p2"), setOf("A0B/v11", "A0D/v8"), "LX/09BS;->A02:LX/02ta;",
                "LX/02uV;->A04/p2/p1", setOf("LX/02uV;->A04@58/v0", "LX/03Gn;->invoke@70/v3"),
                setOf("LX/02uV;->A04@74/v10", "LX/03Gn;->invoke@85/v0"), "LX/02uV;->A03:LX/02ta;",
            ),
        ),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private val payloads = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

    /**
     * [code] without the nop dexlib2 puts right before a switch or array payload to keep it aligned: a
     * hook that shifts the payload by an odd number of code units adds one (588 `LX/03Gn;->invoke`).
     */
    private fun unpadded(code: List<Instruction>) = code.filterIndexed { at, instruction ->
        instruction.opcode != Opcode.NOP || code.getOrNull(at + 1)?.opcode !in payloads
    }

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

    /**
     * Checks [patched] is [original] with one call to [nameCall] right after each of [names], handed
     * that place's register, and right after each of [lines] the line count through [NAME_LINES] and
     * back into its register.
     */
    private fun checkNamesAndLines(where: String, original: List<Instruction>, patched: List<Instruction>, names: List<Place>,
                                   nameCall: String, lines: List<Place>) {
        assertEquals("$where: one instruction per name hook, two per line hook", original.size + names.size + 2 * lines.size, patched.size)
        var added = 0
        val hooks = mutableSetOf<Int>()
        (names.map { it to false } + lines.map { it to true }).sortedBy { it.first.index }.forEach { (place, line) ->
            val at = place.index + 1 + added
            val hook = patched[at]
            assertTrue("$where: the hook after $place calls ${if (line) NAME_LINES else nameCall}", hook.names(if (line) NAME_LINES else nameCall))
            assertEquals("$where: $place's register handed over", place.register to 1,
                (hook as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
            hooks += at
            if (line) {
                assertEquals("$where: the line count goes back in v${place.register}", Opcode.MOVE_RESULT to place.register,
                    patched[at + 1].opcode to (patched[at + 1] as OneRegisterInstruction).registerA)
                hooks += at + 1
                added += 2
            } else {
                added += 1
            }
        }
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
                val anchors = listOf(TRAY_TITLE_KEY, STORY_TITLE_SPEC, POST_HEADER_COMPONENT, REEL_AUTHOR_COMPONENT, FEED_TITLE_TRACE)
                    .flatMap { FixtureDex.classesHolding(bundle, it) }
                    .filter { !it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
                val made = FixtureDex.classes(bundle, madeIn(anchors.values) - anchors.keys)
                // 588's store-less row asks a string table for its names' key.
                val tables = FixtureDex.classesHolding(bundle, TRAY_NAME_KEY)
                    .filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, TRAY_NAME_KEY) } }
                    .filterNot { it.type in anchors.keys }.associateBy { it.type }
                // Its post header asks one for the header's name in its constructor.
                val headerTables = anchors.values.flatMap { it.methods }
                    .filter { isStringTable(it) && holdsString(it, POST_HEADER_COMPONENT) }
                    .associate { "${it.definingClass}->${it.name}" to tableIndices(it, POST_HEADER_COMPONENT) }
                val headerAskers = if (headerTables.isEmpty()) emptyMap() else FixtureDex.classes(bundle,
                    FixtureDex.methodsWhere(bundle, { true }) { method ->
                        val code = method.implementation?.instructions?.toList() ?: emptyList()
                        method.name == "<init>" && code.indices.any { at ->
                            val index = tableIndexAt(code, at) ?: return@any false
                            val call = (code[at] as ReferenceInstruction).reference as MethodReference
                            index in headerTables["${call.definingClass}->${call.name}"].orEmpty()
                        }
                    }.map { it.definingClass }.toSet() - anchors.keys)
                val headerMade = FixtureDex.classes(bundle, madeIn(headerAskers.values) - anchors.keys - headerAskers.keys - made.keys)

                val tray = PatchContexts.of(anchors.values.toList() + tables.values + extension).findTrayNames()
                val builderType = tray.text.definingClass
                val chain = hierarchy(bundle, builderType)
                val shaped = FixtureDex.methodsWhere(bundle, { true }) {
                    !it.definingClass.startsWith(EXTENSION_CLASSES) && backgroundShaped(it)
                }
                val styles = FixtureDex.classes(bundle, shaped.map { it.returnType }.toSet())
                val emptyStyles = styles.values.flatMap { it.staticFields }.map { it.type }.filter { it.startsWith("LX/") }.toSet()
                val backgrounds = FixtureDex.classes(bundle, shaped.map { it.definingClass }.toSet() + emptyStyles)
                // The looks a post header can take: the enums its fields hold (#533).
                val looks = FixtureDex.classes(bundle, anchors.values.flatMap { it.instanceFields }.map { it.type }
                    .filter { it.startsWith("LX/") }.toSet() - anchors.keys)
                    .filterValues { it.superclass == "Ljava/lang/Enum;" }
                val classes = made + headerMade + backgrounds + styles + chain + tables + anchors + headerAskers + looks

                val context = PatchContexts.of(classes.values.toList() + extension)
                val trayNames = context.findTrayNames()
                val openStory = context.findOpenStoryNames(trayNames.text)
                val reel = context.findReelNames(trayNames.text)
                val lines = findNameLines(reel)
                val look = context.findHeaderLook(reel)
                val feed = context.findFeedNames(trayNames.text, reel)
                val style = context.findBoxStyle(builderType)
                val trayHost = trayNames.places.map { it.method.definingClass }.toSet().single()
                val openStoryHosts = openStory.map { it.method.definingClass }.toSet()
                val header = reel.names.map { it.method.definingClass }.toSet().single()
                val marks = reel.marks.map { place ->
                    ((place.method.code()[place.index] as ReferenceInstruction).reference as MethodReference).definingClass
                }.toSet()
                val entries = reel.entries.map { (method, props) -> "${method.name}/p$props" }.toSet()
                val lineReads = lines.map { "${it.method.name}/v${it.register}" }.toSet()
                val lookField = "${look.definingClass}->${look.name}:${look.type}"
                val feedHost = feed.build.definingClass
                val feedLook = "${feed.look.definingClass}->${feed.look.name}:${feed.look.type}"
                expected[version]?.get(abi)?.let { hosts ->
                    assertEquals("$name: where the names are drawn", hosts, Hosts(
                        trayHost, trayNames.places.size, openStoryHosts, openStory.size, header, reel.props, marks, entries,
                        lineReads, lookField, "$feedHost->${feed.build.name}/p${feed.props}/p${feed.host}",
                        feed.names.map { it.toString() }.toSet(), feed.lines.map { it.toString() }.toSet(), feedLook,
                    ))
                }
                // A feed post's header is its own class (#533), drawn in the same looks as the reel's, and
                // builds its name in two places: itself, and the lambda it traces the build in.
                assertTrue("$name: the feed's header is the reel's", feedHost != header)
                assertEquals("$name: the feed's header looks like the reel's", look.type, feed.look.type)
                assertEquals("$name: the feed's header is the look's owner", feedHost, feed.look.definingClass)
                val feedMethods = (feed.names + feed.lines).map { it.method }.distinctBy { "${it.definingClass}->${it.name}" }
                assertEquals("$name: the feed names a post in its build and its lambda", 2, feedMethods.size)
                feedMethods.forEach { method ->
                    assertEquals("$name: ${method.definingClass}->${method.name} names a post once and reads its lines once",
                        1 to 1, feed.names.count { it.method == method } to feed.lines.count { it.method == method })
                }
                val lambda = feedMethods.single { it.definingClass != feedHost }.definingClass
                // Negative control: without the lambda the traced build can't be found, so the patch says so.
                assertThrows("$name: the feed's names were found without its traced build", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it != lambda }.values.toList() + extension).findFeedNames(trayNames.text, reel)
                }
                assertEquals("$name: each entry of the header reads its name's line count", reel.entries.map { it.first }.toSet(),
                    lines.map { it.method }.toSet())
                assertEquals("$name: the header's look is its own", header, look.definingClass)
                assertTrue("$name: the header's look names ON_MEDIA", (looks[look.type]?.methods ?: emptyList()).any {
                    it.name == "<clinit>" && holdsString(it, "ON_MEDIA")
                })
                // Negative control: without the look's enum the header's look can't be told.
                assertThrows("$name: a header look was found without its enum", PatchException::class.java) {
                    PatchContexts.of(classes.filterKeys { it !in looks.keys }.values.toList() + extension).findHeaderLook(reel)
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

                val originals = ((trayNames.places + openStory + reel.marks + reel.names + lines + feed.names + feed.lines)
                    .map { it.method } + reel.entries.map { it.first } + feed.build)
                    .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
                    .associateWith { it.code() }
                context.addStoryNameBoxes(trayNames.text, trayNames.places, openStory)
                context.addReelNameBoxes(trayNames.text, reel, lines, look)
                context.addFeedNameBoxes(trayNames.text, feed)

                context.checkHelper(name, trayHost, STORY_NAME_HELPER, FOR_STORY_NAME, builderType, style)
                openStoryHosts.forEach { host ->
                    context.checkStyleHelper(name, host, STORY_TITLE_HELPER, FOR_STORY_NAME, style)
                    assertTrue("$name: an open story's names keep their builders as Facebook made them",
                        context.mutableClassDefBy(host).methods.none { it.name == STORY_NAME_HELPER })
                }
                context.checkHelper(name, header, REEL_NAME_HELPER, FOR_REEL_NAME, builderType, style)
                context.checkHelper(name, feedHost, REEL_NAME_HELPER, FOR_REEL_NAME, builderType, style)

                for ((method, raw) in originals) {
                    val where = "$name: ${method.definingClass}->${method.name}"
                    val original = unpadded(raw)
                    var patched = unpadded(context.method(method).code())
                    val entry = reel.entries.singleOrNull { it.first == method }
                    if (entry != null) {
                        // The header says whose props it builds from, and how it's drawn, first thing:
                        // the props and its look in its own first two registers, then the call.
                        val mutable = context.method(method)
                        val ins = (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
                            method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                        val firstParameter = mutable.implementation!!.registerCount - ins
                        assertEquals("$where starts by moving its props to v0", Triple(Opcode.MOVE_OBJECT_FROM16, 0, firstParameter + entry.second),
                            (patched[0] as TwoRegisterInstruction).let { Triple(it.opcode, it.registerA, it.registerB) })
                        assertEquals("$where moves itself to v1", Triple(Opcode.MOVE_OBJECT_FROM16, 1, firstParameter),
                            (patched[1] as TwoRegisterInstruction).let { Triple(it.opcode, it.registerA, it.registerB) })
                        assertEquals("$where reads its look into v1", Opcode.IGET_OBJECT, patched[2].opcode)
                        assertEquals(lookField, (patched[2] as ReferenceInstruction).reference.toString())
                        assertEquals(1 to 1, (patched[2] as TwoRegisterInstruction).let { it.registerA to it.registerB })
                        assertTrue("$where then names its props and look", patched[3].names(HEADER))
                        assertEquals("$where hands over v0 and v1", listOf(0, 1),
                            (patched[3] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD).take(it.registerCount) })
                        patched = patched.drop(4)
                    }
                    if (method == feed.build) {
                        // The feed's build says whose props it builds from and how its header is drawn, first
                        // thing: the props and the header in its own first two registers, the header's look
                        // read when there's a header, then the call.
                        val mutable = context.method(method)
                        val ins = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                        val firstParameter = mutable.implementation!!.registerCount - ins
                        assertEquals("$where starts by moving its props to v0", Triple(Opcode.MOVE_OBJECT_FROM16, 0, firstParameter + feed.props),
                            (patched[0] as TwoRegisterInstruction).let { Triple(it.opcode, it.registerA, it.registerB) })
                        assertEquals("$where moves its header to v1", Triple(Opcode.MOVE_OBJECT_FROM16, 1, firstParameter + feed.host),
                            (patched[1] as TwoRegisterInstruction).let { Triple(it.opcode, it.registerA, it.registerB) })
                        assertEquals("$where skips the look without a header", Opcode.IF_EQZ to 1,
                            patched[2].opcode to (patched[2] as OneRegisterInstruction).registerA)
                        assertEquals("$where skips straight to the call", 4, (patched[2] as BuilderOffsetInstruction).target.location.index)
                        assertEquals("$where reads its look into v1", Opcode.IGET_OBJECT, patched[3].opcode)
                        assertEquals(feedLook, (patched[3] as ReferenceInstruction).reference.toString())
                        assertEquals(1 to 1, (patched[3] as TwoRegisterInstruction).let { it.registerA to it.registerB })
                        assertTrue("$where then names its props and look", patched[4].names(HEADER))
                        assertEquals("$where hands over v0 and v1", listOf(0, 1),
                            (patched[4] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD).take(it.registerCount) })
                        patched = patched.drop(5)
                    }
                    val story = trayNames.places.filter { it.method == method }
                    val titles = openStory.filter { it.method == method }
                    val names = (reel.names + feed.names).filter { it.method == method }
                    val nameHost = if (method in feedMethods) feedHost else header
                    val nameLines = (lines + feed.lines).filter { it.method == method }
                    val reelMarks = reel.marks.filter { it.method == method }
                    when {
                        story.isNotEmpty() -> checkAfter(where, original, patched, story,
                            "${method.definingClass}->$STORY_NAME_HELPER($builderType)V")
                        titles.isNotEmpty() -> checkBefore(where, original, patched, titles,
                            "${method.definingClass}->$STORY_TITLE_HELPER($styleType)$styleType")
                        names.isNotEmpty() || nameLines.isNotEmpty() -> checkNamesAndLines(where, original, patched, names,
                            "$nameHost->$REEL_NAME_HELPER($builderType)V", nameLines)
                        else -> checkAfter(where, original, patched, reelMarks, REEL_AUTHOR)
                    }
                }
                assertEquals("$name: CaptionBackdrop.storyNamesInBuild() isn't switched on", 1, inBuild(context, "storyNamesInBuild"))
                assertEquals("$name: CaptionBackdrop.reelNamesInBuild() isn't switched on", 1, inBuild(context, "reelNamesInBuild"))
                assertEquals("$name: CaptionBackdrop.feedVideoNamesInBuild() isn't switched on", 1, inBuild(context, "feedVideoNamesInBuild"))
                assertEquals("$name: CaptionBackdrop.fullNamesInBuild() isn't switched on", 1, inBuild(context, "fullNamesInBuild"))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
