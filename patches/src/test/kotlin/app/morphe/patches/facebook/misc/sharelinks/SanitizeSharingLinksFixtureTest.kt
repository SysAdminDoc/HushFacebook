/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanitize sharing links on every Facebook build the bundle declares: the patch runs over the real
 * classes, every link each tagging method returns goes through the extension, and a group's share
 * reaches the tracker the patch cleans. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class SanitizeSharingLinksFixtureTest {
    private val string = "Ljava/lang/String;"
    private val integer = "Ljava/lang/Integer;"
    private val story = "Lcom/facebook/graphql/model/GraphQLStory;"
    private val sanitize = "Lapp/morphe/extension/facebook/misc/LinkCleaner;->sanitizeShared($string)$string"

    /** The mutation a group's Share button sends, in the method that goes on to make the link. */
    private val groupShare = "ViewerIntendedToShareGroup"

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun isMainTracker(method: Method) = method.returnType == string &&
        method.parameters() == listOf(FB_USER_SESSION, integer, integer, string, string, "Z") &&
        holdsString(method, "ExternalShareTracker")

    @Test
    fun `each declared build's shared links go through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val trackers = FixtureDex.classesHolding(bundle, "ExternalShareTracker")
                    .filter { owner -> owner.methods.any(::isMainTracker) }
                assertEquals("$name: classes with ExternalShareTracker's main method", 1, trackers.size)
                val tracker = trackers.single()
                val main = tracker.methods.single(::isMainTracker)

                val appenderOwners = FixtureDex.classesHolding(bundle, "sfnsn")
                val appenders = appenderOwners.flatMap { owner ->
                    owner.methods.filter {
                        it.returnType == string && it.parameters() == listOf(string, string) && holdsString(it, "sfnsn")
                    }
                }
                assertEquals("$name: sfnsn appenders", 1, appenders.size)

                val refShares = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.typeSection.any { it == story }
                }) {
                    it.returnType == string && it.parameters() == listOf(story, string) &&
                        holdsString(it, "ref") && holdsString(it, "share")
                }
                assertEquals("$name: ref=share appenders", 1, refShares.size)

                val live = FixtureDex.classes(bundle, setOf(LIVE_SHARE_DIALOG_MODEL)).getValue(LIVE_SHARE_DIALOG_MODEL)
                val liveLinks = live.methods.filter {
                    it.returnType == string && it.parameters().isEmpty() && holdsString(it, "sfnsn")
                }
                assertEquals("$name: the live dialog's link", 1, liveLinks.size)

                val cleaned = listOf(main, shareLinkTracker(tracker.methods), extidTracker(tracker.methods),
                    appenders.single(), refShares.single(), liveLinks.single())
                val owners = appenderOwners + tracker + live +
                    FixtureDex.classes(bundle, setOf(refShares.single().definingClass)).values +
                    ExtensionDex.classDef(SETTINGS_STATUS)

                // A fingerprint keeps its match, and each ABI is its own Redex output.
                listOf(
                    ExternalShareTrackerFingerprint,
                    SfnsnAppenderFingerprint,
                    RefShareAppenderFingerprint,
                    LiveShareLinkFingerprint,
                ).forEach { it.clearMatch() }
                val context = PatchContexts.of(owners.associateBy { it.type }.values)
                sanitizeSharingLinksPatch.execute(context)

                cleaned.forEach { stock ->
                    val where = "$name: ${stock.descriptor()}"
                    val code = context.mutableClassDefBy(stock.definingClass).methods
                        .single { it.descriptor() == stock.descriptor() }.code()
                    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
                    assertEquals("$where: its returns", stock.code().count { it.opcode == Opcode.RETURN_OBJECT }, returns.size)
                    returns.forEach { at ->
                        val register = (code[at] as OneRegisterInstruction).registerA
                        val ask = code[at - 2]
                        assertEquals("$where: return $at asks the extension", sanitize, ask.called()?.descriptor())
                        assertEquals("$where: return $at hands over its link", register, (ask as RegisterRangeInstruction).startRegister)
                        assertEquals("$where: return $at takes the answer", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
                        assertEquals("$where: return $at gives the answer", register, (code[at - 1] as OneRegisterInstruction).registerA)
                    }
                }

                assertGroupShareReachesTracker(name, FixtureDex.classesHolding(bundle, groupShare), tracker, main)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "sanitizeSharingLinks" }
                assertEquals("$name: SettingsStatus.sanitizeSharingLinks() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** A group's link has no builder of its own: it comes from the tracker, directly or one step away. */
    private fun assertGroupShareReachesTracker(name: String, owners: List<ClassDef>, tracker: ClassDef, main: Method) {
        val sharers = owners.flatMap { owner -> owner.methods.filter { holdsString(it, groupShare) } }
        assertTrue("$name: nothing sends $groupShare", sharers.isNotEmpty())
        val asked = sharers.flatMap { it.code() }.mapNotNull { it.called() }
            .filter { it.definingClass == tracker.type && it.returnType == string }
        assertTrue("$name: a group's share asks the tracker for no link", asked.isNotEmpty())
        asked.forEach { reference ->
            val method = tracker.methods.single { it.descriptor() == reference.descriptor() }
            assertTrue("$name: ${method.descriptor()} doesn't reach the tracker's main method",
                method.descriptor() == main.descriptor() ||
                    method.code().any { it.called()?.descriptor() == main.descriptor() })
        }
    }
}
