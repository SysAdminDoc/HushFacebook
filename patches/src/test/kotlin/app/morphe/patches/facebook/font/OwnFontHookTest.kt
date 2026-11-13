/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.font

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What the font swap's return hooks read and borrow at each object return: the resolver reads the
 * family and the weight there and copies into v0 to v2, a builder's build reads `this` and copies
 * into v0 and v1, and React Native's resolver reads the family name and copies into v0 and v1. Each
 * is proved rather than trusted.
 */
class OwnFontHookTest {
    private val family = "Lfixture/Family;"

    /** A resolver, (family, int weight)Typeface, static, with three locals: family in v3, weight in v4. */
    private fun resolver(smali: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Repository;", "resolve",
            listOf(ImmutableMethodParameter(family, null, null), ImmutableMethodParameter("I", null, null)),
            TYPEFACE, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(5, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /** A builder's build, build(Context)Typeface on an instance, with two locals: this in v2. */
    private fun build(smali: String, static: Boolean = false): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Builder;", "build", listOf(ImmutableMethodParameter(CONTEXT, null, null)),
            TYPEFACE, AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(if (static) 3 else 4, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private val lookup = "invoke-static { v3, v4 }, Lfixture/Cache;->get(${family}I)$TYPEFACE"

    @Test
    fun `a resolver that still holds its family and weight at every return takes the hook`() {
        resolver(
            """
                $lookup
                move-result-object v1
                if-eqz v1, :miss
                return-object v1
                :miss
                const/4 v0, 0x0
                return-object v0
            """,
        ).requireResolverHookFits()
    }

    /** The resolver is done with the family and puts something else in its register before returning. */
    @Test
    fun `a resolver writing over its family or weight before a return stops the patch`() {
        val family = assertThrows(PatchException::class.java) {
            resolver(
                """
                    $lookup
                    move-result-object v0
                    const/4 v3, 0x0
                    return-object v0
                """,
            ).requireResolverHookFits()
        }
        assertTrue(family.message, family.message.orEmpty().contains("writes over parameter 0 (v3)"))

        val weight = assertThrows(PatchException::class.java) {
            resolver(
                """
                    $lookup
                    move-result-object v0
                    const/4 v4, 0x1
                    return-object v0
                """,
            ).requireResolverHookFits()
        }
        assertTrue(weight.message, weight.message.orEmpty().contains("writes over parameter 1 (v4)"))
    }

    /**
     * The return sits in a try block whose handler reads v1, and the hook's call can throw into it
     * after the copies went into v0 to v2. The positive control is the same method with the
     * handler reading v1 only after writing it.
     */
    @Test
    fun `a return a handler can be reached from keeps what the handler reads`() {
        val cacheGet = ImmutableMethodReference("Lfixture/Cache;", "get", listOf(family, "I"), TYPEFACE)
        val note = ImmutableMethodReference("Lfixture/Log;", "note", listOf("Ljava/lang/Object;"), "V")
        fun resolverWithHandler(handlerWritesFirst: Boolean) = MutableMethod(
            ImmutableMethod(
                "Lfixture/Repository;", "resolve",
                listOf(ImmutableMethodParameter(family, null, null), ImmutableMethodParameter("I", null, null)),
                TYPEFACE, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    5,
                    listOfNotNull(
                        // 0: address 0, 3 units
                        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 3, 4, 0, 0, 0, cacheGet),
                        // 1: address 3
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                        // 2: address 4, the return, inside the try
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        // 3: address 5, the handler
                        ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, if (handlerWritesFirst) 1 else 2),
                        // 4: address 6, reads v1
                        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, note),
                        // 5: address 9
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                    listOf(ImmutableTryBlock(0, 5, listOf(ImmutableExceptionHandler(null, 5)))),
                    null,
                ),
            ),
        )
        resolverWithHandler(handlerWritesFirst = true).requireResolverHookFits()
        val refused = assertThrows(PatchException::class.java) { resolverWithHandler(handlerWritesFirst = false).requireResolverHookFits() }
        assertTrue(refused.message, refused.message.orEmpty().contains("still reads v1"))
    }

    @Test
    fun `a build that still holds this at its returns takes the hook, and one that doesn't stops the patch`() {
        build(
            """
                invoke-static { v2, v3 }, Lfixture/Fonts;->make(Ljava/lang/Object;$CONTEXT)$TYPEFACE
                move-result-object v0
                return-object v0
            """,
        ).requireBuilderHookFits()

        val reused = assertThrows(PatchException::class.java) {
            build(
                """
                    invoke-static { v2, v3 }, Lfixture/Fonts;->make(Ljava/lang/Object;$CONTEXT)$TYPEFACE
                    move-result-object v0
                    move-object v2, v3
                    return-object v0
                """,
            ).requireBuilderHookFits()
        }
        assertTrue(reused.message, reused.message.orEmpty().contains("writes over this (v2)"))

        val static = assertThrows(PatchException::class.java) {
            build("const/4 v0, 0x0\nreturn-object v0", static = true).requireBuilderHookFits()
        }
        assertTrue(static.message, static.message.orEmpty().contains("is static"))
    }

    private val fonts = "Lfixture/Fonts;"
    private val typefaceBuilder = "Lfixture/TypefaceBuilder;"
    private val fromFile = "$TYPEFACE_BUILDERS->$BUILDER_FACTORY(Ljava/io/File;Ljava/util/List;)$typefaceBuilder"

    /**
     * A builder maker cut down from 587's: an instance method taking the family, with four locals,
     * so this is in v4 and the family in v5. The builder comes back in v1 when the family has axes,
     * and null from v3, a jump's target, when it has none.
     */
    private fun maker(type: String = fonts, parameters: List<String> = listOf(family), smali: String = makerSmali): MutableMethod =
        MutableMethod(
            ImmutableMethod(
                type, "make", parameters.map { ImmutableMethodParameter(it, null, null) }, typefaceBuilder,
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(5 + parameters.size, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }

    private val makerSmali = """
        iget-object v0, v5, $family->axes:Ljava/util/List;
        const/4 v3, 0x0
        if-eqz v0, :none
        iget-object v2, v5, $family->file:Ljava/io/File;
        invoke-static { v2, v0 }, $fromFile
        move-result-object v1
        return-object v1
        :none
        return-object v3
    """

    private fun fontsClass(type: String, vararg methods: MutableMethod) =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    /**
     * Through the patcher: at each return the maker hands its builder and the family to the
     * extension, takes back what it answers and casts it to its own return type. The return a jump
     * lands on gets the hook at its label, so the jump goes through it too. The factory's own class
     * and the extension's, which call the factory as well, are left alone.
     */
    @Test
    fun `a builder maker hands each builder and its family to the extension`() {
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val passOn = MutableMethod(
            ImmutableMethod(
                TYPEFACE_BUILDERS, "passOn", listOf("Ljava/io/File;", "Ljava/util/List;").map { ImmutableMethodParameter(it, null, null) },
                typefaceBuilder, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, "invoke-static { v0, v1 }, $fromFile\nmove-result-object v0\nreturn-object v0") }
        val context = PatchContexts.of(
            listOf(fontsClass(fonts, maker()), fontsClass(extension, maker(type = extension)), fontsClass(TYPEFACE_BUILDERS, passOn)),
        )
        assertEquals("makers hooked", 1, context.hookBuilderMakers(family))

        val body = context.mutableClassDefBy(fonts).methods.single().implementation!!.instructions.toList()
        val calls = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == REMEMBER_FAMILY }
        assertEquals("one call before each of the two returns", 2, calls.size)
        for ((call, returned) in calls.zip(listOf(1, 3))) {
            assertEquals(listOf(2, 0, 1), (body[call] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC, it.registerD) })
            val copied = body[call - 1] as TwoRegisterInstruction
            assertEquals("the family is copied down from v5", listOf(Opcode.MOVE_OBJECT_FROM16, 1, 5),
                listOf(copied.opcode, copied.registerA, copied.registerB))
            val builder = body[call - 2] as TwoRegisterInstruction
            assertEquals("the builder is copied to v0 first", listOf(Opcode.MOVE_OBJECT_FROM16, 0, returned),
                listOf(builder.opcode, builder.registerA, builder.registerB))
            assertEquals(listOf(Opcode.MOVE_RESULT_OBJECT, returned), (body[call + 1] as OneRegisterInstruction).let { listOf(it.opcode, it.registerA) })
            assertEquals(listOf(Opcode.CHECK_CAST, returned), (body[call + 2] as OneRegisterInstruction).let { listOf(it.opcode, it.registerA) })
            assertEquals(typefaceBuilder, (body[call + 2] as ReferenceInstruction).reference.toString())
            assertEquals(listOf(Opcode.RETURN_OBJECT, returned), (body[call + 3] as OneRegisterInstruction).let { listOf(it.opcode, it.registerA) })
        }
        val jump = body.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the jump to the null return goes through the hook", calls[1] - 2, jump.target.location.index)
        for (type in listOf(extension, TYPEFACE_BUILDERS)) {
            assertEquals("$type was hooked", 0, context.mutableClassDefBy(type).methods.single().implementation!!.instructions
                .count { (it as? ReferenceInstruction)?.reference?.toString() == REMEMBER_FAMILY })
        }
    }

    /** A maker that doesn't take the family, or writes over it before a return, stops the patch; nothing calling the factory too. */
    @Test
    fun `a builder maker that can't name its family stops the patch`() {
        val noFamily = PatchContexts.of(listOf(fontsClass(fonts, maker(parameters = listOf("Ljava/lang/Object;")))))
        val unnamed = assertThrows(PatchException::class.java) { noFamily.hookBuilderMakers(family) }
        assertTrue(unnamed.message, unnamed.message.orEmpty().contains("takes 0 parameter(s) of the family"))

        val overwritten = PatchContexts.of(listOf(fontsClass(fonts, maker(smali = makerSmali.replace("move-result-object v1", "move-result-object v1\nconst/4 v5, 0x0")))))
        val spoiled = assertThrows(PatchException::class.java) { overwritten.hookBuilderMakers(family) }
        assertTrue(spoiled.message, spoiled.message.orEmpty().contains("writes over parameter 0 (v5)"))

        val none = PatchContexts.of(listOf(fontsClass(fonts, build("const/4 v0, 0x0\nreturn-object v0"))))
        val missing = assertThrows(PatchException::class.java) { none.hookBuilderMakers(family) }
        assertTrue(missing.message, missing.message.orEmpty().contains("calls its $BUILDER_FACTORY"))
    }

    private val reactUtils = "Lfixture/ReactTypefaceUtils;"

    /**
     * React Native's resolver, (AssetManager, Typeface, String family, int style, int weight)Typeface,
     * static, with two locals: the family in v4, the style in v5 and the weight in v6.
     */
    private fun reactResolver(smali: String, name: String = "applyStyles", static: Boolean = true): MutableMethod =
        MutableMethod(
            ImmutableMethod(
                reactUtils, name,
                listOf(ASSET_MANAGER, TYPEFACE, STRING, "I", "I").map { ImmutableMethodParameter(it, null, null) },
                TYPEFACE, AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(if (static) 7 else 8, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }

    private val fromAsset =
        "invoke-static { v2, v4 }, $TYPEFACE->createFromAsset($ASSET_MANAGER$STRING)$TYPEFACE"

    /** Two returns: the asset it found, and the phone's family by the name when there's none. */
    private val twoReturns = """
        $fromAsset
        move-result-object v0
        if-eqz v0, :system
        return-object v0
        :system
        const/4 v5, 0x0
        invoke-static { v4, v5 }, $TYPEFACE->create(${STRING}I)$TYPEFACE
        move-result-object v1
        return-object v1
    """

    /** It resolves the style and the weight in place, which the hook doesn't read. The family it reads. */
    @Test
    fun `a React Native resolver that keeps its family name to every return takes the hook`() {
        reactResolver(twoReturns).requireReactNativeHookFits()

        val refused = assertThrows(PatchException::class.java) {
            reactResolver(
                """
                    $fromAsset
                    move-result-object v0
                    const/4 v4, 0x0
                    return-object v0
                """,
            ).requireReactNativeHookFits()
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("writes over parameter 2 (v4)"))
    }

    @Test
    fun `React Native's resolver is told apart by its shape and its asset lookup`() {
        val resolver = reactResolver("$fromAsset\nmove-result-object v0\nreturn-object v0")
        val noAssets = reactResolver(
            "invoke-static { v4, v5 }, $TYPEFACE->create(${STRING}I)$TYPEFACE\nmove-result-object v0\nreturn-object v0",
            name = "withoutAssets",
        )
        val instance = reactResolver(
            "invoke-static { v3, v5 }, $TYPEFACE->createFromAsset($ASSET_MANAGER$STRING)$TYPEFACE\n" +
                "move-result-object v0\nreturn-object v0",
            name = "onAnInstance", static = false,
        )
        val owner = ImmutableClassDef(reactUtils, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(resolver, noAssets, instance))
        assertEquals(listOf("applyStyles"), reactNativeResolvers(owner).map { it.name })
    }

    /** A method that logs the refusal React Native's utilities log, so the patcher's string search finds the class. */
    private fun refusalLogger(type: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            type, "parseFontVariationSettings", listOf(ImmutableMethodParameter(STRING, null, null)), STRING,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, "const-string v0, \"$INVALID_FONT_VARIATION\"\nreturn-object v0") }

    /**
     * Through the patcher: each return of the one resolver hands its answer and the family name to
     * the extension and returns what comes back. The extension's own class, which the patcher sees
     * merged in, doesn't count even when it holds the same literal and shape.
     */
    @Test
    fun `the hook hands each answer and the family name to the extension`() {
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val context = PatchContexts.of(
            listOf(
                ImmutableClassDef(reactUtils, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                    listOf(refusalLogger(reactUtils), reactResolver(twoReturns))),
                ImmutableClassDef(extension, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                    listOf(refusalLogger(extension), reactResolver(twoReturns).let {
                        ImmutableMethod(extension, it.name, it.parameters, it.returnType, it.accessFlags, null, null,
                            it.implementation)
                    })),
            ),
        )
        context.hookReactNativeFonts()

        val body = context.mutableClassDefBy(reactUtils).methods.single { it.name == "applyStyles" }
            .implementation!!.instructions.toList()
        val calls = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == REPLACE_REACT_NATIVE }
        assertEquals("one call before each of the two returns", 2, calls.size)
        for ((call, returned) in calls.zip(listOf(0, 1))) {
            assertEquals(listOf(0, 1), (body[call] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            val family = body[call - 1] as TwoRegisterInstruction
            assertEquals("the family is copied down from v4", listOf(Opcode.MOVE_OBJECT_FROM16, 1, 4),
                listOf(family.opcode, family.registerA, family.registerB))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, body[call + 1].opcode)
            assertEquals(returned, (body[call + 1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_OBJECT, body[call + 2].opcode)
            assertEquals(returned, (body[call + 2] as OneRegisterInstruction).registerA)
        }
        val answer = body[calls[1] - 2] as TwoRegisterInstruction
        assertEquals("the answer in v1 is copied to v0 first", listOf(Opcode.MOVE_OBJECT_FROM16, 0, 1),
            listOf(answer.opcode, answer.registerA, answer.registerB))
        assertEquals("the extension's copy was hooked", 0, context.mutableClassDefBy(extension).methods
            .single { it.name == "applyStyles" }.implementation!!.instructions
            .count { (it as? ReferenceInstruction)?.reference?.toString() == REPLACE_REACT_NATIVE })
    }

    @Test
    fun `a build without React Native's resolver stops the patch`() {
        val empty = PatchContexts.of(
            listOf(ImmutableClassDef(reactUtils, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(refusalLogger(reactUtils)))),
        )
        val refused = assertThrows(PatchException::class.java) { empty.hookReactNativeFonts() }
        assertTrue(refused.message, refused.message.orEmpty().contains("found 0"))
    }

    private val roboto = "Lfixture/Roboto;"
    private val paint = "Landroid/graphics/Paint;"

    /**
     * Facebook's Roboto builder, (Context, weight)Typeface, cut down from 580's `LX/2do;->A01`: the
     * typeface built for the weight, and when there's none, the log, with a jump past it to the one
     * return. The answer sits in v3, the Context's register, as in 580.
     */
    private fun robotoBuilder(type: String = roboto, name: String = "A01", static: Boolean = true): MutableMethod =
        MutableMethod(
            ImmutableMethod(
                type, name, listOf(CONTEXT, "Lfixture/Weight;").map { ImmutableMethodParameter(it, null, null) },
                TYPEFACE, AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(5, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { v3, v4 }, Lfixture/Robotos;->build(${CONTEXT}Lfixture/Weight;)$TYPEFACE
                    move-result-object v3
                    if-nez v3, :built
                    const-string v0, "$NO_ROBOTO"
                    invoke-static { v0 }, Lfixture/Log;->w($STRING)V
                    :built
                    return-object v3
                """,
            )
        }

    private fun robotoClass(type: String, vararg methods: MutableMethod) =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    /**
     * Through the patcher: the one Roboto builder hands its answer to the extension at its return's
     * own label, so the way past the log goes through the hook too, and returns what comes back in
     * the same register. The extension's own class doesn't count.
     */
    @Test
    fun `the Roboto builder's answer goes through the extension`() {
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val context = PatchContexts.of(
            listOf(robotoClass(roboto, robotoBuilder()), robotoClass(extension, robotoBuilder(type = extension))),
        )
        context.hookRobotoBuilder()

        val body = context.mutableClassDefBy(roboto).methods.single().implementation!!.instructions.toList()
        val call = body.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == REPLACE_PHONE_FONT }
        assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            body.subList(call, call + 3).map { it.opcode })
        assertEquals(listOf(3, 1), (body[call] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals(3, (body[call + 1] as OneRegisterInstruction).registerA)
        assertEquals(3, (body[call + 2] as OneRegisterInstruction).registerA)
        val addresses = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        assertEquals("the jump past the log lands on the hook", call,
            addresses.indexOf(addresses[2] + (body[2] as OffsetInstruction).codeOffset))
        assertEquals("the extension's copy was hooked", 0, context.mutableClassDefBy(extension).methods.single()
            .implementation!!.instructions.count { (it as? ReferenceInstruction)?.reference?.toString() == REPLACE_PHONE_FONT })
    }

    /** One builder that logs the refusal, static and answering a Typeface for a Context, or the patch stops. */
    @Test
    fun `a build without one Roboto builder stops the patch`() {
        fun refusal(vararg methods: MutableMethod) = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(robotoClass(roboto, *methods))).hookRobotoBuilder()
        }.message.orEmpty()
        val none = refusal(robotoBuilder(static = false))
        assertTrue(none, "expected one Roboto builder logging \"$NO_ROBOTO\", found 0" in none)
        val two = refusal(robotoBuilder(), robotoBuilder(name = "A02"))
        assertTrue(two, "found 2" in two)
    }

    /**
     * A span's draw that reads both of Android's default typefaces on two ways that join, then asks
     * defaultFromStyle in both call forms. The Paint is p0 (v3) and the style p1 (v4).
     */
    private fun defaultsReader(type: String): MutableMethod = MutableMethod(
        ImmutableMethod(
            type, "updateDrawState", listOf(paint, "I").map { ImmutableMethodParameter(it, null, null) },
            TYPEFACE, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(5, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                if-eqz v4, :plain
                sget-object v0, $TYPEFACE->DEFAULT_BOLD:$TYPEFACE
                goto :set
                :plain
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                :set
                invoke-virtual { v3, v0 }, $paint->setTypeface($TYPEFACE)$TYPEFACE
                invoke-static/range { v4 .. v4 }, $DEFAULT_FROM_STYLE
                move-result-object v1
                invoke-static { v4 }, $DEFAULT_FROM_STYLE
                move-result-object v2
                return-object v2
            """,
        )
    }

    private fun reference(instruction: Instruction) =
        (instruction as? ReferenceInstruction)?.reference?.toString()

    /**
     * Through the patcher: each read of DEFAULT or DEFAULT_BOLD becomes the extension's getter with
     * its answer moved into the read's register, and each defaultFromStyle call the extension's, in
     * the same form on the same register. The jump to the second read lands on its getter, and the
     * jump past it still lands on what followed the read, skipping the move. The extension's own
     * class keeps its reads.
     */
    @Test
    fun `each read of Android's default typefaces goes to the extension`() {
        val span = "Lfixture/NameSpan;"
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val context = PatchContexts.of(listOf(robotoClass(span, defaultsReader(span)), robotoClass(extension, defaultsReader(extension))))
        assertEquals(4, context.hookDefaultTypefaces())

        val body = context.mutableClassDefBy(span).methods.single().implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.GOTO,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
                Opcode.RETURN_OBJECT,
            ),
            body.map { it.opcode },
        )
        assertEquals("$OWN_FONT->defaultBold()$TYPEFACE", reference(body[1]))
        assertEquals("$OWN_FONT->defaultTypeface()$TYPEFACE", reference(body[4]))
        assertEquals(0, (body[2] as OneRegisterInstruction).registerA)
        assertEquals(0, (body[5] as OneRegisterInstruction).registerA)
        assertEquals(OWN_DEFAULT_FROM_STYLE, reference(body[7]))
        assertEquals(listOf(4, 1), (body[7] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals(OWN_DEFAULT_FROM_STYLE, reference(body[9]))
        assertEquals(listOf(4, 1), (body[9] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerCount) })

        val addresses = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        fun target(index: Int) = addresses.indexOf(addresses[index] + (body[index] as OffsetInstruction).codeOffset)
        assertEquals("the jump to the second read lands on its getter", 4, target(0))
        assertEquals("the jump past the second read lands on setTypeface", 6, target(3))

        val kept = context.mutableClassDefBy(extension).methods.single().implementation!!.instructions.count { defaultRead(it) != null }
        assertEquals("the extension's reads were sent", 4, kept)
    }

    private fun staticMethod(type: String, registers: Int, params: List<String>, body: String) = MutableMethod(
        ImmutableMethod(
            type, "run", params.map { ImmutableMethodParameter(it, null, null) }, TYPEFACE,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /**
     * A field read whose every use compares it with another typeface, by equals or by identity,
     * stays Android's, so the check still asks whether a typeface is the phone's default. Litho's
     * text asks that before it sets a typeface on its paint. A read that's also drawn with is sent.
     */
    @Test
    fun `a default read only compared with another typeface stays Android's`() {
        val type = "Lfixture/TextPaints;"
        val method = staticMethod(
            type, registers = 3, params = listOf(TYPEFACE),
            body = """
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                invoke-virtual { p0, v0 }, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :done
                sget-object v0, $TYPEFACE->DEFAULT_BOLD:$TYPEFACE
                if-eq p0, v0, :done
                sget-object v0, $TYPEFACE->SANS_SERIF:$TYPEFACE
                return-object v0
                :done
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                if-eq p0, v0, :same
                :same
                return-object v0
            """,
        )
        // No static call here, so what the calls are doesn't matter.
        assertEquals(listOf(true, true, false, false), listOf(0, 4, 6, 8).map { method.onlyCompared(it) { false } })
        val context = PatchContexts.of(listOf(robotoClass(type, method)))
        assertEquals("the drawn SANS_SERIF and DEFAULT", 2, context.hookDefaultTypefaces())
        val body = context.mutableClassDefBy(type).methods.single().implementation!!.instructions.toList()
        assertEquals(listOf("$TYPEFACE->DEFAULT:$TYPEFACE", "$TYPEFACE->DEFAULT_BOLD:$TYPEFACE"),
            body.mapNotNull(::defaultRead))
        assertEquals(listOf("$OWN_FONT->sansSerif()$TYPEFACE", "$OWN_FONT->defaultTypeface()$TYPEFACE"),
            body.mapNotNull(::reference).filter { it.startsWith(OWN_FONT) })
    }

    private val OBJECT_TYPE = "Ljava/lang/Object;"

    /** Kotlin's areEqual as R8 leaves it: either side null, or equals. */
    private val areEqualBody = """
        if-nez p0, :first
        if-nez p1, :differ
        const/4 v0, 0x1
        return v0
        :differ
        const/4 v0, 0x0
        return v0
        :first
        invoke-virtual { p0, p1 }, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
        move-result v0
        return v0
    """

    private fun twoObjects(type: String, body: String, registers: Int = 3, static: Boolean = true, name: String = "A00") = MutableMethod(
        ImmutableMethod(
            type, name, listOf(OBJECT_TYPE, OBJECT_TYPE).map { ImmutableMethodParameter(it, null, null) }, "Z",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /**
     * Kotlin's areEqual, a static call on two objects answering a boolean under whatever name R8
     * gave it, is a check too: the post text asks it whether its typeface is Typeface.DEFAULT, and
     * so is Java's Objects.equals. What the called method does decides it, not its shape: one that
     * keeps or hands on what it's given isn't a check, and nor is one this build doesn't hold, so a
     * read handed to either is sent. So is one a static call taking a typeface along with something
     * else is handed.
     */
    @Test
    fun `a default read only handed to Kotlin's areEqual stays Android's`() {
        val type = "Lfixture/PostText;"
        val method = staticMethod(
            type, registers = 3, params = listOf(TYPEFACE),
            body = """
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                invoke-static { p0, v0 }, LX/0bL;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :other
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                invoke-static { v0, p0 }, LX/GMb;->A0V(${TYPEFACE}Ljava/lang/Object;)Z
                move-result v1
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                invoke-static { v0, p0 }, LX/0bM;->A00(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                invoke-static { v0, p0 }, Ljava/util/Objects;->equals(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                sget-object v0, $TYPEFACE->DEFAULT:$TYPEFACE
                invoke-static { v0, p0 }, LX/0bN;->A00(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v1
                :other
                return-object p0
            """,
        )
        val equality = robotoClass("LX/0bL;", twoObjects("LX/0bL;", areEqualBody, name = "areEqual"))
        // Keeps the typeface it's handed, and answers whether it had one.
        val keeper = robotoClass("LX/0bM;", twoObjects("LX/0bM;", """
            sput-object p0, LX/0bM;->kept:Ljava/lang/Object;
            const/4 v0, 0x1
            return v0
        """))
        val context = PatchContexts.of(listOf(robotoClass(type, method), equality, keeper))
        val isEquality = context.equalityChecks()
        assertEquals(listOf(true, false, false, true, false), listOf(0, 4, 7, 10, 13).map { method.onlyCompared(it, isEquality) })
        assertEquals("the reads handed to the builder, the keeper and a method this build hasn't got", 3, context.hookDefaultTypefaces())
        val body = context.mutableClassDefBy(type).methods.single().implementation!!.instructions.toList()
        assertEquals(listOf("$TYPEFACE->DEFAULT:$TYPEFACE", "$TYPEFACE->DEFAULT:$TYPEFACE"), body.mapNotNull(::defaultRead))
        assertEquals("the check's read is still first", "$TYPEFACE->DEFAULT:$TYPEFACE", defaultRead(body[0]))
        val objectsEquals = body.indexOfFirst { reference(it) == OBJECTS_EQUALS }
        assertEquals("Objects.equals still compares Android's", "$TYPEFACE->DEFAULT:$TYPEFACE", defaultRead(body[objectsEquals - 1]))
    }

    /** An equality check by its code: what Kotlin's areEqual does, and nothing a method could also do with what it's handed. */
    @Test
    fun `only a method that does nothing but compare its two objects is an equality check`() {
        val type = "LX/0bL;"
        assertTrue("Kotlin's areEqual", isEqualityCheck(twoObjects(type, areEqualBody)))
        assertTrue("an identity check", isEqualityCheck(twoObjects(type, """
            if-eq p0, p1, :same
            const/4 v0, 0x0
            return v0
            :same
            const/4 v0, 0x1
            return v0
        """)))
        assertTrue("equals in the range form", isEqualityCheck(twoObjects(type, """
            invoke-virtual/range { p0 .. p1 }, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
            move-result v0
            return v0
        """)))
        val not = mapOf(
            "an instance method" to twoObjects(type, areEqualBody, static = false),
            "a field it reads" to twoObjects(type, """
                sget-object v0, LX/0bL;->last:Ljava/lang/Object;
                if-eq p0, v0, :same
                const/4 v0, 0x0
                return v0
                :same
                const/4 v0, 0x1
                return v0
            """),
            "another call" to twoObjects(type, """
                invoke-static { p0, p1 }, LX/0bM;->A00(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                return v0
            """),
            "equals on something else" to twoObjects(type, """
                const/4 v0, 0x0
                invoke-virtual { p0, v0 }, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
                move-result v0
                return v0
            """),
            "a compare of one with itself" to twoObjects(type, """
                invoke-virtual { p0, p0 }, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
                move-result v0
                return v0
            """),
            "no compare at all" to twoObjects(type, """
                const/4 v0, 0x1
                return v0
            """),
            "a value that isn't a boolean" to twoObjects(type, """
                if-eq p0, p1, :same
                const/4 v0, 0x2
                return v0
                :same
                const/4 v0, 0x1
                return v0
            """),
        )
        for ((what, method) in not) assertTrue(what, !isEqualityCheck(method))
    }

    /**
     * Each Typeface.create Facebook makes goes to the extension's create of the same arguments, on
     * the same registers in the same form: by family name, plain and range, from a typeface at a
     * style, and at a weight.
     */
    @Test
    fun `each create call goes to the extension on its own registers`() {
        val type = "Lfixture/Medium;"
        val method = staticMethod(
            type, registers = 4, params = emptyList(),
            body = """
                const-string v0, "sans-serif-medium"
                const/4 v1, 0x1
                invoke-static { v0, v1 }, $CREATE_FROM_NAME
                move-result-object v2
                invoke-static/range { v0 .. v1 }, $CREATE_FROM_NAME
                move-result-object v2
                invoke-static { v2, v1 }, $CREATE_FROM_TYPEFACE
                move-result-object v2
                const/16 v3, 0x1f4
                invoke-static { v2, v3, v1 }, $CREATE_AT_WEIGHT
                move-result-object v2
                return-object v2
            """,
        )
        val context = PatchContexts.of(listOf(robotoClass(type, method)))
        assertEquals(4, context.hookDefaultTypefaces())
        val body = context.mutableClassDefBy(type).methods.single().implementation!!.instructions.toList()
        val calls = body.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        assertEquals(listOf(CREATE_FROM_NAME, CREATE_FROM_NAME, CREATE_FROM_TYPEFACE, CREATE_AT_WEIGHT).map(::ownCall),
            calls.map(::reference))
        assertEquals(listOf(listOf(0, 1), listOf(0, 1), listOf(2, 1), listOf(2, 3, 1)), calls.map { call ->
            when (call) {
                is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
                else -> (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE).take(it.registerCount) }
            }
        })
        assertEquals(Opcode.INVOKE_STATIC_RANGE, calls[1].opcode)
        assertEquals("nothing else moved", method.implementation!!.instructions.size, body.size)
    }

    /**
     * Each text layout call Facebook makes goes to the extension's stand-in on the same registers
     * in the same form (#96): obtain plain, setIncludePad as the builder's call, BoringLayout.make by
     * range. The extension's own class keeps Android's calls, which its stand-ins make.
     */
    @Test
    fun `each text layout call goes to the extension on its own registers`() {
        val boring = LAYOUT_CALLS.keys.single { it.contains("BoringLayout;->make") && !it.contains("TruncateAt") }
        val body = """
            const-string v0, "y"
            const/4 v1, 0x0
            const/4 v2, 0x1
            const/4 v3, 0x0
            const/16 v4, 0x64
            invoke-static { v0, v1, v2, v3, v4 }, $OBTAIN_LAYOUT
            move-result-object v5
            invoke-virtual { v5, v1 }, $SET_INCLUDE_PAD
            move-result-object v5
            invoke-virtual { v5 }, $BUILD_LAYOUT
            move-result-object v6
            invoke-static/range { v0 .. v7 }, $boring
            move-result-object v8
            const/4 v8, 0x0
            return-object v8
        """
        val type = "Lfixture/TextLayout;"
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val method = staticMethod(type, registers = 9, params = emptyList(), body = body)
        val context = PatchContexts.of(
            listOf(robotoClass(type, method), robotoClass(extension, staticMethod(extension, registers = 9, params = emptyList(), body = body))),
        )
        val sent = listOf(OBTAIN_LAYOUT, SET_INCLUDE_PAD, BUILD_LAYOUT, boring)
        assertEquals(sent, context.hookTextLayouts())

        val calls = context.mutableClassDefBy(type).methods.single().implementation!!.instructions
            .filter { it.opcode.name.startsWith("invoke") }
        assertEquals(sent.map(LAYOUT_CALLS::getValue), calls.map(::reference))
        assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE), calls.map { it.opcode })
        assertEquals(listOf((0..4).toList(), listOf(5, 1), listOf(5), (0..7).toList()), calls.map { call ->
            when (call) {
                is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
                else -> (call as FiveRegisterInstruction).let {
                    listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
                }
            }
        })
        assertEquals("nothing else moved", method.implementation!!.instructions.size,
            context.mutableClassDefBy(type).methods.single().implementation!!.instructions.size)
        assertEquals("the extension keeps Android's calls", sent,
            context.mutableClassDefBy(extension).methods.single().implementation!!.instructions.mapNotNull(::layoutCall))
    }

    /**
     * A line-height span's chooseHeight, the even split Facebook's set line heights use: the
     * shortfall comes off the ascent and the descent alike, and the first line's top and the last
     * line's bottom go to the new edges. v6 is this, v7 the text, v8 the start, v9 the end, v12 the
     * metrics.
     */
    private fun lineHeightSpan(type: String) = MutableMethod(
        ImmutableMethod(
            type, "chooseHeight",
            listOf("Ljava/lang/CharSequence;", "I", "I", "I", "I", "Landroid/graphics/Paint\$FontMetricsInt;").map {
                ImmutableMethodParameter(it, null, null)
            },
            "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(13, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                iget v1, v6, $type->height:I
                iget v2, v12, Landroid/graphics/Paint${'$'}FontMetricsInt;->ascent:I
                iget v3, v12, Landroid/graphics/Paint${'$'}FontMetricsInt;->descent:I
                sub-int v0, v3, v2
                sub-int/2addr v1, v0
                div-int/lit8 v1, v1, 0x2
                sub-int/2addr v2, v1
                add-int/2addr v3, v1
                iput v2, v12, Landroid/graphics/Paint${'$'}FontMetricsInt;->ascent:I
                iput v3, v12, Landroid/graphics/Paint${'$'}FontMetricsInt;->descent:I
                if-nez v8, :middle
                iput v2, v12, Landroid/graphics/Paint${'$'}FontMetricsInt;->top:I
                :middle
                invoke-interface { v7 }, Ljava/lang/CharSequence;->length()I
                move-result v0
                if-ne v9, v0, :done
                iput v3, v12, Landroid/graphics/Paint${'$'}FontMetricsInt;->bottom:I
                :done
                return-void
            """.trimIndent(),
        )
    }

    /**
     * Through the patcher: a line-height span's writes of the first line's top and the last line's
     * bottom become the extension's stand-ins on the write's own two registers, one instruction for
     * one, so the jump to the top's write lands on its stand-in and the one past it on what followed
     * (#96). Its writes of the ascent and descent stay, as does the extension's own span.
     */
    @Test
    fun `a line-height span's top and bottom writes go to the extension`() {
        val type = "Lfixture/LineHeight;"
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val before = lineHeightSpan(type).implementation!!.instructions.toList()
        val context = PatchContexts.of(listOf(robotoClass(type, lineHeightSpan(type)), robotoClass(extension, lineHeightSpan(extension))))
        assertEquals(2, context.hookLineHeights())

        val after = context.mutableClassDefBy(type).methods.single().implementation!!.instructions.toList()
        assertEquals("one instruction for one", before.size, after.size)
        val writes = before.indices.filter { lineEdgeWrite(before[it]) != null }
        assertEquals(listOf(11, 15), writes)
        for (index in before.indices) {
            if (index in writes) continue
            assertEquals("instruction $index", before[index].opcode, after[index].opcode)
        }
        assertEquals(LINE_EDGES.values.toList(), writes.map { reference(after[it]) })
        assertEquals(listOf(listOf(2, 12), listOf(3, 12)), writes.map { index ->
            (after[index] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD).take(it.registerCount) }
        })
        assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC), writes.map { after[it].opcode })
        assertEquals("the ascent and descent writes stay", 2, after.count { it.opcode == Opcode.IPUT })

        val addresses = after.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        fun target(index: Int) = addresses.indexOf(addresses[index] + (after[index] as OffsetInstruction).codeOffset)
        assertEquals("the jump past the top lands on length()", 12, target(10))
        assertEquals("the jump past the bottom lands on the return", 16, target(14))

        assertEquals("the extension's span keeps its writes", 2,
            context.mutableClassDefBy(extension).methods.single().implementation!!.instructions.count { lineEdgeWrite(it) != null })
    }

    /** A build whose spans never set a line's edges has moved its line heights somewhere the patch doesn't know. */
    @Test
    fun `a build without a line-height span stops the patch`() {
        val type = "Lfixture/TextLayout;"
        val method = staticMethod(type, registers = 1, params = emptyList(), body = """
            const/4 v0, 0x0
            return-object v0
        """)
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(robotoClass(type, method))).hookLineHeights() }
    }

    /** Both line edge stand-ins are public static methods of the extension's OwnFont, at those very signatures. */
    @Test
    fun `the extension has each line edge stand-in the patch calls`() {
        val own = ExtensionDex.classDef(OWN_FONT).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        for (call in LINE_EDGES.values) assertTrue(call, call in own)
    }

    /** A build that never sets the padding on a layout builder has moved its text somewhere the patch doesn't know. */
    @Test
    fun `a build without a padding choice on a layout builder stops the patch`() {
        val type = "Lfixture/TextLayout;"
        val method = staticMethod(type, registers = 6, params = emptyList(), body = """
            const-string v0, "y"
            const/4 v1, 0x0
            invoke-static { v0, v1, v1, v1, v1 }, $OBTAIN_LAYOUT
            move-result-object v5
            const/4 v5, 0x0
            return-object v5
        """)
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(robotoClass(type, method))).hookTextLayouts() }
    }

    /** Every stand-in the patch calls is a public static method of the extension's OwnFont, at that very signature. */
    @Test
    fun `the extension has each text layout stand-in the patch calls`() {
        val own = ExtensionDex.classDef(OWN_FONT).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        for (call in LAYOUT_CALLS.values) assertTrue(call, call in own)
    }

    /**
     * A try block over the read alone covers its getter and the move after it, and one that starts
     * right after the read leaves both out, as it left the read out.
     */
    @Test
    fun `a try block's edges on a read stay where they were`() {
        val type = "Lfixture/Header;"
        val reader = MutableMethod(
            ImmutableMethod(
                type, "bold", emptyList(), TYPEFACE, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    2,
                    listOf(
                        // 0: address 0, 2 units, the read, alone in the first try
                        ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(TYPEFACE, "DEFAULT_BOLD", TYPEFACE)),
                        // 1: address 2, in the second try
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        // 2: address 3, the handler
                        ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 1),
                        // 3: address 4
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                    listOf(
                        ImmutableTryBlock(0, 2, listOf(ImmutableExceptionHandler(null, 3))),
                        ImmutableTryBlock(2, 1, listOf(ImmutableExceptionHandler(null, 3))),
                    ),
                    null,
                ),
            ),
        )
        val context = PatchContexts.of(listOf(robotoClass(type, reader)))
        assertEquals(1, context.hookDefaultTypefaces())

        val implementation = context.mutableClassDefBy(type).methods.single().implementation!!
        assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT, Opcode.MOVE_EXCEPTION, Opcode.RETURN_OBJECT),
            implementation.instructions.map { it.opcode })
        // The getter is 3 units and the move 1, so the return is at 4 and the handler at 5.
        assertEquals(listOf(listOf(0, 4, 5), listOf(4, 1, 5)), implementation.tryBlocks.map { block ->
            listOf(block.startCodeAddress, block.codeUnitCount, block.exceptionHandlers.single().handlerCodeAddress)
        })
    }

    private val inflater = "Landroid/view/LayoutInflater;"

    /** A constructor, <init>(Context)V, of [type], whose body is [smali] with this in v0. */
    private fun constructor(type: String, smali: String) = MutableMethod(
        ImmutableMethod(
            type, "<init>", listOf(ImmutableMethodParameter(CONTEXT, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali.trimIndent()) }

    /** Builds a Button, an EditText by range and an ImageView, and asks an inflater for a view twice. */
    private fun rows(type: String) = MutableMethod(
        ImmutableMethod(
            type, "rows", listOf(ImmutableMethodParameter(CONTEXT, null, null), ImmutableMethodParameter(inflater, null, null)),
            VIEW, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(8, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                if-eqz v6, :built
                new-instance v0, Landroid/widget/Button;
                invoke-direct { v0, v6 }, Landroid/widget/Button;-><init>($CONTEXT)V
                :built
                new-instance v4, Landroid/widget/EditText;
                move-object v5, v6
                invoke-direct/range { v4 .. v5 }, Landroid/widget/EditText;-><init>($CONTEXT)V
                new-instance v1, Landroid/widget/ImageView;
                invoke-direct { v1, v6 }, Landroid/widget/ImageView;-><init>($CONTEXT)V
                const-string v1, "TextView"
                const-string v2, "android.widget."
                const/4 v3, 0x0
                invoke-virtual { v7, v1, v2, v3 }, $CREATE_VIEW
                move-result-object v0
                invoke-virtual { v7, v1, v2, v3 }, $CREATE_VIEW
                return-object v0
            """.trimIndent(),
        )
    }

    /**
     * Through the patcher: each of Android's text views a method builds goes to the extension right
     * after its constructor, on the register the constructor got, by `new` in either form and as a
     * view's super call, and so does each view an inflater's createView answers. The jump past the
     * Button still lands on what followed its constructor, so a path where it was never built skips
     * the hook. A view of another kind, a super call to a view that isn't Android's, an answer
     * nothing keeps and the extension's own classes are left alone.
     */
    @Test
    fun `each text view Facebook builds goes to the extension right after it's built`() {
        val rowsType = "Lfixture/Rows;"
        val label = "Lfixture/Label;"
        val compat = "Lfixture/CompatLabel;"
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val context = PatchContexts.of(
            listOf(
                robotoClass(rowsType, rows(rowsType)),
                ImmutableClassDef(label, AccessFlags.PUBLIC.value, TEXT_VIEW, null, null, null, null, listOf(
                    constructor(label, """
                        invoke-direct { p0, p1 }, $TEXT_VIEW-><init>($CONTEXT)V
                        return-void
                    """),
                )),
                ImmutableClassDef(compat, AccessFlags.PUBLIC.value, "Landroidx/appcompat/widget/AppCompatTextView;", null, null, null, null, listOf(
                    constructor(compat, """
                        invoke-direct { p0, p1 }, Landroidx/appcompat/widget/AppCompatTextView;-><init>($CONTEXT)V
                        return-void
                    """),
                )),
                robotoClass(extension, rows(extension)),
            ),
        )
        assertEquals("the Button, the EditText, the inflated view and Label's super call", 4, context.hookTextViews())

        val body = context.mutableClassDefBy(rowsType).methods.single().implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.IF_EQZ, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC_RANGE,
                Opcode.NEW_INSTANCE, Opcode.MOVE_OBJECT, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC_RANGE,
                Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.CONST_STRING, Opcode.CONST_STRING, Opcode.CONST_4,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL,
                Opcode.RETURN_OBJECT,
            ),
            body.map { it.opcode },
        )
        val hooks = listOf(3, 7, 15).map { body[it] as RegisterRangeInstruction }
        assertEquals(listOf(OWN_TEXT_VIEW, OWN_TEXT_VIEW, OWN_INFLATED), hooks.map(::reference))
        assertEquals("the Button's, the EditText's and the answer's register, one each",
            listOf(listOf(0, 1), listOf(4, 1), listOf(0, 1)), hooks.map { listOf(it.startRegister, it.registerCount) })
        val addresses = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        assertEquals("the jump past the Button lands on the EditText's new-instance", 4,
            addresses.indexOf(addresses[0] + (body[0] as OffsetInstruction).codeOffset))

        val built = context.mutableClassDefBy(label).methods.single().implementation!!.instructions.toList()
        assertEquals(listOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC_RANGE, Opcode.RETURN_VOID), built.map { it.opcode })
        assertEquals(listOf(OWN_TEXT_VIEW, "0"), (built[1] as RegisterRangeInstruction).let { listOf(reference(it), "${it.startRegister}") })
        assertEquals(2, context.mutableClassDefBy(compat).methods.single().implementation!!.instructions.count())
        assertTrue("the extension's views were sent", context.mutableClassDefBy(extension).methods.single().implementation!!
            .instructions.none { reference(it)?.startsWith(OWN_FONT) == true })
    }

    /**
     * Morphe Manager patches on the phone, in the phone's language. On one whose language writes
     * numbers in digits of its own, like Egyptian Arabic, the hooks still name their registers in
     * the digits the assembler reads, so they go in.
     */
    @Test
    fun `the text view hooks go in on a phone whose language writes other digits`() {
        val rowsType = "Lfixture/Rows;"
        val was = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals("the language writes digits of its own", "\u0661\u0662", "%d".format(12))
            val context = PatchContexts.of(listOf(robotoClass(rowsType, rows(rowsType))))
            assertEquals("the Button, the EditText and the inflated view", 3, context.hookTextViews())
            val body = context.mutableClassDefBy(rowsType).methods.single().implementation!!.instructions.toList()
            assertEquals(listOf(0, 4, 0), body.filter { reference(it)?.startsWith(OWN_FONT) == true }
                .map { (it as RegisterRangeInstruction).startRegister })
        } finally {
            Locale.setDefault(was)
        }
    }

    /** A build where nothing outside the extension builds one of Android's text views stops the patch. */
    @Test
    fun `a build that builds no text view stops the patch`() {
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val refused = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(robotoClass(extension, rows(extension)))).hookTextViews()
        }
        assertTrue(refused.message, "found no text view Facebook builds" in refused.message.orEmpty())
    }

    /** A build where nothing outside the extension reads Android's defaults stops the patch. */
    @Test
    fun `a build reading none of Android's default typefaces stops the patch`() {
        val extension = "Lapp/morphe/extension/facebook/font/Stub;"
        val refused = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(robotoClass(extension, defaultsReader(extension)), robotoClass(roboto, robotoBuilder())))
                .hookDefaultTypefaces()
        }
        assertTrue(refused.message, "found no read of Android's default typefaces outside the extension" in refused.message.orEmpty())
    }

    /**
     * The calls the patch writes are in the OwnFont the bundle ships, public and static, with
     * exactly the descriptors the patch writes. Read from the compiled extension, so a Java
     * parameter that compiles to another type fails here rather than as a NoSuchMethodError when
     * Facebook first draws text.
     */
    @Test
    fun `every call the patch writes is in the extension`() {
        val declared = ExtensionDex.classDef(OWN_FONT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$OWN_FONT->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val getters = DEFAULT_TYPEFACES.values.map { "$OWN_FONT->$it()$TYPEFACE" }
        val calls = DEFAULT_CALLS.map(::ownCall)
        assertTrue(OWN_DEFAULT_FROM_STYLE in calls)
        val views = listOf(OWN_TEXT_VIEW, OWN_INFLATED)
        for (call in listOf(REPLACE, REMEMBER_VARIATION, REPLACE_BUILT, REMEMBER_FAMILY, REPLACE_REACT_NATIVE, REPLACE_PHONE_FONT) + getters + calls + views) {
            assertTrue("OwnFont declares no public static $call: $declared", call in declared)
        }
    }
}
