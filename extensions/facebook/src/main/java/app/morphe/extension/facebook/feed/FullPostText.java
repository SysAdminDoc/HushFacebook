/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.text.SpannableString;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A post's text shows whole in the feed, with no "See more" to tap (issue #268), and a tap on it
 * folds it back to See more (#354). Facebook cuts a story's message to a character and line budget
 * as it builds the text, and hands back the cut text with "See more" on the end, or nothing when
 * the text fits. The builder then shows the cut text when there is one and the whole text
 * otherwise. With the switch on, the cut comes back as nothing, so the builder takes its whole-text
 * path.
 *
 * <p>The feed's post component (FeedStoryContentWithExtensionComponent) keeps an "allow truncation"
 * state per post: it starts true, and a tap
 * on the text flips it, which is how stock Facebook expands a post in place and folds it again. With
 * the switch on it starts false, so a post opens whole by Facebook's own path, and the first tap on
 * the text folds it. A post the reader folded is remembered by its story id, and its cut is let
 * through until a tap opens it again.
 *
 * <p>A reel's caption (FbShortsViewerDescriptionComponent) keeps an "expanded" state that "more"
 * flips. Its first value goes through the extension too, so with Show full reel captions on a long
 * caption starts whole, and a tap folds it; Facebook keeps that per reel by itself (#268). That
 * switch is apart from the feed's (#398).
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the cut and the state
 * are Facebook's own.
 */
public final class FullPostText {
    /** Counted under the patch's name each time a post's text that Facebook cut shows whole. */
    static final String KEPT_WHOLE = "post text kept whole";

    /** Counted each time a post the reader folded keeps Facebook's cut. */
    static final String KEPT_FOLDED = "post text folded by a tap";

    /** The member the report names once a post's text has gone through the cutter. */
    static final String CUTTER = "post text cut";

    /** The member the report names once a post's first state has gone through here. */
    static final String FIRST_STATE = "post text first state";

    /** The member the report names once a tap on a post's text has gone through here. */
    static final String TAP = "post text tap";

    /** The member the report names once a reel caption's first state has gone through here. */
    static final String REEL_CAPTION = "reel caption first state";

    /** Counted each time a reel caption that would start cut starts whole. */
    static final String REEL_CAPTION_WHOLE = "reel caption opened whole";

    /** The key a story's id is cached under on its GraphQL model (Facebook's own truncation map reads it, 585). */
    static final int STORY_ID = 0xe6297af8;

    /** How many folded posts are remembered; the oldest goes first. */
    static final int MAX_FOLDED = 300;

    private static final String FAMILY = FamilyNames.FULL_POST_TEXT;

    /** Posts the reader folded with the switch on, by story id (or the story itself when it has none). */
    private static final Set<Object> folded = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<Object, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Boolean> eldest) {
                    return size() > MAX_FOLDED;
                }
            }));

    private static volatile Method idReader;

    private static volatile boolean logged;

    private static volatile boolean reelLogged;

    private FullPostText() {
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.SHOW_FULL_POST_TEXT.get();
    }

    /** Reel captions have a switch of their own (#398). */
    private static boolean reelsOn() {
        return Utils.settingsReady() && Settings.SHOW_FULL_REEL_CAPTIONS.get();
    }

    /**
     * The hook, right after Facebook cuts a post's text, handed the cut text, or null when the text
     * fit, and the post's story. Answers null while the switch is on, so the whole text shows, unless
     * the reader folded this post. Otherwise the cut.
     */
    public static SpannableString cut(SpannableString cut, Object story) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, CUTTER);
            if (cut == null || !on()) return cut;
            if (folded.contains(key(story))) {
                HookStatus.counted(FAMILY, KEPT_FOLDED);
                return cut;
            }
            HookStatus.counted(FAMILY, KEPT_WHOLE);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Full post text: a post's text Facebook cut for See more shows whole");
            }
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CUTTER, failure);
            return cut;
        }
    }

    /**
     * The hook on a feed post's first state, handed Facebook's "allow truncation" (true). With the
     * switch on the post starts whole, so the first tap on its text folds it.
     */
    public static boolean allowTruncation(boolean allow) {
        try {
            HookStatus.bound(FAMILY, FIRST_STATE);
            if (!allow || !on()) return allow;
            // A post that starts whole never reaches the cutter, so this is where the switch shows it ran.
            HookStatus.invoked(FAMILY);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, FIRST_STATE, failure);
            return allow;
        }
    }

    /**
     * The hook on a reel caption's first state, handed the "expanded" Facebook starts it with before
     * its own per-reel memory is read. With the switch on it starts expanded, so a long caption shows
     * whole, and a tap folds it by Facebook's path, which Facebook then remembers for that reel.
     */
    public static boolean reelCaptionExpanded(boolean expanded) {
        try {
            HookStatus.bound(FAMILY, REEL_CAPTION);
            if (expanded || !reelsOn()) return expanded;
            HookStatus.invoked(FAMILY);
            HookStatus.counted(FAMILY, REEL_CAPTION_WHOLE);
            if (!reelLogged) {
                reelLogged = true;
                Logger.printDebug(() -> "Full post text: a reel caption starts whole");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, REEL_CAPTION, failure);
            return expanded;
        }
    }

    /**
     * The hook on a tap that flips a feed post between cut and whole, handed the post's story and
     * the new "allow truncation": true when the tap folded it, false when it opened it.
     */
    public static void truncation(Object story, boolean allow) {
        try {
            HookStatus.bound(FAMILY, TAP);
            if (!on()) return;
            Object key = key(story);
            if (key == null) return;
            if (allow) folded.add(key);
            else folded.remove(key);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, TAP, failure);
        }
    }

    /** The story's id, or the story itself when it has none or it can't be read. */
    static Object key(Object story) {
        if (story == null) return null;
        try {
            Method reader = idReader;
            if (reader == null || !reader.getDeclaringClass().isInstance(story)) {
                reader = story.getClass().getMethod("getCachedString", int.class);
                idReader = reader;
            }
            Object id = reader.invoke(story, STORY_ID);
            if (id instanceof String && !((String) id).isEmpty()) return id;
        } catch (Throwable failure) {
            // A story model without the reader; the story itself is the key.
        }
        return story;
    }

    static void forgetForTests() {
        folded.clear();
        idReader = null;
    }
}
