/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.reels.PostTextSelect;
import app.morphe.extension.facebook.settings.ShimmerTitle;
import app.morphe.extension.shared.L10n;

/**
 * The box a post's Translate or See original line opens: the translation and the post's own text,
 * one button apart. It opens straight away and fills in when the answer comes, since a post already
 * laid out can't change in place. Built from a framework dialog theme, like the box for selecting
 * a post's text, so its selection handles stay Android's own.
 */
final class PostTranslationBox {
    private static WeakReference<AlertDialog> showing = new WeakReference<>(null);

    private final Context context;
    private final String original;
    private final AlertDialog dialog;
    private final TextView body;
    @Nullable
    private String translated;
    private boolean onOriginal;

    /** Opens on the post's own text when [startOnOriginal], which is where See original leads. */
    static void show(Activity activity, String original, boolean startOnOriginal) {
        AlertDialog open = showing.get();
        if (open != null && open.isShowing()) open.dismiss();
        new PostTranslationBox(activity, original, startOnOriginal);
    }

    private PostTranslationBox(Activity activity, String original, boolean startOnOriginal) {
        this.context = activity;
        this.original = original;
        this.onOriginal = startOnOriginal;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, PostTextSelect.dark(activity)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(L10n.t(activity, "Translation"))
                .setPositiveButton(L10n.t(activity, "Done"), null)
                .setNeutralButton(L10n.t(activity, "Show original"), null);
        Context themed = builder.getContext();
        body = new TextView(themed);
        body.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        body.setTextIsSelectable(true);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, themed.getResources().getDisplayMetrics()));
        body.setPadding(pad, pad / 2, pad, 0);
        body.setText(startOnOriginal ? original : L10n.t(activity, "Translating..."));
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(body);
        dialog = builder.setView(scroll).create();
        showing = new WeakReference<>(dialog);
        dialog.show();
        ShimmerTitle.wearTitleOf(dialog);
        Button toggle = toggle();
        toggle.setEnabled(false);
        toggle.setOnClickListener(button -> {
            onOriginal = !onOriginal;
            showChosen();
        });
        labelToggle();
        PostTranslation.ask(original, this::answered);
    }

    private Button toggle() {
        return dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
    }

    private void answered(@Nullable GoogleTranslate.Answer answer) {
        if (!dialog.isShowing()) return;
        if (answer == null) {
            if (!onOriginal) body.setText(L10n.t(context, "Couldn't translate this. Check your connection and try again.") + "\n\n" + original);
            return;
        }
        boolean cut = original.length() > GoogleTranslate.LONGEST;
        translated = cut ? answer.text + " ..." : answer.text;
        toggle().setEnabled(true);
        showChosen();
    }

    private void showChosen() {
        body.setText(onOriginal ? original : translated);
        labelToggle();
    }

    private void labelToggle() {
        toggle().setText(onOriginal ? L10n.t(context, "Show translation") : L10n.t(context, "Show original"));
    }

    /** The box on the screen, or null. For tests. */
    @Nullable
    static AlertDialog shownDialog() {
        AlertDialog dialog = showing.get();
        return dialog != null && dialog.isShowing() ? dialog : null;
    }

    /** For tests: closes the box shown. */
    static void reset() {
        AlertDialog open = showing.get();
        if (open != null && open.isShowing()) open.dismiss();
        showing = new WeakReference<>(null);
    }
}
