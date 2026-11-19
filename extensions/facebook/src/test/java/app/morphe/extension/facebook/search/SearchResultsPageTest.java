/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.ads.SearchAdFilterForTests;
import app.morphe.extension.facebook.ads.SearchAdFilterForTests.Module;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The one hook first in a search results page's constructor, which both search patches filter the
 * page through: each patch's filter runs in turn behind its own switch, with its own counters and
 * Hook status family, a patch that isn't in the build isn't asked, and any failure keeps
 * Facebook's list.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchResultsPageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for the result role enum: only the constant names matter to both filters. */
    enum Role { ENTITY_USER, PUBLIC_POSTS, SEARCH_ADS, SEARCH_META_AI_ANSWER, HCM_RELATED_META_AI_PROMPTS }

    /** Hide Meta AI in search's filter, reading the stand-in module's role as its filled stub does. */
    private static final SearchResultsPage.Filter META_AI =
            (modules, page) -> MetaAiSearch.keptResults(modules, page, module -> ((Module) module).role);

    /** Hide sponsored search results' filter, with its stand-in stubs. */
    private static final SearchResultsPage.Filter ADS = SearchAdFilterForTests::keptResults;

    private final Module answer = new Module(Role.SEARCH_META_AI_ANSWER);
    private final Module person = new Module(Role.ENTITY_USER);
    private final Module ad = new Module(Role.SEARCH_ADS);
    private final Module prompts = new Module(Role.HCM_RELATED_META_AI_PROMPTS);
    private final Module post = new Module(Role.PUBLIC_POSTS);
    private final List<Module> page = Arrays.asList(answer, person, ad, prompts, post);

    @Before
    public void startClean() {
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_META_AI_IN_SEARCH.resetToDefault();
        Settings.HIDE_SPONSORED_SEARCH_RESULTS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String line(List<String> report, String prefix) {
        for (String line : report) {
            if (line.startsWith(prefix + ":")) return line;
        }
        return null;
    }

    @Test
    public void withBothPatchesEachTakesOutItsOwnAndCountsItsOwn() {
        assertEquals(Arrays.asList(person, post), SearchResultsPage.keptModules(page, "search_results", META_AI, ADS, null));
        List<String> counters = FeedFilterCounters.report();
        assertTrue(line(counters, "Search results"), line(counters, "Search results").contains(" 2 removed"));
        assertTrue(line(counters, "Search ads"), line(counters, "Search ads").startsWith("Search ads: 1 lists, 3 items, 1 removed"));
        List<String> status = HookStatus.report();
        assertTrue(line(status, FamilyNames.META_AI_SEARCH), line(status, FamilyNames.META_AI_SEARCH).contains("invoked 1"));
        assertTrue(line(status, FamilyNames.SPONSORED_SEARCH), line(status, FamilyNames.SPONSORED_SEARCH).contains("invoked 1"));
    }

    /** A build with only one of the two patches asks only that one, and the other family stays out of the report. */
    @Test
    public void aPatchThatIsntInTheBuildIsntAsked() {
        assertEquals(Arrays.asList(answer, person, prompts, post), SearchResultsPage.keptModules(page, null, null, ADS, null));
        assertNull(line(HookStatus.report(), FamilyNames.META_AI_SEARCH));
        HookStatus.clear();
        assertEquals(Arrays.asList(person, ad, post), SearchResultsPage.keptModules(page, null, META_AI, null, null));
        assertNull(line(HookStatus.report(), FamilyNames.SPONSORED_SEARCH));
        assertNull("neither patch in the build keeps Facebook's list", SearchResultsPage.keptModules(page, null, null, null, null));
    }

    /** Each switch is its own: one off leaves only that patch's modules. */
    @Test
    public void theSwitchesAreIndependent() {
        Settings.HIDE_SPONSORED_SEARCH_RESULTS.save(false);
        assertEquals(Arrays.asList(person, ad, post), SearchResultsPage.keptModules(page, null, META_AI, ADS, null));
        Settings.HIDE_SPONSORED_SEARCH_RESULTS.save(true);
        Settings.HIDE_META_AI_IN_SEARCH.save(false);
        assertEquals(Arrays.asList(answer, person, prompts, post), SearchResultsPage.keptModules(page, null, META_AI, ADS, null));
        Settings.HIDE_SPONSORED_SEARCH_RESULTS.save(false);
        assertNull("both off keeps Facebook's list", SearchResultsPage.keptModules(page, null, META_AI, ADS, null));
    }

    @Test
    public void aPageWithNothingToTakeOutKeepsFacebooksList() {
        assertNull(SearchResultsPage.keptModules(Arrays.asList(person, post), null, META_AI, ADS, null));
        assertNull(SearchResultsPage.keptModules(null, null, META_AI, ADS, null));
    }

    /** The Meta AI tab's own page is what someone asked for, and neither filter touches it. */
    @Test
    public void theMetaAiTabPageIsLeftWhole() {
        assertNull(SearchResultsPage.keptModules(page, MetaAiSearch.META_AI_TAB_PAGE, META_AI, ADS, null));
    }

    /** A filter that throws past its own guard costs the page nothing: Facebook's list, and both families told. */
    @Test
    public void aFailureKeepsFacebooksList() {
        SearchResultsPage.Filter broken = (modules, name) -> {
            throw new IllegalStateException("filter broke");
        };
        assertNull(SearchResultsPage.keptModules(page, null, META_AI, broken, null));
        List<String> status = HookStatus.report();
        assertTrue(line(status, FamilyNames.SPONSORED_SEARCH), line(status, FamilyNames.SPONSORED_SEARCH).contains("IllegalStateException"));
        assertTrue(line(status, FamilyNames.META_AI_SEARCH), line(status, FamilyNames.META_AI_SEARCH).contains("IllegalStateException"));
    }

    /**
     * Hide posts by words goes last (#458): it gets what Meta AI's and the ads filter kept, its own
     * answer is the page, and when it keeps everything the other two's list stands.
     */
    @Test
    public void postWordsJudgesWhatTheOtherTwoKept() {
        List<List<?>> handed = new java.util.ArrayList<>();
        SearchResultsPage.Filter words = (modules, name) -> {
            handed.add(modules);
            return modules.contains(post) ? Arrays.asList(new Object[]{person}) : null;
        };
        assertEquals(Arrays.asList(person), SearchResultsPage.keptModules(page, null, META_AI, ADS, words));
        assertEquals("it reads only what the other two left", Arrays.asList(person, post), handed.get(0));

        SearchResultsPage.Filter keepsAll = (modules, name) -> null;
        assertEquals(Arrays.asList(person, post), SearchResultsPage.keptModules(page, null, META_AI, ADS, keepsAll));
        assertEquals("alone, it can take a post out", Arrays.asList(person),
                SearchResultsPage.keptModules(Arrays.asList(person, post), null, null, null, words));

        SearchResultsPage.Filter broken = (modules, name) -> {
            throw new IllegalStateException("words broke");
        };
        assertNull(SearchResultsPage.keptModules(page, null, null, null, broken));
        assertTrue(HookStatus.report().toString(),
                line(HookStatus.report(), FamilyNames.POST_WORDS).contains("IllegalStateException"));
    }

    /** The public entry asks SettingsStatus, which answers no patch until the patches switch it on. */
    @Test
    public void unpatchedTheHookKeepsFacebooksList() {
        assertNull(SearchResultsPage.keptModules(page, null));
        assertFalse(FeedFilterCounters.report().iterator().hasNext());
    }
}
