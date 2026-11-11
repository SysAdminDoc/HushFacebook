/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.downloads.video.IMMUTABLE_LIST
import app.morphe.patches.facebook.downloads.video.callAfterMenuFill
import app.morphe.patches.facebook.downloads.video.graphQlGetter
import app.morphe.patches.facebook.downloads.video.postMenu
import app.morphe.patches.facebook.downloads.video.postMenuHelper
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The extension call that adds Save photo to a post's menu once Facebook has filled it. */
internal const val ADD_PHOTO_ITEM = "Lapp/morphe/extension/facebook/download/PhotoMenuItem;->add(" +
    "Landroid/view/Menu;Landroid/view/View;Ljava/lang/Object;I" +
    "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The helper this patch adds to the class that builds every post menu, beside the video patch's. */
internal const val PHOTO_MENU_HELPER = "hushfacebookPhotoMenuItem"

/** The provider that serves a Search photo tap's photo to the app picked in the chooser. */
internal const val PHOTO_SEARCH_PROVIDER = "app.morphe.extension.facebook.download.PhotoSearchFiles"

/** Its authority after the package's name, as the extension's PhotoSearch.AUTHORITY_SUFFIX builds it. */
internal const val PHOTO_SEARCH_AUTHORITY_SUFFIX = ".hushfacebook.photosearch"

private fun Document.all(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).mapNotNull { list.item(it) as? Element } }

/**
 * Declares [PHOTO_SEARCH_PROVIDER] under [packageName]: unexported, so only an app a chooser's
 * intent granted can read, and only the one photo it was granted. A manifest without a single
 * application is refused. A second run leaves the one declaration it made.
 */
internal fun Document.declarePhotoSearchProvider(packageName: String) {
    val application = all("application").singleOrNull()
        ?: throw PatchException("$PHOTO_PATCH: the manifest has no single <application>")
    if (all("provider").any { it.getAttribute("android:name") == PHOTO_SEARCH_PROVIDER }) return
    application.appendChild(
        createElement("provider").apply {
            setAttribute("android:name", PHOTO_SEARCH_PROVIDER)
            setAttribute("android:authorities", packageName + PHOTO_SEARCH_AUTHORITY_SUFFIX)
            setAttribute("android:exported", "false")
            setAttribute("android:grantUriPermissions", "true")
        },
    )
}

private val photoSearchProviderPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { it.declarePhotoSearchProvider(packageMetadata.packageName) }
    }
}

/**
 * The hash of `subattachments`, the field a multi-photo post's attachment lists its photos in.
 * GraphQLStoryAttachment reads it in one public getter on 577, 580 and 581 (`A09()` on each,
 * 2026-10-07), the only one of its no-argument getters holding the constant.
 */
internal val SUBATTACHMENTS_FIELD = "subattachments".hashCode()

/**
 * Offers Save photo on every photo the viewer opens and saves it through Hushfacebook. See
 * PhotoSaveAnchors.kt for where the hooks go, and the extension's PhotoSave for what they do. The
 * save asks Facebook's own image address modifier for the most the CDN serves the photo at, the
 * call PhotoSizeAnchors.kt finds in Facebook's save.
 *
 * Its second switch puts Save photo in a post's own three-dot menu too, through the same place
 * Download any video adds its item ([postMenu]): after Facebook fills the menu, the extension's
 * PhotoMenuItem adds the item when the post holds photos, saving the one photo, or each photo of a
 * multi-photo post's attachment one after another. Its third switch adds Search photo there for a
 * post with one photo (#245, #248), which hands the photo to the app picked in Android's chooser
 * through the unexported provider [declarePhotoSearchProvider] declares.
 *
 * A comment's long-press sheet gets Save GIF on a GIF comment, next to where Facebook puts Save
 * photo on a photo one (#226, CommentGifAnchors.kt), behind the first switch.
 */
@Suppress("unused")
val downloadPhotoPatch = bytecodePatch(
    // The README table check reads this literal; PHOTO_PATCH carries the same text for the messages.
    name = "Download any photo",
    description = "Shows Save photo on every photo you open, even where the poster turned saving off, and saves " +
        "the largest size so you get the best copy. A long press on a comment with a photo or GIF in it offers Save " +
        "photo or Save GIF. On by default. Turn it off in Hushfacebook settings > Downloads.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, facebookExtensionPatch, photoSearchProviderPatch, commentSheetDialogPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // The post menu's anchors are found before either hook goes in, so a build without one
        // fails whole instead of keeping the photo hooks with no switch in settings.
        val addPostMenuItem = postMenuPhotoItem()
        val addCommentGifRow = commentSheetGifRow()
        val addCommentPhotoRow = commentSheetPhotoRow()
        unlockPhotoSave()
        fillCdnResize()
        addPostMenuItem()
        addCommentGifRow()
        addCommentPhotoRow()
        enableStatus("photoDownload")
    }
}

/**
 * Finds the post menu's Save photo item, which the extension shows only while its switch is on.
 * Nothing changes until the step it returns runs.
 */
internal fun BytecodePatchContext.postMenuPhotoItem(): () -> Unit {
    val menu = postMenu()
    val subattachments = graphQlGetter(GRAPHQL_STORY_ATTACHMENT, IMMUTABLE_LIST, SUBATTACHMENTS_FIELD, "subattachments")
    val helper = postMenuHelper(
        menu,
        PHOTO_MENU_HELPER,
        ADD_PHOTO_ITEM,
        listOf(menu.attachments, menu.media, menu.attachedStory, subattachments),
    )
    return { callAfterMenuFill(menu, helper) }
}

/** Puts the switch on every photo-menu read of `can_viewer_download` and wraps Save photo's action. */
internal fun BytecodePatchContext.unlockPhotoSave() {
    val gated = mutableListOf<Pair<String, String>>()
    val actions = mutableListOf<Pair<String, String>>()
    // Every gate checks the literal Video type. getBooleanValue is common across unrelated
    // models, so selecting it would decode most of the app just to find the viewer's two gates.
    classDefForEachReferencing(strings = listOf(VIDEO_TYPE)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.forEach { method ->
            if (photoDownloadGates(method).isNotEmpty()) gated += classDef.type to method.name
        }
    }
    classDefForEachReferencing(types = listOf(MENU_LISTENER)) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES)) {
            classDef.methods.filter(::isSavePhotoAction).forEach { actions += classDef.type to it.name }
        }
    }
    if (gated.isEmpty()) throw PatchException("$PHOTO_PATCH: found no photo menu reading can_viewer_download")
    val (owner, name) = actions.singleOrNull()
        ?: throw PatchException("$PHOTO_PATCH: expected one $SAVE_PHOTO_ACTION, found ${actions.size}")

    gated.distinct().forEach { (type, method) ->
        mutableClassDefBy(type).methods.filter { it.name == method }.forEach { mutable ->
            // From the last gate back, so an insert doesn't move the ones still to come.
            photoDownloadGates(mutable).sortedDescending().forEach(mutable::answerGateWithTheSwitch)
        }
    }
    mutableClassDefBy(owner).methods.single { it.name == name && isSavePhotoAction(it) }.wrapSaveAction()
}
