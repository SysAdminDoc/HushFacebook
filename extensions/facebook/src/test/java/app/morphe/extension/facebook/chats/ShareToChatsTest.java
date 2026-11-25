/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.navigation.TopBarButtons;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Share to Facebook chats (#568): what a share brings, the share screen following its switch, and
 * the Messaging button pressed on the first Facebook screen after a share.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ShareToChatsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Every note the share target shows, in English: each one a toast. */
    private static final List<String> NOTES = Arrays.asList(
            "Share to Facebook chats is off.",
            "Nothing in it to send.",
            "Couldn't save it to your gallery.",
            "Copied and saved. Pick a chat.",
            "In your gallery now. Pick a chat.",
            "Copied. Pick a chat and paste it.",
            "Open your chats to pick one.");

    /** The languages Hushfacebook carries tables for, as resource qualifiers. */
    private static final List<String> LANGUAGES = Arrays.asList(
            "ar", "bg", "de", "es", "in", "it", "ko", "pt-rBR", "ru", "th", "tr", "vi", "zh-rCN", "zh-rTW");

    @After
    public void restore() {
        Settings.SHARE_TO_CHATS.resetToDefault();
        PauseForTests.resume();
        ShareToChats.forgetForTests();
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static Uri photo(String name) {
        return Uri.parse("content://com.example.photos/" + name);
    }

    @Test
    public void aShareBringsItsTextAndItsPhotosAndVideos() {
        Intent one = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_TEXT, "look at this")
                .putExtra(Intent.EXTRA_STREAM, photo("1"));
        ShareToChats.Shared shared = ShareToChats.read(one, app());
        assertEquals("look at this", shared.text);
        assertEquals(Arrays.asList(photo("1")), shared.media);
        assertEquals(Arrays.asList("image/jpeg"), shared.types);

        ArrayList<Uri> streams = new ArrayList<>(Arrays.asList(photo("2"), Uri.parse("file:///sdcard/3.jpg"), photo("2"),
                Uri.parse("content://com.example.videos/4")));
        Intent many = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
        shared = ShareToChats.read(many, app());
        assertNull("no text", shared.text);
        assertEquals("another app's file address and a repeat left out",
                Arrays.asList(photo("2"), Uri.parse("content://com.example.videos/4")), shared.media);
        assertEquals(Arrays.asList("video/*", "video/*"), shared.types);
    }

    @Test
    public void aLinkSharedWithOnlyASubjectIsCopiedAndAFileThatIsNoPhotoIsLeft() {
        ShareToChats.Shared shared = ShareToChats.read(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "https://example.com/a"), app());
        assertEquals("https://example.com/a", shared.text);
        assertTrue(shared.media.isEmpty());

        shared = ShareToChats.read(new Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, photo("5.pdf")), app());
        assertTrue("a PDF isn't for the gallery", shared.empty());
        assertTrue(ShareToChats.read(null, app()).empty());
    }

    @Test
    public void aShareNamingOneOfFacebooksOwnProvidersIsLeft() {
        ProviderInfo own = new ProviderInfo();
        own.packageName = app().getPackageName();
        own.name = "app.morphe.fixture.OwnFiles";
        own.authority = "com.example.own.files;com.example.own.cache";
        own.exported = false;
        own.applicationInfo = app().getApplicationInfo();
        shadowOf(app().getPackageManager()).addOrUpdateProvider(own);
        try {
            ArrayList<Uri> streams = new ArrayList<>(Arrays.asList(Uri.parse("content://com.example.own.cache/a.jpg"),
                    Uri.parse("content://0@com.example.own.files/b.jpg"), Uri.parse("content://COM.EXAMPLE.OWN.FILES/c.mp4"),
                    photo("7")));
            Intent many = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
            assertEquals("only the other app's photo is saved", Arrays.asList(photo("7")), ShareToChats.read(many, app()).media);
            assertTrue(ShareToChats.ownProvider(app(), Uri.parse("content://com.example.own.files/d.jpg")));
            assertFalse(ShareToChats.ownProvider(app(), photo("8")));
        } finally {
            shadowOf(app().getPackageManager()).removeProvider(new ComponentName(own.packageName, own.name));
        }
    }

    @Test
    public void aGalleryEntryIsntCopiedAgain() {
        assertTrue(ShareToChats.inGallery(Uri.parse("content://media/external/images/media/12")));
        assertFalse(ShareToChats.inGallery(Uri.parse("content://com.google.android.apps.photos.contentprovider/-1/1/x")));
        assertFalse(ShareToChats.inGallery(photo("6")));
    }

    @Test
    public void theShareScreenFollowsTheSwitchAndAPause() {
        PackageManager manager = app().getPackageManager();
        ComponentName screen = new ComponentName(app().getPackageName(), ShareToChats.class.getName());
        ShareToChats.sync(app());
        assertEquals("off as it ships", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(screen));

        Settings.SHARE_TO_CHATS.save(true);
        ShareToChats.changed(app());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(screen));
        assertTrue(ShareToChats.wanted());

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ShareToChats.changed(app());
        assertEquals("paused", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(screen));
        assertFalse(ShareToChats.wanted());

        PauseForTests.resume();
        ShareToChats.changed(app());
        Settings.SHARE_TO_CHATS.save(false);
        ShareToChats.changed(app());
        assertEquals("the switch off", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(screen));
    }

    private static int[] messengerButton(Activity activity) {
        int[] clicks = {0};
        FrameLayout bar = new FrameLayout(activity);
        View glyph = new View(activity);
        bar.addView(glyph);
        bar.setOnClickListener(view -> clicks[0]++);
        activity.setContentView(bar);
        TopBarButtons.barButton(glyph, "messenger");
        return clicks;
    }

    @Test
    public void theFirstScreenAfterAShareChoosesChatsWithItsMessagingButton() {
        Activity main = Robolectric.buildActivity(Activity.class).setup().get();
        int[] clicks = messengerButton(main);
        ShareToChats.activityResumed(main);
        assertEquals("no share, no press", 0, clicks[0]);

        long[] clock = {1_000};
        ShareToChats.clockForTests = () -> clock[0];
        ShareToChats.askForTests();
        ShareToChats.activityResumed(main);
        assertEquals("the button's bar takes the press", 1, clicks[0]);
        ShareToChats.activityResumed(main);
        assertEquals("one share, one press", 1, clicks[0]);

        ShareToChats.askForTests();
        clock[0] += ShareToChats.OPEN_WITHIN_MS + 1;
        ShareToChats.activityResumed(main);
        assertEquals("a share long ago presses nothing", 1, clicks[0]);
    }

    @Test
    public void aButtonWithNothingToTakeItsClickIsntPressed() {
        Activity main = Robolectric.buildActivity(Activity.class).setup().get();
        View alone = new View(main);
        main.setContentView(alone);
        assertFalse(ShareToChats.press(alone));
        int[] clicks = {0};
        alone.setOnClickListener(view -> clicks[0]++);
        assertTrue(ShareToChats.press(alone));
        assertEquals(1, clicks[0]);
    }

    @Test
    public void theNoteSaysWhereTheTextAndTheFilesWent() {
        assertEquals("Copied and saved. Pick a chat.", ShareToChats.note(app(), true, true));
        assertEquals("In your gallery now. Pick a chat.", ShareToChats.note(app(), false, true));
        assertEquals("Copied. Pick a chat and paste it.", ShareToChats.note(app(), true, false));
    }

    /** The lines [text] takes at [width] characters, words kept whole unless one is wider than a line. */
    static int lines(String text, int width) {
        int lines = 1;
        int column = 0;
        for (String word : text.trim().split(" +")) {
            int length = word.codePointCount(0, word.length());
            int needed = column == 0 ? length : column + 1 + length;
            if (needed <= width) {
                column = needed;
                continue;
            }
            if (column > 0) lines++;
            lines += (length - 1) / width;
            column = (length - 1) % width + 1;
        }
        return lines;
    }

    @Test
    public void everyNoteFitsTwoLinesOfANarrowPhoneInEveryLanguage() {
        assertEquals(1, lines("Nothing in it to send.", 28));
        assertEquals("the check can say no", 3, lines("a b c d e f g h i j k l m n o p q r s t u v w x y z a b c", 20));
        assertEquals(2, lines("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十", 28));
        List<String> wide = new ArrayList<>();
        for (String language : LANGUAGES) {
            // L10n reads the app's own locales, so the whole app changes language.
            RuntimeEnvironment.setQualifiers("+" + language);
            for (String note : NOTES) {
                String shown = L10n.t(app(), note);
                assertNotEquals(language + " has its own words for: " + note, note, shown);
                if (lines(shown, 28) > 2) wide.add(language + ": " + shown);
            }
        }
        for (String note : NOTES) if (lines(note, 28) > 2) wide.add("en: " + note);
        assertEquals(wide.toString(), 0, wide.size());
    }
}
