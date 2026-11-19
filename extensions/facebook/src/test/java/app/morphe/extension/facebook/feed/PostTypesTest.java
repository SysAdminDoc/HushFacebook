/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Hide posts by words' switches for kinds of post: an attachment's first style sorts it, a share
 * is judged by the post it wraps, a text format with a colour or font is a colored background, and
 * nothing is read with every switch off or Hushfacebook paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostTypesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC }

    /** Stands in for Facebook's attachment style enum, which Redex renames but whose constants keep their names. */
    enum Style {
        PHOTO, ALBUM, VIDEO_INLINE, SHARE, SHARE_LARGE_IMAGE, FALLBACK, NATIVE_TEMPLATES, MESSENGER_GENERIC_TEMPLATE,
        FB_SHORTS, EVENT
    }

    /** Stands in for the three stubs the patch fills, and counts what was asked. */
    private static final class Stand {
        final Map<Object, Object> styles = new IdentityHashMap<>();
        final Map<Object, Object> formats = new IdentityHashMap<>();
        int calls;

        GraphQLStoryAttachment attachment(Style... drawn) {
            GraphQLStoryAttachment attachment = new GraphQLStoryAttachment(new GraphQLMedia("Photo"));
            styles.put(attachment, Arrays.asList(drawn));
            return attachment;
        }

        Stand format(Object story, Object model) {
            formats.put(story, model);
            return this;
        }

        PostTypes.Readers readers() {
            return new PostTypes.Readers(story -> {
                calls++;
                return ((GraphQLStory) story).A0n();
            }, attachment -> {
                calls++;
                Object list = styles.get(attachment);
                if (list instanceof RuntimeException) throw (RuntimeException) list;
                return list;
            }, story -> {
                calls++;
                return formats.get(story);
            });
        }
    }

    private static final StoryFlag.Accessor SHARED = story -> story instanceof GraphQLStory ? ((GraphQLStory) story).A04() : null;

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_PHOTO_POSTS.resetToDefault();
        Settings.HIDE_VIDEO_POSTS.resetToDefault();
        Settings.HIDE_LINK_POSTS.resetToDefault();
        Settings.LINK_POSTS_ON_FEED.resetToDefault();
        Settings.HIDE_BACKGROUND_POSTS.resetToDefault();
        PostText.cachedMembers = null;
        FeedFilterCounters.clear();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static boolean guard(Object unit, PostTypes.Readers readers) {
        return FeedFilter.hideEdge(Category.ORGANIC, unit, true, true, story -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, true, story -> null, SHARED, story -> null, null, null, readers);
    }

    /** The line a route wrote, or null when nothing counted on it. */
    private static String line(String route) {
        for (String reported : FeedFilterCounters.report()) {
            if (reported.startsWith(route + ": ")) return reported;
        }
        return null;
    }

    private static BaseModelWithTree background() {
        return new BaseModelWithTree(PostTypes.TEXT_FORMAT_TYPE_TAG).with("background_color", "FF1877F2");
    }

    @Test
    public void aStyleSortsAPostByItsName() {
        assertEquals("PHOTO", PostTypes.firstStyle(Arrays.asList(Style.PHOTO, Style.FALLBACK)));
        assertEquals("FALLBACK", PostTypes.firstStyle(Arrays.asList(Style.FALLBACK, Style.PHOTO)));
        assertNull(PostTypes.firstStyle(Collections.emptyList()));
        assertNull("a style is an enum constant", PostTypes.firstStyle(Collections.singletonList("PHOTO")));
        assertNull(PostTypes.firstStyle(null));
        assertEquals(PostTypes.PHOTO, PostTypes.kind("ALBUM"));
        assertEquals(PostTypes.VIDEO, PostTypes.kind("VIDEO_AUTOPLAY"));
        assertEquals("a Page's video with a button under it", PostTypes.VIDEO,
                PostTypes.kind("VIDEO_DIRECT_RESPONSE_AUTOPLAY"));
        assertEquals(PostTypes.VIDEO, PostTypes.kind("VIDEO_DIRECT_RESPONSE"));
        assertEquals(PostTypes.LINK, PostTypes.kind("SHARE"));
        assertEquals("FALLBACK", PostTypes.kind("FALLBACK"));
    }

    @Test
    public void aPhotoPostGoesWithItsSwitchOn() {
        Stand stand = new Stand();
        GraphQLStory story = new GraphQLStory(null, stand.attachment(Style.ALBUM, Style.FALLBACK));
        assertFalse("switch off", guard(story, stand.readers()));
        assertEquals("nothing was read with every switch off", 0, stand.calls);
        assertNull(line(PostTypes.ROUTE));

        Settings.HIDE_PHOTO_POSTS.save(true);
        assertTrue(guard(story, stand.readers()));
        assertEquals(PostTypes.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: photo post. "
                + "Removed: photo post 1. Kinds: photo post 1", line(PostTypes.ROUTE));
        assertEquals(FeedFilter.FEED_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: photo post. "
                + "Removed: photo post 1. Kinds: ORGANIC 2", line(FeedFilter.FEED_ROUTE));
    }

    @Test
    public void anotherKindStaysAndTheReportNamesItsStyle() {
        Settings.HIDE_PHOTO_POSTS.save(true);
        Settings.HIDE_LINK_POSTS.save(true);
        Stand stand = new Stand();
        assertFalse(guard(new GraphQLStory(null, stand.attachment(Style.VIDEO_INLINE, Style.PHOTO)), stand.readers()));
        assertFalse(guard(new GraphQLStory(null, stand.attachment(Style.FALLBACK)), stand.readers()));
        assertFalse(guard(new GraphQLStory(), stand.readers()));
        assertEquals(PostTypes.ROUTE + ": 3 lists, 3 items, 0 removed. Kinds: no attachments 1, "
                + "style FALLBACK 1, style VIDEO_INLINE 1", line(PostTypes.ROUTE));

        Settings.HIDE_VIDEO_POSTS.save(true);
        assertTrue("a later attachment's kind counts too",
                guard(new GraphQLStory(null, stand.attachment(Style.FALLBACK), stand.attachment(Style.VIDEO_INLINE)),
                        stand.readers()));
    }

    /**
     * The News feed has its own switch under Hide link posts (#458). It starts on, and off, Home's
     * link posts stay and nothing is read for them, while the other kinds' switches still work.
     */
    @Test
    public void theNewsFeedSwitchKeepsHomesLinkPosts() {
        assertTrue(Settings.LINK_POSTS_ON_FEED.defaultValue);
        Settings.HIDE_LINK_POSTS.save(true);
        Settings.LINK_POSTS_ON_FEED.save(false);
        Stand stand = new Stand();
        GraphQLStory link = new GraphQLStory(new GraphQLStory(null, stand.attachment(Style.SHARE_LARGE_IMAGE)));

        assertFalse(guard(link, stand.readers()));
        assertEquals("nothing was read with the News feed switch off", 0, stand.calls);
        assertNull(line(PostTypes.ROUTE));

        Settings.HIDE_PHOTO_POSTS.save(true);
        assertTrue("the photo switch still hides", guard(new GraphQLStory(null, stand.attachment(Style.PHOTO)),
                stand.readers()));
        assertFalse("and the link post still stays", guard(link, stand.readers()));

        Settings.LINK_POSTS_ON_FEED.save(true);
        assertTrue(guard(link, stand.readers()));
    }

    @Test
    public void aShareIsJudgedByThePostItWraps() {
        Settings.HIDE_LINK_POSTS.save(true);
        Stand stand = new Stand();
        GraphQLStory shared = new GraphQLStory(null, stand.attachment(Style.SHARE_LARGE_IMAGE));
        assertTrue(guard(new GraphQLStory(shared), stand.readers()));
        assertEquals(PostTypes.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: link post. "
                + "Removed: link post 1. Kinds: link post 1", line(PostTypes.ROUTE));
    }

    /** The style lists as 582's Home logged them on the emulator: a website link, a shared reel, an event. */
    @Test
    public void aLinkLedByAnotherStyleGoesWhenItLeadsOffFacebook() {
        Settings.HIDE_LINK_POSTS.save(true);
        Stand stand = new Stand();
        GraphQLStoryAttachment site = stand.attachment(Style.NATIVE_TEMPLATES, Style.SHARE, Style.FALLBACK);
        site.with("url", "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dx&h=AT0");
        GraphQLStoryAttachment event = stand.attachment(Style.EVENT, Style.SHARE);
        event.with("url", "https://www.facebook.com/events/123/");
        GraphQLStoryAttachment noUrl = stand.attachment(Style.NATIVE_TEMPLATES, Style.SHARE);
        GraphQLStoryAttachment reel = stand.attachment(Style.MESSENGER_GENERIC_TEMPLATE, Style.FB_SHORTS, Style.FALLBACK);
        reel.with("url", "https://example.com/");
        assertTrue(guard(new GraphQLStory(null, site), stand.readers()));
        assertFalse("a shared event links to Facebook", guard(new GraphQLStory(null, event), stand.readers()));
        assertFalse("no url to go on", guard(new GraphQLStory(null, noUrl), stand.readers()));
        assertFalse("a shared reel has no SHARE style", guard(new GraphQLStory(null, reel), stand.readers()));
        assertEquals(PostTypes.ROUTE + ": 4 lists, 4 items, 1 removed. Last reason: link post. Removed: link post 1. "
                + "Kinds: link post 1, style EVENT 1, style MESSENGER_GENERIC_TEMPLATE 1, style NATIVE_TEMPLATES 1",
                line(PostTypes.ROUTE));

        Settings.HIDE_LINK_POSTS.save(false);
        Settings.HIDE_PHOTO_POSTS.save(true);
        assertTrue("the photo switch hides", guard(new GraphQLStory(null, stand.attachment(Style.PHOTO)), stand.readers()));
        assertFalse("only the link switch asks", guard(new GraphQLStory(null, site), stand.readers()));
    }

    @Test
    public void aColoredBackgroundIsATextFormatWithAColourOrFont() {
        Settings.HIDE_BACKGROUND_POSTS.save(true);
        Stand stand = new Stand();
        GraphQLStory colored = new GraphQLStory();
        GraphQLStory plain = new GraphQLStory();
        GraphQLStory otherType = new GraphQLStory();
        stand.format(colored, background())
                .format(plain, new BaseModelWithTree(PostTypes.TEXT_FORMAT_TYPE_TAG).with("preset_id", "12"))
                .format(otherType, new BaseModelWithTree(StoryFlag.typeTag("TextWithEntities")).with("color", "red"));
        assertTrue(guard(colored, stand.readers()));
        assertFalse(guard(plain, stand.readers()));
        assertFalse(guard(otherType, stand.readers()));
        assertFalse("no format at all", guard(new GraphQLStory(), stand.readers()));
        assertEquals(PostTypes.ROUTE + ": 4 lists, 4 items, 1 removed. Last reason: colored background post. "
                + "Removed: colored background post 1. Kinds: no kind to hide 3, colored background post 1",
                line(PostTypes.ROUTE));
    }

    @Test
    public void whatCantBeReadStaysAndSaysWhy() {
        Settings.HIDE_PHOTO_POSTS.save(true);
        Settings.HIDE_BACKGROUND_POSTS.save(true);
        GraphQLStory story = new GraphQLStory(null, new GraphQLStoryAttachment(new GraphQLMedia("Photo")));
        assertFalse("the stubs unfilled", guard(story, PostTypes.READERS));
        List<String> misses = HookStatus.missing(FamilyNames.POST_WORDS);
        assertTrue(misses.toString(), misses.contains("method " + StoryFlag.STORY_CLASS + "#the attachments accessor"));
        assertTrue(misses.toString(),
                misses.contains("method " + StoryFlag.STORY_CLASS + "#the text_format_metadata accessor"));

        Stand stand = new Stand();
        GraphQLStoryAttachment broken = stand.attachment();
        stand.styles.put(broken, new IllegalStateException("tree gone"));
        assertFalse(guard(new GraphQLStory(null, broken), stand.readers()));
        assertFalse(guard(new Object(), stand.readers()));
        assertFalse(guard(new GraphQLStory(null, stand.attachment(Style.PHOTO)).released(), stand.readers()));
        assertEquals(PostTypes.ROUTE + ": 4 lists, 4 items, 0 removed. Kinds: accessor not patched 1, "
                + "not a story 1, read failed 1, tree released 1", line(PostTypes.ROUTE));
    }

    @Test
    public void pausedReadsNothing() {
        Settings.HIDE_PHOTO_POSTS.save(true);
        Settings.HIDE_BACKGROUND_POSTS.save(true);
        Stand stand = new Stand();
        GraphQLStory story = new GraphQLStory(null, stand.attachment(Style.PHOTO));
        stand.format(story, background());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(guard(story, stand.readers()));
        assertEquals(0, stand.calls);
        assertNull(line(PostTypes.ROUTE));
        PauseForTests.resume();
        assertTrue("the switches didn't come back after the pause", guard(story, stand.readers()));
    }
}
