/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.feed.isStringTable
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.feed.tableStringAt
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.util.ControlFlow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import app.morphe.util.returnEarly
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** The test keys the stories row hands a story's name and its title (587 `LX/03CI;->render`). */
internal const val TRAY_NAME_KEY = "profile_name"
internal const val TRAY_TITLE_KEY = "story_pog_title"

/**
 * What the method making an open story's title loads: the title's component and its spec (587
 * `LX/0C8E;->A00`). The header's own render (587 `LX/0C8D;->A19`) loads the component's name alone.
 */
internal const val STORY_TITLE_COMPONENT = "StoryViewerTitleSubtitleComponent"
internal const val STORY_TITLE_SPEC = "StoryViewerTitleSubtitleComponentSpec"

/** What the post header's constructor loads: its component's name (587 `LX/09Y7;`). */
internal const val POST_HEADER_COMPONENT = "FDSPostHeaderKComponent"

/** What the method making a reel's author line loads (587 `LX/08j3;->A19`). */
internal const val REEL_AUTHOR_COMPONENT = "FbShortsViewerVideoAuthorComponentSpec:placeholder_only_for_blank_pfp"

/** The helpers the name boxes add to each class that hands a name to its text builder. */
internal const val STORY_NAME_HELPER = "hushfacebookStoryNameBox"
internal const val REEL_NAME_HELPER = "hushfacebookReelNameBox"

/** The helper the box adds to an open story's title components, putting the box on the style its name is laid out with. */
internal const val STORY_TITLE_HELPER = "hushfacebookStoryTitleBox"

/** The helper the box adds to each post header, putting the box on the column holding its name and the line under it (#490). */
internal const val NAME_BLOCK_HELPER = "hushfacebookNameBlockBox"

/** A group reel's author line, a component of its own whose static setup names it (588 `LX/0X81;`, #490). */
internal const val GROUP_AUTHOR_COMPONENT = "FbShortsViewerVideoGroupAuthorKComponent"

/** The helper a group reel's author line hands its names' column style through (#490). */
internal const val GROUP_NAME_HELPER = "hushfacebookGroupNameBox"

private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val OBJECT = "Ljava/lang/Object;"
internal const val FOR_STORY_NAME = "$CAPTION_BACKDROP->forStoryName()$DRAWABLE"
internal const val FOR_REEL_NAME = "$CAPTION_BACKDROP->forReelName()$DRAWABLE"
internal const val REEL_AUTHOR = "$CAPTION_BACKDROP->reelAuthor($OBJECT)V"
internal const val HEADER = "$CAPTION_BACKDROP->header($OBJECT$OBJECT)V"
internal const val NAME_LINES = "$CAPTION_BACKDROP->nameLines(I)I"
internal const val NAME_BLOCK = "$CAPTION_BACKDROP->nameBlock()V"
internal const val FOR_NAME_BLOCK = "$CAPTION_BACKDROP->forNameBlock()$DRAWABLE"
internal const val FOR_GROUP_NAME = "$CAPTION_BACKDROP->forGroupName()$DRAWABLE"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

/** One of the looks the post header can be drawn in, named in its look enum's static initializer (588 `LX/02ta;`). */
internal const val ON_MEDIA = "ON_MEDIA"

/** The trace a feed post's header builds its name under when tracing is on (588 `LX/02uV;->A04`). */
internal const val FEED_TITLE_TRACE = "render_title_text_builder"

/**
 * Instruction [index] of [method] leaves an object in v[register]; the hook goes right after it. For an
 * open story's title, [index] is the call making the title's container or wrapping the shown title's
 * name, v[register] the style it's handed, and the hook goes right before it.
 */
internal class Place(val method: Method, val index: Int, val register: Int) {
    override fun toString() = "${method.definingClass}->${method.name}@$index/v$register"
}

/**
 * The stories row's names (#490): each [places] entry is where a name's text builder gets its test
 * key, in the builder's register. [text] is the field a text builder keeps its text in, which an
 * open story's title and the post header put their names into too.
 */
internal class TrayNames(val text: FieldReference, val places: List<Place>)

/**
 * A reel's name (#490): [marks] are where the reel's author component makes its header props, [entries]
 * the header's methods that build a name from those props (with the props' parameter register, p
 * numbered), and [names] where each of those hands its text builder the name.
 */
internal class ReelNames(val props: String, val marks: List<Place>, val entries: List<Pair<Method, Int>>, val names: List<Place>)

/**
 * A feed post's name (#533). Feed posts draw their header through a header class of their own (588
 * `LX/02uV;`, FDSPostHeader), not the one a reel's author line makes ([findReelNames]). Its static
 * [build] (588 `A04`) takes the header's props in parameter [props] and the header in [host] (p
 * numbered), and the header keeps how it's drawn in [look]. [names] are where a name's text builder
 * gets its text and [lines] where the name's line count is read, in [build] and in the lambda [build]
 * hands its trace when tracing is on (588 `LX/03Gn;->invoke`).
 */
internal class FeedNames(
    val build: Method,
    val props: Int,
    val host: Int,
    val look: FieldReference,
    val names: List<Place>,
    val lines: List<Place>,
)

/**
 * A post header's name and the line under it (the date, "AI content", a song), which Facebook builds into
 * one column (#490): [list] is the constructor call of the list that column gets, with the list's
 * register, and [column] the column's constructor call, with its style's register.
 */
internal class NameBlock(val list: Place, val column: Place) {
    override fun toString() = "$column, list $list"

    /** The kind of column Facebook builds the name and the line under it into (588 `LX/02gk;`). */
    val columnType: String
        get() = ((column.method.implementation!!.instructions.elementAt(column.index) as ReferenceInstruction)
            .reference as MethodReference).definingClass
}

private fun isVirtualCall(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE

internal fun isConstructorCall(instruction: Instruction) =
    (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
        ((instruction as ReferenceInstruction).reference as MethodReference).name == "<init>"

/** The register [instruction] hands its call in [position] (0 is the receiver of a virtual call), or null. */
internal fun argument(instruction: Instruction, position: Int): Int? = when (instruction) {
    is RegisterRangeInstruction -> (instruction.startRegister + position).takeIf { position < instruction.registerCount }
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount).getOrNull(position)
    else -> null
}

private fun loads(instruction: Instruction, string: String) =
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == string

/** Whether [instruction] puts a value into [field]. */
private fun puts(instruction: Instruction, field: FieldReference) =
    instruction.opcode == Opcode.IPUT_OBJECT && ((instruction as ReferenceInstruction).reference as FieldReference).let {
        it.definingClass == field.definingClass && it.name == field.name && it.type == field.type
    }

/** Where [method] puts a text into a text builder's [text] field, with the builder's register. */
private fun textPuts(method: Method, text: FieldReference): List<Place> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { puts(code[it], text) }.map { Place(method, it, (code[it] as TwoRegisterInstruction).registerB) }
}

/**
 * Finds the stories row's names. The row is drawn in the one method loading [TRAY_NAME_KEY] and
 * [TRAY_TITLE_KEY] (587 `LX/03CI;->render`). Each name's text builder gets the test key
 * [TRAY_NAME_KEY] through a one-string setter (587 `LX/0HCx;->A1t`), after the builder's text went into
 * its one text field (587 `LX/034v;->A0A`) since the builder was made.
 *
 * 588's store-less build splits the row's render (`LX/03tw;->render`) into helpers of its class: one
 * holds [TRAY_TITLE_KEY] (`A0A`) and the one making the names (`A08`) asks a string table for
 * [TRAY_NAME_KEY] (`LX/07ho;->A00`), so that one is the row ([splitTrays]) and the key it hands over
 * is the table's answer.
 */
internal fun BytecodePatchContext.findTrayNames(): TrayNames {
    val trays = methodsWithString(TRAY_NAME_KEY).filter { loadsString(it, TRAY_TITLE_KEY) }.ifEmpty { splitTrays() }
    val tray = trays.singleOrNull()
        ?: refuse("expected one method drawing the stories row (\"$TRAY_NAME_KEY\", \"$TRAY_TITLE_KEY\"), found ${trays.size}")
    val where = "${tray.definingClass}->${tray.name}"
    val code = tray.implementation!!.instructions.toList()
    val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    fun namesKey(index: Int) = loads(code[index], TRAY_NAME_KEY) ||
        (code[index].opcode == Opcode.MOVE_RESULT_OBJECT && tableStringAt(code, index - 1, resolve) == TRAY_NAME_KEY)
    val keys = code.indices.filter { index ->
        val instruction = code[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (!isVirtualCall(instruction) || reference == null || reference.returnType != "V" ||
            reference.parameterTypes.map { it.toString() } != listOf(STRING)
        ) return@filter false
        val key = argument(instruction, 1) ?: return@filter false
        val writers = tray.writersReaching(index, key)
        writers.isNotEmpty() && writers.all { it >= 0 && namesKey(it) }
    }
    if (keys.isEmpty()) refuse("$where never hands \"$TRAY_NAME_KEY\" to a text builder")
    val places = keys.map { Place(tray, it, firstArgument(code[it])!!) }
    val texts = places.map { place ->
        // Back to where the builder was made: the text it was handed on the way.
        val made = (place.index - 1 downTo 0).firstOrNull { writes(code[it], place.register) }
            ?: refuse("$where hands \"$TRAY_NAME_KEY\" to v${place.register} without making it there")
        val put = ((made + 1) until place.index).filter { index ->
            code[index].opcode == Opcode.IPUT_OBJECT && (code[index] as TwoRegisterInstruction).registerB == place.register &&
                ((code[index] as ReferenceInstruction).reference as FieldReference).type == CHAR_SEQUENCE
        }.map { (code[it] as ReferenceInstruction).reference as FieldReference }.distinctBy { it.toString() }
        put.singleOrNull() ?: refuse("expected one text put into $place's builder, found ${put.size}")
    }.distinctBy { it.toString() }
    val text = texts.singleOrNull() ?: refuse("expected the stories row's names in one kind of text builder, found ${texts.size}")
    return TrayNames(text, places)
}

/**
 * The stories row when its render is split into helpers ([findTrayNames]): the methods asking a string
 * table for [TRAY_NAME_KEY] in a class that holds [TRAY_TITLE_KEY] in another method, where one method
 * of the class calls both.
 */
private fun BytecodePatchContext.splitTrays(): List<Method> {
    val titles = methodsWithString(TRAY_TITLE_KEY)
    val hosts = titles.map { it.definingClass }.toSet()
    if (hosts.isEmpty()) return emptyList()
    val names = methodsNaming(TRAY_NAME_KEY) { it.definingClass in hosts && !isStringTable(it) }
        .filterNot { loadsString(it, TRAY_NAME_KEY) }.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
    fun Method.calls(callee: Method) = implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == callee.definingClass && it.name == callee.name &&
                it.parameterTypes.map(CharSequence::toString) == callee.parameterTypes.map(CharSequence::toString)
        } == true
    } == true
    return names.filter { name ->
        val title = titles.filter { it.definingClass == name.definingClass }
        classDefBy(name.definingClass).methods.any { render -> render.calls(name) && title.any { render.calls(it) } }
    }
}

/**
 * The style register [instruction] hands a container it makes from a [style] and a list of lines, or
 * null when it makes no such thing: a static call or a constructor taking the style first and a list
 * (587 `LX/04UP;->A0Q` and `LX/02lQ;-><init>`).
 */
private fun containerStyle(instruction: Instruction, style: String): Int? {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val types = reference.parameterTypes.map { it.toString() }
    if (types.firstOrNull() != style || LIST !in types) return null
    return when {
        instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE -> argument(instruction, 0)
        isConstructorCall(instruction) -> argument(instruction, 1)
        else -> null
    }
}

/**
 * The register of the style [instruction] wraps a component in, or null when it's no such call: a static
 * call taking a [component] first and one [style] after it (587 arm64 `LX/04UR;->A0T` takes the style
 * second, arm-v7a `LX/0AeV;->A0K` third, after the context).
 */
private fun wrapStyle(instruction: Instruction, component: String, style: String): Int? {
    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val types = ((instruction as ReferenceInstruction).reference as MethodReference).parameterTypes.map { it.toString() }
    if (types.firstOrNull() != component || types.count { it == style } != 1) return null
    val before = types.take(types.indexOf(style))
    return argument(instruction, before.size + before.count { it == "J" || it == "D" })
}

/** The types [method] makes with new-instance. */
private fun madeBy(method: Method) = method.implementation!!.instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
    .map { ((it as ReferenceInstruction).reference as TypeReference).type }

/**
 * Finds an open story's titles, each place being a call handed the style the name is laid out with,
 * with that style's register. The box goes on that style.
 *
 * The title an open story shows (587 `LX/0C8Y;`, StoryViewerTitleComponent) is the one class the
 * methods loading [STORY_TITLE_COMPONENT] make (587 `LX/0C8D;->A19`) whose code wraps a component of its
 * own method's return type in a Litho style (587 `render` and `LX/04UR;->A0T`). That wrap is the clickable
 * host drawing the name.
 *
 * The other title component is the one class the method loading [STORY_TITLE_COMPONENT] and
 * [STORY_TITLE_SPEC] makes (587 `LX/0C8E;->A00`) that puts text into a text builder's [text] field (587
 * `LX/0C8e;`). Each of its methods doing that, for a name alone (587 `render`) or a name over its line
 * (`A07`), hands the built lines to one container made with a Litho style. On 587 on the S22 it only
 * fills the story pager's hidden neighbour card (#490), and it's boxed in case Facebook shows it.
 */
internal fun BytecodePatchContext.findOpenStoryNames(text: FieldReference): List<Place> {
    val headers = methodsWithString(STORY_TITLE_COMPONENT)
    val makers = headers.filter { loadsString(it, STORY_TITLE_SPEC) }
    if (makers.isEmpty()) refuse("no method makes an open story's title (\"$STORY_TITLE_COMPONENT\", \"$STORY_TITLE_SPEC\")")
    // 588's Android 9 build makes the text builder itself there too, whose own setter puts its text.
    val made = makers.flatMap(::madeBy).toSet() - text.definingClass
    val titles = made.filter { type -> classDefByOrNull(type)?.methods?.any { textPuts(it, text).isNotEmpty() } == true }
    val title = titles.singleOrNull() ?: refuse("expected one open story title component naming its text, found ${titles.size}")
    val style = findBoxStyle(text.definingClass).background.substringAfterLast(')')

    val wraps = (headers.flatMap(::madeBy).toSet() - title).flatMap { type ->
        (classDefByOrNull(type)?.methods ?: emptyList()).flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.mapNotNull { index -> wrapStyle(code[index], method.returnType, style)?.let { Place(method, index, it) } }
        }
    }
    val shown = wraps.singleOrNull()
        ?: refuse("expected one open story title wrapping its name in a style (\"$STORY_TITLE_COMPONENT\"), found ${wraps.size}")

    return listOf(shown) + classDefByOrNull(title)!!.methods.mapNotNull { method ->
        val puts = textPuts(method, text)
        if (puts.isEmpty()) return@mapNotNull null
        val code = method.implementation!!.instructions.toList()
        val containers = (puts.minOf { it.index } + 1 until code.size).mapNotNull { index ->
            containerStyle(code[index], style)?.let { Place(method, index, it) }
        }
        containers.singleOrNull()
            ?: refuse("expected ${method.definingClass}->${method.name} to hand its title to one container, found ${containers.size}")
    }
}

/**
 * Finds a reel's name. It's drawn by the post header that feed posts use too, the class whose
 * constructor loads [POST_HEADER_COMPONENT] (587 `LX/09Y7;`). The header's methods that put a name
 * into a text builder's [text] field take the header's props (587 `LX/03J1;`), and the reel's author
 * component, the method loading [REEL_AUTHOR_COMPONENT] (587 `LX/08j3;->A19`), makes its own
 * subclasses of those props (587 `LX/09Y8;` and `LX/039q;`). The props are marked as they're made, each
 * of those header methods says whose props it builds from as it starts, and its first text put is the
 * name.
 */
internal fun BytecodePatchContext.findReelNames(text: FieldReference): ReelNames {
    // 588's Android 9 build asks a string table for the header's name in its constructor (`LX/09GG;`).
    val headers = methodsWithString(POST_HEADER_COMPONENT).filter { it.name == "<init>" }.map { it.definingClass }.toSet()
        .ifEmpty { methodsNaming(POST_HEADER_COMPONENT) { it.name == "<init>" }.map { it.definingClass }.toSet() }
    val header = headers.singleOrNull() ?: refuse("expected one post header (\"$POST_HEADER_COMPONENT\"), found ${headers.size}")
    val authors = methodsWithString(REEL_AUTHOR_COMPONENT)
    val author = authors.singleOrNull()
        ?: refuse("expected one method making a reel's author line (\"$REEL_AUTHOR_COMPONENT\"), found ${authors.size}")
    val authorWhere = "${author.definingClass}->${author.name}"
    val authorCode = author.implementation!!.instructions.toList()

    val namers = classDefByOrNull(header)!!.methods.filter { textPuts(it, text).isNotEmpty() }
    if (namers.isEmpty()) refuse("$header never puts a name into a text builder")
    fun superclassOf(type: String) = classDefByOrNull(type)?.superclass
    val made = authorCode.indices.filter { isConstructorCall(authorCode[it]) }
    fun madeType(index: Int) = ((authorCode[index] as ReferenceInstruction).reference as MethodReference).definingClass
    val propsTypes = namers.flatMap { method -> method.parameterTypes.map { it.toString() } }.filter(::appClass).toSet()
        .filter { props -> made.any { superclassOf(madeType(it)) == props } }
    val props = propsTypes.singleOrNull()
        ?: refuse("expected $authorWhere to make one kind of $header props, found ${propsTypes.size}")
    val marks = made.filter { superclassOf(madeType(it)) == props }.map { Place(author, it, firstArgument(authorCode[it])!!) }

    val entries = namers.mapNotNull { method ->
        val at = method.parameterTypes.indexOfFirst { it.toString() == props }
        if (at < 0) return@mapNotNull null
        // p0 is the header itself; a long or a double takes two registers.
        val before = method.parameterTypes.take(at).sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        method to (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) + before
    }
    if (entries.isEmpty()) refuse("no method of $header names its text from its $props props")
    val names = entries.map { (method, _) -> textPuts(method, text).minBy { it.index } }
    return ReelNames(props, marks, entries, names)
}

/**
 * Where each of the header's name methods reads how many lines the name may take (#533): the one int
 * its props carry (588 `LX/03GS;->A00:I`). 588's `LX/09BS;->A0B` reads it for a name alone and, when
 * it's more than one line and there's text to follow the name, hands over to `A0D`, which reads it
 * again for the name and that text together. Each [Place] is the read, with the register it lands in.
 */
internal fun findNameLines(reel: ReelNames): List<Place> = reel.entries.map { (method, _) -> lineRead(method, reel.props) }

/** Where [method] reads the line count of its [props] props, with the register it lands in. */
private fun lineRead(method: Method, props: String): Place {
    val code = method.implementation!!.instructions.toList()
    val reads = code.indices.filter { index ->
        code[index].opcode == Opcode.IGET &&
            ((code[index] as ReferenceInstruction).reference as FieldReference).let { it.definingClass == props && it.type == "I" }
    }
    val at = reads.singleOrNull()
        ?: refuse("expected ${method.definingClass}->${method.name} to read its $props props' line count once, found ${reads.size}")
    return Place(method, at, (code[at] as TwoRegisterInstruction).registerA)
}

/**
 * The post header's field holding how it's drawn (#533): an enum whose constants include [ON_MEDIA],
 * the look of a header drawn over a feed post's photo or video (588 `LX/09BS;->A02:LX/02ta;`).
 */
internal fun BytecodePatchContext.findHeaderLook(reel: ReelNames): FieldReference =
    lookOf(reel.names.map { it.method.definingClass }.toSet().single())

/** [header]'s field holding how it's drawn ([findHeaderLook]). */
private fun BytecodePatchContext.lookOf(header: String): FieldReference {
    val enums = classDefByOrNull(header)!!.instanceFields.filter { classDefByOrNull(it.type)?.superclass == "Ljava/lang/Enum;" }
    val looks = enums.takeIf { it.size == 1 } ?: enums.filter { field ->
        classDefByOrNull(field.type)!!.methods.any { it.name == "<clinit>" && loadsAny(it, ON_MEDIA) }
    }
    return looks.singleOrNull() ?: refuse("expected $header to keep one look naming \"$ON_MEDIA\", found ${looks.size}")
}

/**
 * Finds a feed post's name ([FeedNames]). The one method loading [FEED_TITLE_TRACE] builds it (588
 * `LX/02uV;->A04`, a588a9 `LX/02vR;->A04`): static, handed its own class's header and the [reel]
 * header's props. It puts the name into a text builder's [text] field itself, and when tracing is on it
 * hands the same build to a lambda it makes (588 `LX/03Gn;`, case 1 of its `invoke`), which gives the
 * builder its name through a one-text setter (588 `LX/02tw;->A1s`). Each reads the props' line count
 * once (588 `LX/03GS;->A00:I`).
 */
internal fun BytecodePatchContext.findFeedNames(text: FieldReference, reel: ReelNames): FeedNames {
    val builds = methodsWithString(FEED_TITLE_TRACE)
    val build = builds.singleOrNull()
        ?: refuse("expected one method building a feed post's name (\"$FEED_TITLE_TRACE\"), found ${builds.size}")
    val where = "${build.definingClass}->${build.name}"
    val header = build.definingClass
    val types = build.parameterTypes.map { it.toString() }
    if (!AccessFlags.STATIC.isSet(build.accessFlags) || types.count { it == reel.props } != 1 || types.count { it == header } != 1) {
        refuse("expected $where to be static and take one $header and one ${reel.props}")
    }
    // A static method's first parameter is p0; a long or a double takes two registers.
    fun parameter(type: String) = types.take(types.indexOf(type)).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val puts = textPuts(build, text)
    if (puts.isEmpty()) refuse("$where never puts a name into a text builder")

    val setters = (classDefByOrNull(text.definingClass)?.methods ?: emptyList()).filter { method ->
        method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(CHAR_SEQUENCE) &&
            textPuts(method, text).isNotEmpty()
    }.map { it.name }.toSet()
    fun setterCalls(method: Method): List<Place> {
        val code = method.implementation?.instructions?.toList() ?: return emptyList()
        return code.indices.filter { index ->
            val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
            isVirtualCall(code[index]) && call != null && call.definingClass == text.definingClass && call.name in setters &&
                call.returnType == "V" && call.parameterTypes.map { it.toString() } == listOf(CHAR_SEQUENCE)
        }.map { Place(method, it, argument(code[it], 0)!!) }
    }
    val traced = madeBy(build).distinct().mapNotNull { classDefByOrNull(it) }.flatMap { it.methods }
        .filter { it.name == "invoke" && setterCalls(it).isNotEmpty() }
    val lambda = traced.singleOrNull() ?: refuse("expected $where to trace one lambda naming a post, found ${traced.size}")

    return FeedNames(
        build,
        parameter(reel.props),
        parameter(header),
        lookOf(header),
        puts + setterCalls(lambda),
        listOf(lineRead(build, reel.props), lineRead(lambda, reel.props)),
    )
}

private fun loadsAny(method: Method, string: String) = method.implementation?.instructions?.any { loads(it, string) } == true

/** Whether [instruction] calls one of [methods]. */
private fun callsOneOf(instruction: Instruction, methods: Collection<Method>): Boolean {
    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return methods.any { descriptor(it) == "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" }
}

/**
 * Finds where each post header builds its name and the line under it into one column (#490), the
 * column's list made empty before the name is built. The feed header's one other method calling
 * [FeedNames.build] (588 `LX/02uV;->A05`) makes one container from a style and a list, with the name
 * built in between: that's the column (588 `LX/02gk;`). The reel header's method calling one of its name
 * methods from outside them (588 `LX/09BS;->render`) makes one container of the same type, also after
 * building a name into its list. A column a branch jumps straight into, or whose style is read again
 * after it's made, isn't taken: the hook right before it would be skipped, or its box would land twice.
 */
internal fun BytecodePatchContext.findNameBlocks(reel: ReelNames, feed: FeedNames, styleType: String): List<NameBlock> {
    val feedHost = feed.build.definingClass
    val feedCallers = classDefByOrNull(feedHost)!!.methods.filter { method ->
        descriptor(method) != descriptor(feed.build) && method.implementation?.instructions?.any { callsOneOf(it, listOf(feed.build)) } == true
    }
    val feedBlock = feedCallers.singleOrNull()
        ?: refuse("expected one other method of $feedHost to build a post's name, found ${feedCallers.size}")
    val feedColumn = nameBlockIn(feedBlock, styleType, null, listOf(feed.build))

    val reelHost = reel.names.map { it.method.definingClass }.toSet().single()
    val entries = reel.entries.map { it.first }
    val reelCallers = classDefByOrNull(reelHost)!!.methods.filter { method ->
        entries.none { descriptor(it) == descriptor(method) } &&
            method.implementation?.instructions?.any { callsOneOf(it, entries) } == true
    }
    val reelBlock = reelCallers.singleOrNull()
        ?: refuse("expected one method of $reelHost to build its name from outside its name methods, found ${reelCallers.size}")
    return listOf(nameBlockIn(reelBlock, styleType, feedColumn.columnType, entries), feedColumn)
}

/**
 * Finds the column a group reel's author line puts the group's name and the line under it in (#490). A
 * group reel draws that line with a component of its own, the one whose static setup names
 * [GROUP_AUTHOR_COMPONENT] (588 `LX/0X81;`), and no post header, so the reel name hooks never see it. Its
 * render makes the picture's column of [columnType] first and the names' column last (588 `LX/02gk;` at
 * 642, style v97, its list holding the group's name and the row under it). A column a branch jumps
 * straight into, or whose style is read again after it's made, isn't taken.
 */
internal fun BytecodePatchContext.findGroupNameBlock(columnType: String, styleType: String): Place {
    val owners = methodsNaming(GROUP_AUTHOR_COMPONENT) { it.name == "<clinit>" }.map { it.definingClass }.toSet()
    val owner = owners.singleOrNull()
        ?: refuse("expected one group reel author line (\"$GROUP_AUTHOR_COMPONENT\"), found ${owners.size}")
    val renders = classDefByOrNull(owner)!!.methods.filter { it.name == "render" && it.implementation != null }
    val render = renders.singleOrNull() ?: refuse("expected $owner to render once, found ${renders.size}")
    val where = "$owner->render"
    val code = render.implementation!!.instructions.toList()
    val columns = code.indices.filter { at ->
        isConstructorCall(code[at]) && containerStyle(code[at], styleType) != null &&
            ((code[at] as ReferenceInstruction).reference as MethodReference).definingClass == columnType
    }
    if (columns.size < 2) refuse("expected $where to make its picture's column and its names', found ${columns.size} $columnType")
    val at = columns.last()
    val style = containerStyle(code[at], styleType)!!
    val flow = ControlFlow.of(render)
    if (flow.normal.withIndex().any { (from, to) -> at in to && from != at - 1 }) refuse("a branch in $where jumps straight to its names' column")
    if (render.readsAfter(at, style).isNotEmpty()) refuse("$where reads its names' column style again after the column")
    return Place(render, at, style)
}

/**
 * The one column [method] makes from a [styleType] style and a list (of [type], when given), with a call to
 * one of [names] between making the list and making the column.
 */
private fun nameBlockIn(method: Method, styleType: String, type: String?, names: List<Method>): NameBlock {
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val columns = code.indices.filter { at ->
        isConstructorCall(code[at]) && containerStyle(code[at], styleType) != null &&
            (type == null || ((code[at] as ReferenceInstruction).reference as MethodReference).definingClass == type)
    }
    val at = columns.singleOrNull() ?: refuse("expected $where to make one column for its name, found ${columns.size}")
    val call = (code[at] as ReferenceInstruction).reference as MethodReference
    val style = containerStyle(code[at], styleType)!!
    val listArgument = argument(code[at], 1 + call.parameterTypes.map { it.toString() }.indexOf(LIST))
        ?: refuse("$where hands its column no list")

    // Back from the column to the list it was handed, through the moves on the way.
    var index = at
    var register = listArgument
    var made: Int? = null
    while (made == null) {
        val writer = method.writersReaching(index, register).singleOrNull()?.takeIf { it >= 0 }
            ?: refuse("expected one list to reach $where's column")
        val instruction = code[writer]
        when {
            instruction.opcode == Opcode.NEW_INSTANCE && ((instruction as ReferenceInstruction).reference as TypeReference).type == ARRAY_LIST ->
                made = writer
            instruction.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) -> {
                index = writer
                register = (instruction as TwoRegisterInstruction).registerB
            }
            else -> refuse("$where's column takes a list it didn't make")
        }
    }
    val list = (made + 1 until at).firstOrNull { index ->
        isConstructorCall(code[index]) && argument(code[index], 0) == register &&
            ((code[index] as ReferenceInstruction).reference as MethodReference).definingClass == ARRAY_LIST
    } ?: refuse("$where makes its column's list without its constructor")
    if ((list + 1 until at).none { callsOneOf(code[it], names) }) refuse("$where builds no name between its list and its column")

    val flow = ControlFlow.of(method)
    if (flow.normal.withIndex().any { (from, to) -> at in to && from != at - 1 }) refuse("a branch in $where jumps straight to its column")
    if (flow.normal.withIndex().any { (from, to) -> list + 1 in to && from != list }) refuse("a branch in $where skips its list's start")
    if (method.readsAfter(at, style).isNotEmpty()) refuse("$where reads its column's style again after the column")
    return NameBlock(Place(method, list, register), Place(method, at, style))
}

/**
 * Adds a helper to [host] that asks [ask] for a drawable and, given one, puts it on a [builderType]
 * text builder as its background.
 */
private fun BytecodePatchContext.addBoxHelper(host: String, name: String, builderType: String, style: BoxStyle, ask: String) {
    val owner = mutableClassDefBy(host)
    if (owner.methods.any { it.name == name }) return
    owner.methods.add(
        ImmutableMethod(
            host,
            name,
            listOf(ImmutableMethodParameter(builderType, null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-static { }, $ask
                    move-result-object v0
                    if-eqz v0, :facebook
                    sget-object v1, ${style.emptyStyle}
                    invoke-static { v0, v1 }, ${style.background}
                    move-result-object v0
                    invoke-virtual { p0, v0 }, ${style.setter}
                    :facebook
                    return-void
                """,
            )
        },
    )
}

/**
 * Adds a helper to [host] that takes a container's Litho style and hands it back with a background
 * made from [ask]'s drawable on top, or as it was when [ask] answers null. A missing style becomes
 * [BoxStyle.emptyStyle] under the background.
 */
private fun BytecodePatchContext.addStyleHelper(host: String, name: String, style: BoxStyle, ask: String) {
    val owner = mutableClassDefBy(host)
    if (owner.methods.any { it.name == name }) return
    val styleType = style.background.substringAfterLast(')')
    owner.methods.add(
        ImmutableMethod(
            host,
            name,
            listOf(ImmutableMethodParameter(styleType, null, null)),
            styleType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-static { }, $ask
                    move-result-object v0
                    if-eqz v0, :facebook
                    if-nez p0, :styled
                    sget-object p0, ${style.emptyStyle}
                    :styled
                    invoke-static { v0, p0 }, ${style.background}
                    move-result-object p0
                    :facebook
                    return-object p0
                """,
            )
        },
    )
}

/** Adds [code] after each of [places], the last of a method first so the earlier indices still hold. */
private fun BytecodePatchContext.addAfter(places: List<Place>, code: (Place) -> String) = addAfter(places.map { it to code(it) })

/**
 * Adds each hook's code right after its place, the last of a method first so the earlier indices still
 * hold. A way that jumps to the instruction after a place skips its hook, which only takes what the
 * place itself left.
 */
private fun BytecodePatchContext.addAfter(hooks: List<Pair<Place, String>>) {
    hooks.groupBy { it.first.method }.forEach { (method, at) ->
        val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        at.sortedByDescending { it.first.index }.forEach { (place, code) -> mutable.addInstructions(place.index + 1, code) }
    }
}

/** Adds [code] right before each of [places], the last of a method first so the earlier indices still hold. */
private fun BytecodePatchContext.addBefore(places: List<Place>, code: (Place) -> String) {
    places.groupBy { it.method }.forEach { (method, at) ->
        val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        at.sortedByDescending { it.index }.forEach { mutable.addInstructions(it.index, code(it)) }
    }
}

private fun call(helper: String, builderType: String, register: Int, host: String) =
    "invoke-static/range { v$register .. v$register }, $host->$helper($builderType)V"

/**
 * Boxes the stories row's names and an open story's title (#490). Right after a name in the row goes
 * into its text builder, a helper on that class asks [CAPTION_BACKDROP] for a box and puts it on the
 * builder as its background. Right before an open story's title is wrapped in its style or goes into its
 * container ([openStory] from [findOpenStoryNames]), a helper on the title's class puts the box on that
 * style.
 */
internal fun BytecodePatchContext.addStoryNameBoxes(text: FieldReference, tray: List<Place>, openStory: List<Place>) {
    val builderType = text.definingClass
    val style = findBoxStyle(builderType)
    val styleType = style.background.substringAfterLast(')')
    val trayHosts = tray.map { it.method.definingClass }.toSet()
    val titleHosts = openStory.map { it.method.definingClass }.toSet()
    (trayHosts + titleHosts).forEach { requireReachable(style, it) }
    val inBuild = classDefByOrNull(CAPTION_BACKDROP)?.methods?.any { it.name == "storyNamesInBuild" }
    if (inBuild != true) refuse("the extension has no $CAPTION_BACKDROP->storyNamesInBuild()")
    trayHosts.forEach { addBoxHelper(it, STORY_NAME_HELPER, builderType, style, FOR_STORY_NAME) }
    addAfter(tray) { call(STORY_NAME_HELPER, builderType, it.register, it.method.definingClass) }
    titleHosts.forEach { addStyleHelper(it, STORY_TITLE_HELPER, style, FOR_STORY_NAME) }
    addBefore(openStory) {
        """
            invoke-static/range { v${it.register} .. v${it.register} }, ${it.method.definingClass}->$STORY_TITLE_HELPER($styleType)$styleType
            move-result-object v${it.register}
        """
    }
    mutableClassDefBy(CAPTION_BACKDROP).methods.single { it.name == "storyNamesInBuild" }.returnEarly(true)
}

/**
 * Boxes a reel's name (#490): its author component's header props are marked as they're made, the
 * header's name methods say whose props they build from, and how the header is drawn ([look], from
 * [findHeaderLook]), as they start, and right after the name goes into its text builder a helper on the
 * header asks [CAPTION_BACKDROP] for a box: a reel's, or a feed video's drawn over the video (#533).
 * Right after each of [lines] ([findNameLines]) the name's line count goes through the extension too.
 */
internal fun BytecodePatchContext.addReelNameBoxes(
    text: FieldReference,
    reel: ReelNames,
    lines: List<Place> = emptyList(),
    look: FieldReference? = null,
) {
    val builderType = text.definingClass
    val style = findBoxStyle(builderType)
    val host = reel.names.map { it.method.definingClass }.toSet().single()
    requireReachable(style, host)
    val extension = classDefByOrNull(CAPTION_BACKDROP)?.methods?.map { it.name }?.toSet().orEmpty()
    listOf("reelNamesInBuild", "feedVideoNamesInBuild", "fullNamesInBuild").forEach { name ->
        if (name !in extension) refuse("the extension has no $CAPTION_BACKDROP->$name()")
    }
    // The header's own registers at its start are free, nothing having written them yet; the props and
    // the look go in the first two.
    reel.entries.forEach { (method, _) ->
        if (method.localRegisterCount() < 2) refuse("${method.definingClass}->${method.name} has no two registers of its own to name its props in")
    }
    addBoxHelper(host, REEL_NAME_HELPER, builderType, style, FOR_REEL_NAME)
    addAfter(reel.marks) { "invoke-static/range { v${it.register} .. v${it.register} }, $REEL_AUTHOR" }
    addAfter(
        reel.names.map { it to call(REEL_NAME_HELPER, builderType, it.register, host) } +
            lines.map { it to "invoke-static/range { v${it.register} .. v${it.register} }, $NAME_LINES\nmove-result v${it.register}" },
    )
    reel.entries.forEach { (method, props) ->
        val readsLook = look != null && !AccessFlags.STATIC.isSet(method.accessFlags)
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method).addInstructions(
            0,
            """
                move-object/from16 v0, p$props
                ${if (readsLook) "move-object/from16 v1, p0\niget-object v1, v1, ${look!!.definingClass}->${look.name}:${look.type}" else "const/4 v1, 0x0"}
                invoke-static { v0, v1 }, $HEADER
            """,
        )
    }
    switchOn("reelNamesInBuild")
    if (look != null) switchOn("feedVideoNamesInBuild")
    if (lines.isNotEmpty()) switchOn("fullNamesInBuild")
}

/** Makes [CAPTION_BACKDROP]'s [name] answer true, once. */
private fun BytecodePatchContext.switchOn(name: String) {
    val method = mutableClassDefBy(CAPTION_BACKDROP).methods.single { it.name == name }
    val code = method.implementation!!.instructions.toList()
    val on = code.size > 1 && (code[0] as? NarrowLiteralInstruction)?.narrowLiteral == 1 && code[1].opcode == Opcode.RETURN
    if (!on) method.returnEarly(true)
}

/**
 * Boxes a feed post's name and lets it take more lines (#533), the way [addReelNameBoxes] does a reel's:
 * the feed header's [FeedNames.build] says whose props it builds from and how the header is drawn as it
 * starts (a missing header standing in for no look), right after each name goes into its text builder a
 * helper on the header asks [CAPTION_BACKDROP] for a box (a feed video's, drawn over the video), and
 * right after each line count is read it goes through the extension. The traced lambda runs inside
 * [FeedNames.build], after that start.
 */
internal fun BytecodePatchContext.addFeedNameBoxes(text: FieldReference, feed: FeedNames) {
    val builderType = text.definingClass
    val style = findBoxStyle(builderType)
    val host = feed.build.definingClass
    requireReachable(style, host)
    val extension = classDefByOrNull(CAPTION_BACKDROP)?.methods?.map { it.name }?.toSet().orEmpty()
    listOf("feedVideoNamesInBuild", "fullNamesInBuild").forEach { name ->
        if (name !in extension) refuse("the extension has no $CAPTION_BACKDROP->$name()")
    }
    // As in the reel header, the build's own first two registers are free at its start.
    if (feed.build.localRegisterCount() < 2) refuse("$host->${feed.build.name} has no two registers of its own to name its props in")
    addBoxHelper(host, REEL_NAME_HELPER, builderType, style, FOR_REEL_NAME)
    addAfter(
        feed.names.map { it to call(REEL_NAME_HELPER, builderType, it.register, host) } +
            feed.lines.map { it to "invoke-static/range { v${it.register} .. v${it.register} }, $NAME_LINES\nmove-result v${it.register}" },
    )
    mutableClassDefBy(host).findMutableMethodOf(feed.build).addInstructions(
        0,
        """
            move-object/from16 v0, p${feed.props}
            move-object/from16 v1, p${feed.host}
            if-eqz v1, :named
            iget-object v1, v1, ${feed.look.definingClass}->${feed.look.name}:${feed.look.type}
            :named
            invoke-static { v0, v1 }, $HEADER
        """,
    )
    switchOn("feedVideoNamesInBuild")
    switchOn("fullNamesInBuild")
}

/**
 * Boxes a post header's name and the line under it as one (#490), for [blocks] from [findNameBlocks]:
 * right after the header makes the list for the column holding them, the extension hears a column has
 * started, so the name built next leaves its box to it, and right before the column is made a helper on
 * the header puts the box on the column's style. The name hooks of [addReelNameBoxes] and
 * [addFeedNameBoxes] tell the extension whose name it is, so the box follows the same two switches.
 */
internal fun BytecodePatchContext.addNameBlockBoxes(text: FieldReference, blocks: List<NameBlock>) {
    val style = findBoxStyle(text.definingClass)
    val styleType = style.background.substringAfterLast(')')
    val extension = classDefByOrNull(CAPTION_BACKDROP)?.methods?.map { it.name }?.toSet().orEmpty()
    listOf("nameBlock", "forNameBlock").forEach { name ->
        if (name !in extension) refuse("the extension has no $CAPTION_BACKDROP->$name()")
    }
    blocks.map { it.column.method.definingClass }.toSet().forEach { host ->
        requireReachable(style, host)
        addStyleHelper(host, NAME_BLOCK_HELPER, style, FOR_NAME_BLOCK)
    }
    blocks.forEach { block ->
        val host = block.column.method.definingClass
        val register = block.column.register
        val method = mutableClassDefBy(host).findMutableMethodOf(block.column.method)
        // The column comes after its list, so it's hooked first and the list's index still holds.
        method.addInstructions(
            block.column.index,
            """
                invoke-static/range { v$register .. v$register }, $host->$NAME_BLOCK_HELPER($styleType)$styleType
                move-result-object v$register
            """,
        )
        method.addInstructions(block.list.index + 1, "invoke-static { }, $NAME_BLOCK")
    }
}

/**
 * Boxes a group reel's name and the line under it as one (#490), for [block] from [findGroupNameBlock]:
 * right before the author line makes that column, a helper on its class puts [FOR_GROUP_NAME]'s box on
 * the column's style, so Dark box behind reel names covers group reels too.
 */
internal fun BytecodePatchContext.addGroupNameBox(text: FieldReference, block: Place) {
    val style = findBoxStyle(text.definingClass)
    val styleType = style.background.substringAfterLast(')')
    if (classDefByOrNull(CAPTION_BACKDROP)?.methods?.any { it.name == "forGroupName" } != true) {
        refuse("the extension has no $CAPTION_BACKDROP->forGroupName()")
    }
    val host = block.method.definingClass
    requireReachable(style, host)
    addStyleHelper(host, GROUP_NAME_HELPER, style, FOR_GROUP_NAME)
    addBefore(listOf(block)) {
        "invoke-static/range { v${it.register} .. v${it.register} }, $host->$GROUP_NAME_HELPER($styleType)$styleType\n" +
            "move-result-object v${it.register}"
    }
}
