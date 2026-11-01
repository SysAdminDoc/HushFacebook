/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.search.PictureSearch;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The home top bar's optional hides: the Messenger button, the count on it and the Facebook logo
 * (#181), the Create (+) button (#257) and the search button (#321). The patch calls each hook
 * where Facebook builds the thing it hides.
 *
 * <ul>
 *   <li>{@link #messengerButton} runs right after the bar adds the Messenger button. With the
 *       switch on the button is set to GONE, which takes it out of the bar's layout, so the buttons
 *       beside it close up.
 *   <li>{@link #messengerCount} runs first in the two methods that put an unread count on the
 *       button. With the switch on the count is 0, which Facebook draws as no badge.
 *   <li>{@link #facebookLogo} runs right after the bar builds the logo. With the switch on the
 *       logo is drawn fully transparent. It keeps its size and its touch listener, so the empty
 *       spot still opens Hushfacebook settings on a long press.
 *   <li>{@link #createButton} runs right after the bar adds the Create button, and sets it GONE
 *       like the Messenger button.
 *   <li>{@link #searchButton} runs right after the bar adds the search button (#321), and
 *       {@link #barSearchButton} right after the newer bar makes it, and on Home's bar they set it
 *       GONE the same way. A kept button gets the Google Lens long press of {@link PictureSearch}
 *       while that switch is on (#245).
 * </ul>
 *
 * <p>Some accounts get the newer bar instead, where every button is a glyph the bar makes from a
 * named spec. There {@link #barButton} runs as each glyph is made: it hides the one named
 * "trep-composer-button" (Create) and keeps the one named "messenger", and {@link #barButtonCount}
 * runs first in that glyph class's count setters, which every glyph of the bar shares, so only the
 * kept one loses its count.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, Facebook's own
 * buttons, count and logo are left alone. The buttons and logo are built as the main screen
 * starts, so those switches show after a restart. The count switch shows with the next count.
 */
public final class TopBarButtons {
    /** Counted under the patch's name each time one of the three is hidden. */
    static final String BUTTON_HIDDEN = "Messenger button hidden";
    static final String COUNT_HIDDEN = "Messenger count hidden";
    static final String LOGO_HIDDEN = "Facebook logo hidden";
    static final String CREATE_HIDDEN = "Create button hidden";
    static final String SEARCH_HIDDEN = "Search button hidden";

    /** The newer bar's spec name for the Messenger button. */
    static final String MESSENGER_SPEC = "messenger";

    /** The newer bar's spec name for the Create button. */
    static final String CREATE_SPEC = "trep-composer-button";

    /** The screen name the home feed's top bar gives the newer bar inside it. */
    static final String HOME_BAR_SCREEN = "NewsFeedFragment";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /** The newer bar's Messenger glyphs. Weak, so a rebuilt bar lets the old ones go. */
    private static final Set<View> BAR_MESSENGER_BUTTONS = Collections.newSetFromMap(new WeakHashMap<>());

    private TopBarButtons() {
    }

    /** Injection point, after the bar adds the Messenger button. Never throws. */
    public static void messengerButton(View button) {
        try {
            HookStatus.invoked(FAMILY);
            hideButton(button);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Messenger button", failure);
        }
    }

    /**
     * Injection point, right after the newer bar makes a button's glyph from its spec, before the
     * glyph gets its first count. Hides the one named {@link #CREATE_SPEC}, and keeps and hides the
     * one named {@link #MESSENGER_SPEC}. Never throws.
     */
    public static void barButton(View button, String name) {
        try {
            HookStatus.invoked(FAMILY);
            if (button == null) return;
            if (CREATE_SPEC.equals(name)) {
                hideCreate(button);
                return;
            }
            if (!MESSENGER_SPEC.equals(name)) return;
            synchronized (BAR_MESSENGER_BUTTONS) {
                BAR_MESSENGER_BUTTONS.add(button);
            }
            hideButton(button);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Messenger button", failure);
        }
    }

    private static void hideButton(View button) {
        if (button == null || !Utils.settingsReady() || !Settings.HIDE_MESSENGER_BUTTON.get()) return;
        button.setVisibility(View.GONE);
        HookStatus.bound(FAMILY, "Messenger button");
        HookStatus.counted(FAMILY, BUTTON_HIDDEN);
        Logger.printDebug(() -> "Top bar: hid the Messenger button");
    }

    /** Injection point, after the bar adds the Create button. Never throws. */
    public static void createButton(View button) {
        try {
            HookStatus.invoked(FAMILY);
            hideCreate(button);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Create button", failure);
        }
    }

    private static void hideCreate(View button) {
        if (button == null || !Utils.settingsReady() || !Settings.HIDE_CREATE_BUTTON.get()) return;
        button.setVisibility(View.GONE);
        HookStatus.bound(FAMILY, "Create button");
        HookStatus.counted(FAMILY, CREATE_HIDDEN);
        Logger.printDebug(() -> "Top bar: hid the Create button");
    }

    /** Injection point, after the classic bar adds the search button. That bar is only ever Home's. */
    public static void searchButton(View button) {
        searchButton(button, true);
    }

    /**
     * Injection point, after the newer bar makes its search button, with the name of the screen the
     * bar is for. Marketplace and other screens build their top bars from the same class, and their
     * search stays when Home's is hidden.
     */
    public static void barSearchButton(View button, String screen) {
        searchButton(button, HOME_BAR_SCREEN.equals(screen));
    }

    /**
     * Hides a search button of Home's bar, or with Search with a picture on gives any kept one the
     * long press that opens Google Lens (#245). Never throws.
     */
    private static void searchButton(View button, boolean onHome) {
        try {
            HookStatus.invoked(FAMILY);
            if (button == null || !Utils.settingsReady()) return;
            if (!onHome || !Settings.HIDE_SEARCH_BUTTON.get()) {
                if (Settings.SEARCH_WITH_PICTURE.get()) PictureSearch.attach(button);
                return;
            }
            button.setVisibility(View.GONE);
            HookStatus.bound(FAMILY, "Search button");
            HookStatus.counted(FAMILY, SEARCH_HIDDEN);
            Logger.printDebug(() -> "Top bar: hid the search button");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Search button", failure);
        }
    }

    /**
     * Injection point, first thing in each count setter of the newer bar's glyph class. The count to
     * show: {@link #messengerCount}'s answer for a kept Messenger glyph, the given count for any
     * other glyph. Never throws.
     */
    public static int barButtonCount(View button, int count) {
        try {
            if (count == 0) return 0;
            synchronized (BAR_MESSENGER_BUTTONS) {
                if (!BAR_MESSENGER_BUTTONS.contains(button)) return count;
            }
            return messengerCount(count);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Messenger count", failure);
            return count;
        }
    }

    /**
     * Injection point, first thing in each method that sets the Messenger button's unread count.
     * The count to show: 0 while the switch is on, the given count otherwise. Never throws.
     */
    public static int messengerCount(int count) {
        try {
            HookStatus.invoked(FAMILY);
            if (count == 0 || !Utils.settingsReady() || !Settings.HIDE_MESSENGER_COUNT.get()) return count;
            HookStatus.bound(FAMILY, "Messenger count");
            HookStatus.counted(FAMILY, COUNT_HIDDEN);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Messenger count", failure);
            return count;
        }
    }

    /** Injection point, after the bar builds the Facebook logo. Never throws. */
    public static void facebookLogo(View logo) {
        try {
            HookStatus.invoked(FAMILY);
            if (logo == null || !Utils.settingsReady() || !Settings.HIDE_FACEBOOK_LOGO.get()) return;
            // Alpha keeps the view's size and its long press. The image alpha also survives a later
            // drawable swap, such as the app icon the Unlock app icons patch dresses the logo with.
            if (logo instanceof ImageView) {
                ((ImageView) logo).setImageAlpha(0);
            } else {
                logo.setAlpha(0f);
            }
            HookStatus.bound(FAMILY, "Facebook logo");
            HookStatus.counted(FAMILY, LOGO_HIDDEN);
            Logger.printDebug(() -> "Top bar: hid the Facebook logo");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Facebook logo", failure);
        }
    }
}
