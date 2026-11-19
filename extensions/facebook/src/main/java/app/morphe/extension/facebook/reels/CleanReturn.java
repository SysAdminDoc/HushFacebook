/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

/**
 * Back to Clean mode: when a reel taken out of Clean mode goes back into it on its own
 * ({@link ReelCleanReturn}, #519). Off, once a sheet opened over the reel closes, or after a few
 * seconds with no touch.
 */
public enum CleanReturn {
    OFF(-1L, "off"),
    WHEN_A_SHEET_CLOSES(0L, "sheet"),
    THREE_SECONDS(3_000L, "3s"),
    FIVE_SECONDS(5_000L, "5s"),
    TEN_SECONDS(10_000L, "10s");

    /** How long the reel goes untouched first, 0 for a sheet closing, or -1 for never. */
    public final long millis;
    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;

    CleanReturn(long millis, String fileValue) {
        this.millis = millis;
        this.fileValue = fileValue;
    }

    /** Whether this choice waits a time with no touch, rather than for a sheet. */
    public boolean timed() {
        return millis > 0L;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static CleanReturn fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (CleanReturn choice : values()) {
            if (choice.fileValue.equals(value)) return choice;
        }
        return null;
    }
}
