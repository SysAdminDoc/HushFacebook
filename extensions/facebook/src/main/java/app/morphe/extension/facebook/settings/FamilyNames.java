/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

/**
 * The name each patch goes by in Morphe Manager, which is also the family its hooks report
 * under in Hook status.
 *
 * <p>Compile-time constants, so a hook that names its family inlines the text and loads no
 * class. That matters for the hooks that can run before Hushfacebook has a context, such as the
 * signature check Facebook makes while its content providers start: touching {@link PatchFamily}
 * there would load the settings with no context to read them from.
 */
public final class FamilyNames {
    public static final String SPONSORED_POSTS = "Hide sponsored posts";
    public static final String SUGGESTED_POSTS = "Hide suggested and promoted posts";
    /**
     * The Hook status row of the same patch's hook on your own profile, so the report tells the feed
     * guard's reads from the profile section's. Not a patch name: PatchFamily lists the patch once.
     */
    public static final String SUGGESTED_POSTS_PROFILE = "Hide suggested and promoted posts (your profile)";
    public static final String AI_DETECTED_POSTS = "Hide AI-detected posts";
    /**
     * The Hook status row of the same patch's Reels and Watch hooks, so the report tells the feed
     * guard's reads from the reel page filters'. Not a patch name: PatchFamily lists the patch once.
     */
    public static final String AI_DETECTED_REELS = "Hide AI-detected posts (Reels and Watch)";
    public static final String POST_WORDS = "Hide posts by words";
    public static final String POST_PROMPTS = "Hide post prompts";
    public static final String SEEN_POSTS = "Hide seen posts";
    public static final String META_AI_QUESTIONS = "Hide Meta AI questions under posts";
    public static final String POST_DATES = "Keep post dates";
    public static final String AUTO_TRANSLATION = "Turn off auto-translation";
    public static final String FEEDS_HEADER = "Hide the Feeds header";
    public static final String STORIES_TRAY = "Hide Stories tray";
    public static final String FEED_REELS = "Hide Reels in the feed";
    public static final String RETURN_REFRESH = "Block background-return feed refresh";
    public static final String SPONSORED_STORIES = "Hide sponsored stories";
    public static final String SUGGESTED_STORIES = "Hide suggested stories";
    public static final String STORY_AUTO_ADVANCE = "Stop Story auto-advance";
    public static final String STORY_SEEN = "View stories anonymously";
    public static final String SPONSORED_REELS = "Hide sponsored reels";
    public static final String SPONSORED_SEARCH = "Hide sponsored search results";
    public static final String SPONSORED_PROFILE_POSTS = "Hide sponsored profile posts";
    public static final String SPONSORED_MARKETPLACE = "Hide sponsored Marketplace listings";
    public static final String AFFILIATE_LINKS = "Hide affiliate product links";
    public static final String REEL_DECLUTTER = "Clean up Reels";
    public static final String REEL_PROMPTS = "Hide reel interest prompts";
    public static final String REEL_WATCH_HISTORY = "Don't send reel watch history";
    public static final String DOUBLE_TAP_LIKE = "Turn off double tap to like";
    public static final String KEEP_REEL_SPEED = "Keep the reel speed";
    public static final String HOLD_REEL_FOR_2X = "Hold a reel for 2x";
    public static final String DEFAULT_COMMENT_ORDER = "Default comment order";
    public static final String META_AI_SUMMARIES = "Hide Meta AI comment summaries";
    public static final String COMMENT_SHEET_OPTIONS = "Comment sheet options";
    public static final String TAG_SUGGESTIONS = "Tag suggestions only after @";
    public static final String TAP_TO_PLAY = "Tap to play";
    public static final String RESUME_LONG_VIDEOS = "Resume long videos";
    public static final String PLAYBACK_QUALITY = "Default playback quality";
    public static final String PICTURE_IN_PICTURE = "Picture-in-picture";
    public static final String HDR_BRIGHTNESS = "Turn off HDR brightness";
    public static final String PROGRESS_BAR = "Keep the progress bar";
    public static final String SYSTEM_FONT = "Use the system font";
    public static final String SYSTEM_EMOJI = "Use the phone's emoji";
    public static final String HAPTICS = "Turn off haptics";
    public static final String SCREEN_TRANSITIONS = "Turn off screen transitions";
    public static final String APP_ICONS = "Unlock app icons";
    public static final String EXTERNAL_BROWSER = "Open links in external browser";
    public static final String SANITIZE_SHARING_LINKS = "Sanitize sharing links";
    public static final String UPDATE_PROMPTS = "Stop update prompts";
    public static final String STORY_DOWNLOAD = "Download any story";
    public static final String REEL_DOWNLOAD = "Download any reel";
    public static final String VIDEO_DOWNLOAD = "Download any video";
    public static final String PHOTO_DOWNLOAD = "Download any photo";
    public static final String START_TAB = "Open on a chosen tab";
    public static final String FOLLOWING_HOME = "Following feed on Home";
    public static final String MARKETPLACE_ONLY = "Marketplace only";
    public static final String SELLER_VIEW_PROFILE = "Show View profile on Marketplace sellers";
    public static final String REELS_TAB = "Hide the Reels tab";
    public static final String REELS_TAB_DOT = "Hide the Reels tab dot";
    public static final String TAB_BADGES = "Hide tab badges";
    public static final String HIDDEN_TABS = "Hide tabs";
    public static final String BOTTOM_TAB_BAR = "Tab bar at the bottom";
    public static final String FORCE_DARK_MODE = "Force dark mode";
    public static final String MESSENGER_CARD = "Hide the Get Messenger card";
    public static final String CHAT_LIST = "Clean up Facebook's chat list";
    public static final String MESSENGER_ICON = "Open Messenger from the top bar";
    public static final String MENU_PROMOTIONS = "Hide Menu promotions";
    public static final String META_UPSELLS = "Hide Meta upsells";
    public static final String SHARE_SHEET_ITEMS = "Share sheet items";
    public static final String META_AI_SEARCH = "Hide Meta AI in search";
    public static final String PROMO_NOTIFICATIONS = "Block promotional notifications";
    public static final String AD_PREFETCH = "Block background ad prefetch";
    public static final String AD_TELEMETRY = "Block ad telemetry";
    public static final String ANALYTICS_UPLOADS = "Hold back analytics uploads";
    public static final String SCREENSHOTS = "Allow screenshots";
    public static final String SCREENSHOT_DETECTION = "Block screenshot detection";
    public static final String TYPING_INDICATOR = "Hide typing indicator";
    public static final String READ_RECEIPTS = "Hide read receipts";
    public static final String ORIGINAL_CHAT_MEDIA = "Send chat photos and videos at original quality";
    public static final String AUDIENCE_NETWORK = "Disable Audience Network";
    public static final String GAME_ADS = "Block Instant Games ads";
    public static final String AMOLED_THEME = "AMOLED black theme";
    public static final String MATERIAL_YOU_THEME = "Material You theme";
    public static final String ACCENT_COLOR = "Accent color";
    public static final String RESTORE_TRUST = "Restore screens on re-signed builds";
    public static final String TRANSLATED_START = "Start on x86 devices";
    public static final String INSTALL_BESIDE_META_APPS = "Install beside Meta's apps";
    public static final String PLAY_STORE_UPDATES = "Disable Play Store updates";
    public static final String MENU_SETTINGS_ROW = "Hushfacebook in the Menu";

    private FamilyNames() {
    }
}
