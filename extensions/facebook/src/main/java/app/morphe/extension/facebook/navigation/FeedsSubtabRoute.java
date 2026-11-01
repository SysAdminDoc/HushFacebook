/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Opens the Feeds tab on the filter chosen in the settings, once, after a start from the launcher
 * icon that {@link StartTabRoute} sent to Feeds (#56), or to the Feeds page when the tab bar has no
 * Feeds tab. The page is the same fragment as the tab, on a screen of its own.
 *
 * <p>Facebook already has a way to pick one of the Feeds tab's filters. Its main screen hands an
 * intent meant for the tab to the tab's {@code handleDeeplinkFromMainActivity}, which looks for the
 * feed type it last showed among the tab's filters and picks that filter when they have it. A start
 * from the icon never calls it, so the patch calls {@link #feedsResumed} as the Feeds tab's
 * {@code onResume} ends, and when this start asked for a filter, that calls the handler. Where the
 * handler reads the feed type it last showed, the patch asks {@link #feedType}, which answers the
 * chosen filter's instead while that call lasts, and the lookup's answer goes through
 * {@link #filterFound}, which says whether the tab had it. A filter has a name on each of
 * Facebook's two lists of filters, so each is asked for in turn until the tab has one. A filter it
 * hasn't got under either leaves the tab as it opened.
 *
 * <p>A filter picked this way shows its page but never asks for its posts, so it reads "You're all
 * caught up" with nothing above it (#330). A second tap on the Feeds tab does ask, but on a start on
 * the Feeds tab Facebook drops that ask for the first several seconds, about eight on an emulator,
 * with no sign that it did. So once the chosen filter's page is the one showing, {@link #askForPosts}
 * does what that second tap does each second, until {@link #filterPostsRequested} says a request for
 * the filter went out.
 *
 * <p>It's asked once: the first time the Feeds tab resumes after such a start, whether as the tab
 * Facebook opened on or later from the Menu, and not again until the next start from the icon.
 * Everything else picks as Facebook does: a link, a notification, a screen restored after Android
 * put it away, and every filter the person taps. A link the main screen hands the Feeds tab before
 * it's asked cancels the ask, so the link still opens where it leads. The feed types are found by the names Facebook
 * keeps, the class's and the handler's, and by what each one is called. It fails open: when
 * anything isn't as expected, the Feeds tab opens as Facebook chooses.
 */
public final class FeedsSubtabRoute {
    /** Facebook's feed type class, whose name and whose feed types' names it keeps. */
    static final String FEED_TYPE_CLASS = "com.facebook.api.feedtype.FeedType";

    /** The Feeds tab's handler for an intent its main screen hands it. */
    static final String HANDLER = "handleDeeplinkFromMainActivity";

    /** The Feeds tab's feed type showing, whether its page has a list yet, and its second tap. */
    private static final String SHOWING = "getCurrentFeedType";
    private static final String LIST_MADE = "isListViewLoaded";
    private static final String RESELECT = "onTabReselection";

    /** What {@link #found} holds while no lookup has answered. */
    private static final int NOT_FOUND = -1;

    /** How often, and how many times, to look for the chosen filter's page once it's been picked. */
    private static final long SHOWN_LOOK_MS = 100;
    private static final int SHOWN_LOOKS = 50;

    /** How often, and how many times, to ask the chosen filter's page for its posts. */
    static final long ASK_MS = 1000;
    static final int ASKS = 30;

    /** The main screen whose start asked for a filter, and the filter, or null. */
    private static final class Armed {
        final WeakReference<Activity> screen;
        final FeedsSubtab subtab;

        Armed(Activity screen, FeedsSubtab subtab) {
            this.screen = new WeakReference<>(screen);
            this.subtab = subtab;
        }
    }

    @Nullable
    private static volatile Armed armed;

    /** The feed type asked for while {@link #feedsResumed} calls the handler, null the rest of the time. */
    @Nullable
    private static volatile Object asking;

    /** Where the tab's filters had the feed type asked for, while asking. */
    private static volatile int found = NOT_FOUND;

    /** Whether the chosen filter's page is still being asked for its posts. */
    private static volatile boolean awaitingPosts;

    private FeedsSubtabRoute() {
    }

    /** Asks for [subtab] the next time the Feeds tab resumes while [screen] is there. All asks for nothing. */
    static void arm(Activity screen, FeedsSubtab subtab) {
        armed = subtab == FeedsSubtab.ALL ? null : new Armed(screen, subtab);
    }

    /** Forgets what an earlier start asked for. */
    static void disarm() {
        armed = null;
        awaitingPosts = false;
    }

    /** Whether a feed request's parameters are worth reading for {@link #filterPostsRequested}. */
    public static boolean awaitingPosts() {
        return awaitingPosts;
    }

    /** A request for the chosen filter's posts went out, so the asking is done. */
    public static void filterPostsRequested() {
        if (!awaitingPosts) return;
        awaitingPosts = false;
        debug(() -> "the chosen filter's page asked for its posts.");
    }

    /**
     * Injection point, as the Feeds tab's {@code onResume} ends, with the tab. Asks the tab for the
     * filter this start asked for, the first time only. Never throws.
     */
    public static void feedsResumed(@Nullable Object feeds) {
        try {
            Armed asked = armed;
            if (asked == null || feeds == null) return;
            // Once, whatever happens next.
            armed = null;
            Activity screen = asked.screen.get();
            if (screen == null || screen.isDestroyed()) return;
            Class<?> types = Class.forName(FEED_TYPE_CLASS, false, feeds.getClass().getClassLoader());
            Method handler = feeds.getClass().getMethod(HANDLER, Intent.class);
            for (String name : asked.subtab.feedTypes) {
                Object type = feedTypeNamed(types, name);
                if (type == null) {
                    HookStatus.missingMember(FamilyNames.START_TAB, "feeds filter", FEED_TYPE_CLASS, name);
                    continue;
                }
                found = NOT_FOUND;
                asking = type;
                try {
                    handler.invoke(feeds, new Intent());
                } finally {
                    asking = null;
                }
                int at = found;
                if (at != NOT_FOUND) {
                    HookStatus.bound(FamilyNames.START_TAB, asked.subtab.fileValue + " filter");
                    debug(() -> "asked the Feeds tab for its " + name + " filter, which it has at " + at + ".");
                    lookAgain(feeds, type, 0);
                    return;
                }
            }
            debug(() -> "the Feeds tab has no " + asked.subtab.fileValue + " filter, so it stays as it opened.");
        } catch (Throwable failure) {
            threw(failure);
        }
    }

    /**
     * Once the Feeds tab shows the page for [type], with its list made, starts asking it for its
     * posts. The pick reaches the page a moment after the handler returns. Another filter showing by
     * then means a link or a tap got there first, so looking stops after a while with nothing asked.
     */
    private static void loadOnceShown(Object feeds, Object type, int looks) {
        try {
            if (!shows(feeds, type)) {
                lookAgain(feeds, type, looks + 1);
                return;
            }
            awaitingPosts = true;
            askForPosts(feeds, type, 0);
        } catch (Throwable failure) {
            threw(failure);
        }
    }

    /**
     * Does what a second tap on the tab does, which has the page ask for its posts, then again each
     * {@link #ASK_MS}. An ask Facebook drops does nothing. Stops when the request goes out, when the
     * page isn't the one showing any more because the person moved on, or after {@link #ASKS}.
     */
    private static void askForPosts(Object feeds, Object type, int asks) {
        try {
            if (!awaitingPosts) return;
            if (asks >= ASKS || !shows(feeds, type)) {
                awaitingPosts = false;
                debug(() -> "stopped asking the chosen filter's page for its posts after " + asks + " asks.");
                return;
            }
            feeds.getClass().getMethod(RESELECT).invoke(feeds);
            new Handler(Looper.getMainLooper()).postDelayed(() -> askForPosts(feeds, type, asks + 1), ASK_MS);
        } catch (Throwable failure) {
            awaitingPosts = false;
            threw(failure);
        }
    }

    private static void lookAgain(Object feeds, Object type, int looks) {
        if (looks >= SHOWN_LOOKS) {
            debug(() -> "the Feeds tab never showed the chosen filter's page, so nothing asked it for posts.");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> loadOnceShown(feeds, type, looks), SHOWN_LOOK_MS);
    }

    /** Whether the Feeds tab's current page is [type]'s, with its list made. Both methods keep their names. */
    private static boolean shows(Object feeds, Object type) throws ReflectiveOperationException {
        Class<?> tab = feeds.getClass();
        return type.equals(tab.getMethod(SHOWING).invoke(feeds))
                && Boolean.TRUE.equals(tab.getMethod(LIST_MADE).invoke(feeds));
    }

    /** What Facebook's method threw, rather than the reflection's wrapper around it. */
    private static void threw(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                ? failure.getCause() : failure;
        HookStatus.threw(FamilyNames.START_TAB, "feeds filter", cause);
    }

    /**
     * Injection point, where the Feeds tab's handler reads the feed type it last showed: the
     * chosen filter's while {@link #feedsResumed} asks, and [last] the rest of the time. The rest
     * of the time it's Facebook handing the tab a link, which wins: the start's ask is dropped.
     */
    @Nullable
    public static Object feedType(@Nullable Object last) {
        Object type = asking;
        if (type != null) return type;
        if (armed != null) {
            armed = null;
            debug(() -> "a link reached the Feeds tab first, so it opens where the link leads.");
        }
        return last;
    }

    /**
     * Injection point, right after the handler's lookup answers where the tab's filters have the
     * feed type: [index], or -1 when they haven't got it. Kept while {@link #feedsResumed} asks, and
     * handed back as it is.
     */
    public static int filterFound(int index) {
        if (asking != null) found = index < 0 ? NOT_FOUND : index;
        return index;
    }

    /** The feed type Facebook keeps as a constant of [types] and calls [name], or null. */
    @Nullable
    public static Object feedTypeNamed(Class<?> types, String name) {
        if (!FEED_TYPE_CLASS.equals(types.getName())) return null;
        for (Field field : types.getFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != types) continue;
            try {
                Object type = field.get(null);
                if (type != null && name.equals(String.valueOf(type))) return type;
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                // The next one.
            }
        }
        return null;
    }

    private static void debug(Logger.LogMessage message) {
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, StartTabRoute.SOURCE,
                () -> StartTabRoute.PREFIX + message.buildMessageString());
    }
}
