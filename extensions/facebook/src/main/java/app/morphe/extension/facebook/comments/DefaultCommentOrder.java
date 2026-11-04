/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.jvm.functions.Function1;

import app.morphe.extension.facebook.download.PostDetails;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The order a comment sheet asks Facebook for.
 *
 * <p>Every request for a post's comments is built from one params object, and the patch has its
 * constructor ask {@link #requestedOrder} first. An ordinary sheet asks for no order, and then
 * Facebook's servers choose one, usually Most relevant, and choose it again on every refresh and
 * every reopening, whatever was picked in the sheet's menu. While the switch is on and an order is
 * chosen, this fills in that order where Facebook would have chosen, and nowhere else:
 *
 * <ul>
 *   <li>a request that already names an order keeps it: a pick in the sheet's menu, a list that
 *       names its own, a request restored after Android closed Facebook;
 *   <li>a link to one comment, a notification about a reply for one, keeps Facebook's order, so
 *       the comment it leads to is where Facebook puts it;
 *   <li>a post whose order was picked in its sheet's menu since Facebook started gets that order
 *       again ({@link #picked} hears the pick first), until Facebook restarts.
 * </ul>
 *
 * <p>A post's own page, which a link to the post and many notifications open, gets its comments
 * with the story in one request of its own, and that request carried only the order the link
 * named (#192). The patch has the one builder of that request from a link ask {@link #pageOrder}
 * right before it makes the request's params, and the chosen order fills in there the same way.
 *
 * <p>Facebook's answer names the order it used, which is what the sheet's menu then shows. Its own
 * links to a post can name an order the same way.
 *
 * <p>All comments is the exception (#110). Asked for up front on a friend's post whose sheet has no
 * sort menu, Facebook's answer drew no comment at all, while Newest and Most relevant drew them.
 * So a request for All comments goes out with Facebook's own choice, and when the answer's sort
 * menu offers All comments, {@link #sortMenu} picks it there the way a tap on that row does, by
 * whichever token that menu names the row by ({@link CommentOrder#rowIn}). A post whose menu
 * doesn't offer it, or that has no menu, stays in Facebook's order.
 *
 * <p>It fails open: with the patch not in the build, the switch off, Hushfacebook paused, the
 * settings not ready yet, Facebook's order chosen, or a failure in here, the request is Facebook's
 * own.
 */
public final class DefaultCommentOrder {
    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "DefaultCommentOrder";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Default comment order: ";

    /** How long after a pick in the menu the request it sends may come. It comes at once. */
    static final long PICK_WINDOW_MS = 2000;

    /**
     * How long a pick in a sheet's menu waits after Facebook builds that menu. The menu is built
     * while Facebook is still taking in the sheet's first answer, and a pick in the middle of that
     * left the sheet's next page loading forever: a post opened with its first ten or so comments
     * and a spinner under them (#384). A tap by hand never lands that early.
     */
    static final long PICK_DELAY_MS = 1000;

    /** The posts whose picked order is kept, the most recently used ones. */
    static final int REMEMBERED_POSTS = 200;

    /** Requests logged one by one before the log only counts them. */
    static final int LOGGED_ONE_BY_ONE = 40;

    /** After those, one line per this many requests. */
    static final int SUMMED_UP_BY = 50;

    /** How long after a request was left for the menu its sheet may draw that menu. */
    static final long AWAIT_WINDOW_MS = 60_000;

    /** Counted each time a request for All comments goes out with Facebook's order, for its menu to offer. */
    static final String LEFT_FOR_MENU = "All comments left for the menu";

    /** Counted each time a sheet's menu offered All comments and it was picked there. */
    static final String PICKED_IN_MENU = "All comments picked in the menu";

    /** Counted each time Facebook's answer was already All comments. */
    static final String ANSWERED = "Answer already All comments";

    /** Counted each time a sheet's menu didn't offer All comments, so Facebook's order stayed. */
    static final String NOT_OFFERED = "Menu without All comments";

    /** Counted each time a post that kept All comments came back in another order and it was picked again. */
    static final String PICKED_AGAIN = "All comments picked again";

    // Facebook's trees are read by the hash of each field's GraphQL name.
    private static final int ID = "id".hashCode();
    private static final int RENDERING = "comment_rendering_instance".hashCode();
    private static final int SELECTED = "selected_intent".hashCode();
    private static final int OFFERED = "selectable_intents".hashCode();
    private static final int TOKEN = "intent_token".hashCode();

    /** Runs a test's picks instead of the main thread. */
    @Nullable
    static volatile Executor mainForTests;

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** The order a test chooses instead of the saved one. The switch is still asked. */
    @Nullable
    static volatile CommentOrder orderForTests;

    /** The clock a test sets instead of the phone's uptime, in milliseconds. */
    @Nullable
    static volatile Long nowForTests;

    /** Thrown by the next hook call, for a test of the fail-open path. */
    @Nullable
    static volatile RuntimeException failNextForTests;

    /** A pick heard in the menu, waiting for the request it sends on the same thread. */
    private static final class Pick {
        final String token;
        final long at;

        Pick(String token, long at) {
            this.token = token;
            this.at = at;
        }
    }

    private static final ThreadLocal<Pick> pending = new ThreadLocal<>();

    /** Feedback id to the order picked for that post, least recently used first. */
    private static final Map<String, String> picks = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > REMEMBERED_POSTS;
                }
            });

    /** Feedback id to when its request was left for the menu, least recently used first. */
    private static final Map<String, Long> awaiting = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > REMEMBERED_POSTS;
                }
            });

    /**
     * Feedback id to whether an answer came back on the kept order since that post's last pick,
     * least recently used first. A post that's false here isn't picked in again, so a menu drawn
     * again before the pick's answer, or a server that won't answer that order, gets one pick.
     */
    private static final Map<String, Boolean> answered = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED_POSTS;
                }
            });

    /**
     * When a post page's request was last left for its menu, or 0 for none. The page's request has
     * no feedback id yet, so its menu is the first one drawn after it within {@link
     * #AWAIT_WINDOW_MS} that no sheet's request is waiting for.
     */
    private static final AtomicLong pageLeftAt = new AtomicLong();

    /** Set while a pick this class made in a menu runs, so the line it writes says so. */
    private static final ThreadLocal<Boolean> picking = new ThreadLocal<>();

    /** How many requests this changed or left alone for a reason worth a line. */
    private static final AtomicInteger decisions = new AtomicInteger();

    private DefaultCommentOrder() {
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.defaultCommentOrder();
    }

    private static long now() {
        Long forced = nowForTests;
        return forced != null ? forced : SystemClock.uptimeMillis();
    }

    private static boolean isEmpty(@Nullable String text) {
        return text == null || text.isEmpty();
    }

    /**
     * The order to ask for when a request names none: the chosen one while the patch is in, the
     * settings are ready and the switch is on, which a pause answers off. Null for Facebook's own
     * choice. Never throws.
     */
    @Nullable
    static CommentOrder chosen() {
        try {
            if (!inBuild() || !Utils.settingsReady() || !Settings.DEFAULT_COMMENT_ORDER.get()) return null;
            CommentOrder forced = orderForTests;
            CommentOrder order = forced != null ? forced : Settings.COMMENT_ORDER.get();
            return order == null || order.token == null ? null : order;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEFAULT_COMMENT_ORDER, "comment order setting", failure);
            return null;
        }
    }

    /**
     * Injection point, first thing in the constructor of the params every request for a post's
     * comments is built from. [token] is the order the request would name, empty or null for none,
     * [feedbackId] the post's feedback id and [focusedCommentId] the comment a link leads to, if
     * any. Answers the order the request names. Never throws.
     */
    @Nullable
    public static String requestedOrder(@Nullable String token, @Nullable String feedbackId,
                                        @Nullable String focusedCommentId) {
        try {
            if (!inBuild()) return token;
            HookStatus.invoked(FamilyNames.DEFAULT_COMMENT_ORDER);
            RuntimeException failure = failNextForTests;
            if (failure != null) {
                failNextForTests = null;
                throw failure;
            }
            boolean named = !isEmpty(token);
            if (named) rememberPick(token, feedbackId);
            if (isEmpty(feedbackId)) return token;
            HookStatus.bound(FamilyNames.DEFAULT_COMMENT_ORDER, "comment request");
            if (named) return token;
            CommentOrder order = chosen();
            if (order == null) return token;
            if (!isEmpty(focusedCommentId)) {
                // A link's view stays Facebook's: a post that kept the order isn't picked again
                // in its menu until an answer comes back on that order.
                if (picks.containsKey(feedbackId)) answered.put(feedbackId, Boolean.FALSE);
                log("left a link to one comment in Facebook's order.");
                return token;
            }
            String picked = picks.get(feedbackId);
            if (picked != null) {
                log("asked for the order picked for this post, " + describe(picked) + ".");
                return picked;
            }
            if (deferred(order)) {
                awaiting.put(feedbackId, now());
                HookStatus.counted(FamilyNames.DEFAULT_COMMENT_ORDER, LEFT_FOR_MENU);
                log("left " + describe(order.token) + " for the post's menu to offer.");
                return token;
            }
            log("asked for " + describe(order) + ".");
            return order.token;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEFAULT_COMMENT_ORDER, "comment request", failure);
            return token;
        }
    }

    /**
     * Injection point, right before the one builder of a post page's request from a link makes
     * the request's params, with the order the link names, empty or null for none (#192). Answers
     * the order the page asks for: a named one stays, Most relevant and Newest go out up front,
     * and All comments is left for the page's menu, noted in {@link #pageLeftAt} for {@link
     * #sortMenu}. A link to one comment opens a sheet, not the page, so there's no focused comment
     * to keep here. Never throws.
     */
    @Nullable
    public static String pageOrder(@Nullable String token) {
        try {
            if (!inBuild()) return token;
            HookStatus.invoked(FamilyNames.DEFAULT_COMMENT_ORDER);
            RuntimeException failure = failNextForTests;
            if (failure != null) {
                failNextForTests = null;
                throw failure;
            }
            HookStatus.bound(FamilyNames.DEFAULT_COMMENT_ORDER, "post page request");
            if (!isEmpty(token)) return token;
            CommentOrder order = chosen();
            if (order == null) return token;
            if (deferred(order)) {
                pageLeftAt.set(now());
                HookStatus.counted(FamilyNames.DEFAULT_COMMENT_ORDER, LEFT_FOR_MENU);
                log("left " + describe(order.token) + " for a post page's menu to offer.");
                return token;
            }
            log("asked for " + describe(order) + " on a post's page.");
            return order.token;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEFAULT_COMMENT_ORDER, "post page request", failure);
            return token;
        }
    }

    /**
     * Injection point, first thing in a comment sheet's pick handler, with the token of the order
     * picked in its menu. The request it sends comes next, on this thread, and {@link
     * #requestedOrder} keeps the order for that post. Never throws.
     */
    public static void picked(@Nullable String token) {
        try {
            if (!inBuild()) return;
            HookStatus.invoked(FamilyNames.DEFAULT_COMMENT_ORDER);
            if (isEmpty(token)) return;
            HookStatus.bound(FamilyNames.DEFAULT_COMMENT_ORDER, "order picked in a comment sheet");
            pending.set(new Pick(token, now()));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEFAULT_COMMENT_ORDER, "order picked in a comment sheet", failure);
        }
    }

    /**
     * Keeps [token] as the order picked for [feedbackId] when this request is the one a pick just
     * sent: the same token, on the same thread, within {@link #PICK_WINDOW_MS}. A pick is used up
     * by the first request that names an order, whatever it names.
     */
    private static void rememberPick(String token, @Nullable String feedbackId) {
        Pick pick = pending.get();
        if (pick == null) return;
        pending.remove();
        if (isEmpty(feedbackId) || !pick.token.equals(token) || now() - pick.at > PICK_WINDOW_MS) return;
        String before = picks.put(feedbackId, token);
        answered.put(feedbackId, Boolean.FALSE);
        if (!token.equals(before)) {
            String how = Boolean.TRUE.equals(picking.get()) ? "which its menu offered" : "picked in a post's comments";
            log("kept " + describe(token) + ", " + how + ", for that post until Facebook restarts.");
        }
    }

    /** Whether [order] waits for a sheet's menu to offer it rather than being asked for up front. */
    static boolean deferred(CommentOrder order) {
        return order == CommentOrder.ALL_COMMENTS;
    }

    /**
     * Injection point, first thing in the constructor of a comment sheet's sort menu, which Facebook
     * builds only when its answer offers more than one order. [feedback] is the post's feedback
     * tree, [pick] what a tap on one of the menu's rows calls with that row's token. When the
     * request for this post was left for the menu and the menu offers the chosen order, that order
     * is picked on the main thread {@link #PICK_DELAY_MS} later, as a tap would. A post that kept
     * that order and came back in another one, as a refetch built from the first request's params
     * does, gets it picked again, once per answer. Never throws.
     */
    public static void sortMenu(@Nullable Object feedback, @Nullable Object pick) {
        try {
            if (!inBuild()) return;
            HookStatus.invoked(FamilyNames.DEFAULT_COMMENT_ORDER);
            if (feedback == null || !(pick instanceof Function1)) return;
            HookStatus.bound(FamilyNames.DEFAULT_COMMENT_ORDER, "comment sort menu");
            CommentOrder order = chosen();
            if (order == null || !deferred(order)) return;
            String feedbackId = string(feedback, ID);
            if (isEmpty(feedbackId)) return;
            Long asked = awaiting.remove(feedbackId);
            boolean left = asked != null && now() - asked <= AWAIT_WINDOW_MS;
            if (!left) {
                // A post page's request had no feedback id, so its menu is the next one drawn.
                long page = pageLeftAt.getAndSet(0L);
                left = page != 0L && now() - page <= AWAIT_WINDOW_MS;
            }
            // A post that kept the order, from a tap or a pick here, can come back in another one: a
            // refetch built from the first request's params names none and leaves nothing waiting.
            boolean kept = !left && order.isNamedBy(picks.get(feedbackId));
            if (!left && !kept) return;
            Object rendering = tree(feedback, RENDERING);
            if (rendering == null) {
                HookStatus.missingMember(FamilyNames.DEFAULT_COMMENT_ORDER, "comment sort menu",
                        feedback.getClass().getName(), "comment_rendering_instance");
                return;
            }
            String selected = string(tree(rendering, SELECTED), TOKEN);
            List<String> offered = tokens(rendering);
            String token = order.rowIn(offered);
            // Answered when Facebook is on the row this menu would be picked at. On another of the
            // order's rows while the usual one is offered, the usual one is picked.
            if (token != null ? token.equals(selected) : order.isNamedBy(selected)) {
                answered.put(feedbackId, Boolean.TRUE);
                if (kept) return;
                HookStatus.counted(FamilyNames.DEFAULT_COMMENT_ORDER, ANSWERED);
                log("Facebook answered " + describe(selected) + " already.");
                return;
            }
            if (token == null) {
                if (kept) return;
                HookStatus.counted(FamilyNames.DEFAULT_COMMENT_ORDER, NOT_OFFERED);
                log("left a post whose menu offers " + offered + " in Facebook's order.");
                return;
            }
            if (kept && Boolean.FALSE.equals(answered.get(feedbackId))) return;
            answered.put(feedbackId, Boolean.FALSE);
            if (kept) {
                HookStatus.counted(FamilyNames.DEFAULT_COMMENT_ORDER, PICKED_AGAIN);
                log("picked " + describe(token) + " again in the post's menu, which came back "
                        + describe(selected) + ".");
            } else {
                HookStatus.counted(FamilyNames.DEFAULT_COMMENT_ORDER, PICKED_IN_MENU);
                log("picked " + describe(token) + " in the post's menu.");
            }
            @SuppressWarnings("unchecked")
            Function1<Object, Object> menu = (Function1<Object, Object>) pick;
            Runnable tap = () -> {
                picking.set(Boolean.TRUE);
                try {
                    menu.invoke(token);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.DEFAULT_COMMENT_ORDER, "comment sort menu pick", failure);
                } finally {
                    picking.remove();
                }
            };
            Executor forced = mainForTests;
            if (forced != null) forced.execute(tap);
            else Utils.runOnMainThreadDelayed(tap, PICK_DELAY_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEFAULT_COMMENT_ORDER, "comment sort menu", failure);
        }
    }

    /** The tokens of the orders the answer's menu offers, in its order. */
    private static List<String> tokens(Object rendering) {
        List<String> tokens = new ArrayList<>();
        Object list = call(rendering, "getTreeList", OFFERED);
        if (!(list instanceof List)) return tokens;
        for (Object option : (List<?>) list) {
            String token = string(option, TOKEN);
            if (!isEmpty(token)) tokens.add(token);
        }
        return tokens;
    }

    @Nullable
    private static Object tree(@Nullable Object model, int field) {
        return call(model, "getTree", field);
    }

    @Nullable
    private static String string(@Nullable Object model, int field) {
        Object value = call(model, "getString", field);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Facebook's kept reader [name] of [model] for [field], or null when there's none or it fails.
     * A released tree isn't read, since that read goes to native code no try block catches.
     */
    @Nullable
    private static Object call(@Nullable Object model, String name, int field) {
        if (!PostDetails.isLiveTree(model)) return null;
        try {
            return model.getClass().getMethod(name, int.class).invoke(model, field);
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The chosen [order] as a line names it, which a token alone can't tell from another's row. */
    static String describe(CommentOrder order) {
        return order.fileValue + " (" + order.token + ")";
    }

    /** An order as a line names it: its name here and Facebook's token, or the token alone. */
    static String describe(String token) {
        CommentOrder order = CommentOrder.ofToken(token);
        return order == null ? token : order.fileValue + " (" + token + ")";
    }

    /** One line per decision for the first {@link #LOGGED_ONE_BY_ONE}, then one per {@link #SUMMED_UP_BY}. */
    private static void log(String line) {
        int count = decisions.incrementAndGet();
        if (count <= LOGGED_ONE_BY_ONE) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> PREFIX + line);
        } else if (count % SUMMED_UP_BY == 0) {
            Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> PREFIX + count + " comment requests so far. The last one " + line);
        }
    }

    /** Forgets the picks, the pending one, the posts and page left for their menus and the line count, as a new process would. */
    static void forget() {
        picks.clear();
        pending.remove();
        awaiting.clear();
        pageLeftAt.set(0L);
        answered.clear();
        decisions.set(0);
    }
}
