/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Each patch whose switch lives on the News feed page brings the page with it, so a build patched
 * with only that one feed patch still shows its switch. The page used to open only for a fixed list
 * of families, and Show full post text, Translate posts, the post gestures, See who shared and
 * Photo carousel weren't on it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class NewsFeedPageGateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void aFeedPatchAloneStillShowsItsSwitch() {
        Map<PatchFamily, String> rows = new LinkedHashMap<>();
        rows.put(PatchFamily.FULL_POST_TEXT, Settings.SHOW_FULL_POST_TEXT.key);
        rows.put(PatchFamily.TRANSLATE_POSTS, Settings.TRANSLATE_POST_LINK.key);
        rows.put(PatchFamily.REEL_GESTURES, Settings.SELECT_POST_TEXT.key);
        rows.put(PatchFamily.WHO_SHARED, Settings.SEE_WHO_SHARED.key);
        rows.put(PatchFamily.PHOTO_CAROUSEL, Settings.PHOTO_CAROUSEL.key);
        for (Map.Entry<PatchFamily, String> row : rows.entrySet()) {
            PatchFamily.inBuildForTests = EnumSet.of(row.getKey());
            try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
                HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
                controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
                assertTrue(row.getKey() + " alone has no row for " + row.getValue(),
                        keysOf(page.getPreferenceScreen()).contains(row.getValue()));
            }
        }
    }

    private static List<String> keysOf(PreferenceGroup group) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) keys.addAll(keysOf((PreferenceGroup) row));
            else if (row.getKey() != null) keys.add(row.getKey());
        }
        return keys;
    }
}
