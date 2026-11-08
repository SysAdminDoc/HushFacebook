/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Search comments (#420): with its switch on, a comment sheet handed over by Open comments at full
 * height gets a magnifier at the top corner of its list. Words typed in its bar are looked for from
 * the top of the list, a page at a time, whatever their case or accents, in the text the rows draw,
 * not in what they tell a screen reader. The comment found is brought under the bar, outlined and
 * counted, down and up step through the others even after the list recycled the rows they were in,
 * and the end of the list says there are no more. A list the keyboard squeezes to a sliver keeps the
 * bar, above it, with the focus in its box, and the search key closes the keyboard and searches once
 * the list is back. Closing the bar takes the words and the outline away. Off, a sheet is left alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w400dp-h800dp-mdpi")
public class CommentSearchTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 400;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FrameLayout sheet;
    private Rows list;

    @After
    public void restore() {
        Settings.SEARCH_COMMENTS.resetToDefault();
    }

    /**
     * A comment list the way a RecyclerView keeps one: only the rows on screen are views, and a row
     * that scrolls away hands its view to the next one coming in. A text starting "label:" is a row
     * that draws nothing and only tells a screen reader the rest.
     */
    public static final class Rows extends RecyclerView {
        static final int ROW = 100;
        final List<String> texts;
        int offset;
        private final Map<Integer, TextView> shown = new HashMap<>();
        private final List<TextView> spare = new ArrayList<>();

        public Rows(Context context, String... texts) {
            super(context, true);
            this.texts = Arrays.asList(texts);
        }

        private int most() {
            return Math.max(0, texts.size() * ROW - getHeight());
        }

        @Override
        public boolean canScrollVertically(int direction) {
            return direction > 0 ? offset < most() : offset > 0;
        }

        @Override
        public void scrollBy(int x, int y) {
            offset = Math.max(0, Math.min(offset + y, most()));
            fill();
        }

        public void scrollToPosition(int position) {
            offset = Math.max(0, Math.min(position * ROW, most()));
            fill();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            fill();
        }

        void fill() {
            int height = getHeight();
            for (Integer position : new ArrayList<>(shown.keySet())) {
                int top = position * ROW - offset;
                if (top + ROW > 0 && top < height) continue;
                TextView gone = shown.remove(position);
                removeViewInLayout(gone);
                spare.add(gone);
            }
            for (int position = 0; position < texts.size(); position++) {
                int top = position * ROW - offset;
                if (top + ROW <= 0 || top >= height) continue;
                TextView row = shown.get(position);
                if (row == null) {
                    row = spare.isEmpty() ? new TextView(getContext()) : spare.remove(0);
                    String text = texts.get(position);
                    boolean label = text.startsWith("label:");
                    row.setText(label ? "" : text);
                    row.setContentDescription(label ? text.substring(6) : null);
                    addViewInLayout(row, -1, new FrameLayout.LayoutParams(getWidth(), ROW), true);
                    shown.put(position, row);
                }
                row.measure(View.MeasureSpec.makeMeasureSpec(getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(ROW, View.MeasureSpec.EXACTLY));
                row.layout(0, top, getWidth(), top + ROW);
            }
        }
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** A sheet with [texts] in its list, on screen, handed to the hook with the switch on. */
    private CommentSearch.Search sheetWith(String... texts) {
        Settings.SEARCH_COMMENTS.save(true);
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        sheet = new FrameLayout(screen);
        list = new Rows(screen, texts);
        sheet.addView(list, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        screen.setContentView(sheet);
        idle(50);
        CommentSearch.sheetOpened(sheet);
        idle(50);
        CommentSearch.Search search = CommentSearch.searchOf(sheet);
        assertNotNull("the sheet was taken", search);
        search.place();
        return search;
    }

    private void type(CommentSearch.Search search, String words) {
        search.openBar();
        search.field.setText(words);
        idle(CommentSearch.TYPING_PAUSE_MS + 6000);
        search.place();
    }

    private void step(CommentSearch.Search search, boolean down) {
        search.step(down);
        idle(6000);
        search.place();
    }

    /** The count the bar shows, once it's checked a match is outlined. */
    private static String outlined(CommentSearch.Search search) {
        assertTrue("the match is outlined", search.layer.marking);
        return search.count.getText().toString();
    }

    private static List<String> twenty(Map<Integer, String> special) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 20; i++) texts.add(special.containsKey(i) ? special.get(i) : "Comment number " + i);
        return texts;
    }

    @Test
    public void theMagnifierSitsAtTheTopCornerOfTheList() {
        CommentSearch.Search search = sheetWith(twenty(new HashMap<>()).toArray(new String[0]));
        assertNotNull("the layer went over the sheet's window", search.layer.getParent());
        assertEquals(View.VISIBLE, search.magnifier.getVisibility());
        assertEquals(View.GONE, search.bar.getVisibility());
        assertEquals("right edge, 8 dp in", WIDTH - 8 - 36, search.magnifier.getTranslationX(), 0.5f);

        search.openBar();
        search.place();
        assertEquals(View.GONE, search.magnifier.getVisibility());
        assertEquals(View.VISIBLE, search.bar.getVisibility());
        assertEquals("the bar spans the list", WIDTH - 16, search.bar.getLayoutParams().width);
    }

    @Test
    public void backClosingTheSheetsWindowTakesTheLayerOffAfterTheDetach() {
        CommentSearch.Search search = sheetWith(twenty(new HashMap<>()).toArray(new String[0]));
        search.openBar();
        ViewGroup frame = (ViewGroup) sheet.getParent();
        assertSame("the layer sits beside the sheet", frame, search.layer.getParent());
        // Back on Facebook's comments dialog: the frame holding the sheet and the layer detaches its
        // children in one pass, from a count taken before the loop.
        ((ViewGroup) frame.getParent()).removeView(frame);
        idle(50);
        assertNull("taken off once the detach ended", search.layer.getParent());
        assertEquals(1, frame.getChildCount());
    }

    @Test
    public void findsACommentPagesDownWhateverItsAccentsAndOutlinesIt() {
        Map<Integer, String> special = new HashMap<>();
        special.put(12, "Best Café in town");
        CommentSearch.Search search = sheetWith(twenty(special).toArray(new String[0]));
        type(search, "cafe");

        assertEquals("1/1", outlined(search));
        assertEquals(1, search.ordinal);
        boolean shown = false;
        for (int i = 0; i < list.getChildCount(); i++) {
            TextView child = (TextView) list.getChildAt(i);
            if ("Best Café in town".contentEquals(child.getText())) {
                shown = child.getTop() >= 52 && child.getBottom() <= HEIGHT;
                assertEquals("outlined where the row is", child.getTop() - 2, search.layer.mark.top, 0.5f);
            }
        }
        assertTrue("the comment is under the bar, in view", shown);
    }

    @Test
    public void downAndUpStepThroughMatchesAfterTheirRowsWereRecycled() {
        Map<Integer, String> special = new HashMap<>();
        special.put(3, "my dog Rex");
        special.put(9, "dog again");
        special.put(15, "the DOG");
        CommentSearch.Search search = sheetWith(twenty(special).toArray(new String[0]));
        type(search, "Dog");
        assertEquals("1/1", outlined(search));

        step(search, true);
        assertEquals("2/2", outlined(search));
        step(search, true);
        assertEquals("3/3", outlined(search));
        assertEquals(15, firstMatchRow(search));

        step(search, true);
        assertEquals("the end of the list", "No more matches", search.count.getText().toString());
        assertTrue("back on the last match", search.layer.marking);
        assertEquals(15, firstMatchRow(search));

        step(search, false);
        assertEquals("2/3", outlined(search));
        assertEquals(9, firstMatchRow(search));
    }

    /** Which row of the list the outlined match is. */
    private int firstMatchRow(CommentSearch.Search search) {
        float middle = (search.layer.mark.top + search.layer.mark.bottom) / 2f;
        int[] layerAt = new int[2];
        int[] listAt = new int[2];
        search.layer.getLocationInWindow(layerAt);
        list.getLocationInWindow(listAt);
        float inList = middle + layerAt[1] - listAt[1];
        return (int) ((inList + list.offset) / Rows.ROW);
    }

    @Test
    public void noMatchSaysSoAtTheEnd() {
        CommentSearch.Search search = sheetWith(twenty(new HashMap<>()).toArray(new String[0]));
        type(search, "zebra");
        assertEquals("No matches", search.count.getText().toString());
        assertFalse(search.layer.marking);
    }

    @Test
    public void onlyTextARowDrawsCounts() {
        Map<Integer, String> special = new HashMap<>();
        special.put(2, "label:Like Rex's comment");
        CommentSearch.Search search = sheetWith(twenty(special).toArray(new String[0]));
        type(search, "rex");
        assertEquals("a screen reader's label isn't a comment's text", "No matches", search.count.getText().toString());
    }

    @Test
    public void closingTheBarTakesTheWordsAndTheOutlineAway() {
        Map<Integer, String> special = new HashMap<>();
        special.put(1, "hello there");
        CommentSearch.Search search = sheetWith(twenty(special).toArray(new String[0]));
        type(search, "hello");
        assertEquals("1/1", outlined(search));

        search.closeBar();
        search.place();
        assertFalse(search.open);
        assertEquals("", search.words);
        assertEquals("", search.field.getText().toString());
        assertFalse(search.layer.marking);
        assertEquals(View.VISIBLE, search.magnifier.getVisibility());
        assertEquals(View.GONE, search.bar.getVisibility());
    }

    /** Lays the list out [height] tall, [top] down the sheet, as the keyboard squeezing it would. */
    private void listAt(int top, int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) list.getLayoutParams();
        params.topMargin = top;
        params.height = height;
        list.setLayoutParams(params);
        idle(50);
    }

    @Test
    public void aListTheKeyboardSqueezesKeepsTheBarAndTheSearchKeyFindsTheComment() {
        Map<Integer, String> special = new HashMap<>();
        special.put(12, "snow on the hills");
        CommentSearch.Search search = sheetWith(twenty(special).toArray(new String[0]));
        search.openBar();
        idle(50);
        search.place();
        assertTrue("the box has the focus", search.field.isFocused());
        InputMethodManager keyboard = (InputMethodManager)
                search.field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        assertTrue("the keyboard came up", shadowOf(keyboard).isSoftInputVisible());

        listAt(150, 27);
        search.place();
        assertEquals("the bar stays", View.VISIBLE, search.bar.getVisibility());
        assertEquals("above the list", 150 - 40 - 4, search.bar.getTranslationY(), 0.5f);
        assertTrue("the box keeps the focus", search.field.isFocused());

        search.field.setText("snow");
        idle(CommentSearch.TYPING_PAUSE_MS + 6000);
        search.place();
        assertEquals("a sliver of list isn't paged through", "", search.count.getText().toString());

        search.field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertFalse("the search key closes the keyboard", shadowOf(keyboard).isSoftInputVisible());
        listAt(0, HEIGHT);
        idle(6000);
        search.place();
        assertEquals("1/1", outlined(search));
        assertEquals("back at the top of the list", 4, search.bar.getTranslationY(), 0.5f);
        assertEquals(12, firstMatchRow(search));
    }

    /** A Litho host the 585 way: getTextContent lists TextContent objects, not the text. */
    public static final class LithoHost extends FrameLayout {
        final List<Object> content;

        public LithoHost(Context context, Object... content) {
            super(context);
            this.content = Arrays.asList(content);
        }

        public List<Object> getTextContent() {
            return content;
        }
    }

    /** Litho's TextContent on 585: getItems is kept by name, the text list's getter isn't. */
    public static final class TextContent {
        final List<Object> items;

        TextContent(Object... items) {
            this.items = Arrays.asList(items);
        }

        public List<Object> getItems() {
            return items;
        }

        @Override
        public String toString() {
            return "com.facebook.litho.TextContent@1f2e3d";
        }
    }

    /** One of a TextContent's items, whose getText is kept by name. */
    public static final class TextItem {
        final CharSequence text;

        TextItem(CharSequence text) {
            this.text = text;
        }

        public CharSequence getText() {
            return text;
        }
    }

    /** Rendercore's text view: a ViewGroup, not a TextView, with a kept getText. */
    public static final class RenderText extends ViewGroup {
        final CharSequence text;

        public RenderText(Context context, CharSequence text) {
            super(context);
            this.text = text;
        }

        public CharSequence getText() {
            return text;
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }
    }

    @Test
    public void readsTheTextACommentDrawsThe585Way() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout drawable = new FrameLayout(context);
        LithoHost host = new LithoHost(context,
                new TextContent(new TextItem("Ian Hoare")), new TextContent(new TextItem("Prolonged drought Thats the problem.")));
        drawable.addView(host);
        assertEquals("the items' text, not the objects'", "Ian Hoare\nProlonged drought Thats the problem.",
                CommentSearch.ownText(host).toString());
        assertSame(host, CommentSearch.hitIn(drawable, "drought"));
        assertNull(CommentSearch.hitIn(drawable, "textcontent"));

        FrameLayout viewed = new FrameLayout(context);
        RenderText text = new RenderText(context, "lean up the INDIAN Ocean and the snow will come back.");
        viewed.addView(text);
        assertSame(text, CommentSearch.hitIn(viewed, "snow"));
    }

    @Test
    public void wordsAreMatchedWithoutCaseOrAccents() {
        assertEquals("cafe elan", CommentSearch.fold("Café ÉLAN"));
        assertEquals("", CommentSearch.fold(null));
    }

    @Test
    public void aListWrappedInAOneRowListIsTheInnerOne() {
        Context context = RuntimeEnvironment.getApplication();
        RecyclerView outer = new RecyclerView(context, false);
        Rows inner = new Rows(context, "a", "b", "c");
        outer.addView(inner);
        FrameLayout root = new FrameLayout(context);
        root.addView(outer);
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
        assertSame(inner, CommentSearch.findList(root));
    }

    @Test
    public void offASheetIsLeftAlone() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(screen);
        content.addView(new Rows(screen, "a", "b"));
        screen.setContentView(content);
        CommentSearch.sheetOpened(content);
        idle(50);
        assertFalse(CommentSearch.watching(content));
        assertNull(CommentSearch.searchOf(content));
        ViewGroup frame = screen.findViewById(android.R.id.content);
        assertEquals("nothing was added over the sheet", 1, frame.getChildCount());
    }
}
