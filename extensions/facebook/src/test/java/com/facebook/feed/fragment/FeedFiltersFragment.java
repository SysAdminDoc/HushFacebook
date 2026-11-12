package com.facebook.feed.fragment;

import android.content.Context;
import android.content.Intent;

import com.facebook.api.feedtype.FeedType;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.navigation.FeedsSubtabRoute;
import app.morphe.extension.facebook.navigation.StartTabRoute;

/**
 * Stands in for the Feeds tab, under its kept name, as the patch leaves it: its filters, and the
 * handler its main screen hands an intent to, which asks the extension where it reads the last
 * feed type, hands its lookup's answer through the extension, and picks that filter when the
 * filters have it, and its onBackPressed, which asks the extension first. A test can make the
 * handler throw, hold the picked filter's page back, or make the second tap on the tab throw.
 */
public class FeedFiltersFragment {
    public final List<FeedType> filters = new ArrayList<>();
    public FeedType last = FeedType.TOP_STORIES;
    public int picked = -1;
    public int handled;
    public RuntimeException failHandle;
    /** Whether the picked filter's page has taken over, and whether the page showing has its list. */
    public boolean pageSwapped = true;
    public boolean listLoaded = true;
    public int reselected;
    public RuntimeException failReselect;

    public FeedType getCurrentFeedType() {
        if (picked >= 0 && pageSwapped) return filters.get(picked);
        return filters.isEmpty() ? null : filters.get(0);
    }

    public boolean isListViewLoaded() {
        return listLoaded;
    }

    public boolean onTabReselection() {
        reselected++;
        if (failReselect != null) throw failReselect;
        return false;
    }

    public void handleDeeplinkFromMainActivity(Intent intent) {
        if (intent == null) return;
        handled++;
        if (failHandle != null) throw failHandle;
        FeedType asked = (FeedType) FeedsSubtabRoute.feedType(last);
        int index = FeedsSubtabRoute.filterFound(filters.indexOf(asked));
        if (index != -1) picked = index;
    }

    /** The patched onResume's end. */
    public void onResume() {
        FeedsSubtabRoute.feedsResumed(this);
    }

    /** The screen the tab shows on, which androidx's Fragment answers by this name. */
    public Context context;
    /** How many times Facebook's own Back ran, which keeps Back on the feed. */
    public int backKept;

    public Context getContext() {
        return context;
    }

    /** The patched onBackPressed: the extension first, then Facebook's own, which answers yes. */
    public boolean onBackPressed() {
        if (StartTabRoute.feedsPageBack(this) == null) return true;
        backKept++;
        return true;
    }
}
