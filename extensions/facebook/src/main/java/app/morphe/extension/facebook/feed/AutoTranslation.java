/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Posts, comments and reel captions stay in the language they were written in.
 *
 * <p>Facebook marks each post with how it may be translated. A post marked AUTO_TRANSLATION gets
 * its translated text, with a "See original" link, and one marked SEE_TRANSLATION gets its own
 * text with a "See translation" link. While the switch is on, the getter Facebook reads that mark
 * through answers SEE_TRANSLATION where it would answer AUTO_TRANSLATION, so the post keeps its
 * words and the link to translate it stays.
 *
 * <p>A reel's caption is translated on the phone's request: the reels footer asks Facebook for a
 * translation when the reel says its caption can be. While the switch is on, that answer is no, so
 * the request isn't made.
 *
 * <p>A comment comes with its own translation type and, when Facebook translated it, the
 * translated text. The comment body starts on that text unless the type is ORIGINAL (#372). While
 * the switch is on, the type it reads is ORIGINAL, so the comment starts in its own words and a tap
 * on See translation asks Facebook for the translation as it does for any other comment.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class AutoTranslation {
    /** Counted each time a post marked for automatic translation is shown with its own text. */
    static final String POST_KEPT = "post kept in its own language";

    /** Counted each time the reels footer is told a caption can't be translated by itself. */
    static final String CAPTION_KEPT = "reel caption kept in its own language";

    /** The member the report names once the translation type getter has run. */
    static final String TYPE = "translation type";

    /** The member the report names once the reels footer has asked about a caption. */
    static final String CAPTION = "reel caption translation";

    /** Counted each time a comment Facebook translated is shown in its own words. */
    static final String COMMENT_KEPT = "comment kept in its own language";

    /** The member the report names once the comment body has read a comment's translation type. */
    static final String COMMENT = "comment translation type";

    /** Facebook's GraphQLTranslatabilityType constants, kept as the enum's names. */
    static final String AUTO_TRANSLATION = "AUTO_TRANSLATION";
    static final String SEE_TRANSLATION = "SEE_TRANSLATION";

    /** The comment translation type's constant for a comment shown as it was written. */
    static final String ORIGINAL = "ORIGINAL";

    private static final String FAMILY = FamilyNames.AUTO_TRANSLATION;

    /** SEE_TRANSLATION of the last enum class asked about. Facebook has one, so it's found once. */
    @Nullable
    private static volatile Enum<?> seeTranslation;

    /** ORIGINAL of the comment translation type, found the first time a comment asks. */
    @Nullable
    private static volatile Enum<?> original;

    private static volatile boolean loggedPost;
    private static volatile boolean loggedCaption;
    private static volatile boolean loggedComment;

    private AutoTranslation() {
    }

    /**
     * The hook, as the post translatability model hands back its translation type. Answers
     * SEE_TRANSLATION of the same enum for AUTO_TRANSLATION while the switch is on, and [type]
     * otherwise.
     */
    @Nullable
    public static Enum<?> translationType(@Nullable Enum<?> type) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, TYPE);
            if (type == null || !AUTO_TRANSLATION.equals(type.name())) return type;
            if (!Utils.settingsReady() || !Settings.TURN_OFF_AUTO_TRANSLATION.get()) return type;
            Enum<?> see = seeTranslationOf(type);
            if (see == null) return type;
            HookStatus.counted(FAMILY, POST_KEPT);
            if (!loggedPost) {
                loggedPost = true;
                Logger.printDebug(() -> "Auto-translation: a post marked for automatic translation keeps its own text");
            }
            return see;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, TYPE, failure);
            return type;
        }
    }

    /**
     * The hook, as the reels footer reads whether the reel's caption can be translated by itself
     * before it asks Facebook for that translation. Answers false while the switch is on, and
     * [translatable] otherwise.
     */
    public static boolean captionAutoTranslates(boolean translatable) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, CAPTION);
            if (!translatable || !Utils.settingsReady() || !Settings.TURN_OFF_AUTO_TRANSLATION.get()) {
                return translatable;
            }
            HookStatus.counted(FAMILY, CAPTION_KEPT);
            if (!loggedCaption) {
                loggedCaption = true;
                Logger.printDebug(() -> "Auto-translation: a reel caption keeps its own text");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CAPTION, failure);
            return translatable;
        }
    }

    /**
     * The hook, as the comment body reads a comment's translation type, for its starting text and
     * for its See translation or See original link. Answers ORIGINAL of the same enum for any other
     * type while the switch is on, and [type] otherwise.
     */
    @Nullable
    public static Enum<?> commentTranslation(@Nullable Enum<?> type) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, COMMENT);
            if (type == null || ORIGINAL.equals(type.name())) return type;
            if (!Utils.settingsReady() || !Settings.TURN_OFF_AUTO_TRANSLATION.get()) return type;
            Enum<?> kept = originalOf(type);
            if (kept == null) return type;
            HookStatus.counted(FAMILY, COMMENT_KEPT);
            if (!loggedComment) {
                loggedComment = true;
                Logger.printDebug(() -> "Auto-translation: a comment Facebook translated (" + type.name()
                        + ") keeps its own text");
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, COMMENT, failure);
            return type;
        }
    }

    /** ORIGINAL from the enum [type] belongs to, or null when it has none. */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> originalOf(Enum<?> type) {
        Class kind = type.getDeclaringClass();
        Enum<?> cached = original;
        if (cached != null && cached.getDeclaringClass() == kind) return cached;
        try {
            Enum<?> found = Enum.valueOf(kind, ORIGINAL);
            original = found;
            return found;
        } catch (IllegalArgumentException missing) {
            HookStatus.missingMember(FAMILY, "enum constant", kind.getName(), ORIGINAL);
            return null;
        }
    }

    /** SEE_TRANSLATION from the enum [type] belongs to, or null when it has none. */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> seeTranslationOf(Enum<?> type) {
        Class kind = type.getDeclaringClass();
        Enum<?> cached = seeTranslation;
        if (cached != null && cached.getDeclaringClass() == kind) return cached;
        try {
            Enum<?> found = Enum.valueOf(kind, SEE_TRANSLATION);
            seeTranslation = found;
            return found;
        } catch (IllegalArgumentException missing) {
            HookStatus.missingMember(FAMILY, "enum constant", kind.getName(), SEE_TRANSLATION);
            return null;
        }
    }
}
