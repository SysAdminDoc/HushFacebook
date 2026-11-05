/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stands in for Facebook's quiet mode manager, the way the patch's filled-in stubs reach its one
 * AtomicBoolean and tell its listeners, for a test outside this package.
 */
public final class CenteredFullscreenForTests {
    /** What would be on its way to the main thread. {@link #runMain} runs it. */
    static final List<Runnable> MAIN = new ArrayList<>();

    /** Stands in for the quiet mode manager: its flag, as Facebook's setup leaves it in portrait. */
    public static final class Manager {
        public final AtomicBoolean quiet = new AtomicBoolean(false);
        /** How many times its listeners were told quiet mode changed, and what the flag was then. */
        public int told;
        public boolean quietWhenTold;
        /** False makes it a manager the patch couldn't reach the listeners of. */
        public boolean answers = true;
    }

    private CenteredFullscreenForTests() {
    }

    /** Stands in for the patched stubs. */
    public static void install() {
        CenteredFullscreen.flags = manager -> manager instanceof Manager ? ((Manager) manager).quiet : null;
        CenteredFullscreen.notices = manager -> {
            if (!(manager instanceof Manager) || !((Manager) manager).answers) return false;
            Manager known = (Manager) manager;
            known.told++;
            known.quietWhenTold = known.quiet.get();
            return true;
        };
        CenteredFullscreen.later = MAIN::add;
    }

    /** Runs what was on its way to the main thread. */
    static void runMain() {
        List<Runnable> due = new ArrayList<>(MAIN);
        MAIN.clear();
        for (Runnable task : due) task.run();
    }

    /** Puts the patched stubs back. */
    public static void forget() {
        CenteredFullscreen.flags = CenteredFullscreen.PATCHED;
        CenteredFullscreen.notices = CenteredFullscreen.TOLD;
        CenteredFullscreen.later = CenteredFullscreen.MAIN;
        CenteredFullscreen.resetForTests();
        MAIN.clear();
    }

    /**
     * For the pause probe: a full screen video set up in portrait. True when it starts with its
     * details and comments folded away, which a paused Hushfacebook must never do.
     */
    public static boolean centersAVideo() {
        install();
        try {
            Manager manager = new Manager();
            CenteredFullscreen.started(manager, false);
            return manager.quiet.get() || manager.told > 0;
        } finally {
            forget();
        }
    }
}
