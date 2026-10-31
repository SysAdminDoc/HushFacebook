/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
 * Most viewed first on a profile's Reels tab (#232). The video grid section draws its tiles from a
 * paginated list of reel nodes. With the switch on, the patch hands that list to {@link #list} just
 * before the section takes it, and the section gets a copy carrying the same paging with its loaded
 * reels sorted by the play count Facebook itself reads for a reel's view badge. The section's own
 * handlers keep the list they were given, so loading the next page works as before and the next bind
 * sorts again. The Reels tab of a profile or Page builds its tiles from the rows of Facebook's own
 * model instead, so the patch hands those rows to {@link #rows} where the tab reads them, and the tab
 * gets a sorted copy of the same kind. A tile opens its reel by id, so the order costs nothing there.
 * The reel viewer's section hands over its plain list the same way, to be sorted in place
 * ({@link #sort}). Off, or when a count can't be read, the list is Facebook's.
 *
 * <p>The patch hands over the class that holds Facebook's play count reader and a names string,
 * "reader/getter": the reader's name, then the name Facebook's tree models give their play count
 * getter (a no-argument int method). No obfuscated name is written down here. A count is read by
 * walking no-argument getters returning interfaces from a list node, shortest chains first, either
 * to an object answering the play count getter or to the reader's argument, and taking the first
 * chain that reads. The Reels tab of a Page carries its count on a different branch than the one
 * the reader takes (empty there), which is why the getter comes first. The chains are cached per
 * node class. A reel with no readable count keeps its place after those that have one, and equal
 * counts keep Facebook's order.
 *
 * <p>Every way the hook leaves a list alone, except the switch being off, is counted under the
 * family's report, so a report says why a profile wasn't sorted. Nothing here logs an id.
 */
public final class ProfileReelsSort {
    private static final String FAMILY = FamilyNames.NEWEST_REELS_FIRST;
    static final String SORTED = "Profile reels sorted by views";
    static final String UNREADABLE = "profile reels play count";
    static final String NO_LIST = "Profile reels left alone: no list handed over";
    static final String TOO_SHORT = "Profile reels left alone: fewer than two reels loaded";
    static final String NO_PATH = "Profile reels left alone: no play count path";
    static final String NO_COUNT = "Profile reels left alone: no play count read";
    static final String IN_ORDER = "Profile reels already in order";

    /** The deepest chain of getters from a list node to its count. */
    private static final int MAX_HOPS = 8;

    /** The most chains of each kind kept for one node class. */
    private static final int MAX_PATHS = 8;

    /** How many ways one type is walked on from. */
    private static final int WALKS = 2;

    /** The chains from a node to its count, cached by node class. */
    private static final Map<Class<?>, Paths> paths = new WeakHashMap<>();

    /** The chains found for one node class, and what they were searched for. */
    private static final class Paths {
        @Nullable final Class<?> wanted;
        @Nullable final String getter;
        final List<Method[]> found;

        Paths(@Nullable Class<?> wanted, @Nullable String getter, List<Method[]> found) {
            this.wanted = wanted;
            this.getter = getter;
            this.found = found;
        }

        boolean matches(@Nullable Class<?> wanted, @Nullable String getter) {
            return this.wanted == wanted && (this.getter == null ? getter == null : this.getter.equals(getter));
        }
    }

    private ProfileReelsSort() {
    }

    /**
     * The hook for the video grid section and the Reels tab: the paginated list it is about to bind,
     * or a copy with the same paging and its loaded reels most viewed first. Never throws.
     *
     * @param paginated the profile's paginated list; null when there is none
     * @param holder    the class holding Facebook's play count reader
     * @param names     "reader/getter": the reader's name, a static method of one argument that
     *                  answers an Integer, then the tree models' play count getter; the getter may
     *                  be left out
     */
    @Nullable
    public static Object list(@Nullable Object paginated, @Nullable Class<?> holder, @Nullable String names) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.PROFILE_REELS_MOST_VIEWED.get()) return paginated;
            HookStatus.bound(FAMILY, "profile reels grid");
            if (paginated == null || holder == null || names == null) {
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
            Method read = reader(holder, readerName(names));
            String getter = getterName(names);
            if (read == null && getter == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no reader"));
                return paginated;
            }
            int[] order = orderOf(loaded, read, getter);
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
     * The hook for the Reels tab of a profile or a Page, where it reads the tiles from Facebook's own
     * model: [rows] with the reels most viewed first, as a list of the same kind, or [rows] itself.
     * Never throws.
     *
     * @param rows   the tab's rows, each a reel node or a holder of one; null when there are none
     * @param holder the class holding Facebook's play count reader
     * @param names  "reader/getter", as {@link #list} takes it
     */
    @Nullable
    public static List<?> rows(@Nullable List<?> rows, @Nullable Class<?> holder, @Nullable String names) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.PROFILE_REELS_MOST_VIEWED.get()) return rows;
            HookStatus.bound(FAMILY, "profile reels tab");
            if (rows == null || holder == null || names == null) {
                HookStatus.counted(FAMILY, NO_LIST);
                return rows;
            }
            if (rows.size() < 2) {
                HookStatus.counted(FAMILY, TOO_SHORT);
                return rows;
            }
            Method read = reader(holder, readerName(names));
            String getter = getterName(names);
            if (read == null && getter == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no reader"));
                return rows;
            }
            int[] order = orderOf(rows, read, getter);
            if (order == null) return rows;
            boolean moved = false;
            for (int i = 0; i < order.length; i++) moved |= order[i] != i;
            if (!moved) {
                HookStatus.counted(FAMILY, IN_ORDER);
                return rows;
            }
            List<Object> sorted = new ArrayList<>(order.length);
            for (int index : order) sorted.add(rows.get(index));
            Object copy = as(rows.getClass(), sorted);
            if (!(copy instanceof List)) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no copy"));
                return rows;
            }
            HookStatus.counted(FAMILY, SORTED);
            return (List<?>) copy;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, UNREADABLE, failure);
            return rows;
        }
    }

    /**
     * The hook for the reel viewer's section, just before it binds its list. Sorts [list] in place;
     * never throws.
     *
     * @param list   the loaded reel nodes, sorted in place; null when the section has none
     * @param holder the class holding Facebook's play count reader
     * @param names  "reader/getter", as {@link #list} takes it
     */
    public static void sort(@Nullable List<?> list, @Nullable Class<?> holder, @Nullable String names) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.PROFILE_REELS_MOST_VIEWED.get()) return;
            if (list == null || holder == null || names == null) {
                HookStatus.counted(FAMILY, NO_LIST);
                return;
            }
            if (list.size() < 2) {
                HookStatus.counted(FAMILY, TOO_SHORT);
                return;
            }
            Method read = reader(holder, readerName(names));
            String getter = getterName(names);
            if (read == null && getter == null) {
                HookStatus.threw(FAMILY, UNREADABLE, new IllegalStateException("no reader"));
                return;
            }
            int[] order = orderOf(list, read, getter);
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

    private static String readerName(String names) {
        int slash = names.indexOf('/');
        return slash < 0 ? names : names.substring(0, slash);
    }

    @Nullable
    private static String getterName(String names) {
        int slash = names.indexOf('/');
        return slash < 0 || slash == names.length() - 1 ? null : names.substring(slash + 1);
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
        Object list = as(rowsType, rows);
        if (list == null) return null;
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
     * [rows] as a list of [type]: itself when an ArrayList will do, else by the type's static
     * copyOf(Collection), or null when it has none.
     */
    @Nullable
    private static Object as(Class<?> type, List<Object> rows) throws Exception {
        if (type.isAssignableFrom(ArrayList.class)) return rows;
        for (Method method : type.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("copyOf") && Modifier.isStatic(method.getModifiers())
                    && types.length == 1 && types[0] == Collection.class) {
                return method.invoke(null, rows);
            }
        }
        return null;
    }

    /**
     * [item], or the one object it holds when [item] has no way to a count itself: the Reels tab's
     * rows hold their tab item in a field.
     */
    @Nullable
    private static Object countable(@Nullable Object item, @Nullable Method read, @Nullable String getter) {
        if (item == null || !pathsFor(item, read, getter).isEmpty()) return item;
        Field only = null;
        for (Field field : item.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            if (only != null) return item;
            only = field;
        }
        if (only == null) return item;
        try {
            only.setAccessible(true);
            Object held = only.get(item);
            return held == null ? item : held;
        } catch (Throwable unreadable) {
            return item;
        }
    }

    /**
     * The indexes of [list] most viewed first, equal counts and unread reels keeping their order,
     * or null (counted) when no count could be read.
     */
    @Nullable
    private static int[] orderOf(List<?> list, @Nullable Method read, @Nullable String getter) {
        int size = list.size();
        long[] counts = new long[size];
        boolean any = false;
        boolean pathless = true;
        for (int i = 0; i < size; i++) {
            Object node = countable(list.get(i), read, getter);
            List<Method[]> chains = node == null ? Collections.<Method[]>emptyList() : pathsFor(node, read, getter);
            if (!chains.isEmpty()) pathless = false;
            counts[i] = count(node, read, chains);
            if (counts[i] >= 0) any = true;
        }
        if (!any) {
            String reason = pathless ? NO_PATH : NO_COUNT;
            HookStatus.counted(FAMILY, reason);
            Object first = countable(list.get(0), read, getter);
            Logger.printDebug(() -> "Newest reels first: " + reason + " from "
                    + (first == null ? "null" : first.getClass().getName() + describe(pathsFor(first, read, getter))));
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
        Object first = countable(list.get(0), read, getter);
        Logger.printDebug(() -> "Newest reels first: views " + Arrays.toString(counts) + " from "
                + (first == null ? "null" : first.getClass().getName() + describe(pathsFor(first, read, getter))));
        return result;
    }

    /** The getter names of each chain, for the debug log. */
    private static String describe(List<Method[]> chains) {
        StringBuilder out = new StringBuilder();
        for (Method[] chain : chains) {
            out.append(" [");
            for (int i = 0; i < chain.length; i++) out.append(i == 0 ? "" : ".").append(chain[i].getName());
            out.append(']');
        }
        return out.toString();
    }

    /** The reel's play count by the first of [chains] that reads one, or -1. */
    private static long count(@Nullable Object node, @Nullable Method read, List<Method[]> chains) {
        if (node == null) return -1;
        for (Method[] chain : chains) {
            try {
                Object step = node;
                int last = chain.length - 1;
                boolean byGetter = last >= 0 && chain[last].getReturnType() == int.class;
                for (int i = 0; i < (byGetter ? last : chain.length) && step != null; i++) {
                    step = chain[i].invoke(step);
                }
                if (step == null) continue;
                Object value = byGetter ? chain[last].invoke(step) : read == null ? null : read.invoke(null, step);
                if (value instanceof Integer && (Integer) value >= 0) return (Integer) value;
            } catch (Throwable ignored) {
                // The next chain is tried.
            }
        }
        return -1;
    }

    private static List<Method[]> pathsFor(Object node, @Nullable Method read, @Nullable String getter) {
        Class<?> type = node.getClass();
        Class<?> wanted = read == null ? null : read.getParameterTypes()[0];
        synchronized (paths) {
            Paths cached = paths.get(type);
            if (cached != null && cached.matches(wanted, getter)) return cached.found;
        }
        List<Method[]> found = search(type, wanted, getter);
        synchronized (paths) {
            paths.put(type, new Paths(wanted, getter, found));
        }
        return found;
    }

    /** The shortest chain of no-argument interface getters from [start] to an object of [wanted], or null. */
    @Nullable
    static Method[] search(Class<?> start, Class<?> wanted) {
        List<Method[]> found = search(start, wanted, null);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The chains of no-argument getters from [start], each step returning an interface, that reach
     * a count, shortest first: those ending in the no-argument int method named [getter], then those
     * ending at an object of [wanted]. Either kind is skipped when its target is null.
     */
    static List<Method[]> search(Class<?> start, @Nullable Class<?> wanted, @Nullable String getter) {
        List<Method[]> byGetter = new ArrayList<>();
        List<Method[]> byReader = new ArrayList<>();
        ArrayDeque<Method[]> queue = new ArrayDeque<>();
        Map<Class<?>, Integer> walked = new HashMap<>();
        reach(new Method[0], start, wanted, getter, byGetter, byReader);
        queue.add(new Method[0]);
        while (!queue.isEmpty()) {
            Method[] path = queue.poll();
            if (path.length >= MAX_HOPS) continue;
            Class<?> from = path.length == 0 ? start : path[path.length - 1].getReturnType();
            for (Method step : from.getMethods()) {
                if (step.getParameterTypes().length != 0 || Modifier.isStatic(step.getModifiers())) continue;
                Class<?> next = step.getReturnType();
                if (!next.isInterface() || next.getName().startsWith("java.") || onPath(path, start, next)) continue;
                Method[] longer = append(path, step);
                reach(longer, next, wanted, getter, byGetter, byReader);
                // A type is walked from more than one way, since the first branch to reach it can be empty.
                int times = walked.containsKey(next) ? walked.get(next) : 0;
                if (times < WALKS) {
                    walked.put(next, times + 1);
                    queue.add(longer);
                }
            }
        }
        List<Method[]> found = new ArrayList<>(byGetter);
        found.addAll(byReader);
        return found;
    }

    /** Keeps [path] as a chain when [type], where it ends, answers the play count or is the reader's argument. */
    private static void reach(Method[] path, Class<?> type, @Nullable Class<?> wanted, @Nullable String getter,
                              List<Method[]> byGetter, List<Method[]> byReader) {
        if (getter != null && byGetter.size() < MAX_PATHS) {
            Method count = intGetter(type, getter);
            if (count != null) byGetter.add(accessible(append(path, count)));
        }
        if (wanted != null && byReader.size() < MAX_PATHS && wanted.isAssignableFrom(type)) {
            byReader.add(accessible(path));
        }
    }

    private static boolean onPath(Method[] path, Class<?> start, Class<?> type) {
        if (type == start) return true;
        for (Method step : path) {
            if (step.getReturnType() == type) return true;
        }
        return false;
    }

    @Nullable
    private static Method intGetter(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            return method.getReturnType() == int.class && !Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Method[] append(Method[] path, Method step) {
        Method[] longer = new Method[path.length + 1];
        System.arraycopy(path, 0, longer, 0, path.length);
        longer[path.length] = step;
        return longer;
    }

    private static Method[] accessible(Method[] path) {
        for (Method step : path) {
            try {
                step.setAccessible(true);
            } catch (Throwable ignored) {
                // Invoked as it is.
            }
        }
        return path;
    }
}
