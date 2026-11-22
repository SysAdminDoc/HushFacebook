/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import app.morphe.patcher.patch.patch
import app.morphe.util.executeBytecode
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failing patch shows. A stack trace names each frame's file and line, and the patcher calls
 * a patch with no name by the class of its execute or finalize block, so where a body's block is
 * compiled decides both (util/PatchBodies.kt).
 */
class PatchDiagnosticsTest {
    private class Marker : RuntimeException()

    @Test
    fun `a patch body that throws points at its own file and line`() {
        val line = Throwable().stackTrace[0].lineNumber + 1
        val probe = patch { executeBytecode { throw Marker() } }
        val thrown = runCatching { probe.execute(PatchContexts.of(emptyList())) }.exceptionOrNull()
        val marker = generateSequence(thrown) { it.cause }.filterIsInstance<Marker>().firstOrNull()
        assertTrue("the body never threw (got $thrown)", marker != null)
        val top = marker!!.stackTrace.first()
        assertEquals("PatchDiagnosticsTest.kt:$line", "${top.fileName}:${top.lineNumber}")
    }

    /**
     * The loader names a patch with no name after the field it's held in, but only in a class that
     * hands out a patch of its own, and never one a function builds. Loaded as the CLI and Manager
     * load the bundle, every patch it reaches is called by its own file.
     */
    @Test
    fun `every patch the loader reaches is named after its own file`() {
        val reached = mutableSetOf<Patch>()
        fun walk(patch: Patch) {
            if (reached.add(patch)) patch.dependencies.forEach(::walk)
        }
        val jar = classesJar()
        try {
            loadPatchesFromJar(setOf(jar)).forEach(::walk)
        } finally {
            jar.delete()
        }
        val unnamed = reached.filter { it.name == null }.map { it.toString() }
        // The patches built in a function: what the check below is for, so it can't pass on none.
        for (own in listOf("AppLabel", "LauncherIconStyle", "NotificationIconStyle", "AmoledThemePatch", "SharedExtensionPatch")) {
            assertTrue("no unnamed patch reads $own: ${unnamed.sorted()}", own in unnamed)
        }
        assertEquals(emptyList<String>(), unnamed.filter { it == "PatchBodies" || it.startsWith("PatchBodies.") })
    }

    /** The compiled patches as one jar, the shape the loader reads. */
    private fun classesJar(): File {
        val classes = File(Class.forName("app.morphe.util.PatchBodiesKt").protectionDomain.codeSource.location.toURI())
        assertTrue("the compiled patches aren't a folder: $classes", classes.isDirectory)
        val jar = Files.createTempFile("hushfacebook-patches", ".jar").toFile()
        JarOutputStream(jar.outputStream()).use { out ->
            classes.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return jar
    }
}
