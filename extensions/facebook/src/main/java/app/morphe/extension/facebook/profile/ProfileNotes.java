/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.profile;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide profile notes patch asks each time a Bloks node hands out its children.
 *
 * <p>Facebook draws the top of a profile with Bloks, its server-sent layout. Every Bloks node keeps
 * its attributes in a SparseArray, and the note bubble on the profile picture is a node like any
 * other: on 584 it's the last of the three children stacked over the picture. What tells it apart
 * is a script in its own attributes. Its tap action names
 * {@code com.bloks.www.bloks.profile.note.self_note.tap_action.async} on your own profile, and the
 * action that loads a bubble's content names
 * {@code com.bloks.www.bloks.profile.async.fetch.note_bubble}. The patch hands every list the node
 * class's child getters return to {@link #children}, and while the switch is on that answers the
 * list without those nodes, so Facebook never draws the bubble or loads its content.
 *
 * <p>The Send a gift button on someone else's profile (#576) goes the same way under its own switch.
 * Its node is told apart by what its own scripts open: one of Facebook's gift addresses, a Bloks
 * app whose name holds "gift", or a name holding both "profile" and "gift", the way the note
 * bubble's names its entry point. That button never showed on the accounts this was written
 * against, so what it opens is read from the gift addresses 588 knows, and every node left out is
 * counted under {@link #GIFT_REASON} and logged with what it named.
 *
 * <p>Only a node's own string attributes are read, never the ones of its children, so a node that
 * holds the bubble somewhere below it, the profile picture's stack among them, is kept. A node's
 * answer is kept by the node, since the getters run on every layout pass.
 *
 * <p>It fails open: switches off, a pause, settings that aren't ready, or any failure in here, and
 * the list goes back as Facebook made it.
 */
public final class ProfileNotes {
    /** The diagnostic counter route: the note nodes left out. */
    static final String ROUTE = "Profile notes";

    /** What a node's string attribute names when the node is a note bubble or loads one. */
    static final String[] MARKERS = {
            "com.bloks.www.bloks.profile.note.",
            "com.bloks.www.bloks.profile.async.fetch.note_bubble",
    };

    /** What a left-out node is counted under. */
    static final String REASON = "note bubble";

    /** What a node's string attribute names when the node sends a gift: Facebook's gift addresses. */
    static final String[] GIFT_ROUTES = {
            "fb://gift/",
            "/gifts/start",
    };

    /** A Bloks app whose name holds "gift", or a quoted name holding both "profile" and "gift". */
    static final Pattern GIFT_ACTION = Pattern.compile(
            "com\\.bloks\\.www\\.[\\w.]*gift|\"(?=[a-z_.]*profile)(?=[a-z_.]*gift)[a-z_.]+\"",
            Pattern.CASE_INSENSITIVE);

    /** What a left-out gift button is counted under. */
    static final String GIFT_REASON = "gift button";

    /** What a node is, as bits. */
    static final int NOTE = 1;
    static final int GIFT = 2;

    /** Each class's attribute field, or {@link #NO_FIELD} for a class that has none. */
    private static final Map<Class<?>, Object> ATTRIBUTES = new ConcurrentHashMap<>();
    private static final Object NO_FIELD = new Object();

    /** Each node's bits. Bloks nodes keep Object's identity equality, so this is by node. */
    private static final Map<Object, Integer> KINDS = Collections.synchronizedMap(new WeakHashMap<>());

    private ProfileNotes() {
    }

    /**
     * Injection point, before every return of the Bloks node class's child getters: the list the
     * getter would return, or the same list without its note bubble or its gift button, each while
     * its switch is on. Never throws.
     */
    public static List<?> children(@Nullable List<?> list) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_NOTES);
            if (list == null || list.isEmpty()) return list;
            if (!Utils.settingsReady()) return list;
            int hidden = (Settings.HIDE_PROFILE_NOTES.get() ? NOTE : 0) | (Settings.HIDE_PROFILE_GIFTS.get() ? GIFT : 0);
            if (hidden == 0) return list;
            int size = list.size();
            List<Object> kept = null;
            int notes = 0;
            int gifts = 0;
            for (int index = 0; index < size; index++) {
                Object child = list.get(index);
                int kind = kindOf(child) & hidden;
                if (kind != 0) {
                    if (kept == null) kept = new ArrayList<>(list.subList(0, index));
                    if ((kind & NOTE) != 0) notes++;
                    else gifts++;
                } else if (kept != null) {
                    kept.add(child);
                }
            }
            if (kept == null) return list;
            if (notes > 0) FeedFilterCounters.removed(ROUTE, notes, REASON);
            if (gifts > 0) FeedFilterCounters.removed(ROUTE, gifts, GIFT_REASON);
            int leftNotes = notes;
            int leftGifts = gifts;
            Logger.printDebug(() -> "Profile notes: left out " + leftNotes + " note bubble node(s) and "
                    + leftGifts + " gift button node(s)");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_NOTES, "Bloks children", failure);
            Logger.printException(() -> "Profile notes: could not read a Bloks node's children", failure);
            return list;
        }
    }

    /** Whether [node] is a Bloks node whose own string attributes name a note bubble. */
    static boolean isNote(@Nullable Object node) throws IllegalAccessException {
        return (kindOf(node) & NOTE) != 0;
    }

    /**
     * What [node] is, as bits: {@link #NOTE} when its own string attributes name a note bubble,
     * {@link #GIFT} when they name a gift. 0 for anything else, a child that isn't a node included.
     */
    static int kindOf(@Nullable Object node) throws IllegalAccessException {
        if (node == null) return 0;
        Field field = attributes(node.getClass());
        if (field == null) return 0;
        Integer known = KINDS.get(node);
        if (known != null) return known;
        SparseArray<?> attributes = (SparseArray<?>) field.get(node);
        int kind = namesANote(attributes) ? NOTE : 0;
        String gift = giftNamedBy(attributes);
        if (kind == 0 && gift != null) kind = GIFT;
        KINDS.put(node, kind);
        if (kind != 0) HookStatus.bound(FamilyNames.PROFILE_NOTES, node.getClass().getName() + "#" + field.getName());
        if (kind == GIFT) Logger.printDebug(() -> "Profile notes: a gift button names " + gift);
        return kind;
    }

    /** The first of [attributes]' string values that names a gift, cut to 200 characters, or null. */
    @Nullable
    static String giftNamedBy(@Nullable SparseArray<?> attributes) {
        if (attributes == null) return null;
        for (int index = 0, size = attributes.size(); index < size; index++) {
            Object value = attributes.valueAt(index);
            if (!(value instanceof String)) continue;
            String text = (String) value;
            boolean named = GIFT_ACTION.matcher(text).find();
            for (String route : GIFT_ROUTES) {
                named |= text.contains(route);
            }
            if (named) return text.length() > 200 ? text.substring(0, 200) : text;
        }
        return null;
    }

    /** Whether one of [attributes]' string values holds a marker. */
    static boolean namesANote(@Nullable SparseArray<?> attributes) {
        if (attributes == null) return false;
        for (int index = 0, size = attributes.size(); index < size; index++) {
            Object value = attributes.valueAt(index);
            if (!(value instanceof String)) continue;
            String text = (String) value;
            for (String marker : MARKERS) {
                if (text.contains(marker)) return true;
            }
        }
        return false;
    }

    /** [type]'s SparseArray field, its own or a superclass's, or null when it has none. */
    @Nullable
    public static Field attributes(Class<?> type) {
        Object known = ATTRIBUTES.get(type);
        if (known == null) {
            known = NO_FIELD;
            search:
            for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (field.getType() != SparseArray.class || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    known = field;
                    break search;
                }
            }
            ATTRIBUTES.put(type, known);
        }
        return known == NO_FIELD ? null : (Field) known;
    }

    /** Forgets every class and node answer, for tests. */
    static void forgetForTests() {
        ATTRIBUTES.clear();
        KINDS.clear();
    }
}
