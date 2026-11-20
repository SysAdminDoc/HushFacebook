/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Dark box behind folded captions (#472) sits right under Dark box behind open captions on Reels and
 * Watch, off to start, and shows only in a build with the caption hook, as that one does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class CaptionBoxRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FULL_POST_TEXT);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.DIM_FOLDED_REEL_CAPTIONS.resetToDefault();
        Settings.TEXT_BOX_DARKNESS.resetToDefault();
    }

    @Test
    public void theFoldedCaptionsRowFollowsTheOpenOnesAndStartsOff() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int open = indexOfKey(rows, Settings.DIM_OPEN_REEL_CAPTIONS.key);
            int folded = indexOfKey(rows, Settings.DIM_FOLDED_REEL_CAPTIONS.key);
            assertTrue("Dark box behind open captions is missing", open >= 0);
            assertEquals(open + 1, folded);
            SwitchPreference row = (SwitchPreference) rows.get(folded);
            assertEquals("Dark box behind folded captions", String.valueOf(row.getTitle()));
            assertEquals("A folded reel caption, the short one with more at the end, sits on the same dark box. Turn on "
                    + "both to box every caption. A change shows from the next reel.", String.valueOf(row.getSummary()));
            assertFalse(row.isChecked());
            assertFalse(Settings.DIM_FOLDED_REEL_CAPTIONS.defaultValue);
        }
    }

    /**
     * #490: reel names get the box from the next row, then names drawn over feed videos (#533), then the
     * darkness and soft edges every box shares.
     */
    @Test
    public void reelNamesDarknessAndSoftEdgesFollowTheCaptionRows() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int folded = indexOfKey(rows, Settings.DIM_FOLDED_REEL_CAPTIONS.key);
            assertTrue("Dark box behind folded captions is missing", folded >= 0);
            assertEquals(folded + 1, indexOfKey(rows, Settings.DIM_REEL_NAMES.key));
            assertEquals(folded + 2, indexOfKey(rows, Settings.DIM_FEED_VIDEO_NAMES.key));
            assertEquals(folded + 3, indexOfKey(rows, Settings.TEXT_BOX_DARKNESS.key));
            assertEquals(folded + 4, indexOfKey(rows, Settings.SOFT_TEXT_BOXES.key));

            SwitchPreference names = (SwitchPreference) rows.get(folded + 1);
            assertEquals("Dark box behind reel names", String.valueOf(names.getTitle()));
            assertEquals("The name at the bottom of a reel sits on the same dark box, so it's easy to read over a bright "
                    + "video. A change shows from the next reel.", String.valueOf(names.getSummary()));
            assertFalse(names.isChecked());
            assertFalse(Settings.DIM_REEL_NAMES.defaultValue);

            SwitchPreference feedNames = (SwitchPreference) rows.get(folded + 2);
            assertEquals("Dark box behind feed video names", String.valueOf(feedNames.getTitle()));
            assertEquals("When a video in your feed has the poster's name on top of the video, that name sits on the "
                    + "same dark box. A change shows on the posts drawn after it.", String.valueOf(feedNames.getSummary()));
            assertFalse(feedNames.isChecked());
            assertFalse(Settings.DIM_FEED_VIDEO_NAMES.defaultValue);

            ValueRows.PercentRow darkness = (ValueRows.PercentRow) rows.get(folded + 3);
            assertEquals("Box darkness", String.valueOf(darkness.getTitle()));
            assertEquals("The dark boxes are 70 percent black. Type a number to change it.",
                    String.valueOf(darkness.getSummary()));
            assertEquals("How dark the boxes behind captions and names are, in percent, from 10 to 100. Leave it "
                    + "blank for 70.", String.valueOf(darkness.getDialogMessage()));
            darkness.setText("45");
            assertEquals("The dark boxes are 45 percent black.", String.valueOf(darkness.getSummary()));
            darkness.setText("4");
            assertEquals("The dark boxes are 10 percent black.", String.valueOf(darkness.getSummary()));

            SwitchPreference soft = (SwitchPreference) rows.get(folded + 4);
            assertEquals("Soft box edges", String.valueOf(soft.getTitle()));
            assertEquals("The dark boxes fade out at their edges instead of ending sharp. A change shows from the next "
                    + "reel or story.", String.valueOf(soft.getSummary()));
            assertFalse(soft.isChecked());
        }
    }

    /** #490: story names get their switch on the Stories page, even when no stories patch is in. */
    @Test
    public void storyNamesHaveASwitchOnTheStoriesPage() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            int names = indexOfKey(rows, Settings.DIM_STORY_NAMES.key);
            assertTrue("Dark box behind story names is missing", names >= 0);
            SwitchPreference row = (SwitchPreference) rows.get(names);
            assertEquals("Dark box behind story names", String.valueOf(row.getTitle()));
            assertEquals("Names in the stories row and on an open story sit on a dark box, so a bright story doesn't "
                    + "hide them. Box darkness under Reels and Watch sets how dark. A change shows the next time the "
                    + "stories load.", String.valueOf(row.getSummary()));
            assertFalse(row.isChecked());
            assertFalse(Settings.DIM_STORY_NAMES.defaultValue);
        }
    }

    @Test
    public void withoutTheCaptionHookNeitherShows() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(page(controller));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_OPEN_REEL_CAPTIONS.key));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_FOLDED_REEL_CAPTIONS.key));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_REEL_NAMES.key));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_FEED_VIDEO_NAMES.key));
            assertEquals(-1, indexOfKey(rows, Settings.DIM_STORY_NAMES.key));
            assertEquals(-1, indexOfKey(rows, Settings.TEXT_BOX_DARKNESS.key));
            assertEquals(-1, indexOfKey(rows, Settings.SOFT_TEXT_BOXES.key));
        }
    }

    private static HushfacebookPreferenceFragment page(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private static List<Preference> rowsOf(HushfacebookPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        return rows;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) collect((PreferenceGroup) row, rows);
            else rows.add(row);
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }
}
