/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Search posts tab (#339): the Posts chip comes back to the row of chips over search results,
 * right after Reels.
 *
 * <p>Facebook's results page reads its chips from the search answer: each entry names the tab it
 * stands for (Reels, People, Groups and so on), and Facebook builds every tab chip itself from
 * that name, with its own label and the query that tab runs. Posts is still one of the tabs it
 * can build, with its own "Posts" label and the blended posts search, but the answer stopped
 * listing it. The patch hands this class the answer's entries before Facebook reads them. With
 * the switch on, an entry that names Posts goes in after Reels (or after the first tab when
 * there's no Reels), and Facebook builds that chip like any other. An answer with no tab entries,
 * such as a page of filters, or one that already has Posts, stays as it came.
 */
public final class SearchPostsTab {
    /** The tab this class adds, as Facebook's tab enum names it. */
    static final String POSTS = "POSTS";

    /** The tab Posts goes after. */
    static final String REELS = "REELS";

    /** The all-results tab, which Posts goes after when there's no Reels. */
    static final String TOP = "TOP";

    /** Tabs the enum must hold for its type to be taken as Facebook's tab enum. */
    static final String[] TAB_NAMES = {POSTS, REELS, "PEOPLE", "GROUPS"};

    /** The enum's constant for an entry that names no tab Facebook knows. */
    static final String UNSET = "UNSET_OR_UNRECOGNIZED_ENUM_VALUE";

    /** The member the report names once the answer's entries have been handed over. */
    static final String ENTRIES = "search answer entries";

    /** What the report counts each time Posts goes into the chips. */
    static final String ADDED = "Posts chip added";

    private static final String FAMILY = FamilyNames.SEARCH_POSTS_TAB;

    private SearchPostsTab() {
    }

    /**
     * The hook before Facebook reads the chips from a search answer, handed the answer's entries.
     * The same list comes back when nothing changes, and for a throw.
     */
    public static List<?> edges(List<?> edges) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, ENTRIES);
            if (edges == null || edges.isEmpty() || !Utils.settingsReady() || !Settings.SEARCH_POSTS_TAB.get()) {
                return edges;
            }
            Method tab = tabMethod(edges.get(0));
            if (tab == null) return edges;
            List<String> names = new ArrayList<>(edges.size());
            for (Object edge : edges) names.add(tabName(tab, edge));
            int at = insertAt(names);
            if (at < 0) {
                Logger.printDebug(() -> "Search posts tab: chips stay " + names);
                return edges;
            }
            Object posts = postsEntry(tab);
            if (posts == null) return edges;
            List<Object> out = new ArrayList<>(edges.size() + 1);
            out.addAll(edges);
            out.add(at, posts);
            HookStatus.counted(FAMILY, ADDED);
            Logger.printDebug(() -> "Search posts tab: Posts goes in at " + at + " of " + names);
            return out;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, ENTRIES, failure);
            return edges;
        }
    }

    /**
     * Where Posts goes among the entries' [tabs] (each entry's tab name, or null for one that
     * names none, like All), or -1 for no change: after Reels, else right before the first tab
     * (after it when that's Top). No change when no entry names a tab or one already names Posts.
     */
    static int insertAt(List<String> tabs) {
        int first = -1;
        int reels = -1;
        for (int i = 0; i < tabs.size(); i++) {
            String name = tabs.get(i);
            if (name == null || UNSET.equals(name)) continue;
            if (POSTS.equals(name)) return -1;
            if (first < 0) first = i;
            if (REELS.equals(name) && reels < 0) reels = i;
        }
        if (reels >= 0) return reels + 1;
        if (first < 0) return -1;
        return TOP.equals(tabs.get(first)) ? first + 1 : first;
    }

    /**
     * The method an entry answers its tab with: an interface method of [edge] taking nothing and
     * answering Facebook's tab enum. Null when there isn't exactly one.
     */
    @Nullable
    static Method tabMethod(Object edge) {
        if (edge == null) return null;
        Method found = null;
        for (Class<?> type : interfaces(edge.getClass())) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0 || Modifier.isStatic(method.getModifiers())) continue;
                if (!isTabEnum(method.getReturnType())) continue;
                if (found != null && !found.getName().equals(method.getName())) return null;
                if (found == null) found = method;
            }
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    /** Whether [type] is an enum holding every one of {@link #TAB_NAMES}. */
    static boolean isTabEnum(Class<?> type) {
        if (!type.isEnum()) return false;
        Set<String> names = new HashSet<>();
        for (Object constant : type.getEnumConstants()) names.add(((Enum<?>) constant).name());
        for (String name : TAB_NAMES) {
            if (!names.contains(name)) return false;
        }
        return true;
    }

    /** The tab [edge] names through [tab], or null for none. */
    @Nullable
    static String tabName(Method tab, Object edge) throws ReflectiveOperationException {
        if (edge == null || !tab.getDeclaringClass().isInstance(edge)) return null;
        Object value = tab.invoke(edge);
        return value instanceof Enum ? ((Enum<?>) value).name() : null;
    }

    /**
     * An entry of [tab]'s interface that names Posts and nothing else: every other answer is
     * empty, so Facebook builds the chip from the tab alone, as it does for every tab entry.
     */
    @Nullable
    static Object postsEntry(Method tab) {
        Class<?> type = tab.getDeclaringClass();
        Object posts = null;
        for (Object constant : tab.getReturnType().getEnumConstants()) {
            if (POSTS.equals(((Enum<?>) constant).name())) posts = constant;
        }
        if (posts == null) return null;
        Object answer = posts;
        String name = tab.getName();
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals":
                    if (method.getParameterTypes().length == 1) return args[0] == proxy;
                    break;
                case "hashCode":
                    if (method.getParameterTypes().length == 0) return System.identityHashCode(proxy);
                    break;
                case "toString":
                    if (method.getParameterTypes().length == 0) return "Hushfacebook Posts tab entry";
                    break;
                default:
                    break;
            }
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) return answer;
            return empty(method.getReturnType());
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** What a method answering [type] answers when it has nothing: null, or a primitive's zero. */
    @Nullable
    static Object empty(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return 0d;
    }

    /** Every interface [type] and its superclasses implement, their own superinterfaces included. */
    private static List<Class<?>> interfaces(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) pending.add(face);
        }
        while (!pending.isEmpty()) {
            Class<?> face = pending.poll();
            if (!seen.add(face)) continue;
            out.add(face);
            for (Class<?> parent : face.getInterfaces()) pending.add(parent);
        }
        return out;
    }
}
