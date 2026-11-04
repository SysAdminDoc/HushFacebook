/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.EnumSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The main page's rows run edge to edge with square corners, and an open section's rows share one
 * card inset 18 dp, rounded at its top and bottom. Every row leads with a 24 dp icon a screen
 * reader skips, section titles are small caps in the heading colour, and the switch is a squared
 * track and thumb.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 37}, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsCardsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;
    private HushfacebookPreferenceFragment page;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        page = SettingsL10nTest.pageOf(dialog);
    }

    @After public void close() {
        controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test public void theMainPageRunsEdgeToEdgeWithSquareCorners() {
        ScreenColors colors = ScreenColors.current();
        int sections = 0;
        for (int at = 0; at < list().getCount(); at++) {
            Preference item = item(at);
            if (item instanceof PreferenceCategory || item instanceof SettingsRows.Subheading) continue;
            View row = render(at);
            if (!(row.getBackground() instanceof RippleDrawable || row.getBackground() instanceof InsetDrawable)) continue;
            String name = String.valueOf(item.getTitle());
            GradientDrawable card = card(row);
            for (float radius : card.getCornerRadii()) assertEquals(name + " has a rounded corner", 0, radius, 0);
            assertEquals(name, colors.card, card.getColor().getDefaultColor());
            Rect drawn = drawn(row);
            assertEquals(name + ": the side stroke shows at the page's edge", -dp(1), drawn.left);
            assertEquals(name + ": the side stroke shows at the page's edge", row.getWidth() + dp(1), drawn.right);
            assertEquals(name + ": two strokes meet between rows", -dp(1), drawn.top);
            assertEquals(name + ": no stroke under the row", row.getHeight(), drawn.bottom);
            assertEquals(name, dp(52), row.getMinimumHeight());
            assertTrue(name + " is shorter than a touch target", row.getHeight() >= dp(52));
            assertEquals(name, dp(20), row.getPaddingStart());
            assertEquals(name, dp(20), row.getPaddingEnd());
            TextView title = row.findViewById(android.R.id.title);
            assertEquals(name, sp(15), title.getTextSize(), 0.01);
            TextView summary = row.findViewById(android.R.id.summary);
            if (summary != null && summary.getVisibility() == View.VISIBLE) {
                assertEquals(name, sp(12), summary.getTextSize(), 0.01);
            }
            if (item.getKey() != null && item.getKey().startsWith("section_")) sections++;
        }
        assertEquals("every section opens from a row on the page", page.sections().size(), sections);
    }

    @Test public void anOpenSectionsRowsShareOneInsetRoundedCard() {
        ScreenColors colors = ScreenColors.current();
        int cards = 0;
        int rows = 0;
        for (Preference section : page.sections()) {
            String key = "section_" + page.navigation.sectionId(section);
            tap(key);
            int header = position(key);
            int end = header + 1;
            while (end < list().getCount() && item(end).getParent() == section) end++;
            assertTrue(section.getTitle() + " opened on nothing", end > header + 1);
            for (int at = header + 1; at < end; at++) {
                Preference item = item(at);
                if (item instanceof SettingsRows.Subheading) continue;
                String name = section.getTitle() + " > " + item.getTitle();
                boolean opens = !(item(at - 1).getParent() == section && !(item(at - 1) instanceof SettingsRows.Subheading));
                boolean closes = !(at + 1 < end && !(item(at + 1) instanceof SettingsRows.Subheading));
                View row = render(at);
                GradientDrawable card = card(row);
                float[] radii = card.getCornerRadii();
                assertEquals(name + " top corners", opens ? dp(10) : 0, radii[0], 0.01);
                assertEquals(name + " top corners", opens ? dp(10) : 0, radii[2], 0.01);
                assertEquals(name + " bottom corners", closes ? dp(10) : 0, radii[4], 0.01);
                assertEquals(name + " bottom corners", closes ? dp(10) : 0, radii[6], 0.01);
                assertEquals(name, colors.card, card.getColor().getDefaultColor());
                Rect drawn = drawn(row);
                assertEquals(name + " is off the 18 dp line", dp(18), drawn.left);
                assertEquals(name + " is off the 18 dp line", row.getWidth() - dp(18), drawn.right);
                assertEquals(name + " top", opens ? dp(4) : -dp(1), drawn.top);
                assertEquals(name + " bottom", closes ? row.getHeight() - dp(4) : row.getHeight(), drawn.bottom);
                assertEquals(name, dp(64), row.getMinimumHeight());
                assertEquals(name, dp(30), row.getPaddingStart());
                assertEquals(name, dp(30), row.getPaddingEnd());
                TextView title = row.findViewById(android.R.id.title);
                if (title != null) assertEquals(name, sp(16), title.getTextSize(), 0.01);
                if (opens) cards++;
                rows++;
            }
            tap(key);
        }
        assertTrue("too few rows to mean anything: " + rows, rows > 150);
        assertTrue("a section is more than one card, or none: " + cards, cards >= page.sections().size());
    }

    @Test public void everyRowLeadsWithAnIconAScreenReaderSkips() {
        int rows = 0;
        for (Preference section : page.sections()) {
            PreferenceCategory group = (PreferenceCategory) section;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference row = group.getPreference(i);
                if (row instanceof SettingsRows.Subheading || row instanceof PreferenceCategory) continue;
                assertNotNull(section.getTitle() + " > " + row.getTitle() + " has no icon", row.getIcon());
                rows++;
            }
        }
        assertTrue("too few rows to mean anything: " + rows, rows > 150);

        String key = "section_" + page.navigation.sectionId(sectionHolding(Settings.TAP_TO_PLAY.key));
        tap(key);
        View row = render(position(Settings.TAP_TO_PLAY.key));
        ImageView icon = row.findViewById(android.R.id.icon);
        assertNotNull(icon);
        assertEquals(View.VISIBLE, icon.getVisibility());
        assertEquals("the icon is decoration", View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertNull(icon.getContentDescription());
        assertEquals(dp(24), icon.getLayoutParams().width);
        assertEquals(dp(24), icon.getLayoutParams().height);
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, icon.getScaleType());
        assertNull("the line icon has no tile behind it", icon.getBackground());
        ViewGroup frame = (ViewGroup) icon.getParent();
        assertEquals(dp(38), frame.getLayoutParams().width);
        assertEquals(dp(14), frame.getPaddingEnd());
    }

    @Test public void aRowsIconFollowsTheWordsOfItsKey() {
        String fallback = SettingsIcons.FEED;
        assertSame(SettingsIcons.DOWNLOADS, SettingsIcons.forKey(Settings.POST_DOWNLOAD_BUTTON.key, fallback));
        assertSame(SettingsIcons.BLOCK, SettingsIcons.forKey(Settings.HIDE_SPONSORED_POSTS.key, fallback));
        assertSame(SettingsIcons.CHATS, SettingsIcons.forKey(Settings.HIDE_CHAT_TYPING.key, fallback));
        assertSame(SettingsIcons.PLAYBACK, SettingsIcons.forKey(Settings.TAP_TO_PLAY.key, fallback));
        assertSame("an earlier word still wins", SettingsIcons.COMMENTS,
                SettingsIcons.forKey(Settings.DOUBLE_TAP_OPENS_COMMENTS.key, fallback));
        assertSame("an earlier word still wins", SettingsIcons.REELS,
                SettingsIcons.forKey(Settings.DONT_SEND_REEL_WATCH_HISTORY.key, fallback));
        assertSame("whole words only: download isn't load", fallback, SettingsIcons.forKey("hushfacebook_load_more", fallback));
        assertSame(fallback, SettingsIcons.forKey("hushfacebook_hide_people_you_may_know", fallback));
        assertSame(fallback, SettingsIcons.forKey(null, fallback));
    }

    @Test public void aSectionTitleIsASmallCapsHeadingInTheHeadingColour() {
        page.navigation.search("video");
        ShadowLooper.idleMainLooper();
        int headings = 0;
        for (int at = 0; at < list().getCount(); at++) {
            if (!(item(at) instanceof PreferenceCategory)) continue;
            View row = render(at);
            TextView title = row.findViewById(android.R.id.title);
            String name = String.valueOf(item(at).getTitle());
            assertEquals(name, ScreenColors.current().heading, title.getCurrentTextColor());
            assertEquals(name, sp(12), title.getTextSize(), 0.01);
            assertEquals(name, 0.055f, title.getLetterSpacing(), 0.0001f);
            assertTrue(name + " isn't in capitals", title.isAllCaps());
            assertEquals(name, dp(22), row.getPaddingStart());
            assertEquals(name, dp(20), row.getPaddingTop());
            assertEquals(name, dp(22), row.getPaddingEnd());
            assertEquals(name, dp(8), row.getPaddingBottom());
            headings++;
        }
        assertTrue("the search found no section", headings > 0);
    }

    @Test public void theSwitchIsASquaredTrackAndThumb() {
        ScreenColors colors = ScreenColors.current();
        tap("section_" + page.navigation.sectionId(sectionHolding(Settings.TAP_TO_PLAY.key)));
        Switch toggle = render(position(Settings.TAP_TO_PLAY.key)).findViewById(android.R.id.switch_widget);
        assertNotNull(toggle);
        assertEquals(dp(40), toggle.getSwitchMinWidth());
        GradientDrawable thumb = (GradientDrawable) toggle.getThumbDrawable();
        assertEquals(dp(4), thumb.getCornerRadius(), 0.01);
        assertEquals(dp(20), thumb.getIntrinsicWidth());
        assertEquals(dp(20), thumb.getIntrinsicHeight());
        GradientDrawable track = (GradientDrawable) toggle.getTrackDrawable();
        assertEquals(dp(6), track.getCornerRadius(), 0.01);
        assertEquals(dp(40), track.getIntrinsicWidth());
        assertEquals(dp(24), track.getIntrinsicHeight());
        int[] on = {android.R.attr.state_enabled, android.R.attr.state_checked};
        int[] off = {android.R.attr.state_enabled};
        assertEquals(colors.accent, toggle.getTrackTintList().getColorForState(on, 0));
        assertEquals(colors.onAccent, toggle.getThumbTintList().getColorForState(on, 0));
        assertEquals(colors.switchOff, toggle.getTrackTintList().getColorForState(off, 0));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheTextSizeTheCardsGrowAroundTheWholeText() {
        controller.close();
        RuntimeEnvironment.setFontScale(2f);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        page = SettingsL10nTest.pageOf(dialog);
        Preference section = sectionHolding(Settings.HIDE_SPONSORED_POSTS.key);
        String key = "section_" + page.navigation.sectionId(section);
        assertWhole(render(position(key)));
        tap(key);
        int wrapped = 0;
        int header = position(key);
        for (int at = header + 1; at < list().getCount() && item(at).getParent() == section; at++) {
            if (item(at) instanceof SettingsRows.Subheading) continue;
            View row = render(at);
            assertWhole(row);
            TextView summary = row.findViewById(android.R.id.summary);
            if (summary != null && summary.getLayout() != null && summary.getLayout().getLineCount() > 1) wrapped++;
        }
        assertTrue("nothing wrapped at twice the text size", wrapped > 0);
    }

    /** Every line of [row]'s text is drawn in full, inside the row. */
    private static void assertWhole(View row) {
        int texts = 0;
        for (int which : new int[]{android.R.id.title, android.R.id.summary}) {
            TextView text = row.findViewById(which);
            if (text == null || text.getVisibility() != View.VISIBLE || text.getText().length() == 0) continue;
            texts++;
            Layout layout = text.getLayout();
            assertNotNull(text.getText() + " was never laid out", layout);
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(text.getText() + " line " + line + " is cut short", 0, layout.getEllipsisCount(line));
            }
            assertTrue(text.getText() + " is taller than its view",
                    layout.getHeight() <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
            int bottom = text.getHeight();
            for (View at = text; at != row; at = (View) at.getParent()) bottom += at.getTop();
            assertTrue(text.getText() + " runs past the bottom of its row", bottom <= row.getHeight());
        }
        assertTrue("the row drew no text", texts > 0);
    }

    private Preference sectionHolding(String key) {
        Preference row = page.findPreference(key);
        assertNotNull("no row keyed " + key, row);
        for (Preference section : page.sections()) if (row.getParent() == section) return section;
        throw new AssertionError(key + " is in no section");
    }

    /** The row at [position], drawn by the page's own list at the phone's width. */
    private View render(int position) {
        View row = list().getAdapter().getView(position, null, list());
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
        return row;
    }

    private static InsetDrawable inset(View row) {
        Drawable background = row.getBackground();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no inset card behind the row: " + background, background instanceof InsetDrawable);
        return (InsetDrawable) background;
    }

    /** The surface a row draws its card with. */
    private static GradientDrawable card(View row) {
        Drawable surface = inset(row).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return (GradientDrawable) surface;
    }

    /** Where the card is drawn when the row's background fills the row. */
    private static Rect drawn(View row) {
        InsetDrawable inset = inset(row);
        inset.setBounds(0, 0, row.getWidth(), row.getHeight());
        return new Rect(inset.getDrawable().getBounds());
    }

    private ListView list() { return dialog.getView().findViewById(android.R.id.list); }

    private Preference item(int position) { return (Preference) list().getItemAtPosition(position); }

    private int position(String key) {
        for (int i = 0; i < list().getCount(); i++) {
            if (key.equals(item(i).getKey())) return i;
        }
        throw new AssertionError("no visible row " + key);
    }

    private void tap(String key) {
        int at = position(key);
        View row = list().getAdapter().getView(at, null, list());
        assertTrue(list().performItemClick(row, at, list().getAdapter().getItemId(at)));
        ShadowLooper.idleMainLooper();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                controller.get().getResources().getDisplayMetrics());
    }
}
