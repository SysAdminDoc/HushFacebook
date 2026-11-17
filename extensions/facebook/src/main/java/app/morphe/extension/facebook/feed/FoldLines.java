/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

/**
 * How many lines Fold long text lets a long post or comment show before See more (#497).
 * {@link #FACEBOOK}, the default, leaves the fold where Facebook puts it.
 */
public enum FoldLines {
    FACEBOOK("facebook", 0),
    ONE("1", 1),
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6),
    EIGHT("8", 8),
    TEN("10", 10),
    FIFTEEN("15", 15),
    TWENTY("20", 20);

    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;
    /** The lines a fold shows, or 0 for Facebook's own fold. */
    public final int lines;

    FoldLines(String fileValue, int lines) {
        this.fileValue = fileValue;
        this.lines = lines;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static FoldLines fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (FoldLines choice : values()) {
            if (choice.fileValue.equals(value)) return choice;
        }
        return null;
    }
}
