/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.file

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.posttext.methodsWithString
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

/*
 * Facebook's download of a file attached to a post, read from 588 on 2026-11-25 (#590). The
 * obfuscated names are for reviewers; the code finds the place by its log line and a framework call.
 *
 * A tap on a file attachment (a PDF, a document, a text file) lands in one case of a click lambda
 * (588 `LX/0V0d;->onClick`). For an address that isn't http or https it logs NON_HTTP_FILE and stops.
 * Otherwise it makes a DownloadManager request from the address, gives it a title, both network
 * types and a notification, and enqueues it. It never calls a setDestination method, so
 * DownloadManager keeps the file in its own private cache, where no file manager can reach it.
 *
 * Right after the request's constructor, the request and the address go to the extension, which
 * points the request at the public Downloads folder while the switch is on. Facebook's other
 * DownloadManager users (Meta AI's Imagine saves) already pick a public folder and aren't touched.
 */

internal const val PATCH = "Save files to Downloads"

/** What Facebook logs, in the method making the request, when a file's address isn't http or https. */
internal const val NON_HTTP_FILE = "Tried to download a non http[s] file. Aborting..."

internal const val REQUEST_INIT = "Landroid/app/DownloadManager\$Request;-><init>(Landroid/net/Uri;)V"

internal const val TO_DOWNLOADS =
    "$EXTENSION_PACKAGE/download/FileDownloads;->toDownloads(Landroid/app/DownloadManager\$Request;Landroid/net/Uri;)V"

private val CONSTRUCTOR_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)

/**
 * Where the hook goes.
 *
 * @property init the index of the request's constructor call in [method].
 * @property request the register holding the request, made by that call.
 * @property uri the register holding the address it was made from.
 */
internal class FileRequest(val method: Method, val init: Int, val request: Int, val uri: Int)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The one method outside the extension logging [NON_HTTP_FILE], and in it the one call making a
 * DownloadManager request from an address. Refuses a build with none or more than one of either,
 * a call whose registers the hook can't name, and one whose next instruction something jumps to,
 * which would go around the hook. Reads only.
 */
internal fun BytecodePatchContext.findFileRequest(): FileRequest {
    val methods = methodsWithString(NON_HTTP_FILE)
    val method = methods.singleOrNull()
        ?: refuse("expected one method logging \"$NON_HTTP_FILE\", found ${methods.size}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val inits = code.indices.filter { index ->
        code[index].opcode in CONSTRUCTOR_CALLS &&
            ((code[index] as ReferenceInstruction).reference.toString() == REQUEST_INIT)
    }
    val init = inits.singleOrNull()
        ?: refuse("expected $where to make one DownloadManager request from an address, found ${inits.size}")
    val (request, uri) = when (val call = code[init]) {
        is FiveRegisterInstruction -> call.registerC to call.registerD
        is RegisterRangeInstruction -> call.startRegister to call.startRegister + 1
        else -> refuse("$where makes its DownloadManager request in an unknown form")
    }
    // invoke-static names registers up to v15, or a run of two in its range form.
    if ((request > 15 || uri > 15) && uri != request + 1) {
        refuse("$where holds its request and address in v$request and v$uri, which no call can name")
    }
    if (init + 1 in branchTargets(code)) refuse("$where jumps straight past where its DownloadManager request is made")
    return FileRequest(method, init, request, uri)
}

/** Right after [file]'s request is made, hands it and its address to [TO_DOWNLOADS]. */
internal fun BytecodePatchContext.hookFileRequest(file: FileRequest) {
    val call = if (file.request <= 15 && file.uri <= 15) {
        "invoke-static { v${file.request}, v${file.uri} }, $TO_DOWNLOADS"
    } else {
        "invoke-static/range { v${file.request} .. v${file.uri} }, $TO_DOWNLOADS"
    }
    mutableClassDefBy(file.method.definingClass).findMutableMethodOf(file.method).addInstruction(file.init + 1, call)
}

@Suppress("unused")
val saveFilesToDownloadsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Save files to Downloads",
    description = "Puts a PDF, document or other file you download from a post in your phone's Downloads folder, " +
        "where a file manager can open it. Facebook kept those in a folder no file manager could reach. On by " +
        "default. Turn it off in Hushfacebook settings > Downloads.",
) {
    category("Downloads")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val file = findFileRequest()
        requireStatusMethod("fileDownloads")
        hookFileRequest(file)
        enableStatus("fileDownloads")
    }
}
