/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.pip.sheetContext
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #240: on each declared build, the reel caption's render hands each tap lambda it builds to a
 * helper right after building it, and the helper hands the lambda's reel and whole caption to the
 * extension. The component's own caption field is null in the Reels viewer, so the lambda is where
 * the shown caption lives. A reel's More sheet builder asks a helper for the Copy caption row first thing,
 * with the sheet's context, the reel's video id and the row list in v0 to v2, and the helper draws
 * the row with Facebook's own copy icon.
 */
class ReelCaptionRowFixtureTest {
    private val list = "Ljava/util/List;"

    /** Every class [method] names or calls with, and its own parameter types. */
    private fun reachedBy(method: Method): Set<String> = method.implementation!!.instructions.flatMap { instruction ->
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is MethodReference -> listOf(reference.definingClass, reference.returnType) + reference.parameterTypes.map(CharSequence::toString)
            is FieldReference -> listOf(reference.definingClass, reference.type)
            is TypeReference -> listOf(reference.type)
            else -> emptyList()
        }
    }.plus(method.parameterTypes.map(CharSequence::toString)).filter { it.startsWith("L") }.toSet()

    private fun Method.calls() = implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
    }

    private fun Method.fields() = implementation!!.instructions.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString()
    }

    @Test
    fun `the caption render and the More sheet hand Copy caption what it needs on each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = mutableMapOf<String, ClassDef>()

                // The More sheet, as the Picture-in-picture fixture loads it.
                val sheetFixture = sheetFixture(bundle)
                val holder = sheetFixture.builderClass
                val builder = sheetFixture.builder
                // 585 builds the Clear mode row in a method of its own, which the builder calls.
                val reached = reachedBy(builder) + reachedBy(sheetFixture.row) + sheetFixture.row.definingClass
                FixtureDex.classes(bundle, reached).forEach { (type, classDef) -> classes.putIfAbsent(type, classDef) }
                val rowTypes = reached.mapNotNull(classes::get).flatMap { classDef ->
                    classDef.methods.filter { it.name == "<init>" }.flatMap { it.parameterTypes.map(CharSequence::toString) }
                }.toSet()
                FixtureDex.classes(bundle, rowTypes).forEach { (type, classDef) -> classes.putIfAbsent(type, classDef) }
                FixtureDex.classesHolding(bundle, OWN_DOWNLOAD_ROW).forEach { classes.putIfAbsent(it.type, it) }
                classes[holder.type] = holder

                // The caption component and the lambdas its render builds.
                val holders = FixtureDex.classesHolding(bundle, DESCRIPTION_COMPONENT)
                // 588's Android 9 build asks a string table for the name, from a render in another class.
                val tables = holders.filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, DESCRIPTION_COMPONENT) } }
                    .associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val asking = if (tables.isEmpty()) emptySet() else FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in tables } }) {
                    !isStringTable(it) && !holdsString(it, DESCRIPTION_COMPONENT) && namesString(it, DESCRIPTION_COMPONENT, resolve)
                }.map { it.definingClass }.toSet()
                val components = holders + FixtureDex.classes(bundle, asking).values
                components.forEach { classes.putIfAbsent(it.type, it) }
                val built = components.flatMap { it.methods }.filter { it.implementation != null }.flatMap { method ->
                    method.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
                        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                }.toSet()
                FixtureDex.classes(bundle, built).forEach { (type, classDef) -> classes.putIfAbsent(type, classDef) }
                classes[CAPTION_HANDLER] = ExtensionDex.classDef(CAPTION_HANDLER)

                val context = PatchContexts.of(classes.values)
                val sheet = context.moreSheet("the fixture")
                val icons = sheet.iconWrap.parameterTypes.single().toString()
                FixtureDex.classes(bundle, setOf(icons)).values.single().let { classes.putIfAbsent(it.type, it) }
                val withIcons = PatchContexts.of(classes.values)
                val caption = withIcons.captionRender("the fixture")
                val render = caption.render
                val lambda = caption.lambda
                assertEquals("$name: the reel is the lambda's", "$lambda->\$shortFormVideoUnit", caption.unit.toString().substringBefore(':'))
                assertEquals("$name: the caption is the lambda's whole text",
                    "$lambda->\$baseDescriptionText:Ljava/lang/CharSequence;", caption.caption.toString())

                withIcons.rememberCaptions("the fixture")
                val patchedRender = withIcons.mutableClassDefBy(render.definingClass).methods
                    .single { it.name == render.name && it.parameterTypes == render.parameterTypes }
                val instructions = patchedRender.implementation!!.instructions.toList()
                val builds = instructions.indices.filter { i ->
                    val reference = (instructions[i] as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.definingClass == lambda && reference.name == "<init>"
                }
                assertTrue("$name: the render builds the lambda", builds.isNotEmpty())
                for (i in builds) {
                    val built = instructions[i]
                    val next = instructions[i + 1] as RegisterRangeInstruction
                    assertEquals("$name: right after the lambda is built, the helper gets it",
                        "$lambda->$CAPTION_REMEMBER_HELPER($lambda)V", ((next as ReferenceInstruction).reference as MethodReference).toString())
                    val lambdaRegister = if (built is RegisterRangeInstruction) built.startRegister else (built as FiveRegisterInstruction).registerC
                    assertEquals("$name: the helper gets the very lambda", "$lambdaRegister 1", "${next.startRegister} ${next.registerCount}")
                }
                val rememberer = withIcons.mutableClassDefBy(lambda).methods.single { it.name == CAPTION_REMEMBER_HELPER }
                assertEquals("$name: the helper reads the reel and the caption off the lambda",
                    listOf(caption.unit.toString(), caption.caption.toString()), rememberer.fields())
                assertEquals("$name: and hands them to remember",
                    listOf("$CAPTION_HANDLER->remember(Ljava/lang/Object;Ljava/lang/CharSequence;)V"), rememberer.calls())

                val sheetAgain = withIcons.moreSheet("the fixture")
                withIcons.addCaptionRow("the fixture", sheetAgain)
                val patchedClass = withIcons.mutableClassDefBy(holder.type)
                val body = patchedClass.methods.single { it.name == builder.name && it.parameterTypes.size == builder.parameterTypes.size }
                    .implementation!!.instructions.toList()
                assertEquals("$name: four instructions added", builder.implementation!!.instructions.count() + 4, body.size)
                val call = body[3] as RegisterRangeInstruction
                val helperParameters = listOf(withIcons.sheetContext(sheetAgain).type, "Ljava/lang/String;", list)
                assertEquals("$name: the helper gets the context, the video id and the list from v0 to v2",
                    "${builder.definingClass}->$CAPTION_ROW_HELPER(${helperParameters.joinToString("")})V 0 3",
                    "${(call as ReferenceInstruction).reference} ${call.startRegister} ${call.registerCount}")

                val helper = patchedClass.methods.single { it.name == CAPTION_ROW_HELPER }
                val strings = helper.implementation!!.instructions.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }
                assertEquals("$name: the row's id", listOf(CAPTION_ROW_ID), strings)
                val icon = helper.fields().last()
                assertTrue("$name: the row draws Facebook's copy icon, not Clear mode's: $icon",
                    icon.startsWith("$icons->") && icon != sheetAgain.clearModeIcon.toString())
                val calls = helper.calls()
                assertTrue("$name: the helper asks the handler: $calls",
                    "$CAPTION_HANDLER->forVideo(Landroid/content/Context;Ljava/lang/String;)$CAPTION_HANDLER" in calls)
                assertTrue("$name: the helper builds Facebook's row: $calls", calls.any { it.startsWith("${sheetAgain.row.definingClass}-><init>(") })
                assertTrue("$name: the helper adds it to the list: $calls", "$list->add(Ljava/lang/Object;)Z" in calls)

                val handler = withIcons.mutableClassDefBy(CAPTION_HANDLER)
                assertEquals("$name: the handler takes the row's click", listOf(sheetAgain.click), handler.interfaces.toList())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
