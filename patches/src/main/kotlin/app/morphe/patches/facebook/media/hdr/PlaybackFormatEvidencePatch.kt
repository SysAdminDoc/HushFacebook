/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.hdr

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.MAIN_TAB_ACTIVITY
import app.morphe.patches.facebook.misc.settings.compressedCodeRefusal
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EVIDENCE = "Playback format evidence"

/** dav1d's adapter, which keeps its name in every build. */
internal const val DAV1D_ADAPTER = "Lexoplayer2/av1/src/Dav1dMediaCodecAdapter;"
internal const val FORMAT_EVIDENCE = "$EXTENSION_PACKAGE/media/PlaybackFormatEvidence;"
internal const val MEDIA_FORMAT = "Landroid/media/MediaFormat;"
private val CONFIGURE_PARAMETERS = listOf(MEDIA_FORMAT, "Landroid/view/Surface;", "Landroid/media/MediaCrypto;", "I", "Ljava/lang/Object;")

/** Whether [method] is a playback adapter's configure: (MediaFormat, Surface, MediaCrypto, int, Object)V, not static. */
internal fun isPlaybackConfigure(method: Method): Boolean = method.name == "configure" && method.returnType == "V" &&
    method.parameterTypes.map(CharSequence::toString) == CONFIGURE_PARAMETERS && !AccessFlags.STATIC.isSet(method.accessFlags)

/** Whether [method] is a playback adapter's getOutputFormat: ()MediaFormat, not static. */
internal fun isPlaybackOutput(method: Method): Boolean = method.name == "getOutputFormat" && method.returnType == MEDIA_FORMAT &&
    method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags)

/** How many times this method calls [owner]'s [name]. */
private fun Method.calls(owner: String, name: String): Int = implementation?.instructions?.count {
    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
    call?.definingClass == owner && call.name == name
} ?: 0

/**
 * Whether [candidate] is the adapter for Android's own decoder on the playback interface
 * [adapterInterface]: it implements it, and its configure and getOutputFormat each call
 * MediaCodec's once. A wrapper that hands both on to another adapter calls neither, and an
 * encoder isn't on that interface. A configure Turn off HDR brightness already sent to the
 * extension still counts, whichever patch runs first.
 */
internal fun isPlatformPlaybackAdapter(candidate: ClassDef, adapterInterface: String): Boolean =
    adapterInterface in candidate.interfaces &&
        candidate.methods.count { isPlaybackConfigure(it) && it.calls(MEDIA_CODEC, "configure") + it.calls(HDR_BRIGHTNESS, "configure") == 1 } == 1 &&
        candidate.methods.count { isPlaybackOutput(it) && it.calls(MEDIA_CODEC, "getOutputFormat") == 1 } == 1

/** One decoder's configure and getOutputFormat, and the [route] its lines name ("platform" or "dav1d"). */
internal data class PlaybackEvidenceAnchor(val owner: ClassDef, val configure: Method, val output: Method, val route: String)

/**
 * Both decoders' anchors, Android's first. dav1d's adapter keeps its name. Its playback interface
 * is the one of its interfaces with a configure of that shape and a setDav1dPresenter, and
 * Android's decoder is the one adapter on that interface that calls MediaCodec itself
 * ([isPlatformPlaybackAdapter]). Throws, naming what's missing, before anything changes.
 */
internal fun BytecodePatchContext.findPlaybackEvidenceAnchors(): List<PlaybackEvidenceAnchor> {
    fun refuse(message: String): Nothing = throw PatchException("$EVIDENCE: $message")
    val dav1d = classDefByOrNull(DAV1D_ADAPTER) ?: refuse("no kept Dav1d playback adapter")
    val configure = dav1d.methods.singleOrNull { isPlaybackConfigure(it) && it.implementation != null }
        ?: refuse("Dav1d has no unique configure boundary")
    val output = dav1d.methods.singleOrNull { isPlaybackOutput(it) && it.implementation != null }
        ?: refuse("Dav1d has no unique output-format boundary")
    val interfaces = dav1d.interfaces.mapNotNull { classDefByOrNull(it) }.filter { candidate ->
        candidate.methods.count(::isPlaybackConfigure) == 1 && candidate.methods.any { it.name == "setDav1dPresenter" }
    }
    val adapterInterface = interfaces.singleOrNull()?.type ?: refuse("Dav1d has no unique playback configure interface")
    val platforms = mutableListOf<ClassDef>()
    classDefForEach { candidate ->
        if (isPlatformPlaybackAdapter(candidate, adapterInterface)) platforms += candidate
    }
    val platform = platforms.singleOrNull() ?: refuse("expected one linked platform adapter, found ${platforms.size}")
    val anchors = listOf(
        PlaybackEvidenceAnchor(platform, platform.methods.single(::isPlaybackConfigure), platform.methods.single(::isPlaybackOutput), "platform"),
        PlaybackEvidenceAnchor(dav1d, configure, output, "dav1d"),
    )
    // Checked here rather than while hooking, so a refusal leaves both decoders as they were.
    anchors.firstOrNull { anchor -> anchor.output.implementation!!.instructions.none { it.opcode == Opcode.RETURN_OBJECT } }
        ?.let { refuse("the ${it.route} output-format boundary never returns") }
    return anchors
}

/**
 * Hands each decoder's formats to the extension: configure's at index 0, and getOutputFormat's
 * right before each return-object, on that return's own label, so a jump to the return goes
 * through it too. Both by range, so any register fits, and both read only: the argument and the
 * returned format stay in their registers, nothing else changes and no register is added.
 */
internal fun BytecodePatchContext.applyPlaybackEvidenceAnchors(anchors: List<PlaybackEvidenceAnchor>) {
    for (anchor in anchors) {
        val owner = mutableClassDefBy(anchor.owner.type)
        owner.findMutableMethodOf(anchor.configure).addInstruction(
            0, "invoke-static/range { p1 .. p1 }, $FORMAT_EVIDENCE->${anchor.route}Input($MEDIA_FORMAT)V",
        )
        val output = owner.findMutableMethodOf(anchor.output)
        val returns = output.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        returns.asReversed().forEach { (index, instruction) ->
            val register = (instruction as OneRegisterInstruction).registerA
            output.addInstructionsAtControlFlowLabel(
                index, "invoke-static/range { v$register .. v$register }, $FORMAT_EVIDENCE->${anchor.route}Output($MEDIA_FORMAT)V",
            )
        }
    }
}

/**
 * With Debug logging on, the diagnostic report names the video formats Facebook's two decoders
 * are set up with and hand back, so it says whether a video was served in HDR (see the
 * extension's PlaybackFormatEvidence). No switch and no name of its own: Hushfacebook settings
 * brings it into every build, whichever patches were picked, Turn off HDR brightness or not. It
 * reads the formats and changes nothing about them. A build missing one of its anchors goes on
 * without it, with a warning: a throw here would stop Hushfacebook settings, and every patch with it.
 */
internal val playbackFormatEvidencePatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        // Dependencies run before Settings. Its refusal still has to be what explains why an
        // opaque Android 9 build can't be patched, so nothing is looked up in one.
        if (compressedCodeRefusal(classDefByOrNull(FACEBOOK_APPLICATION) != null, classDefByOrNull(MAIN_TAB_ACTIVITY)) != null) {
            return@execute
        }
        val anchors = try {
            findPlaybackEvidenceAnchors()
        } catch (missing: Exception) {
            // A missing anchor is a PatchException with its reason; anything else is a bug, named as one.
            val why = if (missing is PatchException) missing.message else "$EVIDENCE: ${missing::class.java.name}: ${missing.message}"
            patchLog.warning("$why. Debug reports go on without the video formats.")
            return@execute
        }
        applyPlaybackEvidenceAnchors(anchors)
    }
}
