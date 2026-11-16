/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Display;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplayManager;

import app.morphe.extension.facebook.misc.RefreshRate.Rate;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Refresh rate (#480): a Facebook window asks for the screen's fastest mode, or the fixed rate
 * chosen, only among modes at the screen's current resolution. Facebook's choice, a paused
 * Hushfacebook and a rate the screen doesn't offer ask for nothing, and they put Facebook's own
 * value back only on a window this class changed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RefreshRateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        RefreshRate.forgetForTests();
        PauseForTests.resume();
        Settings.REFRESH_RATE.resetToDefault();
    }

    /** A display mode as a screen reports it. */
    private static Display.Mode mode(int id, int width, int height, float hertz) {
        try {
            Constructor<Display.Mode> make = Display.Mode.class.getDeclaredConstructor(int.class, int.class, int.class, float.class);
            make.setAccessible(true);
            return make.newInstance(id, width, height, hertz);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** The modes a Galaxy S25 listed on 2026-11-14, set to FHD+, in its own ids: 120, 80, 60, 48, 30, 24 and 10 Hz at each resolution. */
    private static final Display.Mode[] S25 = {
            mode(1, 1440, 3120, 120.00001f), mode(2, 1440, 3120, 80.0f), mode(3, 1440, 3120, 60.000004f),
            mode(4, 1440, 3120, 48.000004f), mode(5, 1440, 3120, 30.000002f), mode(6, 1440, 3120, 24.000002f),
            mode(7, 1440, 3120, 10.0f),
            mode(8, 1080, 2340, 120.00001f), mode(9, 1080, 2340, 80.0f), mode(10, 1080, 2340, 60.000004f),
            mode(11, 1080, 2340, 48.000004f), mode(12, 1080, 2340, 30.000002f), mode(13, 1080, 2340, 24.000002f),
            mode(14, 1080, 2340, 10.0f),
    };
    private static final Display.Mode S25_FHD = S25[7];

    @Test
    public void theChoiceIsAModeAtTheResolutionTheScreenIsAt() {
        assertEquals(8, RefreshRate.pick(S25, S25_FHD, Rate.HIGHEST));
        assertEquals(8, RefreshRate.pick(S25, S25_FHD, Rate.HZ120));
        assertEquals(10, RefreshRate.pick(S25, S25_FHD, Rate.HZ60));
        // At WQHD+ the same choices are that resolution's modes.
        assertEquals(1, RefreshRate.pick(S25, S25[0], Rate.HIGHEST));
        assertEquals(3, RefreshRate.pick(S25, S25[3], Rate.HZ60));
    }

    @Test
    public void facebooksChoiceAndARateTheScreenLacksAskForNothing() {
        assertEquals(0, RefreshRate.pick(S25, S25_FHD, Rate.FACEBOOK));
        assertEquals("the S25 has 80 Hz, not 90", 0, RefreshRate.pick(S25, S25_FHD, Rate.HZ90));
        assertEquals(0, RefreshRate.pick(S25, S25_FHD, Rate.HZ144));
        assertEquals("a screen with no current mode", 0, RefreshRate.pick(S25, null, Rate.HIGHEST));
        assertEquals("a screen that lists none", 0, RefreshRate.pick(null, S25_FHD, Rate.HIGHEST));
    }

    @Test
    public void aRateCountsWhenTheScreenReportsItSlightlyOff() {
        Display.Mode[] ntsc = {mode(1, 1080, 2400, 59.94f), mode(2, 1080, 2400, 119.88f), mode(3, 1080, 2400, 90.0f)};
        assertEquals(1, RefreshRate.pick(ntsc, ntsc[0], Rate.HZ60));
        assertEquals(2, RefreshRate.pick(ntsc, ntsc[0], Rate.HZ120));
        assertEquals(3, RefreshRate.pick(ntsc, ntsc[0], Rate.HZ90));
        assertEquals(2, RefreshRate.pick(ntsc, ntsc[0], Rate.HIGHEST));
        Display.Mode[] far = {mode(1, 1080, 2400, 60f), mode(2, 1080, 2400, 88f)};
        assertEquals("88 Hz isn't 90", 0, RefreshRate.pick(far, far[0], Rate.HZ90));
    }

    @Test
    public void theListOffersTheRatesTheScreenHasAndKeepsTheOneSet() {
        assertEquals(Arrays.asList(Rate.FACEBOOK, Rate.HIGHEST, Rate.HZ60, Rate.HZ120),
                RefreshRate.offered(S25, S25_FHD, Rate.FACEBOOK));
        assertEquals("a 90 Hz choice brought from another phone stays in the list",
                Arrays.asList(Rate.FACEBOOK, Rate.HIGHEST, Rate.HZ60, Rate.HZ90, Rate.HZ120),
                RefreshRate.offered(S25, S25_FHD, Rate.HZ90));
        assertEquals(Arrays.asList(Rate.FACEBOOK, Rate.HIGHEST), RefreshRate.offered(null, null, Rate.HIGHEST));
    }

    @Test
    public void aGamingPhoneGets144And165AsChoices() {
        // A 165 Hz screen in the shape gaming phones report: 165, 144, 120, 90 and 60 Hz at one resolution.
        Display.Mode[] fast = {
                mode(1, 1080, 2448, 165.00002f), mode(2, 1080, 2448, 144.00002f), mode(3, 1080, 2448, 120.00001f),
                mode(4, 1080, 2448, 90.0f), mode(5, 1080, 2448, 60.000004f),
        };
        assertEquals(1, RefreshRate.pick(fast, fast[4], Rate.HZ165));
        assertEquals(2, RefreshRate.pick(fast, fast[4], Rate.HZ144));
        assertEquals(1, RefreshRate.pick(fast, fast[4], Rate.HIGHEST));
        assertEquals(Arrays.asList(Rate.FACEBOOK, Rate.HIGHEST, Rate.HZ60, Rate.HZ90, Rate.HZ120, Rate.HZ144, Rate.HZ165),
                RefreshRate.offered(fast, fast[4], Rate.FACEBOOK));
        assertEquals("the S25 tops out at 120", 0, RefreshRate.pick(S25, S25_FHD, Rate.HZ165));
        assertEquals("165 Hz", Rate.HZ165.hertzLabel());
    }

    @Test
    public void everyChoiceRoundTripsThroughItsFileValue() {
        for (Rate rate : Rate.values()) assertEquals(rate, Rate.fromFile(rate.fileValue));
        assertEquals(null, Rate.fromFile("HZ120"));
        assertEquals(null, Rate.fromFile(120));
        assertEquals("120 Hz", Rate.HZ120.hertzLabel());
        assertEquals(null, Rate.HIGHEST.hertzLabel());
    }

    /**
     * A Facebook screen on a phone with [extra] modes at its resolution beside its current one,
     * ids from the current one's plus 100 in order. The current mode is first, and under
     * Robolectric its id is 0, the same as no choice at all.
     */
    private static ActivityController<Activity> screenWith(float... extra) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create();
        @SuppressWarnings("deprecation")
        Display display = controller.get().getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode[] modes = new Display.Mode[extra.length + 2];
        modes[0] = current;
        for (int i = 0; i < extra.length; i++) {
            modes[i + 1] = mode(current.getModeId() + 100 + i, current.getPhysicalWidth(), current.getPhysicalHeight(), extra[i]);
        }
        // A faster mode at another resolution, which no choice may pick.
        modes[extra.length + 1] = mode(current.getModeId() + 200, current.getPhysicalWidth() * 2,
                current.getPhysicalHeight() * 2, 240f);
        ShadowDisplayManager.setSupportedModes(display.getDisplayId(), modes);
        return controller;
    }

    @SuppressWarnings("deprecation")
    private static int currentMode(Activity activity) {
        return activity.getWindowManager().getDefaultDisplay().getMode().getModeId();
    }

    private static int asked(Activity activity) {
        return activity.getWindow().getAttributes().preferredDisplayModeId;
    }

    @Test
    public void aScreenAsksForTheFastestModeAndGivesItBackWhenTheChoiceIsFacebooks() {
        try (ActivityController<Activity> controller = screenWith(120f, 90f)) {
            Activity activity = controller.get();
            int fastest = currentMode(activity) + 100;
            assertEquals(0, asked(activity));

            Settings.REFRESH_RATE.save(Rate.HIGHEST);
            RefreshRate.activity(activity);
            assertEquals(fastest, asked(activity));

            Settings.REFRESH_RATE.save(Rate.HZ90);
            RefreshRate.activity(activity);
            assertEquals(fastest + 1, asked(activity));

            Settings.REFRESH_RATE.save(Rate.FACEBOOK);
            RefreshRate.activity(activity);
            assertEquals(0, asked(activity));
        }
    }

    @Test
    public void facebooksOwnModeStaysUnlessThisClassSetTheWindowsMode() {
        try (ActivityController<Activity> controller = screenWith(120f, 90f)) {
            Activity activity = controller.get();
            int fastest = currentMode(activity) + 100;
            int facebooks = fastest + 1;
            android.view.WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            attributes.preferredDisplayModeId = facebooks;
            activity.getWindow().setAttributes(attributes);

            RefreshRate.activity(activity);
            assertEquals("Facebook's choice touched a mode Facebook set", facebooks, asked(activity));

            // Set here, then changed by someone else: the later value stays when the choice goes back.
            Settings.REFRESH_RATE.save(Rate.HIGHEST);
            RefreshRate.activity(activity);
            assertEquals(fastest, asked(activity));
            attributes = activity.getWindow().getAttributes();
            attributes.preferredDisplayModeId = facebooks;
            activity.getWindow().setAttributes(attributes);
            Settings.REFRESH_RATE.save(Rate.FACEBOOK);
            RefreshRate.activity(activity);
            assertEquals(facebooks, asked(activity));
        }
    }

    @Test
    public void pausingHushfacebookGivesTheScreenBackToFacebook() {
        try (ActivityController<Activity> controller = screenWith(120f)) {
            Activity activity = controller.get();
            Settings.REFRESH_RATE.save(Rate.HIGHEST);
            RefreshRate.activity(activity);
            assertTrue(asked(activity) != 0);

            PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
            RefreshRate.activity(activity);
            assertEquals(0, asked(activity));
        }
    }

    @Test
    public void aRateTheScreenLacksLeavesTheWindowAlone() {
        try (ActivityController<Activity> controller = screenWith(120f)) {
            Activity activity = controller.get();
            Settings.REFRESH_RATE.save(Rate.HZ144);
            RefreshRate.activity(activity);
            assertEquals(0, asked(activity));
            List<Rate> offered = RefreshRate.offered(activity, Rate.FACEBOOK);
            assertFalse(offered.contains(Rate.HZ144));
            assertTrue(offered.contains(Rate.HZ120));
        }
    }
}
