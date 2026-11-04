/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.util

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.lang.ref.SoftReference
import java.util.Arrays
import java.util.WeakHashMap

/**
 * What each class of the app names, so a pass that wants the classes naming one thing can skip the
 * rest without decoding them.
 *
 * #216: a Manager patch of Facebook spent 78% of its time in about 57 passes over every class, each
 * decoding every method's instructions again, where most of them want the handful of classes that
 * call one class or hold one string. The index reads every class once and keeps, for each, the
 * 16-bit keys of what it names, sorted: the types its instructions and declarations
 * name (an array type also names its element type), the member names its instructions call or
 * read, and the strings it loads.
 *
 * Only classes that can't change are kept: the app's own dex classes and immutable ones. A class a
 * patch has changed is a MutableClass, whose keys the index never keeps, even when it's built after
 * the change, and a class added after the build isn't in it at all, so both always go to the block,
 * as does a class naming something the index has no key for (a call site or a method handle).
 * Keys are hashes, so two names can share one. That only hands the block a class it turns down
 * itself, and the block finds what a full pass finds.
 *
 * The classes are kept in the order classDefForEach hands them over, each one's keys a run in
 * [chunks] that [starts] points at. The patcher keeps its classes in one insertion-ordered map and a
 * class a patch changes takes its old place there, so a walk meets them in the same order: each
 * class is found by comparing it with the one the index holds next, with no lookup. Looking every
 * class up in an identity map instead cost about 3 s of the 21 s patch step of Facebook 585 on two
 * cores.
 */
internal class ReferenceIndex private constructor(
    /** Each class in classDefForEach's order, null where the index doesn't know what it names. */
    private val classes: Array<ClassDef?>,
    /** Where each class's run starts: the chunk in the top bits, the offset in [CHUNK_BITS]. */
    private val starts: IntArray,
    /** Each run is its length, then the class's keys, sorted. */
    private val chunks: Array<CharArray>,
) {
    /** How many classes the index knows what they name. */
    val size: Int = classes.count { it != null }

    /**
     * Hands [block] each class of [context] that may name one of [wanted], in classDefForEach's
     * order. A class the index doesn't hold, one a patch changed or added, always goes to it.
     */
    fun forEachNaming(context: BytecodePatchContext, wanted: CharArray, block: (ClassDef) -> Unit) {
        // A run is about 35 keys, so past a few wanted keys one pass over it beats a search for each.
        val bits = if (wanted.size > FEW_WANTED) LongArray(1 shl 10).also { bits -> wanted.forEach { bits.mark(it) } } else null
        var next = 0
        context.classDefForEach { classDef ->
            val at = find(classDef, next)
            // A class the index doesn't hold usually stands where one it held did, changed by a patch.
            next = if (at < 0) next + 1 else at + 1
            if (at < 0 || if (bits == null) names(at, wanted) else names(at, bits)) block(classDef)
        }
    }

    /** Where the index holds [classDef], looking from [from] a few classes on, or -1. */
    private fun find(classDef: ClassDef, from: Int): Int {
        for (at in from until minOf(classes.size, from + LOOK_AHEAD)) {
            if (classes[at] === classDef) return at
        }
        return -1
    }

    /** Whether the class at [at] may name one of [wanted]. */
    private fun names(at: Int, wanted: CharArray): Boolean {
        val start = starts[at]
        val chunk = chunks[start ushr CHUNK_BITS]
        val from = (start and CHUNK_MASK) + 1
        val to = from + chunk[from - 1].code
        return wanted.any { Arrays.binarySearch(chunk, from, to, it) >= 0 }
    }

    /** Whether the class at [at] may name one of the keys set in [bits]. */
    private fun names(at: Int, bits: LongArray): Boolean {
        val start = starts[at]
        val chunk = chunks[start ushr CHUNK_BITS]
        val from = (start and CHUNK_MASK) + 1
        for (key in from until from + chunk[from - 1].code) {
            if (bits.has(chunk[key])) return true
        }
        return false
    }

    internal companion object {
        /**
         * The index of [context], built on the first walk. Null once memory pressure took it back
         * while the heap has no room to build it again, and for good once the last of its
         * [MAX_BUILDS] builds was taken back too.
         */
        fun of(context: BytecodePatchContext): ReferenceIndex? {
            val held = indexes[context]
            held?.index?.get()?.let { return it }
            val builds = held?.builds ?: 0
            if (builds == MAX_BUILDS || builds > 0 && !hasRoom()) return null
            return build(context).also { indexes[context] = Held(SoftReference(it), builds + 1) }
        }

        /** Lets the index of [context] go, the way memory pressure does. */
        fun drop(context: BytecodePatchContext) {
            indexes[context]?.index?.clear()
        }

        /** Whether the heap has room to build the index again: a third of it free, garbage counted as used. */
        internal var hasRoom: () -> Boolean = {
            val runtime = Runtime.getRuntime()
            runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) > runtime.maxMemory() / 3
        }

        // Held softly, which a heap short of memory can take back rather than fail the patch. A
        // resource patch's passing peak can do that right after the build: Accent color's decoding
        // took it back that way at 768 MB on Facebook 585, and every walk after read every class
        // (#216). So a walk builds it again, at most twice, but only with room: a second build with
        // the heap at its fullest ran out of memory at 600 MB where the patch without an index fits.
        private val indexes = WeakHashMap<BytecodePatchContext, Held>()

        private class Held(val index: SoftReference<ReferenceIndex>, val builds: Int)

        private fun build(context: BytecodePatchContext): ReferenceIndex {
            var count = 0
            context.classDefForEach { count++ }
            val classes = arrayOfNulls<ClassDef>(count)
            val starts = IntArray(count)
            val runs = RunWriter()
            val keys = KeyBuffer()
            var at = 0
            context.classDefForEach { classDef ->
                if (at == count) return@classDefForEach
                if ((classDef is DexBackedClassDef || classDef is ImmutableClassDef) && keys.read(classDef)) {
                    classes[at] = classDef
                    starts[at] = runs.write(keys)
                }
                at++
            }
            return ReferenceIndex(classes, starts, runs.chunks())
        }
    }
}

/**
 * How far on a walk looks for the next class the index holds, past classes removed since the build.
 * A changed class keeps its place and the patcher adds classes at the end, so neither moves the
 * cursor out of step. A class added in the middle, or more than this many removed in a row, would
 * leave it out of step and send every class after it to the block: slower, never wrong.
 */
private const val LOOK_AHEAD = 16

/** Up to this many wanted keys, a walk searches a class's run for each; past it, one pass over the run. */
private const val FEW_WANTED = 4

private fun LongArray.mark(key: Char) {
    this[key.code ushr 6] = this[key.code ushr 6] or (1L shl key.code)
}

private fun LongArray.has(key: Char) = this[key.code ushr 6] and (1L shl key.code) != 0L

/** The first build and two more after memory pressure took it back. */
private const val MAX_BUILDS = 3

// Chunks of 64K keys, 128 KB. G1 gives an array of half a heap region or more (512 KB at a 768 MB
// heap) whole regions of its own, so chunks of 256K keys, 16 bytes past that, took twice their size.
private const val CHUNK_BITS = 16
private const val CHUNK_MASK = (1 shl CHUNK_BITS) - 1

/** Writes each class's run into chunks that never grow, so nothing is copied as they fill. */
private class RunWriter {
    private val chunks = mutableListOf(CharArray(1 shl CHUNK_BITS))
    private var used = 0

    /** Writes [keys]' run and answers where it starts. */
    fun write(keys: KeyBuffer): Int {
        if (used + keys.size + 1 > (1 shl CHUNK_BITS)) {
            chunks += CharArray(1 shl CHUNK_BITS)
            used = 0
        }
        val start = (chunks.lastIndex shl CHUNK_BITS) or used
        val chunk = chunks.last()
        chunk[used] = keys.size.toChar()
        keys.copyInto(chunk, used + 1)
        used += keys.size + 1
        return start
    }

    /** The chunks, the last cut to what it holds. */
    fun chunks(): Array<CharArray> {
        chunks[chunks.lastIndex] = chunks.last().copyOf(used)
        return chunks.toTypedArray()
    }
}

private const val TYPE_KIND = 0x1B873593
private const val NAME_KIND = 0x5BD1E995
private const val STRING_KIND = 0x27D4EB2F

// The top 16 bits of a multiplicative hash, its best mixed. Half the memory of 32-bit keys, and about
// 35 keys a class in Facebook 584 still leave a wanted key's chance of a false match near 1 in 2000.
private fun key(hash: Int, kind: Int): Char = (((hash xor kind) * -0x61C88647) ushr 16).toChar()

/** The key of a class type, as a reference or a declaration names it. */
internal fun typeKey(type: String): Char = key(type.hashCode(), TYPE_KIND)

/** The key of a member name a field or method reference names. */
internal fun nameKey(name: String): Char = key(name.hashCode(), NAME_KIND)

/** The key of a string a const-string loads. */
internal fun stringKey(string: String): Char = key(string.hashCode(), STRING_KIND)

/** The keys of [types], [names] and [strings], sorted. A type is a class or array type: no primitive has a class to name it. */
internal fun wantedKeys(
    types: Collection<String> = emptyList(),
    names: Collection<String> = emptyList(),
    strings: Collection<String> = emptyList(),
): CharArray {
    types.forEach { require(it.length > 1) { "$it is a primitive type, which the reference index doesn't keep" } }
    return (types.map(::typeKey) + names.map(::nameKey) + strings.map(::stringKey)).distinct().sorted().toCharArray()
}

/**
 * [classDefForEach], handing [block] only the classes that may name one of [types] (a class or array
 * type an instruction refers to, or one a declaration names), [names] (the name of a field or method
 * an instruction refers to) or [strings] (a string an instruction loads). Same order as
 * classDefForEach. Exact for a block that only finds a class naming one of them: what such a block
 * finds is what it finds handed every class ([ReferenceIndex]). Without an index ([ReferenceIndex.of]
 * answers null), every class goes to the block.
 */
internal fun BytecodePatchContext.classDefForEachReferencing(
    types: Collection<String> = emptyList(),
    names: Collection<String> = emptyList(),
    strings: Collection<String> = emptyList(),
    block: (ClassDef) -> Unit,
) {
    val wanted = wantedKeys(types, names, strings)
    val index = ReferenceIndex.of(this) ?: return classDefForEach(block)
    index.forEachNaming(this, wanted, block)
}

/** Keys of one class, each once, gathered in a buffer that's reused for the next. */
private class KeyBuffer {
    private var keys = CharArray(1024)
    var size = 0
        private set

    // The class each key was last added for, so a key named again isn't added again.
    private val addedFor = IntArray(1 shl 16)
    private var reading = 0

    /**
     * Reads what [classDef] names, its keys sorted. False when it names something the index has no
     * key for, or more keys than a run's length can say.
     */
    fun read(classDef: ClassDef): Boolean {
        size = 0
        reading++
        if (!addAll(classDef) || size > Char.MAX_VALUE.code) return false
        keys.sort(0, size)
        return true
    }

    fun copyInto(destination: CharArray, offset: Int) {
        keys.copyInto(destination, offset, 0, size)
    }

    private fun add(key: Char) {
        if (addedFor[key.code] == reading) return
        addedFor[key.code] = reading
        if (size == keys.size) keys = keys.copyOf(size * 2)
        keys[size++] = key
    }

    private fun type(type: CharSequence) {
        val text = type.toString()
        if (text.length <= 1) return
        add(typeKey(text))
        if (text[0] == '[') {
            val element = text.trimStart('[')
            if (element.length > 1) add(typeKey(element))
        }
    }

    /** Adds what [reference] names. False for a reference whose names the index has no key for. */
    private fun add(reference: Reference): Boolean {
        when (reference) {
            is StringReference -> add(stringKey(reference.string))
            is TypeReference -> type(reference.type)
            is FieldReference -> {
                type(reference.definingClass)
                type(reference.type)
                add(nameKey(reference.name))
            }
            is MethodReference -> {
                type(reference.definingClass)
                type(reference.returnType)
                reference.parameterTypes.forEach(::type)
                add(nameKey(reference.name))
            }
            is MethodProtoReference -> {
                type(reference.returnType)
                reference.parameterTypes.forEach(::type)
            }
            else -> return false
        }
        return true
    }

    /** Adds what [classDef] names. False when it names something the index has no key for. */
    private fun addAll(classDef: ClassDef): Boolean {
        classDef.superclass?.let(::type)
        classDef.interfaces.forEach(::type)
        classDef.fields.forEach { type(it.type) }
        for (method in classDef.methods) {
            type(method.returnType)
            method.parameterTypes.forEach(::type)
            val instructions = method.implementation?.instructions ?: continue
            for (instruction in instructions) {
                if (instruction !is ReferenceInstruction) continue
                if (!add(instruction.reference)) return false
                if (instruction is DualReferenceInstruction && !add(instruction.reference2)) return false
            }
        }
        return true
    }
}
