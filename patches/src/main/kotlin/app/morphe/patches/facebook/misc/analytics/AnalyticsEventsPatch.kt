/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.analytics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** Hold back analytics uploads, in this package too, has the plain PATCH name. */
private const val EVENTS_PATCH = "Analytics events"

private const val STRING = "Ljava/lang/String;"

/** The extension's one question about each analytics event: true keeps it out. */
internal const val ANALYTICS_EVENTS = "$EXTENSION_PACKAGE/misc/AnalyticsEvents;"
internal const val KEEP_EVENT_OUT = "$ANALYTICS_EVENTS->keepOut(Ljava/lang/String;)Z"

/** What an analytics event factory reads off the event's details and hands the event it makes. */
internal const val PRIVACY_CONTEXT = "Lcom/facebook/logginginfra/falco/PrivacyContext;"

/**
 * The extension is asked about every analytics event, by name, first thing where it's made.
 *
 * Every analytics event is made by one private factory of the logger (584 `LX/3tF;->A00(LX/1S4;
 * String)LX/3UE;`), found by its shape: it reads the event's [PRIVACY_CONTEXT] and answers its own
 * class's "not logged" event when the event isn't sampled. First thing there, the extension is
 * handed the event's name, and a true sends that "not logged" event back, which every caller
 * already handles.
 *
 * Nameless, and carried by each patch that keeps events out there: Don't save search history
 * (profile visits, #221) and Don't send reel watch history (video playback, #408). The patcher
 * runs a shared dependency once, so the factory gets one question whichever of them is selected,
 * and the extension asks each feature only when its patch is in the build. A second question put
 * in front of the first left Facebook spinning on its start screen (#408, 585).
 */
internal val analyticsEventsPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        // Everything is found before anything changes.
        val factories = analyticsEventFactories()
        if (factories.isEmpty()) {
            patchLog.warning(
                "$EVENTS_PATCH: no analytics event factory reads a $PRIVACY_CONTEXT and answers a \"not logged\" event of its own " +
                    "class, so a page opened from a search can still show in your search history as a visit, and videos " +
                    "you play can still show in Videos you've watched.",
            )
        }
        for ((factory, notLogged) in factories) {
            mutableClassDefBy(factory.definingClass).methods.single { it.descriptor() == factory.descriptor() }
                .keepEventsOut(notLogged)
        }
    }
}

/** Every analytics event factory outside the extension, each with its "not logged" event. */
internal fun BytecodePatchContext.analyticsEventFactories(): List<Pair<Method, FieldReference>> {
    val factories = mutableListOf<Pair<Method, FieldReference>>()
    // An event factory reads the privacy context.
    classDefForEachReferencing(types = listOf(PRIVACY_CONTEXT)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        for (candidate in classDef.methods) notLoggedEvent(candidate)?.let { factories += candidate to it }
    }
    return factories
}

/**
 * The "not logged" event [method] answers if it's an analytics event factory, else null. A factory
 * is a private instance method taking the event's details and its name, a String, and answering an
 * event. It reads a [PRIVACY_CONTEXT] field and answers one static of its own class, loaded right
 * before the return, when the event isn't sampled. One such answer, or it isn't one.
 */
internal fun notLoggedEvent(method: Method): FieldReference? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PRIVATE.isSet(method.accessFlags)) return null
    val types = method.parameterTypes.map(CharSequence::toString)
    if (types.size != 2 || !types[0].startsWith("L") || types[1] != STRING || !method.returnType.startsWith("L")) return null
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val readsPrivacy = instructions.any {
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.type == PRIVACY_CONTEXT
    }
    if (!readsPrivacy) return null
    val answers = instructions.indices.mapNotNull { index ->
        val load = instructions[index]
        val answer = instructions.getOrNull(index + 1)
        if (load.opcode != Opcode.SGET_OBJECT || answer?.opcode != Opcode.RETURN_OBJECT) return@mapNotNull null
        if ((load as OneRegisterInstruction).registerA != (answer as OneRegisterInstruction).registerA) return@mapNotNull null
        ((load as ReferenceInstruction).reference as FieldReference).takeIf { it.definingClass == method.definingClass }
    }
    return answers.singleOrNull()
}

/**
 * First thing in an analytics event factory: hand the extension the event's name, the last
 * register, and on a true answer [notLogged], the factory's own "not logged" event. v0 is free
 * there, below the three registers `this` and the two parameters take.
 */
internal fun MutableMethod.keepEventsOut(notLogged: FieldReference) {
    val registers = implementation?.registerCount ?: throw PatchException("$EVENTS_PATCH: ${descriptor()} has no code")
    if (registers < 4) throw PatchException("$EVENTS_PATCH: ${descriptor()} has no register free below its parameters")
    val name = registers - 1
    addInstructions(
        0,
        """
            invoke-static/range { v$name .. v$name }, $KEEP_EVENT_OUT
            move-result v0
            if-eqz v0, :facebook
            sget-object v0, $notLogged
            return-object v0
            :facebook
            nop
        """,
    )
}
