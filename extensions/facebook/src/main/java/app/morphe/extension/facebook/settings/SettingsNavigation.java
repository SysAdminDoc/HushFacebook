/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.preference.SwitchPreference;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * A view of the complete preference model. Filtering never removes preferences from their
 * screen, so imports, dependencies, persistence and restart notices still reach hidden pages.
 */
@SuppressWarnings("deprecation")
final class SettingsNavigation extends BaseAdapter {
    private static final String STATE = "hushfacebook_navigation";
    private static final String MORE = "more";
    /** Marks the Pause, Resume or Undo button placed under a status row's title, so a rebind replaces it. */
    private static final String STATUS_ACTION = "hushfacebook_status_action";
    private final HushfacebookPreferenceFragment page;
    private final SettingsDialog host;
    private final ListView list;
    private final PreferenceScreen screen;
    private final ListAdapter source;
    private final List<Section> sections = new ArrayList<>();
    private final List<Preference> visible = new ArrayList<>();
    private final Preference browse;
    private final Preference more;
    /** The home page's last row, which opens Hushfacebook's Ko-fi page in a browser. */
    private final Preference support;
    private final Preference empty;
    /** The line a category or search page starts with while a pause or a restart applies to it. */
    private final Preference pageStatus;
    /** Whether that line is about Pause, and so carries Resume or Undo. */
    private boolean pageAction;
    private String route = "";
    private String query = "";
    /**
     * The row Back returns to. With a screen reader on it takes accessibility focus once it's back
     * on screen: on the S22 TalkBack otherwise held the whole screen, and the person's place in
     * the list was lost.
     */
    private Preference refocus;
    private int homePosition;
    private int homeOffset;
    private int morePosition;
    private int moreOffset;

    private final DataSetObserver changes = new DataSetObserver() {
        @Override public void onChanged() { rebuild(); }
        @Override public void onInvalidated() { rebuild(); }
    };

    SettingsNavigation(HushfacebookPreferenceFragment page, SettingsDialog host, Bundle saved) {
        this.page = page;
        this.host = host;
        screen = page.getPreferenceScreen();
        list = page.getView().findViewById(android.R.id.list);
        source = screen.getRootAdapter();
        Context context = screen.getContext();
        // Stable English route IDs survive a locale change; the displayed names are localized.
        section("Opening Facebook", L10n.t("Opening Facebook"), L10n.t("Marketplace mode and your start tab"), SettingsIcons.OPENING, true);
        section("News feed", L10n.t("News feed"), L10n.t("Ads, suggestions and word filters"), SettingsIcons.FEED, true);
        section("Stories", L10n.t("Stories"), L10n.t("Suggestions, saving, auto-advance and viewing anonymously"), SettingsIcons.STORIES, true);
        section("Reels and Watch", L10n.t("Reels and Watch"), L10n.t("Cleaner reels and video controls"), SettingsIcons.REELS, true);
        section("Playback", L10n.t("Playback"), L10n.t("Tap to play, quality and resume"), SettingsIcons.PLAYBACK, true);
        section("Downloads", L10n.t("Downloads"), L10n.t("Quality, format and file names"), SettingsIcons.DOWNLOADS, true);
        section("Comments", L10n.t("Comments"), null, SettingsIcons.COMMENTS, false);
        section("Writing", L10n.t("Writing"), null, SettingsIcons.WRITING, false);
        section("Chats", L10n.t("Chats"), null, SettingsIcons.CHATS, false);
        section("Menu", L10n.t("Menu"), null, SettingsIcons.MENU, false);
        section("Meta's other products", L10n.t("Meta's other products"), null, SettingsIcons.BLOCK, false);
        section("Search", L10n.t("Search"), null, SettingsIcons.SEARCH, false);
        section("Marketplace", L10n.t("Marketplace"), null, SettingsIcons.MARKETPLACE, false);
        section("Notifications", L10n.t("Notifications"), null, SettingsIcons.NOTIFICATIONS, false);
        section("Links", L10n.t("Links"), null, SettingsIcons.LINKS, false);
        section("Privacy", L10n.t("Privacy"), null, SettingsIcons.TELEMETRY, false);
        section("Updates", L10n.t("Updates"), null, SettingsIcons.UPDATES, false);
        section("Appearance", L10n.t("Appearance"), null, SettingsIcons.APPEARANCE, false);
        section("Set when you patched", L10n.t("Set when you patched"), null, SettingsIcons.PATCHED, false);
        section("Pause, backup and diagnostics", L10n.t("Pause, backup and diagnostics"), null, SettingsIcons.TOOLS, false);
        section("About", L10n.t("About"), null, SettingsIcons.ABOUT, false);
        browse = new SettingsRows.Heading(context);
        browse.setTitle(L10n.t("Browse settings"));
        more = link(context, L10n.t("More settings"), L10n.t("Additional Facebook preferences"), SettingsIcons.SETTINGS);
        more.setOnPreferenceClickListener(ignored -> { navigate(MORE); return true; });
        support = link(context, L10n.t("Support Hushfacebook"), L10n.t("Buy me a coffee on Ko-fi"), SettingsIcons.OPENING);
        support.setKey(HushfacebookPages.SUPPORT);
        support.setOnPreferenceClickListener(row -> { HushfacebookPages.openSupport(row.getContext()); return true; });
        empty = new SettingsRows.Row(context);
        empty.setTitle(L10n.t("No matching settings"));
        empty.setSummary(L10n.t("Try a different word or clear the search."));
        empty.setSelectable(false);
        empty.setPersistent(false);
        pageStatus = new SettingsRows.Row(context);
        pageStatus.setSelectable(false);
        pageStatus.setPersistent(false);
        Bundle state = saved == null ? null : saved.getBundle(STATE);
        if (state != null) {
            route = state.getString("route", "");
            query = state.getString("query", "");
            homePosition = state.getInt("homePosition");
            homeOffset = state.getInt("homeOffset");
            morePosition = state.getInt("morePosition");
            moreOffset = state.getInt("moreOffset");
            if (!MORE.equals(route) && selected() == null) route = "";
        }
        source.registerDataSetObserver(changes);
        list.setAdapter(this);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Preference item = getItem(position);
            int original = originalPosition(item);
            if (original >= 0) {
                // PreferenceScreen indexes its OWN adapter, not the adapter on the ListView.
                screen.onItemClick(parent, view, original, source.getItemId(original));
            } else if (item.getOnPreferenceClickListener() != null) {
                item.getOnPreferenceClickListener().onPreferenceClick(item);
            }
        });
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        if (state != null) showAt(state.getInt("position"), state.getInt("offset"));
    }

    private void section(String id, String title, String detail, String icon, boolean primary) {
        for (Preference candidate : page.sections()) {
            if (!title.contentEquals(candidate.getTitle())) continue;
            Preference link = link(screen.getContext(), title, detail, icon);
            if (!primary) link.setIcon(SettingsIcons.icon(screen.getContext(), icon, palette().heading));
            link.setKey("section_" + id);
            link.setOnPreferenceClickListener(ignored -> { navigate(id); return true; });
            sections.add(new Section(id, (PreferenceCategory) candidate, link, primary));
            return;
        }
    }

    private static Preference link(Context context, String title, String summary, String icon) {
        Preference row = new SettingsRows.Row(context);
        row.setTitle(title);
        row.setSummary(summary);
        row.setIcon(SettingsIcons.icon(context, icon, palette().summary));
        row.setPersistent(false);
        return row;
    }

    private Section selected() {
        for (Section section : sections) if (section.id.equals(route)) return section;
        return null;
    }

    private CharSequence title() {
        if (MORE.equals(route)) return more.getTitle();
        Section section = selected();
        return section == null ? "Hushfacebook" : section.category.getTitle();
    }

    void navigate(String destination) {
        refocus = null;
        rememberIndex();
        query = "";
        route = destination;
        if (!MORE.equals(route) && selected() == null) route = "";
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        showAt(0, 0);
        list.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    boolean open(Preference preference) {
        for (Section section : sections) {
            if (preference != section.category && preference.getParent() != section.category) continue;
            navigate(section.id);
            // A link to one setting lands on its row rather than the top of a long page.
            int row = visible.indexOf(preference);
            if (row > 0) showAt(row, 0);
            return true;
        }
        return false;
    }

    void search(String text) {
        if (query.equals(text)) return;
        refocus = null;
        if (query.isEmpty()) rememberIndex();
        query = text;
        route = "";
        rebuild();
        showAt(query.isEmpty() ? homePosition : 0, query.isEmpty() ? homeOffset : 0);
    }

    boolean back() {
        if (!query.isEmpty()) {
            search("");
            host.showPage(title(), true, "");
            return true;
        }
        if (route.isEmpty()) return false;
        Section section = selected();
        Preference left = section != null ? section.link : more;
        route = section != null && !section.primary ? MORE : "";
        rebuild();
        host.showPage(title(), route.isEmpty(), query);
        showAt(MORE.equals(route) ? morePosition : homePosition,
                MORE.equals(route) ? moreOffset : homeOffset);
        refocus = left;
        return true;
    }

    private void showAt(int position, int offset) {
        // A header resize otherwise syncs the old page's visible children back over this
        // selection in touch mode. Rebind when changing pages; normal preference updates
        // still keep their current views and scroll position through notifyDataSetChanged.
        list.setAdapter(this);
        list.setSelectionFromTop(position, offset);
    }

    private void rememberIndex() {
        View first = list.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        if (route.isEmpty() && query.isEmpty()) {
            homePosition = list.getFirstVisiblePosition();
            homeOffset = offset;
        } else if (MORE.equals(route)) {
            morePosition = list.getFirstVisiblePosition();
            moreOffset = offset;
        }
    }

    void save(Bundle out) {
        Bundle state = new Bundle();
        state.putString("route", route);
        state.putString("query", query);
        state.putInt("homePosition", homePosition);
        state.putInt("homeOffset", homeOffset);
        state.putInt("morePosition", morePosition);
        state.putInt("moreOffset", moreOffset);
        state.putInt("position", list.getFirstVisiblePosition());
        View first = list.getChildAt(0);
        state.putInt("offset", first == null ? 0 : first.getTop());
        out.putBundle(STATE, state);
    }

    void close() { source.unregisterDataSetObserver(changes); }

    private void rebuild() {
        visible.clear();
        String terms = normalized(query).trim();
        Section selected = selected();
        if (!terms.isEmpty()) {
            for (Section section : sections) {
                boolean headingAdded = false;
                for (int i = 0; i < section.category.getPreferenceCount(); i++) {
                    Preference row = section.category.getPreference(i);
                    String text = normalized(section.category.getTitle() + " " + row.getTitle() + " " + row.getSummary());
                    boolean matches = true;
                    for (String term : terms.split("\\s+")) if (!text.contains(term)) matches = false;
                    if (!matches) continue;
                    if (!headingAdded) visible.add(section.category);
                    headingAdded = true;
                    visible.add(row);
                }
            }
            host.showResults(visible.size() - headings());
            if (visible.isEmpty()) visible.add(empty);
            else addPageStatus();
        } else if (selected != null) {
            for (int i = 0; i < selected.category.getPreferenceCount(); i++) visible.add(selected.category.getPreference(i));
            addPageStatus();
        } else if (MORE.equals(route)) {
            for (Section section : sections) if (!section.primary) visible.add(section.link);
        } else {
            visible.add(screen.getPreference(0));
            // Only after an update that turned switches off, until it's opened: its own line under the card.
            Preference startsOff = screen.findPreference(StartsOffNote.KEY);
            if (startsOff != null) visible.add(startsOff);
            // Only in a build that lacks Restore screens on a re-signed install: its own line under the card.
            Preference restore = screen.findPreference(HushfacebookPreferenceFragment.MISSING_RESTORE_TRUST);
            if (restore != null) visible.add(restore);
            // Only in a build that lacks another default patch: the card's own line under it.
            Preference missing = screen.findPreference(HushfacebookPreferenceFragment.MISSING_DEFAULTS);
            if (missing != null) visible.add(missing);
            visible.add(browse);
            for (Section section : sections) if (section.primary) visible.add(section.link);
            visible.add(more);
            visible.add(support);
        }
        if (terms.isEmpty()) host.showResults(-1);
        notifyDataSetChanged();
    }

    private int headings() {
        int count = 0;
        for (Preference item : visible) if (item instanceof PreferenceCategory) count++;
        return count;
    }

    /**
     * Starts a category or search page with a short line while Hushfacebook is paused or a pause
     * or its end waits for a restart, when the page shows a switch Pause reaches, or while a restart
     * is owed for a setting the page shows. On a paused start the switches keep showing what was
     * saved, which read as active without it. Nothing here changes a saved choice.
     */
    private void addPageStatus() {
        Context context = screen.getContext();
        boolean paused = HushfacebookPause.isPaused();
        boolean nextPaused = HushfacebookPause.pausesNextStart(context);
        pageAction = (paused || nextPaused) && showsPausable();
        if (pageAction) {
            pageStatus.setTitle(paused ? L10n.t("Hushfacebook is paused") : L10n.t("Hushfacebook is on"));
            // Worded like the Pause switch: Debug logging, Lock Facebook and what was set when patching stay in.
            pageStatus.setSummary(paused == nextPaused
                    ? L10n.t("Until you resume, every switch but Debug logging and Lock Facebook acts as if it "
                    + "were off. Changes made when you patched stay in.")
                    : paused ? L10n.t("Hushfacebook turns back on when Facebook restarts.")
                    : L10n.t("Hushfacebook pauses when Facebook restarts."));
            pageStatus.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                    paused ? palette().summary : palette().heading));
        } else if (showsRestartOwed()) {
            pageStatus.setTitle(L10n.t("A change here applies after Facebook restarts."));
            pageStatus.setSummary(null);
            pageStatus.setIcon(SettingsIcons.icon(context, SettingsIcons.UPDATES, palette().heading));
        } else {
            return;
        }
        visible.add(0, pageStatus);
    }

    /** Whether the page shows a setting Pause answers for, or the Pause switch itself. */
    private boolean showsPausable() {
        for (Preference row : visible) {
            Setting<?> setting = row.hasKey() ? Setting.getSettingFromPath(row.getKey()) : null;
            if (setting != null && (!setting.isKeptWhenPaused() || setting == BaseSettings.PAUSED)) return true;
        }
        return false;
    }

    private boolean showsRestartOwed() {
        for (Preference row : visible) {
            if (row.hasKey() && AbstractPreferenceFragment.restartPending.contains(row.getKey())) return true;
        }
        return false;
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private int originalPosition(Preference item) {
        for (int i = 0; i < source.getCount(); i++) if (source.getItem(i) == item) return i;
        return -1;
    }

    @Override public int getCount() { return visible.size(); }
    @Override public Preference getItem(int position) { return visible.get(position); }
    @Override public long getItemId(int position) { return System.identityHashCode(getItem(position)); }
    @Override public boolean hasStableIds() { return true; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return getItem(position).isEnabled() && getItem(position).isSelectable(); }
    // Preference creates several incompatible layouts. Never recycle one kind into another.
    @Override public int getItemViewType(int position) { return IGNORE_ITEM_VIEW_TYPE; }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Preference item = getItem(position);
        View row = item.getView(null, parent);
        if (item == refocus) {
            refocus = null;
            // Posted from the new row, it runs after this layout has put the row in the window.
            row.post(() -> row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
        if (!(item instanceof PreferenceCategory)) {
            Object group = group(item);
            boolean first = position == 0 || group(getItem(position - 1)) != group;
            boolean last = position + 1 == getCount() || group(getItem(position + 1)) != group;
            palette().paintSurface(row, item, first, last);
            if (MORE.equals(route)) {
                row.setMinimumHeight(dp(56));
                row.setPaddingRelative(dp(34), dp(12), dp(34), dp(12));
            }
        }
        if (item == screen.getPreference(0)) {
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        }
        if (item == screen.getPreference(0)) bindStatus(row);
        if (item == pageStatus && pageAction) {
            bindAction(row, HushfacebookPause.isPaused(), HushfacebookPause.pausesNextStart(screen.getContext()));
        }
        return row;
    }

    private void bindStatus(View row) {
        boolean nextPaused = HushfacebookPause.pausesNextStart(screen.getContext());
        boolean paused = HushfacebookPause.isPaused();
        TextView summary = row.findViewById(android.R.id.summary);
        String build = "\n" + HushfacebookPreferenceFragment.overviewBuildDetails();
        if (!paused && !nextPaused && ReleaseCheck.statusLine() == null) {
            summary.setText(L10n.t("Your controls are active.") + build);
        } else if (paused && nextPaused && HushfacebookPause.reason() == HushfacebookPause.Reason.SWITCH
                && !markerLeft()) {
            // A marker Resume couldn't remove keeps the card's own line, which says what to do.
            summary.setText(L10n.t("Tap Resume, then restart Facebook.") + build);
        }
        bindAction(row, paused, nextPaused);
    }

    /**
     * Pause, Resume or Undo in [row], whichever changes the next start. The list keeps its place,
     * so the page the button is on stays where it was.
     */
    private void bindAction(View row, boolean paused, boolean nextPaused) {
        ViewGroup frame = row.findViewById(android.R.id.widget_frame);
        frame.removeAllViews();
        Button action = new Button(screen.getContext());
        action.setText(paused != nextPaused ? L10n.t("Undo") : nextPaused ? L10n.t("Resume") : L10n.t("Pause"));
        action.setAllCaps(false);
        action.setTextSize(14);
        action.setTextColor(palette().heading);
        ScreenColors.pressFeedback(action, palette().heading, false);
        action.setMinWidth(dp(48));
        action.setMinHeight(dp(48));
        action.setMinimumWidth(dp(48));
        action.setMinimumHeight(dp(48));
        action.setPadding(0, 0, 0, 0);
        action.setOnClickListener(ignored -> {
            if (nextPaused) {
                page.resumeFromOverview();
            } else {
                Preference pause = page.findPreference(BaseSettings.PAUSED.key);
                if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(!nextPaused);
            }
            rebuild();
        });
        // From one and a half times the text size, a button beside the text leaves the name too
        // little room and it breaks inside the word. Keep the action before long recovery advice.
        TextView summary = row.findViewById(android.R.id.summary);
        if (screen.getContext().getResources().getConfiguration().fontScale >= 1.5f
                && summary != null && summary.getParent() instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams place = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            place.addRule(android.widget.RelativeLayout.BELOW, android.R.id.title);
            place.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START);
            ViewGroup column = (ViewGroup) summary.getParent();
            View earlier = column.findViewWithTag(STATUS_ACTION);
            if (earlier != null) column.removeView(earlier);
            action.setTag(STATUS_ACTION);
            action.setId(View.generateViewId());
            column.addView(action, place);
            android.widget.RelativeLayout.LayoutParams detail = (android.widget.RelativeLayout.LayoutParams) summary.getLayoutParams();
            detail.addRule(android.widget.RelativeLayout.BELOW, action.getId());
            summary.setLayoutParams(detail);
            frame.setVisibility(View.GONE);
            return;
        }
        frame.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setVisibility(View.VISIBLE);
    }

    private boolean markerLeft() {
        java.io.File marker = HushfacebookPause.markerFile(screen.getContext());
        return marker != null && marker.exists();
    }

    private int dp(int value) { return Math.round(value * screen.getContext().getResources().getDisplayMetrics().density); }

    private Object group(Preference item) {
        if (item == more || item == support || item == browse || item == screen.getPreference(0)) return item;
        if (item.getParent() != null) return item.getParent();
        return sections;
    }

    private static ScreenColors palette() { return ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown; }

    private static final class Section {
        final String id;
        final PreferenceCategory category;
        final Preference link;
        final boolean primary;
        Section(String id, PreferenceCategory category, Preference link, boolean primary) {
            this.id = id;
            this.category = category;
            this.link = link;
            this.primary = primary;
        }
    }
}
