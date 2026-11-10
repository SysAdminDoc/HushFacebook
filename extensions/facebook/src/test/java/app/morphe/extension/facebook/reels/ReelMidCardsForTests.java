/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stand-ins for a Reels page holding mid-cards: the mid-card item, whose class the patch names,
 * Facebook's mid-card type enum, of which only the constant names matter, and a section wrapper.
 */
public final class ReelMidCardsForTests {
    private ReelMidCardsForTests() {
    }

    /** Stands in for Facebook's mid-card type enum. */
    public enum MidCardType { THREADS_MIDCARD, PYML_MIDCARD, USER_SURVEY, GYSJ_MIDCARD }

    /** Stands in for the mid-card item. */
    public static final class MidCard {
        final MidCardType type;

        public MidCard(MidCardType type) {
            this.type = type;
        }
    }

    /** A section wrapper: the screen reads the list it holds. */
    public static final class Section {
        public List<Object> items;

        public Section(List<Object> items) {
            this.items = items;
        }
    }

    /** The mid-card item's binary name, as the patch passes it. */
    public static final String MID_CARD = MidCard.class.getName();

    /** Reads a stand-in's type the way the stub the patch fills reads Facebook's. */
    static final ReelMidCards.Types TYPES = item -> ((MidCard) item).type;

    /**
     * The filter over [page], reading types through the stand-in, with Clean up Reels and Hide reel
     * interest prompts both in the build unless a test said otherwise.
     */
    public static List<?> withoutThreadsCards(List<?> page) {
        Boolean threads = ReelMidCards.threadsPatchForTests;
        Boolean prompts = ReelMidCards.promptsPatchForTests;
        if (threads == null) ReelMidCards.threadsPatchForTests = Boolean.TRUE;
        if (prompts == null) ReelMidCards.promptsPatchForTests = Boolean.TRUE;
        try {
            return ReelMidCards.withoutThreadsCards(page, MID_CARD, TYPES);
        } finally {
            ReelMidCards.threadsPatchForTests = threads;
            ReelMidCards.promptsPatchForTests = prompts;
        }
    }

    /**
     * Hands the filter a section with a reel and a Threads card. True when the card came off, which
     * is the switch changing what Facebook would have done.
     */
    public static boolean dropsAThreadsCard() {
        MidCard card = new MidCard(MidCardType.THREADS_MIDCARD);
        Section section = new Section(new ArrayList<>(Arrays.asList(new Object(), card)));
        withoutThreadsCards(Collections.singletonList(section));
        return !section.items.contains(card);
    }
}
