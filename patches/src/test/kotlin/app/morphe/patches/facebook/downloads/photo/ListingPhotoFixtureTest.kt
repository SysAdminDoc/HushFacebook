/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long press to save Marketplace photos on every declared build: React Native's image manager on
 * Fresco Vito (a kept name), which draws every photo on a Marketplace listing, has a typed setSource
 * and setSrc taking its own view and the array of sources, and that view is an ImageView. Each
 * setter hands the sources to one method on the view, which reads them the way ListingPhoto does:
 * each source's `uri`, and its `width` and `height`, through the bridge's kept calls, and the bridge's
 * map has the hasKey ListingPhoto asks first. After the patch, the first thing each setter does is
 * hand the view and the sources to the extension, one range call over its own parameters, with the
 * rest of the body kept. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ListingPhotoFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
    private val readableArray = "Lcom/facebook/react/bridge/ReadableArray;"
    private val readableMap = "Lcom/facebook/react/bridge/ReadableMap;"

    private fun Method.shape() = parameterTypes.map(CharSequence::toString)

    private fun isTypedSetter(method: Method) = method.name in setOf("setSource", "setSrc") && method.returnType == "V" &&
        method.shape().size == 2 && method.shape()[1] == readableArray &&
        method.shape()[0] != "Landroid/view/View;" && method.implementation != null

    private fun patched(context: BytecodePatchContext, method: Method): List<Instruction> =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType && it.shape() == method.shape()
        }.implementation!!.instructions.toList()

    /** The register of the method's first parameter after `this`. */
    private fun firstParameter(method: Method) = method.implementation!!.registerCount - method.shape().size

    @Test
    fun `the image manager's setSource and setSrc hand their sources to the extension, on each declared build`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val stock = FixtureDex.classes(bundle, setOf(VITO_IMAGE_MANAGER, readableMap))
                val manager = stock[VITO_IMAGE_MANAGER] ?: throw AssertionError("$name: no $VITO_IMAGE_MANAGER")
                val setters = manager.methods.filter(::isTypedSetter)
                assertEquals("$name: typed setters", setOf("setSource", "setSrc"), setters.map { it.name }.toSet())
                assertEquals("$name: one of each", 2, setters.size)
                val map = stock[readableMap] ?: throw AssertionError("$name: no $readableMap")
                assertTrue("$name: ReadableMap has hasKey(String)", map.methods.any {
                    it.name == "hasKey" && it.returnType == "Z" && it.shape() == listOf("Ljava/lang/String;")
                })

                val viewType = setters.map { it.shape()[0] }.toSet().single()
                // Each setter hands the sources to one method on its view, which reads them.
                val loader = setters.map { setter ->
                    setter.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                        .single { it.definingClass == viewType && it.parameterTypes.map(CharSequence::toString) == listOf(readableArray) }
                }.toSet().single()
                val view = FixtureDex.classes(bundle, setOf(viewType))[viewType] ?: throw AssertionError("$name: no $viewType")
                assertEquals("$name: the photo's view", "Landroid/widget/ImageView;", view.superclass)
                val load = view.methods.single { it.name == loader.name && it.shape() == listOf(readableArray) }
                val reads = load.implementation!!.instructions.toList()
                val keys = reads.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
                assertTrue("$name: $viewType->${load.name} reads uri, width and height, read $keys", keys.containsAll(listOf("uri", "width", "height")))
                val calls = reads.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.map { it.toString() }.toSet()
                for (call in listOf(
                    "$readableArray->size()I",
                    "$readableArray->getMap(I)$readableMap",
                    "$readableMap->getString(Ljava/lang/String;)Ljava/lang/String;",
                    "$readableMap->getDouble(Ljava/lang/String;)D",
                )) {
                    assertTrue("$name: $viewType->${load.name} calls $call", call in calls)
                }

                val before = setters.associateWith { it.implementation!!.instructions.toList() }
                val context = PatchContexts.of(listOf(manager, view))
                val step = context.listingPhotoSave()
                for (setter in setters) {
                    assertEquals("$name: nothing changes before the step runs", before.getValue(setter).size, patched(context, setter).size)
                }
                step()

                for (setter in setters) {
                    val where = "$name $VITO_IMAGE_MANAGER->${setter.name}"
                    val after = patched(context, setter)
                    val call = after.first()
                    assertEquals("$where: the hook comes first", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    assertEquals("$where: the hook", LISTING_SOURCE, (call as ReferenceInstruction).reference.toString())
                    assertEquals("$where: registers handed over", 2, (call as RegisterRangeInstruction).registerCount)
                    assertEquals("$where: from the view", firstParameter(setter), call.startRegister)
                    // Counted without nops: an insert can move a payload off its alignment, which adds one.
                    assertEquals("$where: the rest of the body is kept",
                        before.getValue(setter).filter { it.opcode != Opcode.NOP }.map { it.opcode },
                        after.drop(1).filter { it.opcode != Opcode.NOP }.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * React Native's touch dispatcher, an obfuscated class built around the root view it serves:
     * only its handleTouchEvent logs [TOUCH_DOWN_TWICE], and it takes the touch first. The roots
     * calling it from onInterceptTouchEvent ignore requestDisallowInterceptTouchEvent, so it hears
     * every touch in the window. After the patch its first instruction hands the touch over, one
     * range call over its own parameter, with the rest of the body kept.
     */
    @Test
    fun `React Native's touch dispatcher hands every touch to the extension, on each declared build`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, TOUCH_DOWN_TWICE)
                val handlers = holders.flatMap { holder ->
                    holder.methods.filter { method ->
                        method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == TOUCH_DOWN_TWICE } == true
                    }
                }
                val handler = handlers.singleOrNull() ?: throw AssertionError("$name: methods logging the line: ${handlers.map { "${it.definingClass}->${it.name}" }}")
                assertEquals("$name: takes the touch first", "Landroid/view/MotionEvent;", handler.shape().firstOrNull())
                assertEquals("$name: returns nothing", "V", handler.returnType)
                val dispatcher = holders.single { it.type == handler.definingClass }
                assertTrue("$name: ${dispatcher.type} is built around its root view", dispatcher.methods.any {
                    it.name == "<init>" && it.shape() == listOf("Landroid/view/ViewGroup;")
                })

                val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass == handler.definingClass && it.name == handler.name } }) { method ->
                    method.definingClass != handler.definingClass && method.implementation?.instructions?.any {
                        val called = (it as? ReferenceInstruction)?.reference as? MethodReference
                        called?.definingClass == handler.definingClass && called.name == handler.name
                    } == true
                }
                assertTrue("$name: no root view hands its touches to ${handler.definingClass}->${handler.name}",
                    callers.any { it.name == "onInterceptTouchEvent" })
                val rootTypes = callers.map { it.definingClass }.toSet()
                val roots = FixtureDex.classes(bundle, rootTypes)
                assertEquals("$name: root views read", rootTypes, roots.keys)
                for ((type, root) in roots) {
                    // React Native's roots never set their own flag, so they keep hearing every touch.
                    val disallow = root.methods.singleOrNull { it.name == "requestDisallowInterceptTouchEvent" && it.shape() == listOf("Z") }
                        ?: throw AssertionError("$name: $type keeps the framework's requestDisallowInterceptTouchEvent")
                    assertTrue("$name: $type stops intercepting when a view asks", disallow.implementation!!.instructions.none {
                        it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE
                    })
                }

                val before = handler.implementation!!.instructions.toList()
                val context = PatchContexts.of(listOf(dispatcher))
                val step = context.listingPhotoTouches()
                assertEquals("$name: nothing changes before the step runs", before.size, patched(context, handler).size)
                step()
                val after = patched(context, handler)
                val call = after.first()
                val where = "$name ${handler.definingClass}->${handler.name}"
                assertEquals("$where: the hook comes first", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals("$where: the hook", LISTING_ROOT, (call as ReferenceInstruction).reference.toString())
                assertEquals("$where: registers handed over", 1, (call as RegisterRangeInstruction).registerCount)
                assertEquals("$where: from the touch", firstParameter(handler), call.startRegister)
                assertEquals("$where: the rest of the body is kept",
                    before.filter { it.opcode != Opcode.NOP }.map { it.opcode },
                    after.drop(1).filter { it.opcode != Opcode.NOP }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
