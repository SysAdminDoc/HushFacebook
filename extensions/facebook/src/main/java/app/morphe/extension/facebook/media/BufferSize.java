/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import androidx.annotation.Nullable;

/**
 * How much of a video Video buffer has the player load before it plays and keep loaded ahead
 * (#526). {@link #FACEBOOK}, the default, leaves every number as Facebook's player works it out.
 * {@link #LOW} caps them, so videos start sooner on less, and the larger two only ever raise them,
 * so a number Facebook already sets higher stays Facebook's.
 */
public enum BufferSize {
    FACEBOOK("facebook", 0, 0, 0, 0),
    LOW("low", 500, 1_000, 5_000, 10_000),
    MEDIUM("medium", 1_500, 3_000, 15_000, 30_000),
    MAXIMUM("maximum", 3_000, 5_000, 30_000, 60_000);

    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;
    /** Milliseconds loaded before a video starts. */
    public final int startMs;
    /** Milliseconds loaded before a video plays again after it stalled. */
    public final int afterStallMs;
    /** Milliseconds ahead below which the player always loads more. */
    public final int aheadLowMs;
    /** Milliseconds ahead past which the player stops loading. */
    public final int aheadHighMs;

    BufferSize(String fileValue, int startMs, int afterStallMs, int aheadLowMs, int aheadHighMs) {
        this.fileValue = fileValue;
        this.startMs = startMs;
        this.afterStallMs = afterStallMs;
        this.aheadLowMs = aheadLowMs;
        this.aheadHighMs = aheadHighMs;
    }

    /** Whether this choice's numbers are a ceiling on Facebook's rather than a floor under them. */
    public boolean caps() {
        return this == LOW;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static BufferSize fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (BufferSize choice : values()) {
            if (choice.fileValue.equals(value)) return choice;
        }
        return null;
    }
}
