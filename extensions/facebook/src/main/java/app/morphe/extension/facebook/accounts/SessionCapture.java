/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.accounts;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * The sessions Facebook builds for itself, kept as they pass.
 *
 * <p>Facebook's token isn't on disk: the app gets it at sign-in and keeps it in a
 * {@code ViewerContext}, which every request carries. FacebookAMD shows that token, and this is how
 * Hushfacebook reads the same ones. The patch hands each {@code ViewerContext} Facebook constructs
 * to {@link #capture}, which reads `mAuthToken`, `mUserId` and `mUsername` off it and keeps them by
 * account id: a phone signed into two accounts builds a context for each as the switcher fills, and
 * all of them are exported.
 *
 * <p>The instance is kept, not only its values: a context is built empty and filled in, so the
 * fields are read when the account is exported, by which time Facebook has written them. A read
 * that finds nothing leaves that account without a token rather than failing it.
 */
public final class SessionCapture {
    /** What one context said about one account: its token and the name it carried. */
    public static final class Session {
        public final String token;
        public final String name;

        Session(String token, String name) {
            this.token = token;
            this.name = name;
        }
    }

    /** Account id to what its newest context said. A context per account, not one for the phone. */
    private static final Map<String, Session> SESSIONS =
            Collections.synchronizedMap(new LinkedHashMap<String, Session>());

    /** The newest context Facebook made, so a field written after it was kept is read again. */
    private static volatile Object latest;

    /** The newest context's account id, which is the one signed in. */
    private static volatile String currentUid = "";

    private SessionCapture() {
    }

    /** Called by the patch for every ViewerContext Facebook constructs, on whatever thread. */
    public static void capture(@Nullable Object viewerContext) {
        if (viewerContext == null) return;
        latest = viewerContext;
        read(viewerContext);
    }

    /** Every account Facebook has built a session for this run, in the order it built them. */
    public static Map<String, Session> sessions() {
        Object context = latest;
        if (context != null) read(context);
        synchronized (SESSIONS) {
            return new LinkedHashMap<>(SESSIONS);
        }
    }

    /** The signed-in account's token, or "" before one has been seen. */
    public static String token() {
        Object context = latest;
        if (context != null) read(context);
        Session session = SESSIONS.get(currentUid);
        return session == null ? "" : session.token;
    }

    /** The signed-in account's id, or "". */
    public static String uid() {
        return currentUid;
    }

    /** The name the signed-in account's context carries, or "". */
    public static String name() {
        Object context = latest;
        if (context != null) read(context);
        Session session = SESSIONS.get(currentUid);
        return session == null ? "" : session.name;
    }

    /** Reads the three fields off [context], keeping what it finds under the id it names. */
    private static void read(Object context) {
        String uid = string(context, "mUserId");
        String token = string(context, "mAuthToken");
        String name = string(context, "mUsername");
        if (uid == null || !uid.matches("[0-9]{1,32}")) return;
        if (token == null || !token.startsWith("EAA")) return;
        String known = SESSIONS.containsKey(uid) ? SESSIONS.get(uid).name : "";
        String kept = name == null || name.isEmpty() ? known : name;
        SESSIONS.put(uid, new Session(token, kept));
        currentUid = uid;
    }

    /** One String field of [context] by name, or null when the build has no such field. */
    @Nullable
    private static String string(Object context, String fieldName) {
        try {
            Field field = context.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(context);
            return value instanceof String ? (String) value : null;
        } catch (NoSuchFieldException missing) {
            // A build that renamed it. The cookies still export.
            return null;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            Logger.printInfo(() -> "The session field " + fieldName + " wasn't read: "
                    + failed.getClass().getSimpleName());
            return null;
        }
    }
}
