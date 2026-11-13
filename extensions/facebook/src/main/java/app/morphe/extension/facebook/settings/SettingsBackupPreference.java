/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/bcc57ee555f4346c4eb1cbdae9f6ca8a3fa6fef4/extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/SettingsBackupPreference.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: two rows, Export settings and Import settings, on the
 * framework preference page inside Hushfacebook's settings dialog; an import is read and shown as a
 * preview of how many switches it changes before anything is written; no Reset or Undo.
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.comments.CommentsHeight;
import app.morphe.extension.facebook.reels.BurnInMode;
import app.morphe.extension.facebook.reels.BurnInWait;
import app.morphe.extension.facebook.reels.GrayOver;
import app.morphe.extension.facebook.reels.HoldReaction;
import app.morphe.extension.facebook.reels.ReelDateStyle;
import app.morphe.extension.facebook.reels.ReelsOrder;
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.PostSources;
import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.feed.FeedTypeNames;
import app.morphe.extension.facebook.menu.MenuTiles;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.RecentlyWatched;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.notifications.QuietHour;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.BreakTimer;
import app.morphe.extension.facebook.misc.OpeningPause;
import app.morphe.extension.facebook.misc.ShareSheetItems;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.feed.ReactionCeiling;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.theme.MarkColors.Mark;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.facebook.navigation.TabOrder;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Export settings and Import settings.
 *
 * <p>Both open Android's file picker from the settings page. The picker is an activity of its
 * own, so Android may rebuild Facebook's activity behind it, and the settings dialog and its page
 * with it. The result still reaches the page: Android hands it to the fragment the request came
 * from by the name the framework gave it, and a rebuilt page is given the same one. So nothing
 * about a request is kept on the page. Its code says which it was, and the file's address comes
 * with the result.
 *
 * <p>An import reads the file first and shows how many switches it changes. Nothing is written
 * until the person says so, and then everything is written in one commit. The preview is kept in
 * the page's saved state, so a rotation or a trip away from Facebook brings it back.
 *
 * <p>The app holding a file opens, reads and writes it on a worker. The screen waits for it
 * {@link #timeoutMs} at most, then gives the rows back, cancels the open and closes the file.
 * Whatever that app does afterwards shows nothing and changes nothing. An export is read back, so
 * a file that won't import isn't called saved.
 */
@SuppressWarnings("deprecation") // Framework preferences are what the shared settings page builds on.
public class SettingsBackupPreference extends Preference {
    static final int EXPORT = 7311;
    static final int IMPORT = 7312;

    /** What a settings file is saved as. */
    static final String MIME_TYPE = "application/json";

    /**
     * What an import offers to open. A provider that doesn't know .json files calls them text or
     * plain bytes, and a picker filtered to JSON alone greyed out a file saved there.
     */
    static final String[] OPENABLE_TYPES = {MIME_TYPE, "text/plain", "application/octet-stream"};

    /** How long the app holding a file gets to open, read or write it before the rows come back. A test sets its own. */
    static volatile long timeoutMs = 30_000L;

    /**
     * The run that holds the rows, or null. Only it may show a result or offer a preview, so a run
     * the screen stopped waiting for can't announce anything or lead to an import when it ends.
     */
    private static final AtomicReference<Run> OWNER = new AtomicReference<>();

    /**
     * A run the screen stopped waiting for. Until its file's app answers it holds a worker, so no
     * other run starts: stuck apps can't take the shared pool, and a second write can't reach a
     * file the first may still be writing.
     */
    @Nullable
    private static volatile Run stalled;

    /** One read, write or import, with what the screen needs to stop waiting for it. */
    private static final class Run {
        /** Reaches the file's app while it opens the file, for an app that honours it. */
        final CancellationSignal cancel = new CancellationSignal();
        /** The file the worker has open, closed when the screen stops waiting. */
        @Nullable
        volatile Closeable open;
        /** The worker has returned. */
        volatile boolean ended;

        /** Keeps [file] where the screen can close it, or closes it now when the screen has stopped waiting. */
        AssetFileDescriptor hold(@Nullable AssetFileDescriptor file) throws IOException {
            if (file == null) throw new FileNotFoundException("No file");
            open = file;
            if (cancel.isCanceled()) {
                file.close();
                throw new OperationCanceledException();
            }
            return file;
        }
    }

    /** The rows on the page right now, so a run can take them all out of reach. */
    private static final List<WeakReference<SettingsBackupPreference>> ROWS = new CopyOnWriteArrayList<>();

    /** The action running and the line its row shows, so a row built during a run shows it too. */
    private static volatile int runningAction;
    @Nullable
    private static volatile String runningLine;

    /**
     * The newest page, which a file read finishes on. A page rebuilt while the file was being read
     * is a new one, and the old one can no longer show anything.
     */
    private static WeakReference<HushfacebookPreferenceFragment> latestPage = new WeakReference<>(null);

    private final int rowAction;
    private final CharSequence restingSummary;
    /** The line this row shows while it's the one running, else null. */
    @Nullable
    private String busyLine;

    SettingsBackupPreference(HushfacebookPreferenceFragment page, Context context, int action,
                             CharSequence title, CharSequence summary) {
        super(context);
        rowAction = action;
        restingSummary = summary;
        setKey(action == EXPORT ? "action_export_settings" : "action_import_settings");
        setPersistent(false);
        setTitle(title);
        setSummary(summary);
        setOnPreferenceClickListener(preference -> {
            // Rows are out of reach while a run is going, so this is only the race between a tap
            // and that.
            if (OWNER.get() == null && !stillStalled()) pickFile(page, action);
            return true;
        });
        latestPage = new WeakReference<>(page);
        ROWS.add(new WeakReference<>(this));
        int running = runningAction;
        if (running != 0) showBusy(running, runningLine);
    }

    /** A readable, sortable name for the file, stamped in UTC like the diagnostic report. */
    static String suggestedExportName() {
        return "hushfacebook-settings-" + LogBufferManager.fileTimestamp() + ".json";
    }

    private static void pickFile(HushfacebookPreferenceFragment page, int action) {
        Intent intent = new Intent(action == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE);
        if (action == EXPORT) {
            intent.setType(MIME_TYPE).putExtra(Intent.EXTRA_TITLE, suggestedExportName());
        } else {
            intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, OPENABLE_TYPES);
        }
        try {
            page.startActivityForResult(intent, action);
        } catch (ActivityNotFoundException missing) {
            Logger.printInfo(() -> "No file picker for the settings file");
            Utils.showToastLong(L10n.t("This phone has no file picker, so there's no way to choose a file here."));
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Could not open the file picker: " + error.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("Couldn't open the file picker. Try again."));
        }
    }

    /**
     * A picker's answer, from the page's onActivityResult.
     *
     * @return whether the request was one of these rows'.
     */
    static boolean onResult(HushfacebookPreferenceFragment page, int request, int result, @Nullable Intent data) {
        if (request != EXPORT && request != IMPORT) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return true; // Cancelled: nothing to do and nothing to say.
        if (request == EXPORT) export(page, uri);
        else readForPreview(page, uri);
        return true;
    }

    private static void export(HushfacebookPreferenceFragment page, Uri uri) {
        Context context = appContext(page);
        Run run = start(EXPORT, L10n.t("Saving the settings file"));
        if (run == null) return;
        // Read here, on the main thread, where a Quick setup or a word list save is never halfway.
        // The worker only writes these out, so an edit made while it runs can't land in the file in
        // part. An import is the one change to them written off this thread, and it holds the rows
        // until it's done, so none is halfway once this run has them.
        SettingsBackup.Saved saved = SettingsBackup.savedNow();
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            String said = null;
            try {
                said = write(context.getContentResolver(), uri, saved.file().getBytes(StandardCharsets.UTF_8), run);
            } catch (Exception error) {
                Logger.printInfo(() -> "Settings export failed: " + error.getClass().getSimpleName());
                said = L10n.t("Couldn't save the settings file. Try again.");
            } finally {
                String result = said;
                ended(run, result == null ? null : () -> Utils.showToastLong(result));
            }
        });
        if (!accepted) notStarted(run);
        else watch(run, L10n.t("The app holding the settings file is taking too long, so Hushfacebook stopped waiting. "
                + "That app may still finish saving it, so check the file before you rely on it."));
    }

    /**
     * Writes over whatever is there, reads the file back and says how that went. "wt" truncates a
     * file being replaced; an app that turns the mode down gets "w", which can leave old bytes past
     * the new end. The read back catches that and anything else that keeps the file from importing.
     * An app that won't hand the file back leaves it unchecked, and the answer says so.
     */
    private static String write(ContentResolver resolver, Uri uri, byte[] bytes, Run run) throws IOException {
        AssetFileDescriptor file;
        try {
            file = resolver.openAssetFileDescriptor(uri, "wt", run.cancel);
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException unsupported) {
            file = resolver.openAssetFileDescriptor(uri, "w", run.cancel);
        }
        try (AssetFileDescriptor held = run.hold(file); OutputStream stream = held.createOutputStream()) {
            stream.write(bytes);
        }
        String back;
        try {
            back = read(resolver, uri, run);
        } catch (SettingsBackup.Rejected refused) {
            if (refused.reason == SettingsBackup.Reason.UNREADABLE) {
                return L10n.t("Settings exported. Hushfacebook couldn't read the file back to check it.");
            }
            back = null;
        }
        if (new String(bytes, StandardCharsets.UTF_8).equals(back)) return L10n.t("Settings exported.");
        Logger.printInfo(() -> "Settings export read back differently");
        return L10n.t("The settings file was saved, but it doesn't match what Hushfacebook wrote. Save it again as a new "
                + "file.");
    }

    private static void readForPreview(HushfacebookPreferenceFragment page, Uri uri) {
        Context context = appContext(page);
        Run run = start(IMPORT, L10n.t("Reading the settings file"));
        if (run == null) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            Runnable result = null;
            try {
                String text = read(context.getContentResolver(), uri, run);
                Logger.printInfo(() -> "Settings file read: " + text.length() + " chars");
                SettingsBackup.Snapshot snapshot = SettingsBackup.parse(text);
                result = () -> offer(snapshot);
            } catch (SettingsBackup.Rejected refused) {
                // A refusal's message names a setting at most, never a value from the file.
                Logger.printInfo(() -> "Settings file refused: " + refused.reason + ", " + refused.getMessage());
                String said = refusal(refused.reason);
                result = () -> Utils.showToastLong(said);
            } finally {
                ended(run, result);
            }
        });
        if (!accepted) notStarted(run);
        else watch(run, L10n.t("The app holding that file is taking too long, so Hushfacebook stopped waiting. "
                + "Nothing was changed."));
    }

    /** The file's text, opened through its app with [run]'s cancel signal and held where the screen can close it. */
    private static String read(ContentResolver resolver, Uri uri, Run run) throws SettingsBackup.Rejected {
        InputStream stream;
        try {
            AssetFileDescriptor file = run.hold(resolver.openAssetFileDescriptor(uri, "r", run.cancel));
            long start = file.getStartOffset();
            long declared = file.getDeclaredLength();
            Logger.printInfo(() -> "Settings file opened at " + start + ", declared length " + declared);
            stream = file.createInputStream();
        } catch (IOException | RuntimeException error) {
            closeQuietly(run.open);
            // The class only: an app's message can carry the document's name or address.
            throw new SettingsBackup.Rejected(SettingsBackup.Reason.UNREADABLE, error.getClass().getSimpleName());
        }
        return SettingsBackup.read(stream);
    }

    /** One sentence per refusal, so a cut-off download and a newer version's file don't read the same. */
    static String refusal(SettingsBackup.Reason reason) {
        switch (reason) {
            case SIZE:
                return L10n.t("That file is too large to be a settings file. Nothing was changed.");
            case ENCODING:
                return L10n.t("That file isn't readable text, so it may have been damaged on the way. Nothing was changed.");
            case DAMAGED:
                return L10n.t("That settings file is damaged or only partly downloaded. Nothing was changed.");
            case DUPLICATE:
                return L10n.t("That file lists a setting twice, so Hushfacebook can't tell which value to use. Nothing was changed.");
            case FORMAT:
                return L10n.t("That isn't a Hushfacebook settings file. Nothing was changed.");
            case SCHEMA:
                return L10n.t("That settings file was written by a newer Hushfacebook than this one. Nothing was changed.");
            case VALUE:
                return L10n.t("That settings file holds a value Hushfacebook can't read. Nothing was changed.");
            case WORDS:
                return L10n.t("The word lists in that file are longer than the two lists have room for. Nothing was changed.");
            default:
                return L10n.t("Couldn't open that file. Nothing was changed.");
        }
    }

    /** Hands a read file to the page on screen, which shows the preview now or when it resumes. */
    private static void offer(SettingsBackup.Snapshot snapshot) {
        HushfacebookPreferenceFragment page = latestPage.get();
        if (page == null || !page.isAdded()) {
            // The settings screen was closed while the file was read.
            Logger.printInfo(() -> "Settings file read with no settings page left to preview it on");
            return;
        }
        page.pendingImport = snapshot.toBundle();
        if (page.isResumed()) showPreview(page);
    }

    /** Called when the page resumes: a preview waiting on an answer is shown again. */
    static void onPageResumed(HushfacebookPreferenceFragment page) {
        if (page.pendingImport != null) showPreview(page);
    }

    /**
     * Takes the preview off the screen without answering it, when the page's view goes. A page
     * rebuilt from the saved state shows it again.
     */
    static void closePreview(HushfacebookPreferenceFragment page) {
        AlertDialog shown = page.importPreview;
        page.importPreview = null;
        if (shown == null) return;
        shown.setOnCancelListener(null);
        shown.dismiss();
    }

    /**
     * How many switches the waiting file changes, what it does to the other settings, and what's
     * in it that this build doesn't know.
     */
    static void showPreview(HushfacebookPreferenceFragment page) {
        if (page.importPreview != null) return;
        SettingsBackup.Snapshot snapshot = SettingsBackup.Snapshot.fromBundle(page.pendingImport);
        Activity activity = page.getActivity();
        if (snapshot == null || activity == null) {
            page.pendingImport = null;
            return;
        }
        int changes = snapshot.changes().size();
        int switches = snapshot.switchChanges();
        String message;
        if (changes == 0) {
            message = L10n.t("Your settings already match that file, so nothing will change.");
        } else {
            List<String> parts = new ArrayList<>();
            if (switches > 0) {
                parts.add(L10n.quantity(switches, "%1$d switch will change.", "%1$d switches will change.", switches));
            }
            parts.addAll(valueSentences(snapshot.folderChange(), snapshot.qualityChange(), snapshot.fileNameChange(),
                    snapshot.startChange(), snapshot.orderChange(), snapshot.hiddenChange(), snapshot.keptChange(),
                    snapshot.playbackChange(), snapshot.actionChange(), snapshot.appChange(), snapshot.toChange(),
                    snapshot.subtabChange(), snapshot.sourcesChange(), snapshot.photoNameChange(),
                    snapshot.videoSubfolderChange(), snapshot.photoSubfolderChange(), snapshot.reelsQualityChange(),
                    snapshot.storiesQualityChange(), snapshot.quietFromChange(), snapshot.quietUntilChange(),
                    snapshot.lockAfterChange(), snapshot.textSizeChange(), snapshot.accentChange()));
            if (snapshot.ceilingChange() != null) parts.add(ceilingSentence(snapshot.ceilingChange()));
            if (snapshot.shareItemsChange() != null) parts.add(shareItemsSentence(snapshot.shareItemsChange()));
            if (snapshot.menuTilesChange() != null) parts.add(menuTilesSentence(snapshot.menuTilesChange()));
            if (snapshot.feedTypesChange() != null) parts.add(feedTypesSentence(snapshot.feedTypesChange()));
            if (snapshot.tabOrderChange() != null) parts.add(tabOrderSentence(snapshot.tabOrderChange()));
            if (snapshot.homeFeedChange() != null) parts.add(homeFeedSentence(snapshot.homeFeedChange()));
            if (snapshot.reelsOrderChange() != null) parts.add(reelsOrderSentence(snapshot.reelsOrderChange()));
            if (snapshot.burnInChange() != null) parts.add(burnInSentence(snapshot.burnInChange()));
            if (snapshot.burnInWaitChange() != null) parts.add(burnInWaitSentence(snapshot.burnInWaitChange()));
            if (snapshot.holdReactionChange() != null) parts.add(holdReactionSentence(snapshot.holdReactionChange()));
            if (snapshot.grayOverChange() != null) parts.add(grayOverSentence(snapshot.grayOverChange()));
            if (snapshot.newRowMarkChange() != null) parts.add(newRowMarkSentence(snapshot.newRowMarkChange()));
            if (snapshot.dateStyleChange() != null) parts.add(dateStyleSentence(snapshot.dateStyleChange()));
            if (snapshot.commentsHeightChange() != null) parts.add(commentsHeightSentence(snapshot.commentsHeightChange()));
            if (snapshot.backgroundChange() != null) parts.add(backgroundSentence(snapshot.backgroundChange()));
            if (snapshot.lightBackgroundChange() != null) parts.add(lightBackgroundSentence(snapshot.lightBackgroundChange()));
            if (snapshot.badgeChange() != null) parts.add(markColorSentence(Mark.BADGE, snapshot.badgeChange()));
            if (snapshot.logoChange() != null) parts.add(markColorSentence(Mark.LOGO, snapshot.logoChange()));
            if (snapshot.tabChange() != null) parts.add(markColorSentence(Mark.TAB, snapshot.tabChange()));
            if (snapshot.headerTextChange() != null) parts.add(headerTextSentence(snapshot.headerTextChange()));
            if (snapshot.logoEmojiChange() != null) parts.add(logoEmojiSentence(snapshot.logoEmojiChange()));
            if (snapshot.accentCustomChange() != null) parts.add(accentCustomSentence(snapshot.accentCustomChange()));
            if (snapshot.seenKeep != null && snapshot.changes().containsKey(SettingsBackup.SEEN_KEEP)) {
                parts.add(L10n.f("Posts you've seen will stay hidden for %1$s.",
                        HushfacebookPreferenceFragment.seenKeepLabel(snapshot.seenKeep)));
            }
            if (snapshot.pauseLength != null && snapshot.changes().containsKey(SettingsBackup.PAUSE_LENGTH)) {
                parts.add(pauseLengthSentence(snapshot.pauseLength));
            }
            if (snapshot.breakAfter != null && snapshot.changes().containsKey(SettingsBackup.BREAK_AFTER)) {
                parts.add(breakAfterSentence(snapshot.breakAfter));
            }
            if (snapshot.breakLength != null && snapshot.changes().containsKey(SettingsBackup.BREAK_LENGTH)) {
                parts.add(breakLengthSentence(snapshot.breakLength));
            }
            if (snapshot.watchedKeep != null && snapshot.changes().containsKey(SettingsBackup.WATCHED_KEEP)) {
                parts.add(watchedKeepSentence(snapshot.watchedKeep));
            }
            message = String.join("\n\n", parts);
        }
        if (snapshot.unknown > 0) {
            message += "\n\n" + L10n.quantity(snapshot.unknown,
                    "%1$d item in that file isn't a setting this version of Hushfacebook knows, so it'll be left out.",
                    "%1$d items in that file aren't settings this version of Hushfacebook knows, so they'll be left out.",
                    snapshot.unknown);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(HushfacebookPreferenceFragment.themed(activity))
                .setTitle(L10n.t("Import settings"))
                .setMessage(message)
                .setOnCancelListener(dialog -> answered(page));
        if (changes == 0) {
            builder.setPositiveButton(L10n.t("OK"), (dialog, which) -> answered(page));
        } else {
            builder.setPositiveButton(L10n.t("Import"), (dialog, which) -> {
                Bundle chosen = page.pendingImport;
                answered(page);
                apply(page, chosen);
            });
            builder.setNegativeButton(L10n.t("Cancel"), (dialog, which) -> answered(page));
        }
        page.importPreview = builder.show();
        addSwitchDifferences(page.importPreview, snapshot);
        ScreenColors.dialog(page.importPreview);
    }

    /** Keep the framework's one scroll area and pinned actions, adding one spoken row per switch. */
    private static void addSwitchDifferences(AlertDialog dialog, SettingsBackup.Snapshot snapshot) {
        TextView message = dialog.findViewById(android.R.id.message);
        if (message == null || !(message.getParent() instanceof ViewGroup)) return;
        LinearLayout details = new LinearLayout(dialog.getContext());
        details.setOrientation(LinearLayout.VERTICAL);
        ScreenColors colors = ScreenColors.current();
        for (Map.Entry<BooleanSetting, Boolean> entry : snapshot.values.entrySet()) {
            BooleanSetting setting = entry.getKey();
            boolean current = setting.savedValue();
            boolean incoming = entry.getValue();
            if (current == incoming) continue;
            String name = SwitchLabels.title(setting);
            String before = current ? L10n.t("On") : L10n.t("Off");
            String after = incoming ? L10n.t("On") : L10n.t("Off");
            LinearLayout row = new LinearLayout(dialog.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPaddingRelative(message.getPaddingStart(), dp(row, 16), message.getPaddingEnd(), dp(row, 8));
            row.setTag(setting);
            row.setScreenReaderFocusable(true);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            row.setContentDescription(name + ". " + L10n.f("Saved now %1$s. After import %2$s.",
                    L10n.isolate(before), L10n.isolate(after)));
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(TextView.class.getName());
                    info.setScreenReaderFocusable(true);
                    info.setContentDescription(host.getContentDescription());
                }
            });
            TextView title = new TextView(dialog.getContext());
            title.setText(name);
            title.setTextSize(16);
            title.setTextColor(colors.title);
            title.setTypeface(Typeface.create("sans-serif-medium", 0));
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(title);
            TextView states = new TextView(dialog.getContext());
            states.setText(L10n.isolate(before) + " \u2192 " + L10n.isolate(after));
            states.setTextDirection(View.TEXT_DIRECTION_LTR);
            states.setTextSize(14);
            states.setTextColor(colors.summary);
            states.setPaddingRelative(0, dp(row, 4), 0, 0);
            states.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(states);
            details.addView(row);
        }
        if (details.getChildCount() == 0) return;
        ViewGroup parent = (ViewGroup) message.getParent();
        int index = parent.indexOfChild(message);
        ViewGroup.LayoutParams size = message.getLayoutParams();
        parent.removeView(message);
        details.addView(message, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(details, index, size);
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /** The sentence that says which top folder saves go to after an import. */
    static String saveToSentence(SaveTo to) {
        if (to == SaveTo.MOVIES_AND_PICTURES) {
            return L10n.f("Videos will go to %1$s and photos to %2$s.", L10n.isolate(to.directory(true)),
                    L10n.isolate(to.directory(false)));
        }
        return L10n.f("Videos and photos will go to %1$s.", L10n.isolate(to.directory(true)));
    }

    /** The sentence that says where saves go after an import, for the folder name [folder]. */
    static String folderSentence(String folder) {
        return L10n.f("Saves will go to a folder named %1$s.", L10n.isolate(folder));
    }

    /**
     * The sentence that says what quality videos save at after an import. Worded like the row's
     * summary (HushfacebookPreferenceFragment.qualitySummary): below the cap first, above it only
     * when a video has nothing that low.
     */
    static String qualitySentence(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Videos will save at the best quality.");
            case SMALLEST:
                return L10n.t("Videos will save at their lowest quality, for the smallest files.");
            default:
                return L10n.f("Videos will save at %1$s or the closest quality below it. A video with nothing "
                        + "that low will save at the closest quality above.", L10n.isolate(quality.ceilingLabel()));
        }
    }

    /** The sentence that says what saved videos are named after an import, for [template]. */
    static String fileNameSentence(String template) {
        return L10n.f("Saved videos will be named %1$s.", L10n.isolate(template));
    }

    /** The sentence that says what saved photos will be named after an import. */
    static String photoNameSentence(String template) {
        return L10n.f("Saved photos will be named %1$s.", L10n.isolate(template));
    }

    /**
     * The sentence that says which tab Facebook opens on after an import. The tab only counts while
     * its switch is on, which is off unless it's changed, so the sentence names the switch too.
     */
    static String startTabSentence(StartTab tab) {
        return L10n.f("With %1$s on, Facebook will open on %2$s.",
                L10n.isolate(SwitchLabels.title(Settings.OPEN_ON_CHOSEN_TAB)),
                HushfacebookPreferenceFragment.tabLabel(tab));
    }

    /** The sentence that says which filter the Feeds tab opens on after an import. */
    static String feedsSubtabSentence(FeedsSubtab subtab) {
        if (subtab == FeedsSubtab.ALL) return L10n.t("The Feeds tab will open on the filter Facebook picks.");
        return L10n.f("The Feeds tab will open on %1$s.", HushfacebookPreferenceFragment.feedsSubtabLabel(subtab));
    }

    /** The sentence that says which filter's posts Home shows after an import, while its switch is on. */
    static String homeFeedSentence(FeedsSubtab feed) {
        return L10n.f("With %1$s on, Home will show the same posts as %2$s in the Feeds tab.",
                L10n.isolate(SwitchLabels.title(Settings.FOLLOWING_FEED_HOME)),
                HushfacebookPreferenceFragment.feedsSubtabLabel(feed));
    }

    /** The sentence that says what Sort loaded reels sorts by after an import. */
    static String reelsOrderSentence(ReelsOrder order) {
        return L10n.f("Reels order will be set to %1$s.", HushfacebookPreferenceFragment.reelsOrderLabel(order));
    }

    /** The sentence that says what Burn-in guard does after an import (#288). */
    static String burnInSentence(BurnInMode mode) {
        return L10n.f("Burn-in guard will be set to %1$s.", HushfacebookPreferenceFragment.burnInLabel(mode));
    }

    /** The sentence that says how new settings are marked after an import (#16). */
    static String newRowMarkSentence(NewRowMark mark) {
        if (mark == NewRowMark.OFF) return L10n.t("Settings an update adds won't get a dot.");
        if (mark == NewRowMark.ONE_VISIT) return L10n.t("Settings an update adds will get a dot until you leave their page.");
        return L10n.f("Settings an update adds will get a dot for %1$s from the first time you open their page.",
                HushfacebookPreferenceFragment.newRowMarkLabel(mark));
    }

    /** The sentence that says how long Burn-in guard waits after an import (#288). */
    static String burnInWaitSentence(BurnInWait wait) {
        if (wait == BurnInWait.AT_ONCE) return L10n.t("Burn-in guard will dim the Reels screen as soon as you stop touching it.");
        return L10n.f("Burn-in guard will dim the Reels screen after %1$d seconds without a touch.", (int) (wait.millis / 1000));
    }

    /** The sentence that says what Hold a post to react gives after an import (#454). */
    static String holdReactionSentence(HoldReaction reaction) {
        if (reaction == HoldReaction.PICK) return L10n.t("Hold a post to react will open the reactions to pick from.");
        return L10n.f("Hold a post to react will give %1$s.", HushfacebookPreferenceFragment.holdReactionLabel(reaction));
    }

    /** The sentence that says how Show Reels in gray takes the color out after an import. */
    static String grayOverSentence(GrayOver over) {
        if (over == GrayOver.AT_ONCE) return L10n.t("Show Reels in gray will make reels gray from the start.");
        return L10n.f("Show Reels in gray will take reels from color to gray over %1$s.",
                HushfacebookPreferenceFragment.grayOverLabel(over));
    }

    /** The sentence that says how a reel's post date reads after an import (#357). */
    static String dateStyleSentence(ReelDateStyle style) {
        if (style == ReelDateStyle.DATE) return L10n.t("Reel post dates will show the day they were posted.");
        return L10n.t("Reel post dates will show how long ago they were posted.");
    }

    /** The sentence that says how high comments open after an import (#179). */
    static String commentsHeightSentence(CommentsHeight height) {
        if (height == CommentsHeight.FULL) return L10n.t("Comments will open at full height.");
        if (height == CommentsHeight.HALF) return L10n.t("Comments will open at half height.");
        return L10n.t("Comments will open where Facebook stops them.");
    }

    /** The sentence that says in what order comments open after an import. */
    static String commentOrderSentence(CommentOrder order) {
        if (order == CommentOrder.FACEBOOK) return L10n.t("Comments will open in the order Facebook picks.");
        if (order == CommentOrder.OLDEST) return L10n.t("Comments will open oldest first.");
        return L10n.f("Comments will open with %1$s picked in their sort menu.",
                HushfacebookPreferenceFragment.commentOrderLabel(order));
    }

    /** The sentence that says what quality videos play at after an import. */
    static String playbackQualitySentence(PlaybackQuality quality) {
        if (quality == PlaybackQuality.AUTO) return L10n.t("Facebook will pick the quality videos play at.");
        return L10n.f("Default quality will be set to %1$s.", HushfacebookPreferenceFragment.playbackQualityLabel(quality));
    }

    /** The sentence that says how long Facebook may be away before the app lock asks, after an import. */
    static String lockAfterSentence(AppLock.After after) {
        if (after == AppLock.After.IMMEDIATELY) return L10n.t("With Lock Facebook on, it will lock as soon as you leave it.");
        return L10n.f("With Lock Facebook on, it will lock once you've been away for %1$s.",
                HushfacebookPreferenceFragment.lockAfterLabel(after));
    }

    /** The sentence that says how long the opening pause counts down, after an import. */
    static String pauseLengthSentence(OpeningPause.Length length) {
        return L10n.f("With Opening pause on, Facebook will wait %1$s before it opens.",
                HushfacebookPreferenceFragment.pauseLengthLabel(length));
    }

    /** The sentence that says how long browsing goes before a break, after an import. */
    static String breakAfterSentence(BreakTimer.Use use) {
        return L10n.f("With Take breaks on, a break will start after %1$s of browsing.",
                HushfacebookPreferenceFragment.minutesLabel(use.minutes));
    }

    /** The sentence that says how long a break lasts, after an import. */
    static String breakLengthSentence(BreakTimer.Length length) {
        return L10n.f("With Take breaks on, a break will last %1$s.",
                HushfacebookPreferenceFragment.minutesLabel(length.minutes));
    }

    /** The sentence that says how long Recently watched keeps an entry, after an import. */
    static String watchedKeepSentence(RecentlyWatched.Keep keep) {
        if (keep == RecentlyWatched.Keep.UNTIL_CLEARED) {
            return L10n.t("Recently watched will keep entries until you clear them.");
        }
        return L10n.f("Recently watched will keep entries for %1$s.", HushfacebookPreferenceFragment.watchedKeepLabel(keep));
    }

    /** The sentence that says what the feed will do about a post's reaction count after an import. */
    static String ceilingSentence(ReactionCeiling ceiling) {
        if (ceiling == ReactionCeiling.OFF) return L10n.t("The feed won't hide posts for how many reactions they have.");
        return L10n.f("The feed will hide posts with more than %1$s reactions.",
                HushfacebookPreferenceFragment.ceilingNumber(ceiling));
    }

    /** The sentence that says which accent color Facebook will have after an import. */
    static String accentSentence(AccentColor.Preset accent) {
        if (accent == AccentColor.Preset.FACEBOOK) return L10n.t("Facebook will keep its own blue.");
        return L10n.f("If your patched Facebook includes Accent color, Facebook's blue will be %1$s.",
                HushfacebookPreferenceFragment.accentLabel(accent, false));
    }

    /** The sentence that says which color dark mode's backgrounds will take after an import, [background] blank or a code. */
    static String backgroundSentence(String background) {
        if (background.isEmpty()) {
            return L10n.t("If your patched Facebook includes the AMOLED black theme, dark mode's backgrounds will be "
                    + "the color it was patched with.");
        }
        return L10n.f("If your patched Facebook includes the AMOLED black theme, dark mode's backgrounds will be %1$s.",
                L10n.isolate(background));
    }

    /** The sentence that says which color the badges, the logo or the selected tab will take after an import, [code] blank or a code. */
    static String markColorSentence(Mark mark, String code) {
        if (code.isEmpty()) {
            switch (mark) {
                case LOGO: return L10n.t("If your patched Facebook includes Badge and logo colors, the logo will be Facebook's blue.");
                case TAB: return L10n.t("If your patched Facebook includes Badge and logo colors, the selected tab will be "
                        + "Facebook's color.");
                default: return L10n.t("If your patched Facebook includes Badge and logo colors, badges will be Facebook's red.");
            }
        }
        switch (mark) {
            case LOGO: return L10n.f("If your patched Facebook includes Badge and logo colors, the logo will be %1$s.",
                    L10n.isolate(code));
            case TAB: return L10n.f("If your patched Facebook includes Badge and logo colors, the selected tab will be %1$s.",
                    L10n.isolate(code));
            default: return L10n.f("If your patched Facebook includes Badge and logo colors, badges will be %1$s.",
                    L10n.isolate(code));
        }
    }

    /** The sentence that says which color light mode's backgrounds will take after an import, [background] blank or a code. */
    static String lightBackgroundSentence(String background) {
        if (background.isEmpty()) {
            return L10n.t("If your patched Facebook includes the AMOLED black theme, light mode's backgrounds will be "
                    + "Facebook's own white.");
        }
        return L10n.f("If your patched Facebook includes the AMOLED black theme, light mode's backgrounds will be %1$s.",
                L10n.isolate(background));
    }

    /** The sentence that says what the top of Home will show after an import, [text] blank or the words. */
    static String headerTextSentence(String text) {
        if (text.isEmpty()) return L10n.t("The top of Home will show Facebook's own logo.");
        return L10n.f("If your patched Facebook includes Home header text, the top of Home will say %1$s.",
                L10n.isolate(text));
    }

    /** The sentence that says what follows the logo at the top of Home after an import, [emoji] blank or the text. */
    static String logoEmojiSentence(String emoji) {
        if (emoji.isEmpty()) return L10n.t("The top of Home will show no emoji after the logo.");
        return L10n.f("If your patched Facebook includes Emoji after the logo, the top of Home will show %1$s after the logo.",
                L10n.isolate(emoji));
    }

    /** The sentence that says which color the Accent color row's Custom choice will use after an import, [code] blank or a code. */
    static String accentCustomSentence(String code) {
        if (code.isEmpty()) {
            return L10n.t("If your patched Facebook includes Accent color, the Custom choice will keep Facebook's blue.");
        }
        return L10n.f("If your patched Facebook includes Accent color, the Custom choice will be %1$s.",
                L10n.isolate(code));
    }

    /** The sentence that says how large Facebook's text will be after an import. */
    static String textSizeSentence(TextSize.Scale scale) {
        if (scale == TextSize.Scale.P100) {
            return L10n.t("Facebook's text will be the size your phone's font size setting gives it.");
        }
        return L10n.f("Facebook's text will be %1$s of the size your phone's font size setting gives it.",
                scale.label());
    }

    /** The sentence that says what quality reels ([reels]) or video stories play at after an import. */
    static String surfaceQualitySentence(SurfaceQuality choice, boolean reels) {
        if (choice.quality == null) {
            return reels ? L10n.t("Reels will play at the same quality as other videos.")
                    : L10n.t("Video stories will play at the same quality as other videos.");
        }
        String label = HushfacebookPreferenceFragment.playbackQualityLabel(choice.quality);
        return reels ? L10n.f("Reels quality will be set to %1$s.", label)
                : L10n.f("Stories quality will be set to %1$s.", label);
    }

    /** The sentence that says when notification quiet hours start ([from]) or end after an import. */
    static String quietHourSentence(QuietHour hour, boolean from) {
        String time = HushfacebookPreferenceFragment.quietHourLabel(hour);
        return from ? L10n.f("Quiet hours will start at %1$s.", time) : L10n.f("Quiet hours will end at %1$s.", time);
    }

    /** The sentence that says what a tap on Download does after an import. */
    static String downloadActionSentence(SendLink.Action action) {
        return action == SendLink.Action.SEND
                ? L10n.t("Tapping Download on a reel or video will send its link to an app.")
                : L10n.t("Reels and videos will save to this phone when you tap Download.");
    }

    /** The sentence that says where links go after an import, for [app], blank for Android's chooser. */
    static String sendAppSentence(String app) {
        if (app.isEmpty()) return L10n.t("Android will ask which app gets the links each time.");
        return L10n.f("Links will go to %1$s.", L10n.isolate(app));
    }

    /**
     * The sentence that says where video ([video]) or photo saves go inside the save folder after an
     * import, for [subfolder], blank for the save folder itself.
     */
    static String subfolderSentence(boolean video, String subfolder) {
        if (subfolder.isEmpty()) {
            return video ? L10n.t("Videos will go in the save folder itself.")
                    : L10n.t("Photos will go in the save folder itself.");
        }
        return video ? L10n.f("Videos will go in a subfolder named %1$s.", L10n.isolate(subfolder))
                : L10n.f("Photos will go in a subfolder named %1$s.", L10n.isolate(subfolder));
    }

    /**
     * The sentence that says what a word list holds after an import: how many phrases, never
     * which. [hides] picks the list of words to hide, otherwise the keep list.
     */
    static String wordsSentence(String list, boolean hides) {
        int phrases = PostWords.count(list);
        if (hides) {
            if (phrases == 0) return L10n.t("Your list of words to hide will be empty.");
            return L10n.quantity(phrases, "Your list of words to hide will hold %1$d word or phrase.",
                    "Your list of words to hide will hold %1$d words or phrases.", phrases);
        }
        if (phrases == 0) return L10n.t("Your list of words that keep a post will be empty.");
        return L10n.quantity(phrases, "Your list of words that keep a post will hold %1$d word or phrase.",
                "Your list of words that keep a post will hold %1$d words or phrases.", phrases);
    }

    /** The sentence that says what the people, Pages and sites list holds after an import: how many, never who. */
    static String sourcesSentence(String list) {
        int rules = PostSources.count(list);
        if (rules == 0) return L10n.t("Your list of people, Pages and sites to hide will be empty.");
        return L10n.quantity(rules, "Your list of people, Pages and sites to hide will hold %1$d entry.",
                "Your list of people, Pages and sites to hide will hold %1$d entries.", rules);
    }

    /** The sentence that says how many share sheet items will be hidden after an import. */
    static String shareItemsSentence(String list) {
        int items = ShareSheetItems.count(list);
        if (items == 0) return L10n.t("Your list of share sheet items to hide will be empty.");
        return L10n.quantity(items, "Your list of share sheet items to hide will hold %1$d item.",
                "Your list of share sheet items to hide will hold %1$d items.", items);
    }

    /** The sentence that says how many Menu tiles will be hidden after an import. */
    static String menuTilesSentence(String list) {
        int tiles = MenuTiles.count(list);
        if (tiles == 0) return L10n.t("Your list of Menu tiles to hide will be empty.");
        return L10n.quantity(tiles, "Your list of Menu tiles to hide will hold %1$d tile.",
                "Your list of Menu tiles to hide will hold %1$d tiles.", tiles);
    }

    /** The sentence that says how many feed types will be hidden after an import. */
    static String feedTypesSentence(String list) {
        int types = FeedTypeNames.count(list);
        if (types == 0) return L10n.t("Your list of feed types to hide will be empty.");
        return L10n.quantity(types, "Your list of feed types to hide will hold %1$d type.",
                "Your list of feed types to hide will hold %1$d types.", types);
    }

    /** The sentence that says the order the tab bar will have after an import. */
    static String tabOrderSentence(String order) {
        List<TabOrder.Tab> tabs = TabOrder.parse(order);
        if (tabs.isEmpty()) return L10n.t("Your tab bar will keep Facebook's order.");
        List<String> names = new ArrayList<>();
        for (TabOrder.Tab tab : tabs) names.add(TabOrderRow.label(tab));
        return L10n.f("Your tab bar will go in this order: %1$s.", L10n.join(names));
    }

    /** A sentence for each setting that isn't a switch an import changes, with no word list among them. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order) {
        return valueSentences(folder, quality, fileName, start, order, null, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the playback quality aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the download action and app aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, null, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the top folder aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the Feeds filter aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the people, Pages and sites list aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the photo file name aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                sources, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the subfolders and the reels and video stories qualities aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources, @Nullable String photoName) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                sources, photoName, null, null, null, null);
    }

    /** A sentence for each setting that isn't a switch an import changes, the app lock's time aside. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources, @Nullable String photoName,
                                       @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                       @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, null, null);
    }

    /** {@link #valueSentences} with the hours notification quiet hours start and end, which come next. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources, @Nullable String photoName,
                                       @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                       @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                       @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom, quietUntil,
                null);
    }

    /**
     * A sentence for each setting that isn't a switch an import changes, in the order the screen
     * shows them: the tab Facebook opens on and the Feeds filter, the word filter's lists and the
     * people, Pages and sites list, the order comments open in, the qualities videos, reels and video
     * stories play at, the download settings, then the quiet hours and how long the app lock waits.
     */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources, @Nullable String photoName,
                                       @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                       @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                       @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil,
                                       @Nullable AppLock.After lockAfter) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom, quietUntil,
                lockAfter, null);
    }

    /** {@link #valueSentences} with how large Facebook's text is, which comes last. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources, @Nullable String photoName,
                                       @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                       @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                       @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil,
                                       @Nullable AppLock.After lockAfter, @Nullable TextSize.Scale textSize) {
        return valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to, subtab,
                sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom, quietUntil,
                lockAfter, textSize, null);
    }

    /** {@link #valueSentences} with the accent color, which comes last. */
    static List<String> valueSentences(@Nullable String folder, @Nullable DownloadQuality quality,
                                       @Nullable String fileName, @Nullable StartTab start,
                                       @Nullable CommentOrder order, @Nullable String hidden,
                                       @Nullable String kept, @Nullable PlaybackQuality playback,
                                       @Nullable SendLink.Action action, @Nullable String app,
                                       @Nullable SaveTo to, @Nullable FeedsSubtab subtab,
                                       @Nullable String sources, @Nullable String photoName,
                                       @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                       @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                       @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil,
                                       @Nullable AppLock.After lockAfter, @Nullable TextSize.Scale textSize,
                                       @Nullable AccentColor.Preset accent) {
        List<String> sentences = new ArrayList<>();
        if (start != null) sentences.add(startTabSentence(start));
        if (subtab != null) sentences.add(feedsSubtabSentence(subtab));
        if (hidden != null) sentences.add(wordsSentence(hidden, true));
        if (kept != null) sentences.add(wordsSentence(kept, false));
        if (sources != null) sentences.add(sourcesSentence(sources));
        if (order != null) sentences.add(commentOrderSentence(order));
        if (playback != null) sentences.add(playbackQualitySentence(playback));
        if (reelsQuality != null) sentences.add(surfaceQualitySentence(reelsQuality, true));
        if (storiesQuality != null) sentences.add(surfaceQualitySentence(storiesQuality, false));
        if (quality != null) sentences.add(qualitySentence(quality));
        if (to != null) sentences.add(saveToSentence(to));
        if (folder != null) sentences.add(folderSentence(folder));
        if (fileName != null) sentences.add(fileNameSentence(fileName));
        if (photoName != null) sentences.add(photoNameSentence(photoName));
        if (action != null) sentences.add(downloadActionSentence(action));
        if (app != null) sentences.add(sendAppSentence(app));
        if (videoSubfolder != null) sentences.add(subfolderSentence(true, videoSubfolder));
        if (photoSubfolder != null) sentences.add(subfolderSentence(false, photoSubfolder));
        if (quietFrom != null) sentences.add(quietHourSentence(quietFrom, true));
        if (quietUntil != null) sentences.add(quietHourSentence(quietUntil, false));
        if (lockAfter != null) sentences.add(lockAfterSentence(lockAfter));
        if (textSize != null) sentences.add(textSizeSentence(textSize));
        if (accent != null) sentences.add(accentSentence(accent));
        return sentences;
    }

    private static void answered(HushfacebookPreferenceFragment page) {
        page.pendingImport = null;
        page.importPreview = null;
    }

    private static void apply(HushfacebookPreferenceFragment page, @Nullable Bundle chosen) {
        SettingsBackup.Snapshot snapshot = SettingsBackup.Snapshot.fromBundle(chosen);
        if (snapshot == null) return;
        Run run = start(IMPORT, L10n.t("Importing settings"));
        if (run == null) return;
        // The page shows what the store now holds rather than reading its own switches back into it.
        Map<Setting<?>, Object> before = new LinkedHashMap<>();
        boolean accepted = false;
        try {
            before.putAll(AbstractPreferenceFragment.beginSettingsImport(snapshot.changes().keySet()));
            // Counted before the write, which makes every change match the store.
            String done = importedMessage(snapshot.switchChanges(), snapshot.folderChange(), snapshot.qualityChange(),
                    snapshot.fileNameChange(), snapshot.startChange(), snapshot.orderChange(), snapshot.hiddenChange(),
                    snapshot.keptChange(), snapshot.playbackChange(), snapshot.actionChange(), snapshot.appChange(),
                    snapshot.toChange(), snapshot.subtabChange(), snapshot.sourcesChange(), snapshot.photoNameChange(),
                    snapshot.videoSubfolderChange(), snapshot.photoSubfolderChange(), snapshot.reelsQualityChange(),
                    snapshot.storiesQualityChange(), snapshot.quietFromChange(), snapshot.quietUntilChange(),
                    snapshot.lockAfterChange(), snapshot.textSizeChange(), snapshot.accentChange())
                    + (snapshot.ceilingChange() == null ? "" : " " + ceilingSentence(snapshot.ceilingChange()))
                    + (snapshot.shareItemsChange() == null ? "" : " " + shareItemsSentence(snapshot.shareItemsChange()))
                    + (snapshot.menuTilesChange() == null ? "" : " " + menuTilesSentence(snapshot.menuTilesChange()))
                    + (snapshot.feedTypesChange() == null ? "" : " " + feedTypesSentence(snapshot.feedTypesChange()))
                    + (snapshot.tabOrderChange() == null ? "" : " " + tabOrderSentence(snapshot.tabOrderChange()))
                    + (snapshot.homeFeedChange() == null ? "" : " " + homeFeedSentence(snapshot.homeFeedChange()))
                    + (snapshot.reelsOrderChange() == null ? "" : " " + reelsOrderSentence(snapshot.reelsOrderChange()))
                    + (snapshot.burnInChange() == null ? "" : " " + burnInSentence(snapshot.burnInChange()))
                    + (snapshot.burnInWaitChange() == null ? "" : " " + burnInWaitSentence(snapshot.burnInWaitChange()))
                    + (snapshot.holdReactionChange() == null ? "" : " " + holdReactionSentence(snapshot.holdReactionChange()))
                    + (snapshot.grayOverChange() == null ? "" : " " + grayOverSentence(snapshot.grayOverChange()))
                    + (snapshot.newRowMarkChange() == null ? "" : " " + newRowMarkSentence(snapshot.newRowMarkChange()))
                    + (snapshot.dateStyleChange() == null ? "" : " " + dateStyleSentence(snapshot.dateStyleChange()))
                    + (snapshot.commentsHeightChange() == null ? "" : " " + commentsHeightSentence(snapshot.commentsHeightChange()))
                    + (snapshot.backgroundChange() == null ? "" : " " + backgroundSentence(snapshot.backgroundChange()))
                    + (snapshot.lightBackgroundChange() == null ? "" : " " + lightBackgroundSentence(snapshot.lightBackgroundChange()))
                    + (snapshot.badgeChange() == null ? "" : " " + markColorSentence(Mark.BADGE, snapshot.badgeChange()))
                    + (snapshot.logoChange() == null ? "" : " " + markColorSentence(Mark.LOGO, snapshot.logoChange()))
                    + (snapshot.tabChange() == null ? "" : " " + markColorSentence(Mark.TAB, snapshot.tabChange()))
                    + (snapshot.headerTextChange() == null ? "" : " " + headerTextSentence(snapshot.headerTextChange()))
                    + (snapshot.logoEmojiChange() == null ? "" : " " + logoEmojiSentence(snapshot.logoEmojiChange()))
                    + (snapshot.accentCustomChange() == null ? "" : " " + accentCustomSentence(snapshot.accentCustomChange()))
                    + (snapshot.pauseLength == null || !snapshot.changes().containsKey(SettingsBackup.PAUSE_LENGTH) ? ""
                            : " " + pauseLengthSentence(snapshot.pauseLength))
                    + (snapshot.breakAfter == null || !snapshot.changes().containsKey(SettingsBackup.BREAK_AFTER) ? ""
                            : " " + breakAfterSentence(snapshot.breakAfter))
                    + (snapshot.breakLength == null || !snapshot.changes().containsKey(SettingsBackup.BREAK_LENGTH) ? ""
                            : " " + breakLengthSentence(snapshot.breakLength))
                    + (snapshot.watchedKeep == null || !snapshot.changes().containsKey(SettingsBackup.WATCHED_KEEP) ? ""
                            : " " + watchedKeepSentence(snapshot.watchedKeep));
            accepted =Utils.runOnBackgroundThread(() -> {
                try {
                    SettingsBackup.apply(snapshot);
                    Utils.showToastLong(done);
                } catch (SettingsBackup.ApplyFailed failure) {
                    Logger.printInfo(() -> "Settings import failed: " + failure.getMessage()
                            + (failure.rolledBack ? ", rolled back" : ", not rolled back"));
                    Utils.showToastLong(failure.rolledBack
                            ? L10n.t("Couldn't import the settings. Nothing was changed.")
                            : L10n.t("Couldn't import the settings, and couldn't put back the ones you had. "
                                    + "Check the switches on this screen."));
                } finally {
                    Utils.runOnMainThread(() -> {
                        HushfacebookPreferenceFragment current = latestPage.get();
                        try {
                            AbstractPreferenceFragment.finishSettingsImport(before, current);
                        } finally {
                            finish(run);
                            if (current != null && current.isAdded()) current.refreshSwitches();
                        }
                    });
                }
            });
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Settings import didn't start: " + error.getClass().getSimpleName());
        } finally {
            // This path has no wait that runs out, so nothing else would give the rows back.
            if (!accepted) {
                try {
                    AbstractPreferenceFragment.finishSettingsImport(before, page);
                } finally {
                    notStarted(run);
                }
            }
        }
    }

    /** The toast after an import that changed no start tab. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName) {
        return importedMessage(switches, folder, quality, fileName, null);
    }

    /** The toast after an import that changed no comment order. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start) {
        return importedMessage(switches, folder, quality, fileName, start, null);
    }

    /** The toast after an import that changed no word list. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order) {
        return importedMessage(switches, folder, quality, fileName, start, order, null, null);
    }

    /** The toast after an import that changed no playback quality. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, null);
    }

    /** The toast after an import that changed no download action or app. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, null, null);
    }

    /** The toast after an import that changed no top folder. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                null);
    }

    /** The toast after an import that changed no Feeds filter. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, null);
    }

    /** What the toast after an import says, the people, Pages and sites list aside. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, null);
    }

    /** What the toast after an import says, the photo file name aside. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, sources, null);
    }

    /** What the toast after an import says, the subfolders and the reels and video stories qualities aside. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, sources, photoName, null, null, null, null);
    }

    /**
     * What the toast after an import says: how many switches changed, then a sentence for each
     * other setting that did. A folder alone keeps the one sentence it always had.
     */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                                  @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                  @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, null, null);
    }

    /** {@link #importedMessage} with the hours notification quiet hours start and end. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                                  @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                  @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                  @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                quietUntil, null);
    }

    /** {@link #importedMessage}, with how long the app lock waits after the import. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                                  @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                  @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                  @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil,
                                  @Nullable AppLock.After lockAfter) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                quietUntil, lockAfter, null);
    }

    /** {@link #importedMessage}, with how large Facebook's text is after the import. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                                  @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                  @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                  @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil,
                                  @Nullable AppLock.After lockAfter, @Nullable TextSize.Scale textSize) {
        return importedMessage(switches, folder, quality, fileName, start, order, hidden, kept, playback, action, app,
                to, subtab, sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                quietUntil, lockAfter, textSize, null);
    }

    /** {@link #importedMessage}, with the accent color after the import. */
    static String importedMessage(int switches, @Nullable String folder, @Nullable DownloadQuality quality,
                                  @Nullable String fileName, @Nullable StartTab start, @Nullable CommentOrder order,
                                  @Nullable String hidden, @Nullable String kept, @Nullable PlaybackQuality playback,
                                  @Nullable SendLink.Action action, @Nullable String app, @Nullable SaveTo to,
                                  @Nullable FeedsSubtab subtab, @Nullable String sources, @Nullable String photoName,
                                  @Nullable String videoSubfolder, @Nullable String photoSubfolder,
                                  @Nullable SurfaceQuality reelsQuality, @Nullable SurfaceQuality storiesQuality,
                                  @Nullable QuietHour quietFrom, @Nullable QuietHour quietUntil,
                                  @Nullable AppLock.After lockAfter, @Nullable TextSize.Scale textSize,
                                  @Nullable AccentColor.Preset accent) {
        if (switches == 0 && folder != null && quality == null && fileName == null && start == null && order == null
                && hidden == null && kept == null && playback == null && action == null && app == null && to == null
                && subtab == null && sources == null && photoName == null && videoSubfolder == null
                && photoSubfolder == null && reelsQuality == null && storiesQuality == null && quietFrom == null
                && quietUntil == null && lockAfter == null && textSize == null && accent == null) {
            return L10n.f("Settings imported. Saves will go to a folder named %1$s.", L10n.isolate(folder));
        }
        List<String> parts = new ArrayList<>();
        parts.add(switches == 0 ? L10n.t("Settings imported.") : L10n.quantity(switches,
                "Settings imported. %1$d switch changed.", "Settings imported. %1$d switches changed.", switches));
        parts.addAll(valueSentences(folder, quality, fileName, start, order, hidden, kept, playback, action, app, to,
                subtab, sources, photoName, videoSubfolder, photoSubfolder, reelsQuality, storiesQuality, quietFrom,
                quietUntil, lockAfter, textSize, accent));
        return String.join(" ", parts);
    }

    @Nullable
    private static Context appContext(HushfacebookPreferenceFragment page) {
        Activity activity = page.getActivity();
        return activity != null ? activity.getApplicationContext() : Utils.getContext();
    }

    /** Claims the rows for one run, or says why not. */
    @Nullable
    private static Run start(int action, String line) {
        if (stillStalled()) return null;
        Run run = new Run();
        if (!OWNER.compareAndSet(null, run)) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
            return null;
        }
        runningAction = action;
        runningLine = line;
        setRowsBusy(action, line);
        return run;
    }

    /** Gives the rows back, if [run] still holds them. */
    private static boolean finish(Run run) {
        if (!OWNER.compareAndSet(run, null)) return false;
        runningAction = 0;
        runningLine = null;
        setRowsBusy(0, null);
        return true;
    }

    /** The worker queue was full, so nothing ran: the rows come back and the person hears why. */
    private static void notStarted(Run run) {
        run.ended = true;
        finish(run);
        Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
    }

    /**
     * A file run's worker has returned. The rows come back and [result] runs, unless the screen
     * already stopped waiting for it: then it says nothing and offers nothing.
     */
    private static void ended(Run run, @Nullable Runnable result) {
        run.ended = true;
        Utils.runOnMainThread(() -> {
            if (!finish(run)) {
                Logger.printInfo(() -> "Settings file: the app answered after the screen stopped waiting");
                return;
            }
            if (result != null) result.run();
        });
    }

    /**
     * After {@link #timeoutMs} the rows come back if [run] still holds them, the open is cancelled
     * through the file's app and the file is closed. An app can ignore both, so [message] says only
     * that the screen stopped waiting, never that the app stopped. A worker that has already
     * returned has its answer on the way to this thread, so that answer is the one shown.
     */
    private static void watch(Run run, String message) {
        Utils.runOnMainThreadDelayed(() -> {
            if (run.ended || !finish(run)) return;
            if (!run.ended) stalled = run;
            run.cancel.cancel();
            Closeable open = run.open;
            // Closing can wait on the app too, so not on the main thread.
            if (open != null) new Thread(() -> closeQuietly(open), "hushfacebook-settings-file").start();
            Logger.printInfo(() -> "Settings file: stopped waiting on the app holding it");
            Utils.showToastLong(message);
        }, timeoutMs);
    }

    /** Says so, and answers true, while a run the screen stopped waiting for still holds a worker. */
    private static boolean stillStalled() {
        Run held = stalled;
        if (held == null || held.ended) return false;
        Utils.showToastLong(L10n.t("The app holding the last settings file still hasn't answered. Try again later."));
        return true;
    }

    private static void closeQuietly(@Nullable Closeable open) {
        if (open == null) return;
        try {
            open.close();
        } catch (IOException | RuntimeException ignored) {
            // Nothing more to do with a file that won't close.
        }
    }

    /**
     * Takes both rows out of reach while one of them runs and puts the running line on the one
     * acting. With a zero action and no line, they come back with their own summaries.
     */
    static void setRowsBusy(int action, @Nullable String line) {
        for (WeakReference<SettingsBackupPreference> held : ROWS) {
            SettingsBackupPreference row = held.get();
            if (row == null) {
                ROWS.remove(held);
                continue;
            }
            if (line == null) row.showResting();
            else row.showBusy(action, line);
        }
    }

    private void showBusy(int action, @Nullable String line) {
        busyLine = rowAction == action ? line : null;
        setEnabled(false);
        if (busyLine != null) setSummary(busyLine);
    }

    private void showResting() {
        busyLine = null;
        setEnabled(true);
        setSummary(restingSummary);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        // A screen reader hears the row as unavailable, and this says why. Below Android 11 the
        // summary has to.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.setStateDescription(busyLine);
    }
}
