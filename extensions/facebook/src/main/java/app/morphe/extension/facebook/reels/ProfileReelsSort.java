/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Most viewed first on a profile's Reels tab (#232). The tab's tiles are drawn by the Reels tab
 * section of a profile or Page, and by the video grid section elsewhere, from a paginated list of reel
 * nodes. With the switch on, the patch hands that list to {@link #list} just before the section takes
 * it, and the section gets a copy carrying the
 * same paging with its loaded reels sorted by the play count Facebook itself reads for a reel's view
 * badge. The section's own handlers keep the list they were given, so loading the next page works
 * as before and the next bind sorts again. The reel viewer's section hands over its plain list the
 * same way, to be sorted in place ({@link #sort}). Off, or when a count can't be read, the list is
 * Facebook's.
 *
 * <p>The patch hands over the class that holds Facebook's play count reader and the reader's name,
 * so no obfuscated name is written down here. The reader takes the reel's video-story object; the
 * path from a list node to it is found by shape (the shortest chain of no-argument getters returning
 * interfaces that ends at the reader's parameter type) and cached per node class. A reel with no
 * readable count keeps its place after those that have one, and equal counts keep Facebook's order.
 *
 * <p>Every way the hook leaves a list alone, except the switch being off, is counted under the
 * family's report, so a report says why a profile wasn't sorted. Nothing here logs an id or a count.
 */
public final class ProfileReelsSort {
    private static final String FAMILY = FamilyNames.NEWEST_REELS_FIRST;
    static final String SORTED = "Profile reels sorted by views";
    static final String UNREADABLE = "profile reels play count";
    static final String NO_LIST = "Profile reels left alone: no list handed over";
    static final String TOO_SHORT = "Profile reels left alone: fewer than two reels loaded";
    static final String NO_PATH = "Profile reels left alone: no play count path";
    static final String IN_ORDER = "Profile reels already in order";

    /** The deepest chain of getters from a list node to the reader's argument. */
    private static final int MAX_HOPS = 8;

    /** The getters from a node to the reader's argument, cached by node class. */
    private static final Map<Class<?>, Method[]> paths = new WeakHashMap<>();

    private ProfileReelsSort() {
    }

    /**
     * The hook for the video grid section: the paginated list it is about to bind, or a copy with
     * the same paging and its loaded reels most viewed first. Never throws.
     *
     * @param paginated the profile's paginated list; null when there is none
     * @param holder    the class holding Facebook's play count reader
     * @param reader    the reader's name, a static method of one argument that answers an Integer
     */
    @Nullable
    public static Object list(@Nullable Object paginated, @Nullable Class<?> holder, @Nullable String reader) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.PROFILE_REELS_MOST_VIEWED.get()) return paginated;
            HookStatus.bound(FAMILY, "profile reels grid");
            if (paginated == null || holder == null || reader == null) {
                HookStatus.counted(FAMILY, NO_LIST);
                return paginated;
            }
            Method underlying = paginated.getClass().getMethod("underlyingList");
            Object rows = underlying.invoke(paginated);
            if (!(rows instanceof List)) {
                HookStatus.counted(FAMILY, NO_LIST);
                return paginated;
            }
            List<?> loaded = (List<?>) rows;
            if (loaded.size() < 2) {
                HookStatus.counted(FAMILY, TOO_SHORT);
                return paginated;
            }
            Method read = reader(holder, reader);
            if (read == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no reader"));
                return paginated;
            }
            int[] order = orderOf(loaded, read);
            if (order == null) return paginated;
            boolean moved = false;
            for (int i = 0; i < order.length; i++) moved |= order[i] != i;
            if (!moved) {
                HookStatus.counted(FAMILY, IN_ORDER);
                return paginated;
            }
            List<Object> sorted = new ArrayList<>(order.length);
            for (int index : order) sorted.add(loaded.get(index));
            Object copy = withRows(paginated, underlying.getReturnType(), sorted);
            if (copy == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no copy"));
                return paginated;
            }
            HookStatus.counted(FAMILY, SORTED);
            return copy;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, UNREADABLE, failure);
            return paginated;
        }
    }

    /**
     * The hook for the reel viewer's section, just before it binds its list. Sorts [list] in place;
     * never throws.
     *
     * @param list   the loaded reel nodes, sorted in place; null when the section has none
     * @param holder the class holding Facebook's play count reader
     * @param reader the reader's name, a static method of one argument that answers an Integer
     */
    public static void sort(@Nullable List<?> list, @Nullable Class<?> holder, @Nullable String reader) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.PROFILE_REELS_MOST_VIEWED.get()) return;
            if (list == null || holder == null || reader == null) {
                HookStatus.counted(FAMILY, NO_LIST);
                return;
            }
            if (list.size() < 2) {
                HookStatus.counted(FAMILY, TOO_SHORT);
                return;
            }
            Method read = reader(holder, reader);
            if (read == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no reader"));
                return;
            }
            int[] order = orderOf(list, read);
            if (order == null) return;
            List<Object> copy = new ArrayList<>(list);
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<Object> target = (List) list;
            for (int i = 0; i < order.length; i++) target.set(i, copy.get(order[i]));
            HookStatus.counted(FAMILY, SORTED);
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

    /**
     * A copy of [paginated] with [rows] as its list and its paging kept, or null when it can't be
     * made. [rowsType] is what its underlying list is declared as.
     */
    @Nullable
    private static Object withRows(Object paginated, Class<?> rowsType, List<Object> rows) throws Exception {
        Object list = rows;
        if (!rowsType.isAssignableFrom(ArrayList.class)) {
            Method copyOf = null;
            for (Method method : rowsType.getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals("copyOf") && Modifier.isStatic(method.getModifiers())
                        && types.length == 1 && types[0] == Collection.class) {
                    copyOf = method;
                    break;
                }
            }
            if (copyOf == null) return null;
            list = copyOf.invoke(null, rows);
        }
        for (Method method : paginated.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("withMetadata") && Modifier.isStatic(method.getModifiers())
                    && types.length == 2 && types[0].isInstance(list) && types[1].isInstance(paginated)) {
                return method.invoke(null, list, paginated);
            }
        }
        return null;
    }

    /**
     * The indexes of [list] most viewed first, equal counts and unread reels keeping their order,
     * or null (reported) when no count could be read.
     */
    @Nullable
    private static int[] orderOf(List<?> list, Method read) {
        int size = list.size();
        long[] counts = new long[size];
        boolean any = false;
        boolean pathless = true;
        for (int i = 0; i < size; i++) {
            Object node = list.get(i);
            if (node != null && pathFor(node, read) != null) pathless = false;
            counts[i] = count(node, read);
            if (counts[i] >= 0) any = true;
        }
        if (!any) {
            if (pathless) {
                HookStatus.counted(FAMILY, NO_PATH);
            } else {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no count read"));
            }
            return null;
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
        int[] result = new int[size];
        for (int i = 0; i < size; i++) result[i] = order.get(i);
        return result;
    }

    /** The reel's play count, or -1 when it can't be read. */
    private static long count(@Nullable Object node, Method read) {
        if (node == null) return -1;
        try {
            Method[] path = pathFor(node, read);
            if (path == null) return -1;
            Object step = node;
            for (Method getter : path) {
                step = getter.invoke(step);
                if (step == null) return -1;
            }
            Object value = read.invoke(null, step);
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
        Method[] found = search(type, read.getParameterTypes()[0]);
        synchronized (paths) {
            paths.put(type, found);
        }
        return found;
    }

    /**
     * The shortest chain of no-argument getters from [start] that ends at an object of [wanted],
     * each step returning an interface, or null.
     */
    @Nullable
    static Method[] search(Class<?> start, Class<?> wanted) {
        ArrayDeque<Method[]> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        seen.add(start);
        queue.add(new Method[0]);
        while (!queue.isEmpty()) {
            Method[] path = queue.poll();
            if (path.length >= MAX_HOPS) continue;
            Class<?> from = path.length == 0 ? start : path[path.length - 1].getReturnType();
            for (Method getter : from.getMethods()) {
                if (getter.getParameterTypes().length != 0 || Modifier.isStatic(getter.getModifiers())) continue;
                Class<?> next = getter.getReturnType();
                if (!next.isInterface() || next.getName().startsWith("java.")) continue;
                Method[] longer = new Method[path.length + 1];
                System.arraycopy(path, 0, longer, 0, path.length);
                longer[path.length] = getter;
                if (next == wanted) {
                    for (Method step : longer) {
                        try {
                            step.setAccessible(true);
                        } catch (Throwable ignored) {
                            // Invoked as it is.
                        }
                    }
                    return longer;
                }
                if (seen.add(next)) queue.add(longer);
            }
        }
        return null;
    }
}
