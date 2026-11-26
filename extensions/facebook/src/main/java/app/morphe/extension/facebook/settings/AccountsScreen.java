/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.preference.Preference;

import app.morphe.extension.facebook.accounts.TokenExport;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;

/**
 * The account row: the token export.
 *
 * <p>One row opens {@link TokenExport#showForm(Context)} on whatever the phone is signed in with:
 * each account, its access token in the open, a Check and a Copy for each, and Save JSON for all of
 * them. Nothing is written into Facebook from there, so there is nothing else on this page.
 */
@SuppressWarnings("deprecation") // Framework preferences, as the rest of the screen.
final class AccountsScreen {
    private AccountsScreen() {
    }

    /** The row, under its section. */
    static void section(HushfacebookPreferenceFragment page, android.preference.PreferenceCategory category,
                        Context context) {
        category.addPreference(tokenRow(context));
    }

    /** Export token: the form, on whatever the phone is signed in with. */
    private static Preference tokenRow(Context context) {
        Preference row = new Row(context);
        row.setTitle(L10n.t("Export token"));
        row.setSummary(L10n.t("Shows the access token of each account signed in on this phone. Copy one, "
                + "or save them all as JSON."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(tapped -> {
            TokenExport.showForm(tapped.getContext());
            return true;
        });
        return row;
    }
}
