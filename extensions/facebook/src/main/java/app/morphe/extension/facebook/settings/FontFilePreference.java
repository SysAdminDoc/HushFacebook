/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.provider.OpenableColumns;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import app.morphe.extension.facebook.emoji.OwnEmoji;
import app.morphe.extension.facebook.font.FontFile;
import app.morphe.extension.facebook.font.OwnFont;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.ImmediateAction;

/**
 * Font file and Use your phone's font, the two rows under the Use the system font switch.
 *
 * <p>Font file opens Android's file picker for a font. The picker is an activity of its own, so
 * Android may rebuild Facebook's activity behind it, and the settings page with it. As with Import
 * settings, the answer still reaches the page: Android hands it to the fragment the request came
 * from by the name the framework gave it. Nothing about a request is kept on the page, and the
 * file's address comes with the answer. The file is copied into Facebook's own files on a worker
 * ({@link FontFile#copy}), and the setting names it only once the copy has passed its checks.
 *
 * <p>The app holding the file answers the name, the open and every read, and it can stall in any of
 * them. As with Import settings, the screen waits {@link #timeoutMs} at most, then gives the rows
 * back, cancels through that app and closes the file. A copy the screen stopped waiting for can't
 * name the font or take the old copy's place when it ends, and says nothing. Until it has ended no
 * other copy starts, so a stuck app holds one worker at most. Use your phone's font reads no file
 * and still works meanwhile.
 *
 * <p>Use your phone's font takes the copy away. Font changes apply after a restart and only while
 * Use the system font is on. The file is removed when the row is tapped, so it goes without a
 * chevron, and it's on the page only while a file is picked or a copy is left to remove.
 *
 * <p>Emoji font file and Remove emoji font file are the same pair under Use the phone's emoji
 * ({@link Slot#EMOJI}, issue #513). They keep a copy and a setting of their own, so picking one font
 * leaves the other alone, and one run at a time covers both pairs.
 */
@SuppressWarnings("deprecation") // Framework preferences are what the shared settings page builds on.
public class FontFilePreference extends Preference implements ImmediateAction {
    /** The picker's request code. Import and Export settings use 7311 and 7312. */
    static final int PICK_FONT = 7313;
    /** The emoji font picker's request code. The header picture uses 7314. */
    static final int PICK_EMOJI_FONT = 7315;

    /** The row that opens the picker. */
    static final int CHOOSE = 1;
    /** The row that goes back to the phone's font. */
    static final int PHONE_FONT = 2;

    static final String CHOOSE_KEY = "action_choose_font";
    static final String PHONE_FONT_KEY = "action_phone_font";
    static final String CHOOSE_EMOJI_KEY = "action_choose_emoji_font";
    static final String PHONE_EMOJI_KEY = "action_phone_emoji";

    /** Which font a pair of rows looks after. */
    enum Slot {
        TEXT, EMOJI;

        int request() {
            return this == TEXT ? PICK_FONT : PICK_EMOJI_FONT;
        }

        String chooseKey() {
            return this == TEXT ? CHOOSE_KEY : CHOOSE_EMOJI_KEY;
        }

        String wayBackKey() {
            return this == TEXT ? PHONE_FONT_KEY : PHONE_EMOJI_KEY;
        }

        /** The setting naming the picked file. */
        StringSetting source() {
            return this == TEXT ? Settings.FONT_SOURCE : Settings.EMOJI_FONT_SOURCE;
        }

        /** The switch the picked file waits on. */
        BooleanSetting override() {
            return this == TEXT ? Settings.USE_SYSTEM_FONT : Settings.USE_SYSTEM_EMOJI;
        }

        File file(Context context) {
            return this == TEXT ? FontFile.file(context) : FontFile.emojiFile(context);
        }

        void copy(InputStream input, Context context, FontFile.Choice choice) throws FontFile.Refused {
            if (this == TEXT) FontFile.copy(input, file(context), OwnFont::loads, choice);
            else FontFile.copyEmoji(input, file(context), OwnFont::loads, choice);
        }

        /** A replacement can keep the same name, so its bytes, not a setting comparison, owe a restart. */
        void changed() {
            if (this == TEXT) OwnFont.fileChanged();
            else OwnEmoji.fileChanged();
            // The existing page reminder follows visible row keys. The source has no row of its own.
            HushfacebookPreferenceFragment.restartPending.add(chooseKey());
        }
    }

    /**
     * What the picker offers: the names providers give fonts, and plain bytes for a provider that
     * doesn't know a font when it sees one. What a file really is gets checked after it's picked.
     */
    static final String[] OPENABLE_TYPES = {"font/ttf", "font/otf", "font/collection", "font/sfnt",
            "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt",
            "application/vnd.ms-opentype", "application/octet-stream"};

    /** The longest name a row shows for a picked file. */
    static final int MAX_NAME_LENGTH = 80;

    /** How long the app holding a picked file gets to name, open and read it before the rows come back. A test sets its own. */
    static volatile long timeoutMs = 30_000L;

    /** Wraps a picked file's stream, for a test whose app stalls partway through a read. */
    @Nullable
    static volatile UnaryOperator<InputStream> streamForTests;

    /**
     * The copy or removal that holds the rows, or null. Only it may say how it went, so a copy the
     * screen stopped waiting for can't announce anything when it ends.
     */
    private static final AtomicReference<Run> OWNER = new AtomicReference<>();

    /** A copy the screen stopped waiting for. Until its file's app answers it holds a worker, and no other copy starts. */
    @Nullable
    private static volatile Run stalled;

    /** One copy or removal, with what the screen needs to stop waiting for it. */
    private static final class Run {
        /** Reaches the file's app while it names and opens the file, for an app that honours it. */
        final CancellationSignal cancel = new CancellationSignal();
        /** The file or stream the worker has open, closed when the screen stops waiting. */
        @Nullable
        volatile Closeable open;
        /** The worker has returned, or never ran. Set by {@link #end}, under this run's lock. */
        volatile boolean ended;
        /** The screen stopped waiting. Guarded by this run. */
        private boolean abandoned;
        /** The copy passed its checks and went on to name the font. Guarded by this run. */
        private boolean committing;

        /** Keeps [file] where the screen can close it, or closes it now when the screen has stopped waiting. */
        <T extends Closeable> T hold(@Nullable T file) throws IOException {
            if (file == null) throw new FileNotFoundException("No file");
            open = file;
            if (cancel.isCanceled()) {
                file.close();
                throw new OperationCanceledException();
            }
            return file;
        }

        /**
         * Whether the copy may name the font and move in. After yes the screen waits for it: what's
         * left is Facebook's own files, not the app holding the picked one.
         */
        synchronized boolean commit() {
            if (abandoned) return false;
            committing = true;
            return true;
        }

        /**
         * Whether the screen may stop waiting. After yes the copy can't name the font. A worker that
         * has already returned has its answer on the way, so the screen waits for that instead of
         * saying the app took too long.
         */
        synchronized boolean abandon() {
            if (committing || ended) return false;
            abandoned = true;
            return true;
        }

        /** The worker has returned. After this the screen can't stop waiting, so its answer is the one shown. */
        synchronized void end() {
            ended = true;
        }
    }

    /** The line the running copy's row shows, or null. */
    @Nullable
    private static volatile String runningLine;

    /** Whose copy or removal is running, so only that pair's row shows the line. */
    @Nullable
    private static volatile Slot runningSlot;

    /** The rows on the page right now, so a run can take them all out of reach and bring them back. */
    private static final List<WeakReference<FontFilePreference>> ROWS = new CopyOnWriteArrayList<>();

    private final int role;
    private final Slot slot;

    /**
     * On Font file, the Use your phone's font row it keeps beside itself while a font file is
     * picked or a copy of one is left, and takes away when there's neither to go back from.
     */
    @Nullable
    FontFilePreference wayBack;

    FontFilePreference(HushfacebookPreferenceFragment page, Context context, int role) {
        this(page, context, role, Slot.TEXT);
    }

    FontFilePreference(HushfacebookPreferenceFragment page, Context context, int role, Slot slot) {
        super(context);
        this.role = role;
        this.slot = slot;
        setKey(role == CHOOSE ? slot.chooseKey() : slot.wayBackKey());
        setPersistent(false);
        if (slot == Slot.TEXT) setTitle(role == CHOOSE ? L10n.t("Font file") : L10n.t("Use your phone's font"));
        else setTitle(role == CHOOSE ? L10n.t("Emoji font file") : L10n.t("Remove emoji font file"));
        setOnPreferenceClickListener(preference -> {
            // Rows are out of reach while a run is going, so this is only the race between a tap
            // and that.
            if (OWNER.get() == null) {
                if (role == PHONE_FONT) usePhoneFont(page, slot);
                else if (!stillStalled()) pickFont(page, slot);
            }
            return true;
        });
        ROWS.add(new WeakReference<>(this));
        show();
    }

    @Override
    public boolean actsOnTap() {
        return role == PHONE_FONT;
    }

    /**
     * The row's line and whether a tap reaches it, from the saved font and whether a run is going.
     * Font file also puts Use your phone's font beside itself, or takes it away.
     */
    void show() {
        String source = slot.source().savedValue();
        String line = runningSlot == slot ? runningLine : null;
        boolean running = OWNER.get() != null;
        boolean copy = copyExists(slot);
        // A copy no name points at, one a removal couldn't delete, still has a way out.
        boolean wayBack = !source.isEmpty() || copy;
        if (role == CHOOSE) {
            setEnabled(!running);
            setSummary(line != null ? line : chosenSummary(slot, source, copy));
            showWayBack(wayBack);
        } else {
            setEnabled(!running && wayBack);
            setSummary(wayBackSummary());
        }
    }

    private String wayBackSummary() {
        boolean on = slot.override().savedValue();
        if (slot == Slot.EMOJI) {
            return on ? L10n.t("Removes the saved emoji font file. Restart Facebook to use your phone's emoji.")
                    : L10n.t("Removes the saved emoji font file. Use the phone's emoji stays off.");
        }
        return on ? L10n.t("Removes the saved font file. Restart Facebook to use your phone's font.")
                : L10n.t("Removes the saved font file. Use the system font stays off.");
    }

    /** Puts Use your phone's font in Font file's group when [wanted], after it, or takes it out. */
    private void showWayBack(boolean wanted) {
        FontFilePreference row = wayBack;
        PreferenceGroup group = getParent();
        if (row == null || group == null) return;
        boolean shown = row.getParent() == group;
        if (wanted && !shown) {
            row.show();
            group.addPreference(row);
        } else if (!wanted && shown) {
            group.removePreference(row);
        }
    }

    /**
     * The saved choice, rather than a claim about text already drawn. The override can be off,
     * and a font already on screen keeps its typeface until Facebook restarts.
     */
    static String chosenSummary(String source, boolean copyExists) {
        return chosenSummary(Slot.TEXT, source, copyExists);
    }

    /** {@link #chosenSummary(String, boolean)} for [slot]'s font. */
    static String chosenSummary(Slot slot, String source, boolean copyExists) {
        if (source.isEmpty()) {
            return slot == Slot.EMOJI
                    ? L10n.f("No file chosen. Choose a .ttf or .otf emoji font of up to %1$d MB.", FontFile.MAX_EMOJI_MEGABYTES)
                    : L10n.f("No file chosen. Choose a .ttf or .otf font file of up to %1$d MB.", FontFile.MAX_MEGABYTES);
        }
        if (!copyExists) {
            return L10n.f("The saved copy of %1$s is missing. Choose the file again.",
                    L10n.isolate(source));
        }
        if (slot == Slot.EMOJI) {
            return slot.override().savedValue()
                    ? L10n.f("Selected %1$s. Emoji changes apply after restarting Facebook.", L10n.isolate(source))
                    : L10n.f("Selected %1$s. Use the phone's emoji is off. Turn it on and restart Facebook to use this file.",
                            L10n.isolate(source));
        }
        return slot.override().savedValue()
                ? L10n.f("Selected %1$s. Font changes apply after restarting Facebook.", L10n.isolate(source))
                : L10n.f("Selected %1$s. Use the system font is off. Turn it on and restart Facebook to use this file.",
                        L10n.isolate(source));
    }

    private static boolean copyExists(Slot slot) {
        Context context = Utils.getContext();
        return context != null && slot.file(context).isFile();
    }

    private static void pickFont(HushfacebookPreferenceFragment page, Slot slot) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, OPENABLE_TYPES);
        try {
            page.startActivityForResult(intent, slot.request());
        } catch (ActivityNotFoundException missing) {
            Logger.printInfo(() -> "No file picker for the font file");
            Utils.showToastLong(L10n.t("This phone has no file picker, so there's no way to choose a file here."));
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Could not open the file picker: " + error.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("Couldn't open the file picker. Try again."));
        }
    }

    /**
     * A picker's answer, from the page's onActivityResult.
     *
     * @return whether the request was this row's.
     */
    static boolean onResult(HushfacebookPreferenceFragment page, int request, int result, @Nullable Intent data) {
        Slot slot = request == PICK_FONT ? Slot.TEXT : request == PICK_EMOJI_FONT ? Slot.EMOJI : null;
        if (slot == null) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri != null) copyIn(page, uri, slot); // Cancelled: nothing to do and nothing to say.
        return true;
    }

    private static void copyIn(HushfacebookPreferenceFragment page, Uri uri, Slot slot) {
        Context context = appContext(page);
        if (context == null || stillStalled()) return;
        Run run = start(slot == Slot.EMOJI ? L10n.t("Copying the emoji font file") : L10n.t("Copying the font file"), slot);
        if (run == null) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            Runnable result = null;
            try {
                String name = displayName(context, uri, run.cancel);
                String before = slot.source().savedValue();
                // The name is saved before the copy moves in and put back if it can't, so the row,
                // the message and the copy never disagree about which font is selected. A copy the
                // screen stopped waiting for saves no name, so it doesn't move in either.
                slot.copy(open(context, uri, run), context, new FontFile.Choice() {
                    @Override
                    public boolean save() {
                        return run.commit() && slot.source().save(name);
                    }

                    @Override
                    public void undo() {
                        slot.source().save(before);
                    }
                });
                // Whatever was built from the copy that was there before is stale now.
                slot.changed();
                String said = pickedMessage(slot, name);
                result = () -> Utils.showToastLong(said);
            } catch (FontFile.Refused refused) {
                Logger.printInfo(() -> "Font file refused: " + refused.reason);
                String said = refusal(slot, refused.reason);
                result = () -> Utils.showToastLong(said);
            } finally {
                ended(run, result);
            }
        });
        if (!accepted) notStarted(run);
        else watch(run, slot);
    }

    private static String pickedMessage(Slot slot, String name) {
        boolean on = slot.override().savedValue();
        if (slot == Slot.EMOJI) {
            return on ? L10n.f("Selected %1$s. Restart Facebook to apply the emoji change.", L10n.isolate(name))
                    : L10n.f("Selected %1$s. Turn on Use the phone's emoji and restart Facebook to use it.",
                            L10n.isolate(name));
        }
        return on ? L10n.f("Selected %1$s. Restart Facebook to apply the font change.", L10n.isolate(name))
                : L10n.f("Selected %1$s. Turn on Use the system font and restart Facebook to use it.",
                        L10n.isolate(name));
    }

    /** The picked file's bytes, opened through its app with [run]'s cancel signal and held where the screen can close them. */
    private static InputStream open(Context context, Uri uri, Run run) throws FontFile.Refused {
        try {
            // A name the app answered after the screen stopped waiting goes no further.
            if (run.cancel.isCanceled()) throw new OperationCanceledException();
            InputStream stream = run.hold(context.getContentResolver().openAssetFileDescriptor(uri, "r", run.cancel))
                    .createInputStream();
            UnaryOperator<InputStream> wrap = streamForTests;
            // Closing the stream lets a read the app holds go, and closes the file under it.
            return run.hold(wrap == null ? stream : wrap.apply(stream));
        } catch (IOException | RuntimeException error) {
            closeQuietly(run.open);
            // The class only: a provider's message can carry the document's name or address.
            throw new FontFile.Refused(FontFile.Refusal.UNREADABLE, error.getClass().getSimpleName());
        }
    }

    /** One sentence per refusal: a photo picked by mistake and a file too large to take call for different things. */
    static String refusal(FontFile.Refusal reason) {
        return refusal(Slot.TEXT, reason);
    }

    /** {@link #refusal(FontFile.Refusal)} for [slot]'s font. */
    static String refusal(Slot slot, FontFile.Refusal reason) {
        if (slot == Slot.EMOJI) {
            switch (reason) {
                case NOT_A_FONT:
                    return L10n.t("That isn't a .ttf or .otf font file. Your emoji didn't change.");
                case TOO_LARGE:
                    return L10n.f("That font file is over %1$d MB. Your emoji didn't change.", FontFile.MAX_EMOJI_MEGABYTES);
                case WONT_LOAD:
                    return L10n.t("Android couldn't use that font file. Your emoji didn't change.");
                case NO_EMOJI:
                    return L10n.t("That font has no emoji in it. Your emoji didn't change.");
                case NOT_SAVED:
                    return L10n.t("Couldn't save a copy of that font. Check that the phone has room, then try again.");
                default:
                    return L10n.t("Couldn't open that file. Your emoji didn't change.");
            }
        }
        switch (reason) {
            case NOT_A_FONT:
                return L10n.t("That isn't a .ttf or .otf font file. Your font didn't change.");
            case TOO_LARGE:
                return L10n.f("That font file is over %1$d MB. Your font didn't change.", FontFile.MAX_MEGABYTES);
            case WONT_LOAD:
                return L10n.t("Android couldn't use that font file. Your font didn't change.");
            case NOT_SAVED:
                return L10n.t("Couldn't save a copy of that font. Check that the phone has room, then try again.");
            default:
                return L10n.t("Couldn't open that file. Your font didn't change.");
        }
    }

    /**
     * The name the picked file goes by, as the provider gives it, or else the last part of its
     * address. Only the name: the file's path and its provider stay out of the setting.
     */
    static String displayName(Context context, Uri uri, @Nullable CancellationSignal cancel) {
        return displayName(context, uri, cancel, "font");
    }

    /** {@link #displayName(Context, Uri, CancellationSignal)}, with [fallback] when no name is left. */
    static String displayName(Context context, Uri uri, @Nullable CancellationSignal cancel, String fallback) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null, cancel)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException unknown) {
            // A provider that can't say, or one the screen stopped waiting for, leaves the
            // address's own last part.
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return cleanName(name, fallback);
    }

    /**
     * [name] as a row shows it: one line with no control or formatting characters, at most
     * {@link #MAX_NAME_LENGTH} characters, and "font" when nothing is left.
     */
    static String cleanName(@Nullable String name) {
        return cleanName(name, "font");
    }

    /** {@link #cleanName(String)}, with [fallback] when nothing is left. */
    static String cleanName(@Nullable String name, String fallback) {
        if (name == null) return fallback;
        StringBuilder clean = new StringBuilder();
        int kept = 0;
        for (int index = 0; index < name.length() && kept < MAX_NAME_LENGTH; ) {
            int at = name.codePointAt(index);
            index += Character.charCount(at);
            int type = Character.getType(at);
            boolean hidden = type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE;
            if (hidden) continue;
            clean.appendCodePoint(Character.isWhitespace(at) ? ' ' : at);
            kept++;
        }
        String trimmed = clean.toString().trim().replaceAll(" {2,}", " ");
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /**
     * Takes the copy away and saves the phone's font as the choice, on a worker: the file is
     * removed only after the setting stops naming it. It reads no picked file, so it has no wait to
     * run out, and a copy the screen stopped waiting for doesn't keep it from running.
     */
    private static void usePhoneFont(HushfacebookPreferenceFragment page, Slot slot) {
        Context context = appContext(page);
        if (context == null) return;
        Run run = start(null, slot);
        if (run == null) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            String said = null;
            try {
                if (slot.source().save("")) {
                    slot.changed();
                    File copy = slot.file(context);
                    if (copy.exists() && !copy.delete()) {
                        Logger.printInfo(() -> "The font file copy couldn't be removed");
                    }
                    said = slot == Slot.EMOJI
                            ? L10n.t("Emoji font file cleared. Restart Facebook to apply the emoji change.")
                            : L10n.t("Font file cleared. Restart Facebook to apply the font change.");
                } else {
                    said = slot == Slot.EMOJI
                            ? L10n.t("Couldn't go back to your phone's emoji. Try again.")
                            : L10n.t("Couldn't go back to your phone's font. Try again.");
                }
            } finally {
                String result = said;
                ended(run, result == null ? null : () -> Utils.showToastLong(result));
            }
        });
        if (!accepted) notStarted(run);
    }

    @Nullable
    private static Context appContext(HushfacebookPreferenceFragment page) {
        Activity activity = page.getActivity();
        return activity != null ? activity.getApplicationContext() : Utils.getContext();
    }

    /** Claims the rows for one run, or says why not. */
    @Nullable
    private static Run start(@Nullable String line, Slot slot) {
        Run run = new Run();
        if (!OWNER.compareAndSet(null, run)) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
            return null;
        }
        runningSlot = slot;
        runningLine = line;
        showAll();
        return run;
    }

    /** Gives the rows back, if [run] still holds them. */
    private static boolean finish(Run run) {
        if (!OWNER.compareAndSet(run, null)) return false;
        runningLine = null;
        runningSlot = null;
        showAll();
        return true;
    }

    /** The worker queue was full, so nothing ran: the rows come back and the person hears why. */
    private static void notStarted(Run run) {
        run.end();
        finish(run);
        Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
    }

    /**
     * A run's worker has returned. The rows come back and [result] runs, unless the screen already
     * stopped waiting for it: then it says nothing.
     */
    private static void ended(Run run, @Nullable Runnable result) {
        run.end();
        Utils.runOnMainThread(() -> {
            if (!finish(run)) {
                Logger.printInfo(() -> "Font file: the app answered after the screen stopped waiting");
                return;
            }
            if (result != null) result.run();
        });
    }

    /**
     * After {@link #timeoutMs} the rows come back if [run] still holds them and hasn't gone on to
     * name the font, the name query and open are cancelled through the file's app and the file is
     * closed. An app can ignore the cancel, so the message says only that the screen stopped
     * waiting. The font didn't change: the copy can't name it after this.
     */
    private static void watch(Run run, Slot slot) {
        Utils.runOnMainThreadDelayed(() -> {
            if (OWNER.get() != run || !run.abandon()) return;
            finish(run);
            if (!run.ended) stalled = run;
            run.cancel.cancel();
            Closeable open = run.open;
            // Closing can wait on the app too, so not on the main thread.
            if (open != null) new Thread(() -> closeQuietly(open), "hushfacebook-font-file").start();
            Logger.printInfo(() -> "Font file: stopped waiting on the app holding it");
            Utils.showToastLong(slot == Slot.EMOJI
                    ? L10n.t("The app holding that font file is taking too long, so Hushfacebook stopped "
                            + "waiting. Your emoji didn't change.")
                    : L10n.t("The app holding that font file is taking too long, so Hushfacebook stopped "
                            + "waiting. Your font didn't change."));
        }, timeoutMs);
    }

    /** Says so, and answers true, while a copy the screen stopped waiting for still holds a worker. */
    private static boolean stillStalled() {
        Run held = stalled;
        if (held == null || held.ended) return false;
        Utils.showToastLong(L10n.t("The app holding the last font file still hasn't answered. Try again later."));
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

    private static void showAll() {
        for (WeakReference<FontFilePreference> held : ROWS) {
            FontFilePreference row = held.get();
            if (row == null) {
                ROWS.remove(held);
                continue;
            }
            row.show();
        }
    }

    @Override
    protected void onBindView(View view) {
        // The override switch can change without picking a new file or rebuilding this page.
        show();
        super.onBindView(view);
        // A screen reader hears Font file as unavailable during a copy, and this says why. Below
        // Android 11 it reads the summary, which says it too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setStateDescription(role == CHOOSE && runningSlot == slot ? runningLine : null);
        }
    }
}
