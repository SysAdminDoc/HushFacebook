/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.ads.affiliate.writesRegister
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.appicons.logoDressSite
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.util.findFreeRegister
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Hide the Messenger button, its count and the Facebook logo in the home top bar (#181). Read from
 * 583, 2026-10-17, arm64 and armv7; the obfuscated names here are for reviewers, the code never
 * writes one down.
 *
 * - The home top bar is WordmarkNavigationBar (a kept class). Its builder method (arm64 A0J, armv7
 *   A0I) holds the kept trace strings "WordmarkNavigationBar#setupMessengerGlyphView" and
 *   "#createWordmarkView".
 * - After the setupMessengerGlyphView string the builder makes the Messenger button: a FrameLayout
 *   subclass (arm64 LX/4Qi;, armv7 LX/4R7;) it keeps in a field of the bar and, further down, adds to
 *   the bar with ViewGroup.addView(View), the view loaded by the iget-object just before the call.
 *   The button hook goes right after that call, on the register the iget-object loaded.
 * - The button class has one (I)V instance method (arm64 F32) and one (FbUserSession, I, Z)V
 *   instance method (arm64 F33), both of which set the unread count, and a kept
 *   "badgableGlyphView" string. The count hooks go first in each and replace the int.
 * - The logo is dressed by the same site the Unlock app icons patch uses ([logoDressSite]): the hook
 *   goes right after the stock wordmark load, with the logo register that holds the touch listener.
 * - Behind a MobileConfig flag the bar skips that Messenger block and shows the newer bar instead
 *   (the S22's account, 2026-10-17): NavigationBar (a kept class) makes every button from a spec
 *   (arm64 and armv7 LX/28u;) in its one instance method that takes a spec and calls setId, setTag
 *   and setOnLongClickListener (A0B). It makes the button's glyph first (LX/2BK;, the one
 *   new-instance there built with just a Context), then reads the spec's name, the String field it
 *   hands the button's click listener (LX/2Cd;) with the bar and the spec: "messenger" for the
 *   Messenger button. The hook goes right after the glyph is made, with the glyph and that name, so
 *   it runs before the glyph's first count. 584 (2026-10-22, spec LX/3GO;, method A0A, listener
 *   LX/3Gp;) inlines the content description helper (583's static NavigationBar->A06) into the same
 *   method, so a second String field of the spec is read there too, the label (A0E); it goes to
 *   setContentDescription and never to a constructor, so the name is still the one field handed
 *   to the listener (A0F on both).
 * - That glyph class is shared by every button of the newer bar. Its count setters are the
 *   `(I)V` and `(FbUserSession, I, Z)V` methods of the one interface it implements that declares
 *   both (arm64 LX/DQv;, F32 and F33, the same interface the Messenger button class implements).
 *   Their hooks go first and get the glyph too, so only the Messenger one loses its count.
 * - Hide the Create (+) button (#257), read from 584 arm64, 2026-10-26. The classic bar's method
 *   holding the kept "WordmarkNavigationBar#setupCreateGlyphView" (A0D) makes the button, keeps it
 *   in a View field of the bar (A0d) and adds it further down with addView(View), the view loaded
 *   from that field by the iget-object just before the call. The hook goes right after that call.
 *   On the newer bar the Create spec is named "trep-composer-button", so the same [BAR_BUTTON_HOOK]
 *   covers it.
 * - Hide the search button (#321), read from 585 arm64, 2026-10-30. The classic bar's method holding
 *   the kept "WordmarkNavigationBar#addSearchGlyphView" (A0F, the same one as the Messenger button
 *   on 585) adds the button right after the string: an iget-object of a bar field (A0J), then
 *   addView(View) of that view. The hook goes right after that call. The newer bar's search button
 *   isn't made from a spec: its config method (A0E) makes a glyph of the same class as the spec
 *   buttons' (LX/02Gs;, also the classic bar's search glyph) and keeps it in the bar's one field of
 *   that class (A08), the only write to it. The hook goes right after that iput-object, before the
 *   glyph is added to the bar. That class also draws Marketplace's and other screens' top bars, which
 *   the first 0.20.0 walk caught losing their search too (2026-11-01), so the hook also gets the bar's
 *   one String field (A0B), the name of its screen, written before the config method runs. The classic
 *   bar holds Home's newer bar and names it "NewsFeedFragment" in both calls, a literal that doesn't
 *   change with the language.
 *
 * A missing anchor refuses the whole Tab bar at the bottom patch, like every other anchor in it.
 */

internal const val TOP_BAR_BUTTONS = "$EXTENSION_PACKAGE/navigation/TopBarButtons;"
internal const val HIDE_MESSENGER_BUTTON_HOOK = "$TOP_BAR_BUTTONS->messengerButton(Landroid/view/View;)V"
internal const val HIDE_MESSENGER_COUNT_HOOK = "$TOP_BAR_BUTTONS->messengerCount(I)I"
internal const val HIDE_FACEBOOK_LOGO_HOOK = "$TOP_BAR_BUTTONS->facebookLogo(Landroid/view/View;)V"
internal const val BAR_BUTTON_HOOK = "$TOP_BAR_BUTTONS->barButton(Landroid/view/View;Ljava/lang/String;)V"
internal const val BAR_BUTTON_COUNT_HOOK = "$TOP_BAR_BUTTONS->barButtonCount(Landroid/view/View;I)I"
internal const val HIDE_CREATE_BUTTON_HOOK = "$TOP_BAR_BUTTONS->createButton(Landroid/view/View;)V"
internal const val HIDE_SEARCH_BUTTON_HOOK = "$TOP_BAR_BUTTONS->searchButton(Landroid/view/View;)V"
internal const val BAR_SEARCH_BUTTON_HOOK = "$TOP_BAR_BUTTONS->barSearchButton(Landroid/view/View;Ljava/lang/String;)V"
internal const val HOME_BAR_SCREEN = "NewsFeedFragment"

internal const val SETUP_MESSENGER_GLYPH_VIEW = "WordmarkNavigationBar#setupMessengerGlyphView"
internal const val SETUP_CREATE_GLYPH_VIEW = "WordmarkNavigationBar#setupCreateGlyphView"
internal const val ADD_SEARCH_GLYPH_VIEW = "WordmarkNavigationBar#addSearchGlyphView"
internal const val BADGABLE_GLYPH_VIEW = "badgableGlyphView"
internal const val NAVIGATION_BAR = "Lcom/facebook/navigation/navbar/NavigationBar;"
private const val FRAME_LAYOUT_INIT = "Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V"
private const val ADD_VIEW = "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V"
private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"
private val SPEC_BUTTON_CALLS = setOf(
    "Landroid/view/View;->setId(I)V",
    "Landroid/view/View;->setTag(Ljava/lang/Object;)V",
    "Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V",
)
private val COUNT_SHAPES = listOf(listOf("I"), listOf(USER_SESSION, "I", "Z"))

private const val TOP_BAR = "Tab bar at the bottom"

private fun refuse(detail: String): Nothing = throw PatchException("$TOP_BAR: $detail")

/** The Messenger button's class, and where the bar adds it: the addView call's index and the register holding the button. */
internal class MessengerButtonSite(val buttonType: String, val addView: Int, val register: Int)

/** The bar's builder methods holding [string]. */
internal fun buildersHolding(bar: ClassDef, string: String): List<Method> = bar.methods.filter { holdsString(it, string) }

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Instruction.signature(): String? = (reference() as? MethodReference)?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
}

/**
 * Where [builder] (the bar's builder) adds the Messenger button. After the load of the
 * setupMessengerGlyphView string it makes one object whose constructor is a FrameLayout's, then
 * adds a field of that type to the bar with addView(View), the view loaded by the instruction right
 * before the call. Refuses unless that finds exactly one.
 */
internal fun messengerButtonSite(builder: Method): MessengerButtonSite {
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val marks = code.indices.filter { (code[it].reference() as? StringReference)?.string == SETUP_MESSENGER_GLYPH_VIEW }
    val mark = marks.singleOrNull() ?: refuse("expected one \"$SETUP_MESSENGER_GLYPH_VIEW\" in $where, found ${marks.size}")
    val types = (mark until code.size - 1).filter { index ->
        code[index].opcode == Opcode.NEW_INSTANCE && code[index + 1].opcode == Opcode.INVOKE_DIRECT &&
            code[index + 1].signature() == FRAME_LAYOUT_INIT
    }.map { ((code[it].reference() as TypeReference).type) }.distinct()
    val buttonType = types.singleOrNull()
        ?: refuse("expected one FrameLayout subclass made after \"$SETUP_MESSENGER_GLYPH_VIEW\" in $where, found $types")
    val adds = (mark + 1 until code.size).filter { index ->
        if (code[index].opcode != Opcode.INVOKE_VIRTUAL || code[index].signature() != ADD_VIEW) return@filter false
        val load = code[index - 1]
        val field = load.reference() as? FieldReference
        load.opcode == Opcode.IGET_OBJECT && field != null && field.type == buttonType &&
            field.definingClass == builder.definingClass &&
            (code[index] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerD == (load as OneRegisterInstruction).registerA }
    }
    val add = adds.singleOrNull() ?: refuse("expected one addView of the $buttonType field in $where, found ${adds.size}")
    return MessengerButtonSite(buttonType, add, (code[add - 1] as OneRegisterInstruction).registerA)
}

/** Where the bar adds a button: the bar's field holding it, the addView call's index and the register holding the button. */
internal class AddedButtonSite(val field: FieldReference, val addView: Int, val register: Int)

/**
 * Where [builder] (the bar's method holding setupCreateGlyphView) adds the Create button. After the
 * string it puts objects in fields of the bar; the one of those fields whose iget-object comes right
 * before an addView(View) of the view it loaded is the button. Refuses unless that finds exactly one.
 */
internal fun createButtonSite(builder: Method): AddedButtonSite {
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val marks = code.indices.filter { (code[it].reference() as? StringReference)?.string == SETUP_CREATE_GLYPH_VIEW }
    val mark = marks.singleOrNull() ?: refuse("expected one \"$SETUP_CREATE_GLYPH_VIEW\" in $where, found ${marks.size}")
    val kept = (mark + 1 until code.size).filter { code[it].opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { code[it].reference() as? FieldReference }
        .filter { it.definingClass == builder.definingClass }
        .map { it.smali() }.toSet()
    val adds = (mark + 1 until code.size).filter { index ->
        if (code[index].opcode != Opcode.INVOKE_VIRTUAL || code[index].signature() != ADD_VIEW) return@filter false
        val load = code[index - 1]
        val field = load.reference() as? FieldReference
        load.opcode == Opcode.IGET_OBJECT && field != null && field.smali() in kept &&
            (code[index] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerD == (load as OneRegisterInstruction).registerA }
    }
    val add = adds.singleOrNull() ?: refuse("expected one addView of a bar field kept after \"$SETUP_CREATE_GLYPH_VIEW\" in $where, found ${adds.size}")
    return AddedButtonSite(code[add - 1].reference() as FieldReference, add, (code[add - 1] as OneRegisterInstruction).registerA)
}

/**
 * Where [builder] (the bar's method holding addSearchGlyphView) adds the search button: the first
 * call after the string's trace call is an addView(View) of the view the iget-object right before
 * it loaded from a field of the bar. Refuses unless the string is there once and that call follows.
 */
internal fun searchButtonSite(builder: Method): AddedButtonSite {
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val marks = code.indices.filter { (code[it].reference() as? StringReference)?.string == ADD_SEARCH_GLYPH_VIEW }
    val mark = marks.singleOrNull() ?: refuse("expected one \"$ADD_SEARCH_GLYPH_VIEW\" in $where, found ${marks.size}")
    // The string goes to the trace call, so the add is the call after that one.
    val calls = (mark + 1 until code.size).filter { code[it].reference() is MethodReference }
    val add = calls.getOrNull(1)
    val load = add?.let { code[it - 1] }
    val field = load?.reference() as? FieldReference
    if (add == null || load == null || field == null || code[add].opcode != Opcode.INVOKE_VIRTUAL || code[add].signature() != ADD_VIEW ||
        load.opcode != Opcode.IGET_OBJECT || field.definingClass != builder.definingClass ||
        (code[add] as FiveRegisterInstruction).let { it.registerCount != 2 || it.registerD != (load as OneRegisterInstruction).registerA }
    ) {
        refuse("expected the bar to add a field of its own with addView right after \"$ADD_SEARCH_GLYPH_VIEW\" in $where")
    }
    return AddedButtonSite(field, add, (load as OneRegisterInstruction).registerA)
}

/**
 * Where the newer bar keeps its search glyph: the method, the iput-object's index, the register
 * holding the glyph and the bar's field naming its screen.
 */
internal class SearchGlyphSite(val method: Method, val kept: Int, val register: Int, val screenField: FieldReference)

/**
 * The newer bar's search glyph: the one write to [bar]'s one instance field of [glyphType] (the
 * class [specButtonSite] found for the spec buttons), an iput-object of a glyph made right before
 * it with just a Context. Refuses unless the field and the write are each found once, and unless
 * the bar has one String field to read its screen from.
 */
internal fun searchGlyphSite(bar: ClassDef, glyphType: String): SearchGlyphSite {
    val fields = bar.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == glyphType }
    val field = fields.singleOrNull() ?: refuse("expected one $glyphType field in ${bar.type}, found ${fields.size}")
    val writes = bar.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.filter { index ->
            val written = code[index].reference() as? FieldReference
            code[index].opcode == Opcode.IPUT_OBJECT && written != null && written.definingClass == bar.type &&
                written.name == field.name && written.type == glyphType
        }.map { Triple(method, code, it) }
    }
    val (method, code, kept) = writes.singleOrNull()
        ?: refuse("expected one write to ${bar.type}->${field.name} in ${bar.type}, found ${writes.size}")
    val glyph = (code[kept] as TwoRegisterInstruction).registerA
    val init = code.getOrNull(kept - 1)?.reference() as? MethodReference
    val made = kept >= 2 && code[kept - 2].opcode == Opcode.NEW_INSTANCE &&
        (code[kept - 2].reference() as TypeReference).type == glyphType &&
        (code[kept - 2] as OneRegisterInstruction).registerA == glyph &&
        code[kept - 1].opcode == Opcode.INVOKE_DIRECT && init != null && init.name == "<init>" &&
        init.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) &&
        (code[kept - 1] as FiveRegisterInstruction).registerC == glyph
    if (!made) refuse("the write to ${bar.type}->${field.name} in ${method.name} doesn't follow a glyph made with just a Context")
    val names = bar.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == STRING }
    val screen = names.singleOrNull() ?: refuse("expected one String field in ${bar.type} to name its screen, found ${names.size}")
    return SearchGlyphSite(method, kept, glyph, screen)
}

/**
 * How many of [wordmark]'s calls name the newer bar inside it [HOME_BAR_SCREEN]. Refuses unless
 * there's at least one, each passes that literal, and the method it calls writes the bar's screen
 * field before it runs the method holding the search glyph. Without all of that the extension
 * couldn't tell Home's bar from Marketplace's.
 */
internal fun homeBarScreenCalls(wordmark: ClassDef, bar: ClassDef, search: SearchGlyphSite): Int {
    val calls = wordmark.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.filter { index ->
            val called = code[index].reference() as? MethodReference
            called != null && called.definingClass == NAVIGATION_BAR && called.parameterTypes.size == 4 &&
                called.parameterTypes.first().toString() == "Landroid/app/Activity;" && called.parameterTypes.last().toString() == STRING
        }.map { Triple(method, code, it) }
    }
    if (calls.isEmpty()) refuse("${wordmark.type} no longer hands $NAVIGATION_BAR a screen name")
    calls.forEach { (method, code, index) ->
        val where = "${wordmark.type}->${method.name}"
        val call = code[index] as? FiveRegisterInstruction ?: refuse("expected a plain call to the newer bar in $where")
        val named = (maxOf(0, index - 4) until index).any { before ->
            code[before].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
                (code[before] as OneRegisterInstruction).registerA == call.registerG &&
                (code[before].reference() as StringReference).string == HOME_BAR_SCREEN
        }
        if (!named) refuse("expected \"$HOME_BAR_SCREEN\" right before the call to the newer bar in $where")
        val entryRef = code[index].reference() as MethodReference
        val entry = bar.methods.singleOrNull {
            it.name == entryRef.name && it.parameterTypes.map(CharSequence::toString) == entryRef.parameterTypes.map(CharSequence::toString)
        } ?: refuse("${bar.type} has no ${entryRef.name} for $where to call")
        val entryCode = entry.implementation?.instructions?.toList() ?: refuse("${bar.type}->${entry.name} has no code")
        val written = entryCode.indexOfFirst { insn ->
            val field = insn.reference() as? FieldReference
            insn.opcode == Opcode.IPUT_OBJECT && field != null && field.definingClass == bar.type && field.name == search.screenField.name
        }
        val built = entryCode.indexOfFirst { insn ->
            val target = insn.reference() as? MethodReference
            target != null && target.definingClass == bar.type && target.name == search.method.name &&
                target.parameterTypes.map(CharSequence::toString) == search.method.parameterTypes.map(CharSequence::toString)
        }
        if (written < 0 || built < 0 || written > built) {
            refuse("${bar.type}->${entry.name} doesn't name the bar before it builds (write at $written, build at $built)")
        }
    }
    return calls.size
}

/** The register of an instance method's int parameter at [at], counted from the parameters. */
internal class CountMethod(val name: String, val parameters: List<String>, val intRegister: Int)

/**
 * The two methods of the button class that set its count: the one `(I)V` and the one
 * `(FbUserSession, int, boolean)V` instance method. Refuses unless the class holds the kept
 * badgableGlyphView string and has exactly one of each.
 */
internal fun countMethods(button: ClassDef): List<CountMethod> {
    if (button.methods.none { holdsString(it, BADGABLE_GLYPH_VIEW) }) refuse("${button.type} no longer holds \"$BADGABLE_GLYPH_VIEW\"")
    val instance = button.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
    return COUNT_SHAPES.map { shape ->
        val found = instance.filter { it.parameterTypes.map(CharSequence::toString) == shape }
        val method = found.singleOrNull() ?: refuse("expected one (${shape.joinToString("")})V instance method in ${button.type}, found ${found.size}")
        // p0 is the button; the int is parameter number [shape.indexOf("I")] after it.
        CountMethod(method.name, shape, 1 + shape.indexOf("I"))
    }
}

/**
 * Where the newer bar makes a button from its spec: the method, the glyph's class, the index of the
 * glyph's constructor call and the register holding the glyph, and the spec's name field.
 */
internal class SpecButtonSite(
    val method: Method,
    val glyphType: String,
    val made: Int,
    val glyph: Int,
    val nameField: FieldReference,
)

private fun FieldReference.smali() = "$definingClass->$name:$type"

/**
 * Whether the String read into a register at [read] of [code] is handed to a constructor taking the
 * bar and [spec], as the button's click listener is: the first instructions after the read, up to
 * a branch, a way out or another write to the register, call such an `<init>` with the register.
 */
private fun handsToListener(code: List<Instruction>, read: Int, spec: String): Boolean {
    val register = (code[read] as OneRegisterInstruction).registerA
    for (instruction in code.subList(read + 1, code.size)) {
        val init = instruction.reference() as? MethodReference
        if ((instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            init?.name == "<init>" && init.parameterTypes.map(CharSequence::toString).containsAll(listOf(NAVIGATION_BAR, spec))
        ) {
            val handed = when (instruction) {
                is RegisterRangeInstruction -> instruction.startRegister until instruction.startRegister + instruction.registerCount
                is FiveRegisterInstruction ->
                    listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG)
                        .take(instruction.registerCount)
                else -> emptyList()
            }
            if (register in handed) return true
        }
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue() || writesRegister(instruction, register)) return false
    }
    return false
}

/**
 * The newer bar's spec-to-button method in [bar]: its one instance method that takes one spec and
 * calls setId, setTag and setOnLongClickListener. In it, the one object made with just a Context is
 * the glyph, and the one String field read off the spec and handed to the click listener
 * ([handsToListener]) is its name. Refuses unless each is found exactly once.
 */
internal fun specButtonSite(bar: ClassDef): SpecButtonSite {
    val makers = bar.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType != "V" && method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.mapNotNull { it.signature() }?.toSet()?.containsAll(SPEC_BUTTON_CALLS) == true
    }
    val method = makers.singleOrNull()
        ?: refuse("expected one method of ${bar.type} that makes a button from a spec, found ${makers.size}")
    val where = "${method.definingClass}->${method.name}"
    val spec = method.parameterTypes.single().toString()
    val code = method.implementation!!.instructions.toList()
    val made = code.indices.filter { index ->
        if (index == 0 || code[index].opcode != Opcode.INVOKE_DIRECT || code[index - 1].opcode != Opcode.NEW_INSTANCE) return@filter false
        val init = code[index].reference() as? MethodReference ?: return@filter false
        init.name == "<init>" && init.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) &&
            init.definingClass == (code[index - 1].reference() as TypeReference).type &&
            (code[index] as FiveRegisterInstruction).registerC == (code[index - 1] as OneRegisterInstruction).registerA
    }
    val at = made.singleOrNull() ?: refuse("expected one object made with just a Context in $where, found ${made.size}")
    // The spec is the last register: p1 of an instance method with one object parameter.
    val specRegister = method.implementation!!.registerCount - 1
    val names = code.indices.filter { index ->
        val instruction = code[index]
        val field = instruction.reference() as? FieldReference
        instruction.opcode == Opcode.IGET_OBJECT && field != null && field.definingClass == spec && field.type == STRING &&
            (instruction as TwoRegisterInstruction).registerB == specRegister && handsToListener(code, index, spec)
    }.map { code[it].reference() as FieldReference }.distinctBy { it.smali() }
    val name = names.singleOrNull()
        ?: refuse("expected one String field of $spec read in $where and handed to its click listener, found ${names.map { it.smali() }}")
    return SpecButtonSite(method, (code[at - 1].reference() as TypeReference).type, at, (code[at] as FiveRegisterInstruction).registerC, name)
}

/**
 * The glyph class's two count setters: the `(I)V` and `(FbUserSession, int, boolean)V` methods of
 * the one interface among [interfaces] (the ones [glyph] implements) that declares one of each.
 * Refuses unless that interface is found once and [glyph] implements both.
 */
internal fun glyphCountMethods(glyph: ClassDef, interfaces: List<ClassDef>): List<CountMethod> {
    fun ClassDef.declared(shape: List<String>) = methods.filter {
        AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == shape
    }
    val badges = interfaces.filter { face -> face.type in glyph.interfaces && COUNT_SHAPES.all { face.declared(it).size == 1 } }
    val badge = badges.singleOrNull()
        ?: refuse("expected one interface of ${glyph.type} declaring the two count setters, found ${badges.map { it.type }}")
    return COUNT_SHAPES.map { shape ->
        val name = badge.declared(shape).single().name
        if (glyph.methods.none { it.name == name && it.parameterTypes.map(CharSequence::toString) == shape && it.implementation != null }) {
            refuse("${glyph.type} doesn't implement ${badge.type}->$name(${shape.joinToString("")})V")
        }
        CountMethod(name, shape, 1 + shape.indexOf("I"))
    }
}

/** Hands the new glyph and its spec's name over right after the glyph is made. */
internal fun MutableMethod.markSpecButtonAfter(site: SpecButtonSite) {
    val at = site.made + 1
    val name = findFreeRegister(at, site.glyph)
    if (maxOf(site.glyph, name, implementation!!.registerCount - 1) > 15) {
        refuse("the glyph (v${site.glyph}), the free v$name or the spec is past v15 in ${site.method.definingClass}->${site.method.name}")
    }
    addInstructions(
        at,
        """
            iget-object v$name, p1, ${site.nameField.smali()}
            invoke-static { v${site.glyph}, v$name }, $BAR_BUTTON_HOOK
        """,
    )
}

/** Sends the count and the glyph through the extension first thing. */
internal fun MutableMethod.gateGlyphCountFirst(register: Int) {
    addInstructions(
        0,
        """
            invoke-static { p0, p$register }, $BAR_BUTTON_COUNT_HOOK
            move-result p$register
        """,
    )
}

/** Puts the Messenger button's hook after the addView call. */
internal fun MutableMethod.hideMessengerButtonAfter(site: MessengerButtonSite) {
    addInstructions(site.addView + 1, "invoke-static/range { v${site.register} .. v${site.register} }, $HIDE_MESSENGER_BUTTON_HOOK")
}

/** Puts the Create button's hook after the addView call. */
internal fun MutableMethod.hideCreateButtonAfter(site: AddedButtonSite) {
    addInstructions(site.addView + 1, "invoke-static/range { v${site.register} .. v${site.register} }, $HIDE_CREATE_BUTTON_HOOK")
}

/** Puts the search button's hook after the classic bar's addView call. */
internal fun MutableMethod.hideSearchButtonAfter(site: AddedButtonSite) {
    addInstructions(site.addView + 1, "invoke-static/range { v${site.register} .. v${site.register} }, $HIDE_SEARCH_BUTTON_HOOK")
}

/** Puts the search button's hook after the newer bar keeps its search glyph, with the name of the bar's screen. */
internal fun MutableMethod.hideSearchGlyphAfter(site: SearchGlyphSite) {
    val at = site.kept + 1
    val screen = findFreeRegister(at, site.register)
    if (maxOf(site.register, screen, implementation!!.registerCount - 1) > 15) {
        refuse("the glyph (v${site.register}), the free v$screen or the bar is past v15 in ${site.method.definingClass}->${site.method.name}")
    }
    addInstructions(
        at,
        """
            iget-object v$screen, p0, ${site.screenField.smali()}
            invoke-static { v${site.register}, v$screen }, $BAR_SEARCH_BUTTON_HOOK
        """,
    )
}

/** Sends the count through the extension first thing, so a hidden count reaches Facebook as 0. */
internal fun MutableMethod.hideCountFirst(register: Int) {
    addInstructions(
        0,
        """
            invoke-static/range { p$register .. p$register }, $HIDE_MESSENGER_COUNT_HOOK
            move-result p$register
        """,
    )
}

/** Puts the logo's hook after the stock wordmark load, on the register that holds the logo. */
internal fun MutableMethod.hideLogoAfter(stockLoad: Int, logo: Int) {
    addInstructions(stockLoad + 1, "invoke-static/range { v$logo .. v$logo }, $HIDE_FACEBOOK_LOGO_HOOK")
}

/**
 * The three hooks of #181, carried by Tab bar at the bottom (its family holds the switches). The
 * settings dependency has to run first: the logo site is found after it has sent the logo's touch
 * listener through the settings entry.
 */
internal fun BytecodePatchContext.hideTopBarButtons() {
    run {
        val bar = mutableClassDefByOrNull(WORDMARK_NAVIGATION_BAR)
            ?: refuse("this Facebook has no $WORDMARK_NAVIGATION_BAR, the home feed's top bar")
        val glyphBuilders = buildersHolding(bar, SETUP_MESSENGER_GLYPH_VIEW)
        val glyphBuilder = glyphBuilders.singleOrNull()
            ?: refuse("expected one method of $WORDMARK_NAVIGATION_BAR holding \"$SETUP_MESSENGER_GLYPH_VIEW\", found ${glyphBuilders.size}")
        val logoBuilders = buildersHolding(bar, CREATE_WORDMARK_VIEW)
        val logoBuilder = logoBuilders.singleOrNull()
            ?: refuse("expected one method of $WORDMARK_NAVIGATION_BAR holding \"$CREATE_WORDMARK_VIEW\", found ${logoBuilders.size}")

        val button = messengerButtonSite(glyphBuilder)
        val buttonClass = mutableClassDefByOrNull(button.buttonType)
            ?: refuse("this Facebook has no ${button.buttonType}, the Messenger button")
        val counts = countMethods(buttonClass)
        logoDressSite(logoBuilder)
        val createBuilders = buildersHolding(bar, SETUP_CREATE_GLYPH_VIEW)
        val createBuilder = createBuilders.singleOrNull()
            ?: refuse("expected one method of $WORDMARK_NAVIGATION_BAR holding \"$SETUP_CREATE_GLYPH_VIEW\", found ${createBuilders.size}")
        createButtonSite(createBuilder)
        val searchBuilders = buildersHolding(bar, ADD_SEARCH_GLYPH_VIEW)
        val searchBuilder = searchBuilders.singleOrNull()
            ?: refuse("expected one method of $WORDMARK_NAVIGATION_BAR holding \"$ADD_SEARCH_GLYPH_VIEW\", found ${searchBuilders.size}")
        searchButtonSite(searchBuilder)

        bar.findMutableMethodOf(glyphBuilder).hideMessengerButtonAfter(button)
        counts.forEach { count ->
            buttonClass.methods.single {
                it.name == count.name && it.parameterTypes.map(CharSequence::toString) == count.parameters
            }.hideCountFirst(count.intRegister)
        }
        // Each builder may be one the hooks above went into, which now holds more instructions: find
        // its method and site again.
        fun MutableMethod.isLike(method: Method) =
            this.name == method.name && parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        val createMethod = bar.methods.single { it.isLike(createBuilder) }
        createMethod.hideCreateButtonAfter(createButtonSite(createMethod))
        val searchMethod = bar.methods.single { it.isLike(searchBuilder) }
        searchMethod.hideSearchButtonAfter(searchButtonSite(searchMethod))
        val logoMethod = bar.methods.single { it.isLike(logoBuilder) }
        val site = logoDressSite(logoMethod)
        logoMethod.hideLogoAfter(site.stockLoad, site.logo)
    }
    run {
        val bar = mutableClassDefByOrNull(NAVIGATION_BAR)
            ?: refuse("this Facebook has no $NAVIGATION_BAR, the newer top bar")
        val spec = specButtonSite(bar)
        val glyph = mutableClassDefByOrNull(spec.glyphType)
            ?: refuse("this Facebook has no ${spec.glyphType}, the newer bar's button glyph")
        val counts = glyphCountMethods(glyph, glyph.interfaces.mapNotNull { classDefByOrNull(it) })
        val wordmark = classDefByOrNull(WORDMARK_NAVIGATION_BAR)
            ?: refuse("this Facebook has no $WORDMARK_NAVIGATION_BAR, the home feed's top bar")
        homeBarScreenCalls(wordmark, bar, searchGlyphSite(bar, spec.glyphType))

        bar.findMutableMethodOf(spec.method).markSpecButtonAfter(spec)
        // Found again: the search glyph's method may be the spec method, which now holds more instructions.
        val search = searchGlyphSite(bar, spec.glyphType)
        bar.findMutableMethodOf(search.method).hideSearchGlyphAfter(search)
        counts.forEach { count ->
            glyph.methods.single {
                it.name == count.name && it.parameterTypes.map(CharSequence::toString) == count.parameters
            }.gateGlyphCountFirst(count.intRegister)
        }
    }
}

internal val topBarButtonsPatch = bytecodePatch {
    dependsOn(settingsPatch, facebookExtensionPatch)

    execute { hideTopBarButtons() }
}
