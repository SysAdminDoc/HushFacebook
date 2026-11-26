/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.preference.Preference;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import app.morphe.extension.facebook.profile.FriendList;
import app.morphe.extension.facebook.profile.FriendList.Gone;
import app.morphe.extension.facebook.settings.RecentlyWatchedScreen.Host;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The Friends no longer on your list row under the friends list switch and the list it opens (#597):
 * the people {@link FriendList} found missing from your friends list, the latest found first, each
 * with the two reads they dropped off between. A tap opens their profile through Facebook's own link
 * handler and closes the settings. Each row's remove button takes that one off, and Clear empties the
 * list. The kept copy of your friends list stays, so the next read still has something to compare with.
 */
@SuppressWarnings("deprecation") // Framework preferences, as the rest of the screen.
final class FriendsGoneScreen {
    /** The row's key, for the settings search. No setting is behind it. */
    static final String ROW = "action_friends_gone";

    /** The link a friend's profile opens with, by their id. */
    static final String PROFILE_LINK = "fb://profile/";

    private FriendsGoneScreen() {
    }

    static Preference row(HushfacebookPreferenceFragment page, Context context) {
        Row row = new Row(context);
        row.setKey(ROW);
        row.setTitle(L10n.t("Friends no longer on your list"));
        row.setSummary(L10n.t("People who were on your friends list one time you scrolled it to the end and weren't the next. "
                + "Tap one to open their profile."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(p -> {
            show(RecentlyWatchedScreen.over(page), context);
            return true;
        });
        return row;
    }

    /** The list, through [host], so it closes with what it shows over and takes its colours. */
    @Nullable
    static AlertDialog show(Host host, Context context) {
        ScreenColors palette = host.palette();
        Listing listing = new Listing(context, palette, FriendList.gone());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView kept = new TextView(context);
        kept.setTextColor(palette.summary);
        kept.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        kept.setPaddingRelative(dp(context, 24), dp(context, 4), dp(context, 24), dp(context, 8));
        kept.setText(keptLine(context, FriendList.keptCount(), FriendList.keptAt()));
        content.addView(kept, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(listing);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView empty = new TextView(context);
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setPaddingRelative(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16));
        empty.setText(emptyMessage());
        content.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.setEmptyView(empty);
        listing.remover = entry -> {
            FriendList.remove(entry);
            listing.replace(FriendList.gone());
            Utils.showToastShort(L10n.f("Removed %1$s", L10n.isolate(name(entry))));
        };

        AlertDialog dialog = host.show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Friends no longer on your list"))
                .setView(content)
                .setNeutralButton(L10n.t("Clear"), null)
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return null;
        list.setOnItemClickListener((parent, view, position, id) -> open(host, dialog, context, listing.getItem(position)));
        Button clear = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        listing.clearButton = clear;
        if (clear != null) {
            clear.setEnabled(!listing.entries.isEmpty());
            clear.setOnClickListener(button -> {
                FriendList.clear();
                listing.replace(Collections.emptyList());
                Utils.showToastShort(L10n.t("List cleared"));
            });
        }
        return dialog;
    }

    /** Opens [entry]'s profile in Facebook, then closes the list and the settings over it. */
    static void open(Host host, @Nullable AlertDialog list, Context context, Gone entry) {
        Activity activity = host.activity();
        Context from = activity != null && !activity.isFinishing() ? activity : context;
        try {
            from.startActivity(RecentlyWatchedScreen.viewIntent(from, link(entry)));
        } catch (ActivityNotFoundException | SecurityException noHandler) {
            Logger.printInfo(() -> "Friends no longer on your list: the link handler didn't take the profile");
            Utils.showToastLong(L10n.t("Their profile can't open here."));
            return;
        }
        if (list != null) list.dismiss();
        host.opened();
    }

    /** The link [entry]'s profile opens with. */
    static String link(Gone entry) {
        return PROFILE_LINK + entry.id;
    }

    /** The row's first line: their name, or a stand-in when none was read. */
    static String name(Gone entry) {
        return entry.name.isEmpty() ? L10n.t("Someone without a name") : entry.name;
    }

    /** The row's second line: the two reads they dropped off between. */
    static String detail(Context context, Gone entry) {
        return L10n.f("Gone between %1$s and %2$s", date(context, entry.lastSeenAt), date(context, entry.noticedAt));
    }

    /** The line over the list: how many friends the kept copy has and when it was read, or that there's none yet. */
    static String keptLine(Context context, int count, long at) {
        if (at <= 0) return L10n.t("No copy of your friends list yet.");
        return L10n.f("Your list was last read to its end on %1$s, with %2$d friends.", date(context, at), count);
    }

    static String emptyMessage() {
        return L10n.t("Nobody yet. With Keep track of your friends list on, scroll Profile > Friends > All friends to its end "
                + "now and then. Anyone who drops off between two of those reads shows up here.");
    }

    private static String date(Context context, long at) {
        return DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
    }

    // ------------------------------------------------------------------ the list

    static final class Listing extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        List<Gone> entries;
        @Nullable Button clearButton;
        /** What a row's remove button does with its entry. */
        @Nullable Consumer<Gone> remover;

        Listing(Context context, ScreenColors palette, List<Gone> entries) {
            this.context = context;
            this.palette = palette;
            this.entries = entries;
        }

        void replace(List<Gone> entries) {
            this.entries = entries;
            if (clearButton != null) clearButton.setEnabled(!entries.isEmpty());
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Gone getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, @Nullable View reused, ViewGroup parent) {
            LinearLayout row = reused instanceof LinearLayout ? (LinearLayout) reused : newRow();
            Gone entry = getItem(position);
            LinearLayout text = (LinearLayout) row.getChildAt(0);
            String name = L10n.isolate(name(entry));
            ((TextView) text.getChildAt(0)).setText(name);
            ((TextView) text.getChildAt(1)).setText(detail(context, entry));
            ImageButton remove = (ImageButton) row.getChildAt(1);
            remove.setContentDescription(L10n.f("Remove %1$s", name));
            remove.setOnClickListener(button -> {
                if (remover != null) remover.accept(entry);
            });
            return row;
        }

        /** A row: the name and the dates, then the button that takes it off the list. */
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
            return row;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
