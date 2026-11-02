/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.ReopenSettingsAfterRestart;
import app.morphe.extension.shared.settings.preference.SettingsSearch;

/**
 * A view of the complete preference model. Filtering never removes preferences from their
 * screen, so imports, dependencies, persistence and restart notices reach collapsed sections.
 */
@SuppressWarnings("deprecation")
final class SettingsNavigation extends BaseAdapter {
    private static final String STATE = "hushfacebook_navigation";
    private static final String RESTART_STATE = "hushsettings://accordion";
    static final String SUPPORT = "support";
    /** Marks the Pause, Resume or Undo button placed under a status row's title, so a rebind replaces it. */
    private static final String STATUS_ACTION = "hushfacebook_status_action";
    /** The restart row's key. Like the other rows only this view draws, it stores nothing. */
    static final String RESTART = "action_restart_to_apply";
    static final String REPORT = "action_report_problem";
    /** The home page's own restart row, there whether or not a change waits. */
    static final String RESTART_NOW = "action_restart_now";
    private final HushfacebookPreferenceFragment page;
    private final SettingsDialog host;
    private final ListView list;
    private final PreferenceScreen screen;
    private final ListAdapter source;
    private final List<Section> sections = new ArrayList<>();
    private final List<Preference> visible = new ArrayList<>();
    private final Object guideLinks = new Object();
    /** Which rows the person hasn't seen yet, for the dot beside them. Null when it couldn't start. */
    @Nullable private final NewRowDots dots;
    private final Set<String> expanded = new LinkedHashSet<>();
    /** Report, restart and project links expand in the same list as the settings. */
    private final Preference support;
    private final PreferenceCategory supportHeading;
    private final Preference supportProject;
    /** Over Report a problem and Restart Facebook, and over Support Hushfacebook, so help reads apart from donating. */
    private final SettingsRows.Subheading helpHeading;
    private final SettingsRows.Subheading projectHeading;
    private final Preference buildDetails;
    /** Just above Support: a new GitHub bug report with this install's versions filled in. */
    private final Preference report;
    /**
     * Between Report a problem and Support whenever no change waits for a restart: a tap restarts
     * Facebook and the screen comes back on the home page, for the times Facebook needs a fresh
     * start anyway (issue #155). While a change waits, {@link #restart} is the page's one restart row.
     */
    private final SettingsRows.Row restartNow;
    /**
     * Shown on every page while a change waits for a restart: a tap restarts Facebook and the
     * screen comes back on the same page ({@link ReopenSettingsAfterRestart}).
     */
    private final SettingsRows.Row restart;
    /** Keeps the shared fragment's restart toast off this screen, which shows the restart row instead. */
    private final AbstractPreferenceFragment.RestartFeedbackPresenter restartFeedback;
    /** The navigation state at the last rebuild, so live row updates keep the list's place. */
    private String rebuiltPage;
    private final Preference empty;
    /** The line a category or search page starts with while a pause applies to it. */
    private final Preference pageStatus;
    /** Whether that line is about Pause, and so carries Resume or Undo. */
    private boolean pageAction;
    private String route = "";
    private String query = "";
    /** The toggled header regains screen-reader focus after its children change. */
    private Preference refocus;
    private int homePosition;
    private int homeOffset;
    private String homeAnchor = "";
    private Preference pendingAnchor;
    private int pendingOffset;
    private int auditGeneration;

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
        section("Opening Facebook", L10n.t("Opening Facebook"), L10n.t("Your start tab and launch settings"), SettingsIcons.OPENING);
        section("News feed", L10n.t("News feed"), L10n.t("Ads, suggestions and word filters"), SettingsIcons.FEED);
        section("Stories", L10n.t("Stories"), L10n.t("Saving, playback and anonymous viewing"), SettingsIcons.STORIES);
        section("Reels and Watch", L10n.t("Reels and Watch"), L10n.t("Cleaner reels and video controls"), SettingsIcons.REELS);
        // Renamed sections keep their first names as route IDs, and as words search still finds them by.
        section("Playback", L10n.t("Video and audio"), L10n.t("Autoplay, quality and sound"), SettingsIcons.PLAYBACK,
                L10n.t("Playback"));
        section("Downloads", L10n.t("Downloads"), L10n.t("Quality, format and file names"), SettingsIcons.DOWNLOADS);
        section("Comments", L10n.t("Comments and writing"), L10n.t("Sorting, replies and reactions"),
                SettingsIcons.COMMENTS, L10n.t("Writing"));
        section("Chats", L10n.t("Chats and Messenger"), null, SettingsIcons.CHATS, L10n.t("Chats"));
        section("Menu", L10n.t("Menu"), null, SettingsIcons.MENU);
        section("Meta's other products", L10n.t("Meta's other products"), null, SettingsIcons.BLOCK);
        section("Search", L10n.t("Search"), null, SettingsIcons.SEARCH);
        section("Marketplace", L10n.t("Marketplace"), null, SettingsIcons.MARKETPLACE);
        section("Notifications", L10n.t("Notifications"), null, SettingsIcons.NOTIFICATIONS);
        section("Links", L10n.t("Links and sharing"), null, SettingsIcons.LINKS, L10n.t("Links"));
        section("Privacy", L10n.t("Privacy"), null, SettingsIcons.TELEMETRY);
        section("Updates", L10n.t("Updates"), null, SettingsIcons.UPDATES);
        section("Tabs and navigation", L10n.t("Tabs and navigation"), L10n.t("Tab bar, top bar, hidden tabs and dots"),
                SettingsIcons.PHONE);
        section("Appearance", L10n.t("Appearance"), null, SettingsIcons.APPEARANCE);
        section("Set when you patched", L10n.t("Built-in changes"), null, SettingsIcons.PATCHED,
                L10n.t("Set when you patched"));
        section("Pause, backup and diagnostics", L10n.t("Pause, backup and diagnostics"), null, SettingsIcons.TOOLS);
        section("About", L10n.t("About"), null, SettingsIcons.ABOUT);
        List<PreferenceCategory> pages = new ArrayList<>();
        for (Section section : sections) pages.add(section.category);
        dots = NewRowDots.create(context, pages);
        guideIcon(Settings.HIDE_FEED_REELS, SettingsIcons.FEED);
        guideIcon(Settings.TAP_TO_PLAY, SettingsIcons.PLAYBACK);
        guideIcon(Settings.HIDE_REELS_TAB, SettingsIcons.REELS);
        guideIcon(Settings.MARKETPLACE_ONLY, SettingsIcons.MARKETPLACE);
        support = link(context, L10n.t("Support"), L10n.t("Reports, restart and app information"), SettingsIcons.ABOUT);
        support.setKey(SUPPORT);
        support.setOnPreferenceClickListener(ignored -> { toggle(SUPPORT); return true; });
        supportHeading = new SettingsRows.Heading(context);
        supportHeading.setTitle(support.getTitle());
        helpHeading = new SettingsRows.Subheading(context);
        helpHeading.setTitle(L10n.t("Help and restart"));
        projectHeading = new SettingsRows.Subheading(context);
        projectHeading.setTitle(L10n.t("Support the project"));
        supportProject = link(context, L10n.t("Support Hushfacebook"), null, SettingsIcons.OPENING);
        supportProject.setKey(HushfacebookPages.SUPPORT);
        supportProject.setOnPreferenceClickListener(row -> { HushfacebookPages.openSupport(row.getContext()); return true; });
        buildDetails = new SettingsRows.Row(context);
        buildDetails.setSummary(HushfacebookPreferenceFragment.overviewBuildDetails());
        buildDetails.setSelectable(false);
        buildDetails.setPersistent(false);
        report = link(context, L10n.t("Report a problem"),
                L10n.t("Opens a GitHub report with your versions and settings filled in. Add what happened."), SettingsIcons.BUG);
        report.setKey(REPORT);
        report.setOnPreferenceClickListener(row -> { ReportProblemForm.open(row.getContext(), screen); return true; });
        restartNow = new SettingsRows.Row(context);
        restartNow.setKey(RESTART_NOW);
        restartNow.setPersistent(false);
        restartNow.actsAtOnce = true;
        restartNow.setTitle(L10n.t("Restart Facebook"));
        restartNow.setSummary(L10n.t("Facebook closes and reopens on this page."));
        restartNow.setIcon(SettingsIcons.icon(context, SettingsIcons.UPDATES, palette().heading));
        restartNow.setOnPreferenceClickListener(row -> {
            ReopenSettingsAfterRestart.restartInto(row.getContext(), restartTarget());
            return true;
        });
        restart = new SettingsRows.Row(context);
        restart.setKey(RESTART);
        restart.setPersistent(false);
        // A tap restarts at once, so the row goes without a chevron.
        restart.actsAtOnce = true;
        restart.setSummary(L10n.t("Facebook closes and reopens on this page."));
        restart.setIcon(SettingsIcons.icon(context, SettingsIcons.UPDATES, palette().heading));
        restart.setOnPreferenceClickListener(row -> {
            ReopenSettingsAfterRestart.restartInto(row.getContext(), restartTarget());
            return true;
        });
        // The row appears as the switch flips. A screen reader hears the saved message as well,
        // since the row can land above the part of the list in view.
        restartFeedback = (shown, message) -> list.announceForAccessibility(message);
        AbstractPreferenceFragment.setRestartFeedbackPresenter(restartFeedback);
        empty = new SettingsRows.Row(context);
        empty.setTitle(L10n.t("No matching settings"));
        empty.setSummary(L10n.t("Try a different word or clear the search."));
        empty.setIcon(SettingsIcons.icon(context, SettingsIcons.SEARCH, palette().heading));
        empty.setSelectable(false);
        empty.setPersistent(false);
        pageStatus = new SettingsRows.Row(context);
        pageStatus.setSelectable(false);
        pageStatus.setPersistent(false);
        Bundle state = saved == null ? null : saved.getBundle(STATE);
        if (state == null) state = restartState(host.startPage());
        if (state != null) {
            route = state.getString("route", "");
            query = state.getString("query", "");
            homePosition = state.getInt("homePosition");
            homeOffset = state.getInt("homeOffset");
            homeAnchor = state.getString("homeAnchor", "");
            ArrayList<String> opened = state.getStringArrayList("expanded");
            if (opened != null) {
                for (String id : opened) if (header(id) != null) expanded.add(id);
            } else if (header(route) != null) {
                // Saved state and restart markers from the earlier category screens still open the target.
                expanded.add(route);
            }
            if (header(route) == null) route = "";
        } else {
            // Nothing saved: the page the screen was asked to open on, such as the one a restart
            // from the restart row left.
            route = host.startPage();
            if (header(route) != null) expanded.add(route);
            else route = "";
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
        showPage();
        if (state != null) restoreAnchor(state.getString("anchor", ""), state.getInt("position"), state.getInt("offset"));
        else if (header(route) != null) showAt(visible.indexOf(header(route)), 0);
    }

    private void section(String id, String title, String detail, String icon) {
        section(id, title, detail, icon, null);
    }

    /**
     * A section, found by the title its category was given, so a renamed category needs its
     * title changed here too. [alias] is the name it had before, which search still answers to.
     */
    private void section(String id, String title, String detail, String icon, @Nullable String alias) {
        for (Preference candidate : page.sections()) {
            if (!title.contentEquals(candidate.getTitle())) continue;
            Preference link = link(screen.getContext(), title, detail, icon);
            link.setKey("section_" + id);
            link.setOnPreferenceClickListener(ignored -> { toggle(id); return true; });
            sections.add(new Section(id, (PreferenceCategory) candidate, link, alias));
            return;
        }
    }

    private static Preference link(Context context, String title, String summary, String icon) {
        Preference row = new SettingsRows.Row(context);
        row.setTitle(title);
        row.setSummary(summary);
        row.setIcon(SettingsIcons.icon(context, icon, palette().heading));
        row.setPersistent(false);
        return row;
    }

    private void guideIcon(Setting<?> setting, String icon) {
        Preference row = screen.findPreference("action_show_" + setting.key);
        if (row != null) row.setIcon(SettingsIcons.icon(screen.getContext(), icon, palette().heading));
    }

    private Section selected() {
        for (Section section : sections) if (section.id.equals(route)) return section;
        return null;
    }

    private CharSequence title() {
        if (SUPPORT.equals(route)) return support.getTitle();
        Section section = selected();
        return section == null ? "Hushfacebook" : section.category.getTitle();
    }

    private void showPage() {
        host.showPage("Hushfacebook", true, query);
        String shownRoute = route;
        int generation = ++auditGeneration;
        if (!query.isEmpty()) return;
        list.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                list.getViewTreeObserver().removeOnPreDrawListener(this);
                if (generation != auditGeneration || !query.isEmpty() || !shownRoute.equals(route)
                        || host.getDialog() == null || !host.getDialog().isShowing()
                        || !list.isAttachedToWindow() || list.getAdapter() != SettingsNavigation.this
                        || list.getChildCount() == 0 || list.isLayoutRequested()) return true;
                Preference target = header(route);
                int index = visible.indexOf(target);
                if (target != null && (index < list.getFirstVisiblePosition() || index > list.getLastVisiblePosition()))
                    return true;
                AuditMode.pageShown(list.getContext(), route, title(), new ArrayList<>(expanded), sectionRows(route));
                return true;
            }
        });
    }

    /** Closing settings acknowledges open sections. Search alone never marks a section seen. */
    private void leftPage() {
        for (Section section : sections) if (expanded.contains(section.id)) acknowledge(section);
    }

    private void acknowledge(Section section) {
        if (dots == null) return;
        try {
            dots.left(section.category);
        } catch (RuntimeException ignored) {
            // The dot stays a visit longer.
        }
    }

    /** Whether [item] gets the dot: a new row, or a home page row for a section with one. */
    private boolean fresh(Preference item) {
        if (dots == null || !query.isEmpty()) return false;
        try {
            for (Section section : sections) if (item == section.link)
                return !expanded.contains(section.id) && dots.anyNew(section.category);
            return dots.isNew(item);
        } catch (RuntimeException failed) {
            return false;
        }
    }

    void navigate(String destination) {
        refocus = null;
        query = "";
        route = header(destination) == null ? "" : destination;
        if (route.isEmpty()) {
            leftPage();
            expanded.clear();
        } else openAlone(route);
        rebuild();
        showPage();
        showAt(route.isEmpty() ? 0 : visible.indexOf(header(route)), 0);
    }

    /**
     * Opens [id] and folds every other open section (#368), so the list never grows into several
     * sections at once. A folded section's new rows count as seen, as when it's folded by hand.
     */
    private void openAlone(String id) {
        for (Iterator<String> open = expanded.iterator(); open.hasNext(); ) {
            String other = open.next();
            if (other.equals(id)) continue;
            for (Section section : sections) if (section.id.equals(other)) acknowledge(section);
            open.remove();
        }
        expanded.add(id);
    }

    boolean isExpanded(String id) {
        return expanded.contains(id);
    }

    @Nullable
    private Preference header(String id) {
        if (SUPPORT.equals(id)) return support;
        for (Section section : sections) if (section.id.equals(id)) return section.link;
        return null;
    }

    @Nullable
    private String headerId(Preference item) {
        if (item == support) return SUPPORT;
        for (Section section : sections) if (section.link == item) return section.id;
        return null;
    }

    /** The route id of [category]'s section, which stays the same when its title is renamed. */
    @Nullable
    String sectionId(Preference category) {
        for (Section section : sections) if (section.category == category) return section.id;
        return null;
    }

    private int sectionRows(String id) {
        if (!expanded.contains(id)) return 0;
        if (SUPPORT.equals(id)) {
            int count = 0;
            for (Preference row : new Preference[]{report, restartNow, supportProject})
                if (visible.contains(row)) count++;
            return count;
        }
        for (Section section : sections) if (section.id.equals(id)) {
            int count = 0;
            for (int i = 0; i < section.category.getPreferenceCount(); i++)
                if (visible.contains(section.category.getPreference(i))) count++;
            return count;
        }
        return 0;
    }

    private void toggle(String id) {
        setExpanded(id, !expanded.contains(id));
    }

    private void setExpanded(String id, boolean open) {
        Preference header = header(id);
        if (header == null || expanded.contains(id) == open) return;
        int before = visible.indexOf(header);
        View bound = list.getChildAt(before - list.getFirstVisiblePosition());
        int top = bound == null ? 0 : bound.getTop();
        int position = list.getFirstVisiblePosition();
        String anchor = position < visible.size() ? rowToken(getItem(position)) : "";
        View first = list.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        if (open) openAlone(id);
        else {
            for (Section section : sections) if (section.id.equals(id)) acknowledge(section);
            expanded.remove(id);
        }
        route = id;
        query = "";
        refocus = header;
        rebuild();
        showPage();
        if (bound != null) {
            // Keep the tapped header in place, revealing at least one child near the bottom edge.
            int reveal = Math.max(0, list.getHeight() - bound.getHeight() - dp(72));
            showAt(visible.indexOf(header), open ? Math.min(top, reveal) : top);
        } else restoreAnchor(anchor, visible.indexOf(header), offset);
    }

    boolean open(Preference preference) {
        for (Section section : sections) {
            if (preference != section.category && preference.getParent() != section.category) continue;
            navigate(section.id);
            // A link to one setting reveals its row within the expanded section.
            int row = visible.indexOf(preference);
            if (row > 0) showAt(row, 0);
            return true;
        }
        return false;
    }

    void search(String text) {
        if (query.equals(text)) return;
        auditGeneration++;
        refocus = null;
        if (query.isEmpty()) rememberIndex();
        query = text;
        rebuild();
        if (query.isEmpty()) restoreAnchor(homeAnchor, homePosition, homeOffset);
        else showAt(0, 0);
    }

    boolean back() {
        if (!query.isEmpty()) {
            search("");
            showPage();
            return true;
        }
        return false;
    }

    private void showAt(int position, int offset) {
        if (getCount() == 0) return;
        position = Math.max(0, Math.min(position, getCount() - 1));
        pendingAnchor = getItem(position);
        pendingOffset = offset;
        // A header resize otherwise syncs the old visible children back over this
        // selection in touch mode. Rebind when revealing a section; normal preference updates
        // still keep their current views and scroll position through notifyDataSetChanged.
        list.setAdapter(this);
        // Outside touch mode, selection skips read-only status rows and headings. Offset the
        // next selectable row by their full heights so the requested row still starts the page.
        // No status becomes actionable, and no later animation moves the person's place.
        int selectable = position;
        int top = offset;
        if (!list.isInTouchMode()) {
            int width = list.getWidth();
            if (width == 0) width = list.getResources().getDisplayMetrics().widthPixels;
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    width - list.getPaddingLeft() - list.getPaddingRight(), View.MeasureSpec.EXACTLY);
            while (selectable >= 0 && selectable < getCount() && !isEnabled(selectable)) {
                View row = getView(selectable++, null, list);
                row.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                top += row.getMeasuredHeight() + list.getDividerHeight();
            }
        }
        list.setSelectionFromTop(selectable < getCount() ? selectable : position,
                selectable < getCount() ? top : offset);
    }

    private void rememberIndex() {
        View first = list.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        if (query.isEmpty()) {
            homePosition = list.getFirstVisiblePosition();
            homeOffset = offset;
            homeAnchor = homePosition < getCount() ? rowToken(getItem(homePosition)) : "";
        }
    }

    void save(Bundle out) {
        out.putBundle(STATE, currentState());
    }

    private Bundle currentState() {
        rememberIndex();
        Bundle state = new Bundle();
        state.putString("route", route);
        state.putString("query", query);
        state.putInt("homePosition", homePosition);
        state.putInt("homeOffset", homeOffset);
        state.putString("homeAnchor", homeAnchor);
        state.putStringArrayList("expanded", new ArrayList<>(expanded));
        state.putInt("position", list.getFirstVisiblePosition());
        state.putString("anchor", list.getFirstVisiblePosition() < getCount()
                ? rowToken(getItem(list.getFirstVisiblePosition())) : "");
        View first = list.getChildAt(0);
        state.putInt("offset", first == null ? 0 : first.getTop());
        return state;
    }

    private String rowToken(Preference item) {
        String id = headerId(item);
        if (id != null) return "section:" + id;
        for (Section section : sections) if (item == section.category) return "heading:" + section.id;
        if (item == buildDetails) return "build";
        if (item == screen.getPreference(0)) return "status";
        // An inline heading has no key, so its section and title find it again.
        if (item instanceof SettingsRows.Subheading && item.getParent() != null) {
            return "sub:" + sectionId(item.getParent()) + ":" + item.getTitle();
        }
        return item.hasKey() ? "key:" + item.getKey() : "";
    }

    private void restoreAnchor(String anchor, int fallback, int offset) {
        if (anchor != null && !anchor.isEmpty()) {
            for (int i = 0; i < getCount(); i++) if (anchor.equals(rowToken(getItem(i)))) {
                showAt(i, offset);
                return;
            }
        }
        showAt(fallback, offset);
    }

    /** Reuse the existing expiring, one-shot restart marker for UI state only. */
    private String restartTarget() {
        Bundle state = currentState();
        Uri.Builder uri = Uri.parse(RESTART_STATE).buildUpon();
        for (String id : expanded) uri.appendQueryParameter("open", id);
        for (String key : new String[]{"route", "query", "anchor", "homeAnchor"})
            uri.appendQueryParameter(key, state.getString(key, ""));
        for (String key : new String[]{"position", "offset", "homePosition", "homeOffset"})
            uri.appendQueryParameter(key, Integer.toString(state.getInt(key)));
        return uri.build().toString();
    }

    @Nullable
    private Bundle restartState(String value) {
        if (value == null || !value.startsWith(RESTART_STATE + "?")) return null;
        Bundle state = new Bundle();
        if (value.length() > 32768) return state;
        Uri uri = Uri.parse(value);
        if (!"hushsettings".equals(uri.getScheme()) || !"accordion".equals(uri.getAuthority())) return state;
        state.putStringArrayList("expanded", new ArrayList<>(uri.getQueryParameters("open")));
        for (String key : new String[]{"route", "query", "anchor", "homeAnchor"}) {
            String text = uri.getQueryParameter(key);
            state.putString(key, text == null ? "" : text);
        }
        for (String key : new String[]{"position", "offset", "homePosition", "homeOffset"}) {
            try {
                int number = Integer.parseInt(uri.getQueryParameter(key));
                state.putInt(key, Math.max(-10000, Math.min(10000, number)));
            } catch (NumberFormatException malformed) {
                // A malformed or older UI marker opens safely at the top; saved settings are separate.
                state.putInt(key, 0);
            }
        }
        return state;
    }

    void close() {
        auditGeneration++;
        leftPage();
        source.unregisterDataSetObserver(changes);
        AbstractPreferenceFragment.clearRestartFeedbackPresenter(restartFeedback);
    }

    /** A change that waits for a restart was made or taken back: the restart row comes, goes or counts again. */
    void restartChanged() { rebuild(); }

    private void rebuild() {
        View first = list.getChildAt(0);
        Preference anchor = first != null && first.getTag() instanceof Preference
                ? (Preference) first.getTag() : pendingAnchor;
        int position = visible.indexOf(anchor);
        int offset = first == null ? pendingOffset : first.getTop();
        visible.clear();
        List<String> terms = SettingsSearch.terms(query);
        // Switches that are on (#353) sits above the sections, on the main list and in search.
        Preference favorites = screen.findPreference(FavoriteSwitchesScreen.ROW);
        if (!terms.isEmpty()) {
            if (favorites != null && SettingsSearch.matchesTerms(terms, favorites.getTitle(), favorites.getSummary()))
                visible.add(favorites);
            for (Section section : sections) {
                boolean headingAdded = false;
                CharSequence subheading = null;
                for (int i = 0; i < section.category.getPreferenceCount(); i++) {
                    Preference row = section.category.getPreference(i);
                    // A subheading names the rows under it, so "video quality" finds them, but isn't a result.
                    if (row instanceof SettingsRows.Subheading) {
                        subheading = row.getTitle();
                        continue;
                    }
                    if (!SettingsSearch.matchesTerms(terms, section.category.getTitle(), section.alias, subheading,
                            row.getTitle(), row.getSummary(), TextUtils.join(" ", SettingsRows.aliases(row)))) continue;
                    if (!headingAdded) visible.add(section.category);
                    headingAdded = true;
                    visible.add(row);
                }
            }
            boolean supportAdded = false;
            for (Preference row : new Preference[]{report, restartNow, supportProject}) {
                if (row == restartNow && ReopenSettingsAfterRestart.changesWaiting(screen.getContext()) > 0) continue;
                if (!SettingsSearch.matchesTerms(terms, support.getTitle(), row.getTitle(), row.getSummary())) continue;
                if (!supportAdded) visible.add(supportHeading);
                supportAdded = true;
                visible.add(row);
            }
            host.showResults(visible.size() - headings());
            if (visible.isEmpty()) visible.add(empty);
            else addPageStatus();
        } else {
            if (HushfacebookPause.isPaused() || HushfacebookPause.pausesNextStart(screen.getContext()))
                visible.add(screen.getPreference(0));
            // Only after an update that turned switches off, until it's opened: its own line under the card.
            Preference startsOff = screen.findPreference(StartsOffNote.KEY);
            if (startsOff != null) visible.add(startsOff);
            // Only in a build that lacks Restore screens on a re-signed install: its own line under the card.
            Preference restore = screen.findPreference(HushfacebookPreferenceFragment.MISSING_RESTORE_TRUST);
            if (restore != null) visible.add(restore);
            // Only while Facebook's images are coarser than the screen (#365): its own line under the card.
            Preference soft = screen.findPreference(HushfacebookPreferenceFragment.SOFT_ICONS);
            if (soft != null) visible.add(soft);
            // Only in a build that lacks another default patch: the card's own line under it.
            Preference missing = screen.findPreference(HushfacebookPreferenceFragment.MISSING_DEFAULTS);
            if (missing != null) visible.add(missing);
            if (favorites != null) visible.add(favorites);
            for (Section section : sections) {
                visible.add(section.link);
                if (expanded.contains(section.id)) {
                    for (int i = 0; i < section.category.getPreferenceCount(); i++) visible.add(section.category.getPreference(i));
                }
            }
            visible.add(support);
            if (expanded.contains(SUPPORT)) {
                visible.add(helpHeading);
                visible.add(report);
                if (ReopenSettingsAfterRestart.changesWaiting(screen.getContext()) <= 0) visible.add(restartNow);
                visible.add(projectHeading);
                visible.add(supportProject);
            }
            visible.add(buildDetails);
        }
        addRestart();
        bindAction(host.pauseAction, HushfacebookPause.isPaused(), HushfacebookPause.pausesNextStart(screen.getContext()));
        host.pauseAction.setVisibility(query.isEmpty() ? View.VISIBLE : View.GONE);
        if (terms.isEmpty()) host.showResults(-1);
        notifyDataSetChanged();
        keepPlace(anchor, position, offset);
    }

    /**
     * The restart row, on whatever page is showing, while a change waits for a restart: under the
     * home page's status card or a page's pause line, and otherwise first. Its title counts the
     * changes a restart applies.
     */
    private void addRestart() {
        Context context = screen.getContext();
        int count = ReopenSettingsAfterRestart.changesWaiting(context);
        if (count <= 0) return;
        restart.setTitle(L10n.quantity(context, count, "Restart Facebook to apply this change",
                "Restart Facebook to apply %1$d changes", count));
        boolean underStatus = !visible.isEmpty()
                && (visible.get(0) == pageStatus || visible.get(0) == screen.getPreference(0));
        visible.add(underStatus ? 1 : 0, restart);
    }

    /** Keep the same control in view when notices or live download rows change above it. */
    private void keepPlace(Preference anchor, int position, int offset) {
        String pageWas = rebuiltPage;
        rebuiltPage = route + '\n' + query;
        if (!rebuiltPage.equals(pageWas) || anchor == null || position < 0) return;
        int next = visible.indexOf(anchor);
        // At the very top, allow a new notice to appear. A scrolled list retains its item and offset.
        if (next >= 0 && next != position && (position > 0 || offset < 0)) showAt(next, offset);
    }

    private int headings() {
        int count = 0;
        for (Preference item : visible) if (item instanceof PreferenceCategory) count++;
        return count;
    }

    /**
     * Starts a category or search page with a short line while Hushfacebook is paused or a pause
     * or its end waits for a restart, when the page shows a switch Pause reaches. On a paused start
     * the switches keep showing what was saved, which read as active without it. Nothing here
     * changes a saved choice. A restart owed for anything else is the restart row's to say.
     */
    private void addPageStatus() {
        Context context = screen.getContext();
        boolean paused = HushfacebookPause.isPaused();
        boolean nextPaused = HushfacebookPause.pausesNextStart(context);
        pageAction = (paused || nextPaused) && showsPausable();
        if (!pageAction) return;
        pageStatus.setTitle(paused ? L10n.t("Hushfacebook is paused") : L10n.t("Hushfacebook is on"));
        // Worded like the Pause switch: Debug logging, Lock Facebook and what was set when patching stay in.
        pageStatus.setSummary(paused == nextPaused
                ? L10n.t("Until you resume, every switch but Debug logging and Lock Facebook acts as if it "
                + "were off. Changes made when you patched stay in.")
                : paused ? L10n.t("Hushfacebook turns back on when Facebook restarts.")
                : L10n.t("Hushfacebook pauses when Facebook restarts."));
        pageStatus.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                paused ? palette().summary : palette().heading));
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
        row.setTag(item);
        if (item == buildDetails) {
            row.setBackgroundColor(palette().background);
            row.setPaddingRelative(dp(16), dp(10), dp(16), dp(10));
            row.setMinimumHeight(0);
            TextView detail = row.findViewById(android.R.id.summary);
            detail.setTag(SettingsDialog.BUILD_DETAILS);
            detail.setTextSize(11);
            detail.setText(buildDetails.getSummary().toString().replace('\n', ' '));
            return row;
        }
        if (item == refocus) {
            refocus = null;
            // Posted from the new row, it runs after this layout has put the row in the window.
            row.post(() -> row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
        if (item instanceof SettingsRows.Subheading) {
            // Drawn on the page like a section title, lined up with the cards it sits over.
            // Support's own two sit flush with its rows, which belong to no section.
            if (query.isEmpty() && item.getParent() instanceof PreferenceCategory)
                row.setPaddingRelative(row.getPaddingStart() + dp(12), row.getPaddingTop(), row.getPaddingEnd(), row.getPaddingBottom());
        } else if (!(item instanceof PreferenceCategory)) {
            Object group = group(item);
            boolean first = position == 0 || group(getItem(position - 1)) != group;
            boolean last = position + 1 == getCount() || group(getItem(position + 1)) != group;
            palette().paintSurface(row, item, first, last);
            if (query.isEmpty() && item.getParent() instanceof PreferenceCategory)
                row.setPaddingRelative(row.getPaddingStart() + dp(12), row.getPaddingTop(), row.getPaddingEnd(), row.getPaddingBottom());
        }
        String sectionId = headerId(item);
        if (sectionId != null) bindHeader(row, item, sectionId);
        NewRowDots.paint(row, fresh(item), palette().accent);
        if (item == screen.getPreference(0)) {
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        }
        if (item == screen.getPreference(0)) bindStatus(row);
        if (item == pageStatus && pageAction) {
            bindAction(row, HushfacebookPause.isPaused(), HushfacebookPause.pausesNextStart(screen.getContext()));
        }
        if (item == screen.getPreference(0)) styleBuildDetails(row);
        return row;
    }

    private void bindHeader(View row, Preference item, String id) {
        boolean open = expanded.contains(id);
        row.setAccessibilityHeading(true);
        row.setStateDescription(L10n.t(open ? "Expanded" : "Collapsed"));
        View arrow = row.findViewWithTag(ScreenColors.CHEVRON);
        if (arrow != null) {
            boolean rtl = row.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            arrow.setRotation((open ? -90 : 90) * (rtl ? -1 : 1));
        }
        TextView title = row.findViewById(android.R.id.title);
        if (title != null && open) title.setTextColor(palette().heading);
        row.setAccessibilityDelegate(new SettingsRows.RowSemantics(item, Button.class) {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                boolean shown = expanded.contains(id);
                info.setHeading(true);
                info.setStateDescription(L10n.t(shown ? "Expanded" : "Collapsed"));
                info.addAction(shown ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }

            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                    setExpanded(id, action == AccessibilityNodeInfo.ACTION_EXPAND);
                    return true;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
    }

    /** Keep provenance visible without giving it the weight of the current running state. */
    private void styleBuildDetails(View row) {
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary == null) return;
        String value = summary.getText().toString();
        String detail = HushfacebookPreferenceFragment.overviewBuildDetails();
        int start = value.indexOf(detail);
        if (start < 0) return;
        summary.setText(value.substring(0, start).trim());
    }

    private void bindStatus(View row) {
        boolean nextPaused = HushfacebookPause.pausesNextStart(screen.getContext());
        boolean paused = HushfacebookPause.isPaused();
        if (!paused && nextPaused) {
            TextView title = row.findViewById(android.R.id.title);
            if (title != null) title.setText(L10n.t("Hushfacebook pauses when Facebook restarts."));
        }
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
        if (frame != null) frame.removeAllViews();
        Button action = row instanceof Button ? (Button) row : new Button(screen.getContext());
        action.setText(paused != nextPaused ? L10n.t("Undo") : nextPaused ? L10n.t("Resume") : L10n.t("Pause"));
        action.setAllCaps(false);
        action.setTextSize(14);
        action.setTextColor(palette().heading);
        palette().outlineAction(action);
        action.setMinWidth(dp(48));
        action.setMinHeight(dp(48));
        action.setMinimumWidth(dp(48));
        action.setMinimumHeight(dp(48));
        action.setOnClickListener(ignored -> {
            if (nextPaused) {
                page.resumeFromOverview();
            } else {
                Preference pause = page.findPreference(BaseSettings.PAUSED.key);
                if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(!nextPaused);
            }
            rebuild();
        });
        if (row == action) return;
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
        return HushfacebookPause.presentMarker(screen.getContext()) != null;
    }

    private int dp(int value) { return Math.round(value * screen.getContext().getResources().getDisplayMetrics().density); }

    Object group(Preference item) {
        if (item == screen.getPreference(0) || item == restart || item == buildDetails) return item;
        if (headerId(item) != null || item instanceof SettingsRows.Subheading) return item;
        if (item == report || item == restartNow || item == supportProject) return support;
        if (!query.trim().isEmpty() && item.getParent() != null) return item.getParent();
        String key = item.getKey();
        if (Settings.DEFAULT_COMMENT_ORDER.key.equals(key) || Settings.COMMENT_ORDER.key.equals(key)) {
            return Settings.COMMENT_ORDER;
        }
        if (Settings.NEWEST_REELS_FIRST.key.equals(key) || Settings.REELS_ORDER.key.equals(key)) {
            return Settings.REELS_ORDER;
        }
        // Guide destinations form one list; independent selectors keep their own surfaces.
        if (key != null && key.startsWith("action_show_")) return guideLinks;
        if (Settings.OPEN_ON_CHOSEN_TAB.key.equals(key) || Settings.START_TAB.key.equals(key)
                || Settings.FEEDS_SUBTAB.key.equals(key)) return Settings.OPEN_ON_CHOSEN_TAB;
        if (Settings.PLAYBACK_QUALITY.key.equals(key) || Settings.REELS_PLAYBACK_QUALITY.key.equals(key)
                || Settings.STORIES_PLAYBACK_QUALITY.key.equals(key)) return Settings.PLAYBACK_QUALITY;
        if (Settings.DOWNLOAD_QUALITY.key.equals(key)) return item;
        if (item.getParent() != null) return item.getParent();
        return sections;
    }

    private static ScreenColors palette() { return ScreenColors.current(); }

    private static final class Section {
        final String id;
        final PreferenceCategory category;
        final Preference link;
        /** The section's earlier name, which search still finds it by, or null. */
        @Nullable final CharSequence alias;
        Section(String id, PreferenceCategory category, Preference link, @Nullable CharSequence alias) {
            this.id = id;
            this.category = category;
            this.link = link;
            this.alias = alias;
        }
    }
}
