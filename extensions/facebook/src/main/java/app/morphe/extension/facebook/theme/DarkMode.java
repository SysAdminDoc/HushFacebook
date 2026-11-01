/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Facebook's own answer to whether its dark mode is on, for the whole app.
 *
 * <p>Both themes change Facebook's dark mode only, and a colour can't always say which mode is on.
 * The Video tab stays dark in light mode: its themed context is built from FDS's dark style, and its
 * bottom bar reads the same colour resource as dark mode's, so the same #252728 reaches the themes
 * in both modes. The patch sends each answer of Facebook's dark mode controller (its Dark mode
 * setting, or the system's night mode when that setting follows the system) through
 * {@link #answer}, which keeps the latest. Facebook asks it as each activity applies its theme, again
 * after the setting or the system's night mode changes, and before it builds the Video tab's themed
 * context, so the kept answer follows the setting. Reading it is one volatile read, which suits the
 * colour hooks that run on every layout pass.
 *
 * <p>Facebook makes a controller per user session, and each activity asks the one of its own
 * session, but one answer does for the app: every signed-in session's controller reads the same
 * app-level setting, and only the logged-out session's follows the phone instead, while nothing but
 * logged-out screens are up (DarkModeSettingFixtureTest). The answer comes from any thread that asks,
 * a feed request or an app job as well as the UI, so a change is one atomic swap.
 *
 * <p>Until Facebook first answers, {@link #on} says dark, so the themes act as they did before there
 * was an answer to ask. {@link #saidOn} waits for the answer, for the colours that are the same in
 * both of Facebook's themes and so can't say for themselves which one is on.
 */
public final class DarkMode {

    private DarkMode() {}

    private static final AtomicBoolean ON = new AtomicBoolean(true);

    /** Whether Facebook has answered since the app started. Outside tests it only goes from false to true. */
    private static volatile boolean answered;

    /**
     * Run when the answer changes, once per change, on the thread that made it. Material You sets it,
     * to write route three's fields again.
     */
    @Nullable
    static volatile Runnable changed;

    /**
     * Called with each answer Facebook's dark mode controller gives, right before it returns it.
     * Force dark mode turns a light answer dark first ({@link ForceDarkMode}), and the answer kept
     * is the one the controller returns.
     *
     * @return {@code dark}, or dark when Force dark mode's switch is on, for the controller to return
     */
    public static boolean answer(boolean dark) {
        dark = ForceDarkMode.answer(dark);
        // The reads keep the usual case, the same answer again, to two volatile reads. Of the answers
        // that change it at the same moment, the swap lets exactly one run the listener.
        boolean flipped = ON.get() != dark && ON.getAndSet(dark) != dark;
        // Only once ON holds this answer, or saidOn could pair a first light answer with the
        // starting dark on another thread.
        if (!answered) answered = true;
        if (flipped) {
            Runnable listener = changed;
            if (listener != null) listener.run();
        }
        return dark;
    }

    /** Whether Facebook's dark mode is on, as it last answered. */
    public static boolean on() {
        return ON.get();
    }

    /** Whether Facebook has answered since the app started, with either answer. */
    public static boolean hasAnswered() {
        return answered;
    }

    /** Whether Facebook has answered, and said its dark mode is on. */
    public static boolean saidOn() {
        return answered && ON.get();
    }

    /** Package-visible for tests: back to before Facebook's first answer. */
    static void forget() {
        answered = false;
        ON.set(true);
    }
}
