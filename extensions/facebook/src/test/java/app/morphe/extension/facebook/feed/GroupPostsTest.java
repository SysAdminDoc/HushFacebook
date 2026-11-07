/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import app.morphe.extension.facebook.feed.FeedGuardForTests.JoinState;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Hide posts from groups you're not in (#195) in the shared feed guard: a story posted in a group
 * whose join state is one Facebook shows its Join button for goes, and so does a story wrapping
 * one, the way a group post a friend commented on or shared reaches Home, and a story naming its
 * group as its {@code target_group} instead of its {@code to}. A post in a group you're in, a post
 * you asked to join, a post with no group and a post whose state Home didn't fetch stay, and so
 * does one that can't be read. Off reads nothing, paused is stock, and the report counts what each
 * read found.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class GroupPostsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC }

    /**
     * Stands in for the accessors the rule reads through: a target and a target group per story, a
     * state per target, and the post a story wraps.
     */
    private static final class Stand {
        final Map<Object, Object> targets = new IdentityHashMap<>();
        final Map<Object, Object> targetGroups = new IdentityHashMap<>();
        final Map<Object, Object> states = new IdentityHashMap<>();
        final Map<Object, Object> wrapped = new IdentityHashMap<>();
        int calls;

        /** A story posted in a group whose join state is [state]. */
        GraphQLStory inGroup(Object state) {
            BaseModelWithTree group = FeedGuardForTests.group();
            GraphQLStory story = new GraphQLStory();
            targets.put(story, group);
            states.put(group, state);
            return story;
        }

        /** A story posted to no profile that names a group whose join state is [state] as its target group. */
        GraphQLStory inTargetGroup(Object state) {
            BaseModelWithTree group = FeedGuardForTests.group();
            GraphQLStory story = new GraphQLStory();
            targetGroups.put(story, group);
            states.put(group, state);
            return story;
        }

        /** A story posted to no profile that wraps [inner], as a friend's comment or share of it. */
        GraphQLStory wrapping(Object inner) {
            GraphQLStory story = new GraphQLStory();
            wrapped.put(story, inner);
            return story;
        }

        GroupPosts.Readers readers() {
            return new GroupPosts.Readers(story -> {
                calls++;
                Object target = targets.get(story);
                if (target instanceof RuntimeException) throw (RuntimeException) target;
                return target;
            }, story -> {
                calls++;
                return targetGroups.get(story);
            }, target -> {
                calls++;
                Object state = states.get(target);
                if (state instanceof RuntimeException) throw (RuntimeException) state;
                return state;
            }, story -> {
                calls++;
                return wrapped.get(story);
            }, GroupPosts.SHAPE_PATCHED);
        }
    }

    /**
     * Stands in for the shape accessors the title and attachment reads go through: a title per
     * story whose ranges are their own entities, attachments that are their own targets, and the
     * profile each title entity reads as. The rest comes from a {@link Stand}.
     */
    private static final class Named {
        final Map<Object, Object> titles = new IdentityHashMap<>();
        final Map<Object, Object> ranges = new IdentityHashMap<>();
        final Map<Object, Object> attachments = new IdentityHashMap<>();
        final Map<Object, Object> profiles = new IdentityHashMap<>();
        /** How a title entity reads as a profile; a test swaps it for a build without the patch's class. */
        StoryFlag.Accessor group = profiles::get;

        /** A story posted to no profile whose title's ranges point at [entities], in order. */
        GraphQLStory titled(Object... entities) {
            GraphQLStory story = new GraphQLStory();
            Object title = new Object();
            titles.put(story, title);
            ranges.put(title, Arrays.asList(entities));
            return story;
        }

        /** A title entity that answers Group and reads as a profile whose join state is [state]. */
        BaseModelWithTree titleGroup(Stand stand, Object state) {
            BaseModelWithTree entity = new BaseModelWithTree(GroupPosts.GROUP_TYPE) { };
            BaseModelWithTree profile = FeedGuardForTests.group();
            profiles.put(entity, profile);
            stand.states.put(profile, state);
            return entity;
        }

        /** [story], its attachments pointing at [targets], in order. */
        GraphQLStory attached(GraphQLStory story, Object... targets) {
            attachments.put(story, Arrays.asList(targets));
            return story;
        }

        GroupPosts.Readers readers(Stand stand) {
            GroupPosts.ShapeReaders shape = new GroupPosts.ShapeReaders(titles::get, s -> null, ranges::get,
                    range -> range, attachments::get, attachment -> attachment, stand.states::get, s -> null,
                    entity -> group.model(entity));
            return new GroupPosts.Readers(stand.targets::get, stand.targetGroups::get, stand.states::get,
                    stand.wrapped::get, shape);
        }
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.resetToDefault();
        Settings.HIDE_GROUP_POSTS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    private static boolean guard(Object unit, GroupPosts.Readers readers) {
        return FeedFilter.hideEdge(Category.ORGANIC, unit, true, true, story -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, false, PostText.MESSAGE, PostText.ATTACHED, story -> null, null, null, null,
                readers);
    }

    private static String line(String route) {
        for (String reported : FeedFilterCounters.report()) {
            if (reported.startsWith(route + ": ")) return reported;
        }
        return null;
    }

    /** The names are the ones Facebook 583's join state enum and Join button rule use. */
    @Test
    public void theStatesAreTheOnesFacebooksJoinButtonShowsFor() {
        assertEquals(2, GroupPosts.NOT_JOINED.length);
        assertTrue(GroupPosts.notJoined("CAN_JOIN"));
        assertTrue(GroupPosts.notJoined("CAN_REQUEST"));
        assertTrue("read through the wrapped post", GroupPosts.notJoined(GroupPosts.WRAPPED + "CAN_JOIN"));
        assertTrue("read through the target group", GroupPosts.notJoined(GroupPosts.TARGET_GROUP + "CAN_JOIN"));
        assertTrue("read through the wrapped post's target group",
                GroupPosts.notJoined(GroupPosts.WRAPPED + GroupPosts.TARGET_GROUP + "CAN_REQUEST"));
        assertTrue("read through the title's group", GroupPosts.notJoined(GroupPosts.TITLE_GROUP + "CAN_JOIN"));
        assertTrue("read through an attachment's group",
                GroupPosts.notJoined(GroupPosts.ATTACHMENT_GROUP + "CAN_REQUEST"));
        assertTrue("read through the wrapped post's title",
                GroupPosts.notJoined(GroupPosts.WRAPPED + GroupPosts.TITLE_GROUP + "CAN_JOIN"));
        for (String kept : new String[] {"MEMBER", "REQUESTED", "CANNOT_JOIN_OR_REQUEST",
                "UNSET_OR_UNRECOGNIZED_ENUM_VALUE", GroupPosts.NOT_A_GROUP, GroupPosts.READ_FAILED,
                GroupPosts.WRAPPED + "MEMBER", GroupPosts.WRAPPED + GroupPosts.NOT_A_GROUP,
                GroupPosts.TARGET_GROUP + "MEMBER", GroupPosts.WRAPPED + GroupPosts.TARGET_GROUP + "MEMBER",
                GroupPosts.TITLE_GROUP + "MEMBER", GroupPosts.TITLE_GROUP + GroupPosts.NO_STATE,
                GroupPosts.ATTACHMENT_GROUP + "MEMBER", GroupPosts.WRAPPED + GroupPosts.ATTACHMENT_GROUP + "REQUESTED",
                GroupPosts.NO_WRAPPED_READER, null}) {
            assertFalse(kept, GroupPosts.notJoined(kept));
        }
        assertFalse("off until picked", Settings.HIDE_GROUP_POSTS_NOT_JOINED.defaultValue);
    }

    @Test
    public void offReadsNothingAndHidesNothing() {
        Stand stand = new Stand();
        assertFalse(guard(stand.inGroup(JoinState.CAN_JOIN), stand.readers()));
        assertEquals("nothing was read with the switch off", 0, stand.calls);
        assertNull(line(GroupPosts.ROUTE));
    }

    @Test
    public void aPostInAGroupYouCanJoinGoesAndYourGroupsStay() {
        Stand stand = new Stand();
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        assertTrue("a public group", guard(stand.inGroup(JoinState.CAN_JOIN), stand.readers()));
        assertTrue("a group that asks first", guard(stand.inGroup(JoinState.CAN_REQUEST), stand.readers()));
        assertFalse("your group", guard(stand.inGroup(JoinState.MEMBER), stand.readers()));
        assertFalse("asked to join", guard(stand.inGroup(JoinState.REQUESTED), stand.readers()));
        assertFalse("can't join", guard(stand.inGroup(JoinState.CANNOT_JOIN_OR_REQUEST), stand.readers()));
        assertFalse("Home didn't fetch the state",
                guard(stand.inGroup(JoinState.UNSET_OR_UNRECOGNIZED_ENUM_VALUE), stand.readers()));

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("6 lists, 6 items, 2 removed"));
        assertTrue(route, route.contains("Group:CAN_JOIN 1"));
        assertTrue(route, route.contains("Group:CAN_REQUEST 1"));
        assertTrue(route, route.contains("MEMBER 1"));
        assertTrue(route, route.contains("UNSET_OR_UNRECOGNIZED_ENUM_VALUE 1"));
        String feed = line(FeedFilter.FEED_ROUTE);
        assertNotNull(feed);
        assertTrue(feed, feed.contains("Group:CAN_JOIN 1"));
    }

    /**
     * Hide all group posts (#409): a post in any group goes, your own groups' and one whose state
     * Home didn't fetch included, and so does a group post a friend commented on or shared. A
     * friend's own post and a post on a Page stay. It counts on its own route, and the switch for
     * groups you're not in reads nothing more while it's on.
     */
    @Test
    public void hideAllGroupPostsTakesEveryGroupsPostsAndLeavesTheRest() {
        assertFalse("off until picked", Settings.HIDE_GROUP_POSTS.defaultValue);
        Settings.HIDE_GROUP_POSTS.save(true);
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        assertTrue("your group", guard(stand.inGroup(JoinState.MEMBER), stand.readers()));
        assertTrue("a public group", guard(stand.inGroup(JoinState.CAN_JOIN), stand.readers()));
        assertTrue("asked to join", guard(stand.inGroup(JoinState.REQUESTED), stand.readers()));
        assertTrue("can't join", guard(stand.inGroup(JoinState.CANNOT_JOIN_OR_REQUEST), stand.readers()));
        assertTrue("Home didn't fetch the state",
                guard(stand.inGroup(JoinState.UNSET_OR_UNRECOGNIZED_ENUM_VALUE), stand.readers()));
        assertTrue("a group with no state at all", guard(stand.inGroup(null), stand.readers()));
        assertTrue("named as its target group", guard(stand.inTargetGroup(JoinState.MEMBER), stand.readers()));
        assertTrue("your group's post a friend shared",
                guard(stand.wrapping(stand.inGroup(JoinState.MEMBER)), stand.readers()));
        assertFalse("a friend's own post", guard(new GraphQLStory(), stand.readers()));
        GraphQLStory onAPage = new GraphQLStory();
        BaseModelWithTree page = new BaseModelWithTree("Page") { };
        stand.targets.put(onAPage, page);
        assertFalse("a post on a Page", guard(onAPage, stand.readers()));
        assertFalse("a share of a friend's own post", guard(stand.wrapping(new GraphQLStory()), stand.readers()));

        String route = line(GroupPosts.ALL_ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("11 lists, 11 items, 8 removed"));
        assertTrue(route, route.contains("Group:MEMBER 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.NO_STATE + " 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.WRAPPED + "MEMBER 1"));
        assertNull("the not-joined rule wasn't asked as well", line(GroupPosts.ROUTE));
        assertTrue(GroupPosts.inAGroup(GroupPosts.TITLE_GROUP + GroupPosts.NO_STATE));
        assertTrue(GroupPosts.inAGroup(GroupPosts.WRAPPED + GroupPosts.ATTACHMENT_GROUP + "MEMBER"));
        for (String kept : new String[] {GroupPosts.NOT_A_STORY, GroupPosts.NOT_PATCHED, GroupPosts.RELEASED,
                GroupPosts.NO_TARGET, GroupPosts.NOT_A_GROUP, GroupPosts.WRAPPED + GroupPosts.NOT_A_GROUP,
                GroupPosts.TARGET_GROUP + GroupPosts.NOT_A_GROUP, GroupPosts.READ_FAILED,
                GroupPosts.NO_WRAPPED_READER, "", null}) {
            assertFalse(kept, GroupPosts.inAGroup(kept));
        }
    }

    /**
     * A friend's own post wraps nothing and a post on a Page has no group as its target, so no
     * group's state is asked.
     */
    @Test
    public void aPostNotInAGroupStaysWithoutAJoinStateRead() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        GraphQLStory own = new GraphQLStory();
        assertFalse(guard(own, stand.readers()));
        GraphQLStory onAPage = new GraphQLStory();
        BaseModelWithTree page = new BaseModelWithTree("Page") { };
        stand.targets.put(onAPage, page);
        stand.states.put(page, JoinState.CAN_JOIN);
        assertFalse(guard(onAPage, stand.readers()));
        assertEquals("the two targets, both target groups and the own post's wrapped post were read", 5, stand.calls);

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains(GroupPosts.NO_TARGET + " 1"));
        assertTrue(route, route.contains(GroupPosts.NOT_A_GROUP + " 1"));
    }

    /**
     * A group post a friend commented on or shared reaches Home as a story posted to no profile
     * that wraps the group's post (the second report on #195): the wrapped post's group decides,
     * and the report says the read went through the wrapper. A wrapped post with no group, one
     * whose tree is gone, one that isn't a story and one a build without Hide posts with words
     * can't read all stay.
     */
    @Test
    public void aGroupPostAFriendCommentedOnOrSharedGoesByTheWrappedPostsGroup() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        assertTrue("commented on, in a public group",
                guard(stand.wrapping(stand.inGroup(JoinState.CAN_JOIN)), stand.readers()));
        assertTrue("shared from a group that asks first",
                guard(stand.wrapping(stand.inGroup(JoinState.CAN_REQUEST)), stand.readers()));
        assertFalse("from your own group", guard(stand.wrapping(stand.inGroup(JoinState.MEMBER)), stand.readers()));
        assertFalse("a share of a friend's own post", guard(stand.wrapping(new GraphQLStory()), stand.readers()));
        GraphQLStory onAPage = new GraphQLStory();
        BaseModelWithTree page = new BaseModelWithTree("Page") { };
        stand.targets.put(onAPage, page);
        stand.states.put(page, JoinState.CAN_JOIN);
        assertFalse("a share of a Page's post", guard(stand.wrapping(onAPage), stand.readers()));
        assertFalse("the wrapped post's tree is gone",
                guard(stand.wrapping(stand.inGroup(JoinState.CAN_JOIN).released()), stand.readers()));
        assertFalse("wraps something that isn't a story", guard(stand.wrapping(new Object()), stand.readers()));
        assertFalse("no reader of the wrapped post", guard(stand.wrapping(stand.inGroup(JoinState.CAN_JOIN)),
                new GroupPosts.Readers(story -> stand.targets.get(story), target -> stand.states.get(target))));

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("8 lists, 8 items, 2 removed"));
        assertTrue(route, route.contains("Group:" + GroupPosts.WRAPPED + "CAN_JOIN 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.WRAPPED + "CAN_REQUEST 1"));
        assertTrue(route, route.contains(GroupPosts.WRAPPED + "MEMBER 1"));
        assertTrue(route, route.contains(GroupPosts.NO_TARGET + " 2"));
        assertTrue(route, route.contains(GroupPosts.WRAPPED + GroupPosts.NOT_A_GROUP + " 1"));
        assertTrue(route, route.contains(GroupPosts.RELEASED + " 1"));
        assertTrue(route, route.contains(GroupPosts.NO_WRAPPED_READER + " 1"));
        String feed = line(FeedFilter.FEED_ROUTE);
        assertNotNull(feed);
        assertTrue(feed, feed.contains("Group:" + GroupPosts.WRAPPED + "CAN_JOIN 1"));
    }

    /**
     * A group post whose {@code to} is empty, or is a Page, can still name its group as its
     * {@code target_group} (the fourth report on #195): that group decides, straight or wrapped in
     * a friend's activity, and the report says the read went through it. A group as {@code to}
     * wins, and a build whose story has no {@code target_group} reads {@code to} alone.
     */
    @Test
    public void aPostNamingItsGroupAsItsTargetGroupGoesByThatGroup() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        assertTrue("posted to no profile, a public group as its target group",
                guard(stand.inTargetGroup(JoinState.CAN_JOIN), stand.readers()));
        assertTrue("a group that asks first as its target group",
                guard(stand.inTargetGroup(JoinState.CAN_REQUEST), stand.readers()));
        assertFalse("your group as its target group", guard(stand.inTargetGroup(JoinState.MEMBER), stand.readers()));
        GraphQLStory onAPage = stand.inTargetGroup(JoinState.CAN_JOIN);
        stand.targets.put(onAPage, new BaseModelWithTree("Page") { });
        assertTrue("posted to a Page, a public group as its target group", guard(onAPage, stand.readers()));
        assertTrue("a friend's comment on one",
                guard(stand.wrapping(stand.inTargetGroup(JoinState.CAN_JOIN)), stand.readers()));
        assertFalse("a friend's comment on one from your group",
                guard(stand.wrapping(stand.inTargetGroup(JoinState.MEMBER)), stand.readers()));
        GraphQLStory both = stand.inGroup(JoinState.MEMBER);
        BaseModelWithTree other = FeedGuardForTests.group();
        stand.targetGroups.put(both, other);
        stand.states.put(other, JoinState.CAN_JOIN);
        assertFalse("your group as to wins over the target group", guard(both, stand.readers()));
        assertFalse("no reader of the target group", guard(stand.inTargetGroup(JoinState.CAN_JOIN),
                new GroupPosts.Readers(story -> stand.targets.get(story), target -> stand.states.get(target))));

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("8 lists, 8 items, 4 removed"));
        assertTrue(route, route.contains("Group:" + GroupPosts.TARGET_GROUP + "CAN_JOIN 2"));
        assertTrue(route, route.contains("Group:" + GroupPosts.TARGET_GROUP + "CAN_REQUEST 1"));
        assertTrue(route, route.contains(GroupPosts.TARGET_GROUP + "MEMBER 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.WRAPPED + GroupPosts.TARGET_GROUP + "CAN_JOIN 1"));
        assertTrue(route, route.contains(GroupPosts.WRAPPED + GroupPosts.TARGET_GROUP + "MEMBER 1"));
        assertTrue(route, route.contains(GroupPosts.NO_WRAPPED_READER + " 1"));
        String feed = line(FeedFilter.FEED_ROUTE);
        assertNotNull(feed);
        assertTrue(feed, feed.contains("Group:" + GroupPosts.TARGET_GROUP + "CAN_JOIN 2"));
    }

    /**
     * A group post with no {@code to}, no {@code target_group} and no wrapped post that names one
     * can still name its group in its title, next to the poster's name: that group decides, read as
     * a profile, straight or wrapped in a friend's activity, and the report says the read went
     * through the title. The title's first group decides. A title naming only people keeps the
     * post, and so do a title group that reads as nothing, one whose state Home didn't fetch, one
     * on a build where it can't be read as a profile, and any group in the title of a post posted
     * to a person's profile.
     */
    @Test
    public void aPostNamingItsGroupInItsTitleGoesByThatGroup() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        Named named = new Named();
        GroupPosts.Readers readers = named.readers(stand);
        BaseModelWithTree user = new BaseModelWithTree("User") { };
        assertTrue("a public group in the title",
                guard(named.titled(user, named.titleGroup(stand, JoinState.CAN_JOIN)), readers));
        assertTrue("a group that asks first", guard(named.titled(named.titleGroup(stand, JoinState.CAN_REQUEST)), readers));
        assertFalse("your group", guard(named.titled(user, named.titleGroup(stand, JoinState.MEMBER)), readers));
        assertFalse("the title's first group decides", guard(named.titled(named.titleGroup(stand, JoinState.MEMBER),
                named.titleGroup(stand, JoinState.CAN_JOIN)), readers));
        assertFalse("Home didn't fetch the title group's state",
                guard(named.titled(named.titleGroup(stand, JoinState.UNSET_OR_UNRECOGNIZED_ENUM_VALUE)), readers));
        assertFalse("a title naming only people", guard(named.titled(user, user), readers));
        assertFalse("a title group that reads as nothing",
                guard(named.titled(new BaseModelWithTree(GroupPosts.GROUP_TYPE) { }), readers));
        assertTrue("a friend's comment on one",
                guard(stand.wrapping(named.titled(named.titleGroup(stand, JoinState.CAN_JOIN))), readers));
        GraphQLStory onAPerson = named.titled(named.titleGroup(stand, JoinState.CAN_JOIN));
        stand.targets.put(onAPerson, user);
        assertFalse("posted to a person's profile, a group in the title", guard(onAPerson, readers));
        named.group = entity -> StoryFlag.NOT_PATCHED;
        assertFalse("a build where the title's group can't be read as a profile",
                guard(named.titled(named.titleGroup(stand, JoinState.CAN_JOIN)), readers));

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("10 lists, 10 items, 3 removed"));
        assertTrue(route, route.contains("Group:" + GroupPosts.TITLE_GROUP + "CAN_JOIN 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.TITLE_GROUP + "CAN_REQUEST 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.WRAPPED + GroupPosts.TITLE_GROUP + "CAN_JOIN 1"));
        assertTrue(route, route.contains(GroupPosts.TITLE_GROUP + "MEMBER 2"));
        assertTrue(route, route.contains(GroupPosts.TITLE_GROUP + "UNSET_OR_UNRECOGNIZED_ENUM_VALUE 1"));
        assertTrue(route, route.contains(GroupPosts.TITLE_GROUP + GroupPosts.NO_STATE + " 1"));
        assertTrue(route, route.contains(GroupPosts.NO_TARGET + " 2"));
        assertTrue(route, route.contains(GroupPosts.NOT_A_GROUP + " 1"));
    }

    /**
     * When the title names no group, the first attachment pointing at a Group decides, read as it
     * is, and the report says the read went through it. An attachment pointing at anything else
     * keeps the post, and so does a group whose tree is gone. A group in the title decides over
     * an attachment, which can be a share of some other group, even when the title's group can't
     * be read.
     */
    @Test
    public void anAttachmentPointingAtAGroupDecidesWhenTheTitleNamesNone() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        Named named = new Named();
        GroupPosts.Readers readers = named.readers(stand);
        BaseModelWithTree user = new BaseModelWithTree("User") { };
        BaseModelWithTree video = new BaseModelWithTree("Video") { };
        BaseModelWithTree open = FeedGuardForTests.group();
        stand.states.put(open, JoinState.CAN_JOIN);
        BaseModelWithTree asks = FeedGuardForTests.group();
        stand.states.put(asks, JoinState.CAN_REQUEST);
        BaseModelWithTree yours = FeedGuardForTests.group();
        stand.states.put(yours, JoinState.MEMBER);
        BaseModelWithTree gone = FeedGuardForTests.group();
        stand.states.put(gone, JoinState.CAN_JOIN);
        gone.released();

        assertTrue("a public group after a video", guard(named.attached(named.titled(user), video, open), readers));
        assertTrue("a group that asks first, no title", guard(named.attached(new GraphQLStory(), asks), readers));
        assertFalse("the first group decides", guard(named.attached(named.titled(user), yours, open), readers));
        assertFalse("a video", guard(named.attached(named.titled(user), video), readers));
        assertFalse("a released group", guard(named.attached(new GraphQLStory(), gone), readers));
        assertFalse("a released group was read", gone.readAfterRelease);
        assertFalse("the title's group decides",
                guard(named.attached(named.titled(named.titleGroup(stand, JoinState.MEMBER)), open), readers));
        named.group = entity -> StoryFlag.NOT_PATCHED;
        assertFalse("a title group that can't be read keeps the post",
                guard(named.attached(named.titled(named.titleGroup(stand, JoinState.CAN_JOIN)), open), readers));

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("7 lists, 7 items, 2 removed"));
        assertTrue(route, route.contains("Group:" + GroupPosts.ATTACHMENT_GROUP + "CAN_JOIN 1"));
        assertTrue(route, route.contains("Group:" + GroupPosts.ATTACHMENT_GROUP + "CAN_REQUEST 1"));
        assertTrue(route, route.contains(GroupPosts.ATTACHMENT_GROUP + "MEMBER 1"));
        assertTrue(route, route.contains(GroupPosts.TITLE_GROUP + "MEMBER 1"));
        assertTrue(route, route.contains(GroupPosts.NO_TARGET + " 3"));
    }

    /**
     * A title's group is read as a profile the way Facebook's TreeConverter turns one model into
     * another: the tree's reinterpret, asked for the profile class under the Group type tag, the
     * first four bytes of the MD5 of "Group". One that already is a profile is taken as it is, and
     * none, a released tree (reinterpret is native) and a build without the patch's class are never
     * asked. Something with no reinterpret fails, for the read to record.
     */
    @Test
    public void aTitlesGroupIsReadAsAProfileThroughTheTreesReinterpret() throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(GroupPosts.GROUP_TYPE.getBytes(StandardCharsets.UTF_8));
        int tag = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        assertEquals(tag, GroupPosts.GROUP_TYPE_TAG);

        BaseModelWithTree profile = FeedGuardForTests.group();
        Class<?> profileClass = profile.getClass();
        BaseModelWithTree entity = new BaseModelWithTree(GroupPosts.GROUP_TYPE) { };
        entity.reinterpreted = profile;
        assertSame(profile, GroupPosts.asGroup(entity, profileClass));
        assertEquals(1, entity.reinterprets);
        assertSame(profileClass, entity.reinterpretedAs);
        assertEquals(GroupPosts.GROUP_TYPE_TAG, entity.reinterpretedTag);

        assertSame("already a profile", profile, GroupPosts.asGroup(profile, profileClass));
        assertEquals(0, profile.reinterprets);
        assertNull(GroupPosts.asGroup(null, profileClass));
        BaseModelWithTree gone = new BaseModelWithTree(GroupPosts.GROUP_TYPE) { };
        gone.released();
        assertNull(GroupPosts.asGroup(gone, profileClass));
        assertEquals(0, gone.reinterprets);
        assertFalse("a released tree was reinterpreted", gone.readAfterRelease);
        BaseModelWithTree unpatched = new BaseModelWithTree(GroupPosts.GROUP_TYPE) { };
        assertSame(StoryFlag.NOT_PATCHED, GroupPosts.asGroup(unpatched, StoryFlag.NOT_PATCHED));
        assertSame("the stub the patch fills", StoryFlag.NOT_PATCHED, GroupPosts.asGroup(unpatched));
        assertEquals(0, unpatched.reinterprets);
        try {
            GroupPosts.asGroup(new Object(), profileClass);
            fail("something with no reinterpret read as a profile");
        } catch (IllegalStateException expected) {
            // The read records it as a failure and keeps the post.
        }
    }

    /** A group the title or an attachment named gets the story's shape on the debug line too. */
    @Test
    public void theDebugLineGivesTheShapeOfAPostReadThroughItsTitle() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        try {
            Stand stand = new Stand();
            Named named = new Named();
            GroupPosts.Readers readers = named.readers(stand);
            BaseModelWithTree user = new BaseModelWithTree("User") { };
            guard(named.titled(user, named.titleGroup(stand, JoinState.CAN_JOIN)), readers);
            BaseModelWithTree open = FeedGuardForTests.group();
            stand.states.put(open, JoinState.CAN_JOIN);
            guard(named.attached(named.titled(user), open), readers);
            String log = LogBufferManager.buildExportText();
            assertTrue(log, log.contains(" group=" + GroupPosts.TITLE_GROUP
                    + "CAN_JOIN shape=target_group:-;title:User+Group;shareable:-;attachments:-"));
            assertTrue(log, log.contains(" group=" + GroupPosts.ATTACHMENT_GROUP
                    + "CAN_JOIN shape=target_group:-;title:User;shareable:-;attachments:Group/CAN_JOIN"));
            assertNull("the debug line counted as a read of the rule", line(GroupPosts.ROUTE));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test
    public void aPostThatCantBeReadStays() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();

        // Neither accessor filled in, as on a build without the anchors.
        assertFalse(guard(stand.inGroup(JoinState.CAN_JOIN), GroupPosts.PATCHED));
        // The join state accessor alone not filled in.
        GraphQLStory half = stand.inGroup(JoinState.CAN_JOIN);
        assertFalse(guard(half, new GroupPosts.Readers(story -> stand.targets.get(story),
                target -> StoryFlag.NOT_PATCHED)));
        // An accessor throws.
        GraphQLStory throwing = new GraphQLStory();
        stand.targets.put(throwing, new IllegalStateException("tree gone"));
        assertFalse(guard(throwing, stand.readers()));
        // The story's native tree is gone.
        assertFalse(guard(stand.inGroup(JoinState.CAN_JOIN).released(), stand.readers()));
        // The group's native tree is gone.
        GraphQLStory goneGroup = new GraphQLStory();
        BaseModelWithTree gone = FeedGuardForTests.group();
        gone.released();
        stand.targets.put(goneGroup, gone);
        stand.states.put(gone, JoinState.CAN_JOIN);
        assertFalse(guard(goneGroup, stand.readers()));
        assertFalse("a released group was read", gone.readAfterRelease);
        // Not a story.
        assertFalse(guard(new Object(), stand.readers()));
        // A state that isn't an enum, and none at all.
        assertFalse(guard(stand.inGroup("CAN_JOIN"), stand.readers()));
        assertFalse(guard(stand.inGroup(null), stand.readers()));

        String route = line(GroupPosts.ROUTE);
        assertNotNull(route);
        assertTrue(route, route.contains("0 removed"));
        assertTrue(route, route.contains(GroupPosts.NOT_PATCHED + " 2"));
        assertTrue(route, route.contains(GroupPosts.READ_FAILED + " 1"));
        assertTrue(route, route.contains(GroupPosts.RELEASED + " 2"));
        assertTrue(route, route.contains(GroupPosts.NOT_A_STORY + " 1"));
        assertTrue(route, route.contains(GroupPosts.NOT_AN_ENUM + " 1"));
        assertTrue(route, route.contains(GroupPosts.NO_STATE + " 1"));
    }

    @Test
    public void pausedFacebookKeepsEveryPost() {
        Stand stand = new Stand();
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        assertTrue(guard(stand.inGroup(JoinState.CAN_JOIN), stand.readers()));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        int before = stand.calls;
        assertFalse(guard(stand.inGroup(JoinState.CAN_JOIN), stand.readers()));
        assertEquals("a paused Facebook read nothing", before, stand.calls);
    }

    /**
     * With debug logging on, each edge's line says what the join state read found, the switch on or
     * off, so a device check can see the state Home fetched. The line isn't a read of the rule.
     */
    @Test
    public void theDebugLineSaysWhatTheJoinStateReads() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        try {
            Stand stand = new Stand();
            guard(stand.inGroup(JoinState.CAN_JOIN), stand.readers());
            guard(new GraphQLStory(), stand.readers());
            String log = LogBufferManager.buildExportText();
            assertTrue(log, log.contains(" group=CAN_JOIN"));
            assertTrue(log, log.contains(" group=" + GroupPosts.NO_TARGET));
            assertNull("the debug line counted as a read of the rule", line(GroupPosts.ROUTE));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    /**
     * A story posted to no profile and wrapping no story (the third report on #195) gets its shape on
     * the debug line: its target group with its join state, the type names its title's ranges point
     * at, its shareable, and each attachment's target, a group's with its join state. At most
     * {@link GroupPosts#SHAPE_LIMIT} a list, and a post in a group gets no shape.
     */
    @Test
    public void theDebugLineSaysWhereAStoryPostedToNoProfileNamesAGroup() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        try {
            Stand stand = new Stand();
            BaseModelWithTree group = FeedGuardForTests.group();
            stand.states.put(group, JoinState.CAN_JOIN);
            BaseModelWithTree user = new BaseModelWithTree("User") { };
            BaseModelWithTree video = new BaseModelWithTree("Video") { };
            Object userRange = new Object();
            Object groupRange = new Object();
            Map<Object, Object> entities = new IdentityHashMap<>();
            entities.put(userRange, user);
            entities.put(groupRange, group);
            Object toGroup = new Object();
            Object toVideo = new Object();
            Map<Object, Object> targets = new IdentityHashMap<>();
            targets.put(toGroup, group);
            targets.put(toVideo, video);
            GraphQLStory story = new GraphQLStory();
            GraphQLStory longTitle = new GraphQLStory();
            Object title = new Object();
            Object manyRanges = new Object();
            GroupPosts.ShapeReaders shape = new GroupPosts.ShapeReaders(
                    s -> s == story ? title : s == longTitle ? manyRanges : null,
                    s -> null,
                    t -> t == title ? Arrays.asList(userRange, groupRange)
                            : t == manyRanges ? Collections.nCopies(GroupPosts.SHAPE_LIMIT + 2, userRange) : null,
                    entities::get,
                    s -> s == story ? Arrays.asList(toGroup, toVideo) : null,
                    targets::get,
                    target -> stand.states.get(target),
                    s -> s == longTitle ? group : null);
            GroupPosts.Readers readers = new GroupPosts.Readers(stand.targets::get, s -> null, stand.states::get, s -> null,
                    shape);

            guard(story, readers);
            guard(longTitle, readers);
            guard(stand.inGroup(JoinState.MEMBER), readers);
            String log = LogBufferManager.buildExportText();
            assertTrue(log, log.contains(" group=" + GroupPosts.NO_TARGET
                    + " shape=target_group:-;title:User+Group;shareable:-;attachments:Group/CAN_JOIN+Video"));
            assertTrue(log, log.contains(
                    " shape=target_group:Group/CAN_JOIN;title:User+User+User+User+more;shareable:-;attachments:-"));
            assertTrue(log, log.contains(" group=MEMBER"));
            assertFalse(log, log.contains(" group=MEMBER shape="));
            assertNull("the debug line counted as a read of the rule", line(GroupPosts.ROUTE));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    /** A build whose patch filled none of the shape's accessors says so with a "?" each, and nothing throws. */
    @Test
    public void theShapeOfAnUnpatchedBuildSaysSo() {
        assertEquals("target_group:?;title:?;shareable:?;attachments:?",
                GroupPosts.shape(new GraphQLStory(), GroupPosts.SHAPE_PATCHED));
        assertEquals(GroupPosts.RELEASED, GroupPosts.shape(new GraphQLStory().released(), GroupPosts.SHAPE_PATCHED));
        GroupPosts.ShapeReaders throwing = new GroupPosts.ShapeReaders(s -> {
            throw new IllegalStateException("tree gone");
        }, s -> null, t -> null, r -> null, s -> null, a -> null, g -> null, s -> null);
        assertEquals(GroupPosts.READ_FAILED, GroupPosts.shape(new GraphQLStory(), throwing));
    }

    /** The guard without the readers, as the overloads before this rule call it, leaves the rule out. */
    @Test
    public void theOlderOverloadsLeaveTheRuleOut() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        Stand stand = new Stand();
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, stand.inGroup(JoinState.CAN_JOIN), true, true));
        assertEquals(0, stand.calls);
        assertTrue(FeedGuardForTests.hidesGroupPost(Category.ORGANIC, FeedGuardForTests.group(), JoinState.CAN_JOIN));
    }

    private static String statusLine() {
        for (String reported : HookStatus.report()) {
            if (reported.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return reported;
        }
        return "";
    }

    /** Off, Home asks for join states only when Facebook would. */
    @Test
    public void offKeepsFacebooksAnswerForTheJoinStateQuery() {
        assertFalse(GroupPosts.askForJoinState(false));
        assertTrue(GroupPosts.askForJoinState(true));
        assertFalse(statusLine().contains(GroupPosts.ASKED));
    }

    /** On, Home always asks, so a group post reads its real state instead of the unset default. */
    @Test
    public void onAsksHomeForJoinStates() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        assertTrue(GroupPosts.askForJoinState(false));
        String status = statusLine();
        assertTrue(status, status.contains(GroupPosts.ASKED + " 1"));
    }

    /** Facebook's own yes stays a yes and isn't counted as the switch's doing. */
    @Test
    public void facebooksYesIsKeptAndNotCounted() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        assertTrue(GroupPosts.askForJoinState(true));
        assertFalse(statusLine().contains(GroupPosts.ASKED));
    }

    @Test
    public void pausedOrNotReadyKeepsFacebooksAnswerForTheJoinStateQuery() {
        Settings.HIDE_GROUP_POSTS_NOT_JOINED.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(GroupPosts.askForJoinState(false));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(GroupPosts.askForJoinState(false)));
        assertTrue(GroupPosts.askForJoinState(false));
    }
}
