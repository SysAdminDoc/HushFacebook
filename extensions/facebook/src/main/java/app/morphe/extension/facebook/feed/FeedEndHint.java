/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Says why Home stopped, once per start, when the bottom of Home comes into view and everything the
 * feed brought since the last post it kept was a suggestion Hush hid (#258, Discussion #371).
 *
 * <p>Once friends' posts run out, Facebook keeps Home going with suggestions ("You're all caught up
 * with friends", then posts from Pages and people you don't follow). With those hidden, each page it
 * fetches past that point comes out empty, and the feed looks stuck on the card or the spinner. People
 * took it for a feed that stopped loading. The feed guard reports each edge it judges here: a kept one
 * starts the count over, a hidden suggestion adds one, and anything else hidden leaves it. Facebook
 * keeps an empty marker at the very bottom of Home that reports when it scrolls into view, and when it
 * does with at least {@link #SHOWN_AFTER} suggestions in a row hidden, a toast says so.
 *
 * <p>Paused, before the settings are ready, after it has shown once, or when anything here fails,
 * nothing shows.
 */
public final class FeedEndHint {
    /** How many suggestions in a row the feed guard has to hide before the bottom of Home says so. */
    static final int SHOWN_AFTER = 10;

    /** Counted under the patch's name the time the bottom of Home says Hush hid what came after. */
    static final String SHOWN = "Home's end said why it stopped";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    private static final String HOOK = "bottom of Home";

    /** Suggestions the feed guard hid since the last edge it kept. */
    private static final AtomicLong STREAK = new AtomicLong();

    private static final AtomicBoolean SAID = new AtomicBoolean();

    private FeedEndHint() {
    }

    /** The feed guard kept an edge: Home has something to show below it. */
    static void kept() {
        STREAK.set(0);
    }

    /** The feed guard hid an edge of this kind. */
    static void hid(@Nullable HiddenTally.Kind kind) {
        if (kind == HiddenTally.Kind.SUGGESTED) STREAK.incrementAndGet();
    }

    /**
     * Injection point, first in the method Facebook calls as the marker at the bottom of Home comes
     * into view and as it leaves. Never throws.
     *
     * @param visible true as the marker comes into view.
     */
    public static void endVisible(boolean visible) {
        try {
            HookStatus.invoked(FAMILY);
            if (!visible || SAID.get() || Setting.isPaused() || !Utils.settingsReady()) return;
            long hidden = STREAK.get();
            Logger.printDebug(() -> "Bottom of Home in view, " + hidden + " suggestions hidden in a row");
            if (hidden < SHOWN_AFTER || !SAID.compareAndSet(false, true)) return;
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, SHOWN);
            Utils.showToastLong(message());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            Logger.printException(() -> "Bottom of Home: could not say why it stopped", failure);
        }
    }

    /** No count: Facebook can judge one edge twice, and a wrong number would mislead more than none. */
    static String message() {
        return L10n.t("Hush hid the suggested posts after this, so Home ends here.");
    }

    static void resetForTests() {
        STREAK.set(0);
        SAID.set(false);
    }
}
