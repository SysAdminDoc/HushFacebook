/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.RepoFiles
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two Stories tray adapter methods of the feed's adapter configuration, as the patch picks
 * them. Each rule has a control that must fail it: the tray controller's constructor holds the
 * start and stop names too, and must never be picked.
 */
class StoriesTrayAdaptersTest {
    private val configuration = "Lcom/example/NewsFeedAdapterConfiguration;"
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    private fun method(
        name: String,
        strings: List<String>,
        flags: Int = static,
        parameters: List<String> = listOf(configuration, IMMUTABLE_LIST_BUILDER),
        returnType: String = "Lcom/example/Adapter;",
        definingClass: String = configuration,
    ): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
        ImmutableMethodImplementation(
            6,
            strings.mapIndexed { i, s -> ImmutableInstruction21c(Opcode.CONST_STRING, i % 4, ImmutableStringReference(s)) } +
                listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            null, null,
        ),
    )

    private fun classOf(vararg methods: Method) =
        ImmutableClassDef(configuration, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    @Test
    fun `the classic adapter holds its trace name and has the adapter shape`() {
        val found = legacyTrayAdapters(classOf(
            method("A00", listOf(ADD_STORIES_ADAPTER)),
            method("A0x", listOf(ADD_STORIES_ADAPTER), flags = AccessFlags.PUBLIC.value),
            method("A0y", listOf(ADD_STORIES_ADAPTER), parameters = listOf(IMMUTABLE_LIST_BUILDER)),
            method("A0z", listOf(ADD_STORIES_ADAPTER), returnType = "V"),
            method("A02", listOf("NewsFeedAdapterConfiguration.<get-inlineComposerSectionAdapter>")),
        ))
        assertEquals(listOf("A00"), found.map { it.name })
    }

    /** 585's Android 9 build: instance methods taking only the builder, `this` standing in for the class. */
    @Test
    fun `an instance adapter takes only the list builder`() {
        val instance = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        val found = legacyTrayAdapters(classOf(
            method("A00", listOf(ADD_STORIES_ADAPTER), flags = instance, parameters = listOf(IMMUTABLE_LIST_BUILDER)),
            method("A0w", listOf(ADD_STORIES_ADAPTER), flags = instance, parameters = emptyList()),
            method("A0v", listOf(ADD_STORIES_ADAPTER), flags = instance,
                parameters = listOf(IMMUTABLE_LIST_BUILDER, IMMUTABLE_LIST_BUILDER)),
            method("<init>", listOf(ADD_STORIES_ADAPTER), flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                parameters = listOf(IMMUTABLE_LIST_BUILDER), returnType = "V"),
        ))
        assertEquals(listOf("A00"), found.map { it.name })
        val unified = unifiedTrayAdapters(classOf(method("A01", listOf(TRAY_ADAPTER_START, TOFU, TRAY_ADAPTER_STOP),
            flags = instance, parameters = listOf(IMMUTABLE_LIST_BUILDER))))
        assertEquals(listOf("A01"), unified.map { it.name })
    }

    @Test
    fun `the unified adapter holds all three names and has the adapter shape`() {
        val all = listOf(TRAY_ADAPTER_START, TOFU, TRAY_ADAPTER_STOP)
        val found = unifiedTrayAdapters(classOf(
            method("A01", all),
            method("A0a", listOf(TRAY_ADAPTER_START, TRAY_ADAPTER_STOP)),
            method("A0b", listOf(TRAY_ADAPTER_START, TOFU)),
            method("A0c", listOf(TOFU, TRAY_ADAPTER_STOP)),
            method("A0d", all, parameters = listOf("Lcom/example/Other;", IMMUTABLE_LIST_BUILDER)),
            // The tray controller's constructor: the same start and stop names, not an adapter.
            method("<init>", all, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                parameters = listOf("Landroid/content/Context;"), returnType = "V"),
        ))
        assertEquals(listOf("A01"), found.map { it.name })
    }

    /** The hook passes the adapters under the numbers the extension reads them by. */
    @Test
    fun `the patch and the extension agree on the hook and the adapter numbers`() {
        val text = File(RepoFiles.root,
            "extensions/facebook/src/main/java/app/morphe/extension/facebook/feed/FeedFilter.java").readText()
        assertTrue("the extension has no public static int storiesTrayCount(Object, int, int)",
            Regex("""public static int storiesTrayCount\(Object \w+, int \w+, int \w+\)""").containsMatchIn(text))
        assertTrue(STORIES_TRAY_COUNT.endsWith("/feed/FeedFilter;->storiesTrayCount(Ljava/lang/Object;II)I"))
        assertTrue("LEGACY_TRAY differs", text.contains("public static final int LEGACY_TRAY = $LEGACY_TRAY;"))
        assertTrue("UNIFIED_TRAY differs", text.contains("public static final int UNIFIED_TRAY = $UNIFIED_TRAY;"))
        assertTrue("HOME_COMPOSER differs", text.contains("public static final int HOME_COMPOSER = $HOME_COMPOSER;"))
    }

    /** The composer row getter: static, taking only its own class, holding the trace name. */
    @Test
    fun `the composer row getter holds its trace name and takes only its own class`() {
        val getter = method("A02", listOf(INLINE_COMPOSER_ADAPTER), parameters = listOf(configuration))
        assertEquals(listOf(getter), composerAdapters(classOf(getter)))

        assertTrue("an instance method", composerAdapters(classOf(method("A02", listOf(INLINE_COMPOSER_ADAPTER),
            flags = AccessFlags.PUBLIC.value, parameters = listOf(configuration)))).isEmpty())
        // 585's Android 9 build: an instance getter taking nothing.
        val instance = method("A02", listOf(INLINE_COMPOSER_ADAPTER), flags = AccessFlags.PUBLIC.value, parameters = emptyList())
        assertEquals(listOf(instance), composerAdapters(classOf(instance)))
        assertTrue("the tray adapter shape", composerAdapters(classOf(method("A02", listOf(INLINE_COMPOSER_ADAPTER)))).isEmpty())
        assertTrue("no trace name", composerAdapters(classOf(method("A02", listOf(ADD_STORIES_ADAPTER),
            parameters = listOf(configuration)))).isEmpty())
        assertTrue("returns nothing", composerAdapters(classOf(method("A02", listOf(INLINE_COMPOSER_ADAPTER),
            parameters = listOf(configuration), returnType = "V"))).isEmpty())
    }

    private val classic = method("A00", listOf(ADD_STORIES_ADAPTER))
    private val unified = method("A01", listOf(TRAY_ADAPTER_START, TOFU, TRAY_ADAPTER_STOP))
    private val owner = "Lcom/example/FeedAdapters;"

    private fun reference(method: Method) = ImmutableMethodReference(
        method.definingClass, method.name, method.parameterTypes.map { it.toString() }, method.returnType,
    )

    /**
     * The list builder as the fixtures have it: a load of its own list field and an `if-nez` past
     * everything when [guarded], the trace name, a static call to each of [calls], return-void. The
     * guard's field belongs to [field]'s class, and [guardOffset] is where the `if-nez` goes: 10
     * code units reach the return-void, 2 only reach the trace name, before the calls.
     */
    private fun builder(
        guarded: Boolean = true,
        field: String = owner,
        guardOffset: Int = 10,
        calls: List<Method> = listOf(classic, unified),
        guard: Opcode = Opcode.IF_NEZ,
    ): Method {
        val head = if (!guarded) emptyList() else listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, ImmutableFieldReference(field, "A02", "Lcom/example/Multi;")),
            ImmutableInstruction21t(guard, 0, guardOffset),
        )
        val body = listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(CREATE_ADAPTER))) +
            calls.map { ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 3, 1, 0, 0, 0, reference(it)) } +
            ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableMethod(
            owner, "A00", listOf(ImmutableMethodParameter(owner, null, null)), "V", static, null, null,
            ImmutableMethodImplementation(4, head + body, null, null),
        )
    }

    /** The shape the wording rests on, and the controls that must fail it. */
    @Test
    fun `a list builder keeps its list only with a guard on its own field that skips both calls`() {
        assertTrue(buildsListOnce(builder(), listOf(classic, unified)))

        assertFalse("no guard at all", buildsListOnce(builder(guarded = false), listOf(classic, unified)))
        assertFalse("the guard lands before the calls", buildsListOnce(builder(guardOffset = 2), listOf(classic, unified)))
        assertFalse("the guard reads another class's field",
            buildsListOnce(builder(field = configuration), listOf(classic, unified)))
        assertFalse("the guard skips the calls when the field is empty",
            buildsListOnce(builder(guard = Opcode.IF_EQZ), listOf(classic, unified)))
        assertFalse("one of the adapters is never called",
            buildsListOnce(builder(calls = listOf(classic)), listOf(classic, unified)))
    }

    /**
     * 585's Android 9 build: the guarded method calls one static method of the configuration class
     * that calls both instance adapters with invoke-direct.
     */
    @Test
    fun `a list builder may call both adapters through one assembler`() {
        val instance = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        val classic = method("A00", listOf(ADD_STORIES_ADAPTER), flags = instance, parameters = listOf(IMMUTABLE_LIST_BUILDER))
        val unified = method("A01", listOf(TRAY_ADAPTER_START, TOFU, TRAY_ADAPTER_STOP), flags = instance,
            parameters = listOf(IMMUTABLE_LIST_BUILDER))
        fun assembler(calls: List<Method>) = ImmutableMethod(
            configuration, "A04", listOf(ImmutableMethodParameter(configuration, null, null)), "Ljava/lang/Object;", static,
            null, null,
            ImmutableMethodImplementation(4, calls.map {
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 2, 3, 1, 0, 0, 0, reference(it))
            } + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0), null, null),
        )
        val both = assembler(listOf(classic, unified))
        val adapters = listOf(classic, unified)
        // With one call in the builder, 7 code units reach the return-void.
        assertTrue(buildsListOnce(builder(calls = listOf(both), guardOffset = 7), adapters, listOf(classic, unified, both)))

        assertFalse("no assembler offered", buildsListOnce(builder(calls = listOf(both), guardOffset = 7), adapters))
        val one = assembler(listOf(classic))
        assertFalse("the assembler calls one adapter",
            buildsListOnce(builder(calls = listOf(one), guardOffset = 7), adapters, listOf(classic, unified, one)))
        assertFalse("the guard lands before the assembler",
            buildsListOnce(builder(calls = listOf(both), guardOffset = 2), adapters, listOf(classic, unified, both)))
        assertFalse("the assembler is never called",
            buildsListOnce(builder(calls = emptyList()), adapters, listOf(classic, unified, both)))
    }

    /** The settings row and the README tell the same story about when the switch acts. */
    @Test
    fun `the settings row and the README both say a change shows on the next refresh`() {
        val row = File(RepoFiles.root,
            "extensions/facebook/src/main/java/app/morphe/extension/facebook/settings/FeedPages.java")
            .readText()
        val readme = File(RepoFiles.root, "README.md").readText()
        assertTrue("the row doesn't say a change shows on the next refresh",
            row.contains("\"A change shows the next time you pull down to refresh.\""))
        assertFalse("the row still says the switch waits for a restart",
            row.contains("\"The switch takes effect when Facebook restarts.\""))
        assertTrue("the README doesn't say a change shows on the next refresh",
            readme.contains("The Stories tray switch shows the next time you pull the feed down to refresh"))
        assertFalse("the README still says the switch waits for a restart",
            readme.contains("so that switch takes effect when Facebook restarts"))
        assertFalse("the README still says the tray comes back when you return to the feed",
            readme.contains("such as when you come back to it"))
    }
}
