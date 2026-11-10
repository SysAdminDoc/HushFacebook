/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val ROW = "$EXTENSION_PACKAGE/menu/MenuSettingsRow;"
internal const val WITH_ROW = "$ROW->withRow(Ljava/util/List;)Ljava/util/List;"
internal const val ON_TAP = "$ROW->onTap(${VIEW}J)Z"
internal const val IS_ROW = "$ROW->isRow(J)Z"
private const val COPY_OF = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

/**
 * Adds a Hushfacebook settings row at the end of the Menu's Settings and privacy group. The
 * group's row list goes through the extension, which adds the row once, a tap on the row opens
 * the settings, and the row's loggers leave it out. See SettingsRowAnchors.kt for the shapes.
 */
@Suppress("unused")
val hushfacebookInTheMenuPatch = bytecodePatch(
    name = "Hushfacebook in the Menu",
    description = "Adds a Hushfacebook settings row to Facebook's Menu, at the end of Settings and privacy, so " +
        "the settings are easy to find. A Saved row joins it while the Saved shortcut switch is on, a Watch " +
        "history row while the Watch history shortcut switch is, and a Free stuff row, Marketplace's free listings, " +
        "while the Free stuff shortcut switch under Marketplace is. On by default. Turn it off with Settings row in Menu in Hushfacebook settings > Menu, unless it's the last " +
        "way into the settings still on.",
    default = true,
) {
    category("Settings")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val natives = classDefByStrings(NATIVE_SECTION_KEY, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
            .flatMap { classDef -> classDef.methods.filter(::isNativeSectionChildren) }
        val native = natives.singleOrNull()
            ?: throw PatchException("$ROW_PATCH: expected one native Menu group section, found ${natives.size}")
        // 586 asks a static method beside the section for its group's list, which makes the builds.
        val site = rowListSite(native) { classDefByOrNull(it) }
        val builds = rowListBuilds(site)
        // 584 inlined the builder into the section, which keeps the list itself, and 586's Android 9
        // build into the static method, which keeps it the same way.
        val kept = if (builds.isEmpty()) keptRowLists(site)?.settings else null
        if (kept == null && builds.size != 1) {
            throw PatchException(
                "$ROW_PATCH: expected ${site.definingClass}->${site.name} to build one row list or keep one itself, " +
                    "found ${builds.size} builds",
            )
        }

        val taps = methodsNaming(ROW_TAP_TRACE, ::isRowTapShape)
        // 583's 32-bit build inlines the handler into a lambda, so with no handler the tap is found there.
        val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
        val isRowItem: (String) -> Boolean = { type ->
            classDefByOrNull(type)?.let { fullConstructor(it) != null && idField(it) != null } == true
        }
        val inlined = if (taps.isNotEmpty()) emptyList() else methodsNaming(ROW_TAP_TRACE, ::isLambdaInvoke)
            .mapNotNull { inlinedRowTap(it, resolve, isRowItem) }
        val tap = taps.singleOrNull()
        if (tap == null && inlined.size != 1) {
            throw PatchException(
                "$ROW_PATCH: expected one Menu row tap handler naming \"$ROW_TAP_TRACE\" or one lambda with its body inlined, " +
                    "found ${taps.size} and ${inlined.size}",
            )
        }
        val item = tap?.let(::rowItemType) ?: inlined.single().itemType
        val itemClass = classDefByOrNull(item) ?: throw PatchException("$ROW_PATCH: the row item $item isn't in this build")
        val constructor = fullConstructor(itemClass)
            ?: throw PatchException("$ROW_PATCH: $item has no constructor over a title, two addresses, icons and an id")
        val fields = constructorFields(constructor)
            ?: throw PatchException("$ROW_PATCH: $item's full constructor doesn't store each argument in a field")
        val id = idField(itemClass) ?: throw PatchException("$ROW_PATCH: $item has no single id field")
        if (fields.last().name != id.name) {
            throw PatchException("$ROW_PATCH: $item's constructor stores its id in ${fields.last().name}, not ${id.name}")
        }

        if (kept != null) {
            if (!constructs(site, item)) {
                throw PatchException("$ROW_PATCH: ${site.definingClass}->${site.name} builds no $item, so its list isn't rows")
            }
            mutableClassDefBy(site.definingClass).findMutableMethodOf(site).passKeptListThroughRow(kept)
        } else {
            val build = builds.single()
            val builder = mutableClassDefBy(build.definingClass).methods.single {
                it.name == build.name && it.parameterTypes.map(Any::toString) == build.parameterTypes.map(Any::toString)
            }
            if (!constructs(builder, item)) {
                throw PatchException("$ROW_PATCH: ${build.definingClass}->${build.name} builds no $item, so its list isn't rows")
            }
            builder.passListThroughRow()
        }

        addRowHelpers(item, constructor, fields, id)

        if (tap == null) {
            // Found again on the mutable copy, in case another patch has edited the lambda since.
            val lambda = mutableClassDefBy(inlined.single().method.definingClass).findMutableMethodOf(inlined.single().method)
            val found = inlinedRowTap(lambda, resolve, isRowItem)
                ?: throw PatchException("$ROW_PATCH: ${lambda.definingClass}->invoke changed before its tap could be hooked")
            lambda.skipInlinedRow(found)
            enableStatus("menuSettingsRow")
            return@execute
        }
        val owner = mutableClassDefBy(tap.definingClass)
        // 582's 32-bit build has dropped both loggers, so there may be none to hook.
        val loggers = rowLoggers(owner, tap, item)
        val tapMethod = owner.findMutableMethodOf(tap)
        tapMethod.skipRow(tapMethod.parameterTypes.indexOfFirst { it.toString() == item }, id, tapToo = true)
        loggers.forEach { logger ->
            val mutable = owner.findMutableMethodOf(logger)
            mutable.skipRow(mutable.parameterTypes.indexOfFirst { it.toString() == item }, id, tapToo = false)
        }
        enableStatus("menuSettingsRow")
    }
}

/** Whether [method] constructs a [type]. */
private fun constructs(method: Method, type: String): Boolean =
    method.implementation?.instructions?.toList().orEmpty().any { instruction ->
        instruction.opcode == Opcode.NEW_INSTANCE &&
            ((instruction as ReferenceInstruction).reference as TypeReference).type == type
    }

/**
 * Each list the builder hands back goes through the extension, which never answers null, and back
 * into an ImmutableList, which is what the builder promises. `copyOf` hands an ImmutableList back
 * as it is, so a list the extension left alone is the builder's own. 583's builder has two returns,
 * the list it just built and the one it kept from the last call, and the extension adds the row
 * once to either.
 */
internal fun MutableMethod.passListThroughRow() {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index }
    if (returns.isEmpty()) throw PatchException("$ROW_PATCH: $definingClass->$name returns no list")
    returns.asReversed().forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $WITH_ROW
                move-result-object v$register
                invoke-static/range { v$register .. v$register }, $COPY_OF
                move-result-object v$register
            """.trimIndent(),
        )
    }
}

/**
 * 584's native section keeps the Settings list in [field] itself, so its copy goes through the
 * extension where 583's builder returns did: right after the section reads the kept list and right
 * after it writes a list it just built ([keptListReads]), a list that's there goes through
 * [WITH_ROW] and back into an ImmutableList. The kept list stays Facebook's own, as on 583.
 */
internal fun MutableMethod.passKeptListThroughRow(field: FieldReference) {
    val reads = keptListReads(this, field) ?: throw PatchException(
        "$ROW_PATCH: $definingClass->$name has another way into the code after it reads or writes ${reference(field)}",
    )
    if (reads.size != 2) {
        throw PatchException("$ROW_PATCH: expected $definingClass->$name to read ${reference(field)} once and write it once, found ${reads.size}")
    }
    reads.asReversed().forEach { passListThrough(it.index, it.register, WITH_ROW) }
}

/** Adds the row factory and the id reader to the row item class. */
private fun BytecodePatchContext.addRowHelpers(item: String, constructor: Method, fields: List<FieldReference>, id: FieldReference) {
    val classDef = mutableClassDefBy(item)
    if (classDef.methods.any { it.name == ROW_FACTORY || it.name == ROW_ID_READER }) {
        throw PatchException("$ROW_PATCH: $item already has a method named $ROW_FACTORY or $ROW_ID_READER")
    }
    val locals = rowFactoryLocals(constructor)
    // The template is read through 4-bit operands, so it has to sit at v15 or below.
    if (locals > 15) throw PatchException("$ROW_PATCH: $item's constructor needs $locals registers, more than the factory can read through")
    classDef.methods.add(
        staticMethod(item, ROW_FACTORY, listOf("Ljava/lang/Object;", "Ljava/lang/CharSequence;", "J"), "Ljava/lang/Object;",
            locals + 4, rowFactorySmali(item, constructor, fields)),
    )
    classDef.methods.add(
        staticMethod(item, ROW_ID_READER, listOf("Ljava/lang/Object;"), "J", 3,
            "check-cast p0, $item\niget-wide v0, p0, ${reference(id)}\nreturn-wide v0"),
    )
}

private fun staticMethod(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
    ImmutableMethod(
        owner,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(registers),
    ).toMutable().apply { addInstructions(0, smali) }

/**
 * First thing in a method taking the row item in declared parameter [itemIndex]: the item's id goes
 * to the extension, and a yes returns before Facebook does anything with it. The tap handler asks
 * [ON_TAP], which also opens the settings; the loggers ask [IS_ROW]. The item is copied into v0
 * through the 16-bit form and its id read into v1 and v2, which hold nothing yet.
 */
internal fun MutableMethod.skipRow(itemIndex: Int, id: FieldReference, tapToo: Boolean) {
    if (itemIndex < 0) throw PatchException("$ROW_PATCH: $definingClass->$name doesn't take the row item")
    if (returnType != "V") throw PatchException("$ROW_PATCH: $definingClass->$name returns a value")
    requireLocals(ROW_PATCH, 3)
    val ask = if (tapToo) {
        """
            move-object/from16 v0, ${parameterRegister(0)}
            invoke-static { v0, v1, v2 }, $ON_TAP
        """.trimIndent()
    } else {
        "invoke-static { v1, v2 }, $IS_ROW"
    }
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameterRegister(itemIndex)}
            iget-wide v1, v0, ${reference(id)}
        """.trimIndent() + "\n" + ask + "\n" + """
            move-result v0
            if-eqz v0, :facebook
            return-void
        """.trimIndent(),
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * At the inlined tap's [InlinedRowTap.hook]: the view and the row's id (read through [ROW_ID_READER],
 * since the item's register can sit past what `iget-wide` reaches) go to [ON_TAP], and a yes goes
 * to the lambda's own `return Unit`, before Facebook does anything with the row. The three
 * registers it borrows are the first run of locals nothing reads from there on.
 */
internal fun MutableMethod.skipInlinedRow(tap: InlinedRowTap) {
    val live = RegisterLiveness.of(this).liveInto(tap.hook)
    val borrowed = (0..minOf(localRegisterCount(), 256) - 3).firstOrNull { first ->
        (first..first + 2).none { it in live || it == tap.view || it == tap.item }
    } ?: throw PatchException("$ROW_PATCH: $definingClass->$name has no three free locals at its row tap")
    val code = implementation!!.instructions.toList()
    val units = code.indices.filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.SGET_OBJECT && field != null && field.type == field.definingClass &&
            code.getOrNull(at + 1)?.opcode == Opcode.RETURN_OBJECT &&
            (code[at + 1] as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA
    }
    val unit = units.singleOrNull() ?: throw PatchException("$ROW_PATCH: $definingClass->$name has no single return of its Unit")
    addInstructionsWithLabels(
        tap.hook,
        """
            move-object/from16 v$borrowed, v${tap.view}
            invoke-static/range { v${tap.item} .. v${tap.item} }, ${tap.itemType}->$ROW_ID_READER(Ljava/lang/Object;)J
            move-result-wide v${borrowed + 1}
            invoke-static/range { v$borrowed .. v${borrowed + 2} }, $ON_TAP
            move-result v$borrowed
            if-nez v$borrowed, :unit
        """.trimIndent(),
        ExternalLabel("unit", getInstruction(unit)),
    )
}
