/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Called when Facebook reports a Story's progress as complete, or when a touch lifts on a story
 * whose progress is complete, with every progress of the story playing, and when a forward tap
 * reaches a bucket's last card.
 */
public final class StoryAdvance {
    /** What a finished story started again is counted under. */
    static final String LOOPED = "stories looped";
    /** What a held story started again from the play button is counted under. */
    static final String REPLAYED = "stories replayed";
    /** What a forward tap on a bucket's last card that moved on is counted under. */
    static final String LAST_CARD_TAPS = "last story taps";

    /** The name of Facebook's navigation reason for a tap on the forward side of a story. */
    static final String TAP_FORWARD = "TAP_FORWARD";

    /** The progress Facebook reports a finished card with. */
    static final int COMPLETE = 1000;

    private static final WeakReference<Object> NONE = new WeakReference<>(null);

    /** The progress callback of the story held on its last frame, none while a story plays. */
    private static WeakReference<Object> held = NONE;

    /** What the {@link #restart} stub does in tests, where no patch fills it in. */
    @Nullable
    static Runnable restartForTests;

    private StoryAdvance() { }

    public static boolean waitForTap() {
        try {
            HookStatus.invoked(FamilyNames.STORY_AUTO_ADVANCE);
            return Utils.settingsReady() && Settings.BLOCK_STORY_AUTO_ADVANCE.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story completion", failure);
            return false;
        }
    }

    /**
     * Asked once {@link #waitForTap} has held a finished story: true has the patch make Facebook's
     * own restart, so the story plays again from the start. Loop stories works only together with
     * Stop Story auto-advance, so either off, a pause, or settings that aren't ready leave the
     * story on its last frame (or moving on, with Stop Story auto-advance off). Never throws.
     */
    public static boolean loop() {
        try {
            if (!Utils.settingsReady() || !Settings.BLOCK_STORY_AUTO_ADVANCE.get()
                    || !Settings.LOOP_STORIES.get()) return false;
            HookStatus.counted(FamilyNames.STORY_AUTO_ADVANCE, LOOPED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story loop", failure);
            return false;
        }
    }

    /**
     * Facebook's restart threw inside the loop helper: the viewer had detached, or the surface
     * has no restart. The story stays on its last frame.
     */
    public static void loopFailed(Throwable failure) {
        HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story loop restart", failure);
    }

    /**
     * Injection point, right after {@link #waitForTap} held a finished story, with its progress
     * callback: kept so the story play button can start the story again, and the button is told.
     * A story Loop stories is about to start again isn't held. Never throws.
     */
    public static void held(Object callback) {
        try {
            if (callback == null || Settings.LOOP_STORIES.get()) return;
            held = new WeakReference<>(callback);
            StoryTaps.storyFinished();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story held", failure);
        }
    }

    /**
     * Injection point, first thing in the progress callback, with each progress from 0 to {@link
     * #COMPLETE}: a card that plays again, or the next one, isn't held any more. Runs many times a
     * second, so it does no more than that.
     */
    public static void progressed(int progress) {
        if (progress < COMPLETE && held != NONE) held = NONE;
    }

    /** Whether a finished story is held on its last frame, so the play button can offer to start it again. */
    public static boolean finished() {
        try {
            return held.get() != null && Utils.settingsReady() && Settings.BLOCK_STORY_AUTO_ADVANCE.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story held", failure);
            return false;
        }
    }

    /**
     * Starts the held story again from the start, through the same restart of Facebook's that
     * Loop stories makes. False when no story is held, the viewer has gone or the restart threw,
     * and the story stays where it is. Main thread. Never throws.
     */
    public static boolean replay() {
        Object callback = held.get();
        if (callback == null) return false;
        try {
            if (!restart(callback)) return false;
            held = NONE;
            HookStatus.counted(FamilyNames.STORY_AUTO_ADVANCE, REPLAYED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story replay", failure);
            return false;
        }
    }

    /**
     * Injection point, where Facebook's forward navigation takes its branch for a bucket's last
     * card, with the navigation's reason: true has the patch give that branch the reason a story's
     * own completion moves on with. Some surfaces, a Page's stories among them, ignore a forward
     * tap on the last card and leave only through that completion, which this switch holds, so
     * the stories never closed there (#380). With the switch on, a forward tap on the last card
     * goes where the finished story would have gone, held or still playing, Loop stories too.
     * Never throws.
     */
    public static boolean lastCardTap(Object reason) {
        try {
            if (!(reason instanceof Enum) || !TAP_FORWARD.equals(((Enum<?>) reason).name())) return false;
            if (!Utils.settingsReady() || !Settings.BLOCK_STORY_AUTO_ADVANCE.get()) return false;
            held = NONE;
            HookStatus.counted(FamilyNames.STORY_AUTO_ADVANCE, LAST_CARD_TAPS);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story last card tap", failure);
            return false;
        }
    }

    /**
     * Filled in by the patch: Facebook's restart of [callback]'s card, made by the method the
     * patch adds to the callback's class, and whether it got as far as the reset. Until it's
     * filled it runs {@link #restartForTests}, answering whether there was one.
     */
    public static boolean restart(Object callback) {
        Runnable test = restartForTests;
        if (test == null) return false;
        test.run();
        return true;
    }

    /** Forgets the held story, for tests. */
    static void forgetForTests() {
        held = NONE;
        restartForTests = null;
    }
}
