/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Choose the quality on each save (#448), driven the way the reel's Download button and a video's
 * Download to phone call it: the save goes in as a callback. Off, paused, or with no screen to ask
 * on, the caller saves at once at Download quality. On, the list shows with Download quality
 * checked, and the save starts at the quality picked while Download quality keeps its value.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class QualityChooserTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity screen;

    /** The quality each save the list started read when it started. */
    private final List<DownloadQuality> started = new ArrayList<>();

    private final QualityChooser.Save save = () -> {
        started.add(MediaDownload.quality());
        return true;
    };

    @Before
    public void setUp() {
        screen = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After
    public void restore() {
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        if (list != null && list.isShowing()) list.dismiss();
        idle();
        PauseForTests.resume();
        Settings.DOWNLOAD_QUALITY_ASK.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The list showing now, or null. */
    private static AlertDialog showing() {
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        return list != null && list.isShowing() ? list : null;
    }

    @Test
    public void offTheSaveStartsAtOnceAsBefore() {
        assertFalse(Settings.DOWNLOAD_QUALITY_ASK.defaultValue);
        assertFalse(QualityChooser.ask(screen, save));
        idle();
        assertNull("a list with the switch off", showing());
        assertTrue("the chooser started a save itself", started.isEmpty());
    }

    @Test
    public void onTheListShowsWithDownloadQualityCheckedAndThePickSavesAtItAlone() {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        Settings.DOWNLOAD_QUALITY_ASK.save(true);

        assertTrue(QualityChooser.ask(screen, save));
        idle();
        AlertDialog list = showing();
        assertNotNull("no list of qualities", list);
        assertTrue("a save started before a pick", started.isEmpty());

        ListView rows = list.getListView();
        assertEquals(DownloadQuality.values().length, rows.getAdapter().getCount());
        assertEquals("Best", String.valueOf(rows.getAdapter().getItem(0)));
        assertEquals("Smallest", String.valueOf(rows.getAdapter().getItem(DownloadQuality.values().length - 1)));
        assertEquals("Download quality isn't the one checked",
                DownloadQuality.P720.ordinal(), rows.getCheckedItemPosition());

        rows.performItemClick(null, DownloadQuality.P360.ordinal(), 0);
        idle();
        assertNull("the list stayed up after the pick", showing());
        assertEquals(List.of(DownloadQuality.P360), started);
        assertSame("the pick changed Download quality", DownloadQuality.P720, Settings.DOWNLOAD_QUALITY.get());
        assertSame("the pick outlived its save", DownloadQuality.P720, MediaDownload.quality());
    }

    @Test
    public void cancelSavesNothing() {
        Settings.DOWNLOAD_QUALITY_ASK.save(true);
        assertTrue(QualityChooser.ask(screen, save));
        idle();
        AlertDialog list = showing();
        assertNotNull(list);
        assertTrue(list.getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
        idle();
        assertNull(showing());
        assertTrue("Cancel started a save", started.isEmpty());
    }

    @Test
    public void pausedTheSaveStartsAtOnce() {
        Settings.DOWNLOAD_QUALITY_ASK.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(QualityChooser.ask(screen, save));
        idle();
        assertNull("a list while paused", showing());
    }

    @Test
    public void aPickThatThrowsLeavesDownloadQualityAsItWas() {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P1080);
        QualityChooser.picked(DownloadQuality.SMALLEST, () -> {
            started.add(MediaDownload.quality());
            throw new IllegalStateException("the save broke");
        });
        assertEquals(List.of(DownloadQuality.SMALLEST), started);
        assertSame(DownloadQuality.P1080, MediaDownload.quality());
    }
}
