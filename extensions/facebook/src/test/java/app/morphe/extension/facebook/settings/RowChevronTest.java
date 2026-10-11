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
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.preference.DialogPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.SwitchPreference;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A row a tap opens something from wears a chevron, so it reads as one by its shape and not only by
 * its title's colour, which was all that set "Licenses" apart from "Version" (WCAG 1.4.1).
 *
 * <p>Laid out on a phone's screen, 411 dp wide, with native text measuring, so a title that wraps at
 * twice the text size wraps here too. Switch rows keep their switch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class RowChevronTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Every row on the screen whose tap opens a dialog, a file picker, the browser, Android's settings or another page. */
    private static final Set<String> OPENS_SOMETHING = new LinkedHashSet<>(Arrays.asList(
            "Jump to a section", "Reels in the feed", "Reels that play by themselves", "The Reels tab",
            "Everything except Marketplace",
            "Tab to open on", "Feeds opens on", "Keep seen posts hidden for", "Words to hide", "Words that keep a post", "People, Pages and sites to hide", "Comment order", "Hide reaction counts", "Playback quality", "Reels quality", "Stories quality", "Font file", "Download quality", "Save to", "Save folder",
            "Video subfolder", "Photo subfolder",
            "Video file name", "Photo file name", "When you tap Download", "App to send to", "Share sheet items to hide", "Supported links", "Meta App Manager", "Messenger", "Instagram",
            "Quiet hours start", "Quiet hours end", "Lock after",
            "Ads in the feed", "Ads on profiles", "Ads in Stories", "Ads in Reels", "Ads in search results",
            "Ads in Marketplace", "Ads in Instant Games", "Usage statistics uploads", "Reel watch history", "Text size", "Accent color", "Hide posts with more reactions than",
            "Export settings", "Import settings",
            "Export diagnostic report", "Facebook's Whitehat settings", "Source code and issues", "Licenses"));

    /** Built by the first show(), after a test has set the text size it wants. */
    private ActivityController<Activity> controller;

    @Before
    public void everyPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        // Meta App Manager, Messenger and Instagram on the phone put their rows under Supported links,
        // each opening its own page.
        for (String name : Arrays.asList(SupportedLinks.APP_MANAGER, SupportedLinks.MESSENGER, SupportedLinks.INSTAGRAM)) {
            PackageInfo holder = new PackageInfo();
            holder.packageName = name;
            holder.applicationInfo = new ApplicationInfo();
            holder.applicationInfo.packageName = name;
            holder.applicationInfo.enabled = true;
            shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(holder);
        }
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
        Settings.FONT_SOURCE.resetToDefault();
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test
    public void onlyARowATapOpensSomethingFromWearsAChevron() {
        Set<String> wearing = new LinkedHashSet<>();
        int switches = 0;
        for (View row : rows(show())) {
            Preference preference = item(row);
            ImageView chevron = chevronOf(row);
            if (chevron == null) {
                if (preference instanceof SwitchPreference) {
                    View widget = row.findViewById(android.R.id.switch_widget);
                    assertTrue(preference.getTitle() + " lost its switch", widget instanceof Switch
                            && widget.getVisibility() == View.VISIBLE);
                    switches++;
                }
                continue;
            }
            String title = String.valueOf(preference.getTitle());
            assertFalse(title + " is a section title", preference instanceof PreferenceCategory);
            wearing.add(title);
            assertEquals(title, View.VISIBLE, ((View) chevron.getParent()).getVisibility());
            assertTrue(title, chevron.getDrawable() instanceof ScreenColors.Chevron);
            assertEquals(title + " chevron is read out", View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                    chevron.getImportantForAccessibility());
            assertTrue(title + " wears a chevron and has nothing to open", preference instanceof DialogPreference
                    || preference.getOnPreferenceClickListener() != null);
        }
        assertEquals(OPENS_SOMETHING, wearing);
        assertTrue("Licenses and Version still look alike", wearing.contains("Licenses") && !wearing.contains("Version"));
        assertTrue("only " + switches + " switch rows", switches > 10);
    }

    /**
     * Four rows act the moment they're tapped: the paused card turns Hushfacebook back on for the
     * next start, Clear diagnostic data clears it, Check now asks GitHub and says how that went in
     * its own summary, and Use your phone's font takes the picked font away. A chevron there would
     * promise something opens.
     */
    @Test
    public void thePausedCardAndClearActAtOnceAndWearNone() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        // The way back to the phone's font is on the page while a font file is picked.
        Settings.FONT_SOURCE.save("Inter.ttf");
        List<View> rows = rows(show());
        View card = rows.get(0);
        assertTrue(item(card).isSelectable());
        assertNotNull(item(card).getOnPreferenceClickListener());
        assertNull("the paused card wears a chevron", chevronOf(card));
        assertNull("Clear diagnostic data wears a chevron", chevronOf(rowTitled(rows, "Clear diagnostic data")));
        View checkNow = rowTitled(rows, "Check now");
        assertNotNull(item(checkNow).getOnPreferenceClickListener());
        assertNull("Check now wears a chevron", chevronOf(checkNow));
        assertNull("Use your phone's font wears a chevron", chevronOf(rowTitled(rows, "Use your phone's font")));
        assertNull("Return to regular Facebook wears a chevron", chevronOf(rowTitled(rows, "Return to regular Facebook")));
        assertNotNull("Licenses lost its chevron while paused", chevronOf(rowTitled(rows, "Licenses")));
    }

    /** Version and Licenses are one row class, so the list hands one's view to the other. */
    @Test
    public void aRecycledRowTakesItsChevronOffAndPutsItBack() {
        List<View> rows = rows(show());
        View view = rowTitled(rows, "Licenses");
        ListView list = (ListView) view.getParent();
        Preference licenses = item(view);
        Preference version = item(rowTitled(rows, "Version"));
        assertSame(licenses.getClass(), version.getClass());
        ViewGroup frame = view.findViewById(android.R.id.widget_frame);

        assertSame(view, version.getView(view, list));
        assertNull("Version kept the chevron it was handed", chevronOf(view));
        assertEquals("an empty frame still takes room from the text", View.GONE, frame.getVisibility());

        assertSame(view, licenses.getView(view, list));
        assertNotNull(chevronOf(view));
        assertEquals(View.VISIBLE, frame.getVisibility());
        licenses.getView(view, list);
        assertEquals("a second bind added a second chevron", 1, frame.getChildCount());
    }

    @Test
    public void atTwiceTheTextSizeTheChevronStaysInItsCardBesideTheWholeText() {
        RuntimeEnvironment.setFontScale(2f);
        assertPlaced(false);
    }

    @Test
    @Config(qualifiers = "+ar-rXB-ldrtl")
    public void inRightToLeftAtTwiceTheTextSizeTheChevronMirrors() {
        RuntimeEnvironment.setFontScale(2f);
        assertPlaced(true);
    }

    @Test
    public void theChevronPointsTheWayItsRowReads() {
        ScreenColors.Chevron chevron = new ScreenColors.Chevron(Color.WHITE, 6);
        chevron.setBounds(0, 0, 48, 72);
        assertTrue(chevron.isAutoMirrored());
        float[] ltr = chevron.points();
        assertTrue("left to right, the tip isn't on the right", ltr[2] > ltr[0] && ltr[2] > ltr[4]);
        assertEquals("the arms aren't even", ltr[1] + ltr[5], 2 * ltr[3], 0.01);
        assertTrue(chevron.setLayoutDirection(View.LAYOUT_DIRECTION_RTL));
        float[] rtl = chevron.points();
        assertTrue("right to left, the tip isn't on the left", rtl[2] < rtl[0] && rtl[2] < rtl[4]);
        assertEquals("not mirrored about its centre", 48 - ltr[2], rtl[2], 0.01);
    }

    /** A row out of reach, such as Import while an export runs, dims its chevron with its text. */
    @Test
    public void aRowOutOfReachDimsItsChevron() {
        SettingsDialog dialog = show();
        Preference importRow = SettingsL10nTest.pageOf(dialog).findPreference("action_import_settings");
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;

        importRow.setEnabled(false);
        ImageView chevron = chevronOf(rowTitled(rows(dialog), "Import settings"));
        assertFalse(chevron.isEnabled());
        assertEquals(ScreenColors.dimmedWhenDisabled(colors.summary).getColorForState(new int[0], 0),
                ((ScreenColors.Chevron) chevron.getDrawable()).currentColor());

        importRow.setEnabled(true);
        chevron = chevronOf(rowTitled(rows(dialog), "Import settings"));
        assertTrue(chevron.isEnabled());
        assertEquals(colors.summary, ((ScreenColors.Chevron) chevron.getDrawable()).currentColor());
    }

    /**
     * Every chevron at [rightToLeft]'s end of its row: inside the row's card, clear of its title and
     * summary, which show in full, and pointing the way the row reads.
     */
    private void assertPlaced(boolean rightToLeft) {
        List<View> rows = rows(show());
        assertEquals(2f, controller.get().getResources().getConfiguration().fontScale, 0f);
        int inset = dp(10);
        List<String> placed = new ArrayList<>();
        for (View row : rows) {
            ImageView chevron = chevronOf(row);
            if (chevron == null) continue;
            String name = String.valueOf(item(row).getTitle());
            placed.add(name);
            int left = leftIn(chevron, row);
            int right = left + chevron.getWidth();
            int top = topIn(chevron, row);
            assertEquals(name, dp(16), chevron.getWidth());
            assertEquals(name, dp(24), chevron.getHeight());
            assertTrue(name + ": the chevron is outside its card, " + left + ".." + right + " of " + row.getWidth(),
                    left >= inset && right <= row.getWidth() - inset);
            assertTrue(name + ": the chevron is outside its row", top >= 0 && top + chevron.getHeight() <= row.getHeight());
            assertEquals(name + ": the chevron is on the wrong end", rightToLeft, left < row.getWidth() / 2);
            for (int id : new int[]{android.R.id.title, android.R.id.summary}) {
                TextView text = row.findViewById(id);
                if (text == null || text.getVisibility() != View.VISIBLE) continue;
                int textLeft = leftIn(text, row);
                int textRight = textLeft + text.getWidth();
                assertTrue(name + ": the chevron sits on \"" + text.getText() + "\"",
                        rightToLeft ? right <= textLeft : textRight <= left);
                assertEquals(name + ": \"" + text.getText() + "\" was cut off", 0, cutOff(text));
            }
            assertEquals(name, rightToLeft ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR,
                    chevron.getDrawable().getLayoutDirection());
            float[] at = ((ScreenColors.Chevron) chevron.getDrawable()).points();
            assertEquals(name + " points the wrong way", rightToLeft, at[2] < at[0]);
        }
        assertEquals("rows with a chevron: " + placed, OPENS_SOMETHING.size(), placed.size());
        // At twice the text size the rows have to wrap, or this proves nothing about wrapping.
        for (View row : rows) {
            if (!"action_export_diagnostic_report".equals(item(row).getKey())) continue;
            TextView summary = row.findViewById(android.R.id.summary);
            assertTrue("nothing wrapped at twice the text size", summary.getLineCount() > 2);
            return;
        }
        throw new AssertionError("no export row");
    }

    private SettingsDialog show() {
        if (controller == null) controller = Robolectric.buildActivity(Activity.class).setup();
        return SettingsL10nTest.show(controller.get());
    }

    /** Every row the list draws, laid out on the phone's width, tall enough that none is left off. */
    private List<View> rows(SettingsDialog dialog) {
        ListView list = dialog.getView().findViewById(android.R.id.list);
        HushfacebookPreferenceFragment page = (HushfacebookPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        // Verify every preference row independently of the category shell.
        list.setAdapter(page.getPreferenceScreen().getRootAdapter());
        list.setOnItemClickListener(page.getPreferenceScreen());
        assertNotNull("no list in the dialog", list);
        int width = controller.get().getResources().getDisplayMetrics().widthPixels;
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400_000, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, width, 400_000);
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < list.getChildCount(); index++) rows.add(list.getChildAt(index));
        assertEquals("the list left rows off", list.getAdapter().getCount(), rows.size());
        return rows;
    }

    private static View rowTitled(List<View> rows, String title) {
        for (View row : rows) {
            if (title.contentEquals(String.valueOf(item(row).getTitle()))) return row;
        }
        throw new AssertionError("no row titled " + title);
    }

    private static Preference item(View row) {
        ListView list = (ListView) row.getParent();
        return (Preference) list.getItemAtPosition(list.getPositionForView(row));
    }

    private static ImageView chevronOf(View row) {
        View chevron = row.findViewWithTag(ScreenColors.CHEVRON);
        return chevron instanceof ImageView ? (ImageView) chevron : null;
    }

    private static int leftIn(View view, View row) {
        int x = 0;
        for (View at = view; at != row; at = (View) at.getParent()) x += at.getLeft();
        return x;
    }

    private static int topIn(View view, View row) {
        int y = 0;
        for (View at = view; at != row; at = (View) at.getParent()) y += at.getTop();
        return y;
    }

    /** How many characters [text] leaves off behind an ellipsis. */
    private static int cutOff(TextView text) {
        Layout layout = text.getLayout();
        assertNotNull("\"" + text.getText() + "\" was never laid out", layout);
        int cut = 0;
        for (int line = 0; line < layout.getLineCount(); line++) cut += layout.getEllipsisCount(line);
        return cut;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }
}
