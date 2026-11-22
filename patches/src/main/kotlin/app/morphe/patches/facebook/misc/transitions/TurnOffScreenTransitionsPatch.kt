/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode

/**
 * Shows Facebook's tabs, its Menu and the screens that open over it without the slide between
 * them. Each moves its own way. A screen that opens over the app, like the post composer, is its
 * own activity: the settings patch already watches every Facebook screen from the application's
 * onCreate, and from those callbacks the extension's ScreenTransitions asks Android for no
 * transition once this patch says it's in the build. A tab and the Menu are Facebook's own view
 * animations, and for those the patch asks the extension where Facebook decides to slide
 * (TransitionAnchors.kt). A tab strip inside a screen asks its pager for the page, and the patch
 * asks the extension first in the two places a tap goes through (PagerTabs.kt). A photo zooms from
 * its place in the feed on a spring, and the patch asks the extension as the spring is set going
 * (PhotoZoom.kt), and a multi-photo post's photo opens on a page whose zoom, fade and slides run
 * for a length the patch has go through the extension (PhotoPage.kt). A screen opened inside
 * Facebook's own, like a post's page, is a fragment that
 * slides in and out with the animations its transaction is handed, and the patch has each go
 * through the extension as it's handed over (FragmentSlides.kt).
 *
 * In the default selection with its switch off: Facebook's transitions are a matter of taste, not
 * something it does to you.
 */
@Suppress("unused")
val turnOffScreenTransitionsPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Turn off screen transitions",
    description = "Shows Facebook's tabs, Menu, photos and new screens at once, without the slide or zoom between " +
        "them, so the app feels quicker. Swipes and animations inside a page stay. Starts off. Turn it on in Hushfacebook " +
        "settings > Appearance.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        // Both are found before either is changed, so a build missing one is left as it was.
        val showTab = ShowTabFingerprint.method
        val snapToPanel = SnapToPanelFingerprint.method
        showTab.quietTabSwitch()
        snapToPanel.quietPanelSnap()

        // Tab strips inside a screen: see PagerTabs.kt. A build that draws them another way keeps the rest.
        try {
            val setPage = pagerTabClick(pagerTabStrip()).quietPagerTabTap()
            try {
                PickerSetTabFingerprint.method.quietPickerTab(setPage)
            } catch (moved: PatchException) {
                patchLog.warning("${moved.message}. The patch goes on without the Feelings and Activities tabs.")
            }
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the tab strips inside a screen.")
        }

        // The zoom a photo opens and closes with: see PhotoZoom.kt. A build that zooms another way keeps the rest.
        try {
            quietPhotoZoom(PhotoOpenFingerprint.method)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the photo zoom.")
        }

        // The photo viewer's slides, away on Back and up for a photo opened without its place: see PhotoZoom.kt.
        try {
            quietPhotoViewerSlides()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the photo viewer's slides.")
        }

        // The zoom, fade and slides of the page a multi-photo post's photo opens on: see PhotoPage.kt.
        try {
            quietPhotoPage()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the photo page's zoom.")
        }

        // The slides of a screen opened inside Facebook's own, like a post's page: see FragmentSlides.kt.
        try {
            quietFragmentSlides(BackStackDumpFingerprint.method)
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without the slides of screens inside the app.")
        }
        enableStatus("turnOffScreenTransitions")
    }
}
