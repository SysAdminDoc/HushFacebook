/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.preference.Preference;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.media.RecentlyWatched.Entry;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The Recently watched row on the Playback page and the list it opens (#183): the reels, videos and
 * stories {@link RecentlyWatched} kept, the one watched last first, with a chip for each kind. A tap
 * opens one in Facebook through its own link handler and closes the settings, so it shows. Each row's
 * remove button takes that one off, with Undo under the list to put it back. Clear empties the list
 * at once. The Recently watched row in Facebook's Menu opens the same list straight over the Menu
 * (#529), with no settings under it.
 */
@SuppressWarnings("deprecation") // Framework preferences and fragments, as the rest of the screen.
public final class RecentlyWatchedScreen {
    /** The row's key, for the settings search. No setting is behind it. */
    static final String ROW = "action_recently_watched";

    /** Facebook's own handler for the links it opens, a manifest component that keeps its name. */
    static final String URI_HANDLER = "com.facebook.katana.IntentUriHandler";

    /** The chips, in their order: every kind, then each one. Null stands for every kind. */
    private static final String[] KINDS = {null, RecentlyWatched.REEL, RecentlyWatched.VIDEO, RecentlyWatched.STORY};

    private static final String DOT = " " + (char) 0x00B7 + " ";

    private RecentlyWatchedScreen() {
    }

    /** What the list shows over: the settings page, or a screen of Facebook's own. */
    interface Host {
        /** The colours the list is painted in. */
        ScreenColors palette();

        /** Shows [builder]'s dialog, painted, or nothing when the screen is going. */
        @Nullable
        AlertDialog show(AlertDialog.Builder builder);

        /** The activity a tapped entry starts from, when there's one. */
        @Nullable
        Activity activity();

        /** An entry is on its way: whatever would hide it closes. */
        void opened();
    }

    /** The settings page: its colours, its dialogs, and the settings close once an entry opens. */
    static Host over(HushfacebookPreferenceFragment page) {
        return new Host() {
            @Override
            public ScreenColors palette() {
                return ScreenColors.current();
            }

            @Nullable
            @Override
            public AlertDialog show(AlertDialog.Builder builder) {
                return page.show(builder);
            }

            @Nullable
            @Override
            public Activity activity() {
                return page.getActivity();
            }

            @Override
            public void opened() {
                Fragment screen = page.getParentFragment();
                if (screen instanceof SettingsDialog) {
                    SettingsEntry.onClosedByUser();
                    ((SettingsDialog) screen).dismissAllowingStateLoss();
                }
            }
        };
    }

    /**
     * One of Facebook's screens, the Menu's: the phone's colours under Material You, the black
     * page's otherwise, as the settings would have. Nothing else is open to close.
     */
    static Host over(Activity activity, ScreenColors colors) {
        return new Host() {
            @Override
            public ScreenColors palette() {
                return colors;
            }

            @Nullable
            @Override
            public AlertDialog show(AlertDialog.Builder builder) {
                if (activity.isFinishing() || activity.isDestroyed()) return null;
                AlertDialog dialog = builder.show();
                colors.paint(dialog);
                return dialog;
            }

            @Nullable
            @Override
            public Activity activity() {
                return activity;
            }

            @Override
            public void opened() {
            }
        };
    }

    /**
     * Whether Facebook's Menu shows the Recently watched row: its switch on, with Resume long
     * videos in the build and not paused. The Menu row patch asks each time it builds the rows.
     */
    public static boolean menuRowWanted() {
        return Utils.settingsReady() && PatchFamily.RESUME_LONG_VIDEOS.inBuild()
                && Settings.RECENTLY_WATCHED_MENU_ROW.get();
    }

    /** Opens the list over [activity], the Menu's screen. False when it couldn't show. */
    public static boolean openOver(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        ScreenColors colors = ScreenColors.forScreen(activity);
        if (colors == null) colors = ScreenColors.black();
        Context themed = new ContextThemeWrapper(activity, colors.theme());
        return show(over(activity, colors), themed) != null;
    }

    static Preference row(HushfacebookPreferenceFragment page, Context context) {
        Row row = new Row(context);
        row.setKey(ROW);
        row.setTitle(L10n.t("Recently watched"));
        row.setSummary(L10n.t("Reels, videos and stories you watched, newest first. Tap one to open it again."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(p -> {
            show(over(page), context);
            return true;
        });
        return row;
    }

    /** The list, through [host], so it closes with what it shows over and takes its colours. */
    @Nullable
    static AlertDialog show(Host host, Context context) {
        ScreenColors palette = host.palette();
        Listing listing = new Listing(context, palette, RecentlyWatched.entries());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView chipRow = new HorizontalScrollView(context);
        chipRow.setHorizontalScrollBarEnabled(false);
        RadioGroup chips = new RadioGroup(context);
        chips.setOrientation(RadioGroup.HORIZONTAL);
        chips.setPaddingRelative(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 8));
        for (int index = 0; index < KINDS.length; index++) {
            RadioButton chip = chip(context, palette, chipLabel(KINDS[index]));
            chip.setId(View.generateViewId());
            String kind = KINDS[index];
            chip.setOnCheckedChangeListener((button, checked) -> {
                paintChip(button, palette, checked);
                if (checked) listing.only(kind);
            });
            RadioGroup.LayoutParams place = new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            place.setMarginEnd(dp(context, 8));
            chips.addView(chip, place);
            if (index == 0) chips.check(chip.getId());
        }
        chipRow.addView(chips);
        content.addView(chipRow);

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(listing);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView empty = new TextView(context);
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setPaddingRelative(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16));
        content.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.setEmptyView(empty);
        listing.emptyText = empty;
        listing.showEmptyText();

        // Under the list: what was just removed, and the button that puts it back.
        LinearLayout undoBar = new LinearLayout(context);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setPaddingRelative(dp(context, 24), dp(context, 4), dp(context, 12), dp(context, 4));
        undoBar.setVisibility(View.GONE);
        TextView removed = new TextView(context);
        removed.setTextColor(palette.title);
        removed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        removed.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        undoBar.addView(removed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button undo = new Button(context);
        undo.setText(L10n.t("Undo"));
        undo.setAllCaps(false);
        undo.setTextColor(palette.accent);
        undo.setMinHeight(dp(context, 48));
        undo.setMinWidth(dp(context, 64));
        ScreenColors.pressFeedback(undo, palette.accent, false);
        undoBar.addView(undo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(undoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listing.remover = entry -> {
            Entry gone = RecentlyWatched.remove(entry);
            listing.replace(RecentlyWatched.entries());
            if (gone == null) {
                undoBar.setVisibility(View.GONE);
                return;
            }
            removed.setText(L10n.f("Removed %1$s", title(gone)));
            undo.setOnClickListener(button -> {
                undoBar.setVisibility(View.GONE);
                if (!RecentlyWatched.restore(gone)) {
                    Utils.showToastLong(L10n.t("It wasn't put back. It was watched again since, or its time on the list is up."));
                }
                listing.replace(RecentlyWatched.entries());
            });
            undoBar.setVisibility(View.VISIBLE);
        };

        AlertDialog dialog = host.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Recently watched"))
                .setView(content)
                .setNeutralButton(L10n.t("Clear"), null)
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return null;
        list.setOnItemClickListener((parent, view, position, id) -> open(host, dialog, context, listing.getItem(position)));
        Button clear = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        listing.clearButton = clear;
        if (clear != null) {
            clear.setEnabled(!listing.all.isEmpty());
            clear.setOnClickListener(button -> {
                RecentlyWatched.clear();
                undoBar.setVisibility(View.GONE);
                listing.replace(Collections.emptyList());
                Utils.showToastShort(L10n.t("List cleared"));
            });
        }
        return dialog;
    }

    /**
     * Opens [entry] in Facebook and, once it's on its way, closes the list and the settings over
     * it, so what opens isn't hidden behind them. Says so when nothing opens it.
     */
    static void open(Host host, @Nullable AlertDialog list, Context context, Entry entry) {
        Activity activity = host.activity();
        Context from = activity != null && !activity.isFinishing() ? activity : context;
        String link = entry.link();
        if (link == null || !startView(from, link)) {
            String address = link == null ? "" : link;
            Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                    L10n.isolate(address)));
            return;
        }
        Logger.printDebug(() -> "Recently watched: opened a " + entry.kind);
        if (list != null) list.dismiss();
        host.opened();
    }

    /** The view intent [link] opens with: Facebook's own link handler, in this app. */
    static Intent viewIntent(Context from, String link) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(from.getPackageName(), URI_HANDLER);
        if (!(from instanceof Activity)) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return view;
    }

    /** Starts [link] in this app: its link handler first, then whatever in it takes the link. */
    private static boolean startView(Context from, String link) {
        try {
            from.startActivity(viewIntent(from, link));
            return true;
        } catch (ActivityNotFoundException | SecurityException noHandler) {
            Logger.printInfo(() -> "Recently watched: the link handler didn't take the link");
        }
        Intent anyScreen = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(from.getPackageName());
        if (!(from instanceof Activity)) anyScreen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            from.startActivity(anyScreen);
            return true;
        } catch (ActivityNotFoundException | SecurityException noScreen) {
            Logger.printInfo(() -> "Recently watched: nothing in Facebook took the link");
            return false;
        }
    }

    // ------------------------------------------------------------------ what a row says

    /** The chip for [kind], or for every kind when it's null. */
    static String chipLabel(@Nullable String kind) {
        if (kind == null) return L10n.t("All");
        switch (kind) {
            case RecentlyWatched.REEL:
                return L10n.t("Reels");
            case RecentlyWatched.VIDEO:
                return L10n.t("Videos");
            default:
                return L10n.t("Stories");
        }
    }

    /** One of [kind]. */
    static String kindLabel(String kind) {
        switch (kind) {
            case RecentlyWatched.REEL:
                return L10n.t("Reel");
            case RecentlyWatched.VIDEO:
                return L10n.t("Video");
            default:
                return L10n.t("Story");
        }
    }

    /** The row's first line: who posted it, or its kind when that isn't known. */
    static String title(Entry entry) {
        return entry.owner != null ? L10n.isolate(entry.owner) : kindLabel(entry.kind);
    }

    /** The row's second line: its kind when the title is its poster, its length, and when it was watched. */
    static String detail(Entry entry, long now) {
        List<String> parts = new ArrayList<>();
        if (entry.owner != null) parts.add(kindLabel(entry.kind));
        if (entry.durationMs > 0) parts.add(DateUtils.formatElapsedTime(Math.max(1, entry.durationMs / 1000)));
        // Under a minute, Android's own words would be "0 min. ago".
        if (now - entry.watchedAt < DateUtils.MINUTE_IN_MILLIS) {
            parts.add(L10n.t("Just now"));
        } else {
            parts.add(DateUtils.getRelativeTimeSpanString(entry.watchedAt, now, DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE).toString());
        }
        return TextUtils.join(DOT, parts);
    }

    /** The entries of [kind] in [entries], in their order, or all of them when it's null. */
    static List<Entry> only(List<Entry> entries, @Nullable String kind) {
        if (kind == null) return entries;
        List<Entry> shown = new ArrayList<>();
        for (Entry entry : entries) {
            if (kind.equals(entry.kind)) shown.add(entry);
        }
        return shown;
    }

    // ------------------------------------------------------------------ the list

    /** The rows under the chips. */
    static final class Listing extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        List<Entry> all;
        List<Entry> shown;
        @Nullable String kind;
        @Nullable TextView emptyText;
        @Nullable Button clearButton;
        /** What a row's remove button does with its entry. */
        @Nullable Consumer<Entry> remover;

        Listing(Context context, ScreenColors palette, List<Entry> entries) {
            this.context = context;
            this.palette = palette;
            this.all = entries;
            this.shown = entries;
        }

        /** Shows only [kind], or every kind when it's null. */
        void only(@Nullable String kind) {
            this.kind = kind;
            shown = RecentlyWatchedScreen.only(all, kind);
            showEmptyText();
            notifyDataSetChanged();
        }

        /** Shows [entries] in place of what was there, with the same chip picked. */
        void replace(List<Entry> entries) {
            all = entries;
            if (clearButton != null) clearButton.setEnabled(!entries.isEmpty());
            only(kind);
        }

        /** What the list says while it has no rows: nothing at all yet, or nothing of this kind. */
        String emptyMessage() {
            return all.isEmpty()
                    ? L10n.t("Nothing here yet. With Remember what you watch on, the reels, videos and stories you watch show up here.")
                    : L10n.t("Nothing of this kind yet.");
        }

        void showEmptyText() {
            if (emptyText != null) emptyText.setText(emptyMessage());
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Entry getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, @Nullable View reused, ViewGroup parent) {
            LinearLayout row = reused instanceof LinearLayout ? (LinearLayout) reused : newRow();
            Entry entry = getItem(position);
            LinearLayout text = (LinearLayout) row.getChildAt(0);
            TextView title = (TextView) text.getChildAt(0);
            TextView detail = (TextView) text.getChildAt(1);
            TextView caption = (TextView) text.getChildAt(2);
            title.setText(title(entry));
            detail.setText(detail(entry, System.currentTimeMillis()));
            caption.setText(entry.caption == null ? "" : entry.caption);
            caption.setVisibility(entry.caption == null ? View.GONE : View.VISIBLE);
            ImageButton remove = (ImageButton) row.getChildAt(1);
            remove.setContentDescription(L10n.f("Remove %1$s", title(entry)));
            remove.setOnClickListener(button -> {
                if (remover != null) remover.accept(entry);
            });
            return row;
        }

        /** A row: its title, detail and caption, then the button that takes it off the list. */
        private LinearLayout newRow() {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(context, 56));
            row.setPaddingRelative(dp(context, 24), dp(context, 10), dp(context, 8), dp(context, 10));
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton remove = new ImageButton(context);
            remove.setImageDrawable(SettingsIcons.icon(context, SettingsIcons.CLOSE, palette.summary));
            ScreenColors.pressFeedback(remove, palette.summary, true);
            remove.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
            // Not focusable, or the list stops handing taps on the row to its click listener.
            remove.setFocusable(false);
            row.addView(remove, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
            TextView title = new TextView(context);
            title.setTextColor(palette.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);
            TextView detail = new TextView(context);
            detail.setTextColor(palette.summary);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.addView(detail);
            TextView caption = new TextView(context);
            caption.setTextColor(palette.summary);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            caption.setMaxLines(2);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(caption);
            return row;
        }
    }

    private static RadioButton chip(Context context, ScreenColors palette, String label) {
        RadioButton chip = new RadioButton(context);
        chip.setButtonDrawable(null);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setMinHeight(dp(context, 48));
        chip.setPaddingRelative(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6));
        paintChip(chip, palette, false);
        return chip;
    }

    private static void paintChip(android.widget.CompoundButton chip, ScreenColors palette, boolean picked) {
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(dp(chip.getContext(), 8));
        surface.setColor(picked ? palette.accent : Color.TRANSPARENT);
        surface.setStroke(dp(chip.getContext(), 1), picked ? palette.accent : palette.outline);
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(
                surface, 0, dp(chip.getContext(), 6), 0, dp(chip.getContext(), 6));
        chip.setBackground(inset);
        // The inset's padding replaced the chip's own, so it goes back on.
        chip.setPaddingRelative(dp(chip.getContext(), 16), dp(chip.getContext(), 6),
                dp(chip.getContext(), 16), dp(chip.getContext(), 6));
        chip.setTextColor(picked ? palette.onAccent : palette.title);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
