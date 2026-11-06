/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import app.morphe.extension.facebook.reels.BurnInGuard;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The second switch of View stories anonymously: a Mark as seen button over the story viewer.
 *
 * <p>Facebook draws the story viewer's header with Litho, so there's no row of views to put a
 * button in. The eye goes on the story viewer's window instead and {@link Follower} keeps it on the
 * story card's header, left of its menu and close buttons: Facebook letterboxes the 9:16 card on a
 * taller screen, turns it away for the next person's stories and slides it down when the viewer is
 * dragged closed, and the eye stays out of the way while it moves (issue #117). The patch calls {@link #onCard} first thing in the seen
 * helper's per-card method, which runs for each card Facebook is about to count as viewed, with the
 * account's session, so the button always speaks for the card on screen. It shows where that card
 * stands ({@link StoryMarks}): an eye with a slash while it's held back, a solid eye once marked,
 * dimmed once sent. A tap marks the card, or takes the mark back before it goes. A card a batch
 * already held back is sent straight away through the sender that held it ({@link StorySeen#sendHeld}).
 *
 * <p>The seen helper doesn't count every card (some bucket types skip it, and a late callback can
 * count a card the viewer has already left), so a second patch hook, {@link #onActive}, runs when
 * Facebook makes a card the active one. The button speaks for that card only: moving to a card the
 * helper hasn't named hides the eye, a card counted after the viewer moved on never gets it, and a
 * tap on an eye that no longer matches the active card marks nothing. A card is sent at once only
 * while it's still the active card; otherwise the mark waits for the next batch that holds it. A
 * viewer that only goes behind another screen, like the share sheet, keeps its card and comes
 * back with the eye on it; one that closes forgets it, whether it closes in front or behind
 * another screen.
 *
 * <p>Each card is named for the story viewer that was newest when Facebook named it, and the eye
 * binds a card only over that viewer. One viewer is the same from its open to its close, through
 * a rebuild for a configuration change. So a viewer opened after another one closed never shows,
 * or sends with a quick tap, the card the closed one left behind.
 *
 * <p>Facebook counts a card only once its progress bar plays, so a card still loading would show
 * the eye late (#150). The patch also calls {@link #onCard} where the seen helper puts off counting
 * a card until it plays, so the eye comes with the card's own header. While the story is held,
 * Facebook hides its header (progress bar, name and menu); {@link #onState} hears the story
 * viewer's state, whose long press flag is what Facebook's own state dump calls
 * LONG_PRESS_ACTIVE, and the eye hides and comes back with the header.
 *
 * <p>The button shows only while views are anonymous and its own switch is on, and only over
 * StoryViewerActivity while it's in front. Either switch off, Hushfacebook paused or the settings
 * not read yet, it's hidden and forgets its card, and the send hook holds batches back or lets them
 * through as it would without it.
 */
public final class StorySeenButton {
    /** The story viewer's activity, a kept class name. 584 opens it as one of its subclasses too ({@link #isViewer}). */
    static final String VIEWER = "com.facebook.stories.viewer.activity.StoryViewerActivity";

    static final String HOOK = "story seen button";

    /**
     * The eye's size, and its place in dp: [CARD_TOP_DP] below the story card's top and [END_DP] in
     * from its end, centred on the header's ⌄ and ⋯ buttons and left of them. With no card found,
     * [TOP_DP] below the status bar and [END_DP] in from the window's end.
     */
    static final int SIZE_DP = 40;
    static final int TOP_DP = 14;
    static final int CARD_TOP_DP = 16;
    static final int END_DP = 92;

    /**
     * How long a card that's still turned, moved or scaled, like one held mid-drag, has to stay that
     * way before the eye shows on it. A card back in its place gets the eye in the same frame.
     */
    static final long STILL_TRANSFORMED_MS = 1000;

    /** What Hook status counts each time the story is held and the eye hides with Facebook's header. */
    static final String HELD = "eye hidden while held";

    /** The card on screen: the account it's viewed on, its id and the story viewer it was named for. */
    static final class Shown {
        final String account;
        final String card;
        /** The viewer's token ({@link #viewers}), or null when no viewer was open. */
        @Nullable
        final Object viewer;

        Shown(String account, String card, @Nullable Object viewer) {
            this.account = account;
            this.card = card;
            this.viewer = viewer;
        }
    }

    /** The card Facebook made active, and the story viewer it was made active in. */
    static final class Active {
        final String card;
        @Nullable
        final Object viewer;

        Active(String card, @Nullable Object viewer) {
            this.card = card;
            this.viewer = viewer;
        }
    }

    /** Where {@link #onCard} reads a card's id: the patch's filled stub. Tests hand in their own. */
    static volatile Function<Object, String> cardIds = StorySeenButton::cardId;

    /** What a tap uses to send a held card at once, with its account and id. Tests record the calls instead. */
    static volatile BiPredicate<String, String> sendNow = StorySeen::sendHeld;

    /** Where {@link #onState} reads whether a story viewer state is a long press: the patch's filled stub. */
    static volatile Predicate<Object> longPresses = StorySeenButton::longPressed;

    /** Whether the story is held, while Facebook hides its header and the eye hides with it. */
    private static volatile boolean held;

    /** How many cards' accounts {@link #known} remembers. */
    static final int KNOWN_CARDS = 64;

    @Nullable
    private static volatile Shown shown;
    /** The card Facebook made active last, or null before any, or once its viewer closed. */
    @Nullable
    private static volatile Active active;
    /**
     * A token for each open story viewer, one viewer from its open to its close. Read and written on
     * the main thread only.
     */
    private static final Map<Activity, Object> viewers = new WeakHashMap<>();
    /** The newest story viewer's token, which the cards Facebook names now belong to, or null with none open. */
    @Nullable
    private static volatile Object newest;
    /** The token of a viewer destroyed to be rebuilt for a configuration change, for the instance that replaces it. */
    @Nullable
    private static Object rebuilding;
    /** The account each recently counted card was viewed on, so a card returned to can show the eye again. */
    private static final Map<String, String> known = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > KNOWN_CARDS;
        }
    };
    private static volatile WeakReference<Activity> viewer = new WeakReference<>(null);
    private static volatile WeakReference<ImageView> button = new WeakReference<>(null);

    private StorySeenButton() {
    }

    /**
     * Injected first thing in the seen helper's per-card method, with the account's session, the
     * card's bucket and the card, and where the helper puts off counting a card until its progress
     * bar plays, with the helper's session. Points the button at that card while the switches are
     * on, and hides it otherwise. Never throws.
     */
    public static void onCard(@Nullable Object session, @Nullable Object bucket, @Nullable Object card) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            Shown now = null;
            if (switchedOn() && card != null) {
                String id = cardIds.apply(card);
                String account = StorySeen.account(session);
                if (id != null && !id.isEmpty() && account != null) {
                    remember(id, account);
                    // A card counted after the viewer moved on isn't the one on screen.
                    Object viewer = newest;
                    String current = activeIn(viewer);
                    if (current == null || current.equals(id)) now = new Shown(account, id, viewer);
                }
            }
            rebind(now);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * Injected first thing in the listener the seen helper makes to count [card] once its progress
     * bar plays, with the card's bucket and the helper. The card passed Facebook's checks and will
     * be counted, so the eye goes on it now, with the helper's session, instead of when the story
     * starts playing (#150). Never throws.
     */
    public static void onCardLater(@Nullable Object bucket, @Nullable Object card, @Nullable Object helper) {
        try {
            onCard(helper == null ? null : helperSession(helper), bucket, card);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * Injected where Facebook's story controllers take a card as the active one, with the card.
     * Points the button at that card when the seen helper has counted it on an account, and hides
     * the eye otherwise, so it never stays on the card the viewer left. Runs once per controller
     * that listens, so it's cheap and repeats harmlessly. Never throws.
     */
    public static void onActive(@Nullable Object card) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            String id = card == null ? null : cardIds.apply(card);
            if (id != null && id.isEmpty()) id = null;
            Object viewer = newest;
            active = id == null ? null : new Active(id, viewer);
            Shown now = null;
            if (id != null && switchedOn()) {
                String account;
                synchronized (known) {
                    account = known.get(id);
                }
                if (account != null) now = new Shown(account, id, viewer);
            }
            rebind(now);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * Injected first thing where Facebook's story viewer takes a new state for its card, with the
     * state. Facebook sets the state's long press flag when the story is held and hides its header
     * then, so the eye hides too, and comes back when the flag clears. Never throws.
     */
    public static void onState(@Nullable Object state) {
        try {
            boolean now = state != null && longPresses.test(state);
            if (now == held) return;
            held = now;
            if (now) HookStatus.counted(FamilyNames.STORY_SEEN, HELD);
            Utils.runOnMainThreadNowOrLater(StorySeenButton::follow);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /** Whether the story is held, as Facebook's story viewer last said. */
    static boolean held() {
        return held;
    }

    /** Puts the eye where its card is now, or hides it while the story is held. Main thread. */
    private static void follow() {
        ImageView eye = button.get();
        if (eye == null) return;
        Follower follower = followers.get(eye);
        if (follower != null) follower.place();
    }

    /** The card active in [viewer], or null when the active card belongs to another viewer or there's none. */
    @Nullable
    private static String activeIn(@Nullable Object viewer) {
        Active current = active;
        return current != null && current.viewer == viewer ? current.card : null;
    }

    private static void remember(String id, String account) {
        synchronized (known) {
            known.put(id, account);
        }
    }

    /** Points the button at [now], or hides it for null, redrawing only when that changes something. */
    private static void rebind(@Nullable Shown now) {
        Shown was = shown;
        if (now == null && was == null) return;
        if (now != null && was != null && now.card.equals(was.card) && now.account.equals(was.account)
                && now.viewer == was.viewer) {
            return;
        }
        shown = now;
        Utils.runOnMainThread(StorySeenButton::refresh);
    }

    /** Whether the button shows: views held back and its own switch on. Both read off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.VIEW_STORIES_ANONYMOUSLY.get() && Settings.MARK_STORIES_SEEN.get();
    }

    /** The card the button speaks for, or null. */
    @Nullable
    static Shown shown() {
        return shown;
    }

    /**
     * From the activity callbacks: [activity] was created. A story viewer gets its token here, the
     * one of the viewer it replaces when that one was rebuilt for a configuration change, so the
     * cards Facebook names while it opens are its own. Never throws.
     */
    public static void activityCreated(Activity activity) {
        try {
            if (!isViewer(activity)) return;
            Object token = rebuilding != null ? rebuilding : new Object();
            rebuilding = null;
            viewers.put(activity, token);
            newest = token;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /** From the activity callbacks: [activity] came to the front. Never throws. */
    public static void activityResumed(Activity activity) {
        try {
            if (!isViewer(activity)) return;
            newest = tokenOf(activity);
            viewer = new WeakReference<>(activity);
            refresh();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * True for the story viewer. 584 opens it as StoryViewerActivity or as one of its empty
     * subclasses (DefaultNavBar, BlackNavBar, Floating), so the kept name is looked for up the class's
     * parents.
     */
    public static boolean isViewer(Activity activity) {
        for (Class<?> type = activity.getClass(); type != null; type = type.getSuperclass()) {
            if (VIEWER.equals(type.getName())) return true;
        }
        return false;
    }

    /** [activity]'s token, given one now when its creation went unseen. Main thread. */
    private static Object tokenOf(Activity activity) {
        Object token = viewers.get(activity);
        if (token == null) {
            token = new Object();
            viewers.put(activity, token);
        }
        return token;
    }

    /**
     * From the activity callbacks: [activity] left the front. Hides the eye if it was over it. A
     * viewer that's closing forgets its cards. One that only went behind another screen, like the
     * share sheet, is still on the card it showed when it comes back, so it keeps them, and a tap
     * then still sends a held card at once. Never throws.
     */
    public static void activityPaused(Activity activity) {
        try {
            if (viewer.get() != activity) return;
            viewer = new WeakReference<>(null);
            if (activity.isFinishing()) closed(viewers.get(activity));
            ImageView eye = button.get();
            if (eye != null) hide(eye);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * From the activity callbacks: [activity] is gone. A story viewer that closed behind another
     * screen forgets its cards here, since its pause didn't know it was closing. One destroyed to be
     * rebuilt for a configuration change hands its token to the instance that replaces it. Never
     * throws.
     */
    public static void activityDestroyed(Activity activity) {
        try {
            if (!isViewer(activity)) return;
            Object token = viewers.remove(activity);
            if (viewer.get() == activity) {
                viewer = new WeakReference<>(null);
                ImageView eye = button.get();
                if (eye != null) hide(eye);
            }
            if (token == null) return;
            if (activity.isChangingConfigurations()) {
                rebuilding = token;
                return;
            }
            closed(token);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /**
     * The viewer with [token] closed. The newest viewer closing forgets everything, as before a
     * viewer opens. An older one, closing behind a newer viewer, takes only its own cards with it.
     */
    private static void closed(@Nullable Object token) {
        if (token == null || token == newest) {
            forget();
            return;
        }
        Shown was = shown;
        if (was != null && was.viewer == token) shown = null;
        Active current = active;
        if (current != null && current.viewer == token) active = null;
    }

    /** Forgets the card on screen, the active one, the accounts of the cards counted and the newest viewer. */
    private static void forget() {
        shown = null;
        active = null;
        newest = null;
        held = false;
        synchronized (known) {
            known.clear();
        }
    }

    /** Shows the eye for the card on screen over the story viewer in front, or hides it. Main thread. */
    static void refresh() {
        try {
            Activity activity = viewer.get();
            Shown now = shown;
            ImageView eye = button.get();
            // A card named for another viewer, or before any, is never this viewer's to show.
            if (activity == null || now == null || now.viewer == null || now.viewer != viewers.get(activity)
                    || !switchedOn()) {
                if (eye != null) hide(eye);
                return;
            }
            if (eye == null || eye.getContext() != activity || eye.getParent() == null) {
                eye = attach(activity);
                if (eye == null) return;
                button = new WeakReference<>(eye);
            }
            eye.setTag(now);
            show(eye, StorySeen.MARKS.state(now.account, now.card));
            Follower follower = followers.get(eye);
            if (follower != null) follower.place();
            else eye.setVisibility(held ? View.INVISIBLE : View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /** A tap on [eye]: marks its card or takes the mark back, and shows where it stands. Never throws. */
    static void tapped(ImageView eye) {
        try {
            if (!(eye.getTag() instanceof Shown)) return;
            if (!switchedOn()) {
                hide(eye);
                return;
            }
            Shown bound = (Shown) eye.getTag();
            Context shownOn = eye.getContext();
            if (!(shownOn instanceof Activity) || bound.viewer == null || bound.viewer != viewers.get(shownOn)) {
                // The eye's card belongs to another viewer than the one it sits on.
                hide(eye);
                return;
            }
            String current = activeIn(bound.viewer);
            if (current != null && !current.equals(bound.card)) {
                // The viewer moved on and the eye hasn't caught up: this tap isn't for that card.
                hide(eye);
                return;
            }
            // Send at once only while the card is known to be on screen. Without that, the mark
            // waits and goes with the next batch that holds the card.
            boolean onScreen = current != null;
            if (StorySeen.MARKS.toggle(bound.account, bound.card) == StoryMarks.State.MARKED && onScreen) {
                sendNow.test(bound.account, bound.card);
            }
            StoryMarks.State now = StorySeen.MARKS.state(bound.account, bound.card);
            show(eye, now);
            eye.announceForAccessibility(describe(now));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
        }
    }

    /** What the button says it does, for TalkBack. */
    static String describe(StoryMarks.State state) {
        switch (state) {
            case MARKED:
                return L10n.t("Marked as seen. Tap again to undo.");
            case SENT:
                return L10n.t("Marked as seen and sent");
            default:
                return L10n.t("Mark as seen");
        }
    }

    /** Forgets the card, the viewer and the button. */
    static void resetForTests() {
        cardIds = StorySeenButton::cardId;
        sendNow = StorySeen::sendHeld;
        longPresses = StorySeenButton::longPressed;
        forget();
        viewers.clear();
        rebuilding = null;
        viewer = new WeakReference<>(null);
        button = new WeakReference<>(null);
    }

    /** The eye on [activity]'s window, or null when the window has no frame to put it in. */
    @Nullable
    private static ImageView attach(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return null;
        float density = activity.getResources().getDisplayMetrics().density;
        ImageView eye = new ImageView(activity);
        eye.setImageDrawable(new Eye(density));
        eye.setScaleType(ImageView.ScaleType.CENTER);
        eye.setClickable(true);
        eye.setFocusable(true);
        eye.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            eye.setBackgroundResource(ripple.resourceId);
        }
        eye.setOnClickListener(view -> tapped((ImageView) view));
        int size = Math.round(SIZE_DP * density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        WindowInsets insets = decor.getRootWindowInsets();
        params.topMargin = (insets == null ? 0 : insets.getSystemWindowInsetTop()) + Math.round(TOP_DP * density);
        params.setMarginEnd(Math.round(END_DP * density));
        ((ViewGroup) decor).addView(eye, params);
        // Beside the content frame, so Burn-in guard's walk up from the video wouldn't find it.
        BurnInGuard.overlay(eye);
        Follower follower = new Follower(eye, decor);
        followers.put(eye, follower);
        decor.getViewTreeObserver().addOnPreDrawListener(follower);
        return eye;
    }

    /** Each eye's follower, while the eye lives. Main thread. */
    private static final Map<ImageView, Follower> followers = new WeakHashMap<>();

    /**
     * Keeps a bound eye on the story card's header (issue #117). Before each frame it finds the card
     * ({@link #findCard}). While the card is turned, moved or scaled, turning away for the next
     * person's stories, sliding down as the viewer is dragged closed or growing out of the tray as
     * it opens, the eye hides. In the frame the card is back in its place the eye shows at its top
     * end, with the card's own header (#150), and follows the card from frame to frame. While the
     * story is held it hides, as Facebook's header does. With no card found the eye keeps its place
     * on the window. An eye that isn't bound to a card is left alone.
     */
    static final class Follower implements ViewTreeObserver.OnPreDrawListener, Runnable {
        private final ImageView eye;
        private final View root;
        private final int[] at = new int[2];
        @Nullable
        private View card;
        /** Whether a card was ever found, after which losing it means it's moving, not that there's none. */
        private boolean sawCard;
        private int cardX = Integer.MIN_VALUE;
        private int cardY = Integer.MIN_VALUE;
        private long movedAt;

        Follower(ImageView eye, View root) {
            this.eye = eye;
            this.root = root;
        }

        @Override
        public boolean onPreDraw() {
            place();
            return true;
        }

        /** The settle check after the card stopped, for when no frame comes to run it. */
        @Override
        public void run() {
            place();
        }

        /** Shows, hides or moves the eye for where the card is now. Never throws. */
        void place() {
            try {
                if (!(eye.getTag() instanceof Shown) || eye.getParent() == null) return;
                if (held) {
                    // Facebook hides its header while the story is held.
                    visible(View.INVISIBLE);
                    return;
                }
                View now = findCard(root);
                if (now == null) {
                    card = null;
                    if (sawCard) {
                        // Turned or slid away. The frames that bring the next card in find it.
                        visible(View.INVISIBLE);
                        return;
                    }
                    eye.setTranslationX(0);
                    eye.setTranslationY(0);
                    visible(View.VISIBLE);
                    return;
                }
                sawCard = true;
                now.getLocationInWindow(at);
                long clock = SystemClock.uptimeMillis();
                if (now != card || at[0] != cardX || at[1] != cardY) {
                    card = now;
                    cardX = at[0];
                    cardY = at[1];
                    movedAt = clock;
                }
                // A card turning in on its left edge keeps its corner where it is, so a turned,
                // moved or scaled card has to stay that way a while before it counts as put. One in
                // its place takes the eye now, which moves with it from frame to frame.
                if (transformed(now, root)) {
                    long still = clock - movedAt;
                    if (still < STILL_TRANSFORMED_MS) {
                        visible(View.INVISIBLE);
                        eye.removeCallbacks(this);
                        eye.postDelayed(this, STILL_TRANSFORMED_MS - still);
                        return;
                    }
                }
                float density = eye.getResources().getDisplayMetrics().density;
                int[] origin = new int[2];
                root.getLocationInWindow(origin);
                float left = cardX - origin[0] + now.getWidth() - END_DP * density - eye.getWidth();
                float top = cardY - origin[1] + CARD_TOP_DP * density;
                eye.setTranslationX(Math.round(left - eye.getLeft()));
                eye.setTranslationY(Math.round(top - eye.getTop()));
                visible(View.VISIBLE);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
            }
        }

        private void visible(int visibility) {
            if (eye.getVisibility() != visibility) eye.setVisibility(visibility);
        }
    }

    /** Whether [view] or a parent below [root] is turned, moved or scaled from where its layout puts it. */
    private static boolean transformed(View view, View root) {
        for (View at = view; at != null && at != root; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (!at.getMatrix().isIdentity()) return true;
        }
        return false;
    }

    /**
     * The story card under [root]: the outermost view shown at 9:16 (within 3%), at least 60% as
     * wide as [root], whose left edge is at [root]'s (within 5% of its width), so a card turned or
     * slid away for the next one isn't it. Null when there's none.
     */
    @Nullable
    static View findCard(View root) {
        int width = root.getWidth();
        if (width <= 0) return null;
        int[] origin = new int[2];
        root.getLocationInWindow(origin);
        int[] at = new int[2];
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            int w = view.getWidth();
            int h = view.getHeight();
            if (view != root && w >= width * 0.6f && h > 0 && Math.abs(h * 9f - w * 16f) <= h * 9f * 0.03f) {
                view.getLocationInWindow(at);
                if (Math.abs(at[0] - origin[0]) <= width * 0.05f) return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    /** Hides [eye] and forgets its card, so nothing can mark it until a card shows it again. */
    private static void hide(ImageView eye) {
        eye.setVisibility(View.GONE);
        eye.setTag(null);
    }

    private static void show(ImageView eye, StoryMarks.State state) {
        Drawable drawable = eye.getDrawable();
        if (drawable instanceof Eye) ((Eye) drawable).setState(state);
        eye.setContentDescription(describe(state));
        eye.setEnabled(state != StoryMarks.State.SENT);
        eye.setAlpha(state == StoryMarks.State.SENT ? 0.6f : 1f);
    }

    /**
     * The button's eye, drawn rather than taken from Facebook's resources: an outline with a slash
     * while the card is held back, solid once it's marked or sent. White with a soft shadow, so it
     * reads over any story.
     */
    static final class Eye extends Drawable {
        private final float unit;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iris = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private StoryMarks.State state = StoryMarks.State.UNMARKED;

        Eye(float density) {
            unit = density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * density);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.WHITE);
            stroke.setShadowLayer(2 * density, 0, 0, 0x66000000);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.WHITE);
            fill.setShadowLayer(2 * density, 0, 0, 0x66000000);
            iris.setStyle(Paint.Style.FILL);
            iris.setColor(0xFF1C1E21);
        }

        StoryMarks.State state() {
            return state;
        }

        void setState(StoryMarks.State state) {
            if (this.state == state) return;
            this.state = state;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            float reach = 10 * unit;
            outline.reset();
            outline.moveTo(x - reach, y);
            outline.cubicTo(x - 5 * unit, y - 8 * unit, x + 5 * unit, y - 8 * unit, x + reach, y);
            outline.cubicTo(x + 5 * unit, y + 8 * unit, x - 5 * unit, y + 8 * unit, x - reach, y);
            outline.close();
            if (state == StoryMarks.State.UNMARKED) {
                canvas.drawPath(outline, stroke);
                canvas.drawCircle(x, y, 2.5f * unit, stroke);
                canvas.drawLine(x - 9 * unit, y + 9 * unit, x + 9 * unit, y - 9 * unit, stroke);
            } else {
                canvas.drawPath(outline, fill);
                canvas.drawCircle(x, y, 3 * unit, iris);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(24 * unit);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(24 * unit);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
            iris.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            stroke.setColorFilter(filter);
            fill.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the id Facebook's seen helper queues for [card], a StoryCard. */
    @Nullable
    public static String cardId(Object card) {
        return null;
    }

    /**
     * Filled in by the patch: the long press flag of [state], the story viewer's state for its
     * card, which Facebook's own state dump names LONG_PRESS_ACTIVE.
     */
    public static boolean longPressed(Object state) {
        return false;
    }

    /** Filled in by the patch: the FbUserSession of [helper], the story viewer's seen helper. */
    @Nullable
    public static Object helperSession(Object helper) {
        return null;
    }
}
