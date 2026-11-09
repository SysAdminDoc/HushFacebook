/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

/**
 * Turn gray over: how many minutes on the Reels screen Show Reels in gray takes to go from full
 * color to gray ({@link GrayReels}). At once is the gray as it always was.
 */
public enum GrayOver {
    AT_ONCE(0, "at_once"),
    FIVE_MINUTES(5, "5m"),
    TEN_MINUTES(10, "10m"),
    FIFTEEN_MINUTES(15, "15m"),
    THIRTY_MINUTES(30, "30m");

    public final int minutes;
    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;

    GrayOver(int minutes, String fileValue) {
        this.minutes = minutes;
        this.fileValue = fileValue;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static GrayOver fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (GrayOver over : values()) {
            if (over.fileValue.equals(value)) return over;
        }
        return null;
    }
}
