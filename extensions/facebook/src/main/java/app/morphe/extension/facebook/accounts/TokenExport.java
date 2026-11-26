/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.accounts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
 * The tokens this phone is signed in with, one row per account, from Hushfacebook's settings.
 *
 * <p>A token is the string another tool asks for, and nothing else here is one: each account gets
 * its own Copy and Check, and Save JSON writes them all to a file as the small object a tool reads.
 * Nothing is written back into Facebook from here.
 *
 * <p>An account is captured the way the app runs it: the token Facebook built this run, from the
 * session behind the native one, with the cookie store and the account files for the accounts
 * Facebook hasn't built a session for yet.
 */
public final class TokenExport {
    private TokenExport() {
    }

    /** The form: every account's token, a Copy and a Check for each, and Save JSON for all of them. */
    public static void showForm(Context context) {
        List<AccountShare.Account> tokens = tokens(context);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        column.setPadding(padding, padding, padding, padding);
        column.addView(body(context, tokens));

        // The dialog is made before its buttons, so Close and Save can close it.
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t("Export token"))
                .setView(column)
                .create();

        LinearLayout actions = row(context);
        actions.addView(action(context, L10n.t("Save JSON"), view -> {
            if (tokens.isEmpty()) {
                Toast.makeText(context, L10n.t("There's nothing to save."), Toast.LENGTH_SHORT).show();
                return;
            }
            exportToFile(context, AccountShare.tokens(tokens));
        }));
        actions.addView(action(context, L10n.t("Close"), view -> dialog.dismiss()));
        column.addView(actions);

        dialog.show();
    }

    /** The accounts this phone is signed in with that carry a token, in the order they were read. */
    private static List<AccountShare.Account> tokens(Context context) {
        List<AccountShare.Account> accounts;
        try {
            accounts = AccountSession.captureAll(context);
        } catch (AccountShare.Refused signedOut) {
            Logger.printInfo(() -> "Nothing is signed in, so the form carries no token");
            return java.util.Collections.emptyList();
        } catch (RuntimeException failed) {
            Logger.printException(() -> "The accounts weren't read for the token form", failed);
            return java.util.Collections.emptyList();
        }
        List<AccountShare.Account> tokens = new ArrayList<>();
        for (AccountShare.Account account : accounts) {
            if (!account.token.isEmpty()) tokens.add(account);
        }
        return tokens;
    }

    /** The scrollable list of tokens, or the line that says there is none to show. */
    private static View body(Context context, List<AccountShare.Account> tokens) {
        if (tokens.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(L10n.t("Facebook hasn't built a session yet. Open a post, then try again."));
            empty.setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8));
            return empty;
        }
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        for (AccountShare.Account account : tokens) list.addView(card(context, account));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(list);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 360)));
        return scroll;
    }

    /** One account: who it is, its token in the open, and a Check and Copy beside it. */
    private static View card(Context context, AccountShare.Account account) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 10);
        card.setPadding(0, padding, 0, padding);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        String who = account.name.isEmpty() ? "" : account.name + "\n";
        title.setText(who + "uid " + (account.uid.isEmpty() ? "-" : account.uid));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        head.addView(check(context, account));

        Button copy = new Button(context);
        copy.setText(L10n.t("Copy"));
        copy.setAllCaps(false);
        copy.setOnClickListener(tapped -> {
            Utils.setClipboard(account.token);
            Toast.makeText(context, L10n.t("Copied."), Toast.LENGTH_SHORT).show();
        });
        head.addView(copy);
        card.addView(head);

        // Selectable as well as copyable: a token pasted by hand into a tool has to survive the
        // trip whole, and one in a field that wraps can be read back before it is used.
        TextView token = new TextView(context);
        token.setText(account.token);
        token.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        token.setTypeface(Typeface.MONOSPACE);
        token.setTextIsSelectable(true);
        card.addView(token);
        return card;
    }

    /** The Check button of one account: it asks Facebook whether the token still signs in. */
    private static Button check(Context context, AccountShare.Account account) {
        Button button = new Button(context);
        button.setText(L10n.t("Check"));
        button.setAllCaps(false);
        button.setOnClickListener(tapped -> {
            Future<String> work = Utils.submitOnBackgroundThread(
                    (Callable<String>) () -> answer(account.token));
            Utils.runOnMainThread(() -> {
                String answer = get(work);
                if (answer == null) answer = L10n.t("The check couldn't reach Facebook.");
                new AlertDialog.Builder(context)
                        .setTitle(account.uid.isEmpty() ? L10n.t("Check") : "uid " + account.uid)
                        .setMessage(answer)
                        .setPositiveButton(L10n.t("Close"), null)
                        .show();
            });
        });
        return button;
    }

    /**
     * What Facebook answers about [token]: the name and id it still signs in as, or that it was
     * rejected, or that the phone couldn't reach Facebook to ask. Runs off the main thread.
     */
    @Nullable
    private static String answer(String token) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://graph.facebook.com/me?fields=id,name&access_token="
                    + URLEncoder.encode(token, "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != 200) return L10n.t("Facebook rejected this token.");
            JSONObject json = new JSONObject(read(connection.getInputStream()));
            String uid = json.optString("id", "");
            if (uid.isEmpty()) return L10n.t("Facebook rejected this token.");
            String name = json.optString("name", "");
            return L10n.f("Live: %1$s (uid %2$s)", name.isEmpty() ? uid : name, uid);
        } catch (Exception failed) {
            Logger.printInfo(() -> "The token check didn't finish: " + failed.getClass().getSimpleName());
            return L10n.t("The check couldn't reach Facebook.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Everything [in] holds, as text. */
    private static String read(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** What a background check answered, or null when it didn't finish. */
    @Nullable
    private static String get(Future<String> work) {
        try {
            return work.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failed) {
            Logger.printException(() -> "The token check failed", failed);
            return null;
        }
    }

    /** Writes [text] where the person picks, through Android's own save dialog. */
    private static void exportToFile(Context context, String text) {
        Activity activity = activityOf(context);
        if (activity == null) {
            Toast.makeText(context, L10n.t("That file couldn't be written."), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "hushface-tokens-" + System.currentTimeMillis() + ".json");
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
            Toast.makeText(activity, L10n.t("Tokens saved.")
                    + (name == null ? "" : "\n" + name), Toast.LENGTH_LONG).show();
        } catch (Exception failed) {
            Logger.printInfo(() -> "The token file wasn't written: " + failed.getClass().getSimpleName());
            Toast.makeText(activity, L10n.t("That file couldn't be written."), Toast.LENGTH_SHORT).show();
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
                startActivityForResult(intent, 1);
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

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
