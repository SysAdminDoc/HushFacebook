/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import com.facebook.graphservice.tree.TreeJNI;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Default comment order as the constructor of a comment request's params asks it, and as a comment
 * sheet's pick handler tells it about a pick: which requests get the chosen order, which keep their
 * own or Facebook's, and how a pick stays with its post.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DefaultCommentOrderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String POST = "ZmVlZGJhY2s6MTAx";
    private static final String OTHER_POST = "ZmVlZGJhY2s6MjAy";
    private static final String COMMENT = "Y29tbWVudDoxMDFfNQ==";

    private static final String RELEVANT = "RANKED_FILTERED_INTENT_V1";
    private static final String NEWEST = "RECENT_ACTIVITY_INTENT_V1";
    private static final String ALL = "RANKED_UNFILTERED_CHRONOLOGICAL_REPLIES_INTENT_V1";

    // The rows other posts' menus offer, as r7c7d7's diagnostic report on #110 listed them.
    private static final String RELEVANT_ROW = "CHRONOLOGICAL_FILTERED_INTENT_V1";
    private static final String NEWEST_ROW = "REVERSE_CHRONOLOGICAL_UNFILTERED_INTENT_V1";
    private static final String ALL_ROW = "CHRONOLOGICAL_UNFILTERED_INTENT_V1";

    @Before
    public void inBuild() {
        DefaultCommentOrderForTests.inBuild(Boolean.TRUE);
        DefaultCommentOrder.forget();
        DefaultCommentOrder.nowForTests = 1_000L;
        // The patch is in Morphe Manager's default selection with its switch off; these tests run with it on.
        Settings.DEFAULT_COMMENT_ORDER.save(true);
    }

    @After
    public void restore() {
        DefaultCommentOrderForTests.inBuild(null);
        DefaultCommentOrderForTests.order(null);
        DefaultCommentOrder.nowForTests = null;
        DefaultCommentOrder.mainForTests = null;
        DefaultCommentOrder.failNextForTests = null;
        DefaultCommentOrder.forget();
        PauseForTests.resume();
        Settings.DEFAULT_COMMENT_ORDER.resetToDefault();
        Settings.COMMENT_ORDER.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.DEFAULT_COMMENT_ORDER + ":")) return line;
        }
        return null;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    /** What a sheet's own request would name after a pick in its menu: the pick, then the request. */
    private static String pick(String token, String post) {
        DefaultCommentOrder.picked(token);
        return DefaultCommentOrder.requestedOrder(token, post, null);
    }

    @Test
    public void theTokensAreFacebooksOwn() {
        assertNull(CommentOrder.FACEBOOK.token);
        assertEquals(RELEVANT, CommentOrder.MOST_RELEVANT.token);
        assertEquals(NEWEST, CommentOrder.NEWEST.token);
        assertEquals(ALL, CommentOrder.ALL_COMMENTS.token);
        assertEquals("Oldest asks for All comments' other row", ALL_ROW, CommentOrder.OLDEST.token);
        for (CommentOrder order : CommentOrder.values()) {
            assertSame(order, CommentOrder.fromFile(order.fileValue));
            if (order.token != null && order != CommentOrder.OLDEST) {
                assertSame(order, CommentOrder.ofToken(order.token));
            }
        }
        assertTrue(CommentOrder.OLDEST.isNamedBy(ALL_ROW));
        assertNull(CommentOrder.fromFile("NEWEST"));
        assertNull(CommentOrder.fromFile(2));
        assertNull(CommentOrder.ofToken("STARS_INTENT_V1"));
        assertNull(CommentOrder.ofToken(null));
        assertSame("All comments' row in other menus", CommentOrder.ALL_COMMENTS, CommentOrder.ofToken(ALL_ROW));
        assertNull(CommentOrder.ofToken(NEWEST_ROW));
        assertNull(CommentOrder.ofToken(RELEVANT_ROW));
        assertFalse(CommentOrder.FACEBOOK.isNamedBy(null));
    }

    /** The switch starts off, and turned on with the order still Facebook's, nothing changes. */
    @Test
    public void onItsDefaultsARequestIsFacebooksOwn() {
        assertFalse("the switch starts off", Settings.DEFAULT_COMMENT_ORDER.defaultValue);
        assertSame(CommentOrder.FACEBOOK, Settings.COMMENT_ORDER.get());
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        assertEquals("", DefaultCommentOrder.requestedOrder("", POST, null));
        assertEquals(FamilyNames.DEFAULT_COMMENT_ORDER + ": invoked 2, 1 found, 0 missing", statusLine());
    }

    /** Newest and Most relevant go out up front. All comments waits for the menu, below. */
    @Test
    public void aRequestThatNamesNoOrderAsksForTheChosenOne() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, POST, null));
        assertEquals("an empty order is none", NEWEST, DefaultCommentOrder.requestedOrder("", POST, ""));
        Settings.COMMENT_ORDER.save(CommentOrder.MOST_RELEVANT);
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(null, OTHER_POST, null));
        assertFalse(DefaultCommentOrder.deferred(CommentOrder.NEWEST));
        assertFalse(DefaultCommentOrder.deferred(CommentOrder.MOST_RELEVANT));
        assertTrue(DefaultCommentOrder.deferred(CommentOrder.ALL_COMMENTS));
    }

    /** Oldest (#295) goes out up front on every post, even one whose menu offers no such row. */
    @Test
    public void oldestIsAskedForUpFront() {
        Settings.COMMENT_ORDER.save(CommentOrder.OLDEST);
        assertSame(CommentOrder.OLDEST, CommentOrder.fromFile("oldest"));
        assertFalse(DefaultCommentOrder.deferred(CommentOrder.OLDEST));
        assertEquals(ALL_ROW, DefaultCommentOrder.requestedOrder(null, POST, null));
        assertEquals(ALL_ROW, DefaultCommentOrder.requestedOrder("", OTHER_POST, ""));
        assertEquals("oldest (" + ALL_ROW + ")", DefaultCommentOrder.describe(CommentOrder.OLDEST));
    }

    /** A menu offering [offered], with Facebook's answer [selected], built for [post]. */
    private static List<Object> openMenu(String post, String selected, String... offered) {
        List<Object> tapped = new ArrayList<>();
        Function1<Object, Unit> pick = token -> {
            tapped.add(token);
            return Unit.INSTANCE;
        };
        DefaultCommentOrder.sortMenu(DefaultCommentOrderForTests.feedback(post, selected, offered), pick);
        return tapped;
    }

    /**
     * #110: asked for up front, All comments drew an answer with no comment at all on some friends'
     * posts. So the request goes out with Facebook's own choice, and the menu its answer draws picks
     * All comments the way a tap on that row does, on the main thread.
     */
    @Test
    public void allCommentsIsPickedInTheMenuItsAnswerDraws() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        List<Runnable> main = new ArrayList<>();
        DefaultCommentOrder.mainForTests = main::add;
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        assertEquals("an empty order stays empty", "", DefaultCommentOrder.requestedOrder("", OTHER_POST, null));

        List<Object> tapped = openMenu(POST, RELEVANT, RELEVANT, NEWEST, ALL);
        assertTrue("the pick waits for the main thread", tapped.isEmpty());
        assertEquals(1, main.size());
        main.get(0).run();
        assertEquals(Collections.singletonList(ALL), tapped);

        // The pick's own request names All comments, and it stays with that post like a tap's.
        DefaultCommentOrder.mainForTests = Runnable::run;
        List<Object> again = new ArrayList<>();
        DefaultCommentOrder.sortMenu(DefaultCommentOrderForTests.feedback(OTHER_POST, RELEVANT, RELEVANT, ALL),
                (Function1<Object, Unit>) token -> {
                    DefaultCommentOrder.picked((String) token);
                    again.add(DefaultCommentOrder.requestedOrder((String) token, OTHER_POST, null));
                    return Unit.INSTANCE;
                });
        assertEquals(Collections.singletonList(ALL), again);
        assertEquals("a refresh asks for it up front now", ALL, DefaultCommentOrder.requestedOrder(null, OTHER_POST, null));

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("All comments left for the menu 2"));
        assertTrue(line, line.contains("All comments picked in the menu 2"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Default comment order: kept all_comments (" + ALL
                + "), which its menu offered, for that post until Facebook restarts."));
    }

    /**
     * #384: the menu is built while Facebook is still taking in the sheet's first answer, and a pick
     * right then left the next page loading forever. The pick waits on the main thread first.
     */
    @Test
    public void thePickWaitsForTheSheetsFirstAnswerToLand() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        ShadowLooper main = shadowOf(Looper.getMainLooper());

        List<Object> tapped = openMenu(POST, RELEVANT, RELEVANT, NEWEST, ALL);
        main.idle();
        assertTrue("nothing picked at once", tapped.isEmpty());
        main.idleFor(Duration.ofMillis(DefaultCommentOrder.PICK_DELAY_MS - 1));
        assertTrue("nor before the delay is up", tapped.isEmpty());
        main.idleFor(Duration.ofMillis(1));
        assertEquals(Collections.singletonList(ALL), tapped);
    }

    /** Only the first menu after the request is picked in, within the window, and only for that post. */
    @Test
    public void onlyTheMenuOfARequestLeftForItIsPickedIn() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.mainForTests = Runnable::run;
        assertTrue("no request was left for it", openMenu(POST, RELEVANT, RELEVANT, ALL).isEmpty());

        DefaultCommentOrder.requestedOrder(null, POST, null);
        assertTrue("another post's menu", openMenu(OTHER_POST, RELEVANT, RELEVANT, ALL).isEmpty());
        assertEquals(1, openMenu(POST, RELEVANT, RELEVANT, ALL).size());
        assertTrue("a menu drawn again, as Litho does, is left alone", openMenu(POST, RELEVANT, RELEVANT, ALL).isEmpty());

        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        DefaultCommentOrder.nowForTests = 1_000L + DefaultCommentOrder.AWAIT_WINDOW_MS + 1;
        assertTrue("too late", openMenu(OTHER_POST, RELEVANT, RELEVANT, ALL).isEmpty());

        // A link to one comment isn't left for the menu, so its menu keeps Facebook's order.
        DefaultCommentOrder.nowForTests = 90_000L;
        assertNull(DefaultCommentOrder.requestedOrder(null, OTHER_POST, COMMENT));
        assertTrue(openMenu(OTHER_POST, RELEVANT, RELEVANT, ALL).isEmpty());
    }

    /** An answer that's All comments already, or a menu without it, stays as Facebook drew it. */
    @Test
    public void aMenuWithoutAllCommentsOrAlreadyOnItIsLeftAlone() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.mainForTests = Runnable::run;
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();

        DefaultCommentOrder.requestedOrder(null, POST, null);
        assertTrue(openMenu(POST, ALL, RELEVANT, ALL).isEmpty());
        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        assertTrue(openMenu(OTHER_POST, RELEVANT, RELEVANT, NEWEST).isEmpty());

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("Answer already All comments 1"));
        assertTrue(line, line.contains("Menu without All comments 1"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 2, occurrences(report, "Default comment order: left all_comments (" + ALL
                + ") for the post's menu to offer."));
        assertEquals(report, 1, occurrences(report, "Default comment order: Facebook answered all_comments (" + ALL
                + ") already."));
        assertEquals(report, 1, occurrences(report, "Default comment order: left a post whose menu offers ["
                + RELEVANT + ", " + NEWEST + "] in Facebook's order."));
        assertEquals(report, 0, occurrences(report, POST));
    }

    /**
     * #110: a refetch built from the first request's params names no order, so its answer comes back
     * in Facebook's order with nothing left waiting for the menu. A post that kept All comments gets
     * it picked again, once per answer: a menu drawn again before the pick's answer, or a server that
     * won't answer All comments, gets no second pick, and neither does a link to one of its comments.
     * A post picked on another order keeps that one.
     */
    @Test
    public void aPostThatKeptAllCommentsGetsItPickedAgainOncePerAnswer() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.mainForTests = Runnable::run;
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        List<Object> sent = new ArrayList<>();
        Function1<Object, Unit> sheet = token -> {
            DefaultCommentOrder.picked((String) token);
            sent.add(DefaultCommentOrder.requestedOrder((String) token, POST, null));
            return Unit.INSTANCE;
        };
        TreeJNI onRelevant = DefaultCommentOrderForTests.feedback(POST, RELEVANT, RELEVANT, NEWEST, ALL);
        TreeJNI onAll = DefaultCommentOrderForTests.feedback(POST, ALL, RELEVANT, NEWEST, ALL);

        DefaultCommentOrder.requestedOrder(null, POST, null);
        DefaultCommentOrder.sortMenu(onRelevant, sheet);
        assertEquals(Collections.singletonList(ALL), sent);
        DefaultCommentOrder.sortMenu(onRelevant, sheet);
        assertEquals("drawn again before the pick's answer", 1, sent.size());
        DefaultCommentOrder.sortMenu(onAll, sheet);
        assertEquals(1, sent.size());

        // The refetch: Facebook's order again, and no request left waiting.
        DefaultCommentOrder.sortMenu(onRelevant, sheet);
        assertEquals(Arrays.asList(ALL, ALL), sent);
        DefaultCommentOrder.sortMenu(onRelevant, sheet);
        assertEquals("a server that won't answer All comments is asked once", 2, sent.size());
        DefaultCommentOrder.sortMenu(onAll, sheet);
        DefaultCommentOrder.sortMenu(onRelevant, sheet);
        assertEquals("answered, so the next refetch is picked again", 3, sent.size());

        // A link to one comment of that post: Facebook's view stays until it answers All comments.
        DefaultCommentOrder.sortMenu(onAll, sheet);
        DefaultCommentOrder.requestedOrder(null, POST, COMMENT);
        DefaultCommentOrder.sortMenu(onRelevant, sheet);
        assertEquals("a link to one comment keeps Facebook's order", 3, sent.size());

        // Picked on Most relevant by hand, a post keeps it.
        DefaultCommentOrder.picked(RELEVANT);
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(RELEVANT, OTHER_POST, null));
        assertTrue(openMenu(OTHER_POST, RELEVANT, RELEVANT, ALL).isEmpty());

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("All comments picked in the menu 1"));
        assertTrue(line, line.contains("All comments picked again 2"));
        assertFalse("a kept post's answers aren't counted", line.contains("Answer already All comments"));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 2, occurrences(report, "Default comment order: picked all_comments (" + ALL
                + ") again in the post's menu, which came back most_relevant (" + RELEVANT + ")."));
    }

    /**
     * #110: r7c7d7's report counted 8 menus "without All comments" that offered [RANKED_FILTERED,
     * REVERSE_CHRONOLOGICAL_UNFILTERED, CHRONOLOGICAL_UNFILTERED] or [CHRONOLOGICAL_FILTERED,
     * CHRONOLOGICAL_UNFILTERED], so those posts stayed on Most relevant. Their unfiltered row in All
     * comments' place is picked now, the usual token still wins where a menu has both, and a menu
     * with neither stays Facebook's.
     */
    @Test
    public void allCommentsIsPickedByTheTokenAPostsMenuNamesItsRowBy() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.mainForTests = Runnable::run;
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();

        DefaultCommentOrder.requestedOrder(null, POST, null);
        assertEquals(Collections.singletonList(ALL_ROW), openMenu(POST, RELEVANT, RELEVANT, NEWEST_ROW, ALL_ROW));
        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        assertEquals(Collections.singletonList(ALL_ROW), openMenu(OTHER_POST, RELEVANT_ROW, RELEVANT_ROW, ALL_ROW));

        DefaultCommentOrder.forget();
        DefaultCommentOrder.requestedOrder(null, POST, null);
        assertEquals("the usual token first", Collections.singletonList(ALL), openMenu(POST, RELEVANT, RELEVANT, ALL_ROW, ALL));
        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        assertTrue("already on that row", openMenu(OTHER_POST, ALL_ROW, RELEVANT_ROW, ALL_ROW).isEmpty());
        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        assertEquals("on the other row while the usual one is offered", Collections.singletonList(ALL),
                openMenu(OTHER_POST, ALL_ROW, RELEVANT, ALL_ROW, ALL));
        DefaultCommentOrder.requestedOrder(null, POST, null);
        assertTrue("Newest isn't All comments", openMenu(POST, RELEVANT_ROW, RELEVANT_ROW, NEWEST_ROW).isEmpty());

        // The pick's own request names that row, and the post keeps it like a tap's.
        DefaultCommentOrder.forget();
        DefaultCommentOrder.requestedOrder(null, POST, null);
        List<Object> sent = new ArrayList<>();
        DefaultCommentOrder.sortMenu(DefaultCommentOrderForTests.feedback(POST, RELEVANT_ROW, RELEVANT_ROW, ALL_ROW),
                (Function1<Object, Unit>) token -> {
                    DefaultCommentOrder.picked((String) token);
                    sent.add(DefaultCommentOrder.requestedOrder((String) token, POST, null));
                    return Unit.INSTANCE;
                });
        assertEquals(Collections.singletonList(ALL_ROW), sent);
        assertEquals("a refresh asks for that row", ALL_ROW, DefaultCommentOrder.requestedOrder(null, POST, null));

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 3, occurrences(report, "Default comment order: picked all_comments (" + ALL_ROW
                + ") in the post's menu."));
        assertEquals(report, 1, occurrences(report, "Default comment order: kept all_comments (" + ALL_ROW
                + "), which its menu offered, for that post until Facebook restarts."));
        assertEquals(report, 1, occurrences(report, "Default comment order: Facebook answered all_comments (" + ALL_ROW
                + ") already."));
        assertEquals(report, 1, occurrences(report, "Default comment order: left a post whose menu offers ["
                + RELEVANT_ROW + ", " + NEWEST_ROW + "] in Facebook's order."));
    }

    /** With another order chosen, the switch off, paused or not in the build, a menu is never picked in. */
    @Test
    public void otherwiseAMenuIsNeverPickedIn() {
        DefaultCommentOrder.mainForTests = Runnable::run;
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        DefaultCommentOrder.requestedOrder(null, POST, null);
        assertTrue(openMenu(POST, RELEVANT, RELEVANT, NEWEST, ALL).isEmpty());

        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.requestedOrder(null, POST, null);
        Settings.DEFAULT_COMMENT_ORDER.save(false);
        assertTrue("switched off after the request", openMenu(POST, RELEVANT, RELEVANT, ALL).isEmpty());
        Settings.DEFAULT_COMMENT_ORDER.save(true);

        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue("paused", openMenu(OTHER_POST, RELEVANT, RELEVANT, ALL).isEmpty());
        PauseForTests.resume();

        HookStatus.clear();
        DefaultCommentOrderForTests.inBuild(Boolean.FALSE);
        assertTrue(openMenu(OTHER_POST, RELEVANT, RELEVANT, ALL).isEmpty());
        assertNull("a build without the patch reports nothing", statusLine());
        DefaultCommentOrderForTests.inBuild(Boolean.TRUE);

        assertTrue(DefaultCommentOrderForTests.picksInTheMenu());
        boolean[] picked = {true};
        SettingsContextRule.withoutContext(() -> picked[0] = DefaultCommentOrderForTests.picksInTheMenu());
        assertFalse("a menu built before the context was picked in", picked[0]);
    }

    /** Odd trees, a released one, a pick that isn't a Function1 or that throws: Facebook's order, never a crash. */
    @Test
    public void anOddMenuOrAFailingPickLeavesFacebooksOrder() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.mainForTests = Runnable::run;

        DefaultCommentOrder.requestedOrder(null, POST, null);
        DefaultCommentOrder.sortMenu(DefaultCommentOrderForTests.feedback(POST, RELEVANT, RELEVANT, ALL), "not a pick");
        DefaultCommentOrder.sortMenu(null, null);

        TreeJNI released = DefaultCommentOrderForTests.feedback(POST, RELEVANT, RELEVANT, ALL).releasedTree();
        DefaultCommentOrder.sortMenu(released, (Function1<Object, Unit>) token -> Unit.INSTANCE);
        assertFalse("a released tree isn't read", released.readAfterRelease);

        DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        DefaultCommentOrder.sortMenu(new TreeJNI().with("id", OTHER_POST), (Function1<Object, Unit>) token -> Unit.INSTANCE);
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("1 missing"));
        assertTrue(line, line.contains("comment_rendering_instance"));

        HookStatus.clear();
        DefaultCommentOrder.requestedOrder(null, POST, null);
        DefaultCommentOrder.sortMenu(DefaultCommentOrderForTests.feedback(POST, RELEVANT, RELEVANT, ALL),
                (Function1<Object, Unit>) token -> {
                    throw new IllegalStateException("for this test");
                });
        line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("'comment sort menu pick' hook (it threw java.lang.IllegalStateException)"));
    }

    /** A pick in the menu, a list that names its own, a restored request: the order named stays. */
    @Test
    public void aRequestThatNamesAnOrderKeepsIt() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(ALL, POST, null));
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(RELEVANT, POST, null));
        assertEquals("a token this build doesn't know stays too", "STARS_INTENT_V1",
                DefaultCommentOrder.requestedOrder("STARS_INTENT_V1", POST, null));
    }

    /** A link to one comment opens where Facebook puts it, and a request without a post is left alone. */
    @Test
    public void aLinkToACommentAndARequestWithoutAPostKeepFacebooksOrder() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, COMMENT));
        assertEquals("", DefaultCommentOrder.requestedOrder("", POST, COMMENT));
        assertNull(DefaultCommentOrder.requestedOrder(null, null, null));
        assertNull(DefaultCommentOrder.requestedOrder(null, "", null));
        assertEquals(FamilyNames.DEFAULT_COMMENT_ORDER + ": invoked 4, 1 found, 0 missing", statusLine());
    }

    @Test
    public void offPausedOrNotInTheBuildARequestIsFacebooksOwn() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        Settings.DEFAULT_COMMENT_ORDER.save(false);
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        Settings.DEFAULT_COMMENT_ORDER.save(true);

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertNull(why.name(), DefaultCommentOrder.requestedOrder(null, POST, null));
        }
        PauseForTests.resume();

        HookStatus.clear();
        DefaultCommentOrderForTests.inBuild(Boolean.FALSE);
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        DefaultCommentOrder.picked(ALL);
        assertNull("a build without the patch reports nothing", statusLine());
        DefaultCommentOrderForTests.inBuild(Boolean.TRUE);

        boolean[] asked = {true};
        SettingsContextRule.withoutContext(() -> asked[0] = DefaultCommentOrderForTests.asksForTheChosenOrder());
        assertFalse("a request built before the context got an order", asked[0]);
        assertTrue(DefaultCommentOrderForTests.asksForTheChosenOrder());
    }

    /**
     * The order picked in a post's menu comes back for that post whenever its comments are fetched
     * with none, a refresh or a reopening, and only for that post, until Facebook restarts.
     */
    @Test
    public void anOrderPickedInAPostsMenuStaysWithThatPost() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        assertEquals(ALL, pick(ALL, POST));
        assertEquals("a refresh of that post", ALL, DefaultCommentOrder.requestedOrder(null, POST, null));
        assertEquals("another post", NEWEST, DefaultCommentOrder.requestedOrder(null, OTHER_POST, null));
        // A later pick replaces it.
        assertEquals(RELEVANT, pick(RELEVANT, POST));
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(null, POST, null));
        // A link to one of its comments still keeps Facebook's order.
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, COMMENT));

        // With the order left as Facebook's, or the switch off, a pick changes nothing afterwards.
        Settings.COMMENT_ORDER.save(CommentOrder.FACEBOOK);
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        Settings.DEFAULT_COMMENT_ORDER.save(false);
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        Settings.DEFAULT_COMMENT_ORDER.save(true);
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(null, POST, null));

        // A new process has none.
        DefaultCommentOrder.forget();
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, POST, null));
    }

    /**
     * Only the request a pick sends counts as the pick: the same token, next on the same thread,
     * within the window. Another request that names an order, such as a prefetch Facebook sends
     * with the last order it was asked for, isn't a pick for its post.
     */
    @Test
    public void onlyTheRequestAPickSendsKeepsTheOrderForItsPost() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        // No pick heard: an order named for a post isn't kept for it.
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(ALL, POST, null));
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, POST, null));

        // Another token than the one picked, and the pick is used up.
        DefaultCommentOrder.picked(ALL);
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(RELEVANT, POST, null));
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(ALL, POST, null));
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, POST, null));

        // Too late.
        DefaultCommentOrder.picked(ALL);
        DefaultCommentOrder.nowForTests = 1_000L + DefaultCommentOrder.PICK_WINDOW_MS + 1;
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(ALL, POST, null));
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, POST, null));

        // Another thread.
        DefaultCommentOrder.nowForTests = 5_000L;
        DefaultCommentOrder.picked(ALL);
        AtomicReference<String> sent = new AtomicReference<>();
        Thread other = new Thread(() -> sent.set(DefaultCommentOrder.requestedOrder(ALL, OTHER_POST, null)));
        other.start();
        try {
            other.join();
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
        assertEquals(ALL, sent.get());
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, OTHER_POST, null));
        // The pick still waits on this thread for its own request.
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(ALL, POST, null));
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(null, POST, null));

        // An empty pick is none.
        DefaultCommentOrder.picked("");
        DefaultCommentOrder.picked(null);
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(RELEVANT, OTHER_POST, null));
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, OTHER_POST, null));
    }

    @Test
    public void onlyTheMostRecentlyUsedPicksAreKept() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        assertEquals(ALL, pick(ALL, "post-0"));
        for (int i = 1; i <= DefaultCommentOrder.REMEMBERED_POSTS; i++) {
            if (i == 100) assertEquals("reading a pick keeps it", ALL, DefaultCommentOrder.requestedOrder(null, "post-0", null));
            pick(RELEVANT, "post-" + i);
        }
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(null, "post-0", null));
        assertEquals("the oldest one went", NEWEST, DefaultCommentOrder.requestedOrder(null, "post-1", null));
        assertEquals(RELEVANT, DefaultCommentOrder.requestedOrder(null, "post-2", null));
    }

    /**
     * #192: a post's own page, the one a link or a notification opens, builds its request another
     * way than the sheet, and that request asks for the chosen order too. A named order, Facebook's
     * own choice, the switch off, a pause, a failure and a build without the patch all leave it.
     */
    @Test
    public void aPostsOwnPageAsksForTheChosenOrderToo() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        assertEquals(NEWEST, DefaultCommentOrder.pageOrder(null));
        assertEquals(NEWEST, DefaultCommentOrder.pageOrder(""));
        assertEquals("a named order stays", RELEVANT, DefaultCommentOrder.pageOrder(RELEVANT));
        Settings.COMMENT_ORDER.save(CommentOrder.MOST_RELEVANT);
        assertEquals(RELEVANT, DefaultCommentOrder.pageOrder(null));
        Settings.COMMENT_ORDER.save(CommentOrder.FACEBOOK);
        assertNull(DefaultCommentOrder.pageOrder(null));

        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        Settings.DEFAULT_COMMENT_ORDER.save(false);
        assertNull(DefaultCommentOrder.pageOrder(null));
        Settings.DEFAULT_COMMENT_ORDER.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(DefaultCommentOrder.pageOrder(null));
        PauseForTests.resume();

        DefaultCommentOrder.failNextForTests = new IllegalStateException("for this test");
        assertNull(DefaultCommentOrder.pageOrder(null));
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("'post page request' hook (it threw java.lang.IllegalStateException)"));
        assertEquals(NEWEST, DefaultCommentOrder.pageOrder(null));

        HookStatus.clear();
        DefaultCommentOrderForTests.inBuild(Boolean.FALSE);
        assertNull(DefaultCommentOrder.pageOrder(null));
        assertNull("a build without the patch reports nothing", statusLine());
        DefaultCommentOrderForTests.inBuild(Boolean.TRUE);
    }

    /**
     * All comments on a post's page goes out with Facebook's order, like the sheet's, and the next
     * menu drawn within the window picks it, whichever post it belongs to, once.
     */
    @Test
    public void allCommentsOnAPostsPageIsPickedInTheMenuDrawnNext() {
        Settings.COMMENT_ORDER.save(CommentOrder.ALL_COMMENTS);
        DefaultCommentOrder.mainForTests = Runnable::run;
        assertNull(DefaultCommentOrder.pageOrder(null));
        assertEquals(Collections.singletonList(ALL), openMenu(POST, RELEVANT, RELEVANT, NEWEST, ALL));
        assertTrue("picked once", openMenu(OTHER_POST, RELEVANT, RELEVANT, NEWEST, ALL).isEmpty());

        // A sheet's own request keeps its menu, and the page's wait is still there for another.
        assertNull(DefaultCommentOrder.pageOrder(null));
        assertNull(DefaultCommentOrder.requestedOrder(null, "post-3", null));
        assertEquals(Collections.singletonList(ALL), openMenu("post-3", RELEVANT, RELEVANT, ALL));
        assertEquals(Collections.singletonList(ALL), openMenu("post-4", RELEVANT, RELEVANT, ALL));

        assertNull(DefaultCommentOrder.pageOrder(null));
        DefaultCommentOrder.nowForTests = 1_000L + DefaultCommentOrder.AWAIT_WINDOW_MS + 1;
        assertTrue("too late", openMenu("post-5", RELEVANT, RELEVANT, ALL).isEmpty());

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("All comments left for the menu 4"));
        assertTrue(line, line.contains("All comments picked in the menu 3"));
    }

    /** A failure in the hook leaves the request as it was and says so in Hook status. */
    @Test
    public void aFailureLeavesTheRequestAlone() {
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        DefaultCommentOrder.failNextForTests = new IllegalStateException("for this test");
        assertNull(DefaultCommentOrder.requestedOrder(null, POST, null));
        DefaultCommentOrder.failNextForTests = new IllegalStateException("for this test");
        assertEquals(ALL, DefaultCommentOrder.requestedOrder(ALL, POST, null));
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains("1 missing"));
        assertTrue(line, line.contains("'comment request' hook (it threw java.lang.IllegalStateException)"));
        assertEquals(NEWEST, DefaultCommentOrder.requestedOrder(null, POST, null));
    }

    /**
     * With Debug logging on, each decision has a line naming the order, for the phone check, up to
     * forty, then one line per fifty. No line names the post.
     */
    @Test
    public void debugLoggingSaysWhatEachRequestAskedFor() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.COMMENT_ORDER.save(CommentOrder.NEWEST);
        DefaultCommentOrder.requestedOrder(null, POST, null);
        DefaultCommentOrder.requestedOrder(null, POST, COMMENT);
        pick(ALL, POST);
        DefaultCommentOrder.requestedOrder(null, POST, null);
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, occurrences(report, "Default comment order: asked for newest (" + NEWEST + ")."));
        assertEquals(report, 1, occurrences(report, "Default comment order: left a link to one comment in Facebook's order."));
        assertEquals(report, 1, occurrences(report, "Default comment order: kept all_comments (" + ALL
                + "), picked in a post's comments, for that post until Facebook restarts."));
        assertEquals(report, 1, occurrences(report,
                "Default comment order: asked for the order picked for this post, all_comments (" + ALL + ")."));
        assertEquals(report, 0, occurrences(report, POST));
        assertEquals(report, 0, occurrences(report, COMMENT));

        LogBufferManager.clearLogBuffer();
        for (int i = 4; i < DefaultCommentOrder.LOGGED_ONE_BY_ONE + 2 * DefaultCommentOrder.SUMMED_UP_BY; i++) {
            DefaultCommentOrder.requestedOrder(null, OTHER_POST, null);
        }
        report = LogBufferManager.buildExportText();
        assertEquals(report, DefaultCommentOrder.LOGGED_ONE_BY_ONE - 4,
                occurrences(report, "Default comment order: asked for newest"));
        assertEquals(report, 2, occurrences(report, " comment requests so far. The last one asked for newest"));
        assertEquals(report, 1, occurrences(report, "Default comment order: 50 comment requests so far."));
        assertEquals(report, 1, occurrences(report, "Default comment order: 100 comment requests so far."));
    }
}
