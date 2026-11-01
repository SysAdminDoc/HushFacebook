/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/downloadreel/DownloadReelPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.patcher.StringComparisonType
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.resume.TRACK_START
import app.morphe.patches.facebook.media.resume.paramsGetters
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.facebook.media.resume.trackers
import app.morphe.patches.facebook.media.taptoplay.FB_SHORTS
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.shared.reportedFieldNames
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.liveAcrossInjection
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.reels.REEL_SPEAKER_LAYOUT
import app.morphe.patches.facebook.misc.extension.requireParameterIntact
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.singleOrPatchException
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.morphe.patches.facebook.misc.settings.settingsPatch
import java.util.BitSet

private const val HANDLER = "Lapp/morphe/extension/facebook/download/ReelDownload;"
internal const val REEL_SIDEBAR_DIAGNOSTICS = "Lapp/morphe/extension/facebook/download/ReelSidebarDiagnostics;"

private const val VIDEO_DATA_SOURCE = "Lcom/facebook/video/engine/api/VideoDataSource;"
private const val VIDEO_PLAYER_PARAMS = "Lcom/facebook/video/engine/api/VideoPlayerParams;"
private const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val CONTEXT = "Landroid/content/Context;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * How the injection adds to the sidebar's lists: through the List interface.
 *
 * The assembly call declares the button list only as a `java.util.List`, and where it comes from
 * already changed once: 577 makes it with `new ArrayList`, 580 gets it from a helper declared to
 * return an ArrayList. A class method such as `AbstractCollection.add` needs the verifier to know
 * the register holds one, so a helper declared to return a plain List would make ART reject the
 * whole sidebar class. An interface call verifies for any List.
 */
private const val LIST_ADD = "$LIST->add(Ljava/lang/Object;)Z"

private const val PATCH = "Download any reel"

/** The name the sidebar component reports for itself, inside the method that builds it. */
internal const val SIDEBAR = "UDDSideBarComponent"

/**
 * The other component Facebook draws a reel's buttons with. It builds them from a list the viewer's
 * config hands it, through no factory this button could go in, so its reels get no Download button.
 */
private const val OTHER_SIDEBAR = "FbShortsSideBarComponent"

/** The row Facebook shows on your own video. Its icon is the one this button borrows. */
private const val DOWNLOAD_ROW = "fds_control_download_video"

/** What this button is called in the slot the sidebar fills with `share_button`. */
private const val TEST_ID = "download_button"

/**
 * The label, read at run time in the phone's language. The factory takes it as a plain string, so
 * no resource is needed, and a string written into the patch was English on every phone.
 */
private const val LABEL = "$HANDLER->label()Ljava/lang/String;"

/** The Cast button (#182): its slot name, its label read the same way, and Facebook's own cast icon it draws. */
private const val CAST_TEST_ID = "cast_button"
private const val CAST_LABEL = "$HANDLER->castLabel()Ljava/lang/String;"
internal const val CAST_ICON = "GOOGLE_CAST"

/** Asked inside the helper, once the sidebar's switch said yes, for each of the two buttons. */
private const val DOWNLOAD_BUTTON = "$HANDLER->downloadButton()Z"
private const val CAST_BUTTON = "$HANDLER->castButton()Z"

/**
 * What is written under the icon.
 *
 * The other buttons of the strip put a count here, so it is sized for three or four characters and
 * a word wraps across two lines. The icon already says what the button does.
 */
private const val CAPTION = ""

/**
 * Which of the factory's handlers is the tap.
 *
 * Measured, not guessed. Every slot got a handler that reported the event it received. The touch
 * slot fires twice per press with a `MotionEvent`. A visibility slot fires by itself. This one
 * fires once per press with an event holding only the `View`, which is a click.
 */
private const val TAP_SLOT = 1

/** The helper this patch adds to the component. */
private const val HELPER = "hushfacebookDownloadButton"

/** The MobileConfig flag that picks a reel's button layout: on draws the UDD sidebar, off the older strip (#316). */
internal const val VERTICAL_UFI_FLAG = "fdsConfig.enableFDSVerticalUfiFbReels"

/** Handed Facebook's answer for [VERTICAL_UFI_FLAG], returns the one the render goes by. */
internal const val VERTICAL_UFI = "$HANDLER->verticalUfi(Z)Z"

/**
 * Adds a download button beside every reel.
 *
 * Facebook has a download row and builds it only for a video that you posted. Ownership chooses
 * the whole viewer rather than a flag inside one. So for anybody else's reel the sheet that holds
 * that row is never asked for, and there is nothing to force. `docs/facebook-ads-map.md` records
 * the four device rounds that established this, and the two readings of it that were wrong.
 *
 * So this adds a button to the sidebar beside the reel, which every reel has. Nothing is invented.
 * The sidebar builds all of its own buttons through one factory. That factory takes the label as a
 * plain string and the icon as an enum constant, so no resource and no downloaded string pack is
 * involved. This calls the same factory, with the icon Facebook's own download row draws.
 *
 * The handler holds the player of that one item, read off a field of the component, so the file
 * saved is the reel on the screen. The app prepares the reels that come next, so a handler that
 * reads a shared place saves the wrong one.
 */
@Suppress("unused")
val downloadReelPatch = bytecodePatch(
    name = "Download any reel",
    description = "Adds a Download button beside every reel and a Download row to its More menu, so you can keep " +
        "reels on your phone. Videos save at the quality you set, best by default. The More menu also gets Copy " +
        "caption, which copies the reel's whole caption. On by default. Turn either off in " +
        "Hushfacebook settings > Reels and Watch.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    execute {
        // ---- the Kotlin type the handler runs on ------------------------------------------------
        //
        // The handler is a Java class implementing Function1, and the extension carries no Kotlin
        // standard library of its own: Function1 resolves against Facebook's copy at run time. The
        // sidebar factory's own signature names it, so Facebook keeps that name; kotlin.Unit it
        // renames, which is why the handler answers null instead. A build that renamed Function1
        // would load the handler into a NoClassDefFoundError on the first reel, so it is refused.
        check(classDefByOrNull(FUNCTION1) != null) {
            "This Facebook build does not carry $FUNCTION1 under that name, which the download handler needs"
        }

        // ---- the real names of the address fields ---------------------------------------------
        //
        // The source keeps a debug dump that pairs each field with the name it reports for it.
        // So the patch reads those names out of the app, rather than writing down letters that
        // change every release. It matters: the third address on that object is the subtitles, so
        // "the first Uri" saves the wrong thing without a word.
        val names = reportedFieldNames(VIDEO_DATA_SOURCE, marker = "videoHdUri")
        val hdField = names["videoHdUri"]
        val sdField = names["videoUri"]
        val manifestField = names["abrManifestContent"]

        check(hdField != null && sdField != null && manifestField != null) {
            "Expected videoHdUri, videoUri and abrManifestContent on the source, found ${names.keys}"
        }

        // ---- the sidebar ------------------------------------------------------------------------
        val sidebars = mutableListOf<Pair<String, String>>()
        val otherRenders = mutableListOf<Pair<String, Method>>()

        // A sidebar builder names SIDEBAR ([isSidebarBuilder]).
        classDefForEachReferencing(strings = listOf(OTHER_SIDEBAR, SIDEBAR)) { classDef ->
            classDef.methods.forEach { method ->
                val list = method.instructions()

                if (method.parameterTypes.size == 1 && list.any { it.stringReference() == OTHER_SIDEBAR }) {
                    otherRenders += classDef.type to method
                }

                if (isSidebarBuilder(method, classDef.methods)) sidebars += classDef.type to method.name
            }
        }

        check(sidebars.size == 1) {
            "Expected 1 sidebar builder, found ${sidebars.size}: " +
                sidebars.joinToString { "${it.first}->${it.second}" }
        }

        val (sidebarClass, sidebarName) = sidebars.single()
        val builderClass = mutableClassDefBy(sidebarClass)
        val sidebar = builderClass.methods.filter { it.name == sidebarName }
            .singleOrPatchException("$PATCH: the one method named $sidebarName on $sidebarClass")
        val instructions = sidebar.instructions()

        // Up to 583 the builder calls the button factory itself. 584 calls it from a static of the
        // component instead (LX/AF0;->A01, which the render LX/AF0;->A0l calls before and in its
        // button loop). 585 goes one step further: the render hands the button's kind to a static
        // (LX/0B7e;->A0D) that news up one small class for each kind, and each of those calls the
        // factory from a method of its own. So the factory, its mode constant and the icon wrapper
        // are read from every method that reaches it ([reachedFactoryCallers]).
        val factoryCallers = reachedFactoryCallers(sidebar, builderClass.methods) { call ->
            classDefByOrNull(call.definingClass)?.let { resolveMethod(it, call) }
        }
        if (factoryCallers.isEmpty()) {
            throw PatchException("$PATCH: no method of $sidebarClass, nor any it hands a button to, calls the button factory for $sidebarName")
        }
        val factoryCalls = factoryCallers.flatMap { it.instructions() }

        // ---- the render ---------------------------------------------------------------------------
        //
        // Up to 582 the builder is the component's own render, which takes the scoped context
        // alone and reads the component's fields. 583 leaves the body in the component's spec,
        // handed the scoped context, then the fields and the state, and the render only calls it.
        // The component, whose fields the player and the story are found by, is the render's class.
        val (renderClass, render) = if (sidebar.parameterTypes.size == 1) {
            sidebarClass to sidebar
        } else {
            val renders = mutableListOf<Pair<String, Method>>()
            classDefForEachReferencing(types = listOf(sidebar.definingClass)) { classDef ->
                classDef.methods.filter { rendersThrough(it, sidebar) }.forEach { renders += classDef.type to it }
            }
            renders.singleOrPatchException("$PATCH: the one render handing the scoped context to $sidebarClass->$sidebarName")
        }
        val component = mutableClassDefBy(renderClass)

        val scopedType = render.parameterTypes
            .singleOrPatchException("$PATCH: the one parameter of the sidebar's render $renderClass->${render.name}")
            .toString()

        // ---- the other sidebar --------------------------------------------------------------------
        //
        // The Reels viewer draws its buttons with this sidebar or with FbShortsSideBarComponent, as
        // a server-side MobileConfig flag decides, and several other viewers draw the FbShorts one
        // too. That one gets no Download button (#18), and nothing said so. Its render, the same
        // Litho method with the same scoped context as the render above, now counts itself in Hook
        // status first thing, so a report says which of the two a phone draws. The call takes no
        // register and changes nothing on the screen. A build without exactly one such render keeps
        // the button and only loses the count, so it's a warning, not a refusal.
        val others = otherRenders.filter { (type, method) ->
            type != renderClass && method.name == render.name && method.returnType == render.returnType &&
                method.parameterTypes.single().toString() == scopedType
        }
        if (others.size == 1) {
            val (otherClass, otherRender) = others.single()
            mutableClassDefBy(otherClass).methods.single {
                it.name == otherRender.name && it.returnType == otherRender.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(scopedType)
            }.addInstructions(0, "invoke-static { }, $HANDLER->otherSidebarBuilt()V")
        } else {
            patchLog.warning(
                "$PATCH: found ${others.size} $OTHER_SIDEBAR renders rather than one, so the diagnostic report " +
                    "can't count the reels drawn without the button.",
            )
        }

        // ---- the button factory -----------------------------------------------------------------
        //
        // One call there takes four handlers. That is the factory, and its own parameter list then
        // names every wrapper the button needs, so none of them has to be searched for separately.
        val factories = factoryCalls.mapNotNull { it.methodReference() }
            .filter(::isButtonFactory)
            .distinctBy { "${it.definingClass}->${it.name}" }

        check(factories.size == 1) {
            "Expected 1 button factory behind $sidebarClass->$sidebarName, found ${factories.size}"
        }

        val factory = factories.single()
        val parameters = factory.parameterTypes.map(CharSequence::toString)

        // 577 takes 19 parameters, 580 one boolean more (a MobileConfig flag Facebook reads for
        // its own buttons). Everything up to the int keeps its place, and the booleans after it
        // are filled in order, so the shape is checked rather than one count.
        check(parameters.size in 19..20 && parameters[15] == "I" && parameters.drop(16).all { it == "Z" }) {
            "The button factory takes ${parameters.size} parameters ending ${parameters.drop(15)}, " +
                "expected the int and three or four booleans"
        }

        val modeType = parameters[1]
        val primaryType = parameters[2]
        val labelType = parameters[3]
        val iconType = parameters[5]

        // The mode constant that every one of Facebook's own buttons is built with.
        val modes = factoryCalls.mapNotNull { instruction ->
            instruction.fieldReference()?.takeIf {
                instruction.opcode == Opcode.SGET_OBJECT && it.type == modeType
            }
        }.distinctBy { "${it.definingClass}->${it.name}" }

        check(modes.size == 1) {
            "Expected 1 constant of $modeType in the button builders behind $sidebarName, found ${modes.size}"
        }

        val mode = modes.single()

        // The icon is wrapped by a class that extends the icon parameter and takes the enum.
        val iconWrappers = factoryCalls.mapNotNull { instruction ->
            instruction.methodReference()?.takeIf {
                it.name == "<init>" &&
                    it.parameterTypes.size == 1 &&
                    mutableClassDefByOrNull(it.definingClass)?.superclass == iconType
            }
        }.distinctBy { it.definingClass }

        check(iconWrappers.size == 1) {
            "Expected 1 wrapper of $iconType in the button builders behind $sidebarName, found ${iconWrappers.size}"
        }

        val iconWrapper = iconWrappers.single()

        val iconEnumType = iconWrapper.parameterTypes.single().toString()

        // The icon itself is whichever constant Facebook's own download row draws, so the button
        // looks like the one Facebook draws itself.
        var icon: FieldReference? = null

        classDefForEachReferencing(strings = listOf(DOWNLOAD_ROW)) { classDef ->
            classDef.methods.forEach { method ->
                val list = method.instructions()
                if (list.none { it.stringReference() == DOWNLOAD_ROW }) return@forEach

                list.forEach { instruction ->
                    val field = instruction.fieldReference() ?: return@forEach
                    if (instruction.opcode == Opcode.SGET_OBJECT && field.type == iconEnumType) {
                        if (icon == null) icon = field
                    }
                }
            }
        }

        check(icon != null) { "The download row of Facebook draws no icon of $iconEnumType" }

        // The Cast button (#182) draws Facebook's own cast icon, a constant of the same enum. A build
        // whose enum lost it keeps the Download button.
        val castIcon = mutableClassDefByOrNull(iconEnumType)?.let { enumConstant(it, CAST_ICON) }
        if (castIcon == null) patchLog.warning("$PATCH: $iconEnumType names no $CAST_ICON, so reels get no Cast button.")

        // ---- what the component holds --------------------------------------------------------
        //
        // Both by type. The player params are the field whose own type holds the player, and the
        // session is the only field of its kind.
        val playerField = component.fields.filter { field ->
            mutableClassDefByOrNull(field.type.toString())?.fields?.any {
                it.type.toString() == VIDEO_PLAYER_PARAMS
            } == true
        }.singleOrPatchException("$PATCH: the one field of $sidebarClass whose class holds VideoPlayerParams")

        val sessionField = fieldOfType(component.fields, FB_USER_SESSION, "the one FbUserSession field of $sidebarClass")
        val contextField = fieldOfType(
            mutableClassDefBy(scopedType).fields,
            CONTEXT,
            "the one Context field of the sidebar's scoped context $scopedType",
        )

        // ---- the helper -------------------------------------------------------------------------
        //
        // Its own method, so it has a fresh set of registers. The sidebar runs with more than
        // ninety and every one of them is live somewhere. It builds the button and adds it and
        // its marker to the two lists itself, so the sidebar's block only has to hand it six values.
        val markers = iconMarkers(sidebar, iconEnumType) { call ->
            classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }
        }

        check(markers.size == 1) {
            "Expected 1 marker for an icon, found ${markers.size}"
        }

        val marker = markers.single()

        val helper = ImmutableMethod(
            sidebarClass,
            HELPER,
            listOf(
                ImmutableMethodParameter(FB_USER_SESSION, null, null),
                ImmutableMethodParameter(scopedType, null, null),
                ImmutableMethodParameter(playerField.type.toString(), null, null),
                ImmutableMethodParameter(OBJECT, null, null),
                ImmutableMethodParameter(LIST, null, null),
                ImmutableMethodParameter(LIST, null, null),
            ),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(HELPER_LOCALS + 6),
        ).toMutable().apply {
            fun button(icon: String, testId: String, label: String, cast: Boolean) = buildButton(
                factory = factory,
                parameters = parameters,
                trailingBooleans = parameters.size - 16,
                mode = mode,
                icon = icon,
                iconWrapperClass = iconWrapper.definingClass,
                iconEnumType = iconEnumType,
                primaryType = primaryType,
                labelType = labelType,
                scopedType = scopedType,
                contextField = contextField.name,
                hdField = hdField,
                sdField = sdField,
                manifestField = manifestField,
                marker = "${marker.definingClass}->${marker.name}($iconEnumType)${marker.returnType}",
                testId = testId,
                label = label,
                cast = cast,
            )
            addInstructions(
                0,
                helperBody(
                    download = button("${icon!!.definingClass}->${icon!!.name}:$iconEnumType", TEST_ID, LABEL, cast = false),
                    cast = castIcon?.let { button(it, CAST_TEST_ID, CAST_LABEL, cast = true) },
                ),
            )
        }

        builderClass.methods.add(helper)

        // ---- the injection ----------------------------------------------------------------------
        //
        // The sidebar hands three lists to the call that assembles it: one of buttons, one of a
        // marker for each, one of a name for each. The injection goes before that call, where the
        // lists are the same objects that the call receives.
        val assemblyIndex = sidebarAssemblyIndex(instructions)

        check(assemblyIndex > 0) { "$sidebarName does not assemble a sidebar" }

        val assembly = instructions[assemblyIndex]
        val assemblyReference = assembly.methodReference()!!
        val start = (assembly as RegisterRangeInstruction).startRegister

        // The session, the scoped context, the story, the buttons (the List, not either of the two
        // marker arrays) and the markers (the first ArrayList) are arguments this very call
        // receives. So their registers come from its own parameter list, which states their types,
        // and they're followed back from there to where the block reads them (argumentAt).
        //
        // The parameter registers of the method itself cannot be used. This method reuses them as
        // locals long before the end. So a read of `p0` here answers with whatever was last put
        // there, which is how the first attempt earned a VerifyError.
        fun argumentRegister(type: String): Int {
            val at = assemblyReference.parameterTypes.indexOfFirst { it.toString() == type }
            check(at >= 0) { "The sidebar assembly takes no $type" }
            return start + at
        }

        // The player of this item is read once in the prologue and parked, because the register
        // holding the component is overwritten soon after. Follow it to where it was parked. On
        // 583 the render reads the field and the builder gets it as a parameter, which keeps its
        // own register to the end (arm64 LX/APP;->A02 only reads its LX/5DL in v113, and copies
        // it to v35 behind a branch, so the copy isn't set on every path). The block reads the
        // parameter there, once that's proved below.
        val playerParameter = if (sidebar === render) {
            null
        } else {
            sidebar.parameterTypes.indices
                .filter { sidebar.parameterTypes[it].toString() == playerField.type.toString() }
                .singleOrPatchException("$PATCH: the one parameter of $sidebarClass->$sidebarName typed as the player ${playerField.type}")
        }
        // Where it's parked: the move straight after the field read, and the register it fills.
        val parking = if (playerParameter != null) {
            null
        } else {
            instructions.withIndex().firstNotNullOfOrNull { (index, instruction) ->
                val field = instruction.fieldReference() ?: return@firstNotNullOfOrNull null

                if (field.definingClass != sidebarClass || field.name != playerField.name) {
                    return@firstNotNullOfOrNull null
                }

                val read = instruction as? TwoRegisterInstruction ?: return@firstNotNullOfOrNull null
                val next = instructions.getOrNull(index + 1) as? TwoRegisterInstruction

                next?.takeIf {
                    instructions[index + 1].opcode == Opcode.MOVE_OBJECT_FROM16 &&
                        it.registerB == read.registerA
                }?.let { index + 1 to it.registerA }
            }
        }

        check(playerParameter != null || parking != null) { "The player of the item is not parked in $sidebarName" }

        val playerRegister = parking?.second ?: sidebar.parameterRegisterNumber(playerParameter!!)

        // The reel's own story is the props the sidebar was built from, and the assembly call
        // receives it too: the one argument, besides the session, whose type is also the type of
        // one of the component's fields. Its class is a GraphQL tree, and Facebook reads the
        // reel's creation_time off it for the reel's time label. The handler reads that and the
        // first actor's name for the file name. (The component's own tree field is the reel's
        // feedback, which knows neither.)
        val storyType = assemblyReference.parameterTypes.map(CharSequence::toString)
            .filter { type ->
                type.startsWith("L") && type != FB_USER_SESSION &&
                    component.fields.any { it.type.toString() == type }
            }
            .singleOrPatchException("$PATCH: the one argument of the sidebar assembly, besides the session, the component also holds")

        // Up to 584 the strip's own code leaves v0 to v2 free a few instructions before the call. 585
        // keeps the scoped context in v2 and a constant in v0 for the whole method, and the render
        // reuses the register that held the component, so the block goes where the call's straight
        // run starts, where the button loop ends, and writes six consecutive registers that nothing
        // reads again (sidebarBlockPlace). The player was parked early and is overwritten by the
        // event handle setup further down the run, so the block comes before that.
        //
        // None of that is self-evident, so it is proved rather than trusted: an earlier version of
        // this patch read `p0` here, which is live nowhere near the end of this method, and the app
        // died with a VerifyError on every reel. A build that fails with a message below is the same
        // fault found early. Every path to the block has to bring the parked player, and every
        // argument it reads has to be what the call gets.
        val place = sidebar.sidebarBlockPlace(
            assemblyIndex = assemblyIndex,
            session = argumentRegister(FB_USER_SESSION),
            scoped = argumentRegister(scopedType),
            story = argumentRegister(storyType),
            buttons = argumentRegister(LIST),
            markers = argumentRegister(ARRAY_LIST),
            player = playerRegister,
            parkedAt = parking?.first,
            playerParameter = playerParameter,
        )

        // The switch is asked first, every time a reel's sidebar is built. Off, paused, or before the
        // settings are ready, the branch goes straight to the instruction the block was put in
        // front of, so the sidebar is Facebook's own and none of the button's code runs. That
        // keeps a paused or safe-mode start clear of this injection, and a Download button that
        // crashed a start away from the next one. The block takes over that instruction's label,
        // so a jump to it, like the button loop's exit, runs the block first.
        sidebar.addInstructionsAtControlFlowLabel(
            place.index,
            sidebarButtonBlock(
                place = place,
                helper = "$sidebarClass->$HELPER($FB_USER_SESSION$scopedType${playerField.type}$OBJECT$LIST$LIST)V",
            ),
        )

        // ---- the older button layout --------------------------------------------------------------
        //
        // The render draws a reel's buttons one of two ways (#316, #298). The newer layout goes on to
        // the sidebar assembly, where the block above adds the button. The older one, which a server
        // flag still gives some accounts, draws its own strip (585 LX/0B7e;->A17 from 499) and never
        // reaches that call, so those reels had Facebook's buttons alone. On 585 the render is the
        // builder itself. It reads the flag once into a boolean and branches on it, so the hook goes
        // where both of its writes meet and hands Facebook's answer to the extension, which says yes
        // while the button is on. The block's scratch registers include the flag's (v8 on 585), but
        // the block comes after the flag's one branch, where the flag is dead.
        try {
            hookSidebarLayout(component.findMutableMethodOf(render), VERTICAL_UFI)
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so reels Facebook draws with its older button layout keep Facebook's own buttons.")
        }

        // ---- the More sheet -----------------------------------------------------------------------
        //
        // Reels drawn without the UDD sidebar, like the ones some accounts get in a list inside the
        // main screen, show no button above (#18). Every reel's More sheet comes from one builder,
        // so a Download row goes there too. The fixture tests hold it on every declared build; on
        // another one the sidebar button is still worth having, so a miss here is a warning.
        //
        // Copy caption goes in first, so it runs after the Download row and lands below it (#240).
        // It needs the caption render too; either miss leaves the rest working, so each is a warning.
        try {
            rememberCaptions(PATCH)
            addCaptionRow(PATCH, moreSheet(PATCH))
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so reels get no Copy caption row in their More sheet.")
        }
        try {
            addDownloadRow(
                patch = PATCH,
                sheet = moreSheet(PATCH),
                scopedType = scopedType,
                contextField = contextField.name,
                storyType = storyType,
                hdField = hdField,
                sdField = sdField,
                manifestField = manifestField,
            )
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so reels get no Download row in their More sheet.")
        }

        try {
            addMissingSidebarDiagnostic()
        } catch (exception: PatchException) {
            patchLog.warning("${exception.message}, so the missing-sidebar layout diagnostic isn't added.")
        }

        enableStatus("reelDownload")
    }
}

/**
 * The kept start tracker and params getter used by the playback patches, without depending on
 * either optional patch. All anchors are proved before the stub or tracker changes. One range
 * call reads the player without borrowing a register, and the single predicate stub uses v0
 * throughout so its getter and boolean field read fit the 4-bit register formats.
 */
internal fun BytecodePatchContext.addMissingSidebarDiagnostic() {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: missing-sidebar diagnostic: $detail")
    val plays = classDefByStrings(GROOT_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.flatMap(::grootPlays)
    val play = plays.singleOrNull() ?: refuse("expected one player play holding $GROOT_PLAY, found ${plays.size}")
    val owner = classDefBy(play.definingClass)
    val starts = trackers(owner, TRACK_START, play.parameterTypes.single().toString())
    val start = starts.singleOrNull() ?: refuse("expected one $TRACK_START in ${owner.type}, found ${starts.size}")
    if (start.implementation!!.instructions.none()) refuse("$TRACK_START has no instructions")
    val getters = paramsGetters(owner)
    val getter = getters.singleOrNull() ?: refuse("expected one params getter in ${owner.type}, found ${getters.size}")
    val params = classDefByOrNull(VIDEO_PLAYER_PARAMS) ?: refuse("this build has no $VIDEO_PLAYER_PARAMS")
    val flags = params.methods.filter { holdsString(it, FB_SHORTS) }
        .mapNotNull { reportedValues(it)[FB_SHORTS] }.distinctBy(FieldReference::toString)
    val flag = flags.singleOrNull() ?: refuse("expected one field reported as $FB_SHORTS, found ${flags.size}")
    if (flag.type != "Z") refuse("the field reported as $FB_SHORTS isn't boolean")
    val declared = params.fields.singleOrNull {
        it.name == flag.name && it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$FB_SHORTS isn't an instance boolean field of $VIDEO_PLAYER_PARAMS")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("the $FB_SHORTS field isn't public")
    if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("the params getter isn't public")
    listOf(owner, params).forEach { if (!AccessFlags.PUBLIC.isSet(it.accessFlags)) refuse("${it.type} isn't public") }
    if (classDefByOrNull(REEL_SIDEBAR_DIAGNOSTICS) == null) refuse("the extension has no $REEL_SIDEBAR_DIAGNOSTICS")
    val predicate = mutableClassDefBy(REEL_SIDEBAR_DIAGNOSTICS).methods.singleOrNull {
        it.name == "isReel" && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
    } ?: refuse("the extension has no static boolean isReel(Object)")

    val body = predicate.implementation ?: refuse("isReel has no body")
    if (body.registerCount !in 1..16 || body.tryBlocks.isNotEmpty()) refuse("isReel has an unexpected body")
    repeat(body.instructions.size) { body.removeInstruction(0) }
    predicate.addInstructions(
        0,
        """
            if-eqz p0, :not_reel
            check-cast p0, ${owner.type}
            invoke-virtual/range { p0 .. p0 }, ${owner.type}->${getter.name}()$VIDEO_PLAYER_PARAMS
            move-result-object p0
            if-eqz p0, :not_reel
            iget-boolean p0, p0, $VIDEO_PLAYER_PARAMS->${flag.name}:Z
            return p0
            :not_reel
            const/4 p0, 0x0
            return p0
        """,
    )
    mutableClassDefBy(owner.type).findMutableMethodOf(start).addInstructions(
        0,
        "invoke-static/range { p0 .. p0 }, $REEL_SIDEBAR_DIAGNOSTICS->started($OBJECT)V",
    )
}

/** How many values [sidebarButtonBlock] hands the helper: the session, the scoped context, the player, the story and the two lists. */
internal const val SIDEBAR_BLOCK_ARGUMENTS = 6

/** The locals the helper keeps for the button's factory call, whose 20 arguments sit in v40 and up. */
private const val HELPER_LOCALS = 60

/** The instructions that take the answer of the call before them, which nothing may be put in front of. */
private val ANSWERS = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_EXCEPTION)

/**
 * Where [sidebarButtonBlock] goes and what it reads and writes: at [index], the registers holding
 * the session, scoped context, player, story and the two lists, and [scratch], the first of six
 * consecutive locals the block writes with the values it hands the helper.
 */
internal class SidebarBlockPlace(
    val index: Int,
    val session: Int,
    val scoped: Int,
    val player: Int,
    val story: Int,
    val buttons: Int,
    val markers: Int,
    val scratch: Int,
)

/**
 * Where [sidebarButtonBlock] goes in front of the assembly call at [assemblyIndex]: the first
 * instruction of the call's straight run ([straightRunStart]) where the block can stand.
 *
 * At that instruction each of the call's arguments the block reads ([argumentAt]) has to be
 * what the call gets, the player has to be what the builder parked in [player] ([requireParkedAt])
 * or the parameter it came in as, and six consecutive locals have to exist that nothing reads
 * again and the block doesn't read either. A run for the block's own writes is needed because
 * 585's render holds a constant in v0 and the scoped context in v2 to the end, so no fixed
 * scratch registers are ever free there. The block never goes between a call and the instruction
 * taking its answer. A run that offers no such instruction is refused with the first reason.
 */
internal fun Method.sidebarBlockPlace(
    assemblyIndex: Int,
    session: Int,
    scoped: Int,
    story: Int,
    buttons: Int,
    markers: Int,
    player: Int,
    parkedAt: Int?,
    playerParameter: Int?,
): SidebarBlockPlace {
    val code = implementation!!.instructions.toList()
    val locals = localRegisterCount()
    val refusals = mutableListOf<String>()
    for (index in straightRunStart(assemblyIndex) until assemblyIndex) {
        if (code[index].opcode in ANSWERS) continue
        try {
            val held = listOf(session, scoped, story, buttons, markers).map { argumentAt(index, assemblyIndex, it) }
            parkedAt?.let { requireParkedAt("$PATCH: the player", player, it, index) }
            playerParameter?.let { requireParameterIntact("$PATCH: the player", it, listOf(index)) }
            val reads = held + player
            val live = liveAcrossInjection(index)
            // The block writes its six locals as 8-bit destinations (move-result, if-eqz,
            // move-object/from16), so the run has to end at v255 even in a bigger frame.
            val scratch = (0..minOf(locals, 256) - SIDEBAR_BLOCK_ARGUMENTS).firstOrNull { first ->
                (first until first + SIDEBAR_BLOCK_ARGUMENTS).none { it in live || it in reads }
            } ?: throw PatchException(
                "$PATCH: $definingClass->$name has no $SIDEBAR_BLOCK_ARGUMENTS consecutive locals at or below v255 " +
                    "that nothing reads after instruction $index",
            )
            return SidebarBlockPlace(index, held[0], held[1], player, held[2], held[3], held[4], scratch)
        } catch (exception: PatchException) {
            refusals += "instruction $index: ${exception.message}"
        }
    }
    throw PatchException(
        "$PATCH: $definingClass->$name has no place for the button before the assembly call at $assemblyIndex " +
            "(${refusals.size} tried); first refusal, ${refusals.firstOrNull() ?: "the call has no run in front of it"}",
    )
}

/**
 * The first instruction of the straight run that ends at instruction [index]: from there to
 * [index] each instruction only falls through to the next, and nothing else jumps in.
 */
internal fun Method.straightRunStart(index: Int): Int {
    val flow = ControlFlow.of(this)
    val entries = IntArray(flow.instructions.size)
    flow.normal.forEach { next -> next.forEach { entries[it]++ } }
    var start = index
    while (start > 0 && flow.normal[start - 1] == listOf(start) && entries[start] == 1) start--
    return start
}

/**
 * The register that holds, at instruction [index], what the call at [callIndex] gets in its
 * argument register [argument]. That's [argument] itself when nothing in between writes it. When an
 * object move fills it in between, it's the register the move copies, found the same way up to the
 * move, as on 584, whose render copies the session, the story and both lists into the call's range
 * after the block. Anything else written there in between doesn't exist yet at [index], so it's
 * refused, and so is an [index] the call's straight run ([straightRunStart]) doesn't reach back to.
 */
internal fun Method.argumentAt(index: Int, callIndex: Int, argument: Int): Int {
    if (index < straightRunStart(callIndex)) {
        throw PatchException(
            "$PATCH: $definingClass->$name jumps between instruction $index and the call at $callIndex, " +
                "so the block there doesn't see what the call gets",
        )
    }
    val instructions = implementation!!.instructions.toList()
    val write = (callIndex - 1 downTo index).firstOrNull { argument in writtenRegisters(instructions[it]) }
        ?: return argument
    val move = instructions[write]
    if (move.opcode != Opcode.MOVE_OBJECT && move.opcode != Opcode.MOVE_OBJECT_FROM16 && move.opcode != Opcode.MOVE_OBJECT_16) {
        throw PatchException(
            "$PATCH: $definingClass->$name makes v$argument at instruction $write, after the block at $index " +
                "reads it for the call at $callIndex",
        )
    }
    return argumentAt(index, write, (move as TwoRegisterInstruction).registerB)
}

/**
 * Throws naming [what] unless [register] holds, at instruction [index], what instruction [parkedAt]
 * put there: no path from the method's start, nor from any other write of [register], reaches
 * [index] without going through [parkedAt]. A throw from an instruction leaves the register as it
 * was, so the handlers it can reach count as well.
 */
internal fun Method.requireParkedAt(what: String, register: Int, parkedAt: Int, index: Int) {
    val flow = ControlFlow.of(this)
    val writes = flow.instructions.indices.filter { it != parkedAt && register in writtenRegisters(flow.instructions[it]) }
    val reached = BitSet()
    val pending = ArrayDeque<Int>()
    fun visit(next: List<Int>) = next.forEach { if (!reached[it]) { reached.set(it); pending += it } }
    visit(listOf(0))
    writes.forEach { visit(flow.normal[it]) }
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at != parkedAt) visit(flow.normal[at])
        visit(flow.exceptional[at])
    }
    if (reached[index]) {
        throw PatchException(
            "$what: $definingClass->$name reaches instruction $index with something other than what instruction " +
                "$parkedAt parks in v$register (it also writes v$register at ${writes.joinToString().ifEmpty { "no other instruction" }})",
        )
    }
}

/**
 * The calls that turn an icon of [iconEnumType] into its marker for the sidebar's marker list: one
 * parameter, that enum, and an answer. Read off [builder] itself, and when it names none, off the
 * static methods it hands an icon to, which [resolve] finds. 583's armeabi-v7a builder adds each
 * button, its marker and its name in a loop body Redex outlined into one static taking the icon and
 * the three lists (LX/AT6;->A04, which asks LX/AT6;->A00), while its arm64 one asks LX/ARN;->A00
 * itself.
 */
internal fun iconMarkers(builder: Method, iconEnumType: String, resolve: (MethodReference) -> Method?): List<MethodReference> {
    fun markersIn(method: Method) = method.instructions().mapNotNull { it.methodReference() }.filter {
        it.parameterTypes.size == 1 && it.parameterTypes.single().toString() == iconEnumType && it.returnType != "V"
    }
    val own = markersIn(builder)
    val found = own.ifEmpty {
        builder.instructions()
            .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { it.methodReference() }
            .filter { call -> call.returnType == "V" && call.parameterTypes.any { it.toString() == iconEnumType } }
            .distinctBy { it.toString() }
            .mapNotNull(resolve)
            .flatMap(::markersIn)
    }
    return found.distinctBy { "${it.definingClass}->${it.name}" }
}

/**
 * Whether [method] is a render handing everything to [builder], the sidebar's builder kept in its
 * spec: it takes one parameter, the scoped context [builder] takes first, and calls [builder]. On
 * 583 the builder (arm64 LX/APP;->A02, armeabi-v7a LX/AR8;->A02, 37 parameters) is handed the
 * scoped context, then the component's fields and its state, by the component's render
 * (LX/APO;->A1C, LX/AR7;->A1C).
 */
internal fun rendersThrough(method: Method, builder: Method): Boolean {
    val scoped = builder.parameterTypes.firstOrNull()?.toString() ?: return false
    if (method.parameterTypes.size != 1 || method.parameterTypes.single().toString() != scoped) return false
    val parameters = builder.parameterTypes.map(CharSequence::toString)
    return method.instructions().any { instruction ->
        instruction.methodReference()?.let {
            it.definingClass == builder.definingClass && it.name == builder.name &&
                it.parameterTypes.map(CharSequence::toString) == parameters
        } == true
    }
}

/**
 * The calls [method] reads [VERTICAL_UFI_FLAG] with. Facebook's config dump (585
 * LX/0Ap7;->getExtraFileFromWorkerThread, armeabi-v7a LX/0ApA) asks the config for the flag, turns
 * the answer into a string and names it, so the reader is the interface call closest before the
 * name, a few instructions up, and it answers a boolean with no arguments (585 LX/0DhM;->BUD()Z).
 */
internal fun verticalUfiReadersIn(method: Method): List<MethodReference> {
    val code = method.instructions()
    return code.indices.filter { code[it].stringReference() == VERTICAL_UFI_FLAG }.mapNotNull { named ->
        (named - 1 downTo maxOf(0, named - 6)).firstNotNullOfOrNull { at ->
            code[at].takeIf { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }?.methodReference()
        }?.takeIf { it.returnType == "Z" && it.parameterTypes.isEmpty() }
    }
}

/** The one call the app reads [VERTICAL_UFI_FLAG] with, found by the name its config dump gives it. */
internal fun BytecodePatchContext.verticalUfiReader(): MethodReference {
    val readers = classDefByStrings(VERTICAL_UFI_FLAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.flatMap(::verticalUfiReadersIn) }
        .distinctBy(MethodReference::toString)
    return readers.singleOrNull()
        ?: throw PatchException("$PATCH: ${readers.size} config reads name $VERTICAL_UFI_FLAG, not one")
}

/**
 * Hands the side column [render]'s choice of button layout to the extension through [hook]. Download
 * and Clean up Reels (#331) each want the newer layout and either can be in a build without the other,
 * so whichever comes second goes right behind the first's answer: its hook is the flag's one read now,
 * and [layoutBranch] no longer knows the shape. Refuses a render it can't read, having written nothing.
 */
internal fun BytecodePatchContext.hookSidebarLayout(render: MutableMethod, hook: String) {
    val code = render.instructions()
    val earlier = code.indexOfFirst { it.methodReference()?.toString().let { call -> call == VERTICAL_UFI || call == REEL_SPEAKER_LAYOUT } }
    if (earlier >= 0) {
        val flag = (code[earlier + 1] as OneRegisterInstruction).registerA
        render.addInstructions(
            earlier + 2,
            """
                invoke-static/range { v$flag .. v$flag }, $hook
                move-result v$flag
            """,
        )
        return
    }
    val reader = verticalUfiReader()
    val branch = layoutBranch(render, reader)
        ?: throw PatchException("$PATCH: ${render.definingClass}->${render.name} doesn't pick its button layout by $reader the way 585 does")
    render.addInstructionsAtControlFlowLabel(
        branch.join,
        """
            invoke-static/range { v${branch.register} .. v${branch.register} }, $hook
            move-result v${branch.register}
        """,
    )
}

/** Where the render's layout flag is settled: the instruction both its writes run into, and its register. */
internal class LayoutBranch(val join: Int, val register: Int)

/**
 * The render's choice of button layout, read the way 585 reads it (LX/0B7e;->A17, 208 to 216):
 *
 *     invoke-interface {v7}, LX/0DhM;->BUD()Z   # the flag, through [reader]
 *     move-result v7
 *     const/4 v8, 0x1
 *     if-eq v7, v0, :join                        # v0 is 1 on every way in
 *     const/4 v8, 0x0
 *   :join
 *
 * so v8 is the flag, and 0 when the config is missing (the null checks before the call jump to the
 * second write). Both writes have to be all that reaches the join, and the flag has to feed one
 * `if-eqz` (233, to the older strip at 499) and nothing else. Null when the render reads [reader]
 * any other way or more than once, or the flag's register is beyond `move-result`'s reach.
 */
internal fun layoutBranch(render: Method, reader: MethodReference): LayoutBranch? {
    val code = render.instructions()
    val ask = code.indices.filter { code[it].methodReference()?.toString() == reader.toString() }.singleOrNull() ?: return null
    val join = ask + 5
    if (join > code.lastIndex) return null
    val answer = code[ask + 1]
    if (answer.opcode != Opcode.MOVE_RESULT) return null
    val read = (answer as OneRegisterInstruction).registerA
    val flag = literalWrite(code[ask + 2], 1) ?: return null
    if (literalWrite(code[ask + 4], 0) != flag || flag == read || flag > 255) return null
    val test = code[ask + 3]
    if (test.opcode != Opcode.IF_EQ) return null
    test as TwoRegisterInstruction
    val one = when (read) {
        test.registerA -> test.registerB
        test.registerB -> test.registerA
        else -> return null
    }
    val flow = ControlFlow.of(render)
    if (flow.normal[ask + 3].toSet() != setOf(ask + 4, join) || flow.normal[ask + 4] != listOf(join)) return null
    if (render.writersReaching(ask + 3, one).any { it < 0 || literalWrite(code[it], 1) != one }) return null
    if (render.writersReaching(join, flag) != listOf(ask + 2, ask + 4)) return null
    val branches = listOf(ask + 2, ask + 4).map { render.readsAfter(it, flag).singleOrNull() ?: return null }.distinct()
    val branch = code[branches.singleOrNull() ?: return null]
    if (branch.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != flag) return null
    return LayoutBranch(join, flag)
}

private val NARROW_CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** The register [instruction] sets to [literal], or null when it sets no such constant. */
private fun literalWrite(instruction: Instruction, literal: Int): Int? =
    if (instruction.opcode in NARROW_CONSTANTS && (instruction as NarrowLiteralInstruction).narrowLiteral == literal) {
        (instruction as OneRegisterInstruction).registerA
    } else {
        null
    }

/** The registers [instruction] writes: its destination, and the one above for a wide value. */
private fun writtenRegisters(instruction: Instruction): Set<Int> {
    val destination = (instruction as? OneRegisterInstruction)?.registerA
    if (destination == null || !instruction.opcode.setsRegister()) return emptySet()
    return if (instruction.opcode.setsWideRegister()) setOf(destination, destination + 1) else setOf(destination)
}

/**
 * What goes in front of the sidebar assembly: ask the switch, then copy the six values the helper
 * needs (the session, the scoped context, the player, the story and the two lists) into the six
 * locals from [SidebarBlockPlace.scratch] up and call the helper over that range. The helper builds
 * the button and adds it and its marker to the two lists itself, so nothing comes back and the
 * block writes nothing but those six locals.
 *
 * The switch's no jumps to the block's own last label, on a nop, which falls through to the
 * instruction the block goes in front of; the block takes over that instruction's label
 * (`addInstructionsAtControlFlowLabel`), so the jump can't name it.
 */
internal fun sidebarButtonBlock(place: SidebarBlockPlace, helper: String): String {
    val r = place.scratch
    return """
        invoke-static { }, $HANDLER->showsButton()Z
        move-result v$r
        if-eqz v$r, :facebooks_own
        move-object/from16 v$r, v${place.session}
        move-object/from16 v${r + 1}, v${place.scoped}
        move-object/from16 v${r + 2}, v${place.player}
        move-object/from16 v${r + 3}, v${place.story}
        move-object/from16 v${r + 4}, v${place.buttons}
        move-object/from16 v${r + 5}, v${place.markers}
        invoke-static/range { v$r .. v${r + 5} }, $helper
        :facebooks_own
        nop
    """
}

/** The one field among [fields] whose type is [type], or a refusal naming [what]. */
internal fun <T : Field> fieldOfType(fields: Iterable<T>, type: String, what: String): T =
    fields.filter { it.type.toString() == type }.singleOrPatchException("$PATCH: $what")

/**
 * The helper's whole body: the Download button and then the Cast button (#182), each built only
 * when its own switch is on. The sidebar's block asked whether either is, so it calls the helper for
 * a sidebar that gets one or both. [cast] is null on a build whose icon enum has no cast icon.
 */
private fun helperBody(download: String, cast: String?): String = """
        invoke-static { }, $DOWNLOAD_BUTTON
        move-result v0
        if-eqz v0, :no_download
$download
        :no_download
""" + (cast?.let {
    """
        invoke-static { }, $CAST_BUTTON
        move-result v0
        if-eqz v0, :no_cast
$it
        :no_cast
"""
} ?: "") + """
        return-void
"""

/**
 * The code that builds one button and adds it and its marker to the two lists, falling through at
 * the end.
 *
 * Register use here is dictated by the instruction formats, not by taste. An `invoke-direct` with
 * arguments and an `iget` both take **4-bit** registers, so everything they touch has to sit in
 * `v0` to `v15`. The factory takes nineteen arguments, and thus needs nineteen **consecutive**
 * registers, so its block sits high at `v40` and each value is moved up once it is built.
 * `new-instance` and `const-string` take 8-bit registers, so those can write high directly.
 *
 * The six parameters are the session, the scoped context, the player, the reel's story and the
 * sidebar's lists of buttons and markers. The story goes to every handler beside the player, as
 * `v3`. The button the factory builds and its icon's marker are added to the two lists here, so the
 * sidebar's own code only has to call this, and `v0` to `v2` are scratch of this method alone.
 */
private fun buildButton(
    factory: MethodReference,
    parameters: List<String>,
    trailingBooleans: Int,
    mode: FieldReference,
    icon: String,
    iconWrapperClass: String,
    iconEnumType: String,
    primaryType: String,
    labelType: String,
    scopedType: String,
    contextField: String,
    hdField: String,
    sdField: String,
    manifestField: String,
    marker: String,
    testId: String,
    label: String,
    cast: Boolean,
): String {
    val signature = "${factory.definingClass}->${factory.name}" +
        parameters.joinToString("", "(", ")") + factory.returnType

    return """
        move-object/from16 v0, p1
        iget-object v0, v0, $scopedType->$contextField:$CONTEXT
        move-object/from16 v5, v0
        move-object/from16 v4, p2
        move-object/from16 v6, p0
        move-object/from16 v3, p3

${handlers(hdField, sdField, manifestField, cast)}
        invoke-static { }, $label
        move-result-object v1

        new-instance v2, $primaryType
        invoke-direct { v2, v1, v8 }, $primaryType-><init>(Ljava/lang/String;$FUNCTION1)V
        move-object/from16 v42, v2

        new-instance v2, $labelType
        invoke-direct { v2, v1, v9 }, $labelType-><init>(Ljava/lang/String;$FUNCTION1)V
        move-object/from16 v43, v2

        new-instance v2, $labelType
        invoke-direct { v2, v1, v10 }, $labelType-><init>(Ljava/lang/String;$FUNCTION1)V
        move-object/from16 v44, v2

        sget-object v7, $icon
        new-instance v2, $iconWrapperClass
        invoke-direct { v2, v7 }, $iconWrapperClass-><init>($iconEnumType)V
        move-object/from16 v45, v2

        move-object/from16 v40, v6
        sget-object v41, ${mode.definingClass}->${mode.name}:${mode.type}
        sget-object v46, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
        sget-object v47, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
        const-string v48, "$testId"
        const-string v49, "$CAPTION"
        const/16 v50, 0x0
        move-object/from16 v51, v11
        move-object/from16 v52, v12
        move-object/from16 v53, v13
        move-object/from16 v54, v14
        const/16 v55, 0x11
${trailingBooleanArguments(trailingBooleans)}

        invoke-static/range { v40 .. v${55 + trailingBooleans} }, $signature
        move-result-object v0
        move-object/from16 v1, p4
        invoke-interface { v1, v0 }, $LIST_ADD

        sget-object v2, $icon
        invoke-static { v2 }, $marker
        move-result-object v2
        move-object/from16 v1, p5
        invoke-interface { v1, v2 }, $LIST_ADD
    """
}

/**
 * The booleans after the factory's int, from v56 up. The first three are what Andrew Liang
 * measured on 577 (off, on, off); a flag 580 added in front of the last is passed as off, the
 * value for an experiment Facebook has not turned on for the button.
 */
private fun trailingBooleanArguments(count: Int): String =
    (0 until count).joinToString("\n") { index ->
        val value = when {
            index == 1 -> 1
            else -> 0
        }
        "        const/16 v${56 + index}, 0x$value"
    }

/**
 * One handler for each slot the factory takes, each knowing which slot it is.
 *
 * Only the tap slot saves. The others are still given a handler rather than null, because the
 * factory is not documented to accept null, and a handler that returns without a word costs
 * nothing. Each gets the player (v4), the context (v5) and the reel's story (v3), and whether it
 * belongs to the Cast button, whose tap casts instead of saving.
 */
private fun handlers(hd: String, sd: String, manifest: String, cast: Boolean) = (0..6).joinToString("\n") { slot ->
    val saves = if (slot == TAP_SLOT) 1 else 0

    """
        new-instance v20, $HANDLER
        move-object/from16 v21, v4
        move-object/from16 v22, v5
        const-string v23, "$hd"
        const-string v24, "$sd"
        const-string v25, "$manifest"
        const/16 v26, 0x$slot
        const/16 v27, 0x$saves
        move-object/from16 v28, v3
        const/16 v29, 0x${if (cast) 1 else 0}
        invoke-direct/range { v20 .. v29 }, $HANDLER-><init>($OBJECT${CONTEXT}Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZ${OBJECT}Z)V
        move-object/from16 v${8 + slot}, v20
    """
}

/**
 * Whether [method] is the reel sidebar's builder. The name alone is in a dozen places: state
 * helpers, lambdas, update calls. The one wanted both names the sidebar and builds a button, the
 * call taking the session and four handlers. 582 merges a lambda naming the sidebar with one
 * building an FDSBottomSheet (four handlers, no session first) into LX/VrR;->invoke, so the
 * session is what tells the button apart. On 583 it's the spec's method the render calls
 * (arm64 LX/APP;->A02, armeabi-v7a LX/AR8;->A02), not the render; see [rendersThrough]. On 584
 * it's the render again (LX/AF0;->A0l), which builds its buttons through a static of its own
 * class; see [buttonFactoryCallers]. 585 keeps the render (arm64 LX/0B7e;->A17, armeabi-v7a
 * LX/0B7h;->A17) but moves the factory two calls away, into per-button classes, so the render
 * itself neither calls it nor a sibling that does. What it still does is name the sidebar and hand
 * the buttons, markers and story to the assembly call ([sidebarAssemblyIndex]), which no other
 * method naming the sidebar does. [siblings] are the methods of [method]'s class.
 */
internal fun isSidebarBuilder(method: Method, siblings: Iterable<Method>): Boolean =
    method.instructions().any { it.stringReference() == SIDEBAR } &&
        (sidebarAssemblyIndex(method.instructions()) >= 0 || buttonFactoryCallers(method, siblings).isNotEmpty())

/**
 * The index of the call that assembles the sidebar from its lists: the first call in [code] with
 * exactly two `ArrayList` parameters, the buttons' markers and their names. -1 when there is none.
 * Why this and not the call's name: the name is renamed every build, while the two lists the render
 * builds up are the call's shape on every build from 577 to 585.
 */
internal fun sidebarAssemblyIndex(code: List<Instruction>): Int = code.indexOfFirst { instruction ->
    (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
        instruction.methodReference()?.let { call -> call.parameterTypes.count { it.toString() == ARRAY_LIST } == 2 } == true
}

/**
 * The methods that call the button factory for [builder]: [builder] itself up to 583, or the
 * statics among [siblings], the methods of its own class, that it calls and that call the factory.
 * 584's render (LX/AF0;->A0l) builds some of its buttons through LX/AF0;->A01, which takes the
 * button's kind and calls the factory (LX/2Uj;->A00) for it, and leaves the rest to a builder
 * shared with the FbShorts sidebar (LX/93I;->A06), which makes the button component (LX/93B)
 * itself.
 */
internal fun buttonFactoryCallers(builder: Method, siblings: Iterable<Method>): List<Method> {
    if (callsButtonFactory(builder)) return listOf(builder)
    return calledStatics(builder, siblings).filter(::callsButtonFactory)
}

/**
 * The statics among [siblings], the methods of [builder]'s class, that [builder] calls: the call
 * names the sibling's name, return type and parameter types.
 */
internal fun calledStatics(builder: Method, siblings: Iterable<Method>): List<Method> {
    val called = builder.instructions()
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { it.methodReference() }
        .filter { it.definingClass == builder.definingClass }
    return siblings.filter { sibling ->
        AccessFlags.STATIC.isSet(sibling.accessFlags) &&
            called.any { call ->
                call.name == sibling.name && call.returnType == sibling.returnType &&
                    call.parameterTypes.map(CharSequence::toString) == sibling.parameterTypes.map(CharSequence::toString)
            }
    }
}

/**
 * The method of [owner] that [call] names (name, return type and parameter types, any access), or
 * null when [owner] has none. Used to follow a call into a class the patch reads but doesn't change.
 */
internal fun resolveMethod(owner: ClassDef, call: MethodReference): Method? = owner.methods.firstOrNull { method ->
    method.name == call.name && method.returnType == call.returnType &&
        method.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
}

/**
 * The methods that call the button factory for [builder], found from the builder outward: the ones
 * [buttonFactoryCallers] finds, or, when the render calls none, the methods the statics it calls
 * call in turn, which [resolve] finds from a call. 585's render calls a static of its class
 * (arm64 LX/0B7e;->A0D, armeabi-v7a LX/0B7h;->A0D) that switches on the button's kind and news up one
 * small class for each, whose method calls the factory (LX/02cD;->A00); the factory is read off
 * those classes' methods, so a kind added or dropped changes nothing here.
 */
internal fun reachedFactoryCallers(
    builder: Method,
    siblings: Iterable<Method>,
    resolve: (MethodReference) -> Method?,
): List<Method> {
    val direct = buttonFactoryCallers(builder, siblings)
    if (direct.isNotEmpty()) return direct
    return calledStatics(builder, siblings)
        .flatMap { it.instructions() }
        .filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        .mapNotNull { it.methodReference() }
        .distinctBy { it.toString() }
        .mapNotNull(resolve)
        .filter(::callsButtonFactory)
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
}

/** The button factory: a call taking the session first and four handlers. */
private fun isButtonFactory(reference: MethodReference): Boolean =
    reference.parameterTypes.count { it.toString() == FUNCTION1 } == 4 &&
        reference.parameterTypes.firstOrNull()?.toString() == FB_USER_SESSION

private fun callsButtonFactory(method: Method): Boolean =
    method.instructions().any { instruction -> instruction.methodReference()?.let(::isButtonFactory) == true }

private fun Instruction.methodReference() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.stringReference() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun MutableMethod.instructions(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun com.android.tools.smali.dexlib2.iface.Method.instructions(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()
