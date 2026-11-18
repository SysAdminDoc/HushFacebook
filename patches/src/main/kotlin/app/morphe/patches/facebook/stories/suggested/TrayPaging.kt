/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.shared.readsMobileConfig
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Home's tray pages forward on its own when the filters leave it short (issue #317). While the
 * tray has a next page its row ends in three gray placeholder tiles. On 585 every tile of the tray
 * reports coming into view to one handler (LX/02fs;, the only LX/0hx9;), whose I0l(bucket id,
 * position) the placeholder tile (LX/04p7;) calls with "placeholder_bucket_id". For a placeholder,
 * I0l returns while a fetch is running, then loads the tray's next page only when a MobileConfig
 * flag is on (0x101029900041f52), which it isn't on the S22's account. Otherwise the tray loads
 * its next page when one of its last buckets comes into view. A row the filters cut to one bucket
 * shows that bucket once, before the tray knows it has a next page, and the placeholders after it,
 * so the next page never loaded and the placeholders stayed until a drag (S22, 2026-10-31).
 *
 * The flag's read answers through the extension, which lets it through while the filters are
 * leaving buckets out, so a placeholder coming into view loads the next page the way it does for
 * accounts with the flag on. Facebook's own checks still run: nothing loads while a fetch is
 * running or when the tray has no next page. A page whose buckets are all filtered out changes
 * nothing in view, so no placeholder comes into view again. I0l hands the extension each tile
 * coming into view, the placeholder tile's leaving view (IlM) tells it so, and the handler is made a
 * StoriesTrayPaging.Pager whose method shows I0l a placeholder again after such a page settles.
 *
 * An earlier version made the tray's scroll listener (LX/0CYa;) the pager. That listener belongs to
 * a tray Home doesn't draw on 585 (Home's tray is LX/02ft;, whose listener LX/02g0; never pages),
 * so it never ran.
 */

internal const val TRAY_PAGING = "$EXTENSION_PACKAGE/stories/StoriesTrayPaging;"
internal const val TRAY_PAGER = "$EXTENSION_PACKAGE/stories/StoriesTrayPaging\$Pager;"
internal const val TILE_SHOWN = "$TRAY_PAGING->tileShown(Ljava/lang/Object;Ljava/lang/String;I)V"
internal const val PLACEHOLDER_HIDDEN = "$TRAY_PAGING->placeholderHidden()V"
internal const val PLACEHOLDER_PAGES = "$TRAY_PAGING->placeholderPages(Z)Z"
internal const val SHOW_PLACEHOLDER_METHOD = "hushShowPlaceholder"

/** The bucket id the tray's placeholder tiles hand the tile handler. */
internal const val PLACEHOLDER_BUCKET = "placeholder_bucket_id"

/**
 * The tray's tile [handler] and its [shown] method, the index of the move-result of the flag the
 * placeholder path checks before loading, and the placeholder tile's [hidden] method.
 */
internal class TrayPagingAnchors(
    val handler: String,
    val shown: Method,
    val flagResult: Int,
    val hidden: Method,
)

/** Whether [method] is the tile handler's: (String, int) to void, holding [PLACEHOLDER_BUCKET]. */
internal fun isTileShown(method: Method): Boolean =
    method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "I") &&
        trayBody(method).any { it.stringReference() == PLACEHOLDER_BUCKET }

/**
 * Reads the tray's tile handler: the one class outside the extension with an [isTileShown] method.
 * In it, the register holding whether the bucket is [PLACEHOLDER_BUCKET], the first branch that
 * skips the placeholder path when it isn't, and the first MobileConfig boolean read after it, which
 * 588 makes through a static helper taking the config as an Object.
 * Then the placeholder tile: the class calling the handler's interface method with
 * [PLACEHOLDER_BUCKET], and its other method holding the string, which hands it on as a String.
 */
internal fun BytecodePatchContext.trayPagingAnchors(): TrayPagingAnchors {
    val holders = classDefByStrings(PLACEHOLDER_BUCKET, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    val handler = holders.filter { classDef -> classDef.methods.any(::isTileShown) }
        .singleOrPatchException("$PATCH: the tray's tile handler holding \"$PLACEHOLDER_BUCKET\"")
    val shown = handler.methods.single(::isTileShown)
    val body = trayBody(shown)

    val compare = body.indexOfFirst { it.stringReference() == PLACEHOLDER_BUCKET }
    val equals = body.drop(compare).indexOfFirst { it.methodRef()?.name == "equals" } + compare
    val isPlaceholder = (body.getOrNull(equals + 1) as? OneRegisterInstruction)
        ?.takeIf { body[equals + 1].opcode == Opcode.MOVE_RESULT }?.registerA
        ?: throw PatchException("$PATCH: ${handler.type}->${shown.name} doesn't keep whether the tile is a placeholder")
    val skip = (equals + 2 until body.size).firstOrNull { index ->
        body[index].opcode == Opcode.IF_EQZ && (body[index] as OneRegisterInstruction).registerA == isPlaceholder
    } ?: throw PatchException("$PATCH: ${handler.type}->${shown.name} has no placeholder path")
    val read = (skip + 1 until body.size).firstOrNull { index ->
        readsMobileConfig(body[index], "Z") { classDefByOrNull(it) }
    } ?: throw PatchException("$PATCH: ${handler.type}->${shown.name}'s placeholder path reads no MobileConfig flag")
    if (body.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException("$PATCH: ${handler.type}->${shown.name} doesn't keep its placeholder flag")
    }

    val tileInterface = handler.interfaces.singleOrNull()
        ?: throw PatchException("$PATCH: ${handler.type} doesn't answer one tile interface")
    val tile = holders.filter { classDef ->
        classDef.methods.any { method ->
            trayBody(method).any { it.methodRef()?.let { call -> call.definingClass == tileInterface && call.name == shown.name } == true }
        }
    }.singleOrPatchException("$PATCH: the placeholder tile calling $tileInterface->${shown.name}")
    val hidden = tile.methods.filter { method ->
        val calls = trayBody(method).mapNotNull { it.methodRef() }
        trayBody(method).any { it.stringReference() == PLACEHOLDER_BUCKET } &&
            calls.none { it.definingClass == tileInterface } &&
            calls.any { it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/String;") && it.returnType == "V" }
    }.singleOrPatchException("$PATCH: ${tile.type}'s method for a placeholder leaving view")

    return TrayPagingAnchors(handler.type, shown, read + 1, hidden)
}

/**
 * Makes the tile handler a StoriesTrayPaging.Pager whose method shows its own tile method a
 * placeholder, hands the extension every tile coming into view first thing in that method, lets
 * its placeholder flag answer through the extension, and tells the extension first thing when a
 * placeholder leaves view.
 */
internal fun BytecodePatchContext.pageShortTrays(anchors: TrayPagingAnchors) {
    val handler = mutableClassDefBy(anchors.handler)
    if (TRAY_PAGER !in handler.interfaces) handler.interfaces.add(TRAY_PAGER)
    val shown = handler.methods.single { it.name == anchors.shown.name && isTileShown(it) }
    if (handler.methods.none { it.name == SHOW_PLACEHOLDER_METHOD }) {
        handler.methods.add(
            ImmutableMethod(
                handler.type, SHOW_PLACEHOLDER_METHOD, listOf(ImmutableMethodParameter("I", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(0, """
                    const-string v0, "$PLACEHOLDER_BUCKET"
                    invoke-virtual { p0, v0, p1 }, ${handler.type}->${shown.name}(Ljava/lang/String;I)V
                    return-void
                """)
            },
        )
    }
    val flag = (trayBody(shown)[anchors.flagResult] as OneRegisterInstruction).registerA
    shown.addInstructions(anchors.flagResult + 1, """
        invoke-static/range { v$flag .. v$flag }, $PLACEHOLDER_PAGES
        move-result v$flag
    """)
    shown.addInstructions(0, "invoke-static/range { p0 .. p2 }, $TILE_SHOWN")

    val hiddenParameters = anchors.hidden.parameterTypes.map { it.toString() }
    mutableClassDefBy(anchors.hidden.definingClass).methods.single {
        it.name == anchors.hidden.name && it.parameterTypes.map { p -> p.toString() } == hiddenParameters
    }.addInstructions(0, "invoke-static {}, $PLACEHOLDER_HIDDEN")
}

private fun trayBody(method: Method): List<Instruction> = method.implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.stringReference(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
