/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.robolectric.RuntimeEnvironment;

/** A story laid out as Facebook has it, for {@link StoryProgressBar}: segments in a header on a full-height card. */
public final class StoryProgressBarForTests {
    private StoryProgressBarForTests() {
    }

    /** The views of one story: the window's root, the story's card and the segments at its top. */
    public static final class Story {
        public final FrameLayout root;
        public final View card;
        public final View segments;
        private final View page;
        private final View header;

        Story(FrameLayout root, View card, View page, View header, View segments) {
            this.root = root;
            this.card = card;
            this.page = page;
            this.header = header;
            this.segments = segments;
        }

        /** Lays the views out as {@link #story()} says, again after a window laid them out its own way. */
        public void layOut() {
            root.layout(0, 0, 1080, 2200);
            card.layout(0, 100, 1080, 2100);
            page.layout(0, 0, 1080, 1000);
            header.layout(30, 0, 1080, 200);
            segments.layout(20, 40, 1020, 48);
        }
    }

    /**
     * A 1080 by 2200 window holding a 2000 tall card 100 down, scrolled 7 across and 3 down, holding
     * a 1000 tall page scrolled 10 across, its 200 tall header 30 in and nudged 5 more, and in that the
     * segments, 1000 wide, 20 in from the left and padded 4 on the left and 6 on the right. Where
     * the card's overlay draws, the segments' padded part runs from 49 to 1039.
     */
    public static Story story() {
        Context app = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(app);
        FrameLayout card = new FrameLayout(app);
        FrameLayout page = new FrameLayout(app);
        FrameLayout header = new FrameLayout(app);
        View segments = new View(app);
        header.addView(segments);
        page.addView(header);
        card.addView(page);
        root.addView(card);
        Story story = new Story(root, card, page, header, segments);
        story.layOut();
        card.scrollTo(7, 3);
        page.scrollTo(10, 0);
        header.setTranslationX(5);
        segments.setPadding(4, 0, 6, 0);
        return story;
    }

    /** Whether a story's card gets a bar when its segments move. */
    public static boolean drawsABar() {
        Story story = story();
        StoryProgressBar.segmentsMoved(story.segments, 500);
        StoryProgressBar.Bar bar = StoryProgressBar.barOn(story.card);
        if (bar != null) bar.takeOff(story.card);
        return bar != null;
    }
}
