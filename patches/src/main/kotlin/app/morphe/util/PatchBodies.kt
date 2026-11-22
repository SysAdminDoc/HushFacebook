/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
@file:Suppress("DEPRECATION")

package app.morphe.util

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodeContext
import app.morphe.patcher.patch.resourceContext

// Patcher 2.5.0 deprecates bytecodePatch and resourcePatch for the one patch builder, whose execute
// and finalize blocks take no receiver and reach the APK through the patcher's global functions.
// The patch bodies here, and the hundreds of helpers they call, are written against a context
// receiver, so these run such a body on the context of the APK being patched, and a body moved
// over unchanged does what it did under the old builder.
//
// The context comes from bytecodeContext and resourceContext, which the patcher keeps, deprecated,
// for code that still needs one (its replacements, currentBytecode and currentResources, are
// internal to it). This file is the one place left on them: once the helpers take no receiver,
// these go and the bodies move to execute { } as they are.
//
// A body runs on the patcher's current context, never on the one Patch.execute is handed: the
// patcher hands it NoPatchContext, and the builder's execute that takes a context is hidden from
// Kotlin. A test that makes several contexts makes each one right before it patches it.
//
// They aren't inline. Kotlin copies an inlined lambda into a class that keeps this file as its
// source, so a body that throws would print PatchBodies.kt and a line past the end of it in every
// stack trace. As plain functions, the body stays a lambda in its own file at its own line.
//
// The cost is the name of a patch with no name: the patcher calls it by the class of the block it
// was handed, and that class is here. The loader names a patch held in a field after the field, so
// that only reaches a patch built in a function (the app label, the two icons, AMOLED's resources,
// the shared extension). Those write `execute { onResources { } }` themselves: onBytecode and
// onResources are inline with no lambda of their own, so the body lands in the caller's block and
// the block is theirs. PatchDiagnosticsTest holds both sides.

/** Runs [block] on the bytecode of the APK being patched, as `bytecodePatch { execute { } }` did. */
internal fun PatchDefinition.executeBytecode(block: BytecodePatchContext.() -> Unit) =
    execute { bytecodeContext.block() }

/** Runs [block] on the bytecode once every patch has run, as `bytecodePatch { finalize { } }` did. */
internal fun PatchDefinition.finalizeBytecode(block: BytecodePatchContext.() -> Unit) =
    finalize { bytecodeContext.block() }

/** Runs [block] on the resources of the APK being patched, as `resourcePatch { execute { } }` did. */
internal fun PatchDefinition.executeResources(block: ResourcePatchContext.() -> Unit) =
    execute { resourceContext.block() }

/** Runs [block] on the resources once every patch has run, as `resourcePatch { finalize { } }` did. */
internal fun PatchDefinition.finalizeResources(block: ResourcePatchContext.() -> Unit) =
    finalize { resourceContext.block() }

/** Runs [block] on the bytecode of the APK being patched, inside an execute or finalize the caller writes. */
internal inline fun onBytecode(block: BytecodePatchContext.() -> Unit) = bytecodeContext.block()

/** Runs [block] on the resources of the APK being patched, inside an execute or finalize the caller writes. */
internal inline fun onResources(block: ResourcePatchContext.() -> Unit) = resourceContext.block()
