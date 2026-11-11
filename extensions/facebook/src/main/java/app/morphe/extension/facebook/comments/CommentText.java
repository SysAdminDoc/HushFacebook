/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A comment's own words: {@code body.text} on a GraphQLComment, as the person typed it.
 *
 * <p>Read through members Facebook keeps, the way its own body accessor reads them:
 * {@code BaseModelWithTree.getCachedModel} with the body's key, TextWithEntities' class and its
 * type tag, then {@code getCachedString} with the text's key. A tree model keys a field by its
 * GraphQL name's hash code (582's accessor asks for {@code 0x2e39a2}, "body"), and tags a model
 * with the first four bytes of its type name's MD5, which for TextWithEntities is the
 * {@code 0xdb1d8904} the accessor passes.
 *
 * <p>Nothing here keeps, logs or reports the text.
 */
final class CommentText {
    static final String COMMENT_CLASS = "com.facebook.graphql.model.GraphQLComment";
    static final String TEXT_CLASS = "com.facebook.graphql.model.GraphQLTextWithEntities";
    static final String TREE_MODEL_CLASS = "com.facebook.graphql.modelutil.BaseModelWithTree";
    static final int BODY_KEY = "body".hashCode();
    static final int TEXT_KEY = "text".hashCode();
    static final int TEXT_TYPE_TAG = 0xdb1d8904;

    private static final String FAMILY = FamilyNames.COMMENT_SHEET_OPTIONS;

    private CommentText() {
    }

    /**
     * Whether a comment's words have been read yet. Until then a missing body reads as out of
     * reach, so a build that moved the body's key keeps every comment.
     */
    static volatile boolean wordsRead;

    /**
     * The words of [comment]: empty when it has no body, as a comment that's only a GIF or a
     * sticker, and null when it isn't a GraphQLComment, or a member this build lacks or a read that
     * failed keeps them out of reach. Never throws.
     */
    @Nullable
    static String body(@Nullable Object comment) {
        if (comment == null) return null;
        Members found = members();
        report(found);
        if (!found.complete() || !found.comment.isInstance(comment)) return null;
        try {
            Object body = found.cachedModel.invoke(comment, BODY_KEY, found.text, TEXT_TYPE_TAG);
            if (body == null) return wordsRead ? "" : null;
            if (!found.text.isInstance(body)) return null;
            Object value = found.cachedString.invoke(body, TEXT_KEY);
            if (!(value instanceof String)) return null;
            wordsRead = true;
            return (String) value;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(FAMILY, "comment text reader", failure.getCause());
            return null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "comment text reader", failure);
            return null;
        }
    }

    /** Which Hook status row the members were last reported into. */
    private static volatile long reportedGeneration = -1;

    /**
     * The members, reported into the family's Hook status row once per row: again after a
     * diagnostic clear, which empties the row it was written into.
     */
    private static void report(Members found) {
        long generation = HookStatus.generation();
        if (reportedGeneration == generation) return;
        reportedGeneration = generation;
        if (found.comment == null) {
            HookStatus.missingMember(FAMILY, "class", "com.facebook.graphql.model", "GraphQLComment");
        }
        if (found.text == null) {
            HookStatus.missingMember(FAMILY, "class", "com.facebook.graphql.model", "GraphQLTextWithEntities");
        }
        if (found.cachedModel == null) {
            HookStatus.missingMember(FAMILY, "method", TREE_MODEL_CLASS, "getCachedModel(int, Class, int)");
        }
        if (found.cachedString == null) {
            HookStatus.missingMember(FAMILY, "method", TREE_MODEL_CLASS, "getCachedString(int)");
        }
        if (found.complete()) HookStatus.bound(FAMILY, "comment text");
    }

    /** The kept members the text is read through, looked up once. A missing one is null. */
    static final class Members {
        @Nullable final Class<?> comment;
        @Nullable final Class<?> text;
        @Nullable final Method cachedModel;
        @Nullable final Method cachedString;

        Members(@Nullable Class<?> comment, @Nullable Class<?> text, @Nullable Method cachedModel,
                @Nullable Method cachedString) {
            this.comment = comment;
            this.text = text;
            this.cachedModel = cachedModel;
            this.cachedString = cachedString;
        }

        boolean complete() {
            return comment != null && text != null && cachedModel != null && cachedString != null;
        }

        static Members lookUp(ClassLoader loader) {
            Class<?> comment = classOrNull(COMMENT_CLASS, loader);
            Class<?> text = classOrNull(TEXT_CLASS, loader);
            Class<?> treeModel = classOrNull(TREE_MODEL_CLASS, loader);
            Method cachedModel = null;
            Method cachedString = null;
            if (treeModel != null) {
                try {
                    cachedModel = treeModel.getMethod("getCachedModel", int.class, Class.class, int.class);
                } catch (NoSuchMethodException | RuntimeException missing) {
                    // Named in the report.
                }
                try {
                    Method method = treeModel.getMethod("getCachedString", int.class);
                    if (method.getReturnType() == String.class) cachedString = method;
                } catch (NoSuchMethodException | RuntimeException missing) {
                    // Named in the report.
                }
            }
            return new Members(comment, text, cachedModel, cachedString);
        }

        @Nullable
        private static Class<?> classOrNull(String name, ClassLoader loader) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable missing) {
                return null;
            }
        }
    }

    /** Looked up on first use. A test sets it to stand in for a build missing a member. */
    static volatile Members cachedMembers;

    static Members members() {
        Members found = cachedMembers;
        if (found == null) {
            found = Members.lookUp(CommentText.class.getClassLoader());
            cachedMembers = found;
        }
        return found;
    }
}
