/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.facebook.messaging.model.threadkey.ThreadKey;

import org.robolectric.Robolectric;

/** Chat background as the settings package's tests ask it. */
public final class ChatBackgroundForTests {
    private ChatBackgroundForTests() {
    }

    /** Stands in for the chat screen's fragment. */
    public static final class ChatScreen {
        public ThreadKey threadKey = new ThreadKey("ONE_TO_ONE:1");
    }

    /** Whether a chat screen drawn now, with what's saved, got a color behind its messages. */
    public static boolean paintsAChat() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        RecyclerView list = new RecyclerView(activity, true);
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(root);
        root.layout(0, 0, 1080, 2200);
        list.layout(0, 0, 1080, 2200);
        ChatBackground.threadView(new ChatScreen(), root);
        root.getViewTreeObserver().dispatchOnPreDraw();
        return list.getBackground() != null;
    }
}
