/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Whether a feed post was posted in a group the person isn't in (issue #195). Home shows a friend's
 * post in a public group with a Join button next to the group's name, and it isn't a suggestion:
 * no recommendation flag is set and the edge comes as an ordinary story.
 *
 * <p>A story's {@code to} is the profile it was posted to, typed Profile, which answers
 * {@code getTypeName()} with "Group" for a post in a group. Facebook's own code reads it that way.
 * The group's {@code viewer_join_state} is the enum Facebook's Join button reads: it shows the
 * button for CAN_JOIN and CAN_REQUEST, the two {@link #NOT_JOINED} names. MEMBER, REQUESTED,
 * CANNOT_JOIN_OR_REQUEST and the unset default all keep the post, and so does a story whose state
 * Home didn't fetch, which reads as the default.
 *
 * <p>A group post a friend commented on or shared reaches Home wrapped in the friend's activity
 * ("Tasha replied to a comment."), a story posted to no profile of its own: the group, and the
 * Join button Facebook draws, are on the post it wraps ({@code attached_story}), so that post's
 * group decides, through the accessor Hide posts with words fills. A build without that patch
 * can't read the wrapped post and keeps it. The second report on #195 (2026-10-29) showed one of
 * those getting through while every post posted straight to a joinable group was hidden.
 *
 * <p>A story can also name its group as {@code target_group}, read as the same model class as
 * {@code to}. The fourth report on #195 (2026-10-30) showed group posts whose {@code to} was empty
 * and whose title, share and attachments named no group either, so when {@code to} is empty or
 * isn't a group, that group decides, and so does the wrapped post's. A build whose story declares
 * no {@code target_group} reads {@code to} alone.
 *
 * <p>A story posted to no profile that names no group either way, itself or in the post it wraps,
 * can still name one in its title, next to the poster's name, or point an attachment at one. The first
 * group the title's ranges point at decides, and when the title names none, the first attachment
 * target that's a group does. A title's entities are a model that answers its type name and
 * nothing more, so a group among them is read again as the class {@link #joinState} takes, under
 * the Group type tag, the way Facebook's own TreeConverter turns one model into another
 * ({@link #asGroup}). Whether Home fetches the join state on a title's group isn't proven: one it
 * didn't fetch reads as the default and keeps the post. A friend's own post sharing a group reads
 * as that group too.
 *
 * <p>The story's accessors of {@code to} and {@code target_group} and the profile's accessor of
 * its join state are Redex names, so the patch fills in {@link #storyTarget},
 * {@link #storyTargetGroup} and {@link #joinState}. The enum's constant names survive the
 * obfuscator. What the report counts is a shape, never the group.
 *
 * <p>Home asks the server for the join state only while a MobileConfig answer says so, and an
 * account without it reads the unset default on every group post. The patch hands that answer to
 * {@link #askForJoinState}, which says yes while the switch is on.
 */
public final class GroupPosts {
    /** The route the report counts each story read for its group on, while the switch is on. */
    static final String ROUTE = "Groups you're not in";
    /** The route Hide all group posts counts each story read on, while its switch is on. */
    static final String ALL_ROUTE = "Group posts";

    /** The type name a story's target answers when the post was posted in a group. */
    static final String GROUP_TYPE = "Group";
    /** The GraphQL fields read, for the report. */
    static final String TARGET_FIELD = "to";
    static final String TARGET_GROUP_FIELD = "target_group";
    static final String JOIN_STATE_FIELD = "viewer_join_state";

    /** The join states Facebook shows its Join button for, the only two that hide a post. */
    static final String[] NOT_JOINED = {"CAN_JOIN", "CAN_REQUEST"};

    /** What {@link #read} answers when there's no state to read, each its own shape for the report. */
    static final String NOT_A_STORY = "not a story";
    static final String NOT_PATCHED = "accessor not patched";
    static final String NO_TARGET = "not posted to a profile";
    static final String RELEASED = "tree released";
    static final String NOT_A_GROUP = "not posted in a group";
    static final String NO_STATE = "no join state";
    static final String NOT_AN_ENUM = "join state not an enum";
    static final String READ_FAILED = "read failed";
    /** The read went through the post the story wraps: what it found, under this, for the report. */
    static final String WRAPPED = "wrapped ";
    /** The read went through the story's {@code target_group}: what it found, under this (after {@link #WRAPPED}). */
    static final String TARGET_GROUP = TARGET_GROUP_FIELD + " ";
    /** A story posted to no profile whose wrapped post can't be read: Hide posts with words isn't in the build. */
    static final String NO_WRAPPED_READER = "wrapped post not readable";
    /** The read went through the group the story's title names: what it found, under this (after {@link #WRAPPED}). */
    static final String TITLE_GROUP = "title ";
    /** The read went through a group an attachment of the story points at: what it found, under this (after {@link #WRAPPED}). */
    static final String ATTACHMENT_GROUP = "attachment target ";

    /** How Facebook's tree models tag a Group: the first four bytes of the MD5 of its name, big-endian. */
    static final int GROUP_TYPE_TAG = 0x03937134;
    /** The tree's kept method that reads the same tree as another model class, under another type tag. */
    static final String REINTERPRET = "reinterpret";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    /** Counted under the patch's name each time Home is asked for join states Facebook wouldn't ask for. */
    static final String ASKED = "Home asked for groups' join states";

    private static final String QUERY_HOOK = "group join state query";

    /**
     * The accessors a read goes through: the three stubs the patch filled, and the story's
     * {@code attached_story} that Hide posts with words fills, or stand-ins in a test.
     */
    static final class Readers {
        final StoryFlag.Accessor target;
        final StoryFlag.Accessor targetGroup;
        final StoryFlag.Accessor joinState;
        final StoryFlag.Accessor attached;
        /**
         * What a story posted to no profile names a group through ({@link #namedGroup}), and what
         * Debug logging reads its shape through ({@link #describe}).
         */
        final ShapeReaders shape;

        Readers(StoryFlag.Accessor target, StoryFlag.Accessor joinState) {
            this(target, joinState, PostText.ATTACHED);
        }

        Readers(StoryFlag.Accessor target, StoryFlag.Accessor joinState, StoryFlag.Accessor attached) {
            this(target, joinState, attached, SHAPE_PATCHED);
        }

        Readers(StoryFlag.Accessor target, StoryFlag.Accessor joinState, StoryFlag.Accessor attached,
                ShapeReaders shape) {
            this(target, GroupPosts::storyTargetGroup, joinState, attached, shape);
        }

        Readers(StoryFlag.Accessor target, StoryFlag.Accessor targetGroup, StoryFlag.Accessor joinState,
                StoryFlag.Accessor attached, ShapeReaders shape) {
            this.target = target;
            this.targetGroup = targetGroup;
            this.joinState = joinState;
            this.attached = attached;
            this.shape = shape;
        }
    }

    // Before PATCHED, which takes it as its default: static fields are set in the order they're written.
    static final ShapeReaders SHAPE_PATCHED = new ShapeReaders(GroupPosts::storyTitle, GroupPosts::storyShareable,
            GroupPosts::titleRanges, GroupPosts::rangeEntity, PostSources.ATTACHMENTS, GroupPosts::attachmentTarget,
            GroupPosts::joinState, GroupPosts::storyTargetGroup);

    static final Readers PATCHED = new Readers(GroupPosts::storyTarget, GroupPosts::joinState);

    private GroupPosts() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code to}, or null when it has none.
     * Only a GraphQLStory may be passed.
     */
    public static Object storyTarget(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, filled in by the patch on a build whose story declares it: the story's
     * {@code target_group}, of the same class as its {@code to}, or null when it has none. Only a
     * GraphQLStory may be passed.
     */
    public static Object storyTargetGroup(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, filled in by the patch: the profile's {@code viewer_join_state} enum, or
     * Facebook's unset constant when it has none. Only a story's {@code to} or {@code target_group}
     * may be passed.
     */
    public static Object joinState(Object profile) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection points for Debug logging's {@link #shape}, filled in by the patch. The story's
     * {@code title} (a GraphQLTextWithEntities), and its {@code shareable}; only a GraphQLStory may
     * be passed to either.
     */
    public static Object storyTitle(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    public static Object storyShareable(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /** The title's {@code ranges}, a list of models. Only what {@link #storyTitle} answers may be passed. */
    public static Object titleRanges(Object title) {
        return StoryFlag.NOT_PATCHED;
    }

    /** A range's {@code entity}. Only an element of what {@link #titleRanges} answers may be passed. */
    public static Object rangeEntity(Object range) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * An attachment's {@code target}, of the same class as a story's {@code to}, so a target that
     * is a group can be handed to {@link #joinState}. Only a GraphQLStoryAttachment may be passed.
     */
    public static Object attachmentTarget(Object attachment) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, filled in by the patch on a build whose tree keeps {@code reinterpret}: the
     * class a story's {@code to} is read as, the one {@link #joinState} casts to. The argument is
     * never read.
     */
    public static Object storyTargetClass(Object unused) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * A title entity that answers Group, read again as the class {@link #joinState} takes through
     * {@link #storyTargetClass}, or {@link StoryFlag#NOT_PATCHED} on a build where the patch couldn't
     * hand that class over. Throws what {@link #asGroup(Object, Object)} throws.
     */
    @Nullable
    static Object asGroup(@Nullable Object entity) {
        return asGroup(entity, storyTargetClass(null));
    }

    /**
     * [entity] read as [modelClass] under {@link #GROUP_TYPE_TAG}, through the tree's public
     * {@code reinterpret(Class, int)}, the call Facebook's TreeConverter makes to turn a model into
     * another class. [entity] itself when it already is one, null for none or one whose tree is gone
     * (reinterpret is native, and a released tree there is beyond any try block), and
     * {@link StoryFlag#NOT_PATCHED} when [modelClass] isn't a class. Throws when the call fails.
     */
    @Nullable
    static Object asGroup(@Nullable Object entity, @Nullable Object modelClass) {
        if (!(modelClass instanceof Class)) return StoryFlag.NOT_PATCHED;
        if (entity == null || FeedFilter.released(entity)) return null;
        if (((Class<?>) modelClass).isInstance(entity)) return entity;
        try {
            return entity.getClass().getMethod(REINTERPRET, Class.class, int.class)
                    .invoke(entity, modelClass, GROUP_TYPE_TAG);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("could not read a title's group as a profile", failure);
        }
    }

    /** The accessors {@link #shape} reads through: the stubs above, or stand-ins in a test. */
    static final class ShapeReaders {
        final StoryFlag.Accessor title;
        final StoryFlag.Accessor shareable;
        final StoryFlag.Accessor ranges;
        final StoryFlag.Accessor entity;
        final StoryFlag.Accessor attachments;
        final StoryFlag.Accessor target;
        final StoryFlag.Accessor joinState;
        final StoryFlag.Accessor targetGroup;
        /** A title entity that answers Group, read as a model {@link #joinState} takes ({@link #asGroup}). */
        final StoryFlag.Accessor group;

        ShapeReaders(StoryFlag.Accessor title, StoryFlag.Accessor shareable, StoryFlag.Accessor ranges,
                StoryFlag.Accessor entity, StoryFlag.Accessor attachments, StoryFlag.Accessor target,
                StoryFlag.Accessor joinState, StoryFlag.Accessor targetGroup) {
            this(title, shareable, ranges, entity, attachments, target, joinState, targetGroup, GroupPosts::asGroup);
        }

        ShapeReaders(StoryFlag.Accessor title, StoryFlag.Accessor shareable, StoryFlag.Accessor ranges,
                StoryFlag.Accessor entity, StoryFlag.Accessor attachments, StoryFlag.Accessor target,
                StoryFlag.Accessor joinState, StoryFlag.Accessor targetGroup, StoryFlag.Accessor group) {
            this.title = title;
            this.shareable = shareable;
            this.ranges = ranges;
            this.entity = entity;
            this.attachments = attachments;
            this.target = target;
            this.joinState = joinState;
            this.targetGroup = targetGroup;
            this.group = group;
        }
    }

    /** How many of a list's models {@link #shape} names, so a long title doesn't flood the log. */
    static final int SHAPE_LIMIT = 4;
    /** What {@link #shape} writes for a piece whose accessor the patch didn't fill, and for none. */
    static final String SHAPE_NOT_PATCHED = "?";
    static final String SHAPE_NONE = "-";

    /**
     * For Debug logging only: what a story posted to no profile carries that could name a group, as
     * GraphQL type names and nothing else, never its text or an id. Its {@code target_group} with
     * its join state, the entities its title's ranges point at, its {@code shareable}, and each
     * attachment's target, a target that's a group with its join state:
     * {@code target_group:-;title:User+Group;shareable:-;attachments:Group/CAN_JOIN}. The third
     * report on #195 (2026-10-29) kept three group posts whose {@code to} and wrapped post were both
     * empty. Never throws.
     */
    static String shape(@Nullable Object story, ShapeReaders readers) {
        try {
            if (story == null || FeedFilter.released(story)) return RELEASED;
            Object group = readers.targetGroup.model(story);
            String named = group == StoryFlag.NOT_PATCHED ? SHAPE_NOT_PATCHED : typeAndState(group, readers.joinState);
            Object title = readers.title.model(story);
            String titles;
            if (title == StoryFlag.NOT_PATCHED) {
                titles = SHAPE_NOT_PATCHED;
            } else if (title == null || FeedFilter.released(title)) {
                titles = SHAPE_NONE;
            } else {
                titles = typesOf(readers.ranges.model(title), readers.entity, null);
            }
            Object shareable = readers.shareable.model(story);
            String shared = shareable == StoryFlag.NOT_PATCHED ? SHAPE_NOT_PATCHED : typeOf(shareable);
            String attached = typesOf(readers.attachments.model(story), readers.target, readers.joinState);
            return "target_group:" + named + ";title:" + titles + ";shareable:" + shared + ";attachments:" + attached;
        } catch (Throwable failure) {
            return READ_FAILED;
        }
    }

    /**
     * The type names of what [via] answers for each of [list]'s first models, joined with "+", a
     * group's join state after a "/" when [joinState] is given.
     */
    private static String typesOf(@Nullable Object list, StoryFlag.Accessor via,
            @Nullable StoryFlag.Accessor joinState) {
        if (list == StoryFlag.NOT_PATCHED) return SHAPE_NOT_PATCHED;
        if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty()) return SHAPE_NONE;
        StringBuilder names = new StringBuilder();
        int seen = 0;
        for (Object item : (java.util.List<?>) list) {
            if (seen++ == SHAPE_LIMIT) {
                names.append("+more");
                break;
            }
            if (names.length() > 0) names.append('+');
            if (item == null || FeedFilter.released(item)) {
                names.append(SHAPE_NONE);
                continue;
            }
            Object model = via.model(item);
            names.append(model == StoryFlag.NOT_PATCHED ? SHAPE_NOT_PATCHED : typeAndState(model, joinState));
        }
        return names.toString();
    }

    /** {@link #typeOf} the model, and after a "/" the join state of one that's a group, when [joinState] is given. */
    private static String typeAndState(@Nullable Object model, @Nullable StoryFlag.Accessor joinState) {
        String type = typeOf(model);
        if (joinState != null && GROUP_TYPE.equals(type)) {
            Object state = joinState.model(model);
            if (state instanceof Enum) return type + "/" + ((Enum<?>) state).name();
        }
        return type;
    }

    /** A model's GraphQL type name, or {@link #SHAPE_NONE} for none or one whose tree is gone. */
    private static String typeOf(@Nullable Object model) {
        if (model == null || FeedFilter.released(model)) return SHAPE_NONE;
        String type = FeedFilter.typeName(model);
        return type == null ? SHAPE_NONE : type;
    }

    /**
     * What Debug logging's feed edge line says for the group rule: what {@link #read} found, and for a
     * story posted to no profile also its {@link #shape}. Not a read of the rule: nothing is counted.
     */
    static String describe(@Nullable Object feedUnit, Readers readers) {
        String found = read(feedUnit, readers);
        String state = found.startsWith(WRAPPED) ? found.substring(WRAPPED.length()) : found;
        boolean named = state.startsWith(TITLE_GROUP) || state.startsWith(ATTACHMENT_GROUP);
        return NO_TARGET.equals(found) || named ? found + " shape=" + shape(feedUnit, readers.shape) : found;
    }

    /**
     * Injection point, in Home's query builder, right before it hands over its answer for
     * {@code should_include_viewer_join_state}. Never throws.
     *
     * @param facebooks whether Facebook would ask for each group's join state.
     * @return whether to ask for it.
     */
    public static boolean askForJoinState(boolean facebooks) {
        try {
            HookStatus.invoked(FAMILY);
            if (facebooks || !Utils.settingsReady() || !Settings.HIDE_GROUP_POSTS_NOT_JOINED.get()) {
                Logger.printDebug(() -> "Group posts: kept Facebook's " + facebooks + " for join states");
                return facebooks;
            }
            HookStatus.bound(FAMILY, QUERY_HOOK);
            HookStatus.counted(FAMILY, ASKED);
            Logger.printDebug(() -> "Group posts: asked Home for join states");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, QUERY_HOOK, failure);
            Logger.printException(() -> "Group posts: could not read the switch for join states", failure);
            return facebooks;
        }
    }

    /**
     * The join state's constant name for a post in a group, or why there is none. A story whose
     * {@code to} is empty or isn't a group is read through its {@code target_group}, under
     * {@link #TARGET_GROUP}, and a story posted to no profile that names no group that way is read
     * again through the post it wraps, the same way, under {@link #WRAPPED}. When neither names a
     * group, the group the story's title or an attachment names decides ({@link #namedGroup}), the
     * story's own before the wrapped post's. Never throws: an accessor that throws is recorded in
     * Hook status and the post reads as {@link #READ_FAILED}, which keeps it.
     */
    static String read(@Nullable Object feedUnit, Readers readers) {
        Class<?> story = StoryFlag.members().story;
        if (feedUnit == null || story == null || !story.isInstance(feedUnit)) return NOT_A_STORY;
        if (FeedFilter.released(feedUnit)) return RELEASED;
        try {
            Object target = readers.target.model(feedUnit);
            if (target == StoryFlag.NOT_PATCHED) {
                HookStatus.missingMember(FAMILY, "method", StoryFlag.STORY_CLASS, "the " + TARGET_FIELD + " accessor");
                return NOT_PATCHED;
            }
            String found = stateOf(feedUnit, target, readers, "");
            if (found != null) return found;
            // Posted to no profile and naming no group: a post a friend commented on or shared wraps the group's post.
            Object wrapped = readers.attached.model(feedUnit);
            if (wrapped == StoryFlag.NOT_PATCHED) return orElse(namedGroup(feedUnit, readers, ""), NO_WRAPPED_READER);
            if (wrapped == null || !story.isInstance(wrapped)) return orElse(namedGroup(feedUnit, readers, ""), NO_TARGET);
            if (FeedFilter.released(wrapped)) return RELEASED;
            Object wrappedTarget = readers.target.model(wrapped);
            String wrappedFound = stateOf(wrapped, wrappedTarget == StoryFlag.NOT_PATCHED ? null : wrappedTarget, readers, WRAPPED);
            if (wrappedFound != null) return wrappedFound;
            String named = namedGroup(feedUnit, readers, "");
            return orElse(named != null ? named : namedGroup(wrapped, readers, WRAPPED), NO_TARGET);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "group join state reader", failure);
            return READ_FAILED;
        }
    }

    private static String orElse(@Nullable String found, String otherwise) {
        return found != null ? found : otherwise;
    }

    /**
     * What the group [story]'s title or attachments name reads as, under [prefix], for a story
     * posted to no profile that names no {@code target_group}. The first title range pointing at a
     * Group decides, read as a profile through [readers]' shape {@code group}; a title group that
     * can't be read that way keeps the post rather than fall back to an attachment, which could be
     * a share of some other group. A title naming no group hands over to the first attachment whose
     * target is a Group, read as it is. Null when neither names a group, or the readers aren't
     * filled in. Throws what an accessor throws.
     */
    @Nullable
    private static String namedGroup(Object story, Readers readers, String prefix) {
        ShapeReaders shape = readers.shape;
        Object title = shape.title.model(story);
        if (title != null && title != StoryFlag.NOT_PATCHED && !FeedFilter.released(title)) {
            Object entity = firstGroup(shape.ranges.model(title), shape.entity);
            if (entity != null) {
                Object group = shape.group.model(entity);
                if (group == StoryFlag.NOT_PATCHED) return null;
                return group == null ? prefix + TITLE_GROUP + NO_STATE : stateOf(group, readers, prefix + TITLE_GROUP);
            }
        }
        Object target = firstGroup(shape.attachments.model(story), shape.target);
        return target == null ? null : stateOf(target, readers, prefix + ATTACHMENT_GROUP);
    }

    /** The first model [via] answers for one of [list]'s items that answers Group, or null. */
    @Nullable
    private static Object firstGroup(@Nullable Object list, StoryFlag.Accessor via) {
        if (!(list instanceof java.util.List)) return null;
        for (Object item : (java.util.List<?>) list) {
            if (item == null || FeedFilter.released(item)) continue;
            Object model = via.model(item);
            if (model != null && model != StoryFlag.NOT_PATCHED && GROUP_TYPE.equals(FeedFilter.typeName(model))) {
                return model;
            }
        }
        return null;
    }

    /**
     * What [story]'s group's join state reads as, under [prefix]: through its [target] when that's
     * a group, else through its {@code target_group} when it names one, else through [target] when
     * there is one. Null when the story has neither. Throws what an accessor throws.
     */
    @Nullable
    private static String stateOf(Object story, @Nullable Object target, Readers readers, String prefix) {
        String byTarget = target == null ? null : stateOf(target, readers, prefix);
        if (byTarget != null && !byTarget.equals(prefix + NOT_A_GROUP)) return byTarget;
        Object group = readers.targetGroup.model(story);
        if (group == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FAMILY, "method", StoryFlag.STORY_CLASS, "the " + TARGET_GROUP_FIELD + " accessor");
            return byTarget;
        }
        return group == null ? byTarget : stateOf(group, readers, prefix + TARGET_GROUP);
    }

    /**
     * The join state's constant name of [target], the profile a story was posted to, or why there
     * is none, each under [prefix] for the report. Throws what an accessor throws.
     */
    private static String stateOf(Object target, Readers readers, String prefix) {
        if (FeedFilter.released(target)) return RELEASED;
        if (!GROUP_TYPE.equals(FeedFilter.typeName(target))) return prefix + NOT_A_GROUP;
        Object state = readers.joinState.model(target);
        if (state == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FAMILY, "method", GROUP_TYPE, "the " + JOIN_STATE_FIELD + " accessor");
            return NOT_PATCHED;
        }
        if (state == null) return prefix + NO_STATE;
        if (!(state instanceof Enum)) return prefix + NOT_AN_ENUM;
        HookStatus.bound(FAMILY, GROUP_TYPE + "#" + JOIN_STATE_FIELD);
        return prefix + ((Enum<?>) state).name();
    }

    /**
     * Whether what a read found is a group the person could join, so isn't in, the post posted to
     * it straight, naming it as its {@code target_group}, in its title or through an attachment, or
     * wrapped in a friend's activity.
     */
    static boolean notJoined(@Nullable String kind) {
        if (kind == null) return false;
        String state = state(kind);
        for (String joinable : NOT_JOINED) {
            if (joinable.equals(state)) return true;
        }
        return false;
    }

    /**
     * Whether what a read found is a group, whatever its join state, for Hide all group posts
     * (#409): a join state, or a group that has none or whose state isn't an enum. A read that found
     * no group, or couldn't read, keeps the post.
     */
    static boolean inAGroup(@Nullable String kind) {
        if (kind == null) return false;
        String state = state(kind);
        if (state.isEmpty()) return false;
        for (String none : NO_GROUP) {
            // "wrapped post not readable" starts with the wrapped prefix, so check the whole kind too.
            if (none.equals(kind) || none.equals(state)) return false;
        }
        return true;
    }

    /** What {@link #read} answers when it found no group, or couldn't tell. */
    private static final String[] NO_GROUP = {NOT_A_STORY, NOT_PATCHED, RELEASED, NO_TARGET, NOT_A_GROUP,
            READ_FAILED, NO_WRAPPED_READER};

    /** [kind] without the route it was read through: the wrapped post's, then the target group's, title's or attachment's. */
    private static String state(String kind) {
        String state = kind.startsWith(WRAPPED) ? kind.substring(WRAPPED.length()) : kind;
        for (String route : new String[] {TARGET_GROUP, TITLE_GROUP, ATTACHMENT_GROUP}) {
            if (state.startsWith(route)) return state.substring(route.length());
        }
        return state;
    }
}
