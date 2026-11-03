/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import com.facebook.graphql.model.GraphQLStory;

import java.io.File;
import java.io.IOException;

/**
 * Hide seen posts with its patch-time flag filled in, for a test outside this package. A test JVM
 * has no patched {@code SettingsStatus}, so the rule would see itself as not in the build.
 */
public final class SeenPostsForTests {
    private SeenPostsForTests() {
    }

    /** A post with a cache id, the way Facebook's own story answers {@code getCacheId()}. */
    public static class Story extends GraphQLStory {
        private final String id;

        public Story(String id) {
            this.id = id;
        }

        public String getCacheId() {
            return id;
        }
    }

    /**
     * Whether a post the store remembers is kept out by the rule. True while the switch is on and
     * Hushfacebook runs, false paused or off. The store is a temporary file, forgotten after.
     */
    public static boolean hidesARememberedPost() {
        return withStore(() -> {
            Story story = new Story("pause-probe");
            return seenTwiceThenHidden(story);
        });
    }

    /** Whether the post goes through the public seen hook and is then hidden. For the cold start check. */
    public static boolean hidesBeforeTheContext() {
        return withStore(() -> {
            Story story = new Story("cold-start-probe");
            SeenPosts.seen(story);
            return seenTwiceThenHidden(story);
        });
    }

    /**
     * Remembers [story], shows it once, remembers it again and answers whether the rule hides it then.
     * With `Let each post come back once` on as well, the first look lets a post back, so a check
     * that turns every switch on still sees the hide.
     */
    private static boolean seenTwiceThenHidden(Story story) {
        String id = SeenPosts.idOf(story);
        SeenPosts.remember(id, System.currentTimeMillis());
        SeenPosts.hideReason(story);
        SeenPosts.remember(id, System.currentTimeMillis());
        return SeenPosts.hideReason(story) != null;
    }

    /** Points the store at [file] for a test outside this package, with every write run at once. */
    public static void useStore(File file) {
        SeenPosts.resetForTests();
        SeenPosts.inBuildForTests = Boolean.TRUE;
        SeenPosts.fileForTests = file;
        SeenPosts.laterForTests = () -> { };
        SeenPosts.backgroundForTests = Runnable::run;
    }

    /** Puts the store back as a test found it. */
    public static void forgetStore() {
        SeenPosts.resetForTests();
    }

    /** Remembers a post by an id of its own and writes the file. */
    public static void rememberAndWrite(String id) {
        SeenPosts.remember(id, System.currentTimeMillis());
        SeenPosts.writeNow();
    }

    /** How many posts the store remembers. */
    public static int size() {
        return SeenPosts.size();
    }

    private interface Check {
        boolean run();
    }

    private static boolean withStore(Check check) {
        File file;
        try {
            file = File.createTempFile("seen-posts", ".txt");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        SeenPosts.resetForTests();
        SeenPosts.inBuildForTests = Boolean.TRUE;
        SeenPosts.fileForTests = file;
        SeenPosts.laterForTests = () -> { };
        SeenPosts.backgroundForTests = Runnable::run;
        try {
            return check.run();
        } finally {
            SeenPosts.resetForTests();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
