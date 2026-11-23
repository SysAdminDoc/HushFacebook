/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.notifications

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.resignedtrust.FB_USER_SESSION
import app.morphe.patches.facebook.misc.resignedtrust.sessionLookup
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

internal const val MARK_ALL_READ = "Lapp/morphe/extension/facebook/notifications/MarkAllRead;"

/** What Facebook's notifications controller logs as the ⋯ menu's Mark all as read runs (588). */
internal const val MARK_ALL_CLICK = "MAAR_CLICK"

private const val CONTEXT = "Landroid/content/Context;"
private const val OBJECT = "Ljava/lang/Object;"

/** The controller class and its method that marks every notification read. */
internal class MarkAllReadAnchors(val controller: ClassDef, val markAll: Method, val session: Method)

/**
 * Whether [method] is the controller's mark-all: public, not static, (FbUserSession, Context)V,
 * holding [MARK_ALL_CLICK].
 */
internal fun isMarkAll(method: Method): Boolean =
    AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(FB_USER_SESSION, CONTEXT) &&
        holdsString(method, MARK_ALL_CLICK)

/** Whether [classDef] is public with a public constructor taking nothing, so the fill can make one. */
internal fun madeFromNothing(classDef: ClassDef): Boolean =
    AccessFlags.PUBLIC.isSet(classDef.accessFlags) && classDef.methods.any {
        it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }

/**
 * The notifications controller the ⋯ menu's Mark all as read row runs: the one class with a
 * mark-all ([isMarkAll]) that can be made from nothing, and a Context-to-session lookup. Changes
 * nothing.
 */
internal fun BytecodePatchContext.findMarkAllReadAnchors(patch: String): MarkAllReadAnchors {
    val found = classDefByStrings(MARK_ALL_CLICK, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(::isMarkAll).map { classDef to it } }
        .filter { (classDef, _) -> madeFromNothing(classDef) }
    val (controller, markAll) = found.singleOrNull() ?: throw PatchException(
        "$patch: expected one public (FbUserSession, Context)V holding \"$MARK_ALL_CLICK\" in a class made " +
            "from nothing, found ${found.size}",
    )
    return MarkAllReadAnchors(controller, markAll, sessionLookup())
}

/**
 * Fills MarkAllRead's stubs. Each uses only its parameter registers: the mark-all stub builds the
 * controller in its spare parameter, since its empty body has no local to borrow.
 */
internal fun BytecodePatchContext.fillMarkAllRead(anchors: MarkAllReadAnchors) {
    val extension = mutableClassDefBy(MARK_ALL_READ)
    fun stub(name: String, parameters: List<String>, answer: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: throw PatchException("$MARK_ALL_READ has no static $answer $name(${parameters.joinToString("")})")

    val session = anchors.session
    stub("userSession", listOf(CONTEXT), OBJECT).addInstructions(
        0,
        """
            invoke-static/range { p0 .. p0 }, ${session.definingClass}->${session.name}($CONTEXT)$FB_USER_SESSION
            move-result-object p0
            return-object p0
        """,
    )
    val controller = anchors.controller.type
    stub("markAll", listOf(OBJECT, CONTEXT, OBJECT), "V").addInstructions(
        0,
        """
            check-cast p0, $FB_USER_SESSION
            new-instance p2, $controller
            invoke-direct { p2 }, $controller-><init>()V
            invoke-virtual { p2, p0, p1 }, $controller->${anchors.markAll.name}($FB_USER_SESSION$CONTEXT)V
            return-void
        """,
    )
    // Last, so a stub this build couldn't fill never runs.
    stub("patched", emptyList(), "Z").returnEarly(true)
}
