/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The walk over a page of Reels sections that the page filters share.
 *
 * <p>The Reels controller wraps each fetched page in section wrappers, and each wrapper holds its
 * own list of items, which is the list the screen reads. So a filter that only sees the flat item
 * collection one step later changes nothing on screen (the sponsored reels filter learned that on a
 * device, 2026-09-19), and every filter of a page runs here, over the list inside each section.
 *
 * <p>The item list is found by type and never by name: the field is {@code A01} today and another
 * name after the next Facebook release. It is the only {@code List} on the wrapper that holds
 * items. Items are removed in place when the list permits it, so every other holder of that list
 * agrees with the screen, and a list that refuses is replaced through its field. A section left
 * with nothing goes off the page, so the screen doesn't show an empty one.
 */
public final class ReelSections {
    private ReelSections() {
    }

    /** Whether an item comes off its section. Asked once per item. */
    public interface Drop {
        boolean drop(Object item);
    }

    /** What a walk over a page did: the page to go on with, and how many items came off it. */
    public static final class Result {
        /** The same page, or a copy without the sections the walk emptied. */
        public final List<?> page;
        public final int dropped;

        Result(List<?> page, int dropped) {
            this.page = page;
            this.dropped = dropped;
        }
    }

    /**
     * The same page with every item {@code drop} says so about taken out of its section. A section
     * the walk can't read stays as it came, with the item list it couldn't find or read named in
     * Hook status under {@code family}, and the error logged under {@code source}: the items in it
     * reach the screen, and nothing else would tell anyone why.
     */
    public static Result strip(List<?> page, Drop drop, String family, String source) {
        int dropped = 0;
        boolean sectionEmptied = false;
        ArrayList<Object> kept = new ArrayList<>(page.size());

        for (Object section : page) {
            int removed = stripItems(section, drop, family, source);
            dropped += removed;

            // A section that held only what came off is empty now. Off the page it goes, so the
            // screen doesn't show it.
            if (removed > 0 && !holdsAnyItem(section, family, source)) {
                sectionEmptied = true;
                continue;
            }

            kept.add(section);
        }

        return new Result(sectionEmptied ? kept : page, dropped);
    }

    /** Whether an item is the one looked for. Asked in page order until one says yes. */
    public interface Pick {
        boolean picks(Object item);
    }

    /**
     * The first item, in page order, that {@code pick} says yes to, read from the item lists the walk
     * reads, or null when there is none. A section that can't be read is passed over. Never throws.
     */
    public static Object firstItem(List<?> page, Pick pick) {
        for (Object section : page) {
            if (section == null) continue;
            try {
                for (Field field : section.getClass().getDeclaredFields()) {
                    List<?> items = itemsOf(field, section);
                    if (items == null) continue;
                    for (Object item : items) {
                        if (pick.picks(item)) return item;
                    }
                }
            } catch (Throwable ignored) {
                // The strip that follows reads this section again and records why it can't.
            }
        }
        return null;
    }

    /** The number of items {@code drop} took out of the item lists of the section. */
    private static int stripItems(Object section, Drop drop, String family, String source) {
        if (section == null) return 0;

        int removed = 0;

        try {
            // A section with no list of its own is a wrapper the filter can't see into, and the items
            // in it reach the screen. Nothing else reads the page at this level, so it is recorded.
            // A static list is no section's own, the way itemsOf reads it.
            boolean holdsAList = false;
            for (Field field : section.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && List.class.isAssignableFrom(field.getType())) {
                    holdsAList = true;
                }
                List<?> items = itemsOf(field, section);
                if (items == null || items.isEmpty()) continue;

                // Each item is asked once, so a filter that counts what it read counts it once.
                ArrayList<Object> dropping = new ArrayList<>();
                for (Object item : items) {
                    if (drop.drop(item)) dropping.add(item);
                }
                if (dropping.isEmpty()) continue;

                try {
                    // One call, so an immutable list refuses before it changes anything.
                    items.removeAll(dropping);
                } catch (UnsupportedOperationException immutable) {
                    ArrayList<Object> keptItems = new ArrayList<>(items.size() - dropping.size());
                    for (Object item : items) {
                        if (!holdsSame(dropping, item)) keptItems.add(item);
                    }
                    field.set(section, keptItems);
                }

                removed += dropping.size();
            }
            if (holdsAList) {
                HookStatus.bound(family, "section item list");
            } else {
                HookStatus.missingMember(family, "item list", section.getClass().getName(), "List field");
            }
        } catch (Throwable t) {
            HookStatus.threw(family, "section filter", t);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, source, () -> "could not filter a section", t);
        }

        return removed;
    }

    /** Whether the section still holds an item. An empty section can then go. */
    private static boolean holdsAnyItem(Object section, String family, String source) {
        try {
            for (Field field : section.getClass().getDeclaredFields()) {
                List<?> items = itemsOf(field, section);
                if (items != null && !items.isEmpty()) return true;
            }
        } catch (Throwable t) {
            HookStatus.threw(family, "section re-read", t);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, source, () -> "could not re-read a section", t);

            // Keep the section. A section that you cannot read is not a proven empty section.
            return true;
        }

        return false;
    }

    /**
     * The list this field holds, or null when the field holds no list. A static field is no
     * section's own items, and writing a static final one back is refused from Android 17 on.
     */
    private static List<?> itemsOf(Field field, Object section) throws IllegalAccessException {
        if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) return null;

        field.setAccessible(true);
        Object value = field.get(section);

        return value instanceof List ? (List<?>) value : null;
    }

    /** Whether {@code item} itself, not an equal one, is among {@code dropping}. */
    private static boolean holdsSame(List<Object> dropping, Object item) {
        for (Object candidate : dropping) {
            if (candidate == item) return true;
        }
        return false;
    }
}
