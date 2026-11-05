/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Starts reels and the videos that open in the same viewer in Facebook's own Clean mode, for Clean
 * up Reels (#75).
 *
 * <p>Facebook's three-dot menu has a Clean mode item that hides the buttons down the side of one
 * reel. For accounts it gives "sticky" Clean mode, it also remembers that choice in one flag, and
 * each part of the viewer that follows Clean mode reads the flag as it's made: the overlay with the
 * buttons, the footer, the video controls and the unified player's controls. The patch hands each
 * of those reads here. A yes is passed on as it came, and a no becomes a yes while the switch is
 * on, so every new reel starts the way the menu item leaves one.
 *
 * <p>Facebook's own ways out of Clean mode still work for the reel on screen, and the next one
 * starts clean again. The overlay skips the read for an ad, so an ad keeps its controls. Off,
 * paused, before the settings are ready, or when anything here fails, the answer is Facebook's own.
 *
 * <p>The Clean mode button over a clean reel (#304) only ever leaves Clean mode in Facebook's
 * design: it goes through the remembered choice for sticky accounts, and for everyone else tells
 * parts of the viewer the overlay doesn't listen to, so with the switch on it kept its "off" label
 * and did nothing more. While the switch is on, the button and the store take the sticky way, the
 * overlay hears the change, and when a reel leaves Clean mode its no is marked as the person's own,
 * so the button stays to turn Clean mode back on. An ad's no is Facebook's plain one, and an ad
 * still gets no button.
 */
public final class ReelCleanMode {
    /** Counted under the patch's name each time a part of the viewer is started clean. */
    static final String STARTED_CLEAN = "Viewer part started in Clean mode";

    /** Counted each time the Clean mode button or the store is let through the sticky way. */
    static final String SWITCHED_BY_BUTTON = "Clean mode switched through the remembered choice";

    /**
     * The overlay's no for a reel that left Clean mode while the switch is on. It equals
     * {@link Boolean#FALSE}, which is how Facebook compares it, so only {@link #cleanButtonState}
     * tells it from the no an ad starts with.
     */
    @SuppressWarnings({"deprecation", "removal", "BooleanConstructorCall", "UnnecessaryBoxing"})
    static final Boolean LEFT_CLEAN = new Boolean(false);

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    private ReelCleanMode() {
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.REEL_CLEAN_MODE.get();
    }

    /** Whether the sidebar's Clean mode button (#391) is switched on, store or not. */
    private static boolean buttonOn() {
        return Utils.settingsReady() && Settings.REEL_CLEAN_BUTTON.get();
    }

    /** Counted each time the sidebar's Clean mode button puts the viewer in Clean mode. */
    static final String ENTERED_FROM_SIDEBAR = "Clean mode turned on from the sidebar button";

    /**
     * Facebook's Clean mode store (#391), kept as it's made. Weakly, since Facebook's injector owns
     * it and a new session makes a new one. Package-private so a test can start without one.
     */
    static volatile WeakReference<Object> store;

    /**
     * Injection point, at the end of the Clean mode store's constructor, with the new store. Never
     * throws.
     */
    public static void keepStore(Object facebook) {
        try {
            if (facebook != null) store = new WeakReference<>(facebook);
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel Clean mode: could not keep the store", failure);
        }
    }

    @Nullable
    private static Object keptStore() {
        WeakReference<Object> kept = store;
        return kept == null ? null : kept.get();
    }

    /**
     * Whether a reel's sidebar gets the Clean mode button: its switch is on and the store it goes
     * through was kept, which only a build with Clean up Reels does. Never throws.
     */
    public static boolean cleanButton() {
        try {
            return buttonOn() && keptStore() != null;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** The sidebar button's name, in the phone's language. Never throws. */
    public static String cleanLabel() {
        try {
            return L10n.t("Clean mode");
        } catch (Throwable failure) {
            return "Clean mode";
        }
    }

    /**
     * The sidebar button's tap. It sets the store's remembered choice the way Facebook's own menu
     * item does for accounts with sticky Clean mode, and the store tells the overlay, which hides the
     * sidebar. The choice then holds for each reel that follows, until Facebook's Clean mode button
     * over a clean reel turns it off through {@link #stickyClean}. Never throws.
     */
    public static void enterClean() {
        try {
            HookStatus.invoked(FAMILY);
            Object kept = keptStore();
            if (kept == null || !buttonOn()) {
                Logger.printInfo(() -> "Reel Clean mode: no store to switch from the sidebar button");
                return;
            }
            switchStore(kept, null, true);
            HookStatus.bound(FAMILY, "Clean mode sidebar button");
            HookStatus.counted(FAMILY, ENTERED_FROM_SIDEBAR);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Clean mode sidebar button", failure);
            Logger.printException(() -> "Reel Clean mode: the sidebar button could not turn it on", failure);
        }
    }

    /**
     * Filled by the patch: calls the store's copy of its setter with [clean]. The copy skips the
     * setter's checks of the reel and the account, takes Facebook's own source of a Clean mode tap,
     * and goes straight to setting the choice and telling the overlay. [spare] only lends the patch
     * a register for the source and the player the copy doesn't read.
     */
    public static void switchStore(Object store, @Nullable Object spare, boolean clean) { }

    /**
     * Injection point, right after a part of the Reels viewer reads Facebook's remembered Clean
     * mode flag as it's made. True starts that part clean. Never throws.
     *
     * @param facebook what Facebook's flag answered.
     */
    public static boolean startClean(boolean facebook) {
        try {
            HookStatus.invoked(FAMILY);
            if (facebook || !Utils.settingsReady() || !Settings.REEL_CLEAN_MODE.get()) return facebook;
            HookStatus.bound(FAMILY, "reel Clean mode");
            HookStatus.counted(FAMILY, STARTED_CLEAN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel Clean mode", failure);
            Logger.printException(() -> "Reel Clean mode: could not read its switch", failure);
            return facebook;
        }
    }

    /**
     * The same, for the part that reads the flag through Facebook's helper that boxes it. The
     * answer is Facebook's own object unless the switch turns a no into a yes. Never throws.
     *
     * @param facebook what Facebook's helper answered, a Boolean.
     */
    public static Boolean startClean(Boolean facebook) {
        boolean clean = Boolean.TRUE.equals(facebook);
        return startClean(clean) && !clean ? Boolean.TRUE : facebook;
    }

    /**
     * Injection point, where the Clean mode button's tap and the store's setter ask whether this
     * account keeps its Clean mode choice. A yes while the switch is on, so a tap sets the choice
     * and tells the overlay, which flips the button. The sidebar's Clean mode button turns Clean mode
     * on through the choice, so with that button on it's a yes too, and Facebook's button over a
     * clean reel turns the choice back off. Never throws.
     *
     * @param facebook what Facebook's sticky Clean mode check answered.
     */
    public static boolean stickyClean(boolean facebook) {
        try {
            HookStatus.invoked(FAMILY);
            if (facebook || !(on() || buttonOn())) return facebook;
            HookStatus.bound(FAMILY, "Clean mode button");
            HookStatus.counted(FAMILY, SWITCHED_BY_BUTTON);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Clean mode button", failure);
            Logger.printException(() -> "Reel Clean mode: could not read its switch for the button", failure);
            return facebook;
        }
    }

    /**
     * Injection point, the overlay's state update as it hears Clean mode switch, holding the new
     * value as its only element. While the switch is on, a no becomes {@link #LEFT_CLEAN}. The same
     * array comes back. Never throws.
     *
     * @param state Facebook's state update.
     */
    public static Object[] markLeftClean(Object[] state) {
        try {
            if (state != null && state.length == 1 && Boolean.FALSE.equals(state[0]) && on()) {
                state[0] = LEFT_CLEAN;
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel Clean mode: could not mark a reel leaving it", failure);
        }
        return state;
    }

    /**
     * Injection point, where the Clean mode button reads the overlay's value to leave itself out
     * once a reel is out of Clean mode. While the switch is on, a reel the person took out reads as
     * still clean here, so the button stays. Its label comes from Facebook's own read of the same
     * value, so it offers to turn Clean mode back on. Never throws.
     *
     * @param facebook the overlay's value.
     */
    @SuppressWarnings("NumberEquality")
    public static Object cleanButtonState(Object facebook) {
        KEPT_BUTTON.remove();
        try {
            if (facebook != LEFT_CLEAN || !on()) return facebook;
            KEPT_BUTTON.set(Boolean.TRUE);
            return Boolean.TRUE;
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel Clean mode: could not read its switch for the button", failure);
            return facebook;
        }
    }

    /** Counted each time a kept button is laid over the reel's details instead of below them. */
    static final String FLOATED_BUTTON = "Kept Clean mode button laid over the details";

    /**
     * Whether the button the builder is making right now is one {@link #cleanButtonState} kept for a
     * reel taken out of Clean mode. The builder reads its value and makes its button in one call, on
     * one thread, so the answer is kept per thread between the two.
     */
    private static final ThreadLocal<Boolean> KEPT_BUTTON = new ThreadLocal<>();

    /**
     * Injection point, in the Clean mode button's builder once it has the style of the button's row
     * (#329). True for a button kept for a reel taken out of Clean mode, and the patch then sets the
     * row to an absolute position at the overlay's bottom end. Facebook's design makes no row there,
     * so in the column it took the room of its own height from the reel's details, which all moved up
     * and left a gap over the seek bar. Laid over the column, the button stays where it sits over a
     * clean reel and the details sit where Facebook puts them. Never throws.
     */
    public static boolean floatCleanButton() {
        try {
            boolean kept = KEPT_BUTTON.get() == Boolean.TRUE;
            KEPT_BUTTON.remove();
            if (!kept) return false;
            HookStatus.counted(FAMILY, FLOATED_BUTTON);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Reel Clean mode: could not place the kept button", failure);
            return false;
        }
    }
}
