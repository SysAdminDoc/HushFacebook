/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.content.Intent;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.katana.activity.FbMainTabActivityDelegate;
import com.facebook.navigation.tabbar.state.model.NavigationConfig;
import com.facebook.navigation.tabbar.state.model.TabTag;

import org.robolectric.Robolectric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Facebook's main screen and its tab bar as the start tab hook reads them, for tests in any
 * package: a start from the launcher icon, the screen it starts, and the tab bar state behind it.
 */
public final class StartTabRouteForTests {
    private StartTabRouteForTests() {
    }

    /** A start from the launcher icon, as a launcher sends one. */
    public static Intent launcherStart() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** The main screen started by [intent], attached and not yet created. */
    public static FbMainTabActivity screen(Intent intent) {
        return Robolectric.buildActivity(FbMainTabActivity.class, intent).get();
    }

    /**
     * Hands the hook a main screen started from the launcher icon. True when it asked Facebook for
     * a tab, which is the switch changing what Facebook would have done.
     */
    public static boolean routes() {
        FbMainTabActivity screen = screen(launcherStart());
        StartTabRoute.onActivityCreate(screen, null);
        // The screen is never built here, so nothing would end the start the hook now waits on.
        StartTabRoute.settled();
        return screen.getIntent().hasExtra(FacebookTabs.TARGET_TAB_ID);
    }

    /**
     * Stands in for Kotlin's lazy value as Redex leaves it: the two method names are kept. Like the
     * real one, the first getValue() makes it initialized. A test can make getValue() throw.
     */
    public static final class Lazy {
        private final Object value;
        private boolean initialized;
        public RuntimeException failure;

        public Lazy(Object value, boolean initialized) {
            this.value = value;
            this.initialized = initialized;
        }

        public boolean isInitialized() {
            return initialized;
        }

        public Object getValue() {
            if (failure != null) throw failure;
            initialized = true;
            return value;
        }
    }

    /** Stands in for the tab bar's state: its configuration, and the tabs it shows. */
    public static final class TabBarState {
        public NavigationConfig config;
        public List<Object> shown;
    }

    /** Stands in for a wrapper Facebook can hand out in place of the main screen's delegate. */
    public static final class DelegateWrapper {
        @SuppressWarnings("unused")
        private final Object wrapped;

        public DelegateWrapper(Object wrapped) {
            this.wrapped = wrapped;
        }
    }

    /**
     * Gives [screen] a delegate whose built tab bar state shows [shown] and whose configuration has
     * [configured]. Either can be null.
     */
    public static FbMainTabActivityDelegate tabBar(FbMainTabActivity screen, List<Object> shown, List<Object> configured) {
        TabBarState state = new TabBarState();
        state.shown = shown == null ? null : new ArrayList<>(shown);
        state.config = configured == null ? null : new NavigationConfig(new ArrayList<>(configured));
        FbMainTabActivityDelegate delegate = new FbMainTabActivityDelegate();
        delegate.tabBarStateManager$delegate = new Lazy(state, true);
        screen.delegate = delegate;
        return delegate;
    }

    public static List<Object> tabs(Object... tabs) {
        return Arrays.asList(tabs);
    }

    /**
     * A tab of a class this build doesn't know, the shape a bar that opens on full-screen Reels
     * could take (Meta tests one with Home second, 2026).
     */
    public static final class UnknownTab extends TabTag {
        public UnknownTab() {
            super(1234L);
        }
    }
}
