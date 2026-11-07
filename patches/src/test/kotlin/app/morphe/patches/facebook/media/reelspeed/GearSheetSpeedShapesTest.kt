/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the gear menu's speed sheet builder falls back to normal speed when it finds no player
 * (#199), in both of the lookups 585 builds use: a static call taking the PlayerOrigin (arm64), and
 * a no-argument call on what a PlayerOrigin call answered straight before (the Android 9+ build,
 * #102).
 */
class GearSheetSpeedShapesTest {
    private val player = "Lfixture/Player;"
    private val lookup = "Lfixture/Lookup;"
    private val players = "Lfixture/Players;"

    private fun sheet(find: String) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sheet;", "A02", listOf(PLAYER_ORIGIN, players).map { ImmutableMethodParameter(it, null, null) }, "F",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                $find
                if-eqz v0, :miss
                invoke-virtual { v0 }, $player->ErA()F
                move-result v1
                :back
                return v1
                :miss
                const/high16 v1, 0x3f800000
                goto :back
            """.trimIndent(),
        )
    }

    private val staticFind = """
        invoke-static { p0 }, Lfixture/Finder;->A00($PLAYER_ORIGIN)$player
        move-result-object v0
    """.trimIndent()

    private fun originFind(origin: String = PLAYER_ORIGIN, receiver: String = "v0") = """
        invoke-virtual { p1, p0 }, $players->A0H($origin)$lookup
        move-result-object v0
        invoke-virtual { $receiver }, $lookup->A03()$player
        move-result-object v0
    """.trimIndent()

    @Test
    fun `the normal speed loaded for a player that isn't found is found after either lookup`() {
        val arm64 = gearMisses(sheet(staticFind), player).single()
        assertEquals("arm64's normal speed", 6, arm64.index)
        assertEquals("arm64's speed register", 1, arm64.speed)
        assertEquals("arm64's getter", "ErA", arm64.getter.name)

        val a9 = gearMisses(sheet(originFind()), player).single()
        assertEquals("the Android 9+ build's normal speed", 8, a9.index)
        assertEquals("the Android 9+ build's speed register", 1, a9.speed)
        assertEquals("the Android 9+ build's getter", "ErA", a9.getter.name)
    }

    @Test
    fun `a lookup that doesn't start from a PlayerOrigin isn't one`() {
        assertEquals("a call on another register", 0, gearMisses(sheet(originFind(receiver = "p1")), player).size)
        assertEquals("a call before it taking no PlayerOrigin", 0, gearMisses(sheet(originFind(origin = "Ljava/lang/String;")), player).size)
        assertEquals("another player class", 0, gearMisses(sheet(originFind()), "Lfixture/Other;").size)
    }
}
