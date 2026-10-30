/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.ads.MarketplaceSearchSyntax;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Adv. Filters button of Marketplace search: a chip right under Facebook's own Filters chip
 * and the save button beside it, as wide as the two together, that opens one dialog of three
 * lists: words a listing's title must have, words it mustn't, and places the listing must be in.
 * The lists are read out of the last search's rules ({@link MarketplaceSearchSyntax}), and the
 * dialog's Go writes them back into the search box as rules and searches that, as typing them
 * would. So the search box always shows every rule, and nothing outlasts the search.
 *
 * <p>Marketplace search is React Native, so nothing native marks its screen. The button goes up on
 * the screen in front when a Marketplace search request goes out ({@link #searchSeen}), on the tab
 * that was showing then, and that starts a search visit. It comes down while a listing's page is
 * open ({@link #listingSeen}) and comes back with Facebook's Filters chip. The visit ends when the
 * main screen shows another tab or when for a few seconds neither the Filters chip, the search box
 * nor the keyboard is on screen, which it checks every second.
 *
 * <p>Where it sits comes from the views React Native drew: the one labelled Filters, the clickable
 * view to the right of it on the same row, and the button sits a small gap under them, in the
 * colours read from Facebook's chip. It follows them before each frame is drawn, and hides while the Filters
 * chip is off screen. When no Filters chip can be found, in another language or a changed layout,
 * it sits at the bottom right instead.
 *
 * <p>Everything here runs on the main thread, and nothing throws into Facebook.
 */
public final class MarketplaceFilterButton {
    private static final String FAMILY = FamilyNames.SPONSORED_MARKETPLACE;
    private static final long CHECK_MS = 1000;
    /** How many checks in a row without the search on screen end the visit. */
    private static final int LEAVE_CHECKS = 4;
    private static final long RESCAN_MS = 300;
    /** How long the button waits for the Filters chip before it settles at the bottom right. */
    private static final long FALLBACK_MS = 6000;
    /** What Facebook's own chip says, which the button lines up under. */
    private static final String FILTERS = "Filters";
    /** The gap between Facebook's Filters row and the button, in dp. */
    private static final int GAP_DP = 10;

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The screen in front, as Hushfacebook's own watch last saw it resume. */
    private static WeakReference<Activity> resumed = new WeakReference<>(null);

    /** The screen the last search went out from, while the button belongs on it. */
    private static WeakReference<Activity> searchScreen = new WeakReference<>(null);

    /** The tab the main screen showed when that search went out, or null when it showed none. */
    @Nullable
    private static String searchTab;

    private static WeakReference<TextView> button = new WeakReference<>(null);

    /** Facebook's Filters chip and the save button beside it, as last found. */
    private static WeakReference<View> chip = new WeakReference<>(null);
    private static WeakReference<View> save = new WeakReference<>(null);
    private static long lastScan;
    private static long attachedAt;

    @Nullable
    private static ViewTreeObserver.OnPreDrawListener tracker;
    private static WeakReference<View> tracked = new WeakReference<>(null);

    private static final Runnable check = MarketplaceFilterButton::checkTab;

    /**
     * Places the button again while the Filters chip hasn't been found: a still screen draws no
     * frame, so waiting for one would leave the button unplaced until the results scroll.
     */
    private static final Runnable retry = () -> {
        try {
            View root = tracked.get();
            if (root instanceof ViewGroup) place((ViewGroup) root);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "filter button place", failure);
        }
    };

    /** Whether a search visit is on: from its first results until the search is left. */
    private static boolean visiting;
    /** Whether a listing's page was opened from the results, and whether the Filters chip has gone since. */
    private static boolean onListing;
    private static boolean chipGone;
    /** Checks in a row that found no search on screen. */
    private static int missing;

    /** How long the search key waits after Go puts the new text in the search field. */
    private static final long SUBMIT_DELAY_MS = 600;

    private MarketplaceFilterButton() {
    }

    /** A Marketplace search request is going out. Any thread. */
    public static void searchSeen() {
        main.post(() -> {
            try {
                Activity screen = resumed.get();
                if (screen == null) screen = Utils.getActivity();
                if (screen == null) return;
                if (!visiting || searchScreen.get() != screen) searchTab = tabName(screen);
                searchScreen = new WeakReference<>(screen);
                visiting = true;
                onListing = false;
                missing = 0;
                main.removeCallbacks(check);
                main.postDelayed(check, CHECK_MS);
                // The results may be a new screen over the old one, with a Filters chip of its own.
                chip = new WeakReference<>(null);
                apply();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "filter button", failure);
            }
        });
    }

    /**
     * Whether the keyboard is up on the screen in front, as it is while a search is being typed.
     * Facebook fetches results with each letter, so the button and its notes wait until it's down.
     * Any thread.
     */
    public static boolean typing() {
        Activity screen = resumed.get();
        if (screen == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = screen.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) return insets.isVisible(WindowInsets.Type.ime());
            }
            return screen.getCurrentFocus() instanceof EditText;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** A listing's own page is asking for its listing: the button comes down until the results are back. Any thread. */
    public static void listingSeen() {
        main.post(() -> {
            onListing = true;
            chipGone = false;
            apply();
        });
    }

    /** Ends the search visit: the button comes down. */
    private static void leave(String why) {
        Logger.printDebug(() -> "Marketplace filters: search left, " + why);
        visiting = false;
        onListing = false;
        missing = 0;
        main.removeCallbacks(check);
        searchScreen = new WeakReference<>(null);
        apply();
    }

    /** From Hushfacebook's activity watch, on the main thread. */
    public static void activityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        apply();
    }

    /** From Hushfacebook's activity watch, on the main thread. */
    public static void activityPaused(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
        apply();
    }

    /** Puts the button on the search screen while it's in front, and takes it off anywhere else. */
    private static void apply() {
        try {
            Activity target = searchScreen.get();
            if (target != null && (onListing || target != resumed.get() || target.isFinishing())) target = null;
            TextView current = button.get();
            if (current != null && (target == null || current.getContext() != target || current.getParent() == null)) {
                remove(current);
                current = null;
            }
            if (target == null) return;
            if (current != null) {
                label(current);
                return;
            }
            ViewGroup content = target.findViewById(android.R.id.content);
            if (content == null) return;
            TextView added = chipView(target);
            added.setVisibility(View.INVISIBLE);
            content.addView(added, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            button = new WeakReference<>(added);
            chip = new WeakReference<>(null);
            attachedAt = SystemClock.uptimeMillis();
            lastScan = 0;
            track(content);
            HookStatus.bound(FAMILY, "filter button");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "filter button", failure);
        }
    }

    private static void remove(View current) {
        ViewGroup parent = (ViewGroup) current.getParent();
        if (parent != null) parent.removeView(current);
        button = new WeakReference<>(null);
        View root = tracked.get();
        if (root != null && tracker != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnPreDrawListener(tracker);
        }
        tracker = null;
        tracked = new WeakReference<>(null);
        main.removeCallbacks(retry);
    }

    /** Lines the button up before each frame [content] draws. */
    private static void track(ViewGroup content) {
        tracker = () -> {
            try {
                place(content);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "filter button place", failure);
            }
            return true;
        };
        content.getViewTreeObserver().addOnPreDrawListener(tracker);
        tracked = new WeakReference<>(content);
    }

    /** Puts the button under Facebook's Filters row, as wide as the chip and the save button together. */
    private static void place(ViewGroup content) {
        TextView current = button.get();
        if (current == null) return;
        if (typing()) {
            if (current.getVisibility() != View.INVISIBLE) current.setVisibility(View.INVISIBLE);
            return;
        }
        View filters = chip.get();
        long now = SystemClock.uptimeMillis();
        if ((filters == null || !filters.isAttachedToWindow() || !onScreen(filters)) && now - lastScan >= RESCAN_MS) {
            lastScan = now;
            scan(content, current);
            filters = chip.get();
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) current.getLayoutParams();
        float density = content.getResources().getDisplayMetrics().density;
        if (filters == null || !filters.isAttachedToWindow()) {
            main.removeCallbacks(retry);
            main.postDelayed(retry, RESCAN_MS);
            if (now - attachedAt < FALLBACK_MS) return;
            // No Filters chip to line up with: the bottom right, as a pill.
            if (params.gravity != (Gravity.BOTTOM | Gravity.END)) {
                params.gravity = Gravity.BOTTOM | Gravity.END;
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.leftMargin = 0;
                params.topMargin = 0;
                params.rightMargin = Math.round(16 * density);
                params.bottomMargin = Math.round(96 * density);
                current.setLayoutParams(params);
            }
            if (current.getVisibility() != View.VISIBLE) current.setVisibility(View.VISIBLE);
            return;
        }
        if (!onScreen(filters)) {
            if (current.getVisibility() != View.INVISIBLE) current.setVisibility(View.INVISIBLE);
            return;
        }
        int[] base = new int[2];
        int[] at = new int[2];
        content.getLocationInWindow(base);
        filters.getLocationInWindow(at);
        // The button's margins count from inside the content view's padding, which on an
        // edge-to-edge screen holds the status bar.
        int left = at[0] - base[0] - content.getPaddingLeft();
        int top = at[1] - base[1] - content.getPaddingTop();
        int height = filters.getHeight();
        int right = left + filters.getWidth();
        View beside = save.get();
        if (beside != null && beside.isShown()) {
            beside.getLocationInWindow(at);
            right = Math.max(right, at[0] - base[0] - content.getPaddingLeft() + beside.getWidth());
        }
        int y = top + height + Math.round(GAP_DP * density);
        if (params.gravity != (Gravity.TOP | Gravity.START) || params.leftMargin != left || params.topMargin != y
                || params.width != right - left || params.height != height) {
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = left;
            params.topMargin = y;
            params.rightMargin = 0;
            params.bottomMargin = 0;
            params.width = right - left;
            params.height = height;
            current.setLayoutParams(params);
            ((GradientDrawable) current.getBackground()).setCornerRadius(height * 0.25f);
        }
        if (current.getVisibility() != View.VISIBLE) current.setVisibility(View.VISIBLE);
    }

    /** Finds Facebook's Filters chip and the save button on its row. */
    /** The view on screen under [content] labelled Filters, the topmost when there are more, or null. */
    @Nullable
    private static View filtersChip(ViewGroup content, @Nullable View own) {
        View label = null;
        int[] at = new int[2];
        int labelY = Integer.MAX_VALUE;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(content);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view == own || view.getVisibility() != View.VISIBLE) continue;
            if (isFilters(text(view)) && onScreen(view)) {
                view.getLocationInWindow(at);
                // The later of two at the same height is drawn over the other.
                if (at[1] <= labelY) {
                    labelY = at[1];
                    label = view;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return label;
    }

    private static void scan(ViewGroup content, View own) {
        View label = filtersChip(content, own);
        int[] at = new int[2];
        ArrayDeque<View> queue = new ArrayDeque<>();
        if (label == null) {
            chip = new WeakReference<>(null);
            return;
        }
        View found = label;
        for (int up = 0; up < 4 && !found.isClickable() && found.getParent() instanceof View && found.getParent() != content; up++) {
            found = (View) found.getParent();
        }
        chip = new WeakReference<>(found);
        TextView current = button.get();
        if (current != null) colour(current, found);

        found.getLocationInWindow(at);
        int chipTop = at[1];
        int chipBottom = chipTop + found.getHeight();
        int chipRight = at[0] + found.getWidth();
        View beside = null;
        int besideRight = chipRight;
        queue.add(content);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view == own || view.getVisibility() != View.VISIBLE) continue;
            if (view != found && onScreen(view)) {
                view.getLocationInWindow(at);
                int middle = at[1] + view.getHeight() / 2;
                int right = at[0] + view.getWidth();
                if (view.isClickable() && middle > chipTop && middle < chipBottom && at[0] >= chipRight - 2
                        && view.getWidth() < content.getWidth() / 2 && right > besideRight) {
                    besideRight = right;
                    beside = view;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        save = new WeakReference<>(beside);
    }

    /** Whether [view] is shown and some of it is on screen, not on a screen kept behind another. */
    private static boolean onScreen(View view) {
        return view.isShown() && view.getGlobalVisibleRect(new Rect());
    }

    /**
     * Takes the colours of Facebook's own chip, read from a point of its background, so the
     * button matches it in light and dark alike. Keeps its own when the chip can't be read.
     */
    private static void colour(TextView view, View facebook) {
        int width = facebook.getWidth();
        int height = facebook.getHeight();
        if (width <= 0 || height <= 0) return;
        try {
            Bitmap picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            facebook.draw(new Canvas(picture));
            int background = picture.getPixel(width / 2, Math.max(1, height / 10));
            picture.recycle();
            if (Color.alpha(background) < 200) return;
            double light = (0.299 * Color.red(background) + 0.587 * Color.green(background)
                    + 0.114 * Color.blue(background)) / 255;
            ((GradientDrawable) view.getBackground()).setColor(background);
            view.setTextColor(light > 0.5 ? 0xFF050505 : 0xFFE4E6EB);
        } catch (Throwable unreadable) {
            Logger.printDebug(() -> "Marketplace filter button: Filters chip colour unreadable");
        }
    }

    /** Whether [shown] is the label of Facebook's Filters chip, with or without a count after it. */
    private static boolean isFilters(@Nullable String shown) {
        if (shown == null) return false;
        String trimmed = shown.trim();
        return trimmed.length() <= FILTERS.length() + 8 && trimmed.regionMatches(true, 0, FILTERS, 0, FILTERS.length());
    }

    /**
     * What [view] says: a TextView's text, or else its content description, or else for a view
     * with nothing inside it what it tells accessibility, which is where React Native's own text
     * views, which aren't TextViews, keep their text.
     */
    @Nullable
    private static String text(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) return text.toString();
        }
        CharSequence description = view.getContentDescription();
        if (description != null && description.length() > 0) return description.toString();
        if (view instanceof TextView || (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0)) return null;
        AccessibilityNodeInfo info = null;
        try {
            info = view.createAccessibilityNodeInfo();
            CharSequence told = info.getText();
            if (told == null || told.length() == 0) told = info.getContentDescription();
            return told == null || told.length() == 0 ? null : told.toString();
        } catch (Throwable unreadable) {
            return null;
        } finally {
            if (info != null && Build.VERSION.SDK_INT < 33) info.recycle();
        }
    }

    /**
     * While a search visit is on: ends it when the main screen shows a tab other than the search's,
     * or when the search screen, in front, has shown neither the Filters chip, the search box nor
     * the keyboard for a few checks. Brings the button back when the results are back from a listing.
     */
    private static void checkTab() {
        try {
            if (!visiting) return;
            Activity screen = searchScreen.get();
            if (screen == null || screen.isFinishing()) {
                leave("search screen closed");
                return;
            }
            String tab = tabName(screen);
            if (searchTab != null && tab != null && !searchTab.equals(tab)) {
                leave("another tab");
                return;
            }
            ViewGroup content = screen == resumed.get() ? screen.findViewById(android.R.id.content) : null;
            if (content != null) {
                boolean filters = filtersChip(content, button.get()) != null;
                if (onListing) {
                    if (!filters) chipGone = true;
                    else if (chipGone) {
                        onListing = false;
                        apply();
                    }
                }
                boolean searching = onListing || filters || typing() || editText(content, true) != null
                        || showing(content, MarketplaceSearchSyntax.lastText()) != null;
                missing = searching ? 0 : missing + 1;
                if (missing >= LEAVE_CHECKS) {
                    leave("no search on screen");
                    return;
                }
            }
            main.postDelayed(check, CHECK_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "filter button", failure);
        }
    }

    /** The class of the tab [screen] shows, or null when it shows none or it can't be read. */
    @Nullable
    private static String tabName(Activity screen) {
        try {
            Object tab = StartTabRoute.TabBar.currentTab(screen);
            return tab == null ? null : tab.getClass().getName();
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** A chip in the colours of Facebook's own, grey with dark text, or the other way round at night. */
    private static TextView chipView(Activity screen) {
        TextView view = new TextView(screen);
        float density = screen.getResources().getDisplayMetrics().density;
        boolean night = night(screen);
        int h = Math.round(14 * density);
        int v = Math.round(9 * density);
        view.setPadding(h, v, h, v);
        view.setTextColor(night ? 0xFFE4E6EB : 0xFF050505);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        if (Build.VERSION.SDK_INT >= 26) {
            view.setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, TypedValue.COMPLEX_UNIT_SP);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(night ? 0xFF3A3B3C : 0xFFE4E6EB);
        background.setCornerRadius(10 * density);
        view.setBackground(background);
        view.setClickable(true);
        view.setOnClickListener(tapped -> open(screen));
        label(view);
        return view;
    }

    private static void label(TextView view) {
        int count = MarketplaceSearchSyntax.ruleCount(MarketplaceSearchSyntax.lastText());
        view.setText("\u2699  " + (count == 0 ? L10n.t("Adv. Filters") : L10n.f("Adv. Filters (%1$d)", count)));
        view.setContentDescription(count == 0 ? L10n.t("Advanced Marketplace filters")
                : L10n.f("Advanced Marketplace filters, %1$d on", count));
    }

    /**
     * The dialog of the three lists. Each shows its terms as tiles with an x that takes one out, and
     * a field with an Add button below for a new one: what's typed there is one term however many
     * words it has. Go adds what's still in a field, then searches.
     */
    static void open(Activity screen) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(screen, night(screen)
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            Context themed = builder.getContext();
            float density = themed.getResources().getDisplayMetrics().density;
            int pad = Math.round(20 * density);
            boolean night = night(screen);
            String last = MarketplaceSearchSyntax.lastText();

            LinearLayout box = new LinearLayout(themed);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, Math.round(8 * density), pad, 0);

            TextView intro = new TextView(themed);
            intro.setText(L10n.t("Type a word or a phrase and tap Add. A phrase like red wing has to be in the title "
                    + "as written. A term matches anywhere in a title, so 128 matches 128GB, and \"128 \" with a "
                    + "space in quotes matches it on its own. Go puts these into the search box and searches again.")
                    + "\n" + L10n.t("You can also type \"ram\", -dell or in:melbourne straight into the search box."));
            intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            intro.setAlpha(0.8f);
            box.addView(intro);

            Section include = new Section(themed, box, L10n.t("Title must include"),
                    MarketplaceSearchSyntax.rules(last, MarketplaceSearchSyntax.INCLUDE),
                    night ? 0xFF263951 : 0xFFE7F3FF, night ? 0xFFB8D6FF : 0xFF0A58CA);
            Section exclude = new Section(themed, box, L10n.t("Hide titles with"),
                    MarketplaceSearchSyntax.rules(last, MarketplaceSearchSyntax.EXCLUDE),
                    night ? 0xFF4A2626 : 0xFFFDECEC, night ? 0xFFFFB4B4 : 0xFFB3261E);
            Section places = new Section(themed, box, L10n.t("Only show listings in"),
                    MarketplaceSearchSyntax.rules(last, MarketplaceSearchSyntax.PLACES),
                    night ? 0xFF3A3B3C : 0xFFE4E6EB, night ? 0xFFE4E6EB : 0xFF050505);

            ScrollView scroll = new ScrollView(themed);
            scroll.addView(box);

            builder.setTitle(L10n.t("Advanced Marketplace filters"))
                    .setView(scroll)
                    .setPositiveButton(L10n.t("Go"), (dialog, which) -> {
                        include.addTyped();
                        exclude.addTyped();
                        places.addTyped();
                        go(screen, include.terms, exclude.terms, places.terms);
                    })
                    .setNeutralButton(L10n.t("Clear all"), (dialog, which) -> go(screen,
                            new ArrayList<>(), new ArrayList<>(), new ArrayList<>()))
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .show();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "filter dialog", failure);
            Logger.printException(() -> "Marketplace filters dialog didn't open", failure);
        }
    }

    /** Searches the last search's own words with the lists as its rules. */
    private static void go(Activity screen, List<String> include, List<String> exclude, List<String> places) {
        String text = MarketplaceSearchSyntax.compose(MarketplaceSearchSyntax.lastText(), include, exclude, places);
        main.postDelayed(() -> searchAgain(screen, text), 300);
    }

    /** One of the dialog's lists: its heading, its terms as tiles, and a field with an Add button. */
    private static final class Section {
        final List<String> terms = new ArrayList<>();
        private final Context themed;
        private final Tiles tiles;
        private final EditText field;
        private final int fill;
        private final int ink;

        Section(Context themed, LinearLayout box, String title, List<String> start, int fill, int ink) {
            this.themed = themed;
            this.fill = fill;
            this.ink = ink;
            float density = themed.getResources().getDisplayMetrics().density;
            TextView label = new TextView(themed);
            label.setText(title);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setPadding(0, Math.round(16 * density), 0, Math.round(6 * density));
            box.addView(label);

            tiles = new Tiles(themed, Math.round(6 * density));
            box.addView(tiles, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(themed);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            field = new EditText(themed);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            field.setOnEditorActionListener((view, action, event) -> {
                addTyped();
                return true;
            });
            row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button add = new Button(themed);
            add.setText(L10n.t("Add"));
            add.setOnClickListener(tapped -> addTyped());
            row.addView(add, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(row);

            for (String term : start) add(term);
        }

        /** Adds what's typed in the field as terms, a comma between two, and empties the field. */
        void addTyped() {
            for (String term : MarketplaceSearchSyntax.terms(field.getText().toString())) add(term);
            field.setText("");
        }

        private void add(String term) {
            if (terms.contains(term)) return;
            terms.add(term);
            float density = themed.getResources().getDisplayMetrics().density;
            String shown = MarketplaceSearchSyntax.shown(term);
            TextView tile = new TextView(themed);
            tile.setText(shown + "  \u2715");
            tile.setTextColor(ink);
            tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tile.setSingleLine(true);
            int h = Math.round(12 * density);
            int v = Math.round(6 * density);
            tile.setPadding(h, v, h, v);
            GradientDrawable background = new GradientDrawable();
            background.setColor(fill);
            background.setCornerRadius(16 * density);
            tile.setBackground(background);
            tile.setContentDescription(L10n.f("Remove %1$s", shown));
            tile.setOnClickListener(tapped -> {
                terms.remove(term);
                tiles.removeView(tile);
            });
            tiles.addView(tile);
        }
    }

    /** Lays its children out in rows, left to right, starting a new row when one is full. */
    private static final class Tiles extends ViewGroup {
        private final int gap;

        Tiles(Context context, int gap) {
            super(context);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                    ? Integer.MAX_VALUE : MeasureSpec.getSize(widthSpec);
            int x = 0;
            int y = 0;
            int row = 0;
            int widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    x = 0;
                    y += row + gap;
                    row = 0;
                }
                x += child.getMeasuredWidth() + gap;
                widest = Math.max(widest, x - gap);
                row = Math.max(row, child.getMeasuredHeight());
            }
            int height = row == 0 ? 0 : y + row + gap;
            setMeasuredDimension(resolveSize(widest, widthSpec), resolveSize(height, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int x = 0;
            int y = 0;
            int row = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                if (x > 0 && x + w > width) {
                    x = 0;
                    y += row + gap;
                    row = 0;
                }
                child.layout(x, y, x + w, y + h);
                x += w + gap;
                row = Math.max(row, h);
            }
        }
    }

    /**
     * Searches [text]: taps the search box showing the last search, which opens Facebook's search
     * field, and once that has the keyboard's focus puts [text] in it and submits it. The tap is a
     * touch, not a click, since React Native's views answer touches only. A field that had the
     * focus before is let go first, so a field kept behind the results isn't the one used. Says
     * so when no search box can be found.
     */
    static void searchAgain(Activity screen, String text) {
        try {
            ViewGroup content = screen.findViewById(android.R.id.content);
            if (content == null) return;
            View focused = screen.getCurrentFocus();
            if (focused != null) focused.clearFocus();
            View box = showing(content, MarketplaceSearchSyntax.lastText());
            if (box == null) box = editText(content, false);
            if (box != null) {
                note("tapping the search box");
                touch(screen, box);
                waitForField(screen, text, 0);
                return;
            }
            note("no search box found");
            Utils.showToastShort(L10n.t("Search box not found. Type the search again."));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "filter search again", failure);
        }
    }

    /** Touches the middle of [view] as a finger would: down, then up a moment later. */
    private static void touch(Activity screen, View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        float x = at[0] + view.getWidth() / 2f;
        float y = at[1] + view.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        screen.dispatchTouchEvent(press);
        press.recycle();
        main.postDelayed(() -> {
            MotionEvent lift = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            screen.dispatchTouchEvent(lift);
            lift.recycle();
        }, 60);
    }

    /** Checks for Facebook's search field every 150 ms for 4.5 seconds, and searches [text] once it has the focus. */
    private static void waitForField(Activity screen, String text, int tries) {
        main.postDelayed(() -> {
            try {
                Activity front = resumed.get() != null ? resumed.get() : screen;
                ViewGroup content = front.findViewById(android.R.id.content);
                // Only the field the tap opened has the keyboard's focus.
                EditText edit = content == null ? null : editText(content, true);
                if (edit != null) {
                    // Give the field a moment to take the text the search screen hands it.
                    main.postDelayed(() -> submit(edit, text), 250);
                } else if (tries < 30) {
                    waitForField(screen, text, tries + 1);
                } else {
                    note("the search field never opened");
                    Utils.showToastShort(L10n.t("The search box didn't open. Type the search again."));
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "filter search again", failure);
            }
        }, 150);
    }

    /**
     * Types [text] into [edit] the way the keyboard does, through an input connection, and then
     * presses its search key the same way. Facebook's search field keeps its own copy of the text
     * and puts it back over a text set any other way. The search key waits a moment after the new
     * text, so Facebook's copy has it by then.
     */
    private static void submit(EditText edit, String text) {
        edit.requestFocus();
        InputConnection typing = edit.onCreateInputConnection(new EditorInfo());
        if (typing != null) {
            typing.beginBatchEdit();
            typing.setSelection(0, edit.length());
            typing.commitText(text, 1);
            typing.endBatchEdit();
        } else {
            edit.setText(text);
            edit.setSelection(text.length());
        }
        main.postDelayed(() -> {
            try {
                String shown = edit.getText() == null ? "" : edit.getText().toString();
                if (!shown.equals(text)) note("the field went back to " + shown);
                if (typing == null || !typing.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) {
                    edit.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
                }
                note("searched " + text);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "filter search again", failure);
            }
        }, SUBMIT_DELAY_MS);
    }

    /** Logs what Go did, for Debug logging. */
    private static void note(String what) {
        Logger.printDebug(() -> "Marketplace filters Go: " + what);
    }

    /** The topmost text field on screen under [root], or the focused one only when [focused], or null. */
    @Nullable
    private static EditText editText(ViewGroup root, boolean focused) {
        EditText best = null;
        int bestY = Integer.MAX_VALUE;
        int[] at = new int[2];
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view instanceof EditText && onScreen(view) && (!focused || view.isFocused())) {
                view.getLocationInWindow(at);
                if (at[1] < bestY) {
                    bestY = at[1];
                    best = (EditText) view;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return best;
    }

    /** The view shown under [root] that says [typed], or null. */
    @Nullable
    private static View showing(ViewGroup root, @Nullable String typed) {
        if (typed == null || typed.trim().isEmpty()) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            String shown = text(view);
            if (shown != null && shown.trim().equals(typed.trim()) && onScreen(view)) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }
}
