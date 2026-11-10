package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.preference.EditTextPreference;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.feed.PostWordsTest;
import app.morphe.extension.facebook.feed.TopicPacks;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Every edit dialog on the settings page resizes above the keyboard. At font scale 2 the video file
 * name's long message pushed Save and Cancel under the keyboard on a phone-sized screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class EditDialogKeyboardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test
    public void theSaveFolderDialogResizesAboveTheKeyboard() {
        assertResizes(Settings.SAVE_FOLDER.key);
    }

    @Test
    public void theVideoFileNameDialogResizesAboveTheKeyboard() {
        assertResizes(Settings.FILENAME_TEMPLATE.key);
    }

    @Test
    public void bothWordListDialogsResizeAboveTheKeyboard() {
        assertResizes(Settings.HIDDEN_WORDS.key);
        assertResizes(Settings.KEPT_WORDS.key);
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    public void everySingleLineEditorKeepsItsActionsWithTheLandscapeKeyboard() {
        for (String key : new String[]{Settings.SAVE_FOLDER.key, Settings.VIDEO_SUBFOLDER.key,
                Settings.PHOTO_SUBFOLDER.key, Settings.FILENAME_TEMPLATE.key,
                Settings.PHOTO_FILENAME_TEMPLATE.key, Settings.SEND_TO_APP.key}) {
            assertResizes(key);
        }
    }

    /**
     * #58: with the longest list on a phone-sized screen, a word list's dialog is one scroll that
     * holds its explanation and the whole list. The list doesn't scroll inside itself, the scroll
     * follows the caret to the list's last line and back to its first, and Save and Cancel sit
     * below it, on the screen. Cancel keeps the saved list.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h640dp")
    public void aLongWordListScrollsAsOneAboveItsButtons() {
        assertLongListReachable();
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w640dp-h360dp-land")
    public void aLongWordListScrollsAsOneInLandscape() {
        assertLongListReachable();
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rXB-ldrtl-w360dp-h640dp-night")
    public void aLongWordListScrollsAsOneRightToLeftInTheDarkAtTwiceTheTextSize() {
        RuntimeEnvironment.setFontScale(2f);
        assertLongListReachable();
    }

    private static void assertLongListReachable() {
        StringBuilder longest = new StringBuilder();
        for (int phrase = 0; phrase < PostWords.MAX_PHRASES; phrase++) {
            if (phrase > 0) longest.append('\n');
            longest.append("phrase ").append(phrase).append(" that runs on");
        }
        String list = longest.toString();
        assertTrue(PostWords.isClean(list));
        Settings.HIDDEN_WORDS.save(list);
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow row =
                    (ValueRows.WordsRow) shown(controller, Settings.HIDDEN_WORDS.key);
            AlertDialog dialog = (AlertDialog) row.getDialog();
            try {
                EditText field = row.getEditText();
                ScrollView scroll = scrollAround(field);
                assertNotNull("the list isn't in a scroll", scroll);
                // AlertDialog also keeps a hidden stock message with this ID. Check the displayed
                // explanation, rather than whichever duplicate findViewById finds first.
                ArrayList<View> explanations = new ArrayList<>();
                dialog.getWindow().getDecorView().findViewsWithText(explanations,
                        row.getDialogMessage(), View.FIND_VIEWS_WITH_TEXT);
                int visible = 0;
                for (View explanation : explanations) {
                    if (!explanation.isShown()) continue;
                    visible++;
                    assertSame("the explanation scrolls with the list", scroll, scrollAround(explanation));
                }
                assertEquals("one displayed explanation", 1, visible);
                assertEquals("the list scrolls inside itself", Integer.MAX_VALUE, field.getMaxLines());
                assertEquals(list, field.getText().toString());
                assertTrue("the whole list fits, so this shows nothing", scroll.canScrollVertically(1));

                View window = dialog.getWindow().getDecorView();
                for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                    Button button = dialog.getButton(which);
                    assertFalse(button.getText() + " is inside the scroll", scrollAround(button) == scroll);
                    int[] at = new int[2];
                    button.getLocationInWindow(at);
                    assertTrue(button.getText() + " is off the screen", button.isShown()
                            && at[1] >= 0 && at[1] + button.getHeight() <= window.getHeight());
                }

                field.requestFocus();
                field.setSelection(list.length());
                ShadowLooper.idleMainLooper();
                dialog.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
                // ScrollView animates the rectangle request TextView sends before drawing. A
                // looper drain alone neither advances animation time nor draws the next frame.
                for (int frame = 0; frame < 32; frame++) {
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
                    scroll.computeScroll();
                }
                int lastLine = field.getTop() + field.getLayout().getLineTop(field.getLineCount() - 1) + field.getTotalPaddingTop();
                assertTrue("the caret on the last line is out of sight: line=" + lastLine
                        + ", scroll=" + scroll.getScrollY() + ", height=" + scroll.getHeight(), lastLine >= scroll.getScrollY()
                        && lastLine < scroll.getScrollY() + scroll.getHeight());
                field.setSelection(0);
                ShadowLooper.idleMainLooper();
                dialog.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
                for (int frame = 0; frame < 32; frame++) {
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
                    scroll.computeScroll();
                }
                int firstLine = field.getTop() + field.getTotalPaddingTop();
                assertTrue("the caret on the first line is out of sight", firstLine >= scroll.getScrollY()
                        && firstLine < scroll.getScrollY() + scroll.getHeight());

                field.setText("changed");
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                ShadowLooper.idleMainLooper();
                assertEquals("Cancel saved the list", list, Settings.HIDDEN_WORDS.savedValue());
            } finally {
                if (dialog.isShowing()) dialog.dismiss();
            }
        }
    }

    /** The scroll [view] sits in, or null. */
    private static ScrollView scrollAround(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
        }
        return null;
    }

    @Test
    public void aDialogWithNoWindowIsLeftAlone() {
        HushfacebookPreferenceFragment.fitAboveKeyboard(null);
    }

    /**
     * #58: Save refuses a list past the room the two lists share, or one of more than a thousand
     * phrases, with its dialog still open and what was typed whole, and says why. The line under the
     * explanation says it as the list is typed. A list that fits saves and closes the dialog.
     */
    @Test
    public void aListThatDoesntFitIsRefusedWithItsDialogStillOpen() {
        Settings.KEPT_WORDS.save(PostWordsTest.asciiOfBytes(PostWords.MAX_LIST_BYTES - 10));
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow row =
                    (ValueRows.WordsRow) shown(controller, Settings.HIDDEN_WORDS.key);
            AlertDialog dialog = (AlertDialog) row.getDialog();
            String fits = "spoiler";
            String tooLong = "spoiler\ngiveaway now";
            String tooMany = lines(PostWords.MAX_PHRASES + 1);
            for (String typed : new String[]{tooLong, tooMany}) {
                ShadowAlertDialog.reset();
                row.getEditText().setText(typed);
                String why = HushfacebookPreferenceFragment.wordsRefusal(
                        PostWords.size(typed, PostWords.MAX_LIST_BYTES - 10));
                assertNotNull(why);
                ArrayList<View> lines = new ArrayList<>();
                dialog.getWindow().getDecorView().findViewsWithText(lines, why, View.FIND_VIEWS_WITH_TEXT);
                assertFalse("the dialog doesn't say why as it's typed", lines.isEmpty());

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ShadowLooper.idleMainLooper();
                assertTrue("Save closed the dialog", dialog.isShowing());
                assertEquals("what was typed was cut", typed, row.getEditText().getText().toString());
                assertEquals("", Settings.HIDDEN_WORDS.savedValue());
                AlertDialog said = ShadowAlertDialog.getLatestAlertDialog();
                assertTrue(said != null && said != dialog);
                assertEquals(why, Shadows.shadowOf(said).getMessage().toString());
                said.dismiss();
            }
            assertTrue(why(PostWords.size(tooLong, PostWords.MAX_LIST_BYTES - 10)).contains("101"));

            row.getEditText().setText(fits);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(dialog.isShowing());
            assertEquals(fits, Settings.HIDDEN_WORDS.savedValue());
        }
    }

    /**
     * A topic pack goes into the open editor as ordinary lines and nothing is saved until Save. The
     * button is on the list that hides posts and not on the keep list. A word already typed isn't
     * added twice, and Cancel keeps the saved list.
     */
    @Test
    public void aTopicPackFillsTheEditorAndSavesNothingUntilSave() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow keep = (ValueRows.WordsRow) shown(controller, Settings.KEPT_WORDS.key);
            assertNull("the keep list offers topic packs", keep.choosePack);
            keep.getDialog().dismiss();
        }
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow row = (ValueRows.WordsRow) shown(controller, Settings.HIDDEN_WORDS.key);
            AlertDialog dialog = (AlertDialog) row.getDialog();
            assertNotNull(row.choosePack);
            ArrayList<View> buttons = new ArrayList<>();
            dialog.getWindow().getDecorView().findViewsWithText(buttons, "Add a topic pack", View.FIND_VIEWS_WITH_TEXT);
            assertFalse("no Add a topic pack button in the dialog", buttons.isEmpty());

            row.getEditText().setText("Bitcoin");
            ShadowAlertDialog.reset();
            buttons.get(0).performClick();
            ShadowLooper.idleMainLooper();
            AlertDialog packs = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("the pack list didn't open", packs != null && packs != dialog);
            int crypto = TopicPacks.Pack.CRYPTO.ordinal();
            assertEquals(TopicPacks.Pack.values().length, Shadows.shadowOf(packs).getItems().length);
            Shadows.shadowOf(packs).clickOnItem(crypto);
            ShadowLooper.idleMainLooper();

            String typed = row.getEditText().getText().toString();
            assertTrue(typed.startsWith("Bitcoin" + (char) 10));
            assertEquals("the pack's lines went in once after the typed word",
                    1 + TopicPacks.Pack.CRYPTO.lines().size(), PostWords.count(typed));
            assertEquals("nothing is saved yet", "", Settings.HIDDEN_WORDS.savedValue());
            assertTrue("the dialog says what the pack added, in full", dialogSays(dialog, "Tap Save to keep the list."));

            buttons.get(0).performClick();
            ShadowLooper.idleMainLooper();
            Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(crypto);
            ShadowLooper.idleMainLooper();
            assertEquals("a second pick adds nothing", typed, row.getEditText().getText().toString());
            assertTrue("a second pick says so", dialogSays(dialog, "Every word from Crypto is already in the list."));

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(dialog.isShowing());
            assertEquals("the words are ordinary saved lines", typed, Settings.HIDDEN_WORDS.savedValue());
        }
        Settings.HIDDEN_WORDS.resetToDefault();
    }

    /**
     * The sample under a word list is judged against the list as typed, before Save, and against
     * the other list as saved. The sample isn't saved anywhere, and Cancel keeps the saved list.
     */
    @Test
    public void aSampleIsJudgedAgainstTheTypedListAndSavesNothing() {
        Settings.KEPT_WORDS.save("trailer");
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow row = (ValueRows.WordsRow) shown(controller, Settings.HIDDEN_WORDS.key);
            AlertDialog dialog = (AlertDialog) row.getDialog();
            assertTrue("the dialog doesn't offer a sample", dialogSays(dialog, "Try the lists on some text"));
            EditText sample = sampleField(dialog, row.getEditText());
            assertFalse("the sample is kept across a restore", sample.isSaveEnabled());
            assertTrue(dialogSays(dialog, "Type or paste a post's text to see what these lists would do with it. It isn't saved."));
            sample.setText("Big SPOILER inside");
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            assertTrue(dialogSays(dialog, "The hide list is empty, so no post would be hidden."));

            row.getEditText().setText("spoiler");
            sample.setText("Big SPOILER inside");
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            assertTrue("the typed list didn't hide the sample", dialogSays(dialog, "A post with this text would be hidden."));

            sample.setText("Spoiler in the trailer");
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            assertTrue("the saved keep list didn't keep the sample",
                    dialogSays(dialog, "A post with this text would stay, because the keep list matches it."));

            sample.setText("Nothing here");
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            assertTrue(dialogSays(dialog, "A post with this text would stay. Nothing on either list matches it."));

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("Cancel saved the list", "", Settings.HIDDEN_WORDS.savedValue());
            assertEquals("trailer", Settings.KEPT_WORDS.savedValue());
        }
    }

    /**
     * A pause in typing that leaves the verdict as it was doesn't set the line again. It's a live
     * region, so TalkBack would read the same verdict out after every pause.
     */
    @Test
    public void aVerdictThatStaysTheSameIsntSetAgain() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow row = (ValueRows.WordsRow) shown(controller, Settings.HIDDEN_WORDS.key);
            AlertDialog dialog = (AlertDialog) row.getDialog();
            try {
                row.getEditText().setText("spoiler");
                EditText sample = sampleField(dialog, row.getEditText());
                sample.setText("Big SPOILER inside");
                ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
                ArrayList<View> found = new ArrayList<>();
                dialog.getWindow().getDecorView().findViewsWithText(found,
                        "A post with this text would be hidden.", View.FIND_VIEWS_WITH_TEXT);
                assertEquals(1, found.size());
                TextView verdict = (TextView) found.get(0);
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, verdict.getAccessibilityLiveRegion());
                int[] sets = {0};
                verdict.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence text, int start, int before, int after) { sets[0]++; }
                    @Override public void afterTextChanged(android.text.Editable text) { }
                });

                sample.setText("Big SPOILER inside, again");
                ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
                assertEquals("the same verdict was set again", 0, sets[0]);

                sample.setText("Nothing here");
                ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
                assertEquals("a new verdict wasn't shown", 1, sets[0]);
                assertEquals("A post with this text would stay. Nothing on either list matches it.", verdict.getText().toString());
            } finally {
                if (dialog.isShowing()) dialog.dismiss();
            }
        }
    }

    /**
     * Typing in the sample keeps the verdict under it in sight. The scroll follows the caret, and
     * with the keyboard up that left the line below the field off the screen on the S22.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h640dp")
    public void theVerdictScrollsIntoSightWhileTheSampleIsTyped() {
        Settings.HIDDEN_WORDS.save(lines(60));
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ValueRows.WordsRow row = (ValueRows.WordsRow) shown(controller, Settings.HIDDEN_WORDS.key);
            AlertDialog dialog = (AlertDialog) row.getDialog();
            try {
                EditText sample = sampleField(dialog, row.getEditText());
                ScrollView scroll = scrollAround(sample);
                assertNotNull(scroll);
                sample.requestFocus();
                sample.setText("has w59 in it");
                ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
                dialog.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
                for (int frame = 0; frame < 32; frame++) {
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
                    scroll.computeScroll();
                }
                ArrayList<View> found = new ArrayList<>();
                dialog.getWindow().getDecorView().findViewsWithText(found,
                        "A post with this text would be hidden.", View.FIND_VIEWS_WITH_TEXT);
                assertEquals(1, found.size());
                int[] line = new int[2];
                int[] port = new int[2];
                found.get(0).getLocationInWindow(line);
                scroll.getLocationInWindow(port);
                assertTrue("the verdict is out of sight: line " + line[1] + "-" + (line[1] + found.get(0).getHeight())
                        + ", scroll " + port[1] + "-" + (port[1] + scroll.getHeight()),
                        line[1] >= port[1] && line[1] + found.get(0).getHeight() <= port[1] + scroll.getHeight());
            } finally {
                if (dialog.isShowing()) dialog.dismiss();
            }
        }
    }

    /** The dialog's one edit field that isn't [list]. */
    private static EditText sampleField(AlertDialog dialog, EditText list) {
        ArrayList<View> all = new ArrayList<>();
        collect(dialog.getWindow().getDecorView(), all);
        EditText found = null;
        for (View view : all) {
            if (!(view instanceof EditText) || view == list || !view.isShown()) continue;
            assertNull("two sample fields", found);
            found = (EditText) view;
        }
        assertNotNull("no sample field", found);
        assertEquals("Text of a post", String.valueOf(found.getHint()));
        return found;
    }

    private static void collect(View view, ArrayList<View> into) {
        into.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
    }

    private static boolean dialogSays(AlertDialog dialog, String text) {
        ArrayList<View> found = new ArrayList<>();
        dialog.getWindow().getDecorView().findViewsWithText(found, text, View.FIND_VIEWS_WITH_TEXT);
        return !found.isEmpty();
    }

    private static String why(PostWords.Size size) {
        return HushfacebookPreferenceFragment.wordsRefusal(size);
    }

    private static String lines(int count) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < count; i++) lines.append(i == 0 ? "" : "\n").append("w").append(i);
        return lines.toString();
    }

    private static void assertResizes(String key) {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            EditTextPreference row = shown(controller, key);
            try {
                int mode = row.getDialog().getWindow().getAttributes().softInputMode;
                assertEquals(key + " doesn't resize above the keyboard", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
                int flags = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
                assertEquals("the landscape keyboard can replace the edit dialog", flags,
                        row.getEditText().getImeOptions() & flags);
            } finally {
                row.getDialog().dismiss();
            }
        }
    }

    /** The settings page's edit row for [key], with its dialog showing. */
    private static EditTextPreference shown(ActivityController<Activity> controller, String key) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "hushfacebook_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page", page instanceof HushfacebookPreferenceFragment);
        EditTextPreference row = (EditTextPreference) ((HushfacebookPreferenceFragment) page).findPreference(key);
        assertNotNull("no row for " + key, row);
        if (row instanceof ValueRows.FolderRow) {
            ((ValueRows.FolderRow) row).showDialog(null);
        } else if (row instanceof ValueRows.SubfolderRow) {
            ((ValueRows.SubfolderRow) row).showDialog(null);
        } else if (row instanceof ValueRows.FileNameRow) {
            ((ValueRows.FileNameRow) row).showDialog(null);
        } else if (row instanceof ValueRows.WordsRow) {
            ((ValueRows.WordsRow) row).showDialog(null);
        } else if (row instanceof ValueRows.SendAppRow) {
            ((ValueRows.SendAppRow) row).showDialog(null);
        } else {
            fail(key + " is a " + row.getClass().getName() + ", not one of the page's edit rows");
        }
        ShadowLooper.idleMainLooper();
        return row;
    }
}
