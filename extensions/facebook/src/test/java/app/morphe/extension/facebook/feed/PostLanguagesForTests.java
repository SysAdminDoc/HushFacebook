/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import java.util.Map;
import java.util.function.Function;

/**
 * Hide posts in other languages for a test outside this package: a stand-in for the phone's
 * classifier, and the rule over a story whose message the test hands it, the way the Reels tab
 * reads a reel's caption.
 */
public final class PostLanguagesForTests {
    public static final String REASON = PostLanguages.REASON;
    public static final String KEPT = PostLanguages.KEPT;
    public static final String UNSURE = PostLanguages.UNSURE;
    public static final String TOO_SHORT = PostLanguages.TOO_SHORT;
    public static final String NO_TEXT = PostText.Outcome.NO_TEXT.reason;

    private PostLanguagesForTests() {
    }

    /** The phone's guesses come from [languages] by text, none when a text isn't in it, and the main thread waits. */
    public static void guessFrom(Map<String, String> languages) {
        PostLanguages.forgetForTests();
        PostLanguages.detectorForTests(languages::get, true);
    }

    /** The phone's classifier again, with no guesses remembered. */
    public static void restore() {
        PostLanguages.detectorForTests(null, false);
        PostLanguages.forgetForTests();
    }

    /**
     * What the rule makes of [story] when its message holds the text [captions] gives it, no message
     * when that's null. It shares no post and has no link cards.
     */
    public static String storyKind(Object story, Function<Object, String> captions) {
        return PostLanguages.storyKind(story, unit -> {
            String caption = captions.apply(unit);
            return caption == null ? null : FeedGuardForTests.postText(caption);
        }, unit -> null, unit -> null, unit -> null);
    }
}
