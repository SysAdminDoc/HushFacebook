/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Dialog;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * A comment's long-press sheet, so Save GIF, Save photo and Save video close it after their tap the
 * way Facebook's own rows do.
 *
 * <p>The sheet's fragment makes a dialog and sets the sheet as its content, and the patch hands that
 * dialog to {@link #opened} right then. Facebook's rows close the sheet with the dialog's dismiss,
 * so {@link #close} calls the same, once it knows the tap came from that dialog: the click event
 * Facebook hands a row's action holds the tapped view, and its window has to be the dialog's.
 */
public final class CommentSheet {

    /** The source every event here carries in the diagnostic report. */
    static final String SOURCE = "CommentSheet";

    /** The newest comment sheet's dialog, held weakly so a closed sheet isn't kept. */
    private static volatile WeakReference<Dialog> sheet = new WeakReference<>(null);

    private CommentSheet() {
    }

    /** A comment sheet's dialog got its content. Called from the sheet's fragment; never throws. */
    public static void opened(Dialog dialog) {
        if (dialog != null) sheet = new WeakReference<>(dialog);
    }

    /**
     * Closes the comment sheet a row was tapped in, given the click event Facebook handed the row's
     * action. Leaves it open when the tapped view sits in another window. Never throws.
     */
    static void close(Object event) {
        try {
            Dialog dialog = sheet.get();
            if (dialog == null || !dialog.isShowing()) return;
            View tapped = tappedView(event);
            Window window = dialog.getWindow();
            if (tapped != null && window != null && tapped.getRootView() != window.peekDecorView()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "a row was tapped outside the comment sheet, so it stays open");
                return;
            }
            dialog.dismiss();
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not close a comment's sheet", t);
        }
    }

    /**
     * The view [event] holds: Litho's click event keeps it in its one View field, whose name changes
     * from build to build, so it's found by type. Null when there's none.
     */
    static View tappedView(Object event) {
        if (event instanceof View) return (View) event;
        if (event == null) return null;
        for (Class<?> type = event.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !View.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(event);
                    if (value instanceof View) return (View) value;
                } catch (IllegalAccessException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
