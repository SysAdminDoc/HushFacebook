/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Fragment;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.BackCallbacksForTests;
import app.morphe.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings dialog as Facebook hosts it: a dialog fragment over an activity that isn't ours,
 * with the preference page in the dialog's child manager.
 *
 * <p>The recovery page's Retry used to commit through the activity's manager, which can't see the
 * dialog's container; its Back finished the activity when the activity's back stack was empty,
 * which inside Facebook is Facebook itself; and a new container id on every view creation left a
 * page restored after rotation with no container to go back into.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsDialogTest {
    private static final String TAG = "hushfacebook_settings";
    private static final String ERROR = "morphe_settings_error_message";
    private static final String RETRY = "morphe_settings_error_retry";
    private static final String BACK = "morphe_settings_error_back";

    private static final java.util.function.Predicate<View> KEYBOARD_UP = SettingsDialog.keyboardUp;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();

    @After
    public void tearDown() {
        HushfacebookPreferenceFragment.failNextInitialization = null;
        SettingsDialog.keyboardUp = KEYBOARD_UP;
        controller.close();
    }

    /** On a dark phone, whose page is the black one; a light phone gets the light page (#559). */
    @Test
    @Config(qualifiers = "night")
    public void aFailedInitializationShowsATitledReadableRecoveryPage() {
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        HushfacebookPreferenceFragment page = pageOf(show(controller.get()));

        Preference message = page.findPreference(ERROR);
        assertNotNull("no recovery page", message);
        assertEquals(String.valueOf(page.initializationErrorTitle(controller.get())), String.valueOf(message.getTitle()));
        assertTrue(String.valueOf(message.getTitle()), String.valueOf(message.getTitle()).contains("couldn't open"));
        for (String key : new String[]{ERROR, RETRY, BACK}) {
            Preference row = page.findPreference(key);
            assertNotNull(key, row);
            assertTrue(key + " has no title", row.getTitle() != null && row.getTitle().length() > 0);
            assertLightText(row);
        }
    }

    /** The bar's title, which every page and search show their name in, wears Facebook's gradient. */
    @Test
    public void theBarTitleWearsTheGradient() {
        SettingsDialog dialog = show(controller.get());
        List<android.widget.TextView> headings = new ArrayList<>();
        headings(dialog.getDialog().getWindow().getDecorView(), headings);
        assertFalse("the bar has no title", headings.isEmpty());
        assertEquals("Hushfacebook", String.valueOf(headings.get(0).getText()));
        assertTrue(ShimmerTitle.wears(headings.get(0)));
    }

    private static void headings(View view, List<android.widget.TextView> into) {
        if (view instanceof android.widget.TextView && view.isAccessibilityHeading()) into.add((android.widget.TextView) view);
        if (!(view instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) headings(group.getChildAt(i), into);
    }

    @Test
    public void retryRebuildsThePageInsideTheDialog() {
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        SettingsDialog dialog = show(controller.get());
        HushfacebookPreferenceFragment failed = pageOf(dialog);

        click(failed.findPreference(RETRY));
        dialog.getChildFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        HushfacebookPreferenceFragment rebuilt = pageOf(dialog);
        assertNotSame("Retry left the failed page in place", failed, rebuilt);
        assertNull("the rebuilt page is still the recovery page", rebuilt.findPreference(ERROR));
        assertTrue("the rebuilt page is empty", rebuilt.getPreferenceScreen().getPreferenceCount() > 0);
        assertEquals(1, pages(dialog).size());
        assertFalse(controller.get().isFinishing());
    }

    @Test
    public void theRecoveryPagesBackClosesOnlyTheSettings() {
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        Activity activity = controller.get();
        click(pageOf(show(activity)).findPreference(BACK));
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        assertNull("the settings stayed open", activity.getFragmentManager().findFragmentByTag(TAG));
        assertFalse("Back finished the host activity", activity.isFinishing());
    }

    @Test
    public void theBackKeyClosesOnlyTheSettings() {
        Activity activity = controller.get();
        show(activity).getDialog().onBackPressed();
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        assertNull("the settings stayed open", activity.getFragmentManager().findFragmentByTag(TAG));
        assertFalse("Back finished the host activity", activity.isFinishing());
    }

    /**
     * Facebook 585 targets Android 16, so there Back reaches the settings as the dialog window's
     * own callback, never as a key. It still closes only the settings.
     */
    @Test
    @Config(sdk = 36)
    public void backThroughTheWindowsCallbackClosesOnlyTheSettings() throws ReflectiveOperationException {
        BackCallbacksForTests.on(true);
        Activity activity = controller.get();
        SettingsDialog dialog = show(activity);
        BackCallbacksForTests.top(dialog.getDialog().getWindow().getDecorView()).onBackInvoked();
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        assertNull("the settings stayed open", activity.getFragmentManager().findFragmentByTag(TAG));
        assertFalse("Back finished the host activity", activity.isFinishing());
    }

    /**
     * Recreation hands back the page the framework saved, with its state, rather than a fresh one
     * built over it. A marker in the saved page's arguments is how the test tells the two apart: a
     * dialog that built a new page on every view would pass the count and container checks alone,
     * and the list would jump back to the top on every rotation.
     */
    @Test
    public void recreationKeepsOnePageInItsContainer() {
        android.os.Bundle marker = new android.os.Bundle();
        marker.putString("marker", "the page the framework saved");
        pageOf(show(controller.get())).setArguments(marker);

        controller.recreate();
        ShadowLooper.idleMainLooper();

        SettingsDialog restored = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag(TAG);
        assertNotNull("the settings didn't come back after recreation", restored);
        assertEquals("pages after recreation", 1, pages(restored).size());
        HushfacebookPreferenceFragment page = pageOf(restored);
        assertNotNull("recreation replaced the saved page with a new one", page.getArguments());
        assertEquals("the page the framework saved", page.getArguments().getString("marker"));
        View view = page.getView();
        assertNotNull("the restored page has no view", view);
        assertEquals(SettingsDialog.CONTAINER_ID, ((View) view.getParent()).getId());
        assertNull(page.findPreference(ERROR));
        assertTrue(page.getPreferenceScreen().getPreferenceCount() > 0);
    }

    /**
     * Android 17 leaves the keyboard down after a configuration change the app doesn't handle, so a
     * screen recreated while its search box was being typed in puts the box back in focus with what
     * was typed (and asks for the keyboard), and one recreated with the keyboard down leaves both.
     */
    @Test
    public void recreationWhileTypingPutsTheSearchBoxBackInFocus() {
        SettingsDialog.keyboardUp = view -> true;
        android.widget.EditText box = searchOf(show(controller.get()));
        box.requestFocus();
        box.setText("speed");
        ShadowLooper.idleMainLooper();

        controller.recreate();
        ShadowLooper.idleMainLooper();

        android.widget.EditText restored = searchOf(restored());
        assertEquals("speed", restored.getText().toString());
        assertTrue("the search box lost its focus to the recreation", restored.hasFocus());
    }

    @Test
    public void recreationWithTheKeyboardDownLeavesTheSearchBoxAlone() {
        SettingsDialog.keyboardUp = view -> false;
        android.widget.EditText box = searchOf(show(controller.get()));
        box.requestFocus();
        box.setText("speed");
        ShadowLooper.idleMainLooper();

        controller.recreate();
        ShadowLooper.idleMainLooper();

        assertFalse("a box nobody was typing in took focus", searchOf(restored()).hasFocus());
    }

    private SettingsDialog restored() {
        SettingsDialog restored = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag(TAG);
        assertNotNull("the settings didn't come back after recreation", restored);
        return restored;
    }

    private static android.widget.EditText searchOf(SettingsDialog dialog) {
        android.widget.EditText box = firstEditor(dialog.getView());
        assertNotNull("no search box on the screen", box);
        return box;
    }

    /** The first text editor under the view, or null. */
    private static android.widget.EditText firstEditor(View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.EditText found = firstEditor(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static SettingsDialog show(Activity activity) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(activity.getFragmentManager(), TAG);
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    private static HushfacebookPreferenceFragment pageOf(SettingsDialog dialog) {
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page in the dialog: " + page, page instanceof HushfacebookPreferenceFragment);
        return (HushfacebookPreferenceFragment) page;
    }

    private static List<Fragment> pages(SettingsDialog dialog) {
        List<Fragment> pages = new ArrayList<>();
        for (Fragment fragment : dialog.getChildFragmentManager().getFragments()) {
            if (fragment instanceof HushfacebookPreferenceFragment) pages.add(fragment);
        }
        return pages;
    }

    private static void click(Preference row) {
        assertNotNull("no such row", row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    private static void assertLightText(Preference row) {
        TypedArray styled = row.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            ColorStateList primary = styled.getColorStateList(0);
            assertNotNull(primary);
            assertTrue("\"" + row.getTitle() + "\" is drawn dark on the black page",
                    Color.luminance(primary.getDefaultColor()) > 0.5f);
        } finally {
            styled.recycle();
        }
    }
}
