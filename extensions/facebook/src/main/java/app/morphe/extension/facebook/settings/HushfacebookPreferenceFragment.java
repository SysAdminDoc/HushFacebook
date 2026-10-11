/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.coexist.FamilySignatureTrust;
import app.morphe.extension.facebook.comments.CommentOrder;
import app.morphe.extension.facebook.download.DownloadQuality;
import app.morphe.extension.facebook.download.FileNameTemplate;
import app.morphe.extension.facebook.download.SaveControl;
import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.facebook.download.SaveTo;
import app.morphe.extension.facebook.download.SendLink;
import app.morphe.extension.facebook.feed.PostSources;
import app.morphe.extension.facebook.feed.PostWords;
import app.morphe.extension.facebook.feed.ReactionCeiling;
import app.morphe.extension.facebook.feed.TopicPacks;
import app.morphe.extension.facebook.feed.SeenPosts;
import app.morphe.extension.facebook.media.PlaybackQuality;
import app.morphe.extension.facebook.media.SurfaceQuality;
import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.misc.ShareSheetItems;
import app.morphe.extension.facebook.misc.TextSize;
import app.morphe.extension.facebook.theme.AccentColor;
import app.morphe.extension.facebook.navigation.HiddenTabs;
import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.facebook.navigation.MarketplaceOnly;
import app.morphe.extension.facebook.notifications.QuietHour;
import app.morphe.extension.facebook.settings.SettingsRows.Heading;
import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.facebook.settings.SettingsRows.SaveRow;
import app.morphe.extension.facebook.settings.SettingsRows.Toggle;
import app.morphe.extension.facebook.settings.ValueRows.CommentOrderRow;
import app.morphe.extension.facebook.settings.ValueRows.LockAfterRow;
import app.morphe.extension.facebook.settings.ValueRows.SeenKeepRow;
import app.morphe.extension.facebook.settings.ValueRows.AccentRow;
import app.morphe.extension.facebook.settings.ValueRows.ReactionCeilingRow;
import app.morphe.extension.facebook.settings.ValueRows.TextSizeRow;
import app.morphe.extension.facebook.settings.ValueRows.DownloadActionRow;
import app.morphe.extension.facebook.settings.ValueRows.FeedsSubtabRow;
import app.morphe.extension.facebook.settings.ValueRows.FileNameRow;
import app.morphe.extension.facebook.settings.ValueRows.FolderRow;
import app.morphe.extension.facebook.settings.ValueRows.PlaybackQualityRow;
import app.morphe.extension.facebook.settings.ValueRows.QuietHourRow;
import app.morphe.extension.facebook.settings.ValueRows.QualityRow;
import app.morphe.extension.facebook.settings.ValueRows.SaveToRow;
import app.morphe.extension.facebook.settings.ValueRows.SendAppRow;
import app.morphe.extension.facebook.settings.ValueRows.StartTabRow;
import app.morphe.extension.facebook.settings.ValueRows.SubfolderRow;
import app.morphe.extension.facebook.settings.ValueRows.SurfaceQualityRow;
import app.morphe.extension.facebook.settings.ValueRows.SourcesRow;
import app.morphe.extension.facebook.settings.ValueRows.WordsRow;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The preference list, built in code rather than from an XML resource so the bundle adds no
 * resources to Facebook. A switch appears only when its patch is in this build
 * ({@link PatchFamily}), but for the settings entry's own ({@link PatchFamily#ENTRY_SWITCHES}),
 * which every build has, and the ones the downloads share ({@link PatchFamily#DOWNLOAD_SWITCHES}),
 * which any download patch brings; a patch that works entirely at patch time gets a line saying so, and
 * what Pause can't reach is listed under the Pause switch. Switches are keyed by their setting,
 * which is how the shared fragment keeps them in sync with stored values. Every word is read from
 * {@link L10n} in the phone's language; the product names and the address stay as they are.
 * Each section is built by its category page's class, {@link FeedPages} and the three beside it,
 * from the row kinds in {@link SettingsRows} and {@link ValueRows} and the builders here.
 */
@SuppressWarnings("deprecation")
public final class HushfacebookPreferenceFragment extends AbstractPreferenceFragment
        implements ReleaseCheck.Listener {
    /** The repository as a link, and as a person reads it. ExtensionHostsTest reads the link. */
    static final String SOURCE_URL = "https://github.com/SysAdminDoc/Hushfacebook";
    static final String SOURCE_ADDRESS = SOURCE_URL.substring(SOURCE_URL.indexOf("://") + 3);
    /** The English of the row listing what Pause can't reach, and its key in {@link L10n}. */
    static final String STAYS_WHILE_PAUSED = "Stays in while paused";
    /** The Check now row's key. It stores nothing: no setting has this name. */
    static final String CHECK_NOW = "action_check_for_release";
    /** The Supported links row's key. It stores nothing either. */
    static final String SUPPORTED_LINKS = "action_supported_links";
    /** The key of the overview row explaining a missing re-signed build fix. */
    static final String MISSING_RESTORE_TRUST = "action_missing_restore_trust";
    /** The key of the row naming the default patches this build lacks. It stores nothing either. */
    static final String MISSING_DEFAULTS = "action_missing_default_patches";
    /** How many of the missing patches that row names before it counts the rest. */
    static final int MISSING_NAMES_SHOWN = 8;

    /** Thrown by the next initialize() and then cleared: how a test reaches the recovery page. */
    static volatile RuntimeException failNextInitialization;

    /** The first row, which says whether Hushfacebook runs now and whether the next start changes that. */
    @Nullable
    private Preference statusCard;

    /** The row that asks GitHub for the newest release now, and says what the last try found. */
    @Nullable
    private Preference checkNowRow;

    /** Where a settings file waiting on the person's answer is kept across the page being rebuilt. */
    static final String PENDING_IMPORT_STATE = "hushfacebook_pending_import";

    /**
     * A settings file that was read and waits on the person's answer, as
     * {@link SettingsBackup.Snapshot#toBundle()} wrote it, or null.
     */
    @Nullable
    Bundle pendingImport;

    /** The preview of {@link #pendingImport} while it's on screen. */
    @Nullable
    AlertDialog importPreview;

    /** The page's other dialogs that may still be on screen: the sections, Licenses and a word list's note. */
    private final List<Dialog> shownDialogs = new ArrayList<>();

    /**
     * Where the list was before the last section jump, as its first visible position and that row's
     * top, which Back goes back to once; null when there's been no jump since.
     */
    @Nullable
    int[] beforeJump;

    @Nullable
    SettingsNavigation navigation;

    /** The Downloads section, where the running saves are listed, or null when no download patch is in. */
    @Nullable
    private PreferenceCategory downloads;

    /** The rows of the saves running now, by save number. */
    private final Map<Integer, SaveRow> saveRows = new HashMap<>();

    /** Keeps the rows in step with the saves while the page is showing. Saves tell it from their own thread. */
    private final SaveControl.Watcher saves = () -> Utils.runOnMainThread(this::showSaves);

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // The complete model remains usable as a standalone preference page. The dialog adds
        // its navigation shell only after the framework has bound that model to the list.
        if (getParentFragment() instanceof SettingsDialog && !sections().isEmpty()) {
            navigation = new SettingsNavigation(this, (SettingsDialog) getParentFragment(), savedInstanceState);
        }
        showRequestedSetting();
    }

    /** Goes to the row the request that opened the page named, if it named one this build has. */
    void showRequestedSetting() {
        String key = SettingsEntry.takeRequestedSetting();
        Preference target = key == null ? null : findPreference(key);
        if (target != null) jumpTo(target);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) pendingImport = savedInstanceState.getBundle(PENDING_IMPORT_STATE);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            list.setDivider(null);
            list.setDividerHeight(0);
            // From the context, not from what initialize() stores: a page whose initialize() failed
            // before it got that far kept an earlier screen's colours, or none, and a light Material
            // You error page drew its dark message on black.
            ScreenColors colors = ScreenColors.forScreen(getContext());
            list.setBackgroundColor(colors == null ? Color.BLACK : colors.background);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        SettingsBackupPreference.onPageResumed(this);
        showMarketplaceSettings();
        showSupportedLinks();
        SaveControl.watch(saves);
        showSaves();
    }

    @Override
    public void onPause() {
        SaveControl.unwatch(saves);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingImport != null) outState.putBundle(PENDING_IMPORT_STATE, pendingImport);
        if (navigation != null) navigation.save(outState);
    }

    @Override
    public void onDestroyView() {
        // The preview is drawn over this page's window. It stays unanswered, and the page that
        // replaces this one shows it again.
        SettingsBackupPreference.closePreview(this);
        // The page's other dialogs are drawn over its window too, and would outlive it.
        for (Dialog dialog : new ArrayList<>(shownDialogs)) dialog.dismiss();
        shownDialogs.clear();
        ReleaseCheck.unwatch(this);
        if (navigation != null) navigation.close();
        navigation = null;
        super.onDestroyView();
    }

    /** A release check ended: the card and the Check now row say what it found. */
    @Override
    public void releaseCheckFinished() {
        Context context = getContext();
        if (context == null) return;
        if (statusCard != null) showStatus(statusCard, context);
        if (checkNowRow != null) checkNowRow.setSummary(ReleaseCheck.checkNowSummary());
    }

    /**
     * The file pickers' answers. Android finds this page by the name the framework gave it when
     * the picker was opened, and a page rebuilt while the picker was up is given the same name.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (SettingsBackupPreference.onResult(this, requestCode, resultCode, data)) return;
        FontFilePreference.onResult(this, requestCode, resultCode, data);
    }

    /** Shows what the switches are saved as, after an import wrote them. */
    void refreshSwitches() {
        updateUIToSettingValues();
    }

    @Override
    protected void initialize() {
        RuntimeException fault = failNextInitialization;
        if (fault != null) {
            failNextInitialization = null;
            throw fault;
        }
        // Loads the switches before the shared fragment syncs them to the screen.
        Settings.HIDE_SPONSORED_POSTS.get();

        // Every row inflates with the theme of the context it was built with. Facebook's activity
        // theme is light, so its near-black primary text vanished on this screen's black background
        // on a phone (2026-09-24). The rows get a dark Material theme instead, or with the Material
        // You theme in the build, the phone's dark or light one in its wallpaper colours.
        ScreenColors.shown = ScreenColors.forScreen(getContext());
        Context context = themed(getContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        screen.addPreference(statusCard(context));
        // The export row below reads these; registering twice keeps one.
        PatchFamily.registerDiagnostics();
        LogBufferManager.registerReportSection(ReleaseCheck.REPORT);
        Set<PatchFamily> build = PatchFamily.inThisBuild();
        // Right under the card, once, for someone a default change turned switches off for.
        Preference startsOff = StartsOffNote.row(context, build);
        if (startsOff != null) screen.addPreference(startsOff);
        screen.addPreference(jumpRow(context));

        // The sections in the order they come on the page, each in its category page's class.
        FeedPages.opening(this, screen, context, build);
        FeedPages.newsFeed(this, screen, context, build);
        FeedPages.stories(this, screen, context, build);
        VideoPages.reels(this, screen, context, build);
        FeedPages.comments(this, screen, context, build);
        FeedPages.writing(this, screen, context, build);
        VideoPages.playback(this, screen, context, build);
        downloads = VideoPages.downloads(screen, context, build);
        AppPages.chats(this, screen, context, build);
        AppPages.menu(this, screen, context, build);
        AppPages.search(this, screen, context, build);
        AppPages.marketplace(this, screen, context, build);
        AppPages.notifications(this, screen, context, build);
        AppPages.links(this, screen, context, build);
        AppPages.privacy(this, screen, context, build);
        HushfacebookPages.updates(this, screen, context, build);
        HushfacebookPages.appearance(this, screen, context, build);
        HushfacebookPages.patched(this, screen, context, build);
        HushfacebookPages.pause(this, screen, context, build);
        HushfacebookPages.about(this, screen, context, build);

        // The overview shows these under the card by key. Last in the model, they move no other row.
        Preference restore = missingRestoreTrustRow(context, build);
        if (restore != null) screen.addPreference(restore);
        Preference lacking = missingDefaultsRow(context, build);
        if (lacking != null) screen.addPreference(lacking);
    }

    /**
     * Lists each save running now at the top of Downloads, and takes a finished one away. The
     * notification was the only way to follow or stop a save, and with Facebook's notifications or
     * the saves channel off there wasn't one. A row that stays is changed in place.
     */
    void showSaves() {
        PreferenceCategory group = downloads;
        if (group == null) return;
        List<SaveControl.Running> running = SaveControl.running();
        Set<Integer> now = new HashSet<>();
        for (SaveControl.Running save : running) now.add(save.id);
        for (java.util.Iterator<Map.Entry<Integer, SaveRow>> rows = saveRows.entrySet().iterator(); rows.hasNext(); ) {
            Map.Entry<Integer, SaveRow> row = rows.next();
            if (now.contains(row.getKey())) continue;
            group.removePreference(row.getValue());
            rows.remove();
        }
        for (SaveControl.Running save : running) {
            SaveRow row = saveRows.get(save.id);
            if (row != null) {
                row.show(save);
                continue;
            }
            row = new SaveRow(group.getContext(), save);
            saveRows.put(save.id, row);
            group.addPreference(row);
        }
    }

    /**
     * Straight to one section. The page is long enough that reaching Downloads took several screens
     * of swiping on a phone (2026-09-26), so two taps reach any section from the top, and Back goes
     * back once to where the list was.
     */
    private Preference jumpRow(Context context) {
        Row row = new Row(context);
        row.setKey("action_jump_to_section");
        row.setPersistent(false);
        row.setTitle(L10n.t("Jump to a section"));
        row.setSummary(L10n.t("Go straight to one group of settings. Back returns to where you were."));
        row.setOnPreferenceClickListener(p -> {
            showSections(context);
            return true;
        });
        return row;
    }

    /** The page's section titles in the order they're on the page; a tap on one goes there. */
    void showSections(Context context) {
        List<Preference> sections = sections();
        CharSequence[] titles = new CharSequence[sections.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = sections.get(i).getTitle();
        show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Jump to a section"))
                .setItems(titles, (dialog, which) -> jumpTo(sections.get(which)))
                .setNegativeButton(L10n.t("Cancel"), null));
    }

    /**
     * Shows [builder]'s dialog in the screen's colours, and closes it with the page's view. Nothing
     * shows once the view is gone or the activity is finishing: a word list's Save can land as the
     * activity goes, and its note would come up over a window that's gone.
     */
    void show(AlertDialog.Builder builder) {
        Activity activity = getActivity();
        if (getView() == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = builder.show();
        ScreenColors.dialog(dialog);
        shownDialogs.add(dialog);
        dialog.setOnDismissListener(shownDialogs::remove);
    }

    /** The section headings on the page, in order. */
    List<Preference> sections() {
        List<Preference> sections = new ArrayList<>();
        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 0; screen != null && i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof PreferenceCategory) sections.add(preference);
        }
        return sections;
    }

    /**
     * Scrolls [section]'s heading to the top of the list, and remembers where the list was for
     * Back. Looked up in the list's own adapter, so it lands right however the page is flattened.
     */
    boolean jumpTo(Preference section) {
        if (navigation != null) return navigation.open(section);
        ListView list = listView();
        if (list == null || list.getAdapter() == null) return false;
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            if (list.getAdapter().getItem(position) != section) continue;
            View first = list.getChildAt(0);
            beforeJump = new int[] {list.getFirstVisiblePosition(), first == null ? 0 : first.getTop()};
            list.setSelectionFromTop(position, 0);
            return true;
        }
        return false;
    }

    /** Back after a jump: the list goes back to where it was, once, and the page stays open. */
    boolean backFromJump() {
        if (navigation != null) return navigation.back();
        int[] before = beforeJump;
        if (before == null) return false;
        beforeJump = null;
        ListView list = listView();
        if (list == null) return false;
        list.setSelectionFromTop(before[0], before[1]);
        return true;
    }

    @Nullable
    private ListView listView() {
        View view = getView();
        return view == null ? null : view.findViewById(android.R.id.list);
    }

    /**
     * The first row: whether Hushfacebook is on, and why not when it isn't. Paused by safe mode or
     * the marker file, a tap turns it back on from the next start.
     */
    private Preference statusCard(Context context) {
        Row card = new Row(context);
        card.setPersistent(false);
        ScreenColors colors = ScreenColors.shown;
        boolean paused = HushfacebookPause.isPaused();
        ScreenColors palette = colors == null ? ScreenColors.DEFAULT : colors;
        card.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                paused ? palette.summary : palette.heading));
        statusCard = card;
        if (!paused) {
            card.setTitle(L10n.t("Hushfacebook is on"));
            card.setSelectable(false);
            showStatus(card, context);
            return card;
        }
        card.setTitle(L10n.t("Hushfacebook is paused"));
        showStatus(card, context);
        // A tap acts at once, taking the pause off the next start, and the card says so in words.
        card.actsAtOnce = true;
        card.setOnPreferenceClickListener(p -> {
            resumeFromOverview();
            return true;
        });
        return card;
    }

    /**
     * Under the card when this build lacks a patch Morphe Manager selects by default, or null when
     * it has them all. A patch left out is the usual answer to "ads still show" (#29, #35). A tap
     * opens and closes the list of names, which would run past eighty for a build patched with one
     * patch picked, so it names {@link #MISSING_NAMES_SHOWN} and counts the rest.
     */
    @Nullable
    private static Preference missingDefaultsRow(Context context, Set<PatchFamily> build) {
        List<String> missing = PatchFamily.missingDefaults(build);
        missing.remove(PatchFamily.RESTORE_TRUST.patchName);
        if (missing.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (String name : missing) names.add(L10n.isolate(name));
        // A build patched with a selection saved before 32 patches joined the default one lacks all
        // of them, and the names ran on for a screen. Past a few the rest are a count: Manager's
        // default selection brings them all, and the diagnostic report names every one.
        if (names.size() > MISSING_NAMES_SHOWN + 1) {
            int more = names.size() - MISSING_NAMES_SHOWN;
            names = new ArrayList<>(names.subList(0, MISSING_NAMES_SHOWN));
            names.add(L10n.f("%1$d more", more));
        }
        String closed = L10n.t("Tap to see which.");
        String open = L10n.quantity(missing.size(),
                "Not in this build: %1$s. Morphe Manager selects it by default. Patch again with it selected to "
                        + "get what it does.",
                "Not in this build: %1$s. Morphe Manager selects them by default. Patch again with them selected to "
                        + "get what they do.",
                L10n.join(names));
        Row row = new Row(context);
        row.setKey(MISSING_DEFAULTS);
        row.setPersistent(false);
        // The tap only opens or closes the list, so the row goes without a chevron.
        row.actsAtOnce = true;
        row.setTitle(L10n.quantity(missing.size(), "%1$d default patch isn't in this build",
                "%1$d default patches aren't in this build", missing.size()));
        row.setSummary(closed);
        row.setOnPreferenceClickListener(p -> {
            p.setSummary(closed.contentEquals(p.getSummary()) ? open : closed);
            return true;
        });
        return row;
    }

    /**
     * A missing Restore screens patch breaks profiles, photos and posts on ordinary re-signed
     * installs (#68, #97). Pull it out of the generic missing-defaults list so the overview says
     * exactly what fails. Root Mount installs still carry Meta's key and do not need this patch.
     */
    @Nullable
    private static Preference missingRestoreTrustRow(Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.RESTORE_TRUST)) return null;
        if (FamilySignatureTrust.thisBuildCarriesMetaKey(context)) return null;
        Preference row = info(context, L10n.t("Profiles, photos and posts won't open"),
                L10n.f("Patch again with %1$s selected. A patched Facebook needs it to open profiles, photos, posts and "
                        + "some of Facebook's Settings pages.", L10n.isolate(FamilyNames.RESTORE_TRUST)));
        row.setKey(MISSING_RESTORE_TRUST);
        return row;
    }

    void resumeFromOverview() {
        Context context = getContext();
        if (context == null || statusCard == null) return;
        HushfacebookPause.Reason still = HushfacebookPause.turnBackOn(context);
        // The switch shows what was kept. Setting it to off here would write off through the
        // preference itself when the store had just refused to.
        Preference pause = findPreference(BaseSettings.PAUSED.key);
        if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(BaseSettings.PAUSED.savedValue());
        if (still == HushfacebookPause.Reason.MARKER_FILE) {
            String file = L10n.isolate(HushfacebookPause.MARKER_FILE_NAME);
            String folder = L10n.isolate(markerFolder(context.getPackageName()));
            // The switches stay as they were until the file goes, so say when they still pause.
            String left = BaseSettings.PAUSED.savedValue() || BaseSettings.SAFE_MODE.savedValue()
                    ? L10n.f("The file %1$s couldn't be removed. Delete it from %2$s, then tap Resume again.", file, folder)
                    : L10n.f("The file %1$s couldn't be removed. Delete it from %2$s to turn Hushfacebook back on.",
                    file, folder);
            statusCard.setSummary(left + "\n" + overviewBuildDetails());
            // Resume can be tapped on a category page too, where the card isn't in view.
            Utils.showToastLong(left);
            return;
        }
        if (still != HushfacebookPause.Reason.NONE) {
            Utils.showToastLong(L10n.t("Couldn't turn Hushfacebook back on. Try again."));
        }
        showStatus(statusCard, context);
    }

    /**
     * The card's line under its title: this start's state, and the next start's when a change on
     * this screen makes it differ. Pause switched on said "Hushfacebook is on" and nothing more,
     * and Pause switched back on after a tap on the card still said it turns back on at restart.
     * A newer release the release check found goes on a line of its own under that, paused or
     * not: a newer release can be the fix for what a pause is working around.
     */
    private void showStatus(Preference card, Context context) {
        boolean pausedNext = HushfacebookPause.pausesNextStart(context);
        String status;
        if (!HushfacebookPause.isPaused()) {
            status = L10n.t(pausedNext ? "Hushfacebook pauses when Facebook restarts." : "Your controls are active.");
        } else if (pausedNext) {
            status = pausedSummary(HushfacebookPause.reason(), context.getPackageName())
                    + " " + L10n.t("Tap to turn it back on.");
        } else {
            status = L10n.t("Hushfacebook turns back on when Facebook restarts.");
        }
        status += "\n" + overviewBuildDetails();
        String release = ReleaseCheck.statusLine();
        card.setSummary(release == null ? status : status + "\n" + release);
    }

    private static final java.util.regex.Pattern BUILD_IDENTITY = java.util.regex.Pattern.compile(
            "sha256=([0-9a-f]{64}); source=(?:(clean|dirty):[0-9a-f]{40}; tree=[0-9a-f]{40}|(unknown)); inputs=[0-9a-f]{64}");

    /** Only the overview abbreviates identity. About and exported diagnostics keep the original bytes. */
    static String overviewBuildDetails() {
        String value = Utils.getPatchesBuildIdentity();
        java.util.regex.Matcher identity = BUILD_IDENTITY.matcher(value == null ? "" : value);
        String build;
        if (identity.matches()) {
            String shortId = L10n.isolate(identity.group(1).substring(0, 8));
            build = "dirty".equals(identity.group(2)) ? L10n.f("Build %1$s (modified)", shortId)
                    : "clean".equals(identity.group(2)) ? L10n.f("Build %1$s (source known)", shortId)
                    : L10n.f("Build %1$s (source unknown)", shortId);
        } else {
            String state = value == null || value.isEmpty() || "unknown".equals(value) ? "unknown" : "unverified";
            build = L10n.f("Build %1$s", L10n.isolate(state));
        }
        return L10n.f("Version %1$s", L10n.isolate(Utils.getPatchesReleaseVersion())) + "\n" + build;
    }

    /**
     * Asks GitHub for the newest release now, and says what the last try found. The tap is the
     * request for that one check, so it runs with the switch off too.
     */
    Preference checkNowRow(Context context) {
        Row row = new Row(context);
        row.setKey(CHECK_NOW);
        row.setTitle(L10n.t("Check now"));
        row.setPersistent(false);
        // The tap starts the check and the row says how it went: nothing opens, so no chevron.
        row.actsAtOnce = true;
        row.setSummary(ReleaseCheck.checkNowSummary());
        row.setOnPreferenceClickListener(p -> {
            if (!ReleaseCheck.checkNow()) Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
            p.setSummary(ReleaseCheck.checkNowSummary());
            return true;
        });
        checkNowRow = row;
        return row;
    }

    /**
     * What Android says about sending Facebook's web addresses here, and the way to its page for
     * them (Morphe Manager #1028). The page opens over Facebook, so the row reads the state again
     * on the way back.
     */
    Preference supportedLinksRow(Context context) {
        Row row = new Row(context);
        row.setKey(SUPPORTED_LINKS);
        row.setTitle(L10n.t("Supported links"));
        row.setPersistent(false);
        row.setSummary(SupportedLinks.summary(SupportedLinks.read(context).state));
        row.setOnPreferenceClickListener(p -> {
            openLinkSettings(context);
            return true;
        });
        return row;
    }

    /**
     * The way to the link page of each of Meta's apps on the phone that may hold an address that
     * doesn't open here, under Supported links: Meta App Manager (#30), Messenger and Instagram
     * (#78). Android won't let a person select an address another app is verified for, so that app
     * has to let go first.
     */
    List<Preference> linkHolderRows(Context context) {
        SupportedLinks.Snapshot snapshot = SupportedLinks.read(context);
        List<Preference> rows = new ArrayList<>();
        for (SupportedLinks.Holder holder : SupportedLinks.Holder.values()) {
            if (!SupportedLinks.mayHoldLinks(holder, snapshot, SupportedLinks.isOn(context, holder))) continue;
            Row row = new Row(context);
            row.setKey(holder.rowKey);
            row.setTitle(holder.title());
            row.setPersistent(false);
            row.setSummary(SupportedLinks.holderSummary(holder, snapshot));
            row.setOnPreferenceClickListener(p -> {
                openLinkPage(SupportedLinks.holderIntents(holder), holder.reportKey, holder.notOpened());
                return true;
            });
            rows.add(row);
        }
        return rows;
    }

    private void showSupportedLinks() {
        if (getPreferenceScreen() == null) return;
        Preference row = findPreference(SUPPORTED_LINKS);
        if (row == null) return;
        SupportedLinks.Snapshot snapshot = SupportedLinks.read(row.getContext());
        row.setSummary(SupportedLinks.summary(snapshot.state));
        for (SupportedLinks.Holder holder : SupportedLinks.Holder.values()) {
            Preference holderRow = findPreference(holder.rowKey);
            if (holderRow != null) holderRow.setSummary(SupportedLinks.holderSummary(holder, snapshot));
        }
    }

    private void openLinkSettings(Context context) {
        openLinkPage(SupportedLinks.settingsIntents(context), "supported links",
                L10n.t("Android's settings for this app didn't open. Open App info from Facebook's icon, "
                        + "then Open by default."));
    }

    private void openLinkPage(List<Intent> pages, String what, String failure) {
        for (Intent page : pages) {
            try {
                startActivity(page);
                return;
            } catch (ActivityNotFoundException | SecurityException missing) {
                // A phone without Open by default still has the app's own page, which leads there.
            }
        }
        Logger.printInfo(() -> "No settings page opened for " + what);
        Utils.showToastLong(failure);
    }

    @Override
    protected boolean noteRestartPending(Setting<?> setting, Object valueBefore) {
        if (setting != Settings.MARKETPLACE_ONLY) return super.noteRestartPending(setting, valueBefore);
        // This mode can leave the bar unchanged when Marketplace is unavailable. Its observed
        // result, rather than the previous saved switch, decides whether restarting is useful.
        boolean pending = MarketplaceOnly.state() == MarketplaceOnly.State.RESTART_NEEDED;
        if (pending) restartPending.add(setting.key);
        else restartPending.remove(setting.key);
        onRestartPendingChanged();
        return pending;
    }

    @Override
    protected void onRestartPendingChanged() {
        Preference card = statusCard;
        Context context = getContext();
        if (card != null && context != null) showStatus(card, context);
        showMarketplaceSettings();
    }

    @Override
    protected void updateUIAvailability() {
        super.updateUIAvailability();
        showMarketplaceSettings();
        showSaveFolder();
    }

    @Override
    protected void updateUIToSettingValues() {
        super.updateUIToSettingValues();
        showMarketplaceSettings();
        showSaveFolder();
        showShareItemsSummary();
    }

    /** No setting is behind the share sheet row's key, so its summary is redone here after an import. */
    private void showShareItemsSummary() {
        if (getPreferenceScreen() == null) return;
        Preference row = findPreference(AppPages.SHARE_ITEMS_ROW);
        if (row != null) row.setSummary(AppPages.shareItemsSummary(ShareSheetItems.hidden()));
    }

    /**
     * The folder row names the top folder Save to picks, in its summary and its dialog, so it's
     * redone after a tap on either row or an import.
     */
    private void showSaveFolder() {
        if (getPreferenceScreen() == null) return;
        Preference folder = findPreference(Settings.SAVE_FOLDER.key);
        if (!(folder instanceof FolderRow)) return;
        FolderRow row = (FolderRow) folder;
        row.setSummary(folderSummary(SaveFolder.sanitize(row.getText())));
        row.setDialogMessage(folderDialogMessage(Settings.SAVE_TO.savedValue()));
        for (String key : new String[]{Settings.VIDEO_SUBFOLDER.key, Settings.PHOTO_SUBFOLDER.key}) {
            Preference subfolder = findPreference(key);
            if (subfolder instanceof SubfolderRow) ((SubfolderRow) subfolder).showSummary();
        }
    }

    /**
     * Recomputed after a tap, an import or a resumed page; no saved start-tab choice is changed.
     * The start tab's rows are redone without Marketplace only too, since Hide the Reels tab changes
     * what a chosen Reels tab does.
     */
    private void showMarketplaceSettings() {
        if (getPreferenceScreen() == null) return;
        Preference mode = findPreference(Settings.MARKETPLACE_ONLY.key);
        boolean selected = mode != null && Settings.MARKETPLACE_ONLY.savedValue();
        if (mode != null) {
            mode.setSummary(marketplaceSummary());
            Preference regular = findPreference("action_regular_facebook");
            if (regular != null) regular.setEnabled(selected);
            Preference quiet = findPreference(Settings.MARKETPLACE_QUIET_NOTIFICATIONS.key);
            if (quiet != null) quiet.setEnabled(selected);
            Preference prefetch = findPreference(Settings.MARKETPLACE_SKIP_FEED_PREFETCH.key);
            if (prefetch != null) prefetch.setEnabled(selected);
        }
        Preference chosen = findPreference(Settings.OPEN_ON_CHOSEN_TAB.key);
        if (chosen != null) {
            chosen.setEnabled(!selected);
            chosen.setSummary(selected ? L10n.t("Marketplace only chooses the opening tab. Your previous choice stays saved.")
                    : L10n.t("Choose where Facebook opens from its icon. Notifications and links still open their destination."));
        }
        Preference tab = findPreference(Settings.START_TAB.key);
        if (tab != null) {
            tab.setEnabled(!selected);
            tab.setSummary(selected ? L10n.t("Marketplace only chooses the opening tab. Your previous choice stays saved.")
                    : startTabSummary(Settings.START_TAB.savedValue()));
        }
        Preference subtab = findPreference(Settings.FEEDS_SUBTAB.key);
        if (subtab != null) {
            subtab.setEnabled(!selected);
            subtab.setSummary(selected ? L10n.t("Marketplace only chooses the opening tab. Your previous choice stays saved.")
                    : feedsSubtabSummary(Settings.FEEDS_SUBTAB.savedValue()));
        }
    }

    /** The AMOLED row under Patched: black, or the Background colour the patch was given (issue #34). */
    static String amoledSummary(int background) {
        if (background == Color.BLACK) {
            return L10n.t("Dark mode uses black instead of dark gray. Turn on dark mode in Facebook to see it.");
        }
        String colour = String.format(Locale.ROOT, "#%06X", background & 0xFFFFFF);
        return L10n.f("Dark mode uses %1$s instead of dark gray. Turn on dark mode in Facebook to see it.",
                L10n.isolate(colour));
    }

    static String marketplaceSummary() {
        switch (MarketplaceOnly.state()) {
            case ACTIVE:
                return L10n.t("Active. Marketplace, Notifications and Profile/Menu stay. Find Hushfacebook settings in Menu, under Settings and privacy.");
            case RESTART_NEEDED:
                return Settings.MARKETPLACE_ONLY.savedValue()
                        ? L10n.t("Restart needed. Marketplace only will turn on the next time Facebook starts.")
                        : L10n.t("Restart needed. The normal tabs will return the next time Facebook starts.");
            case PAUSED:
                return L10n.t("Paused with Hushfacebook. Your Marketplace choice stays saved. Tabs already hidden return after a restart while paused.");
            case HIDDEN:
                return L10n.t("Marketplace is hidden in Facebook's tab settings. Show it under Settings, Tab bar, Customize the bar. Your normal tabs stay available.");
            case MISSING:
                return L10n.t("Marketplace unavailable. Facebook hasn't supplied a Marketplace tab for this account. Your normal tabs stay available.");
            case UNREADABLE:
                return L10n.t("Couldn't check the tab bar. Your normal tabs stay available. Restart Facebook to try again.");
            case WAITING:
                return L10n.t("Waiting for Facebook's tab bar. Marketplace only will apply when Facebook builds it.");
            default:
                return L10n.t("Off. Turn on to open Marketplace and hide the feed and other social tabs after restarting Facebook.");
        }
    }

    /**
     * Why this start runs paused, what a pause does and doesn't reach, and that nothing the reader
     * saved has changed.
     */
    static String pausedSummary(HushfacebookPause.Reason reason, String packageName) {
        String why;
        switch (reason) {
            case CRASH_LOOP:
                // Only a crash, a native crash or a hang counts toward safe mode (HushfacebookPause).
                why = L10n.t("Facebook crashed or froze within a minute of starting three times in a row, so "
                        + "Hushfacebook paused itself.");
                break;
            case MARKER_FILE:
                why = L10n.f("A file named %1$s in %2$s paused Hushfacebook.",
                        L10n.isolate(HushfacebookPause.MARKER_FILE_NAME), L10n.isolate(markerFolder(packageName)));
                break;
            default:
                why = L10n.t("You paused Hushfacebook.");
                break;
        }
        return why + " " + L10n.t("Every switch but Debug logging and Lock Facebook acts as if it were off, and "
                + "what was set when you patched stays in. Your settings stay as they are.");
    }

    /**
     * Where the marker file goes, the way a file manager shows it: the app's own files folder, not
     * the folder above it, which is the one a person finds first.
     */
    static String markerFolder(String packageName) {
        return "Android/data/" + packageName + "/files";
    }

    /**
     * The theme every row and dialog on this screen is built with, over Facebook's own: dark
     * Material, or with the Material You theme in the build, the phone's dark or light setting.
     */
    static Context themed(Context base) {
        return new ContextThemeWrapper(base, ScreenColors.themeFor(base));
    }

    /** The recovery page draws on the same page as the rows, so it gets the same theme. */
    @Override
    protected Context pageContext(Activity activity) {
        return themed(activity);
    }

    @Override
    protected ErrorActionStyler errorActionStyler() {
        return ScreenColors::recoveryAction;
    }

    @Override
    protected void styleInitializationMessage(View row) {
        ScreenColors.recoveryMessage(row);
    }

    static PreferenceCategory category(PreferenceScreen screen, String title) {
        PreferenceCategory category = new Heading(screen.getContext());
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    static SwitchPreference toggle(Context context, BooleanSetting setting, String summary) {
        return toggle(context, setting, SwitchLabels.title(setting), summary);
    }

    static SwitchPreference toggle(Context context, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference = new Toggle(context);
        preference.setKey(setting.key);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    /**
     * The quality a video save asks for. The list's values are the setting's own names, which is
     * what the shared page syncs a list by, and the summary says what the choice does.
     */
    static QualityRow qualityRow(Context context) {
        QualityRow row = new QualityRow(context);
        row.setKey(Settings.DOWNLOAD_QUALITY.key);
        row.setTitle(L10n.t("Download quality"));
        row.setDialogTitle(L10n.t("Download quality"));
        // A list's dialog keeps DialogPreference's Cancel, which Android fills in the activity's
        // language unless it's set here, as the folder and file name rows' are.
        row.setNegativeButtonText(L10n.t("Cancel"));
        DownloadQuality[] qualities = DownloadQuality.values();
        CharSequence[] entries = new CharSequence[qualities.length];
        CharSequence[] values = new CharSequence[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            entries[i] = qualityLabel(qualities[i]);
            values[i] = qualities[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.DOWNLOAD_QUALITY.savedValue().name());
        return row;
    }

    /** What the list shows for [quality]: a word for the two ends, the label itself between them. */
    static String qualityLabel(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Best");
            case SMALLEST:
                return L10n.t("Smallest");
            default:
                return L10n.isolate(quality.ceilingLabel());
        }
    }

    /**
     * What a save does with [quality], for the row's summary. A cap takes the best rendition at or
     * under it, and only a video with nothing that low goes above it ({@link DownloadQuality}), so
     * the sentence names both directions: "the closest quality" alone read as 1080p beating 240p
     * under a 720p cap, and the save picks 240p.
     */
    static String qualitySummary(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Each video saves at the best quality the player streams.");
            case SMALLEST:
                return L10n.t("Each video saves at its lowest quality, for the smallest file.");
            default:
                return L10n.f("Each video saves at %1$s or the closest quality below it. A video with nothing "
                        + "that low saves at the closest quality above.", L10n.isolate(quality.ceilingLabel()));
        }
    }

    /**
     * The tab a start from the launcher icon opens on. Like the quality row, its values are the
     * setting's own names and its summary says what the choice does.
     */
    static StartTabRow startTabRow(Context context) {
        StartTabRow row = new StartTabRow(context);
        row.setKey(Settings.START_TAB.key);
        row.setTitle(L10n.t("Tab to open on"));
        row.setDialogTitle(L10n.t("Tab to open on"));
        // Android's own Cancel follows the activity's language, as the quality row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        StartTab[] tabs = StartTab.values();
        CharSequence[] entries = new CharSequence[tabs.length];
        CharSequence[] values = new CharSequence[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            entries[i] = tabLabel(tabs[i]);
            values[i] = tabs[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.START_TAB.savedValue().name());
        return row;
    }

    /** What the list, its summary and an import's preview call [tab]: the tab's name in Facebook. */
    static String tabLabel(StartTab tab) {
        switch (tab) {
            case HOME:
                return L10n.t("Home");
            case FEEDS:
                return L10n.t("Feeds");
            case VIDEO:
                return L10n.t("Video");
            case FRIENDS:
                return L10n.t("Friends");
            case NOTIFICATIONS:
                return L10n.t("Notifications");
            case MENU:
                return L10n.t("Menu");
            default:
                return L10n.t("Marketplace");
        }
    }

    /**
     * What a start does with [tab], for the row's summary. Facebook opens Home for a tab the
     * account's tab bar hasn't got, so the summary says so rather than promise the tab. A Reels tab
     * Hide the Reels tab keeps off the bar is asked for as Home, and the summary says that instead.
     * Feeds is the exception: off the bar, for either reason, its own page opens over Home
     * (StartTabRoute's openFeedsPage).
     */
    static String startTabSummary(StartTab tab) {
        if (tab == StartTab.FEEDS) {
            return L10n.t("Facebook opens on Feeds. If your tab bar doesn't have it, the Feeds page opens over Home.");
        }
        if (tab == StartTab.VIDEO && Settings.HIDE_REELS_TAB.savedValue() && PatchFamily.REELS_TAB.inBuild()) {
            return L10n.t("Facebook opens on Home while Hide the Reels tab is on, since Video is off the tab bar. "
                    + "Your choice stays saved.");
        }
        HiddenTabs.Tab hidden = HiddenTabs.Tab.forStart(tab);
        if (hidden != null && hidden.setting().savedValue() && PatchFamily.HIDDEN_TABS.inBuild()) {
            return L10n.f("Facebook opens on Home while %1$s is on, since %2$s is off the tab bar. Your choice stays saved.",
                    SwitchLabels.title(hidden.setting()), tabLabel(tab));
        }
        return L10n.f("Facebook opens on %1$s. If your tab bar doesn't have it, Facebook opens on Home.",
                tabLabel(tab));
    }

    /**
     * The filter the Feeds tab opens on after a start the start tab sends there (#56). Like the
     * start tab row, its values are the setting's own names and its summary says what the choice
     * does.
     */
    static FeedsSubtabRow feedsSubtabRow(Context context) {
        FeedsSubtabRow row = new FeedsSubtabRow(context);
        row.setKey(Settings.FEEDS_SUBTAB.key);
        row.setTitle(L10n.t("Feeds opens on"));
        row.setDialogTitle(L10n.t("Feeds opens on"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        FeedsSubtab[] subtabs = FeedsSubtab.values();
        CharSequence[] entries = new CharSequence[subtabs.length];
        CharSequence[] values = new CharSequence[subtabs.length];
        for (int i = 0; i < subtabs.length; i++) {
            entries[i] = feedsSubtabLabel(subtabs[i]);
            values[i] = subtabs[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.FEEDS_SUBTAB.savedValue().name());
        return row;
    }

    /** What the list, its summary and an import's preview call [subtab]: the filter's name in Facebook. */
    static String feedsSubtabLabel(FeedsSubtab subtab) {
        switch (subtab) {
            case FAVORITES:
                return L10n.t("Favorites");
            case FRIENDS:
                return L10n.t("Friends");
            case GROUPS:
                return L10n.t("Groups");
            case PAGES:
                return L10n.t("Pages");
            default:
                return L10n.t("All");
        }
    }

    /**
     * What a start on the Feeds tab does with [subtab], for the row's summary. A filter the Feeds
     * tab hasn't got leaves it as it opened, so the summary says so rather than promise the filter.
     */
    static String feedsSubtabSummary(FeedsSubtab subtab) {
        if (subtab == FeedsSubtab.ALL) {
            return L10n.t("When Facebook opens on Feeds, the Feeds tab opens on the filter Facebook picks.");
        }
        return L10n.f("When Facebook opens on Feeds, the Feeds tab opens on %1$s. If your Feeds tab doesn't "
                + "have it, it opens as usual.", feedsSubtabLabel(subtab));
    }

    /**
     * The order comment sheets ask for. Like the start tab row, its values are the setting's own
     * names and its summary says what the choice does.
     */
    static CommentOrderRow commentOrderRow(Context context) {
        CommentOrderRow row = new CommentOrderRow(context);
        row.setKey(Settings.COMMENT_ORDER.key);
        row.setTitle(L10n.t("Comment order"));
        row.setDialogTitle(L10n.t("Comment order"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        CommentOrder[] orders = CommentOrder.values();
        CharSequence[] entries = new CharSequence[orders.length];
        CharSequence[] values = new CharSequence[orders.length];
        for (int i = 0; i < orders.length; i++) {
            entries[i] = commentOrderLabel(orders[i]);
            values[i] = orders[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.COMMENT_ORDER.savedValue().name());
        pickTurnsOn(row, Settings.COMMENT_ORDER, Settings.DEFAULT_COMMENT_ORDER);
        return row;
    }

    /**
     * A pick in [row] other than [list]'s default turns on [toggle], the switch the list works
     * under. The switch starts off, so a pick on its own used to change nothing until the switch
     * was found too. The switch's own row shows it on through the page's usual sync. Turning the
     * switch off afterwards keeps the pick, and a pick while it's on changes only the list.
     */
    static void pickTurnsOn(ListPreference row, EnumSetting<?> list, BooleanSetting toggle) {
        row.setOnPreferenceChangeListener((preference, value) -> {
            if (!toggle.savedValue() && !list.defaultValue.name().equals(String.valueOf(value))) toggle.save(true);
            return true;
        });
    }

    /** What the list and its summary call [order]: the name Facebook's own sort menu gives it. */
    static String commentOrderLabel(CommentOrder order) {
        switch (order) {
            case MOST_RELEVANT:
                return L10n.t("Most relevant");
            case NEWEST:
                return L10n.t("Newest");
            case ALL_COMMENTS:
                return L10n.t("All comments");
            default:
                return L10n.t("Facebook's choice");
        }
    }

    /**
     * What a comment sheet does with [order], for the row's summary. Facebook answers with the
     * order it used and its menu shows that one, so the summary names the menu, and says the post
     * has to offer the order rather than promise it.
     */
    static String commentOrderSummary(CommentOrder order) {
        if (order == CommentOrder.FACEBOOK) {
            return L10n.t("Comments open in the order Facebook picks, which is usually Most relevant.");
        }
        return L10n.f("A post's comments open with %1$s picked in their sort menu, where the post offers it.",
                commentOrderLabel(order));
    }

    /**
     * How long Facebook may be away before Lock Facebook asks again. Like the comment order row,
     * its values are the setting's own names and its summary says what the choice does.
     */
    static LockAfterRow lockAfterRow(Context context) {
        LockAfterRow row = new LockAfterRow(context);
        row.setKey(Settings.APP_LOCK_AFTER.key);
        row.setTitle(L10n.t("Lock after"));
        row.setDialogTitle(L10n.t("Lock after"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        AppLock.After[] choices = AppLock.After.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = lockAfterLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.APP_LOCK_AFTER.savedValue().name());
        return row;
    }

    /** What the list and the sentences about it call [after]. */
    static String lockAfterLabel(AppLock.After after) {
        switch (after) {
            case IMMEDIATELY:
                return L10n.t("As soon as you leave");
            case FIVE_MINUTES:
                return L10n.t("5 minutes");
            case FIFTEEN_MINUTES:
                return L10n.t("15 minutes");
            case ONE_HOUR:
                return L10n.t("1 hour");
            default:
                return L10n.t("1 minute");
        }
    }

    /** What a return does with [after], for the row's summary. */
    static String lockAfterSummary(AppLock.After after) {
        if (after == AppLock.After.IMMEDIATELY) return L10n.t("Facebook locks as soon as you leave it.");
        return L10n.f("Facebook locks once you've been away for %1$s.", lockAfterLabel(after));
    }

    /**
     * How long a seen post stays hidden. Like the lock row, its values are the setting's own names
     * and its summary says what the choice does.
     */
    static SeenKeepRow seenKeepRow(Context context) {
        SeenKeepRow row = new SeenKeepRow(context);
        row.setKey(Settings.SEEN_POSTS_KEEP.key);
        // Settings search shows the title on its own, so it names what it keeps hidden.
        row.setTitle(L10n.t("Keep seen posts hidden for"));
        row.setDialogTitle(L10n.t("Keep seen posts hidden for"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        SeenPosts.Keep[] choices = SeenPosts.Keep.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = seenKeepLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.SEEN_POSTS_KEEP.savedValue().name());
        return row;
    }

    /** What the list and the sentences about it call [keep]. */
    static String seenKeepLabel(SeenPosts.Keep keep) {
        switch (keep) {
            case ONE_DAY:
                return L10n.t("1 day");
            case THREE_DAYS:
                return L10n.t("3 days");
            case THIRTY_DAYS:
                return L10n.t("30 days");
            default:
                return L10n.t("7 days");
        }
    }

    /** What the choice does, for the row's summary. */
    static String seenKeepSummary(SeenPosts.Keep keep) {
        return L10n.f("A post you've scrolled past stays out of the feed for %1$s.", seenKeepLabel(keep));
    }

    /**
     * How large Facebook's text is. Like the comment order row, its values are the setting's own
     * names and its summary says what the choice does.
     */
    static TextSizeRow textSizeRow(Context context) {
        TextSizeRow row = new TextSizeRow(context);
        row.setKey(Settings.TEXT_SIZE.key);
        row.setTitle(L10n.t("Text size"));
        row.setDialogTitle(L10n.t("Text size"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        TextSize.Scale[] choices = TextSize.Scale.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = textSizeLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.TEXT_SIZE.savedValue().name());
        return row;
    }

    /** What the list and the sentences about it call [scale]. */
    static String textSizeLabel(TextSize.Scale scale) {
        return scale == TextSize.Scale.P100 ? L10n.t("As Facebook has it") : scale.label();
    }

    /** What a choice does, for the row's summary. */
    static String textSizeSummary(TextSize.Scale scale) {
        if (scale == TextSize.Scale.P100) {
            return L10n.t("Facebook's text is the size your phone's font size setting gives it.");
        }
        return L10n.f("Facebook's text is %1$s of the size your phone's font size setting gives it. Restart Facebook to "
                + "see the change.", scale.label());
    }

    /**
     * The reaction ceiling, with Hide posts by words in the build. Like the accent row, its values
     * are the setting's own names and its summary says what the choice does.
     */
    static ReactionCeilingRow reactionCeilingRow(Context context) {
        ReactionCeilingRow row = new ReactionCeilingRow(context);
        row.setKey(Settings.HIDE_POSTS_OVER_REACTIONS.key);
        row.setTitle(L10n.t("Hide posts with more reactions than"));
        row.setDialogTitle(L10n.t("Hide posts with more reactions than"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        ReactionCeiling[] choices = ReactionCeiling.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = ceilingLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.HIDE_POSTS_OVER_REACTIONS.savedValue().name());
        return row;
    }

    /** A ceiling as a number with its thousands separator, for the sentences about it. */
    static String ceilingNumber(ReactionCeiling ceiling) {
        return java.text.NumberFormat.getIntegerInstance().format(ceiling.limit);
    }

    /** What the list calls [ceiling]. */
    static String ceilingLabel(ReactionCeiling ceiling) {
        if (ceiling == ReactionCeiling.OFF) return L10n.t("Off");
        return L10n.f("%1$s reactions", ceilingNumber(ceiling));
    }

    /** What a choice does, for the row's summary. */
    static String ceilingSummary(ReactionCeiling ceiling) {
        if (ceiling == ReactionCeiling.OFF) return L10n.t("Posts stay however many reactions they have.");
        return L10n.f("Hides posts with more than %1$s reactions, read from the count Facebook shows under a post.",
                ceilingNumber(ceiling));
    }

    /**
     * The accent color, with the Accent color patch in the build. Like the text size row, its values
     * are the setting's own names and its summary says what the choice does.
     */
    static AccentRow accentRow(Context context, boolean materialYou) {
        AccentRow row = new AccentRow(context, materialYou);
        row.setKey(Settings.ACCENT_COLOR.key);
        row.setTitle(L10n.t("Accent color"));
        row.setDialogTitle(L10n.t("Accent color"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        AccentColor.Preset[] choices = AccentColor.Preset.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = accentLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.ACCENT_COLOR.savedValue().name());
        return row;
    }

    /** What the list and the sentences about it call [accent]. */
    static String accentLabel(AccentColor.Preset accent) {
        switch (accent) {
            case TEAL:
                return L10n.t("Teal");
            case GREEN:
                return L10n.t("Green");
            case PURPLE:
                return L10n.t("Purple");
            case PINK:
                return L10n.t("Pink");
            case ORANGE:
                return L10n.t("Orange");
            case RED:
                return L10n.t("Red");
            case INDIGO:
                return L10n.t("Indigo");
            case AMBER:
                return L10n.t("Amber");
            default:
                return L10n.t("Facebook blue");
        }
    }

    /** What a choice does, for the row's summary. */
    static String accentSummary(AccentColor.Preset accent) {
        if (accent == AccentColor.Preset.FACEBOOK) {
            return L10n.t("Links, buttons, switches and the selected tab keep Facebook's blue.");
        }
        return L10n.f("Links, buttons, switches and the selected tab use %1$s instead of Facebook's blue. Restart Facebook "
                + "to see the change. If the Material You theme is added, it takes over.",
                accentLabel(accent));
    }

    /**
     * The quality videos play at. Like the comment order row, its values are the setting's own
     * names and its summary says what the choice does.
     */
    static PlaybackQualityRow playbackQualityRow(Context context) {
        PlaybackQualityRow row = new PlaybackQualityRow(context);
        row.setKey(Settings.PLAYBACK_QUALITY.key);
        row.setTitle(L10n.t("Playback quality"));
        row.setDialogTitle(L10n.t("Playback quality"));
        // Android's own Cancel follows the activity's language, as the other lists' did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        PlaybackQuality[] qualities = PlaybackQuality.values();
        CharSequence[] entries = new CharSequence[qualities.length];
        CharSequence[] values = new CharSequence[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            entries[i] = playbackQualityLabel(qualities[i]);
            values[i] = qualities[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.PLAYBACK_QUALITY.savedValue().name());
        pickTurnsOn(row, Settings.PLAYBACK_QUALITY, Settings.DEFAULT_PLAYBACK_QUALITY);
        return row;
    }

    /**
     * The quality reels ([reels]) or video stories play at: the same as every other video, the
     * default, or one of the playback qualities, Auto included. Its values are the setting's own
     * names, as the playback quality's are.
     */
    static SurfaceQualityRow surfaceQualityRow(Context context, boolean reels) {
        EnumSetting<SurfaceQuality> setting = reels ? Settings.REELS_PLAYBACK_QUALITY : Settings.STORIES_PLAYBACK_QUALITY;
        SurfaceQualityRow row = new SurfaceQualityRow(context, reels);
        row.setKey(setting.key);
        String title = reels ? L10n.t("Reels quality") : L10n.t("Stories quality");
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setNegativeButtonText(L10n.t("Cancel"));
        SurfaceQuality[] choices = SurfaceQuality.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            PlaybackQuality quality = choices[i].quality;
            entries[i] = quality == null ? L10n.t("Same as videos") : playbackQualityLabel(quality);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(setting.savedValue().name());
        pickTurnsOn(row, setting, Settings.DEFAULT_PLAYBACK_QUALITY);
        return row;
    }

    /** What the Reels ([reels]) or Stories quality row says [choice] does. A quality of their own is named as the list names it. */
    static String surfaceQualitySummary(SurfaceQuality choice, boolean reels) {
        if (choice.quality != null) return playbackQualityLabel(choice.quality);
        return reels ? L10n.t("Reels play at the playback quality above.")
                : L10n.t("Video stories play at the playback quality above.");
    }

    /** What the list calls [quality]: Auto, as Facebook's own quality menu calls it, and a ceiling by its label. */
    static String playbackQualityLabel(PlaybackQuality quality) {
        switch (quality) {
            case DATA_SAVER:
                return L10n.t("Data saver");
            case P480:
            case P720:
                return L10n.f("Up to %1$s", L10n.isolate(quality.fileValue));
            case HIGHEST:
                return L10n.t("Highest");
            default:
                return L10n.t("Auto");
        }
    }

    /**
     * What a video does with [quality], for the row's summary. The rungs are the ones Facebook offers
     * for each video, so the summary says the video has to offer the quality rather than promise it.
     */
    static String playbackQualitySummary(PlaybackQuality quality) {
        switch (quality) {
            case DATA_SAVER:
                return L10n.t("Videos play at the lowest quality Facebook offers for each.");
            case P480:
            case P720:
                return L10n.f("Videos play at the best quality up to %1$s that Facebook offers for each, or the closest above.",
                        L10n.isolate(quality.fileValue));
            case HIGHEST:
                return L10n.t("Videos play at the highest quality Facebook offers for each.");
            default:
                return L10n.t("Facebook picks the quality as each video plays, from your connection.");
        }
    }

    /**
     * The hour quiet hours start ([from]) or end. Its values are the setting's own names, as the
     * comment order's are, and each hour reads the way the phone's language writes a time.
     */
    static QuietHourRow quietHourRow(Context context, boolean from) {
        EnumSetting<QuietHour> setting = from ? Settings.QUIET_HOURS_FROM : Settings.QUIET_HOURS_UNTIL;
        QuietHourRow row = new QuietHourRow(context);
        row.setKey(setting.key);
        String title = from ? L10n.t("Quiet hours start") : L10n.t("Quiet hours end");
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setNegativeButtonText(L10n.t("Cancel"));
        QuietHour[] hours = QuietHour.values();
        CharSequence[] entries = new CharSequence[hours.length];
        CharSequence[] values = new CharSequence[hours.length];
        for (int i = 0; i < hours.length; i++) {
            entries[i] = quietHourLabel(hours[i]);
            values[i] = hours[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(setting.savedValue().name());
        return row;
    }

    /**
     * What the list, its summary and an import call [hour]: the time as the phone's language writes
     * it. It comes from the platform, not the catalog, so it's isolated like a folder name.
     */
    static String quietHourLabel(QuietHour hour) {
        return L10n.isolate(hour.label(L10n.locale()));
    }

    /**
     * The quality, download action, start tab, Feeds filter, comment order and playback quality
     * rows' summaries are sentences of their own rather than the chosen entry.
     */
    @Override
    protected void updateListPreferenceSummary(ListPreference listPreference, Setting<?> setting) {
        if (listPreference instanceof QualityRow) {
            ((QualityRow) listPreference).showSummary();
        } else if (listPreference instanceof DownloadActionRow) {
            ((DownloadActionRow) listPreference).showSummary();
        } else if (listPreference instanceof SaveToRow) {
            ((SaveToRow) listPreference).showSummary();
        } else if (listPreference instanceof StartTabRow) {
            ((StartTabRow) listPreference).showSummary();
        } else if (listPreference instanceof FeedsSubtabRow) {
            ((FeedsSubtabRow) listPreference).showSummary();
        } else if (listPreference instanceof CommentOrderRow) {
            ((CommentOrderRow) listPreference).showSummary();
        } else if (listPreference instanceof LockAfterRow) {
            ((LockAfterRow) listPreference).showSummary();
        } else if (listPreference instanceof SeenKeepRow) {
            ((SeenKeepRow) listPreference).showSummary();
        } else if (listPreference instanceof TextSizeRow) {
            ((TextSizeRow) listPreference).showSummary();
        } else if (listPreference instanceof AccentRow) {
            ((AccentRow) listPreference).showSummary();
        } else if (listPreference instanceof ReactionCeilingRow) {
            ((ReactionCeilingRow) listPreference).showSummary();
        } else if (listPreference instanceof PlaybackQualityRow) {
            ((PlaybackQualityRow) listPreference).showSummary();
        } else if (listPreference instanceof SurfaceQualityRow) {
            ((SurfaceQualityRow) listPreference).showSummary();
        } else if (listPreference instanceof QuietHourRow) {
            ((QuietHourRow) listPreference).showSummary();
        } else {
            super.updateListPreferenceSummary(listPreference, setting);
        }
    }

    /**
     * The folder every save goes to. What's typed is cleaned before it's kept, so the row, the
     * setting and the next save all show the one folder name the save will use.
     */
    static FolderRow folderRow(Context context) {
        FolderRow row = new FolderRow(context);
        row.setKey(Settings.SAVE_FOLDER.key);
        row.setTitle(L10n.t("Save folder"));
        row.setDialogTitle(L10n.t("Save folder"));
        row.setDialogMessage(folderDialogMessage(Settings.SAVE_TO.savedValue()));
        row.setPositiveButtonText(L10n.t("Save"));
        // Unset, Android fills in its own Cancel in the activity's language, which can differ
        // from Facebook's, and the dialog read "Speichern" next to "Cancel".
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("Folder name"));
        row.setText(Settings.SAVE_FOLDER.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = SaveFolder.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean name in place of what was typed. The store changes, and the shared
            // page reads the setting from the row as it does for any change.
            ((FolderRow) preference).setText(clean);
            Utils.showToastShort(L10n.f("Folder set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /**
     * The subfolder of the save folder that videos ([video]) or photos go in. What's typed is
     * cleaned before it's kept, as the save folder is, and nothing left means no subfolder.
     */
    static SubfolderRow subfolderRow(Context context, boolean video) {
        StringSetting setting = video ? Settings.VIDEO_SUBFOLDER : Settings.PHOTO_SUBFOLDER;
        SubfolderRow row = new SubfolderRow(context, video);
        row.setKey(setting.key);
        String title = video ? L10n.t("Video subfolder") : L10n.t("Photo subfolder");
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setDialogMessage(video
                ? L10n.t("Choose a folder for videos inside the save folder. Invalid characters become underscores. "
                        + "Leave it blank to keep videos in the save folder itself.")
                : L10n.t("Choose a folder for photos inside the save folder. Invalid characters become underscores. "
                        + "Leave it blank to keep photos in the save folder itself."));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("Folder name"));
        row.setText(setting.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = SaveFolder.cleanSubfolder(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean name in place of what was typed, as the save folder's row does.
            ((SubfolderRow) preference).setText(clean);
            if (!clean.isEmpty()) Utils.showToastShort(L10n.f("Folder set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /** "Videos go to Movies/Facebook/Clips." for the [subfolder] videos ([video]) or photos go in, or none. */
    static String subfolderSummary(boolean video, String subfolder) {
        String path = Settings.SAVE_TO.savedValue().directory(video) + "/"
                + SaveFolder.within(SaveFolder.sanitize(Settings.SAVE_FOLDER.savedValue()), subfolder);
        return video ? L10n.f("Videos go to %1$s.", L10n.isolate(path)) : L10n.f("Photos go to %1$s.", L10n.isolate(path));
    }

    /** What the folder row's dialog says, naming the top folder [to] puts the folder under. */
    static String folderDialogMessage(SaveTo to) {
        if (to == SaveTo.MOVIES_AND_PICTURES) {
            return L10n.f("Choose a folder name under Movies and Pictures. Invalid characters become "
                    + "underscores. Leave it blank to use the default folder, %1$s.", L10n.isolate(SaveFolder.DEFAULT));
        }
        return L10n.f("Choose a folder name under %1$s. Invalid characters become underscores. Leave it blank to "
                + "use the default folder, %2$s.", L10n.isolate(to.directory(true)), L10n.isolate(SaveFolder.DEFAULT));
    }

    /**
     * The top folder saves go to (#42): Movies and Pictures, where Facebook's own saves go, or DCIM
     * or Download for both. The values are the setting's own names, as the quality's are. DCIM and
     * Download are the folders' own names on the phone, so they aren't translated.
     */
    static SaveToRow saveToRow(Context context) {
        SaveToRow row = new SaveToRow(context);
        row.setKey(Settings.SAVE_TO.key);
        row.setTitle(L10n.t("Save to"));
        row.setDialogTitle(L10n.t("Save to"));
        // Android's own Cancel follows the activity's language, as the quality row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        // DCIM and Download are folder names, spelled as the phone spells them in every language.
        row.setEntries(new CharSequence[]{L10n.t("Movies and Pictures"), L10n.isolate(SaveTo.DCIM.directory(true)),
                L10n.isolate(SaveTo.DOWNLOAD.directory(true))});
        row.setEntryValues(new CharSequence[]{SaveTo.MOVIES_AND_PICTURES.name(), SaveTo.DCIM.name(),
                SaveTo.DOWNLOAD.name()});
        row.setValue(Settings.SAVE_TO.savedValue().name());
        return row;
    }

    /** What the Save to row says for [to]. */
    static String saveToSummary(SaveTo to) {
        switch (to) {
            case DCIM:
                return L10n.f("Videos and photos go to %1$s, next to the camera's. Saves you already have stay "
                        + "where they are.", L10n.isolate(to.directory(true)));
            case DOWNLOAD:
                return L10n.f("Videos and photos go to %1$s, with the phone's other downloads. Saves you already "
                        + "have stay where they are.", L10n.isolate(to.directory(true)));
            default:
                return L10n.f("Videos go to %1$s and photos to %2$s, as Facebook's own saves do. Some galleries "
                        + "don't show %1$s.", L10n.isolate(to.directory(true)), L10n.isolate(to.directory(false)));
        }
    }

    /**
     * The name every saved video gets, next to the folder it goes to. What's typed is cleaned the
     * way the folder is, and held to the gallery's naming, so the row, the setting and the next
     * save all show the one template the save will use.
     */
    static FileNameRow fileNameRow(Context context) {
        FileNameRow row = nameRow(context, false, Settings.FILENAME_TEMPLATE, L10n.t("Video file name"));
        row.setDialogMessage(L10n.f("%1$s becomes the date and time of the save, %2$s the video's number on "
                        + "Facebook, %3$s who posted it, %4$s their profile's number on Facebook and %5$s the day it "
                        + "was posted. What a save doesn't know is left out, and a name with none of these gets the "
                        + "date added. When the name is already in the folder, the time of the save goes on the end. "
                        + "Invalid characters become underscores. Leave it blank to use the default, %6$s.",
                L10n.isolate(FileNameTemplate.DATE), L10n.isolate(FileNameTemplate.VIDEO_ID),
                L10n.isolate(FileNameTemplate.OWNER), L10n.isolate(FileNameTemplate.OWNER_ID),
                L10n.isolate(FileNameTemplate.POSTED), L10n.isolate(FileNameTemplate.DEFAULT)));
        return row;
    }

    /** The name every photo Save any photo keeps gets, the same way, under the video's. */
    static FileNameRow photoNameRow(Context context) {
        FileNameRow row = nameRow(context, true, Settings.PHOTO_FILENAME_TEMPLATE, L10n.t("Photo file name"));
        row.setDialogMessage(L10n.f("%1$s becomes the date and time of the save, %2$s the photo's number on "
                        + "Facebook, %3$s who posted it, %4$s their profile's number on Facebook and %5$s the day it "
                        + "was posted. What a save doesn't know is left out, and a name with none of these gets the "
                        + "date added. When the name is already in the folder, the time of the save goes on the end. "
                        + "Invalid characters become underscores. Leave it blank to use the default, %6$s.",
                L10n.isolate(FileNameTemplate.DATE), L10n.isolate(FileNameTemplate.PHOTO_ID),
                L10n.isolate(FileNameTemplate.OWNER), L10n.isolate(FileNameTemplate.OWNER_ID),
                L10n.isolate(FileNameTemplate.POSTED), L10n.isolate(FileNameTemplate.PHOTO_DEFAULT)));
        return row;
    }

    private static FileNameRow nameRow(Context context, boolean photo, StringSetting setting, String title) {
        FileNameRow row = new FileNameRow(context, photo);
        row.setKey(setting.key);
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setPositiveButtonText(L10n.t("Save"));
        // Android's own Cancel follows the activity's language, as the folder row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("File name"));
        row.setText(setting.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = photo ? FileNameTemplate.sanitizePhoto(raw) : FileNameTemplate.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean template in place of what was typed, as the folder row does.
            ((FileNameRow) preference).setText(clean);
            Utils.showToastShort(L10n.f("File name set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /** "Videos are named FB_VID_{date}." for [template]. */
    static String fileNameSummary(String template) {
        return L10n.f("Videos are named %1$s.", L10n.isolate(template));
    }

    /** "Photos are named FB_IMG_{date}." for [template]. */
    static String photoNameSummary(String template) {
        return L10n.f("Photos are named %1$s.", L10n.isolate(template));
    }

    /**
     * What a tap on Download does for a reel or a video: save it to the phone, the default, or send
     * its link to another app (#41). The values are the setting's own names, as the quality's are.
     */
    static DownloadActionRow downloadActionRow(Context context) {
        DownloadActionRow row = new DownloadActionRow(context);
        row.setKey(Settings.DOWNLOAD_ACTION.key);
        row.setTitle(L10n.t("When you tap Download"));
        row.setDialogTitle(L10n.t("When you tap Download"));
        // Android's own Cancel follows the activity's language, as the quality row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        row.setEntries(new CharSequence[]{L10n.t("Save to phone"), L10n.t("Send the link to an app")});
        row.setEntryValues(new CharSequence[]{SendLink.Action.SAVE.name(), SendLink.Action.SEND.name()});
        row.setValue(Settings.DOWNLOAD_ACTION.savedValue().name());
        return row;
    }

    /** What the download action's row says for [action]. */
    static String downloadActionSummary(SendLink.Action action) {
        return action == SendLink.Action.SEND
                ? L10n.t("Reels and videos go to the app below as a facebook.com link. Hold the reel Download "
                        + "button to copy the link instead. Stories still save to this phone.")
                : L10n.t("Reels, videos and stories save to this phone.");
    }

    /**
     * The app links go to while they're sent, by package name. What's typed is trimmed, anything
     * that isn't a package name is turned down with a message, and blank leaves the pick to
     * Android each time.
     */
    static SendAppRow sendAppRow(Context context) {
        SendAppRow row = new SendAppRow(context);
        row.setKey(Settings.SEND_TO_APP.key);
        row.setTitle(L10n.t("App to send to"));
        row.setDialogTitle(L10n.t("App to send to"));
        row.setDialogMessage(L10n.f("The ID of the app that gets the links, such as %1$s for YTDLnis or %2$s for Seal. Leave it blank to "
                + "pick an app each time.",
                L10n.isolate(SendLink.YTDLNIS), L10n.isolate(SendLink.SEAL)));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("App ID"));
        row.setText(Settings.SEND_TO_APP.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = raw.trim();
            if (!clean.isEmpty() && SendLink.targetPackage(clean) == null) {
                Utils.showToastShort(L10n.f("%1$s isn't a valid app ID, so the app stays as it was.",
                        L10n.isolate(clean)));
                return false;
            }
            if (clean.equals(raw)) return true;
            // Keeps the trimmed name in place of what was typed, as the folder row does.
            ((SendAppRow) preference).setText(clean);
            return false;
        });
        return row;
    }

    /** What the send-to row says for [app], the package typed there. */
    static String sendAppSummary(String app) {
        String target = SendLink.targetPackage(app);
        return target == null
                ? L10n.t("Android asks which app each time.")
                : L10n.f("Links go to %1$s. When it isn't installed, Android asks which app.", L10n.isolate(target));
    }

    /** {@link #folderSummary(String, SaveTo)} under the top folder Save to picks now. */
    static String folderSummary(String leaf) {
        return folderSummary(leaf, Settings.SAVE_TO.savedValue());
    }

    /**
     * "Videos go to Movies/Clips and photos to Pictures/Clips." for the folder [leaf] under
     * [to], or "Videos and photos go to Download/Clips." when both go to one folder. A kind with a
     * subfolder of its own has it on the end.
     */
    static String folderSummary(String leaf, SaveTo to) {
        String videos = to.directory(true) + "/" + SaveFolder.within(leaf, SaveFolder.cleanSubfolder(Settings.VIDEO_SUBFOLDER.savedValue()));
        String photos = to.directory(false) + "/" + SaveFolder.within(leaf, SaveFolder.cleanSubfolder(Settings.PHOTO_SUBFOLDER.savedValue()));
        if (videos.equals(photos)) return L10n.f("Videos and photos go to %1$s.", L10n.isolate(videos));
        return L10n.f("Videos go to %1$s and photos to %2$s.", L10n.isolate(videos), L10n.isolate(photos));
    }

    /**
     * One of the word filter's two lists: the words that hide a post ([hides]), or the ones that keep
     * it. What's typed is cleaned before it's kept, one phrase per line within the bounds
     * {@link PostWords} holds every list to, so the row, the setting and the filter all read the
     * same phrases. A dialog says how many lines were left out, never which.
     */
    WordsRow wordsRow(Context context, StringSetting setting, boolean hides) {
        WordsRow row = new WordsRow(context, hides);
        row.setKey(setting.key);
        String title = hides ? L10n.t("Words to hide") : L10n.t("Words that keep a post");
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setDialogMessage((hides
                ? L10n.f("One word or phrase per line, up to %1$d lines, each %2$d to %3$d characters long. A single emoji or "
                        + "Chinese, Japanese or Korean character is enough on its own. Capital letters don't matter, and a "
                        + "phrase matches anywhere in a post's text, even inside longer words.",
                        PostWords.MAX_PHRASES, PostWords.MIN_LENGTH, PostWords.MAX_LENGTH)
                : L10n.f("A post with any of these stays, even when it also has a word to hide. One per line, up to %1$d "
                        + "lines, each %2$d to %3$d characters long. A single emoji or Chinese, Japanese or Korean character "
                        + "is enough on its own.", PostWords.MAX_PHRASES, PostWords.MIN_LENGTH,
                        PostWords.MAX_LENGTH)) + " " + patternHelp());
        row.setPositiveButtonText(L10n.t("Save"));
        // Android's own Cancel follows the activity's language, as the folder row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // A landscape IME's extracted editor replaces the dialog and hides Save and Cancel.
        field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // No line cap: the list grows inside its dialog's scroll, which a field scrolling itself
        // inside it would fight (#58).
        field.setMinLines(3);
        field.setHint(L10n.t("One word or phrase per line"));
        row.setText(setting.savedValue());
        if (hides) {
            row.choosePack = () -> {
                TopicPacks.Pack[] packs = TopicPacks.Pack.values();
                CharSequence[] names = new CharSequence[packs.length];
                for (int i = 0; i < packs.length; i++) names[i] = packLabel(packs[i]);
                show(new AlertDialog.Builder(row.getContext())
                        .setTitle(L10n.t("Add a topic pack"))
                        .setItems(names, (dialog, which) -> row.addPack(packs[which]))
                        .setNegativeButton(L10n.t("Cancel"), null));
            };
        }
        row.refused = why -> show(new AlertDialog.Builder(row.getContext())
                .setTitle(title)
                .setMessage(why)
                .setPositiveButton(L10n.t("OK"), null));
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = PostWords.clean(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean list in place of what was typed, as the folder row does.
            int leftOut = PostWords.leftOut(raw);
            ((WordsRow) preference).setText(clean);
            if (leftOut > 0) {
                // A dialog, not a toast: Android 12 and later cut a toast to two lines, and the
                // reasons run past that, most of all at a large text size.
                // Too many phrases never get here: Save refuses those with the dialog still open.
                String why = L10n.quantity(leftOut,
                        "%1$d line was left out. A phrase needs %2$d to %3$d characters, but a single emoji or Chinese, "
                                + "Japanese or Korean character is enough. One given twice counts once.",
                        "%1$d lines were left out. A phrase needs %2$d to %3$d characters, but a single emoji or Chinese, "
                                + "Japanese or Korean character is enough. One given twice counts once.",
                        leftOut, PostWords.MIN_LENGTH, PostWords.MAX_LENGTH);
                show(new AlertDialog.Builder(preference.getContext())
                        .setTitle(title)
                        .setMessage(why)
                        .setPositiveButton(L10n.t("OK"), null));
            }
            return false;
        });
        return row;
    }

    /**
     * The list Hide posts from people, Pages and sites reads. What's typed is cleaned before it's
     * kept, one rule per line within the bounds {@link PostSources} holds the list to, so the row,
     * the setting and the filter all read the same rules. A dialog says how many lines were left
     * out, never which.
     */
    SourcesRow sourcesRow(Context context) {
        SourcesRow row = new SourcesRow(context);
        row.setKey(Settings.HIDDEN_SOURCES.key);
        String title = L10n.t("People, Pages and sites to hide");
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setDialogMessage(L10n.f("One per line, up to %1$d: a name as Facebook shows it, a profile or Page ID, or a site like "
                + "example.com, which also covers addresses like m.example.com. Capital letters don't matter. A "
                + "Facebook link works when it has the ID in it.",
                PostSources.MAX_RULES));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // A landscape IME's extracted editor replaces the dialog and hides Save and Cancel.
        field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setMinLines(3);
        field.setHint(L10n.t("One name, id or site per line"));
        row.setText(Settings.HIDDEN_SOURCES.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = PostSources.clean(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean list in place of what was typed, as the word lists do.
            int leftOut = PostSources.leftOut(raw);
            ((SourcesRow) preference).setText(clean);
            if (leftOut > 0) {
                String why = L10n.quantity(leftOut,
                        "%1$d line was left out. A line holds up to %2$d characters, the list up to %3$d lines, "
                                + "and one given twice counts once.",
                        "%1$d lines were left out. A line holds up to %2$d characters, the list up to %3$d lines, "
                                + "and one given twice counts once.",
                        leftOut, PostSources.MAX_LENGTH, PostSources.MAX_RULES);
                show(new AlertDialog.Builder(preference.getContext())
                        .setTitle(title)
                        .setMessage(why)
                        .setPositiveButton(L10n.t("OK"), null));
            }
            return false;
        });
        return row;
    }

    /** What the people, Pages and sites row says: how many it holds. Counts only, never a name. */
    static String sourcesSummary(String stored) {
        int rules = PostSources.count(stored);
        if (rules == 0) return L10n.t("Nobody listed yet, so no post is hidden.");
        return L10n.quantity(rules, "%1$d person, Page or site.", "%1$d people, Pages or sites.", rules);
    }

    /**
     * What a word list's row says: how many phrases it holds, and for the hide list how many posts
     * it has hidden since Facebook started. Counts only, never a phrase.
     */
    static String wordsSummary(String stored, boolean hides) {
        int phrases = PostWords.count(stored);
        if (phrases == 0) {
            return hides ? L10n.t("No words yet, so no post is hidden.") : L10n.t("No words yet.");
        }
        String summary = L10n.quantity(phrases, "%1$d word or phrase.", "%1$d words or phrases.", phrases);
        int hidden = hides ? PostWords.hiddenSinceStart() : 0;
        if (hidden > 0) {
            summary += " " + L10n.quantity(hidden, "Hid %1$d post since Facebook started.",
                    "Hid %1$d posts since Facebook started.", hidden);
        }
        return summary;
    }

    /**
     * What a word list's dialog says under its explanation as the list is typed: how many phrases
     * it holds and how full the room the two lists share would be, or why it can't be saved.
     */
    static String wordsEditorLine(PostWords.Size size) {
        if (size.tooMany()) return wordsRefusal(size);
        String count = size.phrases == 0 ? L10n.t("No words yet.")
                : L10n.quantity(size.phrases, "%1$d word or phrase.", "%1$d words or phrases.", size.phrases);
        String why = wordsRefusal(size);
        return count + " " + (why != null ? why
                : L10n.f("Both lists together fill %1$d%% of the room they share.", size.percent()));
    }

    /** What the topic pack list calls [pack]. */
    static String packLabel(TopicPacks.Pack pack) {
        switch (pack) {
            case POLITICS:
                return L10n.t("Politics");
            case ELECTIONS:
                return L10n.t("Elections");
            case CRYPTO:
                return L10n.t("Crypto");
            case SPORTS:
                return L10n.t("Sports");
            case CELEBRITY_GOSSIP:
                return L10n.t("Celebrity gossip");
            case WEIGHT_LOSS_ADS:
                return L10n.t("Weight loss ads");
            default:
                return L10n.t("Giveaways and engagement bait");
        }
    }

    /** What the toast says after a pack was added: how many words went in, and why any didn't. */
    static String packResult(TopicPacks.Pack pack, TopicPacks.Result result) {
        String label = packLabel(pack);
        String line;
        if (result.added > 0) {
            line = L10n.quantity(result.added, "Added %1$d word from %2$s.", "Added %1$d words from %2$s.",
                    result.added, label);
            if (result.duplicates > 0) {
                line += " " + L10n.quantity(result.duplicates, "%1$d was already in the list.",
                        "%1$d were already in the list.", result.duplicates);
            }
            if (result.full) line += " " + L10n.t("The list is full, so the rest weren't added.");
        } else if (result.full) {
            line = L10n.t("The list is full, so nothing was added.");
        } else {
            line = L10n.f("Every word from %1$s is already in the list.", label);
        }
        return line + " " + L10n.t("Tap Save to keep the list.");
    }

    /**
     * The word lists' second paragraph: a line between slashes is a pattern, and how many a list
     * holds. Both dialogs end with it.
     */
    static String patternHelp() {
        return L10n.f("A line between slashes, like /gr[ae]y/, is a pattern that can match several spellings (a regular "
                + "expression). A list holds up to %1$d, each up to %2$d characters.", PostWords.MAX_PATTERNS, PostWords.MAX_PATTERN_LENGTH);
    }

    /** Why a typed list can't be saved, the same in its dialog and when Save is tapped, or null when it can be. */
    @Nullable
    static String wordsRefusal(PostWords.Size size) {
        if (size.tooMany()) {
            return L10n.f("A list holds up to %1$d phrases, and this one has more. Remove some, then save again.",
                    PostWords.MAX_PHRASES);
        }
        if (size.badLine > 0) {
            return L10n.f("Line %1$d isn't a pattern Hushfacebook can read. Fix it or remove it, then save again.",
                    size.badLine);
        }
        if (size.tooManyPatterns()) {
            return L10n.f("A list holds up to %1$d patterns, and this one has more. Remove some, then save again.",
                    PostWords.MAX_PATTERNS);
        }
        if (size.fits()) return null;
        return L10n.f("Both lists together would fill %1$d%% of the room they share. Remove or shorten some "
                + "phrases, then save again.", size.percent());
    }

    static Preference mark(Preference row, String icon) {
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        row.setIcon(SettingsIcons.icon(row.getContext(), icon, colors.heading));
        return row;
    }

    static Preference info(Context context, String title, String summary) {
        Preference preference = new Row(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        preference.setPersistent(false);
        return preference;
    }

    /**
     * Lets a row's title and summary wrap to as many lines as they need. Android draws a row's
     * title on one line and cuts its summary at ten, and at 200% text size the paused card, the
     * Pause row and the list of what Pause can't reach ran past ten lines and lost their ends.
     */
    static void showAllText(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) summary.setMaxLines(Integer.MAX_VALUE);
    }

    /**
     * An edit dialog shrinks to fit above the keyboard instead of going under it. Android's own
     * EditTextPreference only asks for the keyboard, so at a large font size a long message pushed
     * the dialog's Save and Cancel behind the keyboard, where nobody could reach them. The dialog's
     * keyboard state is kept; only how it adjusts changes.
     */
    static void fitAboveKeyboard(Dialog dialog) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        int mode = window.getAttributes().softInputMode;
        window.setSoftInputMode((mode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override
    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return L10n.t(context, "Hushfacebook settings couldn't open");
    }
}
