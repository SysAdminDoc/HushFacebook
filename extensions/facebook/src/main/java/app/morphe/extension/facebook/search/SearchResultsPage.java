/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.facebook.ads.SearchAdFilter;
import app.morphe.extension.facebook.feed.TimelineWords;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The one hook first in the constructor of a page of search results, which three patches filter the
 * page's modules through: Hide Meta AI in search ({@link MetaAiSearch#keptResults}), Hide
 * sponsored search results ({@link SearchAdFilter#keptResults}) and Hide posts by words, whose
 * search switches judge each post a page carries ({@link TimelineWords#keptSearchResults}).
 *
 * <p>Each patch asks for the hook and it goes in once, so the page is handed over once and each
 * filter that's in this build gets it in turn: Meta AI's first, then the ads filter over what Meta
 * AI's left. Each one keeps its own switch, its own counters and its own Hook status family, and a
 * filter whose patch isn't in the build isn't asked, so its family stays out of the report. Both
 * leave the Meta AI tab's own page ({@link MetaAiSearch#META_AI_TAB_PAGE}) whole.
 *
 * <p>Null keeps Facebook's own list, which is what the hook answers when neither filter left
 * anything out, and also on any failure in here.
 */
public final class SearchResultsPage {
    /** One patch's filter of a page: null keeps the list it was handed, otherwise the modules to keep. */
    interface Filter {
        @Nullable
        List<Object> kept(List<?> modules, @Nullable String page);
    }

    private SearchResultsPage() {
    }

    /**
     * Injection point, first thing in the constructor of a page of search results, with the page's
     * modules and its name. Null leaves Facebook's list as it is; otherwise the modules to keep, in
     * their order, which the patch copies into a list of Facebook's own kind. Never throws.
     */
    @Nullable
    public static List<Object> keptModules(@Nullable List<?> modules, @Nullable String page) {
        return keptModules(modules, page,
                SettingsStatus.metaAiSearch() ? MetaAiSearch::keptResults : null,
                SettingsStatus.sponsoredSearch() ? SearchAdFilter::keptResults : null,
                SettingsStatus.postWords() ? TimelineWords::keptSearchResults : null);
    }

    /**
     * {@link #keptModules(List, String)} with each patch's filter passed in, or null when that
     * patch isn't in the build, so a test can stand in for them. Each filter gets what the ones
     * before it kept: Meta AI's, then the ads, then Hide posts by words (#458).
     */
    @Nullable
    static List<Object> keptModules(@Nullable List<?> modules, @Nullable String page,
                                    @Nullable Filter metaAi, @Nullable Filter ads, @Nullable Filter words) {
        if (modules == null) return null;
        try {
            List<?> current = modules;
            List<Object> changed = null;
            for (Filter filter : new Filter[]{metaAi, ads, words}) {
                if (filter == null) continue;
                List<Object> kept = filter.kept(current, page);
                if (kept != null) {
                    current = kept;
                    changed = kept;
                }
            }
            return changed;
        } catch (Throwable failure) {
            if (metaAi != null) HookStatus.threw(FamilyNames.META_AI_SEARCH, "search results page", failure);
            if (ads != null) HookStatus.threw(FamilyNames.SPONSORED_SEARCH, "search results page", failure);
            if (words != null) HookStatus.threw(FamilyNames.POST_WORDS, "search results page", failure);
            Logger.printException(() -> "Search results page: could not filter a page", failure);
            return null;
        }
    }
}
