/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide the Get Messenger card patch asks before Facebook's Chats shows its "Get the
 * Messenger app" card.
 *
 * <p>Facebook drops the card by itself when Messenger is installed, but it counts Messenger only
 * when Messenger is signed with Facebook's own key. A re-signed Facebook never matches, so the card
 * stays at the top of Chats with Messenger right there. The patch runs {@link #hide} first in the
 * question the card's plugin answers before it shows, and answers no for Facebook while the switch
 * is on and Messenger is installed and enabled, whoever signed it. A copy under another package
 * name, as HushMessenger's install beside Meta's apps makes, counts too, the same one the top bar's
 * Messenger icon opens.
 *
 * <p>Whether Messenger is installed is looked up once per process start and kept. The manifest's
 * queries already let Facebook see it (Messenger has a launcher activity, and Facebook asks for
 * every app with one), so no permission is added. It fails open: with the switch off, a pause,
 * settings that aren't ready, Messenger missing or disabled, or any failure in here, Facebook asks
 * its own question and the card keeps its install path.
 */
public final class MessengerCard {
    /** Messenger's package. */
    static final String MESSENGER = "com.facebook.orca";

    /** The diagnostic counter route: each time Chats asked about the card, and the ones hidden. */
    static final String ROUTE = "Get Messenger card";

    /** What a hidden card is counted under. */
    static final String HIDDEN = "Messenger installed";

    /** Whether Messenger was installed when this process first asked, or null before then. */
    @Nullable
    private static volatile Boolean installed;

    private MessengerCard() {
    }

    /**
     * Injection point, first thing in the card's show question. True answers no for Facebook, so
     * the card isn't shown. Never throws.
     */
    public static boolean hide() {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_CARD);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_GET_MESSENGER_CARD.get()) return false;
            if (!messengerInstalled()) return false;
            FeedFilterCounters.removed(ROUTE, 1, HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_CARD, "Get Messenger card", failure);
            return false;
        }
    }

    /** Whether Messenger is installed and enabled, looked up on the first call and kept after it. */
    static boolean messengerInstalled() {
        Boolean known = installed;
        if (known != null) return known;
        Context context = Utils.getContext();
        // No context yet: say no without keeping it, so a later call can still look.
        if (context == null) return false;
        PackageManager packages = context.getPackageManager();
        boolean found = lookUp(packages) || MessengerIcon.copyLaunch(packages, context.getPackageName()) != null;
        installed = found;
        Logger.printDebug(() -> found
                ? "Get Messenger card: Messenger is installed, so Chats leaves the card out"
                : "Get Messenger card: Messenger isn't installed or is disabled, so the card stays");
        return found;
    }

    /** True when [packages] has Messenger installed and enabled, whatever key it's signed with. */
    static boolean lookUp(PackageManager packages) {
        try {
            ApplicationInfo messenger = packages.getApplicationInfo(MESSENGER, 0);
            return messenger.enabled;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    /** Drops the kept answer, so the next call looks again. For tests. */
    static void forget() {
        installed = null;
    }
}
