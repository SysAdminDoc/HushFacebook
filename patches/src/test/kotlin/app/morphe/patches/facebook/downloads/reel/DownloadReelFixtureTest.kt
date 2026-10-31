/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.media.resume.TRACK_START
import app.morphe.patches.facebook.media.resume.paramsGetters
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.facebook.media.resume.trackers
import app.morphe.patches.facebook.media.taptoplay.FB_SHORTS
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
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
 * Download any reel run whole on each declared build's own sidebar and the classes it reaches.
 * The sidebar's render doesn't call the button factory on 585: it hands the button's kind to a
 * static of its class that news up one small class per kind, and those call the factory, so the
 * fixture loads the whole chain. The block goes in at the first instruction of the assembly call's
 * straight run where six consecutive locals are dead and every value it reads is what the call
 * gets (the player from its parked register), and it calls the helper over those six registers. The
 * helper builds the button and adds it and its marker to the two lists itself, then the Cast button
 * the same way from the build's GOOGLE_CAST icon (#182), each behind its own switch. The build's
 * one FbShortsSideBarComponent render, which the button can't go in, gets a counter as its first
 * instruction and nothing else (#18). And the More sheet's builder asks for a Download row first
 * thing, through a helper on its class, with the handler made to take the row's click interface
 * (#18). The kept player start gets one range call for the missing-sidebar diagnostic, whose
 * one-register predicate reads this build's getter and flag.
 */
class DownloadReelFixtureTest {
    private val sidebarName = "UDDSideBarComponent"
    private val function1 = "Lkotlin/jvm/functions/Function1;"
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val otherSidebarName = "FbShortsSideBarComponent"
    private val showsButton = "Lapp/morphe/extension/facebook/download/ReelDownload;->showsButton()Z"
    private val helperName = "hushfacebookDownloadButton"
    private val reelDownload = "Lapp/morphe/extension/facebook/download/ReelDownload;"

    private val verticalUfi = "Lapp/morphe/extension/facebook/download/ReelDownload;->verticalUfi(Z)Z"

    /** What the block did on each ABI: how far in front of the assembly call it went, its first scratch register, and where it read the helper's six values (session, scoped context, player, story, buttons, markers); then where the render's layout flag is handed to the extension, and its register (#316). Both 585 ABIs lay the render out alike. */
    private val expected = mapOf(
        "arm64-v8a" to "before=42 scratch=3 sources=[62, 2, 28, 15, 23, 21] layout=217 v8",
        "armeabi-v7a" to "before=42 scratch=3 sources=[62, 2, 28, 15, 23, 21] layout=217 v8",
    )

    /** A render of the same Litho method as [render]: its name, its one scoped parameter and its return type. */
    private fun rendersLike(method: Method, render: Method) = method.name == render.name &&
        method.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString) &&
        method.returnType == render.returnType && holdsString(method, otherSidebarName)

    private fun isFactory(call: MethodReference) =
        call.parameterTypes.count { it.toString() == function1 } == 4 && call.parameterTypes.firstOrNull()?.toString() == session

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

    /** Every class the method names: the owners of what it calls and reads, and the types it makes or casts to. */
    private fun namedBy(method: Method): Set<String> = method.body().mapNotNull { instruction ->
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is MethodReference -> reference.definingClass
            is FieldReference -> reference.definingClass
            is TypeReference -> reference.type
            else -> null
        }
    }.filter { it.startsWith("L") }.toSet()

    /** [namedBy], plus the parameter and return types of everything [method] calls. */
    private fun reachedBy(method: Method): Set<String> = namedBy(method) + method.body().flatMap { instruction ->
        instruction.called()?.let { it.parameterTypes.map(CharSequence::toString) + it.returnType }.orEmpty()
    }.filter { it.startsWith("L") }

    @Test
    fun `the reel button and the More sheet row go in with proved registers on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val actual = sortedMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val abi = bundle.name.substringAfter("-$version-").substringBefore(".apkm")
                val holders = FixtureDex.classesHolding(bundle, sidebarName)
                val (builderClass, builder) = holders.flatMap { classDef ->
                    classDef.methods.filter { isSidebarBuilder(it, classDef.methods) }.map { classDef to it }
                }.single()

                // The classes the chain from the render to the factory names, loaded step by step as the patch's own lookups follow it.
                val classes = mutableMapOf<String, ClassDef>()
                FixtureDex.classes(bundle, namedBy(builder)).forEach { (type, classDef) -> classes[type] = classDef }
                classes[builderClass.type] = builderClass
                val statics = calledStatics(builder, builderClass.methods)
                assertTrue("${bundle.name}: the render hands its buttons to a static of its class", statics.isNotEmpty())
                FixtureDex.classes(bundle, statics.flatMap { namedBy(it) }.toSet()).forEach { (type, classDef) -> classes.putIfAbsent(type, classDef) }
                val makers = reachedFactoryCallers(builder, builderClass.methods) { call ->
                    classes[call.definingClass]?.let { resolveMethod(it, call) }
                }
                assertTrue("${bundle.name}: the buttons are made by ${makers.size} methods", makers.size >= 2)
                assertTrue(
                    "${bundle.name}: the render itself doesn't call the factory, it's two calls away",
                    builder.body().none { instruction -> instruction.called()?.let(::isFactory) == true },
                )
                val factory = makers.flatMap { it.body() }.mapNotNull { it.called() }.filter(::isFactory)
                    .distinctBy { "${it.definingClass}->${it.name}" }.single()

                // The component's render: the builder itself up to 582, the one handing it the
                // scoped context on 583. The component declares itself by name, so it's a holder too.
                val (component, render) = if (builder.parameterTypes.size == 1) builderClass to builder else {
                    holders.flatMap { classDef -> classDef.methods.filter { rendersThrough(it, builder) }.map { classDef to it } }.single()
                }

                val types = namedBy(builder) + makers.flatMap { namedBy(it) } + component.fields.map { it.type } +
                    builder.parameterTypes.map { it.toString() } + setOf(function1, "Lcom/facebook/video/engine/api/VideoDataSource;")
                FixtureDex.classes(bundle, types).forEach { (type, classDef) -> classes[type] = classDef }
                val rowHolders = FixtureDex.classesHolding(bundle, "fds_control_download_video")
                rowHolders.forEach { classes.putIfAbsent(it.type, it) }
                // The other sidebar, whose render the patch counts in Hook status (#18).
                val others = FixtureDex.classesHolding(bundle, otherSidebarName).filter { classDef ->
                    classDef.type != component.type && classDef.methods.any { rendersLike(it, render) }
                }
                assertEquals("${bundle.name}: the other sidebar components", 1, others.size)
                val other = others.single()
                classes.putIfAbsent(other.type, other)
                classes[component.type] = component
                classes[builderClass.type] = builderClass
                // The More sheet's builder, everything it calls with, and the row's own types (#18).
                // On 585 the method holding the row's id only builds the row, and a static method of another class adds it.
                val sheetFixture = sheetFixture(bundle)
                val sheetHolder = sheetFixture.builderClass
                val sheetBuilder = sheetFixture.builder
                val sheetTypes = reachedBy(sheetBuilder) + reachedBy(sheetFixture.row) + sheetFixture.row.definingClass
                FixtureDex.classes(bundle, sheetTypes).forEach { (type, classDef) -> classes.putIfAbsent(type, classDef) }
                val rowTypes = sheetTypes.mapNotNull(classes::get).flatMap { classDef ->
                    classDef.methods.filter { it.name == "<init>" }.flatMap { it.parameterTypes.map(CharSequence::toString) }
                }.toSet()
                FixtureDex.classes(bundle, rowTypes).forEach { (type, classDef) -> classes.putIfAbsent(type, classDef) }
                classes[sheetHolder.type] = sheetHolder
                classes[SETTINGS_STATUS] = ExtensionDex.classDef(SETTINGS_STATUS)
                classes[MENU_HANDLER] = ExtensionDex.classDef(MENU_HANDLER)
                classes[REEL_SIDEBAR_DIAGNOSTICS] = ExtensionDex.classDef(REEL_SIDEBAR_DIAGNOSTICS)
                val player = FixtureDex.classesHolding(bundle, GROOT_PLAY).single { grootPlays(it).size == 1 }
                classes[player.type] = player
                val paramsType = "Lcom/facebook/video/engine/api/VideoPlayerParams;"
                val params = FixtureDex.classes(bundle, setOf(paramsType)).getValue(paramsType)
                classes[paramsType] = params
                val play = grootPlays(player).single()
                val start = trackers(player, TRACK_START, play.parameterTypes.single().toString()).single()
                val getter = paramsGetters(player).single()
                val flag = params.methods.filter { holdsString(it, FB_SHORTS) }
                    .mapNotNull { reportedValues(it)[FB_SHORTS] }.distinctBy(FieldReference::toString).single()
                // The config dump that names the layout flag, which the reader is found by (#316).
                FixtureDex.classesHolding(bundle, VERTICAL_UFI_FLAG).forEach { classes.putIfAbsent(it.type, it) }
                val context = PatchContexts.of(classes.values)
                val sheet = context.moreSheet("the fixture")

                downloadReelPatch.execute(context)

                val sheetBody = context.mutableClassDefBy(sheetHolder.type).methods
                    .single { it.name == sheetBuilder.name &&
                            it.parameterTypes.map(CharSequence::toString) == sheetBuilder.parameterTypes.map(CharSequence::toString)
                    }
                    .implementation!!.instructions.toList()
                val rowCall = sheetBody[6] as RegisterRangeInstruction
                assertEquals(
                    "${bundle.name}: the More sheet asks the row helper first thing, from v0 to v5",
                    "$ROW_HELPER 0 6",
                    "${((rowCall as ReferenceInstruction).reference as MethodReference).name} ${rowCall.startRegister} ${rowCall.registerCount}",
                )
                assertEquals(
                    "${bundle.name}: the builder keeps the rest of its own code",
                    sheetBuilder.implementation!!.instructions.count() + 7,
                    sheetBody.size,
                )
                assertTrue(
                    "${bundle.name}: the helper is on the builder's class",
                    context.mutableClassDefBy(sheetHolder.type).methods.any { it.name == ROW_HELPER },
                )
                val handler = context.mutableClassDefBy(MENU_HANDLER)
                assertEquals("${bundle.name}: the handler takes the row's click", listOf(sheet.click), handler.interfaces.toList())
                assertTrue(
                    "${bundle.name}: the handler answers the click's one method",
                    handler.methods.any { it.name == sheet.clickMethod && it.parameterTypes.isEmpty() && it.returnType == "V" },
                )

                val patchedClass = context.mutableClassDefBy(builderClass.type)
                val patched = patchedClass.methods.single {
                    it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }
                val body = patched.implementation!!.instructions.toList()
                val original = builder.body()
                val assemblyAt = original.indexOfFirst { instruction ->
                    instruction.called()?.parameterTypes?.count { it.toString() == "Ljava/util/ArrayList;" } == 2
                }
                val injectAt = body.indexOfFirst { it.called()?.toString() == showsButton }
                assertEquals("${bundle.name}: the switch is asked once", 1, body.count { it.called()?.toString() == showsButton })
                // On 585 the builder is the render, so the layout hook (#316) is in this method too.
                val renderIsBuilder = component.type == builderClass.type && render.name == builder.name &&
                    render.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                fun withoutLayoutHook(code: List<Instruction>): List<Instruction> {
                    val at = code.indexOfFirst { it.called()?.toString() == verticalUfi }
                    return if (at < 0) code else code.take(at) + code.drop(at + 2)
                }
                val hookShift = body.indexOfFirst { it.called()?.toString() == verticalUfi }.let { if (it in 0 until injectAt) 2 else 0 }
                assertTrue("${bundle.name}: the block goes in front of the assembly call", injectAt in 0 until assemblyAt)
                // The block: the switch, its answer, the branch past it, six moves into six consecutive
                // locals, the helper over those six, and the nop its branch lands on.
                val blockSize = 11
                val scratch = (body[injectAt + 1] as OneRegisterInstruction).registerA
                assertEquals("${bundle.name}: the block branches on the switch's answer", Opcode.IF_EQZ, body[injectAt + 2].opcode)
                assertEquals("${bundle.name}: on the register the answer is in", scratch, (body[injectAt + 2] as OneRegisterInstruction).registerA)
                val moves = (3..8).map { body[injectAt + it] }
                moves.forEach { assertEquals("${bundle.name}: the block copies six values", Opcode.MOVE_OBJECT_FROM16, it.opcode) }
                assertEquals(
                    "${bundle.name}: into six consecutive locals",
                    (scratch..scratch + 5).toList(),
                    moves.map { (it as TwoRegisterInstruction).registerA },
                )
                val helperCall = body[injectAt + 9] as RegisterRangeInstruction
                assertEquals("${bundle.name}: the helper is called over the six", Opcode.INVOKE_STATIC_RANGE, helperCall.opcode)
                assertEquals("${bundle.name}: from the first", scratch, helperCall.startRegister)
                assertEquals("${bundle.name}: six registers", 6, helperCall.registerCount)
                assertEquals("${bundle.name}: the helper", helperName, (helperCall as ReferenceInstruction).let { (it.reference as MethodReference).name })
                assertEquals("${bundle.name}: the block ends on its nop", Opcode.NOP, body[injectAt + blockSize - 1].opcode)
                // Hooked code is counted without the alignment nops a payload makes dexlib2 add or drop.
                fun unpadded(list: List<Instruction>) = list.filter { it.opcode != Opcode.NOP }.map { it.opcode }
                assertEquals(
                    "${bundle.name}: the rest is Facebook's own code",
                    unpadded(original),
                    unpadded(withoutLayoutHook(body.take(injectAt) + body.drop(injectAt + blockSize))),
                )
                // The scratch registers are free at the block: nothing reads them again, and the
                // sources aren't among them.
                val sources = moves.map { (it as TwoRegisterInstruction).registerB }
                assertTrue("${bundle.name}: the block reads none of the registers it writes", sources.none { it in scratch..scratch + 5 })
                // The older button layout (#316, #298): the render reads the layout flag once, 1 for
                // yes and 0 for no, and where the two writes meet the extension gets Facebook's
                // answer back into the same register, so the branch to the older strip goes by it.
                val renderOriginal = render.body()
                val renderBody = context.mutableClassDefBy(component.type).methods.single {
                    it.name == render.name && it.parameterTypes.map(CharSequence::toString) == render.parameterTypes.map(CharSequence::toString)
                }
                val renderCode = renderBody.implementation!!.instructions.toList()
                val layoutAt = renderCode.indexOfFirst { it.called()?.toString() == verticalUfi }
                assertEquals("${bundle.name}: the layout is asked once", 1, renderCode.count { it.called()?.toString() == verticalUfi })
                // Where the hook sits in Facebook's own code, past the button block when that's ahead of it.
                val layoutOriginal = layoutAt - if (renderIsBuilder && injectAt < layoutAt) blockSize else 0
                val asked = renderCode[layoutAt] as RegisterRangeInstruction
                val flagRegister = asked.startRegister
                assertEquals("${bundle.name}: with the flag alone", 1, asked.registerCount)
                assertEquals("${bundle.name}: its answer goes back in the flag", Opcode.MOVE_RESULT, renderCode[layoutAt + 1].opcode)
                assertEquals("${bundle.name}: the same register", flagRegister, (renderCode[layoutAt + 1] as OneRegisterInstruction).registerA)
                assertEquals(
                    "${bundle.name}: right after Facebook's own read of the flag",
                    listOf(Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.CONST_4, Opcode.IF_EQ, Opcode.CONST_4),
                    renderOriginal.subList(layoutOriginal - 5, layoutOriginal).map { it.opcode },
                )
                assertEquals(
                    "${bundle.name}: through the reader the config dump names the flag by",
                    context.verticalUfiReader().toString(),
                    renderOriginal[layoutOriginal - 5].called().toString(),
                )
                assertEquals(
                    "${bundle.name}: yes and no both write the flag",
                    listOf(flagRegister, flagRegister),
                    listOf(renderOriginal[layoutOriginal - 3], renderOriginal[layoutOriginal - 1]).map { (it as OneRegisterInstruction).registerA },
                )
                val renderFlow = ControlFlow.of(renderBody)
                assertEquals("${bundle.name}: a yes jumps to the hook", setOf(layoutAt - 1, layoutAt), renderFlow.normal[layoutAt - 2].toSet())
                assertEquals("${bundle.name}: a no runs into it", listOf(layoutAt), renderFlow.normal[layoutAt - 1])
                if (!renderIsBuilder) {
                    assertEquals(
                        "${bundle.name}: the rest of the render is Facebook's own",
                        unpadded(renderOriginal),
                        unpadded(withoutLayoutHook(renderCode)),
                    )
                }
                actual[abi] = "before=${assemblyAt - (injectAt - hookShift)} scratch=$scratch sources=$sources layout=$layoutOriginal v$flagRegister"

                // The helper builds the Download and Cast buttons (#182), each behind its own switch,
                // and adds each and its marker itself.
                val helperMethod = patchedClass.methods.single { it.name == helperName }
                assertTrue("${bundle.name}: the helper is static", AccessFlags.STATIC.isSet(helperMethod.accessFlags))
                assertEquals("${bundle.name}: the helper takes six values", 6, helperMethod.parameterTypes.size)
                assertEquals("${bundle.name}: and answers nothing", "V", helperMethod.returnType)
                val helperBody = helperMethod.implementation!!.instructions.toList()
                assertEquals("${bundle.name}: the helper returns void", Opcode.RETURN_VOID, helperBody.last().opcode)
                assertEquals("${bundle.name}: it makes two buttons", 2, helperBody.count { it.called()?.toString() == factory.toString() })
                val gates = helperBody.mapNotNull { it.called()?.toString() }.filter { it.endsWith("Button()Z") }
                assertEquals(
                    "${bundle.name}: each behind its own switch, Download first",
                    listOf("$reelDownload->downloadButton()Z", "$reelDownload->castButton()Z"),
                    gates,
                )
                val handlerFlags = helperBody.indices.filter { at ->
                    helperBody[at].called()?.let { it.definingClass == reelDownload && it.name == "<init>" } == true
                }.map { at -> (helperBody[at - 1] as NarrowLiteralInstruction).narrowLiteral }
                assertEquals("${bundle.name}: seven handlers that save, then seven that cast", List(7) { 0 } + List(7) { 1 }, handlerFlags)
                assertEquals(
                    "${bundle.name}: it adds both buttons and their markers",
                    4,
                    helperBody.count { instruction ->
                        instruction.opcode == Opcode.INVOKE_INTERFACE &&
                            instruction.called()?.let { it.definingClass == "Ljava/util/List;" && it.name == "add" } == true
                    },
                )
                val helperFields = helperBody.mapNotNull { it.field() }.map(FieldReference::toString)
                val modeType = factory.parameterTypes[1].toString()
                val factoryMode = makers.flatMap { it.body() }
                    .filter { it.opcode == Opcode.SGET_OBJECT }.mapNotNull { it.field() }
                    .filter { it.type == modeType }.map(FieldReference::toString).distinct().single()
                assertTrue("${bundle.name}: the helper passes the mode every button of Facebook's gets", factoryMode in helperFields)
                val marker = helperBody.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.called() }
                    .filter { it.parameterTypes.size == 1 && it.returnType != "V" }.distinctBy(MethodReference::toString).single()
                val iconEnum = marker.parameterTypes.single().toString()
                val rowIcon = rowHolders.flatMap { holder -> holder.methods.filter { holdsString(it, "fds_control_download_video") } }
                    .flatMap { it.body() }.filter { it.opcode == Opcode.SGET_OBJECT }.mapNotNull { it.field() }
                    .first { it.type == iconEnum }
                assertTrue("${bundle.name}: the helper draws the icon Facebook's download row draws", rowIcon.toString() in helperFields)
                val castIcon = enumConstant(context.classDefBy(iconEnum), CAST_ICON)
                assertTrue("${bundle.name}: and Facebook's own cast icon for the Cast button, $castIcon", castIcon != null && castIcon in helperFields)
                assertTrue(
                    "${bundle.name}: the Cast button has its own slot name",
                    helperBody.any { (it as? ReferenceInstruction)?.reference?.let { ref -> ref is StringReference && ref.string == "cast_button" } == true },
                )

                val otherRender = context.mutableClassDefBy(other.type).methods.single { rendersLike(it, render) }
                val otherOriginal = other.methods.single { rendersLike(it, render) }
                assertEquals(
                    "${bundle.name}: the other sidebar counts itself first thing",
                    "Lapp/morphe/extension/facebook/download/ReelDownload;->otherSidebarBuilt()V",
                    (otherRender.implementation!!.instructions.first() as ReferenceInstruction).reference.toString(),
                )
                assertEquals(
                    "${bundle.name}: the counter is the only thing added to the other sidebar",
                    otherOriginal.implementation!!.instructions.count() + 1,
                    otherRender.implementation!!.instructions.count(),
                )
                val tracked = context.mutableClassDefBy(player.type).methods.single {
                    it.name == start.name && it.parameterTypes.map(CharSequence::toString) == start.parameterTypes.map(CharSequence::toString)
                }
                val trackedBody = tracked.implementation!!.instructions.toList()
                val started = trackedBody.first() as RegisterRangeInstruction
                assertEquals("${bundle.name}: the real start tracker uses the range form", Opcode.INVOKE_STATIC_RANGE, trackedBody.first().opcode)
                assertEquals("${bundle.name}: the diagnostic receives this tracker's player", tracked.implementation!!.registerCount - 2, started.startRegister)
                assertEquals("${bundle.name}: only the player is passed", 1, started.registerCount)
                assertEquals("${bundle.name}: the start calls the diagnostic", "$REEL_SIDEBAR_DIAGNOSTICS->started(Ljava/lang/Object;)V",
                    (trackedBody.first() as ReferenceInstruction).reference.toString())
                assertEquals("${bundle.name}: the tracker keeps all its own instructions", start.implementation!!.instructions.count() + 1, trackedBody.size)
                val predicate = context.mutableClassDefBy(REEL_SIDEBAR_DIAGNOSTICS).methods.single { it.name == "isReel" }
                val predicateBody = predicate.implementation!!.instructions.toList()
                assertEquals("${bundle.name}: the predicate keeps its parameter in v0", 1, predicate.implementation!!.registerCount)
                assertTrue("${bundle.name}: predicate register operands fit their instruction formats",
                    predicateBody.filterIsInstance<OneRegisterInstruction>().all { it.registerA == 0 } &&
                        predicateBody.filterIsInstance<TwoRegisterInstruction>().all { it.registerB == 0 })
                assertEquals("${bundle.name}: the predicate calls the build's actual params getter",
                    "${player.type}->${getter.name}()$paramsType",
                    predicateBody.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }.single())
                assertEquals("${bundle.name}: the predicate reads the field actually reported as isFbShorts", flag.toString(),
                    predicateBody.mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString() }.single())
                assertEquals("${bundle.name}: null players and params return false", 2, predicateBody.count { it.opcode == Opcode.IF_EQZ })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), actual.keys)
        assertEquals("where the block went and what it read", expected, actual)
    }
}
