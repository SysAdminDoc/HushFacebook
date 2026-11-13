/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.showAllText;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.download.ReelQueue;
import app.morphe.extension.facebook.download.SaveControl;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.preference.ClearLogBufferPreference;
import app.morphe.extension.shared.settings.preference.ExportDiagnosticReportPreference;
import app.morphe.extension.shared.settings.preference.ImmediateAction;

/**
 * The kinds of row the settings page is built from: section titles, rows of text, switches, the
 * running saves and the rows that act on a tap, each known by this class's name, as
 * {@code SettingsRows.Row}. The builders that put the text on a row are the page's:
 * {@link HushfacebookPreferenceFragment#toggle} and the ones beside it.
 */
@SuppressWarnings("deprecation")
final class SettingsRows {
    private SettingsRows() { }

    /** A section title, which a screen reader announces as a heading so a reader can jump between sections. */
    static final class Heading extends PreferenceCategory {
        Heading(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            showAllText(view);
            ScreenColors.heading(view);
        }
    }

    /**
     * A small heading inside a section, above the controls it names, as "Video quality" sits over
     * the quality rows. It saves nothing and answers no tap. A screen reader announces it as a
     * heading, and search reads it as part of each row under it without listing it alone.
     */
    static final class Subheading extends Preference {
        Subheading(Context context) {
            super(context);
            setSelectable(false);
            setPersistent(false);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            showAllText(view);
            ScreenColors.heading(view);
        }
    }

    private static final String ALIASES = "search_aliases";

    /** Earlier names search still finds [row] by, after it was renamed or took over other rows. */
    static void alias(Preference row, CharSequence... names) {
        row.getExtras().putCharSequenceArray(ALIASES, names);
    }

    /** The earlier names [row] answers to in search, or none. */
    static CharSequence[] aliases(Preference row) {
        Bundle extras = row.peekExtras();
        CharSequence[] names = extras == null ? null : extras.getCharSequenceArray(ALIASES);
        return names == null ? new CharSequence[0] : names;
    }

    /** A row of text, which a screen reader calls a button when a tap does something. */
    static final class Row extends Preference implements ImmediateAction {
        /** Set on a row whose tap does what it says at once, which then goes without a chevron. */
        boolean actsAtOnce;

        Row(Context context) {
            super(context);
        }

        @Override
        public boolean actsOnTap() {
            return actsAtOnce;
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(isSelectable() ? new RowSemantics(this, Button.class) : null);
            // A row that only shows something, such as the feed's hidden count, is one stop for a
            // screen reader: TalkBack read its title and its summary as two.
            view.setScreenReaderFocusable(!isSelectable());
        }
    }

    /**
     * A running save: what it is, what it's doing, how far it has got, and a Cancel button. Its text
     * changes in place: a row rebuilt under a finger loses the tap on its button.
     */
    static final class SaveRow extends Preference {
        /** Before every setting of the section, oldest save first. */
        private static final int FIRST = Integer.MIN_VALUE / 2;
        /**
         * How often the amount and time move with a screen reader on. TalkBack reads a focused row
         * again at every change, which at the once-a-second tick never let it stop talking.
         */
        static final long READ_ALOUD_EVERY_MILLIS = 10_000L;

        final int id;
        private final boolean video;
        private String status;
        private long shownAt;
        private boolean canCancel;
        private Button cancelButton;
        @Nullable
        private View bound;

        SaveRow(Context context, SaveControl.Running save) {
            super(context);
            id = save.id;
            video = save.video;
            status = SaveControl.status(save);
            shownAt = SystemClock.uptimeMillis();
            canCancel = save.canCancel;
            setKey("running_save_" + save.id);
            setPersistent(false);
            setSelectable(false);
            setOrder(FIRST + save.id);
            setTitle(video ? L10n.t("Saving a video") : L10n.t("Saving a photo"));
        }

        @Override
        public CharSequence getSummary() {
            return status;
        }

        void show(SaveControl.Running save) {
            String next = SaveControl.status(save);
            canCancel = save.canCancel;
            if (cancelButton != null) {
                cancelButton.setEnabled(canCancel);
                cancelButton.setAlpha(canCancel ? 1f : .38f);
            }
            if (next.equals(status)) return;
            long now = SystemClock.uptimeMillis();
            // A new phase shows at once; with a screen reader on, the amount and time wait their turn.
            if (samePhase(next, status) && now - shownAt < READ_ALOUD_EVERY_MILLIS && readAloud()) return;
            status = next;
            shownAt = now;
            TextView summary = bound == null ? null : bound.findViewById(android.R.id.summary);
            if (summary != null) summary.setText(next);
        }

        private boolean readAloud() {
            AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
            return manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled();
        }

        /** The status's first line is what the save is doing. */
        private static boolean samePhase(String a, String b) {
            int end = a.indexOf('\n');
            return end > 0 && b.length() > end && b.charAt(end) == '\n' && a.regionMatches(0, b, 0, end);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            bound = view;
            showAllText(view);
            ScreenColors.row(view, this);
            // What it is and how far it has got are one stop; Cancel keeps its own.
            view.setScreenReaderFocusable(true);
            android.view.ViewGroup frame = view.findViewById(android.R.id.widget_frame);
            if (frame == null) return;
            frame.removeAllViews();
            Button cancel = new Button(getContext());
            cancelButton = cancel;
            cancel.setEnabled(canCancel);
            cancel.setAlpha(canCancel ? 1f : .38f);
            cancel.setText(L10n.t("Cancel"));
            // Two saves can be listed at once, so the button says whose it is.
            cancel.setContentDescription(video ? L10n.t("Cancel saving this video") : L10n.t("Cancel saving this photo"));
            cancel.setAllCaps(false);
            cancel.setTextSize(14);
            ScreenColors colors = ScreenColors.current();
            cancel.setTextColor(colors.heading);
            ScreenColors.pressFeedback(cancel, colors.heading, false);
            int touch = Math.round(48 * view.getResources().getDisplayMetrics().density);
            cancel.setMinWidth(touch);
            cancel.setMinimumWidth(touch);
            cancel.setMinHeight(touch);
            cancel.setMinimumHeight(touch);
            cancel.setPadding(touch / 4, 0, touch / 4, 0);
            cancel.setOnClickListener(ignored -> {
                cancel.setEnabled(false);
                SaveControl.cancel(id);
            });
            frame.addView(cancel, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            frame.setVisibility(View.VISIBLE);
        }
    }

    /**
     * The reel queue (#449): what it's doing, its counts, and Pause or Resume with Cancel. Above the
     * reels in it (#462) and the running saves, one of which is its reel. Its text and buttons change
     * in place, as a save's do.
     */
    static final class QueueRow extends Preference {
        static final String KEY = ReelQueue.ROW_KEY;
        /** Room under it for every reel the queue holds at once, and the one saving, before the saves. */
        static final int ORDER = SaveRow.FIRST - ReelQueue.MOST_WAITING - 2;

        private ReelQueue.Status status;
        private Button toggleButton;
        @Nullable
        private View bound;

        QueueRow(Context context, ReelQueue.Status status) {
            super(context);
            this.status = status;
            setKey(KEY);
            setPersistent(false);
            setSelectable(false);
            setOrder(ORDER);
            setTitle(ReelQueue.title(status));
        }

        @Override
        public CharSequence getSummary() {
            return ReelQueue.counts(status);
        }

        void show(ReelQueue.Status now) {
            status = now;
            setTitle(ReelQueue.title(now));
            TextView summary = bound == null ? null : bound.findViewById(android.R.id.summary);
            if (summary != null) summary.setText(ReelQueue.counts(now));
            if (toggleButton != null) toggleButton.setText(now.paused ? L10n.t("Resume") : L10n.t("Pause"));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            bound = view;
            showAllText(view);
            ScreenColors.row(view, this);
            view.setScreenReaderFocusable(true);
            android.view.ViewGroup frame = view.findViewById(android.R.id.widget_frame);
            if (frame == null) return;
            frame.removeAllViews();
            toggleButton = queueButton(view, status.paused ? L10n.t("Resume") : L10n.t("Pause"), null);
            toggleButton.setOnClickListener(ignored -> {
                if (status.paused) ReelQueue.resume();
                else ReelQueue.pause();
            });
            Button cancel = queueButton(view, L10n.t("Cancel"), L10n.t("Cancel the reel queue"));
            cancel.setOnClickListener(ignored -> {
                cancel.setEnabled(false);
                ReelQueue.cancel();
            });
            twoButtons(frame, toggleButton, cancel);
        }
    }

    /**
     * One reel in the reel queue (#462): who posted it, whether it's saving, waiting or paused, and its
     * own Pause or Resume and Cancel. Under the queue's row, in the order the reels go. Its text and
     * buttons change in place, as the queue's do.
     */
    static final class QueuedReelRow extends Preference {
        final int number;
        private ReelQueue.Item item;
        @Nullable
        private Button toggleButton;
        @Nullable
        private View bound;

        QueuedReelRow(Context context, ReelQueue.Item item, int place) {
            super(context);
            number = item.number;
            this.item = item;
            setKey("queued_reel_" + item.number);
            setPersistent(false);
            setSelectable(false);
            setOrder(QueueRow.ORDER + 1 + place);
            setTitle(ReelQueue.name(item));
        }

        @Override
        public CharSequence getSummary() {
            return ReelQueue.state(item);
        }

        /** [now] is this reel as the queue has it, [place] its place in line, the one saving first. */
        void show(ReelQueue.Item now, int place) {
            item = now;
            setTitle(ReelQueue.name(now));
            if (getOrder() != QueueRow.ORDER + 1 + place) setOrder(QueueRow.ORDER + 1 + place);
            TextView summary = bound == null ? null : bound.findViewById(android.R.id.summary);
            if (summary != null) summary.setText(ReelQueue.state(now));
            if (toggleButton != null) label(toggleButton);
        }

        private void label(Button toggle) {
            boolean paused = item.state == ReelQueue.Item.State.PAUSED;
            toggle.setText(paused ? L10n.t("Resume") : L10n.t("Pause"));
            toggle.setContentDescription(paused ? L10n.t("Resume this reel") : L10n.t("Pause this reel"));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            bound = view;
            showAllText(view);
            ScreenColors.row(view, this);
            view.setScreenReaderFocusable(true);
            android.view.ViewGroup frame = view.findViewById(android.R.id.widget_frame);
            if (frame == null) return;
            frame.removeAllViews();
            Button toggle = queueButton(view, L10n.t("Pause"), null);
            toggleButton = toggle;
            label(toggle);
            toggle.setOnClickListener(ignored -> {
                if (item.state == ReelQueue.Item.State.PAUSED) ReelQueue.resume(number);
                else ReelQueue.pause(number);
            });
            Button cancel = queueButton(view, L10n.t("Cancel"), L10n.t("Cancel this reel"));
            cancel.setOnClickListener(ignored -> {
                cancel.setEnabled(false);
                ReelQueue.cancel(number);
            });
            twoButtons(frame, toggle, cancel);
        }
    }

    /** A button styled as a running save's Cancel is, at least 48 dp each way. */
    private static Button queueButton(View row, String text, @Nullable String description) {
        Button button = new Button(row.getContext());
        button.setText(text);
        if (description != null) button.setContentDescription(description);
        button.setAllCaps(false);
        button.setTextSize(14);
        ScreenColors colors = ScreenColors.current();
        button.setTextColor(colors.heading);
        ScreenColors.pressFeedback(button, colors.heading, false);
        int touch = Math.round(48 * row.getResources().getDisplayMetrics().density);
        button.setMinWidth(touch);
        button.setMinimumWidth(touch);
        button.setMinHeight(touch);
        button.setMinimumHeight(touch);
        button.setPadding(touch / 4, 0, touch / 4, 0);
        return button;
    }

    /** Puts [first] and [second] side by side in a row's widget [frame]. */
    private static void twoButtons(android.view.ViewGroup frame, Button first, Button second) {
        // A preference's widget frame stacks what it holds, which put Cancel under Pause and
        // squeezed both below a finger's size.
        if (frame instanceof android.widget.LinearLayout) {
            ((android.widget.LinearLayout) frame).setOrientation(android.widget.LinearLayout.HORIZONTAL);
        }
        // The frame fills the row's height, and a row leaves such a part out when it sizes itself,
        // so the buttons got what the text and the card's margins left, under a finger's size.
        // Sized to its buttons, the frame makes the row grow to fit them.
        android.view.ViewGroup.LayoutParams place = frame.getLayoutParams();
        if (place != null && place.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
            place.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            frame.setLayoutParams(place);
        }
        android.widget.LinearLayout.LayoutParams wrap = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        frame.addView(first, wrap);
        frame.addView(second, new android.widget.LinearLayout.LayoutParams(wrap));
        frame.setVisibility(View.VISIBLE);
    }

    /** A switch row, which a screen reader calls a switch and reads as on or off. */
    static final class Toggle extends SwitchPreference {
        /** The switch this one works under, if any, and the summary it shows while that switch is off. */
        @Nullable private BooleanSetting needs;
        @Nullable private CharSequence plain;

        Toggle(Context context) {
            super(context);
        }

        /**
         * It's greyed out while [parent] is off, as the shared page works out from the setting, and its
         * summary then names the switch to turn on. Search lists this same row, so it says so there too.
         */
        Toggle needs(BooleanSetting parent) {
            needs = parent;
            plain = getSummary();
            showSummary();
            return this;
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            showSummary();
        }

        private void showSummary() {
            if (needs == null) return;
            String note = L10n.f("Turn on %1$s to use this.", L10n.isolate(SwitchLabels.title(needs)));
            setSummary(isEnabled() ? plain : plain + " " + note);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Switch.class));
        }
    }

    static final class ExportRow extends ExportDiagnosticReportPreference {
        ExportRow(Context context) {
            super(context);
        }

        /** The two choices as cards, each saying what it does under its name. */
        @Override
        protected ListAdapter choices(Context dialogContext) {
            ScreenColors colors = ScreenColors.current();
            return new ChoiceCards(colors,
                    new CharSequence[]{L10n.t(getContext(), "Copy quick report"),
                            L10n.t(getContext(), "Save full report")},
                    new CharSequence[]{L10n.t(getContext(), "Copy a short report to the clipboard."),
                            L10n.t(getContext(), "Save the full report in Download/Hushfacebook.")});
        }

        /**
         * The screen's colours, before the dialog is shown so its first layout measures them. The
         * cards bring their own spacing and press ripple, so the list draws no divider and no
         * highlight of its own over them.
         */
        @Override
        protected void onDialogCreated(AlertDialog dialog) {
            ListView list = dialog.getListView();
            if (list != null) {
                list.setDivider(null);
                list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            }
            ScreenColors.dialog(dialog);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    static final class ClearRow extends ClearLogBufferPreference {
        ClearRow(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /** Font file, which opens the picker, or Use your phone's font, which acts at once. */
    static final class FontRow extends FontFilePreference {
        FontRow(HushfacebookPreferenceFragment page, Context context, int role) {
            super(page, context, role);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /** Home header picture, which opens the picker, or Remove the header picture, which acts at once. */
    static final class PictureRow extends HeaderPicturePreference {
        PictureRow(HushfacebookPreferenceFragment page, Context context, int role) {
            super(page, context, role);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /** Export settings or Import settings. */
    static final class BackupRow extends SettingsBackupPreference {
        BackupRow(HushfacebookPreferenceFragment page, Context context, int action, String title, String summary) {
            super(page, context, action, title, summary);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /**
     * What a screen reader hears about a row a tap acts on. The list itself gives such a row its
     * place and a click action and no role, and the switch drawn in a switch row can't take
     * focus, so on its own a switch row said neither that it was a switch nor whether it was on.
     * A double tap goes through the list, the way a tap does, so the preference's own click runs.
     */
    static class RowSemantics extends View.AccessibilityDelegate {
        private final Preference preference;
        private final Class<? extends View> role;

        RowSemantics(Preference preference, Class<? extends View> role) {
            this.preference = preference;
            this.role = role;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            AbsListView list = listOf(host);
            int position = list == null ? AdapterView.INVALID_POSITION : list.getPositionForView(host);
            if (position != AdapterView.INVALID_POSITION) {
                list.onInitializeAccessibilityNodeInfoForItem(host, position, info);
            }
            info.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                info.setCheckable(true);
                info.setChecked(((TwoStatePreference) preference).isChecked());
            }
            if (preference.isEnabled() && !info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            // The click event is what a screen reader answers a double tap with, so it carries the
            // state the tap left.
            event.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                event.setChecked(((TwoStatePreference) preference).isChecked());
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            AbsListView list = listOf(host);
            if (action == AccessibilityNodeInfo.ACTION_CLICK && list != null && preference.isEnabled()) {
                int position = list.getPositionForView(host);
                if (position != AdapterView.INVALID_POSITION) {
                    return list.performItemClick(host, position, list.getItemIdAtPosition(position));
                }
            }
            return super.performAccessibilityAction(host, action, arguments);
        }

        @Nullable
        private static AbsListView listOf(View row) {
            return row.getParent() instanceof AbsListView ? (AbsListView) row.getParent() : null;
        }
    }
}
