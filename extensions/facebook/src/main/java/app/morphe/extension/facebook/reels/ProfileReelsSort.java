/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Most viewed first on a profile's Reels tab (#232). Facebook builds the tab's tiles from a list of
 * reel nodes the profile section loaded. With the switch on, the patch hands that list here before
 * the section binds it, and it is sorted in place by each reel's play count, the number Facebook
 * itself reads for a reel's view badge. Off, or when a count can't be read, the list is Facebook's.
 *
 * <p>The patch hands over the class that holds Facebook's play count reader and the reader's name,
 * so no obfuscated name is written down here. The reader takes the reel's video-story object; the
 * path from a list node to it is found by shape (a no-argument getter whose result type has a
 * no-argument getter of the reader's parameter type) and cached per node class. A reel with no
 * readable count keeps its place after those that have one, and equal counts keep Facebook's order.
 *
 * <p>Nothing here logs an id or a count.
 */
public final class ProfileReelsSort {
    private static final String FAMILY = FamilyNames.NEWEST_REELS_FIRST;
    static final String SORTED = "Profile reels sorted by views";
    static final String UNREADABLE = "profile reels play count";

    /** The two getters from a node to the reader's argument, cached by node class. */
    private static final Map<Class<?>, Method[]> paths = new WeakHashMap<>();

    private ProfileReelsSort() {
    }

    /**
     * The hook, just before the profile Reels section binds its list. Never throws.
     *
     * @param list   the loaded reel nodes, sorted in place; null when the section has none
     * @param holder the class holding Facebook's play count reader
     * @param reader the reader's name, a static method of one argument that answers an Integer
     */
    public static void sort(@Nullable List<?> list, @Nullable Class<?> holder, @Nullable String reader) {
        try {
            HookStatus.invoked(FAMILY);
            if (list == null || list.size() < 2 || holder == null || reader == null) return;
            if (!Utils.settingsReady() || !Settings.PROFILE_REELS_MOST_VIEWED.get()) return;
            Method read = reader(holder, reader);
            if (read == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no reader"));
                return;
            }
            sortBy(list, read);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, UNREADABLE, failure);
        }
    }

    @Nullable
    private static Method reader(Class<?> holder, String name) {
        for (Method method : holder.getDeclaredMethods()) {
            if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterTypes().length != 1 || method.getReturnType() != Integer.class) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void sortBy(List<?> list, Method read) {
        int size = list.size();
        long[] counts = new long[size];
        boolean any = false;
        for (int i = 0; i < size; i++) {
            counts[i] = count(list.get(i), read);
            if (counts[i] >= 0) any = true;
        }
        if (!any) {
            HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no count read"));
            return;
        }
        List<Integer> order = new ArrayList<>(size);
        for (int i = 0; i < size; i++) order.add(i);
        // Stable, so equal counts and the unread keep Facebook's order.
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Long.compare(counts[b], counts[a]);
            }
        });
        List<Object> copy = new ArrayList<>((List) list);
        List target = (List) list;
        for (int i = 0; i < size; i++) target.set(i, copy.get(order.get(i)));
        HookStatus.counted(FAMILY, SORTED);
    }

    /** The reel's play count, or -1 when it can't be read. */
    private static long count(@Nullable Object node, Method read) {
        if (node == null) return -1;
        try {
            Method[] path = pathFor(node, read);
            if (path == null) return -1;
            Object middle = path[0].invoke(node);
            if (middle == null) return -1;
            Object story = path[1].invoke(middle);
            if (story == null) return -1;
            Object value = read.invoke(null, story);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable failure) {
            return -1;
        }
    }

    @Nullable
    private static Method[] pathFor(Object node, Method read) {
        Class<?> type = node.getClass();
        synchronized (paths) {
            if (paths.containsKey(type)) return paths.get(type);
        }
        Class<?> wanted = read.getParameterTypes()[0];
        Method[] found = null;
        for (Method first : type.getMethods()) {
            if (first.getParameterTypes().length != 0 || !first.getReturnType().isInterface()) continue;
            for (Method second : first.getReturnType().getMethods()) {
                if (second.getParameterTypes().length == 0 && second.getReturnType() == wanted) {
                    found = new Method[] {first, second};
                    break;
                }
            }
            if (found != null) break;
        }
        synchronized (paths) {
            paths.put(type, found);
        }
        return found;
    }
}
