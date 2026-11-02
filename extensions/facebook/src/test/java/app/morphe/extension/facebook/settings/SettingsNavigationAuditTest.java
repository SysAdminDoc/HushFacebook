/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.Setting;

/** Native page proof belongs to a revealed accordion section in a frame that can draw. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 37}, qualifiers = "en-rUS-w390dp-h844dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class SettingsNavigationAuditTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;
    private HushfacebookPreferenceFragment page;
    private File folder;
    private File acknowledgment;
    private boolean createdFolder;
    private boolean framesWerePaused;
    private Map<String, Object> choicesBefore;

    @Before public void open() {
        framesWerePaused = ShadowChoreographer.isPaused();
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.inBuildForTests.remove(PatchFamily.MATERIAL_YOU_THEME);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        page = SettingsL10nTest.pageOf(dialog);
        layoutOnly();
        drawFrame();
        ShadowLooper.idleMainLooper();
        // Posted work can run while the next frame remains under this test's control.
        ShadowChoreographer.setPaused(true);
        folder = new File(RuntimeEnvironment.getApplication().getExternalFilesDir(null), AuditMode.FOLDER);
        createdFolder = folder.mkdirs();
        assertTrue("the audit fixture needs its own empty folder", createdFolder);
        acknowledgment = new File(folder, "page.json");
        choicesBefore = savedValues();
    }

    @After public void close() {
        try {
            if (choicesBefore != null) assertEquals("navigation changed a saved setting", choicesBefore, savedValues());
        } finally {
            if (controller != null) controller.close();
            PatchFamily.inBuildForTests = null;
            PauseForTests.resume();
            ShadowChoreographer.setPaused(framesWerePaused);
            if (createdFolder) {
                File[] files = folder.listFiles();
                if (files != null) for (File file : files) assertTrue("could not remove " + file, file.delete());
                assertTrue("could not remove audit fixture", folder.delete());
            }
        }
    }

    @Test public void aFarCategoryWaitsForItsRevealedHeaderAndTheNextDraw() throws Exception {
        assertFalse("About must begin off screen", headerVisible("section_About"));
        page.navigation.navigate("About");
        assertNoAcknowledgment();
        ShadowLooper.idleMainLooper();
        assertNoAcknowledgment();
        layoutOnly();
        assertTrue("the target header was not revealed", headerVisible("section_About"));
        assertNoAcknowledgment();
        drawFrame();

        JSONObject recorded = readAcknowledgment("About", category("About").getPreferenceCount());
        assertEquals("About", recorded.getString("title"));
        assertEquals(1, recorded.getJSONArray("expandedSections").length());
        assertTrue("the completed acknowledgment could not be cleared", acknowledgment.delete());
        drawFrame();
        assertNoAcknowledgment();
    }

    @Test public void newerNavigationReplacesThePendingTargetAndFoldsTheEarlierSection() throws Exception {
        page.navigation.navigate("About");
        page.navigation.navigate(SettingsNavigation.SUPPORT);
        ShadowLooper.idleMainLooper();
        assertNoAcknowledgment();
        layoutOnly();
        assertTrue(headerVisible(SettingsNavigation.SUPPORT));
        drawFrame();

        assertEquals(3, supportChildren());
        JSONObject recorded = readAcknowledgment(SettingsNavigation.SUPPORT, supportChildren());
        JSONArray expanded = recorded.getJSONArray("expandedSections");
        assertEquals(1, expanded.length());
        assertTrue(contains(expanded, SettingsNavigation.SUPPORT));
    }

    @Test public void searchBeforeTheDrawDiscardsThePendingCategoryAcknowledgment() throws Exception {
        page.navigation.navigate("About");
        search().setText("Report a problem");
        ShadowLooper.idleMainLooper();
        layoutOnly();
        drawFrame();
        assertNoAcknowledgment();
        assertTrue("search collapsed the section", page.navigation.isExpanded("About"));

        page.navigation.navigate("About");
        ShadowLooper.idleMainLooper();
        assertNoAcknowledgment();
        layoutOnly();
        drawFrame();
        readAcknowledgment("About", category("About").getPreferenceCount());
    }

    @Test public void restoringSupportWithOneSearchResultDoesNotClaimTheWholeSection() {
        restoreSupportSearch("Report a problem", 1);
    }

    @Test public void restoringSupportWithNoSearchResultsDoesNotInventVisibleChildren() {
        restoreSupportSearch("noSuchSetting987654", 0);
    }

    private void restoreSupportSearch(String query, int matchingChildren) {
        page.navigation.navigate(SettingsNavigation.SUPPORT);
        search().setText(query);
        // Restore real FragmentManager state without recreate() forcing focus onto the host's
        // unattached window while the restored dialog owns the attached window.
        Bundle saved = new Bundle();
        page.navigation.save(saved);
        page.navigation.close();
        // Exercise saved navigation state on the attached window. Activity recreation is covered
        // separately; its ViewRoot cannot attach while this fixture deliberately pauses frames.
        page.navigation = new SettingsNavigation(page, dialog, saved);
        ShadowLooper.idleMainLooper();
        assertEquals(query, search().getText().toString());
        assertTrue(page.navigation.isExpanded(SettingsNavigation.SUPPORT));
        assertEquals(matchingChildren, supportChildren());
        layoutOnly();
        drawFrame();
        assertNoAcknowledgment();
    }

    private JSONObject readAcknowledgment(String route, int rows) throws Exception {
        assertTrue("the drawn target produced no audit proof", acknowledgment.isFile());
        JSONObject recorded = new JSONObject(new String(Files.readAllBytes(acknowledgment.toPath()), StandardCharsets.UTF_8));
        assertEquals(3, recorded.getInt("schema"));
        assertEquals(route, recorded.getString("route"));
        assertTrue(recorded.getBoolean("targetExpanded"));
        assertTrue(contains(recorded.getJSONArray("expandedSections"), route));
        assertTrue("the fixture's section must have content", rows > 0);
        assertEquals(rows, recorded.getInt("sectionRows"));
        assertTrue(recorded.getJSONArray("localeTags").length() > 0);
        return recorded;
    }

    private void assertNoAcknowledgment() {
        assertFalse("an undrawn or filtered target was acknowledged", acknowledgment.exists());
    }

    private void layoutOnly() {
        View root = dialog.getView();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, 1688);
    }

    private void drawFrame() {
        assertTrue("the fixture must use an attached list", list().isAttachedToWindow());
        assertTrue("the fixture must complete layout before drawing", list().isLaidOut());
        assertFalse("another listener canceled the frame", list().getViewTreeObserver().dispatchOnPreDraw());
        Bitmap frame = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888);
        try {
            dialog.getView().draw(new Canvas(frame));
        } finally {
            frame.recycle();
        }
    }

    private boolean headerVisible(String key) {
        for (int child = 0; child < list().getChildCount(); child++) {
            Preference item = (Preference) list().getItemAtPosition(list().getFirstVisiblePosition() + child);
            View row = list().getChildAt(child);
            if (key.equals(item.getKey())) return row.isShown() && row.getBottom() > 0 && row.getTop() < list().getHeight();
        }
        return false;
    }

    private PreferenceCategory category(String title) {
        for (Preference category : page.sections()) if (title.contentEquals(category.getTitle())) return (PreferenceCategory) category;
        throw new AssertionError("missing category " + title);
    }

    private int supportChildren() {
        int count = 0;
        for (int row = 0; row < list().getCount(); row++) {
            String key = ((Preference) list().getItemAtPosition(row)).getKey();
            if (SettingsNavigation.REPORT.equals(key) || SettingsNavigation.RESTART_NOW.equals(key)
                    || HushfacebookPages.SUPPORT.equals(key)) count++;
        }
        return count;
    }

    private static boolean contains(JSONArray values, String wanted) throws Exception {
        for (int i = 0; i < values.length(); i++) if (wanted.equals(values.getString(i))) return true;
        return false;
    }

    private ListView list() { return dialog.getView().findViewById(android.R.id.list); }

    private EditText search() {
        EditText found = findSearch(dialog.getView());
        assertNotNull("the dialog's search box is missing", found);
        return found;
    }

    private static EditText findSearch(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findSearch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Map<String, Object> savedValues() {
        Map<String, Object> values = new TreeMap<>();
        for (Setting<?> setting : Setting.allLoadedSettings()) values.put(setting.key, setting.savedValue());
        return values;
    }
}
