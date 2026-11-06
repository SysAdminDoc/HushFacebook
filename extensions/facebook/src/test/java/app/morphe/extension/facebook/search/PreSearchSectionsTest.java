/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.facebook.graphservice.tree.TreeJNI;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The search screen before typing (#80): For you and People you may know are each drawn as an empty
 * row while their switch is on, and with Debug logging on the rows are named, only by type and module
 * names.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PreSearchSectionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for a tree: answers its type, whether it's valid, and its strings by key. */
    public static class Tree {
        private final String type;
        private final boolean valid;
        private final String modules;
        int stringReads;

        Tree(String type, boolean valid, String modules) {
            this.type = type;
            this.valid = valid;
            this.modules = modules;
        }

        public String getTypeName() {
            return type;
        }

        public boolean isValidGraphServicesJNIModel() {
            return valid;
        }

        public String getCachedNullableString(int key) {
            stringReads++;
            return key == "rendered_modules".hashCode() ? modules : "1000123456789 at another key";
        }
    }

    @Before
    public void prepare() {
        PreSearchSections.forget();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        PreSearchSections.forget();
        BaseSettings.DEBUG.resetToDefault();
        Settings.HIDE_SEARCH_FOR_YOU.resetToDefault();
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static Tree forYou() {
        return new Tree(PreSearchSections.NULL_STATE_NODE, true, PreSearchSections.FOR_YOU_MODULE);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOffAndOffDrawsForYou() {
        BaseSettings.DEBUG.save(false);
        assertFalse(Settings.HIDE_SEARCH_FOR_YOU.defaultValue);
        assertTrue(PatchFamily.SUGGESTED_POSTS.switches.contains(Settings.HIDE_SEARCH_FOR_YOU));
        Tree node = forYou();
        Object module = new Tree("NativeTemplateView", true, null);
        assertSame(module, PreSearchSections.entry(node, module));
        assertEquals("off reads nothing off the node", 0, node.stringReads);
        assertFalse(statusLine().contains(PreSearchSections.HIDDEN));
    }

    @Test
    public void onDrawsForYouAsAnEmptyRow() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        assertNull(PreSearchSections.entry(forYou(), new Tree("NativeTemplateView", true, null)));
        // For you among a few module names is enough.
        assertNull(PreSearchSections.entry(new Tree(PreSearchSections.NULL_STATE_NODE, true,
                "SEARCH_HEADER, " + PreSearchSections.FOR_YOU_MODULE), new Tree("NativeTemplateView", true, null)));
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN + " 2"));
        // Debug logging still names the row Facebook would have drawn.
        assertTrue(log(), log().contains("node=" + PreSearchSections.NULL_STATE_NODE + " module=NativeTemplateView rendered_modules="
                + PreSearchSections.FOR_YOU_MODULE));
    }

    @Test
    public void everyOtherRowStays() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Object module = new Tree("NativeTemplateView", true, null);
        assertSame(module, PreSearchSections.entry(new Tree(PreSearchSections.NULL_STATE_NODE, true, "RECENT_SEARCHES"), module));
        assertSame(module, PreSearchSections.entry(new Tree(PreSearchSections.NULL_STATE_NODE, true, null), module));
        assertSame(module, PreSearchSections.entry(new Tree(PreSearchSections.NULL_STATE_NODE, true,
                PreSearchSections.FOR_YOU_MODULE + "_V2"), module));
        assertSame(module, PreSearchSections.entry(new Tree("SearchNullStateUnit", true, PreSearchSections.FOR_YOU_MODULE), module));
        // A released node can't be read, so its row is Facebook's.
        Tree released = new Tree(PreSearchSections.NULL_STATE_NODE, false, PreSearchSections.FOR_YOU_MODULE);
        assertSame(module, PreSearchSections.entry(released, module));
        assertEquals(0, released.stringReads);
        // An empty row stays empty.
        assertNull(PreSearchSections.entry(forYou(), null));
        assertFalse(statusLine().contains(PreSearchSections.HIDDEN));
    }

    @Test
    public void peopleYouMayKnowGoesOnlyWithItsOwnSwitch() {
        assertFalse(Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.defaultValue);
        assertTrue(PatchFamily.SUGGESTED_POSTS.switches.contains(Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW));
        // The shape a reporter's Debug log named on 583: the null state node, NativeTemplateView, PEOPLE_YOU_MAY_KNOW.
        Tree people = new Tree(PreSearchSections.NULL_STATE_NODE, true, PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE);
        Object module = new Tree("NativeTemplateView", true, null);
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        assertSame("For you's switch leaves People you may know", module, PreSearchSections.entry(people, module));
        Settings.HIDE_SEARCH_FOR_YOU.save(false);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        assertNull(PreSearchSections.entry(people, module));
        assertNull("584 hands over the node alone", PreSearchSections.node(people));
        assertSame("its switch leaves For you", module, PreSearchSections.entry(forYou(), module));
        Tree longer = new Tree(PreSearchSections.NULL_STATE_NODE, true, PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE + "_V2");
        assertSame(module, PreSearchSections.entry(longer, module));
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN_PEOPLE + " 2"));
        assertFalse(line, line.contains(PreSearchSections.HIDDEN + " "));
    }

    @Test
    public void bothSwitchesOnHideBothRows() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        Object module = new Tree("NativeTemplateView", true, null);
        assertNull(PreSearchSections.entry(forYou(), module));
        assertNull(PreSearchSections.entry(new Tree(PreSearchSections.NULL_STATE_NODE, true,
                PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE), module));
        assertSame(module, PreSearchSections.entry(new Tree(PreSearchSections.NULL_STATE_NODE, true, "RECENT_SEARCHES"), module));
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN + " 1"));
        assertTrue(line, line.contains(PreSearchSections.HIDDEN_PEOPLE + " 1"));
    }

    @Test
    public void fiveEightyFourHandsTheNodeAloneAndANullNodeIsTheEmptyRow() {
        Tree node = forYou();
        assertSame("off draws the node", node, PreSearchSections.node(node));
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        assertNull(PreSearchSections.node(forYou()));
        Tree other = new Tree(PreSearchSections.NULL_STATE_NODE, true, "RECENT_SEARCHES");
        assertSame(other, PreSearchSections.node(other));
        Tree unit = new Tree("SearchNullStateUnit", true, PreSearchSections.FOR_YOU_MODULE);
        assertSame(unit, PreSearchSections.node(unit));
        assertNull(PreSearchSections.node(null));
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN + " 1"));
        // The log names the node and its module names, and no module it wasn't handed.
        String log = log();
        assertTrue(log, log.contains("node=SearchNullStateUnit rendered_modules=" + PreSearchSections.FOR_YOU_MODULE));
        assertFalse(log, log.contains("module=none"));
        assertFalse(log, log.contains("node=unreadable"));
    }

    @Test
    public void theHeadAndTheQueryLeaveOutWhatTheListHides() {
        // #285: every row of the reporter's search screen came from the head, which never reached the list's hook.
        Tree other = new Tree(PreSearchSections.NULL_STATE_NODE, true, "RECENT_SEARCHES");
        assertSame("off keeps the head's node", other, PreSearchSections.headNode(other));
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        assertNull(PreSearchSections.headNode(forYou()));
        assertNull(PreSearchSections.queryNode(new Tree(PreSearchSections.NULL_STATE_NODE, true,
                PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE)));
        assertSame(other, PreSearchSections.headNode(other));
        assertSame(other, PreSearchSections.queryNode(other));
        // The query may not carry module names at all, and then its row is Facebook's.
        Tree unnamed = new Tree(PreSearchSections.NULL_STATE_NODE, true, null);
        assertSame(unnamed, PreSearchSections.queryNode(unnamed));
        assertNull(PreSearchSections.headNode(null));
        assertNull(PreSearchSections.queryNode(null));
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN + " 1"));
        assertTrue(line, line.contains(PreSearchSections.HIDDEN_PEOPLE + " 1"));
    }

    /** A node of Facebook's older null-state query: no rendered_modules, and a template per text given. */
    public static class QueryNode extends TreeJNI {
        QueryNode(String... templates) {
            super(PreSearchSections.NULL_STATE_NODE);
            List<TreeJNI> bundles = new ArrayList<>();
            for (String template : templates) bundles.add(new TreeJNI().with("nt_bundle_tree", template));
            with("native_templates_view", new TreeJNI().with("native_template_bundles", bundles));
        }

        public String getCachedNullableString(int key) {
            return cachedString(key);
        }
    }

    /**
     * A template's text as the S22's held it on 585: each row's logging data is JSON inside a JSON
     * string, so its keys and values sit between escaped quotes.
     */
    private static String template(String key, String module) {
        return "{\"logging\":\"{\\\"extra_data\\\":{\\\"" + key + "\\\":\\\"" + module + "\\\",\\\"position_in_module\\\":0}}\"}";
    }

    /** [inner] as a string inside one more JSON object, as the For you template nests its rows' data. */
    private static String nested(String inner) {
        return "{\"rows\":\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    @Test
    public void theQuerysNodesAreMatchedByTheModuleTheirTemplateNames() {
        // #285: a reporter's 0.20.0 log had only query nodes, all without rendered_modules, and For you stayed.
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        assertNull(PreSearchSections.queryNode(new QueryNode(template("module_name", PreSearchSections.FOR_YOU_MODULE))));
        assertNull(PreSearchSections.queryNode(new QueryNode(nested(template("module_name", PreSearchSections.FOR_YOU_MODULE)))));
        assertNull("one of the bundles is enough", PreSearchSections.queryNode(
                new QueryNode(template("module_name", "SEARCH_HEADER"), template("module_name", PreSearchSections.FOR_YOU_MODULE))));
        QueryNode recent = new QueryNode(template("module_name", "RECENT_SEARCH"));
        assertSame(recent, PreSearchSections.queryNode(recent));
        QueryNode longer = new QueryNode(template("module_name", PreSearchSections.FOR_YOU_MODULE + "_V2"));
        assertSame(longer, PreSearchSections.queryNode(longer));
        QueryNode keyed = new QueryNode(template("module_name", "RECENT_SEARCH"), template("result_type", PreSearchSections.FOR_YOU_MODULE));
        assertSame("a template that names its module is taken at its word", keyed, PreSearchSections.queryNode(keyed));
        QueryNode bare = new QueryNode();
        assertSame(bare, PreSearchSections.queryNode(bare));
        // A node that names its modules is taken at its word, and its template isn't read.
        QueryNode named = new QueryNode(template("module_name", PreSearchSections.FOR_YOU_MODULE));
        named.with("rendered_modules", "RECENT_SEARCH");
        assertSame(named, PreSearchSections.queryNode(named));
        // People you may know leaves only with its own switch.
        QueryNode people = new QueryNode(template("module_name", PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE));
        assertSame(people, PreSearchSections.queryNode(people));
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        assertNull(PreSearchSections.queryNode(people));
        // A released node's template is never asked for.
        TreeJNI released = new QueryNode(template("module_name", PreSearchSections.FOR_YOU_MODULE)).releasedTree();
        assertSame(released, PreSearchSections.queryNode(released));
        assertFalse(released.readAfterRelease);
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN + " 3"));
        assertTrue(line, line.contains(PreSearchSections.HIDDEN_PEOPLE + " 1"));
    }

    /**
     * A template row as the S22's Visited entities row held it on 585: the row's kind is a value in
     * the tree, with no module_name key anywhere, next to style constants and colors.
     */
    private static String row(String kind) {
        return "{\"\\\"\":\"\\uc432\",\" \":[{\"p\":[{\"&\":\"" + kind + "\",\"*\":\"ns_recent_search\"}],"
                + "\"8\":\"HEADLINE3_DEEMPHASIZED\",\"+\":\"FF080809\",\"0\":\"LEFT\"}]}";
    }

    @Test
    public void withNoModuleNameTheModulesNameAnywhereInTheTemplateCounts() {
        // #80: a reporter's 0.21.0 log had RECENT_SEARCH and one unnamed query row, and both rows stayed.
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        assertNull(PreSearchSections.queryNode(new QueryNode(row(PreSearchSections.FOR_YOU_MODULE))));
        assertNull("one of the bundles is enough", PreSearchSections.queryNode(
                new QueryNode(row("VISITED_ENTITIES"), template("result_type", PreSearchSections.FOR_YOU_MODULE))));
        QueryNode people = new QueryNode(row(PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE));
        assertSame("People you may know leaves only with its own switch", people, PreSearchSections.queryNode(people));
        Settings.HIDE_SEARCH_PEOPLE_YOU_MAY_KNOW.save(true);
        assertNull(PreSearchSections.queryNode(people));
        // The S22's own unnamed rows, Visited entities and the Recent searches header, stay.
        QueryNode visited = new QueryNode(row("VISITED_ENTITIES"));
        assertSame(visited, PreSearchSections.queryNode(visited));
        QueryNode recent = new QueryNode("{\"cds\":\"{\\\"SEARCH_NULL_STATE_RECENT_SEARCHES_MODULE_CDS\\\":\\\"nc66hi:0\\\"}\",\"v\":\"VISIBLE\"}");
        assertSame(recent, PreSearchSections.queryNode(recent));
        // Only the whole name counts, in capitals.
        for (String near : new String[] {PreSearchSections.FOR_YOU_MODULE + "_V2", "OLD_" + PreSearchSections.FOR_YOU_MODULE,
                PreSearchSections.FOR_YOU_MODULE.toLowerCase(java.util.Locale.ROOT), PreSearchSections.PEOPLE_YOU_MAY_KNOW_MODULE + "2"}) {
            QueryNode node = new QueryNode(row(near));
            assertSame(near, node, PreSearchSections.queryNode(node));
        }
        String line = statusLine();
        assertTrue(line, line.contains(PreSearchSections.HIDDEN + " 2"));
        assertTrue(line, line.contains(PreSearchSections.HIDDEN_PEOPLE + " 1"));
    }

    @Test
    public void anUnnamedRowsLineListsTheConstantsItsTemplateCarries() {
        PreSearchSections.queryNode(new QueryNode(row("VISITED_ENTITIES")));
        PreSearchSections.queryNode(new QueryNode(row("SUGGESTED_SEARCHES")));
        PreSearchSections.queryNode(new QueryNode(row("ROW_1000123456789")));
        String log = log();
        String node = PreSearchSections.PREFIX + "node=" + PreSearchSections.NULL_STATE_NODE + " rendered_modules=none template_modules=none";
        // Two unnamed rows are two lines, where both used to log as one.
        assertTrue(log, log.contains(node + " template_names=VISITED_ENTITIES,HEADLINE3_DEEMPHASIZED from=" + PreSearchSections.FROM_QUERY));
        assertTrue(log, log.contains(node + " template_names=SUGGESTED_SEARCHES,HEADLINE3_DEEMPHASIZED from=" + PreSearchSections.FROM_QUERY));
        // Colors aren't constants, and a name with an id in it never gets out.
        assertTrue(log, log.contains(node + " template_names=HEADLINE3_DEEMPHASIZED from=" + PreSearchSections.FROM_QUERY));
        assertFalse(log, log.contains("1000123456789"));
        assertFalse(log, log.contains("FF080809"));
    }

    @Test
    public void aRowLoggedBeforeADiagnosticClearLogsAgainAfterIt() {
        // Facebook builds the search screen once at start; a report taken after a clear had no line for it.
        PreSearchSections.queryNode(new QueryNode(row("VISITED_ENTITIES")));
        String line = PreSearchSections.PREFIX + "node=" + PreSearchSections.NULL_STATE_NODE
                + " rendered_modules=none template_modules=none template_names=VISITED_ENTITIES";
        assertTrue(log(), log().contains(line));
        LogBufferManager.clearLogBuffer();
        assertFalse(log(), log().contains(line));
        PreSearchSections.queryNode(new QueryNode(row("VISITED_ENTITIES")));
        assertTrue(log(), log().contains(line));
        // Without a clear it's still one line a run.
        PreSearchSections.queryNode(new QueryNode(row("VISITED_ENTITIES")));
        String log = log();
        assertEquals(log, log.indexOf(line), log.lastIndexOf(line));
    }

    @Test
    public void aQueryNodesLineNamesItsTemplatesModules() {
        PreSearchSections.queryNode(new QueryNode(template("module_name", "RECENT_SEARCH"),
                nested(template("module_name", PreSearchSections.FOR_YOU_MODULE))));
        PreSearchSections.queryNode(new QueryNode(template("module_name", "Recent search 1000123456789")));
        String log = log();
        String node = PreSearchSections.PREFIX + "node=" + PreSearchSections.NULL_STATE_NODE;
        assertTrue(log, log.contains(node + " rendered_modules=none template_modules=RECENT_SEARCH,"
                + PreSearchSections.FOR_YOU_MODULE + " from=" + PreSearchSections.FROM_QUERY));
        // Only a name in capitals is a module name, so text after the key never gets out.
        assertTrue(log, log.contains(node + " rendered_modules=none template_modules=none from=" + PreSearchSections.FROM_QUERY));
        assertFalse(log, log.contains("1000123456789"));
    }

    @Test
    public void eachLineSaysWhichBuilderHandedTheNodeOver() {
        PreSearchSections.node(new Tree(PreSearchSections.NULL_STATE_NODE, true, "RECENT_SEARCHES"));
        PreSearchSections.headNode(new Tree(PreSearchSections.NULL_STATE_NODE, true, "RECENT_SEARCHES"));
        PreSearchSections.queryNode(new Tree(PreSearchSections.NULL_STATE_NODE, true, null));
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES"), null);
        String log = log();
        String node = PreSearchSections.PREFIX + "node=" + PreSearchSections.NULL_STATE_NODE;
        assertTrue(log, log.contains(node + " rendered_modules=RECENT_SEARCHES from=" + PreSearchSections.FROM_LIST));
        assertTrue(log, log.contains(node + " rendered_modules=RECENT_SEARCHES from=" + PreSearchSections.FROM_HEAD));
        assertTrue(log, log.contains(node + " rendered_modules=none template_modules=none from=" + PreSearchSections.FROM_QUERY));
        // 583's static reader names no builder.
        assertTrue(log, log.contains("node=SearchNullStateUnit module=none rendered_modules=RECENT_SEARCHES"));
        assertFalse(log, log.contains("node=SearchNullStateUnit module=none rendered_modules=RECENT_SEARCHES from="));
    }

    @Test
    public void pausedOrBeforeTheSettingsAreReadyDrawsTheNode() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Tree node = forYou();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(node, PreSearchSections.node(node));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(node, PreSearchSections.node(node)));
        assertNull(PreSearchSections.node(node));
    }

    @Test
    public void pausedOrBeforeTheSettingsAreReadyDrawsForYou() {
        Settings.HIDE_SEARCH_FOR_YOU.save(true);
        Object module = new Tree("NativeTemplateView", true, null);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(module, PreSearchSections.entry(forYou(), module));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(module, PreSearchSections.entry(forYou(), module)));
        assertNull(PreSearchSections.entry(forYou(), module));
    }

    private static String log() {
        return LogBufferManager.buildExportText();
    }

    @Test
    public void namesTheNodeTheModuleAndItsModuleNames() {
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES, PYMK2"),
                new Tree("NTSearchModule", true, null));
        String log = log();
        assertTrue(log, log.contains(PreSearchSections.PREFIX
                + "node=SearchNullStateUnit module=NTSearchModule rendered_modules=RECENT_SEARCHES,PYMK2"));
    }

    @Test
    public void idsAndTextNeverGetOut() {
        PreSearchSections.entry(new Tree("Search Unit", true, "100012345678901,People you may know,"),
                new Tree("https://example.com/x", true, null));
        String log = log();
        assertTrue(log, log.contains("node=other module=other rendered_modules=other,other,other"));
        assertFalse(log, log.contains("100012345678901"));
        assertFalse(log, log.contains("People you may know"));
        assertFalse(log, log.contains("example.com"));
    }

    @Test
    public void anEmptyRowAndNoModuleNamesSaySo() {
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, null), null);
        String log = log();
        assertTrue(log, log.contains("node=SearchNullStateUnit module=none rendered_modules=none"));
    }

    @Test
    public void aReleasedNodeIsNeverAskedForItsStrings() {
        Tree released = new Tree("SearchNullStateUnit", false, "RECENT_SEARCHES");
        PreSearchSections.entry(released, null);
        assertEquals(0, released.stringReads);
        String log = log();
        assertTrue(log, log.contains("node=unreadable module=none rendered_modules=unread"));
    }

    @Test
    public void debugOffLogsNothingAndTouchesNothing() {
        BaseSettings.DEBUG.save(false);
        Tree node = new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES");
        Object module = new Tree("NTSearchModule", true, null);
        assertSame(module, PreSearchSections.entry(node, module));
        assertEquals(0, node.stringReads);
        assertFalse(log(), log().contains(PreSearchSections.PREFIX));
    }

    @Test
    public void longModuleListsAreCut() {
        StringBuilder modules = new StringBuilder("A");
        for (int i = 1; i < PreSearchSections.MAX_MODULES + 3; i++) modules.append(",A");
        PreSearchSections.entry(new Tree("SearchNullStateUnit", true, modules.toString()), null);
        String log = log();
        assertTrue(log, log.contains("rendered_modules=A,A,A,A,A,A,A,A,more"));
    }

    @Test
    public void eachLineOnceAndABudget() {
        Tree module = new Tree("NTSearchModule", true, null);
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < PreSearchSections.MAX_SHAPES + 5; i++) {
                PreSearchSections.entry(new Tree("Unit" + (char) ('a' + i % 26) + (char) ('a' + i / 26), true, null), module);
            }
        }
        String log = log();
        assertEquals(PreSearchSections.MAX_SHAPES + 1, log.split(PreSearchSections.PREFIX, -1).length - 1);
        assertEquals(1, log.split("more rows than the log keeps", -1).length - 1);
    }

    @Test
    public void aSpentBudgetStopsReadingEntries() {
        for (int i = 0; i <= PreSearchSections.MAX_SHAPES; i++) {
            PreSearchSections.entry(new Tree("Unit" + (char) ('a' + i % 26) + (char) ('a' + i / 26), true, null), null);
        }
        assertTrue(log(), log().contains("more rows than the log keeps"));
        Tree late = new Tree("SearchNullStateUnit", true, "RECENT_SEARCHES");
        PreSearchSections.entry(late, null);
        assertEquals(0, late.stringReads);
        assertFalse(log(), log().contains("RECENT_SEARCHES"));
    }

    @Test
    public void namesAreLettersUnderscoresAndAFewDigits() {
        assertEquals("PaginatedPeopleYouMayKnow", PreSearchSections.name("PaginatedPeopleYouMayKnow"));
        assertEquals("FB_Module_2026", PreSearchSections.name("FB_Module_2026"));
        assertEquals("other", PreSearchSections.name("Module_20261"));
        assertEquals("other", PreSearchSections.name("2026Module"));
        assertEquals("other", PreSearchSections.name(""));
        assertEquals("other", PreSearchSections.name("Caf" + (char) 0xe9));
        assertEquals("unreadable", PreSearchSections.name(null));
    }
}
