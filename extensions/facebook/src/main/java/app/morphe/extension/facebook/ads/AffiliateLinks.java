/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The product cards of the shop links a creator attaches to a post to earn a commission. Facebook
 * shows the card in three places, and each place asks its own question first: the reel overlay
 * asks whether a reel gets its product card, the feed asks for the id of the server-built footer
 * under a post's attachment, and the comment sheet asks for the model of the card that floats over
 * the comment box. With the switch on, the first answers no and the other two answer null, which is
 * what Facebook gets for a post with no shop link.
 *
 * <p>The reel overlay has other shop cards beside the affiliate one: products tagged on the reel, the
 * creator's storefront and Shop similar, each a "Shop now" card above the creator's name. They are
 * kinds of the overlay's call-to-action list, and with the switch on the list comes back without
 * them.
 *
 * <p>The "Commission eligible" label under a reel's creator has a switch of its own, off by default
 * (#329). The label's row and the check for whether a reel has that row each read the same flag on
 * the reel, and {@link #keepCommissionLabel} answers both.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class AffiliateLinks {
    /** Counted under the patch's name for each card answered away, one name per place. */
    static final String REEL_CARD = "Reel product card kept out";
    static final String FEED_CARD = "Feed product card kept out";
    static final String COMMENT_CARD = "Comment sheet product card kept out";
    static final String COMMISSION_LABEL = "Commission eligible label kept out";
    /**
     * Counted with the kind's name after it, so a report says which shop card a reel had. The name is
     * one of {@link #SHOP_CTAS}, so the count's text stays fixed.
     */
    static final String REEL_SHOP_CARD = "Reel shop card kept out: ";

    /**
     * The reel overlay's call-to-action kinds that are shop cards, by their enum names, which Facebook
     * keeps since its server sends them. 581 builds AFFILIATE_BANNER without any code naming it, so
     * it is here for a build that draws it.
     */
    static final Set<String> SHOP_CTAS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "AFFILIATE_BANNER", "AFFILIATE_EYEBROW", "PRODUCT_TAGGING", "PRODUCT_TAGGING_V2", "STOREFRONT",
            "SHOP_SIMILAR")));

    private static final String FAMILY = FamilyNames.AFFILIATE_LINKS;

    private static volatile boolean logged;

    private AffiliateLinks() {
    }

    /**
     * The hook right after Facebook reads a reel's "Commission eligible" flag, handed the flag as an
     * int. False while the label's switch is on, and the flag otherwise.
     */
    public static boolean keepCommissionLabel(int commissionEligible) {
        boolean shows = commissionEligible != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (!shows || !Utils.settingsReady() || !Settings.HIDE_COMMISSION_LABEL.get()) return shows;
            kept("commission label", COMMISSION_LABEL);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "commission label", failure);
            return shows;
        }
    }

    /**
     * The entry the patch calls, handed the predicate's answer as an int: a boolean method may
     * return a register the verifier types as int, and a boolean parameter wouldn't take it.
     */
    public static boolean keepReelCard(int showsCard) {
        return keepReelCard(showsCard != 0);
    }

    /**
     * The hook at each return of the reel overlay's product card predicate, handed what it was about
     * to answer. False for a reel that would get the card while the switch is on, and Facebook's
     * answer otherwise.
     */
    public static boolean keepReelCard(boolean showsCard) {
        try {
            HookStatus.invoked(FAMILY);
            if (!showsCard || !hiding()) return showsCard;
            kept("reel card check", REEL_CARD);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel card check", failure);
            return showsCard;
        }
    }

    /**
     * The hook at each return of the feed's footer id lookup, handed the id it was about to answer.
     * Null, the answer for an attachment with no server-built footer, while the switch is on.
     */
    @Nullable
    public static String keepFooter(@Nullable String footerId) {
        try {
            HookStatus.invoked(FAMILY);
            if (footerId == null || !hiding()) return footerId;
            kept("feed footer id", FEED_CARD);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed footer id", failure);
            return footerId;
        }
    }

    /**
     * The hook at each return of the comment sheet's floating card model reader, handed the model it
     * was about to answer. Null, the answer for a post with no shop link, while the switch is on.
     */
    @Nullable
    public static Object keepCommentCard(@Nullable Object card) {
        try {
            HookStatus.invoked(FAMILY);
            if (card == null || !hiding()) return card;
            kept("comment card model", COMMENT_CARD);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment card model", failure);
            return card;
        }
    }

    /**
     * The entry the patch calls where the floating card plugin asks a yes-or-no question instead of
     * reading the model (Facebook 585), handed the answer as an int for the same reason as
     * {@link #keepReelCard(int)}.
     */
    public static boolean keepCommentCard(int showsCard) {
        return keepCommentCard(showsCard != 0);
    }

    /**
     * The hook at each return of the floating card plugin's check, handed what it was about to
     * answer. False for a post that would get the card while the switch is on, and Facebook's answer
     * otherwise.
     */
    public static boolean keepCommentCard(boolean showsCard) {
        try {
            HookStatus.invoked(FAMILY);
            if (!showsCard || !hiding()) return showsCard;
            kept("comment card check", COMMENT_CARD);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comment card check", failure);
            return showsCard;
        }
    }

    /**
     * The hook at each return of the reel overlay's filter of its call-to-action kinds, handed what it
     * was about to answer and the full list it was given. The filter answers null when Facebook leaves
     * no category out, and the overlay then keeps the full list. While the switch is on and the list
     * has a shop card, the answer is a new list without the shop cards; otherwise it is Facebook's.
     */
    @Nullable
    public static List<?> keepReelCtas(@Nullable List<?> filtered, @Nullable List<?> all) {
        try {
            HookStatus.invoked(FAMILY);
            List<?> shown = filtered != null ? filtered : all;
            if (shown == null || shown.isEmpty() || !hiding()) return filtered;
            List<Object> kept = new ArrayList<>(shown.size());
            List<String> dropped = new ArrayList<>(1);
            for (Object cta : shown) {
                if (cta instanceof Enum && SHOP_CTAS.contains(((Enum<?>) cta).name())) {
                    dropped.add(((Enum<?>) cta).name());
                } else {
                    kept.add(cta);
                }
            }
            if (dropped.isEmpty()) return filtered;
            for (String kind : dropped) kept("reel CTA list", REEL_SHOP_CARD + kind);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel CTA list", failure);
            return filtered;
        }
    }

    private static boolean hiding() {
        return Utils.settingsReady() && Settings.HIDE_AFFILIATE_LINKS.get();
    }

    private static void kept(String anchor, String count) {
        HookStatus.bound(FAMILY, anchor);
        HookStatus.counted(FAMILY, count);
        if (!logged) {
            logged = true;
            Logger.printDebug(() -> "Affiliate links: a product card was kept out");
        }
    }
}
