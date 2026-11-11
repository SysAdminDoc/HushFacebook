/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import com.facebook.graphservice.tree.TreeJNI;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** Save photo in a photo comment's long-press sheet (#440): which comments get the row, and what it saves. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentPhotoTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String FULL = "https://scontent-mia3-1.xx.fbcdn.net/v/t39.30808-6/1_n.jpg?stp=dst-jpg_s2048x2048_tt6&oh=00_A&oe=6A1B2C3D";
    private static final String SMALL = "https://scontent-mia3-1.xx.fbcdn.net/v/t39.30808-6/1_n.jpg?stp=dst-jpg_s320x320_tt6&oh=00_B&oe=6A1B2C3D";
    private static final String GIF = "https://external-mia5-1.xx.fbcdn.net/emg1/v/t13/1234567890?url=https%3A%2F%2Fmedia4.giphy.com"
            + "%2Fmedia%2Fabc%2Fgiphy.gif&stp=dst-gif_fr_q75&oh=06_Q3&oe=6ADE4856";

    @After
    public void restore() {
        HookStatus.clear();
        PauseForTests.resume();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        System.clearProperty(CommentPhoto.ACCESSIBILITY_PROPERTY);
        shadowOf(accessibility()).setTouchExplorationEnabled(false);
    }

    private static AccessibilityManager accessibility() {
        return (AccessibilityManager) Utils.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    private static TreeJNI image(String uri, int width, int height) {
        return new TreeJNI().with("uri", uri).number("width", width).number("height", height);
    }

    /** A photo comment's attachment: the media is a Photo carrying its images. */
    private static TreeJNI photoAttachment(String id) {
        return new TreeJNI().with("media", new TreeJNI("Photo").with("id", id)
                .with("image", image(SMALL, 320, 240))
                .with("imageHigh", image(FULL, 2048, 1536)));
    }

    private static TreeJNI gifAttachment() {
        return new TreeJNI().with("media", new TreeJNI("GenericAttachmentMedia")
                .with("image", image(SMALL, 320, 240))
                .with("animated_image", new TreeJNI().with("uri", GIF)));
    }

    private static TreeJNI videoAttachment() {
        return new TreeJNI().with("media", new TreeJNI("Video").with("id", "77")
                .with("image", image(SMALL, 320, 240)));
    }

    private static TreeJNI comment(TreeJNI... attachments) {
        return new TreeJNI("Comment").with("attachments", Arrays.asList(attachments));
    }

    @Test
    public void aPhotoCommentGetsTheRowWithItsLargestImage() {
        Object click = CommentPhoto.clickFor(comment(photoAttachment("123")));
        assertNotNull(click);
        assertEquals(FULL, ((CommentPhoto) click).url);
        assertEquals("Save photo", CommentPhoto.title());
    }

    @Test
    public void theFirstPhotoAfterAGifIsTheOne() {
        CommentPhoto row = (CommentPhoto) CommentPhoto.clickFor(comment(gifAttachment(), photoAttachment("123")));
        assertNotNull(row);
        assertEquals(FULL, row.url);
    }

    @Test
    public void aCommentWithNoPhotoGetsNoRow() {
        assertNull("a GIF keeps its own Save GIF", CommentPhoto.clickFor(comment(gifAttachment())));
        assertNull("a video keeps its own Save video", CommentPhoto.clickFor(comment(videoAttachment())));
        assertNull("text only", CommentPhoto.clickFor(new TreeJNI("Comment").with("attachments", Collections.<TreeJNI>emptyList())));
        assertNull("a photo with no image", CommentPhoto.clickFor(comment(
                new TreeJNI().with("media", new TreeJNI("Photo").with("id", "123")))));
        assertNull("no comment", CommentPhoto.clickFor(null));
        assertNull("not a model", CommentPhoto.clickFor("comment"));
        assertNull("a released model", CommentPhoto.clickFor(comment(photoAttachment("123")).releasedTree()));
    }

    @Test
    public void aReleasedPhotoIsSkippedForTheNextLiveOne() {
        TreeJNI released = new TreeJNI("Photo").with("image", image(SMALL, 320, 240)).releasedTree();
        CommentPhoto row = (CommentPhoto) CommentPhoto.clickFor(
                comment(new TreeJNI().with("media", released), photoAttachment("123")));
        assertNotNull(row);
        assertEquals(FULL, row.url);
        assertFalse("the released photo was read", released.readAfterRelease);
    }

    @Test
    public void facebooksOwnRowLeavesNoSecondOne() {
        TreeJNI photo = comment(photoAttachment("123"));
        shadowOf(accessibility()).setTouchExplorationEnabled(true);
        assertNull("TalkBack on: Facebook's sheet has Save photo already", CommentPhoto.clickFor(photo));
        shadowOf(accessibility()).setTouchExplorationEnabled(false);
        System.setProperty(CommentPhoto.ACCESSIBILITY_PROPERTY, "true");
        assertNull("the system property Facebook also reads", CommentPhoto.clickFor(photo));
        System.clearProperty(CommentPhoto.ACCESSIBILITY_PROPERTY);
        assertNotNull(CommentPhoto.clickFor(photo));
    }

    @Test
    public void theSwitchOffOrPausedLeavesTheSheetAlone() {
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertNull(CommentPhoto.clickFor(comment(photoAttachment("123"))));
        Settings.DOWNLOAD_PHOTOS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull(CommentPhoto.clickFor(comment(photoAttachment("123"))));
        PauseForTests.resume();
        assertNotNull(CommentPhoto.clickFor(comment(photoAttachment("123"))));
    }

    @Test
    public void aTapAfterTheSwitchWentOffSavesNothing() {
        CommentPhoto row = (CommentPhoto) CommentPhoto.clickFor(comment(photoAttachment("123")));
        assertNotNull(row);
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertNull(row.invoke(null));
    }
}
