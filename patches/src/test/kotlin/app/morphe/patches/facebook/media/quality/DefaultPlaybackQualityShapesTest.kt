/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How Default playback quality finds the evaluator's setter and first choice on 586, beside the near misses it must not take. */
class DefaultPlaybackQualityShapesTest {
    private val evaluatorType = "Lfixture/Evaluator;"
    private val track = "Lfixture/Track;"
    private val info = "Lfixture/Info;"
    private val log = "Lfixture/Log;"
    private val string = "Ljava/lang/String;"
    private val preselected = "$evaluatorType->preselected:$string"

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        body: String,
        registers: Int,
        static: Boolean = false,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    private fun classDef(type: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.map(ImmutableMethod::of))

    /** The setter 586 calls again (LX/05gB;->A05): each track's label through an info class, compared with the one it's handed. */
    private val setter = method(evaluatorType, "apply", listOf(string), """
        iget-object v0, p0, $evaluatorType->tracks:[$track
        array-length v1, v0
        const/4 v2, 0x0
        :loop
        if-ge v2, v1, :done
        aget-object v3, v0, v2
        invoke-static {v3}, $info->of($track)$info
        move-result-object v4
        iget-object v4, v4, $info->label:$string
        invoke-virtual {p1, v4}, $string->equals(Ljava/lang/Object;)Z
        move-result v4
        if-nez v4, :done
        add-int/lit8 v2, v2, 0x1
        goto :loop
        :done
        return-void
    """, registers = 7)

    private val constructor = method(evaluatorType, "<init>", listOf(ABR_CONFIGURATION, string), """
        invoke-direct {p0}, Ljava/lang/Object;-><init>()V
        iput-object p1, p0, $evaluatorType->configuration:$ABR_CONFIGURATION
        iput-object p2, p0, $preselected
        return-void
    """, registers = 3)

    /** 586's first choice (Bhh): a null check on the preselected label, then a second read handed to the setter, then the field emptied. */
    private val firstChoice = method(evaluatorType, "choose", emptyList(), """
        iget-object v0, p0, $preselected
        if-eqz v0, :done
        iget-object v0, p0, $preselected
        invoke-virtual {p0, v0}, $evaluatorType->apply($string)V
        const/4 v0, 0x0
        iput-object v0, p0, $preselected
        :done
        return-void
    """, registers = 3)

    /** The label handed to the setter of another evaluator, not the one it was read from. */
    private val otherReceiver = method(evaluatorType, "other", emptyList(), """
        iget-object v1, p0, $evaluatorType->next:$evaluatorType
        iget-object v0, p0, $preselected
        invoke-virtual {v1, v0}, $evaluatorType->apply($string)V
        return-void
    """, registers = 3)

    /** The Hero player's method holding the trace: a (String)V call to a class not built with the configuration, one to a class the build lacks, then the setter. */
    private val holder = method("Lfixture/Hero;", "setCustom", listOf(evaluatorType, string, log), """
        const-string v0, "$SET_CUSTOM_QUALITY"
        invoke-virtual {p2, p1}, $log->note($string)V
        invoke-virtual {p2, p1}, Lfixture/Missing;->gone($string)V
        invoke-virtual {p0, p1}, $evaluatorType->apply($string)V
        return-void
    """, registers = 4, static = true)

    private val logClass = classDef(log, method(log, "<init>", emptyList(), "return-void", registers = 1), method(log, "note", listOf(string), "return-void", registers = 2))

    @Test
    fun `586's holder calls the setter, which reads the tracks and their labels`() {
        val evaluator = classDef(evaluatorType, constructor, setter, firstChoice)
        val lookup = mapOf(evaluatorType to evaluator, log to logClass)::get
        val setters = customQualitySetters(holder, lookup)
        assertEquals("setters found", listOf("$evaluatorType->apply"), setters.map { "${it.definingClass}->${it.name}" })
        val reader = setters.single()
        assertEquals("arrays the setter reads", listOf("$evaluatorType->tracks:[$track"), trackArrayReads(reader).map { it.toString() })
        assertEquals("how the setter reads a label", listOf("$info->of($track)$info" to "$info->label:$string"),
            labelReads(reader, track).map { (call, field) -> call.toString() to field.toString() })
        assertTrue("the holder reads tracks itself", trackArrayReads(holder).isEmpty())
    }

    @Test
    fun `586's first choice hands its second read of the label to the setter`() {
        val evaluator = classDef(evaluatorType, constructor, setter, firstChoice)
        val (labelOf, _) = labelReads(setter, track).single()
        val reads = preselectedReads(evaluator, constructor, labelOf, setter)
        assertEquals("reads of the preselected label", listOf("choose" to 2), reads.map { it.method.name to it.index })
        assertEquals("the field", preselected, reads.single().field.toString())
        assertTrue("585's lookup, without the setter, takes the hand-over", preselectedReads(evaluator, constructor, labelOf).isEmpty())
    }

    @Test
    fun `a label handed to another evaluator's setter isn't the first choice`() {
        val evaluator = classDef(evaluatorType, constructor, setter, otherReceiver)
        val (labelOf, _) = labelReads(setter, track).single()
        assertTrue("reads of the preselected label", preselectedReads(evaluator, constructor, labelOf, setter).isEmpty())
    }
}
