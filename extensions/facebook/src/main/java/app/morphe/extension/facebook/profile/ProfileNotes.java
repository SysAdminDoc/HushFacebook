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
 * <p>Only a node's own string attributes are read, never the ones of its children, so a node that
 * holds the bubble somewhere below it, the profile picture's stack among them, is kept. A node's
 * answer is kept by the node, since the getters run on every layout pass.
 *
 * <p>It fails open: switch off, a pause, settings that aren't ready, or any failure in here, and
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

    /** Each class's attribute field, or {@link #NO_FIELD} for a class that has none. */
    private static final Map<Class<?>, Object> ATTRIBUTES = new ConcurrentHashMap<>();
    private static final Object NO_FIELD = new Object();

    /** Each node's answer. Bloks nodes keep Object's identity equality, so this is by node. */
    private static final Map<Object, Boolean> NOTES = Collections.synchronizedMap(new WeakHashMap<>());

    private ProfileNotes() {
    }

    /**
     * Injection point, before every return of the Bloks node class's child getters: the list the
     * getter would return, or the same list without its note bubble. Never throws.
     */
    public static List<?> children(@Nullable List<?> list) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_NOTES);
            if (list == null || list.isEmpty()) return list;
            if (!Utils.settingsReady() || !Settings.HIDE_PROFILE_NOTES.get()) return list;
            int size = list.size();
            List<Object> kept = null;
            for (int index = 0; index < size; index++) {
                Object child = list.get(index);
                if (isNote(child)) {
                    if (kept == null) kept = new ArrayList<>(list.subList(0, index));
                } else if (kept != null) {
                    kept.add(child);
                }
            }
            if (kept == null) return list;
            int removed = size - kept.size();
            FeedFilterCounters.removed(ROUTE, removed, REASON);
            Logger.printDebug(() -> "Profile notes: left out " + removed + " note bubble node(s)");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_NOTES, "Bloks children", failure);
            Logger.printException(() -> "Profile notes: could not read a Bloks node's children", failure);
            return list;
        }
    }

    /** Whether [node] is a Bloks node whose own string attributes name a note bubble. */
    static boolean isNote(@Nullable Object node) throws IllegalAccessException {
        if (node == null) return false;
        Field field = attributes(node.getClass());
        if (field == null) return false;
        Boolean known = NOTES.get(node);
        if (known != null) return known;
        boolean note = namesANote((SparseArray<?>) field.get(node));
        NOTES.put(node, note);
        if (note) HookStatus.bound(FamilyNames.PROFILE_NOTES, node.getClass().getName() + "#" + field.getName());
        return note;
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
        NOTES.clear();
    }
}
