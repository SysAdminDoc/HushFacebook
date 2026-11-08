/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Search comments (#420): a magnifier at the top of a comment sheet's list opens a search box that
 * finds the comments with your words and steps through them.
 *
 * <p>Open comments at full height hands {@link #sheetOpened} the views of every comment sheet as
 * Facebook sets them up. With the switch on, a layer of our own goes over the sheet's window. It
 * holds nothing but a magnifier kept at the top corner of the comment list, beside All comments, so
 * every other touch passes through to the sheet. A tap on it opens a bar there with a search box, a
 * count, up, down and close.
 *
 * <p>Facebook only keeps the comments on screen as views, so a search scrolls the list itself:
 * from the top, a page at a time, until a comment draws the words, whatever their case or accents.
 * Only text a view draws counts, not the labels screen readers get, since Facebook names every Like
 * and Reply button after the comment's writer. The comment found is brought into view and outlined.
 * Down and the keyboard's search key go on to the next one and up goes back. At the end of what
 * Facebook has loaded, after a short wait for more, the bar says there are no more and the list goes
 * back to the comment found last. The count is
 * the matches seen so far, so it's exact once the search has reached the end.
 *
 * <p>Off, nothing is added.
 */
public final class CommentSearch {
    /** The member the report names once a comment sheet has been handed over with the switch on. */
    static final String SHEET = "comment sheet search";

    /** What the report counts each time a search starts over a sheet's comments. */
    static final String SEARCHED = "Comments searched";

    /** What the report counts each time a search brings a comment with the words into view. */
    static final String FOUND = "Comment found by a search";

    /** The kept name of the list the comments are drawn in; Facebook's own lists extend it. */
    static final String LIST = "androidx.recyclerview.widget.RecyclerView";

    /** How far one page scrolls, as a share of the list's height shown. */
    static final float PAGE = 0.8f;

    /** How many pages one step scrolls looking for a match before it stops and says so. */
    static final int MAX_PAGES = 40;

    /** How long typing has to pause before the search runs. */
    static final long TYPING_PAUSE_MS = 400;

    /** How long the end of the list is given to load more comments. */
    static final long MORE_WAIT_MS = 1500;

    /** How often the end of the list is looked at again while more comments may be loading. */
    static final long MORE_POLL_MS = 250;

    /** The gap between a page scrolled and the next look, so the list can draw what came in. */
    static final long PAGE_SETTLE_MS = 50;

    /** How often a sheet with no list in it yet is looked through again. */
    static final long LIST_RETRY_MS = 250;

    /** How often the list is looked at for its room back after the search key closed the keyboard. */
    static final long ROOM_POLL_MS = 50;

    /** How many times, so a list that never grows back is still searched, a sliver at a time. */
    static final int ROOM_POLLS = 12;

    // Sizes, in dp. The bar fits the All comments row, 48 dp tall, so it doesn't cover a comment.
    private static final int EDGE_DP = 4;
    private static final int BAR_DP = 40;
    private static final int BUTTON_DP = 36;
    private static final int ICON_PAD_DP = 9;
    private static final int GAP_DP = 8;

    private static final int BAR_COLOR = 0xF2303133;
    private static final int TEXT_COLOR = 0xFFE4E6EB;
    private static final int HINT_COLOR = 0xFFB0B3B8;
    private static final int MARK_EDGE = 0xFF0866FF;
    private static final int MARK_FILL = 0x2E0866FF;

    private static final String FAMILY = FamilyNames.FULL_HEIGHT_COMMENTS;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Object NO_METHOD = new Object();

    /** Each view class's getTextContent, or {@link #NO_METHOD} for a class without one. */
    private static final Map<Class<?>, Object> TEXT_CONTENT = new ConcurrentHashMap<>();

    /** Each class's getItems, the kept name of a Litho TextContent's list, or {@link #NO_METHOD}. */
    private static final Map<Class<?>, Object> ITEMS = new ConcurrentHashMap<>();

    /** Each class's getText returning text, or {@link #NO_METHOD} for a class without one. */
    private static final Map<Class<?>, Object> GET_TEXT = new ConcurrentHashMap<>();

    /** The comment sheets handed over, by their views, weakly. */
    private static final Map<View, Search> SHEETS = Collections.synchronizedMap(new WeakHashMap<>());

    @Nullable private static Handler main;

    private CommentSearch() {
    }

    /**
     * Called where Facebook sets up a comment sheet's views, whatever Open comments at full height
     * says. With the switch on, the sheet gets the magnifier once its views are on screen.
     */
    public static void sheetOpened(@Nullable View content) {
        try {
            if (content == null || !on()) return;
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET);
            Search search;
            synchronized (SHEETS) {
                if (SHEETS.containsKey(content)) return;
                search = new Search(content);
                SHEETS.put(content, search);
            }
            content.addOnAttachStateChangeListener(search);
            if (content.isAttachedToWindow()) later(search::attach, 0);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, SHEET, failure);
        }
    }

    /** Whether [content], a comment sheet's views, was handed over and gets the magnifier. */
    static boolean watching(View content) {
        return SHEETS.containsKey(content);
    }

    /** The search kept for [content], or null. For tests. */
    @Nullable
    static Search searchOf(View content) {
        return SHEETS.get(content);
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.SEARCH_COMMENTS.get();
    }

    private static void later(Runnable task, long delay) {
        Handler handler = main;
        if (handler == null) handler = main = new Handler(Looper.getMainLooper());
        handler.postDelayed(task, delay);
    }

    private static void drop(Runnable task) {
        Handler handler = main;
        if (handler != null) handler.removeCallbacks(task);
    }

    /** [text] the way it's matched: lower case, with accents and other marks dropped, so "Café" is "cafe". */
    static String fold(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) return "";
        String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return plain.toLowerCase(Locale.ROOT);
    }

    /**
     * The text [view] draws itself, or null: a TextView's text, a rendercore text view's (a ViewGroup
     * with a kept getText, which 585 draws comments in), or a Litho host's text. On 585 a host lists
     * TextContent objects rather than the text, each one's getItems holding items with a getText.
     */
    @Nullable
    static CharSequence ownText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return TextUtils.isEmpty(text) ? null : text;
        }
        if (!(view instanceof ViewGroup)) return null;
        StringBuilder all = new StringBuilder();
        add(all, textOf(view));
        Object items = call(TEXT_CONTENT, view, "getTextContent");
        if (items instanceof List) {
            for (Object item : (List<?>) items) addItem(all, item);
        }
        return all.length() == 0 ? null : all.toString();
    }

    /** Adds the text of one thing a Litho host lists: text itself, a TextContent's items, or an item's text. */
    private static void addItem(StringBuilder all, @Nullable Object item) {
        if (item == null) return;
        if (item instanceof CharSequence) {
            add(all, (CharSequence) item);
            return;
        }
        Object parts = call(ITEMS, item, "getItems");
        if (parts instanceof List) {
            for (Object part : (List<?>) parts) {
                if (part instanceof CharSequence) add(all, (CharSequence) part);
                else if (part != null) add(all, textOf(part));
            }
            return;
        }
        add(all, textOf(item));
    }

    private static void add(StringBuilder all, @Nullable CharSequence text) {
        if (TextUtils.isEmpty(text)) return;
        if (all.length() > 0) all.append('\n');
        all.append(text);
    }

    /** What [target]'s getText hands back when it's text, or null. */
    @Nullable
    private static CharSequence textOf(Object target) {
        Object text = call(GET_TEXT, target, "getText");
        return text instanceof CharSequence ? (CharSequence) text : null;
    }

    /**
     * What [target]'s public no-argument method [name] hands back, or null when its class has none or
     * it throws. Each class's method is looked up once and kept in [cache].
     */
    @Nullable
    private static Object call(Map<Class<?>, Object> cache, Object target, String name) {
        Class<?> type = target.getClass();
        Object method = cache.get(type);
        if (method == null) {
            try {
                Method found = type.getMethod(name);
                try {
                    // A public method of a class that isn't public can't be called without this.
                    found.setAccessible(true);
                } catch (RuntimeException e) {
                    // Left as it is: a public method of a public class still calls.
                }
                method = found;
            } catch (NoSuchMethodException | RuntimeException e) {
                method = NO_METHOD;
            }
            cache.put(type, method);
        }
        if (method == NO_METHOD) return null;
        try {
            return ((Method) method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The view in [row] whose own text holds [words], already folded, deepest first, or null.
     * Only what a view draws counts, not what it tells a screen reader.
     */
    @Nullable
    static View hitIn(View row, String words) {
        if (words.isEmpty() || row.getVisibility() != View.VISIBLE) return null;
        if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = hitIn(group.getChildAt(i), words);
                if (hit != null) return hit;
            }
        }
        CharSequence text = ownText(row);
        return text != null && fold(text).contains(words) ? row : null;
    }

    /** Whether [view] is a RecyclerView, by its class name. */
    static boolean isList(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            if (LIST.equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * The list in [root] the comments are drawn in: of the RecyclerViews shown that are at least a
     * third as tall as [root], the one with the most rows, the deeper one on a tie, since a sheet
     * can wrap its list in a one-row list of the same size. Null when there's none.
     */
    @Nullable
    static ViewGroup findList(View root) {
        return bestList(root, Math.max(1, root.getHeight() / 3), null);
    }

    @Nullable
    private static ViewGroup bestList(View view, int tall, @Nullable ViewGroup best) {
        if (!(view instanceof ViewGroup) || view.getVisibility() != View.VISIBLE) return best;
        ViewGroup group = (ViewGroup) view;
        if (isList(group) && group.getHeight() >= tall
                && (best == null || group.getChildCount() >= best.getChildCount())) {
            best = group;
        }
        for (int i = 0; i < group.getChildCount(); i++) best = bestList(group.getChildAt(i), tall, best);
        return best;
    }

    /** [list]'s rows that are shown, top first. */
    static List<View> rows(ViewGroup list) {
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getVisibility() == View.VISIBLE && row.getHeight() > 0) rows.add(row);
        }
        Collections.sort(rows, (a, b) -> Integer.compare(a.getTop(), b.getTop()));
        return rows;
    }

    /** [view]'s top edge, in pixels down from [list]'s top edge. */
    static int topIn(View view, View list) {
        int[] at = new int[2];
        int[] base = new int[2];
        view.getLocationInWindow(at);
        list.getLocationInWindow(base);
        return at[1] - base[1];
    }

    /** One comment sheet's search: its magnifier, its bar, and the comment it found last. */
    static final class Search implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> content;
        @Nullable Layer layer;
        @Nullable ImageView magnifier;
        @Nullable LinearLayout bar;
        @Nullable EditText field;
        @Nullable TextView count;
        @Nullable private ViewTreeObserver observer;
        private WeakReference<ViewGroup> list = new WeakReference<>(null);
        /** When the list was last looked for, or MIN_VALUE before the first look, which never waits. */
        private long listLooked = Long.MIN_VALUE;
        private float density = 1f;

        boolean open;
        /** The words searched for, folded, or empty. */
        String words = "";
        /** The row of the comment found last, the view in it with the words, and that view's text then. */
        private WeakReference<View> row = new WeakReference<>(null);
        private WeakReference<View> hit = new WeakReference<>(null);
        @Nullable private String hitText;
        /** Which match the one found last is, counted from the top, and how many have been seen. */
        int ordinal;
        int seen;
        /** Whether the count holds: false once the list was scrolled away from the match by hand. */
        private boolean counting;
        /** Each step takes a number, and a later one stops it. */
        private int walk;
        /** How far the step under way has scrolled the list, down positive, to go back when it finds nothing. */
        private int walked;
        /** Where the comment found last sat in the list when the step under way began, or MIN_VALUE. */
        private int hitTop = Integer.MIN_VALUE;
        private boolean quiet;

        /**
         * A pause in typing searches, unless the keyboard has squeezed the list to a sliver: a look
         * there pages a few pixels at a time, so the search waits for the search key.
         */
        private final Runnable typed = () -> {
            if (roomy(list(false))) start();
        };

        Search(View content) {
            this.content = new WeakReference<>(content);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            later(this::attach, 0);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                walk++;
                drop(typed);
                hideKeyboard();
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                observer = null;
                // Never here: Back detaches the frame holding the sheet and the layer in one pass, and
                // a child taken out mid-pass leaves a null in that frame's child array.
                later(this::detach, 0);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "comment search closed", failure);
            }
        }

        /** Takes the layer off its frame once the detach under way ended, unless the sheet came back. */
        void detach() {
            try {
                View sheet = content.get();
                if (sheet != null && sheet.isAttachedToWindow()) return;
                Layer shown = layer;
                if (shown != null && shown.getParent() instanceof ViewGroup) {
                    ((ViewGroup) shown.getParent()).removeView(shown);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "comment search closed", failure);
            }
        }

        /** Puts the layer over the sheet's window and starts keeping it in place. */
        void attach() {
            try {
                View sheet = content.get();
                if (sheet == null || !sheet.isAttachedToWindow() || !on()) return;
                // The window's content frame, where overlays go, or the window's root.
                View root = sheet.getRootView().findViewById(android.R.id.content);
                if (!(root instanceof FrameLayout)) root = sheet.getRootView();
                if (!(root instanceof FrameLayout)) return;
                if (layer == null) build(sheet.getContext());
                Layer shown = layer;
                if (shown.getParent() != root) {
                    if (shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown);
                    ((FrameLayout) root).addView(shown, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                ViewTreeObserver now = sheet.getViewTreeObserver();
                if (observer != now) {
                    if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                    observer = now;
                    now.addOnPreDrawListener(this);
                }
                place();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, SHEET, failure);
            }
        }

        @Override
        public boolean onPreDraw() {
            try {
                place();
            } catch (Throwable failure) {
                // Once is enough: the sheet goes on without its magnifier.
                HookStatus.threw(FAMILY, "comment search layout", failure);
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                observer = null;
                if (layer != null) layer.setVisibility(View.GONE);
            }
            return true;
        }

        private int px(int dp) {
            return Math.round(dp * density);
        }

        /** Makes the layer, the magnifier and the bar. */
        private void build(Context context) {
            density = context.getResources().getDisplayMetrics().density;
            Layer made = new Layer(context, density);

            ImageView glass = new ImageView(context);
            glass.setImageDrawable(new Glyph(Glyph.GLASS, TEXT_COLOR, 2f * density));
            glass.setBackground(round(BAR_COLOR, BUTTON_DP));
            glass.setPadding(px(ICON_PAD_DP), px(ICON_PAD_DP), px(ICON_PAD_DP), px(ICON_PAD_DP));
            glass.setContentDescription(L10n.t("Search comments"));
            glass.setOnClickListener(v -> openBar());
            made.addView(glass, new FrameLayout.LayoutParams(px(BUTTON_DP), px(BUTTON_DP)));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(round(BAR_COLOR, BAR_DP));
            row.setPadding(px(12), 0, px(4), 0);
            row.setVisibility(View.GONE);
            // A tap on the bar's empty parts stays on the bar.
            row.setClickable(true);

            EditText box = new EditText(context);
            box.setSingleLine(true);
            box.setInputType(InputType.TYPE_CLASS_TEXT);
            box.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            box.setHint(L10n.t("Search comments"));
            box.setTextColor(TEXT_COLOR);
            box.setHintTextColor(HINT_COLOR);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            box.setBackground(null);
            box.setPadding(0, 0, 0, 0);
            box.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (quiet) return;
                    drop(typed);
                    later(typed, TYPING_PAUSE_MS);
                }
            });
            box.setOnEditorActionListener((v, action, event) -> {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (action != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
                if (enter && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                searchKey();
                return true;
            });
            row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            TextView number = new TextView(context);
            number.setTextColor(HINT_COLOR);
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            number.setSingleLine(true);
            number.setPadding(px(6), 0, px(4), 0);
            row.addView(number, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(button(context, Glyph.UP, L10n.t("Previous match"), v -> step(false)));
            row.addView(button(context, Glyph.DOWN, L10n.t("Next match"), v -> step(true)));
            row.addView(button(context, Glyph.CLOSE, L10n.t("Close search"), v -> closeBar()));
            made.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(BAR_DP)));

            layer = made;
            magnifier = glass;
            bar = row;
            field = box;
            count = number;
        }

        private ImageView button(Context context, int shape, String label, View.OnClickListener click) {
            ImageView button = new ImageView(context);
            button.setImageDrawable(new Glyph(shape, TEXT_COLOR, 2f * density));
            button.setPadding(px(ICON_PAD_DP), px(ICON_PAD_DP), px(ICON_PAD_DP), px(ICON_PAD_DP));
            button.setContentDescription(label);
            button.setOnClickListener(click);
            button.setLayoutParams(new LinearLayout.LayoutParams(px(BUTTON_DP), px(BUTTON_DP)));
            return button;
        }

        private GradientDrawable round(int color, int heightDp) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color);
            shape.setCornerRadius(heightDp * density / 2f);
            return shape;
        }

        /** The comment list, found again when the one kept has left the screen. */
        @Nullable
        private ViewGroup list(boolean fresh) {
            ViewGroup kept = list.get();
            if (!fresh && kept != null && kept.isAttachedToWindow() && kept.isShown()) return kept;
            long now = SystemClock.uptimeMillis();
            if (!fresh && listLooked != Long.MIN_VALUE && now - listLooked < LIST_RETRY_MS) {
                return kept != null && kept.isShown() ? kept : null;
            }
            listLooked = now;
            View sheet = content.get();
            ViewGroup found = sheet == null ? null : findList(sheet);
            list = new WeakReference<>(found);
            return found;
        }

        /** Whether [comments] is on screen with room for the bar and a comment or two under it. */
        private boolean roomy(@Nullable ViewGroup comments) {
            return comments != null && comments.isShown() && comments.getHeight() >= px(BAR_DP) * 3;
        }

        /**
         * Keeps the magnifier or the bar at the top of the list, and the outline on the comment found.
         * An open bar stays while the keyboard squeezes the list to a sliver, as Facebook's sheet does
         * with a landscape video over it (S22 2026-11-08, 27 px left): it goes above the list instead,
         * since hiding it took the focus from its box and the words typed went nowhere.
         */
        void place() {
            Layer shown = layer;
            if (shown == null || magnifier == null || bar == null) return;
            ViewGroup comments = list(false);
            boolean room = roomy(comments);
            if (comments == null || !comments.isShown() || (!room && !open)) {
                show(magnifier, false);
                show(bar, false);
                shown.unmark();
                return;
            }
            int[] at = new int[2];
            int[] base = new int[2];
            comments.getLocationInWindow(at);
            shown.getLocationInWindow(base);
            float left = at[0] - base[0];
            float top = at[1] - base[1];
            int width = comments.getWidth();
            boolean rtl = comments.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            if (open) {
                show(magnifier, false);
                show(bar, true);
                int wide = width - 2 * px(GAP_DP);
                ViewGroup.LayoutParams params = bar.getLayoutParams();
                if (params.width != wide) {
                    params.width = wide;
                    bar.setLayoutParams(params);
                }
                move(bar, left + px(GAP_DP),
                        room ? top + px(EDGE_DP) : Math.max(0f, top - px(BAR_DP) - px(EDGE_DP)));
            } else {
                show(bar, false);
                show(magnifier, true);
                float x = rtl ? left + px(GAP_DP) : left + width - px(GAP_DP) - px(BUTTON_DP);
                move(magnifier, x, top + (px(48) - px(BUTTON_DP)) / 2f);
            }
            View found = hit.get();
            if (!open || found == null || !found.isAttachedToWindow() || !found.isShown()
                    || !TextUtils.equals(ownTextString(found), hitText)) {
                shown.unmark();
                return;
            }
            int[] spot = new int[2];
            found.getLocationInWindow(spot);
            float l = spot[0] - base[0] - px(2);
            float t = Math.max(spot[1] - base[1] - px(2), top);
            float r = spot[0] - base[0] + found.getWidth() + px(2);
            float b = Math.min(spot[1] - base[1] + found.getHeight() + px(2), top + comments.getHeight());
            if (b <= t) shown.unmark();
            else shown.mark(l, t, r, b);
        }

        private void show(View view, boolean shown) {
            int want = shown ? View.VISIBLE : View.GONE;
            if (view.getVisibility() != want) view.setVisibility(want);
        }

        private void move(View view, float x, float y) {
            if (view.getTranslationX() != x) view.setTranslationX(x);
            if (view.getTranslationY() != y) view.setTranslationY(y);
        }

        /** Opens the bar with the keyboard up. */
        void openBar() {
            EditText box = field;
            if (box == null) return;
            open = true;
            place();
            box.requestFocus();
            box.post(() -> {
                InputMethodManager keyboard = (InputMethodManager)
                        box.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        /** Closes the bar: the words, the outline and the keyboard go, and the magnifier comes back. */
        void closeBar() {
            open = false;
            walk++;
            drop(typed);
            words = "";
            forget();
            say("");
            hideKeyboard();
            EditText box = field;
            if (box != null) {
                quiet = true;
                box.setText("");
                quiet = false;
                box.clearFocus();
            }
            place();
        }

        /**
         * The keyboard's search key: the keyboard goes, so the list gets its room back and the comment
         * found shows outlined, and once the list has grown back, on to the next match.
         */
        void searchKey() {
            drop(typed);
            hideKeyboard();
            waitForRoom(++walk, 0);
        }

        private void waitForRoom(int token, int polls) {
            if (token != walk || !open) return;
            if (polls < ROOM_POLLS && !roomy(list(false))) {
                later(() -> waitForRoom(token, polls + 1), ROOM_POLL_MS);
                return;
            }
            step(true);
        }

        private void hideKeyboard() {
            EditText box = field;
            if (box == null || box.getWindowToken() == null) return;
            InputMethodManager keyboard = (InputMethodManager)
                    box.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(box.getWindowToken(), 0);
        }

        private void forget() {
            row = new WeakReference<>(null);
            hit = new WeakReference<>(null);
            hitText = null;
            ordinal = 0;
            seen = 0;
            counting = false;
        }

        private void say(CharSequence text) {
            if (count != null) count.setText(text);
        }

        private String typedWords() {
            return field == null ? "" : fold(field.getText().toString().trim());
        }

        /** A new search, from the top of the list. */
        void start() {
            try {
                drop(typed);
                walk++;
                words = typedWords();
                forget();
                say("");
                if (words.isEmpty()) return;
                HookStatus.counted(FAMILY, SEARCHED);
                ViewGroup comments = list(true);
                if (comments == null) {
                    say(L10n.t("No matches"));
                    return;
                }
                counting = true;
                toTop(comments);
                walked = 0;
                hitTop = Integer.MIN_VALUE;
                int token = walk;
                later(() -> look(token, true, 0, 0), PAGE_SETTLE_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "comment search", failure);
            }
        }

        /** On to the next match below the one found last, or [down] false, the one above it. */
        void step(boolean down) {
            try {
                if (!typedWords().equals(words) || words.isEmpty()) {
                    start();
                    return;
                }
                walk++;
                walked = 0;
                ViewGroup comments = list(true);
                View found = hit.get();
                hitTop = comments != null && found != null && lastRow(comments) != null
                        ? topIn(found, comments) : Integer.MIN_VALUE;
                look(walk, down, 0, 0);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "comment search", failure);
            }
        }

        /** Scrolls [comments] back to its first row. */
        private void toTop(ViewGroup comments) {
            try {
                comments.getClass().getMethod("scrollToPosition", int.class).invoke(comments, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                for (int i = 0; i < MAX_PAGES && comments.canScrollVertically(-1); i++) {
                    comments.scrollBy(0, -comments.getHeight());
                }
            }
        }

        /**
         * One look over the rows shown for the next match [down] or up from the one found last, and
         * with none, a page scrolled and another look, [pages] in so far; at the end of the list,
         * the time [waited] so far for Facebook to load more.
         */
        void look(int token, boolean down, int pages, long waited) {
            try {
                if (token != walk || !open || words.isEmpty()) return;
                View sheet = content.get();
                ViewGroup comments = list(true);
                if (sheet == null || comments == null || !sheet.isAttachedToWindow()) return;
                List<View> shown = rows(comments);
                if (!down) Collections.reverse(shown);
                View last = lastRow(comments);
                if (last == null && pages == 0 && ordinal > 0) counting = false;
                boolean past = last == null;
                int bottom = shownBottom(comments);
                for (View candidate : shown) {
                    if (!past) {
                        past = candidate == last;
                        continue;
                    }
                    if (candidate.getBottom() <= comments.getPaddingTop() || candidate.getTop() >= bottom) continue;
                    View found = hitIn(candidate, words);
                    if (found != null) {
                        pick(comments, candidate, found, down);
                        return;
                    }
                }
                if (pages < MAX_PAGES && comments.canScrollVertically(down ? 1 : -1)) {
                    int page = Math.round(PAGE * (bottom - comments.getPaddingTop()));
                    scroll(comments, down ? page : -page);
                    later(() -> look(token, down, pages + 1, 0), PAGE_SETTLE_MS);
                    return;
                }
                if (down && pages < MAX_PAGES && waited < MORE_WAIT_MS) {
                    // Facebook loads the next comments once the end of the list comes into view.
                    later(() -> look(token, true, pages, waited + MORE_POLL_MS), MORE_POLL_MS);
                    return;
                }
                if (pages >= MAX_PAGES) {
                    // Left where it got to, so the next step carries on from there.
                    say(L10n.t("Not found yet"));
                    return;
                }
                back(comments);
                if (hit.get() == null && seen == 0) say(L10n.t("No matches"));
                else if (down) say(L10n.t("No more matches"));
                else sayCount();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "comment search", failure);
            }
        }

        /**
         * Scrolls [comments] by [by] and adds how far it really went to {@link #walked}, measured on
         * the row at the edge it scrolls toward, which a page leaves on screen.
         */
        private void scroll(ViewGroup comments, int by) {
            List<View> shown = rows(comments);
            View anchor = shown.isEmpty() ? null : shown.get(by > 0 ? shown.size() - 1 : 0);
            int before = anchor == null ? 0 : topIn(anchor, comments);
            comments.scrollBy(0, by);
            walked += anchor != null && anchor.getParent() == comments ? before - topIn(anchor, comments) : by;
        }

        /**
         * After a step that found nothing new, puts the list back where the step began and takes the
         * comment found last again, the row with its text nearest where it was, since the list may
         * have drawn it in another view on the way back.
         */
        private void back(ViewGroup comments) {
            if (walked != 0) comments.scrollBy(0, -walked);
            walked = 0;
            if (hitTop == Integer.MIN_VALUE || hitText == null || lastRow(comments) != null) return;
            View bestRow = null;
            View best = null;
            int gap = Integer.MAX_VALUE;
            for (View candidate : rows(comments)) {
                View found = hitIn(candidate, words);
                if (found == null || !TextUtils.equals(ownTextString(found), hitText)) continue;
                int off = Math.abs(topIn(found, comments) - hitTop);
                if (off < gap) {
                    gap = off;
                    bestRow = candidate;
                    best = found;
                }
            }
            if (best == null) return;
            row = new WeakReference<>(bestRow);
            hit = new WeakReference<>(best);
            Layer shown = layer;
            if (shown != null) shown.invalidate();
        }

        /** The row of the comment found last while [comments] still shows that comment in it, or null. */
        @Nullable
        private View lastRow(ViewGroup comments) {
            View kept = row.get();
            View found = hit.get();
            if (kept == null || found == null || kept.getParent() != comments) return null;
            if (!TextUtils.equals(ownTextString(found), hitText)) return null;
            for (ViewParent up = found.getParent(); up instanceof View; up = up.getParent()) {
                if (up == kept) return kept;
            }
            return found == kept ? kept : null;
        }

        /** How far down [comments] its rows can be seen, above its padding and the keyboard. */
        private int shownBottom(ViewGroup comments) {
            int bottom = comments.getHeight() - comments.getPaddingBottom();
            Rect frame = new Rect();
            comments.getWindowVisibleDisplayFrame(frame);
            int[] at = new int[2];
            comments.getLocationOnScreen(at);
            if (frame.bottom > at[1]) bottom = Math.min(bottom, frame.bottom - at[1]);
            return bottom;
        }

        /** Takes [found] in [candidate] as the match, brings it into view and counts it. */
        private void pick(ViewGroup comments, View candidate, View found, boolean down) {
            walked = 0;
            row = new WeakReference<>(candidate);
            hit = new WeakReference<>(found);
            hitText = ownTextString(found);
            if (counting) {
                ordinal += down ? 1 : -1;
                if (ordinal < 1) counting = false;
            }
            bring(comments, candidate, found);
            if (counting) seen = Math.max(seen, ordinal + hitsBelow(comments, candidate));
            sayCount();
            HookStatus.counted(FAMILY, FOUND);
            Logger.printDebug(() -> "Search comments: match " + ordinal + " of " + seen + " seen");
            Layer shown = layer;
            if (shown != null) shown.invalidate();
        }

        /** Scrolls [comments] so [candidate], or for a tall one the part with the words, shows under the bar. */
        private void bring(ViewGroup comments, View candidate, View found) {
            int top = px(EDGE_DP) + px(BAR_DP) + px(GAP_DP);
            int bottom = shownBottom(comments) - px(GAP_DP);
            View target = candidate.getHeight() <= bottom - top ? candidate : found;
            int y = topIn(target, comments);
            int end = y + target.getHeight();
            if (y < top) comments.scrollBy(0, y - top);
            else if (end > bottom) comments.scrollBy(0, Math.min(y - top, end - bottom));
        }

        /** How many rows shown below [candidate] hold the words. */
        private int hitsBelow(ViewGroup comments, View candidate) {
            int bottom = shownBottom(comments);
            int below = 0;
            boolean past = false;
            for (View other : rows(comments)) {
                if (!past) {
                    past = other == candidate;
                    continue;
                }
                if (other.getTop() >= bottom) break;
                if (hitIn(other, words) != null) below++;
            }
            return below;
        }

        private void sayCount() {
            say(counting && ordinal > 0 ? ordinal + "/" + seen : "");
        }
    }

    @Nullable
    private static String ownTextString(View view) {
        CharSequence text = ownText(view);
        return text == null ? null : text.toString();
    }

    /** The layer over a sheet's window. It draws the outline under its own views and takes no touch itself. */
    static final class Layer extends FrameLayout {
        final RectF mark = new RectF();
        boolean marking;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        Layer(Context context, float density) {
            super(context);
            radius = 8 * density;
            fill.setColor(MARK_FILL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * density);
            edge.setColor(MARK_EDGE);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void mark(float left, float top, float right, float bottom) {
            if (marking && mark.left == left && mark.top == top && mark.right == right && mark.bottom == bottom) return;
            mark.set(left, top, right, bottom);
            marking = true;
            invalidate();
        }

        void unmark() {
            if (!marking) return;
            marking = false;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (marking) {
                canvas.drawRoundRect(mark, radius, radius, fill);
                canvas.drawRoundRect(mark, radius, radius, edge);
            }
            super.dispatchDraw(canvas);
        }
    }

    /** The bar's icons, drawn in code since Facebook's APK has none of ours. */
    static final class Glyph extends Drawable {
        static final int GLASS = 0;
        static final int UP = 1;
        static final int DOWN = 2;
        static final int CLOSE = 3;

        final int shape;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Glyph(int shape, int color, float stroke) {
            this.shape = shape;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect box = getBounds();
            float size = Math.min(box.width(), box.height());
            float x = box.exactCenterX();
            float y = box.exactCenterY();
            path.rewind();
            if (shape == GLASS) {
                float r = size * 0.3f;
                float cx = x - size * 0.08f;
                float cy = y - size * 0.08f;
                canvas.drawCircle(cx, cy, r, paint);
                float reach = r * 0.7071f;
                path.moveTo(cx + reach, cy + reach);
                path.lineTo(x + size * 0.4f, y + size * 0.4f);
            } else if (shape == UP || shape == DOWN) {
                float half = size * 0.3f;
                float rise = (shape == UP ? -1 : 1) * size * 0.15f;
                path.moveTo(x - half, y - rise);
                path.lineTo(x, y + rise);
                path.lineTo(x + half, y - rise);
            } else {
                float half = size * 0.28f;
                path.moveTo(x - half, y - half);
                path.lineTo(x + half, y + half);
                path.moveTo(x + half, y - half);
                path.lineTo(x - half, y + half);
            }
            canvas.drawPath(path, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
