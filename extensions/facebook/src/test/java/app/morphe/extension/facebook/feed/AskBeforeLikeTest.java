/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Ask before a like (#437, #443), driven the way the patch leaves Facebook's Like handlers: each
 * asks the extension first and returns when the tap is held. While a kind's switch is on, a tap puts
 * the question up and likes nothing; Continue runs the same handler again, which likes this time,
 * and Cancel leaves it. Off, paused, before settings are ready or with no screen to ask on, the tap
 * likes as Facebook built it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AskBeforeLikeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String OWN = "Lapp/morphe/extension/facebook/feed/AskBeforeLikeTest$Footer;";
    private static final String FOOTER = OWN + "->like(Landroid/view/View;Ljava/lang/String;Z)V";
    private static final String REEL = OWN + "->reel(Landroid/view/View;" + OWN + "Z)V";
    private static final String COMMENT = OWN + "->comment(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String KEPT = "Lapp/morphe/extension/facebook/feed/AskBeforeLikeTest$Kept;";
    private static final String TOUCH = OWN + "->touch(Ljava/lang/Object;" + KEPT + ")V";
    private static final String CLICK = OWN + "->click(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String INLINE = OWN + "->inline(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String HEADER_FOLLOW = OWN + "->onClick(Landroid/view/View;)V";
    private static final String COLLAB_FOLLOW = OWN + "->dispatch(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BAR = "Lapp/morphe/extension/facebook/feed/AskBeforeLikeTest$StoryBar;";
    private static final String STORY = BAR + "->react(Landroid/view/View;Landroid/view/ViewGroup;Ljava/lang/Object;"
            + "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;I)V";
    private static final String SINGLE = "Lapp/morphe/extension/facebook/feed/AskBeforeLikeTest$SingleReaction;"
            + "->invoke(Ljava/lang/Object;)Ljava/lang/Object;";

    /** A stand-in for the component context the plugin footer's sender takes: it keeps a context, it isn't one. */
    static final class Kept {
        private final Context context;

        Kept(Context context) {
            this.context = context;
        }
    }

    /** A stand-in for litho's click event, which the footer's click lambda takes: it keeps the tapped view. */
    static final class Event {
        final View view;

        Event(View view) {
            this.view = view;
        }
    }

    /** A stand-in for Facebook's Like handlers, with the hook first as the patch puts it. */
    static final class Footer {
        int likes;
        int painted;
        String lastPost;
        boolean lastFlag;

        void like(View view, String post, boolean flag) {
            if (AskBeforeLike.hold(AskBeforeLike.POST_FOOTER, FOOTER, new Object[] {this, view, post, flag})) return;
            likes++;
            lastPost = post;
            lastFlag = flag;
        }

        static void reel(View view, Footer own, boolean flag) {
            if (AskBeforeLike.hold(AskBeforeLike.REEL, REEL, new Object[] {null, view, own, flag})) return;
            own.likes++;
            own.lastFlag = flag;
        }

        Object comment(Object view, Object post) {
            if (AskBeforeLike.hold(AskBeforeLike.COMMENT, COMMENT, new Object[] {this, view, post})) return "held";
            likes++;
            return "liked";
        }

        void touch(Object session, Kept kept) {
            if (AskBeforeLike.hold(AskBeforeLike.POST_TOUCH, TOUCH, new Object[] {this, session, kept})) return;
            likes++;
        }

        /** The click lambda: runs the hooked footer handler, then paints the button liked. */
        Object click(Object event) {
            if (AskBeforeLike.hold(AskBeforeLike.POST_CLICK, CLICK, new Object[] {this, event})) return "held";
            like(((Event) event).view, "post", true);
            painted++;
            return "clicked";
        }

        /** The inline comment Like's lambda: the span hands it the tapped view and the like it means. */
        Object inline(Object widget, Object state) {
            if (AskBeforeLike.hold(AskBeforeLike.COMMENT_INLINE, INLINE, new Object[] {this, widget, state})) return "held";
            likes++;
            return "liked";
        }

        int follows;

        /** The post header's Follow link: an onClick on the tapped view. */
        public void onClick(View view) {
            if (AskBeforeLike.hold(AskBeforeLike.POST_FOLLOW, HEADER_FOLLOW, new Object[] {this, view})) return;
            follows++;
        }

        /** The collaborator reel's event dispatcher: its click case follows and returns null, as the hook's exit does. */
        Object dispatch(Object handler, Object event) {
            if (AskBeforeLike.hold(AskBeforeLike.REEL_COLLAB_FOLLOW, COLLAB_FOLLOW, new Object[] {this, handler, event})) return null;
            follows++;
            return "followed";
        }
    }

    /**
     * A stand-in for the story viewer's reaction bar handler, with the hook first as the patch puts
     * it: the int is boxed into the call, and the story viewer's environment sits in a field.
     */
    static final class StoryBar {
        final Object environment;
        int reactions;
        String lastReaction;
        int lastPosition = -1;

        StoryBar(Object environment) {
            this.environment = environment;
        }

        void react(View view, ViewGroup bar, Object bucket, Object card, Object reaction, String source, int position) {
            if (AskBeforeLike.hold(AskBeforeLike.STORY_REACTION, STORY,
                    new Object[] {this, view, bar, bucket, card, reaction, source, position})) return;
            reactions++;
            lastReaction = (String) reaction;
            lastPosition = position;
        }
    }

    /** A stand-in for the single reaction button's lambda: it keeps the environment and returns Unit either way. */
    static final class SingleReaction {
        final Object environment;
        int reactions;

        SingleReaction(Object environment) {
            this.environment = environment;
        }

        Object invoke(Object event) {
            if (AskBeforeLike.hold(AskBeforeLike.STORY_SINGLE_REACTION, SINGLE, new Object[] {this, event})) return "unit";
            reactions++;
            return "unit";
        }
    }

    private Activity screen;
    private View button;
    private Footer footer;
    private Object environment;
    private StoryBar bar;

    @Before
    public void setUp() {
        HookStatus.clear();
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        button = new View(screen);
        footer = new Footer();
        environment = new Object();
        bar = new StoryBar(environment);
    }

    @After
    public void restore() {
        AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
        if (question != null && question.isShowing()) question.dismiss();
        idle();
        PauseForTests.resume();
        Settings.ASK_BEFORE_LIKING_POSTS.resetToDefault();
        Settings.ASK_BEFORE_LIKING_REELS.resetToDefault();
        Settings.ASK_BEFORE_LIKING_COMMENTS.resetToDefault();
        Settings.ASK_BEFORE_FOLLOWING_POSTS.resetToDefault();
        Settings.ASK_BEFORE_FOLLOWING_REELS.resetToDefault();
        Settings.ASK_BEFORE_REACTING_TO_STORIES.resetToDefault();
        AskBeforeLike.pauserForTests = null;
        AskBeforeLike.pausedForTests = 0;
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The question showing now, or null. */
    private static AlertDialog showing() {
        AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
        return question != null && question.isShowing() ? question : null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.ASK_BEFORE_LIKE + ":")) return line;
        }
        return null;
    }

    private static void press(AlertDialog question, int which) {
        assertTrue(question.getButton(which).performClick());
        idle();
    }

    @Test
    public void everySwitchStartsOffAndOffATapLikesAsBefore() {
        assertFalse(Settings.ASK_BEFORE_LIKING_POSTS.defaultValue);
        assertFalse(Settings.ASK_BEFORE_LIKING_REELS.defaultValue);
        assertFalse(Settings.ASK_BEFORE_LIKING_COMMENTS.defaultValue);
        assertFalse(Settings.ASK_BEFORE_FOLLOWING_POSTS.defaultValue);
        assertFalse(Settings.ASK_BEFORE_FOLLOWING_REELS.defaultValue);
        assertFalse(Settings.ASK_BEFORE_REACTING_TO_STORIES.defaultValue);
        footer.like(button, "post", true);
        Footer.reel(button, footer, false);
        assertEquals("liked", footer.comment(button, "post"));
        assertEquals(3, footer.likes);
        assertNull("a question went up with every switch off", showing());
        assertEquals(FamilyNames.ASK_BEFORE_LIKE + ": invoked 3, 0 found, 0 missing", statusLine());
    }

    @Test
    public void onATapAsksAndOnlyContinueLikes() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        footer.like(button, "post", true);
        assertEquals("the tap liked before the answer", 0, footer.likes);
        AlertDialog question = showing();
        assertTrue("no question went up", question != null);
        assertEquals("Like or unlike this post?", shadowOf(question).getMessage().toString());

        press(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't like it", 1, footer.likes);
        assertEquals("the like went out with other arguments", "post", footer.lastPost);
        assertTrue(footer.lastFlag);
        assertNull(showing());
        assertTrue(statusLine(), statusLine().contains(AskBeforeLike.ASKED + " 1"));
    }

    @Test
    public void cancelLeavesItAndTheNextTapAsksAgain() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        footer.like(button, "post", false);
        press(showing(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Cancel liked it", 0, footer.likes);
        assertNull(showing());
        footer.like(button, "post", false);
        assertTrue("the next tap didn't ask", showing() != null);
        assertEquals(0, footer.likes);
    }

    @Test
    public void aTapWhileTheQuestionIsUpIsHeldWithoutASecondOne() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        footer.like(button, "post", false);
        AlertDialog first = showing();
        footer.like(button, "post", false);
        assertSame("a second question went up", first, showing());
        assertEquals(0, footer.likes);
        assertTrue(statusLine(), statusLine().contains(AskBeforeLike.HELD + " 1"));
        press(first, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue liked twice", 1, footer.likes);
    }

    @Test
    public void eachKindReadsItsOwnSwitchAndAsksItsOwnQuestion() {
        Settings.ASK_BEFORE_LIKING_REELS.save(true);
        footer.like(button, "post", false);
        assertEquals("comment", "liked", footer.comment(button, "post"));
        assertEquals(2, footer.likes);
        assertNull(showing());

        Footer.reel(button, footer, true);
        AlertDialog reel = showing();
        assertEquals("Like or unlike this reel?", shadowOf(reel).getMessage().toString());
        press(reel, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't run the static handler", 3, footer.likes);
        assertTrue(footer.lastFlag);

        Settings.ASK_BEFORE_LIKING_COMMENTS.save(true);
        assertEquals("a held comment like didn't return at once", "held", footer.comment(button, "post"));
        AlertDialog comment = showing();
        assertEquals("Like or unlike this comment?", shadowOf(comment).getMessage().toString());
        press(comment, AlertDialog.BUTTON_POSITIVE);
        assertEquals(4, footer.likes);
    }

    @Test
    public void pausedATapLikesAsBefore() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            footer.like(button, "post", false);
            assertNull(reason.name(), showing());
        }
        assertEquals(2, footer.likes);
        PauseForTests.resume();
        footer.like(button, "post", false);
        assertEquals(2, footer.likes);
    }

    @Test
    public void withNoScreenToAskOnTheTapLikes() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        footer.like(new View(RuntimeEnvironment.getApplication()), "post", false);
        footer.like(null, "post", false);
        assertEquals(2, footer.likes);
        assertNull(showing());
        screen.finish();
        footer.like(button, "post", false);
        assertEquals("a finishing screen got a question", 3, footer.likes);
    }

    @Test
    public void withNoViewTheQuestionGoesUpOverTheScreenAnArgumentKeeps() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        footer.touch("session", new Kept(RuntimeEnvironment.getApplication()));
        assertEquals("a kept app context held the tap", 1, footer.likes);
        assertNull("a kept app context put a question up", showing());

        footer.touch("session", new Kept(screen));
        assertEquals("the tap liked before the answer", 1, footer.likes);
        AlertDialog question = showing();
        assertTrue("no question went up over the kept screen", question != null);
        assertEquals("Like or unlike this post?", shadowOf(question).getMessage().toString());
        press(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't run the sender", 2, footer.likes);
    }

    @Test
    public void aClickAsksBeforeTheButtonChangesAndContinueAsksOnce() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        assertEquals("a held click didn't return at once", "held", footer.click(new Event(button)));
        assertEquals("the click liked before the answer", 0, footer.likes);
        assertEquals("the button changed before the answer", 0, footer.painted);
        AlertDialog question = showing();
        assertTrue("no question went up over the screen the event's view is on", question != null);
        assertEquals("Like or unlike this post?", shadowOf(question).getMessage().toString());
        press(question, AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Cancel liked it", 0, footer.likes);
        assertEquals("Cancel changed the button", 0, footer.painted);

        footer.click(new Event(button));
        AlertDialog again = showing();
        press(again, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't like it once", 1, footer.likes);
        assertEquals("Continue didn't change the button once", 1, footer.painted);
        assertNull("the handler inside the click asked a second time", showing());
        assertTrue(statusLine(), statusLine().contains(AskBeforeLike.ASKED + " 2"));
    }

    @Test
    public void anInlineCommentLikeUnderAPostAsksWithTheCommentSwitch() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        assertEquals("the posts switch held a comment like", "liked", footer.inline(button, "like"));
        assertNull(showing());

        Settings.ASK_BEFORE_LIKING_COMMENTS.save(true);
        assertEquals("a held inline like didn't return at once", "held", footer.inline(button, "like"));
        AlertDialog question = showing();
        assertTrue("no question went up over the screen the tapped view is on", question != null);
        assertEquals("Like or unlike this comment?", shadowOf(question).getMessage().toString());
        press(question, AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Cancel liked it", 1, footer.likes);

        footer.inline(button, "like");
        press(showing(), AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't like it once", 2, footer.likes);
        assertNull(showing());
    }

    @Test
    public void aFollowTapAsksWithItsOwnSwitchAndOnlyContinueFollows() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        Settings.ASK_BEFORE_LIKING_REELS.save(true);
        footer.onClick(button);
        assertEquals("a like switch held a follow", "followed", footer.dispatch("handler", new Event(button)));
        assertEquals(2, footer.follows);
        assertNull(showing());

        Settings.ASK_BEFORE_FOLLOWING_POSTS.save(true);
        footer.onClick(button);
        assertEquals("the tap followed before the answer", 2, footer.follows);
        AlertDialog question = showing();
        assertTrue("no question went up over the screen the tapped view is on", question != null);
        assertEquals("Follow or unfollow this account?", shadowOf(question).getMessage().toString());
        press(question, AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Cancel followed", 2, footer.follows);
        footer.onClick(button);
        press(showing(), AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't follow once", 3, footer.follows);
        assertNull(showing());

        assertEquals("the posts switch held a reel follow", "followed", footer.dispatch("handler", new Event(button)));
        Settings.ASK_BEFORE_FOLLOWING_REELS.save(true);
        assertNull("a held dispatch didn't return null at once", footer.dispatch("handler", new Event(button)));
        assertEquals(4, footer.follows);
        AlertDialog reel = showing();
        assertTrue("no question went up over the screen the click event's view is on", reel != null);
        assertEquals("Follow or unfollow this account?", shadowOf(reel).getMessage().toString());
        press(reel, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't run the dispatcher", 5, footer.follows);
        assertNull(showing());
        assertEquals(0, footer.likes);
        assertTrue(statusLine(), statusLine().contains(AskBeforeLike.ASKED_FOLLOW + " 3"));
        assertFalse("a follow counted as a like", statusLine().contains(AskBeforeLike.ASKED + " "));
    }

    @Test
    public void aStoryReactionAsksWithTheStoryPausedAndOnlyContinueSendsIt() {
        Settings.ASK_BEFORE_REACTING_TO_STORIES.save(true);
        AskBeforeLike.pauserForTests = environment;
        bar.react(button, new FrameLayout(screen), "bucket", "card", "love", "reply_bar", 3);
        assertEquals("the tap reacted before the answer", 0, bar.reactions);
        AlertDialog question = showing();
        assertTrue("no question went up", question != null);
        assertEquals("React to this story?", shadowOf(question).getMessage().toString());
        assertEquals("the story kept playing under the question", 1, AskBeforeLike.pausedForTests);

        press(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't send the reaction", 1, bar.reactions);
        assertEquals("love", bar.lastReaction);
        assertEquals("the reaction went out at another position", 3, bar.lastPosition);
        assertEquals("the story stayed paused after Continue", 0, AskBeforeLike.pausedForTests);
        assertNull(showing());
        assertTrue(statusLine(), statusLine().contains(AskBeforeLike.ASKED_STORY + " 1"));
        assertFalse("a story reaction counted as a like", statusLine().contains(AskBeforeLike.ASKED + " "));
    }

    @Test
    public void cancelSendsNoReactionAndTheStoryPlaysOn() {
        Settings.ASK_BEFORE_REACTING_TO_STORIES.save(true);
        AskBeforeLike.pauserForTests = environment;
        bar.react(button, new FrameLayout(screen), "bucket", "card", "haha", "long_press", 0);
        assertEquals(1, AskBeforeLike.pausedForTests);
        press(showing(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Cancel sent the reaction", 0, bar.reactions);
        assertEquals("the story stayed paused after Cancel", 0, AskBeforeLike.pausedForTests);
        assertNull(showing());
    }

    @Test
    public void offOrUnderTheOtherSwitchesAStoryReactionGoesOutAsBefore() {
        AskBeforeLike.pauserForTests = environment;
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        Settings.ASK_BEFORE_LIKING_REELS.save(true);
        Settings.ASK_BEFORE_FOLLOWING_REELS.save(true);
        bar.react(button, new FrameLayout(screen), "bucket", "card", "wow", "reply_bar", 2);
        assertEquals("another switch held a story reaction", 1, bar.reactions);
        assertEquals(2, bar.lastPosition);
        assertNull(showing());
        assertEquals("the story paused with its switch off", 0, AskBeforeLike.pausedForTests);

        Settings.ASK_BEFORE_LIKING_POSTS.save(false);
        Settings.ASK_BEFORE_REACTING_TO_STORIES.save(true);
        footer.like(button, "post", false);
        assertEquals("the story switch held a post like", 1, footer.likes);
        assertNull(showing());
    }

    @Test
    public void withNoPauserTheStoryQuestionStillGoesUp() {
        Settings.ASK_BEFORE_REACTING_TO_STORIES.save(true);
        bar.react(button, new FrameLayout(screen), "bucket", "card", "care", "reply_bar", 1);
        AlertDialog question = showing();
        assertTrue("no question went up without a pauser", question != null);
        assertEquals(0, AskBeforeLike.pausedForTests);
        press(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals(1, bar.reactions);
        assertEquals(0, AskBeforeLike.pausedForTests);
    }

    @Test
    public void theSingleReactionButtonAsksAndContinueSendsItOnce() {
        Settings.ASK_BEFORE_REACTING_TO_STORIES.save(true);
        AskBeforeLike.pauserForTests = environment;
        SingleReaction single = new SingleReaction(environment);
        assertEquals("a held tap didn't return Unit at once", "unit", single.invoke(new Event(button)));
        assertEquals(0, single.reactions);
        AlertDialog question = showing();
        assertTrue("no question went up over the screen the event's view is on", question != null);
        assertEquals("React to this story?", shadowOf(question).getMessage().toString());
        assertEquals(1, AskBeforeLike.pausedForTests);
        press(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue didn't send it once", 1, single.reactions);
        assertEquals(0, AskBeforeLike.pausedForTests);
        assertNull(showing());
    }

    @Test
    public void aHandlerThatCantBeFoundAgainIsCountedNotThrown() {
        Settings.ASK_BEFORE_LIKING_POSTS.save(true);
        assertTrue(AskBeforeLike.hold(AskBeforeLike.POST_FOOTER, OWN + "->gone(Landroid/view/View;)V",
                new Object[] {footer, button}));
        press(showing(), AlertDialog.BUTTON_POSITIVE);
        assertEquals(0, footer.likes);
        assertTrue(statusLine(), statusLine().contains("threw"));
        // The failed replay left nothing behind: the next tap asks as usual.
        footer.like(button, "post", false);
        assertTrue(showing() != null);
    }

    @Test
    public void descriptorsNameTheirTypes() throws Exception {
        assertSame(boolean.class, AskBeforeLike.type("Z"));
        assertSame(long.class, AskBeforeLike.type("J"));
        assertSame(String.class, AskBeforeLike.type("Ljava/lang/String;"));
        assertSame(String[][].class, AskBeforeLike.type("[[Ljava/lang/String;"));
        assertSame(int[].class, AskBeforeLike.type("[I"));
        assertEquals(Footer.class.getDeclaredMethod("reel", View.class, Footer.class, boolean.class),
                AskBeforeLike.handler(REEL));
        assertSame("a handler was looked up twice", AskBeforeLike.handler(FOOTER), AskBeforeLike.handler(FOOTER));
    }
}
