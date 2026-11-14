/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package androidx.viewpager.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Stands in for AndroidX's pager in tests. The extension finds Facebook's tab pager by this kept
 * class name, so a view of this name laying its pages out the way the real one does is enough:
 * side by side, each as wide as the pager and as tall as it is inside its padding.
 */
public class ViewPager extends ViewGroup {
    public ViewPager(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int pageWidth = MeasureSpec.makeMeasureSpec(width - getPaddingLeft() - getPaddingRight(), MeasureSpec.EXACTLY);
        int pageHeight = MeasureSpec.makeMeasureSpec(height - getPaddingTop() - getPaddingBottom(), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(pageWidth, pageHeight);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int pageWidth = right - left - getPaddingLeft() - getPaddingRight();
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            int x = getPaddingLeft() + i * (right - left);
            page.layout(x, getPaddingTop(), x + pageWidth, getPaddingTop() + page.getMeasuredHeight());
        }
    }
}
