/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.resourcePatch
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Morphe executes the patches it's given sorted by name, each after its dependencies, and runs
 * their finalize blocks in the reverse order. Clone app renames the package in its finalize block,
 * so the clone support only finalizes after it, and sees the renamed manifest, when it executed
 * before "Clone app": when a patch whose name sorts before that one depends on it. Every patch
 * does, and the default selection holds some whose names sort before it.
 */
class ClonedPackageOrderTest {
    /** Every patch this bundle declares at the top level, the way Morphe's loader finds them. */
    private fun bundlePatches(): List<Patch> {
        val root = File(Class.forName("app.morphe.patches.facebook.coexist.ClonedPackageKt")
            .protectionDomain.codeSource.location.toURI())
        return root.walkTopDown().filter { it.name.endsWith("Kt.class") }.flatMap { file ->
            val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            Class.forName(name).methods.filter {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && Patch::class.java.isAssignableFrom(it.returnType)
            }.map { it.invoke(null) as Patch }
        }.toList()
    }

    private fun Patch.reaches(target: Patch): Boolean = this === target || dependencies.any { it.reaches(target) }

    /**
     * The order Morphe's own [selection] loop would actually execute patches in: each visited sorted
     * by name, a patch's dependencies executed (and so recorded) before the patch itself, and a
     * patch already recorded skipped. This mirrors Patcher.kt's `invoke()` closely enough to say
     * which of two patches executed first, which is what decides which finalizes last: finalize runs
     * in the exact reverse of this order.
     */
    private fun executionOrder(selection: List<Patch>): List<Patch> {
        val executed = LinkedHashSet<Patch>()
        fun visit(patch: Patch) {
            if (patch in executed) return
            patch.dependencies.forEach(::visit)
            executed.add(patch)
        }
        selection.sortedBy { it.name }.forEach(::visit)
        return executed.toList()
    }

    /** Whatever the reader picks, a clone's code reaches its own providers. */
    @Test
    fun everyPatchBringsTheCloneSupport() {
        val named = bundlePatches().filter { it.name != null }.distinct()
        assertTrue("found only ${named.size} named patches", named.size > 30)
        assertEquals("patches without the clone support", emptyList<String>(),
            named.filterNot { it.reaches(clonedPackagePatch) }.map { it.name })
    }

    @Test
    fun theDefaultSelectionHasPatchesMorpheRunsBeforeCloneApp() {
        val early = bundlePatches().filter { it.name != null && it.name!! < "Clone app" }.distinct()
        assertTrue("no default patch sorts before Clone app: ${early.map { it.name }}", early.any { it.default })
    }

    /**
     * The gap ClonedPackage.kt's finalize can't close: a selection holding only patches whose names
     * sort after "Clone app" (its own examples, Hide sponsored posts and Use the system font) never
     * pulls the manifest fix in ahead of it, because a dependency's name doesn't affect the outer
     * sort, only a selected patch's own does, and there's no selected patch here sorting first. So
     * "Clone app" executes (and later finalizes last) before the fix has ever run. clonedPackageManifestPatch
     * is private to that file, but it's always [clonedPackagePatch]'s dependency, and so always
     * executes before it, so the same comparison against clonedPackagePatch proves it either way.
     */
    @Test
    fun aSelectionOfOnlyLaterNamedPatchesRunsCloneAppFirst() {
        val cloneApp = resourcePatch(name = "Clone app") { }
        val bundle = bundlePatches()
        val hideSponsoredPosts = bundle.single { it.name == "Hide sponsored posts" }
        val useTheSystemFont = bundle.single { it.name == "Use the system font" }

        val order = executionOrder(listOf(cloneApp, hideSponsoredPosts, useTheSystemFont))

        assertTrue(
            "expected \"Clone app\" to execute, and so finalize last, before the manifest fix: " +
                order.map { it.name },
            order.indexOf(cloneApp) < order.indexOf(clonedPackagePatch),
        )
    }
}
