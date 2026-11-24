/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.accounts;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * One form for both directions: the account as text, with a file and the clipboard on either side.
 *
 * <p>FacebookAMD puts export and import in one dialog, and so does this: the box holds what the
 * phone is signed in with, already written out. Copy takes it elsewhere, Save writes whatever the
 * box holds, and the two file buttons move the same text to and from a file. A person pasting an
 * account from another phone drops it in the box and taps Save; a person moving one out taps Copy
 * or Export to file. Nothing is written until Save.
 *
 * <p>The same form is on Facebook's login screen, where it is the only way in for a phone with no
 * account signed in, and in the settings page. The button there is added to the window once and can
 * be dragged off whatever it covers; the place is kept as a fraction of the window.
 */
public final class LoginImport {
    /** The tag the button carries, so attaching twice leaves one. */
    private static final String TAG = "hushfacebook_login_import";

    /** Where a file to import is looked for, beside the clipboard. */
    private static final String FILE_FOLDER = "Hushfacebook";

    /** Set once the application has been watched, so the callbacks are registered once. */
    private static volatile boolean watching;

    private LoginImport() {
    }

    /**
     * Watches every screen Facebook opens, from the application's onCreate, and adds the button to
     * the ones that start a login.
     */
    public static void start(Context context) {
        if (watching || !(context instanceof Application)) return;
        watching = true;
        try {
            ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, android.os.Bundle saved) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    consider(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    consider(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, android.os.Bundle out) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
        } catch (RuntimeException failed) {
            Logger.printException(() -> "The login screens weren't watched", failed);
        }
    }

    /** Adds the button to a screen that starts a login, and leaves every other screen alone. */
    private static void consider(Activity activity) {
        if (activity == null) return;
        String name = activity.getClass().getName();
        if (name.contains("SimpleLoginActivity") || name.endsWith("LoginActivity") || name.contains("LoggedOut")) {
            attach(activity);
        }
    }

    /**
     * Adds the Import or export button over [activity]'s window, or raises the one already there.
     * Called from Facebook's login screens as they resume, on the main thread.
     */
    public static void attach(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (decor == null) return;
            View already = decor.findViewWithTag(TAG);
            if (already != null) {
                if (decor.indexOfChild(already) != decor.getChildCount() - 1) already.bringToFront();
                return;
            }

            Button button = new Button(activity);
            button.setTag(TAG);
            button.setText(L10n.t("Import or export"));
            button.setAllCaps(false);
            button.setTextColor(Color.WHITE);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(24, 119, 242));
            background.setCornerRadius(48f);
            button.setBackground(background);
            button.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
            button.setElevation(dp(activity, 6));
            button.setOnClickListener(tapped -> showForm(activity));

            float[] place = placement(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            params.leftMargin = Math.round(place[0] * activity.getResources().getDisplayMetrics().widthPixels);
            params.topMargin = Math.round(place[1] * activity.getResources().getDisplayMetrics().heightPixels);
            drag(activity, button, params);
            decor.addView(button, params);

            decor.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int last = decor.getChildCount() - 1;
                if (last >= 0 && decor.getChildAt(last) != button) button.bringToFront();
            });
        } catch (RuntimeException failed) {
            Logger.printException(() -> "The import button wasn't added", failed);
        }
    }

    /** The form: the account as text, a file on either side, and Save for the way in. */
    public static void showForm(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        column.setPadding(padding, padding, padding, padding);

        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setMinLines(6);
        field.setText(prefill(context));
        // A fixed window with its own scrolling: the text can be dozens of lines, and an EditText
        // that grows with it pushed the buttons off the screen.
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(field);
        column.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 260)));

        LinearLayout files = row(context);
        files.addView(action(context, L10n.t("Export to file"), view -> exportToFile(context, field.getText().toString())));
        files.addView(action(context, L10n.t("Import from file"), view -> pickFile(context, field)));
        column.addView(files);

        // The dialog is made before its buttons, so Cancel and Save can close it.
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t("Import or export"))
                .setView(column)
                .create();

        LinearLayout actions = row(context);
        actions.addView(action(context, L10n.t("Copy"), view -> {
            Utils.setClipboard(field.getText());
            Toast.makeText(context, L10n.t("Copied."), Toast.LENGTH_SHORT).show();
        }));
        actions.addView(action(context, L10n.t("Cancel"), view -> dialog.dismiss()));
        actions.addView(action(context, L10n.t("Save"), view -> {
            apply(context, field.getText().toString());
            dialog.dismiss();
        }));
        column.addView(actions);

        dialog.show();
    }

    /** What the box starts with: the accounts this phone is signed in with, or the stores found. */
    private static String prefill(Context context) {
        List<AccountShare.Account> accounts = java.util.Collections.emptyList();
        try {
            accounts = AccountSession.captureAll(context);
        } catch (AccountShare.Refused signedOut) {
            // No account yet: the box still carries the store report, which a phone with no root
            // can only hand back by saving this form to a file.
            Logger.printInfo(() -> "Nothing is signed in, so the form carries the store report only");
        } catch (RuntimeException failed) {
            Logger.printException(() -> "The accounts weren't read for the form", failed);
        }
        return AccountShare.json(accounts, AccountSession.storeReport(context));
    }

    /** Writes [text] where the person picks, through Android's own save dialog. */
    private static void exportToFile(Context context, String text) {
        if (text.trim().isEmpty()) {
            Toast.makeText(context, L10n.t("There's nothing to save."), Toast.LENGTH_SHORT).show();
            return;
        }
        Activity activity = activityOf(context);
        if (activity == null) {
            Toast.makeText(context, L10n.t("That file couldn't be written."), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "hushface-account-" + System.currentTimeMillis() + ".json");
        try {
            new Picker().begin(activity, intent, uri -> write(activity, uri, text));
        } catch (RuntimeException failed) {
            Logger.printException(() -> "The save dialog didn't open", failed);
            Toast.makeText(activity, L10n.t("That file couldn't be written."), Toast.LENGTH_SHORT).show();
        }
    }

    /** Writes [text] into the file the person saved, and says where it went. */
    private static void write(Activity activity, Uri uri, String text) {
        try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("No stream");
            out.write(text.getBytes(StandardCharsets.UTF_8));
            String name = uri.getLastPathSegment();
            Toast.makeText(activity, L10n.t("Account saved.")
                    + (name == null ? "" : "\n" + name), Toast.LENGTH_LONG).show();
        } catch (Exception failed) {
            Logger.printInfo(() -> "The account file wasn't written: " + failed.getClass().getSimpleName());
            Toast.makeText(activity, L10n.t("That file couldn't be written."), Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the file the person picks, through Android's own open dialog, into [field]. */
    private static void pickFile(Context context, EditText field) {
        Activity activity = activityOf(context);
        if (activity == null) {
            Toast.makeText(context, L10n.t("That file couldn't be read."), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/json", "text/plain", "application/octet-stream"});
        try {
            new Picker().begin(activity, intent, uri -> {
                try {
                    field.setText(read(activity, uri));
                } catch (Exception failed) {
                    Logger.printException(() -> "The account file wasn't read", failed);
                    Toast.makeText(activity, L10n.t("That file couldn't be read."), Toast.LENGTH_LONG).show();
                }
            });
        } catch (RuntimeException failed) {
            Logger.printException(() -> "The open dialog didn't open", failed);
            Toast.makeText(activity, L10n.t("That file couldn't be read."), Toast.LENGTH_SHORT).show();
        }
    }

    /** The text of the file at [uri], at most what an account file may be. */
    private static String read(Activity activity, Uri uri) throws Exception {
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("No stream");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > AccountShare.MAX_TEXT) {
                    throw new IllegalStateException("That file is too large.");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** The activity a context belongs to, or null when the context isn't one. */
    @Nullable
    private static Activity activityOf(Context context) {
        Context cursor = context;
        while (cursor instanceof android.content.ContextWrapper) {
            if (cursor instanceof Activity) return (Activity) cursor;
            cursor = ((android.content.ContextWrapper) cursor).getBaseContext();
        }
        return cursor instanceof Activity ? (Activity) cursor : null;
    }

    /**
     * Android's file dialogs answer the activity that asked, and a fragment added here for the
     * length of one request is how a dialog with no activity of its own asks: it is added, it
     * starts the intent, and it hands the picked address back before removing itself.
     */
    @SuppressWarnings("deprecation") // Framework fragments are what an activity of any kind has.
    public static final class Picker extends android.app.Fragment {
        /** What to do with the address the person picked. */
        public interface Picked {
            void onUri(Uri uri);
        }

        @Nullable
        private Picked picked;

        void begin(Activity activity, Intent intent, Picked callback) {
            picked = callback;
            // Attached before the intent starts: a fragment that isn't attached has no activity to
            // ask, and starting from it threw and took Facebook down with it.
            activity.getFragmentManager().beginTransaction().add(this, "hushface-picker")
                    .commitNowAllowingStateLoss();
            try {
                startActivityForResult(intent, intent.getAction().equals(Intent.ACTION_CREATE_DOCUMENT) ? 1 : 2);
            } catch (RuntimeException failed) {
                Logger.printException(() -> "Android's file dialog didn't open", failed);
                picked = null;
                activity.getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
                Toast.makeText(activity, L10n.t("That file couldn't be written."), Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onCreate(@Nullable android.os.Bundle state) {
            super.onCreate(state);
            setRetainInstance(true);
        }

        @Override
        public void onActivityResult(int request, int result, @Nullable Intent data) {
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            Picked callback = picked;
            picked = null;
            if (uri != null && callback != null) callback.onUri(uri);
            getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    /** Checks and writes [text] off the main thread, then says what happened. */
    private static void apply(Context context, String text) {
        Future<Object> work = Utils.submitOnBackgroundThread((Callable<Object>) () -> {
            try {
                List<AccountShare.Account> accounts = AccountShare.parse(text);
                return new Imported(accounts, AccountSession.restoreAll(context, accounts));
            } catch (AccountShare.Refused refused) {
                return refused;
            } catch (RuntimeException failed) {
                Logger.printException(() -> "The account wasn't imported", failed);
                return failed;
            }
        });
        Utils.runOnMainThread(() -> {
            Object result = get(work);
            if (result instanceof Imported) {
                Imported imported = (Imported) result;
                String line = joined(imported.accounts) + "\n\n"
                        + L10n.t("Facebook has to restart before the account shows.");
                new AlertDialog.Builder(context)
                        .setTitle(L10n.t("Account imported"))
                        .setMessage(line)
                        .setNegativeButton(L10n.t("Later"), null)
                        .setPositiveButton(L10n.t("Restart Facebook"), (dialog, which) -> Utils.restartApp(context))
                        .show();
            } else if (result instanceof AccountShare.Refused) {
                new AlertDialog.Builder(context).setTitle(L10n.t("Nothing was imported"))
                        .setMessage(((AccountShare.Refused) result).getMessage())
                        .setPositiveButton(L10n.t("Close"), null).show();
            } else {
                new AlertDialog.Builder(context).setTitle(L10n.t("Nothing was imported"))
                        .setMessage(L10n.t("That text couldn't be written into this Facebook."))
                        .setPositiveButton(L10n.t("Close"), null).show();
            }
        });
    }

    /** The accounts an import wrote, by id, for the dialog. */
    private static String joined(List<AccountShare.Account> accounts) {
        StringBuilder line = new StringBuilder();
        int shown = 0;
        for (AccountShare.Account account : accounts) {
            if (shown++ == 5) break;
            if (line.length() > 0) line.append(", ");
            line.append(account.uid.isEmpty() ? "-" : account.uid);
        }
        return line.toString();
    }

    /** One import that has finished writing. */
    private static final class Imported {
        final List<AccountShare.Account> accounts;
        final int written;

        Imported(List<AccountShare.Account> accounts, int written) {
            this.accounts = accounts;
            this.written = written;
        }
    }

    /** A row of buttons that share the width evenly. */
    private static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(context, 12), 0, 0);
        return row;
    }

    /** One button of a row, with its weight so the buttons share the width. */
    private static Button action(Context context, String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        button.setLayoutParams(params);
        return button;
    }

    /** Makes the button follow a drag, and keeps the place it was let go in. */
    private static void drag(Activity activity, Button button, FrameLayout.LayoutParams params) {
        int[] from = new int[2];
        int[] to = new int[2];
        boolean[] dragged = new boolean[1];
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    from[0] = params.leftMargin;
                    from[1] = params.topMargin;
                    to[0] = (int) event.getRawX();
                    to[1] = (int) event.getRawY();
                    dragged[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getRawX() - to[0]) > dp(activity, 6)
                            || Math.abs(event.getRawY() - to[1]) > dp(activity, 6)) {
                        dragged[0] = true;
                    }
                    View decor = (View) view.getParent();
                    int width = decor == null ? 0 : decor.getWidth();
                    int height = decor == null ? 0 : decor.getHeight();
                    params.leftMargin = clamp(from[0] + (int) event.getRawX() - to[0], 0, width - view.getWidth());
                    params.topMargin = clamp(from[1] + (int) event.getRawY() - to[1], 0, height - view.getHeight());
                    view.setLayoutParams(params);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    remember(activity, params.leftMargin, params.topMargin);
                    if (!dragged[0]) view.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(Math.max(least, most), value));
    }

    /** The fraction of the window the button was left at, or its first place when it never moved. */
    private static float[] placement(Context context) {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences("hushfacebook_login_import", Context.MODE_PRIVATE);
        return new float[]{preferences.getFloat("x", 0.62f), preferences.getFloat("y", 0.06f)};
    }

    /** Keeps where the button was let go, for the next login screen. */
    private static void remember(Context context, int left, int top) {
        float x = (float) left / Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
        float y = (float) top / Math.max(1, context.getResources().getDisplayMetrics().heightPixels);
        context.getSharedPreferences("hushfacebook_login_import", Context.MODE_PRIVATE).edit()
                .putFloat("x", x).putFloat("y", y).apply();
    }

    /** The clipboard's text, or null when it holds none or isn't text. */
    @Nullable
    private static String clipboard(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        return text == null ? null : text.toString();
    }

    @Nullable
    private static Object get(Future<Object> work) {
        try {
            return work.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failed) {
            Logger.printException(() -> "The account work failed", failed);
            return null;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
