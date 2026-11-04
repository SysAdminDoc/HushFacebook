/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import com.facebook.auth.usersession.FbUserSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.comments.CommentBlockTest.Helper;
import app.morphe.extension.facebook.comments.CommentBlockTest.ParcelSession;
import app.morphe.extension.facebook.comments.CommentBlockTest.Profile;
import app.morphe.extension.facebook.comments.CommentBlockTest.Source;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Block button on reaction lists (#271): off, the row keeps Facebook's buttons; on, the shared
 * friending button is dropped and the row's own button says Block and opens Facebook's profile
 * Block helper for that row's person. A row Facebook hid, the viewer's own row, a row drawn in a
 * session that names no user, a row with no readable person and a row naming two different people
 * keep Facebook's own button, and a reused view never blocks the person its last row had.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReactorBlockTest {
    /** A person model, with the kept getters Facebook's profile models have. */
    public static final class Person {
        private final String id;
        private final String name;

        Person(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /** The row props: the person, among other things. */
    public static final class Props {
        final Object location = new Object();
        final Person person;
        final Person other;
        final String postId = "999";

        Props(Person person, Person other) {
            this.person = person;
            this.other = other;
        }
    }

    /** The row's own button component: a session and the props. */
    public static final class Button {
        final FbUserSession session;
        final Props props;

        Button(FbUserSession session, Props props) {
            this.session = session;
            this.props = props;
        }
    }

    /** The button view: a short label it swaps in, and an icon set through a method of its own. */
    public static final class ButtonView extends TextView {
        CharSequence shortText = "Add";
        Drawable glyph = new ColorDrawable();
        int glyphCalls;

        public ButtonView(Context context) {
            super(context);
        }

        public void setGlyph(Drawable drawable) {
            glyph = drawable;
            glyphCalls++;
        }
    }

    /** A button view with two drawables of its own, so neither is known to be the relation's icon. */
    public static final class TwoIconView extends TextView {
        Drawable glyph = new ColorDrawable();
        Drawable badge = new ColorDrawable();

        public TwoIconView(Context context) {
            super(context);
        }

        public void setGlyph(Drawable drawable) {
            glyph = drawable;
        }

        public void setBadge(Drawable drawable) {
            badge = drawable;
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
        Settings.REACTOR_BLOCK_BUTTON.resetToDefault();
        HookStatus.clear();
        CommentBlock.tappedAt = 0L;
        CommentBlock.tappedPlace = "Comment block";
        CommentBlock.tappedFailed = CommentBlock.BLOCK_FAILED;
    }

    private static String status() {
        String status = "";
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) status = line;
        }
        return status;
    }

    private static ButtonView view() {
        ButtonView view = new ButtonView(RuntimeEnvironment.getApplication());
        view.setText("Add friend");
        return view;
    }

    private static void bind(Object button, Object view) {
        ReactorBlock.binding(button, view);
        ReactorBlock.bound(Props.class, Helper.class, Profile.class, Source.class);
    }

    private static Button row(String id, String name) {
        return new Button(new ParcelSession("100004321"), new Props(new Person(id, name), null));
    }

    private static boolean untouched(ButtonView view) {
        return "Add friend".contentEquals(view.getText()) && "Add".contentEquals(view.shortText) && view.glyphCalls == 0;
    }

    @Test
    public void offLeavesTheRowToFacebook() {
        Object shared = new Object();
        assertSame(shared, ReactorBlock.sharedButton(shared));
        ButtonView view = view();
        bind(row("1234567890", "Sam Rivers"), view);
        assertTrue(untouched(view));
        view.performClick();
        assertEquals(0, Helper.calls);
    }

    @Test
    public void onTheRowSaysBlockAndOpensFacebooksBlockHelperForItsPerson() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        assertNull("the shared friending button is dropped", ReactorBlock.sharedButton(new Object()));
        ButtonView view = view();
        bind(row("1234567890", "Sam Rivers"), view);
        assertEquals("Block", view.getText().toString());
        assertEquals("the short label can't bring Add back", "Block", view.shortText.toString());
        assertEquals(1, view.glyphCalls);
        assertNull("the relation's icon goes", view.glyph);

        assertTrue(view.performClick());
        assertEquals(1, Helper.calls);
        assertEquals(Source.PROFILE, Helper.source);
        assertEquals(1234567890L, Helper.second);
        assertEquals(100004321L, Helper.first);
        assertEquals("Sam Rivers", Helper.profile.getName());
        assertTrue("the context is the button's", Helper.context == view.getContext());
        assertTrue(status(), status().contains(ReactorBlock.BLOCK_OPENED + " 1"));
    }

    @Test
    public void aFailedSheetAfterARowTapIsCountedAsTheRowsNotAComments() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("1234567890", "Sam Rivers"), view);
        assertTrue(view.performClick());
        CommentBlock.sheetFailed(new IllegalStateException("Null action"));
        assertTrue(status(), status().contains(ReactorBlock.BLOCK_FAILED + " 1"));
        assertFalse(status(), status().contains(CommentBlock.BLOCK_FAILED));
    }

    @Test
    public void aCommentTapAfterARowTapIsCountedAsTheComments() throws ReflectiveOperationException {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("1234567890", "Sam Rivers"), view);
        assertTrue(view.performClick());
        assertTrue(CommentBlock.open(view.getContext(), new CommentBlock.Author("55", "Sam", new ParcelSession("100004321")),
                Helper.class, Profile.class, Source.class));
        CommentBlock.sheetFailed(new IllegalStateException("Null action"));
        assertTrue(status(), status().contains(CommentBlock.BLOCK_FAILED + " 1"));
        assertFalse(status(), status().contains(ReactorBlock.BLOCK_FAILED));
    }

    @Test
    public void aViewLeftToFacebookAfterABlockRowLosesBlockEverywhere() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("1234567890", "Sam Rivers"), view);
        assertEquals("Block", view.getContentDescription().toString());
        // Facebook's bind for the viewer's own row writes its label, not the description or the short one.
        view.setText("Message");
        bind(row("100004321", "Me"), view);
        assertEquals("Message", view.getText().toString());
        assertNull(view.getContentDescription());
        assertEquals("Message", view.shortText.toString());
    }

    @Test
    public void aViewWithTwoDrawableSettersKeepsItsIcons() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        TwoIconView view = new TwoIconView(RuntimeEnvironment.getApplication());
        bind(row("1234567890", "Sam Rivers"), view);
        assertEquals("Block", view.getText().toString());
        assertTrue("neither icon is taken, since it can't tell which is the relation's", view.glyph != null && view.badge != null);
    }

    @Test
    public void aRowFacebookHidStaysHidden() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        view.setVisibility(View.GONE);
        bind(row("1234567890", "Sam Rivers"), view);
        assertEquals(View.GONE, view.getVisibility());
        assertTrue(untouched(view));
    }

    @Test
    public void aReusedViewNeverBlocksItsLastRowsPerson() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("1234567890", "Sam Rivers"), view);
        assertEquals("Block", view.getText().toString());
        // The same view, bound next for the viewer's own row, which keeps Facebook's button.
        bind(row("100004321", "Me"), view);
        view.performClick();
        assertEquals(0, Helper.calls);
    }

    @Test
    public void aSessionThatNamesNoUserKeepsFacebooksButton() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(new Button(new ParcelSession(""), new Props(new Person("1234567890", "Sam Rivers"), null)), view);
        assertTrue(untouched(view));
    }

    @Test
    public void aBindOffTheMainThreadIsLeftToFacebook() throws InterruptedException {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        Thread binder = new Thread(() -> bind(row("1234567890", "Sam Rivers"), view));
        binder.start();
        binder.join();
        assertTrue(untouched(view));
    }

    @Test
    public void theSamePersonTwiceIsStillOnePerson() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(new Button(new ParcelSession("100004321"), new Props(new Person("55", "Sam"), new Person("55", ""))), view);
        assertEquals("Block", view.getText().toString());
        assertTrue(view.performClick());
        assertEquals(55L, Helper.second);
        assertEquals("Sam", Helper.profile.getName());
    }

    @Test
    public void twoDifferentPeopleLeaveTheRowToFacebook() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(new Button(new ParcelSession("100004321"), new Props(new Person("55", "Sam"), new Person("66", "Alex"))), view);
        assertTrue(untouched(view));
    }

    @Test
    public void theViewersOwnRowKeepsFacebooksButton() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("100004321", "Me"), view);
        assertTrue(untouched(view));
    }

    @Test
    public void aRowWithNoReadablePersonKeepsFacebooksButton() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("not-a-number", "Sam"), view);
        assertTrue(untouched(view));
        bind(new Button(new ParcelSession("100004321"), null), view);
        assertTrue(untouched(view));
        bind(new Button(null, new Props(new Person("1234567890", "Sam Rivers"), null)), view);
        assertTrue(untouched(view));
    }

    @Test
    public void aMissingNameStillBlocksUnderAPlaceholder() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        bind(row("77", ""), view);
        assertTrue(view.performClick());
        assertEquals(77L, Helper.second);
        assertEquals("this person", Helper.profile.getName());
    }

    @Test
    public void aReturnWithoutABindingDoesNothing() {
        Settings.REACTOR_BLOCK_BUTTON.save(true);
        ButtonView view = view();
        ReactorBlock.binding(row("1234567890", "Sam Rivers"), view);
        ReactorBlock.bound(Props.class, Helper.class, Profile.class, Source.class);
        view.setText("Add friend");
        view.glyphCalls = 0;
        view.shortText = "Add";
        ReactorBlock.bound(Props.class, Helper.class, Profile.class, Source.class);
        assertTrue("the pair is used once", untouched(view));
    }
}
