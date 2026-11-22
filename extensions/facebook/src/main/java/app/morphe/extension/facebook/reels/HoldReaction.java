/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

/**
 * What Hold to react gives (#454): the reaction tray to pick from, by sliding to one or letting go
 * and tapping one, the tray to slide to one only, or one reaction straight away. {@link #index} is
 * the reaction's place in Facebook's tray, which has listed Like, Love, Care, Haha, Wow, Sad and
 * Angry in that order since Care came in.
 */
public enum HoldReaction {
    PICK("pick", -1),
    /** The tray, picked from by sliding only: letting go anywhere but on a reaction closes it. */
    SLIDE("slide", -1),
    LIKE("like", 0),
    LOVE("love", 1),
    CARE("care", 2),
    HAHA("haha", 3),
    WOW("wow", 4),
    SAD("sad", 5),
    ANGRY("angry", 6);

    /** What a settings file holds for this choice. It never changes once written. */
    public final String fileValue;
    /** Where the reaction sits in the tray, Like being 0, or -1 for the tray itself. */
    final int index;

    HoldReaction(String fileValue, int index) {
        this.fileValue = fileValue;
        this.index = index;
    }

    /** Whether the hold opens the tray to pick from, rather than giving one reaction. */
    public boolean picks() {
        return index < 0;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static HoldReaction fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (HoldReaction reaction : values()) {
            if (reaction.fileValue.equals(value)) return reaction;
        }
        return null;
    }
}
