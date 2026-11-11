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
    }

    private Activity screen;
    private View button;
    private Footer footer;

    @Before
    public void setUp() {
        HookStatus.clear();
        screen = Robolectric.buildActivity(Activity.class).setup().get();
        button = new View(screen);
        footer = new Footer();
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
