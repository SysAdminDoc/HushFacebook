/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stands in for Facebook's quiet mode manager, the way the patch's filled-in flag stub reaches its
 * one AtomicBoolean, for a test outside this package.
 */
public final class CenteredFullscreenForTests {
    /** Stands in for the quiet mode manager: its flag, as Facebook's setup leaves it in portrait. */
    public static final class Manager {
        public final AtomicBoolean quiet = new AtomicBoolean(false);
    }

    private CenteredFullscreenForTests() {
    }

    /** Stands in for the patched stub. */
    public static void install() {
        CenteredFullscreen.flags = manager -> manager instanceof Manager ? ((Manager) manager).quiet : null;
    }

    /** Puts the patched stub back. */
    public static void forget() {
        CenteredFullscreen.flags = CenteredFullscreen.PATCHED;
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
            return manager.quiet.get();
        } finally {
            forget();
        }
    }
}
