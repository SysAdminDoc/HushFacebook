/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Translate every comment (#522): comments in another language show Facebook's translation as they
 * load, the way a tap on See translation shows it.
 *
 * <p>The comment body builds its See translation handler as it draws a comment that offers one, and
 * the patch hands each handler here with the body's class. Once the main thread is free, the body's
 * tap method (added by the patch) reads the comment's state through the handler and, unless the
 * comment is translated already or its link was tapped before, sends the handler the same click a
 * tap sends. Facebook then shows the translation it sent along or fetches it, as it does for a tap.
 * A comment someone turned back with See original stays that way.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, nothing is tapped.
 */
public final class TranslateComments {
    /** The tap method the patch adds to the comment body: (Object)Z, true when it sent the click. */
    static final String TAP = "hushfacebookTapLink";

    /** The member the report names once the comment body has handed over a See translation handler. */
    static final String LINK = "comment translation link";

    /** Counted each time a comment's See translation is tapped for it as it loads. */
    static final String TRANSLATED = "comment translated as it loads";

    private static final String FAMILY = FamilyNames.AUTO_TRANSLATION;

    /** The tap method of each comment body class seen. Facebook has one. */
    private static final ConcurrentHashMap<Class<?>, Method> TAPS = new ConcurrentHashMap<>();

    private static volatile boolean logged;

    private TranslateComments() {
    }

    /**
     * The hook, as the comment body [owner] builds a comment's See translation [handler]. With the
     * switch on, the handler is tapped once the main thread is free.
     */
    public static void link(@Nullable Object handler, @Nullable Class<?> owner) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, LINK);
            if (handler == null || owner == null) return;
            if (!Utils.settingsReady() || !Settings.TRANSLATE_EVERY_COMMENT.get()) return;
            Utils.runOnMainThread(() -> tap(handler, owner));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, LINK, failure);
        }
    }

    /** Asks [owner]'s tap method to tap [handler], unless the switch went off in the meantime. */
    private static void tap(Object handler, Class<?> owner) {
        try {
            if (!Utils.settingsReady() || !Settings.TRANSLATE_EVERY_COMMENT.get()) return;
            Method method = TAPS.get(owner);
            if (method == null) {
                try {
                    method = owner.getMethod(TAP, Object.class);
                } catch (NoSuchMethodException missing) {
                    HookStatus.missingMember(FAMILY, "method", owner.getName(), TAP);
                    return;
                }
                TAPS.put(owner, method);
            }
            if (!Boolean.TRUE.equals(method.invoke(null, handler))) return;
            HookStatus.counted(FAMILY, TRANSLATED);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Auto-translation: tapped See translation on a comment as it loaded");
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            HookStatus.threw(FAMILY, LINK, cause == null ? failure : cause);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, LINK, failure);
        }
    }
}
