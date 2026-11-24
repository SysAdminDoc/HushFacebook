/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */

package app.morphe.extension.facebook.feed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Show reactions on the Like button (#541). A post's button row in the feed puts its count beside a
 * thumb on the Like button and stacks the top reaction faces at the far end of the row. With the
 * switch on, the faces sit on the Like button instead, right before the count, the way Facebook's
 * older summary line read.
 *
 * <p>Four hooks do it. The Like button's builders, which know the viewer's reaction, hand {@link
 * #remember} each icon they build and the face it stands for. The footer's renderer only fills in
 * the count afterwards, from state of its own, so the swap waits for the button's renderer to be
 * made: {@link #facesFor} gets the finished icon and text, and for an icon a Like builder noted,
 * with a count, answers a {@link Faces} drawable that goes in through Facebook's own drawable icon
 * (the one it uses for a reaction it has a picture of). The Comment and Share buttons' icons were
 * never noted, so they keep theirs. The renderer's drawing then asks {@link #isFaces} and widens
 * that icon's box by {@link #widthBits} so three faces fit. The faces at the end of the row are a
 * drawable whose draw asks {@link PostPrompts#hideReactionPreview(Drawable, boolean)} first: with
 * the switch on it lands in {@link #movedFrom}, which notes them against their row and holds them
 * back there while the row's Like button is a {@link Faces}. The button then draws them in its own
 * box.
 *
 * <p>The box is as wide as the post's faces need. The builder of a feed post's Like item hands
 * {@link #pendingStory} the post first: its top reactions that someone used, three at most, are the
 * faces Facebook's facepile shows, and every icon the builder notes carries that count to its
 * button. Faces don't change size with the count, so a post with one face has no gap before it. A
 * count that can't be read keeps the box wide enough for three.
 *
 * <p>A row whose end shows something else, like a video's view count, has no faces to move, so its
 * button shows the one face Facebook would give it: Like, or the viewer's own reaction. Hide the
 * reaction preview on posts overrules this switch, and a post with no count keeps its thumb. Off,
 * paused, before the settings are ready or on a throw, every hook answers as Facebook does.
 */
public final class LikeButtonFaces {
    private LikeButtonFaces() {
    }

    static final String BUTTON = "reactions on the Like button";
    /** Counted under the patch's name each time a row's faces are held back for its Like button. */
    static final String MOVED = "Reaction faces moved to the Like button";
    /** How wide the faces' box is, in icon heights, when the post's count of faces isn't known. */
    static final float BOX_WIDTH = 2.4f;
    /** The most faces Facebook's facepile shows. */
    static final int MAX_FACES = 3;
    /** Each face's share of the box, in icon heights, so a box for three is the wide one. */
    static final float FACE_WIDTH = BOX_WIDTH / MAX_FACES;
    /** A count of faces that wasn't read. */
    static final int UNKNOWN = 0;
    /** The GraphQL field of a top reaction edge that says how many people used it. */
    static final int REACTION_COUNT_KEY = "reaction_count".hashCode();
    /** How many of the latest Like icons keep their post's count of faces. */
    private static final int COUNTED = 128;
    /** How far up from a drawable's host a row's LithoView is looked for. */
    private static final int HOPS = 40;
    private static final String FAMILY = FamilyNames.POST_PROMPTS;

    /** Each row's faces from the end of its button row, by the row's LithoView. Main thread only. */
    private static final Map<View, WeakReference<Drawable>> rowFaces = new WeakHashMap<>();
    /** Each row's Like button faces, so a row whose faces arrive later redraws its button. */
    private static final Map<View, WeakReference<Faces>> rowButtons = new WeakHashMap<>();
    private static final Map<Class<?>, Boolean> lithoClasses = new HashMap<>();
    /** True while a button draws its row's faces, so their own draw doesn't hold them back. */
    private static boolean drawingFaces;
    private static volatile boolean logged;
    /**
     * Each icon a Like button's builders made, with the face that button would show: the viewer's
     * reaction, or Like. Builders run on Litho's layout threads, so it's read and written locked.
     */
    private static final Map<Object, Drawable> likeIcons = new WeakHashMap<>();
    /**
     * The latest Like icons with their post's count of faces, matched by identity: Facebook's icon
     * equals every other icon of the same reaction, whatever the post. Read and written locked.
     */
    private static final WeakReference<?>[] countedIcons = new WeakReference<?>[COUNTED];
    private static final int[] iconCounts = new int[COUNTED];
    private static int nextCounted;
    /** The count of faces of the post whose Like item this thread is building. */
    private static final ThreadLocal<Integer> pending = new ThreadLocal<>();

    /** The three reads a post's faces are counted through: the patch's accessors, or a test's. */
    static final class Readers {
        final StoryFlag.Accessor feedback;
        final StoryFlag.Accessor topReactions;
        final StoryFlag.Accessor edges;

        Readers(StoryFlag.Accessor feedback, StoryFlag.Accessor topReactions, StoryFlag.Accessor edges) {
            this.feedback = feedback;
            this.topReactions = topReactions;
            this.edges = edges;
        }
    }

    private static final Readers PATCHED =
            new Readers(LikeButtonFaces::feedback, LikeButtonFaces::topReactions, LikeButtonFaces::reactionEdges);

    /** Injection point, filled in by the patch: a GraphQLStory's {@code feedback}, or null. */
    public static Object feedback(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /** Injection point, filled in by the patch: a GraphQLFeedback's {@code top_reactions}, or null. */
    public static Object topReactions(Object feedback) {
        return StoryFlag.NOT_PATCHED;
    }

    /** Injection point, filled in by the patch: a top reactions connection's {@code edges}, or null. */
    public static Object reactionEdges(Object topReactions) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * The hook first thing in the builder of a feed post's Like item, handed what it reads as the
     * post's GraphQLStory: the post's count of faces is read now, for the icons the builder notes.
     */
    public static void pendingStory(@Nullable Object story) {
        pendingStory(story, PATCHED);
    }

    static void pendingStory(@Nullable Object story, Readers readers) {
        pending.remove();
        try {
            if (story == null || !on()) return;
            int faces = facesOf(story, readers);
            if (faces != UNKNOWN) pending.set(faces);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BUTTON, failure);
        }
    }

    /** The hook at every return of that builder: icons noted after it aren't this post's. */
    public static void clearPending() {
        pending.remove();
    }

    /**
     * How many faces Facebook's facepile shows for [story]: the top reactions somebody used, three
     * at most, the way its own facepile picks them. {@link #UNKNOWN} when they can't be read.
     */
    static int facesOf(Object story, Readers readers) throws Exception {
        PostText.Members found = PostText.members();
        Class<?> storyClass = found.story;
        Class<?> treeModel = found.treeModel;
        if (storyClass == null || !storyClass.isInstance(story) || FeedFilter.released(story)) return UNKNOWN;
        Method cachedInt = PostReactions.cachedInt(treeModel);
        if (cachedInt == null) return UNKNOWN;
        Object feedback = live(readers.feedback.model(story), "the feedback accessor");
        Object top = feedback == null ? null : live(readers.topReactions.model(feedback), "the top reactions accessor");
        Object edges = top == null ? null : readers.edges.model(top);
        if (edges == StoryFlag.NOT_PATCHED) HookStatus.missingMember(FAMILY, "method", "top_reactions", "the edges accessor");
        if (!(edges instanceof List)) return UNKNOWN;
        int faces = 0;
        for (Object edge : (List<?>) edges) {
            if (!treeModel.isInstance(edge) || FeedFilter.released(edge)) continue;
            Object used = cachedInt.invoke(edge, REACTION_COUNT_KEY);
            if (used instanceof Integer && (Integer) used > 0 && ++faces == MAX_FACES) break;
        }
        return faces;
    }

    /** A model a read answered, or null when there's none, it's released or the read wasn't patched. */
    @Nullable
    private static Object live(@Nullable Object model, String accessor) {
        if (model == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FAMILY, "method", "a post's reactions", accessor);
            return null;
        }
        return model == null || FeedFilter.released(model) ? null : model;
    }

    /** Notes [icon] as one of a post with [faces] faces. */
    private static void count(Object icon, int faces) {
        synchronized (countedIcons) {
            countedIcons[nextCounted] = new WeakReference<>(icon);
            iconCounts[nextCounted] = faces;
            nextCounted = (nextCounted + 1) % COUNTED;
        }
    }

    /** The count of faces [icon]'s post was noted with, or {@link #UNKNOWN}. */
    static int countOf(Object icon) {
        synchronized (countedIcons) {
            for (int i = 0; i < COUNTED; i++) {
                WeakReference<?> held = countedIcons[i];
                if (held != null && held.get() == icon) return iconCounts[i];
            }
        }
        return UNKNOWN;
    }

    /** The switch, on and ready, and not overruled by Hide the reaction preview on posts. */
    static boolean on() {
        return Utils.settingsReady() && Settings.REACTIONS_ON_LIKE_BUTTON.get()
                && !Settings.HIDE_POST_REACTIONS.get();
    }

    /** The hook first thing in the Like button's builders: whether to note the icon they built. */
    public static boolean remembering() {
        try {
            HookStatus.invoked(FAMILY);
            return on();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BUTTON, failure);
            return false;
        }
    }

    /** The hook in the Like button's builders, handed the icon they built and the face it stands for. */
    public static void remember(@Nullable Object icon, @Nullable Drawable face) {
        try {
            if (icon == null || face instanceof Faces) return;
            synchronized (likeIcons) {
                likeIcons.put(icon, face);
            }
            Integer faces = pending.get();
            if (faces != null) count(icon, faces);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BUTTON, failure);
        }
    }

    /**
     * The hook as a button's renderer is made, handed the button's finished icon and text. Answers
     * the faces to draw instead of that icon, or null to keep it: only an icon a Like button's
     * builders noted, on a button whose text has a count, gets them, since a post nobody has
     * reacted to has no faces anywhere.
     */
    @Nullable
    public static Drawable facesFor(@Nullable Object icon, @Nullable String text) {
        try {
            if (icon == null || !wanted(text)) return null;
            Drawable face;
            synchronized (likeIcons) {
                if (!likeIcons.containsKey(icon)) return null;
                face = likeIcons.get(icon);
            }
            HookStatus.bound(FAMILY, BUTTON);
            return new Faces(face, countOf(icon));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BUTTON, failure);
            return null;
        }
    }

    /** Whether a button's text has a count in it, with the switch on. */
    static boolean wanted(@Nullable String text) {
        if (text == null || !on()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) return true;
        }
        return false;
    }

    /** The hook in the button's renderer: whether this icon is faces, whose box gets widened. */
    public static boolean isFaces(@Nullable Drawable icon) {
        return icon instanceof Faces;
    }

    /**
     * The width of a faces icon's box for an icon [size] dp tall, in the bits Facebook's style takes:
     * room for its post's faces, or for three when their count isn't known.
     */
    public static long widthBits(@Nullable Drawable icon, float size) {
        int faces = icon instanceof Faces ? ((Faces) icon).faces : UNKNOWN;
        float width = faces >= 1 && faces <= MAX_FACES ? FACE_WIDTH * faces : BOX_WIDTH;
        return Double.doubleToRawLongBits(size * width);
    }

    /**
     * Through {@link PostPrompts#hideReactionPreview(Drawable, boolean)}, at the start of the draw of
     * a post's faces at the end of its button row. They're noted against the row either way, so a
     * Like button drawn after them finds them. True holds them back, and the row's Like button,
     * which is faces, draws them. False leaves them where they are, for a row whose Like button
     * isn't faces (or isn't drawn yet), or while that button is drawing them itself.
     */
    static boolean movedFrom(@Nullable Drawable facepile) {
        try {
            if (drawingFaces || facepile == null || !on()) return false;
            View row = rowOf(hostOf(facepile));
            if (row == null) return false;
            WeakReference<Drawable> had = rowFaces.put(row, new WeakReference<>(facepile));
            Faces button = buttonIn(row);
            if (button == null) return false;
            if (had == null || had.get() != facepile) button.invalidateSelf();
            HookStatus.counted(FAMILY, MOVED);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Post prompts: a post's reaction faces moved to its Like button");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BUTTON, failure);
            return false;
        }
    }

    /** The row's Like button faces, when one is still drawn in that row. */
    @Nullable
    private static Faces buttonIn(View row) {
        WeakReference<Faces> held = rowButtons.get(row);
        Faces button = held == null ? null : held.get();
        if (button == null || rowOf(hostOf(button)) != row) {
            rowButtons.remove(row);
            return null;
        }
        return button;
    }

    /** The row's faces from the end of its button row, when they're still drawn in that row. */
    @Nullable
    private static Drawable facesIn(View row) {
        WeakReference<Drawable> held = rowFaces.get(row);
        Drawable faces = held == null ? null : held.get();
        if (faces == null || rowOf(hostOf(faces)) != row) {
            rowFaces.remove(row);
            return null;
        }
        return faces;
    }

    /** The view a drawable is drawn in, through any drawable that wraps it. */
    @Nullable
    static View hostOf(Drawable drawable) {
        Object callback = drawable.getCallback();
        for (int i = 0; i < 8 && callback instanceof Drawable; i++) {
            callback = ((Drawable) callback).getCallback();
        }
        return callback instanceof View ? (View) callback : null;
    }

    /** The nearest LithoView holding [view], the button row's own. */
    @Nullable
    static View rowOf(@Nullable View view) {
        View at = view;
        for (int i = 0; i < HOPS && at != null; i++) {
            if (isLitho(at.getClass())) return at;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean isLitho(Class<?> type) {
        Boolean known = lithoClasses.get(type);
        if (known != null) return known;
        boolean litho = false;
        for (Class<?> at = type; at != null && at != View.class; at = at.getSuperclass()) {
            if ("LithoView".equals(at.getSimpleName())) {
                litho = true;
                break;
            }
        }
        lithoClasses.put(type, litho);
        return litho;
    }

    /**
     * The Like button's icon while the switch is on: its row's faces, right-aligned against the
     * count and scaled to the icon's height, or the one face Facebook would have shown until the row
     * has faces to give. Equal to another for the same face and count of faces, so a button drawn
     * again keeps it.
     */
    static final class Faces extends Drawable {
        @Nullable private final Drawable source;
        @Nullable private final Drawable face;
        /** How many faces the post's facepile shows, or {@link #UNKNOWN}. */
        final int faces;

        Faces(@Nullable Drawable source) {
            this(source, UNKNOWN);
        }

        Faces(@Nullable Drawable source, int faces) {
            this.source = source;
            this.face = own(source);
            this.faces = faces;
        }

        @Nullable
        private static Drawable own(@Nullable Drawable source) {
            if (source == null) return null;
            Drawable.ConstantState state = source.getConstantState();
            return state != null ? state.newDrawable().mutate() : source;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect box = getBounds();
            if (box.isEmpty()) return;
            View row = rowOf(hostOf(this));
            Drawable faces = null;
            if (row != null) {
                WeakReference<Faces> was = rowButtons.put(row, new WeakReference<>(this));
                faces = facesIn(row);
                // Faces drawn before this button showed at the end of the row: draw them again, so
                // they find this button and hold back there.
                if (faces != null && (was == null || was.get() != this)) faces.invalidateSelf();
            }
            if (faces != null && drawFaces(canvas, faces, box)) return;
            if (face == null) return;
            int side = Math.min(box.width(), box.height());
            int top = box.top + (box.height() - side) / 2;
            face.setBounds(box.right - side, top, box.right, top + side);
            face.draw(canvas);
        }

        private static boolean drawFaces(Canvas canvas, Drawable faces, Rect box) {
            Rect from = faces.getBounds();
            if (from.isEmpty()) return false;
            float scale = (float) box.height() / from.height();
            if (from.width() * scale > box.width()) scale = (float) box.width() / from.width();
            float width = from.width() * scale;
            float height = from.height() * scale;
            int saved = canvas.save();
            drawingFaces = true;
            try {
                canvas.translate(box.right - width, box.top + (box.height() - height) / 2f);
                canvas.scale(scale, scale);
                canvas.translate(-from.left, -from.top);
                faces.draw(canvas);
            } finally {
                drawingFaces = false;
                canvas.restoreToCount(saved);
            }
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            if (face != null) face.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            if (face != null) face.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Faces && ((Faces) other).source == source && ((Faces) other).faces == faces;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(source) * 31 + faces;
        }
    }
}
