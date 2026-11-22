/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.whoshared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.downloads.video.MENU
import app.morphe.patches.facebook.downloads.video.VIEW
import app.morphe.patches.facebook.downloads.video.callAfterMenuFill
import app.morphe.patches.facebook.downloads.video.postMenu
import app.morphe.patches.facebook.downloads.video.postMenuHelper
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "See who shared"

internal const val CONTEXT = "Landroid/content/Context;"
internal const val OBJECT = "Ljava/lang/Object;"
internal const val STRING = "Ljava/lang/String;"

internal const val WHO_SHARED = "Lapp/morphe/extension/facebook/feed/WhoShared;"

/** The extension call that adds the row to a post's menu once Facebook has filled it. */
internal const val ADD_WHO_SHARED_ITEM = "$WHO_SHARED->add($MENU$VIEW${OBJECT}I)V"

/** The helper this patch adds to the class that builds every post menu, beside the download patches'. */
internal const val WHO_SHARED_MENU_HELPER = "hushfacebookWhoSharedMenuItem"

/** The extension's stub the patch fills with the call to Facebook's launcher. */
internal const val OPEN_LIST = "openList"

/** The message Facebook's launcher fails with when it gets no activity, kept inline in 584 and 585. */
internal const val RESHARES_LIST_MESSAGE = "Activity is required to launch reshares list"

/**
 * Facebook's launcher of the list of people who shared a post: a static (Context, GraphQLStory,
 * String) method that opens the feed host on the reshares fragment with the post wrapped as feed
 * props (`LX/OJx;->A00` in 584). Its callers are the share count rows a server test turns on.
 */
internal object ResharesListLauncherFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(CONTEXT, GRAPHQL_STORY, STRING),
    strings = listOf(RESHARES_LIST_MESSAGE),
)

/**
 * Adds See who shared to a post's three-dot menu (#283). Facebook keeps a list of the people who
 * shared a post, but in the feed only a share count row that a server test turns on opens it. The
 * row goes in through the place Download any video adds its item ([postMenu]), and a tap calls
 * Facebook's own launcher, which this patch writes into the extension's [OPEN_LIST] stub, so the
 * list and what it shows are Facebook's.
 */
@Suppress("unused")
val seeWhoSharedPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "See who shared",
    description = "Adds See who shared to a post's menu, which opens Facebook's list of the people who shared " +
        "it. Starts off. Turn it on in Hushfacebook settings > News feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val launcher = ResharesListLauncherFingerprint.method
        val menu = postMenu()
        fillOpenList(launcher)
        callAfterMenuFill(menu, postMenuHelper(menu, WHO_SHARED_MENU_HELPER, ADD_WHO_SHARED_ITEM, emptyList()))
        enableStatus("whoShared")
    }
}

/** The call to [launcher] the stub's body makes, as smali. */
internal fun launcherCall(launcher: Method) =
    "${launcher.definingClass}->${launcher.name}($CONTEXT$GRAPHQL_STORY$STRING)V"

/**
 * Fills the extension's [OPEN_LIST] stub: the post cast to GraphQLStory, Facebook's launcher, and
 * an answer of true. Only the parameter registers are used, so the stub's own register count
 * doesn't matter; the extension only passes a post.
 */
internal fun BytecodePatchContext.fillOpenList(launcher: Method) {
    val stub = mutableClassDefBy(WHO_SHARED).methods.singleOrNull {
        it.name == OPEN_LIST && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, OBJECT, STRING)
    } ?: throw PatchException("$PATCH: $WHO_SHARED has no static boolean $OPEN_LIST(Context, Object, String)")

    stub.addInstructions(
        0,
        """
            check-cast p1, $GRAPHQL_STORY
            invoke-static { p0, p1, p2 }, ${launcherCall(launcher)}
            const/4 p0, 0x1
            return p0
        """,
    )
}
