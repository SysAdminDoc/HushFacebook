/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The diagnostic report's look at video starts (#602): the last starts Facebook asked its players
 * for, newest first, each with its trigger, the player's origin and whether it played or
 * Hushfacebook held it, and what Facebook's Autoplay setting last answered. When feed videos stop
 * starting by themselves, a report sent right then tells a start Hushfacebook held from one
 * Facebook never asked for. Kept whatever the switches say and with Debug logging off, since the
 * moment it's needed can't be planned. An origin is a surface name; no video id or text is kept.
 */
public final class VideoStarts {
    /** How many starts the report lists. */
    static final int KEPT = 24;
    static final String PLAYED = "played";
    static final String HELD_TAP = "held by Tap to play";
    static final String HELD_COMMENTS = "held while comments are open";
    static final String OLDER_PLAYER = "older player";

    private static final ArrayDeque<Start> STARTS = new ArrayDeque<>();
    private static int seen;
    private static int held;
    @Nullable
    private static String autoplay;
    private static long autoplayAt;

    /** The report's section; empty, and so left out, before any start or Autoplay read. */
    public static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "VIDEO STARTS"; }
        @Override public List<String> lines() { return report(SystemClock.elapsedRealtime()); }
        @Override public boolean isAppState() { return true; }
    };

    private static final class Start {
        final long at;
        final String trigger;
        final String origin;
        final String answer;

        Start(long at, String trigger, String origin, String answer) {
            this.at = at;
            this.trigger = trigger;
            this.origin = origin;
            this.answer = answer;
        }
    }

    private VideoStarts() {
    }

    /** Notes a start of [player] Facebook asked for with [trigger], and [answer], what came of it. Never throws. */
    static void started(@Nullable Object player, @Nullable Object trigger, String answer) {
        try {
            String origin;
            if (player == null) {
                origin = "no player";
            } else {
                Object read = TapToPlay.origins.of(player);
                origin = read != null ? read.toString() : "no origin";
            }
            note(nameOf(trigger), origin, answer, SystemClock.elapsedRealtime());
        } catch (Throwable unreadable) {
            note(nameOf(trigger), "origin unreadable", answer, SystemClock.elapsedRealtime());
        }
    }

    /** Notes a start of the older Rich Video Player, which has no origin to read. */
    static void startedOlder(@Nullable Object trigger, String answer) {
        note(nameOf(trigger), OLDER_PLAYER, answer, SystemClock.elapsedRealtime());
    }

    /** Notes what Facebook's Autoplay setting answered, [chosen], and [given], what the hook gave back. Never throws. */
    static synchronized void autoplayRead(@Nullable Object chosen, @Nullable Object given) {
        try {
            String read = nameOf(chosen);
            String back = nameOf(given);
            autoplay = read.equals(back) ? read : read + ", answered " + back;
            autoplayAt = SystemClock.elapsedRealtime();
        } catch (Throwable unreadable) {
            autoplay = "unreadable";
            autoplayAt = SystemClock.elapsedRealtime();
        }
    }

    static synchronized void note(String trigger, String origin, String answer, long now) {
        seen++;
        if (!PLAYED.equals(answer)) held++;
        STARTS.addFirst(new Start(now, trigger, origin, answer));
        while (STARTS.size() > KEPT) STARTS.removeLast();
    }

    static synchronized List<String> report(long now) {
        if (seen == 0 && autoplay == null) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        lines.add(autoplay == null
                ? "Facebook's Autoplay setting: not read yet"
                : "Facebook's Autoplay setting: " + autoplay + ", read " + ago(now, autoplayAt));
        lines.add("Starts asked for: " + seen + ", held by Hushfacebook: " + held);
        if (STARTS.isEmpty()) return lines;
        lines.add("Last starts, newest first:");
        for (Start start : STARTS) {
            lines.add("  " + ago(now, start.at) + ", " + start.trigger + ", " + start.origin + ", " + start.answer);
        }
        return lines;
    }

    private static String ago(long now, long at) {
        long seconds = Math.max(0, now - at) / 1000;
        if (seconds < 120) return seconds + " s ago";
        return (seconds / 60) + " min ago";
    }

    private static String nameOf(@Nullable Object value) {
        if (value == null) return "none";
        if (value instanceof Enum) return ((Enum<?>) value).name();
        return value.toString();
    }

    /** Forgets every start and the Autoplay read. For tests. */
    static synchronized void forget() {
        STARTS.clear();
        seen = 0;
        held = 0;
        autoplay = null;
        autoplayAt = 0;
    }
}
