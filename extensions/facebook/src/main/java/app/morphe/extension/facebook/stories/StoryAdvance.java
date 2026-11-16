/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
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

    /** Facebook's story card, which keeps its name: the log line reads its media type off it. */
    private static final String STORY_CARD = "com.facebook.stories.model.StoryCard";

    /** Names Facebook's media type enum keeps, so the log line can tell it from the card's other enums. */
    private static final List<String> MEDIA_NAMES = Arrays.asList("PHOTO", "VIDEO");

    /** What the log line says for anything it couldn't read. */
    static final String UNREAD = "unread";

    /** Whether the restart running now is the play button's, so the log line can tell it from a loop. */
    private static boolean replaying;

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
            replaying = true;
            if (!restart(callback)) return false;
            held = NONE;
            HookStatus.counted(FamilyNames.STORY_AUTO_ADVANCE, REPLAYED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story replay", failure);
            return false;
        } finally {
            replaying = false;
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

    /**
     * Injection point, in both restart helpers just before Facebook's reset, with the progress
     * callback and the reset's owner, Facebook's interrupt manager. With debug logging on it logs
     * the restart: the loop's or the play button's, the card's media type, and the reasons the
     * viewer was held by just then, which keep a video paused after a reset that clears its
     * finished state (#380). Builds nothing with debug logging off. Never throws.
     */
    public static void restarting(@Nullable Object callback, @Nullable Object interrupts) {
        try {
            boolean replay = replaying;
            Logger.printDebug(() -> restartLine(callback, interrupts, replay));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_AUTO_ADVANCE, "Story restart log", failure);
        }
    }

    static String restartLine(@Nullable Object callback, @Nullable Object interrupts, boolean replay) {
        return (replay ? "Story replay" : "Loop stories") + ": restarting the story (type " + mediaOf(callback)
            + ") through Facebook's reset with replay on, held by " + reasonsOf(interrupts);
    }

    /**
     * The media type of the callback's card, PHOTO or VIDEO and the rest, read off the card's
     * getters that answer an enum with Facebook's media names, since the getters' own names change
     * with each build. {@link #UNREAD} when there's no card or nothing answers.
     */
    private static String mediaOf(@Nullable Object callback) {
        try {
            Object card = fieldValue(callback, STORY_CARD);
            if (card == null) return UNREAD;
            Set<String> names = new TreeSet<>();
            for (Method getter : card.getClass().getMethods()) {
                if (Modifier.isStatic(getter.getModifiers()) || getter.getParameterTypes().length != 0) continue;
                Object[] constants = getter.getReturnType().getEnumConstants();
                if (constants == null || !enumNames(constants).containsAll(MEDIA_NAMES)) continue;
                Object media = getter.invoke(card);
                if (media instanceof Enum) names.add(((Enum<?>) media).name());
            }
            return names.isEmpty() ? UNREAD : String.join("/", names);
        } catch (Throwable failure) {
            return UNREAD;
        }
    }

    /** The interrupt reasons the manager holds, its one enum set, as their names: [] when none. */
    private static String reasonsOf(@Nullable Object interrupts) {
        try {
            if (interrupts == null) return UNREAD;
            for (Class<?> type = interrupts.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object value = field.get(interrupts);
                    if (value instanceof EnumSet) return enumNames(((EnumSet<?>) value).toArray()).toString();
                }
            }
            return UNREAD;
        } catch (Throwable failure) {
            return UNREAD;
        }
    }

    /** The value of the first instance field of [owner]'s class or one it extends declared as [typeName]. */
    @Nullable
    private static Object fieldValue(@Nullable Object owner, String typeName) throws IllegalAccessException {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !typeName.equals(field.getType().getName())) continue;
                field.setAccessible(true);
                return field.get(owner);
            }
        }
        return null;
    }

    private static List<String> enumNames(Object[] constants) {
        List<String> names = new ArrayList<>();
        for (Object constant : constants) if (constant instanceof Enum) names.add(((Enum<?>) constant).name());
        return names;
    }

    /** Forgets the held story, for tests. */
    static void forgetForTests() {
        held = NONE;
        restartForTests = null;
    }
}
