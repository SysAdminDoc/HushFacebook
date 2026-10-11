/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.litho.BaseMountingView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * With Debug logging on, a Menu build has Menu's layout written a moment later, cut down to the
 * Litho components naming the Muse card with their parents (their text left out) and the views
 * whose description names Muse, and the rest of Menu, hidden views and words like Museum left
 * out. A reel's long press has every window read the same way, for the long-press menu's Muse
 * share target. Off, nothing is read.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuLayoutDumpTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** How Litho describes a tree: each component on its line, indented under its parent. */
    private static final String MENU = String.join("\n",
            "litho.Column{1 V.E..... .. 0,0-1080,2000}",
            "  litho.ProfileHeader{2 V.E..... .. 0,0-1080,300 text=\"Pat Doe\"}",
            "  litho.HScroll{3 V.E..... .. 0,300-1080,600 text=\"Pat's \"picks\"\" [clickable]}",
            "    litho.MuseCard{4 V.E..... .. 0,300-1080,600}",
            "      litho.Text{5 V.E..... .. 0,300-1080,350 text=\"Meet Muse, your personal AI agent.\"}",
            "      litho.Button{6 V.E..... .. 0,500-300,600 text=\"Get app\"}",
            "  litho.Shortcuts{7 V.E..... .. 0,600-1080,1200}",
            "    litho.Text{8 V.E..... .. 0,600-1080,650 text=\"Museum trip, so amused\"}");

    /** A reel's long-press menu: the share targets' row, Muse first. */
    private static final String REEL_MENU = String.join("\n",
            "litho.Column{1 V.E..... .. 0,1161-1080,2158}",
            "  litho.HScroll{2 V.E..... .. 0,1195-1080,1500}",
            "    litho.ShareTarget{3 V.E..... .. 0,1195-231,1500 text=\"Muse\"}",
            "    litho.ShareTarget{4 V.E..... .. 231,1195-462,1500 text=\"Messenger\"}",
            "  litho.Actions{5 V.E..... .. 0,1853-1080,2158 text=\"Save\"}");

    @Before
    public void start() {
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
        MenuSectionsForTests.newProcess();
    }

    @After
    public void finish() {
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
        MenuSectionsForTests.newProcess();
    }

    @Test
    public void eachLineNamingTheCardComesWithItsParentsWithoutTheirText() {
        String[] lines = MENU.split("\n");
        assertEquals(Arrays.asList(lines[0], "  litho.HScroll{3 V.E..... .. 0,300-1080,600 [clickable]}",
                lines[3], lines[4], lines[5]), MenuLayoutDump.around(MENU));
        assertTrue("a word holding Muse named the card", MenuLayoutDump.around(lines[0] + "\n" + lines[7]).isEmpty());
        assertEquals("  litho.Row{3", MenuLayoutDump.withoutText("  litho.Row{3 text=\"cut off"));
    }

    @Test
    public void theCardsComponentsAndTheViewNamingItAreWritten() {
        BaseSettings.DEBUG.save(true);
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        BaseMountingView home = new BaseMountingView(context);
        home.description = "litho.Column{9 V.E..... .. 0,0-1080,2000}\n  litho.Text{10 V.E..... .. 0,0-1080,50 text=\"Home\"}";
        BaseMountingView menu = new BaseMountingView(context);
        menu.description = MENU;
        TextView card = new TextView(context);
        card.setContentDescription("Muse, button 1 of 1");
        menu.addView(card);
        BaseMountingView hiddenTab = new BaseMountingView(context);
        hiddenTab.description = "litho.Column{20 V.E..... .. 0,0-1080,2000}\n"
                + "  litho.Text{21 V.E..... .. 0,0-1080,50 text=\"Muse, in a tab not shown\"}";
        hiddenTab.setVisibility(View.GONE);
        TextView post = new TextView(context);
        post.setText("Museum trip with Pat");
        TextView hiddenCard = new TextView(context);
        hiddenCard.setContentDescription("Muse");
        hiddenCard.setVisibility(View.INVISIBLE);
        root.addView(home);
        root.addView(menu);
        root.addView(hiddenTab);
        root.addView(post);
        root.addView(hiddenCard);

        assertTrue(MenuLayoutDump.dump(root));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Menu layout, Litho tree 2 of 2:"));
        assertTrue(report, report.contains("litho.MuseCard{4"));
        assertTrue(report, report.contains("text=\"Get app\""));
        assertFalse("the rest of Menu was written: " + report, report.contains("Pat Doe"));
        assertFalse("a parent's text was written: " + report, report.contains("picks"));
        assertFalse("a word holding Muse was written: " + report, report.contains("Museum"));
        assertFalse("a tree without the card was written: " + report, report.contains("Litho tree 1 of 2"));
        assertFalse("a hidden tab was read: " + report, report.contains("not shown"));
        assertTrue(report, report.contains("Menu layout, a view naming Muse: android.widget.TextView < "
                + BaseMountingView.class.getName() + " < android.widget.FrameLayout"));
        assertEquals("only the card's view: " + report, 2, report.split("a view naming Muse").length);
    }

    @Test
    public void aScreenWithoutTheCardSaysHowManyTreesDescribedThemselves() {
        BaseSettings.DEBUG.save(true);
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        BaseMountingView home = new BaseMountingView(context);
        home.description = "litho.Column{9 V.E..... .. 0,0-1080,2000}";
        root.addView(home);
        root.addView(new BaseMountingView(context));

        assertFalse(MenuLayoutDump.dump(root));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("Menu layout: nothing names Muse in 2 Litho trees (1 gave a description)"));
    }

    @Test
    public void withDebugLoggingOnMenuIsReadAMomentAfterItBuildsUntilTheCardIsFound() {
        FbMainTabActivity screen = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        BaseMountingView menu = new BaseMountingView(screen);
        menu.description = MENU;
        screen.setContentView(menu);
        MenuLayoutDump.screenCreated(screen);

        MenuSectionsForTests.hidesUpgrades();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.DELAY_MS, TimeUnit.MILLISECONDS);
        assertFalse("read with Debug logging off", LogBufferManager.buildExportText().contains("Menu layout"));

        BaseSettings.DEBUG.save(true);
        MenuSectionsForTests.hidesUpgrades();
        assertFalse("read before Litho drew it", LogBufferManager.buildExportText().contains("Menu layout"));
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.DELAY_MS, TimeUnit.MILLISECONDS);
        assertTrue(LogBufferManager.buildExportText(), LogBufferManager.buildExportText().contains("litho.MuseCard{4"));

        LogBufferManager.clearLogBuffer();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.GAP_MS, TimeUnit.MILLISECONDS);
        MenuSectionsForTests.hidesServerUpgrades();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.DELAY_MS, TimeUnit.MILLISECONDS);
        assertFalse("read again after the card was found", LogBufferManager.buildExportText().contains("Menu layout"));
    }

    @Test
    public void withDebugLoggingOnEveryWindowIsReadAMomentAfterAReelsLongPress() {
        FbMainTabActivity screen = Robolectric.buildActivity(FbMainTabActivity.class).setup().get();
        BaseMountingView sheet = new BaseMountingView(screen);
        sheet.description = REEL_MENU;
        screen.setContentView(sheet);

        MenuLayoutDump.reelLongPressed();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.REEL_MENU_DELAY_MS, TimeUnit.MILLISECONDS);
        assertFalse("read with Debug logging off", LogBufferManager.buildExportText().contains(MenuLayoutDump.REEL_MENU));

        BaseSettings.DEBUG.save(true);
        MenuLayoutDump.reelLongPressed();
        assertFalse("read before the menu opened", LogBufferManager.buildExportText().contains(MenuLayoutDump.REEL_MENU));
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.REEL_MENU_DELAY_MS, TimeUnit.MILLISECONDS);
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(MenuLayoutDump.REEL_MENU + ", Litho tree"));
        assertTrue(report, report.contains("litho.ShareTarget{3"));
        assertTrue("the row holding Muse was left out: " + report, report.contains("litho.HScroll{2"));
        // Only the dump's own lines: the report's other sections name Messenger too (the patch list,
        // the links section) once another test has registered them in this process.
        String dump = report.substring(report.indexOf(MenuLayoutDump.REEL_MENU + ", Litho tree"));
        int end = dump.indexOf("\n\n");
        if (end >= 0) dump = dump.substring(0, end);
        assertFalse("another target was written: " + dump, dump.contains("Messenger"));

        LogBufferManager.clearLogBuffer();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.GAP_MS, TimeUnit.MILLISECONDS);
        MenuLayoutDump.reelLongPressed();
        shadowOf(Looper.getMainLooper()).idleFor(MenuLayoutDump.REEL_MENU_DELAY_MS, TimeUnit.MILLISECONDS);
        assertFalse("read again after Muse was found", LogBufferManager.buildExportText().contains(MenuLayoutDump.REEL_MENU));
    }
}
