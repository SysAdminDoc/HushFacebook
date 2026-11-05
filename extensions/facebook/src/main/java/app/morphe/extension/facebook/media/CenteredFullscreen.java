/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Open full screen videos centered (#242). Facebook's full screen player, the one a comment's video
 * or a video link opens, starts a video held upright with its details and comments panel open, and
 * that panel keeps a landscape video at the top of the screen. The player's quiet mode is the same
 * screen with the panel folded away, which is what its "Hide details and comments" chevron turns
 * on, and Facebook only starts in it when the phone is turned sideways.
 *
 * <p>Right after the player sets quiet mode up for a video, {@link #started} gets the quiet mode
 * manager and the setup's last argument, which keeps quiet mode off whichever way the phone is
 * held. With the switch on, quiet mode starts on, so the video opens centered and the chevron
 * still brings the panel back. A video Facebook keeps out of quiet mode, and every video while the
 * switch is off, opens as Facebook lays it out.
 *
 * <p>The flag alone only folds the panel: the player's own layout keeps the video at the top until
 * the manager tells its listeners quiet mode changed. So the flag is set and then the manager tells
 * them, with the reason the chevron gives when it's tapped. The orientation reason won't do: the
 * listener that slides the video to the middle skips it, since turning the phone lays the whole
 * screen out again (on the S22 the panel folded and the comment bar went, but the video stayed at
 * the top). Where it can't be told, the flag goes back, so the screen is never half quiet.
 *
 * <p>The player's own component and the comment bar read the flag before the setup and start
 * listening after it, so they miss that first notice (on the S22 the panel folded but the video
 * stayed at the top and the comment bar stayed up). So while a manager started here stays quiet,
 * {@link #listening} hears each new listener, and the manager tells its listeners once more on the
 * main thread, after the new one is in its list. Listeners that arrive together share one notice.
 * Once the chevron brings the panel back, the manager is Facebook's again.
 */
public final class CenteredFullscreen {
    /** What the report counts each time a video opens centered. */
    static final String CENTERED = "Full screen video opened centered";

    private static final String FAMILY = FamilyNames.TAP_TO_PLAY;

    /** Reaches a manager's quiet mode flag. The tests hand in their own. */
    interface Flags {
        @Nullable AtomicBoolean of(Object manager);
    }

    /** Tells a manager's listeners quiet mode changed. The tests hand in their own. */
    interface Notices {
        boolean tell(Object manager);
    }

    /** Runs a task later on the main thread. The tests run it when they choose. */
    interface Later {
        void run(Runnable task);
    }

    static final Flags PATCHED = CenteredFullscreen::quietFlag;
    static final Notices TOLD = manager -> quietChanged(manager, null);
    static final Later MAIN = Utils::runOnMainThread;

    static Flags flags = PATCHED;
    static Notices notices = TOLD;
    static Later later = MAIN;

    /**
     * How many more notices a manager gets for listeners that arrive late. The player's own pieces
     * all start listening within the first one or two, and a listener that starts over each time
     * it hears one can't keep the manager telling forever.
     */
    static final int RETELLS = 4;

    /** The managers started quiet here that are still quiet, with the notices they have left. */
    private static final Map<Object, Integer> STARTED = Collections.synchronizedMap(new WeakHashMap<>());
    /** The managers with a notice on its way to the main thread. */
    private static final Set<Object> PENDING = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private CenteredFullscreen() {
    }

    /** Filled in by the patch: the quiet mode manager's flag. */
    @Nullable
    public static AtomicBoolean quietFlag(Object manager) {
        return null;
    }

    /**
     * Filled in by the patch: the manager tells its listeners quiet mode changed, the way a tap on
     * its Hide chevron does, and this answers true. Unpatched, false. [spare] only lends the
     * patch a register for the reason: a stub like this one can compile to its parameter's register
     * alone, which leaves no local to borrow.
     */
    public static boolean quietChanged(Object manager, @Nullable Object spare) {
        return false;
    }

    /** Called by the patch right after the full screen player sets quiet mode up for a video. */
    public static void started(Object manager, boolean neverQuiet) {
        try {
            if (neverQuiet || !on()) return;
            AtomicBoolean quiet = flags.of(manager);
            if (quiet == null || !quiet.compareAndSet(false, true)) return;
            if (notices.tell(manager)) {
                STARTED.put(manager, RETELLS);
                HookStatus.counted(FAMILY, CENTERED);
            } else {
                quiet.set(false);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "full screen quiet mode", failure);
        }
    }

    /**
     * Called by the patch as something starts listening to a quiet mode manager, before the
     * manager adds it to its list. For a manager started quiet here, the manager tells its
     * listeners again on the main thread, once that one is listening.
     */
    public static void listening(Object manager) {
        try {
            if (!STARTED.containsKey(manager) || !PENDING.add(manager)) return;
            later.run(() -> retell(manager));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "full screen quiet mode listener", failure);
        }
    }

    private static void retell(Object manager) {
        try {
            PENDING.remove(manager);
            Integer left = STARTED.get(manager);
            if (left == null) return;
            AtomicBoolean quiet = flags.of(manager);
            // Once the chevron brings the panel back, the manager is Facebook's again.
            boolean stillQuiet = quiet != null && quiet.get();
            if (!stillQuiet || left <= 1) STARTED.remove(manager);
            else STARTED.put(manager, left - 1);
            if (stillQuiet) notices.tell(manager);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "full screen quiet mode listener", failure);
        }
    }

    /** Forgets every manager started here. For the tests. */
    static void resetForTests() {
        STARTED.clear();
        PENDING.clear();
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.CENTER_FULL_SCREEN_VIDEOS.get();
    }
}
