/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * What a save knows about the post its video comes from, for the file name: the video's id, who
 * posted it, their id and the day it went up. Each is missing when the route to the save doesn't
 * reach it, and {@link FileNameTemplate} then leaves its token out of the name.
 *
 * <p>The poster and the post time are read off Facebook's GraphQL models the way Facebook's own
 * code reads them. Every model is a {@code TreeJNI}, a class whose name Redex keeps, and its kept
 * {@code getTree}, {@code getTreeList}, {@code getString} and {@code getTimeValue} take the Java
 * hash of a field's GraphQL name. A video's {@code owner}, a post's {@code actors} and a reel's
 * {@code short_form_video_context.video_owner} are actor models with a {@code name} and an
 * {@code id}, the profile's or Page's number, which stays when a Page renames itself; a post's
 * {@code creation_time} is in seconds. Facebook reads each of these the same way: the reel's time
 * label casts the reel's story to {@code TreeJNI} and asks {@code getTimeValue(creation_time)},
 * and its first-actor helper takes the first actor with a {@code name}. Each accessor answers
 * null, an empty list or 0 for a field the tree doesn't hold (with Facebook's set-field list off,
 * its own getters ask the native tree for unset fields the same way), and nothing is asked of a
 * model whose native tree is gone. No getter Redex renames is used, so the reads hold from one
 * Facebook build to the next.
 */
public final class PostDetails {

    /** The class every GraphQL model extends, kept by Redex, and the kept names read on it. */
    static final String TREE = "com.facebook.graphservice.tree.TreeJNI";
    private static final String VALID = "isValidGraphServicesJNIModel";

    /** The fields read, as the hash Facebook's models look a field up by: the Java hash of its GraphQL name. */
    static final int OWNER = "owner".hashCode();
    static final int ACTORS = "actors".hashCode();
    static final int NAME = "name".hashCode();
    static final int ID = "id".hashCode();
    static final int CREATION_TIME = "creation_time".hashCode();
    static final int CREATION_STORY = "creation_story".hashCode();
    static final int SHORT_FORM_VIDEO_CONTEXT = "short_form_video_context".hashCode();
    static final int VIDEO_OWNER = "video_owner".hashCode();

    /** A save that knows nothing of its post. */
    public static final PostDetails NONE = new PostDetails(null, null, null, null);

    /** The video's id on Facebook as the route handed it over, or null. Used only when it's a number. */
    public final String videoId;

    /** The poster's name, cleaned the way a folder name is and bounded, or null when unknown. */
    public final String owner;

    /**
     * The poster's id on Facebook, the number of the profile or Page {@link #owner} names, or null
     * when unknown. Only ever digits.
     */
    public final String ownerId;

    /** When the post went up, or null when unknown. */
    public final Date posted;

    PostDetails(String videoId, String owner, Date posted) {
        this(videoId, owner, null, posted);
    }

    PostDetails(String videoId, String owner, String ownerId, Date posted) {
        this.videoId = videoId;
        String clean = owner == null ? "" : SaveFolder.clean(owner, FileNameTemplate.MAX_OWNER_CODE_POINTS);
        this.owner = clean.isEmpty() ? null : clean;
        // A number, as a video id is, so nothing from the app but digits can reach the name.
        this.ownerId = FileNameTemplate.isVideoId(ownerId) ? ownerId : null;
        this.posted = posted;
    }

    /** A save that knows the video's id and nothing else, as every route did before the poster was read. */
    public static PostDetails of(String videoId) {
        return videoId == null ? NONE : new PostDetails(videoId, null, null);
    }

    public boolean hasVideoId() {
        return FileNameTemplate.isVideoId(videoId);
    }

    public boolean hasOwner() {
        return owner != null;
    }

    public boolean hasOwnerId() {
        return ownerId != null;
    }

    public boolean hasPosted() {
        return posted != null;
    }

    /**
     * The details of a save of [videoId], with the poster and the post time read from the first of
     * [models] that knows each. The poster's id is the one the named poster carries. A model that
     * isn't a tree, or whose tree is gone, answers nothing. Never throws.
     */
    public static PostDetails read(String videoId, Object... models) {
        Object poster = null;
        Date posted = null;
        for (Object model : models) {
            if (poster == null) poster = posterOf(model);
            if (posted == null) posted = postedOf(model);
        }
        return new PostDetails(videoId, nameOf(poster), idOf(poster), posted);
    }

    /**
     * The details of a story save. The card's kept {@code getTimestamp} is its own tree's
     * {@code creation_time} in milliseconds, so it's the post time, and the tree in the card with
     * that same creation time is the card's own story, whose actors say who posted it. A card can
     * hold other trees too, and none of those is asked for a poster. A card of one tree and no
     * timestamp is read from that tree.
     */
    public static PostDetails ofCard(String videoId, Object card) {
        List<Object> trees = treesIn(card);
        long millis = timestampOf(card);
        Object story = null;
        if (millis > 0) {
            for (Object tree : trees) {
                if (time(tree, CREATION_TIME) * 1000L == millis) {
                    story = tree;
                    break;
                }
            }
        } else if (trees.size() == 1) {
            story = trees.get(0);
        }
        Date posted = millis > 0 ? new Date(millis) : postedOf(story);
        Object poster = posterOf(story);
        return new PostDetails(videoId, nameOf(poster), idOf(poster), posted);
    }

    /** The card's kept getTimestamp(), in milliseconds, or 0 when it has none. */
    private static long timestampOf(Object card) {
        if (card == null) return 0;
        try {
            Object millis = card.getClass().getMethod("getTimestamp").invoke(card);
            return millis instanceof Long && (Long) millis > 0 ? (Long) millis : 0;
        } catch (Throwable t) {
            // A card of another shape. The time stays unknown.
            return 0;
        }
    }

    /**
     * The poster's name for [model]: {@link #posterOf}'s. Null when it has no poster or isn't a
     * tree.
     */
    static String ownerOf(Object model) {
        return nameOf(posterOf(model));
    }

    /**
     * The poster of [model], the actor model whose name the file name uses: its owner, a
     * video's; then its first actor with a name, a post's or a reel's; then its short-form video's
     * owner, a reel's. Null when none of them has a name or it isn't a tree.
     */
    static Object posterOf(Object model) {
        if (!isLiveTree(model)) return null;
        Object owner = tree(model, OWNER);
        if (nameOf(owner) != null) return owner;
        List<?> actors = trees(model, ACTORS);
        if (actors != null) {
            for (Object actor : actors) {
                if (nameOf(actor) != null) return actor;
            }
        }
        Object reelOwner = tree(tree(model, SHORT_FORM_VIDEO_CONTEXT), VIDEO_OWNER);
        return nameOf(reelOwner) != null ? reelOwner : null;
    }

    /** The id [actor] carries, when it's a number, or null. */
    static String idOf(Object actor) {
        if (!isLiveTree(actor)) return null;
        String id = string(actor, ID);
        return FileNameTemplate.isVideoId(id) ? id : null;
    }

    /**
     * When [model] was posted: its creation time, and for a video without one, its creation
     * story's. Null when neither is set or it isn't a tree. Public for the date in front of a reel's
     * song line (ReelDeclutter.audioLineText, #357).
     */
    public static Date postedOf(Object model) {
        if (!isLiveTree(model)) return null;
        long seconds = time(model, CREATION_TIME);
        if (seconds <= 0) seconds = time(tree(model, CREATION_STORY), CREATION_TIME);
        return seconds <= 0 ? null : new Date(seconds * 1000L);
    }

    /** The trees [host] holds in its own fields, in declaration order, its parents' included. Never throws. */
    static List<Object> treesIn(Object host) {
        List<Object> trees = new ArrayList<>();
        if (host == null) return trees;
        try {
            for (Class<?> type = host.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(host);
                        if (isTree(value)) trees.add(value);
                    } catch (Throwable ignored) {
                        // One unreadable field must not end the walk.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Whatever was found stands.
        }
        return trees;
    }

    /** Whether [value] is a GraphQL model: an instance of the kept tree class. */
    static boolean isTree(Object value) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(TREE)) return true;
        }
        return false;
    }

    /**
     * A tree whose native side is still there. A read from a released one goes to nothing. Public
     * for the Menu's Muse card, which reads a bookmark's name the same way.
     */
    public static boolean isLiveTree(Object value) {
        if (!isTree(value)) return false;
        try {
            return Boolean.TRUE.equals(value.getClass().getMethod(VALID).invoke(value));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String nameOf(Object actor) {
        if (!isLiveTree(actor)) return null;
        String name = string(actor, NAME);
        return name == null || name.trim().isEmpty() ? null : name;
    }

    /**
     * The tree in [model]'s [field], or null when it holds none or [model] isn't live. Public for
     * the Menu's tiles, which read a bookmark's node this way.
     */
    public static Object tree(Object model, int field) {
        if (!isLiveTree(model)) return null;
        Object value = call(model, "getTree", field);
        return isTree(value) ? value : null;
    }

    static List<?> trees(Object model, int field) {
        if (!isLiveTree(model)) return null;
        Object value = call(model, "getTreeList", field);
        return value instanceof List ? (List<?>) value : null;
    }

    /** The string field [field] of [model], or null when it holds none or the read fails. */
    public static String string(Object model, int field) {
        Object value = call(model, "getString", field);
        return value instanceof String ? (String) value : null;
    }

    /**
     * When the tree [edge] holds in its [field] was posted, in seconds, or 0 when it doesn't say.
     * Public for Newest reels first, which reads a reel edge's node this way.
     */
    public static long postedSecondsOf(Object edge, int field) {
        return time(tree(edge, field), CREATION_TIME);
    }

    /**
     * The whole number [model] holds in its [field], or -1 when [model] isn't live, has no value
     * there, or the read fails. Public for Newest reels first, which reads a reel's like and play
     * counts this way.
     */
    public static long count(Object model, int field) {
        if (!isLiveTree(model)) return -1;
        // A tree answers 0 for a number it never got, so ask first when it can say.
        Object has = call(model, "hasFieldValue", field);
        if (Boolean.FALSE.equals(has)) return -1;
        Object value = call(model, "getIntValue", field);
        return value instanceof Integer && (Integer) value >= 0 ? (Integer) value : -1;
    }

    private static long time(Object model, int field) {
        if (!isLiveTree(model)) return 0;
        Object value = call(model, "getTimeValue", field);
        return value instanceof Long ? (Long) value : 0;
    }

    /** The kept accessor [name] of [model] for [field], or null when the model has none or it fails. */
    static Object call(Object model, String name, int field) {
        if (model == null || (isTree(model) && !isLiveTree(model))) return null;
        try {
            Method method = model.getClass().getMethod(name, int.class);
            return method.invoke(model, field);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String toString() {
        // Never the poster's name or an id: a details object can end up in a diagnostic line.
        return "PostDetails(id " + (hasVideoId() ? "known" : "unknown") + ", poster " + (hasOwner() ? "known" : "unknown")
            + ", poster id " + (hasOwnerId() ? "known" : "unknown") + ", posted " + (hasPosted() ? "known" : "unknown") + ")";
    }
}
