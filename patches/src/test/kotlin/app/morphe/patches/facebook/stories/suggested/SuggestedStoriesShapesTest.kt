/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.suggested

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.TREE_JNI
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
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
 * The parts of Hide suggested stories that need no Facebook build: which methods the anchors take
 * and turn down, the whole search over a small made-up tray with a control for each rule, and the
 * code the hook and the stubs get. The extension's own class is the one the bundle carries.
 */
class SuggestedStoriesShapesTest {
    private val fetch = "Lfixture/TrayFetch;"
    private val data = "Lfixture/TrayData;"
    private val receiverClass = "Lfixture/TrayReceiver;"
    private val bucket = "Lfixture/Bucket;"
    private val tree = "Lfixture/BucketTree;"
    private val label = "Lfixture/Label;"
    private val helperClass = "Lfixture/LabelHelper;"
    private val typeEnumType = "Lfixture/BucketType;"
    private val otherTypeEnum = "Lfixture/OtherBucketType;"
    private val public = AccessFlags.PUBLIC.value
    private val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    private val constructorParameters =
        listOf("Lfixture/Request;", "Lfixture/Model;", "Lfixture/Source;", IMMUTABLE_LIST, "Ljava/lang/String;", "I")
    private val bucketsField = ImmutableFieldReference(data, "A04", IMMUTABLE_LIST)
    private val flagCall = ImmutableMethodReference(bucket, "BZd", emptyList(), "Z")
    private val trendingCall = ImmutableMethodReference(bucket, "BZf", emptyList(), "Z")
    private val getBoolean = ImmutableMethodReference(TREE_JNI, "getBooleanValue", listOf("I"), "Z")
    /** The tree class's own superclass, a TreeJNI subclass, is what the enum read names. */
    private val getCachedEnum =
        ImmutableMethodReference("Lfixture/TreeBase;", "getCachedEnum", listOf("I", "Ljava/lang/Enum;"), "Ljava/lang/Enum;")

    private fun method(
        definingClass: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        flags: Int,
        registers: Int,
        instructions: List<Instruction>,
    ): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
        ImmutableMethodImplementation(registers, instructions, null, null),
    )

    private fun abstractMethod(definingClass: String, name: String, returnType: String): Method = ImmutableMethod(
        definingClass, name, emptyList(), returnType,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
    )

    private fun classOf(type: String, vararg methods: Method, flags: Int = public, superclass: String = "Ljava/lang/Object;",
                        interfaces: List<String> = emptyList()): ClassDef =
        ImmutableClassDef(type, flags, superclass, interfaces, null, null, null, methods.toList())

    private fun invoke(opcode: Opcode, reference: MethodReference, vararg registers: Int): Instruction =
        ImmutableInstruction35c(opcode, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 },
            registers.getOrElse(2) { 0 }, registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 }, reference)

    private fun string(register: Int, value: String) =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    /** The tray data's constructor as both builds have it: the list, parameter 3, into its own field. */
    private fun constructor(field: ImmutableFieldReference = bucketsField, register: Int = 4, parameters: List<String> = constructorParameters) =
        method(data, "<init>", parameters, "V", public or AccessFlags.CONSTRUCTOR.value, 7, listOf(
            invoke(Opcode.INVOKE_DIRECT, ImmutableMethodReference("Ljava/lang/Object;", "<init>", emptyList(), "V"), 0),
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, register, 0, field),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ))

    /** The post-processing: the trace name, then the tray data built through [builds]. */
    private fun postProcess(builds: Method = constructor(), trace: String = POST_PROCESS_RESULT, returnType: String = data) =
        method(fetch, "A03", listOf("Lfixture/Source;", "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/String;"),
            returnType, public or AccessFlags.FINAL.value, 20, listOf(
                string(0, trace),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 8, ImmutableTypeReference(builds.definingClass)),
                ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 8, 7, ImmutableMethodReference(
                    builds.definingClass, builds.name, builds.parameterTypes.map { it.toString() }, builds.returnType,
                )),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 8),
            ))

    /** The classic tray's receiver: the literal, the list read out of the tray data, and two flags asked. */
    private fun receiver(reads: ImmutableFieldReference = bucketsField) =
        method(receiverClass, "A05", listOf(data, receiverClass, "Z"), "V", publicStatic, 12, listOf(
            string(0, OPTIMISTIC_RECEIVES),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 9, reads),
            invoke(Opcode.INVOKE_INTERFACE, flagCall, 2),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 3),
            invoke(Opcode.INVOKE_INTERFACE, trendingCall, 2),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 3),
            invoke(Opcode.INVOKE_INTERFACE, ImmutableMethodReference("Ljava/util/Iterator;", "hasNext", emptyList(), "Z"), 4),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 3),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ))

    /** A tree accessor: [field]'s key read with [reader]. */
    private fun treeReader(name: String, field: String, reader: MethodReference = getBoolean, static: Boolean = false,
                           opcode: Opcode = Opcode.INVOKE_VIRTUAL) =
        method(tree, name, emptyList(), "Z", if (static) publicStatic else public or AccessFlags.FINAL.value, 2, listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, field.hashCode()),
            invoke(opcode, reader, 1, 0),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        ))

    /** The tree's type accessor: [field]'s key read with [reader], Facebook's unset value as the default. */
    private fun typeReader(name: String = "C9d", field: String = BUCKET_TYPE_FIELD, reader: MethodReference = getCachedEnum,
                           static: Boolean = false, opcode: Opcode = Opcode.INVOKE_VIRTUAL) =
        method(tree, name, emptyList(), typeEnumType, if (static) publicStatic else public or AccessFlags.FINAL.value, 3, listOf(
            ImmutableInstruction31i(Opcode.CONST, 1, field.hashCode()),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(typeEnumType, "A0o", typeEnumType)),
            invoke(opcode, reader, 2, 1, 0),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(typeEnumType)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ))

    private fun enumNaming(type: String, names: List<String>) = classOf(type,
        method(type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1,
            names.map { string(0, it) } + ImmutableInstruction10x(Opcode.RETURN_VOID)),
        superclass = "Ljava/lang/Enum;")

    /** The bucket type enum, and another naming the same cards that no bucket method answers. */
    private val typeEnums = listOf(
        enumNaming(typeEnumType, listOf("STORY", "PAGE_STORY") + BUCKET_TYPE_NAMES),
        enumNaming(otherTypeEnum, BUCKET_TYPE_NAMES),
    )

    private fun labelHelper(flags: Int = publicStatic, parameter: String = bucket, returnType: String = label) =
        method(helperClass, "A00", listOf(parameter), returnType, flags, 2, listOf(
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        ))

    private val labelEnum = classOf(label,
        method(label, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1,
            listOf(string(0, "FAMILY"), string(0, "NEWFRIEND"), string(0, "SUGGESTED"), string(0, "TRENDING"),
                ImmutableInstruction10x(Opcode.RETURN_VOID))),
        superclass = "Ljava/lang/Enum;")

    private val immutableList = classOf(IMMUTABLE_LIST,
        method(IMMUTABLE_LIST, "copyOf", listOf("Ljava/util/Collection;"), IMMUTABLE_LIST, publicStatic, 1,
            listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
        flags = public or AccessFlags.ABSTRACT.value)

    /** A made-up tray with every part the patch looks for, each replaceable by a control. */
    private fun tray(
        dataClass: ClassDef = classOf(data, constructor()),
        post: Method = postProcess(),
        receive: Method = receiver(),
        bucketFlags: Int = public or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        readers: List<Method> = listOf(treeReader("BZd", SUGGESTED_FLAG_FIELD), treeReader("BZf", "is_trending_bucket")),
        typeAccessors: List<Method> = listOf(abstractMethod(bucket, "C9d", typeEnumType)),
        typeReaders: List<Method> = listOf(typeReader()),
        enums: List<ClassDef> = typeEnums,
        helper: Method = labelHelper(),
        helperFlags: Int = public or AccessFlags.FINAL.value,
        list: ClassDef = immutableList,
    ): List<ClassDef> = enums + listOf(
        classOf(fetch, post),
        dataClass,
        classOf(receiverClass, receive),
        classOf(bucket, abstractMethod(bucket, "BZd", "Z"), abstractMethod(bucket, "BZf", "Z"), *typeAccessors.toTypedArray(),
            flags = bucketFlags),
        classOf(tree, *(readers + typeReaders).toTypedArray(), superclass = "Lfixture/TreeBase;", interfaces = listOf(bucket)),
        labelEnum,
        classOf(helperClass, helper, flags = helperFlags),
        list,
        ExtensionDex.classDef(SUGGESTED_STORIES),
    )

    private fun found(classes: List<ClassDef>) = with(PatchContexts.of(classes)) { trayBuckets() }

    private fun refusal(classes: List<ClassDef>): String =
        assertThrows(PatchException::class.java) { found(classes) }.message.orEmpty()

    private fun reference(instruction: Instruction): String {
        val call = (instruction as ReferenceInstruction).reference as MethodReference
        return "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
    }

    @Test
    fun `the post-processing holds the trace name and answers an app object`() {
        assertTrue(isPostProcess(postProcess()))
        assertFalse(isPostProcess(postProcess(trace = "StoriesTrayLightFetchControllerQueryOps.createQuery")))
        assertFalse(isPostProcess(postProcess(returnType = "Ljava/lang/Object;")))
        assertFalse(isPostProcess(postProcess(returnType = "V")))
    }

    @Test
    fun `the tray data keeps the one list its constructor takes, in a field of its own`() {
        assertEquals(listOf(3), listParameters(constructor()))
        assertEquals(bucketsField, listField(constructor(), 3)?.let { ImmutableFieldReference.of(it) })
        assertNull("the list goes to another class's field",
            listField(constructor(field = ImmutableFieldReference("Lfixture/Other;", "A04", IMMUTABLE_LIST)), 3))
        assertNull("the field isn't a list", listField(constructor(field = ImmutableFieldReference(data, "A04", "Ljava/util/List;")), 3))
        assertNull("another register goes to the field", listField(constructor(register = 5), 3))
        assertTrue(constructs(postProcess(), constructor()))
        assertFalse("another class's constructor", constructs(postProcess(),
            method("Lfixture/Other;", "<init>", constructorParameters, "V", public or AccessFlags.CONSTRUCTOR.value, 7, emptyList())))
    }

    @Test
    fun `the receiver's flags are its interface calls that answer a boolean from nothing`() {
        val calls = interfaceFlagCalls(receiver())
        // Iterator.hasNext is asked too, and a framework interface is never a bucket's.
        assertEquals(listOf("BZd", "BZf"), calls.map { it.name })
        assertTrue(readsField(receiver(), bucketsField))
        assertFalse(readsField(receiver(reads = ImmutableFieldReference(data, "A05", IMMUTABLE_LIST)), bucketsField))
    }

    /** The key picks the flag: the trending flag beside it is read the same way under another key. */
    @Test
    fun `a flag reader loads the suggested key and reads it as a tree boolean`() {
        assertTrue(isTreeFlagReader(treeReader("BZd", SUGGESTED_FLAG_FIELD), SUGGESTED_FLAG_FIELD))
        assertFalse(isTreeFlagReader(treeReader("BZf", "is_trending_bucket"), SUGGESTED_FLAG_FIELD))
        assertFalse("a static method", isTreeFlagReader(treeReader("BZd", SUGGESTED_FLAG_FIELD, static = true), SUGGESTED_FLAG_FIELD))
        assertFalse("another reader", isTreeFlagReader(treeReader("BZd", SUGGESTED_FLAG_FIELD,
            reader = ImmutableMethodReference(TREE_JNI, "getIntValue", listOf("I"), "I")), SUGGESTED_FLAG_FIELD))
        assertEquals(-1530492979, SUGGESTED_FLAG_FIELD.hashCode())
    }

    /** The type key picks the accessor, read as a cached enum through whatever TreeJNI subclass the tree has. */
    @Test
    fun `a type reader loads the type key and reads it as a cached enum`() {
        assertTrue(isTreeEnumReader(typeReader(), BUCKET_TYPE_FIELD))
        assertFalse("another key", isTreeEnumReader(typeReader(field = "story_bucket_owner_type"), BUCKET_TYPE_FIELD))
        assertFalse("a static method", isTreeEnumReader(typeReader(static = true), BUCKET_TYPE_FIELD))
        assertFalse("another reader", isTreeEnumReader(typeReader(reader = ImmutableMethodReference("Lfixture/TreeBase;",
            "getEnumValue", listOf("I", "Ljava/lang/Enum;"), "Ljava/lang/Enum;")), BUCKET_TYPE_FIELD))
        assertFalse("a boolean flag", isTreeEnumReader(treeReader("BZd", BUCKET_TYPE_FIELD), BUCKET_TYPE_FIELD))
        assertEquals(0xa5133025L.toInt(), BUCKET_TYPE_FIELD.hashCode())
    }

    /**
     * 587's delegating trees ask the delegate they hold through an interface whose methods are
     * obfuscated (LX/0Ttg;->Ey4 and CZs), so there the read is known by its shape, and its other trees
     * read a nullable enum by that name.
     */
    @Test
    fun `a reader may ask a delegate interface by shape, or the nullable enum reader`() {
        val enumDelegate = ImmutableMethodReference("Lfixture/Delegate;", "Ey4", listOf("I", "Ljava/lang/Enum;"), "Ljava/lang/Enum;")
        val flagDelegate = ImmutableMethodReference("Lfixture/Delegate;", "CZs", listOf("I"), "Z")
        assertTrue(isTreeEnumReader(typeReader(reader = enumDelegate, opcode = Opcode.INVOKE_INTERFACE), BUCKET_TYPE_FIELD))
        assertTrue(isTreeEnumReader(typeReader(reader = ImmutableMethodReference("Lfixture/TreeBase;", "getCachedNullableEnum",
            listOf("I", "Ljava/lang/Enum;"), "Ljava/lang/Enum;")), BUCKET_TYPE_FIELD))
        assertFalse("another key", isTreeEnumReader(typeReader(field = "story_bucket_owner_type", reader = enumDelegate,
            opcode = Opcode.INVOKE_INTERFACE), BUCKET_TYPE_FIELD))
        assertFalse("another shape", isTreeEnumReader(typeReader(reader = ImmutableMethodReference("Lfixture/Delegate;", "Ey5",
            listOf("I", "Ljava/lang/Class;"), "Ljava/lang/Enum;"), opcode = Opcode.INVOKE_INTERFACE), BUCKET_TYPE_FIELD))
        assertFalse("a framework interface", isTreeEnumReader(typeReader(reader = ImmutableMethodReference("Ljava/util/Map;", "Ey4",
            listOf("I", "Ljava/lang/Enum;"), "Ljava/lang/Enum;"), opcode = Opcode.INVOKE_INTERFACE), BUCKET_TYPE_FIELD))
        assertTrue(isTreeFlagReader(treeReader("BZd", SUGGESTED_FLAG_FIELD, reader = flagDelegate, opcode = Opcode.INVOKE_INTERFACE),
            SUGGESTED_FLAG_FIELD))
        assertFalse("another key", isTreeFlagReader(treeReader("BZf", "is_trending_bucket", reader = flagDelegate,
            opcode = Opcode.INVOKE_INTERFACE), SUGGESTED_FLAG_FIELD))
        assertFalse("an int read", isTreeFlagReader(treeReader("BZd", SUGGESTED_FLAG_FIELD,
            reader = ImmutableMethodReference("Lfixture/Delegate;", "CZt", listOf("I"), "I"), opcode = Opcode.INVOKE_INTERFACE),
            SUGGESTED_FLAG_FIELD))
    }

    @Test
    fun `the label helper is static, takes one bucket and answers the label`() {
        assertTrue(isLabelHelper(labelHelper(), setOf(bucket), label))
        assertFalse(isLabelHelper(labelHelper(flags = public), setOf(bucket), label))
        assertFalse(isLabelHelper(labelHelper(parameter = "Lfixture/Other;"), setOf(bucket), label))
        assertFalse(isLabelHelper(labelHelper(returnType = "Ljava/lang/Enum;"), setOf(bucket), label))
    }

    @Test
    fun `the patch finds the list, the flag and the helper of a whole tray`() {
        val found = found(tray())
        assertEquals(data, found.constructor.definingClass)
        assertEquals(3, found.list)
        assertEquals(bucket, found.bucket)
        assertEquals("BZd", found.flag)
        assertEquals("A00", found.labelHelper.name)
        assertEquals(label, found.label)
        assertEquals("C9d" to typeEnumType, found.type to found.typeEnum)
    }

    /** Each piece of evidence stops the patch when it's missing, and the message says which. */
    @Test
    fun `each missing piece of evidence stops the patch by name`() {
        assertTrue(refusal(tray(post = postProcess(trace = "something else"))).contains(POST_PROCESS_RESULT))
        assertTrue(refusal(tray(dataClass = classOf(data, constructor(), constructor(parameters = listOf(IMMUTABLE_LIST))))).contains("one constructor"))
        assertTrue(refusal(tray(dataClass = classOf(data, constructor(field = ImmutableFieldReference("Lfixture/Other;", "A04", IMMUTABLE_LIST)))))
            .contains("doesn't keep its bucket list"))
        assertTrue(refusal(tray(receive = receiver(reads = ImmutableFieldReference(data, "A05", IMMUTABLE_LIST)))).contains("the bucket list"))
        assertTrue(refusal(tray(readers = listOf(treeReader("BZf", "is_trending_bucket")))).contains(SUGGESTED_FLAG_FIELD))
        assertTrue(refusal(tray(readers = listOf(treeReader("BZd", SUGGESTED_FLAG_FIELD), treeReader("BZf", SUGGESTED_FLAG_FIELD))))
            .contains(SUGGESTED_FLAG_FIELD))
        assertTrue(refusal(tray(helper = labelHelper(parameter = "Lfixture/Other;"))).contains("static helper"))
        assertTrue(refusal(tray(helperFlags = AccessFlags.FINAL.value)).contains("the class of the label helper"))
        assertTrue(refusal(tray(helper = labelHelper(flags = AccessFlags.STATIC.value))).contains("isn't public"))
        assertTrue(refusal(tray(bucketFlags = AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value)).contains("the bucket interface"))
        assertTrue(refusal(tray(list = classOf(IMMUTABLE_LIST, flags = public or AccessFlags.ABSTRACT.value))).contains("copyOf"))
        assertTrue("no tree class reads the type", refusal(tray(typeReaders = listOf(typeReader(field = "story_bucket_owner_type"))))
            .contains(BUCKET_TYPE_FIELD))
        assertTrue("no bucket method answers a type enum", refusal(tray(typeAccessors = emptyList())).contains("PYMK_STORY"))
        assertTrue("two bucket methods answer one", refusal(tray(typeAccessors = listOf(abstractMethod(bucket, "C9d", typeEnumType),
            abstractMethod(bucket, "C9e", typeEnumType)))).contains("PYMK_STORY"))
        assertTrue("the enum doesn't name the contacts card", refusal(tray(enums = listOf(
            enumNaming(typeEnumType, listOf("STORY", "PYMK_STORY", "PYMK_PROFILE_FORWARD_STORY")))))
            .contains("PYMK_STORY"))
    }

    /**
     * The hook goes first, in the list's own register, through the range form, and holds the
     * answer to what the constructor stores. The stubs call what the patch found.
     */
    @Test
    fun `the hook replaces the list first and the stubs call what was found`() {
        val context = PatchContexts.of(tray())
        val found = with(context) { trayBuckets() }
        with(context) { fillBucketStubs(found) }
        val constructor = with(context) { mutableClassDefBy(data) }.methods.single { it.name == "<init>" }
        constructor.keepOnlyUnsuggestedBuckets(found.list)
        val body = constructor.implementation!!.instructions.toList()
        assertEquals(KEPT_BUCKETS, reference(body[0]))
        assertEquals(4 to 1, (body[0] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(Opcode.MOVE_RESULT_OBJECT, body[1].opcode)
        assertEquals(4, (body[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.CHECK_CAST, body[2].opcode)
        assertEquals(Opcode.INVOKE_DIRECT, body[3].opcode)

        val stubs = with(context) { mutableClassDefBy(SUGGESTED_STORIES) }.methods
        fun stub(name: String) = stubs.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
            .implementation!!.instructions.toList()
        assertEquals(Opcode.INSTANCE_OF, stub(IS_BUCKET_STUB)[0].opcode)
        assertEquals("$bucket->BZd()Z", reference(stub(SUGGESTED_STUB)[1]))
        assertEquals("$bucket->C9d()$typeEnumType", reference(stub(TYPE_STUB)[1]))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, stub(TYPE_STUB)[2].opcode)
        assertEquals("$helperClass->A00($bucket)$label", reference(stub(LABEL_STUB)[1]))
        assertEquals("$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST", reference(stub(COPY_STUB)[0]))
    }

    /** The patch and the extension agree on the injection point, the stubs and what the report names. */
    @Test
    fun `the patch and the extension agree on the hook, the stubs and the names`() {
        val extension = ExtensionDex.classDef(SUGGESTED_STORIES)
        fun declares(name: String, parameters: List<String>, returnType: String) = extension.methods.any {
            it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                it.returnType == returnType && it.parameterTypes.map { type -> type.toString() } == parameters
        }
        assertTrue(KEPT_BUCKETS, declares("keptBuckets", listOf("Ljava/util/List;"), "Ljava/lang/Object;"))
        assertTrue(declares(IS_BUCKET_STUB, listOf("Ljava/lang/Object;"), "Z"))
        assertTrue(declares(SUGGESTED_STUB, listOf("Ljava/lang/Object;"), "Ljava/lang/Object;"))
        assertTrue(declares(LABEL_STUB, listOf("Ljava/lang/Object;"), "Ljava/lang/Object;"))
        assertTrue(declares(TYPE_STUB, listOf("Ljava/lang/Object;"), "Ljava/lang/Object;"))
        assertTrue(declares(BUCKET_ID_STUB, listOf("Ljava/lang/Object;"), "Ljava/lang/Object;"))
        assertTrue(declares(EXPIRES_STUB, listOf("Ljava/lang/Object;", "J"), "Ljava/lang/Object;"))
        assertEquals(ID_FIELD, ExtensionDex.stringConstant(SUGGESTED_STORIES, "ID_FIELD"))
        assertEquals(EXPIRATION_FIELD, ExtensionDex.stringConstant(SUGGESTED_STORIES, "EXPIRATION_FIELD"))
        assertEquals(BUCKET_TYPE_FIELD, ExtensionDex.stringConstant(SUGGESTED_STORIES, "BUCKET_TYPE_FIELD"))
        val cards = listOf("PYMK_TYPE", "PYMK_PROFILE_FORWARD_TYPE", "CONTACT_IMPORTER_TYPE")
            .map { ExtensionDex.stringConstant(SUGGESTED_STORIES, it) }
        assertEquals(BUCKET_TYPE_NAMES.toSet(), cards.toSet())
        assertTrue(declares(COPY_STUB, listOf("Ljava/util/List;"), "Ljava/lang/Object;"))
        assertEquals(SUGGESTED_FLAG_FIELD, ExtensionDex.stringConstant(SUGGESTED_STORIES, "SUGGESTED_FLAG"))
        val labelName = ExtensionDex.stringConstant(SUGGESTED_STORIES, "SUGGESTED_LABEL")
        assertTrue(labelName, labelName in LABEL_NAMES)
    }
}
