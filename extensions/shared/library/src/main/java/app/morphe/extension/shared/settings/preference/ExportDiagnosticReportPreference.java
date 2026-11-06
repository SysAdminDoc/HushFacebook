/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.shared.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListAdapter;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.shared.L10n;

/** Offers a compact choice between a quick clipboard report and a full text file. */
@SuppressWarnings({"deprecation", "unused"})
public class ExportDiagnosticReportPreference extends Preference {
    private enum State { IDLE, COPYING, SAVING, COPIED, SAVED, COPY_FAILED, SAVE_FAILED }

    private static final CopyOnWriteArrayList<WeakReference<ExportDiagnosticReportPreference>> ROWS =
            new CopyOnWriteArrayList<>();
    private static State state = State.IDLE;
    private CharSequence restingSummary;

    {
        // A key so the settings search can index this row. Nothing in the settings
        // framework treats it as a setting: a key with no Setting behind it is skipped.
        setKey("action_export_diagnostic_report");
        setPersistent(false);
        ROWS.add(new WeakReference<>(this));
        showState();
        setOnPreferenceClickListener(pref -> {
            if (busy()) return true;
            DialogInterface.OnClickListener choose = (dialog, which) -> {
                if (busy() || which < 0 || which > 1) return;
                boolean file = which == 1;
                update(file ? State.SAVING : State.COPYING);
                java.util.function.Consumer<Boolean> completed = succeeded -> update(file
                        ? (succeeded ? State.SAVED : State.SAVE_FAILED)
                        : (succeeded ? State.COPIED : State.COPY_FAILED));
                if (file) LogBufferManager.exportToFile(completed);
                else LogBufferManager.exportToClipboard(completed);
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setTitle(dialogTitle());
            ListAdapter drawn = choices(builder.getContext());
            if (drawn == null) builder.setItems(labels(), choose);
            else builder.setAdapter(drawn, choose);
            AlertDialog dialog = builder.setNegativeButton(negativeText(), null).create();
            // Built, then styled, then shown, so the first layout measures what's on show. The
            // list works out its height from rows it builds itself, and styling applied after
            // show() reached rows it had already measured without it.
            dialog.create();
            onDialogCreated(dialog);
            dialog.show();
            return true;
        });
    }

    private static boolean busy() {
        return state == State.COPYING || state == State.SAVING;
    }

    /** Only fixed states are held in memory. Report text and destinations never enter a row. */
    private static void update(State next) {
        state = next;
        for (WeakReference<ExportDiagnosticReportPreference> held : ROWS) {
            ExportDiagnosticReportPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.showState();
        }
    }

    @Override
    public void setSummary(CharSequence summary) {
        restingSummary = summary;
        showState();
    }

    private void showState() {
        setEnabled(!busy());
        super.setSummary(statusLine());
    }

    private CharSequence statusLine() {
        switch (state) {
            case COPYING: return L10n.t(getContext(), "Copying diagnostic report...");
            case SAVING: return L10n.t(getContext(), "Saving diagnostic report...");
            case COPIED: return L10n.t(getContext(), "Diagnostic report copied.");
            case SAVED: return L10n.t(getContext(), "Diagnostic report saved.");
            case COPY_FAILED: return L10n.t(getContext(), "The report couldn't be copied. Try again.");
            case SAVE_FAILED: return L10n.t(getContext(), "The report couldn't be saved. Try again.");
            default: return restingSummary;
        }
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        // Below Android 11 TalkBack reads the summary, which already says the state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.setStateDescription(state == State.IDLE ? null : statusLine());
    }

    /**
     * The dialog, built and not yet shown: a bundle's place to style it. Anything that changes a
     * size belongs here rather than after show(), where the list has already measured itself.
     */
    protected void onDialogCreated(AlertDialog dialog) {
    }

    /** The dialog's title, in the phone's language. A bundle may override these four. */
    protected CharSequence dialogTitle() {
        return L10n.t(getContext(), "Export diagnostic report");
    }

    /** The two choices, quick copy first, full file second. */
    protected CharSequence[] labels() {
        return new CharSequence[]{L10n.t(getContext(), "Copy quick report"),
                L10n.t(getContext(), "Save full report")};
    }

    /**
     * The two choices as the bundle draws them, in the order of {@link #labels()}, or null for
     * Android's own list of those labels. Each row comes from the adapter already styled, padding
     * included, which is what the list measures.
     *
     * @param dialogContext the dialog's themed context
     */
    @Nullable
    protected ListAdapter choices(Context dialogContext) {
        return null;
    }

    /**
     * Cancel from the catalog. Android's own was read from the dialog's activity, which can be
     * in another language than the title and choices above it.
     */
    protected CharSequence negativeText() {
        return L10n.t(getContext(), "Cancel");
    }

    public ExportDiagnosticReportPreference(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes
    ) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ExportDiagnosticReportPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ExportDiagnosticReportPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ExportDiagnosticReportPreference(Context context) {
        super(context);
    }
}
