/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The line under the poster's name keeps the post's date (issue #40). Facebook's newer post header
 * draws that line one of two ways: one line with the date and who can see the post, or, with its
 * rotating subtitle experiment on, a component that rotates the texts the header's subtitle plugins
 * gave the post, fitted into what's left of a width measured beforehand. On the reporter's phone the
 * date showed and the line went blank half a second later, with Hushfacebook paused too. With the
 * switch on, the header's answer to whether it rotates is a no, so every post gets the one line.
 *
 * <p>The same line can drop the song a post plays and show its time in bold (#533). Facebook's
 * subtitle plugins each ask whether they have something to show before they add a piece, so a song
 * answered as missing leaves no piece and no extra dot. The time is handed to the subtitle's model
 * once, as the model is made, and it's made bold there.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class PostDates {
    /** Counted under the patch's name each time a header that would rotate its line keeps the one line. */
    static final String ONE_LINE = "rotating subtitle kept to one line";

    /** The member the report names once a post header has made its choice. */
    static final String CHOICE = "subtitle choice";

    /** The member the report names once a post's song has been asked about (#533). */
    static final String SONG = "post song";

    /** Counted each time a post's song is left out of the line under its name. */
    static final String SONG_HIDDEN = "song left out of a post's subtitle";

    /** The member the report names once a post's time has gone through here (#533). */
    static final String TIME = "post time";

    /** Counted each time a post's time is made bold. */
    static final String TIME_BOLD = "post time made bold";

    private static final String FAMILY = FamilyNames.POST_DATES;

    private static volatile boolean logged;
    private static volatile boolean loggedSong;
    private static volatile boolean loggedTime;

    private PostDates() {
    }

    /** Whether this build can leave a post's song out of its subtitle; the patch answers true. */
    public static boolean songsInBuild() {
        return false;
    }

    /** Whether this build can show a post's time in bold; the patch answers true. */
    public static boolean timesInBuild() {
        return false;
    }

    /**
     * The hook, as the subtitle's song plugin starts working out the post's song. True while Hide
     * songs under names is on, and the plugin then answers that the post has none.
     */
    public static boolean hideSong() {
        try {
            HookStatus.bound(FAMILY, SONG);
            if (!Utils.settingsReady() || !Settings.HIDE_POST_SONGS.get()) return false;
            HookStatus.invoked(FAMILY);
            HookStatus.counted(FAMILY, SONG_HIDDEN);
            if (!loggedSong) {
                loggedSong = true;
                Logger.printDebug(() -> "Post dates: a post's song was left out of the line under its name");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SONG, failure);
            return false;
        }
    }

    /**
     * The hook, as the post header's subtitle model is handed the post's time ("15h", "November 3 at
     * 10:00"). With Bold post times on, answers the same text in bold, its own spans kept; otherwise
     * the text as it came.
     */
    @Nullable
    public static CharSequence boldTime(@Nullable CharSequence time) {
        try {
            HookStatus.bound(FAMILY, TIME);
            if (time == null || time.length() == 0) return time;
            if (!Utils.settingsReady() || !Settings.BOLD_POST_TIMES.get()) return time;
            SpannableString bold = new SpannableString(time);
            bold.setSpan(new StyleSpan(Typeface.BOLD), 0, bold.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            HookStatus.invoked(FAMILY);
            HookStatus.counted(FAMILY, TIME_BOLD);
            if (!loggedTime) {
                loggedTime = true;
                Logger.printDebug(() -> "Post dates: a post's time is shown in bold");
            }
            return bold;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, TIME, failure);
            return time;
        }
    }

    /**
     * The hook, right after the post header works out whether it rotates its subtitle, handed that
     * answer. Answers false while the switch is on, and Facebook's answer otherwise.
     */
    public static boolean cycling(boolean rotates) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, CHOICE);
            if (!rotates || !Utils.settingsReady() || !Settings.KEEP_POST_DATES.get()) return rotates;
            HookStatus.counted(FAMILY, ONE_LINE);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Post dates: a post header that would rotate its subtitle kept the one line with the date");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHOICE, failure);
            return rotates;
        }
    }
}
