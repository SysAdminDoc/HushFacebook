/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.video.GRAPHQL_MEDIA
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY
import app.morphe.patches.facebook.downloads.video.GRAPHQL_STORY_ATTACHMENT
import app.morphe.patches.facebook.downloads.video.IMMUTABLE_LIST
import app.morphe.patches.facebook.downloads.video.MENU
import app.morphe.patches.facebook.downloads.video.PLAYER_ORIGIN
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.downloads.video.VIEW
import app.morphe.patches.facebook.downloads.video.VideoFeedStoryMenuFingerprint
import app.morphe.patches.facebook.downloads.video.fillsPostMenu
import app.morphe.patches.facebook.downloads.video.opensPostMenu
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
 * Save photo in a post's menu on every declared build: one call fills every post menu, and right
 * after it the creator calls a helper of its own that hands the extension the menu, the view, what
 * the menu is for, Facebook's Download icon and the real names of the four getters the item reads:
 * the post's attachments, an attachment's media, the post a share wraps and an album's attachments.
 */
class PostMenuPhotoFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    @Before
    @After
    fun forgetTheLastMatch() = VideoFeedStoryMenuFingerprint.clearMatch()

    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun holds(method: Method, string: String) = method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun isVideoMenu(method: Method): Boolean {
        val parameters = method.parameterTypes.map(CharSequence::toString)
        return method.returnType == "V" && parameters.size >= 3 && parameters[0] == MENU && parameters[1] == VIEW &&
            parameters.last() == PLAYER_ORIGIN && holds(method, "VideoFeedStoryMenuHelper")
    }

    /** The one public no-argument getter of [owner] named [name], returning [returnType]. */
    private fun assertGetter(where: String, owner: ClassDef, name: String, returnType: String) {
        val getters = owner.methods.filter {
            it.name == name && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        assertEquals("$where: ${owner.type}->$name()", listOf(returnType), getters.map { it.returnType })
    }

    @Test
    fun `each declared build's post menu calls the photo item right after Facebook fills it`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetTheLastMatch()
                val name = bundle.name
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
                val callIndex = site.implementation!!.instructions.indexOfFirst(::fills)
                val call = site.implementation!!.instructions.toList()[callIndex] as RegisterRangeInstruction

                val props = signature[2]
                // 585 asks a string table for the stock row's name, so the table's class is read too.
                val tables = videoMenu.implementation!!.instructions.filter(::isStringTableCall)
                    .mapNotNull { it.methodRef()?.definingClass }.toSet()
                val types = setOf(videoMenu.definingClass, base, site.definingClass, props, GRAPHQL_STORY, GRAPHQL_STORY_ATTACHMENT) + tables
                val classes = FixtureDex.classes(bundle, types)
                assertEquals("$name: classes read", types, classes.keys)
                // 587 builds the stock Download row in a helper the menu calls, with a lambda the menu makes.
                val called = videoMenu.implementation!!.instructions.mapNotNull { it.methodRef()?.definingClass } +
                    videoMenu.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
                        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                val helpers = FixtureDex.classes(bundle, called.toSet() - types)

                val context = PatchContexts.of(classes.values + helpers.values)
                with(context) { postMenuPhotoItem()() }

                val creator = context.mutableClassDefBy(site.definingClass)
                val helper = creator.methods.single { it.name == PHOTO_MENU_HELPER }
                assertTrue("$name: the helper is static", AccessFlags.STATIC.isSet(helper.accessFlags))
                assertEquals("$name: the helper takes the menu, the view and the props", listOf(MENU, VIEW, props),
                    helper.parameterTypes.map(CharSequence::toString))

                val patched = creator.methods.single { MethodUtil.methodSignaturesMatch(it, site) }.implementation!!.instructions.toList()
                assertTrue("$name: Facebook's fill stays where it was", fills(patched[callIndex]))
                val hook = patched[callIndex + 1] as RegisterRangeInstruction
                assertEquals("$name: the hook right after the fill", Opcode.INVOKE_STATIC_RANGE, patched[callIndex + 1].opcode)
                assertEquals("$name: the hook calls the helper", PHOTO_MENU_HELPER, patched[callIndex + 1].methodRef()!!.name)
                assertEquals("$name: the hook hands over the menu, the view and the props",
                    listOf(call.startRegister + 1, 3), listOf(hook.startRegister, hook.registerCount))

                val body = helper.implementation!!.instructions.toList()
                val icon = body.filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral }.filter { it ushr 24 == 0x7f }
                assertEquals("$name: Facebook's own Download icon, an app resource, is handed over", 1, icon.size)
                val item = body.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }.single()
                assertEquals("$name: what the menu is for comes from the props", props, item.definingClass)
                assertEquals("$name: as an Object", "Ljava/lang/Object;", item.type)
                val names = body.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                assertEquals("$name: four getter names", 4, names.size)
                val story = classes.getValue(GRAPHQL_STORY)
                val attachment = classes.getValue(GRAPHQL_STORY_ATTACHMENT)
                assertGetter(name, story, names[0], IMMUTABLE_LIST)
                assertGetter(name, attachment, names[1], GRAPHQL_MEDIA)
                assertGetter(name, story, names[2], GRAPHQL_STORY)
                assertGetter(name, attachment, names[3], IMMUTABLE_LIST)
                val add = body.single { it.opcode == Opcode.INVOKE_STATIC_RANGE }
                assertEquals("$name: the extension's add", ADD_PHOTO_ITEM, add.methodRef().toString())
                assertEquals("$name: with every argument", 8, (add as RegisterRangeInstruction).registerCount)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
