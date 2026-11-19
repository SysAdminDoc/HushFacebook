/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the comment body the patch adds its tap to, and hands over a See translation
 * handler the way the body's render does.
 */
public final class TranslateCommentsForTests {
    private TranslateCommentsForTests() {
    }

    /** A comment body with the patch's tap method: keeps each handler it's given and answers [answer]. */
    public static final class Body {
        static final List<Object> tapped = new CopyOnWriteArrayList<>();
        static volatile boolean answer = true;

        public static boolean hushfacebookTapLink(Object handler) {
            tapped.add(handler);
            return answer;
        }

        static void reset() {
            tapped.clear();
            answer = true;
        }
    }

    /** A comment body without the tap method, as on a build the patch never touched. */
    public static final class Untouched {
    }

    /** A comment body whose tap fails, as one whose state is gone would. */
    public static final class Broken {
        public static boolean hushfacebookTapLink(Object handler) {
            throw new IllegalStateException("Component context missing GlobalKey during state update");
        }
    }

    /** True when a comment body's See translation handler is tapped once the main thread is free. */
    public static boolean tapsTheLink() {
        Body.reset();
        try {
            Object handler = new Object();
            TranslateComments.link(handler, Body.class);
            shadowOf(Looper.getMainLooper()).idle();
            return Body.tapped.size() == 1 && Body.tapped.get(0) == handler;
        } finally {
            Body.reset();
        }
    }
}
