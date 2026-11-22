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

// Patcher 2.5.0 deprecates bytecodePatch and resourcePatch for patch(), whose execute and
// finalize blocks take no receiver and reach the APK through the patcher's global functions.
// The patch bodies here, and the hundreds of helpers they call, are written against a context
// receiver, so these run such a body on the context of the APK being patched, and a body moved
// over unchanged does what it did under the old builder.
//
// The context comes from bytecodeContext and resourceContext, which the patcher keeps, deprecated,
// for code that still needs one (its replacements, currentBytecode and currentResources, are
// internal to it). This file is the one place left on them: once the helpers take no receiver,
// these go and the bodies move to execute { } as they are.

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
