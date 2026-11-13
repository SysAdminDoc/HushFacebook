/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.downloads.reel.SIDEBAR
import app.morphe.patches.facebook.downloads.reel.hookSidebarLayout
import app.morphe.patches.facebook.downloads.reel.isSidebarBuilder
import app.morphe.patches.facebook.downloads.reel.sidebarAssemblyIndex
import app.morphe.patches.facebook.downloads.reel.sidebarRender
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/*
 * Move the speaker above the three dots (#331, read from 585 and checked on a phone, 2026-11-01).
 *
 * What's on screen, from the S22's view tree. The side column (like, comment, share, save, download,
 * three dots) is one host, and the speaker under it is another, in the corner's own column (arm64
 * LX/0B7l;->render). Nothing in the corner can reach the side column, so the speaker has to be built
 * by the side column itself.
 *
 * - The side column's render (arm64 LX/0B7e;->A17) hands its buttons to the assembly call
 *   ([sidebarAssemblyIndex]). The assembly's render puts the list first, then a slot and the three
 *   dots, and takes any component in that list, so a speaker added last sits right above the dots.
 * - The side column doesn't have what the speaker's builder (arm64 LX/0CnO;->A00) needs. The corner
 *   does, in public fields of unique types: all of it but the render's context, which the assembly
 *   gets. So the corner gets two helpers: [OFFER_SPEAKER], called right after the reel's builder
 *   (arm64 LX/0B6s;->A0E) news it up, and [SIDE_SPEAKER], called by the side column.
 * - The two renders run in no fixed order, so whether the speaker moves has to be known before either
 *   runs. The reel's builder news up both, and the extension keeps a mark for the reel ([REEL_SIDE_COLUMN_COMING]
 *   when it fills the side column's reel field, [REEL_REMEMBER_CORNER] when it has the corner). A reel
 *   drawn any other way has no mark, so it keeps Facebook's speaker. No mark means no move, and that's
 *   the case that can't leave a reel with no speaker.
 * - The side column's render leaves early, with no assembly, when one boolean of the viewer's config
 *   says so (arm64 LX/0DhY;->BUh, its only return before the assembly). [OFFER_SPEAKER] asks the same
 *   thing and doesn't mark that reel. The older button layout (from 499 on arm64) has no assembly either,
 *   so the switch asks for the newer one, the way the Download button does (#316).
 * - 585's Android 9 build (minapi28, read 2026-11-07) splits the side column: its builder
 *   (LX/09f2;->A03) holds the assembly, and the component the reel's builder news up (LX/0B9q) holds
 *   the reel and reads the layout's flag in the render that calls the builder ([sidebarRender]).
 * - The corner and the video controls then drop theirs for a marked reel ([REEL_SPEAKER_STAYS]), and
 *   the builder is told to build the speaker while the side column asks ([REEL_FOOTER_LEAVES_SPEAKER]),
 *   since a server flag can leave it to the controls. Its other caller, the video settings row, isn't
 *   affected.
 * - [REEL_SPEAKER_STAYS] needs the reel next to what was built, so the hook borrows the call's own
 *   argument register in front of the reel, which nothing reads after the call. A build where something
 *   does is refused.
 */

internal const val REEL_FOOTER_LEAVES_SPEAKER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->footerLeavesSpeaker(Z)Z"
internal const val REEL_SIDE_COLUMN_COMING = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->sideColumnComing(Ljava/lang/Object;)V"
internal const val REEL_REMEMBER_CORNER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->rememberCorner(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val REEL_CORNER_FOR_SIDE = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->cornerForSide(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val REEL_SIDE_SPEAKER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->sideSpeaker(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val REEL_SPEAKER_STAYS = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->speakerStays(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val REEL_SPEAKER_LAYOUT = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->speakerLayout(Z)Z"
internal const val OFFER_SPEAKER = "hushfacebookOfferSpeaker"
internal const val SIDE_SPEAKER = "hushfacebookSideSpeaker"

private const val PATCH = "Clean up Reels"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.calledMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun CharSequence.isObjectType() = startsWith("L") || startsWith("[")

/**
 * The if-nez in the speaker builder [method] that answers null when the video controls are the ones
 * to build it: the last if-nez before it news up one of the sound [toggles], landing on a return.
 * From 587 (arm64 LX/0DnE;->A02, armv7 LX/0DnT;->A02) it lands on a const/4 of 0 moved straight into
 * the return, which answers null the same way. Null when the builder has no such branch.
 */
internal fun footerSpeakerCheck(method: Method, toggles: Set<String>): Int? {
    val code = method.code()
    val toggle = code.indexOfFirst {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type in toggles
    }
    val check = (toggle - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IF_NEZ } ?: return null
    val lands = branchTarget(code, check) ?: return null
    return check.takeIf { code.getOrNull(lands)?.opcode == Opcode.RETURN_OBJECT || returnsNullAt(code, lands) }
}

/**
 * The reel the speaker's builder and the side column both know: what the [builder] takes, the
 * [sidebar]'s fields hold and the [assembly] gets, past the session, which all three have too.
 */
internal fun reelItemType(builder: MethodReference, sidebar: ClassDef, assembly: MethodReference): String? {
    val held = sidebar.fields.map { it.type.toString() }
    val taken = assembly.parameterTypes.map(CharSequence::toString)
    return builder.parameterTypes.map(CharSequence::toString)
        .filter { it != SESSION && it in held && it in taken }.distinct().singleOrNull()
}

/**
 * The config boolean that makes the side column's [render] leave before its assembly at
 * [assemblyIndex]: the last no-argument Z asked of an interface among the builder's [parameters] in
 * front of the render's first return. Null when nothing returns before the assembly.
 */
internal fun earlyExitAsk(render: Method, assemblyIndex: Int, parameters: Collection<String>): MethodReference? {
    val code = render.code()
    val leaves = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
    if (leaves !in 0 until assemblyIndex) return null
    return code.take(leaves).mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@mapNotNull null
        instruction.calledMethod()?.takeIf {
            it.returnType == "Z" && it.parameterTypes.isEmpty() && it.definingClass in parameters
        }
    }.lastOrNull() ?: throw PatchException(
        "$PATCH: ${render.definingClass}->${render.name} returns at $leaves, before its assembly, on no config boolean",
    )
}

/**
 * What makes the side column leave before its assembly, wherever it's asked. On 585 the [builder] is
 * its own [render] and leaves in front of its assembly at [assemblyIndex]. On the Android 9 build the
 * builder never does, and the render (LX/0B9q;->A15) returns at 0165, before it calls the builder,
 * when LX/0DkQ;->BV1()Z says yes. A split build where both leave is refused.
 */
internal fun sideColumnExitAsk(builder: Method, render: Method, assemblyIndex: Int, parameters: Collection<String>): MethodReference? {
    val ownExit = earlyExitAsk(builder, assemblyIndex, parameters)
    if (render === builder) return ownExit
    if (ownExit != null) throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} and its render both leave early")
    val code = render.code()
    val callsBuilder = code.indexOfFirst { instruction -> instruction.calledMethod()?.let { isCalledBy(builder, it) } == true }
    if (callsBuilder < 0) throw PatchException("$PATCH: ${render.definingClass}->${render.name} doesn't call ${builder.definingClass}->${builder.name}")
    return earlyExitAsk(render, callsBuilder, parameters)
}

/** The one instruction of [code] that [picks] picks, or a refusal naming [what]. */
private fun oneOf(code: List<Instruction>, what: String, picks: (Instruction) -> Boolean): Int =
    code.indices.filter { picks(code[it]) }.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what, found ${code.count(picks)}")

/**
 * The register in front of the reel among the arguments of the build at [site]: the slot its hook
 * passes what was built in. Refused unless the call is a range, the reel is in it with a register in
 * front of it, nothing reads that register after the call, and what was built lands in neither.
 */
private fun reelSlot(site: SpeakerSite, item: String): Int {
    val call = site.method.code()[site.build.call]
    val range = call as? RegisterRangeInstruction
        ?: throw PatchException("$PATCH: ${site.owner}->${site.method.name} doesn't call the speaker's builder with a range")
    val where = "${site.owner}->${site.method.name}"
    val index = call.calledMethod()!!.parameterTypes.map(CharSequence::toString).indexOf(item)
    val offset = if (call.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    if (index < 0 || index + offset < 1) throw PatchException("$PATCH: $where passes the speaker's builder no reel behind another argument")
    val slot = range.startRegister + offset + index - 1
    if (slot > 0xFF) throw PatchException("$PATCH: $where keeps the argument before the reel in v$slot")
    if (site.build.register == slot || site.build.register == slot + 1) {
        throw PatchException("$PATCH: $where keeps the built speaker in an argument register")
    }
    if (site.method.readsAfter(site.build.call + 1, slot).isNotEmpty()) {
        throw PatchException("$PATCH: $where reads v$slot after the speaker's builder")
    }
    return slot
}

/**
 * The one instance the speaker's [builder] is called on at [site], or null when it's static. From 586
 * it's an instance method of a stateless singleton (arm64 LX/0Cii;->A01 on LX/0Cii;->A00), and a static
 * call to it shifts every argument one register early. Refused unless [site] calls it virtually on that
 * instance and its class keeps no state of its own.
 */
internal fun builderInstance(site: SpeakerSite, owner: ClassDef, builder: Method): FieldReference? {
    val call = site.method.code()[site.build.call]
    val where = "${owner.type}->${builder.name}"
    if (AccessFlags.STATIC.isSet(builder.accessFlags)) {
        if (call.opcode != Opcode.INVOKE_STATIC_RANGE) throw PatchException("$PATCH: $where is static but not called so")
        return null
    }
    if (call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) throw PatchException("$PATCH: $where isn't called virtually")
    if (owner.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) }) throw PatchException("$PATCH: $where belongs to an object with state")
    val instance = owner.fields.singleOrNull { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == owner.type }
        ?: throw PatchException("$PATCH: $where has no one instance to be called on")
    val reads = site.method.code().any { instruction ->
        instruction.opcode == Opcode.SGET_OBJECT && ((instruction as ReferenceInstruction).reference as FieldReference).let {
            it.definingClass == owner.type && it.name == instance.name && it.type == owner.type
        }
    }
    if (!reads) throw PatchException("$PATCH: ${site.owner}->${site.method.name} doesn't call $where on its instance")
    return instance
}

/** [REEL_SPEAKER_STAYS] at [site], before its null check, with the reel from the call's own arguments. */
private fun hookSpeakerStays(site: SpeakerSite, slot: Int) {
    val built = site.build.register
    site.method.addInstructions(
        site.build.call + 2,
        """
            move-object/from16 v$slot, v$built
            invoke-static/range { v$slot .. v${slot + 1} }, $REEL_SPEAKER_STAYS
            move-result-object v$built
            check-cast v$built, ${site.build.type}
        """,
    )
}

/**
 * Puts the reel's speaker in its side column, right above the three dots, while the switch is on.
 * [footer] is where the corner builds it and [controls] where the video controls do. Everything is
 * read before anything is written, so a build this refuses is left as Facebook has it.
 */
internal fun BytecodePatchContext.moveReelSpeakerAboveMenu(footer: SpeakerSite, controls: SpeakerSite?) {
    val toggles = classDefByStrings(SOUND_TOGGLE_STRING, StringComparisonType.EQUALS)
        .map { it.type }.filterNot { it.startsWith(EXTENSION_PACKAGE) }.toSet()
    val builder = footer.method.code()[footer.build.call].calledMethod()!!
    val parameters = builder.parameterTypes.map(CharSequence::toString)
    val builderClass = mutableClassDefBy(builder.definingClass)
    val builderMethod = builderClass.methods.single { isCalledBy(it, builder) }
    val instance = builderInstance(footer, builderClass, builderMethod)
    val check = footerSpeakerCheck(builderMethod, toggles)
        ?: throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} has no check in front of the sound toggle")
    val answer = (builderMethod.code()[check] as OneRegisterInstruction).registerA

    val outsideExtension = { type: String -> !type.startsWith(EXTENSION_PACKAGE) }
    val (sidebarClass, render) = classDefByStrings(SIDEBAR, StringComparisonType.EQUALS)
        .filter { outsideExtension(it.type) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter { isSidebarBuilder(it, owner.methods) }.map { owner to it } }
        .singleOrNull() ?: throw PatchException("$PATCH: expected one render of \"$SIDEBAR\"")
    // The side column's component, which the reel's builder news up and whose fields hold the reel, is
    // the class rendering it: the builder's own on 585, the one handing it the scoped context on the
    // Android 9 build (LX/0B9q for LX/09f2;->A03). The button layout's flag is read in that render too.
    val (componentType, layoutRender) = sidebarRender(render, PATCH)
    val component = mutableClassDefBy(componentType)
    val renderCode = render.code()
    val assemblyIndex = sidebarAssemblyIndex(renderCode)
    val assembly = renderCode.getOrNull(assemblyIndex)
        ?: throw PatchException("$PATCH: ${sidebarClass.type}->${render.name} has no assembly call")
    if (assembly !is RegisterRangeInstruction) throw PatchException("$PATCH: the assembly isn't called with a range")
    val assemblyCall = assembly.calledMethod()!!
    val taken = assemblyCall.parameterTypes.map(CharSequence::toString)
    val item = reelItemType(builder, component, assemblyCall)
        ?: throw PatchException("$PATCH: the speaker's builder, the side column and its assembly share no one reel type")

    // All but the render's context comes from the corner's fields.
    val corner = mutableClassDefBy(footer.owner)
    val fields = parameters.map { type -> corner.fields.singleOrNull { it.type.toString() == type } }
    val context = parameters.indices.filter { fields[it] == null }.singleOrNull()?.let(parameters::get)
        ?: throw PatchException("$PATCH: the corner holds all or too few of what the speaker's builder takes")
    val itemField = corner.fields.singleOrNull { it.type.toString() == item }
        ?: throw PatchException("$PATCH: the corner doesn't hold the reel")
    val itemAt = taken.indexOf(item)
    val contextAt = taken.indexOf(context)
    val listAt = taken.indexOf(LIST)
    if (itemAt < 0 || contextAt < 0 || listAt < 0) throw PatchException("$PATCH: the assembly doesn't get the reel, the context and the button list")
    val passed = maxOf(itemAt, contextAt, listAt) + 1
    if (!taken.take(passed).all(CharSequence::isObjectType)) throw PatchException("$PATCH: the assembly takes a primitive in front of the button list")

    val asks = sideColumnExitAsk(render, layoutRender, assemblyIndex, parameters)
    val askField = asks?.let { ask -> corner.fields.singleOrNull { it.type.toString() == ask.definingClass } }
    if (asks != null && askField == null) throw PatchException("$PATCH: the corner doesn't hold ${asks.definingClass}")

    val (builderOwner, reelBuilder) = classDefByStrings(FOOTER_COMPONENT, StringComparisonType.EQUALS)
        .filter { outsideExtension(it.type) }.distinctBy { it.type }
        .flatMap { owner -> methodsHolding(owner, FOOTER_COMPONENT).map { owner.type to it } }
        .filter { (_, method) -> newInstanceTypes(method).containsAll(listOf(corner.type, componentType)) }
        .singleOrNull() ?: throw PatchException("$PATCH: expected one method of \"$FOOTER_COMPONENT\" to new up the corner and the side column")
    val reelBuilderCode = reelBuilder.code()
    val marked = oneOf(reelBuilderCode, "write of the side column's reel") { instruction ->
        instruction.opcode == Opcode.IPUT_OBJECT && ((instruction as ReferenceInstruction).reference as FieldReference).let {
            it.definingClass == componentType && it.type == item
        }
    }
    val made = oneOf(reelBuilderCode, "construction of the corner") { instruction ->
        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            instruction.calledMethod()?.let { it.definingClass == corner.type && it.name == "<init>" } == true
    }
    val madeRegister = (reelBuilderCode[made] as? RegisterRangeInstruction)?.startRegister
        ?: (reelBuilderCode[made] as FiveRegisterInstruction).registerC
    val markedRegister = (reelBuilderCode[marked] as TwoRegisterInstruction).registerA

    val footerSlot = reelSlot(footer, item)
    val controlsSlot = controls?.let { reelSlot(it, item) }

    // The older button layout never reaches the assembly. This one can still refuse, so it goes first.
    val layoutMethod = component.methods.single { isCalledBy(it, layoutRender) }
    hookSidebarLayout(layoutMethod, REEL_SPEAKER_LAYOUT)

    // Called as soon as the reel's builder has the corner: marks the reel unless its side column leaves early.
    val cornerType = corner.type
    val offer = ImmutableMethod(
        cornerType,
        OFFER_SPEAKER,
        listOf(ImmutableMethodParameter(cornerType, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        val ask = if (asks == null) "" else """
            iget-object v1, v2, $cornerType->${askField!!.name}:${asks.definingClass}
            if-eqz v1, :offer
            invoke-interface { v1 }, $asks
            move-result v1
            if-nez v1, :none
            :offer
        """
        addInstructions(
            0,
            """
                iget-object v0, v2, $cornerType->${itemField.name}:$item
                $ask
                invoke-static { v0, v2 }, $REEL_REMEMBER_CORNER
                :none
                return-void
            """,
        )
    }
    corner.methods.add(offer)

    // v1 up to the builder's last are its arguments, behind its instance when it has one. The side
    // column's are past the locals, so they're copied.
    val shift = if (instance == null) 0 else 1
    val last = parameters.size + shift
    val locals = last + 3
    val builtAt = last + 1
    val listCopy = last + 2
    val arguments = parameters.indices.joinToString("\n") { index ->
        val field = fields[index]
        val to = index + 1 + shift
        if (field != null) {
            "iget-object v$to, v0, $cornerType->${field.name}:${parameters[index]}"
        } else {
            "move-object/from16 v$to, v${locals + contextAt}\ncheck-cast v$to, ${parameters[index]}"
        }
    }
    val build = if (instance == null) {
        "invoke-static/range { v1 .. v$last }, $builder"
    } else {
        "sget-object v1, ${builder.definingClass}->${instance.name}:${builder.definingClass}\ninvoke-virtual/range { v1 .. v$last }, $builder"
    }
    val side = ImmutableMethod(
        cornerType,
        SIDE_SPEAKER,
        List(passed) { ImmutableMethodParameter(OBJECT, null, null) },
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(locals + passed),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                move-object/from16 v0, v${locals + itemAt}
                invoke-static { v0 }, $REEL_CORNER_FOR_SIDE
                move-result-object v0
                if-eqz v0, :none
                check-cast v0, $cornerType
                $arguments
                $build
                move-result-object v$builtAt
                invoke-static { v$builtAt }, $REEL_SIDE_SPEAKER
                move-result-object v$builtAt
                if-eqz v$builtAt, :none
                move-object/from16 v$listCopy, v${locals + listAt}
                check-cast v$listCopy, $LIST
                invoke-interface { v$listCopy, v$builtAt }, $LIST->add($OBJECT)Z
                :none
                return-void
            """,
        )
    }
    corner.methods.add(side)

    val reelBuilderMutable = mutableClassDefBy(builderOwner).methods.single { isCalledBy(it, reelBuilder) }
    // The later place first, so the earlier one's index still holds.
    val places = listOf(
        made + 1 to "invoke-static/range { v$madeRegister .. v$madeRegister }, $cornerType->$OFFER_SPEAKER($cornerType)V",
        marked + 1 to "invoke-static/range { v$markedRegister .. v$markedRegister }, $REEL_SIDE_COLUMN_COMING",
    ).sortedByDescending { it.first }
    places.forEach { (index, call) -> reelBuilderMutable.addInstructions(index, call) }

    // Last into the list, so it lands behind every other button, the Download one included.
    val sidebarBuilder = mutableClassDefBy(sidebarClass.type).methods.single { isCalledBy(it, render) }
    val assemblyNow = sidebarAssemblyIndex(sidebarBuilder.code())
    val start = (sidebarBuilder.code()[assemblyNow] as RegisterRangeInstruction).startRegister
    val descriptor = "$cornerType->$SIDE_SPEAKER(${OBJECT.repeat(passed)})V"
    sidebarBuilder.addInstructionsAtControlFlowLabel(
        assemblyNow,
        "invoke-static/range { v$start .. v${start + passed - 1} }, $descriptor",
    )

    builderMethod.addInstructions(
        check,
        """
            invoke-static/range { v$answer .. v$answer }, $REEL_FOOTER_LEAVES_SPEAKER
            move-result v$answer
        """,
    )
    hookSpeakerStays(footer, footerSlot)
    if (controls != null && controlsSlot != null) hookSpeakerStays(controls, controlsSlot)
}
