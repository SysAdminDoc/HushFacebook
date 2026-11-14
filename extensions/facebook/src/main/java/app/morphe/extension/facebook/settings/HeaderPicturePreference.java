/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.preference.Preference;
import android.preference.PreferenceGroup;
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

import app.morphe.extension.facebook.misc.HeaderPicture;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ImmediateAction;

/**
 * Home header picture and Remove the header picture, the two rows after Home header text (#230).
 *
 * <p>Home header picture opens Android's picker for a picture, the way Font file opens it for a
 * font: the answer reaches the page by the fragment's name even when Android rebuilt it behind the
 * picker, and the picture is shrunk and copied into Facebook's own files on a worker
 * ({@link HeaderPicture#copy}). The setting names it only once the copy is written.
 *
 * <p>The app holding the picture answers the open and every read, and it can stall. The screen waits
 * {@link #timeoutMs} at most, then gives the rows back, cancels through that app and closes the
 * file. A copy the screen stopped waiting for can't name the picture or take the old copy's place,
 * and until it has ended no other copy starts.
 *
 * <p>Use the Hushfacebook logo (#467) copies the logo the settings patch ships with the app in the
 * same way, so the top of Home shows it the way it shows a picked picture. It acts on the tap, and
 * it's on the page only when the logo is in the app.
 *
 * <p>Remove the header picture takes the copy away, so the words or Facebook's logo come back after
 * a restart. It acts on the tap, so it goes without a chevron, and it's on the page only while a
 * picture is picked or a copy is left to remove.
 */
@SuppressWarnings("deprecation") // Framework preferences are what the shared settings page builds on.
public class HeaderPicturePreference extends Preference implements ImmediateAction {
    /** The picker's request code. Import, Export and Font file use 7311 to 7313. */
    static final int PICK_PICTURE = 7314;

    /** The row that opens the picker. */
    static final int CHOOSE = 1;
    /** The row that takes the picture away. */
    static final int REMOVE = 2;
    /** The row that puts Hushfacebook's logo there. */
    static final int LOGO = 3;

    static final String CHOOSE_KEY = "action_choose_header_picture";
    static final String REMOVE_KEY = "action_remove_header_picture";
    static final String LOGO_KEY = "action_use_hushfacebook_logo";

    /** The logo's resource id in place of the one the settings patch adds, for a test. */
    @Nullable
    static volatile Integer logoForTests;

    /** How long the app holding a picked picture gets to name, open and read it before the rows come back. A test sets its own. */
    static volatile long timeoutMs = 30_000L;

    /** Wraps a picked file's stream, for a test whose app stalls partway through a read. */
    @Nullable
    static volatile UnaryOperator<InputStream> streamForTests;

    /** The copy or removal that holds the rows, or null. Only it may say how it went. */
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
        /** The worker has returned, or never ran. */
        volatile boolean ended;
        /** The screen stopped waiting. Guarded by this run. */
        private boolean abandoned;
        /** The copy is written and went on to name the picture. Guarded by this run. */
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

        /** Whether the copy may name the picture and move in. After yes the screen waits for it. */
        synchronized boolean commit() {
            if (abandoned) return false;
            committing = true;
            return true;
        }

        /** Whether the screen may stop waiting. After yes the copy can't name the picture. */
        synchronized boolean abandon() {
            if (committing || ended) return false;
            abandoned = true;
            return true;
        }

        synchronized void end() {
            ended = true;
        }
    }

    /** The line Home header picture shows while a copy runs, or null. */
    @Nullable
    private static volatile String runningLine;

    /** The rows on the page right now, so a run can take them all out of reach and bring them back. */
    private static final List<WeakReference<HeaderPicturePreference>> ROWS = new CopyOnWriteArrayList<>();

    private final int role;

    /** On Home header picture, the Remove row it keeps beside itself while there's a picture to remove. */
    @Nullable
    HeaderPicturePreference wayBack;

    HeaderPicturePreference(HushfacebookPreferenceFragment page, Context context, int role) {
        super(context);
        this.role = role;
        setKey(role == CHOOSE ? CHOOSE_KEY : role == LOGO ? LOGO_KEY : REMOVE_KEY);
        setPersistent(false);
        setTitle(role == CHOOSE ? L10n.t("Home header picture")
                : role == LOGO ? L10n.t("Use the Hushfacebook logo") : L10n.t("Remove the header picture"));
        setOnPreferenceClickListener(preference -> {
            // Rows are out of reach while a run is going, so this is only the race between a tap
            // and that.
            if (OWNER.get() == null) {
                if (role == REMOVE) remove(page);
                else if (role == LOGO) copyIn(page, LOGO_SOURCE);
                else if (!stillStalled()) pick(page);
            }
            return true;
        });
        ROWS.add(new WeakReference<>(this));
        show();
    }

    @Override
    public boolean actsOnTap() {
        return role != CHOOSE;
    }

    /** The row's line and whether a tap reaches it. Home header picture also shows or hides Remove. */
    void show() {
        String source = Settings.HEADER_PICTURE.savedValue();
        String line = runningLine;
        boolean running = OWNER.get() != null;
        boolean copy = copyExists();
        // A copy no name points at, one a removal couldn't delete, still has a way out.
        boolean wayBack = !source.isEmpty() || copy;
        if (role == CHOOSE) {
            setEnabled(!running);
            setSummary(line != null ? line : chosenSummary(source, copy));
            showWayBack(wayBack);
        } else if (role == LOGO) {
            setEnabled(!running);
            setSummary(L10n.t("Shows Hushfacebook's logo at the top of Home in place of your words and Facebook's "
                    + "logo. Restart Facebook to see it."));
        } else {
            setEnabled(!running && wayBack);
            setSummary(L10n.t("Puts your words or Facebook's logo back at the top of Home. Restart Facebook to see "
                    + "the change."));
        }
    }

    /** Puts Remove the header picture in this row's group when [wanted], after it, or takes it out. */
    private void showWayBack(boolean wanted) {
        HeaderPicturePreference row = wayBack;
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

    /** What Home header picture says for the saved name [source]. */
    static String chosenSummary(String source, boolean copyExists) {
        if (source.isEmpty()) {
            return L10n.t("No picture chosen. Choose one to show at the top of Home instead of Facebook's logo.");
        }
        if (!copyExists) {
            return L10n.f("The saved copy of %1$s is missing. Choose the picture again.", L10n.isolate(source));
        }
        return L10n.f("Selected %1$s. It shows at the top of Home in place of your words and Facebook's logo.",
                L10n.isolate(source));
    }

    private static boolean copyExists() {
        Context context = Utils.getContext();
        return context != null && HeaderPicture.file(context).isFile();
    }

    private static void pick(HushfacebookPreferenceFragment page) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            page.startActivityForResult(intent, PICK_PICTURE);
        } catch (ActivityNotFoundException missing) {
            Logger.printInfo(() -> "No file picker for the header picture");
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
        if (request != PICK_PICTURE) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri != null) copyIn(page, picked(uri)); // Cancelled: nothing to do and nothing to say.
        return true;
    }

    /** Where a copy's picture comes from. */
    private interface Source {
        /** The name the setting saves and the message says. */
        String name(Context context, Run run);

        /** The picture's bytes, held by [run] where the screen can close them. */
        InputStream open(Context context, Run run) throws HeaderPicture.Refused;
    }

    /** The picture the picker answered with, named and read through the app holding it. */
    private static Source picked(Uri uri) {
        return new Source() {
            @Override
            public String name(Context context, Run run) {
                return FontFilePreference.displayName(context, uri, run.cancel, "picture");
            }

            @Override
            public InputStream open(Context context, Run run) throws HeaderPicture.Refused {
                return HeaderPicturePreference.open(context, uri, run);
            }
        };
    }

    /** Hushfacebook's logo, read from the app's own resources. */
    private static final Source LOGO_SOURCE = new Source() {
        @Override
        public String name(Context context, Run run) {
            // What Home header picture names it from then on.
            return L10n.t("Hushfacebook logo");
        }

        @Override
        public InputStream open(Context context, Run run) throws HeaderPicture.Refused {
            try {
                int id = logoId(context);
                if (id == 0) throw new Resources.NotFoundException(SettingsHeader.LOGO);
                return run.hold(context.getResources().openRawResource(id));
            } catch (IOException | RuntimeException error) {
                closeQuietly(run.open);
                throw new HeaderPicture.Refused(HeaderPicture.Refusal.UNREADABLE, error.getClass().getSimpleName());
            }
        }
    };

    /** The logo the settings patch adds, or 0 when this app has none. */
    static int logoId(Context context) {
        Integer forTests = logoForTests;
        if (forTests != null) return forTests;
        try {
            return context.getResources().getIdentifier(SettingsHeader.LOGO, "drawable", context.getPackageName());
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Could not look up the settings logo: " + error.getClass().getSimpleName());
            return 0;
        }
    }

    private static void copyIn(HushfacebookPreferenceFragment page, Source source) {
        Context context = appContext(page);
        if (context == null || stillStalled()) return;
        Run run = start(L10n.t("Copying the picture"));
        if (run == null) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            Runnable result = null;
            try {
                String name = source.name(context, run);
                String before = Settings.HEADER_PICTURE.savedValue();
                // The name is saved before the copy moves in and put back if it can't, so the row,
                // the message and the copy never disagree about which picture is selected.
                HeaderPicture.copy(source.open(context, run), HeaderPicture.file(context), new HeaderPicture.Choice() {
                    @Override
                    public boolean save() {
                        return run.commit() && Settings.HEADER_PICTURE.save(name);
                    }

                    @Override
                    public void undo() {
                        Settings.HEADER_PICTURE.save(before);
                    }
                });
                pictureChanged();
                String said = L10n.f("Selected %1$s. Restart Facebook to see it at the top of Home.", L10n.isolate(name));
                result = () -> Utils.showToastLong(said);
            } catch (HeaderPicture.Refused refused) {
                Logger.printInfo(() -> "Header picture refused: " + refused.reason);
                String said = refusal(refused.reason);
                result = () -> Utils.showToastLong(said);
            } finally {
                ended(run, result);
            }
        });
        if (!accepted) notStarted(run);
        else watch(run);
    }

    /** The picked file's bytes, opened through its app with [run]'s cancel signal and held where the screen can close them. */
    private static InputStream open(Context context, Uri uri, Run run) throws HeaderPicture.Refused {
        try {
            if (run.cancel.isCanceled()) throw new OperationCanceledException();
            InputStream stream = run.hold(context.getContentResolver().openAssetFileDescriptor(uri, "r", run.cancel))
                    .createInputStream();
            UnaryOperator<InputStream> wrap = streamForTests;
            return run.hold(wrap == null ? stream : wrap.apply(stream));
        } catch (IOException | RuntimeException error) {
            closeQuietly(run.open);
            // The class only: a provider's message can carry the document's name or address.
            throw new HeaderPicture.Refused(HeaderPicture.Refusal.UNREADABLE, error.getClass().getSimpleName());
        }
    }

    /** One sentence per refusal. */
    static String refusal(HeaderPicture.Refusal reason) {
        switch (reason) {
            case NOT_A_PICTURE:
                return L10n.t("That isn't a picture Android can open. The top of Home didn't change.");
            case TOO_LARGE:
                return L10n.f("That picture is over %1$d MB. The top of Home didn't change.", HeaderPicture.MAX_MEGABYTES);
            case NOT_SAVED:
                return L10n.t("Couldn't save a copy of that picture. Check that the phone has room, then try again.");
            default:
                return L10n.t("Couldn't open that file. The top of Home didn't change.");
        }
    }

    /**
     * Takes the copy away and saves no picture as the choice, on a worker: the file is removed only
     * after the setting stops naming it.
     */
    private static void remove(HushfacebookPreferenceFragment page) {
        Context context = appContext(page);
        if (context == null) return;
        Run run = start(null);
        if (run == null) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            String said = null;
            try {
                if (Settings.HEADER_PICTURE.save("")) {
                    pictureChanged();
                    File copy = HeaderPicture.file(context);
                    if (copy.exists() && !copy.delete()) {
                        Logger.printInfo(() -> "The header picture copy couldn't be removed");
                    }
                    said = L10n.t("Header picture removed. Restart Facebook to see the change.");
                } else {
                    said = L10n.t("Couldn't remove the header picture. Try again.");
                }
            } finally {
                String result = said;
                ended(run, result == null ? null : () -> Utils.showToastLong(result));
            }
        });
        if (!accepted) notStarted(run);
    }

    /** A new picture can keep the old one's name, so the copy, not a setting comparison, owes a restart. */
    private static void pictureChanged() {
        HeaderPicture.forget();
        // The page's restart row follows visible row keys. HEADER_PICTURE has no row of its own.
        HushfacebookPreferenceFragment.restartPending.add(CHOOSE_KEY);
    }

    @Nullable
    private static Context appContext(HushfacebookPreferenceFragment page) {
        Activity activity = page.getActivity();
        return activity != null ? activity.getApplicationContext() : Utils.getContext();
    }

    /** Claims the rows for one run, or says why not. */
    @Nullable
    private static Run start(@Nullable String line) {
        Run run = new Run();
        if (!OWNER.compareAndSet(null, run)) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
            return null;
        }
        runningLine = line;
        showAll();
        return run;
    }

    /** Gives the rows back, if [run] still holds them. */
    private static boolean finish(Run run) {
        if (!OWNER.compareAndSet(run, null)) return false;
        runningLine = null;
        showAll();
        return true;
    }

    /** The worker queue was full, so nothing ran: the rows come back and the person hears why. */
    private static void notStarted(Run run) {
        run.end();
        finish(run);
        Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
    }

    /** A run's worker has returned. The rows come back and [result] runs, unless the screen already stopped waiting. */
    private static void ended(Run run, @Nullable Runnable result) {
        run.end();
        Utils.runOnMainThread(() -> {
            if (!finish(run)) {
                Logger.printInfo(() -> "Header picture: the app answered after the screen stopped waiting");
                return;
            }
            if (result != null) result.run();
        });
    }

    /**
     * After {@link #timeoutMs} the rows come back if [run] still holds them and hasn't gone on to
     * name the picture, the open is cancelled through the file's app and the file is closed.
     */
    private static void watch(Run run) {
        Utils.runOnMainThreadDelayed(() -> {
            if (OWNER.get() != run || !run.abandon()) return;
            finish(run);
            if (!run.ended) stalled = run;
            run.cancel.cancel();
            Closeable open = run.open;
            // Closing can wait on the app too, so not on the main thread.
            if (open != null) new Thread(() -> closeQuietly(open), "hushfacebook-header-picture").start();
            Logger.printInfo(() -> "Header picture: stopped waiting on the app holding it");
            Utils.showToastLong(L10n.t("The app holding that picture is taking too long, so Hushfacebook stopped "
                    + "waiting. The top of Home didn't change."));
        }, timeoutMs);
    }

    /** Says so, and answers true, while a copy the screen stopped waiting for still holds a worker. */
    private static boolean stillStalled() {
        Run held = stalled;
        if (held == null || held.ended) return false;
        Utils.showToastLong(L10n.t("The app holding the last picture still hasn't answered. Try again later."));
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
        for (WeakReference<HeaderPicturePreference> held : ROWS) {
            HeaderPicturePreference row = held.get();
            if (row == null) {
                ROWS.remove(held);
                continue;
            }
            row.show();
        }
    }

    @Override
    protected void onBindView(View view) {
        show();
        super.onBindView(view);
        // A screen reader hears Home header picture as unavailable during a copy, and this says why.
        // Below Android 11 it reads the summary, which says it too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.setStateDescription(role == CHOOSE ? runningLine : null);
    }
}
