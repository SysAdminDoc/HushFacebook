/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;

import app.morphe.extension.facebook.feed.PostRowTaps;
import app.morphe.extension.facebook.feed.PostSaveButton;

/**
 * The Post action buttons hooks, for a test outside this package: a row's type list and a video
 * post's row, handed over the way the patch hands them.
 */
public final class PostActionButtonsForTests {
    private PostActionButtonsForTests() {
    }

    /** Stands in for Facebook's SAVE button type. */
    static final String SAVE = "SAVE";

    /** True when a row of Like, Comment and Share gets Save. */
    public static boolean addsSave() {
        return PostSaveButton.types(Arrays.asList("LIKE", "COMMENT", "SHARE"), SAVE).contains(SAVE);
    }

    /** True when a row of Like (Facebook's REACT), Comment and Share loses Like. */
    public static boolean hidesLike() {
        return !PostSaveButton.types(Arrays.asList("REACT", "COMMENT", "SHARE"), SAVE).contains("REACT");
    }

    /** True when the row under a post is drawn without its own tap (#540). */
    public static boolean leavesTheTapOff() {
        return PostRowTaps.ignoreRowTaps();
    }

    /** True when a video post's row gets a Download button, with Download any video in the build. */
    public static boolean addsDownload() {
        Boolean before = PostDownloadButton.videoInBuildForTests;
        PostDownloadButton.videoInBuildForTests = true;
        try {
            return PostDownloadButton.click(VideoMenuItemForTests.videoPost(), RuntimeEnvironment.getApplication(),
                    VideoMenuItemForTests.ATTACHMENTS, VideoMenuItemForTests.MEDIA,
                    VideoMenuItemForTests.ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS) != null;
        } finally {
            PostDownloadButton.videoInBuildForTests = before;
        }
    }
}
