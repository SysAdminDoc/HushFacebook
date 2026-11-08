/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one code site a clone breaks (#417): Facebook asks whether an intent opens one of its own
 * screens by comparing the component's package with its name, which the clone's components never
 * carry. Only that comparison, in the method that logs [INTERNAL_ACTIVITY_MARKER], goes through
 * the extension, shaped like 585's `LX/0OLO;->H48`: the name from a string pool by an int, the
 * package straight from getPackageName(). Anything the call could break is left alone. The recent
 * apps label is hooked once, App name's hook counting as done.
 */
class CloneInstallShapesTest {
    private val holder = "Lfixture/Intents;"
    private val getPackageName = "invoke-virtual { p0 }, Landroid/content/ComponentName;->getPackageName()Ljava/lang/String;"
    private val equals = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

    /** Facebook's name the way 585 has it, from a string pool by an int, into v1. */
    private val pool = """
        const/16 v0, 0x2f
        invoke-static { v0 }, Lfixture/Pool;->A00(I)Ljava/lang/String;
        move-result-object v1
    """

    private fun method(type: String, smali: String, parameter: String, returnType: String): Method = MutableMethod(
        ImmutableMethod(
            type, "H48", listOf(ImmutableMethodParameter(parameter, null, null)), returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(5, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }.let(ImmutableMethod::of)

    private fun classDef(type: String, method: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(method))

    private fun check(
        facebooks: String = pool,
        component: String = getPackageName,
        compare: String = "invoke-virtual { v1, v0 }, $equals",
        marker: String = INTERNAL_ACTIVITY_MARKER,
        after: String = "",
    ) = """
        $facebooks
        $component
        move-result-object v0
        $compare
        move-result v2
        if-eqz v2, :outside
        const-string v3, "$marker"
        invoke-static { v3 }, Lfixture/Log;->d(Ljava/lang/String;)V
        $after
        :outside
        return v2
    """

    private fun sites(smali: String, type: String = holder) =
        method(type, smali, "Landroid/content/ComponentName;", "Z").ownComponentChecks()

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    @Test
    fun `the component's package next to Facebook's name is found`() {
        assertEquals("585's shape", listOf(5 to 0), sites(check()))
        assertEquals("the name as a constant", listOf(3 to 0), sites(check(facebooks = "const-string v1, \"com.facebook.katana\"")))
        assertEquals("the sides the other way round", listOf(5 to 0), sites(check(compare = "invoke-virtual { v0, v1 }, $equals")))
    }

    @Test
    fun `anything else is left alone`() {
        val refused = mapOf(
            "no internal activity log line" to check(marker = "fix:something_else"),
            "another app's name" to check(facebooks = "const-string v1, \"com.facebook.orca\""),
            "a pool that answers an Object" to check(facebooks = pool.replace("(I)Ljava/lang/String;", "(I)Ljava/lang/Object;")),
            "a String that isn't from a pool" to check(facebooks = """
                invoke-virtual { p0 }, Landroid/content/ComponentName;->getClassName()Ljava/lang/String;
                move-result-object v1
            """),
            "the class name instead of the package" to
                check(component = getPackageName.replace("getPackageName", "getClassName")),
            "the package read again afterwards" to check(after = "invoke-static { v0 }, Lfixture/Log;->d(Ljava/lang/String;)V"),
            "one register on both sides" to check(compare = "invoke-virtual { v0, v0 }, $equals"),
            "a jump onto the comparison" to check(compare = ":compare\n invoke-virtual { v1, v0 }, $equals", after = "goto :compare"),
            "two writers of the package" to """
                $pool
                if-eqz p0, :other
                $getPackageName
                move-result-object v0
                goto :compare
                :other
                const-string v0, "none"
                :compare
                invoke-virtual { v1, v0 }, $equals
                move-result v2
                const-string v3, "$INTERNAL_ACTIVITY_MARKER"
                return v2
            """,
        )
        for ((why, smali) in refused) assertEquals(why, emptyList<Pair<Int, Int>>(), sites(smali))
    }

    @Test
    fun `the package goes through the extension once, and the extension's own code is left alone`() {
        val extension = "Lapp/morphe/extension/facebook/coexist/Copy;"
        val context = PatchContexts.of(
            listOf(
                classDef(holder, method(holder, check(), "Landroid/content/ComponentName;", "Z")),
                classDef(extension, method(extension, check(), "Landroid/content/ComponentName;", "Z")),
            ),
        )
        val original = context.mutableClassDefBy(holder).methods.single().code()

        assertEquals(1, context.routeOwnComponentChecks())

        val routed = context.mutableClassDefBy(holder).methods.single().code()
        assertEquals(original.size + 2, routed.size)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, routed[5].opcode)
        assertEquals(AS_FACEBOOK_CALL, routed[5].reference())
        assertEquals(0 to 1, (routed[5] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(Opcode.MOVE_RESULT_OBJECT, routed[6].opcode)
        assertEquals(0, (routed[6] as OneRegisterInstruction).registerA)
        assertEquals(equals, routed[7].reference())
        assertEquals("Facebook's code stays", original.map { it.opcode }, routed.take(5).map { it.opcode } + routed.drop(7).map { it.opcode })
        assertEquals("the extension's copy", original.size, context.mutableClassDefBy(extension).methods.single().code().size)

        assertEquals("a second run finds it done", 0, context.routeOwnComponentChecks())
        assertEquals(original.size + 2, context.mutableClassDefBy(holder).methods.single().code().size)
    }

    private val description = "Landroid/app/ActivityManager\$TaskDescription;"

    private fun labeler(type: String, hook: String = "") = classDef(type, method(type, """
        const-string v1, "Facebook"
        const/4 v2, 0x0
        const/4 v3, 0x0
        new-instance v0, $description
        $hook
        invoke-direct { v0, v1, v2, v3 }, $description-><init>(Ljava/lang/String;Landroid/graphics/Bitmap;I)V
        invoke-virtual { p0, v0 }, Landroid/app/Activity;->setTaskDescription($description)V
        return-void
    """, "Landroid/app/Activity;", "V"))

    @Test
    fun `the recent apps label is hooked once, and App name's hook counts`() {
        val type = "Lfixture/TaskLabeler;"
        val context = PatchContexts.of(listOf(labeler(type)))
        assertEquals(1, context.labelCloneTasks())
        val hooked = context.mutableClassDefBy(type).methods.single().code()
        assertEquals(TASK_LABEL, hooked[4].reference())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, hooked[5].opcode)
        assertEquals("a second run", 0, context.labelCloneTasks())
        assertEquals(hooked.size, context.mutableClassDefBy(type).methods.single().code().size)

        val byAppName = PatchContexts.of(listOf(labeler(type, hook = "invoke-static { v1 }, $TASK_LABEL\nmove-result-object v1")))
        assertEquals("App name hooked it already", 0, byAppName.labelCloneTasks())
        val extension = PatchContexts.of(listOf(labeler("Lapp/morphe/extension/facebook/coexist/Labeler;")))
        assertEquals("the extension's own", 0, extension.labelCloneTasks())
    }
}
