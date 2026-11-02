/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Photo carousel (#345): a post with several photos or videos shows them in the row Facebook swipes
 * through, one at a time with a count in the corner, instead of a grid. Facebook picks how such a
 * post looks by asking its album layouts in turn whether each applies. The swipeable row
 * (MultiMediaAttachmentHScrollPlugin) applies only in the feeds and tests Facebook turns it on for,
 * and three layouts a poster can choose, columns, a banner and a frame, are asked before it. With
 * the switch on, the row's feed check answers yes and those three answer no, so the row takes every
 * post it can draw. A post it can't draw gets Facebook's grid as before, and the layouts for
 * sensitive photos and private tagged albums keep their place ahead of it.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, every answer is
 * Facebook's.
 */
public final class PhotoCarousel {
    /** Counted each time the row's feed check is answered yes. */
    static final String LET_IN = "Carousel let in";

    /** Counted each time a layout the poster chose is passed over. */
    static final String PASSED_OVER = "Poster's layout passed over";

    /** The album layouts a poster picks, passed over for the row, by the end of their class names. */
    static final String[] POSTER_LAYOUTS = {
            ".PhotoLayoutColumnsPlugin",
            ".PhotoLayoutBannerPlugin",
            ".PhotoLayoutFramePlugin",
    };

    private static final String FAMILY = FamilyNames.PHOTO_CAROUSEL;

    private static volatile boolean logged;

    private PhotoCarousel() {
    }

    /** Whether the row takes the posts: the switch on, nothing paused, the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.PHOTO_CAROUSEL.get();
    }

    /**
     * The hook right after Facebook asks whether the swipeable row may show in this feed, handed the
     * answer as an int: a boolean method may leave a register the verifier types as int, and a
     * boolean parameter wouldn't take it. Answers yes while the switch is on, Facebook's answer
     * otherwise.
     */
    public static boolean carousel(int allowed) {
        boolean facebooks = allowed != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (facebooks || !on()) return facebooks;
            HookStatus.bound(FAMILY, "carousel check");
            HookStatus.counted(FAMILY, LET_IN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Photo carousel: a post's photos go in the swipeable row");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "carousel check", failure);
            return facebooks;
        }
    }

    /**
     * The hook at the start of Facebook's check of whether an album layout applies, handed the
     * layout's class name. True makes the check answer no, for the three layouts a poster picks while
     * the switch is on. False, every other time, lets Facebook's check run.
     */
    public static boolean skipLayout(String plugin) {
        try {
            if (plugin == null || !on()) return false;
            for (String layout : POSTER_LAYOUTS) {
                if (plugin.endsWith(layout)) {
                    HookStatus.bound(FAMILY, "layout check");
                    HookStatus.counted(FAMILY, PASSED_OVER);
                    return true;
                }
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "layout check", failure);
            return false;
        }
    }
}
