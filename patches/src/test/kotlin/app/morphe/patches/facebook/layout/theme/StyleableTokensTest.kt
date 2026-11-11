/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableArrayPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document

/**
 * The styleable scan (StyleableTokens.kt) on a made-up app: which static int arrays it reads out of
 * a static initializer, which of their attributes some reader takes as an integer, and the tokens
 * those attributes reach through a style's `?attr/` aliases, each with a control.
 */
class StyleableTokensTest {
    private val holder = "Lfixture/Styleable;"
    private val context = "Landroid/content/Context;"
    private val typedArray = "Landroid/content/res/TypedArray;"
    private val intArray = ImmutableTypeReference("[I")
    private val public = AccessFlags.PUBLIC.value
    private val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

    private val obtain = ImmutableMethodReference(context, "obtainStyledAttributes", listOf("[I"), typedArray)
    private val getInteger = ImmutableMethodReference(typedArray, "getInteger", listOf("I", "I"), "I")
    private val getInt = ImmutableMethodReference(typedArray, "getInt", listOf("I", "I"), "I")
    private val getColor = ImmutableMethodReference(typedArray, "getColor", listOf("I", "I"), "I")
    private val styleConstructor = ImmutableMethodReference("Lfixture/Style;", "<init>", listOf(typedArray), "V")

    private fun field(name: String) = ImmutableFieldReference(holder, name, "[I")

    private fun invoke(opcode: Opcode, reference: MethodReference, vararg registers: Int): Instruction =
        ImmutableInstruction35c(opcode, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 },
            registers.getOrElse(2) { 0 }, registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 }, reference)

    private fun method(definingClass: String, name: String, parameters: List<String>, flags: Int, registers: Int,
                       vararg instructions: Instruction): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
        ImmutableMethodImplementation(registers, instructions.toList(), null, null),
    )

    private fun classOf(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, public, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    /**
     * Three arrays the way Facebook's styleable holder fills them: A31 by new-array and
     * fill-array-data, A32 and A33 by filled-new-array of literals.
     */
    private fun initializer() = method(holder, "<clinit>", emptyList(), AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 7,
        ImmutableInstruction11n(Opcode.CONST_4, 0, 3),                                        // @0
        ImmutableInstruction22c(Opcode.NEW_ARRAY, 1, 0, intArray),                            // @1
        ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 1, 27),                               // @3, the payload at @30
        ImmutableInstruction21c(Opcode.SPUT_OBJECT, 1, field("A31")),                         // @6
        ImmutableInstruction31i(Opcode.CONST, 2, 0x7f040912),                                 // @8
        ImmutableInstruction31i(Opcode.CONST, 3, 0x1010095),                                  // @11
        ImmutableInstruction35c(Opcode.FILLED_NEW_ARRAY, 2, 3, 2, 0, 0, 0, intArray),         // @14
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),                                // @17
        ImmutableInstruction21c(Opcode.SPUT_OBJECT, 4, field("A32")),                         // @18
        ImmutableInstruction31i(Opcode.CONST, 5, 0x7f040999),                                 // @20
        ImmutableInstruction35c(Opcode.FILLED_NEW_ARRAY, 1, 5, 0, 0, 0, 0, intArray),         // @23
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6),                                // @26
        ImmutableInstruction21c(Opcode.SPUT_OBJECT, 6, field("A33")),                         // @27
        ImmutableInstruction10x(Opcode.RETURN_VOID),                                          // @29
        ImmutableArrayPayload(4, listOf(0x1010097, 0x101015d, 0x7f040fd6)),                   // @30
    )

    /** Reads A31 through a helper that takes the TypedArray and reads it with getInteger, as the likes line does. */
    private fun likes() = classOf("Lfixture/Likes;", method("Lfixture/Likes;", "<init>", listOf(context), public, 4,
        ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, field("A31")),
        invoke(Opcode.INVOKE_VIRTUAL, obtain, 3, 0),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference("Lfixture/Style;")),
        invoke(Opcode.INVOKE_DIRECT, styleConstructor, 1, 0),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))

    private fun style() = classOf("Lfixture/Style;", method("Lfixture/Style;", "<init>", listOf(typedArray), public, 3,
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
        invoke(Opcode.INVOKE_VIRTUAL, getInteger, 2, 0, 0),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))

    /** Reads [array] itself with [read]. */
    private fun reader(type: String, array: String, read: MethodReference) = classOf(type, method(type, "A00", listOf(context), publicStatic, 3,
        ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, field(array)),
        invoke(Opcode.INVOKE_VIRTUAL, obtain, 2, 0),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
        invoke(Opcode.INVOKE_VIRTUAL, read, 0, 1, 1),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))

    private fun scan(vararg classes: ClassDef) = integerReadAttributes({ visit -> classes.forEach(visit) })

    @Test
    fun `a static initializer's literal int arrays are read by field`() {
        val arrays = staticIntArrays(initializer().implementation!!.instructions.toList())
        assertEquals(setOf("$holder->A31", "$holder->A32", "$holder->A33"), arrays.keys)
        assertEquals(listOf(0x1010097, 0x101015d, 0x7f040fd6), arrays.getValue("$holder->A31").toList())
        assertEquals(listOf(0x1010095, 0x7f040912), arrays.getValue("$holder->A32").toList())
        assertEquals(listOf(0x7f040999), arrays.getValue("$holder->A33").toList())
    }

    @Test
    fun `an array read as an integer, itself or through a helper, gives its own attributes`() {
        val scan = scan(classOf(holder, initializer()), likes(), style(), reader("Lfixture/Direct;", "A32", getInt),
            reader("Lfixture/Colour;", "A33", getColor))
        assertEquals("the framework's attributes don't count", setOf(0x7f040912, 0x7f040fd6), scan.attributes)
        assertEquals(mapOf(
            "Lfixture/Likes;-><init>(Landroid/content/Context;)V" to setOf("$holder->A31"),
            "Lfixture/Direct;->A00(Landroid/content/Context;)V" to setOf("$holder->A32"),
        ), scan.readers)
        assertEquals("the read arrays, whole", setOf("$holder->A31", "$holder->A32"), scan.arrays.keys)
        assertEquals(listOf(0x1010097, 0x101015d, 0x7f040fd6), scan.arrays.getValue("$holder->A31").toList())
    }

    /** The controls: a colour read, a reader in the extension, and an array no reader loads. */
    @Test
    fun `a colour read, the extension's own code and an unread array give nothing`() {
        val colour = scan(classOf(holder, initializer()), reader("Lfixture/Colour;", "A33", getColor))
        assertEquals(emptySet<Int>(), colour.attributes)
        val extension = scan(classOf(holder, initializer()), reader("Lapp/morphe/extension/facebook/Theme;", "A33", getInteger))
        assertEquals(emptySet<Int>(), extension.attributes)
        assertEquals(emptyMap<String, Set<String>>(), scan(classOf(holder, initializer()), style()).readers)
    }

    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    @Test
    fun `a read attribute reaches the tokens the styles alias it to`() {
        val tokens = mapOf("attr_0x7f0405fa" to "SECONDARY_TEXT", "attr_0x7f0405fb" to "PRIMARY_TEXT",
            "attr_0x7f0405fc" to "ACCENT", "attr_0x7f0405fd" to "WASH")
        val styles = listOf(
            xml("<resources><style.2 name=\"style.2_0x7f2000ea\"><item name=\"attr_0x7f040fd6\">?attr/attr_0x7f0405fa</item></style.2>" +
                "<style.2 name=\"style.2_0x7f200248\" parent=\"@style.2/style.2_0x7f2000ea\">" +
                "<item name=\"attr_0x7f040912\">?attr_0x7f0405fb</item>" +
                "<item name=\"attr_0x7f040999\">?attr/attr_0x7f0405fd</item></style.2></resources>"),
            xml("<resources><style name=\"style_0x7f210001\">" +
                "<item name=\"attr_0x7f0405fb\">?com.facebook.katana:attr/attr_0x7f0405fc</item>" +
                "<item name=\"attr_0x7f0405fc\">@color/color_0x7f060001</item></style></resources>"),
        )
        assertEquals("an alias, a bare alias, a packaged alias two steps on, and the unread attribute's token left out",
            setOf("SECONDARY_TEXT", "PRIMARY_TEXT", "ACCENT"), styleablePlainTokens(styles, tokens, setOf(0x7f040fd6, 0x7f040912)))
        assertEquals(setOf("attr_0x7f040fd6", "attr_0x7f0405fa"), aliasedAttributes(styles, setOf("attr_0x7f040fd6")))
        assertEquals(emptySet<String>(), styleablePlainTokens(styles, tokens, emptySet()))
    }
}
