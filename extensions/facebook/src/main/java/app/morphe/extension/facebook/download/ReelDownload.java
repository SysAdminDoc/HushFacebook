/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/ReelDownload.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import kotlin.jvm.functions.Function1;

/**
 * The handler behind a download button added to the reel sidebar.
 *
 * <p>The sidebar builds each of its buttons through one factory of the app, and that factory takes
 * its handlers as {@code Function1}. The reference bundle had no extension of its own, so it had to
 * borrow one of the app's multiplexed lambdas and pick unused case numbers inside it. The module
 * here already carries the Kotlin standard library, so this implements {@code Function1} directly
 * and is passed straight in. Nothing of the app is modified to hold it.
 *
 * <p>The object carries the player of the item that the button belongs to, so the file saved is
 * always the reel on the screen. The app prepares the reels that come next, so a handler that
 * reads a shared place instead saves the wrong one.
 *
 * <h2>Slots</h2>
 *
 * <p>The factory takes several handlers and only one of them is the tap. Which one is not written
 * anywhere, and the render of the button cannot settle it either, so a run answered it. Every slot
 * got one of these with its own number, and the events they received named them. The touch
 * slot fires twice per press with a {@code MotionEvent}. A visibility slot fires on its own. One
 * slot fires once per press with an event that carries nothing but the {@code View}. That last one
 * is the tap, and it is the only slot that saves. The others are kept rather than left null. The
 * factory is not documented to accept null, and a silent handler costs nothing.
 *
 * <p>The same class sits behind the Cast button the patch adds under it (#182). A cast handler's
 * tap hands the reel's file to a casting app ({@link OpenInAnotherApp#castReel}) instead of saving.
 */
public final class ReelDownload implements Function1<Object, Object> {

    /** The source every tap of the button carries in the diagnostic report. */
    private static final String SOURCE = "ReelDownload";

    /** The player params of this item. Its source holds the addresses. */
    private final Object playerParams;

    private final Context context;

    /** Which handler of the factory this one was passed as. Diagnostic. */
    private final int slot;

    /** Whether this slot saves, or only reports that it ran. */
    private final boolean saves;

    /**
     * The real names of the two address fields of the source.
     *
     * <p>The patch reads them out of the app while patching and hands them over. The app renames
     * its fields on every release. The names that it reports for them do not change.
     */
    private final String hdField;
    private final String sdField;

    /** The real name of the field that holds the DASH manifest. The patch reads it the same way. */
    private final String manifestField;

    /**
     * The reel's own story: the props its sidebar was built from, a GraphQL tree the patch hands
     * over beside the player. Facebook reads the reel's creation_time off it for the reel's time
     * label, and its actors are who posted the reel, so it gives the file name its poster, their id
     * and the post day ({@link PostDetails}). Null, or a model that isn't a tree, costs those three
     * tokens and nothing else.
     */
    private final Object story;

    /** Whether this is a handler of the Cast button, whose tap casts rather than saves. */
    private final boolean cast;

    /** A handler with no story of the reel: the name then has no poster and no post day. */
    public ReelDownload(
        Object playerParams,
        Context context,
        String hdField,
        String sdField,
        String manifestField,
        int slot,
        boolean saves
    ) {
        this(playerParams, context, hdField, sdField, manifestField, slot, saves, null);
    }

    public ReelDownload(
        Object playerParams,
        Context context,
        String hdField,
        String sdField,
        String manifestField,
        int slot,
        boolean saves,
        Object story
    ) {
        this(playerParams, context, hdField, sdField, manifestField, slot, saves, story, false);
    }

    public ReelDownload(
        Object playerParams,
        Context context,
        String hdField,
        String sdField,
        String manifestField,
        int slot,
        boolean saves,
        Object story,
        boolean cast
    ) {
        this.playerParams = playerParams;
        this.context = context;
        this.hdField = hdField;
        this.sdField = sdField;
        this.manifestField = manifestField;
        this.slot = slot;
        this.saves = saves;
        this.story = story;
        this.cast = cast;
    }

    /*
     * What Hook status counts each time Facebook builds a reel's buttons (#18). The Download
     * button's only counter ran on a tap, so a reel drawn without the button left nothing in the
     * report. These are fixed phrases: a count names no reel.
     */
    static final String SIDEBAR_WITH_BUTTON = "UDD sidebar with Download";
    static final String SIDEBAR_SWITCH_OFF = "UDD sidebar without Download, switch off";
    static final String SIDEBAR_HIDDEN = "UDD sidebar without Download, hidden";
    static final String SIDEBAR_PAUSED = "UDD sidebar without Download, paused";
    static final String SIDEBAR_NOT_READY = "UDD sidebar without Download, settings not ready";
    static final String SIDEBAR_WITH_CAST = "UDD sidebar with Cast";
    static final String OTHER_SIDEBAR = "FbShorts sidebar, which gets no Download";
    static final String LAYOUT_NEWER = "newer reel button layout from Facebook";
    static final String LAYOUT_SWITCHED = "older reel button layout switched for Download";
    static final String LAYOUT_OLDER = "older reel button layout kept, no Download";

    /**
     * Which of its two button layouts Facebook draws a reel with (#316, #298). The newer one builds
     * the UDD sidebar and gets the Download button. The older one, which a server flag
     * (fdsConfig.enableFDSVerticalUfiFbReels) still gives some accounts, draws its own strip that
     * never reaches the sidebar, so those reels had no Download button at all. The patch hands this
     * Facebook's answer as the reel is drawn. Yes stays yes. No becomes yes while the button is on,
     * so the reel gets the newer layout and the button, and stays no when it's off, hidden, paused or
     * before the settings are ready. Each answer is counted in Hook status. Never throws: Facebook's
     * answer is the fallback.
     */
    public static boolean verticalUfi(boolean facebooks) {
        try {
            if (facebooks) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, LAYOUT_NEWER);
                return true;
            }
            // The Cast button goes in the same sidebar, so it wants the newer layout too (#182).
            boolean shows = Utils.settingsReady() && (Settings.DOWNLOAD_REELS.get()
                    && Settings.SHOW_REEL_DOWNLOAD_BUTTON.get() || Settings.REEL_CAST_BUTTON.get());
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, shows ? LAYOUT_SWITCHED : LAYOUT_OLDER);
            return shows;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "layout switch", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not pick the reel button layout", t);
            return facebooks;
        }
    }

    /**
     * Whether the sidebar Facebook is building for a reel gets the Download button, the Cast button
     * (#182) or both. The patch asks before it builds either, so with both off, paused, or before the
     * settings are ready, the reel has only Facebook's own buttons; the helper then asks
     * {@link #downloadButton} and {@link #castButton} for each one. Hiding the Download button leaves
     * the More menu's download alone. Each answer and its reason are counted in Hook status. Never
     * throws: false is Facebook's own path.
     */
    public static boolean showsButton() {
        try {
            ReelSidebarDiagnostics.sidebarObserved();
            if (!Utils.settingsReady()) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SIDEBAR_NOT_READY);
                return false;
            }
            boolean enabled = Settings.DOWNLOAD_REELS.get();
            boolean shows = enabled && Settings.SHOW_REEL_DOWNLOAD_BUTTON.get();
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, shows ? SIDEBAR_WITH_BUTTON
                    : HushfacebookPause.isPaused() ? SIDEBAR_PAUSED
                    : enabled ? SIDEBAR_HIDDEN : SIDEBAR_SWITCH_OFF);
            boolean casts = Settings.REEL_CAST_BUTTON.get();
            if (casts) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SIDEBAR_WITH_CAST);
            return shows || casts;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "button switch", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not read the reel Download switch", t);
            return false;
        }
    }

    /**
     * Facebook drew a reel's buttons with FbShortsSideBarComponent, which has no place for the
     * Download button. The Reels viewer draws it instead of the UDD sidebar when a server flag
     * says so, and several other viewers do too, so a report that counts only this has never
     * had a sidebar the button could go in. The patch calls it first thing in that component's
     * render. It counts and changes nothing, whatever the switch, Pause or the settings' state.
     * Never throws.
     */
    public static void otherSidebarBuilt() {
        ReelSidebarDiagnostics.sidebarObserved();
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, OTHER_SIDEBAR);
    }

    /**
     * The button's name, in the phone's language. Facebook's factory takes it as a plain string,
     * and it's what a screen reader says for the button, since nothing is written under the icon.
     * It was the English word written into the patch, so every phone heard "Download". Never throws.
     */
    public static String label() {
        try {
            return SendLink.sending() ? L10n.t("Send to app") : L10n.t("Download");
        } catch (Throwable t) {
            return "Download";
        }
    }

    /** The Cast button's name, in the phone's language, the way {@link #label} names Download. Never throws. */
    public static String castLabel() {
        try {
            return L10n.t("Cast this video");
        } catch (Throwable t) {
            return "Cast";
        }
    }

    /**
     * Whether the helper builds the Download button, asked after {@link #showsButton} said yes, so a
     * sidebar with only the Cast button on gets no Download. Never throws.
     */
    public static boolean downloadButton() {
        try {
            return Settings.DOWNLOAD_REELS.get() && Settings.SHOW_REEL_DOWNLOAD_BUTTON.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the helper builds the Cast button, asked the same way. Never throws. */
    public static boolean castButton() {
        try {
            return Settings.REEL_CAST_BUTTON.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Answers null rather than {@code kotlin.Unit}. Facebook renames its own copy of Unit, so the
     * name does not resolve inside the app, and the sidebar's callers discard what a handler
     * answers without looking at it. null also passes any cast one of them might make.
     */
    @Override
    public Object invoke(Object argument) {
        try {
            // A Cast button's tap slot casts and its other slots stay silent. A long press copies
            // nothing there, since that's the Download button's.
            if (cast) {
                if (saves) castTap();
                return null;
            }
            // Only the tap slot saves. The others get the same object, so that no parameter of
            // the factory is null, and they return without a word. They fire on every touch and
            // every visibility change, which buries the log that this feature needs. While links
            // are sent, the touch slot's lift after a long press copies the link (#41).
            if (!saves) {
                if (SendLink.sending()) copyOnLongPress(argument);
                return null;
            }
            // The same lift ends in a tap too, which mustn't send the link the press just copied.
            if (tapEndsACopy(playerParams, SystemClock.uptimeMillis())) return null;

            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            final String event = argument == null ? "" : ", event " + argument.getClass().getName();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel download tapped" + event);

            save();
        } catch (Throwable t) {
            // Nothing can leave this method. It runs on the thread that draws, inside the app's
            // own click dispatch, so a throw here ends the app rather than the download.
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel handler", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the reel handler failed", t);
        }

        return null;
    }

    /** Hands this reel's file to a casting app: the file its player recorded, or the two addresses its source holds. */
    private void castTap() {
        HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
        Object source = sourceOf(playerParams);
        OpenInAnotherApp.castReel(context, videoIdOf(playerParams),
            source == null ? null : RenditionPicker.fieldValue(source, hdField),
            source == null ? null : RenditionPicker.fieldValue(source, sdField));
    }

    private void save() {
        if (SendLink.sending()) {
            String link = SendLink.reelLink(videoIdOf(playerParams));
            if (link == null) noLink();
            else SendLink.send(context, link);
            return;
        }

        Object source = sourceOf(playerParams);

        if (source == null) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "the player of this reel holds no source", null);
            return;
        }

        MediaDownload.saveVideo(context, source, hdField, sdField, manifestField,
            PostDetails.read(videoIdOf(playerParams), story));
    }

    /**
     * The long press that last copied a link, waiting for the tap Android sends for the same lift.
     * Shared, since the touch slot and the tap slot are two handlers of the one button, and tied to
     * that button's player params, which both hold, so a tap on another reel is never taken for
     * it. Null is no copy waiting.
     */
    private static volatile Copied copied;

    /** Which button's long press copied the link, and until when, on the uptime clock, its tap may come. */
    private static final class Copied {
        final Object button;
        final long until;

        Copied(Object button, long until) {
            this.button = button;
            this.until = until;
        }
    }

    /** How long after a long press copies the link its own tap may still come. */
    static final long TAP_AFTER_COPY_MS = 1000;

    /**
     * The touch slot's event: a finger lifted at least the phone's long-press time after it went
     * down copies the reel's link. Only a lift counts, so a press that turns into a swipe, which
     * ends in a cancel, copies nothing.
     */
    private void copyOnLongPress(Object argument) {
        MotionEvent event = motionEventOf(argument);
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_UP) return;
        if (event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) return;
        copied = new Copied(playerParams, SystemClock.uptimeMillis() + TAP_AFTER_COPY_MS);
        String link = SendLink.reelLink(videoIdOf(playerParams));
        if (link == null) noLink();
        else SendLink.copy(context, link);
    }

    /**
     * Whether a tap at [now] on the button of [button], its player params, ends the long press that
     * just copied the link there. Any tap settles the copy waiting, so only the first one can be.
     */
    static boolean tapEndsACopy(Object button, long now) {
        Copied waiting = copied;
        if (waiting == null) return false;
        copied = null;
        return waiting.button == button && now < waiting.until;
    }

    /** The MotionEvent [argument] is, or holds in a field of its own, or null. Never throws. */
    static MotionEvent motionEventOf(Object argument) {
        if (argument instanceof MotionEvent) return (MotionEvent) argument;
        if (argument == null) return null;
        try {
            for (java.lang.reflect.Field field : argument.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!MotionEvent.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(argument);
                return value instanceof MotionEvent ? (MotionEvent) value : null;
            }
        } catch (Throwable ignored) {
            // An event this can't read is a touch that copies nothing.
        }
        return null;
    }

    /** Says the reel's link couldn't be built: its player named no video id. */
    private void noLink() {
        Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
            () -> "the player of this reel names no video id, so it has no link", null);
        Context application = context == null ? Utils.getContext() : context.getApplicationContext();
        if (application != null) Feedback.show(application, L10n.t(application, "Couldn't find this reel's link"), true);
    }

    /** What Facebook's player params say of themselves on 577 and 580, before the id. */
    private static final String ID_PREFIX = "VideoId: ";

    /**
     * The reel's id on Facebook, for the file name, or {@code null}.
     *
     * <p>Facebook's player params keep their class name, and their toString says {@code "VideoId: "}
     * and the id: a kept string and the id field, joined, in both 577 and 580. The sidebar holds a
     * rich params object with one field of that type, so this reads that field, or the params
     * themselves when they're what was handed over. Two such fields leave no way to tell which is
     * the reel on the screen, and a wrong id would name the file after another video, so neither is
     * read. Anything else, or anything but digits after the prefix, is no id. Never throws.
     */
    static String videoIdOf(Object params) {
        try {
            if (params == null) return null;
            if (params.getClass().getName().equals(VIDEO_PLAYER_PARAMS)) return idFrom(params.toString());
            java.lang.reflect.Field only = null;
            for (java.lang.reflect.Field field : params.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().getName().equals(VIDEO_PLAYER_PARAMS)) continue;
                if (only != null) return null;
                only = field;
            }
            if (only == null) return null;
            only.setAccessible(true);
            Object plain = only.get(params);
            return plain == null ? null : idFrom(plain.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String idFrom(String said) {
        if (said == null || !said.startsWith(ID_PREFIX)) return null;
        String id = said.substring(ID_PREFIX.length());
        return FileNameTemplate.isVideoId(id) ? id : null;
    }

    private static final String VIDEO_DATA_SOURCE = "com.facebook.video.engine.api.VideoDataSource";
    private static final String VIDEO_PLAYER_PARAMS = "com.facebook.video.engine.api.VideoPlayerParams";

    /**
     * The source of the player, found by type rather than by name.
     *
     * <p>The params object holds exactly one field of the source type, and that type is a name that
     * Redex keeps, so no letter has to be written down here. Two fields of it at one level would
     * mean that rule no longer picks the reel on the screen, so the walk takes neither and says
     * so in Hook status rather than save a reel that could be the next one.
     */
    static Object sourceOf(Object params) {
        if (params == null) return null;

        // The sidebar holds a rich params object, which holds the plain one, which holds the
        // source. Walking by type keeps all three of those names out of this file.
        Object current = params;

        for (int depth = 0; depth < 3 && current != null; depth++) {
            Object next = null;
            java.lang.reflect.Field sourceField = null;
            int sourceFields = 0;

            for (java.lang.reflect.Field field : current.getClass().getDeclaredFields()) {
                try {
                    String type = field.getType().getName();

                    if (type.equals(VIDEO_DATA_SOURCE)) {
                        if (sourceFields++ == 0) sourceField = field;
                    } else if (type.equals(VIDEO_PLAYER_PARAMS)) {
                        field.setAccessible(true);
                        next = field.get(current);
                    }
                } catch (Throwable ignored) {
                    // One unreadable field must not end the walk.
                }
            }

            if (sourceFields > 1) {
                HookStatus.ambiguous(FamilyNames.REEL_DOWNLOAD, "field", current.getClass().getName(),
                    VIDEO_DATA_SOURCE, sourceFields);
                return null;
            }
            if (sourceField != null) {
                HookStatus.bound(FamilyNames.REEL_DOWNLOAD, VIDEO_DATA_SOURCE);
                try {
                    sourceField.setAccessible(true);
                    return sourceField.get(current);
                } catch (Throwable unreadable) {
                    return null;
                }
            }

            current = next;
        }

        HookStatus.missingMember(FamilyNames.REEL_DOWNLOAD, "field", params.getClass().getName(), VIDEO_DATA_SOURCE);
        return null;
    }

}
