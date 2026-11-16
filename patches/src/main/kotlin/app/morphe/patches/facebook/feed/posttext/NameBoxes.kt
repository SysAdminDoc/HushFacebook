/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.posttext

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
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

private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val OBJECT = "Ljava/lang/Object;"
internal const val FOR_STORY_NAME = "$CAPTION_BACKDROP->forStoryName()$DRAWABLE"
internal const val FOR_REEL_NAME = "$CAPTION_BACKDROP->forReelName()$DRAWABLE"
internal const val REEL_AUTHOR = "$CAPTION_BACKDROP->reelAuthor($OBJECT)V"
internal const val HEADER = "$CAPTION_BACKDROP->header($OBJECT)V"

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

private fun isVirtualCall(instruction: Instruction) =
    instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE

private fun isConstructorCall(instruction: Instruction) =
    (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
        ((instruction as ReferenceInstruction).reference as MethodReference).name == "<init>"

/** The register [instruction] hands its call in [position] (0 is the receiver of a virtual call), or null. */
private fun argument(instruction: Instruction, position: Int): Int? = when (instruction) {
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
 */
internal fun BytecodePatchContext.findTrayNames(): TrayNames {
    val trays = methodsWithString(TRAY_NAME_KEY).filter { loadsString(it, TRAY_TITLE_KEY) }
    val tray = trays.singleOrNull()
        ?: refuse("expected one method drawing the stories row (\"$TRAY_NAME_KEY\", \"$TRAY_TITLE_KEY\"), found ${trays.size}")
    val where = "${tray.definingClass}->${tray.name}"
    val code = tray.implementation!!.instructions.toList()
    val keys = code.indices.filter { index ->
        val instruction = code[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (!isVirtualCall(instruction) || reference == null || reference.returnType != "V" ||
            reference.parameterTypes.map { it.toString() } != listOf(STRING)
        ) return@filter false
        val key = argument(instruction, 1) ?: return@filter false
        val writers = tray.writersReaching(index, key)
        writers.isNotEmpty() && writers.all { it >= 0 && loads(code[it], TRAY_NAME_KEY) }
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
    val made = makers.flatMap(::madeBy).toSet()
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
    val headers = methodsWithString(POST_HEADER_COMPONENT).filter { it.name == "<init>" }.map { it.definingClass }.toSet()
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
private fun BytecodePatchContext.addAfter(places: List<Place>, code: (Place) -> String) {
    places.groupBy { it.method }.forEach { (method, at) ->
        val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        at.sortedByDescending { it.index }.forEach { mutable.addInstructions(it.index + 1, code(it)) }
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
 * header's name methods say whose props they build from as they start, and right after the name goes
 * into its text builder a helper on the header asks [CAPTION_BACKDROP] for a box.
 */
internal fun BytecodePatchContext.addReelNameBoxes(text: FieldReference, reel: ReelNames) {
    val builderType = text.definingClass
    val style = findBoxStyle(builderType)
    val host = reel.names.map { it.method.definingClass }.toSet().single()
    requireReachable(style, host)
    val inBuild = classDefByOrNull(CAPTION_BACKDROP)?.methods?.any { it.name == "reelNamesInBuild" }
    if (inBuild != true) refuse("the extension has no $CAPTION_BACKDROP->reelNamesInBuild()")
    addBoxHelper(host, REEL_NAME_HELPER, builderType, style, FOR_REEL_NAME)
    addAfter(reel.marks) { "invoke-static/range { v${it.register} .. v${it.register} }, $REEL_AUTHOR" }
    addAfter(reel.names) { call(REEL_NAME_HELPER, builderType, it.register, host) }
    reel.entries.forEach { (method, props) ->
        mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
            .addInstructions(0, "invoke-static/range { p$props .. p$props }, $HEADER")
    }
    mutableClassDefBy(CAPTION_BACKDROP).methods.single { it.name == "reelNamesInBuild" }.returnEarly(true)
}
