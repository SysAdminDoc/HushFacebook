/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import android.content.Context;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

/**
 * Marks every notification read, the way the Mark all as read row in the Notifications tab's ⋯
 * menu does (Discussion #338).
 *
 * <p>Facebook decides per account what sits at the top right of that tab: the ⋯ with its menu, a
 * gear, a bell or none of them, so on some accounts there's no way to clear every unread dot at
 * once. The menu row itself only builds a small action around Facebook's notifications
 * controller, whose one method for it (the one that logs {@code MAAR_CLICK}) takes the signed-in
 * session and a Context. The Block promotional notifications patch fills the stubs below with
 * that controller and one of Facebook's Context-to-session lookups, so a row in the Hush settings
 * runs the same thing.
 */
public final class MarkAllRead {
    /** How a tap on the row went. */
    public enum Outcome {
        /** Facebook was asked to mark every notification read. */
        DONE,
        /** This build doesn't carry the controller. */
        NOT_IN_BUILD,
        /** No one is signed in, or the session couldn't be read. */
        NO_SESSION,
        /** Facebook's controller threw. */
        FAILED
    }

    private MarkAllRead() {
    }

    /**
     * Asks Facebook to mark every notification read, through the controller the ⋯ menu's row
     * uses. Run it on the main thread, as that row does. Never throws.
     */
    public static Outcome run(Context context) {
        try {
            if (!patched()) return Outcome.NOT_IN_BUILD;
            Object session = userSession(context);
            if (session == null) {
                Logger.printDebug(() -> "Mark all as read: no signed-in session to mark them with");
                return Outcome.NO_SESSION;
            }
            markAll(session, context, null);
            Logger.printDebug(() -> "Mark all as read: Facebook asked to mark every notification read");
            return Outcome.DONE;
        } catch (Throwable failure) {
            Logger.printException(() -> "Mark all as read failed", failure);
            return Outcome.FAILED;
        }
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
     * Filled in by the patch: a new notifications controller, asked to mark everything read for
     * [session]. Only an FbUserSession may be passed. [spare] is where the fill builds the
     * controller, so pass null: an empty body leaves no local to borrow.
     */
    public static void markAll(Object session, Context context, @Nullable Object spare) {
    }
}
