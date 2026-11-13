/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enum names are intake evidence, not proof that either music type draws the served prompt. */
class KeptBucketTypeFixtureTest {
    // These are the bucket-accessor enum types already pinned by SuggestedStoriesFixtureTest, by
    // build and then by the bundle's ABI, since Redex can name the enum differently per ABI.
    private val types = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to "LX/02d0;",
            "armeabi-v7a" to "LX/02d6;",
            "arm64-v8a-minapi28-storeless" to "LX/02bk;",
        ),
    )

    @Test
    fun `every supported bucket enum distinguishes STORY and the two actual music names`() {
        val declared = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared fixture builds", types.keys, declared)
        val checked = mutableSetOf<String>()
        for ((version, byAbi) in types) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val type = byAbi[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("${bundle.name} has no pin")
                val enum = FixtureDex.classes(bundle, setOf(type))[type]!!
                assertTrue("${bundle.name}: the actual bucket enum", isEnumNaming(enum, BUCKET_TYPE_NAMES))
                val names = enum.methods.single { it.name == "<clinit>" }.implementation!!.instructions
                    .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
                assertTrue("${bundle.name}: the literal ordinary type", "STORY" in names)
                assertEquals("${bundle.name}: actual bucket music names", setOf("MUSIC_MIXTAPE_STORY", "MUSIC_STORY_MID_CARD"),
                    names.filter { it.startsWith("MUSIC_") }.toSet())
                checked += version
            }
        }
        assertEquals("each supported build had a fixture", types.keys, checked)
    }
}
