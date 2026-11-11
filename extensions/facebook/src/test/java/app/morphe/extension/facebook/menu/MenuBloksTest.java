/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.util.SparseArray;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The Menu's Bloks hook (#445), on a stand-in of the Menu's Bloks view as 586 builds it: a column
 * holding the profile section and the Your shortcuts section, the profile section holding the
 * switcher, and the switcher holding the profile row and the Create Facebook Page card.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuBloksTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for the Bloks node class: its attributes in a SparseArray, keyed the way Bloks keys them. */
    static final class Node {
        private final SparseArray<Object> attributes = new SparseArray<>();

        Node with(int key, Object value) {
            attributes.put(key, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        List<Object> children() {
            return (List<Object>) attributes.get(32);
        }
    }

    private static Node column(Object... children) {
        return new Node().with(32, Arrays.asList(children));
    }

    private Node profileRow;
    private Node profileContainer;
    private Node createCard;
    private Node cardBody;
    private Node switcher;
    private Node wrapper;
    private Node profileSection;
    private Node shortcutsSection;
    private Node root;

    @Before
    public void startClean() {
        MenuBloks.forgetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
        profileRow = new Node().with(36, MenuBloks.PROFILE_ROW);
        profileContainer = column(profileRow).with(59, "profile");
        cardBody = column(
                new Node().with(36, MenuBloks.CREATE_PAGE).with(38, "Create Facebook Page")
                        .with(41, Collections.singletonList(new Node().with(36, "(bk.action.Tap)"))),
                new Node().with(36, "impression").with(38, "{\"content_type\":\"profile\",\"lever_type\":\"plaza\"}"));
        createCard = column(cardBody).with(57, "border");
        switcher = column(profileContainer, createCard).with(36, "condensed_switcher_test_key");
        wrapper = column(switcher, new Node().with(59, "spacer"));
        profileSection = column(column(wrapper)).with(33, "b4nrsn:2");
        Node placeholder = new Node().with(33, "b4nrsn:75").with(41, Collections.singletonList(
                column(new Node().with(36, "(bk.action.Async)")
                        .with(45, "/product_engagement/load_shortcuts_v2?plaza_section_id=1"))));
        shortcutsSection = column(new Node().with(43, "Your shortcuts").with(44, "HEADER"), placeholder)
                .with(33, "b4nrsn:4");
        root = column(profileSection, shortcutsSection).with(41, Collections.singletonList(
                new Node().with(36, "/gk/log_exposure?gk=pe_plaza_your_shortcuts_longterm_holdout")));
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_MENU_CREATE_PAGE.resetToDefault();
        Settings.HIDE_MENU_SHORTCUTS.resetToDefault();
        MenuBloks.forgetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(MenuBloks.ROUTE + ":")) return line;
        }
        return null;
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.MENU_PROMOTIONS + ":")) return line;
        }
        return null;
    }

    /** Every list of the view, asked about the way the patched getters ask. */
    private List<List<Object>> lists() {
        return Arrays.asList(root.children(), profileSection.children(), wrapper.children(),
                switcher.children(), createCard.children(), cardBody.children(), shortcutsSection.children());
    }

    @Test
    public void bothSwitchesStartOffAndLeaveEveryListAsItIs() {
        for (List<Object> list : lists()) assertSame(list, MenuBloks.children(list));
        assertNull(counterLine());
    }

    @Test
    public void createPageLeavesTheSwitcherAndTheProfileRowStays() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        assertEquals(Collections.singletonList(profileContainer), MenuBloks.children(switcher.children()));
        for (List<Object> list : lists()) {
            if (list != switcher.children()) assertSame("only the switcher changes", list, MenuBloks.children(list));
        }
        assertEquals(MenuBloks.ROUTE + ": 0 lists, 0 items, 1 removed. Last reason: Create Facebook Page. Removed: "
                + "Create Facebook Page 1", counterLine());
        assertTrue(statusLine(), statusLine().startsWith(FamilyNames.MENU_PROMOTIONS + ": invoked"));
    }

    @Test
    public void yourShortcutsLeavesTheColumnAndTheProfileSectionStays() {
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertEquals(Collections.singletonList(profileSection), MenuBloks.children(root.children()));
        for (List<Object> list : lists()) {
            if (list != root.children()) assertSame("only the column changes", list, MenuBloks.children(list));
        }
        assertEquals(MenuBloks.ROUTE + ": 0 lists, 0 items, 1 removed. Last reason: Your shortcuts. Removed: "
                + "Your shortcuts 1", counterLine());
    }

    /** Once Facebook loads the tiles, the section holds them in place of the placeholder, and still goes. */
    @Test
    public void yourShortcutsWithItsTilesLoadedGoesToo() {
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        Node tile = new Node().with(36, "tile").with(117, Collections.singletonList(
                new Node().with(36, "/product_engagement/shortcuts_bottom_sheet_v2?content_id=1")));
        Node loaded = column(new Node().with(43, "Your shortcuts"), column(tile, new Node().with(36, "tile")));
        List<Node> list = Arrays.asList(profileSection, loaded);
        assertEquals(Collections.singletonList(profileSection), MenuBloks.children(list));
    }

    /** 586's renderer reaches the column and the switcher through a node's attribute walk, never the getters. */
    @Test
    public void theAttributeWalkLeavesThePartsOutToo() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertEquals(Collections.singletonList(profileSection), MenuBloks.visited(root.children()));
        assertEquals(Collections.singletonList(profileContainer), MenuBloks.visited(switcher.children()));
        assertSame(wrapper.children(), MenuBloks.visited(wrapper.children()));
    }

    /** The walk reads every attribute, so whatever isn't a list goes to the visitor untouched. */
    @Test
    public void theAttributeWalkHandsOnWhatIsntAList() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertNull(MenuBloks.visited(null));
        Object text = MenuBloks.CREATE_PAGE;
        assertSame(text, MenuBloks.visited(text));
        assertSame(shortcutsSection, MenuBloks.visited(shortcutsSection));
        Object number = 7;
        assertSame(number, MenuBloks.visited(number));
        assertNull(counterLine());
    }

    @Test
    public void bothOnLeaveOnlyTheProfile() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertEquals(Collections.singletonList(profileSection), MenuBloks.children(root.children()));
        assertEquals(Collections.singletonList(profileContainer), MenuBloks.children(switcher.children()));
        assertSame(wrapper.children(), MenuBloks.children(wrapper.children()));
    }

    /** Negative control: the column sits in a list of its own, beside a node with neither part, and stays. */
    @Test
    public void aListAboveTheColumnStaysWhole() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        List<Node> above = Arrays.asList(new Node().with(36, "header"), root, new Node().with(36, "footer"));
        assertSame(above, MenuBloks.children(above));
    }

    /** With no profile row beside it, Create Facebook Page stays wherever else it shows. */
    @Test
    public void createPageWithNoProfileRowBesideItStays() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        List<Node> list = Arrays.asList(new Node().with(36, "row"), createCard);
        assertSame(list, MenuBloks.children(list));
    }

    /** Only the whole test key counts, and the column's own shortcuts experiment log isn't a shortcut. */
    @Test
    public void stringsThatOnlyMentionThePartsDont() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        Node mention = new Node().with(36, MenuBloks.CREATE_PAGE + "_v2").with(38, "Your shortcuts")
                .with(41, Collections.singletonList(new Node().with(36, "/gk/log_exposure?gk=your_shortcuts")));
        List<Node> list = Arrays.asList(profileContainer, mention);
        assertSame(list, MenuBloks.children(list));
    }

    @Test
    public void pausedEveryListStays() {
        Settings.HIDE_MENU_CREATE_PAGE.save(true);
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        for (List<Object> list : lists()) assertSame(list, MenuBloks.children(list));
    }

    @Test
    public void shortEmptyAndMissingListsPassThrough() {
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertNull(MenuBloks.children(null));
        List<Object> empty = Collections.emptyList();
        assertSame(empty, MenuBloks.children(empty));
        List<Node> one = Collections.singletonList(shortcutsSection);
        assertSame(one, MenuBloks.children(one));
        List<Object> notNodes = Arrays.asList("text", 7, null);
        assertSame(notNodes, MenuBloks.children(notNodes));
    }

    /** A list that throws is recorded and handed back: the hook fails open. */
    @Test
    public void aFailureHandsTheListBack() {
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        List<Object> broken = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("no child " + index);
            }

            @Override
            public int size() {
                return 2;
            }
        };
        assertSame(broken, MenuBloks.children(broken));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    /** A node's answer is kept, so a layout pass over the same node doesn't read it again. */
    @Test
    public void aNodesAnswerIsKept() {
        Settings.HIDE_MENU_SHORTCUTS.save(true);
        assertEquals(Collections.singletonList(profileSection), MenuBloks.children(root.children()));
        shortcutsSection.with(33, "changed").with(32, Collections.emptyList());
        assertEquals("the first answer stands", Collections.singletonList(profileSection),
                MenuBloks.children(root.children()));
    }
}
