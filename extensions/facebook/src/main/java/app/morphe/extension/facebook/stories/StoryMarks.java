/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The story cards you tapped Mark as seen on while viewing anonymously, and what became of each.
 *
 * <p>A card is known by the id Facebook's seen helper queues for it, the same string the report of
 * viewed stories lists in story_ids_list. Everything here belongs to the account it happened on,
 * by the session's user ID: a mark made on one account is never sent for another, and with no
 * account nothing is marked, kept or sent.
 *
 * <p>When a batch of card ids goes to be sent, {@link #choose} picks the ones that are marked, and
 * the ones an earlier batch of the same kind held back that have been marked since, into a new set,
 * and nothing else. Each card picked counts as sent: its mark is spent. The other cards of the batch
 * are kept as held back, with the send that held them, for 24 hours and up to {@link #MAX_HELD} of
 * them, because Facebook counts a card as reported before it sends and won't queue it again until
 * it restarts. A tap on a held card can then send it through that same send. A mark waits 24 hours
 * at most for a send to carry it. All of it is kept in memory only, so it lapses when Facebook's
 * process ends.
 */
final class StoryMarks {
    /** How long a mark waits for a send, and how long a held card or a sent one is remembered. */
    static final long LIFETIME_MS = 24L * 60 * 60 * 1000;

    static final int MAX_MARKS = 200;
    static final int MAX_SENT = 500;
    static final int MAX_HELD = 300;

    /** Where a card stands. */
    enum State {
        /** Held back like any other while you view anonymously. */
        UNMARKED,
        /** Marked, and waiting for the next send to carry it. */
        MARKED,
        /** Marked and handed to Facebook's send. */
        SENT,
    }

    /** A card a batch held back: the account, the card, the send that held it, and when. */
    private static final class Held {
        final String account;
        final String card;
        final StorySeen.Call call;
        final long at;

        Held(String account, String card, StorySeen.Call call, long at) {
            this.account = account;
            this.card = card;
            this.call = call;
            this.at = at;
        }
    }

    private final LongSupplier clock;
    /** Account and card to when it was marked, oldest first. */
    private final LinkedHashMap<String, Long> marked = new LinkedHashMap<>();
    /** Account and card to when it was sent, oldest first. */
    private final LinkedHashMap<String, Long> sent = new LinkedHashMap<>();
    /** Account and card to the batch that held it back, oldest first. */
    private final LinkedHashMap<String, Held> held = new LinkedHashMap<>();

    StoryMarks(LongSupplier clock) {
        this.clock = clock;
    }

    /** Where [card] stands for [account], UNMARKED for either null. */
    synchronized State state(@Nullable String account, @Nullable String card) {
        if (account == null || card == null) return State.UNMARKED;
        prune(clock.getAsLong());
        String id = id(account, card);
        if (sent.containsKey(id)) return State.SENT;
        return marked.containsKey(id) ? State.MARKED : State.UNMARKED;
    }

    /**
     * A tap: marks [card] for [account]. A mark stays until a send carries it, and a card already
     * sent stays sent: a second tap never takes a mark back (#557), so the eye only ever moves on
     * to seen. With no account nothing is marked. Answers where it stands now.
     */
    synchronized State mark(@Nullable String account, @Nullable String card) {
        if (account == null || card == null) return State.UNMARKED;
        long now = clock.getAsLong();
        prune(now);
        String id = id(account, card);
        if (sent.containsKey(id)) return State.SENT;
        if (marked.containsKey(id)) return State.MARKED;
        marked.put(id, now);
        trim(marked, MAX_MARKS);
        return State.MARKED;
    }

    /** The send a batch of [account]'s held [card] back from, or null when none is kept. */
    @Nullable
    synchronized StorySeen.Call heldBy(@Nullable String account, @Nullable String card) {
        if (account == null || card == null) return null;
        prune(clock.getAsLong());
        Held entry = held.get(id(account, card));
        return entry == null ? null : entry.call;
    }

    /**
     * Picks what goes out of [ids], a batch of [account]'s card ids on its way out through [call]:
     * a new set holding only the marked cards of [ids] and the marked cards [account] held back
     * before from a send of the same kind (a peek or not), or null when there are none, and then
     * nothing goes. Keeps the other cards of [ids] as held back. Null, and nothing kept, with no
     * account.
     */
    @Nullable
    synchronized Set<String> choose(@Nullable String account, @Nullable Set<?> ids, StorySeen.Call call) {
        if (account == null) return null;
        long now = clock.getAsLong();
        prune(now);
        Set<String> chosen = new LinkedHashSet<>();
        if (ids != null) {
            for (Object entry : ids.toArray()) {
                if (!(entry instanceof String) || ((String) entry).isEmpty()) continue;
                String card = (String) entry;
                String id = id(account, card);
                if (marked.containsKey(id)) {
                    chosen.add(card);
                } else if (!sent.containsKey(id)) {
                    held.remove(id);
                    held.put(id, new Held(account, card, call, now));
                }
            }
            trim(held, MAX_HELD);
        }
        for (Held entry : held.values()) {
            if (entry.account.equals(account) && entry.call.peek == call.peek
                    && marked.containsKey(id(account, entry.card))) {
                chosen.add(entry.card);
            }
        }
        if (chosen.isEmpty()) return null;
        for (String card : chosen) {
            String id = id(account, card);
            held.remove(id);
            marked.remove(id);
            sent.remove(id);
            sent.put(id, now);
        }
        trim(sent, MAX_SENT);
        return chosen;
    }

    /** Forgets everything. */
    synchronized void clear() {
        marked.clear();
        sent.clear();
        held.clear();
    }

    /** An account's user ID is digits, so it can't run into a card id through the separator. */
    private static String id(String account, String card) {
        return account + "/" + card;
    }

    private void prune(long now) {
        marked.values().removeIf(at -> expired(at, now));
        sent.values().removeIf(at -> expired(at, now));
        held.values().removeIf(entry -> expired(entry.at, now));
    }

    private static boolean expired(long at, long now) {
        return now - at >= LIFETIME_MS;
    }

    private static <K, V> void trim(Map<K, V> map, int max) {
        Iterator<K> oldest = map.keySet().iterator();
        while (map.size() > max && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
