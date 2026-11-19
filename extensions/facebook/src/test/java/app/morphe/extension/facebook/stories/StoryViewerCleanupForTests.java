/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.view.View;

import java.util.Arrays;
import java.util.List;

import org.robolectric.RuntimeEnvironment;

/** Story lists as the viewer's list runner hands them to {@link StoryViewerCleanup}, with a stand-in card. */
public final class StoryViewerCleanupForTests {
    /** A stand-in for Facebook's card bucket between stories that asks you to make one. */
    public static final class CreateCard {
    }

    /** Reads a {@link CreateCard} as the card, the way the patched question reads Facebook's own class. */
    private static final StoryViewerCleanup.CreateCards CARDS = new StoryViewerCleanup.CreateCards() {
        @Override
        public boolean isCard(Object bucket) {
            return bucket instanceof CreateCard;
        }
    };

    private StoryViewerCleanupForTests() {
    }

    /** What the hook hands back for [buckets]. */
    public static List<?> kept(List<?> buckets) {
        return StoryViewerCleanup.withoutCreateCards(buckets, CARDS);
    }

    /** Whether a list with a create card between two stories comes back without it. */
    public static boolean dropsACreateCard() {
        List<Object> buckets = Arrays.asList(new Object(), new CreateCard(), new Object());
        return kept(buckets).size() == 2;
    }

    /** Whether a page the cube turns halfway stays square to the screen (#521). */
    public static boolean flattensACubeTurn() {
        View page = new View(RuntimeEnvironment.getApplication());
        StoryViewerCleanup.cubeTurn(page, 45f);
        return page.getRotationY() == 0f;
    }
}
