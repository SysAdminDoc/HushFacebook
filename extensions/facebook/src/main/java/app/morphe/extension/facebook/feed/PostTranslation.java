/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.icu.util.ULocale;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextLanguage;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Translates posts in the feed (#332). Facebook builds a post's text in one method, which takes the
 * text and hands back what the post shows, cut with See more or not. {@link #text} goes at the top
 * of that method and {@link #shown} at each of its returns, so comments, which have a builder of
 * their own, stay as they are.
 *
 * <p>With the link switch on, a post in another language gets a Translate line under its text, and
 * a tap opens {@link PostTranslationBox}. Nothing leaves the phone before the tap. With the
 * by-themselves switch on, a post's text is swapped for its translation as it's built and gets See
 * original under it, which opens the same box. Layout may run on the main thread, which never
 * waits: its post goes out in its own language with the Translate line, and shows translated the
 * next time Facebook builds it. A worker thread waits {@link #WAIT_MILLIS} at most.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the post is Facebook's
 * own. Post text is never logged or counted.
 */
public final class PostTranslation {
    static final String HOOK = "post text builder";
    static final String LINK_ADDED = "Translate link added to a post";
    static final String SHOWN_TRANSLATED = "post shown translated";
    static final String FAILED = "translation failed";

    private static final String FAMILY = FamilyNames.TRANSLATE_POSTS;
    private static final String TAP = "translate link tap";
    private static final int FEWEST_LETTERS = 4;
    private static final int REMEMBERED = 300;
    private static final int MOST_AT_ONCE = 4;
    private static final int DETECT_LENGTH = 1000;
    private static final float CONFIDENT = 0.8f;
    private static final int LINK_ALPHA = 178;
    private static final int CONTEXT_DEPTH = 20;
    /** How long a worker thread holds a post back for its translation. */
    static final long WAIT_MILLIS = 1000;
    /** How long automatic requests stop after one failed, or after Google asked for fewer. */
    private static final long QUIET_AFTER_FAILURE_MILLIS = 30_000;
    private static final long QUIET_AFTER_LIMIT_MILLIS = 60_000;

    interface Translator {
        GoogleTranslate.Answer translate(String text, String target) throws IOException;
    }

    interface Detector {
        @Nullable
        String language(String text);
    }

    /** Hears how a request the box made went: the answer, or null when there isn't one. */
    interface Heard {
        void answered(@Nullable GoogleTranslate.Answer answer);
    }

    private static volatile Translator translator = GoogleTranslate::translate;
    private static volatile Detector detector = PostTranslation::detectOnPhone;

    /** Answers by target and text. */
    private static final Map<String, GoogleTranslate.Answer> answers = remembering();
    /** The phone's guess at a text's language, empty when it had none. */
    private static final Map<String, String> languages = remembering();
    /** Requests out now, by target and text. It is also the lock for their listeners. */
    private static final Map<String, Request> pending = new HashMap<>();
    /** What {@link #text} decided, for {@link #shown} at the same build's return. */
    private static final ThreadLocal<Build> building = new ThreadLocal<>();
    private static volatile long quietUntil;

    private PostTranslation() {
    }

    private static <V> Map<String, V> remembering() {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > REMEMBERED;
            }
        });
    }

    private static final class Request {
        final CountDownLatch done = new CountDownLatch(1);
        final List<Heard> listeners = new ArrayList<>();
        volatile GoogleTranslate.Answer answer;
    }

    private static final class Build {
        final String original;
        final boolean translated;

        Build(String original, boolean translated) {
            this.original = original;
            this.translated = translated;
        }
    }

    /** The hook at the top of Facebook's builder, handed the post's text. Answers the text to build with. */
    public static Spannable text(Spannable text) {
        building.remove();
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, HOOK);
            if (text == null || !Utils.settingsReady()) return text;
            boolean byThemselves = Settings.TRANSLATE_POSTS_BY_THEMSELVES.get();
            if (!byThemselves && !Settings.TRANSLATE_POST_LINK.get()) return text;
            String original = text.toString();
            if (!hasWords(original)) return text;
            String target = target();
            if (staysAsIs(languageOf(original), target)) return text;
            GoogleTranslate.Answer answer = answers.get(key(original, target));
            if (byThemselves && answer == null) answer = inTime(original, target);
            if (answer != null && staysAsIs(answer.from, target)) return text;
            if (byThemselves && answer != null && original.length() <= GoogleTranslate.LONGEST) {
                building.set(new Build(original, true));
                HookStatus.counted(FAMILY, SHOWN_TRANSLATED);
                return new SpannableString(answer.text);
            }
            building.set(new Build(original, false));
            return text;
        } catch (Throwable failure) {
            building.remove();
            HookStatus.threw(FAMILY, HOOK, failure);
            return text;
        }
    }

    /** The hook at each return of Facebook's builder, handed what the post would show. */
    public static Spannable shown(Spannable built) {
        Build build = building.get();
        if (build == null) return built;
        building.remove();
        try {
            if (built == null) return null;
            String label = build.translated ? L10n.t("See original") : L10n.t("Translate");
            SpannableStringBuilder out = new SpannableStringBuilder(built);
            out.append('\n');
            int start = out.length();
            out.append(label);
            out.setSpan(new Link(build.original, build.translated), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!build.translated) HookStatus.counted(FAMILY, LINK_ADDED);
            return out;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return built;
        }
    }

    /**
     * The line under a post. Litho's text draws it like See more and calls it when it's tapped. It
     * goes by the paint it's handed, so it reads as a quieter, bolder line in the post's own color
     * on a light or a dark feed.
     */
    private static final class Link extends ClickableSpan {
        private final String original;
        private final boolean translated;

        Link(String original, boolean translated) {
            this.original = original;
            this.translated = translated;
        }

        @Override
        public void onClick(View view) {
            try {
                Activity activity = activityOf(view == null ? null : view.getContext());
                if (activity == null) activity = Utils.getActivity();
                if (activity != null) PostTranslationBox.show(activity, original, translated);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, TAP, failure);
            }
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setUnderlineText(false);
            paint.setFakeBoldText(true);
            paint.setAlpha(LINK_ALPHA);
        }
    }

    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < CONTEXT_DEPTH; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** For the box, which asks whatever the quiet time says: tells [heard] on the main thread. */
    static void ask(String original, Heard heard) {
        String target = target();
        String key = key(original, target);
        GoogleTranslate.Answer known = answers.get(key);
        if (known != null) {
            heard.answered(known);
            return;
        }
        synchronized (pending) {
            Request out = pending.get(key);
            if (out != null) {
                out.listeners.add(heard);
                return;
            }
            start(key, original, target).listeners.add(heard);
        }
    }

    /** What's known for this text, waiting a moment for it off the main thread. Null when nothing came in time. */
    @Nullable
    private static GoogleTranslate.Answer inTime(String original, String target) {
        if (original.length() > GoogleTranslate.LONGEST || SystemClock.elapsedRealtime() < quietUntil) return null;
        Request request;
        synchronized (pending) {
            String key = key(original, target);
            request = pending.get(key);
            if (request == null) {
                if (pending.size() >= MOST_AT_ONCE) return null;
                request = start(key, original, target);
            }
        }
        if (Utils.isCurrentlyOnMainThread()) return null;
        try {
            return request.done.await(WAIT_MILLIS, TimeUnit.MILLISECONDS) ? request.answer : null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Sends the request off. The caller holds the lock on {@link #pending}. */
    private static Request start(String key, String original, String target) {
        Request request = new Request();
        pending.put(key, request);
        boolean accepted = Utils.runOnBackgroundThread(() -> request(key, original, target, request));
        if (!accepted) {
            // Nobody can be listening yet, so taking it back out is all there is to undo.
            pending.remove(key);
            quietUntil = SystemClock.elapsedRealtime() + QUIET_AFTER_FAILURE_MILLIS;
            request.done.countDown();
            Utils.runOnMainThread(() -> tell(request));
        }
        return request;
    }

    private static void request(String key, String original, String target, Request request) {
        try {
            GoogleTranslate.Answer answer = translator.translate(clipped(original), target);
            answers.put(key, answer);
            request.answer = answer;
        } catch (GoogleTranslate.Limited limited) {
            failed(limited, QUIET_AFTER_LIMIT_MILLIS);
        } catch (Throwable failure) {
            failed(failure, QUIET_AFTER_FAILURE_MILLIS);
        } finally {
            synchronized (pending) {
                pending.remove(key);
            }
            request.done.countDown();
            Utils.runOnMainThread(() -> tell(request));
        }
    }

    private static void failed(Throwable failure, long quietMillis) {
        quietUntil = SystemClock.elapsedRealtime() + quietMillis;
        HookStatus.counted(FAMILY, FAILED);
        Logger.printDebug(() -> "Post translation: the request failed, " + failure.getClass().getSimpleName());
    }

    private static void tell(Request request) {
        List<Heard> listeners;
        synchronized (pending) {
            listeners = new ArrayList<>(request.listeners);
            request.listeners.clear();
        }
        for (Heard heard : listeners) heard.answered(request.answer);
    }

    /** Text up to the longest one request carries, ending between words where it can. */
    private static String clipped(String text) {
        if (text.length() <= GoogleTranslate.LONGEST) return text;
        int cut = text.lastIndexOf(' ', GoogleTranslate.LONGEST);
        return text.substring(0, cut > 0 ? cut : GoogleTranslate.LONGEST);
    }

    private static String key(String text, String target) {
        return target + '\n' + text;
    }

    private static String target() {
        String chosen = Settings.TRANSLATE_TO.get();
        return chosen.isEmpty() ? TranslateLanguages.phone() : chosen;
    }

    /** Whether a post in [language] stays as it is: it's in the language wanted, or one to leave alone. */
    private static boolean staysAsIs(@Nullable String language, String target) {
        if (language == null || language.isEmpty()) return false;
        if (TranslateLanguages.same(language, target)) return true;
        for (String skipped : Settings.TRANSLATE_LEAVE_ALONE.get().split(",")) {
            if (TranslateLanguages.same(language, skipped)) return true;
        }
        return false;
    }

    /** The phone's guess, once there is one. The main thread asks a worker and carries on without it. */
    @Nullable
    private static String languageOf(String text) {
        String known = languages.get(text);
        if (known != null) return known;
        if (Utils.isCurrentlyOnMainThread()) {
            Utils.runOnBackgroundThread(() -> detect(text));
            return null;
        }
        return detect(text);
    }

    private static String detect(String text) {
        String found = null;
        try {
            found = detector.language(text);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Post translation: no language guess, " + failure.getClass().getSimpleName());
        }
        String language = found == null ? "" : found;
        languages.put(text, language);
        return language;
    }

    /** What the phone's own classifier makes of the text, when it's sure. Nothing leaves the phone. */
    @Nullable
    private static String detectOnPhone(String text) {
        Context context = Utils.getContext();
        TextClassificationManager manager = context == null ? null : context.getSystemService(TextClassificationManager.class);
        if (manager == null) return null;
        String sample = text.length() > DETECT_LENGTH ? text.substring(0, DETECT_LENGTH) : text;
        TextLanguage guess = manager.getTextClassifier().detectLanguage(new TextLanguage.Request.Builder(sample).build());
        if (guess.getLocaleHypothesisCount() == 0) return null;
        ULocale best = guess.getLocale(0);
        return guess.getConfidenceScore(best) >= CONFIDENT ? best.toLanguageTag() : null;
    }

    /** Whether the text has a few letters, which a post of only emoji, numbers or a link's dots doesn't. */
    private static boolean hasWords(String text) {
        int letters = 0;
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetter(text.charAt(index)) && ++letters >= FEWEST_LETTERS) return true;
        }
        return false;
    }

    /** For tests: the provider to use, or null for Google. */
    static void translatorForTests(@Nullable Translator stand) {
        translator = stand == null ? GoogleTranslate::translate : stand;
    }

    /** For tests: the guesser to use, or null for the phone's. */
    static void detectorForTests(@Nullable Detector stand) {
        detector = stand == null ? PostTranslation::detectOnPhone : stand;
    }

    /** For tests: whether any request is still out. */
    static boolean requestsOutForTests() {
        synchronized (pending) {
            return !pending.isEmpty();
        }
    }

    /** For tests: forgets every answer, guess and quiet time. */
    static void forgetForTests() {
        answers.clear();
        languages.clear();
        synchronized (pending) {
            pending.clear();
        }
        building.remove();
        quietUntil = 0L;
    }
}
