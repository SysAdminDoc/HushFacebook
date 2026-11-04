/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code half of the clone support: each place Facebook's code spells out one of its own
 * provider authorities hands it to the extension, which answers the running app's, and nothing
 * that only looks like one is touched.
 */
class ClonedPackageLiteralsTest {
    private val facebook = "com.facebook.katana"
    private val dedup = "$facebook.ClientMessagePushDedupInfoProvider"
    private val authorities = setOf(dedup, "$facebook.provider.UserValuesProvider")

    private fun load(register: Int, literal: String): Instruction =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(literal))

    private fun method(type: String, registers: Int, vararg instructions: Instruction) = ImmutableMethod(
        type, "uris", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(registers, instructions.toList(), null, null),
    )

    private fun classDef(type: String, method: Method): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method))

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.literal() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    @Test
    fun anAuthorityOrAnAddressOnOneIsOwn() {
        assertEquals(dedup, dedup.ownAuthority(authorities))
        assertEquals(dedup, "content://$dedup/mutestatus".ownAuthority(authorities))
        assertEquals(dedup, "content://$dedup".ownAuthority(authorities))
        assertEquals(dedup, "content://$dedup?x=1".ownAuthority(authorities))
        // Another app's, a longer name, a class name, the package, and an authority not at the start.
        assertNull("content://com.facebook.orca.ClientMessagePushDedupInfoProvider/mutestatus".ownAuthority(authorities))
        assertNull("content://${dedup}2/mutestatus".ownAuthority(authorities))
        assertNull("$dedup.Impl".ownAuthority(authorities))
        assertNull(facebook.ownAuthority(authorities))
        assertNull("vnd.android.cursor.item/vnd.$dedup".ownAuthority(authorities))
    }

    /**
     * Facebook's code is routed, into the register each load wrote, a jumbo load and a register
     * above v15 included, and the extension's own classes are left alone.
     */
    @Test
    fun ownAuthoritiesInFacebooksCodeGoThroughTheExtension() {
        val uris = "LX/7b3;"
        val extension = AUTHORITY_CALL.substringBefore("->")
        val context = PatchContexts.of(
            listOf(
                classDef(uris, method(uris, 40,
                    load(0, "content://$dedup/mutestatus"),
                    ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 33, ImmutableStringReference(dedup)),
                    load(1, "content://com.facebook.orca.ClientMessagePushDedupInfoProvider/mutestatus"),
                    load(2, "$facebook.LoginActivity"),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
                classDef(extension, method(extension, 1, load(0, dedup), ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
        )

        assertEquals(2, context.routeOwnAuthorities(facebook, authorities))

        val routed = context.mutableClassDefBy(uris).methods.single().instructions()
        assertEquals(9, routed.size)
        for (index in listOf(0, 3)) {
            val register = (routed[index] as OneRegisterInstruction).registerA
            val call = routed[index + 1]
            assertEquals(AUTHORITY_CALL, (call as ReferenceInstruction).reference.toString())
            assertEquals(register to 1, (call as RegisterRangeInstruction).startRegister to call.registerCount)
            assertEquals(register, (routed[index + 2] as OneRegisterInstruction).registerA)
        }
        assertEquals(listOf("content://com.facebook.orca.ClientMessagePushDedupInfoProvider/mutestatus", "$facebook.LoginActivity"),
            routed.drop(6).mapNotNull { it.literal() })
        assertEquals("the extension's literal was routed", 2,
            context.mutableClassDefBy(extension).methods.single().instructions().size)
    }

    /**
     * An authority put together from BuildConstants' name goes through the extension in each shape
     * the routing looks for: the bare suffix, the suffix after a %s, and a content:// address with a
     * path, which a search by suffix missed (#216). One joined to another class's name stays.
     */
    @Test
    fun authoritiesBuiltFromBuildConstantsNameGoThroughTheExtension() {
        val suffix = ".provider.UserValuesProvider"
        fun built(type: String, literal: String, nameFrom: String = BUILD_CONSTANTS) = classDef(type, method(type, 3,
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                ImmutableMethodReference(nameFrom, "A04", emptyList(), "Ljava/lang/String;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            load(1, literal),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0,
                ImmutableMethodReference("LX/8b0;", "A00", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "Ljava/lang/String;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction10x(Opcode.RETURN_VOID)))
        val shapes = listOf("LX/8b1;" to suffix, "LX/8b2;" to "%s$suffix", "LX/8b3;" to "content://%s$suffix/values")
        val other = "LX/8b4;"
        val context = PatchContexts.of(
            shapes.map { (type, literal) -> built(type, literal) } + built(other, "content://%s$suffix/values", nameFrom = "LX/8b5;"),
        )

        assertEquals(3, context.routeBuiltAuthorities(facebook, authorities))
        for ((type, literal) in shapes) {
            assertEquals(literal, 8, context.mutableClassDefBy(type).methods.single().instructions().size)
        }
        assertEquals(6, context.mutableClassDefBy(other).methods.single().instructions().size)
    }

    /** The calls the patch writes are public static methods of the payload, moving names under Facebook's package. */
    @Test
    fun theExtensionDeclaresTheCall() {
        for (written in listOf(AUTHORITY_CALL, PACKAGE_CALL, AS_FACEBOOK_CALL)) {
            val type = written.substringBefore("->")
            val call = written.substringAfter("->")
            val declared = ExtensionDex.classDef(type).methods.filter {
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }.map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("the extension declares no public static $call: $declared", call in declared)
            assertEquals(AppCompatibilities.FACEBOOK_PACKAGE, ExtensionDex.stringConstant(type, "FACEBOOK"))
        }
    }

    private fun remoteViews(register: Int): Instruction =
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, register, ImmutableTypeReference("Landroid/widget/RemoteViews;"))

    private fun build(views: Int, packageName: Int, layout: Int): Instruction = ImmutableInstruction35c(
        Opcode.INVOKE_DIRECT, 3, views, packageName, layout, 0, 0,
        ImmutableMethodReference("Landroid/widget/RemoteViews;", "<init>", listOf("Ljava/lang/String;", "I"), "V"),
    )

    /**
     * A notification template's RemoteViews built from Facebook's package name spelled out takes the
     * running app's instead (#36), a name two views share included. A load anything else reads, such
     * as a comparison with another package, and the extension's own classes are left alone.
     */
    @Test
    fun aNotificationsViewsBuiltFromFacebooksNameGoThroughTheExtension() {
        val template = "Lcom/example/NotificationTemplate;"
        val extension = PACKAGE_CALL.substringBefore("->")
        val equals = ImmutableMethodReference("Ljava/lang/String;", "equals", listOf("Ljava/lang/Object;"), "Z")
        val context = PatchContexts.of(
            listOf(
                classDef(template, method(template, 20,
                    load(1, facebook),
                    ImmutableInstruction31i(Opcode.CONST, 0, 0x7f0e0042),
                    remoteViews(2),
                    build(2, 1, 0),
                    load(3, facebook),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 4, 0, 0, 0, equals),
                    load(5, facebook),
                    remoteViews(6),
                    build(6, 5, 0),
                    remoteViews(7),
                    build(7, 5, 0),
                    load(8, "com.facebook.orca"),
                    remoteViews(9),
                    build(9, 8, 0),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
                classDef(extension, method(extension, 3,
                    load(1, facebook), remoteViews(2), build(2, 1, 0), ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
        )

        assertEquals(2, context.routeRemoteViewsPackage(facebook))

        val routed = context.mutableClassDefBy(template).methods.single().instructions()
        assertEquals(19, routed.size)
        for ((index, register) in listOf(0 to 1, 8 to 5)) {
            assertEquals(facebook, routed[index].literal())
            val call = routed[index + 1]
            assertEquals(PACKAGE_CALL, (call as ReferenceInstruction).reference.toString())
            assertEquals(register to 1, (call as RegisterRangeInstruction).startRegister to call.registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, routed[index + 2].opcode)
            assertEquals(register, (routed[index + 2] as OneRegisterInstruction).registerA)
        }
        assertEquals("the compared load was routed", Opcode.INVOKE_VIRTUAL, routed[7].opcode)
        assertEquals("the extension's literal was routed", 4,
            context.mutableClassDefBy(extension).methods.single().instructions().size)
    }

    private fun call(
        definingClass: String, name: String, parameters: List<String>, returns: String, vararg registers: Int,
        opcode: Opcode = Opcode.INVOKE_VIRTUAL,
    ) = ImmutableInstruction35c(
        opcode, registers.size, registers[0], registers.getOrElse(1) { 0 }, registers.getOrElse(2) { 0 },
        registers.getOrElse(3) { 0 }, registers.getOrElse(4) { 0 },
        ImmutableMethodReference(definingClass, name, parameters, returns),
    )

    private fun setPackage(intent: Int, packageName: Int) =
        call("Landroid/content/Intent;", "setPackage", listOf("Ljava/lang/String;"), "Landroid/content/Intent;", intent, packageName)

    private fun launchIntent(manager: Int, packageName: Int) = call("Landroid/content/pm/PackageManager;",
        "getLaunchIntentForPackage", listOf("Ljava/lang/String;"), "Landroid/content/Intent;", manager, packageName)

    private fun applicationInfo(manager: Int, packageName: Int, flags: Int) = call("Landroid/content/pm/PackageManager;",
        "getApplicationInfo", listOf("Ljava/lang/String;", "I"), "Landroid/content/pm/ApplicationInfo;", manager, packageName, flags)

    private fun packageInfo(manager: Int, packageName: Int, flags: Int) = call("Landroid/content/pm/PackageManager;",
        "getPackageInfo", listOf("Ljava/lang/String;", "I"), "Landroid/content/pm/PackageInfo;", manager, packageName, flags)

    private fun applicationIcon(manager: Int, packageName: Int) = call("Landroid/content/pm/PackageManager;",
        "getApplicationIcon", listOf("Ljava/lang/String;"), "Landroid/graphics/drawable/Drawable;", manager, packageName)

    private fun setClassName(intent: Int, packageName: Int, className: Int) = call("Landroid/content/Intent;",
        "setClassName", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "Landroid/content/Intent;", intent, packageName, className)

    private fun providerCall(resolver: Int, uri: Int, name: Int, argument: Int, extras: Int) = call(
        "Landroid/content/ContentResolver;", "call",
        listOf("Landroid/net/Uri;", "Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/Bundle;"), "Landroid/os/Bundle;",
        resolver, uri, name, argument, extras,
    )

    private fun resourceId(resources: Int, name: Int, type: Int, packageName: Int) = call("Landroid/content/res/Resources;",
        "getIdentifier", listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"), "I",
        resources, name, type, packageName)

    private fun redexResourceId(resources: Int, name: Int, type: Int, packageName: Int) = call(
        "Lcom/facebook/resources/compat/RedexResourcesCompat;", "getIdentifier",
        listOf("Landroid/content/res/Resources;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"), "I",
        resources, name, type, packageName, opcode = Opcode.INVOKE_STATIC,
    )

    /**
     * #36 leftovers: where Facebook names itself to Android, a clone names itself. An intent's
     * setPackage anywhere, and in Firebase's notification code the launch intent too, take the
     * running package. Elsewhere a launch of Facebook, a read of its version and a comparison with
     * its name mean Meta's app and keep the literal.
     */
    @Test
    fun facebooksNameWhereItMeansThisAppGoesThroughTheExtension() {
        val firebase = FIREBASE_MESSAGING
        val chats = "Lcom/example/ChatNotification;"
        val equals = ImmutableMethodReference("Ljava/lang/String;", "equals", listOf("Ljava/lang/Object;"), "Z")
        val context = PatchContexts.of(
            listOf(
                classDef(firebase, method(firebase, 10,
                    load(0, facebook), setPackage(1, 0),
                    load(0, facebook), launchIntent(2, 0),
                    load(0, facebook), applicationInfo(2, 0, 3),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
                classDef(chats, method(chats, 10,
                    load(0, facebook), setPackage(1, 0),
                    load(0, facebook), launchIntent(2, 0),
                    load(0, facebook), packageInfo(2, 0, 3),
                    load(0, facebook), ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 4, 0, 0, 0, 0, equals),
                    load(0, facebook), setPackage(0, 1),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
        )

        assertEquals(4, context.routeOwnPackageCalls(facebook))

        val inFirebase = context.mutableClassDefBy(firebase).methods.single().instructions()
        assertEquals(13, inFirebase.size)
        for (index in listOf(0, 4, 8)) {
            assertEquals(facebook, inFirebase[index].literal())
            assertEquals(PACKAGE_CALL, (inFirebase[index + 1] as ReferenceInstruction).reference.toString())
            assertEquals(Opcode.MOVE_RESULT_OBJECT, inFirebase[index + 2].opcode)
        }
        val inChats = context.mutableClassDefBy(chats).methods.single().instructions()
        assertEquals("only the setPackage load was routed", 13, inChats.size)
        assertEquals(PACKAGE_CALL, (inChats[1] as ReferenceInstruction).reference.toString())
        assertEquals(facebook, inChats[4].literal())
        assertEquals("a launch of Facebook outside Firebase", Opcode.INVOKE_VIRTUAL, inChats[5].opcode)
        assertEquals("an intent whose own register holds the name", Opcode.INVOKE_VIRTUAL, inChats[11].opcode)
    }

    /**
     * The other places the bare name can only mean the running app take a clone's own: an
     * ApplicationInfo or icon of it anywhere, the package of an explicit intent to one of its
     * activities, the argument of a provider call (Xiaomi's launcher badge), and a resource lookup,
     * whose load goes over whole since Morphe renames a clone's resource table and whatever else
     * reads that name builds on the same table. Its version, a launch of it outside Firebase, an
     * intent whose class is the literal, a load one call reads twice and a load that's also compared
     * keep the literal.
     */
    @Test
    fun whereTheNameCanOnlyMeanThisAppAndItsResourcesItGoesThroughTheExtension() {
        val owner = "Lcom/example/AppInfoCore;"
        val append = ImmutableMethodReference(
            "Ljava/lang/StringBuilder;", "append", listOf("Ljava/lang/String;"), "Ljava/lang/StringBuilder;")
        val equals = ImmutableMethodReference("Ljava/lang/String;", "equals", listOf("Ljava/lang/Object;"), "Z")
        val context = PatchContexts.of(
            listOf(
                classDef(owner, method(owner, 10,
                    load(0, facebook), applicationInfo(2, 0, 3),
                    load(0, facebook), applicationIcon(2, 0),
                    load(0, facebook), setClassName(1, 0, 4),
                    load(0, facebook), setClassName(1, 4, 0),
                    load(0, facebook), providerCall(5, 6, 7, 0, 8),
                    load(0, facebook), resourceId(5, 6, 7, 0),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 9, 0, 0, 0, 0, append),
                    load(0, facebook), redexResourceId(5, 6, 7, 0),
                    load(0, facebook), packageInfo(2, 0, 3),
                    load(0, facebook), launchIntent(2, 0),
                    load(0, facebook), setClassName(1, 0, 0),
                    load(0, facebook), applicationInfo(2, 0, 3),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 4, 0, 0, 0, 0, equals),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
        )

        assertEquals(6, context.routeOwnPackageCalls(facebook))

        val routed = context.mutableClassDefBy(owner).methods.single().instructions()
        assertEquals(37, routed.size)
        val handedOver = routed.indices.filter { routed[it].literal() == facebook }
            .associateWith { (routed[it + 1] as? ReferenceInstruction)?.reference?.toString() == PACKAGE_CALL }
        assertEquals(
            listOf(true, true, true, false, true, true, true, false, false, false, false),
            handedOver.values.toList(),
        )
        for (at in handedOver.filterValues { it }.keys) {
            val call = routed[at + 1] as RegisterRangeInstruction
            assertEquals(0 to 1, call.startRegister to call.registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, routed[at + 2].opcode)
            assertEquals(0, (routed[at + 2] as OneRegisterInstruction).registerA)
        }
    }

    /** A call whose arguments sit above v15 comes as a range, and is read by the same slots. */
    @Test
    fun aRangeCallThatNamesThisAppIsReadLikeAnyOther() {
        val owner = "Lcom/example/ProxyAuthLink;"
        val setClassName = ImmutableMethodReference("Landroid/content/Intent;", "setClassName",
            listOf("Ljava/lang/String;", "Ljava/lang/String;"), "Landroid/content/Intent;")
        val context = PatchContexts.of(
            listOf(
                classDef(owner, method(owner, 20,
                    load(16, facebook), ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 15, 3, setClassName),
                    load(16, facebook), ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 16, 3, setClassName),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
        )

        assertEquals(1, context.routeOwnPackageCalls(facebook))

        val routed = context.mutableClassDefBy(owner).methods.single().instructions()
        assertEquals(7, routed.size)
        assertEquals(PACKAGE_CALL, (routed[1] as ReferenceInstruction).reference.toString())
        assertEquals(16 to 1, (routed[1] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(facebook, routed[4].literal())
        assertEquals("the name as the intent itself", Opcode.INVOKE_VIRTUAL_RANGE, routed[5].opcode)
    }

    /**
     * A switch case that lands right after a load would pass the call that goes there, so that load
     * keeps the literal, the same as one a branch lands after. The case is read from the payload, not
     * the switch's jump to it.
     */
    @Test
    fun aLoadASwitchCaseLandsRightAfterKeepsTheLiteral() {
        val owner = "Lcom/example/BadgeSwitch;"
        // Addresses: switch 0, load 3, setPackage 5, load 8, setPackage 10, return 13, payload 14.
        val context = PatchContexts.of(
            listOf(
                classDef(owner, method(owner, 10,
                    ImmutableInstruction31t(Opcode.PACKED_SWITCH, 9, 14),
                    load(0, facebook), setPackage(1, 0),
                    load(0, facebook), setPackage(1, 0),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 5))))),
            ),
        )

        assertEquals(1, context.routeOwnPackageCalls(facebook))

        val routed = context.mutableClassDefBy(owner).methods.single().instructions()
        assertEquals(facebook, routed[1].literal())
        assertEquals("the case's own setPackage keeps the literal", Opcode.INVOKE_VIRTUAL, routed[2].opcode)
        assertEquals(facebook, routed[3].literal())
        assertEquals(PACKAGE_CALL, (routed[4] as ReferenceInstruction).reference.toString())
    }

    /**
     * #113: where Facebook looks the running package up among Meta's apps (a method that also names
     * Messenger's debug build), the package it reads goes through the extension right after it's
     * moved into its register. A method that doesn't name the debug build is left alone.
     */
    @Test
    fun theRunningPackageInAMetaAppLookupGoesThroughTheExtension() {
        val lookup = "Lcom/example/BackupPreferences;"
        val elsewhere = "Lcom/example/Elsewhere;"
        val getPackageName = ImmutableMethodReference("Landroid/content/Context;", "getPackageName", emptyList(), "Ljava/lang/String;")
        fun read(context: Int) = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, context, 0, 0, 0, 0, getPackageName)
        fun moved(register: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register)
        val context = PatchContexts.of(
            listOf(
                classDef(lookup, method(lookup, 10,
                    read(1), moved(7),
                    load(0, "com.facebook.orca"),
                    load(0, MESSENGER_DEBUG),
                    ImmutableInstruction10x(Opcode.RETURN_VOID))),
                classDef(elsewhere, method(elsewhere, 10,
                    read(1), moved(7), load(0, "com.facebook.orca"), ImmutableInstruction10x(Opcode.RETURN_VOID))),
            ),
        )

        assertEquals(1, context.routeMetaAppLookups())

        val routed = context.mutableClassDefBy(lookup).methods.single().instructions()
        assertEquals(7, routed.size)
        val call = routed[2]
        assertEquals(AS_FACEBOOK_CALL, (call as ReferenceInstruction).reference.toString())
        assertEquals(7 to 1, (call as RegisterRangeInstruction).startRegister to call.registerCount)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, routed[3].opcode)
        assertEquals(7, (routed[3] as OneRegisterInstruction).registerA)
        assertEquals("a method without Messenger's debug name", 4,
            context.mutableClassDefBy(elsewhere).methods.single().instructions().size)
    }

    /**
     * The in-app browser's renderer connection answers getPackageName() with the literal Redex left
     * where context.getPackageName() was, so that load goes through the extension. A table among
     * Meta's apps, a getPackageName() that never asks Android, and another method answering the same
     * literal keep it.
     */
    @Test
    fun aGetPackageNameThatMeantTheRunningAppGoesThroughTheExtension() {
        val renderers = "Lcom/example/RendererConnection;"
        val table = "Lcom/example/MetaApps;"
        val constant = "Lcom/example/ConstantName;"
        val other = "Lcom/example/OtherName;"
        val getPackageName = ImmutableMethodReference("Landroid/content/Context;", "getPackageName", emptyList(), "Ljava/lang/String;")
        fun answer(type: String, name: String, vararg instructions: Instruction) = ImmutableMethod(
            type, name, emptyList(), "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(3, instructions.toList(), null, null),
        )
        fun ask() = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, getPackageName)
        fun answered(register: Int) = ImmutableInstruction11x(Opcode.RETURN_OBJECT, register)
        val context = PatchContexts.of(
            listOf(
                classDef(renderers, answer(renderers, "getPackageName", load(1, facebook), ask(), answered(1))),
                classDef(table, answer(table, "getPackageName",
                    ask(), load(1, "com.facebook.lite"), answered(1), load(1, facebook), answered(1))),
                classDef(constant, answer(constant, "getPackageName", load(1, facebook), answered(1))),
                classDef(other, answer(other, "packageOf", load(1, facebook), ask(), answered(1))),
            ),
        )

        assertEquals(1, context.routeOwnPackageNameAnswers(facebook))

        val routed = context.mutableClassDefBy(renderers).methods.single().instructions()
        assertEquals(5, routed.size)
        assertEquals(facebook, routed[0].literal())
        assertEquals(PACKAGE_CALL, (routed[1] as ReferenceInstruction).reference.toString())
        assertEquals(1 to 1, (routed[1] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals(1, (routed[2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN_OBJECT, routed[4].opcode)
        assertEquals("Meta's app table", 5, context.mutableClassDefBy(table).methods.single().instructions().size)
        assertEquals("never asks Android", 2, context.mutableClassDefBy(constant).methods.single().instructions().size)
        assertEquals("another name", 3, context.mutableClassDefBy(other).methods.single().instructions().size)
    }
}
