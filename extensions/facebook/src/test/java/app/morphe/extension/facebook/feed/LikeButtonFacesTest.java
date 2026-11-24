/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

import com.facebook.graphql.model.GraphQLFeedback;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Show reactions on the Like button (#541): a button with a count asks for faces, the faces drawn
 * at the end of a post's button row move onto the Like button of the same row, whichever of the
 * two draws first, and the end of the row keeps them while the switch is off, paused or overruled
 * by Hide the reaction preview on posts. A comment's faces never move.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LikeButtonFacesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Canvas canvas = new Canvas(Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888));

    /** Litho's row view, which the extension knows by its simple name. */
    static final class LithoView extends FrameLayout {
        LithoView(Context context) {
            super(context);
        }
    }

    /** A view inside the row that drawables are mounted on, counting the redraws they ask for. */
    static final class Host extends FrameLayout {
        int invalidations;

        Host(Context context) {
            super(context);
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidations++;
        }
    }

    /** The faces at the end of a button row, whose draw asks first the way the patched one does. */
    static final class Pile extends Drawable {
        int drawn;
        int invalidated;

        Pile() {
            setBounds(0, 0, 60, 20);
        }

        @Override
        public void draw(Canvas canvas) {
            if (PostPrompts.hideReactionPreview(this, false)) return;
            drawn++;
        }

        @Override
        public void invalidateSelf() {
            invalidated++;
            super.invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The one face Facebook would put on the button. */
    static final class Face extends Drawable {
        int drawn;

        @Override
        public void draw(Canvas canvas) {
            drawn++;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** One post's button row: the Like button on one host, the faces at its end on another. */
    private static final class Row {
        final LithoView row;
        final Host buttonHost;
        final Host pileHost;
        final Face face = new Face();
        final Pile pile = new Pile();
        final LikeButtonFaces.Faces button;

        Row() {
            Context context = RuntimeEnvironment.getApplication();
            row = new LithoView(context);
            buttonHost = new Host(context);
            pileHost = new Host(context);
            row.addView(buttonHost);
            row.addView(pileHost);
            button = new LikeButtonFaces.Faces(face);
            button.setBounds(0, 0, 48, 20);
            button.setCallback(buttonHost);
            pile.setCallback(pileHost);
        }
    }

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.REACTIONS_ON_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_POST_REACTIONS.resetToDefault();
        Settings.HIDE_COMMENT_REACTIONS.resetToDefault();
        LikeButtonFaces.clearPending();
        PostText.cachedMembers = null;
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.POST_PROMPTS + ":")) return line;
        }
        return "";
    }

    /** A Like button's icon, as its builders hand it over: equal to another made the same way. */
    private static final class Glyph {
        final String name;

        Glyph(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Glyph && ((Glyph) other).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    @Test
    public void onlyANotedLikeIconWithACountTurnsIntoFaces() {
        Glyph thumb = new Glyph("thumb");
        Face like = new Face();
        assertFalse("the switch doesn't start off", Settings.REACTIONS_ON_LIKE_BUTTON.get());
        assertFalse("the builders noted icons with the switch off", LikeButtonFaces.remembering());
        LikeButtonFaces.remember(thumb, like);
        assertNull("a Like button got faces with the switch off", LikeButtonFaces.facesFor(thumb, "12"));

        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        assertTrue("the builders don't note icons with the switch on", LikeButtonFaces.remembering());
        Drawable faces = LikeButtonFaces.facesFor(thumb, "12");
        assertTrue("a Like button with a count didn't get faces", LikeButtonFaces.isFaces(faces));
        assertTrue("a Like button with a short count didn't get faces", LikeButtonFaces.isFaces(LikeButtonFaces.facesFor(thumb, "1.2K")));
        assertTrue("the count's own rebuild of the icon lost the faces",
                LikeButtonFaces.isFaces(LikeButtonFaces.facesFor(new Glyph("thumb"), "12")));
        assertNull("a Like button with no count got faces", LikeButtonFaces.facesFor(thumb, "Like"));
        assertNull("a Like button with no text got faces", LikeButtonFaces.facesFor(thumb, null));
        assertNull("the Comment button got faces", LikeButtonFaces.facesFor(new Glyph("comment"), "12"));
        assertNull("faces were wrapped in faces again", LikeButtonFaces.facesFor(faces, "12"));
        LikeButtonFaces.remember(new Glyph("again"), faces);
        assertNull("faces were noted as a Like button's face", LikeButtonFaces.facesFor(new Glyph("again"), "12"));

        Settings.HIDE_POST_REACTIONS.save(true);
        assertNull("Hide the reaction preview on posts didn't overrule it", LikeButtonFaces.facesFor(thumb, "12"));
        assertFalse("the builders noted icons while overruled", LikeButtonFaces.remembering());
        Settings.HIDE_POST_REACTIONS.save(false);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("a paused Hushfacebook gave faces", LikeButtonFaces.facesFor(thumb, "12"));
        assertFalse("a paused Hushfacebook noted icons", LikeButtonFaces.remembering());
        PauseForTests.resume();
        assertNotNull("resumed, the Like button didn't get faces again", LikeButtonFaces.facesFor(thumb, "12"));
    }

    @Test
    public void aButtonsFacesStayTheSameIconAndGetAWideBox() {
        Glyph thumb = new Glyph("thumb");
        Face like = new Face();
        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        LikeButtonFaces.remember(thumb, like);
        Drawable faces = LikeButtonFaces.facesFor(thumb, "12");
        assertFalse("Facebook's own face reads as faces", LikeButtonFaces.isFaces(like));
        assertEquals("a button made again for the same face isn't the same icon", faces, LikeButtonFaces.facesFor(thumb, "13"));
        Glyph unknown = new Glyph("no face");
        LikeButtonFaces.remember(unknown, null);
        assertTrue("a Like button with no face of its own got no faces", LikeButtonFaces.isFaces(LikeButtonFaces.facesFor(unknown, "12")));
        assertEquals("the box isn't 2.4 icons wide", 16f * 2.4f,
                (float) Double.longBitsToDouble(LikeButtonFaces.widthBits(faces, 16f)), 0.001f);
    }

    /** A post's three reads, its top reactions each used by [used] people. Counts every read. */
    private static LikeButtonFaces.Readers post(int[] reads, int... used) {
        GraphQLFeedback feedback = new GraphQLFeedback();
        BaseModelWithTree top = new BaseModelWithTree(0);
        List<Object> edges = new ArrayList<>();
        for (int count : used) edges.add(new BaseModelWithTree(0).number("reaction_count", count));
        return new LikeButtonFaces.Readers(story -> {
            reads[0]++;
            return story instanceof GraphQLStory ? feedback : null;
        }, model -> model == feedback ? top : null, model -> model == top ? edges : null);
    }

    private static float width(Drawable faces) {
        return (float) Double.longBitsToDouble(LikeButtonFaces.widthBits(faces, 16f));
    }

    /** The Like item's builder for one post: the post first, then the icon, then its return. */
    private static Drawable build(Object story, LikeButtonFaces.Readers readers, Object icon) {
        LikeButtonFaces.pendingStory(story, readers);
        LikeButtonFaces.remember(icon, new Face());
        LikeButtonFaces.clearPending();
        return LikeButtonFaces.facesFor(icon, "12");
    }

    @Test
    public void theBoxFitsThePostsFacesAtTheSameFaceSize() {
        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        int[] reads = new int[1];
        int[][] posts = {{4}, {9, 2}, {9, 2, 1}, {9, 2, 1, 1}, {7, 0}};
        float[] boxes = {0.8f, 1.6f, 2.4f, 2.4f, 0.8f};
        Glyph[] icons = new Glyph[posts.length];
        for (int i = 0; i < posts.length; i++) {
            // Every post's icon equals every other's, as Facebook's Like icons do.
            icons[i] = new Glyph("thumb");
            Drawable faces = build(new GraphQLStory(), post(reads, posts[i]), icons[i]);
            assertEquals("the box of a post with reactions " + Arrays.toString(posts[i]),
                    16f * boxes[i], width(faces), 0.001f);
        }
        for (int i = 0; i < posts.length; i++) {
            assertEquals("a later post's count reached an earlier post's equal icon",
                    16f * boxes[i], width(LikeButtonFaces.facesFor(icons[i], "12")), 0.001f);
        }
        assertFalse("one face and two faces read as the same icon",
                build(new GraphQLStory(), post(reads, 3), new Glyph("thumb"))
                        .equals(build(new GraphQLStory(), post(reads, 3, 3), new Glyph("thumb"))));

        Glyph later = new Glyph("thumb");
        LikeButtonFaces.remember(later, new Face());
        assertEquals("an icon noted after the builder returned took a post's count",
                16f * 2.4f, width(LikeButtonFaces.facesFor(later, "12")), 0.001f);
        assertEquals("something that isn't a story got a count",
                16f * 2.4f, width(build("props", post(reads, 1), new Glyph("thumb"))), 0.001f);
        LikeButtonFaces.Readers unpatched = new LikeButtonFaces.Readers(
                story -> StoryFlag.NOT_PATCHED, model -> StoryFlag.NOT_PATCHED, model -> StoryFlag.NOT_PATCHED);
        assertEquals("an unpatched build got a count",
                16f * 2.4f, width(build(new GraphQLStory(), unpatched, new Glyph("thumb"))), 0.001f);
        assertEquals("a post nobody reacted to got a count",
                16f * 2.4f, width(build(new GraphQLStory(), post(reads), new Glyph("thumb"))), 0.001f);

        Settings.REACTIONS_ON_LIKE_BUTTON.save(false);
        int before = reads[0];
        LikeButtonFaces.pendingStory(new GraphQLStory(), post(reads, 1));
        assertEquals("a post was read with the switch off", before, reads[0]);
    }

    @Test
    public void facesDrawnBeforeTheirButtonMoveOnceTheButtonDraws() {
        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        Row post = new Row();

        post.pile.draw(canvas);
        assertEquals("faces with no button in their row yet weren't drawn where they are", 1, post.pile.drawn);

        post.button.draw(canvas);
        assertEquals("the button didn't draw its row's faces", 2, post.pile.drawn);
        assertEquals("the button drew its own face over its row's faces", 0, post.face.drawn);
        assertEquals("the faces weren't asked to draw again so they leave the end of the row",
                1, post.pile.invalidated);

        post.pile.draw(canvas);
        assertEquals("the faces stayed at the end of the row", 2, post.pile.drawn);
        assertTrue("the status line doesn't count the moved faces",
                statusLine().contains(LikeButtonFaces.MOVED + " 1"));

        post.button.draw(canvas);
        assertEquals("the button stopped drawing its row's faces", 3, post.pile.drawn);
        assertEquals("the same button asked the faces to redraw again", 1, post.pile.invalidated);
    }

    @Test
    public void facesDrawnAfterTheirButtonMoveStraightAway() {
        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        Row post = new Row();

        post.button.draw(canvas);
        assertEquals("a button with no faces in its row yet didn't draw Facebook's face", 1, post.face.drawn);

        post.pile.draw(canvas);
        assertEquals("the faces stayed at the end of the row", 0, post.pile.drawn);
        assertEquals("the button wasn't asked to redraw with its row's faces", 1, post.buttonHost.invalidations);

        post.pile.draw(canvas);
        assertEquals("the same faces asked the button to redraw again", 1, post.buttonHost.invalidations);

        post.button.draw(canvas);
        assertEquals("the button didn't draw its row's faces", 1, post.pile.drawn);
        assertEquals("the button drew its own face over its row's faces", 1, post.face.drawn);
    }

    @Test
    public void facesOnlyMoveToTheLikeButtonOfTheirOwnRow() {
        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        Row post = new Row();
        Row other = new Row();
        post.button.draw(canvas);

        other.pile.draw(canvas);
        assertEquals("faces moved to another row's button", 1, other.pile.drawn);
        assertEquals("another row's faces redrew this button", 0, post.buttonHost.invalidations);
    }

    @Test
    public void theEndOfTheRowKeepsItsFacesUnlessTheSwitchIsOn() {
        Row post = new Row();
        post.button.draw(canvas);

        post.pile.draw(canvas);
        assertEquals("faces moved with the switch off", 1, post.pile.drawn);

        Settings.REACTIONS_ON_LIKE_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        post.pile.draw(canvas);
        assertEquals("faces moved while paused", 2, post.pile.drawn);
        PauseForTests.resume();

        assertFalse("a comment's faces moved", PostPrompts.hideReactionPreview(post.pile, true));

        Settings.HIDE_POST_REACTIONS.save(true);
        assertTrue("Hide the reaction preview on posts no longer hides them",
                PostPrompts.hideReactionPreview(post.pile, false));
        Settings.HIDE_POST_REACTIONS.save(false);

        post.pile.draw(canvas);
        assertEquals("with the switch on, the faces stayed at the end of the row", 2, post.pile.drawn);
    }
}
