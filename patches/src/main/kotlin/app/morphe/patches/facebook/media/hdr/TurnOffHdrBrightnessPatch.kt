/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.hdr

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Turn off HDR brightness"
internal const val HDR_BRIGHTNESS = "$EXTENSION_PACKAGE/media/HdrBrightness;"
internal const val WINDOW = "Landroid/view/Window;"
internal const val SURFACE_VIEW = "Landroid/view/SurfaceView;"
internal const val SET_COLOR_MODE = "$WINDOW->setColorMode(I)V"
internal const val SET_HDR_HEADROOM = "$WINDOW->setDesiredHdrHeadroom(F)V"
internal const val OWN_SET_COLOR_MODE = "$HDR_BRIGHTNESS->setColorMode(${WINDOW}I)V"
internal const val OWN_SET_HDR_HEADROOM = "$HDR_BRIGHTNESS->setDesiredHdrHeadroom(${WINDOW}F)V"
internal const val OWN_SURFACE_BUILT = "$HDR_BRIGHTNESS->surfaceBuilt($SURFACE_VIEW)V"
internal const val DISPLAY = "Landroid/view/Display;"
internal const val DISPLAY_MODE = "Landroid/view/Display\$Mode;"
internal const val HDR_CAPABILITIES = "Landroid/view/Display\$HdrCapabilities;"
internal const val IS_HDR = "$DISPLAY->isHdr()Z"
internal const val MODE_HDR_TYPES = "$DISPLAY_MODE->getSupportedHdrTypes()[I"
internal const val CAPABILITY_HDR_TYPES = "$HDR_CAPABILITIES->getSupportedHdrTypes()[I"
internal const val OWN_IS_HDR = "$HDR_BRIGHTNESS->isHdr($DISPLAY)Z"
internal const val OWN_MODE_HDR_TYPES = "$HDR_BRIGHTNESS->getSupportedHdrTypes($DISPLAY_MODE)[I"
internal const val OWN_CAPABILITY_HDR_TYPES = "$HDR_BRIGHTNESS->getSupportedHdrTypes($HDR_CAPABILITIES)[I"
internal const val MEDIA_CODEC = "Landroid/media/MediaCodec;"
private const val CONFIGURE_SHAPE = "Landroid/media/MediaFormat;Landroid/view/Surface;Landroid/media/MediaCrypto;I"
internal const val CODEC_CONFIGURE = "$MEDIA_CODEC->configure(${CONFIGURE_SHAPE})V"
internal const val OWN_CODEC_CONFIGURE = "$HDR_BRIGHTNESS->configure($MEDIA_CODEC${CONFIGURE_SHAPE})V"

/**
 * Keeps HDR video and photos from turning the screen up to full brightness. Facebook asks Android
 * for an HDR window as the feed, a story or a reel comes to the front, and on Android 15 for as
 * much headroom over the screen's usual white as its server says; Android then turns the panel up
 * for the HDR parts. Each of those calls goes through the extension's HdrBrightness, which asks
 * for the default colour mode and no headroom instead while the switch is on, so Android draws
 * the same video tone-mapped into the usual range. A SurfaceView doesn't follow its window's
 * mode, so each one Facebook builds goes to the extension as well, which on Android 15 asks it
 * for no headroom. Each of Facebook's questions of what the screen shows (Display.isHdr and the
 * two getSupportedHdrTypes) goes there too, and the screen answers as one with no HDR, so before
 * Android 15 Facebook doesn't lift ordinary videos into HDR and a Dolby Vision video takes its
 * fallback track. Display.isHdrSdrRatioAvailable stays Facebook's: the AV1 decoder backs its
 * own HDR lift off only when it can read a low ratio.
 *
 * On Android 14 and older nothing above reaches a video Facebook draws on its own surface (#93:
 * a VP9 HLG reel on 582 went to the panel as HDR with none of those calls made), so each of
 * Facebook's calls of MediaCodec.configure goes to the extension too, which asks an HDR video
 * decoder drawing to a surface for the usual range (Android 12's color-transfer-request) before
 * setting it up, and counts whether the phone's decoder said yes. An HLG video is also handed over
 * naming the usual transfer, its BT.2020 colours kept: HLG shows as it is on an ordinary screen,
 * and a VP9 stream, which names no transfer itself, then skips the decoder maker's tone map that
 * left #93's reels bright and yellow. A Qualcomm Codec2 decoder (c2.qti.) only gets the HLG
 * relabel: on a Snapdragon Samsung phone the request starts a vendor tone-map filter that aborts
 * the media codec service on HDR reels.
 *
 * A second switch, Play SDR versions of HDR videos (#66), goes one step earlier: each DASH manifest
 * string Facebook parses goes to the extension's SdrRenditions first, which takes out an HDR
 * rendition when the manifest also has an SDR one at least as tall, so the player never picks it.
 *
 * A third, Fix washed-out video colors (#308), is for phones whose display hardware shows a
 * video's own layer pale until something covers it, which Disable HW overlays fixes: each answer
 * of Facebook's must-use-a-SurfaceView decision goes to the extension's TextureVideo, which says
 * no, and so does each read of the player's own TextureView preference behind that no, which says
 * yes. The player then draws into a TextureView, composed by the GPU with the rest of the screen.
 * The map is in TextureVideo.kt. Tab bar at the bottom's See-through bars uses both for every
 * player origin but the reels viewer's, so a feed video under its frosted bars can be blurred, and
 * reels keep their SurfaceView (#250).
 *
 * In the default selection with its switch off: HDR is how Facebook means those videos to look,
 * and some people want it.
 */
@Suppress("unused")
val turnOffHdrBrightnessPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Turn off HDR brightness",
    description = "Stops HDR videos and photos from turning your screen up to full brightness, which can be " +
        "harsh in the dark. They keep their resolution. A second switch fixes pale, washed-out reels on " +
        "phones that show them that way. Its switches start off. Turn them on in Hushfacebook settings > Video and audio.",
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // All are found before any is changed, so a build missing one is left as it was.
        val windows = windowCallers()
        val surfaces = surfaceViewBuilders()
        val manifest = manifestParse()
        val texture = textureHooks()
        // First: its sites are indices, and the hooks below can add code to the same methods.
        hookTextureVideo(texture)
        hookWindowCalls(windows)
        hookSurfaceViews(surfaces)
        hookManifestParse(manifest)
        enableStatus("turnOffHdrBrightness")
    }
}

/** The tag Facebook's DASH manifest helper logs under, a kept string. */
internal const val MANIFEST_LOG_TAG = "DashManifestHelper2"
internal const val MANIFEST_PARSE_FAILED = "Failed to parse manifest: %s"
private const val URI = "Landroid/net/Uri;"
private const val STRING = "Ljava/lang/String;"
internal const val OWN_MANIFEST = "$EXTENSION_PACKAGE/media/SdrRenditions;->manifest($STRING)$STRING"

/**
 * Whether [method] is Facebook's parse of a DASH manifest string: static, (Uri, parser, String)
 * answering the parsed manifest, logging under [MANIFEST_LOG_TAG] and naming a manifest it
 * couldn't parse. Every string manifest the player, prefetch and offline saves read goes
 * through it (582, 2026-10-14).
 */
internal fun isManifestParse(method: Method): Boolean {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    return AccessFlags.STATIC.isSet(method.accessFlags) && parameters.size == 3 && parameters[0] == URI &&
        parameters[1].startsWith("L") && parameters[2] == STRING && method.returnType.startsWith("L") &&
        holdsString(method, MANIFEST_LOG_TAG) && holdsString(method, MANIFEST_PARSE_FAILED)
}

/** The one manifest parse ([isManifestParse]). Throws, naming how many it found, when it isn't one. */
internal fun BytecodePatchContext.manifestParse(): Method {
    val found = classDefByStrings(MANIFEST_PARSE_FAILED, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter(::isManifestParse) }
        .distinctBy { "${it.definingClass}->${it.name}" }
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one DASH manifest parse logging \"$MANIFEST_LOG_TAG\", found ${found.size}")
}

/**
 * Hands the manifest string to the extension before [parse] reads it, and parses what comes back
 * (Play SDR versions of HDR videos). By range, so any register fits, at index 0, where nothing
 * in the method jumps.
 */
internal fun BytecodePatchContext.hookManifestParse(parse: Method) {
    mutableClassDefBy(parse.definingClass).findMutableMethodOf(parse).addInstructions(
        0,
        """
            invoke-static/range { p2 .. p2 }, $OWN_MANIFEST
            move-result-object p2
        """,
    )
}

/**
 * The extension method taking the place of [instruction], when it's one of Facebook's calls of
 * [SET_COLOR_MODE] or [SET_HDR_HEADROOM], one of its questions of the screen ([IS_HDR],
 * [MODE_HDR_TYPES], [CAPABILITY_HDR_TYPES]), or a decoder's set-up ([CODEC_CONFIGURE]). Null for
 * anything else.
 */
internal fun ownWindowCall(instruction: Instruction): String? {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    return when ((instruction as ReferenceInstruction).reference.toString()) {
        SET_COLOR_MODE -> OWN_SET_COLOR_MODE
        SET_HDR_HEADROOM -> OWN_SET_HDR_HEADROOM
        IS_HDR -> OWN_IS_HDR
        MODE_HDR_TYPES -> OWN_MODE_HDR_TYPES
        CAPABILITY_HDR_TYPES -> OWN_CAPABILITY_HDR_TYPES
        CODEC_CONFIGURE -> OWN_CODEC_CONFIGURE
        else -> null
    }
}

/**
 * The classes outside the extension that make one of Facebook's window calls, screen questions or
 * decoder set-ups ([ownWindowCall]). Throws when none of them sets a colour mode: Facebook asks
 * for its HDR window somewhere else then. The fixture test holds each build's decoder set-ups.
 */
internal fun BytecodePatchContext.windowCallers(): Set<String> {
    val owners = mutableSetOf<String>()
    var colourModes = 0
    val names = listOf(SET_COLOR_MODE, SET_HDR_HEADROOM, IS_HDR, MODE_HDR_TYPES, CAPABILITY_HDR_TYPES, CODEC_CONFIGURE)
        .map { it.substringAfter("->").substringBefore('(') }
    classDefForEachReferencing(names = names) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        val calls = classDef.methods.flatMap { method -> method.implementation?.instructions?.mapNotNull(::ownWindowCall).orEmpty() }
        if (calls.isEmpty()) return@classDefForEachReferencing
        owners += classDef.type
        colourModes += calls.count { it == OWN_SET_COLOR_MODE }
    }
    if (colourModes == 0) throw PatchException("$PATCH: found no call of Window.setColorMode outside the extension")
    return owners
}

/**
 * Sends each of Facebook's calls of [SET_COLOR_MODE] and [SET_HDR_HEADROOM] and each screen
 * question in [owners] to the extension's method of the same name, which takes the window (or the
 * display, mode or capabilities) first and makes the call itself. A question's move-result stays
 * where it was and reads the extension's answer of the same type.
 * The call keeps its registers in their order and its place, so a jump to it or a try block's
 * edge on it stays where it was. Answers how many calls it sent.
 */
internal fun BytecodePatchContext.hookWindowCalls(owners: Set<String> = windowCallers()): Int =
    owners.sumOf { type -> mutableClassDefByOrNull(type)?.methods?.sumOf { it.sendWindowCalls() } ?: 0 }

/** Sends each window call of this method to the extension, last first. Answers how many. */
internal fun MutableMethod.sendWindowCalls(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .mapNotNull { (index, instruction) -> ownWindowCall(instruction)?.let { Triple(index, instruction, it) } }
    sites.asReversed().forEach { (index, instruction, own) ->
        val arguments = when (instruction) {
            is RegisterRangeInstruction ->
                "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1} }"
            is FiveRegisterInstruction -> "invoke-static { " + listOf(
                instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
            ).take(instruction.registerCount).joinToString { "v$it" } + " }"
            else -> throw PatchException("$PATCH: $definingClass->$name calls the window in an unexpected form")
        }
        replaceInstruction(index, "$arguments, $own")
    }
    return sites.size
}

/**
 * The register holding the SurfaceView [instruction] builds, when it's the constructor call of
 * Android's SurfaceView, as `new` or as the super call of a view of Facebook's own. Null for
 * anything else.
 */
internal fun builtSurfaceView(instruction: Instruction): Int? {
    if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    val called = (instruction as ReferenceInstruction).reference as? MethodReference ?: return null
    if (called.name != "<init>" || called.definingClass != SURFACE_VIEW) return null
    return when (instruction) {
        is RegisterRangeInstruction -> instruction.startRegister
        is FiveRegisterInstruction -> instruction.registerC
        else -> null
    }
}

/**
 * The classes outside the extension that build a SurfaceView ([builtSurfaceView]). Throws when
 * there are none: Facebook draws its video somewhere else then.
 */
internal fun BytecodePatchContext.surfaceViewBuilders(): Set<String> {
    val owners = mutableSetOf<String>()
    classDefForEachReferencing(types = listOf(SURFACE_VIEW)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (classDef.methods.any { method -> method.implementation?.instructions?.any { builtSurfaceView(it) != null } == true }) {
            owners += classDef.type
        }
    }
    if (owners.isEmpty()) throw PatchException("$PATCH: found no SurfaceView Facebook builds")
    return owners
}

/**
 * Each SurfaceView the classes in [owners] build goes to the extension right after its
 * constructor, by range so any register fits, so a jump to what followed still skips it. A view
 * of Facebook's own is handed over from its super call, once whatever builds it. Answers how many
 * it hooked.
 */
internal fun BytecodePatchContext.hookSurfaceViews(owners: Set<String> = surfaceViewBuilders()): Int =
    owners.sumOf { type -> mutableClassDefByOrNull(type)?.methods?.sumOf { it.sendSurfaceViews() } ?: 0 }

/** Hands each SurfaceView this method builds to the extension, last first. Answers how many. */
internal fun MutableMethod.sendSurfaceViews(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .mapNotNull { (index, instruction) -> builtSurfaceView(instruction)?.let { index to it } }
    // The register goes in as plain digits: a format's would be the phone's own, which aren't always ASCII.
    sites.asReversed().forEach { (index, register) ->
        addInstruction(index + 1, "invoke-static/range { v$register .. v$register }, $OWN_SURFACE_BUILT")
    }
    return sites.size
}
