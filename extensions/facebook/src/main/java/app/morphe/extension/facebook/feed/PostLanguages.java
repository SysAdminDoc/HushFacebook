/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.content.Context;
import android.icu.util.ULocale;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextLanguage;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;

/**
 * Hide posts in other languages (#382). The feed guard hands {@link #judge} the words the word
 * filter reads of a post (its own text, the post it shares and its link cards), and the phone's own
 * text classifier guesses each one's language. Nothing leaves the phone, and no text is logged or
 * counted, only what the rule decided.
 *
 * <p>A post goes only when the phone is sure of at least one of its texts and every text it's sure of
 * is in a language that isn't kept. A text in a kept language keeps the post, so a Hindi caption on a
 * link with an English headline stays when either is kept. A text with too few letters to read
 * (emoji, a number, a link) and one the classifier isn't sure of don't count either way.
 *
 * <p>The classifier is a call into a system service. Facebook builds each post into the feed once,
 * on the main thread, so there every text's guess starts on a worker at once and the main thread
 * waits briefly for them, within a per-second budget. A post whose guess isn't back by then stays.
 * A worker thread asks and waits for the answer.
 */
public final class PostLanguages {
    /** The posts the rule read, counted only while its switch is on, with what it decided as the kind. */
    static final String ROUTE = "Posts in other languages";
    /** What a post the rule hid counts under, on its route and on the feed's, and a reel on the Reels tab's. */
    public static final String REASON = "other language";
    static final String KEPT = "kept language";
    static final String UNSURE = "language not sure";
    static final String TOO_SHORT = "too few letters";
    static final String NOT_YET = "language not guessed yet";

    private static final int FEWEST_LETTERS = 4;
    private static final int DETECT_LENGTH = 1000;
    private static final float CONFIDENT = 0.8f;
    private static final int REMEMBERED = 300;
    /** A guess the phone wasn't sure of, as the memory of guesses holds it. */
    private static final String NO_GUESS = "";

    /** Every guess the phone made, with how long it took as the kind. */
    static final String GUESS_ROUTE = "Post language guesses";
    /**
     * The main thread waits at most this long for one post's guesses, and at most the budget over
     * each window, so a slow classifier costs the feed a few dropped frames rather than a stall.
     */
    private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(60);
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long WINDOW_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    /** At most this many guesses run at once, so a stuck classifier holds only two of the shared workers. */
    private static final int MOST_PENDING = 2;

    interface Detector {
        /** The language of [text] as a language tag, when the phone is sure, otherwise null. */
        @Nullable
        String language(String text);
    }

    private static volatile Detector detector = PostLanguages::detectOnPhone;
    private static volatile boolean mainThreadWaitsForTests;
    private static volatile long waitNanos = WAIT_NANOS;
    private static volatile long windowBudgetNanos = WINDOW_BUDGET_NANOS;

    /** The phone's guess by text, {@link #NO_GUESS} when it wasn't sure. */
    private static final Map<String, String> guesses = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > REMEMBERED;
                }
            });
    /** The guesses workers are making now, by text, so a post built again before one's done doesn't ask twice. */
    private static final Map<String, Future<String>> pending = new ConcurrentHashMap<>();

    /** The main thread's waits in the current window, which {@link #WINDOW_BUDGET_NANOS} caps. */
    private static long windowStartNanos;
    private static long waitedNanos;

    private PostLanguages() {
    }

    /**
     * What the rule makes of a post with [texts]: {@link #REASON} when it goes, otherwise the reason it
     * stays. [stored] is the kept list as its row saves it; empty keeps the phone's language.
     */
    static String judge(List<String> texts, @Nullable String stored) {
        List<String> kept = kept(stored);
        List<String> samples = new ArrayList<>(texts.size());
        for (String text : texts) {
            String sample = sample(text);
            if (sample != null) samples.add(sample);
        }
        if (samples.isEmpty()) return TOO_SHORT;

        boolean mainThread = Utils.isCurrentlyOnMainThread() && !mainThreadWaitsForTests;
        // Every text a worker has to guess starts now, so the main thread's waits overlap.
        if (mainThread) {
            for (String sample : samples) start(sample);
        }
        boolean other = false;
        boolean waiting = false;
        for (String sample : samples) {
            String language = mainThread ? awaitBriefly(sample) : guessHere(sample);
            if (language == null) {
                waiting = true;
            } else if (!language.isEmpty()) {
                if (isKept(language, kept)) return KEPT;
                other = true;
            }
        }
        if (waiting) return NOT_YET;
        return other ? REASON : UNSURE;
    }

    /**
     * What the rule makes of [story]: the words the word filter reads of it, judged against the kept
     * list, or why none were read. For the Reels tab, whose reels keep their caption in such a story.
     */
    public static String storyKind(@Nullable Object story) {
        return storyKind(story, PostText.MESSAGE, PostText.ATTACHED, PostSources.ATTACHMENTS, PostText.ACTION_LINKS);
    }

    static String storyKind(@Nullable Object story, StoryFlag.Accessor message, StoryFlag.Accessor attached,
            StoryFlag.Accessor links, StoryFlag.Accessor actionLinks) {
        PostText.Read read = PostText.read(story, message, attached, links, actionLinks);
        if (read.outcome != PostText.Outcome.TEXT) return read.outcome.reason;
        return judge(read.texts, Settings.KEPT_LANGUAGES.get());
    }

    /** The languages kept: the stored codes, or the phone's language when none are stored. */
    static List<String> kept(@Nullable String stored) {
        List<String> kept = new ArrayList<>();
        if (stored != null) {
            for (String code : stored.split(",")) {
                String trimmed = code.trim();
                if (!trimmed.isEmpty()) kept.add(trimmed);
            }
        }
        if (kept.isEmpty()) kept.add(TranslateLanguages.phone());
        return kept;
    }

    private static boolean isKept(String language, List<String> kept) {
        for (String code : kept) {
            if (TranslateLanguages.same(language, code)) return true;
        }
        return false;
    }

    /** The start of [text] the phone reads, or null when it has too few letters to guess from. */
    @Nullable
    private static String sample(@Nullable String text) {
        if (text == null) return null;
        String sample = text.length() > DETECT_LENGTH ? text.substring(0, DETECT_LENGTH) : text;
        int letters = 0;
        for (int index = 0; index < sample.length(); index++) {
            if (Character.isLetter(sample.charAt(index)) && ++letters >= FEWEST_LETTERS) return sample;
        }
        return null;
    }

    /** The guess for [sample] off the main thread: remembered, or asked here and waited for. */
    private static String guessHere(String sample) {
        String known = guesses.get(sample);
        return known != null ? known : detect(sample);
    }

    /**
     * A worker's guess at [sample], started now unless one is already running. Null when the guess
     * is remembered already, {@link #MOST_PENDING} guesses are running or no worker could take it.
     */
    @Nullable
    private static Future<String> start(String sample) {
        Future<String> running = pending.get(sample);
        if (running != null || guesses.containsKey(sample) || pending.size() >= MOST_PENDING) return running;
        // The guess stays pending until its answer is set, so a failed ask (which isn't remembered)
        // can't be started a second time while it's finishing.
        FutureTask<String> task = new FutureTask<String>(() -> detect(sample)) {
            @Override
            protected void done() {
                pending.remove(sample, this);
            }
        };
        running = pending.putIfAbsent(sample, task);
        if (running != null) return running;
        if (!Utils.runOnBackgroundThread(task)) {
            pending.remove(sample, task);
            return null;
        }
        return task;
    }

    /**
     * The guess for [sample] on the main thread: remembered, or a worker's when it's ready within
     * the wait this pass allows, otherwise null and the post stays. Facebook builds each post into
     * the feed once, so a guess that's ready only afterwards is never used for it.
     */
    @Nullable
    private static String awaitBriefly(String sample) {
        String known = guesses.get(sample);
        if (known != null) return known;
        Future<String> running = start(sample);
        if (running == null) return guesses.get(sample);
        long began = System.nanoTime();
        try {
            return running.get(allowedWaitNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failed) {
            return NO_GUESS;
        } finally {
            waited(System.nanoTime() - began);
        }
    }

    /** How long the main thread may wait now: one wait's cap, less what this window already spent. */
    private static synchronized long allowedWaitNanos() {
        long now = System.nanoTime();
        if (now - windowStartNanos >= WINDOW_NANOS) {
            windowStartNanos = now;
            waitedNanos = 0;
        }
        return Math.max(0, Math.min(waitNanos, windowBudgetNanos - waitedNanos));
    }

    private static synchronized void waited(long nanos) {
        waitedNanos += nanos;
    }

    /**
     * Asks the phone about [sample] and remembers its answer. A failed ask (the classifier threw, or
     * Facebook hasn't handed over its context yet) isn't remembered, so the text is asked about again
     * the next time instead of staying unguessed for good; this time the post stays. How long each
     * ask took is counted on {@link #GUESS_ROUTE}.
     */
    private static String detect(String sample) {
        String found;
        long began = System.nanoTime();
        try {
            found = detector.language(sample);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Post languages: no guess, " + failure.getClass().getSimpleName());
            return NO_GUESS;
        } finally {
            countGuessTime(System.nanoTime() - began);
        }
        String language = found == null ? NO_GUESS : found;
        guesses.put(sample, language);
        return language;
    }

    private static void countGuessTime(long nanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        String bucket = millis < 10 ? "under 10 ms" : millis < 30 ? "10 to 30 ms" : millis < 60 ? "30 to 60 ms"
                : millis < 200 ? "60 to 200 ms" : "200 ms or more";
        FeedFilterCounters.sawList(GUESS_ROUTE, 1);
        FeedFilterCounters.sawKind(GUESS_ROUTE, bucket);
    }

    /** What the phone's own classifier makes of the text, when it's sure. Nothing leaves the phone. */
    @Nullable
    private static String detectOnPhone(String sample) {
        Context context = Utils.getContext();
        TextClassificationManager manager = context == null ? null : context.getSystemService(TextClassificationManager.class);
        if (manager == null) throw new IllegalStateException("no text classifier yet");
        TextLanguage guess = manager.getTextClassifier().detectLanguage(new TextLanguage.Request.Builder(sample).build());
        if (guess.getLocaleHypothesisCount() == 0) return null;
        ULocale best = guess.getLocale(0);
        return guess.getConfidenceScore(best) >= CONFIDENT ? best.toLanguageTag() : null;
    }

    /**
     * For tests: the guesser to use, or null for the phone's, and whether the main thread asks it
     * itself and waits however long it takes, as a worker does.
     */
    static void detectorForTests(@Nullable Detector stand, boolean mainThreadWaits) {
        detector = stand == null ? PostLanguages::detectOnPhone : stand;
        mainThreadWaitsForTests = mainThreadWaits;
    }

    /**
     * For tests: how long the main thread may wait for one post and over each window, so a test of
     * a quick guess doesn't depend on how fast a loaded machine starts a worker.
     */
    static void waitForTests(long millis) {
        waitNanos = TimeUnit.MILLISECONDS.toNanos(millis);
        windowBudgetNanos = waitNanos;
    }

    /** For tests: forgets every guess and the main thread's waits so far, and restores the waits. */
    static void forgetForTests() {
        guesses.clear();
        pending.clear();
        waitNanos = WAIT_NANOS;
        windowBudgetNanos = WINDOW_BUDGET_NANOS;
        synchronized (PostLanguages.class) {
            windowStartNanos = 0;
            waitedNanos = 0;
        }
    }
}
