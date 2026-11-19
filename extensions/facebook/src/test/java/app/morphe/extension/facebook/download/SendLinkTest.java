/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.facebook.video.engine.api.VideoPlayerParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowToast;

import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Send to an app (#41): a Download tap hands the video's facebook.com link, built from its id and
 * nothing else, to the app named in the settings as plain text, or to Android's chooser when none
 * is named or it isn't installed. A long press on the reel button copies the link. Saving stays
 * the default.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SendLinkTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REEL_ID = "2233445566778899";
    private static final String REEL_LINK = "https://www.facebook.com/reel/" + REEL_ID;

    private Application application;
    private ShadowApplication shadow;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        shadow = Shadows.shadowOf(application);
        ShadowToast.reset();
        while (shadow.getNextStartedActivity() != null) {
            // Left from another test.
        }
    }

    @After
    public void tearDown() {
        Settings.DOWNLOAD_ACTION.resetToDefault();
        Settings.SEND_TO_APP.resetToDefault();
        MediaDownload.detailsForTests = null;
        ReelDownload.tapEndsACopy(null, 0);
    }

    /** Makes [target] an app that takes shared text, so a send aimed at it resolves. */
    private void install(String target) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = target;
        info.activityInfo.name = target + ".ShareActivity";
        Shadows.shadowOf(application.getPackageManager())
                .addResolveInfoForIntent(SendLink.intent(REEL_LINK, target), info);
    }

    /** Makes Android's chooser resolve, as it always does on a phone. */
    private void chooserInstalled() {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "android";
        info.activityInfo.name = "com.android.internal.app.ChooserActivity";
        Shadows.shadowOf(application.getPackageManager())
                .addResolveInfoForIntent(new Intent(Intent.ACTION_CHOOSER), info);
    }

    private static void assertSendsText(Intent send, String link, String target) {
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(link, send.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(target, send.getPackage());
        assertNull("the send carries a subject or more than the link", send.getStringExtra(Intent.EXTRA_SUBJECT));
        assertEquals("the send carries more than the link", 1, send.getExtras().size());
    }

    /** The chooser [started] is, holding the send of [link] to any app. */
    private static void assertChooserFor(Intent started, String link) {
        assertNotNull("nothing opened", started);
        assertEquals(Intent.ACTION_CHOOSER, started.getAction());
        assertTrue("the chooser opens outside Facebook's task", (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        Intent inner = started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull("the chooser holds no send", inner);
        assertSendsText(inner, link, null);
    }

    private String clipboard() {
        ClipboardManager clipboard = (ClipboardManager) application.getSystemService(Context.CLIPBOARD_SERVICE);
        return clipboard.hasPrimaryClip() ? String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()) : null;
    }

    // ---- The link and the intent ----------------------------------------------------------------

    @Test
    public void theIntentIsPlainTextOfTheLinkToTheNamedApp() {
        Intent send = SendLink.intent(REEL_LINK, SendLink.YTDLNIS);
        assertSendsText(send, REEL_LINK, SendLink.YTDLNIS);
        assertTrue("the app opens outside Facebook's task", (send.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertSendsText(SendLink.intent(REEL_LINK, null), REEL_LINK, null);
    }

    /** Only digits make a link, so nothing else of the post can reach the other app. */
    @Test
    public void onlyAVideoIdMakesALink() {
        assertEquals(REEL_LINK, SendLink.reelLink(REEL_ID));
        assertEquals("https://www.facebook.com/watch/?v=1234567890123456", SendLink.videoLink("1234567890123456"));
        for (String notAnId : new String[]{null, "", "12a4", "123 --exec rm", "123\n456", "-1", "1.5",
                "https://example.com/1", "12345678901234567890123456"}) {
            assertNull(notAnId, SendLink.reelLink(notAnId));
            assertNull(notAnId, SendLink.videoLink(notAnId));
        }
    }

    @Test
    public void onlyAPackageNameNamesAnApp() {
        assertEquals(SendLink.YTDLNIS, SendLink.targetPackage(SendLink.YTDLNIS));
        assertEquals(SendLink.SEAL, SendLink.targetPackage("  " + SendLink.SEAL + " \n"));
        for (String notAPackage : new String[]{null, "", "   ", "ytdlnis", "com.", ".com.x", "com..x", "1com.x",
                "com.x/.Main", "com.x --flag", "com.x;rm"}) {
            assertNull(notAPackage, SendLink.targetPackage(notAPackage));
        }
    }

    // ---- Sending --------------------------------------------------------------------------------

    @Test
    public void savingIsTheDefault() {
        assertEquals(SendLink.Action.SAVE, Settings.DOWNLOAD_ACTION.get());
        assertFalse(SendLink.sending());
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        assertTrue(SendLink.sending());
    }

    @Test
    public void theLinkGoesToTheNamedAppWhenItsInstalled() {
        shadow.checkActivities(true);
        install(SendLink.YTDLNIS);
        Settings.SEND_TO_APP.save(SendLink.YTDLNIS);

        assertTrue(SendLink.send(application, REEL_LINK));
        Intent started = shadow.getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertSendsText(started, REEL_LINK, SendLink.YTDLNIS);
        assertNull("the chooser opened as well", shadow.getNextStartedActivity());
    }

    @Test
    public void aMissingAppFallsBackToTheChooser() {
        shadow.checkActivities(true);
        chooserInstalled();
        Settings.SEND_TO_APP.save(SendLink.SEAL);

        assertTrue(SendLink.send(application, REEL_LINK));
        assertChooserFor(shadow.getNextStartedActivity(), REEL_LINK);
    }

    @Test
    public void noAppNamedOpensTheChooser() {
        for (String typed : new String[]{"", "not a package"}) {
            Settings.SEND_TO_APP.save(typed);
            assertTrue(typed, SendLink.send(application, REEL_LINK));
            assertChooserFor(shadow.getNextStartedActivity(), REEL_LINK);
        }
    }

    @Test
    public void nothingToOpenSaysSo() {
        shadow.checkActivities(true);
        assertFalse(SendLink.send(application, REEL_LINK));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Couldn't open an app for this link", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void copyPutsTheLinkOnTheClipboard() {
        SendLink.copy(application, REEL_LINK);
        assertEquals(REEL_LINK, clipboard());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Link copied", ShadowToast.getTextOfLatestToast());
    }

    // ---- The reel button ------------------------------------------------------------------------

    private static FileNameTemplateTest.RichParams reel(String said) {
        return new FileNameTemplateTest.RichParams(new VideoPlayerParams(said, null));
    }

    private ReelDownload handler(Object params, int slot, boolean saves) {
        return new ReelDownload(params, application, "hd", "sd", "manifest", slot, saves);
    }

    private static MotionEvent lift(long heldMs) {
        long up = SystemClock.uptimeMillis();
        return MotionEvent.obtain(up - heldMs, up, MotionEvent.ACTION_UP, 10f, 10f, 0);
    }

    /** A tap sends the reel's own link and saves nothing. */
    @Test
    public void aReelTapSendsTheReelsLink() {
        AtomicInteger saves = new AtomicInteger();
        MediaDownload.detailsForTests = details -> saves.incrementAndGet();
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        assertEquals("Send to app", ReelDownload.label());

        handler(reel("VideoId: " + REEL_ID), 1, true).invoke(null);
        assertChooserFor(shadow.getNextStartedActivity(), REEL_LINK);
        assertEquals("a sent reel was saved too", 0, saves.get());
    }

    @Test
    public void aReelWithNoIdSaysItHasNoLink() {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        handler(reel("something else"), 1, true).invoke(null);
        assertNull("something opened", shadow.getNextStartedActivity());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Couldn't find this reel's link", ShadowToast.getTextOfLatestToast());
    }

    /**
     * The touch slot's lift after a long press copies the link, and the tap Android sends for that
     * same lift is dropped. A short press is an ordinary tap.
     */
    @Test
    public void aLongPressCopiesTheLinkInsteadOfSendingIt() {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        FileNameTemplateTest.RichParams params = reel("VideoId: " + REEL_ID);
        ReelDownload touch = handler(params, 0, false);
        ReelDownload tap = handler(params, 1, true);

        touch.invoke(lift(ViewConfiguration.getLongPressTimeout() + 50));
        assertEquals(REEL_LINK, clipboard());
        tap.invoke(null);
        assertNull("the long press's own tap sent the link", shadow.getNextStartedActivity());

        // The next tap is an ordinary one.
        tap.invoke(null);
        assertChooserFor(shadow.getNextStartedActivity(), REEL_LINK);

        // A short press copies nothing, and a press that became a swipe ends in a cancel.
        ((ClipboardManager) application.getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip();
        touch.invoke(lift(50));
        long up = SystemClock.uptimeMillis();
        touch.invoke(MotionEvent.obtain(up - 2000, up, MotionEvent.ACTION_CANCEL, 10f, 10f, 0));
        assertNull("a short press or a swipe copied the link", clipboard());
        tap.invoke(null);
        assertChooserFor(shadow.getNextStartedActivity(), REEL_LINK);
    }

    /**
     * The tap dropped after a copy is only that button's own: when it never comes, a tap on the
     * next reel's button still sends that reel.
     */
    @Test
    public void aLongPressOnOneReelNeverDropsATapOnAnother() {
        Settings.DOWNLOAD_ACTION.save(SendLink.Action.SEND);
        handler(reel("VideoId: " + REEL_ID), 0, false).invoke(lift(ViewConfiguration.getLongPressTimeout() + 50));
        assertEquals(REEL_LINK, clipboard());

        handler(reel("VideoId: 99887766"), 1, true).invoke(null);
        assertChooserFor(shadow.getNextStartedActivity(), "https://www.facebook.com/reel/99887766");
    }

    /** Saving, the default, keeps the button as it was: a long press is only a tap. */
    @Test
    public void whileSavingALongPressCopiesNothing() {
        AtomicInteger saves = new AtomicInteger();
        MediaDownload.detailsForTests = details -> saves.incrementAndGet();
        handler(reel("VideoId: " + REEL_ID), 0, false).invoke(lift(ViewConfiguration.getLongPressTimeout() + 50));
        assertNull(clipboard());
        assertFalse("a long press left the next tap to be dropped", ReelDownload.tapEndsACopy(null, SystemClock.uptimeMillis()));
        assertEquals("Download", ReelDownload.label());
    }

    /** Facebook's touch event can hold the MotionEvent rather than be one. */
    @Test
    public void aTouchEventHoldingTheMotionIsRead() {
        MotionEvent motion = lift(10);
        assertEquals(motion, ReelDownload.motionEventOf(motion));
        assertEquals(motion, ReelDownload.motionEventOf(new Holder(motion)));
        assertNull(ReelDownload.motionEventOf(new Object()));
        assertNull(ReelDownload.motionEventOf(null));
    }

    /** The tap slot's event holds the button's View, which the Clean mode button reads (#391). */
    @Test
    public void aTapEventHoldingTheViewIsRead() {
        android.view.View button = new android.view.View(RuntimeEnvironment.getApplication());
        assertEquals(button, ReelDownload.viewOf(button));
        assertEquals(button, ReelDownload.viewOf(new Tap(button)));
        assertNull(ReelDownload.viewOf(new Holder(lift(10))));
        assertNull(ReelDownload.viewOf(null));
    }

    static final class Tap {
        static final android.view.View SHARED = null;
        final Object source = new Object();
        final android.view.View view;

        Tap(android.view.View view) {
            this.view = view;
        }
    }

    static final class Holder {
        static final String KIND = "touch";
        final Object view = new Object();
        final MotionEvent event;

        Holder(MotionEvent event) {
            this.event = event;
        }
    }
}
