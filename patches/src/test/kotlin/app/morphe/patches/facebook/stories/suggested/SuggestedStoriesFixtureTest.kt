/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The anchors of Hide suggested stories on every declared Facebook build, found the way the patch
 * finds them: one post-processing method building the tray data through its one constructor, one
 * bucket list in it, one classic tray receiver reading that list, one bucket flag a tree class
 * answers with is_story_bucket_suggested, one label enum naming SUGGESTED and one static helper
 * answering it, one bucket method answering the type enum that names the tray's cards, and the
 * seen flag and owner accessor the Page and seen switches read. Then the hook goes first in the
 * constructor, in the list's own register, and the seven stubs of the extension the patch merges
 * are filled in.
 */
class SuggestedStoriesFixtureTest {
    /**
     * The tray data constructor's registers and the register of its bucket list, per build. Both
     * of 585's builds lay it out the same way.
     */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to (7 to 4),
    )

    /**
     * The bucket interface's type accessor and the enum it answers, per build and then by the
     * bundle's ABI. On 583 and 585 the two builds share the enum but not the accessor's name.
     */
    private val expectedType = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to ("Flp" to "LX/026l;"),
            "armeabi-v7a" to ("Fln" to "LX/026l;"),
        ),
    )

    /**
     * The bucket interface's seen flag, its owner accessor and the owner interface it answers, per
     * build and then by ABI (issues #313 and #317).
     */
    private val expectedOwner = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to Triple("DtG", "Fli", "LX/0eTt;"),
            "armeabi-v7a" to Triple("DtE", "Flg", "LX/0eTa;"),
        ),
    )

    /**
     * The bucket interface's expiration_time accessor, per build and then by ABI, which Hide seen
     * stories looks a bucket watched on this phone up by, with getId() (#218).
     */
    private val expectedExpires = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to "D3I",
            "armeabi-v7a" to "D3G",
        ),
    )

    /** The tray parser's registers and the register of its bucket list, per build (issues #313 and #317). */
    private val expectedParser = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to (7 to 5),
    )

    /** The query builders that set skip_srtt_item_list, per build and then by ABI (issue #21). */
    private val expectedSkips = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to setOf("LX/01oE;->A03", "LX/01oE;->A07"),
            "armeabi-v7a" to setOf("LX/01oE;->A03", "LX/01oE;->A07"),
        ),
    )

    private fun <T> Map<String, Map<String, T>>.pin(version: String, bundle: File): T =
        getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
            ?: throw AssertionError("${bundle.name} has no pin")

    private fun reference(instruction: Instruction): String {
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    private fun body(method: Method) = method.implementation!!.instructions.toList()

    @Test
    fun `each declared build has one tray data list, one suggested flag and one label helper`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val postClasses = FixtureDex.classesHolding(bundle, POST_PROCESS_RESULT)
                val posts = postClasses.flatMap { methodsHolding(it, POST_PROCESS_RESULT) }
                assertEquals("${bundle.name}: methods holding \"$POST_PROCESS_RESULT\"", 1, posts.size)
                assertTrue("${bundle.name}: ${posts.single()} isn't the post-processing shape", isPostProcess(posts.single()))
                val data = posts.single().returnType

                val receiverClasses = FixtureDex.classesHolding(bundle, OPTIMISTIC_RECEIVES)
                val receivers = receiverClasses.flatMap { methodsHolding(it, OPTIMISTIC_RECEIVES) }
                assertEquals("${bundle.name}: methods holding \"$OPTIMISTIC_RECEIVES\"", 1, receivers.size)
                val calls = interfaceFlagCalls(receivers.single())
                val interfaces = calls.map { it.definingClass }.toSet()

                val labels = FixtureDex.classesHolding(bundle, LABEL_NAMES.first()).filter { isEnumNaming(it, LABEL_NAMES) }
                assertEquals("${bundle.name}: label enums naming ${LABEL_NAMES.joinToString()}", 1, labels.size)
                val label = labels.single().type
                val typeEnums = FixtureDex.classesHolding(bundle, BUCKET_TYPE_NAMES.first())
                    .filter { isEnumNaming(it, BUCKET_TYPE_NAMES) }

                // One pass for the tree classes answering one of the receiver's flags with the
                // suggested key, and for the static helpers answering a label for a bucket.
                val readers = mutableListOf<ClassDef>()
                val helperClasses = mutableListOf<ClassDef>()
                var helpers = 0
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val flags = calls.filter { it.definingClass in classDef.interfaces }
                        if (flags.any { call -> classDef.methods.any { it.name == call.name && isTreeFlagReader(it, SUGGESTED_FLAG_FIELD) } }) {
                            readers += ImmutableClassDef.of(classDef)
                        }
                        val found = classDef.methods.count { isLabelHelper(it, interfaces, label) }
                        if (found > 0) {
                            helpers += found
                            helperClasses += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                assertTrue("${bundle.name}: no tree class answers a receiver flag with $SUGGESTED_FLAG_FIELD", readers.isNotEmpty())
                assertEquals("${bundle.name}: static label helpers for a bucket", 1, helpers)

                val (seen, owner, ownerType) = expectedOwner.pin(version, bundle)
                val read = setOf(data, IMMUTABLE_LIST, ownerType) + interfaces
                val support = FixtureDex.classes(bundle, read)
                assertEquals("${bundle.name}: a class the patch reads is missing", read, support.keys)

                val extension = ExtensionDex.classDef(SUGGESTED_STORIES)
                val context = PatchContexts.of(
                    postClasses + receiverClasses + labels + typeEnums + readers + helperClasses + support.values + extension,
                )
                val tray = with(context) { trayBuckets() }
                assertEquals("${bundle.name}: the tray data", data, tray.constructor.definingClass)
                assertEquals("${bundle.name}: the bucket list is the constructor's fourth parameter", 3, tray.list)
                assertEquals("${bundle.name}: the label enum", label, tray.label)
                assertEquals("${bundle.name}: the bucket type accessor", expectedType.pin(version, bundle), tray.type to tray.typeEnum)
                assertTrue("${bundle.name}: ${tray.typeEnum} isn't among the enums naming the cards",
                    tray.typeEnum in typeEnums.map { it.type })
                assertEquals("${bundle.name}: the seen flag, the owner accessor and the owner interface",
                    Triple(seen, owner, ownerType), Triple(tray.seen, tray.owner, tray.ownerType))
                assertEquals("${bundle.name}: getId() and the expiration_time accessor",
                    true to expectedExpires.pin(version, bundle), tray.id to tray.expires)
                assertTrue("${bundle.name}: ${tray.bucket} isn't among the receiver's flags", tray.bucket in interfaces)
                assertTrue("${bundle.name}: ${tray.bucket} isn't public",
                    AccessFlags.PUBLIC.isSet(support.getValue(tray.bucket).accessFlags))

                // The hook goes first in the one constructor, in the list's own register.
                val constructor = with(context) { mutableClassDefBy(data) }.methods.single { it.name == "<init>" }
                val (registers, list) = expected.getValue(version)
                assertEquals("${bundle.name}: constructor registers", registers, constructor.implementation!!.registerCount)
                val own = body(constructor)
                with(context) { fillBucketStubs(tray) }
                constructor.keepOnlyUnsuggestedBuckets(tray.list)
                val hooked = body(constructor)
                assertEquals("${bundle.name}: three instructions in", own.size + 3, hooked.size)
                assertEquals(KEPT_BUCKETS, reference(hooked[0]))
                val call = hooked[0] as RegisterRangeInstruction
                assertEquals("${bundle.name}: the list's register", list to 1, call.startRegister to call.registerCount)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, hooked[1].opcode)
                assertEquals(list, (hooked[1] as OneRegisterInstruction).registerA)
                assertEquals(Opcode.CHECK_CAST, hooked[2].opcode)
                assertEquals(IMMUTABLE_LIST, ((hooked[2] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("${bundle.name}: Facebook's own code follows", own.map { it.opcode }, hooked.drop(3).map { it.opcode })

                // The stubs call what the patch found.
                val stubs = with(context) { mutableClassDefBy(SUGGESTED_STORIES) }.methods
                fun first(name: String) = body(stubs.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) })
                assertEquals(tray.bucket, ((first(IS_BUCKET_STUB)[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("${tray.bucket}->${tray.flag}()Z", reference(first(SUGGESTED_STUB)[1]))
                assertEquals("${tray.bucket}->${tray.type}()${tray.typeEnum}", reference(first(TYPE_STUB)[1]))
                assertEquals(
                    "${tray.labelHelper.definingClass}->${tray.labelHelper.name}(${tray.bucket})$label",
                    reference(first(LABEL_STUB)[1]),
                )
                assertEquals("$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST", reference(first(COPY_STUB)[0]))
                assertEquals("${tray.bucket}->$seen()Z", reference(first(SEEN_STUB)[1]))
                val ownerStub = first(OWNER_TYPE_STUB)
                assertEquals("${tray.bucket}->$owner()$ownerType", reference(ownerStub[1]))
                assertEquals(Opcode.IF_EQZ, ownerStub[3].opcode)
                assertEquals("$ownerType->$TYPE_NAME()Ljava/lang/String;", reference(ownerStub[4]))
                assertEquals("${tray.bucket}->$GET_ID()Ljava/lang/String;", reference(first(BUCKET_ID_STUB)[1]))
                val expiresStub = first(EXPIRES_STUB)
                assertEquals("${tray.bucket}->${tray.expires}()J", reference(expiresStub[1]))
                assertEquals(Opcode.MOVE_RESULT_WIDE, expiresStub[2].opcode)
                assertEquals("Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;", reference(expiresStub[3]))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Issues #313 and #317: Home's tray and a story shown in the feed come inside the feed and build
     * their buckets in the one method holding StoriesTrayParser.parse, never through the tray data's
     * constructor. Its one ImmutableList is the tree buckets, which it hands on. The hook goes first
     * there, in the list's own register, and Facebook's code follows unchanged.
     */
    @Test
    fun `each declared build's tray parser filters its buckets first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expectedParser.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val classes = FixtureDex.classesHolding(bundle, TRAY_PARSE)
                val holders = classes.flatMap { methodsHolding(it, TRAY_PARSE) }
                assertEquals("${bundle.name}: methods holding \"$TRAY_PARSE\"", 1, holders.size)

                val context = PatchContexts.of(classes + ExtensionDex.classDef(SUGGESTED_STORIES))
                val (parser, list) = with(context) { trayParser() }
                assertEquals("${bundle.name}: the parser", holders.single().definingClass to holders.single().name,
                    parser.definingClass to parser.name)
                assertEquals("${bundle.name}: the bucket list is the parser's second parameter", 1, list)

                val method = with(context) { mutableClassDefBy(parser.definingClass) }.methods.single { it.name == parser.name && isTrayParse(it) }
                val (registers, register) = expectedParser.getValue(version)
                assertEquals("${bundle.name}: parser registers", registers, method.implementation!!.registerCount)
                val own = body(method)
                method.keepOnlyUnsuggestedBuckets(list)
                val hooked = body(method)
                assertEquals("${bundle.name}: three instructions in", own.size + 3, hooked.size)
                assertEquals(KEPT_BUCKETS, reference(hooked[0]))
                val call = hooked[0] as RegisterRangeInstruction
                assertEquals("${bundle.name}: the list's register", register to 1, call.startRegister to call.registerCount)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, hooked[1].opcode)
                assertEquals(register, (hooked[1] as OneRegisterInstruction).registerA)
                assertEquals(Opcode.CHECK_CAST, hooked[2].opcode)
                assertEquals(IMMUTABLE_LIST, ((hooked[2] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("${bundle.name}: Facebook's own code follows", own.map { it.opcode }, hooked.drop(3).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Issue #21: each declared build's two tray query builders set skip_srtt_item_list once, a
     * const-string and then the builder's (String, boolean) setter. The hook puts the extension's
     * answer in the boolean's register right before each setter, and leaves Facebook's code as it was.
     */
    @Test
    fun `each tray fetch asks the extension before it sets skip_srtt_item_list`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expectedSkips.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val classes = FixtureDex.classesHolding(bundle, SKIP_PROMPT_CARDS)
                val methods = classes.flatMap { methodsHolding(it, SKIP_PROMPT_CARDS) }
                fun key(method: Method) = "${method.definingClass}->${method.name}"
                assertEquals("${bundle.name}: methods holding \"$SKIP_PROMPT_CARDS\"", expectedSkips.pin(version, bundle), methods.map(::key).toSet())
                assertEquals("${bundle.name}: one method per name", 2, methods.size)

                val own = methods.associate { key(it) to body(it) }
                val sites = methods.associate { key(it) to promptCardSkips(it) }
                for ((name, found) in sites) {
                    assertEquals("${bundle.name}: $name sets $SKIP_PROMPT_CARDS once, straight to its setter", 1, found?.size)
                }

                val context = PatchContexts.of(classes + ExtensionDex.classDef(SUGGESTED_STORIES))
                assertEquals("${bundle.name}: places hooked", 2, with(context) { hookPromptCardSkips() })
                for (classDef in classes) {
                    val mutable = with(context) { mutableClassDefBy(classDef.type) }
                    for (method in mutable.methods.filter { key(it) in own }) {
                        val (index, register) = sites.getValue(key(method))!!.single()
                        val before = own.getValue(key(method))
                        val hooked = body(method)
                        assertEquals("${bundle.name}: ${key(method)} two instructions in", before.size + 2, hooked.size)
                        assertEquals(SKIP_PROMPT_CARDS_HOOK, reference(hooked[index]))
                        val call = hooked[index] as RegisterRangeInstruction
                        assertEquals("${bundle.name}: the boolean's register", register to 1, call.startRegister to call.registerCount)
                        assertEquals(Opcode.MOVE_RESULT, hooked[index + 1].opcode)
                        assertEquals(register, (hooked[index + 1] as OneRegisterInstruction).registerA)
                        val setter = hooked[index + 2] as FiveRegisterInstruction
                        assertEquals("${bundle.name}: the setter still takes the boolean", register, setter.registerE)
                        assertEquals("${bundle.name}: Facebook's own code around it",
                            before.map { it.opcode }, (hooked.take(index) + hooked.drop(index + 2)).map { it.opcode })
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
