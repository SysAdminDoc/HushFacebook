/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Save GIF, Save photo and Save video close the comment sheet after their tap, as Facebook's rows do. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentSheetTest {

    /** Litho's click event: one View field, named differently in every build. */
    static final class Click {
        public final android.view.View A00;

        Click(android.view.View view) {
            A00 = view;
        }
    }

    private Activity activity;
    private Dialog sheet;
    private TextView row;

    @Before
    public void openSheet() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        sheet = new Dialog(activity);
        FrameLayout content = new FrameLayout(activity);
        row = new TextView(activity);
        content.addView(row);
        sheet.setContentView(content);
        sheet.show();
        CommentSheet.opened(sheet);
    }

    @After
    public void forget() {
        if (sheet.isShowing()) sheet.dismiss();
        CommentSheet.opened(new Dialog(activity));
    }

    @Test
    public void aTapInTheSheetClosesIt() {
        CommentSheet.close(new Click(row));
        assertFalse(sheet.isShowing());
    }

    @Test
    public void theTappedViewIsFoundByItsType() {
        assertSame(row, CommentSheet.tappedView(new Click(row)));
        assertSame(row, CommentSheet.tappedView(row));
        assertNull(CommentSheet.tappedView("no view"));
        assertNull(CommentSheet.tappedView(null));
    }

    @Test
    public void aTapFromAnotherWindowLeavesTheSheetOpen() {
        TextView elsewhere = new TextView(activity);
        activity.setContentView(elsewhere);
        CommentSheet.close(new Click(elsewhere));
        assertTrue(sheet.isShowing());
    }

    @Test
    public void anEventWithNoViewStillClosesTheShowingSheet() {
        CommentSheet.close("an event of another shape");
        assertFalse(sheet.isShowing());
    }

    @Test
    public void aClosedSheetStaysClosedAndAnotherTapIsHarmless() {
        sheet.dismiss();
        CommentSheet.close(new Click(row));
        CommentSheet.close(null);
        assertFalse(sheet.isShowing());
    }

    @Test
    public void aRowClosesTheSheetEvenWhenItSavesNothing() {
        // Settings aren't ready here, so the tap saves nothing, and still closes the sheet as Facebook's rows do.
        new CommentPhoto(null, null).invoke(new Click(row));
        assertFalse(sheet.isShowing());
    }
}
