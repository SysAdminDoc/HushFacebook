/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.Settings;

/**
 * The feed guard's rule for the units picked by type name in Feed types to hide, the list for
 * people who read Debug logging. Facebook adds kinds of card between releases that no switch names
 * yet, and Debug logging already records each kind's type name ({@code FriendsLocationsFeedUnit},
 * {@code GroupsYouShouldJoinFeedUnit}). A name picked here hides units of exactly that type, and
 * says which type a switch is owed for.
 *
 * <p>The rule runs after every built-in one, so a unit a switch hides counts under that switch,
 * and before the seen rule. The first post after a link (#306) stays as it does for every other
 * rule. An ordinary post ({@link #STORY}) is never offered or matched: picking it
 * would empty Home and keep the feed asking for more.
 *
 * <p>The list offers the types that got past every other rule since Facebook started, kept in
 * memory only, then the ones picked earlier, so a person picks from real names and a type a switch
 * already hides isn't offered.
 */
public final class FeedTypeNames {
    /** A hidden unit's reason, so the Debug report's Removed line names the rule and the type. */
    static final String REASON = "type name ";

    /** The ordinary post's type, never hidden by name. */
    static final String STORY = "Story";

    /** At most this many types are offered, past every kind of card Home shows. */
    static final int SEEN_LIMIT = 64;

    /**
     * The longest the saved list gets, in chars, room for dozens of type names and little enough
     * that a settings file holding it beside the other lists still fits.
     */
    public static final int MAX_HIDDEN_CHARS = 1024;

    /** A GraphQL type name: letters, digits and underscores, starting with a letter. */
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}");

    /** The types handed to the rule since Facebook started, in the order they came. */
    private static final Set<String> SEEN = new LinkedHashSet<>();

    /** The saved list and its parse, together, so a change to the list parses it once. */
    private static final class Parsed {
        final String saved;
        final Set<String> types;

        Parsed(String saved, Set<String> types) {
            this.saved = saved;
            this.types = types;
        }
    }

    private static volatile Parsed parsed = new Parsed("", Collections.emptySet());

    private FeedTypeNames() {
    }

    /**
     * The rule: [type]'s reason when it's on the list, else null. Writes [type] down for the list
     * first, so a type nobody picked yet can be.
     */
    @Nullable
    static String reason(@Nullable String type) {
        if (type == null || !isType(type)) return null;
        synchronized (SEEN) {
            if (SEEN.size() < SEEN_LIMIT) SEEN.add(type);
        }
        String saved = Settings.HIDDEN_FEED_TYPES.get();
        if (saved.isEmpty()) return null;
        Parsed now = parsed;
        if (!now.saved.equals(saved)) parsed = now = new Parsed(saved, parse(saved));
        return now.types.contains(type) ? REASON + type : null;
    }

    /** The types the list offers: the ones seen since Facebook started, then any picked earlier. */
    public static List<String> choices() {
        Set<String> all;
        synchronized (SEEN) {
            all = new LinkedHashSet<>(SEEN);
        }
        all.addAll(hidden());
        return new ArrayList<>(all);
    }

    /** The types picked, as saved. */
    public static Set<String> hidden() {
        return parse(Settings.HIDDEN_FEED_TYPES.savedValue());
    }

    /** What {@link #hide} did with a pick. */
    public enum Hidden {
        SAVED,
        /** The types need more than {@link #MAX_HIDDEN_CHARS}. Nothing was saved. */
        NO_ROOM,
        /** The phone didn't keep the write. The old list stands, live and on disk. */
        NOT_STORED
    }

    /**
     * Saves [types] as the ones to hide, dropping anything that isn't a type. When they need more
     * than {@link #MAX_HIDDEN_CHARS}, it saves nothing and says so, so a pick is never lost in silence.
     */
    public static Hidden hide(Collection<String> types) {
        String picked = String.join(",", types);
        String kept = clean(picked);
        if (count(kept) < count(picked)) return Hidden.NO_ROOM;
        return Settings.HIDDEN_FEED_TYPES.save(kept) ? Hidden.SAVED : Hidden.NOT_STORED;
    }

    /**
     * [saved] as the list is stored: each type once, in order, as far as {@link #MAX_HIDDEN_CHARS}
     * reaches. A type this build has never seen is kept like any other, so a settings file from a
     * newer Facebook comes through.
     */
    public static String clean(@Nullable String saved) {
        StringBuilder list = new StringBuilder();
        for (String type : parse(saved)) {
            int length = list.length() == 0 ? type.length() : list.length() + 1 + type.length();
            if (length > MAX_HIDDEN_CHARS) break;
            if (list.length() > 0) list.append(',');
            list.append(type);
        }
        return list.toString();
    }

    /** Whether [list] is a list exactly as {@link #clean} stores it. */
    public static boolean isClean(@Nullable String list) {
        return list != null && list.equals(clean(list));
    }

    /** How many types [list] names. */
    public static int count(@Nullable String list) {
        return parse(list).size();
    }

    /** The types in a saved list, in order, each once. Anything that isn't a type, and {@link #STORY}, is dropped. */
    static Set<String> parse(@Nullable String saved) {
        Set<String> types = new LinkedHashSet<>();
        if (saved == null) return types;
        for (String part : saved.split(",")) {
            String type = part.trim();
            if (isType(type)) types.add(type);
        }
        return types;
    }

    private static boolean isType(String type) {
        return !STORY.equals(type) && TYPE.matcher(type).matches();
    }

    static void clearForTests() {
        synchronized (SEEN) {
            SEEN.clear();
        }
        parsed = new Parsed("", Collections.emptySet());
    }
}
