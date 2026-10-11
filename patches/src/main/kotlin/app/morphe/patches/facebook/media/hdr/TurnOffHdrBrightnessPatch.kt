/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.hdr

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
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
 * setting it up, and counts whether the phone's decoder said yes.
 *
 * In the default selection with its switch off: HDR is how Facebook means those videos to look,
 * and some people want it.
 */
@Suppress("unused")
val turnOffHdrBrightnessPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Turn off HDR brightness",
    description = "Stops HDR videos and photos from turning your screen up to full brightness, which can be " +
        "harsh in the dark. They keep their resolution. Starts off. Turn it on in Hushfacebook settings > " +
        "Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Both are found before either is changed, so a build missing one is left as it was.
        val windows = windowCallers()
        val surfaces = surfaceViewBuilders()
        hookWindowCalls(windows)
        hookSurfaceViews(surfaces)
        enableStatus("turnOffHdrBrightness")
    }
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
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
        val calls = classDef.methods.flatMap { method -> method.implementation?.instructions?.mapNotNull(::ownWindowCall).orEmpty() }
        if (calls.isEmpty()) return@classDefForEach
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
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
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
