/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import androidx.annotation.Nullable;

/**
 * Mark new settings: how long the dot beside a setting an update added stays ({@link NewRowDots},
 * #16). Until the page is left is how the dot has always worked, so it's the default.
 */
public enum NewRowMark {
    OFF(-1, "off"),
    ONE_VISIT(0, "visit"),
    THREE_DAYS(3, "3d"),
    SEVEN_DAYS(7, "7d");

    /** Days a dot stays from the first visit to its page: 0 for that visit alone, below 0 for no dot. */
    public final int days;
    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;

    NewRowMark(int days, String fileValue) {
        this.days = days;
        this.fileValue = fileValue;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static NewRowMark fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (NewRowMark mark : values()) {
            if (mark.fileValue.equals(value)) return mark;
        }
        return null;
    }
}
