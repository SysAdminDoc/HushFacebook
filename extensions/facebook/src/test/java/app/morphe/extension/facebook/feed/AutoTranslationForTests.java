/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

/** Asks the translation hooks the way Facebook's post model, reels footer and comment body do. */
public final class AutoTranslationForTests {
    private AutoTranslationForTests() {
    }

    /** A stand-in for Facebook's GraphQLTranslatabilityType, whose constants keep their names. */
    public enum Translatability {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE,
        AUTO_TRANSLATION,
        HIDE_AUTO_TRANSLATION,
        NO_TRANSLATION,
        SEE_TRANSLATION,
    }

    /** A stand-in for the comment's translation type, in 585's order, whose constants keep their names. */
    public enum CommentTranslation {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE,
        AUTO_TRANSLATE,
        MACHINE_TRANSLATION,
        ORIGINAL,
    }

    /** True when a comment Facebook auto-translated is handed back as one shown as written. */
    public static boolean keepsTheComment() {
        return AutoTranslation.commentTranslation(CommentTranslation.AUTO_TRANSLATE) == CommentTranslation.ORIGINAL;
    }

    /** True when a post marked for automatic translation is handed back as one to translate on request. */
    public static boolean keepsThePost() {
        return AutoTranslation.translationType(Translatability.AUTO_TRANSLATION) == Translatability.SEE_TRANSLATION;
    }

    /** True when the reels footer is told a caption it would translate can't be. */
    public static boolean keepsTheCaption() {
        return !AutoTranslation.captionAutoTranslates(true);
    }
}
