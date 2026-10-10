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
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
 * group's finished row list goes through the extension, which adds the row once, a tap on the row opens
 * the settings, and the row's loggers leave it out. See SettingsRowAnchors.kt for the shapes.
 */
@Suppress("unused")
val hushfacebookInTheMenuPatch = bytecodePatch(
    name = "Hushfacebook in the Menu",
    description = "Adds a Hushfacebook settings row to Facebook's Menu, at the end of Settings and privacy, so " +
        "the settings are easy to find. A Saved row joins it while the Saved shortcut switch is on, and a Watch " +
        "history row while the Watch history shortcut switch is. Works as soon as you patch it in, with no switch.",
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
        // Found on the mutable method, since another Menu patch may have gone into it already.
        val section = mutableClassDefBy(native.definingClass).findMutableMethodOf(native)
        val stores = settingsListStores(section)
        val store = stores.singleOrNull() ?: throw PatchException(
            "$ROW_PATCH: expected ${native.definingClass}->${native.name} to store one finished row list, found ${stores.size}",
        )

        val taps = methodsNaming(ROW_TAP_TRACE, ::isRowTapShape)
        val tap = taps.singleOrNull()
            ?: throw PatchException("$ROW_PATCH: expected one Menu row tap handler loading \"$ROW_TAP_TRACE\", found ${taps.size}")
        val item = rowItemType(tap)
        val itemClass = classDefByOrNull(item) ?: throw PatchException("$ROW_PATCH: the row item $item isn't in this build")
        val constructor = fullConstructor(itemClass)
            ?: throw PatchException("$ROW_PATCH: $item has no constructor over a title, two addresses, icons and an id")
        val fields = constructorFields(constructor)
            ?: throw PatchException("$ROW_PATCH: $item's full constructor doesn't store each argument in a field")
        val id = idField(itemClass) ?: throw PatchException("$ROW_PATCH: $item has no single id field")
        if (fields.last().name != id.name) {
            throw PatchException("$ROW_PATCH: $item's constructor stores its id in ${fields.last().name}, not ${id.name}")
        }

        if (!constructs(section, item)) {
            throw PatchException("$ROW_PATCH: ${native.definingClass}->${native.name} builds no $item, so its list isn't rows")
        }
        section.passListThroughRow(store)

        addRowHelpers(item, constructor, fields, id)

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
 * The finished Settings and privacy list, picked up by the `move-result-object` at [result], goes
 * through the extension, which never answers null, and back into an ImmutableList before the
 * section stores it and draws it. `copyOf` hands an ImmutableList back as it is, so a list the
 * extension left alone is Facebook's own. The stored list is what later draws read back, so the
 * row is added once, when the list is built.
 */
internal fun MutableMethod.passListThroughRow(result: Int) {
    val instruction = implementation!!.instructions.elementAtOrNull(result)
    if (instruction?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("$ROW_PATCH: $definingClass->$name has no list picked up at $result")
    }
    val register = (instruction as OneRegisterInstruction).registerA
    addInstructions(
        result + 1,
        """
            invoke-static/range { v$register .. v$register }, $WITH_ROW
            move-result-object v$register
            invoke-static/range { v$register .. v$register }, $COPY_OF
            move-result-object v$register
        """.trimIndent(),
    )
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
