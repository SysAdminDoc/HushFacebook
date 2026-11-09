/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.taptoplay.tapToPlayPatch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.search.branchTarget
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Open feed videos on their own hooks (#161) on every Facebook build the bundle
 * declares (arm64 and armv7): one void method taking the tap beside the Warion class's
 * shouldOpenInChannelFeed tells the extension first,
 * the reels viewer's one entry tells it the origin it reads before its null check, and the data
 * controller's and the client-side ranking loader's fetch mores, one instance method
 * each holding its line, answer false before fetching when the extension says so. The Warion
 * fragment tells it what its arguments say as it's created and when it's destroyed, and the one
 * data source answering the loader's seed check answers yes when it says so. The reels list builder
 * hands it both reads that put a loading page last, the more-coming field and the pagination flag,
 * and takes its answers. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedVideoAloneFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun called(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
        ((instruction as ReferenceInstruction).reference as MethodReference).toString()

    @Test
    fun `each declared build's feed video tap and fetch more ask the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val warionOwners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.name == CHANNEL_FEED_CHECK }) return@forEach
                    dex.classes.filter { classDef ->
                        !classDef.type.startsWith(EXTENSION_PACKAGE) && classDef.methods.any { it.name == CHANNEL_FEED_CHECK }
                    }.mapTo(warionOwners) { ImmutableClassDef.of(it) }
                }
                val warions = warionOwners.distinctBy { it.type }
                    .flatMap { owner -> owner.methods.filter(::isWarionStep).map { owner to it } }
                assertEquals("$name: void methods taking the tap beside a $CHANNEL_FEED_CHECK", 1, warions.size)
                val fetchOwners = FixtureDex.classesHolding(bundle, FETCH_MORE)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val fetches = fetchOwners.flatMap { owner -> owner.methods.filter(::isFetchMore).map { owner to it } }
                assertEquals("$name: instance Z methods holding $FETCH_MORE", 1, fetches.size)
                val loaderOwners = FixtureDex.classesHolding(bundle, SEED_TAIL)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val loaders = loaderOwners.flatMap { owner -> owner.methods.filter(::isLoaderFetchMore).map { owner to it } }
                assertEquals("$name: instance Z methods holding $SEED_TAIL", 1, loaders.size)
                val entryOwners = FixtureDex.classesHolding(bundle, VIEWER_ENTRY)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val entries = entryOwners.flatMap { owner -> owner.methods.filter(::isViewerEntry).map { owner to it } }
                assertEquals("$name: void or boolean methods holding $VIEWER_ENTRY", 1, entries.size)
                val (warionOwner, warion) = warions.single()
                val (fetchOwner, fetch) = fetches.single()
                val (loaderOwner, loader) = loaders.single()
                val (entryOwner, entry) = entries.single()

                // The viewer's entry reads the origin from its params and checks it for null right away,
                // the read Facebook throws "Expected playerOriginOverride" on.
                val origin = originRead(entry)
                    ?: throw AssertionError("$name: the viewer's entry reads no $PLAYER_ORIGIN into a low register")
                val originField = (entry.code()[origin.index - 1] as ReferenceInstruction).reference as FieldReference
                assertTrue("$name: the origin comes from the entry's params",
                    originField.definingClass in entry.parameterTypes.map(CharSequence::toString))
                val nullCheck = entry.code()[origin.index]
                assertEquals("$name: the origin is null-checked next", Opcode.IF_EQZ, nullCheck.opcode)
                assertEquals("$name: on the register read", origin.origin, (nullCheck as OneRegisterInstruction).registerA)
                assertTrue("$name: the entry holds Facebook's null origin message", holdsString(entry, "Expected playerOriginOverride"))

                // The reels list's builder: one method among the string's holders reads whether more
                // are coming past its null check on the same params, and the extension can read their origin.
                val paramsType = originField.definingClass
                val params = FixtureDex.classes(bundle, setOf(paramsType)).getValue(paramsType)
                assertTrue("$name: $paramsType isn't public", AccessFlags.PUBLIC.isSet(params.accessFlags))
                val originDef = params.fields.single { it.name == originField.name && it.type == PLAYER_ORIGIN }
                assertTrue("$name: the origin field isn't public", AccessFlags.PUBLIC.isSet(originDef.accessFlags))
                // The Android 9 build names the string through a table, so the builder is found among
                // every method naming it, the table's callers included, as the patch finds it.
                val nameHolders = FixtureDex.classesHolding(bundle, LOADING_PAGE)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val holderTypes = nameHolders.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> holderTypes[call.definingClass]?.let { resolveStatic(it, call) } }
                val lists = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in holderTypes } }) {
                    !it.definingClass.startsWith(EXTENSION_PACKAGE) && isListBuilder(it, paramsType) && namesString(it, LOADING_PAGE, resolve)
                }.distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}${it.returnType}" }
                assertEquals("$name: list builders naming $LOADING_PAGE", 1, lists.size)
                val list = lists.single()
                val listOwner = FixtureDex.classes(bundle, setOf(list.definingClass)).getValue(list.definingClass)
                val gate = loadingPageRead(list, paramsType)!!
                val listCode = list.code()
                assertEquals("$name: the answer is a boolean field read", Opcode.IGET_BOOLEAN, listCode[gate.index - 1].opcode)
                val skip = listCode[gate.index]
                assertEquals("$name: and decides a branch straight away",
                    listOf(Opcode.IF_EQZ, gate.more), listOf(skip.opcode, (skip as OneRegisterInstruction).registerA))
                // Positive control: the way on when more are coming builds an item through the class's own
                // static helper, the loading page, before the place the branch skips to.
                val listAddresses = listCode.runningFold(0) { at, instruction -> at + instruction.codeUnits }
                val skipTo = listAddresses.indexOf(listAddresses[gate.index] + (skip as OffsetInstruction).codeOffset)
                assertTrue("$name: the branch doesn't skip forward", skipTo > gate.index + 1)
                assertTrue("$name: nothing builds the loading page when more are coming",
                    (gate.index + 1 until skipTo).any { at ->
                        listCode[at].opcode == Opcode.INVOKE_STATIC &&
                            ((listCode[at] as ReferenceInstruction).reference as MethodReference).definingClass == listOwner.type
                    })

                // The pagination page, the loading circle the S25 showed: a static method among its name's
                // holders answers it as an object, and the builder calls it only when the flag read right
                // before the branch says yes.
                val pageOwners = FixtureDex.classesHolding(bundle, PAGINATION_PAGE)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                fun buildsPage(call: MethodReference) = call.returnType.startsWith("L") && pageOwners.any { owner ->
                    owner.type == call.definingClass && owner.methods.any {
                        it.name == call.name &&
                            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                            holdsString(it, PAGINATION_PAGE)
                    }
                }
                val paging = paginationRead(list, paramsType, ::buildsPage)
                    ?: throw AssertionError("$name: the list builder doesn't read whether to add its pagination page")
                assertTrue("$name: the pagination read comes after the loading page's", paging.index > gate.index)
                // Android 9 has written over the params' low copy by the pagination read, never their parameter.
                val paramsArgument = list.parameterTypes.map(CharSequence::toString).indexOf(paramsType)
                    .takeIf { it >= 0 }?.let { list.parameterRegisterNumber(it) }
                assertTrue("$name: the pagination read hands over v${paging.params}, not the params",
                    paging.params == gate.params || paging.params == paramsArgument && paging.params > 15)
                val pageBranch = listCode[paging.index]
                assertTrue("$name: the flag decides a branch", pageBranch.opcode in setOf(Opcode.IF_NE, Opcode.IF_EQZ))
                // Positive control: past the branch, before where it skips to, the builder calls the page's builder.
                val pageSkipTo = listAddresses.indexOf(listAddresses[paging.index] + (pageBranch as OffsetInstruction).codeOffset)
                val pageCall = (paging.index + 1 until listCode.size).first { at -> buildsPaginationPageAt(listCode, at, ::buildsPage) }
                assertTrue("$name: the branch doesn't skip the page's builder", paging.index < pageCall && pageCall < pageSkipTo)
                if (listCode[pageCall].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) {
                    assertEquals("$name: a read with no page builder", null, paginationRead(list, paramsType) { false })
                } else {
                    // Android 9 inlines the page: its name goes straight into the call that builds it.
                    assertTrue("$name: the inlined page isn't named", listCode[pageCall].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO))
                }

                // Positive control: the check turns down the trace's other holders of the same class, and
                // a static or void copy of the fetch more wouldn't pass it.
                assertTrue("$name: the fetch more is static", !AccessFlags.STATIC.isSet(fetch.accessFlags))
                assertTrue("$name: another method of the class passes the Warion check",
                    warionOwner.methods.filterNot { it.sameAs(warion) }.none(::isWarionStep))

                // The Warion viewer: only the fragment's onFragmentCreate, among every method loading
                // the argument's name, reads it into a field the way the hook needs.
                val inWarionReads = FixtureDex.classesHolding(bundle, IN_WARION)
                    .flatMap { owner -> owner.methods.filter { warionRead(it) != null }.map { owner to it } }
                assertEquals("$name: methods reading $IN_WARION into a field", 1, inWarionReads.size)
                val (fragment, create) = inWarionReads.single()
                assertEquals("$name: the reader's class", WARION_FRAGMENT, fragment.type)
                assertEquals("$name: the reader", "onFragmentCreate", create.name)
                val read = warionRead(create)!!
                val destroy = fragment.methods.singleOrNull { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
                    ?: throw AssertionError("$name: $WARION_FRAGMENT has no onDestroy")

                // The data source: the loader's first boolean interface call, answered by one class.
                val check = seedCheck(loader)
                    ?: throw AssertionError("$name: the loader's fetch more asks no boolean interface method first")
                val sources = mutableListOf<Pair<ClassDef, Method>>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type.startsWith(EXTENSION_PACKAGE) || check.definingClass !in classDef.interfaces) continue
                        val copy = ImmutableClassDef.of(classDef)
                        copy.methods.filter { implementsSeedCheck(copy, it, check) }.mapTo(sources) { copy to it }
                    }
                }
                assertEquals("$name: classes answering $check", 1, sources.size)
                val (sourceOwner, source) = sources.single()

                val classes = (listOf(warionOwner, entryOwner, fetchOwner, loaderOwner, fragment, sourceOwner, listOwner, params,
                    ExtensionDex.classDef(FEED_VIDEO_ALONE)) + pageOwners + nameHolders).distinctBy { it.type }
                // Positive control: Clean up Reels refuses when the opening hooks it rests on aren't in.
                val unhooked = assertThrows(PatchException::class.java) { PatchContexts.of(classes).keepFeedVideosAlone() }
                assertTrue("$name: ${unhooked.message}", unhooked.message.orEmpty().contains("doesn't tell the extension"))
                val context = PatchContexts.of(classes)
                context.hookFeedVideoOpening()
                context.keepFeedVideosAlone()

                val listed = context.mutableClassDefBy(listOwner.type).methods.single { it.sameAs(list) }.code()
                // A params parameter past v15 is copied into a local for the call first.
                val copies = if (paging.params > 15) 1 else 0
                assertEquals("$name: instructions added to the list builder", listCode.size + 4 + copies, listed.size)
                // The loading page's two instructions push the pagination read's down by two.
                for ((read, at) in listOf(gate to gate.index, paging to paging.index + 2 + copies)) {
                    val ask = listed[at]
                    assertEquals("$name: the builder asks the extension at ${read.index}", MORE_REELS_COMING, called(ask))
                    ask as FiveRegisterInstruction
                    val handed = if (read.params <= 15) read.params else {
                        val copy = listed[at - 1]
                        assertEquals("$name: the params copied out of their parameter at ${read.index}",
                            listOf(Opcode.MOVE_OBJECT_FROM16, read.params), listOf(copy.opcode, (copy as TwoRegisterInstruction).registerB))
                        assertTrue("$name: the copy writes over the answer at ${read.index}", copy.registerA != read.more)
                        copy.registerA
                    }
                    assertEquals("$name: handed the answer, then the params at ${read.index}",
                        listOf(2, read.more, handed), listOf(ask.registerCount, ask.registerC, ask.registerD))
                    assertEquals("$name: its answer back where Facebook's was at ${read.index}",
                        listOf(Opcode.MOVE_RESULT, read.more),
                        listOf(listed[at + 1].opcode, (listed[at + 1] as OneRegisterInstruction).registerA))
                }
                val added = setOf(gate.index, gate.index + 1) + (paging.index + 2 until paging.index + 4 + copies)
                assertEquals("$name: the rest of the list builder is Facebook's",
                    listCode.map { it.opcode }, listed.filterIndexed { at, _ -> at !in added }.map { it.opcode })
                val stub = context.mutableClassDefBy(FEED_VIDEO_ALONE).methods.single { it.name == VIEWER_ORIGIN }.code()
                assertEquals("$name: the origin stub's shape",
                    listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), stub.take(3).map { it.opcode })
                assertEquals("$name: the stub casts to the params", paramsType,
                    ((stub[0] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: and reads the field the entry reads", originField.toString(),
                    ((stub[1] as ReferenceInstruction).reference as FieldReference).toString())

                val tapped = context.mutableClassDefBy(warionOwner.type).methods.single { it.sameAs(warion) }.code()
                assertEquals("$name: one instruction added to the Warion step", warion.code().size + 1, tapped.size)
                assertEquals("$name: the Warion step tells the extension first", FEED_VIDEO_TAPPED, called(tapped[0]))
                assertEquals("$name: the rest of the Warion step is Facebook's",
                    warion.code().map { it.opcode }, tapped.drop(1).map { it.opcode })

                val entered = context.mutableClassDefBy(entryOwner.type).methods.single { it.sameAs(entry) }.code()
                assertEquals("$name: one instruction added to the viewer's entry", entry.code().size + 1, entered.size)
                val entryCall = entered[origin.index]
                assertEquals("$name: the entry tells the extension where the video opens from", VIEWER_OPENING, called(entryCall))
                assertEquals("$name: with the origin it read", origin.origin, (entryCall as FiveRegisterInstruction).registerC)
                assertEquals("$name: Facebook's null check still follows", Opcode.IF_EQZ, entered[origin.index + 1].opcode)
                assertEquals("$name: the rest of the viewer's entry is Facebook's",
                    entry.code().map { it.opcode }, entered.filterIndexed { at, _ -> at != origin.index }.map { it.opcode })

                val answers = listOf(
                    Triple(fetchOwner to fetch, HOLDS_BACK, 0),
                    Triple(loaderOwner to loader, HOLDS_BACK, 0),
                    Triple(sourceOwner to source, SEED_CHECK, 1),
                )
                for ((at, hook, answer) in answers) {
                    val (owner, method) = at
                    val where = "$name: ${owner.type}->${method.name}"
                    val asked = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(method) }.code()
                    assertEquals("$where: six instructions added", method.code().size + 6, asked.size)
                    assertEquals("$where: shape of the early answer",
                        listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                            Opcode.CONST_4, Opcode.RETURN),
                        asked.take(6).map { it.opcode })
                    assertEquals("$where asks the extension", hook, called(asked[1]))
                    assertEquals("$where answers", answer, (asked[4] as NarrowLiteralInstruction).narrowLiteral)
                    assertEquals("$where: the rest is Facebook's",
                        method.code().map { it.opcode }, asked.drop(6).map { it.opcode })
                }
                // #421: the loader's vend hands the extension the data source and the page just ahead of
                // its branch on the seed check, and the branch still lands where it did.
                val vends = loaderOwner.methods.filter { it.returnType == "V" && holdsString(it, SEED_VEND) }
                assertEquals("$name: void methods beside the loader holding $SEED_VEND", 1, vends.size)
                val vend = vends.single()
                val vendCode = vend.code()
                val vendAt = vendAsk(vend, check) ?: throw AssertionError("$name: the vend doesn't branch on $check past a page")
                val landed = branchTarget(vendCode, vendAt.index)!!
                val vended = context.mutableClassDefBy(loaderOwner.type).methods.single { it.sameAs(vend) }.code()
                // Seven code units in front of a switch payload put it off its four-byte boundary, and
                // dexlib pads it with a nop (the A9 build's vend ends in a sparse-switch payload).
                assertEquals("$name: three instructions added to the vend, not counting alignment nops",
                    vendCode.count { it.opcode != Opcode.NOP } + 3, vended.count { it.opcode != Opcode.NOP })
                val (fromSource, fromPage, told421) = vended.subList(vendAt.index, vendAt.index + 3)
                assertEquals("$name: the vend's copies and call", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
                    listOf(fromSource.opcode, fromPage.opcode, told421.opcode))
                fromSource as TwoRegisterInstruction
                fromPage as TwoRegisterInstruction
                assertEquals("$name: copied from the data source, then the page", listOf(vendAt.source, vendAt.reels),
                    listOf(fromSource.registerB, fromPage.registerB))
                assertEquals("$name: the vend tells the extension", VENDING, called(told421))
                told421 as FiveRegisterInstruction
                assertEquals("$name: with both copies", listOf(2, fromSource.registerA, fromPage.registerA),
                    listOf(told421.registerCount, told421.registerC, told421.registerD))
                val branch = vended[vendAt.index + 3]
                assertEquals("$name: Facebook's branch follows", Opcode.IF_EQZ, branch.opcode)
                assertTrue("$name: the copies leave the answer alone", (branch as OneRegisterInstruction).registerA !in
                    setOf(fromSource.registerA, fromPage.registerA))
                assertEquals("$name: the branch lands where it did", landed + 3, branchTarget(vended, vendAt.index + 3))
                assertEquals("$name: the rest of the vend is Facebook's", vendCode.map { it.opcode }.filter { it != Opcode.NOP },
                    vended.filterIndexed { at, _ -> at !in vendAt.index until vendAt.index + 3 }.map { it.opcode }.filter { it != Opcode.NOP })

                // Positive control: three separate roads, so no hook stands in for another.
                assertEquals("$name: the loader, the data controller and the data source share a class",
                    3, setOf(loaderOwner.type, fetchOwner.type, sourceOwner.type).size)

                val patched = context.mutableClassDefBy(WARION_FRAGMENT)
                val opened = patched.methods.single { it.sameAs(create) }.code()
                assertEquals("$name: one instruction added to onFragmentCreate", create.code().size + 1, opened.size)
                val told = opened[read.index + 1]
                assertEquals("$name: onFragmentCreate tells the extension what it read", WARION_OPENING, called(told))
                told as FiveRegisterInstruction
                assertEquals("$name: the call's operands", listOf(read.fragment, read.value), listOf(told.registerC, told.registerD))
                assertEquals("$name: Facebook still stores what it read", Opcode.IPUT_BOOLEAN, opened[read.index + 2].opcode)
                assertEquals("$name: the rest of onFragmentCreate is Facebook's",
                    create.code().map { it.opcode }, opened.filterIndexed { at, _ -> at != read.index + 1 }.map { it.opcode })
                val closed = patched.methods.single { it.sameAs(destroy) }.code()
                assertEquals("$name: two instructions added to onDestroy", destroy.code().size + 2, closed.size)
                assertEquals("$name: onDestroy's shape", listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC),
                    closed.take(2).map { it.opcode })
                assertEquals("$name: onDestroy tells the extension first", WARION_CLOSED, called(closed[1]))
                assertEquals("$name: the rest of onDestroy is Facebook's",
                    destroy.code().map { it.opcode }, closed.drop(2).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `Tap to play and Clean up Reels both bring the feed video opening hooks`() {
        // #393: Tap to play patched without Clean up Reels held a tapped feed video whose player started late.
        assertTrue("Tap to play doesn't bring them", feedVideoOpeningPatch in tapToPlayPatch.dependencies)
        assertTrue("Clean up Reels doesn't bring them", feedVideoOpeningPatch in cleanUpReelsPatch.dependencies)
    }

    @Test
    fun `the extension has every hook`() {
        val stub = ExtensionDex.classDef(FEED_VIDEO_ALONE).methods.singleOrNull { it.name == VIEWER_ORIGIN }
        assertNotNull("the extension has no $VIEWER_ORIGIN stub", stub)
        assertEquals("the stub's shape", "$FEED_VIDEO_ALONE->$VIEWER_ORIGIN(Ljava/lang/Object;)Ljava/lang/Object;",
            "${stub!!.definingClass}->${stub.name}(${stub.parameterTypes.joinToString("")})${stub.returnType}")
        assertTrue("the stub isn't public static",
            AccessFlags.STATIC.isSet(stub.accessFlags) && AccessFlags.PUBLIC.isSet(stub.accessFlags))
        for (hook in listOf(FEED_VIDEO_TAPPED, VIEWER_OPENING, HOLDS_BACK, WARION_OPENING, WARION_CLOSED, SEED_CHECK, MORE_REELS_COMING,
            VENDING)) {
            val type = hook.substringBefore("->")
            val name = hook.substringAfter("->").substringBefore("(")
            val method = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no $hook", method)
            assertEquals("$name's shape", hook,
                "${method!!.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }
}
