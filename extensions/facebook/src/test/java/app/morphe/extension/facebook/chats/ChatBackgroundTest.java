/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.facebook.messaging.model.threadkey.ThreadKey;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Chat background (#573) on a stand-in chat screen: a top bar with the chat's name over a message
 * list, the way MsysThreadViewFragment lays out its root on 588.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class ChatBackgroundTest {
    private static final String KEY = "ADVANCED_CRYPTO_ONE_TO_ONE:410725021";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for MsysThreadViewFragment, which keeps the chat's key in a field of this name. */
    public static final class ChatScreen {
        public ThreadKey pendingAuthoritativeThreadKey = new ThreadKey("PENDING:1");
        public ThreadKey threadKey;

        ChatScreen(String key) {
            threadKey = new ThreadKey(key);
        }
    }

    /** The stand-in screen: its root, the tappable name on top and the list under it. */
    private static final class Screen {
        final FrameLayout root;
        final FrameLayout name;
        final RecyclerView list;

        Screen(Activity activity, String nameLabel, String innerLabel) {
            root = new FrameLayout(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            int bar = Math.round(56 * density);
            FrameLayout top = new FrameLayout(activity);
            View back = new View(activity);
            back.setClickable(true);
            back.setContentDescription("Back");
            top.addView(back, new FrameLayout.LayoutParams(bar, bar));
            name = new FrameLayout(activity);
            name.setClickable(true);
            name.setContentDescription(nameLabel);
            if (innerLabel != null) {
                View line = new View(activity);
                line.setContentDescription(innerLabel);
                name.addView(line, new FrameLayout.LayoutParams(bar * 3, bar / 2));
            }
            FrameLayout.LayoutParams nameAt = new FrameLayout.LayoutParams(bar * 5, bar);
            nameAt.leftMargin = bar;
            top.addView(name, nameAt);
            root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar));
            // A banner under the top bar with a wide button of its own, like "Add friend".
            View banner = new View(activity);
            banner.setClickable(true);
            banner.setContentDescription("Add friend");
            FrameLayout.LayoutParams bannerAt = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar);
            bannerAt.topMargin = bar + bar / 2;
            root.addView(banner, bannerAt);
            list = new RecyclerView(activity, true);
            FrameLayout.LayoutParams listAt = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            listAt.topMargin = bar * 3;
            root.addView(list, listAt);
            activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 2200);
        }

        void draw() {
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }

    private static Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After
    public void reset() {
        PauseForTests.resume();
        Settings.CHAT_BACKGROUND_COLOR.resetToDefault();
        Settings.CHAT_BACKGROUNDS.resetToDefault();
        Settings.CHAT_BACKGROUND_LAST.resetToDefault();
    }

    private static int colorOf(Drawable drawable) {
        assertTrue("a solid color, not " + drawable, drawable instanceof ColorDrawable);
        return ((ColorDrawable) drawable).getColor();
    }

    @Test
    public void aChatTakesTheColorForEveryChatBehindItsMessages() {
        Settings.CHAT_BACKGROUND_COLOR.save("#1E3A5F");
        Screen screen = new Screen(activity(), "Matt Parker", null);
        ChatBackground.threadView(new ChatScreen(KEY), screen.root);
        screen.draw();
        assertEquals(0xFF1E3A5F, colorOf(screen.list.getBackground()));
        assertNull("the root keeps Facebook's own", screen.root.getBackground());
    }

    @Test
    public void aChatsOwnColorWinsAndTwoCodesFadeFromTopToBottom() {
        Settings.CHAT_BACKGROUND_COLOR.save("#1E3A5F");
        ChatBackground.saveOwnCode(KEY, "#112233, #445566");
        Screen screen = new Screen(activity(), "Matt Parker", null);
        ChatBackground.threadView(new ChatScreen(KEY), screen.root);
        screen.draw();
        Drawable painted = screen.list.getBackground();
        assertTrue("a fade, not " + painted, painted instanceof GradientDrawable);
        assertArrayEquals(new int[]{0xFF112233, 0xFF445566}, ((GradientDrawable) painted).getColors());
        assertEquals(GradientDrawable.Orientation.TOP_BOTTOM, ((GradientDrawable) painted).getOrientation());

        Screen other = new Screen(activity(), "Someone else", null);
        ChatBackground.threadView(new ChatScreen("ONE_TO_ONE:7"), other.root);
        other.draw();
        assertEquals("another chat takes the color for every chat", 0xFF1E3A5F, colorOf(other.list.getBackground()));
    }

    @Test
    public void aThemeFacebookPutsBackIsCoveredAgainAndClearingGivesFacebooksBack() {
        Screen screen = new Screen(activity(), "Matt Parker", null);
        ColorDrawable facebooks = new ColorDrawable(Color.WHITE);
        screen.list.setBackground(facebooks);
        ChatBackground.threadView(new ChatScreen(KEY), screen.root);
        screen.draw();
        assertSame("nothing picked, Facebook's own stays", facebooks, screen.list.getBackground());

        Settings.CHAT_BACKGROUND_COLOR.save("#1E3A5F");
        screen.draw();
        assertEquals(0xFF1E3A5F, colorOf(screen.list.getBackground()));

        // Someone in the chat picks a theme, and Facebook paints the list with it.
        ColorDrawable theme = new ColorDrawable(0xFFFF0066);
        screen.list.setBackground(theme);
        screen.draw();
        assertEquals("the theme is covered again", 0xFF1E3A5F, colorOf(screen.list.getBackground()));

        Settings.CHAT_BACKGROUND_COLOR.save("");
        screen.draw();
        assertSame("clearing it brings back the theme Facebook last set", theme, screen.list.getBackground());
    }

    @Test
    public void thePausedFacebookKeepsItsOwnBackground() {
        Settings.CHAT_BACKGROUND_COLOR.save("#1E3A5F");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Screen screen = new Screen(activity(), "Matt Parker", null);
        ChatBackground.threadView(new ChatScreen(KEY), screen.root);
        screen.draw();
        assertNull(screen.list.getBackground());
    }

    @Test
    public void theChatOnScreenIsRememberedByTheNameOnItsTopBar() {
        Screen screen = new Screen(activity(), "Matt Parker, Active now", "Matt Parker");
        ChatBackground.threadView(new ChatScreen(KEY), screen.root);
        screen.draw();
        assertArrayEquals("the name line, not the banner's button or the threadKey's neighbour",
                new String[]{KEY, "Matt Parker"}, ChatBackground.lastChat());

        Screen plain = new Screen(activity(), "Group chat", null);
        ChatBackground.threadView(new ChatScreen("GROUP:99"), plain.root);
        plain.draw();
        assertArrayEquals(new String[]{"GROUP:99", "Group chat"}, ChatBackground.lastChat());
    }

    @Test
    public void theBackArrowIsNeverTakenForTheNameWhileTheNameIsStillLoading() {
        // Facebook mounts the back arrow with its label a few frames before the chat's name.
        Screen screen = new Screen(activity(), null, null);
        ChatBackground.threadView(new ChatScreen(KEY), screen.root);
        screen.draw();
        assertArrayEquals("no name yet, so the chat's number stands in",
                new String[]{KEY, "410725021"}, ChatBackground.lastChat());

        screen.name.setContentDescription("Matt Parker");
        screen.draw();
        assertArrayEquals(new String[]{KEY, "Matt Parker"}, ChatBackground.lastChat());
    }

    @Test
    public void codesAreKeptInCapitalsAndMoreThanTwoAreRefused() {
        assertEquals("", ChatBackground.stored("  "));
        assertEquals("#1E3A5F", ChatBackground.stored("1e3a5f"));
        assertEquals("#1E3A5F #6A1B9A", ChatBackground.stored("#1e3a5f,  #6A1B9A"));
        assertNull(ChatBackground.stored("#111111 #222222 #333333"));
        assertNull(ChatBackground.stored("#12345"));
        assertNull(ChatBackground.problem("#1E3A5F #6A1B9A"));
        assertNotNull(ChatBackground.problem("#111111 #222222 #333333"));
        assertNotNull(ChatBackground.problem("blue"));

        String chats = ChatBackground.withChat("", KEY, "#abcdef");
        assertEquals(KEY + "\t#ABCDEF", chats);
        chats = ChatBackground.withChat(chats, "ONE_TO_ONE:7", "#111111 #222222");
        assertEquals("#111111 #222222", ChatBackground.chats(chats).get("ONE_TO_ONE:7"));
        assertEquals("ONE_TO_ONE:7\t#111111 #222222", ChatBackground.withChat(chats, KEY, ""));
    }
}
