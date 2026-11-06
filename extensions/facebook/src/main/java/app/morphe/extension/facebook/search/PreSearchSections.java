/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.download.PostDetails;
import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * The rows on the search screen before anything is typed (#80): the For you section leaves while
 * Hide For you in search is on, People you may know while its own switch is on, and with Debug
 * logging on every row is named.
 *
 * <p>Facebook draws each row from a server-built entry. The patch hands over each entry's node and
 * the module tree read off it as the row is drawn, and draws the module this answers, an empty row
 * for null. Facebook 584 tests the node for null before it reads the module, so there the patch hands
 * over the node alone, and a null node is drawn as the same empty row. The For you section, the searches Facebook suggests with a Refresh button, is one entry
 * whose node is a {@link #NULL_STATE_NODE} and whose {@code rendered_modules} string, which Facebook
 * hands its own performance logger, names {@link #FOR_YOU_MODULE} (583 on the S22, 2026-10-20).
 * People you may know is an entry of the same node type whose string names
 * {@link #PEOPLE_YOU_MAY_KNOW_MODULE} (a reporter's Debug log, 583, 2026-10-22), and it leaves while
 * Hide "People you may know" in search is on. Recent searches don't come through here.
 *
 * <p>The section draws only the entries after the first page. Facebook can draw that first page
 * itself, straight into the list (its "nullstate_nt_head_network" builder), and on some accounts every
 * row comes that way (#285), so the patch hands those nodes to {@link #headNode} too, where a null
 * node leaves the entry out. Facebook's older null-state query reads nodes of the same type, and its
 * entries come through {@link #queryNode} the same way. The Debug line ends with which builder handed
 * the node over: {@link #FROM_LIST}, {@link #FROM_HEAD} or {@link #FROM_QUERY}.
 *
 * <p>The query's nodes carry no {@code rendered_modules} (a reporter's 0.20.0 log, #285). Their
 * native template still names its module in each row's logging data, after a {@code module_name}
 * key (the S22's For you template, 585, 2026-11-03, names TRENDING_QUERY_SUGGESTION there), so a
 * node without the string is matched by the module names its template carries, and its Debug line
 * lists them as {@code template_modules}.
 *
 * <p>Not every query row has that key: on the S22 the Visited entities row names VISITED_ENTITIES
 * only as a value deeper in its template (585, 2026-11-05), and a reporter's For you and People you
 * may know stayed with nothing but RECENT_SEARCH and one unnamed row in the log (#80, 0.21.0). A node
 * whose template names no module is matched by {@link #FOR_YOU_MODULE} or
 * {@link #PEOPLE_YOU_MAY_KNOW_MODULE} standing whole anywhere in its template, and its Debug line lists
 * the constants the template carries as {@code template_names}, so two unnamed rows no longer log as
 * one line.
 *
 * <p>With Debug logging on, this logs the GraphQL type names of the node and the module, read through
 * {@link FeedFilter#typeName}'s checks, and the module names. Only names survive: a type or module
 * name that isn't letters, underscores and at most four digits is logged as "other", so no id, title
 * or search text gets out. Each distinct line is logged once, up to {@link #MAX_SHAPES}, and after
 * that no entry is read for the log.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the module or node is
 * Facebook's own.
 */
public final class PreSearchSections {
    static final int MAX_SHAPES = 32;
    static final int MAX_MODULES = 8;
    private static final int MAX_NAME = 64;
    private static final int MAX_DIGITS = 4;
    static final String PREFIX = "Search screen before typing: ";
    /** A tree keys a field by its name's hashCode. */
    private static final int RENDERED_MODULES = "rendered_modules".hashCode();
    private static final int TEMPLATES_VIEW = "native_templates_view".hashCode();
    private static final int TEMPLATE_BUNDLES = "native_template_bundles".hashCode();
    private static final int TEMPLATE_TREE = "nt_bundle_tree".hashCode();
    /** A module name in a template's logging data: the {@code module_name} key, its quotes and colon, the name. */
    private static final Pattern TEMPLATE_MODULE = Pattern.compile("module_name[^A-Za-z0-9_]{1,40}([A-Z][A-Z0-9_]*)(?![A-Za-z0-9_])");
    /** A constant in a template, the way the server names modules and row kinds: capitals and digits joined by underscores, read whole. */
    private static final Pattern TEMPLATE_CONSTANT = Pattern.compile("(?<![A-Za-z0-9_])[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+(?![A-Za-z0-9_])");
    /** How many distinct constants are read off one node's templates at most. */
    static final int MAX_TEMPLATE_NAMES = 256;

    /** The node type of the For you and People you may know entries. */
    static final String NULL_STATE_NODE = "SearchNullStateNativeTemplatesResult";
    /** The module name the For you entry's node lists in its {@code rendered_modules}. */
    static final String FOR_YOU_MODULE = "TRENDING_QUERY_SUGGESTION";
    /** The module name the People you may know entry's node lists in its {@code rendered_modules}. */
    static final String PEOPLE_YOU_MAY_KNOW_MODULE = "PEOPLE_YOU_MAY_KNOW";
    /** Counted under the patch's name each time the For you entry is drawn empty. */
    static final String HIDDEN = "Search screen drawn without For you";
    /** Counted under the patch's name each time the People you may know entry is drawn empty. */
    static final String HIDDEN_PEOPLE = "Search screen drawn without People you may know";

    /** Counted and logged each time the list is handed to Facebook without a next page (#235). */
    static final String PAGING_STOPPED = "Search screen: stopped paging, every loaded row hidden";

    /** How the Debug line names the builder that handed a node over. */
    static final String FROM_LIST = "list";
    static final String FROM_HEAD = "head";
    static final String FROM_QUERY = "query";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;
    private static final String HOOK = "search screen entry";

    private static final ConcurrentHashMap<Class<?>, Object> STRING_READERS = new ConcurrentHashMap<>();
    private static final Set<String> shapes = new HashSet<>();
    private static boolean budgetNoticed;
    /** Set once the budget is spent, so later entries aren't read at all. */
    private static volatile boolean full;
    /**
     * The diagnostic clear the logged lines belong to. A clear empties the report, so every line
     * logs again after one: Facebook builds the search screen once at start, and a report taken after
     * a clear otherwise had no line for it (#285).
     */
    private static long shapesGeneration = -1;

    private PreSearchSections() {
    }

    /**
     * Injection point, in the pre-search section's item case, right after the module tree is read
     * off an entry's node. Logs both with Debug logging on, then answers the module to draw. Never throws.
     *
     * @param node   the entry's node, never null where the patch calls this.
     * @param module the module tree the row is drawn from, null when Facebook draws an empty row.
     * @return the module tree to draw, null for Facebook's empty row.
     */
    @Nullable
    public static Object entry(@Nullable Object node, @Nullable Object module) {
        log(node, module, true, null);
        return hides(node, module != null) ? null : module;
    }

    /**
     * Injection point in 584's item case, right after an entry's node is read and before Facebook
     * tests it for null and reads its module. Logs the node with Debug logging on, then answers the
     * node to draw. Never throws.
     *
     * @param node the entry's node, null when Facebook draws an empty row.
     * @return the node to draw, null for Facebook's empty row.
     */
    @Nullable
    public static Object node(@Nullable Object node) {
        return byNode(node, FROM_LIST);
    }

    /**
     * Injection point in the head builder (#285), right after it reads an entry's node and before
     * Facebook tests it for null. The head draws the first page of entries straight into the list,
     * never through the section, and skips an entry whose node is null. Never throws.
     *
     * @param node the entry's node, null when Facebook skips the entry.
     * @return the node to draw, null to skip the entry.
     */
    @Nullable
    public static Object headNode(@Nullable Object node) {
        return byNode(node, FROM_HEAD);
    }

    /**
     * Injection point in the null-state query's entry reader (#285), right after it reads an entry's
     * node as a {@link #NULL_STATE_NODE} and before Facebook tests it for null. That query's entries
     * are drawn straight into the list, and an entry whose node is null is left out. Never throws.
     *
     * @param node the entry's node, null when Facebook leaves the entry out.
     * @return the node to draw, null to leave the entry out.
     */
    @Nullable
    public static Object queryNode(@Nullable Object node) {
        return byNode(node, FROM_QUERY);
    }

    /**
     * Injection point in the section's build, right after it reads its paginated list and before it
     * hands the list to Facebook's paging (#235). A hidden row is drawn with no height, so a list whose
     * every row is hidden keeps its end in view, loads the next page, gets the same hidden rows back
     * and loops with a spinner under "No recent searches". While a hide switch is on and every row
     * loaded so far is hidden, this answers a copy of the list that says it has no next page. Any
     * visible row, both switches off, Hushfacebook paused or anything unreadable leaves Facebook's
     * list as it is. Never throws.
     *
     * @param list     the paginated list, null when the section has none.
     * @param accessor the name of the entry's no-argument node accessor, as the patch read it.
     * @return the list to page, Facebook's own unless every row of it is hidden.
     */
    @Nullable
    public static Object list(@Nullable Object list, @Nullable String accessor) {
        try {
            HookStatus.invoked(FAMILY);
            if (list == null || accessor == null || !Utils.settingsReady()) return list;
            boolean forYou = Settings.HIDE_SEARCH_FOR_YOU.get();
            boolean people = Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.get();
            Object stopped = withoutPaging(list, accessor, forYou, people);
            if (stopped == list) return list;
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, PAGING_STOPPED);
            Logger.printDebug(() -> PAGING_STOPPED);
            return stopped;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return list;
        }
    }

    /**
     * [list] itself, or a copy without paging when it has a next page, has rows, and every row is
     * one a switch hides (an entry with no node is drawn empty too). Reads nothing with both off.
     */
    static Object withoutPaging(Object list, String accessor, boolean forYou, boolean people) throws Exception {
        if (!forYou && !people) return list;
        Class<?> type = list.getClass();
        if (!Boolean.TRUE.equals(type.getMethod("hasNextPage").invoke(list))) return list;
        Method rows = type.getMethod("underlyingList");
        Object loaded = rows.invoke(list);
        if (!(loaded instanceof List) || ((List<?>) loaded).isEmpty()) return list;
        for (Object entry : (List<?>) loaded) {
            if (entry == null) continue;
            Object node = entry.getClass().getMethod(accessor).invoke(entry);
            if (node != null && hidden(node, forYou, people) == null) return list;
        }
        Object copy = type.getMethod("withoutPaging", rows.getReturnType()).invoke(null, loaded);
        return copy == null ? list : copy;
    }

    private static Object byNode(@Nullable Object node, String from) {
        if (node != null) log(node, null, false, from);
        return hides(node, node != null) ? null : node;
    }

    /** Whether a row that would be drawn from the node is one a switch hides. Counts it when it is. */
    private static boolean hides(@Nullable Object node, boolean drawn) {
        try {
            HookStatus.invoked(FAMILY);
            if (!drawn || !Utils.settingsReady()) return false;
            String hidden = hidden(node, Settings.HIDE_SEARCH_FOR_YOU.get(), Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.get());
            if (hidden == null) return false;
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, hidden);
            Logger.printDebug(() -> hidden + ", an empty row in its place");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return false;
        }
    }

    /**
     * What the node's entry is, as the line it's counted under, when its switch is on: its type, and
     * its module names, read whole, from its template when it has no {@code rendered_modules}, and
     * when the template names none, the module's name standing whole anywhere in it. Null for any
     * other entry, and nothing is read with both off.
     */
    @Nullable
    static String hidden(@Nullable Object node, boolean forYou, boolean people) {
        if (!forYou && !people) return null;
        if (!NULL_STATE_NODE.equals(FeedFilter.typeName(node))) return null;
        String modules = renderedModules(node);
        if (modules == null) modules = templateModules(node);
        if (modules == null) {
            // No module_name key either: the name can sit elsewhere in the template (#80).
            Set<String> names = templateNames(node);
            if (forYou && names.contains(FOR_YOU_MODULE)) return HIDDEN;
            if (people && names.contains(PEOPLE_YOU_MAY_KNOW_MODULE)) return HIDDEN_PEOPLE;
            return null;
        }
        for (String name : modules.split(",")) {
            String module = name.trim();
            if (forYou && FOR_YOU_MODULE.equals(module)) return HIDDEN;
            if (people && PEOPLE_YOU_MAY_KNOW_MODULE.equals(module)) return HIDDEN_PEOPLE;
        }
        return null;
    }

    /**
     * Logs the node, and the module unless {@code moduleRead} is false (584 hands over the node
     * alone), and which of Facebook's builders handed it over when {@code from} names one.
     */
    private static void log(@Nullable Object node, @Nullable Object module, boolean moduleRead, @Nullable String from) {
        if (!enabled()) return;
        startOverIfCleared();
        if (full) return;
        try {
            String nodeType = FeedFilter.typeName(node);
            // A node whose type can't be read may be released, and its strings aren't asked.
            String modules = nodeType == null ? "unread" : modules(node);
            String moduleName = !moduleRead ? "" : " module=" + (module == null ? "none" : name(FeedFilter.typeName(module)));
            String template = "";
            if (NULL_STATE_NODE.equals(nodeType) && renderedModules(node) == null) {
                String named = templateModules(node);
                template = " template_modules=" + (named == null ? "none" : names(named));
                Set<String> constants = named == null ? templateNames(node) : Collections.emptySet();
                if (!constants.isEmpty()) template += " template_names=" + names(String.join(",", constants));
            }
            String source = from == null ? "" : " from=" + from;
            record("node=" + name(nodeType) + moduleName + " rendered_modules=" + modules + template + source);
        } catch (Throwable unreadable) {
            record("unreadable entry");
        }
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The node's module names, comma-separated, "none" when it has none, or "unread". */
    private static String modules(Object node) {
        Object found = STRING_READERS.computeIfAbsent(node.getClass(), PreSearchSections::stringReader);
        if (!(found instanceof Method)) return "unread";
        Object value;
        try {
            value = ((Method) found).invoke(node, RENDERED_MODULES);
        } catch (Throwable failure) {
            return "unread";
        }
        if (!(value instanceof String)) return "none";
        return names((String) value);
    }

    /** The node's {@code rendered_modules} string as Facebook stores it, null when it has none or can't be read. */
    @Nullable
    private static String renderedModules(Object node) {
        Object found = STRING_READERS.computeIfAbsent(node.getClass(), PreSearchSections::stringReader);
        if (!(found instanceof Method)) return null;
        try {
            Object value = ((Method) found).invoke(node, RENDERED_MODULES);
            return value instanceof String ? (String) value : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * The module names the node's own template logs its rows under, comma-separated in the order they
     * first show, at most one past {@link #MAX_MODULES}. Null when the node isn't live, has no
     * template, or its template names no module.
     */
    @Nullable
    static String templateModules(Object node) {
        Set<String> names = new LinkedHashSet<>();
        for (String template : templates(node)) {
            Matcher found = TEMPLATE_MODULE.matcher(template);
            while (names.size() <= MAX_MODULES && found.find()) names.add(found.group(1));
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    /**
     * The constants the node's templates carry anywhere, in the order they first show, each one a
     * {@link #name}, at most {@link #MAX_TEMPLATE_NAMES}. Empty when the node isn't live or has no
     * template.
     */
    static Set<String> templateNames(Object node) {
        Set<String> names = new LinkedHashSet<>();
        for (String template : templates(node)) {
            Matcher found = TEMPLATE_CONSTANT.matcher(template);
            while (names.size() < MAX_TEMPLATE_NAMES && found.find()) {
                String constant = found.group();
                if (!"other".equals(name(constant))) names.add(constant);
            }
        }
        return names;
    }

    /** The text of each of the node's templates, empty when it has none or isn't live. */
    private static List<String> templates(Object node) {
        Object view = PostDetails.tree(node, TEMPLATES_VIEW);
        List<?> bundles = view == null ? null : PostDetails.trees(view, TEMPLATE_BUNDLES);
        List<String> templates = new ArrayList<>();
        if (bundles == null) return templates;
        for (Object bundle : bundles) {
            String template = bundle == null ? null : PostDetails.string(bundle, TEMPLATE_TREE);
            if (template != null) templates.add(template);
        }
        return templates;
    }

    /** Module names for the log, each through {@link #name}, at most {@link #MAX_MODULES} of them. */
    private static String names(String value) {
        String[] parts = value.split(",", MAX_MODULES + 1);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parts.length && i < MAX_MODULES; i++) names.add(name(parts[i].trim()));
        if (parts.length > MAX_MODULES) names.add("more");
        return String.join(",", names);
    }

    private static Object stringReader(Class<?> type) {
        try {
            Method method = type.getMethod("getCachedNullableString", int.class);
            return method.getReturnType() == String.class ? method : Boolean.FALSE;
        } catch (Throwable none) {
            return Boolean.FALSE;
        }
    }

    /** [name] when it's a name: a letter, then letters, digits and underscores, at most four digits. */
    static String name(@Nullable String name) {
        if (name == null) return "unreadable";
        if (name.isEmpty() || name.length() > MAX_NAME || !Character.isLetter(name.charAt(0))) return "other";
        int digits = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++digits > MAX_DIGITS) return "other";
            } else if (c != '_' && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) {
                return "other";
            }
        }
        return name;
    }

    /** Forgets what was logged once a diagnostic clear started a new report. */
    private static void startOverIfCleared() {
        long current = HookStatus.generation();
        synchronized (shapes) {
            if (shapesGeneration == current) return;
            shapesGeneration = current;
            shapes.clear();
            budgetNoticed = false;
            full = false;
        }
    }

    private static void record(String shape) {
        synchronized (shapes) {
            if (shapes.contains(shape)) return;
            if (shapes.size() >= MAX_SHAPES) {
                if (budgetNoticed) return;
                budgetNoticed = true;
                full = true;
                shape = "more rows than the log keeps";
            } else {
                shapes.add(shape);
            }
        }
        String line = shape;
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, "PreSearchSections", () -> PREFIX + line);
    }

    /** Forgets what was logged, for tests. */
    static void forget() {
        synchronized (shapes) {
            shapes.clear();
            budgetNoticed = false;
            full = false;
        }
    }
}
