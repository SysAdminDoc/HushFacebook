/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The top bar's Messenger button, count and logo hides (#181) and Create (#257) and search (#321) button hides: each
 * switch starts off, does its one job when on, and leaves Facebook's own buttons, count and logo
 * alone when off, paused or before the settings are ready.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TopBarButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_MESSENGER_BUTTON.resetToDefault();
        Settings.HIDE_MESSENGER_COUNT.resetToDefault();
        Settings.HIDE_FACEBOOK_LOGO.resetToDefault();
        Settings.HIDE_CREATE_BUTTON.resetToDefault();
        Settings.HIDE_SEARCH_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    private static boolean reported(String count) {
        for (String line : HookStatus.report()) {
            if (line.contains(count)) return true;
        }
        return false;
    }

    private static View button() {
        return new FrameLayout(RuntimeEnvironment.getApplication());
    }

    private static ImageView logo() {
        ImageView logo = new ImageView(RuntimeEnvironment.getApplication());
        logo.setImageDrawable(new ColorDrawable(0xff1877f2));
        return logo;
    }

    @Test
    public void everySwitchStartsOffAndBelongsToTheTabBarFamily() {
        assertFalse(Settings.HIDE_MESSENGER_BUTTON.defaultValue);
        assertFalse(Settings.HIDE_MESSENGER_COUNT.defaultValue);
        assertFalse(Settings.HIDE_FACEBOOK_LOGO.defaultValue);
        assertTrue("the button is built as the screen starts", Settings.HIDE_MESSENGER_BUTTON.rebootApp);
        assertFalse("the count shows with the next count", Settings.HIDE_MESSENGER_COUNT.rebootApp);
        assertTrue("the logo is built as the screen starts", Settings.HIDE_FACEBOOK_LOGO.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_MESSENGER_BUTTON));
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_MESSENGER_COUNT));
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_FACEBOOK_LOGO));
    }

    @Test
    public void offLeavesTheButtonCountAndLogoAlone() {
        View button = button();
        TopBarButtons.messengerButton(button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(7, TopBarButtons.messengerCount(7));
        ImageView logo = logo();
        TopBarButtons.facebookLogo(logo);
        assertEquals(255, logo.getImageAlpha());
        assertEquals(1f, logo.getAlpha(), 0f);
    }

    @Test
    public void onHidesTheButtonAndOnlyTheButton() {
        Settings.HIDE_MESSENGER_BUTTON.save(true);
        View button = button();
        TopBarButtons.messengerButton(button);
        assertEquals("the button leaves the bar's layout", View.GONE, button.getVisibility());
        assertEquals("the count is a separate switch", 7, TopBarButtons.messengerCount(7));
        ImageView logo = logo();
        TopBarButtons.facebookLogo(logo);
        assertEquals("the logo is a separate switch", 255, logo.getImageAlpha());
        assertTrue(reported(TopBarButtons.BUTTON_HIDDEN));
        assertFalse(reported(TopBarButtons.COUNT_HIDDEN));
    }

    @Test
    public void onDropsTheCountAndKeepsTheButton() {
        Settings.HIDE_MESSENGER_COUNT.save(true);
        assertEquals(0, TopBarButtons.messengerCount(12));
        assertEquals("no count stays no count", 0, TopBarButtons.messengerCount(0));
        View button = button();
        TopBarButtons.messengerButton(button);
        assertEquals("the button stays", View.VISIBLE, button.getVisibility());
        assertTrue(reported(TopBarButtons.COUNT_HIDDEN));
    }

    @Test
    public void onMakesTheLogoInvisibleAndKeepsItsPlace() {
        Settings.HIDE_FACEBOOK_LOGO.save(true);
        ImageView logo = logo();
        logo.setOnTouchListener((v, event) -> true);
        logo.layout(0, 0, 90, 90);
        TopBarButtons.facebookLogo(logo);
        assertEquals(0, logo.getImageAlpha());
        assertEquals("it keeps its place in the layout", View.VISIBLE, logo.getVisibility());
        assertEquals(90, logo.getWidth());
        long now = SystemClock.uptimeMillis();
        MotionEvent touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0);
        assertTrue("it keeps its touch listener, the long press", logo.dispatchTouchEvent(touch));

        // A view that isn't an image fades as a whole.
        View other = button();
        TopBarButtons.facebookLogo(other);
        assertEquals(0f, other.getAlpha(), 0f);
    }

    @Test
    public void offAgainRestoresNothingBecauseNothingWasChanged() {
        Settings.HIDE_FACEBOOK_LOGO.save(true);
        Settings.HIDE_FACEBOOK_LOGO.save(false);
        ImageView logo = logo();
        TopBarButtons.facebookLogo(logo);
        assertEquals(255, logo.getImageAlpha());
        Settings.HIDE_MESSENGER_COUNT.save(true);
        Settings.HIDE_MESSENGER_COUNT.save(false);
        assertEquals(4, TopBarButtons.messengerCount(4));
    }

    @Test
    public void pausedLeavesFacebooksOwnWhateverTheSwitches() {
        Settings.HIDE_MESSENGER_BUTTON.save(true);
        Settings.HIDE_MESSENGER_COUNT.save(true);
        Settings.HIDE_FACEBOOK_LOGO.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        View button = button();
        TopBarButtons.messengerButton(button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(5, TopBarButtons.messengerCount(5));
        ImageView logo = logo();
        TopBarButtons.facebookLogo(logo);
        assertEquals(255, logo.getImageAlpha());

        PauseForTests.resume();
        TopBarButtons.messengerButton(button);
        assertEquals("resumed, the switch applies again", View.GONE, button.getVisibility());
        assertEquals(0, TopBarButtons.messengerCount(5));
    }

    @Test
    public void aMissingViewIsLeftAlone() {
        Settings.HIDE_MESSENGER_BUTTON.save(true);
        Settings.HIDE_FACEBOOK_LOGO.save(true);
        TopBarButtons.messengerButton(null);
        TopBarButtons.facebookLogo(null);
        TopBarButtons.barButton(null, TopBarButtons.MESSENGER_SPEC);
        assertEquals(3, TopBarButtons.barButtonCount(null, 3));
    }

    @Test
    public void theNewerBarHidesOnlyTheGlyphNamedMessenger() {
        Settings.HIDE_MESSENGER_BUTTON.save(true);
        View messenger = button();
        View search = button();
        TopBarButtons.barButton(messenger, "messenger");
        TopBarButtons.barButton(search, "search");
        TopBarButtons.barButton(button(), null);
        assertEquals(View.GONE, messenger.getVisibility());
        assertEquals("another button of the bar stays", View.VISIBLE, search.getVisibility());
        assertTrue(reported(TopBarButtons.BUTTON_HIDDEN));
    }

    @Test
    public void theNewerBarOffLeavesItsMessengerGlyphAndCount() {
        View messenger = button();
        TopBarButtons.barButton(messenger, "messenger");
        assertEquals(View.VISIBLE, messenger.getVisibility());
        assertEquals(9, TopBarButtons.barButtonCount(messenger, 9));
    }

    @Test
    public void theNewerBarDropsOnlyTheMessengerGlyphsCount() {
        Settings.HIDE_MESSENGER_COUNT.save(true);
        View messenger = button();
        View notifications = button();
        // Marked as it is made, so even the first count it gets is dropped.
        TopBarButtons.barButton(messenger, "messenger");
        TopBarButtons.barButton(notifications, "notifications");
        assertEquals(0, TopBarButtons.barButtonCount(messenger, 6));
        assertEquals("the glyph class's other buttons keep theirs", 6, TopBarButtons.barButtonCount(notifications, 6));
        assertEquals("a glyph the hook never saw keeps its count", 6, TopBarButtons.barButtonCount(button(), 6));
        assertEquals("the button stays", View.VISIBLE, messenger.getVisibility());
        assertTrue(reported(TopBarButtons.COUNT_HIDDEN));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused, Facebook's own count", 6, TopBarButtons.barButtonCount(messenger, 6));
    }

    @Test
    public void theCreateSwitchStartsOffWaitsForARestartAndBelongsToTheTabBarFamily() {
        assertFalse(Settings.HIDE_CREATE_BUTTON.defaultValue);
        assertTrue("the button is built as the screen starts", Settings.HIDE_CREATE_BUTTON.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_CREATE_BUTTON));
    }

    @Test
    public void createOnHidesTheCreateButtonOnBothBarsAndNothingElse() {
        Settings.HIDE_CREATE_BUTTON.save(true);
        View create = button();
        TopBarButtons.createButton(create);
        assertEquals("the button leaves the bar's layout", View.GONE, create.getVisibility());
        View messenger = button();
        TopBarButtons.messengerButton(messenger);
        assertEquals("the Messenger button is a separate switch", View.VISIBLE, messenger.getVisibility());

        View glyph = button();
        View newerMessenger = button();
        View search = button();
        TopBarButtons.barButton(glyph, TopBarButtons.CREATE_SPEC);
        TopBarButtons.barButton(newerMessenger, TopBarButtons.MESSENGER_SPEC);
        TopBarButtons.barButton(search, "search");
        assertEquals(View.GONE, glyph.getVisibility());
        assertEquals(View.VISIBLE, newerMessenger.getVisibility());
        assertEquals(View.VISIBLE, search.getVisibility());
        assertEquals("the Create glyph's count is Facebook's", 4, TopBarButtons.barButtonCount(glyph, 4));
        assertTrue(reported(TopBarButtons.CREATE_HIDDEN));
        assertFalse(reported(TopBarButtons.BUTTON_HIDDEN));
    }

    @Test
    public void createOffPausedOrMissingLeavesFacebooksOwn() {
        View create = button();
        TopBarButtons.createButton(create);
        TopBarButtons.barButton(create, TopBarButtons.CREATE_SPEC);
        assertEquals(View.VISIBLE, create.getVisibility());

        Settings.HIDE_CREATE_BUTTON.save(true);
        Settings.HIDE_MESSENGER_BUTTON.save(true);
        TopBarButtons.barButton(create, TopBarButtons.CREATE_SPEC);
        assertFalse("the Create glyph isn't taken for Messenger", reported(TopBarButtons.BUTTON_HIDDEN));
        create.setVisibility(View.VISIBLE);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TopBarButtons.createButton(create);
        assertEquals("paused, Facebook's own button", View.VISIBLE, create.getVisibility());
        PauseForTests.resume();
        TopBarButtons.createButton(null);
        TopBarButtons.barButton(null, TopBarButtons.CREATE_SPEC);
    }

    @Test
    public void theSearchSwitchStartsOffWaitsForARestartAndBelongsToTheTabBarFamily() {
        assertFalse(Settings.HIDE_SEARCH_BUTTON.defaultValue);
        assertTrue("the button is built as the screen starts", Settings.HIDE_SEARCH_BUTTON.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.HIDE_SEARCH_BUTTON));
    }

    @Test
    public void searchOnHidesTheSearchButtonAndNothingElse() {
        Settings.HIDE_SEARCH_BUTTON.save(true);
        View search = button();
        TopBarButtons.searchButton(search);
        assertEquals("the button leaves the bar's layout", View.GONE, search.getVisibility());
        View create = button();
        TopBarButtons.createButton(create);
        View messenger = button();
        TopBarButtons.messengerButton(messenger);
        TopBarButtons.barButton(messenger, TopBarButtons.MESSENGER_SPEC);
        assertEquals("the Create button is a separate switch", View.VISIBLE, create.getVisibility());
        assertEquals("the Messenger button is a separate switch", View.VISIBLE, messenger.getVisibility());
        assertTrue(reported(TopBarButtons.SEARCH_HIDDEN));
        assertFalse(reported(TopBarButtons.CREATE_HIDDEN));
    }

    @Test
    public void searchOffPausedOrMissingLeavesFacebooksOwn() {
        View search = button();
        TopBarButtons.searchButton(search);
        assertEquals(View.VISIBLE, search.getVisibility());

        Settings.HIDE_SEARCH_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        TopBarButtons.searchButton(search);
        assertEquals("paused, Facebook's own button", View.VISIBLE, search.getVisibility());
        assertFalse(reported(TopBarButtons.SEARCH_HIDDEN));
        PauseForTests.resume();
        TopBarButtons.searchButton(null);
    }

    @Test
    public void theNewerBarHidesSearchOnHomeOnly() {
        Settings.HIDE_SEARCH_BUTTON.save(true);
        View home = button();
        TopBarButtons.barSearchButton(home, TopBarButtons.HOME_BAR_SCREEN);
        assertEquals("Home's button goes", View.GONE, home.getVisibility());
        assertTrue(reported(TopBarButtons.SEARCH_HIDDEN));
    }

    @Test
    public void anotherScreensSearchStaysWithTheSwitchOn() {
        Settings.HIDE_SEARCH_BUTTON.save(true);
        View marketplace = button();
        TopBarButtons.barSearchButton(marketplace, "MarketplaceHomeFragmentV2");
        assertEquals("Marketplace keeps its search", View.VISIBLE, marketplace.getVisibility());
        View unnamed = button();
        TopBarButtons.barSearchButton(unnamed, null);
        assertEquals("a bar with no name isn't Home's", View.VISIBLE, unnamed.getVisibility());
        assertFalse(reported(TopBarButtons.SEARCH_HIDDEN));
        TopBarButtons.barSearchButton(null, TopBarButtons.HOME_BAR_SCREEN);
    }

    @Test
    public void aKeptSearchButtonStillTakesTheLongPressOnAnyScreen() {
        Settings.HIDE_SEARCH_BUTTON.save(true);
        Settings.SEARCH_WITH_PICTURE.save(true);
        View marketplace = button();
        TopBarButtons.barSearchButton(marketplace, "MarketplaceHomeFragmentV2");
        assertEquals(View.VISIBLE, marketplace.getVisibility());
        assertTrue("Lens stays on the button that's kept", marketplace.isLongClickable());
        Settings.SEARCH_WITH_PICTURE.resetToDefault();
    }
}
