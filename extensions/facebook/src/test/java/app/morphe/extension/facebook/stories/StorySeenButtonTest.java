/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import com.facebook.stories.viewer.activity.StoryViewerActivity;
import com.facebook.stories.viewer.activity.StoryViewerDefaultNavBarActivity;

/**
 * The Mark as seen button: it follows the card the seen helper is about to count, shows over the
 * story viewer only with both switches on, and a tap marks the card or takes the mark back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StorySeenButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        StorySeenForTests.reset();
        StorySeenButton.cardIds = card -> (String) card;
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        StorySeenForTests.reset();
    }

    private static ImageView eyeIn(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof StorySeenButton.Eye) {
                return (ImageView) child;
            }
        }
        return null;
    }

    /** The eye's top-left in the window. */
    private static int[] eyeAt(ImageView eye) {
        return new int[] {Math.round(eye.getLeft() + eye.getTranslationX()), Math.round(eye.getTop() + eye.getTranslationY())};
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_SEEN + ":")) return line;
        }
        return "";
    }

    private static void frame(Activity activity) {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    /** A story viewer with a letterboxed 9:16 card, the eye bound to it on [ACCOUNT]. */
    private static View letterboxedCard(StoryViewerActivity viewer) {
        FrameLayout content = viewer.findViewById(android.R.id.content);
        View card = new View(viewer);
        FrameLayout.LayoutParams letterboxed = new FrameLayout.LayoutParams(270, 480);
        letterboxed.topMargin = 60;
        content.addView(card, letterboxed);
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        return card;
    }

    /**
     * Issue #117: Facebook letterboxes the 9:16 story card on a taller screen, so the eye goes on the
     * card's header, not a place measured from the window's top. It hides while the card moves, as
     * when the viewer turns to the next person's stories or is dragged closed. Issue #150: it comes
     * back in the frame the card is in place, with the card's own header, not a moment later.
     */
    @Test
    public void theEyeSitsOnTheCardsHeaderAndHidesWhileTheCardMoves() {
        Settings.MARK_STORIES_SEEN.save(true);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        View card = letterboxedCard(viewer);
        assertSame(card, StorySeenButton.findCard(viewer.getWindow().getDecorView()));

        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);
        // Growing out of the tray as the viewer opens: scaled, so the eye waits.
        card.setScaleX(0.5f);
        card.setScaleY(0.5f);
        frame(viewer);
        assertEquals("the eye showed while the card was still coming in", View.INVISIBLE, eye.getVisibility());
        card.setScaleX(1f);
        card.setScaleY(1f);
        frame(viewer);
        assertEquals("the eye came later than the card's own header", View.VISIBLE, eye.getVisibility());
        float density = viewer.getResources().getDisplayMetrics().density;
        int[] cardAt = new int[2];
        card.getLocationInWindow(cardAt);
        int size = Math.round(StorySeenButton.SIZE_DP * density);
        assertEquals("not left of the card's ⌄ and ⋯", cardAt[0] + 270 - Math.round(StorySeenButton.END_DP * density) - size, eyeAt(eye)[0]);
        assertEquals("not on the card's header", cardAt[1] + Math.round(StorySeenButton.CARD_TOP_DP * density), eyeAt(eye)[1]);

        // Turned away for the next person's stories: the card is no longer at the window's left.
        card.setTranslationX(150);
        frame(viewer);
        assertEquals("the eye stayed while the card turned away", View.INVISIBLE, eye.getVisibility());
        ShadowLooper.idleMainLooper(StorySeenButton.STILL_TRANSFORMED_MS, TimeUnit.MILLISECONDS);
        frame(viewer);
        assertEquals(View.INVISIBLE, eye.getVisibility());

        // Dragged down to close: the card is still found, but moved, so the eye waits.
        card.setTranslationX(0);
        card.setTranslationY(300);
        frame(viewer);
        assertEquals("the eye stayed over the feed while the viewer was dragged closed", View.INVISIBLE, eye.getVisibility());
        ShadowLooper.idleMainLooper(StorySeenButton.STILL_TRANSFORMED_MS / 2, TimeUnit.MILLISECONDS);
        assertEquals("a card moved from its place counted as put after a moment", View.INVISIBLE, eye.getVisibility());

        // Back in place, it shows on the header again in the same frame.
        card.setTranslationY(0);
        frame(viewer);
        assertEquals(View.VISIBLE, eye.getVisibility());
        assertEquals(cardAt[1] + Math.round(StorySeenButton.CARD_TOP_DP * density), eyeAt(eye)[1]);

        StorySeenButton.activityPaused(viewer);
        assertEquals(View.GONE, eye.getVisibility());
        controller.pause().stop().destroy();
    }

    /**
     * Issue #150: while the story is held, Facebook hides its header (progress bar, name and menu)
     * and the eye hides with it; on release both come back. The story viewer's state says so through
     * its long press flag, and states that don't change it leave the eye alone.
     */
    @Test
    public void theEyeHidesWhileTheStoryIsHeldAndComesBackOnRelease() {
        Settings.MARK_STORIES_SEEN.save(true);
        StorySeenButton.longPresses = state -> (Boolean) state;
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        View card = letterboxedCard(viewer);
        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);
        frame(viewer);
        assertEquals(View.VISIBLE, eye.getVisibility());

        StorySeenButton.onState(Boolean.TRUE);
        assertTrue(StorySeenButton.held());
        assertEquals("the eye stayed while Facebook hid its header", View.INVISIBLE, eye.getVisibility());
        frame(viewer);
        assertEquals("the next frame brought the eye back while still held", View.INVISIBLE, eye.getVisibility());
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        StorySeenButton.refresh();
        assertEquals("a refresh brought the eye back while still held", View.INVISIBLE, eye.getVisibility());
        assertEquals("the eye forgot its card while held", "c1", ((StorySeenButton.Shown) eye.getTag()).card);

        StorySeenButton.onState(Boolean.FALSE);
        assertFalse(StorySeenButton.held());
        assertEquals("the eye didn't come back with the header", View.VISIBLE, eye.getVisibility());
        int[] cardAt = new int[2];
        card.getLocationInWindow(cardAt);
        float density = viewer.getResources().getDisplayMetrics().density;
        assertEquals(cardAt[1] + Math.round(StorySeenButton.CARD_TOP_DP * density), eyeAt(eye)[1]);

        // A state with no flag of its own (none read) counts as released.
        StorySeenButton.onState(Boolean.TRUE);
        StorySeenButton.onState(null);
        assertEquals(View.VISIBLE, eye.getVisibility());

        // Held when the viewer closes: the next viewer starts released.
        StorySeenButton.onState(Boolean.TRUE);
        viewer.finish();
        StorySeenButton.activityPaused(viewer);
        assertFalse("a closed viewer left the eye held", StorySeenButton.held());
        controller.pause().stop().destroy();
    }

    @Test
    public void offOrPausedTheButtonFollowsNoCard() {
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull("the button's switch starts off", StorySeenButton.shown());
        Settings.MARK_STORIES_SEEN.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull(StorySeenButton.shown());
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull("the button showed with views going out", StorySeenButton.shown());
    }

    @Test
    public void onTheButtonFollowsTheCardOnItsAccount() {
        Settings.MARK_STORIES_SEEN.save(true);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertEquals("c1", StorySeenButton.shown().card);
        assertEquals("100", StorySeenButton.shown().account);
        StorySeenButton.onCard(new Object(), null, "c2");
        assertNull("a card with no account kept the button", StorySeenButton.shown());
    }

    @Test
    public void theEyeShowsOverTheStoryViewerAndATapMarksTheCard() {
        Settings.MARK_STORIES_SEEN.save(true);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();

        ImageView eye = eyeIn(viewer);
        assertNotNull("no eye over the story viewer", eye);
        assertEquals(View.VISIBLE, eye.getVisibility());
        assertEquals("Mark as seen", eye.getContentDescription());
        assertTrue(eye.performClick());
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals(StoryMarks.State.MARKED, ((StorySeenButton.Eye) eye.getDrawable()).state());
        assertEquals("Marked as seen", eye.getContentDescription());
        // Marked, the eye dims and stays on seen: a second tap never takes the mark back (#557).
        assertFalse(eye.isEnabled());
        eye.performClick();
        assertEquals("a second tap took the mark back", StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals(StoryMarks.State.MARKED, ((StorySeenButton.Eye) eye.getDrawable()).state());

        // Once sent it stays dimmed, and a tap still changes nothing.
        StorySeenForTests.send(StorySeenForTests.ACCOUNT, StorySeenForTests.cards("c1"));
        StorySeenButton.refresh();
        assertFalse(eye.isEnabled());
        assertEquals("Marked as seen and sent", eye.getContentDescription());

        StorySeenButton.activityPaused(viewer);
        assertEquals("the eye stayed after the viewer left", View.GONE, eye.getVisibility());
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c2");
        ShadowLooper.idleMainLooper();
        assertEquals("the eye came back without the viewer in front", View.GONE, eye.getVisibility());
        controller.pause().stop().destroy();
    }

    /** 584 opens the story viewer as one of its empty subclasses as well, one per nav bar look. */
    @Test
    public void theEyeShowsOverAStoryViewerSubclass() {
        Settings.MARK_STORIES_SEEN.save(true);
        ActivityController<StoryViewerDefaultNavBarActivity> controller =
                Robolectric.buildActivity(StoryViewerDefaultNavBarActivity.class).setup();
        StoryViewerDefaultNavBarActivity viewer = controller.get();
        StorySeenButton.activityCreated(viewer);
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(viewer);
        assertNotNull("no eye over a story viewer subclass", eye);
        assertEquals(View.VISIBLE, eye.getVisibility());
        StorySeenButton.activityPaused(viewer);
        assertEquals(View.GONE, eye.getVisibility());
        controller.pause().stop().destroy();
    }

    @Test
    public void anotherActivityNeverGetsTheEye() {
        Settings.MARK_STORIES_SEEN.save(true);
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        StorySeenButton.activityResumed(other);
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        assertNull(eyeIn(other));
    }

    @Test
    public void movingToACardTheHelperNeverCountedHidesTheEye() {
        Settings.MARK_STORIES_SEEN.save(true);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onActive("c1");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);
        assertEquals(View.VISIBLE, eye.getVisibility());

        // Facebook skips its count for c2, so only the activation names it.
        StorySeenButton.onActive("c2");
        ShadowLooper.idleMainLooper();
        assertNull("the eye stayed bound to the card the viewer left", StorySeenButton.shown());
        assertEquals(View.GONE, eye.getVisibility());

        // Back on c1, which the helper counted before, the eye returns for c1.
        StorySeenButton.onActive("c1");
        ShadowLooper.idleMainLooper();
        assertEquals("c1", StorySeenButton.shown().card);
        assertEquals(View.VISIBLE, eye.getVisibility());
        controller.pause().stop().destroy();
    }

    @Test
    public void aCardCountedAfterTheViewerMovedOnNeverGetsTheEye() {
        Settings.MARK_STORIES_SEEN.save(true);
        StorySeenButton.onActive("c2");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        assertNull("a late count took the eye", StorySeenButton.shown());
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c2");
        assertEquals("c2", StorySeenButton.shown().card);
    }

    @Test
    public void aTapAfterTheViewerMovedNeverMarksTheOldCard() {
        Settings.MARK_STORIES_SEEN.save(true);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onActive("c1");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);

        // The activation lands, but the main thread hasn't redrawn yet when the tap does.
        StorySeenButton.onActive("c2");
        eye.performClick();
        assertEquals("the old card was marked", StoryMarks.State.UNMARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals("the new card was marked", StoryMarks.State.UNMARKED, StorySeen.MARKS.state("100", "c2"));
        assertEquals(View.GONE, eye.getVisibility());
        controller.pause().stop().destroy();
    }

    @Test
    public void aTapSendsAHeldCardOnlyWhileItIsTheActiveOne() {
        Settings.MARK_STORIES_SEEN.save(true);
        List<String> sent = new ArrayList<>();
        StorySeenButton.sendNow = (account, card) -> sent.add(account + "/" + card);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);

        // No activation signal: the card is marked and waits for the next batch.
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);
        assertTrue(eye.performClick());
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertTrue("sent with no word that the card is on screen: " + sent, sent.isEmpty());
        assertTrue(eye.performClick());

        // Active and counted: the same tap sends it now.
        StorySeenButton.onActive("c1");
        assertTrue(eye.performClick());
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals(Collections.singletonList("100/c1"), sent);
        controller.pause().stop().destroy();
    }

    /**
     * Issue #557: a reaction tells the story's owner you saw it, so it marks the card like a tap on
     * the eye. On the card on screen it goes out at once and the eye dims; a reaction sent while the
     * viewer is on another card only marks it; and with the eye's switch off nothing is marked.
     */
    @Test
    public void aReactionMarksTheStorySeenAndDimsTheEye() {
        HookStatus.clear();
        Settings.MARK_STORIES_SEEN.save(true);
        List<String> sent = new ArrayList<>();
        StorySeenButton.sendNow = (account, card) -> sent.add(account + "/" + card);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onActive("c1");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);
        assertEquals("Mark as seen", eye.getContentDescription());

        StorySeenButton.reacted(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals("the reaction's card wasn't sent at once", Collections.singletonList("100/c1"), sent);
        assertEquals("Marked as seen", eye.getContentDescription());
        assertFalse("the eye stayed tappable after a reaction", eye.isEnabled());
        assertTrue(statusLine(), statusLine().contains(StorySeenButton.REACTED + " 1"));

        // A second reaction on the same story changes nothing and counts nothing new.
        StorySeenButton.reacted(StorySeenForTests.ACCOUNT, null, "c1");
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertTrue(statusLine(), statusLine().contains(StorySeenButton.REACTED + " 1"));

        // Not the card on screen: marked, and it goes with the next batch instead.
        sent.clear();
        StorySeenButton.reacted(StorySeenForTests.ACCOUNT, null, "c9");
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c9"));
        assertTrue("sent a card that isn't on screen: " + sent, sent.isEmpty());

        // The eye's switch off: a reaction marks nothing.
        Settings.MARK_STORIES_SEEN.save(false);
        StorySeenButton.reacted(StorySeenForTests.ACCOUNT, null, "c5");
        assertEquals(StoryMarks.State.UNMARKED, StorySeen.MARKS.state("100", "c5"));
        controller.pause().stop().destroy();
    }

    /**
     * The share sheet, or another screen, over the viewer and gone again: the viewer is still on
     * its card, so the eye comes back on it and a tap sends a held card at once. A viewer that
     * closes forgets its cards.
     */
    @Test
    public void aViewerThatComesBackKeepsItsCardAndOneThatClosesForgetsIt() {
        Settings.MARK_STORIES_SEEN.save(true);
        List<String> sent = new ArrayList<>();
        StorySeenButton.sendNow = (account, card) -> sent.add(account + "/" + card);
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityResumed(viewer);
        StorySeenButton.onActive("c1");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(viewer);
        assertNotNull(eye);

        StorySeenButton.activityPaused(viewer);
        assertEquals("the eye stayed while the share sheet was up", View.GONE, eye.getVisibility());
        StorySeenButton.activityResumed(viewer);
        ShadowLooper.idleMainLooper();
        assertEquals("the eye didn't come back with the viewer", View.VISIBLE, eye.getVisibility());
        assertTrue(eye.performClick());
        assertEquals(StoryMarks.State.MARKED, StorySeen.MARKS.state("100", "c1"));
        assertEquals("a held card waited after the viewer came back", Collections.singletonList("100/c1"), sent);
        // Facebook naming the same card again on the return finds its account still known.
        StorySeenButton.onActive("c1");
        assertEquals("c1", StorySeenButton.shown().card);

        viewer.finish();
        StorySeenButton.activityPaused(viewer);
        assertNull("a closed viewer kept its card", StorySeenButton.shown());
        StorySeenButton.onActive("c1");
        assertNull("a closed viewer's card kept its account", StorySeenButton.shown());
        controller.pause().stop().destroy();
    }

    /** A story viewer opening as Facebook's activity callbacks report it: created, then in front. */
    private static StoryViewerActivity openViewer(List<ActivityController<StoryViewerActivity>> controllers) {
        ActivityController<StoryViewerActivity> controller = Robolectric.buildActivity(StoryViewerActivity.class).setup();
        controllers.add(controller);
        StoryViewerActivity viewer = controller.get();
        StorySeenButton.activityCreated(viewer);
        StorySeenButton.activityResumed(viewer);
        ShadowLooper.idleMainLooper();
        return viewer;
    }

    /**
     * Facebook closes a viewer while the share sheet is over it, so its pause didn't know it was
     * closing. Its destroy forgets its card, and the next viewer shows no eye for it, so a quick
     * tap there can't mark or send the old story.
     */
    @Test
    public void aViewerClosedBehindAnotherScreenForgetsItsCard() {
        Settings.MARK_STORIES_SEEN.save(true);
        List<String> sent = new ArrayList<>();
        StorySeenButton.sendNow = (account, card) -> sent.add(account + "/" + card);
        List<ActivityController<StoryViewerActivity>> controllers = new ArrayList<>();
        StoryViewerActivity first = openViewer(controllers);
        StorySeenButton.onActive("c1");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        assertNotNull(eyeIn(first));

        StorySeenButton.activityPaused(first);
        first.finish();
        StorySeenButton.activityDestroyed(first);
        assertNull("a viewer closed behind the share sheet kept its card", StorySeenButton.shown());
        StorySeenButton.onActive("c1");
        assertNull("a closed viewer's card kept its account", StorySeenButton.shown());

        StoryViewerActivity second = openViewer(controllers);
        ImageView eye = eyeIn(second);
        assertTrue("the next viewer showed the closed one's eye", eye == null || eye.getVisibility() != View.VISIBLE);
        if (eye != null) eye.performClick();
        assertTrue("the closed viewer's story went out: " + sent, sent.isEmpty());
        assertEquals(StoryMarks.State.UNMARKED, StorySeen.MARKS.state("100", "c1"));
        for (ActivityController<StoryViewerActivity> controller : controllers) controller.pause().stop().destroy();
    }

    /**
     * A second viewer over one that's still open: the first one's card never binds the eye on the
     * second, the second's own card does, and the first closing behind it leaves that card alone.
     */
    @Test
    public void aCardNamedForAnotherViewerNeverBindsTheEye() {
        Settings.MARK_STORIES_SEEN.save(true);
        List<String> sent = new ArrayList<>();
        StorySeenButton.sendNow = (account, card) -> sent.add(account + "/" + card);
        List<ActivityController<StoryViewerActivity>> controllers = new ArrayList<>();
        StoryViewerActivity first = openViewer(controllers);
        StorySeenButton.onActive("c1");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c1");
        ShadowLooper.idleMainLooper();
        assertNotNull(eyeIn(first));

        StorySeenButton.activityPaused(first);
        StoryViewerActivity second = openViewer(controllers);
        ImageView early = eyeIn(second);
        assertTrue("the first viewer's card bound the eye on the second",
                early == null || early.getVisibility() != View.VISIBLE);

        StorySeenButton.onActive("c2");
        StorySeenButton.onCard(StorySeenForTests.ACCOUNT, null, "c2");
        ShadowLooper.idleMainLooper();
        ImageView eye = eyeIn(second);
        assertNotNull(eye);
        assertEquals(View.VISIBLE, eye.getVisibility());
        assertEquals("c2", ((StorySeenButton.Shown) eye.getTag()).card);

        first.finish();
        StorySeenButton.activityDestroyed(first);
        assertEquals("the first viewer closing took the second's card", "c2", StorySeenButton.shown().card);
        assertTrue(eye.performClick());
        assertEquals(Collections.singletonList("100/c2"), sent);
        for (ActivityController<StoryViewerActivity> controller : controllers) controller.pause().stop().destroy();
    }

    @Test
    public void marksLapseAfterADay() {
        AtomicLong now = new AtomicLong(1_000);
        StoryMarks marks = new StoryMarks(now::get);
        marks.mark("100", "c1");
        now.addAndGet(StoryMarks.LIFETIME_MS);
        assertSame(StoryMarks.State.UNMARKED, marks.state("100", "c1"));
        assertNull("a lapsed mark went out", marks.choose("100", StorySeenForTests.cards("c1"),
                new StorySeen.Call(null, null, null, null, null, null, false)));
    }
}
