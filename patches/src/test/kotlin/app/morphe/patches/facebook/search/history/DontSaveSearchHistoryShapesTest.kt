/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.history

import app.morphe.ExtensionDex
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.misc.analytics.ANALYTICS_EVENTS
import app.morphe.patches.facebook.misc.analytics.KEEP_EVENT_OUT
import app.morphe.patches.facebook.misc.analytics.PRIVACY_CONTEXT
import app.morphe.patches.facebook.misc.analytics.keepEventsOut
import app.morphe.patches.facebook.misc.analytics.notLoggedEvent
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Recent list's put in Don't save search history (#196), without a Facebook build: the one
 * Map.put whose map was just loaded from a Map field of the save method's own class goes to the
 * extension with the same registers in the same call form, and any other put is left alone. And
 * the analytics event factory (#221): only a private method taking details and a name, reading a
 * privacy context and answering one static of its own class is one, and the hook asks with the name.
 */
class DontSaveSearchHistoryShapesTest {
    private val owner = "Lfixture/RecentSearches;"
    private val objectType = "Ljava/lang/Object;"
    private val put = ImmutableMethodReference("Ljava/util/Map;", "put", listOf(objectType, objectType), objectType)
    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun loadMap(into: Int, fieldOwner: String = owner, type: String = "Ljava/util/Map;") =
        ImmutableInstruction22c(Opcode.IGET_OBJECT, into, 6, ImmutableFieldReference(fieldOwner, "A02", type))

    private fun putInto(map: Int, key: Int = 1, value: Int = 4) =
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, map, key, value, 0, 0, put)

    private fun save(vararg body: Instruction, registers: Int = 8): Method = ImmutableMethod(
        owner,
        "A03",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null,
        null,
        ImmutableMethodImplementation(registers, body.toList() + returnVoid, null, null),
    )

    @Test
    fun `the put of its own map goes to the extension with the same registers`() {
        val method = save(loadMap(2), putInto(2))
        assertEquals(1, recentListPut(method))
        val mutable = MutableMethod(method)
        mutable.keepRecentAt(1)
        val patched = mutable.implementation!!.instructions.toList()
        assertEquals("nothing is added or taken out", 3, patched.size)
        assertEquals(Opcode.INVOKE_STATIC, patched[1].opcode)
        assertEquals(KEEP_RECENT, (patched[1] as ReferenceInstruction).reference.toString())
        val call = patched[1] as FiveRegisterInstruction
        assertEquals("the map, the filter and the list", listOf(2, 1, 4), listOf(call.registerC, call.registerD, call.registerE))
        assertEquals(3, call.registerCount)
        assertEquals("the load stays", Opcode.IGET_OBJECT, patched[0].opcode)
    }

    @Test
    fun `a range put stays a range call`() {
        // A field load writes v0 to v15 only, so a range put of a map loaded right before starts low too.
        val rangePut = ImmutableInstruction3rc(Opcode.INVOKE_INTERFACE_RANGE, 12, 3, put)
        val method = save(loadMap(12), rangePut, registers = 16)
        assertEquals(1, recentListPut(method))
        val mutable = MutableMethod(method)
        mutable.keepRecentAt(1)
        val call = mutable.implementation!!.instructions.toList()[1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(KEEP_RECENT, (call as ReferenceInstruction).reference.toString())
        assertEquals(12, (call as RegisterRangeInstruction).startRegister)
        assertEquals(3, call.registerCount)
    }

    @Test
    fun `other puts are left alone`() {
        assertNull("a map of another class", recentListPut(save(loadMap(2, fieldOwner = "Lfixture/Other;"), putInto(2))))
        assertNull("a field that isn't a Map", recentListPut(save(loadMap(2, type = "Ljava/util/HashMap;"), putInto(2))))
        assertNull("a map loaded too long before", recentListPut(save(loadMap(2), returnVoid, returnVoid, returnVoid, returnVoid, putInto(2))))
        assertNull("its answer is read", recentListPut(save(loadMap(2), putInto(2), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))))
        assertNull("a map register written again after the load",
            recentListPut(save(loadMap(2), ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2), putInto(2))))
        assertNull("two puts, so neither is known to be the Recent list's",
            recentListPut(save(loadMap(2), putInto(2), loadMap(3), putInto(3))))
        assertNull("no put at all", recentListPut(save(loadMap(2))))
        assertNull("a wide write over the map register after the load",
            recentListPut(save(loadMap(2), ImmutableInstruction51l(Opcode.CONST_WIDE, 1, 0L), putInto(2))))
        // if-eqz (two code units) jumps over the load (two more) straight to the put, at address 4.
        assertNull("a jump that lands between the load and the put",
            recentListPut(save(ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4), loadMap(2), putInto(2))))
    }

    @Test
    fun `a map loaded as far back as the reach allows is still found`() {
        val nop = ImmutableInstruction10x(Opcode.NOP)
        val filler = Array(MAP_LOAD_REACH - 1) { nop }
        assertEquals(MAP_LOAD_REACH, recentListPut(save(loadMap(2), *filler, putInto(2))))
    }

    @Test
    fun `the extension takes the put's map, filter and list`() {
        val hook = ExtensionDex.classDef(SEARCH_HISTORY).methods.singleOrNull { it.descriptor() == KEEP_RECENT }
        assertTrue("$KEEP_RECENT is not in the extension", hook != null)
        assertTrue("$KEEP_RECENT is static", AccessFlags.STATIC.isSet(hook!!.accessFlags))
    }

    private val logger = "Lfixture/Logger;"
    private val readPrivacy = ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3,
        ImmutableFieldReference("Lfixture/Details;", "A01", PRIVACY_CONTEXT))

    private fun loadNotLogged(into: Int, fieldOwner: String = logger) =
        ImmutableInstruction21c(Opcode.SGET_OBJECT, into, ImmutableFieldReference(fieldOwner, "A04", "Lfixture/NotLogged;"))

    private fun factory(
        vararg body: Instruction,
        flags: Int = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
        parameters: List<String> = listOf("Lfixture/Details;", "Ljava/lang/String;"),
    ): Method = ImmutableMethod(
        logger,
        "A00",
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "Lfixture/Event;",
        flags,
        null,
        null,
        ImmutableMethodImplementation(5, body.toList(), null, null),
    )

    private fun answer(register: Int = 1) = ImmutableInstruction11x(Opcode.RETURN_OBJECT, register)

    @Test
    fun `an event factory answers its own not-logged event`() {
        val method = factory(readPrivacy, loadNotLogged(1), answer())
        assertEquals("$logger->A04:Lfixture/NotLogged;", notLoggedEvent(method).toString())
        val mutable = MutableMethod(method)
        mutable.keepEventsOut(notLoggedEvent(method)!!)
        val patched = mutable.implementation!!.instructions.toList()
        assertEquals(3 + 6, patched.size)
        assertEquals(KEEP_EVENT_OUT, (patched[0] as ReferenceInstruction).reference.toString())
        assertEquals("the event's name, the last register", 4, (patched[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$logger->A04:Lfixture/NotLogged;", (patched[3] as ReferenceInstruction).reference.toString())
        assertEquals("Facebook's code follows as it was", listOf(Opcode.IGET_OBJECT, Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT),
            patched.drop(6).map { it.opcode })
    }

    @Test
    fun `other methods aren't event factories`() {
        assertNull("static", notLoggedEvent(factory(readPrivacy, loadNotLogged(1), answer(),
            flags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value)))
        assertNull("not private", notLoggedEvent(factory(readPrivacy, loadNotLogged(1), answer(), flags = AccessFlags.PUBLIC.value)))
        assertNull("no name last", notLoggedEvent(factory(readPrivacy, loadNotLogged(1), answer(),
            parameters = listOf("Lfixture/Details;", "Lfixture/Details;"))))
        assertNull("three parameters", notLoggedEvent(factory(readPrivacy, loadNotLogged(1), answer(),
            parameters = listOf("Lfixture/Details;", "Ljava/lang/String;", "Ljava/lang/String;"))))
        assertNull("no privacy context read", notLoggedEvent(factory(loadNotLogged(1), answer())))
        assertNull("another class's static", notLoggedEvent(factory(readPrivacy, loadNotLogged(1, "Lfixture/Other;"), answer())))
        assertNull("a static that isn't what it answers", notLoggedEvent(factory(readPrivacy, loadNotLogged(1), answer(2))))
        assertNull("two statics it could answer",
            notLoggedEvent(factory(readPrivacy, loadNotLogged(1), answer(), loadNotLogged(2), answer(2))))
    }

    @Test
    fun `the extension takes the event's name`() {
        for ((owner, descriptor) in listOf(ANALYTICS_EVENTS to KEEP_EVENT_OUT, SEARCH_HISTORY to KEEP_VISIT_OUT)) {
            val hook = ExtensionDex.classDef(owner).methods.singleOrNull { it.descriptor() == descriptor }
            assertTrue("$descriptor is not in the extension", hook != null)
            assertTrue("$descriptor is static", AccessFlags.STATIC.isSet(hook!!.accessFlags))
            assertTrue("$descriptor is public", AccessFlags.PUBLIC.isSet(hook.accessFlags))
        }
    }
}
