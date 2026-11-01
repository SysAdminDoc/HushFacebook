/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.os.SystemClock;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Back on a scrolled-down Home (#247). NewsFeedFragment's onBackPressed scrolls a feed that isn't
 * at the top back to it and starts a refresh there, so the post you came back to after writing a
 * comment is gone. With Back leaves Home where it is on, the first Back only says how to get to the
 * top, and a second Back within {@link #SECOND_BACK_MS} gets Facebook's own scroll and refresh.
 * A Back on a feed already at the top, and every Back with the switch off, is Facebook's.
 */
public final class HomeBack {
    /** How long after the toast a Back still counts as the second one. */
    static final long SECOND_BACK_MS = 2_000L;
    private static final String KEPT = "kept Home where it was on Back";
    private static final String LET_FACEBOOK = "let Facebook go to the top on a second Back";
    /** When the last Back that kept Home showed its toast, or -1 once a second Back used it. */
    private static long keptAt = -1;

    private HomeBack() { }

    /**
     * Called in NewsFeedFragment's onBackPressed once it knows the feed isn't at the top, before it
     * scrolls there. True ends the press as handled with the feed where it is, and a toast says a
     * second Back goes to the top. A failure gives Facebook the press, as it would unpatched.
     */
    public static boolean holdScrollToTop() {
        try {
            HookStatus.invoked(FamilyNames.RETURN_REFRESH);
            if (!Utils.settingsReady() || !Settings.BACK_KEEPS_HOME.get()) return false;
            boolean hold = decideAt(SystemClock.elapsedRealtime());
            Logger.printDebug(() -> "Home back: Back on a scrolled Home"
                    + (hold ? ": kept the feed where it is" : ": second Back, Facebook goes to the top"));
            HookStatus.counted(FamilyNames.RETURN_REFRESH, hold ? KEPT : LET_FACEBOOK);
            if (hold && !Settings.HIDE_BACK_REMINDER.get()) {
                Utils.showToastShort(L10n.t("Press Back again to go to the top"));
            }
            return hold;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RETURN_REFRESH, "Home back", failure);
            return false;
        }
    }

    /**
     * Whether a Back at [now] keeps Home where it is: yes, unless it comes within
     * {@link #SECOND_BACK_MS} of the last one that did, which it uses up so a third Back keeps again.
     */
    static synchronized boolean decideAt(long now) {
        if (keptAt >= 0 && now >= keptAt && now - keptAt <= SECOND_BACK_MS) {
            keptAt = -1;
            return false;
        }
        keptAt = now;
        return true;
    }

    /** Forgets the last kept Back, so the next one keeps Home again. */
    static synchronized void forget() {
        keptAt = -1;
    }
}
