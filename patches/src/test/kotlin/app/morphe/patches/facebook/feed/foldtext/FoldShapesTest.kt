/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.foldtext

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.affiliate.FB_USER_SESSION
import app.morphe.patches.facebook.feed.posttext.CAPTION_TOGGLE
import app.morphe.patches.facebook.feed.posttext.CHARACTER_STYLE
import app.morphe.patches.facebook.feed.posttext.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.posttext.SPANNABLE
import app.morphe.patches.facebook.feed.posttext.SPANNABLE_STRING
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fold long text's places on hand-built code shaped like 588's: the post text builder asks the line
 * limit (its method reads the line limit key), makes the cut config from a character budget and the
 * limit, and hands two cuts a fixed place to cut at. The line limit's class makes the same config
 * again from its own answer behind two jumps, and the comment sheet's method names its trace and
 * makes the config from 400 and 5. The feed component asks whether to show a post whole beside the
 * attached story class, and a video caption's component measures its text against a limit.
 */
class FoldShapesTest {
    private val builderType = "Lfixture/PostText;"
    private val limitType = "Lfixture/Limit;"
    private val commentType = "Lfixture/Comments;"
    private val feedType = "Lfixture/Feed;"
    private val senderType = "Lfixture/CaptionSender;"
    private val captionType = "Lfixture/Caption;"
    private val measureType = "Lfixture/Measure;"
    private val config = "Lfixture/Config;"
    private val limit = "$limitType->A00(${FB_USER_SESSION}Ljava/lang/Object;$GRAPHQL_STORY)I"
    private val factory = "$config->A00(II)$config"
    private val wholeCheck = "$feedType->A0G(${FB_USER_SESSION}Ljava/lang/Object;Ljava/lang/Object;)Z"
    private val measure = "$measureType-><init>(IIZZ)V"
    private val postCut = "Lfixture/Cut;->A05(${CHARACTER_STYLE}${config}Ljava/lang/Boolean;Ljava/lang/CharSequence;" +
        "Ljava/lang/Integer;Ljava/lang/String;I)$SPANNABLE_STRING"
    private val commentCut = "Lfixture/Cut;->A06(${CHARACTER_STYLE}${config}Ljava/lang/CharSequence;Ljava/lang/String;)$SPANNABLE_STRING"

    private fun method(
        owner: String,
        name: String,
        body: String,
        parameters: List<String> = emptyList(),
        returnType: String = "V",
        registers: Int = 40,
    ) = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classOf(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList())

    /** The builder: the line limit's answer in v20, so its hook needs the range form, and the config from v5 and v10. */
    private fun builder(configCall: String = "invoke-static {v5, v10}, $factory") = classOf(
        builderType,
        method(
            builderType, "A06",
            """
                const v0, 0x32d58eae
                invoke-static {v1, v2, v3}, $limit
                move-result v20
                if-lez v20, :whole
                move/from16 v10, v20
                const/16 v5, 0x130
                $configCall
                move-result-object v6
                invoke-static/range {v22 .. v28}, $postCut
                move-result-object v9
                if-nez v9, :done
                invoke-static/range {v22 .. v28}, $postCut
                move-result-object v9
                :done
                return-object v9
                :whole
                return-object v38
            """,
            parameters = listOf(SPANNABLE, CHARACTER_STYLE), returnType = SPANNABLE,
        ),
    )

    /** The feed component: true in v1 unless the post shows whole, which v5 answers and only a branch reads. */
    private fun feed(afterAnswer: String = "") = classOf(
        feedType,
        method(
            feedType, "A1B",
            """
                const-class v0, $ATTACHED_STORY_COMPONENTS
                if-eqz v14, :cut
                invoke-static {v1, v2, v3}, $wholeCheck
                move-result v5
                $afterAnswer
                const/4 v1, 0x1
                if-eqz v5, :keep
                :cut
                const/4 v1, 0x0
                :keep
                const-string v5, "x"
                return-void
            """,
        ),
    )

    /** The method naming the caption's toggle, right before the component it belongs to. */
    private fun captionSender() = classOf(
        senderType,
        method(
            senderType, "A00",
            """
                const-string v0, "$CAPTION_TOGGLE"
                const-class v1, $captionType
                return-void
            """,
        ),
    )

    /**
     * The caption's component. Its limit starts as a field or a 1, then becomes a fixed 3 or a number
     * worked out further down, which jumps back to [workedOutGoesTo]. Past the measure comes a server
     * number or the measure's limit.
     */
    private fun caption(workedOutGoesTo: String = ":limit") = classOf(
        captionType,
        method(
            captionType, "A10",
            """
                iget v9, v4, $captionType->A01:I
                if-lez v9, :one
                if-eqz v16, :join
                if-ne v0, v4, :join
                :one
                const/4 v9, 0x1
                :join
                if-eqz v17, :worked
                const/4 v9, 0x3
                :limit
                move-object/from16 v1, v33
                new-instance v2, $measureType
                :measure
                invoke-direct {v2, v5, v9, v0, v1}, $measure
                if-eqz v18, :own
                const-wide v0, 0x1L
                long-to-int v5, v0
                move/from16 v47, v5
                goto :end
                :own
                iget v0, v2, $measureType->A00:I
                move/from16 v47, v0
                goto :end
                :worked
                if-eqz v19, :limit
                invoke-static {v2, v0}, Ljava/lang/Math;->min(II)I
                move-result v9
                goto $workedOutGoesTo
                :end
                return-void
            """,
            registers = 60,
        ),
    )

    /** The line limit's class: the limit itself, and the maker whose config call two jumps land on. */
    private fun limitClass() = classOf(
        limitType,
        method(
            limitType, "A00",
            """
                const v0, 0x32d58eae
                const/4 v0, 0x5
                return v0
            """,
            parameters = listOf(FB_USER_SESSION, "Ljava/lang/Object;", GRAPHQL_STORY), returnType = "I", registers = 8,
        ),
        method(
            limitType, "A02",
            """
                invoke-static {v0, v1, v2}, $limit
                move-result v3
                const/16 v4, 0x190
                if-gtz v3, :make
                const/4 v3, 0x4
                goto :make
                :make
                invoke-static {v4, v3}, $factory
                move-result-object v5
                return-object v5
            """,
            returnType = config, registers = 8,
        ),
    )

    private fun comments(
        configCalls: String = "invoke-static {v2, v1}, $factory",
        cutCalls: String = "move-result-object v3\ninvoke-static {v4, v3, v5, v0}, $commentCut",
    ) = classOf(
        commentType,
        method(
            commentType, "A19",
            """
                const-string v0, "$COMMENT_TRACE"
                const/16 v2, 0x190
                const/4 v1, 0x5
                const/16 v16, 0x190
                const/16 v17, 0x5
                $configCalls
                $cutCalls
                move-result-object v6
                return-object v6
            """,
            returnType = SPANNABLE_STRING,
        ),
    )

    private fun contextOf(vararg classes: ClassDef) = PatchContexts.of(classes.toList() + ExtensionDex.classDef(SETTINGS_STATUS))

    /** Everything the patch looks for, with [builder] and [comments] as asked. */
    private fun everything(builder: ClassDef = builder(), comments: ClassDef = comments()) =
        contextOf(builder, limitClass(), comments, feed(), captionSender(), caption())

    private fun BytecodePatchContext.code(type: String, name: String): List<Instruction> =
        mutableClassDefBy(type).methods.single { it.name == name }.implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun rangeOf(instruction: Instruction) = (instruction as RegisterRangeInstruction).let { it.startRegister to it.registerCount }

    /** How many branches and gotos in [code] land on [index]. */
    private fun jumpsInto(code: List<Instruction>, index: Int): Int {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        return code.indices.count { from ->
            val jump = code[from] as? OffsetInstruction ?: return@count false
            addresses[from] + jump.codeOffset == addresses[index]
        }
    }

    /** The line hook right after the line limit's answer lands in [register]. */
    private fun assertLineHook(code: List<Instruction>, at: Int, register: Int, call: String) {
        assertTrue("the line limit's answer isn't right before the hook", code[at - 1].opcode == Opcode.MOVE_RESULT)
        assertEquals(register, (code[at - 1] as OneRegisterInstruction).registerA)
        assertTrue("no line hook at $at", code[at].names(call))
        assertEquals(register to 1, rangeOf(code[at]))
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
    }

    /** The hook at [at] on the fixed place in [register], right before the cut that takes it. */
    private fun assertCutHook(code: List<Instruction>, at: Int, register: Int) {
        assertTrue("no fixed cut hook at $at", code[at].names(POST_CUT))
        assertEquals(register to 1, rangeOf(code[at]))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertTrue("the cut doesn't follow its hook", code[at + 2].names(postCut))
        assertEquals(register, arguments(code[at + 2])[4])
    }

    /** The hook at [at] passing the int in [register] through [call]. */
    private fun assertPassedThrough(code: List<Instruction>, at: Int, register: Int, call: String) {
        assertTrue("no hook at $at", code[at].names(call))
        assertEquals(register to 1, rangeOf(code[at]))
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
    }

    /** The config hook at [at], ahead of the config call: the characters, then the lines. */
    private fun assertConfigHook(
        code: List<Instruction>,
        at: Int,
        chars: Int,
        lines: Int,
        charsCall: String,
        linesCall: String,
        text: Int? = null,
    ) {
        assertTrue("no character hook at $at", code[at].names(charsCall))
        assertEquals(listOf(chars, lines) + listOfNotNull(text), arguments(code[at]))
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals(chars, (code[at + 1] as OneRegisterInstruction).registerA)
        assertTrue(code[at + 2].names(linesCall))
        assertEquals(lines to 1, rangeOf(code[at + 2]))
        assertEquals(lines, (code[at + 3] as OneRegisterInstruction).registerA)
        assertTrue("the config call doesn't follow its hook", code[at + 4].names(factory))
        assertEquals(listOf(chars, lines), arguments(code[at + 4]))
    }

    @Test
    fun `the post's two places and the comment's take their hooks, and the status turns on`() {
        val builder = builder()
        val limits = limitClass()
        val comments = comments()
        val builderOriginal = builder.methods.single().implementation!!.instructions.toList()
        val makerOriginal = limits.methods.single { it.name == "A02" }.implementation!!.instructions.toList()
        val makerConfig = makerOriginal.indexOfFirst { it.names(factory) }
        assertEquals("the maker's config call is a jump target twice", 2, jumpsInto(makerOriginal, makerConfig))
        val context = contextOf(builder, limits, comments, feed(), captionSender(), caption())

        foldLongTextPatch.execute(context)

        val post = context.code(builderType, "A06")
        assertEquals(builderOriginal.size + 10, post.size)
        assertLineHook(post, 3, 20, POST_LINES)
        // Index 6 before the line hook's two instructions went in at 3.
        assertConfigHook(post, 8, 5, 10, POST_CHARS, POST_LINES)
        assertEquals("the builder's own config call", 1, post.count { it.names(factory) })
        val cutHooks = post.indices.filter { post[it].names(POST_CUT) }
        assertEquals("a hook for each cut", 2, cutHooks.size)
        cutHooks.forEach { assertCutHook(post, it, 26) }
        assertEquals("the branch to the second cut lands on its hook", 0, jumpsInto(post, cutHooks[1] + 2))

        val maker = context.code(limitType, "A02")
        assertEquals(makerOriginal.size + 6, maker.size)
        assertLineHook(maker, 2, 3, POST_LINES)
        val hook = maker.indexOfFirst { it.names(POST_CHARS) }
        assertConfigHook(maker, hook, 4, 3, POST_CHARS, POST_LINES)
        assertEquals("the jumps land on the hook", 2, jumpsInto(maker, hook))
        assertEquals("a jump skips the hook", 0, jumpsInto(maker, hook + 4))

        val comment = context.code(commentType, "A19")
        val at = comment.indexOfFirst { it.names(COMMENT_CHARS) }
        assertConfigHook(comment, at, 2, 1, COMMENT_CHARS, COMMENT_LINES, text = 5)
        assertEquals("only the comment's config is hooked", 1, comment.count { it.names(COMMENT_CHARS) })
        assertEquals(0, comment.count { it.names(POST_CHARS) || it.names(POST_LINES) })

        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "foldLongText" }
        assertEquals("SettingsStatus.foldLongText() isn't switched on", 1,
            (status.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun `the answer to showing a post whole goes through the extension before its check`() {
        val context = everything()
        foldLongTextPatch.execute(context)
        val code = context.code(feedType, "A1B")
        assertTrue(code[2].names(wholeCheck))
        assertEquals(Opcode.MOVE_RESULT, code[3].opcode)
        assertPassedThrough(code, 4, 5, POST_HOISTED)
        assertEquals("the check still reads the answer", 5, (code[7] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[7].opcode)
    }

    @Test
    fun `a video caption's limit goes through the extension on each way it's set`() {
        val original = caption().methods.single().implementation!!.instructions.toList()
        val join = original.indexOfLast { it.opcode == Opcode.CONST_4 } + 1
        assertEquals("the stand-in's join is a jump target twice", 2, jumpsInto(original, join))
        val context = everything()

        foldLongTextPatch.execute(context)

        val code = context.code(captionType, "A10")
        val hooks = code.indices.filter { code[it].names(CAPTION_LINES) }
        assertEquals("a hook for each way the limit is set", 2, hooks.size)
        assertPassedThrough(code, hooks[0], 9, CAPTION_LINES)
        assertEquals("the fixed 3 is what the first hook follows", 3, (code[hooks[0] - 1] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("the jumps land on the hook", 2, jumpsInto(code, hooks[0]))
        assertEquals("a jump skips the hook", 0, jumpsInto(code, hooks[0] + 2))
        assertEquals("the measure takes the hooked limit", 9, arguments(code.single { it.names(measure) })[2])
        assertPassedThrough(code, hooks[1], 5, CAPTION_LINES)
        assertEquals("the server number is what the second hook follows", Opcode.LONG_TO_INT, code[hooks[1] - 1].opcode)
    }

    @Test
    fun `a config made from registers side by side is handed over in the range form`() {
        val context = everything(
            comments = comments(
                "invoke-static/range {v16 .. v17}, $factory",
                "move-result-object v17\ninvoke-static/range {v16 .. v19}, $commentCut",
            ),
        )
        foldLongTextPatch.execute(context)
        val comment = context.code(commentType, "A19")
        val at = comment.indexOfFirst { it.names(COMMENT_CHARS) }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, comment[at].opcode)
        assertConfigHook(comment, at, 16, 17, COMMENT_CHARS, COMMENT_LINES, text = 18)
    }

    @Test
    fun `a comment's text that no call can hand over refuses`() {
        // v16 and v17 sit side by side, but the text in v5 can't join them in a range.
        val apart = everything(
            comments = comments(
                "invoke-static/range {v16 .. v17}, $factory",
                "move-result-object v3\ninvoke-static {v4, v3, v5, v0}, $commentCut",
            ),
        )
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(apart) }
        assertEquals(0, apart.code(builderType, "A06").count { it.names(POST_LINES) })
    }

    @Test
    fun `a missing or doubled place refuses before anything changes`() {
        // Two configs in the comment's method: which one is the comment's can't be told.
        val doubled = everything(
            comments = comments("invoke-static {v2, v1}, $factory\nmove-result-object v3\ninvoke-static {v2, v1}, $factory"),
        )
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(doubled) }
        assertEquals("the builder changed before the refusal", 0, doubled.code(builderType, "A06").count { it.names(POST_LINES) })

        // One register for both numbers: the hook couldn't change one without the other.
        val shared = everything(builder = builder("invoke-static {v5, v5}, $factory"))
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(shared) }

        // No comment method, or no line limit to read the key from.
        val noComments = contextOf(builder(), limitClass(), feed(), captionSender(), caption())
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(noComments) }
        assertEquals(0, noComments.code(builderType, "A06").count { it.names(POST_LINES) })
        assertThrows(PatchException::class.java) {
            foldLongTextPatch.execute(contextOf(builder(), comments(), feed(), captionSender(), caption()))
        }
    }

    @Test
    fun `a post shown whole that can't be hooked refuses before anything changes`() {
        val missing = contextOf(builder(), limitClass(), comments(), captionSender(), caption())
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(missing) }
        assertEquals(0, missing.code(builderType, "A06").count { it.names(POST_LINES) })

        // An int can't go back where something other than a check reads the boolean.
        val readElsewhere = contextOf(builder(), limitClass(), comments(), feed("invoke-static {v5}, Lfixture/Use;->A00(Z)V"),
            captionSender(), caption())
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(readElsewhere) }
        assertEquals(0, readElsewhere.code(builderType, "A06").count { it.names(POST_LINES) })
    }

    @Test
    fun `a video caption that can't be hooked refuses before anything changes`() {
        val noSender = contextOf(builder(), limitClass(), comments(), feed(), caption())
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(noSender) }
        assertEquals(0, noSender.code(builderType, "A06").count { it.names(POST_LINES) })

        // A value that reaches the measure around the hook would keep Facebook's limit.
        val aroundTheHook = contextOf(builder(), limitClass(), comments(), feed(), captionSender(), caption(":measure"))
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(aroundTheHook) }
        assertEquals(0, aroundTheHook.code(builderType, "A06").count { it.names(POST_LINES) })
    }
}
