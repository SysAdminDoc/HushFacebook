/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import java.util.function.Function;

/**
 * The word filter's count of hidden posts, and the word and link rules over a story whose message
 * the test hands it, for a test outside this package.
 */
public final class PostWordsForTests {
    public static final String HIDE_WORD = PostWords.Verdict.HIDE.reason;
    public static final String KEEP_WORD = PostWords.Verdict.KEEP.reason;
    public static final String NO_MATCH = PostWords.Verdict.NO_MATCH.reason;
    public static final String LINK_POST = PostTypes.LINK;
    public static final String CAPTION_LINK = PostTypes.CAPTION_LINK;
    public static final String NO_SITE_NAMED = PostTypes.NO_SITE_NAMED;
    public static final String NO_TEXT = PostText.Outcome.NO_TEXT.reason;

    private PostWordsForTests() {
    }

    /** Counts [posts] more hidden posts, as the filter does for each one it hides. */
    public static void countHidden(int posts) {
        PostWords.HIDDEN.addAndGet(posts);
    }

    /** Forgets the count and the folded lists. */
    public static void forget() {
        PostWords.forgetForTests();
    }

    /** How many posts and reels the word filter has hidden. */
    public static int hidden() {
        return PostWords.HIDDEN.get();
    }

    /**
     * What the word lists make of [story] when its message holds the text [captions] gives it, no
     * message when that's null, the way the Reels tab reads a reel's caption. It shares no post and
     * has no link cards.
     */
    public static String storyKind(Object story, Function<Object, String> captions) {
        return PostWords.storyKind(story, message(captions), unit -> null, unit -> null, unit -> null);
    }

    /**
     * What Hide link posts makes of [story] when its message holds the text [captions] gives it. It
     * has no attachments, so only a web address in its words can make it a link.
     */
    public static String captionLinkKind(Object story, Function<Object, String> captions) {
        PostTypes.Readers none = new PostTypes.Readers(unit -> null, unit -> null, unit -> null);
        return PostTypes.captionLinkKind(story, none, message(captions), unit -> null, unit -> null);
    }

    private static StoryFlag.Accessor message(Function<Object, String> captions) {
        return unit -> {
            String caption = captions.apply(unit);
            return caption == null ? null : FeedGuardForTests.postText(caption);
        };
    }
}
