/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * A Debug logging test of the link to a Messenger patched with this build's key: runs, on request,
 * the two reads Facebook's message expiration job makes at startup, the opt-out flag and the
 * triggered flag. Each read can ask a Meta family app on the phone for its answer, and that app's
 * caller check then decides whether this Facebook may ask. Facebook makes the reads once and then
 * remembers it did, so without this a test waits on Facebook's own schedule.
 *
 * <p>The report says whether each read answered, never what it answered. Messenger's own log shows
 * whether it let Facebook in.
 *
 * <p>Restore screens on re-signed builds fills the stubs: Facebook's Context-to-session lookup, the
 * flag reader's (FbUserSession) constructor and its two reads. Unfilled, {@link #available()} is
 * false and the settings screen shows no row.
 */
public final class MessengerLinkCheck {
    /** How the test reaches Facebook. Tests put their own in {@link #access}. */
    interface Reads {
        boolean filled();

        @Nullable
        Object session(Context context);

        @Nullable
        Object reader(Object session);

        @Nullable
        Object optOut(Object reader);

        @Nullable
        Object triggered(Object reader);
    }

    static final Reads PATCHED = new Reads() {
        @Override
        public boolean filled() {
            return patched();
        }

        @Override
        public Object session(Context context) {
            return userSession(context);
        }

        @Override
        public Object reader(Object session) {
            return flagReader(session, null);
        }

        @Override
        public Object optOut(Object reader) {
            return readOptOutFlag(reader);
        }

        @Override
        public Object triggered(Object reader) {
            return readTriggeredFlag(reader);
        }
    };

    static volatile Reads access = PATCHED;

    private MessengerLinkCheck() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch to answer true once the stubs below are filled. */
    public static boolean patched() {
        return false;
    }

    /** Filled in by the patch: Facebook's lookup of the signed-in FbUserSession for a Context. */
    @Nullable
    public static Object userSession(Context context) {
        return null;
    }

    /**
     * Filled in by the patch: a new flag reader for [session]. Only an FbUserSession may be passed.
     * [spare] is where the fill builds the reader, so pass null: an unfilled body that only returns
     * null can compile to a single register, the parameter's, which leaves no local to borrow.
     */
    @Nullable
    public static Object flagReader(Object session, @Nullable Object spare) {
        return null;
    }

    /** Filled in by the patch: the reader's read of the opt-out flag. Only a flag reader may be passed. */
    @Nullable
    public static Object readOptOutFlag(Object reader) {
        return null;
    }

    /** Filled in by the patch: the reader's read of the triggered flag. Only a flag reader may be passed. */
    @Nullable
    public static Object readTriggeredFlag(Object reader) {
        return null;
    }

    // ------------------------------------------------------------------ the test

    /** Whether this build can run the test. */
    public static boolean available() {
        try {
            return access.filled();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Runs the test off the main thread, since Facebook's reads refuse the main thread, and hands
     * the answer to [shown] on the main thread: the settings row shows it in its summary, where it
     * stays whole, since a toast that long gets cut on some phones. With no row it's a toast. The
     * log gets the same line.
     */
    public static void start(Context context, @Nullable Consumer<String> shown) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        boolean queued = Utils.runOnBackgroundThread(() -> {
            String report = run(app);
            Logger.printInfo(() -> "Messenger link test: " + report);
            show(report, shown);
        });
        if (!queued) show(L10n.t("The Messenger link test couldn't start. Try again."), shown);
    }

    private static void show(String report, @Nullable Consumer<String> shown) {
        if (shown == null) {
            Utils.showToastLong(report);
            return;
        }
        Utils.runOnMainThread(() -> shown.accept(report));
    }

    /** The test itself, on the calling thread. Says whether each read answered, never what. */
    static String run(Context context) {
        if (!available()) return L10n.t("This build can't run the Messenger link test.");
        Object session;
        try {
            session = access.session(context);
        } catch (Throwable failure) {
            logFailure("the session lookup", failure);
            session = null;
        }
        if (session == null) return L10n.t("No signed-in account to test with. Sign in to Facebook and try again.");
        Object reader;
        try {
            reader = access.reader(session);
        } catch (Throwable failure) {
            logFailure("the flag reader", failure);
            reader = null;
        }
        if (reader == null) return L10n.t("The Messenger link test couldn't start. Try again.");
        Object held = reader;
        String optOut = read("the opt-out read", () -> access.optOut(held));
        String triggered = read("the triggered read", () -> access.triggered(held));
        return L10n.f("Opt-out read: %1$s. Triggered read: %2$s.", optOut, triggered);
    }

    private static String read(String what, Callable<Object> read) {
        try {
            return read.call() == null ? L10n.t("no answer") : L10n.t("answered");
        } catch (Throwable failure) {
            logFailure(what, failure);
            return L10n.f("failed (%1$s)", L10n.isolate(failure.getClass().getSimpleName()));
        }
    }

    /** Only the failure's class goes to the log: a message could carry what was read. */
    private static void logFailure(String what, Throwable failure) {
        Logger.printInfo(() -> "Messenger link test: " + what + " failed with " + failure.getClass().getName());
    }

    /** Puts back Facebook's reads. For tests. */
    static void forget() {
        access = PATCHED;
    }
}
