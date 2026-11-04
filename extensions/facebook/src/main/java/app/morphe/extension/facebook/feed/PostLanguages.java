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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

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
 * <p>The classifier is a call into a system service, so the main thread never waits for it: there a
 * post the phone hasn't guessed yet stays, a worker guesses it, and the next time Facebook builds the
 * feed the guess is ready. A worker thread asks and waits for the answer.
 */
public final class PostLanguages {
    /** The posts the rule read, counted only while its switch is on, with what it decided as the kind. */
    static final String ROUTE = "Posts in other languages";
    /** What a post the rule hid counts under, on its route and on the feed's. */
    static final String REASON = "other language";
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

    interface Detector {
        /** The language of [text] as a language tag, when the phone is sure, otherwise null. */
        @Nullable
        String language(String text);
    }

    private static volatile Detector detector = PostLanguages::detectOnPhone;
    private static volatile boolean mainThreadWaitsForTests;

    /** The phone's guess by text, {@link #NO_GUESS} when it wasn't sure. */
    private static final Map<String, String> guesses = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > REMEMBERED;
                }
            });
    /** Texts a worker is guessing now, so a post built again before it's done doesn't ask twice. */
    private static final Set<String> guessing = Collections.synchronizedSet(new HashSet<>());

    private PostLanguages() {
    }

    /**
     * What the rule makes of a post with [texts]: {@link #REASON} when it goes, otherwise the reason it
     * stays. [stored] is the kept list as its row saves it; empty keeps the phone's language.
     */
    static String judge(List<String> texts, @Nullable String stored) {
        List<String> kept = kept(stored);
        boolean other = false;
        boolean waiting = false;
        boolean read = false;
        for (String text : texts) {
            String sample = sample(text);
            if (sample == null) continue;
            read = true;
            String language = guess(sample);
            if (language == null) {
                waiting = true;
            } else if (!language.isEmpty()) {
                if (isKept(language, kept)) return KEPT;
                other = true;
            }
        }
        if (waiting) return NOT_YET;
        if (other) return REASON;
        return read ? UNSURE : TOO_SHORT;
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

    /** The guess for [sample], {@link #NO_GUESS} when the phone isn't sure, or null while a worker guesses it. */
    @Nullable
    private static String guess(String sample) {
        String known = guesses.get(sample);
        if (known != null) return known;
        if (Utils.isCurrentlyOnMainThread() && !mainThreadWaitsForTests) {
            if (guessing.add(sample) && !Utils.runOnBackgroundThread(() -> guessOnWorker(sample))) {
                guessing.remove(sample);
            }
            return null;
        }
        return detect(sample);
    }

    private static void guessOnWorker(String sample) {
        try {
            detect(sample);
        } finally {
            guessing.remove(sample);
        }
    }

    /**
     * Asks the phone about [sample] and remembers its answer. A failed ask (the classifier threw, or
     * Facebook hasn't handed over its context yet) isn't remembered, so the text is asked about again
     * the next time instead of staying unguessed for good; this time the post stays.
     */
    private static String detect(String sample) {
        String found;
        try {
            found = detector.language(sample);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Post languages: no guess, " + failure.getClass().getSimpleName());
            return NO_GUESS;
        }
        String language = found == null ? NO_GUESS : found;
        guesses.put(sample, language);
        return language;
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

    /** For tests: the guesser to use, or null for the phone's, and whether the main thread waits for it. */
    static void detectorForTests(@Nullable Detector stand, boolean mainThreadWaits) {
        detector = stand == null ? PostLanguages::detectOnPhone : stand;
        mainThreadWaitsForTests = mainThreadWaits;
    }

    /** For tests: forgets every guess. */
    static void forgetForTests() {
        guesses.clear();
        guessing.clear();
    }
}
