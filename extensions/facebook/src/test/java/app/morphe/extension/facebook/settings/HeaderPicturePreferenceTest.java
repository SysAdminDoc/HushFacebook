/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.preference.Preference;

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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import app.morphe.extension.facebook.misc.HeaderPicture;
import app.morphe.extension.facebook.misc.HeaderPictureTest;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Home header picture and Remove the header picture under Appearance (#230): the picker a tap
 * opens, the shrunk copy a picked picture becomes, what's said when a file isn't a picture, and the
 * way back to the words or the logo. Use the Hushfacebook logo (#467) copies the logo the settings
 * patch ships in the same way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class HeaderPicturePreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String AUTHORITY = "picture-test";

    @Before
    public void headerPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HEADER_TEXT);
        ShadowToast.reset();
        FontFileProvider.install(AUTHORITY);
    }

    @After
    public void restore() throws Exception {
        FontFileProvider.releaseAll();
        settle();
        PatchFamily.inBuildForTests = null;
        HeaderPicturePreference.logoForTests = null;
        Settings.HEADER_PICTURE.resetToDefault();
        HeaderPicture.forget();
        File copy = copy();
        if (copy.exists()) assertTrue(copy.delete());
    }

    private static File copy() {
        return HeaderPicture.file(RuntimeEnvironment.getApplication());
    }

    @Test
    public void theRowSitsAfterHomeHeaderTextWithNothingToRemove() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            Preference words = page.findPreference(Settings.HEADER_TEXT.key);
            Preference choose = page.findPreference(HeaderPicturePreference.CHOOSE_KEY);
            assertNotNull(choose);
            assertEquals("Home header picture", String.valueOf(choose.getTitle()));
            assertEquals("No picture chosen. Choose one to show at the top of Home instead of Facebook's logo.",
                    String.valueOf(choose.getSummary()));
            assertEquals(words.getParent(), choose.getParent());
            assertTrue("after the words", choose.getOrder() > words.getOrder());
            assertNull("nothing to remove", page.findPreference(HeaderPicturePreference.REMOVE_KEY));
            assertNull("this app has no Hushfacebook logo", page.findPreference(HeaderPicturePreference.LOGO_KEY));
        }
    }

    @Test
    public void theLogoRowPutsHushfacebooksLogoAtTheTopOfHome() throws Exception {
        // Any picture in the app's resources stands in for the one the settings patch adds.
        HeaderPicturePreference.logoForTests = android.R.drawable.star_big_on;
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Preference choose = page.findPreference(HeaderPicturePreference.CHOOSE_KEY);
            Preference logo = page.findPreference(HeaderPicturePreference.LOGO_KEY);
            assertNotNull("no logo row", logo);
            assertEquals("Use the Hushfacebook logo", String.valueOf(logo.getTitle()));
            assertEquals("Shows Hushfacebook's logo at the top of Home in place of your words and Facebook's logo. "
                    + "Restart Facebook to see it.", String.valueOf(logo.getSummary()));
            assertTrue("it acts on the tap", ((HeaderPicturePreference) logo).actsOnTap());
            assertEquals(choose.getParent(), logo.getParent());
            assertTrue("after the picture", logo.getOrder() > choose.getOrder());

            logo.getOnPreferenceClickListener().onPreferenceClick(logo);
            settle();
            assertNull("no picker opened", shadowOf(activity).getNextStartedActivityForResult());
            assertEquals("Hushfacebook logo", Settings.HEADER_PICTURE.savedValue());
            assertNotNull("the copy is a picture", BitmapFactory.decodeFile(copy().getPath()));
            assertEquals("Selected " + L10n.isolate("Hushfacebook logo") + ". Restart Facebook to see it at the top of Home.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("Selected " + L10n.isolate("Hushfacebook logo")
                            + ". It shows at the top of Home in place of your words and Facebook's logo.",
                    String.valueOf(choose.getSummary()));
            assertTrue(AbstractPreferenceFragment.restartPending.contains(HeaderPicturePreference.CHOOSE_KEY));
            Preference remove = page.findPreference(HeaderPicturePreference.REMOVE_KEY);
            assertNotNull("the way back came", remove);
            assertTrue("the way back comes after the logo", remove.getOrder() > logo.getOrder());
            assertTrue("the logo row stays", logo.isEnabled());
        }
    }

    @Test
    public void aLogoThatWontOpenSaysSoAndChangesNothing() throws Exception {
        HeaderPicturePreference.logoForTests = 0x7f7f0fff; // No resource has this id.
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            Preference logo = page.findPreference(HeaderPicturePreference.LOGO_KEY);
            logo.getOnPreferenceClickListener().onPreferenceClick(logo);
            settle();
            assertEquals("Couldn't open that file. The top of Home didn't change.", ShadowToast.getTextOfLatestToast());
            assertEquals("", Settings.HEADER_PICTURE.savedValue());
            assertFalse(copy().exists());
            assertTrue("the rows came back", logo.isEnabled());
            assertFalse(AbstractPreferenceFragment.restartPending.contains(HeaderPicturePreference.CHOOSE_KEY));
        }
    }

    @Test
    public void noRowsWithoutThePatch() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            assertNull(page.findPreference(HeaderPicturePreference.CHOOSE_KEY));
        }
    }

    @Test
    public void theRowOpensThePickerForPictures() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
            assertEquals(HeaderPicturePreference.PICK_PICTURE, started.requestCode);
            assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("image/*", started.intent.getType());
            assertTrue("the other pickers' codes are their own",
                    HeaderPicturePreference.PICK_PICTURE != FontFilePreference.PICK_FONT
                            && HeaderPicturePreference.PICK_PICTURE != SettingsBackupPreference.IMPORT
                            && HeaderPicturePreference.PICK_PICTURE != SettingsBackupPreference.EXPORT);
        }
    }

    @Test
    public void aPickedPictureIsShrunkInNamedAndRemovable() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://picture-test/documents/42",
                    HeaderPictureTest.picture(1600, 800, Color.RED, Bitmap.CompressFormat.JPEG), "Our logo.jpg");

            assertEquals("Our logo.jpg", Settings.HEADER_PICTURE.savedValue());
            Bitmap kept = BitmapFactory.decodeFile(copy().getPath());
            assertEquals(512, kept.getWidth());
            assertEquals(256, kept.getHeight());
            assertEquals("Selected " + L10n.isolate("Our logo.jpg") + ". Restart Facebook to see it at the top of Home.",
                    ShadowToast.getTextOfLatestToast());
            Preference choose = page.findPreference(HeaderPicturePreference.CHOOSE_KEY);
            assertEquals("Selected " + L10n.isolate("Our logo.jpg")
                            + ". It shows at the top of Home in place of your words and Facebook's logo.",
                    String.valueOf(choose.getSummary()));
            assertTrue(AbstractPreferenceFragment.restartPending.contains(HeaderPicturePreference.CHOOSE_KEY));

            Preference remove = page.findPreference(HeaderPicturePreference.REMOVE_KEY);
            assertNotNull("the way back came", remove);
            assertEquals("Remove the header picture", String.valueOf(remove.getTitle()));
            assertTrue("it acts on the tap", ((HeaderPicturePreference) remove).actsOnTap());
            assertTrue(remove.getOrder() > choose.getOrder());

            remove.getOnPreferenceClickListener().onPreferenceClick(remove);
            settle();
            assertEquals("", Settings.HEADER_PICTURE.savedValue());
            assertFalse("the copy is gone", copy().exists());
            assertEquals("Header picture removed. Restart Facebook to see the change.", ShadowToast.getTextOfLatestToast());
            assertNull("nothing left to remove", page.findPreference(HeaderPicturePreference.REMOVE_KEY));
            assertEquals("No picture chosen. Choose one to show at the top of Home instead of Facebook's logo.",
                    String.valueOf(choose.getSummary()));
        }
    }

    @Test
    public void aFileThatIsntAPictureSaysSoAndChangesNothing() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page), "content://picture-test/documents/notes.txt",
                    "just some words".getBytes(StandardCharsets.UTF_8), null);

            assertEquals("That isn't a picture Android can open. The top of Home didn't change.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("", Settings.HEADER_PICTURE.savedValue());
            assertFalse(copy().exists());
            assertNull(page.findPreference(HeaderPicturePreference.REMOVE_KEY));
            assertTrue(page.findPreference(HeaderPicturePreference.CHOOSE_KEY).isEnabled());
            assertFalse("no restart was asked for",
                    AbstractPreferenceFragment.restartPending.contains(HeaderPicturePreference.CHOOSE_KEY));
        }
    }

    @Test
    public void aMissingCopySaysToChooseAgain() {
        assertEquals("The saved copy of " + L10n.isolate("logo.png") + " is missing. Choose the picture again.",
                HeaderPicturePreference.chosenSummary("logo.png", false));
        assertEquals("That picture is over 20 MB. The top of Home didn't change.",
                HeaderPicturePreference.refusal(HeaderPicture.Refusal.TOO_LARGE));
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private static ShadowActivity.IntentForResult tap(Activity activity, HushfacebookPreferenceFragment page) {
        Preference choose = page.findPreference(HeaderPicturePreference.CHOOSE_KEY);
        assertNotNull("no Home header picture row", choose);
        choose.getOnPreferenceClickListener().onPreferenceClick(choose);
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("Home header picture opened no picker", started);
        return started;
    }

    /** Answers the picker with a file at [address] holding [bytes], named [name], and waits for what that sets off. */
    private static void deliver(Activity activity, ShadowActivity.IntentForResult started, String address,
                                byte[] bytes, String name) throws Exception {
        Uri uri = FontFileProvider.put(address, bytes, name);
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
        assertEquals("The picker result did not open exactly its document: " + address,
                1, FontFileProvider.opens(uri));
    }

    private static void settle() throws Exception {
        for (int round = 0; round < 3; round++) {
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
        }
    }
}
