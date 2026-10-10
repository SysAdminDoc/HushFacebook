/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Open Messenger from the top bar patch asks before Facebook handles a tap on its
 * Messenger icon.
 *
 * <p>The icon at the top of the feed and of the other tabs opens Facebook's own Chats. The patch
 * runs {@link #open} first in the icon's tap, and in the Messenger button handler the older title
 * bar shares with it. While the switch is on and Messenger is installed, a tap starts Messenger's
 * launcher entry, the intent its home screen icon sends, and Facebook's own handling is skipped.
 * Without Meta's Messenger, a copy installed under another package name, as HushMessenger's
 * install beside Meta's apps makes, opens instead (see {@link #messengerLaunch}). Any app may start
 * a launcher entry, so nothing about the key Messenger is signed with matters.
 *
 * <p>It fails open: with the switch off, a pause, settings that aren't ready, a long press (Facebook
 * has its own use for that), no Messenger with a launcher entry, a start Messenger turns down, or
 * any failure in here, Facebook handles the tap as it always did.
 *
 * <p>A long press comes in two ways. Where Facebook gives the icon a long-click listener, which a
 * MobileConfig flag decides, the tap is told so. Where it doesn't, the top bar takes a press held on
 * the icon as a plain tap once the finger lifts, and Facebook opens Chats for it. The tap can't tell
 * that one apart, so the patch also hands {@link #touch} every touch on a Facebook screen before
 * Facebook sees it. A tap that comes as the release of a finger held past the long-press time,
 * timed from that finger's own landing whatever other fingers do, is left to Facebook too.
 */
public final class MessengerIcon {
    /** Counted under the patch's name when a tap opened Messenger. */
    static final String OPENED = "opened Messenger";

    /** Counted when the switch was on but no enabled Messenger had a launcher entry to start. */
    static final String NO_MESSENGER = "no Messenger to open";

    /** Counted when Android or Messenger turned the start down. */
    static final String REFUSED = "Messenger refused to start";

    /** Counted when a long press was left to Facebook while the switch was on. */
    static final String LONG_PRESS = "long press left to Facebook";

    /** How soon after a held finger lifts a tap still counts as its release. */
    static final long RELEASE_WINDOW_MS = 500;

    /** The source every event of this hook carries in the diagnostic report. */
    private static final String SOURCE = "MessengerIcon";

    /**
     * How soon after a counted ask another one, with no finger landing or lifting between, is the
     * same tap asking again from the Messenger button handler. That ask comes straight after, and
     * no second tap comes this quickly.
     */
    static final long SAME_TAP_MS = 250;

    private static final long NONE = Long.MIN_VALUE;

    /** When the hook saw the last finger lift after a long press, on the uptime clock, or NONE. */
    private static volatile long heldReleaseSeenAt = NONE;

    /**
     * When each finger now down landed, by pointer id (0 to 31), on the event clock, or NONE. Main
     * thread only, as the dispatch is, and made once so a touch allocates nothing.
     */
    private static final long[] downAt = new long[32];

    /** When a decline was last counted, on the uptime clock, or NONE. Main thread only. */
    private static long declineCountedAt = NONE;

    static {
        Arrays.fill(downAt, NONE);
    }

    private MessengerIcon() {
    }

    /**
     * Injection point, first thing in FbFragmentActivity.dispatchTouchEvent: notes when a finger
     * lifts after staying down for the long-press time, and forgets it when a new gesture starts or
     * one is called off. It only reads the event. Never throws.
     */
    public static void touch(@Nullable MotionEvent event) {
        try {
            if (event == null) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_CANCEL) {
                Arrays.fill(downAt, NONE);
                heldReleaseSeenAt = NONE;
                declineCountedAt = NONE;
                if (action == MotionEvent.ACTION_CANCEL) return;
                HookStatus.bound(FamilyNames.MESSENGER_ICON, "touches");
            }
            boolean landed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
            if (!landed && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_POINTER_UP) return;
            int pointer = event.getPointerId(event.getActionIndex());
            if (pointer < 0 || pointer >= downAt.length) return;
            // A finger landing or lifting makes the next ask another tap's.
            declineCountedAt = NONE;
            if (landed) {
                downAt[pointer] = event.getEventTime();
                return;
            }
            // Each finger is timed from its own landing: a lift's getDownTime() is when the
            // gesture's first finger went down, another finger's once two are down. A lift whose
            // landing the hook missed is timed from that, which leaves the doubt to Facebook.
            long down = downAt[pointer];
            downAt[pointer] = NONE;
            if (down == NONE) down = event.getDownTime();
            // The latest lift decides, as the tap it makes is the next thing to ask. Held for as long
            // as the events say, but timed from when the lift got here, so a busy main thread that
            // hands it on late doesn't push the tap out of the window.
            boolean held = event.getEventTime() - down >= ViewConfiguration.getLongPressTimeout();
            heldReleaseSeenAt = held ? SystemClock.uptimeMillis() : NONE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger icon touches", failure);
        }
    }

    /**
     * Counts why a tap was left to Facebook, once per tap, and says whether it did. Where MobileConfig
     * sends the tap on to the Messenger button handler, that handler's hook asks again straight
     * after and gets the same answer, with no finger landing or lifting between.
     */
    private static boolean countDecline(String why) {
        long now = SystemClock.uptimeMillis();
        long last = declineCountedAt;
        declineCountedAt = now;
        if (last != NONE && now >= last && now - last <= SAME_TAP_MS) return false;
        HookStatus.counted(FamilyNames.MESSENGER_ICON, why);
        return true;
    }

    /** Whether a finger held past the long-press time lifted no more than the window before {@code now}. */
    private static boolean heldPressJustLifted(long now) {
        long at = heldReleaseSeenAt;
        return at != NONE && now >= at && now - at <= RELEASE_WINDOW_MS;
    }

    /**
     * Injection point, first thing in the icon's tap. True when Messenger was started, so Facebook
     * skips its own handling of the tap. Never throws.
     *
     * @param context   what Facebook would start Chats from
     * @param longPress whether the icon was long-pressed rather than tapped
     */
    public static boolean open(@Nullable Context context, boolean longPress) {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            if (context == null) return false;
            if (!Utils.settingsReady() || !Settings.OPEN_MESSENGER_APP.get()) return false;
            if (longPress || heldPressJustLifted(SystemClock.uptimeMillis())) {
                countDecline(LONG_PRESS);
                return false;
            }
            Intent launch = messengerLaunch(context.getPackageManager(), context.getPackageName());
            if (launch == null) {
                countDecline(NO_MESSENGER);
                return false;
            }
            try {
                context.startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException refused) {
                // Messenger went away since the lookup, or the start was refused. Only the class is
                // reported: the exception's own text quotes the intent.
                if (!countDecline(REFUSED)) return false;
                final String kind = refused.getClass().getSimpleName();
                Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> "Messenger didn't start (" + kind + "). Facebook's Chats opens instead.", null);
                return false;
            }
            HookStatus.counted(FamilyNames.MESSENGER_ICON, OPENED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger icon", failure);
            return false;
        }
    }

    /**
     * The launcher entry a tap starts, or null when there's no Messenger to open. Messenger's own
     * comes first: null from Android unless it's installed, enabled and has a MAIN/LAUNCHER (or
     * INFO) activity, and with FLAG_ACTIVITY_NEW_TASK, so Messenger opens in its own task. Without
     * it, the first launcher entry of another app whose activity is one of Messenger's classes, in a
     * task of its own too: HushMessenger installed beside Meta's apps takes another package name
     * ({@code com.facebook.orca.hush} unless the user picks one) and keeps Messenger's class names.
     * Facebook sees launcher entries through its manifest's plain launcher query.
     */
    @Nullable
    static Intent messengerLaunch(PackageManager packages, @Nullable String self) {
        Intent launch = packages.getLaunchIntentForPackage(MessengerCard.MESSENGER);
        if (launch != null) return launch;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> entries = packages.queryIntentActivities(home, 0);
        if (entries == null) return null;
        for (ResolveInfo entry : entries) {
            ActivityInfo activity = entry == null ? null : entry.activityInfo;
            if (activity == null || activity.packageName == null || activity.name == null) continue;
            if (activity.packageName.equals(self) || !activity.name.startsWith(MessengerCard.MESSENGER + ".")) continue;
            return new Intent(home).setClassName(activity.packageName, activity.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }
}
