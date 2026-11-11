/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Hide Menu promotions that need no Facebook build: which methods the anchors take and
 * turn down, where they find the group and the children list, and the code the hooks put in.
 */
class MenuSectionsShapesTest {
    private val section = "Lfixture/GroupSection;"
    private val model = "Lfixture/GroupModel;"
    private val group = "Lfixture/Group;"
    private val builder = "Lfixture/ChildrenBuilder;"
    private val children = "Lfixture/Children;"
    private val context = "Lfixture/SectionContext;"
    private val isGroup = { type: String -> type == group }

    private val childrenField = ImmutableFieldReference(builder, "A00", children)
    private val modelField = ImmutableFieldReference(section, "A04", model)
    private val groupField = ImmutableFieldReference(section, "A01", group)
    private val groupCall = ImmutableMethodReference(model, "A00", emptyList(), group)
    private val builderInit = ImmutableMethodReference(builder, "<init>", emptyList(), "V")

    private fun constString(register: Int, string: String) =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(string))

    private fun invoke(opcode: Opcode, reference: MethodReference, vararg registers: Int): Instruction =
        ImmutableInstruction35c(opcode, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 },
            registers.getOrElse(2) { 0 }, registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 }, reference)

    private fun newBuilder(register: Int, type: String = builder) =
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, register, ImmutableTypeReference(type))

    private fun childrenOf(to: Int, from: Int, field: FieldReference = childrenField) =
        ImmutableInstruction22c(Opcode.IGET_OBJECT, to, from, field)

    /** A group section's children builder over [instructions], `this` past [locals] locals. */
    private fun method(
        instructions: List<Instruction>,
        locals: Int = 18,
        parameters: List<String> = listOf(context),
        returnType: String = children,
        static: Boolean = false,
    ): Method = ImmutableMethod(
        section,
        "A1S",
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
        null,
        null,
        ImmutableMethodImplementation(locals + (if (static) 0 else 1) + parameters.size, instructions, null, null),
    )

    /**
     * The native section's shape: `this` into v2, the model from it, the group from the model into
     * v3, then the section's own code, which writes v0 first, builds its list in v13 and hands it
     * back. [locals] is 18, so `this` is v18.
     */
    private fun nativeSection(vararg extra: Instruction, groupRegister: Int = 3, key: String = NATIVE_SECTION_KEY) =
        method(
            listOf(
                ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 2, 18),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 6, 2, modelField),
                invoke(Opcode.INVOKE_VIRTUAL, groupCall, 6),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, groupRegister),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(group, "A0G", group)),
                newBuilder(13),
                invoke(Opcode.INVOKE_DIRECT, builderInit, 13),
                constString(1, key),
            ) + extra.toList() + listOf(
                childrenOf(0, 13),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
        )

    /** The server section's shape: its group from its own field into v5, then its list in v2. */
    private fun serverSection(vararg positions: String = SERVER_POSITIONS.toTypedArray(), field: FieldReference = groupField) =
        method(
            listOf(ImmutableInstruction22c(Opcode.IGET_OBJECT, 5, 9, field), newBuilder(2), invoke(Opcode.INVOKE_DIRECT, builderInit, 2)) +
                positions.map { constString(0, it) } +
                listOf(childrenOf(0, 2), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            locals = 9,
        )

    @Test
    fun `the native section's children builder is the instance method loading the list key`() {
        assertTrue(isNativeSectionChildren(nativeSection()))
        assertFalse(isNativeSectionChildren(nativeSection(key = "community_resources_simple_tile_bookmarks_key")))
        assertFalse(isNativeSectionChildren(method(listOf(constString(0, NATIVE_SECTION_KEY)), static = true)))
        assertFalse(isNativeSectionChildren(method(listOf(constString(0, NATIVE_SECTION_KEY)), returnType = "V")))
        assertFalse(isNativeSectionChildren(method(listOf(constString(0, NATIVE_SECTION_KEY)), parameters = emptyList())))
        // 582 holds the body in a singleton's method over the context and the section's fields.
        assertTrue(isNativeSectionChildren(method(listOf(constString(0, NATIVE_SECTION_KEY)),
            parameters = listOf(context, "I"))))
        assertFalse(isNativeSectionChildren(method(listOf(constString(0, NATIVE_SECTION_KEY)),
            parameters = listOf("I", context))))
    }

    @Test
    fun `the server section's children builder loads all three positions`() {
        assertTrue(isServerSectionChildren(serverSection()))
        // GraphQL's own list of the positions is a class initializer, and a section missing one
        // isn't this one.
        assertFalse(isServerSectionChildren(serverSection("ABOVE_NATIVE", "BELOW_NATIVE")))
        assertFalse(isServerSectionChildren(method(SERVER_POSITIONS.map { constString(0, it) }, static = true)))
    }

    @Test
    fun `the native section's group is the kept answer of its one call returning the group enum`() {
        assertEquals(GroupRead(3, 3, group), nativeGroupRead(nativeSection(), isGroup))
        assertEquals(GroupRead(3, 17, group), nativeGroupRead(nativeSection(groupRegister = 17), isGroup))
        // Not the enum: no read.
        assertNull(nativeGroupRead(nativeSection(), { false }))
        // Two calls answering the enum can't be told apart.
        assertNull(nativeGroupRead(nativeSection(invoke(Opcode.INVOKE_VIRTUAL, groupCall, 6),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4)), isGroup))
        // An answer that isn't kept isn't a read.
        val dropped = method(listOf(invoke(Opcode.INVOKE_VIRTUAL, groupCall, 6), newBuilder(13)))
        assertNull(nativeGroupRead(dropped, isGroup))
    }

    @Test
    fun `the server section's group is its one read of a group field of its own class`() {
        assertEquals(GroupRead(0, 5, group), serverGroupRead(serverSection(), isGroup))
        assertNull(serverGroupRead(serverSection(field = ImmutableFieldReference("Lfixture/Other;", "A01", group)), isGroup))
        assertNull(serverGroupRead(serverSection(field = ImmutableFieldReference(section, "A01", model)), isGroup))
        val twice = method(listOf(ImmutableInstruction22c(Opcode.IGET_OBJECT, 5, 9, groupField),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 4, 9, groupField)), locals = 9)
        assertNull(serverGroupRead(twice, isGroup))
    }

    @Test
    fun `the children list is the one field of a class the method builds and constructs bare`() {
        val list = ChildrenList(builder, "$builder->A00:$children")
        assertEquals(list, childrenList(nativeSection()))
        assertEquals(list, childrenList(serverSection()))
        // The range form of the constructor counts too.
        val ranged = method(listOf(newBuilder(13), ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 13, 1, builderInit),
            childrenOf(0, 13)))
        assertEquals(list, childrenList(ranged))
        // Negative controls: a list never constructed, one constructed with an argument, a field of
        // another type, and two candidate fields.
        assertNull(childrenList(method(listOf(newBuilder(13), childrenOf(0, 13)))))
        val withArgument = ImmutableMethodReference(builder, "<init>", listOf("I"), "V")
        assertNull(childrenList(method(listOf(newBuilder(13), invoke(Opcode.INVOKE_DIRECT, withArgument, 13, 1),
            childrenOf(0, 13)))))
        assertNull(childrenList(method(listOf(newBuilder(13), invoke(Opcode.INVOKE_DIRECT, builderInit, 13),
            childrenOf(0, 13, ImmutableFieldReference(builder, "A00", "Lfixture/Other;"))))))
        assertNull(childrenList(nativeSection(childrenOf(0, 13, ImmutableFieldReference(builder, "A01", children)))))
    }

    private fun MutableMethod.at(index: Int) = implementation!!.instructions.elementAt(index)

    /** An `xor-int/lit8` as its destination, its source and its literal. */
    private fun notOf(instruction: Instruction): List<Int> {
        assertEquals(Opcode.XOR_INT_LIT8, instruction.opcode)
        val xor = instruction as TwoRegisterInstruction
        return listOf(xor.registerA, xor.registerB, (instruction as NarrowLiteralInstruction).narrowLiteral)
    }

    /** The instruction index a branch at [index] lands on. */
    private fun offsetTarget(method: MutableMethod, index: Int): Int {
        val instructions = method.implementation!!.instructions.toList()
        val branch = instructions[index] as OffsetInstruction
        var address = 0
        val addresses = instructions.map { val at = address; address += it.codeUnits; at }
        return addresses.indexOf(addresses[index] + branch.codeOffset)
    }

    @Test
    fun `a hidden group hands back a new empty list and any other goes on with the section's own code`() {
        val native = MutableMethod(nativeSection())
        val read = nativeGroupRead(native, isGroup)!!
        val own = native.implementation!!.instructions.count()
        native.buildNothingWhenHidden(read, childrenList(native)!!, HIDE_SECTION)

        val hook = native.at(4) as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, native.at(4).opcode)
        assertEquals(3, hook.startRegister)
        assertEquals(1, hook.registerCount)
        val call = (native.at(4) as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/menu/MenuSections;", call.definingClass)
        assertEquals("hideSection", call.name)
        assertEquals(listOf("Ljava/lang/Object;"), call.parameterTypes.map { it.toString() })
        assertEquals("Z", call.returnType)
        // v0 is borrowed: the section's next instruction writes it before anything reads it.
        assertEquals(Opcode.MOVE_RESULT, native.at(5).opcode)
        assertEquals(0, (native.at(5) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, native.at(6).opcode)
        assertEquals(0, (native.at(6) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.NEW_INSTANCE, native.at(7).opcode)
        assertEquals(builder, ((native.at(7) as ReferenceInstruction).reference as TypeReference).type)
        assertEquals(Opcode.INVOKE_DIRECT_RANGE, native.at(8).opcode)
        assertEquals("$builder-><init>()V", ((native.at(8) as ReferenceInstruction).reference as MethodReference).let {
            "${it.definingClass}->${it.name}()${it.returnType}"
        })
        assertEquals(Opcode.IGET_OBJECT, native.at(9).opcode)
        assertEquals(0, (native.at(9) as TwoRegisterInstruction).registerA)
        assertEquals(0, (native.at(9) as TwoRegisterInstruction).registerB)
        assertEquals("$builder->A00:$children", ((native.at(9) as ReferenceInstruction).reference as FieldReference).let {
            "${it.definingClass}->${it.name}:${it.type}"
        })
        assertEquals(Opcode.RETURN_OBJECT, native.at(10).opcode)
        assertEquals(0, (native.at(10) as OneRegisterInstruction).registerA)
        // A no lands on the section's own next instruction, so none of its code is skipped.
        assertEquals(Opcode.SGET_OBJECT, native.at(11).opcode)
        assertEquals(11, offsetTarget(native, 6))
        assertEquals(own + 7, native.implementation!!.instructions.count())
    }

    /** A group kept above v15 still goes to the hook, through the range form. */
    @Test
    fun `a group in a high register goes through the range form`() {
        val native = MutableMethod(nativeSection(groupRegister = 17))
        native.buildNothingWhenHidden(nativeGroupRead(native, isGroup)!!, childrenList(native)!!, HIDE_SECTION)
        val hook = native.at(4) as RegisterRangeInstruction
        assertEquals(17, hook.startRegister)
    }

    @Test
    fun `the server section's hook goes right after it reads its group`() {
        val server = MutableMethod(serverSection())
        server.buildNothingWhenHidden(serverGroupRead(server, isGroup)!!, childrenList(server)!!, HIDE_SERVER_SECTION)
        assertEquals(Opcode.IGET_OBJECT, server.at(0).opcode)
        val call = (server.at(1) as ReferenceInstruction).reference as MethodReference
        assertEquals("hideServerSection", call.name)
        assertEquals(5, (server.at(1) as RegisterRangeInstruction).startRegister)
        // v0 is the lowest local nothing reads there, and the hook borrows it.
        assertEquals(0, (server.at(2) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.NEW_INSTANCE, server.at(4).opcode)
        // A no lands on the section's own list, built as before.
        assertEquals(Opcode.NEW_INSTANCE, server.at(8).opcode)
        assertEquals(8, offsetTarget(server, 3))
    }

    private val folder = "Lfixture/Folder;"
    private val folderField = ImmutableFieldReference(section, "A02", folder)
    private val addChild = ImmutableMethodReference(builder, "A01", listOf("Lfixture/Child;"), "V")

    /**
     * 586's server section (`LX/0BD4;->A0s`): its group into v5 and its native folder into v3 from
     * fields of its own, its list in v2, and between REPLACE_NATIVE and BELOW_NATIVE an add of the
     * folder that an `if-eqz` skips when there's none. [extra] goes right before that check.
     */
    private fun folderServerSection(
        folderRead: Instruction = ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 9, folderField),
        extra: List<Instruction> = emptyList(),
        skip: Int = 5,
    ) = method(
        listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 5, 9, groupField),
            folderRead,
            newBuilder(2),
            invoke(Opcode.INVOKE_DIRECT, builderInit, 2),
            constString(0, "ABOVE_NATIVE"),
            constString(0, "REPLACE_NATIVE"),
        ) + extra + listOf(
            ImmutableInstruction21t(Opcode.IF_EQZ, 3, skip),
            invoke(Opcode.INVOKE_VIRTUAL, addChild, 2, 3),
            constString(0, "BELOW_NATIVE"),
            childrenOf(0, 2),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ),
        locals = 9,
    )

    @Test
    fun `the server section's native folder add is the one add of its own field an if-eqz skips`() {
        val server = folderServerSection()
        val read = serverGroupRead(server, isGroup)!!
        assertEquals(NativeFolderAdd(6, 3, 8), nativeFolderAdd(server, read))
        // Negative controls: a folder read from another class, a check that skips more than the
        // add, a group the check reads instead, and a group written again before the check.
        assertNull(nativeFolderAdd(folderServerSection(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 9, ImmutableFieldReference("Lfixture/Other;", "A02", folder))), read))
        assertNull(nativeFolderAdd(folderServerSection(skip = 7), read))
        assertNull(nativeFolderAdd(folderServerSection(ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 9, groupField)),
            GroupRead(1, 3, group)))
        assertNull(nativeFolderAdd(folderServerSection(extra = listOf(constString(5, "PROFILE"))), read))
        // The plain server section has no folder.
        assertNull(nativeFolderAdd(serverSection(), serverGroupRead(serverSection(), isGroup)!!))
    }

    @Test
    fun `a hidden folder skips its add the way a missing one does and the server's parts still build`() {
        val server = MutableMethod(folderServerSection())
        val read = serverGroupRead(server, isGroup)!!
        val add = nativeFolderAdd(server, read)!!
        val own = server.implementation!!.instructions.count()
        // In the patch's order: the folder first, so the server hook's code above doesn't move it.
        server.skipFolderWhenHidden(read, add)
        server.buildNothingWhenHidden(read, childrenList(server)!!, HIDE_SERVER_SECTION)

        assertEquals("hideServerSection", ((server.at(1) as ReferenceInstruction).reference as MethodReference).name)
        val at = add.check + 7
        val call = (server.at(at) as ReferenceInstruction).reference as MethodReference
        assertEquals(Opcode.INVOKE_STATIC_RANGE, server.at(at).opcode)
        assertEquals("Lapp/morphe/extension/facebook/menu/MenuSections;", call.definingClass)
        assertEquals("hideFolder", call.name)
        assertEquals(listOf("Ljava/lang/Object;"), call.parameterTypes.map { it.toString() })
        assertEquals("Z", call.returnType)
        assertEquals(5, (server.at(at) as RegisterRangeInstruction).startRegister)
        // v0 is borrowed: BELOW_NATIVE's string writes it before anything reads it, either way.
        assertEquals(Opcode.MOVE_RESULT, server.at(at + 1).opcode)
        assertEquals(0, (server.at(at + 1) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, server.at(at + 2).opcode)
        assertEquals(0, (server.at(at + 2) as OneRegisterInstruction).registerA)
        // A yes lands where a missing folder does, on BELOW_NATIVE, and a no on the section's own check.
        assertEquals(at + 5, offsetTarget(server, at + 2))
        assertEquals("BELOW_NATIVE", ((server.at(at + 5) as ReferenceInstruction).reference as StringReference).string)
        assertEquals(Opcode.IF_EQZ, server.at(at + 3).opcode)
        assertEquals(at + 5, offsetTarget(server, at + 3))
        assertEquals(Opcode.INVOKE_VIRTUAL, server.at(at + 4).opcode)
        assertEquals(own + 3 + 7, server.implementation!!.instructions.count())
    }

    private val baseState = "Lfixture/BaseState;"
    private val foldState = "Lfixture/FoldState;"
    private val stateField = ImmutableFieldReference("Lfixture/Component;", "A03", baseState)
    private val foldField = ImmutableFieldReference(foldState, "A01", "Z")
    private val useFold = ImmutableMethodReference("Lfixture/Header;", "A00", listOf("Z", "Ljava/lang/Object;"), "V")

    private fun foldBoolean(register: Int = 8) = ImmutableInstruction22c(Opcode.IGET_BOOLEAN, register, 1, foldField)

    /**
     * 584's shape: `this` into v2, its state cast to the fold state in v1, the fold into v8, the
     * group into v3, then [extra] before the header takes both. [between] goes after the fold read.
     */
    private fun foldingSection(
        between: List<Instruction> = emptyList(),
        extra: List<Instruction> = emptyList(),
        fold: Instruction = foldBoolean(),
    ) = method(
        listOf(
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 2, 18),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 2, stateField),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(foldState)),
            fold,
        ) + between + listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 6, 2, modelField),
            invoke(Opcode.INVOKE_VIRTUAL, groupCall, 6),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
        ) + extra + listOf(
            invoke(Opcode.INVOKE_STATIC, useFold, 8, 3),
            newBuilder(13),
            invoke(Opcode.INVOKE_DIRECT, builderInit, 13),
            childrenOf(0, 13),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ),
    )

    private fun foldOf(method: Method) = foldRead(method, nativeGroupRead(method, isGroup)!!)

    @Test
    fun `the fold is the one boolean read before the group from a state the section casts`() {
        assertEquals(FoldRead(3, 8), foldOf(foldingSection()))
        // A boolean of the section's own class isn't its state, and a read after the group doesn't count.
        val own = ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 9, 2, ImmutableFieldReference(section, "A08", "Z"))
        assertEquals(FoldRead(3, 8), foldOf(foldingSection(between = listOf(own), extra = listOf(foldBoolean(9)))))
        // Negative controls: two state reads, a state never cast, a register written before the group.
        assertNull(foldOf(foldingSection(between = listOf(ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 9, 1,
            ImmutableFieldReference(foldState, "A02", "Z"))))))
        assertNull(foldOf(foldingSection(fold = ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 8, 1,
            ImmutableFieldReference("Lfixture/Uncast;", "A01", "Z")))))
        assertNull(foldOf(foldingSection(between = listOf(ImmutableInstruction11n(Opcode.CONST_4, 8, 0)))))
        // A wide write into v7 takes v8 with it.
        assertNull(foldOf(foldingSection(between = listOf(ImmutableInstruction22c(Opcode.IGET_WIDE, 7, 1,
            ImmutableFieldReference(foldState, "A00", "J"))))))
    }

    @Test
    fun `586's fold copied at once into a high register before its own is reused is the copy`() {
        val copy = ImmutableInstruction22x(Opcode.MOVE_FROM16, 14, 8)
        val reuse = ImmutableInstruction11n(Opcode.CONST_4, 8, 0)
        assertEquals(FoldRead(4, 14), foldOf(foldingSection(between = listOf(copy, reuse))))
        // A read that lasts to the group stays the fold, even when it's copied.
        assertEquals(FoldRead(3, 8), foldOf(foldingSection(between = listOf(copy))))
        // Negative controls: a read used twice, a copy written over before the group, a wide write into v13 taking v14.
        assertNull(foldOf(foldingSection(between = listOf(copy, ImmutableInstruction22x(Opcode.MOVE_FROM16, 15, 8), reuse))))
        assertNull(foldOf(foldingSection(between = listOf(copy, reuse, ImmutableInstruction11n(Opcode.CONST_4, 14, 0)))))
        assertNull(foldOf(foldingSection(between = listOf(copy, reuse, ImmutableInstruction22c(Opcode.IGET_WIDE, 13, 1,
            ImmutableFieldReference(foldState, "A00", "J"))))))
        // A copy that isn't a plain move doesn't count.
        assertNull(foldOf(foldingSection(between = listOf(ImmutableInstruction22b(Opcode.XOR_INT_LIT8, 14, 8, 1), reuse))))
    }

    @Test
    fun `a branch landing between the fold and the group turns the fold down`() {
        // if-nez v1 right after the fold read, landing past itself and the model read: on the group call.
        val branch = ImmutableInstruction21t(Opcode.IF_NEZ, 1, 2 + 2)
        assertNull(foldOf(foldingSection(between = listOf(branch))))
        // A branch landing past the group, on the header's call, is fine.
        val past = ImmutableInstruction21t(Opcode.IF_NEZ, 1, 2 + 2 + 3 + 1)
        assertEquals(FoldRead(3, 8), foldOf(foldingSection(between = listOf(past))))
    }

    @Test
    fun `the fold and the group go to the extension right after the group, and its answer replaces the fold`() {
        val native = MutableMethod(foldingSection())
        val read = nativeGroupRead(native, isGroup)!!
        val own = native.implementation!!.instructions.count()
        native.askAboutFolding(read, foldRead(native, read)!!)

        // v0 and v1 are borrowed: the section writes v0 before reading it and never reads v1 again.
        assertEquals(Opcode.MOVE_FROM16, native.at(7).opcode)
        assertEquals(listOf(0, 8), (native.at(7) as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(Opcode.MOVE_OBJECT_FROM16, native.at(8).opcode)
        assertEquals(listOf(1, 3), (native.at(8) as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(Opcode.INVOKE_STATIC, native.at(9).opcode)
        val call = (native.at(9) as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/menu/MenuSections;->collapsed(ZLjava/lang/Object;)Z",
            "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
        assertEquals(listOf(0, 1), (native.at(9) as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals(Opcode.MOVE_RESULT, native.at(10).opcode)
        assertEquals(0, (native.at(10) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_FROM16, native.at(11).opcode)
        assertEquals(listOf(8, 0), (native.at(11) as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        // The section goes on with its own code, which now reads the extension's answer.
        assertEquals(useFold, (native.at(12) as ReferenceInstruction).reference)
        assertEquals(own + 5, native.implementation!!.instructions.count())
    }

    private val startField = ImmutableFieldReference(section, "A09", "Z")
    private val headerField = ImmutableFieldReference("Lfixture/Header;", "A07", "Z")

    private fun startBoolean(register: Int = 11, field: FieldReference = startField) =
        ImmutableInstruction22c(Opcode.IGET_BOOLEAN, register, 2, field)

    /** The header's seed: [from] negated into v10 and stored into the header in v4, as 584's `Lmm.A07 = !A09`. */
    private fun seed(from: Int = 11, literal: Int = 1, field: FieldReference = headerField) = listOf(
        ImmutableInstruction22b(Opcode.XOR_INT_LIT8, 10, from, literal),
        ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 10, 4, field),
    )

    /**
     * [foldingSection] with whether the group starts folded read into v11 at 4, after the fold, and
     * seeded into the header after the group, at 8 and 9.
     */
    private fun startingSection(before: List<Instruction> = listOf(startBoolean()), after: List<Instruction> = seed()) =
        foldingSection(between = before, extra = after)

    private fun startOf(method: Method) = startRead(method, nativeGroupRead(method, isGroup)!!)

    @Test
    fun `the header's start is the one own flag read once, negated into another class after the group`() {
        assertEquals(StartRead(4, 11), startOf(startingSection()))
        // The fold, read from the state, is never the start.
        assertNull(startOf(foldingSection()))
        // Negative controls: another class's flag, a flag read again, one xor'd with anything but 1,
        // one stored back into the section, one seeded before the group, one written over first,
        // and two that both qualify.
        assertNull(startOf(startingSection(before = listOf(startBoolean(field = ImmutableFieldReference("Lfixture/Other;", "A09", "Z"))))))
        assertNull(startOf(startingSection(after = seed() + invoke(Opcode.INVOKE_STATIC, useFold, 11, 3))))
        assertNull(startOf(startingSection(after = seed(literal = 2))))
        assertNull(startOf(startingSection(after = seed(field = ImmutableFieldReference(section, "A07", "Z")))))
        assertNull(startOf(startingSection(before = listOf(startBoolean()) + seed(), after = emptyList())))
        assertNull(startOf(startingSection(before = listOf(startBoolean(), ImmutableInstruction11n(Opcode.CONST_4, 11, 0)))))
        assertNull(startOf(startingSection(
            before = listOf(startBoolean(), startBoolean(12, ImmutableFieldReference(section, "A0A", "Z"))),
            after = seed() + seed(from = 12),
        )))
        // if-nez v1 right after the start read, landing on the group call.
        assertNull(startOf(startingSection(before = listOf(startBoolean(), ImmutableInstruction21t(Opcode.IF_NEZ, 1, 2 + 2)))))
    }

    @Test
    fun `with a start, the header's seed gets the extension's answer right after the fold does`() {
        val native = MutableMethod(startingSection())
        val read = nativeGroupRead(native, isGroup)!!
        val own = native.implementation!!.instructions.count()
        native.askAboutFolding(read, foldRead(native, read)!!, startRead(native, read)!!)

        // The fold's five go in at 8 to 12, then the start's five, through the same borrowed v0 and v1.
        // The start says whether the group starts open, so it's asked about turned around and the
        // answer comes back turned around again.
        assertEquals(listOf(8, 0), (native.at(12) as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(listOf(0, 11, 1), notOf(native.at(13)))
        assertEquals(Opcode.INVOKE_STATIC, native.at(14).opcode)
        val call = (native.at(14) as ReferenceInstruction).reference as MethodReference
        assertEquals("collapsed", call.name)
        assertEquals(listOf(0, 1), (native.at(14) as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals(Opcode.MOVE_RESULT, native.at(15).opcode)
        assertEquals(0, (native.at(15) as OneRegisterInstruction).registerA)
        assertEquals(listOf(0, 0, 1), notOf(native.at(16)))
        assertEquals(Opcode.MOVE_FROM16, native.at(17).opcode)
        assertEquals(listOf(11, 0), (native.at(17) as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        // The header's seed now negates the turned-back answer.
        assertEquals(Opcode.XOR_INT_LIT8, native.at(18).opcode)
        assertEquals(own + 10, native.implementation!!.instructions.count())
    }

    private fun seedOf(method: Method): HeaderSeed? {
        val read = nativeGroupRead(method, isGroup)!!
        return headerSeed(method, read, startRead(method, read)!!)
    }

    @Test
    fun `the header's seed is the store the start's negation goes to, with the group still in its register`() {
        assertEquals(HeaderSeed(9, 4, "Lfixture/Header;", 3), seedOf(startingSection()))
        // Negative controls: the group's register written over before the store, and if-nez v1 before
        // the seed landing past its negation and store, right after the seed.
        assertNull(seedOf(startingSection(after = listOf(ImmutableInstruction11n(Opcode.CONST_4, 3, 0)) + seed())))
        assertNull(seedOf(startingSection(after = listOf(ImmutableInstruction21t(Opcode.IF_NEZ, 1, 2 + 2 + 2)) + seed())))
    }

    @Test
    fun `the group's answer marks the header right after its seed`() {
        val native = MutableMethod(startingSection())
        val own = native.implementation!!.instructions.count()
        native.seedHeaderFlag(seedOf(native)!!)

        // v0 is borrowed, since the section writes it before reading it, and the group goes in its range form.
        assertEquals(Opcode.INVOKE_STATIC_RANGE, native.at(10).opcode)
        assertEquals(listOf(3, 1), (native.at(10) as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        val call = (native.at(10) as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/menu/MenuSections;->keepsHeaderOpen(Ljava/lang/Object;)Z",
            "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}")
        assertEquals(Opcode.MOVE_RESULT, native.at(11).opcode)
        assertEquals(0, (native.at(11) as OneRegisterInstruction).registerA)
        assertMark(native.at(12), Opcode.IPUT_BOOLEAN, 0, 4)
        // The section goes on with its own code.
        assertEquals(useFold, (native.at(13) as ReferenceInstruction).reference)
        assertEquals(own + 3, native.implementation!!.instructions.count())
    }

    private val headerType = "Lfixture/Header;"
    private val headerState = "Lfixture/HeaderState;"
    private val collapsedField = ImmutableFieldReference(headerState, "A01", "Z")
    private val headerStateField = ImmutableFieldReference(headerType, "A02", "Ljava/lang/Object;")
    private val headerOwnFlag = ImmutableFieldReference(headerType, "A08", "Z")
    private val drawn = ImmutableMethodReference("Lfixture/Chevron;", "A00", listOf("Z", "Z"), "V")
    private val toggled = ImmutableMethodReference("Lfixture/Chevron;", "A01", listOf("Z", "Z", "Ljava/lang/String;"), "V")

    /** A method of the header: six locals, `this` in v6, then one object. */
    private fun headerMethod(name: String, instructions: List<Instruction>): Method = ImmutableMethod(
        headerType,
        name,
        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null,
        null,
        ImmutableMethodImplementation(8, instructions, null, null),
    )

    /**
     * 586's render (`LX/0OC1;->A1E`): its state cast into v1, a flag of its own into v5 at 2, then
     * whether it's collapsed into v4 at 3, for the chevron. [lead] goes first and [tail] after the read.
     */
    private fun headerRender(lead: List<Instruction> = emptyList(), tail: List<Instruction> = emptyList()) = headerMethod(
        "A1E",
        lead + listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, headerStateField),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(headerState)),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 5, 6, headerOwnFlag),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 4, 1, collapsedField),
        ) + tail + listOf(
            invoke(Opcode.INVOKE_STATIC, drawn, 4, 5),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ),
    )

    /**
     * 586's tap handler (`LX/0OC1;->A1H`): the header into v1 and its state cast into v0, then a flag
     * of its own read into v1 at 3, over the header, and whether it's collapsed into v3 at 4, which
     * the toggle takes beside the [string] it loads.
     */
    private fun headerClick(string: String = HEADER_TOGGLE) = headerMethod(
        "A1H",
        listOf(
            ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 6),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, headerStateField),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(headerState)),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 1, 1, headerOwnFlag),
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 3, 0, collapsedField),
            constString(2, string),
            invoke(Opcode.INVOKE_STATIC, toggled, 1, 3, 2),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ),
    )

    private fun headerClass(vararg methods: Method) =
        ImmutableClassDef(headerType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList())

    /** An [opcode] of the header's mark between [register] and the header in [header]. */
    private fun assertMark(instruction: Instruction, opcode: Opcode, register: Int, header: Int) {
        assertEquals(opcode, instruction.opcode)
        assertEquals(listOf(register, header), (instruction as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        assertEquals("$headerType->$HEADER_FLAG:Z", "${field.definingClass}->${field.name}:${field.type}")
    }

    @Test
    fun `the header's state is read once by its render and once by its tap handler, each beside a free local`() {
        val render = headerRender()
        val click = headerClick()
        // The render's v0 is never read. The tap handler reads v0 and v1 after its own flag, but not v2.
        assertEquals(
            setOf(HeaderStateRead(render, 2, 6, 3, 4, 0, false), HeaderStateRead(click, 3, 1, 4, 3, 2, true)),
            headerStateReads(headerClass(render, click))?.toSet(),
        )
        // Negative controls: no tap handler loading the toggle's name, a render reading the state twice,
        // and if-eqz v7 before the render landing on its read.
        assertNull(headerStateReads(headerClass(render, headerClick("updateState:Other"))))
        assertNull(headerStateReads(headerClass(headerRender(tail = listOf(
            ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 4, 1, collapsedField))), click)))
        assertNull(headerStateReads(headerClass(headerRender(lead = listOf(
            ImmutableInstruction21t(Opcode.IF_EQZ, 7, 2 + 2 + 2 + 2))), click)))
    }

    @Test
    fun `a marked header reads as expanded in its render and its tap handler`() {
        val reads = headerStateReads(headerClass(headerRender(), headerClick()))!!

        val render = MutableMethod(headerRender())
        render.keepReadOpen(reads.single { !it.click }, headerType)
        // The mark goes into v0 before the flag read, and right after the state read it's turned
        // around and anded in, so a marked header's chevron is never collapsed.
        assertMark(render.at(2), Opcode.IGET_BOOLEAN, 0, 6)
        assertEquals(headerOwnFlag, (render.at(3) as ReferenceInstruction).reference)
        assertEquals(collapsedField, (render.at(4) as ReferenceInstruction).reference)
        assertEquals(listOf(0, 0, 1), notOf(render.at(5)))
        assertEquals(Opcode.AND_INT, render.at(6).opcode)
        assertEquals(listOf(4, 4, 0), (render.at(6) as ThreeRegisterInstruction).let { listOf(it.registerA, it.registerB, it.registerC) })
        assertEquals(drawn, (render.at(7) as ReferenceInstruction).reference)

        val click = MutableMethod(headerClick())
        click.keepReadOpen(reads.single { it.click }, headerType)
        // The flag read writes over the header, so the mark is read from v1 before it. After the state
        // read it's ored in, so a tap on a marked header flips it from collapsed to expanded.
        assertMark(click.at(3), Opcode.IGET_BOOLEAN, 2, 1)
        assertEquals(headerOwnFlag, (click.at(4) as ReferenceInstruction).reference)
        assertEquals(collapsedField, (click.at(5) as ReferenceInstruction).reference)
        assertEquals(Opcode.OR_INT, click.at(6).opcode)
        assertEquals(listOf(3, 3, 2), (click.at(6) as ThreeRegisterInstruction).let { listOf(it.registerA, it.registerB, it.registerC) })
        assertEquals(HEADER_TOGGLE, ((click.at(7) as ReferenceInstruction).reference as StringReference).string)
    }

    /**
     * With every local still wanted where the hook goes, nothing is borrowed and the patch stops
     * naming itself, before anything goes in.
     */
    @Test
    fun `a section with no free local stops the patch`() {
        val tight = MutableMethod(method(
            listOf(
                invoke(Opcode.INVOKE_VIRTUAL, groupCall, 1),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
            locals = 1,
        ))
        val refusal = assertThrows(PatchException::class.java) {
            tight.buildNothingWhenHidden(nativeGroupRead(tight, isGroup)!!, ChildrenList(builder, "$builder->A00:$children"),
                HIDE_SECTION)
        }
        assertTrue(refusal.message, refusal.message!!.contains(PATCH))
        assertEquals(3, tight.implementation!!.instructions.count())
    }
}
