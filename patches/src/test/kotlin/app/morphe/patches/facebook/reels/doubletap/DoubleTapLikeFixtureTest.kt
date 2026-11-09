/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.doubletap

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The anchors of Turn off double tap to like on every declared Facebook build, and the patch run on
 * each build's own classes. One reel like helper, with one double-tap like that returns on a missing
 * key; one GestureReactionComponent, whose view has one heart and a double-tap handler that nothing
 * outside the view, its gesture listener and the component's event subscriber reads, each read
 * checked for null straight away; the three players whose own onDoubleTap likes with the source
 * "DOUBLE_TAP"; one feed attachment whose onDoubleTap asks the double-tap like and animates a
 * heart of its own; and for Home's photos, the feed's double-tap like helper with its one like and
 * the one double-tap listener its callers make. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class DoubleTapLikeFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun calls(method: Method, target: MethodReference): Boolean =
        method.implementation?.instructions?.any { it.call?.let(::key) == key(target) } == true

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    /** The registers a call passes, in order. */
    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun sameMethod(a: Method, b: Method) = key(a) == key(b)

    private class Build(
        val name: String,
        val helper: ClassDef,
        val like: Method,
        val doubleTapLike: Method,
        val component: ClassDef,
        val view: ClassDef,
        val heart: Method,
        val handler: FieldReference,
        val readers: List<Method>,
        val readerClasses: List<ClassDef>,
        val attachment: Method,
        val attachmentClass: ClassDef,
        val feedHelper: ClassDef,
        val feedLike: Method,
        val feedListener: Method,
        /** What the patch looks through for the listener, and what it must turn down. */
        val feedClasses: List<ClassDef>,
    )

    private class FeedPhoto(val helper: ClassDef, val like: Method, val listener: Method, val classes: List<ClassDef>)

    /**
     * Home's photos (#220): the feed's double-tap like helper, the only class with a method named
     * getAnimationPosition, and its one like, which only a double tap's heart calls. Then the one
     * gesture listener with nothing but onDoubleTapEvent among the types the helper's callers make.
     * The negative control is every other SimpleOnGestureListener overriding onDoubleTapEvent in the
     * build: none of them is made there.
     */
    private fun readFeedPhoto(bundle: File): FeedPhoto {
        val name = bundle.name
        val found = feedLikeHelpers(bundle)
        val helpers = found.helpers
        assertEquals("$name: classes loading \"$FEED_LIKE_HAPTIC\" (or asking a string table for it) with $ANIMATION_POSITION",
            1, helpers.size)
        val helper = helpers.single()
        assertEquals("$name: classes with a method named $ANIMATION_POSITION", listOf(helper.type),
            found.positions.map { it.definingClass }.distinct())
        val likes = feedLikes(helper)
        assertEquals("$name: the feed helper's static likes taking a GraphQLFeedback", 1, likes.size)
        val like = likes.single()

        // Every method calling the like asks where its heart goes too: it's a double tap's like and
        // never the Like button's, which is why the like can be held back whole.
        val inHelper = { dex: DexBackedDexFile ->
            dex.methodSection.any { it.definingClass == helper.type }
        }
        val likeCallers = FixtureDex.methodsWhere(bundle, inHelper) { calls(it, like) }
        assertTrue("$name: nothing calls the feed helper's like", likeCallers.isNotEmpty())
        for (caller in likeCallers) {
            assertTrue("$name: ${key(caller)} likes without asking where the heart goes", caller.code().any {
                it.call?.let { call -> call.definingClass == helper.type && call.name == ANIMATION_POSITION } == true
            })
        }

        val callers = FixtureDex.methodsWhere(bundle, inHelper) { method ->
            method.definingClass != helper.type && method.implementation != null &&
                method.code().any { it.call?.definingClass == helper.type }
        }
        val makers = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet()).values.toList()
        assertTrue("$name: no class calling the feed helper makes anything", makers.isNotEmpty())
        val made = makers.flatMap(::typesMadeBy).toSet()
        val madeListeners = FixtureDex.classes(bundle, made).values.filter { it.superclass == GESTURE_LISTENER }
        val listeners = madeListeners.mapNotNull(::feedDoubleTapEvent)
        assertEquals("$name: listeners with only $DOUBLE_TAP_EVENT made by a class calling the feed helper", 1, listeners.size)
        val listener = listeners.single()
        // Facebook's own answer: true on the lift, the second tap's end.
        val answer = listener.code()
        assertTrue("$name: ${key(listener)} doesn't read the event's action",
            answer.any { it.call?.let { call -> call.definingClass == MOTION_EVENT_TYPE && call.name == "getAction" } == true })
        assertTrue("$name: ${key(listener)} has no local for the hook to borrow", listener.localRegisterCount() >= 1)
        assertTrue("$name: the feed helper's like has no local for the hook to borrow", like.localRegisterCount() >= 1)

        // The negative control: the build's other onDoubleTapEvent listeners, none made there.
        val overriders = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.stringSection.any { it == DOUBLE_TAP_EVENT } }) {
            it.name == DOUBLE_TAP_EVENT && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT_TYPE)
        }
        val others = FixtureDex.classes(bundle, overriders.map { it.definingClass }.toSet()).values
            .filter { it.superclass == GESTURE_LISTENER && it.type != listener.definingClass }
        assertTrue("$name: no other $DOUBLE_TAP_EVENT listener to tell the feed's from", others.isNotEmpty())
        assertEquals("$name: another $DOUBLE_TAP_EVENT listener is made by a class calling the feed helper", emptyList<String>(),
            others.map { it.type }.filter { it in made })
        return FeedPhoto(helper, like, listener, (makers + madeListeners + others + found.tables).distinctBy { it.type })
    }

    /** Reads what the patch finds in [bundle], checking each anchor is the only one of its kind. */
    private fun read(bundle: File): Build {
        val name = bundle.name
        val helpers = FixtureDex.classesHolding(bundle, MUTATE_LIKE)
        val likes = helpers.flatMap { it.methods }.filter(::isReelLike)
        assertEquals("$name: reel likes holding \"$MUTATE_LIKE\"", 1, likes.size)
        val like = likes.single()
        val helper = helpers.single { it.type == like.definingClass }
        val doubleTapLikes = doubleTapLikes(helper)
        assertEquals("$name: the helper's double-tap likes", 1, doubleTapLikes.size)
        val doubleTapLike = doubleTapLikes.single()
        assertTrue("$name: the double-tap like doesn't return on a missing key", likeKeyResult(doubleTapLike) != null)

        val components = FixtureDex.classesHolding(bundle, GESTURE_REACTION).filter(::isGestureReactionComponent)
        assertEquals("$name: classes whose constructor holds \"$GESTURE_REACTION\"", 1, components.size)
        val component = components.single()
        val viewType = mountedView(component) ?: throw AssertionError("$name: the component makes no one view")
        val view = FixtureDex.classes(bundle, setOf(viewType)).getValue(viewType)
        assertEquals("$name: the view's superclass", "Landroid/widget/FrameLayout;", view.superclass)
        val hearts = hearts(view)
        assertEquals("$name: the view's hearts", 1, hearts.size)
        val heart = hearts.single()
        val handler = handlerField(heart) ?: throw AssertionError("$name: the heart reads none of the view's fields")

        // Nothing in the whole build reads the handler but the view, its gesture listener and the
        // component's event subscriber, the classes the patch looks in.
        val everywhere = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.fieldSection.any { it.definingClass == handler.definingClass && it.name == handler.name }
        }) { readsOf(it, handler).isNotEmpty() }
        val owners = (setOf(viewType) + typesMadeBy(view) + typesMadeBy(component))
        val readerClasses = FixtureDex.classes(bundle, everywhere.map { it.definingClass }.toSet()).values.toList()
        assertTrue("$name: a class the patch doesn't look in reads the handler: " +
            everywhere.filter { it.definingClass !in owners }.map(::key), everywhere.all { it.definingClass in owners })
        assertEquals("$name: readers of the handler", 3, everywhere.size)
        assertEquals("$name: the handler reads", 3, everywhere.sumOf { readsOf(it, handler).size })
        assertTrue("$name: the heart doesn't read the handler", everywhere.any { sameMethod(it, heart) })
        assertEquals("$name: onDoubleTap readers", 1, everywhere.count { it.name == "onDoubleTap" })
        for (reader in everywhere) {
            for (read in readsOf(reader, handler)) {
                assertTrue("$name: ${key(reader)} doesn't check the handler for null straight after reading it",
                    isCheckedRead(reader, read))
            }
        }

        val attachmentClasses = FixtureDex.classesHolding(bundle, HEART_RISE)
        val attachments = attachmentClasses.flatMap { it.methods }.filter { isAttachmentDoubleTap(it, doubleTapLike) }
        assertEquals("$name: onDoubleTaps asking the double-tap like and loading \"$HEART_RISE\"", 1, attachments.size)
        // And the only onDoubleTap loading it at all, which is what the mutation contract picks it by.
        assertEquals("$name: onDoubleTaps loading \"$HEART_RISE\"", 1, attachmentClasses.flatMap { it.methods }.count {
            it.name == "onDoubleTap" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/MotionEvent;") &&
                holdsString(it, HEART_RISE)
        })
        val attachment = attachments.single()
        val feed = readFeedPhoto(bundle)
        return Build(name, helper, like, doubleTapLike, component, view, heart, handler, everywhere, readerClasses,
            attachment, attachmentClasses.single { it.type == attachment.definingClass },
            feed.helper, feed.like, feed.listener, feed.classes)
    }

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    /**
     * The players with a double-tap listener of their own like through the helper's like with the
     * literal "DOUBLE_TAP" as its source, which is what the hook in the like holds back, and the
     * Like button's handler passes something else.
     */
    @Test
    fun `on each declared build three players' own double taps send the reel like with the source DOUBLE_TAP`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val like = FixtureDex.classesHolding(bundle, MUTATE_LIKE).flatMap { it.methods }.single(::isReelLike)
                val senders = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.stringSection.any { it == DOUBLE_TAP } &&
                        dex.methodSection.any { it.definingClass == like.definingClass && it.name == like.name }
                }) { calls(it, like) && holdsString(it, DOUBLE_TAP) }
                val listeners = senders.filter { it.name == "onDoubleTap" }
                assertEquals("${bundle.name}: onDoubleTaps sending the reel like from a double tap", 3, listeners.size)
                for (listener in listeners) {
                    val code = listener.code()
                    val send = code.indexOfLast { it.call?.let(::key) == key(like) }
                    // The helper first, then the like's arguments in order.
                    val source = code[send].registers()[1 + likeSource(like)!!]
                    val loaded = code.subList(0, send).last {
                        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == source
                    }
                    assertEquals("${bundle.name}: ${key(listener)} passes another source", Opcode.CONST_STRING, loaded.opcode)
                    assertEquals("${bundle.name}: ${key(listener)} passes another source", DOUBLE_TAP,
                        ((loaded as ReferenceInstruction).reference as StringReference).string)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    /**
     * The patch itself, run on each build's own classes: the like asks first and returns while the
     * extension holds a double tap's like back, the double-tap like's key goes through the extension
     * before its null check, every read of the gesture view's handler goes through it before its
     * null check (the heart's through the heart hook), and the feed attachment's onDoubleTap asks
     * first and answers false.
     */
    @Test
    fun `on each declared build the patch applies and every hook sits where it's claimed`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val build = read(bundle)
                val name = build.name
                val classes = (listOf(build.helper, build.component, build.view, build.attachmentClass) + build.readerClasses +
                    build.feedHelper + build.feedClasses).distinctBy { it.type } + ExtensionDex.classDef(SETTINGS_STATUS)
                val context = PatchContexts.of(classes)
                turnOffDoubleTapLikePatch.execute(context)

                fun patched(method: Method): Method =
                    context.mutableClassDefBy(method.definingClass).methods.single { sameMethod(it, method) }

                // The like: its source copied down and asked about before anything else runs.
                val like = patched(build.like).code()
                assertEquals("$name: the like's first call", HOLD_BACK_LIKE, like.first { it.call != null }.call.toString())
                val copy = like[0] as TwoRegisterInstruction
                assertEquals("$name: the like doesn't copy its source first", Opcode.MOVE_OBJECT_FROM16, like[0].opcode)
                assertEquals("$name: the source the like asks about",
                    build.like.localRegisterCount() + 1 + likeSource(build.like)!!, copy.registerB)
                assertEquals("$name: the like doesn't return while held back", Opcode.RETURN_VOID, like[4].opcode)

                // The double-tap like: the key through the extension, then Facebook's own null check.
                val original = build.doubleTapLike.code()
                val taken = likeKeyResult(build.doubleTapLike)!!
                val keyRegister = (original[taken] as OneRegisterInstruction).registerA
                val doubleTapLike = patched(build.doubleTapLike).code()
                assertEquals("$name: after the key", LIKE_KEY, doubleTapLike[taken + 1].call.toString())
                assertEquals("$name: the key the extension gets", listOf(keyRegister), doubleTapLike[taken + 1].registers())
                assertEquals("$name: the key taken back", Opcode.MOVE_RESULT_OBJECT, doubleTapLike[taken + 2].opcode)
                assertEquals("$name: the key taken back", keyRegister, (doubleTapLike[taken + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the key's type kept", "Ljava/lang/String;",
                    ((doubleTapLike[taken + 3] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: Facebook's null check of the key", original[taken + 1].opcode, doubleTapLike[taken + 4].opcode)

                // Every read of the handler: the extension's answer in place of it, then the null check.
                for (reader in build.readers) {
                    val before = reader.code()
                    val after = patched(reader).code()
                    val hook = if (sameMethod(reader, build.heart)) HEART else HANDLER
                    val reads = readsOf(reader, build.handler)
                    // The reads after this one moved down three instructions for each hook before them.
                    reads.sorted().forEachIndexed { hooked, read ->
                        val at = read + 3 * hooked
                        val register = (before[read] as OneRegisterInstruction).registerA
                        assertEquals("$name: ${key(reader)} lost its read", build.handler.toString(), after[at].field.toString())
                        assertEquals("$name: ${key(reader)} after the read", hook, after[at + 1].call.toString())
                        assertEquals("$name: ${key(reader)} hands on another register", listOf(register), after[at + 1].registers())
                        assertEquals("$name: ${key(reader)} takes the answer back", register,
                            (after[at + 2] as OneRegisterInstruction).registerA)
                        assertEquals("$name: ${key(reader)} keeps the handler's type", build.handler.type,
                            ((after[at + 3] as ReferenceInstruction).reference as TypeReference).type)
                        assertEquals("$name: ${key(reader)} lost its null check", before[read + 1].opcode, after[at + 4].opcode)
                    }
                    assertEquals("$name: ${key(reader)} hooks", reads.size, after.count { it.call.toString() == hook })
                }

                // The attachment's onDoubleTap: asks first, answers false while held back.
                val attachment = patched(build.attachment).code()
                assertEquals("$name: the attachment's first call", HOLD_BACK_TAP, attachment[0].call.toString())
                assertEquals("$name: the attachment doesn't answer false", Opcode.CONST_4, attachment[3].opcode)
                assertEquals("$name: the attachment doesn't return", Opcode.RETURN, attachment[4].opcode)

                // Home's photo listener: hands over its event first, answers false while held back,
                // then runs Facebook's own answer untouched.
                val listener = patched(build.feedListener).code()
                assertEquals("$name: the photo listener doesn't copy its event first", Opcode.MOVE_OBJECT_FROM16, listener[0].opcode)
                assertEquals("$name: the event the photo listener hands over", build.feedListener.localRegisterCount() + 1,
                    (listener[0] as TwoRegisterInstruction).registerB)
                assertEquals("$name: the photo listener's first call", HOLD_BACK_FEED_TAP, listener[1].call.toString())
                assertEquals("$name: the photo listener doesn't answer false", 0, (listener[4] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the photo listener doesn't return", Opcode.RETURN, listener[5].opcode)
                assertEquals("$name: the photo listener's own answer changed", build.feedListener.code().map { it.opcode },
                    listener.drop(6).map { it.opcode })
                // The feed helper's like: asks first, returns while held back, then likes as before.
                val feedLike = patched(build.feedLike).code()
                assertEquals("$name: the feed like's first call", HOLD_BACK_FEED_LIKE, feedLike[0].call.toString())
                assertEquals("$name: the feed like doesn't return while held back", Opcode.RETURN_VOID, feedLike[3].opcode)
                assertEquals("$name: the feed like changed", build.feedLike.code().map { it.opcode }, feedLike.drop(4).map { it.opcode })
                // Nothing the patch turned down was changed.
                for (other in build.feedClasses.filter { it.type != build.feedListener.definingClass }) {
                    assertEquals("$name: ${other.type} was hooked", 0, context.mutableClassDefBy(other.type).methods.sumOf { method ->
                        method.implementation?.instructions?.count { it.call.toString() == HOLD_BACK_FEED_TAP } ?: 0
                    })
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "doubleTapLike" }
                assertEquals("$name: SettingsStatus.doubleTapLike() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    private companion object {
        const val DOUBLE_TAP = "DOUBLE_TAP"
        const val MOTION_EVENT_TYPE = "Landroid/view/MotionEvent;"
    }
}

/**
 * What Turn off double tap to like looks through for Home's photos in [bundle]: the feed's
 * double-tap like helper, the classes calling into it and the gesture listeners they make. Another
 * fixture test running the patch on a slice of a build adds these, or the patch's feed photo find
 * refuses. [DoubleTapLikeFixtureTest] checks the anchors themselves.
 */
internal fun feedPhotoFixtureClasses(bundle: File): List<ClassDef> {
    val found = feedLikeHelpers(bundle)
    val helper = found.helpers.single()
    val inHelper = { dex: DexBackedDexFile -> dex.methodSection.any { it.definingClass == helper.type } }
    val callers = FixtureDex.methodsWhere(bundle, inHelper) { method ->
        method.definingClass != helper.type && method.implementation?.instructions?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == helper.type
        } == true
    }
    val makers = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet()).values.toList()
    val listeners = FixtureDex.classes(bundle, makers.flatMap(::typesMadeBy).toSet()).values
        .filter { it.superclass == GESTURE_LISTENER }
    return (listOf(helper) + makers + listeners + found.tables).distinctBy { it.type }
}

/**
 * The feed's double-tap like helpers in [bundle]: classes with an instance getAnimationPosition answering a
 * PointF that load [FEED_LIKE_HAPTIC] or, from 586, ask a string table for it. With them every method named
 * getAnimationPosition, and the string tables holding the name, which a slice of the build needs for the
 * patch to read the helper's ask.
 */
internal class FeedLikeHelpers(val helpers: List<ClassDef>, val positions: List<Method>, val tables: List<ClassDef>)

internal fun feedLikeHelpers(bundle: File): FeedLikeHelpers {
    val holders = FixtureDex.classesHolding(bundle, FEED_LIKE_HAPTIC).associateBy { it.type }
    val resolve: (MethodReference) -> Method? = { call -> holders[call.definingClass]?.let { resolveStatic(it, call) } }
    val tables = holders.values.filter { owner -> owner.methods.any { isStringTable(it) && holdsString(it, FEED_LIKE_HAPTIC) } }
    val positions = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.stringSection.any { it == ANIMATION_POSITION } }) {
        it.name == ANIMATION_POSITION
    }
    val owners = FixtureDex.classes(bundle, positions.map { it.definingClass }.toSet()).values
    val helpers = owners.filter { owner ->
        isFeedLikeHelper(owner) && owner.methods.any { namesString(it, FEED_LIKE_HAPTIC, resolve) }
    }
    return FeedLikeHelpers(helpers, positions, tables)
}
