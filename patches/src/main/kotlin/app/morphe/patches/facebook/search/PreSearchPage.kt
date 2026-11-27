/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * How the search screen before typing is built (#600). Read from 588 on 2026-11-26; the obfuscated
 * names are for reviewers, the code never writes one down.
 *
 * Global search asks one supplier for that screen (arm64 LX/0BwI), and the app job that prefetches
 * it at start, which keeps its class name SearchGlobalNullStateAppJob, builds it with
 * new-instance and its (Context, FbUserSession) constructor. That constructor reads a MobileConfig
 * boolean first (LX/01BN;->A1b, 0x101009b00730a16 on 588). True puts one supplier in its list, read
 * from a lazy holder (LX/0BQe, whose network names itself "nullstate_global_full_bloks_network"),
 * and the whole screen comes as one Bloks page the server draws. False goes on to the native
 * suppliers, whose rows PreSearchSections reads. A reporter's 588 report, both switches and Debug
 * logging on, had no line from any of those rows.
 *
 * The read is found by its shape, not its id, which moves between builds: a static (Object, long)
 * boolean whose result is tested at once, and whose untaken side reaches ImmutableList.of(Object)
 * before any branch. The constructor's two other reads store their answer first. The hook hands
 * the answer to the extension right after it's read, before the test, in the same register.
 */

/** The class the global search's app job builds the null-state supplier from. */
internal const val SEARCH_GLOBAL_JOB = "Lcom/facebook/search/typeahead/nullstate/suppliers/SearchGlobalNullStateAppJob;"

/** The supplier constructor's parameters and return, as the app job calls it. */
internal const val SEARCH_SUPPLIER_INIT = "(Landroid/content/Context;Lcom/facebook/auth/usersession/FbUserSession;)V"

/** What the server page's single supplier goes into. */
internal const val ONE_ITEM_LIST = "Lcom/google/common/collect/ImmutableList;->of(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList;"

internal const val PRE_SEARCH_PAGE = "$EXTENSION_PACKAGE/search/PreSearchSections;->serverPage(Z)Z"

private val ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)

private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The type [job] builds its supplier as, or null unless it calls exactly one such constructor. */
internal fun preSearchSupplierType(job: ClassDef): String? =
    job.methods.asSequence()
        .flatMap { it.implementation?.instructions?.asSequence() ?: emptySequence() }
        .filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
        .mapNotNull { it.method() }
        .filter { it.name == "<init>" && "(${it.parameterTypes.joinToString("")})${it.returnType}" == SEARCH_SUPPLIER_INIT }
        .map { it.definingClass }
        .toSet()
        .singleOrNull()

/** Where the supplier's constructor [method] keeps its pick in [register], read at [read]. */
internal class PreSearchPageSite(val method: Method, val read: Int, val register: Int)

/** The one read in [supplier]'s constructor that picks the server page, or null for none or several. */
internal fun preSearchPageSite(supplier: ClassDef): PreSearchPageSite? {
    val init = supplier.methods.singleOrNull {
        it.name == "<init>" && "(${it.parameterTypes.joinToString("")})${it.returnType}" == SEARCH_SUPPLIER_INIT
    } ?: return null
    val code = init.implementation?.instructions?.toList() ?: return null
    return code.indices.mapNotNull { at ->
        if (code[at].opcode != Opcode.INVOKE_STATIC && code[at].opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        val call = code[at].method() ?: return@mapNotNull null
        if (call.returnType != "Z" || call.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;", "J")) {
            return@mapNotNull null
        }
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
            ?: return@mapNotNull null
        val test = code.getOrNull(at + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } as? OneRegisterInstruction
            ?: return@mapNotNull null
        if (test.registerA != result.registerA) return@mapNotNull null
        val untaken = code.drop(at + 3).takeWhile { it !is OffsetInstruction && it.opcode !in ENDS }
        if (untaken.none { it.method()?.smali() == ONE_ITEM_LIST }) return@mapNotNull null
        PreSearchPageSite(init, at + 1, result.registerA)
    }.singleOrNull()
}

/**
 * Hands the supplier's pick of the server page to the extension right after it's read. Refuses
 * unless the app job, its one supplier, the one read and a test no jump lands on are all found.
 */
internal fun BytecodePatchContext.hookPreSearchPage() {
    val job = classDefByOrNull(SEARCH_GLOBAL_JOB) ?: throw PatchException("no $SEARCH_GLOBAL_JOB")
    val type = preSearchSupplierType(job) ?: throw PatchException("$SEARCH_GLOBAL_JOB builds no one null-state supplier")
    val supplier = classDefByOrNull(type) ?: throw PatchException("no class $type for the null-state supplier")
    val site = preSearchPageSite(supplier)
        ?: throw PatchException("$type's constructor has no one read picking the search screen's server page")
    val method = mutableClassDefBy(type).findMutableMethodOf(site.method)
    val test = site.read + 1
    // A label on the test stays there, so a jump to it would skip the call.
    if (test in branchTargets(method.implementation!!.instructions.toList())) {
        throw PatchException("$type's constructor jumps to its test of the server page")
    }
    method.addInstructions(
        test,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $PRE_SEARCH_PAGE
            move-result v${site.register}
        """,
    )
}
