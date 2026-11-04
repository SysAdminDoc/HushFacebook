/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.LinearLayout;
import android.widget.ListView;

/**
 * The settings screen's column: the bar, then the header, the search and the view toggle over the
 * list, which is its last child. HushGram's header and search are rows of its list and scroll away
 * with it. This list's rows are counted and placed by position, so here they sit over it, and the
 * column scrolls them up under the bar first, the way a collapsing app bar does: a drag on the list
 * moves the column until the top has gone, then the list, and a drag down at the top of the list
 * brings the top back. The first {@link #pin pinned} children, the bar and its divider, stay put
 * and draw over what slides under them. The list takes the height below the bar, so with the top
 * gone it reaches the bottom of the screen.
 */
final class HeaderColumn extends LinearLayout {
    private final int touchSlop;
    /** How many children, from the first, stay put while the rest scroll. */
    private int pinned;
    /** Where a drag that started on the top last was, or NaN while there's none. */
    private float dragY = Float.NaN;
    private float downY;
    private boolean dragging;
    /** The top is all the way up, and stays up when it grows or shrinks. */
    private boolean collapsed;

    HeaderColumn(Context context) {
        super(context);
        setOrientation(VERTICAL);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // The pinned children draw last, so they cover what scrolls under them and get a touch first.
        setChildrenDrawingOrderEnabled(true);
    }

    /**
     * Keeps the first [count] children in place. A touch on one of them that none of its buttons
     * takes stays there, rather than reaching a row that slid under it.
     */
    void pin(int count) {
        pinned = count;
        for (int index = 0; index < count && index < getChildCount(); index++) {
            getChildAt(index).setOnTouchListener((view, event) -> true);
        }
    }

    /** Where the pinned children end, in the column's own coordinates. */
    int pinnedBottom() {
        if (pinned == 0 || getChildCount() < pinned) return getPaddingTop();
        View last = getChildAt(pinned - 1);
        return last.getBottom() + ((LayoutParams) last.getLayoutParams()).bottomMargin;
    }

    /** How far the column scrolls: the height of everything between the bar and the list. */
    int range() {
        View list = list();
        return list == null ? 0 : Math.max(0, list.getTop() - pinnedBottom());
    }

    /** Scrolls the top [offset] px up under the bar, held to {@link #range()}. */
    void setOffset(int offset) {
        int range = range();
        int clamped = Math.max(0, Math.min(range, offset));
        collapsed = range > 0 && clamped == range;
        if (clamped != getScrollY()) scrollTo(0, clamped);
    }

    /** Brings the whole top back into view. */
    void expand() {
        setOffset(0);
    }

    /** Takes the whole top up under the bar. */
    void collapse() {
        setOffset(range());
    }

    /**
     * Follows [list]: drags on it move the column through nested scrolling, and a scroll that
     * leaves its top or a fling that reaches it moves the top to match.
     */
    void follow(ListView list) {
        list.setNestedScrollingEnabled(true);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            private int state = SCROLL_STATE_IDLE;

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                state = scrollState;
            }

            @Override
            public void onScroll(AbsListView view, int first, int count, int total) {
                if (!atTop(view)) {
                    // A fling, a jump to a section or a reader moved the list on: the rows own the
                    // screen, so the top can't be halfway.
                    if (!collapsed) collapse();
                } else if (state == SCROLL_STATE_FLING && getScrollY() > 0) {
                    // Flinging back to the first row brings the top with it.
                    expand();
                }
            }
        });
    }

    /** Whether [list] shows its first row from its first pixel. */
    static boolean atTop(AbsListView list) {
        if (list.getChildCount() == 0) return true;
        return list.getFirstVisiblePosition() == 0 && list.getChildAt(0).getTop() >= list.getPaddingTop();
    }

    private View list() {
        int count = getChildCount();
        return count <= pinned ? null : getChildAt(count - 1);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        // Everything over the list takes the height it needs...
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.UNSPECIFIED));
        // ...and the list the height below the bar, which it fills once the top has scrolled away.
        View list = list();
        if (list != null && list.getVisibility() != GONE) {
            int below = height - getPaddingTop() - getPaddingBottom();
            for (int index = 0; index < pinned; index++) {
                View child = getChildAt(index);
                if (child.getVisibility() == GONE) continue;
                LayoutParams place = (LayoutParams) child.getLayoutParams();
                below -= child.getMeasuredHeight() + place.topMargin + place.bottomMargin;
            }
            LayoutParams place = (LayoutParams) list.getLayoutParams();
            int width = getMeasuredWidth() - getPaddingLeft() - getPaddingRight() - place.leftMargin - place.rightMargin;
            list.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, below - place.topMargin - place.bottomMargin), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(getMeasuredWidth(), MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? getMeasuredHeight() : height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // A search line coming or going changes how far the top goes.
        int range = range();
        if (collapsed && getScrollY() != range) scrollTo(0, range);
        else if (getScrollY() > range) scrollTo(0, range);
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        // The pinned children ride along with the scroll, so on screen they don't move.
        for (int index = 0; index < pinned && index < getChildCount(); index++) {
            getChildAt(index).setTranslationY(top);
        }
        super.onScrollChanged(left, top, oldLeft, oldTop);
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        int shown = Math.min(pinned, childCount);
        int scrolled = childCount - shown;
        return drawingPosition < scrolled ? shown + drawingPosition : drawingPosition - scrolled;
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (indexOfChild(child) < pinned) return super.drawChild(canvas, child, drawingTime);
        // What slides up stops at the bar's lower edge.
        int saved = canvas.save();
        canvas.clipRect(getScrollX(), getScrollY() + pinnedBottom(), getScrollX() + getWidth(), getScrollY() + getHeight());
        boolean more = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(saved);
        return more;
    }

    /** Keeps whatever asks to be seen in view below the bar: the focused search, a reader's row, a jump. */
    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (indexOfChild(child) >= 0 && indexOfChild(child) < pinned) return false;
        int top = child.getTop() + rectangle.top;
        int bottom = child.getTop() + rectangle.bottom;
        int shownTop = getScrollY() + pinnedBottom();
        int shownBottom = getScrollY() + getHeight() - getPaddingBottom();
        int move = 0;
        if (top < shownTop) move = top - shownTop;
        else if (bottom > shownBottom) move = Math.min(bottom - shownBottom, top - shownTop);
        if (move == 0) return false;
        int before = getScrollY();
        setOffset(before + move);
        return getScrollY() != before;
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int axes) {
        return (axes & SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        // Rows moving up take the top with them first.
        if (dy <= 0) return;
        int before = getScrollY();
        setOffset(before + dy);
        consumed[1] = getScrollY() - before;
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
        // A pull down past the first row brings the top back.
        if (dyUnconsumed < 0) setOffset(getScrollY() + dyUnconsumed);
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }

    /** Whether a press at [y] on screen lands between the bar and the list. */
    private boolean onTheTop(float y) {
        View list = list();
        return y >= pinnedBottom() && (list == null || y + getScrollY() < list.getTop());
    }

    /** A drag that starts on the header or the search moves the column itself. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downY = event.getY();
                // On the bar, its buttons lead; over the list, the list's own scroll does.
                dragY = onTheTop(event.getY()) ? event.getY() : Float.NaN;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!Float.isNaN(dragY) && Math.abs(event.getY() - downY) > touchSlop) {
                    dragging = true;
                    dragY = event.getY();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragY = Float.NaN;
                dragging = false;
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A press on the top's empty space, which nothing on it took.
                if (!onTheTop(event.getY())) return false;
                downY = event.getY();
                dragY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Float.isNaN(dragY)) return false;
                if (!dragging && Math.abs(event.getY() - downY) <= touchSlop) return true;
                dragging = true;
                setOffset(getScrollY() + Math.round(dragY - event.getY()));
                dragY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragY = Float.NaN;
                dragging = false;
                return true;
            default:
                return true;
        }
    }
}
