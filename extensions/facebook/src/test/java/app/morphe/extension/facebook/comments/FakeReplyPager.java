package app.morphe.extension.facebook.comments;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

/**
 * Stands in for Facebook's View more replies pager under an open reply thread (588 {@code LX/0Eay}):
 * a component keeping the loader its tap runs in a field, beside fields that aren't loaders.
 */
public final class FakeReplyPager {
    /** Stands in for the pager's loader (588 {@code LX/0VmA}), a lambda whose captures keep their names. */
    public static final class Loader {
        public final Object $paginableList;
        public final int $depth;
        public final Object $modelAdapter = new Object();
        public int runs;

        Loader(Object page, int depth) {
            $paginableList = page;
            $depth = depth;
        }

        public Object invoke() {
            runs++;
            return null;
        }
    }

    /**
     * Stands in for the pager's other lambda (588 {@code LX/0Vm6}), which logs that the pager was
     * seen: it names the list and depth as the loader does, but has no model adapter to load through.
     */
    public static final class SeenLogger {
        public final Object $paginableList;
        public final int $depth;
        public int runs;

        SeenLogger(Object page, int depth) {
            $paginableList = page;
            $depth = depth;
        }

        public Object invoke() {
            runs++;
            return null;
        }
    }

    /** The pager's words, a field that isn't a loader. */
    private final String words = "View more replies";

    /** Another lambda the pager keeps, with no captures named like a loader's. */
    private final Runnable impression = () -> { };

    /** Ahead of the loader, as 588 orders the pager's two lambdas. */
    private final SeenLogger seen;

    private final Loader loader;

    /** A pager over a new page of a thread at {@code depth}, 0 being a post's own comments. */
    public FakeReplyPager(int depth) {
        this(new Object(), depth);
    }

    /** A pager over {@code page} of a thread at {@code depth}. */
    public FakeReplyPager(Object page, int depth) {
        seen = new SeenLogger(page, depth);
        loader = new Loader(page, depth);
    }

    /**
     * Lays the pager out through the hook, runs what that left for the main thread, and answers the
     * loader's runs so far. The logger running too would be a wrong pick, so it throws.
     */
    public int layOut() {
        CommentSheetOptions.loadRestOfThread(this);
        shadowOf(Looper.getMainLooper()).idle();
        if (seen.runs != 0) throw new AssertionError("the pager's seen logger ran instead of its loader");
        return loader.runs;
    }

    /** The loader's runs so far, without laying the pager out. */
    public int runs() {
        return loader.runs;
    }
}
