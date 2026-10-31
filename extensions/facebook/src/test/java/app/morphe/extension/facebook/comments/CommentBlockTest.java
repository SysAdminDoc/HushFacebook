/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import com.facebook.auth.usersession.FbUserSession;
import com.facebook.graphql.model.GraphQLActor;
import com.facebook.graphql.model.GraphQLComment;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Block button on comments (#271): off, every hook leaves Facebook's own; on, a tap on the
 * thumbs-down hands the commenter's id and name to Facebook's profile Block helper, as an
 * ordinary profile that gets Facebook's own "Block name?" dialog, and never dislikes. A comment with no readable author opens
 * nothing, and says so.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentBlockTest {
    /** The profile Facebook's helper reads, the shape the real interface has. */
    public interface Profile {
        String getName();

        boolean CIY();

        Header AyU();
    }

    /** The nested model the helper's own dialog reads off the profile. */
    public interface Header {
        Names Fog();
    }

    /** The innermost model: a joined name the dialog shows when there is one. */
    public interface Names {
        String Fx3();
    }

    /** The places the helper is opened from. */
    public enum Source {
        FRIEND_LIST, PROFILE
    }

    /** Facebook's block helper, recording what it was handed. */
    public static final class Helper {
        static Context context;
        static Source source;
        static FbUserSession session;
        static Profile profile;
        static long first;
        static long second;
        static int calls;

        public Helper() {
        }

        public void A01(Context c, Source s, FbUserSession u, Profile p, long a, long b, boolean z) {
            context = c;
            source = s;
            session = u;
            profile = p;
            first = a;
            second = b;
            calls++;
        }
    }

    /** The Dislike component: the comment and the session, among other fields. */
    public static final class Component {
        final String label = "x";
        final GraphQLComment comment;
        final FbUserSession session = new FbUserSession();

        Component(GraphQLComment comment) {
            this.comment = comment;
        }
    }

    /** The click's lambda, holding the component. */
    public static final class Lambda {
        final Object other = new Object();
        final Component component;

        Lambda(Component component) {
            this.component = component;
        }
    }

    /** The click's scope, answering the tapped View. */
    public static final class Scope {
        final View view = new View(RuntimeEnvironment.getApplication());

        public View A00() {
            return view;
        }
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        Helper.calls = 0;
        Helper.profile = null;
    }

    @After
    public void restore() {
        Settings.COMMENT_BLOCK_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return "";
    }

    private static Lambda lambdaFor(String id, String name) {
        GraphQLComment comment = new GraphQLComment("hello").writtenBy(new GraphQLActor(id, name));
        return new Lambda(new Component(comment));
    }

    private static boolean tap(Lambda lambda, Object scope) {
        return CommentBlock.tap(lambda, scope, Component.class, Helper.class, Profile.class, Source.class);
    }

    @Test
    public void offLeavesEverythingToFacebook() {
        assertFalse(Settings.COMMENT_BLOCK_BUTTON.get());
        assertFalse(CommentBlock.blockIcon());
        assertEquals("Dislike", CommentBlock.blockLabel("Dislike"));
        assertFalse(tap(lambdaFor("123", "Sam"), new Scope()));
        assertEquals(0, Helper.calls);
    }

    @Test
    public void onTheTapOpensFacebooksBlockHelperForTheCommenter() {
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        assertTrue(CommentBlock.blockIcon());
        assertEquals("Block", CommentBlock.blockLabel("Dislike"));
        Scope scope = new Scope();
        assertTrue(tap(lambdaFor("1234567890", "Sam Rivers"), scope));
        assertEquals(1, Helper.calls);
        assertEquals(Source.PROFILE, Helper.source);
        assertEquals(1234567890L, Helper.second);
        assertEquals(0L, Helper.first);
        assertTrue("the context is the tapped view's", Helper.context == scope.view.getContext());
        assertEquals("Sam Rivers", Helper.profile.getName());
        assertFalse("an ordinary profile gets Facebook's own dialog, not the server's sheet", Helper.profile.CIY());
        assertTrue("the dialog reads a nested model", Helper.profile.AyU() != null);
        assertNull("and finds no name in it", Helper.profile.AyU().Fog().Fx3());
        assertTrue(statusLine().contains(CommentBlock.BLOCK_OPENED + " 1"));
    }

    @Test
    public void aCommentWithNoReadableAuthorOpensNothing() {
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        assertTrue(tap(lambdaFor("not-a-number", "Sam"), new Scope()));
        assertTrue(tap(lambdaFor(null, "Sam"), new Scope()));
        assertTrue(tap(new Lambda(new Component(new GraphQLComment("hi"))), new Scope()));
        assertEquals(0, Helper.calls);
    }

    @Test
    public void aLambdaWithoutTheComponentIsFacebooks() {
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        assertFalse(CommentBlock.tap(new Object(), new Scope(), Component.class, Helper.class, Profile.class, Source.class));
        assertEquals(0, Helper.calls);
    }

    @Test
    public void aMissingNameFallsBackToTheShortName() {
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        GraphQLActor actor = new GraphQLActor("55", null);
        actor.with("short_name", "Sam");
        assertTrue(tap(new Lambda(new Component(new GraphQLComment("x").writtenBy(actor))), new Scope()));
        assertEquals("Sam", Helper.profile.getName());
    }

    /** A session that writes its user id first, as Facebook's does. */
    public static final class ParcelSession extends FbUserSession implements android.os.Parcelable {
        final String user;

        ParcelSession(String user) {
            this.user = user;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(android.os.Parcel out, int flags) {
            out.writeString(user);
        }
    }

    @Test
    public void theViewerIdComesFromTheSessionLikeTheProfilesOwnBlockRow() {
        assertEquals(100004321L, CommentBlock.viewerId(new ParcelSession("100004321")));
        assertEquals(0L, CommentBlock.viewerId(new ParcelSession("")));
        assertEquals(0L, CommentBlock.viewerId(new ParcelSession("abc")));
        assertEquals(0L, CommentBlock.viewerId(new FbUserSession()));
        assertEquals(0L, CommentBlock.viewerId(null));
    }

    /** Facebook's service failure, carrying a result with the error that came back. */
    public static final class ServiceFailure extends Exception {
        final Result result;

        ServiceFailure(Result result) {
            super("service failed");
            this.result = result;
        }
    }

    /** The service result's shape: an error code, a description and the throwable behind it. */
    public static final class Result {
        final Code errorCode = Code.API_ERROR;
        final String errorDescription = "Blockee 100001234567 can't be blocked";
        final Throwable errorThrowable;

        Result(Throwable errorThrowable) {
            this.errorThrowable = errorThrowable;
        }
    }

    public enum Code {
        API_ERROR
    }

    @Test
    public void aFailureReasonCarriesTheServiceErrorWithNoIds() {
        String reason = CommentBlock.reason(new ServiceFailure(new Result(new IllegalStateException("Null action"))));
        assertTrue(reason, reason.startsWith("ServiceFailure: service failed {"));
        assertTrue(reason, reason.contains("errorCode=API_ERROR"));
        assertTrue(reason, reason.endsWith(" <- IllegalStateException: Null action"));
        assertFalse("an id reached the reason: " + reason, reason.contains("100001234567"));
        assertEquals("no reason given", CommentBlock.reason(null));
    }

    @Test
    public void aFailedSheetAfterATapIsCountedOnce() {
        CommentBlock.tappedAt = System.currentTimeMillis();
        CommentBlock.sheetFailed(new IllegalStateException("Null action"));
        CommentBlock.sheetFailed(new IllegalStateException("Null action"));
        assertTrue(statusLine(), statusLine().contains(CommentBlock.BLOCK_FAILED + " 1"));
        assertEquals("a reason is read once per tap", 0L, CommentBlock.tappedAt);
    }

    @Test
    public void aFailedSheetWithNoTapHereIsLeftAlone() {
        CommentBlock.tappedAt = 0L;
        CommentBlock.sheetFailed(new RuntimeException("Null viewer"));
        CommentBlock.tappedAt = System.currentTimeMillis() - CommentBlock.TAP_WINDOW_MS - 1;
        CommentBlock.sheetFailed(new RuntimeException("Null viewer"));
        assertFalse(statusLine().contains(CommentBlock.BLOCK_FAILED));
    }

    @Test
    public void aTapStartsTheFailureWindow() {
        Settings.COMMENT_BLOCK_BUTTON.save(true);
        CommentBlock.tappedAt = 0L;
        assertTrue(tap(lambdaFor("1234567890", "Sam Rivers"), new Scope()));
        assertTrue(CommentBlock.tappedAt > 0L);
        CommentBlock.sheetFailed(new RuntimeException("Null viewer"));
        assertTrue(statusLine().contains(CommentBlock.BLOCK_FAILED + " 1"));
    }

    @Test
    public void helperIsFoundByItsParameterTypes() {
        assertTrue(CommentBlock.helperMethod(Helper.class, Profile.class, Source.class) != null);
        assertNull(CommentBlock.helperMethod(Helper.class, Runnable.class, Source.class));
    }
}
