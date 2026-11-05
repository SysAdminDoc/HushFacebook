/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.os.Looper;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;
import com.facebook.graphql.modelutil.BaseModelWithTree;
import com.facebook.graphservice.tree.TreeJNI;

import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowToast;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The feed guard with both feed patches in, for a test outside this package. A test JVM has no
 * patched {@code SettingsStatus}, so the public guard would hide nothing whatever the switches say.
 * The GenAI reel filter's stub is unpatched there too, so its page filters take a stand-in finder.
 */
public final class FeedGuardForTests {
    private FeedGuardForTests() {
    }

    /** Stands in for the reel model class the patch names; the filter finds it in an item's field by type. */
    public static final class ReelModel {
        final Object attribution;

        public ReelModel(Object attribution) {
            this.attribution = attribution;
        }
    }

    /** An item of the Reels collection holding a reel model, as the Reels tab's items do. */
    public static final class ReelItem {
        final Object model;

        public ReelItem(Object model) {
            this.model = model;
        }
    }

    /** A reel model whose GenAI attribution carries the detected flag set to [flagged]. */
    public static ReelModel reelModel(boolean flagged) {
        return new ReelModel(new TreeJNI(GenAiReelFilter.ATTRIBUTION_TYPE).holding(GenAiReelFilter.DETECTED_FLAG, flagged));
    }

    /** Whether the reel page filter, with the patch's finder standing in, takes [item] out of a page. */
    public static boolean hidesAiReel(Object item) {
        Collection<?> kept = GenAiReelFilter.withoutAiReels(Arrays.asList(new Object(), item), ReelModel.class.getName(),
                model -> ((ReelModel) model).attribution, GenAiLabel.PATCHED);
        return !kept.contains(item);
    }

    /** The reel section filter with the patch's finder standing in, over [page]. */
    public static List<?> aiReelSections(List<?> page) {
        return GenAiReelFilter.withoutAiSections(page, ReelModel.class.getName(),
                model -> ((ReelModel) model).attribution, GenAiLabel.PATCHED);
    }

    public static boolean hides(Object category, Object feedUnit) {
        return FeedFilter.hideEdge(category, feedUnit, true, true);
    }

    /**
     * Whether the bottom of Home says why it ends after the feed guard hid enough suggestions in a
     * row. Starts from a fresh start and leaves one behind.
     */
    public static boolean endOfHomeSaysWhy() {
        FeedEndHint.resetForTests();
        ShadowToast.reset();
        try {
            for (int i = 0; i < FeedEndHint.SHOWN_AFTER; i++) FeedEndHint.hid(HiddenTally.Kind.SUGGESTED);
            FeedEndHint.endVisible(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            return ShadowToast.getTextOfLatestToast() != null;
        } finally {
            FeedEndHint.resetForTests();
        }
    }

    /** The swap guard with both feed patches in: whether an edge swapped in over another stays out. */
    public static boolean swapHides(Object category, Object feedUnit) {
        return FeedFilter.hideSwappedEdge(category, feedUnit, true, true);
    }

    /**
     * The guard with the GenAI patch in as well. A test JVM has no patched accessor either, so any
     * story's GenAI info is {@code detectedInfo}, and no story has a recommendation context.
     */
    public static boolean hides(Object category, Object feedUnit, Object detectedInfo) {
        return FeedFilter.hideEdge(category, feedUnit, true, true, story -> null, true, story -> detectedInfo);
    }

    /**
     * The guard with the GenAI patch in, where any story's detected-AI info is {@code detectedInfo}
     * and its creator AI label info is {@code selfDisclosureInfo}.
     */
    public static boolean hidesLabelled(Object category, Object feedUnit, Object detectedInfo, Object selfDisclosureInfo) {
        return FeedFilter.hideEdge(category, feedUnit, true, true, story -> null, true, story -> detectedInfo, false,
                ShowcaseType.PATCHED, false, PostText.MESSAGE, PostText.ATTACHED, story -> selfDisclosureInfo);
    }

    /** The guard with both feed patches in, where any story's recommendation context is {@code context}. */
    public static boolean hidesRecommended(Object category, Object feedUnit, Object context) {
        return FeedFilter.hideEdge(category, feedUnit, true, true, story -> context, false, GenAiLabel.PATCHED);
    }

    /** The guard with the reels patch in, and none of the story flag rules. */
    public static boolean hidesReels(Object category, Object feedUnit) {
        return FeedFilter.hideEdge(category, feedUnit, true, true, story -> null, false, GenAiLabel.PATCHED, true);
    }

    /**
     * The guard with the reels patch in, where the showcase stub answers [storyType] for a unit
     * whose type name is ShowcaseFeedUnit.
     */
    public static boolean hidesShowcaseReels(Object category, Object storyType) {
        return FeedFilter.hideEdge(category, new TypedFeedUnit(ShowcaseType.UNIT_TYPE), true, true, story -> null,
                false, GenAiLabel.PATCHED, true, unit -> storyType);
    }

    /**
     * A row of Stories between posts, as Facebook's Discover unit sees it: a model answering
     * DiscoverFeedUnit whose is_unconnected_mbsu is [unconnected], true for "Stories you might like".
     */
    public static BaseModelWithTree storiesRow(boolean unconnected) {
        return new BaseModelWithTree(FeedFilter.DISCOVER_UNIT_TYPE) { }.with(FeedFilter.UNCONNECTED_STORIES_FLAG, unconnected);
    }

    /**
     * The guard with the word filter's patch in, and none of the story flag rules, where any story's
     * message is {@code message} and no story shares another. The hide list is whatever the test saved.
     */
    public static boolean hidesByWords(Object category, Object feedUnit, Object message) {
        return FeedFilter.hideEdge(category, feedUnit, true, true, story -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, true, story -> message, story -> null);
    }

    /** Stands in for Facebook's attachment style enum, whose constants keep their names. */
    public enum AttachmentStyle { PHOTO }

    /**
     * The guard for a story with one attachment drawn as a photo, through stand-ins for the stubs
     * Hide posts by words fills for its kinds of post.
     */
    public static boolean hidesPhotoPost(Object category) {
        GraphQLStory story = new GraphQLStory(null, new GraphQLStoryAttachment(new GraphQLMedia("Photo")));
        PostTypes.Readers readers = new PostTypes.Readers(unit -> ((GraphQLStory) unit).A0n(),
                attachment -> Collections.singletonList(AttachmentStyle.PHOTO), unit -> null);
        return FeedFilter.hideEdge(category, story, true, true, unit -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, true, unit -> null, unit -> null, unit -> null, null, null, readers);
    }

    /**
     * The guard for a story whose feedback says 5,000 reactions, through stand-ins for the stubs the
     * reaction ceiling reads it with.
     */
    public static boolean hidesPopularPost(Object category) {
        BaseModelWithTree reactors = new BaseModelWithTree(0);
        reactors.number("count", 5_000);
        GraphQLStory story = new GraphQLStory();
        com.facebook.graphql.model.GraphQLFeedback feedback = new com.facebook.graphql.model.GraphQLFeedback();
        PostTypes.Readers readers = new PostTypes.Readers(unit -> null, unit -> null, unit -> null,
                unit -> feedback, unit -> reactors);
        return FeedFilter.hideEdge(category, story, true, true, unit -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, true, unit -> null, unit -> null, unit -> null, null, null, readers);
    }

    /**
     * The guard for a story that shares another story, through a stand-in for the attached story
     * stub Hide posts by words fills. Neither story has a message.
     */
    public static boolean hidesRepost(Object category) {
        GraphQLStory share = new GraphQLStory(new GraphQLStory());
        return FeedFilter.hideEdge(category, share, true, true, unit -> null, false, GenAiLabel.PATCHED, false,
                ShowcaseType.PATCHED, true, unit -> null, unit -> ((GraphQLStory) unit).A04());
    }

    /** Stands in for Facebook's group join state enum, whose constants keep their names. */
    public enum JoinState {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE, CANNOT_JOIN_OR_REQUEST, CAN_JOIN, CAN_REQUEST, MEMBER, REQUESTED
    }

    /** A story's target that answers Group, as the {@code to} of a post in a group does. */
    public static BaseModelWithTree group() {
        return new BaseModelWithTree(GroupPosts.GROUP_TYPE) { };
    }

    /**
     * The guard for a story posted to [target] whose join state is [state], through stand-ins for
     * the two stubs Hide suggested and promoted posts fills for Hide posts from groups you're not in.
     */
    public static boolean hidesGroupPost(Object category, Object target, Object state) {
        GroupPosts.Readers readers = new GroupPosts.Readers(story -> target, profile -> state);
        return FeedFilter.hideEdge(category, new GraphQLStory(), true, true, unit -> null, false, GenAiLabel.PATCHED,
                false, ShowcaseType.PATCHED, false, PostText.MESSAGE, PostText.ATTACHED, unit -> null, null, null, null,
                readers);
    }

    /** A story's message, of the type Facebook's posts carry, holding [text]. */
    public static BaseModelWithTree postText(String text) {
        return new BaseModelWithTree(PostText.TEXT_TYPE_TAG).with(PostText.TEXT_FIELD, text);
    }

    /** GenAI info of the type Facebook's detection writes, with its flag set to [flagged]. */
    public static BaseModelWithTree detectedInfo(boolean flagged) {
        return new BaseModelWithTree(GenAiLabel.DETECTED_INFO_TYPE_TAG).with(GenAiLabel.DETECTED_FLAG, flagged);
    }

    /** A creator AI label info of the type Facebook writes, with its flag set to [labelled]. */
    public static BaseModelWithTree selfDisclosureInfo(boolean labelled) {
        return new BaseModelWithTree(GenAiLabel.SELF_DISCLOSURE_INFO_TYPE_TAG).with(GenAiLabel.SELF_DISCLOSED_FLAG, labelled);
    }

    /** A story's recommendation context, with Facebook's recommendation flag set to [recommended]. */
    public static BaseModelWithTree recommendationContext(boolean recommended) {
        return new BaseModelWithTree(RecommendationLabel.FLAG.modelTypeTag)
                .with(RecommendationLabel.RECOMMENDED_FLAG, recommended);
    }
}
