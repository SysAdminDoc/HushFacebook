/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceGroup;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import app.morphe.extension.facebook.font.FontFile;
import app.morphe.extension.facebook.font.FontFileTest;
import app.morphe.extension.facebook.font.OwnFont;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.WorkerPoolForTests;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Font file and Use your phone's font under the Use the system font switch: the picker a tap opens,
 * the copy a picked font becomes, what's said when a file is turned down, the way back, and an app
 * that stalls while naming, opening or reading the file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class FontFilePreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The app behind every file the tests pick. */
    private static final String AUTHORITY = "font-test";
    private static final String KHMER = "content://font-test/documents/NotoSansKhmer-VF.ttf";
    private static final String TIMEOUT = "The app holding that font file is taking too long, so Hushfacebook stopped "
            + "waiting. Your font didn't change.";
    private static final String STALLED = "The app holding the last font file still hasn't answered. Try again later.";
    private static final String CLEARED = "Font file cleared. Restart Facebook to apply the font change.";

    /** The read a test's app is holding, if any. */
    private volatile StallingStream stalledRead;

    /** The read a test's app hands over late, if any. */
    private volatile GatedStream gatedRead;

    @Before
    public void fontPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SYSTEM_FONT);
        ShadowToast.reset();
        FontFileProvider.install(AUTHORITY);
    }

    @After
    public void restore() throws Exception {
        // A test that stopped while the app held a query, an open or a read would leave that copy
        // holding a worker and the rows for the tests after it. Letting them go ends the copy.
        FontFileProvider.releaseAll();
        StallingStream read = stalledRead;
        if (read != null) read.close();
        GatedStream late = gatedRead;
        if (late != null) late.letGo();
        FontFilePreference.streamForTests = null;
        settle();
        PatchFamily.inBuildForTests = null;
        Settings.FONT_SOURCE.resetToDefault();
        Settings.USE_SYSTEM_FONT.resetToDefault();
        OwnFont.fileChanged();
        File copy = copy();
        if (copy.exists()) assertTrue(copy.delete());
    }

    private static File copy() {
        return FontFile.file(RuntimeEnvironment.getApplication());
    }

    /** With nothing picked, the switch and Font file are there, and there's no way back to show. */
    @Test
    public void theAppearanceSectionHasTheSwitchAndTheFileRow() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            Preference toggle = page.findPreference(Settings.USE_SYSTEM_FONT.key);
            assertEquals("Use the system font", String.valueOf(toggle.getTitle()));
            Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
            assertEquals("Font file", String.valueOf(choose.getTitle()));
            assertEquals("No file chosen. Choose a .ttf or .otf font file of up to 20 MB.",
                    String.valueOf(choose.getSummary()));
            assertTrue(choose.isEnabled());
            assertNull("a way back to the phone's font with nothing picked",
                    page.findPreference(FontFilePreference.PHONE_FONT_KEY));
            assertEquals("", Settings.FONT_SOURCE.savedValue());
        }
    }

    /** With a file picked, the way back sits after Font file, in the same section. */
    @Test
    public void aPickedFileBringsTheWayBack() {
        Settings.FONT_SOURCE.save("Inter.ttf");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
            Preference phone = page.findPreference(FontFilePreference.PHONE_FONT_KEY);
            assertNotNull(phone);
            assertEquals("Use your phone's font", String.valueOf(phone.getTitle()));
            assertEquals("Removes the saved font file. Use the system font stays off.", String.valueOf(phone.getSummary()));
            assertTrue(phone.isEnabled());
            assertEquals(choose.getParent(), phone.getParent());
            assertTrue("the way back comes before Font file", phone.getOrder() > choose.getOrder());
        }
    }

    /**
     * A file picked with the page open brings the way back right after Font file, ahead of the
     * emoji switch that shares the section, not at the section's end. Text size leads the section in
     * every build.
     */
    @Test
    public void aPickOnAnOpenPageBringsTheWayBackRightAfterFontFile() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SYSTEM_FONT, PatchFamily.SYSTEM_EMOJI);
        byte[] rubik = FontFileTest.font(FontFileTest.STATIC_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            assertNull(page.findPreference(FontFilePreference.PHONE_FONT_KEY));
            deliver(activity, tap(activity, page), "content://font-test/documents/Rubik-Regular.ttf", rubik);

            PreferenceGroup section = page.findPreference(FontFilePreference.CHOOSE_KEY).getParent();
            List<String> keys = new ArrayList<>();
            // The section opens with its Text and emoji heading, which has no key, and those rows run
            // to the next heading. What's under that one depends on the build.
            assertTrue(section.getPreference(0) instanceof SettingsRows.Subheading);
            assertEquals("Text and emoji", String.valueOf(section.getPreference(0).getTitle()));
            for (int i = 1; i < section.getPreferenceCount() && !(section.getPreference(i) instanceof SettingsRows.Subheading); i++) {
                keys.add(section.getPreference(i).getKey());
            }
            assertEquals(Arrays.asList(Settings.TEXT_SIZE.key, Settings.USE_SYSTEM_FONT.key, FontFilePreference.CHOOSE_KEY,
                    FontFilePreference.PHONE_FONT_KEY, Settings.USE_SYSTEM_EMOJI.key), keys);
        }
    }

    @Test
    public void fontFileOpensThePickerForFonts() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
            assertEquals(FontFilePreference.PICK_FONT, started.requestCode);
            assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("*/*", started.intent.getType());
            List<String> types = Arrays.asList(started.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            for (String type : new String[]{"font/ttf", "font/otf", "font/collection", "application/octet-stream"}) {
                assertTrue(type + " isn't offered: " + types, types.contains(type));
            }
            assertTrue("the backup rows' codes are their own",
                    FontFilePreference.PICK_FONT != SettingsBackupPreference.IMPORT
                            && FontFilePreference.PICK_FONT != SettingsBackupPreference.EXPORT);
        }
    }

    /** A picked font is copied in, named after the file, and the rows say so. */
    @Test
    public void aPickedFontIsCopiedInAndNamed() throws Exception {
        byte[] rubik = FontFileTest.font(FontFileTest.STATIC_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://font-test/documents/Rubik-Regular.ttf", rubik);

            assertEquals("Rubik-Regular.ttf", Settings.FONT_SOURCE.savedValue());
            assertArrayEquals(rubik, Files.readAllBytes(copy().toPath()));
            assertFalse("picking a font enabled the override", Settings.USE_SYSTEM_FONT.savedValue());
            assertEquals("Selected " + L10n.isolate("Rubik-Regular.ttf")
                            + ". Turn on Use the system font and restart Facebook to use it.",
                    ShadowToast.getTextOfLatestToast());
            Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
            assertEquals("Selected " + L10n.isolate("Rubik-Regular.ttf")
                            + ". Use the system font is off. Turn it on and restart Facebook to use this file.",
                    String.valueOf(choose.getSummary()));
            assertTrue(choose.isEnabled());
            assertTrue(page.findPreference(FontFilePreference.PHONE_FONT_KEY).isEnabled());
            assertTrue(AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
        }
    }

    /** A saved file is a selection, and binding reads the current override switch. */
    @Test
    public void aSelectedFilesFeedbackFollowsTheOverrideSwitch() throws Exception {
        Settings.USE_SYSTEM_FONT.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://font-test/Rubik-Regular.ttf",
                    FontFileTest.font(FontFileTest.STATIC_FONT));
            Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
            assertEquals("Selected " + L10n.isolate("Rubik-Regular.ttf")
                            + ". Restart Facebook to apply the font change.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("Selected " + L10n.isolate("Rubik-Regular.ttf")
                            + ". Font changes apply after restarting Facebook.", String.valueOf(choose.getSummary()));
            assertEquals("Removes the saved font file. Restart Facebook to use your phone's font.",
                    String.valueOf(page.findPreference(FontFilePreference.PHONE_FONT_KEY).getSummary()));

            Settings.USE_SYSTEM_FONT.save(false);
            ShadowLooper.idleMainLooper();
            choose.getView(null, null);
            assertEquals("Selected " + L10n.isolate("Rubik-Regular.ttf")
                            + ". Use the system font is off. Turn it on and restart Facebook to use this file.",
                    String.valueOf(choose.getSummary()));
            assertFalse(Settings.USE_SYSTEM_FONT.savedValue());
        }
    }

    /** The file's bytes can change while its name stays the same, and the notice survives reopening. */
    @Test
    @Config(shadows = NonMappingTypefaceBuilder.class)
    public void replacingTheSameNamedFileStillLeavesARestartReminder() throws Exception {
        byte[] first = FontFileTest.font(FontFileTest.STATIC_FONT);
        byte[] replacement = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            SettingsDialog dialog = SettingsL10nTest.show(activity);
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(dialog);
            deliver(activity, tap(activity, page), "content://font-test/first/font.ttf", first);
            AbstractPreferenceFragment.restartPending.clear();
            deliver(activity, tap(activity, page), "content://font-test/second/font.ttf", replacement);
            assertEquals("The replacement was refused after the picker result reached its worker",
                    "Selected " + L10n.isolate("font.ttf")
                            + ". Turn on Use the system font and restart Facebook to use it.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("font.ttf", Settings.FONT_SOURCE.savedValue());
            assertArrayEquals(replacement, Files.readAllBytes(copy().toPath()));
            assertTrue(AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
            page.navigation.navigate("Appearance");
            ShadowLooper.idleMainLooper();
            android.widget.ListView list = dialog.getView().findViewById(android.R.id.list);
            assertEquals("Restart Facebook to apply this change",
                    String.valueOf(((Preference) list.getItemAtPosition(0)).getTitle()));

            dialog.dismissAllowingStateLoss();
            ShadowLooper.idleMainLooper();
            dialog = SettingsL10nTest.show(activity);
            page = SettingsL10nTest.pageOf(dialog);
            page.navigation.navigate("Appearance");
            ShadowLooper.idleMainLooper();
            list = dialog.getView().findViewById(android.R.id.list);
            assertEquals("Restart Facebook to apply this change",
                    String.valueOf(((Preference) list.getItemAtPosition(0)).getTitle()));
        }
    }

    /** Windows cannot replace the first copy while the real builder's mmap survives until GC. */
    @Implements(Typeface.Builder.class)
    public static class NonMappingTypefaceBuilder {
        @Implementation
        protected void __constructor__(File file) {
            // This UI test covers copying and its reminder. OwnFontFileTest covers native loading.
        }

        @Implementation
        protected Typeface build() {
            return Typeface.DEFAULT;
        }
    }

    /** The provider's own name for the file wins over its address. */
    @Test
    public void theProvidersNameForTheFileIsUsed() throws Exception {
        byte[] rubik = FontFileTest.font(FontFileTest.STATIC_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://font-test/document/msf%3A1234", rubik,
                    "Inter\u0000 Variable.ttf");
            assertEquals("Inter Variable.ttf", Settings.FONT_SOURCE.savedValue());
        }
    }

    /** A photo picked by mistake changes nothing and says why; so does a file too large. */
    @Test
    public void aFileThatIsntAFontChangesNothing() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            byte[] photo = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            deliver(activity, tap(activity, page), "content://font-test/photo.png", photo);
            assertEquals("That isn't a .ttf or .otf font file. Your font didn't change.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("", Settings.FONT_SOURCE.savedValue());
            assertFalse(copy().exists());
            assertFalse(AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
            assertTrue(page.findPreference(FontFilePreference.CHOOSE_KEY).isEnabled());
            assertNull(page.findPreference(FontFilePreference.PHONE_FONT_KEY));
        }
        assertEquals("That font file is over 20 MB. Your font didn't change.",
                FontFilePreference.refusal(FontFile.Refusal.TOO_LARGE));
        assertEquals("Android couldn't use that font file. Your font didn't change.",
                FontFilePreference.refusal(FontFile.Refusal.WONT_LOAD));
        assertEquals("Couldn't open that file. Your font didn't change.",
                FontFilePreference.refusal(FontFile.Refusal.UNREADABLE));
        assertEquals("Couldn't save a copy of that font. Check that the phone has room, then try again.",
                FontFilePreference.refusal(FontFile.Refusal.NOT_SAVED));
    }

    /** Use your phone's font takes the copy away, and there's nothing for it to do after. */
    @Test
    public void useYourPhonesFontTakesTheCopyAway() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://font-test/Rubik-Regular.ttf",
                    FontFileTest.font(FontFileTest.STATIC_FONT));
            assertTrue(copy().exists());
            AbstractPreferenceFragment.restartPending.clear();

            Preference phone = page.findPreference(FontFilePreference.PHONE_FONT_KEY);
            assertTrue(((FontFilePreference) phone).actsOnTap());
            phone.getOnPreferenceClickListener().onPreferenceClick(phone);
            settle();
            assertNull("going back to the phone's font opened something", shadowOf(activity).getNextStartedActivityForResult());
            assertEquals("", Settings.FONT_SOURCE.savedValue());
            assertFalse(copy().exists());
            assertEquals("Font file cleared. Restart Facebook to apply the font change.", ShadowToast.getTextOfLatestToast());
            assertTrue(AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
            assertFalse("clearing a file enabled the override", Settings.USE_SYSTEM_FONT.savedValue());
            assertNull("the way back stayed with nothing to go back from",
                    page.findPreference(FontFilePreference.PHONE_FONT_KEY));
            assertEquals(FontFilePreference.chosenSummary("", false),
                    String.valueOf(page.findPreference(FontFilePreference.CHOOSE_KEY).getSummary()));
        }
    }

    /**
     * A pick whose name can't be saved changes nothing: the copy, the name, the row and the message
     * all stay with the font picked before. For that pick the preferences file's folder is swapped
     * for a plain file, so Android can't write the name.
     */
    @Test
    public void aPickWhoseNameWontSaveChangesNothing() throws Exception {
        byte[] rubik = FontFileTest.font(FontFileTest.STATIC_FONT);
        byte[] khmer = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://font-test/documents/Rubik-Regular.ttf", rubik);
            assertEquals("Rubik-Regular.ttf", Settings.FONT_SOURCE.savedValue());
            AbstractPreferenceFragment.restartPending.clear();

            File stored = ReflectionHelpers.getField(Setting.preferences.preferences, "mFile");
            File folder = stored.getParentFile();
            File aside = new File(folder.getParentFile(), folder.getName() + ".aside");
            assertTrue(folder.renameTo(aside));
            assertTrue(folder.createNewFile());
            try {
                deliver(activity, tap(activity, page), "content://font-test/documents/NotoSansKhmer-VF.ttf", khmer);
            } finally {
                assertTrue(folder.delete());
                assertTrue(aside.renameTo(folder));
            }

            assertEquals("Couldn't save a copy of that font. Check that the phone has room, then try again.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("Rubik-Regular.ttf", Settings.FONT_SOURCE.savedValue());
            assertArrayEquals("the copy moved on without its name", rubik, Files.readAllBytes(copy().toPath()));
            assertEquals("Selected " + L10n.isolate("Rubik-Regular.ttf")
                            + ". Use the system font is off. Turn it on and restart Facebook to use this file.",
                    String.valueOf(page.findPreference(FontFilePreference.CHOOSE_KEY).getSummary()));
            assertFalse(AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
            assertTrue(page.findPreference(FontFilePreference.PHONE_FONT_KEY).isEnabled());
        }
    }

    /** A copy no name points at, one a removal couldn't delete, can still be taken away. */
    @Test
    public void aCopyNoNamePointsAtCanStillBeTakenAway() throws Exception {
        File copy = copy();
        Files.write(copy.toPath(), FontFileTest.font(FontFileTest.STATIC_FONT));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            assertEquals(FontFilePreference.chosenSummary("", true),
                    String.valueOf(page.findPreference(FontFilePreference.CHOOSE_KEY).getSummary()));
            Preference phone = page.findPreference(FontFilePreference.PHONE_FONT_KEY);
            assertNotNull("no way to take away a copy no name points at", phone);
            assertTrue(phone.isEnabled());

            phone.getOnPreferenceClickListener().onPreferenceClick(phone);
            settle();
            assertFalse(copy.exists());
            assertEquals("", Settings.FONT_SOURCE.savedValue());
            assertEquals("Font file cleared. Restart Facebook to apply the font change.", ShadowToast.getTextOfLatestToast());
            assertTrue(AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
            assertNull(page.findPreference(FontFilePreference.PHONE_FONT_KEY));
        }
    }

    /** A copy that went missing is said, and the phone's font is what's drawn. */
    @Test
    public void aCopyThatWentMissingIsSaid() throws Exception {
        Settings.FONT_SOURCE.save("Inter.ttf");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            assertEquals("The saved copy of " + L10n.isolate("Inter.ttf")
                            + " is missing. Choose the file again.",
                    String.valueOf(page.findPreference(FontFilePreference.CHOOSE_KEY).getSummary()));
            assertTrue("the way back is out of reach", page.findPreference(FontFilePreference.PHONE_FONT_KEY).isEnabled());
        }
    }

    @Test
    public void aCancelledPickerChangesNothing() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page);
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null);
            settle();
            assertNull(ShadowToast.getTextOfLatestToast());
            assertEquals("", Settings.FONT_SOURCE.savedValue());
            assertTrue(page.findPreference(FontFilePreference.CHOOSE_KEY).isEnabled());
        }
    }

    /** A full worker queue copies nothing, says so, and leaves the rows usable for another try. */
    @Test
    public void aFullWorkerQueueLeavesTheRowsUsable() throws Exception {
        byte[] rubik = FontFileTest.font(FontFileTest.STATIC_FONT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Uri uri = FontFileProvider.put("content://font-test/queued.ttf", rubik, null);
            ShadowActivity.IntentForResult started = tap(activity, page);
            try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
                shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
                ShadowLooper.idleMainLooper();
                assertEquals("Couldn't start that. Try again in a moment.", ShadowToast.getTextOfLatestToast());
                assertTrue(page.findPreference(FontFilePreference.CHOOSE_KEY).isEnabled());
                assertEquals(FontFilePreference.chosenSummary("", false),
                        String.valueOf(page.findPreference(FontFilePreference.CHOOSE_KEY).getSummary()));
            }
            settle();
            assertFalse(copy().exists());
            deliver(activity, tap(activity, page), "content://font-test/again/Rubik-Regular.ttf", rubik);
            assertEquals("Rubik-Regular.ttf", Settings.FONT_SOURCE.savedValue());
        }
    }

    // ---- The app holding the file ------------------------------------------------------------

    /**
     * An app that never names the file: after the wait the rows come back with the font picked
     * before, the cancel reaches the app and the message says only that Hushfacebook stopped
     * waiting. While the app still holds a worker no other copy starts. Its late answer opens
     * nothing, names nothing and says nothing, and then the next copy goes through.
     */
    @Test
    @Config(shadows = NonMappingTypefaceBuilder.class)
    public void aStalledNameGivesTheRowsBackAndItsLateAnswerChangesNothing() throws Exception {
        byte[] rubik = pickedBefore();
        byte[] khmer = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        CountDownLatch release = new CountDownLatch(1);
        FontFileProvider.stallQuery = release;
        FontFileProvider.ignoresCancel = true;
        Uri uri = FontFileProvider.put(KHMER, khmer, "NotoSansKhmer-VF.ttf");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
            try {
                pickHeld(activity, page, uri);
                assertFalse(choose.isEnabled());
                assertEquals("Copying the font file", String.valueOf(choose.getSummary()));
                waitOut();
                assertEquals(TIMEOUT, ShadowToast.getTextOfLatestToast());
                assertBeforeKept(page, rubik);
                assertEquals("the cancel never reached the app", 1, FontFileProvider.cancels.get());

                ShadowToast.reset();
                choose.getOnPreferenceClickListener().onPreferenceClick(choose);
                assertNull("a picker opened while a stuck app held a worker",
                        shadowOf(activity).getNextStartedActivityForResult());
                assertEquals(STALLED, ShadowToast.getTextOfLatestToast());
            } finally {
                release.countDown();
            }
            settle();
            assertEquals("the late answer said something", STALLED, ShadowToast.getTextOfLatestToast());
            assertEquals("the late answer opened the file", 0, FontFileProvider.opens(uri));
            assertBeforeKept(page, rubik);

            FontFileProvider.stallQuery = null;
            deliver(activity, tap(activity, page), KHMER, khmer);
            assertEquals("NotoSansKhmer-VF.ttf", Settings.FONT_SOURCE.savedValue());
            assertArrayEquals(khmer, Files.readAllBytes(copy().toPath()));
        }
    }

    /**
     * An app that holds the open but honours the cancel ends with the wait: nothing was read, the
     * font picked before stays, nothing more is said, and the next copy starts once it has ended.
     */
    @Test
    @Config(shadows = NonMappingTypefaceBuilder.class)
    public void aStalledOpenIsCancelledAndTheNextCopyGoesThrough() throws Exception {
        byte[] rubik = pickedBefore();
        byte[] khmer = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        FontFileProvider.stallOpen = new CountDownLatch(1);
        Uri uri = FontFileProvider.put(KHMER, khmer, null);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            pickHeld(activity, page, uri);
            waitOut();
            assertEquals(TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertEquals(1, FontFileProvider.cancels.get());
            settle();
            assertEquals("the cancelled copy said more", TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertEquals(0, FontFileProvider.opens(uri));
            assertBeforeKept(page, rubik);

            FontFileProvider.stallOpen = null;
            deliver(activity, tap(activity, page), KHMER, khmer);
            assertEquals("NotoSansKhmer-VF.ttf", Settings.FONT_SOURCE.savedValue());
            assertArrayEquals(khmer, Files.readAllBytes(copy().toPath()));
        }
    }

    /**
     * An app that holds the open past the wait and ignores the cancel keeps the next copy waiting,
     * but not Use your phone's font, which reads no file. The open it hands over late is closed
     * unread, so the font it was asked for doesn't come back in after the phone's font.
     */
    @Test
    @Config(shadows = NonMappingTypefaceBuilder.class)
    public void anOpenHeldPastTheWaitIsClosedUnreadAndThePhonesFontStillWorks() throws Exception {
        byte[] rubik = pickedBefore();
        byte[] khmer = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        CountDownLatch release = new CountDownLatch(1);
        FontFileProvider.stallOpen = release;
        FontFileProvider.ignoresCancel = true;
        Uri uri = FontFileProvider.put(KHMER, khmer, null);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
            try {
                pickHeld(activity, page, uri);
                waitOut();
                assertEquals(TIMEOUT, ShadowToast.getTextOfLatestToast());
                assertBeforeKept(page, rubik);

                Preference phone = page.findPreference(FontFilePreference.PHONE_FONT_KEY);
                assertTrue("the way back is out of reach while the app holds the open", phone.isEnabled());
                phone.getOnPreferenceClickListener().onPreferenceClick(phone);
                idleUntil(() -> CLEARED.equals(ShadowToast.getTextOfLatestToast()));
                assertEquals("", Settings.FONT_SOURCE.savedValue());
                assertFalse(copy().exists());

                ShadowToast.reset();
                choose.getOnPreferenceClickListener().onPreferenceClick(choose);
                assertNull("a picker opened while a stuck app held a worker",
                        shadowOf(activity).getNextStartedActivityForResult());
                assertEquals(STALLED, ShadowToast.getTextOfLatestToast());
            } finally {
                release.countDown();
            }
            settle();
            assertEquals("the app never handed the open over", 1, FontFileProvider.opens(uri));
            assertEquals("the late open was called a success", STALLED, ShadowToast.getTextOfLatestToast());
            assertEquals("", Settings.FONT_SOURCE.savedValue());
            assertFalse("the late open moved its font in", copy().exists());
            assertFalse("a part copied was left", partial().exists());
            assertTrue(choose.isEnabled());
            assertEquals(FontFilePreference.chosenSummary("", false), String.valueOf(choose.getSummary()));
        }
    }

    /**
     * An app that stalls partway through the file: after the wait the screen closes the file,
     * which lets the read go. The part copied so far is removed, the font picked before stays, and
     * the next copy goes through.
     */
    @Test
    @Config(shadows = NonMappingTypefaceBuilder.class)
    public void aReadThatStallsIsClosedAndThePartCopiedIsRemoved() throws Exception {
        byte[] rubik = pickedBefore();
        byte[] khmer = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        FontFilePreference.streamForTests = stream -> stalledRead = new StallingStream(stream, 1024);
        Uri uri = FontFileProvider.put(KHMER, khmer, null);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            pickHeld(activity, page, uri);
            assertTrue("the copy hadn't begun when the read stalled", partial().exists());
            waitOut();
            assertEquals(TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertTrue(page.findPreference(FontFilePreference.CHOOSE_KEY).isEnabled());
            settle();
            assertTrue("the screen never closed the file", stalledRead.wasClosed());
            assertEquals("the read that failed said more", TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertFalse("the part copied was left", partial().exists());
            assertBeforeKept(page, rubik);

            FontFilePreference.streamForTests = null;
            deliver(activity, tap(activity, page), KHMER, khmer);
            assertEquals("NotoSansKhmer-VF.ttf", Settings.FONT_SOURCE.savedValue());
            assertArrayEquals(khmer, Files.readAllBytes(copy().toPath()));
        }
    }

    /**
     * An app that hands the whole file over after the screen stopped waiting, and took no notice of
     * the screen closing it: the copy reads it all and passes its checks, but the run was given up,
     * so it can't name the font or move in. What's said stays the wait's message.
     */
    @Test
    @Config(shadows = NonMappingTypefaceBuilder.class)
    public void aCopyThatFinishesAfterTheWaitCantNameTheFont() throws Exception {
        byte[] rubik = pickedBefore();
        byte[] khmer = FontFileTest.font(FontFileTest.VARIABLE_FONT);
        FontFilePreference.streamForTests = stream -> gatedRead = new GatedStream(stream);
        Uri uri = FontFileProvider.put(KHMER, khmer, null);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            pickHeld(activity, page, uri);
            waitOut();
            assertEquals(TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertBeforeKept(page, rubik);

            assertTrue("the screen never closed the file it stopped waiting on", gatedRead.closeAsked());
            gatedRead.letGo();
            settle();
            assertEquals("the late copy didn't read the whole file", khmer.length, gatedRead.bytesRead());
            assertEquals("the late copy said more", TIMEOUT, ShadowToast.getTextOfLatestToast());
            assertFalse("a part copied was left", partial().exists());
            assertBeforeKept(page, rubik);
        }
    }

    /**
     * A copy turned down at the moment the wait runs out: the worker returned first, so the screen
     * says why the file was turned down rather than that the app took too long.
     */
    @Test
    public void aRefusalThatLandsAsTheWaitRunsOutIsTheOneSaid() throws Exception {
        byte[] rubik = pickedBefore();
        FontFilePreference.streamForTests = stream -> gatedRead = new GatedStream(stream);
        Uri uri = FontFileProvider.put("content://font-test/documents/notes.txt",
                "This is not a font".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "notes.txt");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            pickHeld(activity, page, uri);
            // The wait is due, but the main thread hasn't got to it when the worker returns.
            ShadowSystemClock.advanceBy(Duration.ofMillis(FontFilePreference.timeoutMs + 1));
            gatedRead.letGo();
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();

            assertEquals(FontFilePreference.refusal(FontFile.Refusal.NOT_A_FONT), ShadowToast.getTextOfLatestToast());
            assertBeforeKept(page, rubik);
            // Nothing is left holding a worker, so the next pick opens the picker.
            tap(activity, page);
        }
    }

    @Test
    public void aFileNameIsShownOnOneCleanLine() {
        assertEquals("Inter.ttf", FontFilePreference.cleanName("  Inter.ttf \n"));
        assertEquals("My Font.otf", FontFilePreference.cleanName("My\u0000 ‮Font.otf"));
        assertEquals("font", FontFilePreference.cleanName(""));
        assertEquals("font", FontFilePreference.cleanName(null));
        assertEquals("font", FontFilePreference.cleanName("​\u0007"));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 30; i++) longName.append("𝐀bc");
        String shown = FontFilePreference.cleanName(longName.toString());
        assertEquals(FontFilePreference.MAX_NAME_LENGTH, shown.codePointCount(0, shown.length()));
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private static ShadowActivity.IntentForResult tap(Activity activity, HushfacebookPreferenceFragment page) {
        Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
        assertNotNull("no Font file row", choose);
        choose.getOnPreferenceClickListener().onPreferenceClick(choose);
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("Font file opened no picker", started);
        return started;
    }

    /** Answers the picker with a file at [address] holding [bytes], and waits for what that sets off. */
    private static void deliver(Activity activity, ShadowActivity.IntentForResult started, String address,
                                byte[] bytes) throws Exception {
        deliver(activity, started, address, bytes, null);
    }

    /** {@link #deliver}, with the file named [name] by its app, or by its address when that's null. */
    private static void deliver(Activity activity, ShadowActivity.IntentForResult started, String address,
                                byte[] bytes, String name) throws Exception {
        Uri uri = FontFileProvider.put(address, bytes, name);
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
        assertEquals("The picker result did not open exactly its document: " + address,
                1, FontFileProvider.opens(uri));
    }

    /** Rubik as the font picked before, its copy written straight to Facebook's files. */
    private static byte[] pickedBefore() throws Exception {
        byte[] rubik = FontFileTest.font(FontFileTest.STATIC_FONT);
        Files.write(copy().toPath(), rubik);
        assertTrue(Settings.FONT_SOURCE.save("Rubik-Regular.ttf"));
        return rubik;
    }

    /** Where a copy is written until it has passed its checks. */
    private static File partial() {
        return new File(copy().getPath() + ".part");
    }

    /** Taps Font file, answers the picker with [uri], and waits until the app holds what the copy asked it for. */
    private static void pickHeld(Activity activity, HushfacebookPreferenceFragment page, Uri uri) throws Exception {
        shadowOf(activity).receiveResult(tap(activity, page).intent, Activity.RESULT_OK, new Intent().setData(uri));
        ShadowLooper.idleMainLooper();
        assertTrue("the app never got the request", FontFileProvider.holding.tryAcquire(5, TimeUnit.SECONDS));
    }

    /** Lets the screen's wait for the file's app run out. */
    private static void waitOut() {
        ShadowLooper.idleMainLooper(FontFilePreference.timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Idles the main thread until [done], for a run that ends while a stalled copy still holds a
     * worker, which settle would wait on.
     */
    private static void idleUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean()) {
            assertTrue("it never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
            ShadowLooper.idleMainLooper();
        }
    }

    /**
     * The font picked before by pickedBefore: still named, still the copy's bytes, shown on Font
     * file, which is in reach again, and no restart asked for.
     */
    private static void assertBeforeKept(HushfacebookPreferenceFragment page, byte[] before) throws Exception {
        assertEquals("Rubik-Regular.ttf", Settings.FONT_SOURCE.savedValue());
        assertArrayEquals("the copy picked before changed", before, Files.readAllBytes(copy().toPath()));
        Preference choose = page.findPreference(FontFilePreference.CHOOSE_KEY);
        assertTrue("Font file stayed busy", choose.isEnabled());
        assertEquals(FontFilePreference.chosenSummary("Rubik-Regular.ttf", true), String.valueOf(choose.getSummary()));
        assertFalse("a restart was asked for", AbstractPreferenceFragment.restartPending.contains(FontFilePreference.CHOOSE_KEY));
    }

    /**
     * Hands over the first bytes of the file, then holds the next read until the stream is closed,
     * as an app stuck partway through does. Closing a file Android handed over lets a read on it go.
     */
    private static final class StallingStream extends FilterInputStream {
        private final CountDownLatch closed = new CountDownLatch(1);
        private long left;

        StallingStream(InputStream file, long given) {
            super(file);
            left = given;
        }

        boolean wasClosed() {
            return closed.getCount() == 0;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                FontFileProvider.holding.release();
                try {
                    if (!closed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("The test never let the read go");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Closed while reading");
            }
            int count = super.read(buffer, offset, (int) Math.min(length, left));
            if (count > 0) left -= count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            super.close();
        }
    }

    /**
     * Holds the first read until the test lets it go, and ignores a close before then, as an app
     * that hands the file over late and takes no notice of the screen closing it does.
     */
    private static final class GatedStream extends FilterInputStream {
        private final CountDownLatch go = new CountDownLatch(1);
        private final CountDownLatch closeAsked = new CountDownLatch(1);
        private volatile boolean waited;
        private volatile long read;

        GatedStream(InputStream file) {
            super(file);
        }

        void letGo() {
            go.countDown();
        }

        /**
         * Whether something asked to close this within a few seconds. The screen closes it from a
         * thread of its own, so a test waits for that before letting the read go, or the close
         * could land mid-read.
         */
        boolean closeAsked() throws InterruptedException {
            return closeAsked.await(10, TimeUnit.SECONDS);
        }

        long bytesRead() {
            return read;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!waited) {
                waited = true;
                FontFileProvider.holding.release();
                try {
                    if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("The test never let the read go");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while held");
                }
            }
            int count = super.read(buffer, offset, length);
            if (count > 0) read += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            // Decided before the test hears of it, so a letGo that follows can't turn an ignored
            // close into one that lands mid-read.
            boolean late = go.getCount() == 0;
            try {
                if (late) super.close();
            } finally {
                closeAsked.countDown();
            }
        }
    }

    private static void settle() throws Exception {
        for (int round = 0; round < 3; round++) {
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
        }
    }
}
