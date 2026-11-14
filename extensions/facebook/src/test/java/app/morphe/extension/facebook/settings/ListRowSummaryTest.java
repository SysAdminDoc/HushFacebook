/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.preference.ListPreference;

import app.morphe.extension.facebook.misc.RefreshRate;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.shared.SettingsContextRule;

import java.util.IllegalFormatException;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * List rows show their summary as written. Android's ListPreference formats its summary with the
 * chosen entry, so the text size row's "130% of the size" summary threw as the row was drawn, and
 * picking any size but Facebook's own closed Facebook.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ListRowSummaryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.TEXT_SIZE.resetToDefault();
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.REFRESH_RATE.resetToDefault();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void androidsOwnListRowFormatsAPercentSignAndThrows() {
        ListPreference stock = new ListPreference(context());
        stock.setEntries(new CharSequence[] {"130%"});
        stock.setEntryValues(new CharSequence[] {"P130"});
        stock.setValue("P130");
        stock.setSummary("Facebook's text is 130% of the size your phone gives it.");
        assertThrows(IllegalFormatException.class, stock::getSummary);
    }

    @Test
    public void everyTextSizeChoiceShowsItsSummaryAsWritten() {
        for (TextSize.Scale scale : TextSize.Scale.values()) {
            Settings.TEXT_SIZE.save(scale);
            ValueRows.TextSizeRow row = HushfacebookPreferenceFragment.textSizeRow(context());
            assertEquals(scale.name(), HushfacebookPreferenceFragment.textSizeSummary(scale), String.valueOf(row.getSummary()));
        }
    }

    /** The refresh rate row (#480): its summary as written for every choice, and the row lists the one set. */
    @Test
    public void everyRefreshRateChoiceShowsItsSummaryAsWritten() {
        for (RefreshRate.Rate rate : RefreshRate.Rate.values()) {
            Settings.REFRESH_RATE.save(rate);
            ValueRows.RefreshRateRow row = HushfacebookPreferenceFragment.refreshRateRow(context());
            assertEquals(rate.name(), HushfacebookPreferenceFragment.refreshRateSummary(rate), String.valueOf(row.getSummary()));
            assertEquals(rate.name(), HushfacebookPreferenceFragment.refreshRateLabel(rate), String.valueOf(row.getEntry()));
        }
        assertEquals("Facebook asks your screen for 60 Hz. Your phone's own refresh rate setting still caps it.",
                HushfacebookPreferenceFragment.refreshRateSummary(RefreshRate.Rate.HZ60));
    }

    @Test
    public void theAccentRowPicksTheWallpapersStandInWhenMaterialYouIsInTheBuild() {
        Settings.ACCENT_COLOR.save(app.morphe.extension.facebook.theme.AccentColor.Preset.TEAL);
        ValueRows.AccentRow withMaterialYou = HushfacebookPreferenceFragment.accentRow(context(), true);
        assertEquals(true, withMaterialYou.isEnabled());
        assertEquals("Material You uses Teal in place of your wallpaper's accent. Restart Facebook to see the change.",
                String.valueOf(withMaterialYou.getSummary()));
        assertEquals("Wallpaper accent", String.valueOf(withMaterialYou.getEntries()[0]));
        withMaterialYou.setValue("FACEBOOK");
        assertEquals("Material You uses your wallpaper's accent. Pick a color to use it instead.",
                String.valueOf(withMaterialYou.getSummary()));

        ValueRows.AccentRow alone = HushfacebookPreferenceFragment.accentRow(context(), false);
        assertEquals(true, alone.isEnabled());
        assertEquals("Facebook blue", String.valueOf(alone.getEntries()[0]));
        assertEquals("Links, buttons, switches and the selected tab use Teal instead of Facebook's blue. Restart Facebook "
                + "to see the change.", String.valueOf(alone.getSummary()));
    }

    @Test
    public void aListRowKeepsAPercentSignInItsSummary() {
        ValueRows.PlainSummaryList row = new ValueRows.PlainSummaryList(context()) { };
        row.setEntries(new CharSequence[] {"85%"});
        row.setEntryValues(new CharSequence[] {"P85"});
        row.setValue("P85");
        row.setSummary("85% of the size, 100%% sure");
        assertEquals("85% of the size, 100%% sure", String.valueOf(row.getSummary()));
    }
}
