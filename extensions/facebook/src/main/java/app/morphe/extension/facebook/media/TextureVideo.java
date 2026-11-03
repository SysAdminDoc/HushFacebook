/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import app.morphe.extension.facebook.navigation.TranslucentBars;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Draw videos with the rest of the screen (#308). Facebook's player shows most reels and many
 * videos on a SurfaceView, which Android hands to the display hardware as a layer of its own. On
 * some phones that layer comes out pale until something on top makes Android compose the screen
 * on the GPU again, which is why Disable HW overlays in developer options cures it. The player can
 * also draw into a TextureView, which Android composes with the rest of the window.
 *
 * <p>Facebook decides per player in two steps (585): one method answers whether this player must
 * use a SurfaceView (reels say yes), and when it doesn't, the player's own TextureView preference
 * picks. While the switch is on the first answer is no and the preference is yes, so every player
 * Facebook builds or attaches from then on draws into a TextureView. Off, paused, or before the
 * settings are ready, both answers are Facebook's own.
 *
 * <p>See-through bars moves every player but the reels viewer's (#250). The bars' frost copies the
 * window, and a SurfaceView's video isn't in it, so a feed video on one shows sharp through the bar
 * while it plays. The decision gets the player's origin, and some phones answer yes for the feed
 * origin from a server list, so while the bars are see-through both answers lead to the TextureView
 * for any origin but {@value #REELS_VIEWER}. A reel keeps its SurfaceView.
 */
public final class TextureVideo {
    /** Counted under the patch's name for each player kept off its own SurfaceView. */
    static final String DRAWN_WITH_THE_SCREEN = "Video drawn with the rest of the screen";
    /** Counted for each player See-through bars moved onto its TextureView. */
    static final String DRAWN_UNDER_THE_BARS = "Feed video drawn with the screen under see-through bars";
    /** The reels viewer's origin name, which the origin's text holds (585 prints "A00::A01", A01 the name). */
    static final String REELS_VIEWER = "fb_shorts_viewer";

    private static final String FAMILY = FamilyNames.HDR_BRIGHTNESS;

    private static volatile boolean logged;
    private static volatile boolean loggedBars;

    private TextureVideo() {
    }

    /**
     * Injection point, at each answer of Facebook's must-use-a-SurfaceView decision, given the
     * player's origin: no while the switch is on, or while the bars are see-through for any origin
     * but the reels viewer's. Facebook's answer otherwise.
     */
    public static boolean surfaceView(boolean facebook, Object origin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                if (!facebook || reelsViewer(origin) || !TranslucentBars.blursVideos()) return facebook;
                underTheBars();
            } else if (facebook) {
                HookStatus.counted(FAMILY, DRAWN_WITH_THE_SCREEN);
                if (!logged) {
                    logged = true;
                    Logger.printDebug(() -> "Draw videos with the rest of the screen: a player kept off its SurfaceView");
                }
            }
            HookStatus.bound(FAMILY, "surfaceView");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "surfaceView", failure);
            return facebook;
        }
    }

    /**
     * Injection point, where the player reads its own TextureView preference after the decision
     * above said no: yes while the switch is on or the bars are see-through, the player's own
     * preference otherwise.
     */
    public static boolean preferTextureView(boolean facebook) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                if (facebook || !TranslucentBars.blursVideos()) return facebook;
                underTheBars();
            }
            HookStatus.bound(FAMILY, "preferTextureView");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "preferTextureView", failure);
            return facebook;
        }
    }

    private static boolean reelsViewer(Object origin) {
        return origin != null && String.valueOf(origin).contains(REELS_VIEWER);
    }

    private static void underTheBars() {
        HookStatus.counted(FAMILY, DRAWN_UNDER_THE_BARS);
        if (!loggedBars) {
            loggedBars = true;
            Logger.printDebug(() -> "Draw videos with the rest of the screen: a feed video moved onto its TextureView for See-through bars");
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.DRAW_VIDEOS_WITH_THE_SCREEN.get();
    }
}
