/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import androidx.annotation.Nullable;

/** How high the comment sheet opens ({@link FullHeightComments}, #179). */
public enum CommentsHeight {
    /** Where Facebook stops it: part way up on reels and videos, the whole screen on posts. */
    FACEBOOK("facebook"),
    /** The whole screen, with the reel or video above the comments hidden. */
    FULL("full"),
    /** Half the screen, with the reel or video still playing above the comments. */
    HALF("half");

    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;

    CommentsHeight(String fileValue) {
        this.fileValue = fileValue;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static CommentsHeight fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (CommentsHeight height : values()) {
            if (height.fileValue.equals(value)) return height;
        }
        return null;
    }
}
