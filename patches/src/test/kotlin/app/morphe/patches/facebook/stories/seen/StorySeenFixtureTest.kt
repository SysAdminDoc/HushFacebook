/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.seen

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The anchor of View stories anonymously on every Facebook build the bundle declares: one query
 * class for the seen mutation, one builder creating it, and one sender, which the hook goes first
 * in. Nothing else in the APK creates the mutation or calls the builder, so returning from the
 * sender is the only report of viewed stories stopped; the sender's one caller is the seen
 * helper's flush; and replies and reactions are built in another class. Reads the fixture bundles
 * from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StorySeenFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun calls(method: Method, target: Method): Boolean =
        method.implementation?.instructions?.any { instruction ->
            val ref = instruction.call
            ref != null && ref.definingClass == target.definingClass && ref.name == target.name &&
                ref.parameterTypes.map { it.toString() } == target.parameterTypes.map { it.toString() }
        } == true

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return method.implementation!!.registerCount - self - method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
    }

    @Test
    fun `each declared build sends viewed stories from one sender, the only way the seen mutation goes out`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val mutation = seenQueryClass(bundle)

                val owners = FixtureDex.classesHolding(bundle, STORY_IDS)
                val senders = owners.mapNotNull { seenSender(it, mutation) }
                assertEquals("$name: seen senders", 1, senders.size)
                val sender = senders.single()
                assertTrue("$name: the sender has no local register", locals(sender) >= 1)
                val owner = owners.single { it.type == sender.definingClass }
                val builder = owner.methods.single { isRequestBuilder(it, mutation) }

                // Nothing else creates the mutation (or, from 586, makes a query named it), and
                // nothing else calls the builder, so the sender is the one way the report of viewed
                // stories goes out.
                val creators = if (mutation == null) {
                    FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.stringSection.any { it == SEEN_MUTATION } }) {
                        makesSeenQuery(it)
                    }
                } else {
                    FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == mutation } }) {
                        method -> method.implementation?.instructions?.any { instruction ->
                            instruction.opcode == Opcode.NEW_INSTANCE &&
                                ((instruction as ReferenceInstruction).reference as TypeReference).type == mutation
                        } == true
                    }
                }
                assertEquals("$name: methods creating ${mutation ?: "a query named $SEEN_MUTATION"}", listOf(builder.name),
                    creators.map { it.name })
                assertEquals("$name: ${creators.single().definingClass} isn't the builder's class", owner.type,
                    creators.single().definingClass)
                val builderCallers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == owner.type && it.name == builder.name }
                }) { calls(it, builder) }
                assertEquals("$name: callers of the builder", listOf(sender.name), builderCallers.map { it.name })

                // Facebook's own way out of the sender comes before the builder: an empty set of
                // cards returns without sending, which is the path the hook takes too.
                val code = sender.implementation!!.instructions.toList()
                val isEmpty = code.indexOfFirst { it.call?.let { c -> c.definingClass == "Ljava/util/Set;" && c.name == "isEmpty" } == true }
                val build = code.indexOfFirst { it.call?.name == builder.name && it.call?.definingClass == owner.type }
                assertTrue("$name: the sender doesn't ask whether it has cards before building", isEmpty in 0 until build)
                assertTrue("$name: the sender has no return-void", code.any { it.opcode == Opcode.RETURN_VOID })

                // The seen helper's flush, which takes the reason it flushes for, calls the sender.
                // 587 calls it from the story viewer's controller too, in a class of its own; the
                // hook sits in the sender, so it covers both.
                val senderCallers = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == owner.type && it.name == sender.name }
                }) { calls(it, sender) }
                assertEquals("$name: callers of the sender", SENDER_CALLERS[version] ?: 1, senderCallers.size)
                assertEquals("$name: classes calling the sender", senderCallers.size, senderCallers.map { it.definingClass }.toSet().size)
                // 587 made the flush static, taking the helper first and a callback last.
                val flush = senderCallers.filter {
                    val types = it.parameterTypes.map { type -> type.toString() }
                    it.returnType == "V" && (types == listOf("Ljava/lang/String;") ||
                        AccessFlags.STATIC.isSet(it.accessFlags) &&
                        types == listOf(it.definingClass, "Ljava/lang/String;", "Lkotlin/jvm/functions/Function0;"))
                }
                assertEquals("$name: callers shaped like the flush, (String)V or (helper, String, Function0)V", 1, flush.size)

                // Replies and reactions go out from a class of their own, which the hook leaves alone.
                val viewerMutations = FixtureDex.classesHolding(bundle, "surface=story_viewer")
                assertEquals("$name: classes building story viewer mutations", 2, viewerMutations.size)
                val replies = viewerMutations.single { it.type != owner.type }
                // 584 reads "reply_target_users" out of a string table, but still names the reply type.
                assertTrue("$name: the other story viewer mutation isn't the reply one",
                    replies.methods.any { holdsString(it, "You need to handle all cases returned from getStoryReplyType") })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

/** Builds where the story viewer's controller calls the seen sender beside the helper's flush (587 arm64 LX/0CBG;). */
private val SENDER_CALLERS = mapOf("587.0.0.39.55" to 2)

/** Builds whose seen request builder makes the query itself, with no query class of its own (586 on; 587 arm64 LX/0CE7;->getRequest). */
internal val SEEN_QUERY_IN_BUILDER = setOf("587.0.0.39.55")

/**
 * The seen mutation's query class in [bundle], one class whose constructor names it, or null on a
 * build in [SEEN_QUERY_IN_BUILDER], which has none.
 */
internal fun seenQueryClass(bundle: File): String? {
    val mutations = FixtureDex.classesHolding(bundle, SEEN_ROOT_FIELD).filter(::isSeenMutation)
    val made = SEEN_QUERY_IN_BUILDER.any { bundle.name.contains("-$it-") }
    assertEquals("${bundle.name}: query classes naming $SEEN_MUTATION", if (made) 0 else 1, mutations.size)
    return mutations.singleOrNull()?.type
}
