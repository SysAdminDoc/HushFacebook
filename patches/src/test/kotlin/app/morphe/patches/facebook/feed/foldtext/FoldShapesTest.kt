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
 * Fold long text's places on hand-built code shaped like 587's: the post text builder asks the line
 * limit (its method reads the line limit key) and makes the cut config from a character budget and
 * the limit, the line limit's class makes the same config again from its own answer behind two
 * jumps, and the comment sheet's method names its trace and makes the config from 400 and 5.
 */
class FoldShapesTest {
    private val builderType = "Lfixture/PostText;"
    private val limitType = "Lfixture/Limit;"
    private val commentType = "Lfixture/Comments;"
    private val config = "Lfixture/Config;"
    private val limit = "$limitType->A00(${FB_USER_SESSION}Ljava/lang/Object;$GRAPHQL_STORY)I"
    private val factory = "$config->A00(II)$config"
    private val postCut = "Lfixture/Cut;->A05(${CHARACTER_STYLE}${config}Ljava/lang/CharSequence;)$SPANNABLE_STRING"
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
                invoke-static {v7, v6, v8}, $postCut
                move-result-object v9
                return-object v9
                :whole
                return-object v38
            """,
            parameters = listOf(SPANNABLE, CHARACTER_STYLE), returnType = SPANNABLE,
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

    private fun comments(configCalls: String = "invoke-static {v2, v1}, $factory") = classOf(
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
                move-result-object v3
                invoke-static {v4, v3, v5, v0}, $commentCut
                move-result-object v6
                return-object v6
            """,
            returnType = SPANNABLE_STRING,
        ),
    )

    private fun contextOf(vararg classes: ClassDef) = PatchContexts.of(classes.toList() + ExtensionDex.classDef(SETTINGS_STATUS))

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

    /** The config hook at [at], ahead of the config call: the characters, then the lines. */
    private fun assertConfigHook(code: List<Instruction>, at: Int, chars: Int, lines: Int, charsCall: String, linesCall: String) {
        assertTrue("no character hook at $at", code[at].names(charsCall))
        assertEquals(listOf(chars, lines), arguments(code[at]))
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
        val context = contextOf(builder, limits, comments)

        foldLongTextPatch.execute(context)

        val post = context.code(builderType, "A06")
        assertEquals(builderOriginal.size + 6, post.size)
        assertLineHook(post, 3, 20, POST_LINES)
        // Index 6 before the line hook's two instructions went in at 3.
        assertConfigHook(post, 8, 5, 10, POST_CHARS, POST_LINES)
        assertEquals("the builder's own config call", 1, post.count { it.names(factory) })

        val maker = context.code(limitType, "A02")
        assertEquals(makerOriginal.size + 6, maker.size)
        assertLineHook(maker, 2, 3, POST_LINES)
        val hook = maker.indexOfFirst { it.names(POST_CHARS) }
        assertConfigHook(maker, hook, 4, 3, POST_CHARS, POST_LINES)
        assertEquals("the jumps land on the hook", 2, jumpsInto(maker, hook))
        assertEquals("a jump skips the hook", 0, jumpsInto(maker, hook + 4))

        val comment = context.code(commentType, "A19")
        val at = comment.indexOfFirst { it.names(COMMENT_CHARS) }
        assertConfigHook(comment, at, 2, 1, COMMENT_CHARS, COMMENT_LINES)
        assertEquals("only the comment's config is hooked", 1, comment.count { it.names(COMMENT_CHARS) })
        assertEquals(0, comment.count { it.names(POST_CHARS) || it.names(POST_LINES) })

        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "foldLongText" }
        assertEquals("SettingsStatus.foldLongText() isn't switched on", 1,
            (status.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test
    fun `a config made from registers side by side is handed over in the range form`() {
        val context = contextOf(builder(), limitClass(), comments("invoke-static/range {v16 .. v17}, $factory"))
        foldLongTextPatch.execute(context)
        val comment = context.code(commentType, "A19")
        val at = comment.indexOfFirst { it.names(COMMENT_CHARS) }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, comment[at].opcode)
        assertConfigHook(comment, at, 16, 17, COMMENT_CHARS, COMMENT_LINES)
    }

    @Test
    fun `a missing or doubled place refuses before anything changes`() {
        // Two configs in the comment's method: which one is the comment's can't be told.
        val doubled = contextOf(builder(), limitClass(),
            comments("invoke-static {v2, v1}, $factory\nmove-result-object v3\ninvoke-static {v2, v1}, $factory"))
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(doubled) }
        assertEquals("the builder changed before the refusal", 0, doubled.code(builderType, "A06").count { it.names(POST_LINES) })

        // One register for both numbers: the hook couldn't change one without the other.
        val shared = contextOf(builder("invoke-static {v5, v5}, $factory"), limitClass(), comments())
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(shared) }

        // No comment method, or no line limit to read the key from.
        val noComments = contextOf(builder(), limitClass())
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(noComments) }
        assertEquals(0, noComments.code(builderType, "A06").count { it.names(POST_LINES) })
        assertThrows(PatchException::class.java) { foldLongTextPatch.execute(contextOf(builder(), comments())) }
    }
}
