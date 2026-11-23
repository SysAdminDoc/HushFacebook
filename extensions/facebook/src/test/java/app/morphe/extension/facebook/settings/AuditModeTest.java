/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The audit folder is what lets scripts/device-audit drive Hushfacebook over adb, and without
 * it every audit extra is ignored.
 */
@RunWith(RobolectricTestRunner.class)
public class AuditModeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private File folder;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        folder = new File(context.getExternalFilesDir(null), AuditMode.FOLDER);
    }

    @After
    public void tearDown() {
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.resetToDefault();
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) file.delete();
        folder.delete();
    }

    @Test
    public void withoutTheFolderNothingIsReadOrWritten() throws IOException {
        File files = context.getExternalFilesDir(null);
        write(new File(files, "settings.json"), "{\"format\":\"hushfacebook-audit\",\"profile\":\"all-on\"}");
        AuditMode.onApplicationCreate(context);
        assertFalse(Settings.HIDE_REELS_TAB.get());
        assertNull(AuditMode.page(context, pageIntent("News feed")));
        new File(files, "settings.json").delete();
    }

    @Test
    public void allOnTurnsOnEverySwitchButTheExcludedOnes() throws IOException {
        folder.mkdirs();
        write(new File(folder, "settings.json"),
                "{\"format\":\"hushfacebook-audit\",\"profile\":\"all-on\",\"off\":[\"" + Settings.FORCE_DARK_MODE.key + "\"]}");
        AuditMode.onApplicationCreate(context);

        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
            boolean expected = !AuditMode.ALL_ON_EXCLUDED.contains(setting) && setting != Settings.FORCE_DARK_MODE;
            assertEquals(setting.key, expected, setting.get());
        }
        assertTrue(read("import.txt").startsWith("applied "));
        assertFalse(new File(folder, "settings.json").exists());
        assertTrue(new File(folder, "settings.done.json").exists());
        // What was there before the import, all defaults here, kept for the audit to put back.
        assertTrue(read("before.json").contains("\"" + Settings.HIDE_REELS_TAB.key + "\": false"));
    }

    @Test
    public void defaultsPutEverySwitchBackExceptTheOnesNamed() throws IOException {
        folder.mkdirs();
        Settings.HIDE_REELS_TAB.save(true);
        write(new File(folder, "settings.json"),
                "{\"format\":\"hushfacebook-audit\",\"profile\":\"defaults\",\"on\":[\"" + Settings.EVENTS_ONLY.key + "\"]}");
        AuditMode.onApplicationCreate(context);

        assertFalse(Settings.HIDE_REELS_TAB.get());
        assertTrue(Settings.EVENTS_ONLY.get());
    }

    @Test
    public void aBackupFileIsImportedAsTheScreenWould() throws Exception {
        folder.mkdirs();
        Settings.HIDE_REELS_TAB.save(true);
        String backup = SettingsBackup.create();
        Settings.HIDE_REELS_TAB.save(false);
        write(new File(folder, "settings.json"), backup);
        AuditMode.onApplicationCreate(context);
        assertTrue(Settings.HIDE_REELS_TAB.get());
    }

    @Test
    public void aDamagedFileIsRefusedAndSaidSo() throws IOException {
        folder.mkdirs();
        write(new File(folder, "settings.json"), "{\"format\":\"hushfacebook-audit\",\"profile\":\"most\"}");
        AuditMode.onApplicationCreate(context);
        assertTrue(read("import.txt"), read("import.txt").startsWith("refused: "));
    }

    @Test
    public void aPageIsTakenOnlyInItsShape() {
        folder.mkdirs();
        assertEquals("Pause, backup and diagnostics", AuditMode.page(context, pageIntent("Pause, backup and diagnostics")));
        assertEquals("Meta's other products", AuditMode.page(context, pageIntent("Meta's other products")));
        assertNull(AuditMode.page(context, pageIntent("../../etc")));
        assertNull(AuditMode.page(context, new Intent()));
    }

    @Test
    public void theReportLandsWhereAdbCanPullIt() throws IOException {
        folder.mkdirs();
        AuditMode.writeReport(folder);
        assertFalse(read("report.txt").isEmpty());
        assertFalse(new File(folder, "report.txt.part").exists());
    }

    @Test
    public void theAppliedSnapshotReportsActualChoicesAndRefusedImportsCannotRefreshIt() throws Exception {
        folder.mkdirs();
        write(new File(folder, "settings.json"), "{\"format\":\"hushfacebook-audit\",\"profile\":\"defaults\"}");
        AuditMode.onApplicationCreate(context);
        String applied = read("applied.json");
        assertEquals(SettingsBackup.create(), applied);
        write(new File(folder, "settings.json"), "{\"format\":\"hushfacebook-audit\",\"profile\":\"invalid\"}");
        AuditMode.onApplicationCreate(context);
        assertEquals(applied, read("applied.json"));
        assertTrue(read("import.txt").startsWith("refused:"));
    }

    @Test
    public void aDisplayedPageRecordsItsCanonicalRouteAndTranslatedTitleOnlyDuringAnAudit() throws Exception {
        AuditMode.pageShown(context, "News feed", "Actualités");
        assertFalse(new File(folder, "page.json").exists());
        folder.mkdirs();
        AuditMode.pageShown(context, "News feed", "Actualités");
        JSONObject recorded = new JSONObject(read("page.json"));
        assertEquals("News feed", recorded.getString("route"));
        assertEquals("Actualités", recorded.getString("title"));
        assertEquals(2, recorded.getInt("schema"));
        assertEquals(L10n.locale(context).toLanguageTag(), recorded.getString("locale"));
        assertFalse(recorded.has("expandedSections"));
        assertFalse(recorded.has("sectionRows"));
    }

    @Test
    public void anAccordionAcknowledgmentRecordsEveryExpandedSectionAndTheRequestedRows() throws Exception {
        AuditMode.pageShown(context, "News feed", "Actualités",
                Arrays.asList("News feed", "Playback", "support"), 12);
        assertFalse(new File(folder, "page.json").exists());
        folder.mkdirs();
        AuditMode.pageShown(context, "News feed", "Actualités",
                Arrays.asList("News feed", "Playback", "support"), 12);

        JSONObject recorded = new JSONObject(read("page.json"));
        assertEquals(3, recorded.getInt("schema"));
        assertEquals("News feed", recorded.getString("route"));
        assertEquals("Actualités", recorded.getString("title"));
        assertEquals(12, recorded.getInt("sectionRows"));
        assertTrue(recorded.getBoolean("targetExpanded"));
        JSONArray expanded = recorded.getJSONArray("expandedSections");
        assertEquals(3, expanded.length());
        assertEquals("News feed", expanded.getString(0));
        assertEquals("Playback", expanded.getString(1));
        assertEquals("support", expanded.getString(2));
    }

    @Test
    public void aCollapsedSectionAndTheHomeListDoNotClaimAnExpandedTarget() throws Exception {
        folder.mkdirs();
        AuditMode.pageShown(context, "News feed", "News feed", Collections.singletonList("Playback"), 0);
        JSONObject collapsed = new JSONObject(read("page.json"));
        assertEquals(3, collapsed.getInt("schema"));
        assertFalse(collapsed.getBoolean("targetExpanded"));
        assertEquals(0, collapsed.getInt("sectionRows"));

        AuditMode.pageShown(context, "", "Hushfacebook", Collections.singletonList("Playback"), 0);
        JSONObject home = new JSONObject(read("page.json"));
        assertEquals("", home.getString("route"));
        assertEquals("Hushfacebook", home.getString("title"));
        assertFalse(home.getBoolean("targetExpanded"));
        assertEquals(0, home.getInt("sectionRows"));
        assertEquals("Playback", home.getJSONArray("expandedSections").getString(0));
    }

    @Test
    public void supportUsesItsStableIdAndASectionWithoutRowsReportsZero() throws Exception {
        folder.mkdirs();
        AuditMode.pageShown(context, "support", "Support", Collections.singletonList("support"), 4);
        JSONObject support = new JSONObject(read("page.json"));
        assertEquals("support", support.getString("route"));
        assertEquals("Support", support.getString("title"));
        assertTrue(support.getBoolean("targetExpanded"));
        assertEquals(4, support.getInt("sectionRows"));

        AuditMode.pageShown(context, "News feed", "News feed", Collections.singletonList("News feed"), 0);
        JSONObject empty = new JSONObject(read("page.json"));
        assertTrue(empty.getBoolean("targetExpanded"));
        assertEquals(0, empty.getInt("sectionRows"));
    }

    @Test
    public void aPageAcknowledgmentIncludesTheWholeApplicationLocaleLookupOrder() throws Exception {
        Resources resources = context.getResources();
        Configuration previous = new Configuration(resources.getConfiguration());
        try {
            Configuration requested = new Configuration(previous);
            requested.setLocales(new LocaleList(Locale.FRANCE, Locale.GERMANY));
            resources.updateConfiguration(requested, resources.getDisplayMetrics());
            folder.mkdirs();

            // French has no table, so the application's second language supplies the title.
            String title = L10n.t(context, "News feed");
            assertEquals("Newsfeed", title);
            AuditMode.pageShown(context, "News feed", title, Collections.singletonList("News feed"), 8);

            JSONObject recorded = new JSONObject(read("page.json"));
            assertEquals(3, recorded.getInt("schema"));
            assertEquals("News feed", recorded.getString("route"));
            assertEquals("Newsfeed", recorded.getString("title"));
            assertEquals("fr-FR", recorded.getString("locale"));
            JSONArray tags = recorded.getJSONArray("localeTags");
            assertEquals(4, tags.length());
            assertEquals("fr-rfr", tags.getString(0));
            assertEquals("fr", tags.getString(1));
            assertEquals("de-rde", tags.getString(2));
            assertEquals("de", tags.getString(3));
        } finally {
            resources.updateConfiguration(previous, resources.getDisplayMetrics());
        }
        assertEquals(previous.getLocales(), resources.getConfiguration().getLocales());
    }

    private static Intent pageIntent(String page) {
        return new Intent().putExtra(AuditMode.EXTRA_PAGE, page);
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(new File(folder, name).toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
