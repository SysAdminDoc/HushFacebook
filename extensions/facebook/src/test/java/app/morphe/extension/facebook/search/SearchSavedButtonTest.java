/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Saved button in search (#433): with its switch on, Facebook's search bar ends in a bookmark that
 * opens Saved inside the task, in the field's hint colour. With the switch off or paused the bar
 * stays as Facebook built it, and a bar that already has the button never gets a second.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchSavedButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SEARCH_SAVED_BUTTON.resetToDefault();
        HookStatus.clear();
    }

    /** Facebook's bar as its constructor leaves it: the search field, then the clear button. */
    private static LinearLayout bar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        EditText field = new EditText(context);
        field.setHintTextColor(0xFF112233);
        bar.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        bar.addView(new ImageView(context));
        return bar;
    }

    /** Facebook's public Saved route, taken by an activity of the app's own package. */
    private static void addSavedRoute(Context context) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = context.getPackageName();
        info.activityInfo.name = context.getPackageName() + ".IntentUriHandler";
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_VIEW, Uri.parse("fb://saved")).setPackage(context.getPackageName()), info);
    }

    /** Whether a bar Facebook just built gets the button. For the paused hooks probe, with the switch on. */
    public static boolean aBarGetsTheButton() {
        LinearLayout bar = bar(RuntimeEnvironment.getApplication());
        SearchSavedButton.attach(bar);
        return bar.getChildCount() == 3;
    }

    @Test
    public void withTheSwitchOffTheBarStaysAsFacebookBuiltIt() {
        LinearLayout bar = bar(RuntimeEnvironment.getApplication());
        SearchSavedButton.attach(bar);
        assertEquals(2, bar.getChildCount());
    }

    @Test
    public void withTheSwitchOnTheBarEndsInASavedButton() {
        Settings.SEARCH_SAVED_BUTTON.save(true);
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout bar = bar(context);
        SearchSavedButton.attach(bar);
        assertEquals(3, bar.getChildCount());
        View button = bar.getChildAt(2);
        assertEquals(SearchSavedButton.TAG, button.getTag());
        assertEquals("Saved", button.getContentDescription().toString());
        assertTrue(button.isClickable());
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(SearchSavedButton.WIDTH_DP * density), button.getLayoutParams().width);
        assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, button.getLayoutParams().height);
        assertEquals("the icon takes the field's hint colour", 0xFF112233, SearchSavedButton.color(bar));

        SearchSavedButton.attach(bar);
        assertEquals("a bar never gets a second button", 3, bar.getChildCount());
    }

    @Test
    public void pausedTheBarStaysAsFacebookBuiltIt() {
        Settings.SEARCH_SAVED_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        LinearLayout bar = bar(RuntimeEnvironment.getApplication());
        SearchSavedButton.attach(bar);
        assertEquals(2, bar.getChildCount());
    }

    @Test
    public void aTapOpensSavedInsideTheTask() {
        Settings.SEARCH_SAVED_BUTTON.save(true);
        addSavedRoute(RuntimeEnvironment.getApplication());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout bar = bar(activity);
            SearchSavedButton.attach(bar);
            assertTrue(bar.getChildAt(2).performClick());
            Intent started = shadowOf(activity).getNextStartedActivity();
            assertNotNull("Saved didn't open", started);
            assertEquals(Uri.parse("fb://saved"), started.getData());
            assertEquals(activity.getPackageName(), started.getComponent().getPackageName());
            assertEquals("Saved opened in a new task", 0, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
            ShadowLooper.idleMainLooper();
            assertNull("a toast said it couldn't open", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void aTapWithNoSavedRouteSaysSo() {
        Settings.SEARCH_SAVED_BUTTON.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout bar = bar(activity);
            SearchSavedButton.attach(bar);
            bar.getChildAt(2).performClick();
            assertNull("something opened", shadowOf(activity).getNextStartedActivity());
            ShadowLooper.idleMainLooper();
            assertEquals("Saved isn't available in this build.", ShadowToast.getTextOfLatestToast());
        }
    }

    /** A bar already holding the button, shown again after the switch went off, hides it and opens nothing. */
    @Test
    public void aButtonShownAgainWithTheSwitchOffHides() {
        Settings.SEARCH_SAVED_BUTTON.save(true);
        addSavedRoute(RuntimeEnvironment.getApplication());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout bar = bar(activity);
            SearchSavedButton.attach(bar);
            Settings.SEARCH_SAVED_BUTTON.save(false);
            activity.setContentView(bar);
            ShadowLooper.idleMainLooper();
            View button = bar.getChildAt(2);
            assertEquals(View.GONE, button.getVisibility());
            button.performClick();
            assertNull("Saved opened with the switch off", shadowOf(activity).getNextStartedActivity());
        }
    }
}
