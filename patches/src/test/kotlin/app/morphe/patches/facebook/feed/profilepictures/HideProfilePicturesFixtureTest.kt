/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.profilepictures

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide profile pictures on every declared build and ABI (#400). Every way into the post header's
 * merge, Facebook's own null default included, now passes the picture model through the extension,
 * and the comment row's built picture goes through it right before the row adds it, while the row's
 * own null path still jumps straight to the add. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HideProfilePicturesFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
    private val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    private val moves = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

    private fun Method.code() = implementation!!.instructions.toList()
    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()

    private fun holding(bundle: File, string: String): List<ClassDef> =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }

    /** The classes making a new [type]: the header's lambda is made outside the class holding its trace name. */
    private fun making(bundle: File, type: String): Collection<ClassDef> {
        val makers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == type } }) { method ->
            method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE && it.reference() == type } == true
        }
        return FixtureDex.classes(bundle, makers.map { it.definingClass }.toSet()).values
    }

    private fun BytecodePatchContext.patched(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }.code()

    /** Each goto in patched [code] with the index its label sits on. */
    private fun patchedGotos(code: List<Instruction>): Map<Int, Int> =
        code.indices.filter { code[it].opcode in gotos }.associateWith { (code[it] as BuilderOffsetInstruction).target.location.index }

    private fun assertHook(name: String, code: List<Instruction>, at: Int, hook: String, register: Int, type: String) {
        assertEquals("$name: no hook at $at", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
            code.subList(at, at + 3).map { it.opcode })
        assertEquals("$name: the wrong hook", hook, code[at].reference())
        assertEquals("$name: reads the picture", register, (code[at] as RegisterRangeInstruction).startRegister)
        assertEquals("$name: and only it", 1, (code[at] as RegisterRangeInstruction).registerCount)
        assertEquals("$name: puts it back", register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: and casts it back", register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: to its own type", type, code[at + 2].reference())
    }

    @Test
    fun `each declared build's post headers and comments hand their picture to the extension`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val lambdas = holding(bundle, HEADER_TRACE)
                assertEquals("$name: classes holding the header's trace name", 1, lambdas.size)
                val holders = (lambdas + making(bundle, lambdas.single().type) + holding(bundle, COMMENT_PICTURE_TAG))
                    .distinctBy { it.type }
                val context = PatchContexts.of(holders + ExtensionDex.classDef(SETTINGS_STATUS))
                val (shells, header, comment) = context.profilePictureMethods()
                val headerSite = headerSite(header, shells)
                val commentSite = commentSite(comment)
                val headerBefore = header.code()
                val commentBefore = comment.code()

                // Positive controls: the header's lambda takes the very model the merge holds, right
                // after its ContextChain, so the merge is where the picture is decided; and the comment
                // row's own null picture jumps to the instruction right after the build, so that
                // instruction is the row's add.
                val making = headerBefore.indices.filter { index ->
                    headerBefore[index].opcode == Opcode.INVOKE_DIRECT_RANGE &&
                        ((headerBefore[index] as ReferenceInstruction).reference as MethodReference).let {
                            it.name == "<init>" && it.definingClass == shells.definingClass
                        }
                }
                assertEquals("$name: the builder makes the header's lambda once", 1, making.size)
                val at = making.single()
                val parameters = ((headerBefore[at] as ReferenceInstruction).reference as MethodReference)
                    .parameterTypes.map { it.toString() }
                val slot = parameters.indexOf(CONTEXT_CHAIN) + 1
                assertTrue("$name: the header's lambda takes no ContextChain", slot > 0)
                assertEquals("$name: the lambda's argument after ContextChain", headerSite.type, parameters[slot])
                val model = (headerBefore[at] as RegisterRangeInstruction).startRegister + 1 +
                    slot + parameters.take(slot).count { it == "J" || it == "D" }
                val load = (at - 1 downTo headerSite.merge).firstOrNull { index ->
                    headerBefore[index].opcode in moves && (headerBefore[index] as TwoRegisterInstruction).registerA == model
                }
                assertEquals("$name: the header's lambda doesn't take the picture model from the merge", headerSite.register,
                    load?.let { (headerBefore[it] as TwoRegisterInstruction).registerB } ?: model)
                val arrivals = gotoTargets(headerBefore).count { it.value == headerSite.merge }
                val nullPaths = gotoTargets(commentBefore).filter { (from, to) ->
                    to == commentSite.at && commentBefore[from - 1].opcode == Opcode.CONST_4 &&
                        (commentBefore[from - 1] as NarrowLiteralInstruction).narrowLiteral == 0 &&
                        (commentBefore[from - 1] as OneRegisterInstruction).registerA == commentSite.register
                }
                assertEquals("$name: the comment row's own null picture doesn't jump to its add", 1, nullPaths.size)

                context.hideProfilePictures()
                context.enableStatus("profilePictures")

                val headerAfter = context.patched(header)
                assertEquals("$name: the header builder grew by more than the hook", headerBefore.size + 3, headerAfter.size)
                assertHook(name, headerAfter, headerSite.merge, FEED_PICTURE, headerSite.register, headerSite.type)
                assertEquals("$name: the merge's own code moved", headerBefore[headerSite.merge].opcode, headerAfter[headerSite.merge + 3].opcode)
                assertEquals("$name: Facebook's null default no longer falls into the hook",
                    headerBefore[headerSite.merge - 1].opcode, headerAfter[headerSite.merge - 1].opcode)
                val landings = patchedGotos(headerAfter).values
                assertEquals("$name: an actor type skips the hook", arrivals, landings.count { it == headerSite.merge })
                assertEquals("$name: a jump lands past the hook", 0, landings.count { it == headerSite.merge + 3 })

                val commentAfter = context.patched(comment)
                assertEquals("$name: the comment render grew by more than the hook", commentBefore.size + 3, commentAfter.size)
                assertHook(name, commentAfter, commentSite.at, COMMENT_PICTURE, commentSite.register, commentSite.type)
                assertEquals("$name: the row's add moved", commentBefore[commentSite.at].reference(), commentAfter[commentSite.at + 3].reference())
                assertEquals("$name: the row's own null picture goes through the hook",
                    1, patchedGotos(commentAfter).values.count { it == commentSite.at + 3 })

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "profilePictures" }.code()
                assertEquals("$name: the settings screen doesn't show the switches",
                    listOf(Opcode.CONST_4, Opcode.RETURN), status.take(2).map { it.opcode })
                assertEquals("$name: the settings screen doesn't show the switches",
                    1, (status[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += name
            }
        }
        assertTrue("no fixture bundle for ${versions.joinToString()}", checked.isNotEmpty())
    }
}
