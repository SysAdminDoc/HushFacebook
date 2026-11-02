/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Stand-ins for a Reels page read by follow status: the reel model, whose class the patch names,
 * Facebook's follow status enum, of which only the constant names matter, and the item that keeps
 * the model in one of its fields.
 */
public final class FollowedReelsForTests {
    private FollowedReelsForTests() {
    }

    /** Stands in for Facebook's follow status enum. */
    public enum FollowStatus { IS_SUBSCRIBED, CAN_SUBSCRIBE, CANNOT_SUBSCRIBE, UNSET_OR_UNRECOGNIZED_ENUM_VALUE }

    /** Stands in for the reel model. */
    public static final class Model {
        final FollowStatus status;

        public Model(FollowStatus status) {
            this.status = status;
        }
    }

    /** Stands in for a Reels item, which keeps its model in a field. */
    public static final class Item {
        final Object model;

        public Item(Object model) {
            this.model = model;
        }
    }

    /** The reel model's binary name, as the patch passes it. */
    public static final String MODEL = Model.class.getName();

    /** Reads a stand-in's status the way the stub the patch fills reads Facebook's. */
    static final FollowedReels.Statuses STATUSES = model -> ((Model) model).status;

    /** A reel whose author has [status]. */
    public static Item reel(FollowStatus status) {
        return new Item(new Model(status));
    }

    /** The page filter over [page], reading statuses through the stand-in. */
    public static Collection<?> followedOnly(Collection<?> page) {
        return FollowedReels.followedOnly(page, MODEL, STATUSES, FollowedReels.PAGES_ROUTE);
    }

    /** The section filter over [page], reading statuses through the stand-in. */
    public static List<?> followedSections(List<?> page) {
        return FollowedReels.followedSections(page, MODEL, STATUSES);
    }

    /**
     * Hands the filter a page with a followed author's reel and one whose author could be followed.
     * True when the second came off, which is the switch changing what Facebook would have done.
     */
    public static boolean dropsANotFollowedReel() {
        Item notFollowed = reel(FollowStatus.CAN_SUBSCRIBE);
        return !followedOnly(Arrays.asList(reel(FollowStatus.IS_SUBSCRIBED), notFollowed)).contains(notFollowed);
    }
}
