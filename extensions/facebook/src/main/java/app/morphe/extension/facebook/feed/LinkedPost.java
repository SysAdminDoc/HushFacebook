/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Activity;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;

/**
 * The post a link lands Home on (issue #306).
 *
 * <p>A link to a post from another app, a message or a notification lands Facebook on Home with
 * that post first: the deep link goes through {@link #LANDING_SCREEN} on its way to Home, Home asks
 * for the feed with the post's story token, and the response's first edge is the post, under a
 * "From your messages" header. On an account that doesn't follow the post's author Facebook serves
 * that edge as an ENGAGEMENT story whose recommendation flag reads true, the shape of a suggested
 * post, so "Suggested for you" hid the one post the person asked for and Home showed everything
 * but it. A post the person saw before reads the same way to Hide seen posts.
 *
 * <p>The landing screen marks the moment, and the first post the feed guard judges within
 * {@link #WINDOW_MS} of it is the one the link opened, so whatever reason a rule has for hiding it
 * is set aside. That holds for the ad rules too: a boosted page post comes back from the link as
 * a sponsored edge until Facebook stops serving it as an ad, and holding ads back hid the linked
 * post and kept the suggested post after it instead. One link keeps one post: the mark is spent on that first post whether
 * or not a rule would have hidden it, and a mark older than the window is spent on nothing. The
 * post can't be matched to the link by id: a story answers a cache hash, not its post id. A link
 * to a group post opens the group's screen with the post pinned, never Home, so nothing here
 * applies to it.
 */
public final class LinkedPost {
    /**
     * How long after the landing screen is created the first post still counts as the link's. A
     * cold start and a slow network both fit, as for {@code TapToPlay}'s link window.
     */
    static final long WINDOW_MS = 15_000;

    /**
     * The screen Facebook lands a link to a post through, by simple name: its package is Facebook's
     * own and the name has held since 577. Its intent's data is {@code fb://native_post/<token>}.
     */
    static final String LANDING_SCREEN = "NotificationsLandToFeedDeepLinkLoadingActivity";

    /** The screen that pins the post at the top of Home, created after the landing one on 584. */
    static final String HIGHLIGHT_SCREEN = "TopOfFeedHighlightLaunchActivity";

    /** The kind the feed route counts a kept post under, so a report says how often this ran. */
    static final String KEPT_KIND = "kept for a link";

    private static final long NONE = Long.MIN_VALUE;
    /** When a link last landed, on the uptime clock, or {@link #NONE}. */
    private static final AtomicLong LANDED_AT = new AtomicLong(NONE);

    private LinkedPost() {
    }

    /** Hushfacebook's activity watcher, as each Facebook screen is created. */
    public static void activityCreated(Activity activity) {
        try {
            screenCreated(activity.getClass().getSimpleName(), SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            Logger.printException(() -> "Linked post: could not read the screen", failure);
        }
    }

    /**
     * The rule, with the screen's simple name and the clock passed in: a landing screen marks
     * [now] and answers true, any other screen changes nothing.
     */
    static boolean screenCreated(@Nullable String screen, long now) {
        if (!LANDING_SCREEN.equals(screen) && !HIGHLIGHT_SCREEN.equals(screen)) return false;
        LANDED_AT.set(now);
        Logger.printDebug(() -> "Linked post: " + screen + " opened, so the first post in the next "
                + WINDOW_MS / 1000 + " s is the link's");
        return true;
    }

    /** Whether a post judged now is the first since a link landed. Taking it spends the mark. */
    static boolean takeFirst() {
        return takeFirst(SystemClock.uptimeMillis());
    }

    /**
     * Whether a post judged at [now] is the first since a link landed. Taking it spends the mark,
     * and so does a mark past its window. A clock read before the link came isn't the link's and
     * leaves the mark for the post that follows it.
     */
    static boolean takeFirst(long now) {
        long at = LANDED_AT.get();
        if (at == NONE || now < at || !LANDED_AT.compareAndSet(at, NONE)) return false;
        return now - at <= WINDOW_MS;
    }

    /**
     * Whether the unit is a post, a GraphQLStory. Only a post lands from a link, so a row between
     * posts never spends the mark. With the story class missing, nothing is a post.
     */
    static boolean isPost(@Nullable Object unit) {
        if (unit == null) return false;
        PostText.Members found = PostText.members();
        return found.story != null && found.story.isInstance(unit);
    }

    static void clearForTests() {
        LANDED_AT.set(NONE);
    }
}
