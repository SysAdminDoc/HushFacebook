/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;

import app.morphe.extension.facebook.accounts.LoginImport;
import app.morphe.extension.facebook.accounts.SessionCapture;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The account rows: one form for both directions, and the token on its own.
 *
 * <p>Export and import are one dialog, as FacebookAMD has them: the box holds what this phone is
 * signed in with, Copy takes it elsewhere, Save writes whatever the box holds, and the file buttons
 * move the same text to and from a file. {@link LoginImport#showForm} draws it, from here and from
 * the login screen. Copy token is the second row, because a tool that asks for a token wants the
 * string and nothing around it.
 */
@SuppressWarnings("deprecation") // Framework preferences, as the rest of the screen.
final class AccountsScreen {
    private AccountsScreen() {
    }

    /** The two rows, under their section. */
    static void section(HushfacebookPreferenceFragment page, android.preference.PreferenceCategory category,
                        Context context) {
        category.addPreference(formRow(context));
        category.addPreference(tokenRow(page, context));
    }

    /** Import or export: the form, on whatever the phone is signed in with. */
    private static Preference formRow(Context context) {
        Preference row = new Row(context);
        row.setTitle(L10n.t("Import or export"));
        row.setSummary(L10n.t("Shows this phone's accounts as text. Copy or save them here, or paste an account "
                + "and save it into this Facebook."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(tapped -> {
            LoginImport.showForm(tapped.getContext());
            return true;
        });
        return row;
    }

    /** Copy token: the signed-in account's token alone, which is what other tools ask for. */
    private static Preference tokenRow(HushfacebookPreferenceFragment page, Context context) {
        Preference row = new Row(context);
        row.setTitle(L10n.t("Copy token"));
        row.setSummary(L10n.t("Copies only the access token."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(tapped -> {
            String token = SessionCapture.token();
            String uid = SessionCapture.uid();
            if (token.isEmpty()) {
                show(page, L10n.t("Nothing was copied"),
                        L10n.t("Facebook hasn't built a session yet. Open a post, then try again."));
                return true;
            }
            Utils.setClipboard(token);
            show(page, L10n.t("Token copied"), (uid.isEmpty() ? "" : "uid " + uid + "\n\n") + mask(token));
            return true;
        });
        return row;
    }

    /** A token as a dialog shows it: its start and end, with the middle left out. */
    private static String mask(String token) {
        if (token.length() <= 40) return token;
        return token.substring(0, 24) + "..." + token.substring(token.length() - 8);
    }

    /** Shows [message] over the page, or nothing when the page is gone. */
    private static void show(HushfacebookPreferenceFragment page, String title, String message) {
        try {
            page.show(new AlertDialog.Builder(page.getContext()).setTitle(title).setMessage(message)
                    .setPositiveButton(L10n.t("Close"), null));
        } catch (RuntimeException gone) {
            Logger.printInfo(() -> "The account dialog had no page to open over: " + gone.getClass().getSimpleName());
        }
    }
}
