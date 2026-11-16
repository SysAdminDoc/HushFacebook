/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.HushfacebookPause;

/**
 * The posts the person hid since Facebook started, so a pull to refresh can't put one back (#444).
 *
 * <p>Facebook's hide, its "Hide all from" and its Undo all end in one class that publishes the
 * post's new local visibility to the GraphQL consistency store, by the post's id: CONTRACTING while
 * the post folds away, HIDDEN or GONE once it has, and VISIBLE when Undo brings it back. The patch
 * hands each of those publishes here ({@link #published}). With Hushfacebook on, a pull to refresh
 * on the feed could bring a post hidden that way back as a new edge, so the feed guard asks
 * {@link #hideReason} and keeps out every edge whose post was hidden and not brought back since.
 *
 * <p>Kept in memory only, like Facebook's own hidden state, which a fresh start reads from the
 * server again. Never while Hushfacebook is paused.
 */
public final class HiddenPosts {
    /** What the feed guard counts an edge it kept out for this under. */
    static final String REASON = "hidden by you";
    /** How many hidden posts it keeps; the oldest goes first past this. */
    static final int MAX = 500;
    /** Facebook's local story visibility values, as its mapping to the published names reads them. */
    static final int CONTRACTING = 1;
    static final int HIDDEN = 2;
    static final int GONE = 3;

    private static final Set<String> IDS = new LinkedHashSet<>();

    private HiddenPosts() { }

    /**
     * Injection point, at the publishes of a post's local visibility: [id] is the post's GraphQL
     * id and [visibility] Facebook's value for it. A hide remembers the post and anything else, an
     * Undo's VISIBLE included, forgets it. Never throws.
     */
    public static void published(@Nullable String id, @Nullable Integer visibility) {
        try {
            if (id == null || id.isEmpty() || visibility == null) return;
            boolean hidden = hides(visibility);
            int held;
            synchronized (IDS) {
                if (hidden) {
                    IDS.remove(id);
                    IDS.add(id);
                    Iterator<String> oldest = IDS.iterator();
                    while (IDS.size() > MAX && oldest.hasNext()) {
                        oldest.next();
                        oldest.remove();
                    }
                } else if (!IDS.remove(id)) {
                    return;
                }
                held = IDS.size();
            }
            final int count = held;
            Logger.printDebug(() -> "Hidden posts: " + (hidden ? "remembered a post you hid (" : "a post came back (")
                    + visibility + "), " + count + " held");
        } catch (Throwable failure) {
            Logger.printException(() -> "Hidden posts: could not note a visibility change", failure);
        }
    }

    /** Whether Facebook's visibility value means the post is folding away or gone from the feed. */
    static boolean hides(int visibility) {
        return visibility == CONTRACTING || visibility == HIDDEN || visibility == GONE;
    }

    /** Why the feed guard keeps [unit] out, or null when it isn't a post the person hid. Never throws. */
    @Nullable
    static String hideReason(@Nullable Object unit) {
        try {
            if (unit == null) return null;
            synchronized (IDS) {
                if (IDS.isEmpty()) return null;
            }
            if (HushfacebookPause.isPaused()) return null;
            PostText.Members members = PostText.members();
            if (members.treeModel == null || members.cachedString == null || !members.treeModel.isInstance(unit)) {
                return null;
            }
            Object id = members.cachedString.invoke(unit, PostSources.ID_KEY);
            if (!(id instanceof String)) return null;
            synchronized (IDS) {
                return IDS.contains(id) ? REASON : null;
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Hidden posts: could not read a post's id", failure);
            return null;
        }
    }

    static int heldForTests() {
        synchronized (IDS) {
            return IDS.size();
        }
    }

    static void clearForTests() {
        synchronized (IDS) {
            IDS.clear();
        }
    }
}
