/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.emoji

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The big emoji of Use the phone's emoji on every Facebook build the bundle declares: the one maker
 * of emoji picture addresses the hook goes into, and the facts that make a null from it safe. Its
 * only callers are the two methods of the remote emoji class, each already answers null for an emoji
 * Meta has no picture of, and everything that keeps their answer tests it for null. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class RemoteEmojiFixtureTest {
    private fun signature(method: Method) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun MethodReference.signature() = definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

    private fun instructions(method: Method): List<Instruction> = method.implementation?.instructions?.toList().orEmpty()

    /** The indexes where [method] calls [target]. */
    private fun callsTo(method: Method, target: String): List<Int> = instructions(method).withIndex().filter { (_, it) ->
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.signature() == target
    }.map { it.index }

    /** The methods of the bundle that call [target], read only in the dex files that name its class. */
    private fun callers(bundle: File, target: String): List<Method> {
        val owner = target.substringBefore("->")
        return FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == owner } }) {
            callsTo(it, target).isNotEmpty()
        }
    }

    /**
     * What [method] does with each answer of [target]: "dropped" when no move-result takes it,
     * "tested" when the register it lands in is tested for null, "returned" when it goes straight
     * back to [method]'s own caller, and "used" otherwise.
     */
    private fun fates(method: Method, target: String): List<String> {
        val body = instructions(method)
        return callsTo(method, target).map { at ->
            val next = body.getOrNull(at + 1)
            if (next?.opcode != Opcode.MOVE_RESULT_OBJECT) return@map "dropped"
            val register = (next as OneRegisterInstruction).registerA
            when {
                body.getOrNull(at + 2)?.let { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA == register } == true ->
                    "returned"
                body.any { (it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ) && (it as OneRegisterInstruction).registerA == register } ->
                    "tested"
                else -> "used"
            }
        }
    }

    /** Whether [method] has a path answering null: a zero constant returned as an object. */
    private fun answersNull(method: Method): Boolean = instructions(method).zipWithNext().any { (constant, ret) ->
        constant.opcode == Opcode.CONST_4 && (constant as NarrowLiteralInstruction).narrowLiteral == 0 &&
            ret.opcode == Opcode.RETURN_OBJECT &&
            (ret as OneRegisterInstruction).registerA == (constant as OneRegisterInstruction).registerA
    }

    @Test
    fun `each declared build makes its emoji picture addresses in one place, and a null from it is safe`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun check(bundle: File) {
        val name = bundle.name
        val holding = FixtureDex.classesHolding(bundle, REMOTE_EMOJI_BASE)
            .flatMap { owner -> owner.methods.filter { holdsString(it, REMOTE_EMOJI_BASE) } }
        val makers = holding.filter(::isRemoteEmojiUrlMaker)
        assertEquals("$name: makers of emoji picture addresses", 1, makers.size)
        val maker = makers.single()
        val makerSignature = signature(maker)
        assertEquals("$name: methods loading the picture base", listOf(makerSignature), holding.map(::signature))
        assertTrue("$name: the maker has no local for the hook's answer", maker.localRegisterCount() >= 1)

        // Its callers are the remote emoji drawable and one other, and nothing else. Up to 585 the
        // other was a static address method in the drawable's class; 586 inlines that method into an
        // emoji component's render() in a class of its own.
        val askers = callers(bundle, makerSignature)
        assertEquals("$name: callers of the maker: ${askers.map(::signature)}", 2, askers.size)
        assertEquals("$name: classes calling the maker", 2, askers.map { it.definingClass }.toSet().size)
        val drawables = askers.filter { holdsString(it, REMOTE_EMOJI_KEY) }
        assertEquals("$name: the maker's callers holding \"$REMOTE_EMOJI_KEY\"", 1, drawables.size)
        val drawable = drawables.single()
        val other = askers.single { it !== drawable }
        val static = AccessFlags.STATIC.isSet(other.accessFlags) && other.returnType == STRING

        // A static address method already answers null for an emoji Meta has no picture of, and
        // hands the maker's answer straight back. The render it was inlined into tests the address
        // before it makes a Uri, and with none draws the emoji from the drawable instead. The drawable
        // method tests it before it builds a picture.
        if (static) {
            assertTrue("$name: ${signature(other)} never answers null", answersNull(other))
            assertEquals("$name: what ${signature(other)} does with the address", listOf("returned"),
                fates(other, makerSignature))
        } else {
            assertEquals("$name: ${signature(other)} isn't a render", "render", other.name)
            assertEquals("$name: what ${signature(other)} does with the address", listOf("tested"),
                fates(other, makerSignature))
        }
        assertEquals("$name: what ${signature(drawable)} does with the address", listOf("tested"),
            fates(drawable, makerSignature))

        // Everything that asks either of them for the address drops the answer or tests it for null
        // first. For a chat's big emoji that null means drawing the emoji with the typeface provider's
        // font. A render hands back a component, not the address, so only its own test counts.
        for (asked in listOfNotNull(other.takeIf { static }, drawable)) {
            val theirs = callers(bundle, signature(asked))
            assertTrue("$name: nothing calls ${signature(asked)}", theirs.isNotEmpty())
            theirs.forEach { caller ->
                fates(caller, signature(asked)).forEach { fate ->
                    assertTrue("$name: ${signature(caller)} keeps ${signature(asked)}'s answer without a null test ($fate)",
                        fate == "dropped" || fate == "tested")
                }
            }
        }
    }
}
