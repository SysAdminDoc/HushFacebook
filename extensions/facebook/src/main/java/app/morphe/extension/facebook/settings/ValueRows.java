/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.commentOrderSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.downloadActionSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.feedsSubtabSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.fileNameSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.fitAboveKeyboard;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.folderSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.lockAfterSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.photoNameSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.seenKeepSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.playbackQualitySummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.qualitySummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.accentSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.textSizeSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.quietHourLabel;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.saveToSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.sendAppSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.showAllText;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.sourcesSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.startTabSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.subfolderSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.surfaceQualitySummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.ceilingSummary;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.packResult;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.wordsEditorLine;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.wordsRefusal;
import static app.morphe.extension.facebook.settings.HushfacebookPreferenceFragment.wordsSummary;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.FileNameTemplate;
import app.morphe.extension.facebook.download.PostDetails;
import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.feed.ReactionCeiling;
import app.morphe.extension.facebook.feed.TopicPacks;
import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.facebook.notifications.QuietHour;
import app.morphe.extension.facebook.settings.SettingsRows.RowSemantics;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;

/**
 * The rows that edit a value: the text rows, whose dialogs fit above the keyboard, and the lists,
 * each known by this class's name, as {@code ValueRows.FolderRow}. Each row's builder and summary
 * are the page's, such as {@link HushfacebookPreferenceFragment#qualityRow} and
 * {@link HushfacebookPreferenceFragment#qualitySummary}.
 */
@SuppressWarnings("deprecation")
final class ValueRows {
    private ValueRows() { }

    /**
     * A list row whose summary is shown as written. Android's ListPreference runs its summary
     * through String.format with the chosen entry, so a summary with a percent sign in it, like
     * the text size row's "130% of the size", threw as the row was drawn and closed Facebook.
     */
    abstract static class PlainSummaryList extends ListPreference {
        @Nullable
        private CharSequence summary;

        PlainSummaryList(Context context) {
            super(context);
        }

        @Override
        public void setSummary(@Nullable CharSequence summary) {
            this.summary = summary;
            super.setSummary(summary);
        }

        @Override
        public CharSequence getSummary() {
            return summary;
        }
    }

    /**
     * The save folder's row. Its summary follows its text, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class FolderRow extends EditTextPreference {
        FolderRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(folderSummary(SaveFolder.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The row of the subfolder videos ([video]) or photos go in. Its summary follows its text,
     * whoever sets it, and names the folder the kind's saves land in.
     */
    static final class SubfolderRow extends EditTextPreference {
        final boolean video;

        SubfolderRow(Context context, boolean video) {
            super(context);
            this.video = video;
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            showSummary();
        }

        /** Also redone when the save folder or Save to changes, since the path starts with those. */
        void showSummary() {
            setSummary(subfolderSummary(video, SaveFolder.cleanSubfolder(getText())));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * A word list's row. Its summary follows its text, whoever sets it: the person, the shared page
     * syncing it from the setting, or an import. Its dialog says, as the list is typed, how full the
     * room the two lists share would be, and Save refuses a list that doesn't fit with the dialog
     * still open, so nothing typed is cut or lost (#58).
     */
    static final class WordsRow extends EditTextPreference {
        final boolean hides;
        /** Says why Save was refused. The page sets it, since its dialogs close with it. */
        @Nullable Consumer<String> refused;
        @Nullable private TextView count;
        /** The other list's share of the room, read when the dialog opens. */
        private int otherBytes;
        /**
         * Opens the list of topic packs, set by the page for the list that hides posts. The words a
         * pack adds go into the open dialog's text as ordinary lines, and nothing is saved until Save.
         */
        @Nullable Runnable choosePack;

        WordsRow(Context context, boolean hides) {
            super(context);
            this.hides = hides;
            getEditText().addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int after) {
                    if (count != null) count.setText(wordsEditorLine(PostWords.size(text.toString(), otherBytes)));
                }
                @Override public void afterTextChanged(android.text.Editable text) { }
            });
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(wordsSummary(text, hides));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /**
         * The explanation, then the whole list, in one scroll, as the file name's dialog has it. The
         * list grows with its lines rather than scrolling inside itself, so with the keyboard open
         * and the dialog shrunk above it, the first and last lines and the caret are all a scroll
         * away, and Save and Cancel stay below.
         */
        @Override protected View onCreateDialogView() {
            Context context = getContext();
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            TextView help = new TextView(context);
            help.setId(android.R.id.message);
            help.setText(getDialogMessage());
            help.setTextSize(14);
            help.setTextColor(colors.summary);
            help.setPadding(0, 0, 0, Math.round(10 * context.getResources().getDisplayMetrics().density));
            count = new TextView(context);
            count.setTextSize(14);
            count.setTextColor(colors.summary);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if (choosePack == null) return scrollingBody(context, help, count, getEditText());
            Button packs = new Button(context);
            packs.setText(L10n.t("Add a topic pack"));
            packs.setAllCaps(false);
            packs.setTextSize(14);
            packs.setTextColor(colors.heading);
            ScreenColors.pressFeedback(packs, colors.heading, false);
            int touch = Math.round(48 * context.getResources().getDisplayMetrics().density);
            packs.setMinHeight(touch);
            packs.setMinimumHeight(touch);
            packs.setPadding(0, 0, 0, 0);
            packs.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            packs.setOnClickListener(ignored -> choosePack.run());
            return scrollingBody(context, help, count, packs, getEditText());
        }

        /**
         * Adds [pack]'s words to the text in the open dialog, after what's there, and says what came
         * of it on the count line, above the count, until the next edit. A toast lost the end:
         * Android 12 and later cut one to two lines, and the S22 showed "Tap Save to keep the l…".
         * The list isn't saved: the words are lines in the editor until Save.
         */
        void addPack(TopicPacks.Pack pack) {
            String typed = getEditText().getText().toString();
            TopicPacks.Result result = TopicPacks.add(typed, pack, otherBytes);
            if (result.added > 0) {
                getEditText().setText(result.text);
                getEditText().setSelection(getEditText().getText().length());
            }
            String said = packResult(pack, result);
            if (count == null) {
                Utils.showToastLong(said);
                return;
            }
            count.setText(said + "\n" + wordsEditorLine(PostWords.size(getEditText().getText().toString(), otherBytes)));
        }

        @Override protected void onBindDialogView(View view) {
            // The input is already in the scroll, after its explanation.
            getEditText().setText(getText());
        }

        @Override protected void onDialogClosed(boolean positive) {
            super.onDialogClosed(positive);
            count = null;
        }

        /** Its edit dialog takes the screen's colours, as the folder's does. */
        @Override
        protected void showDialog(Bundle state) {
            // Before the dialog binds the list, which the count line measures against it.
            otherBytes = PostWords.encodedBytes(PostWords.clean((hides ? Settings.KEPT_WORDS : Settings.HIDDEN_WORDS).savedValue()));
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) getDialog();
                ScreenColors.dialog(dialog);
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
                    String why = wordsRefusal(PostWords.size(getEditText().getText().toString(), otherBytes));
                    if (why != null) {
                        if (refused != null) refused.accept(why);
                        return;
                    }
                    // As Android's own button does: the click, then the close, each posted in turn.
                    Handler main = new Handler(Looper.getMainLooper());
                    main.post(() -> onClick(dialog, DialogInterface.BUTTON_POSITIVE));
                    main.post(dialog::dismiss);
                });
            }
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The row of the list Hide posts from people, Pages and sites reads. Its summary follows its
     * text, whoever sets it, and its dialog shows the explanation and the whole list in one scroll,
     * as a word list's does.
     */
    static final class SourcesRow extends EditTextPreference {
        SourcesRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(sourcesSummary(text));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        @Override protected View onCreateDialogView() {
            Context context = getContext();
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            TextView help = new TextView(context);
            help.setId(android.R.id.message);
            help.setText(getDialogMessage());
            help.setTextSize(14);
            help.setTextColor(colors.summary);
            help.setPadding(0, 0, 0, Math.round(10 * context.getResources().getDisplayMetrics().density));
            return scrollingBody(context, help, getEditText());
        }

        @Override protected void onBindDialogView(View view) {
            // The input is already in the scroll, after its explanation.
            getEditText().setText(getText());
        }

        /** Its edit dialog takes the screen's colours, as the word lists' do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * An edit dialog's body as one scroll: [parts] top to bottom, each as wide as the dialog. With
     * the keyboard open the dialog shrinks above it ({@link HushfacebookPreferenceFragment#fitAboveKeyboard}),
     * and what no longer fits scrolls while Save and Cancel stay below.
     */
    static ScrollView scrollingBody(Context context, View... parts) {
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        android.widget.LinearLayout content = new android.widget.LinearLayout(context);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, pad);
        for (View part : parts) {
            if (part.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) part.getParent()).removeView(part);
            content.addView(part, new android.widget.LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        return scroll;
    }

    /**
     * The video or the photo file name's row. Its summary follows its text, whoever sets it: the
     * person, the shared page syncing it from the setting, or an import.
     */
    static final class FileNameRow extends EditTextPreference {
        /** Whether it names saved photos rather than videos. */
        final boolean photo;
        @Nullable private TextView preview;
        private java.util.Date previewDate;

        FileNameRow(Context context, boolean photo) {
            super(context);
            this.photo = photo;
            getEditText().addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (preview != null) preview.setText(previewName(text.toString(), previewDate, FileNameRow.this.photo));
                }
                @Override public void afterTextChanged(android.text.Editable text) { }
            });
        }

        static String previewName(String text, java.util.Date when, boolean photo) {
            if (photo) {
                return FileNameTemplate.photoName(FileNameTemplate.sanitizePhoto(text), when, PostDetails.of("123456")) + ".jpg";
            }
            return FileNameTemplate.videoName(FileNameTemplate.sanitize(text), when, "123456") + ".mp4";
        }

        @Override protected View onCreateDialogView() {
            Context context = getContext();
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
            TextView label = new TextView(context);
            label.setText(L10n.t("Example without post details"));
            label.setTextSize(12);
            label.setTextColor(colors.summary);
            label.setPadding(0, pad, 0, pad / 4);
            previewDate = new java.util.Date();
            preview = new TextView(context);
            preview.setTextSize(14);
            preview.setTextColor(colors.title);
            preview.setText(previewName(getText(), previewDate, photo));
            TextView help = new TextView(context);
            help.setId(android.R.id.message);
            help.setText(getDialogMessage());
            help.setTextSize(14);
            help.setTextColor(colors.summary);
            help.setPadding(0, pad, 0, 0);
            return scrollingBody(context, getEditText(), label, preview, help);
        }

        @Override protected void onBindDialogView(View view) {
            // The input is already in the custom scroll container, before its explanatory text.
            getEditText().setText(getText());
        }

        @Override protected void onDialogClosed(boolean positive) {
            super.onDialogClosed(positive);
            preview = null;
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(photo ? photoNameSummary(FileNameTemplate.sanitizePhoto(text))
                    : fileNameSummary(FileNameTemplate.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the folder's does. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The download quality's row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class QualityRow extends PlainSummaryList {
        QualityRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            DownloadQuality quality = DownloadQuality.BEST;
            for (DownloadQuality candidate : DownloadQuality.values()) {
                if (candidate.name().equals(getValue())) quality = candidate;
            }
            setSummary(qualitySummary(quality));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The Save to row. Its summary follows its value, whoever sets it: the person, the shared page
     * syncing it from the setting, or an import.
     */
    static final class SaveToRow extends PlainSummaryList {
        SaveToRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            SaveTo to = SaveTo.MOVIES_AND_PICTURES;
            for (SaveTo candidate : SaveTo.values()) {
                if (candidate.name().equals(getValue())) to = candidate;
            }
            setSummary(saveToSummary(to));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The download action's row. Its summary follows its value, whoever sets it: the person or the
     * shared page syncing it from the setting.
     */
    static final class DownloadActionRow extends PlainSummaryList {
        DownloadActionRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            setSummary(downloadActionSummary(SendLink.Action.SEND.name().equals(getValue())
                    ? SendLink.Action.SEND : SendLink.Action.SAVE));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The send-to app's row. Its summary follows its text, whoever sets it: the person or the
     * shared page syncing it from the setting.
     */
    static final class SendAppRow extends EditTextPreference {
        SendAppRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(sendAppSummary(text));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the folder's does. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The start tab's row. Its summary follows its value, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class StartTabRow extends PlainSummaryList {
        StartTabRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            StartTab tab = StartTab.MARKETPLACE;
            for (StartTab candidate : StartTab.values()) {
                if (candidate.name().equals(getValue())) tab = candidate;
            }
            setSummary(startTabSummary(tab));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The Feeds filter's row, under the start tab's. Its summary follows its value, whoever sets
     * it: the person, the shared page syncing it from the setting, or an import.
     */
    static final class FeedsSubtabRow extends PlainSummaryList {
        FeedsSubtabRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            FeedsSubtab subtab = FeedsSubtab.ALL;
            for (FeedsSubtab candidate : FeedsSubtab.values()) {
                if (candidate.name().equals(getValue())) subtab = candidate;
            }
            setSummary(feedsSubtabSummary(subtab));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The comment order's row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class CommentOrderRow extends PlainSummaryList {
        CommentOrderRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            CommentOrder order = CommentOrder.FACEBOOK;
            for (CommentOrder candidate : CommentOrder.values()) {
                if (candidate.name().equals(getValue())) order = candidate;
            }
            setSummary(commentOrderSummary(order));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The app lock's time away row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class LockAfterRow extends PlainSummaryList {
        LockAfterRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            AppLock.After after = AppLock.After.ONE_MINUTE;
            for (AppLock.After candidate : AppLock.After.values()) {
                if (candidate.name().equals(getValue())) after = candidate;
            }
            setSummary(lockAfterSummary(after));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The seen posts row: how long a post you've scrolled past stays hidden. Its summary follows its
     * value, whoever sets it: the person, the shared page syncing it from the setting, or an import.
     */
    static final class SeenKeepRow extends PlainSummaryList {
        SeenKeepRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        /** It's greyed out while Hide seen posts is off, and its summary then says so. */
        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            showSummary();
        }

        void showSummary() {
            SeenPosts.Keep keep = SeenPosts.Keep.SEVEN_DAYS;
            for (SeenPosts.Keep candidate : SeenPosts.Keep.values()) {
                if (candidate.name().equals(getValue())) keep = candidate;
            }
            String summary = seenKeepSummary(keep);
            if (!isEnabled()) {
                summary += " " + L10n.f("Turn on %1$s to use this.", L10n.isolate(SwitchLabels.title(Settings.HIDE_SEEN_POSTS)));
            }
            setSummary(summary);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The text size row. Its summary follows its value, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class TextSizeRow extends PlainSummaryList {
        TextSizeRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            TextSize.Scale scale = TextSize.Scale.P100;
            for (TextSize.Scale candidate : TextSize.Scale.values()) {
                if (candidate.name().equals(getValue())) scale = candidate;
            }
            setSummary(textSizeSummary(scale));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The reaction ceiling row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class ReactionCeilingRow extends PlainSummaryList {
        ReactionCeilingRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            ReactionCeiling ceiling = ReactionCeiling.OFF;
            for (ReactionCeiling candidate : ReactionCeiling.values()) {
                if (candidate.name().equals(getValue())) ceiling = candidate;
            }
            setSummary(ceilingSummary(ceiling));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The accent color row. Its summary follows its value, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class AccentRow extends PlainSummaryList {
        /** Whether the Material You theme is in the build, so it picks the colors and this row does nothing. */
        private final boolean materialYou;

        AccentRow(Context context, boolean materialYou) {
            super(context);
            this.materialYou = materialYou;
            setEnabled(!materialYou);
        }

        /** The shared page enables every row it syncs from a setting; with Material You this one stays off. */
        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled && !materialYou);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            if (materialYou) {
                setSummary(L10n.t("Material You theme is in this build and picks Facebook's colors, so this has no effect."));
                return;
            }
            AccentColor.Preset accent = AccentColor.Preset.FACEBOOK;
            for (AccentColor.Preset candidate : AccentColor.Preset.values()) {
                if (candidate.name().equals(getValue())) accent = candidate;
            }
            setSummary(accentSummary(accent));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The playback quality's row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class PlaybackQualityRow extends PlainSummaryList {
        PlaybackQualityRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            PlaybackQuality quality = PlaybackQuality.AUTO;
            for (PlaybackQuality candidate : PlaybackQuality.values()) {
                if (candidate.name().equals(getValue())) quality = candidate;
            }
            setSummary(playbackQualitySummary(quality));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The Reels ([reels]) or Stories quality's row. Its summary follows its value, whoever sets it,
     * as the playback quality's does.
     */
    static final class SurfaceQualityRow extends PlainSummaryList {
        final boolean reels;

        SurfaceQualityRow(Context context, boolean reels) {
            super(context);
            this.reels = reels;
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            SurfaceQuality choice = SurfaceQuality.SAME;
            for (SurfaceQuality candidate : SurfaceQuality.values()) {
                if (candidate.name().equals(getValue())) choice = candidate;
            }
            setSummary(surfaceQualitySummary(choice, reels));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The row for the hour quiet hours start or end. Its summary follows its value, whoever sets it,
     * as the playback quality's does.
     */
    static final class QuietHourRow extends PlainSummaryList {
        QuietHourRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            for (QuietHour candidate : QuietHour.values()) {
                if (candidate.name().equals(getValue())) setSummary(quietHourLabel(candidate));
            }
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }
}
