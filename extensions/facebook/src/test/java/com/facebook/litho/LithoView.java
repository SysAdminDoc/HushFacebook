package com.facebook.litho;

import android.content.Context;
import android.widget.FrameLayout;

/** Stands in for Litho's root view, whose name Redex keeps. */
public class LithoView extends FrameLayout {
    /** The tree it renders, when a test gives it one. */
    public ComponentTree tree;

    public LithoView(Context context) {
        super(context);
    }
}
