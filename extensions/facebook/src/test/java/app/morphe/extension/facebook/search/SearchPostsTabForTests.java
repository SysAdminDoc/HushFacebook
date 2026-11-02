/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Asks the Posts chip hook what Facebook's chip row would. */
public final class SearchPostsTabForTests {
    private SearchPostsTabForTests() {
    }

    /** Whether a row of All and Reels comes back with a Posts chip added. */
    public static boolean addsPosts() {
        Map<?, ?> before = SearchPostsTab.testLabels;
        Map<String, SearchPostsTabTest.Tab> labels = new LinkedHashMap<>();
        labels.put("Posts", SearchPostsTabTest.Tab.BLENDED_STORIES);
        labels.put("Reels", SearchPostsTabTest.Tab.REELS);
        SearchPostsTab.testLabels = labels;
        try {
            List<SearchPostsTabTest.Chip> row = Arrays.asList(
                    new SearchPostsTabTest.Chip(true, "All", "All"), new SearchPostsTabTest.Chip(false, "Reels", "Reels"));
            return SearchPostsTab.chips(row).size() == 3;
        } finally {
            SearchPostsTab.testLabels = before;
        }
    }
}
