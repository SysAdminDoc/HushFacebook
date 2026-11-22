/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Where a shared post's link is chosen, read from 581 on 2026-10-09 for #98. The obfuscated names
 * here are for reviewers; the code finds each by a kept string and its shape.
 *
 * As a post's share sheet opens, Facebook's LinkSharingController (581 LX/aIP, the one class that
 * logs "fetch_wrapped_url_send_request") asks the server for a facebook.com/share/ link for it,
 * GraphQL share_url_wrapper.wrapped_url, made new for that share, and keeps it by the post's id.
 * One static method (581 LX/aAk;->A00(FbUserSession, FeedProps)String) then builds the link every
 * share of the post hands out: it reads the post's own address first, the story's wwwURL or else
 * its url, asks the controller's cache through its one (String, String)String method, and returns
 * the /share/ link when the cache has one and the post's own address when it doesn't. The share
 * sheet's Copy link (581 LX/YIQ, through LX/ah8;->A05), the Copy link behind a reel's share button
 * (LX/Ce8 through LX/aPS;->A03), Send in WhatsApp and the share sheet's other actions all take
 * their link from it.
 *
 * The hook goes right after the cache answers. It hands the extension that answer and the register
 * holding the post's own address, the one the method returns when the cache has nothing, so with
 * the switch on the method returns what Facebook gives without a /share/ link. The cache itself is
 * left alone, so nothing else that reads it changes.
 */

/** The extension class the hook asks. */
internal const val OWN_POST_LINK = "$EXTENSION_PACKAGE/misc/OwnPostLink;"
internal const val SHARE_LINK = "$OWN_POST_LINK->shareLink(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

/** A log string only LinkSharingController holds, in its fetch of a /share/ link. */
internal const val LINK_SHARING_ANCHOR = "fetch_wrapped_url_send_request"

private const val STRING = "Ljava/lang/String;"

private const val PATCH = "Sanitize sharing links (post's own link)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The link a share of a post hands out answers through the extension's OwnPostLink. Nameless, so
 * Sanitize sharing links carries it and its fixture test runs it on its own. Refuses unless the
 * controller, its cache read and the one method building a post's share link are each found once.
 */
internal val ownPostLinkPatch = patch {
    dependsOn(facebookExtensionPatch)

    executeBytecode {
        hookOwnPostLink()
    }
}

/** Where the cache's answer goes through the extension: the index after it lands, its register, and the post's own address. */
internal data class ShareLinkHook(val insertAt: Int, val shareRegister: Int, val ownRegister: Int)

internal fun BytecodePatchContext.hookOwnPostLink() {
    val controllers = classDefByStrings(LINK_SHARING_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val controller = controllers.singleOrNull()
        ?: refuse("expected one LinkSharingController holding \"$LINK_SHARING_ANCHOR\", found ${controllers.size}")
    val read = wrappedLinkRead(controller.methods)?.descriptor()
        ?: refuse("${controller.type} has no one (String, String)String read of the /share/ links it keeps")

    val builders = mutableListOf<Method>()
    // A builder calls the read, which names its class.
    classDefForEachReferencing(types = listOf(read.substringBefore("->"))) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filter { isShareLinkShape(it) && asks(it, read) }.forEach { builders += it }
    }
    val builder = builders.singleOrNull()
        ?: refuse("expected one static (FbUserSession, props)String method asking $read, found ${builders.size}")
    val hook = shareLinkHook(builder, read)
        ?: refuse("${builder.definingClass}->${builder.name} doesn't return the cached link or one other address")
    if (hook.shareRegister > 15 || hook.ownRegister > 15) {
        refuse("${builder.definingClass}->${builder.name} keeps its links above v15")
    }

    mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder).addInstructions(
        hook.insertAt,
        """
            invoke-static { v${hook.shareRegister}, v${hook.ownRegister} }, $SHARE_LINK
            move-result-object v${hook.shareRegister}
        """,
    )
}

/** LinkSharingController's read of the /share/ link it keeps for a post: its one instance (String, String)String method. */
internal fun <T : Method> wrappedLinkRead(methods: Iterable<T>): T? = methods.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == STRING &&
        it.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING)
}

/** Whether [method] has the share link builder's shape: static, taking the session and the post's props, answering a String. */
internal fun isShareLinkShape(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == STRING &&
        method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == FB_USER_SESSION

/** Whether [method] calls the method [descriptor] names. */
internal fun asks(method: Method, descriptor: String): Boolean =
    method.implementation?.instructions?.any { it.called() == descriptor } == true

private fun Instruction.called() =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.descriptor()

/**
 * The hook in [builder]: right after the move-result of its one call to [read], with the register
 * the cache's answer lands in and the one other register the builder returns. Nothing after the
 * cache answers may set that other register, so it holds the address the builder returns when the
 * cache has nothing. Null when the builder isn't that shape.
 */
internal fun shareLinkHook(builder: Method, read: String): ShareLinkHook? {
    val code = builder.implementation?.instructions?.toList() ?: return null
    val call = code.indices.filter { code[it].called() == read }.singleOrNull() ?: return null
    val result = code.getOrNull(call + 1) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val share = (result as OneRegisterInstruction).registerA
    val returned = code.filter { it.opcode == Opcode.RETURN_OBJECT }.map { (it as OneRegisterInstruction).registerA }.toSet()
    if (share !in returned) return null
    val own = (returned - share).singleOrNull() ?: return null
    if (code.drop(call + 2).any { writesRegister(it, own) }) return null
    return ShareLinkHook(call + 2, share, own)
}
