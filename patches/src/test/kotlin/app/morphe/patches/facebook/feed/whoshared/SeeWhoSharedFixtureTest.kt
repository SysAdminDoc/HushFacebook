/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.whoshared

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.downloads.video.MENU
import app.morphe.patches.facebook.downloads.video.PLAYER_ORIGIN
import app.morphe.patches.facebook.downloads.video.VIEW
import app.morphe.patches.facebook.downloads.video.VideoFeedStoryMenuFingerprint
import app.morphe.patches.facebook.downloads.video.fillsPostMenu
import app.morphe.patches.facebook.downloads.video.opensPostMenu
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * See who shared on every declared build: Facebook's reshares list launcher is one static
 * (Context, GraphQLStory, String) method holding its message, the extension's stub calls it with
 * the post and answers true, and the post menu calls a helper of its own right after Facebook
 * fills it, handing the extension the menu, the view, what the menu is for and the icon. Reads
 * the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SeeWhoSharedFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Before
    @After
    fun forgetTheLastMatch() {
        VideoFeedStoryMenuFingerprint.clearMatch()
        ResharesListLauncherFingerprint.clearMatch()
    }

    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun holds(method: Method, string: String) = method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun isLauncher(method: Method) = method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, GRAPHQL_STORY, STRING) &&
        AccessFlags.STATIC.isSet(method.accessFlags) && holds(method, RESHARES_LIST_MESSAGE)

    private fun isVideoMenu(method: Method): Boolean {
        val parameters = method.parameterTypes.map(CharSequence::toString)
        return method.returnType == "V" && parameters.size >= 3 && parameters[0] == MENU && parameters[1] == VIEW &&
            parameters.last() == PLAYER_ORIGIN && holds(method, "VideoFeedStoryMenuHelper")
    }

    @Test
    fun `each declared build opens Facebook's shares list from a row the post menu adds`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetTheLastMatch()
                val name = bundle.name

                val launchers = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == RESHARES_LIST_MESSAGE } }, ::isLauncher)
                assertEquals("$name: Facebook's reshares list launcher", 1, launchers.size)
                val launcher = launchers.single()

                val menus = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == "VideoFeedStoryMenuHelper" } }, ::isVideoMenu)
                assertEquals("$name: the video feed's menu", 1, menus.size)
                val videoMenu = menus.single()
                val signature = videoMenu.parameterTypes.map(CharSequence::toString)
                val base = FixtureDex.classes(bundle, setOf(videoMenu.definingClass)).getValue(videoMenu.definingClass).superclass!!

                // 586 fills through an interface of the base, from two builders; the post's takes the player origin.
                val baseClass = FixtureDex.classes(bundle, setOf(base)).getValue(base)
                val owners = setOf(base) + baseClass.interfaces
                fun fills(instruction: Instruction) = fillsPostMenu(instruction, baseClass, videoMenu.name, signature)
                val sites = FixtureDex.methodsWhere(bundle, { dex ->
                    dex.methodSection.any { it.definingClass in owners && it.name == videoMenu.name }
                }) { method ->
                    opensPostMenu(method, signature) && method.implementation?.instructions?.any(::fills) == true
                }
                assertEquals("$name: one place fills a post menu", 1, sites.size)
                val site = sites.single()
                val callIndex = site.code().indexOfFirst(::fills)
                val call = site.code()[callIndex] as RegisterRangeInstruction

                val props = signature[2]
                // 585 asks a string table for the stock row's name, so the table's class is read too.
                val tables = videoMenu.implementation!!.instructions.filter(::isStringTableCall)
                    .mapNotNull { it.methodRef()?.definingClass }.toSet()
                val types = setOf(videoMenu.definingClass, base, site.definingClass, props, GRAPHQL_STORY,
                    GRAPHQL_STORY_ATTACHMENT, launcher.definingClass) + tables
                val classes = FixtureDex.classes(bundle, types)
                assertEquals("$name: classes read", types, classes.keys)

                val context = PatchContexts.of(classes.values +
                    listOf(ExtensionDex.classDef(WHO_SHARED), ExtensionDex.classDef(SETTINGS_STATUS)))
                seeWhoSharedPatch.execute(context)

                // ---- the stub: the post cast, Facebook's launcher, true --------------------------
                val stub = context.mutableClassDefBy(WHO_SHARED).methods.single { it.name == OPEN_LIST }.code()
                assertEquals("$name: the post is cast first", Opcode.CHECK_CAST, stub[0].opcode)
                assertEquals(GRAPHQL_STORY, ((stub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: then Facebook's launcher", Opcode.INVOKE_STATIC, stub[1].opcode)
                assertEquals(launcherCall(launcher), stub[1].methodRef().toString())
                assertEquals("$name: the stub answers true", 1, (stub[2] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(Opcode.RETURN, stub[3].opcode)

                // ---- the menu: the helper right after Facebook fills it -------------------------
                val creator = context.mutableClassDefBy(site.definingClass)
                val helper = creator.methods.single { it.name == WHO_SHARED_MENU_HELPER }
                assertTrue("$name: the helper is static", AccessFlags.STATIC.isSet(helper.accessFlags))
                assertEquals("$name: the helper takes the menu, the view and the props", listOf(MENU, VIEW, props),
                    helper.parameterTypes.map(CharSequence::toString))
                val patched = creator.methods.single { MethodUtil.methodSignaturesMatch(it, site) }.code()
                assertTrue("$name: Facebook's fill stays where it was", fills(patched[callIndex]))
                val hook = patched[callIndex + 1] as RegisterRangeInstruction
                assertEquals("$name: the hook calls the helper", WHO_SHARED_MENU_HELPER, patched[callIndex + 1].methodRef()!!.name)
                assertEquals("$name: the hook hands over the menu, the view and the props",
                    listOf(call.startRegister + 1, 3), listOf(hook.startRegister, hook.registerCount))
                val add = helper.code().single { it.opcode == Opcode.INVOKE_STATIC_RANGE }
                assertEquals("$name: the extension's add", ADD_WHO_SHARED_ITEM, add.methodRef().toString())
                assertEquals("$name: the menu, the view, the item and the icon", 4, (add as RegisterRangeInstruction).registerCount)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "whoShared" }
                assertEquals("$name: SettingsStatus.whoShared() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
