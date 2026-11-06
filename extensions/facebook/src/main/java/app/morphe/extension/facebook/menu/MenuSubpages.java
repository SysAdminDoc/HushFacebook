/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Which Menus share a page stack, for the Keep Menu pages in step patch (issue #276).
 *
 * <p>Facebook keeps the pages its Menu opens in place (Settings and privacy, Help and support, a
 * folder of shortcuts) on one stack per Activity, and every Menu built in that Activity binds the
 * same stack: the Menu tab Facebook builds ahead of time and the side Menu are two section trees on
 * one stack. The stack remembers only the Menu that bound it last, and a push or a pop asks only
 * that one to build the new page. When that was the Menu off screen, the header on screen changed
 * and its Menu stayed.
 *
 * <p>Each root section's children build reports here right after it binds the stack, with its
 * section context and the tree that context belongs to, and a push or a pop asks {@link #others}
 * for the newest context of every other tree still alive on that stack, so each Menu builds the
 * page. One entry per tree, held weakly, so a Menu that's gone drops out.
 */
public final class MenuSubpages {
    private static final Object[] NONE = new Object[0];

    /** Per page stack, the newest section context of each tree that bound it. */
    private static final Map<Object, List<Bound>> BOUND = new WeakHashMap<>();

    private static final class Bound {
        final WeakReference<Object> tree;
        final WeakReference<Object> context;

        Bound(Object tree, Object context) {
            this.tree = new WeakReference<>(tree);
            this.context = new WeakReference<>(context);
        }
    }

    private MenuSubpages() {
    }

    /**
     * Called by the root section's children build right after it binds {@code stack} to
     * {@code context}, a context of {@code tree}. Replaces that tree's older context.
     */
    public static void bound(Object stack, Object context, Object tree) {
        if (stack == null || context == null) return;
        try {
            int trees;
            synchronized (BOUND) {
                List<Bound> list = BOUND.get(stack);
                if (list == null) {
                    list = new ArrayList<>(2);
                    BOUND.put(stack, list);
                }
                for (Iterator<Bound> it = list.iterator(); it.hasNext(); ) {
                    Bound bound = it.next();
                    Object oldContext = bound.context.get();
                    Object oldTree = bound.tree.get();
                    if (oldContext == null || oldContext == context || (tree != null && oldTree == tree)
                            || (tree == null && oldTree == null)) {
                        it.remove();
                    }
                }
                list.add(new Bound(tree, context));
                trees = list.size();
            }
            final int count = trees;
            Logger.printDebug(() -> "Menu subpages: bound stack " + id(stack) + " tree " + id(tree) + " context " + id(context)
                    + ", " + count + " tree(s) on it");
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not record the Menu bound to its page stack", failure);
        }
    }

    /**
     * The newest context of every tree on {@code stack} but the one {@code current} belongs to, which
     * Facebook asks itself. Empty when there's nothing else or anything goes wrong.
     */
    public static Object[] others(Object stack, Object current) {
        if (stack == null) return NONE;
        try {
            List<Object> others = new ArrayList<>(2);
            synchronized (BOUND) {
                List<Bound> list = BOUND.get(stack);
                if (list == null) return NONE;
                Object currentTree = null;
                for (Bound bound : list) {
                    if (bound.context.get() == current) currentTree = bound.tree.get();
                }
                for (Bound bound : list) {
                    Object context = bound.context.get();
                    if (context == null || context == current) continue;
                    Object tree = bound.tree.get();
                    if (tree != null && tree == currentTree) continue;
                    others.add(context);
                }
            }
            Logger.printDebug(() -> "Menu subpages: page change on stack " + id(stack) + ", current " + id(current)
                    + ", asking " + others.size() + " other Menu(s)");
            return others.isEmpty() ? NONE : others.toArray();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not list the other Menus on a page stack", failure);
            return NONE;
        }
    }

    private static String id(Object object) {
        return object == null ? "null" : Integer.toHexString(System.identityHashCode(object));
    }
}
