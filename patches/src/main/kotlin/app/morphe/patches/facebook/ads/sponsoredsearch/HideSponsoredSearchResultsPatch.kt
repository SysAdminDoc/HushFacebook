/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredsearch

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.search.PAGE_MODULES
import app.morphe.patches.facebook.search.filterPageModulesFirst
import app.morphe.patches.facebook.search.isPageConstructor
import app.morphe.patches.facebook.search.pageConstructorOf
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.executeBytecode
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/** The extension class that filters a page's modules, and the two stubs this patch fills in. */
internal const val SEARCH_AD_FILTER = "$EXTENSION_PACKAGE/ads/SearchAdFilter;"
internal const val IS_MODULE_STUB = "isModule"
internal const val ROLE_STUB = "role"

/** What the patch found: the page class, which of its constructor's parameters is the module list, and the module's role field. */
internal class SearchPages(
    val page: String,
    val moduleList: Int,
    val module: String,
    val roleField: String,
    val role: String,
)

/**
 * Removes the ad modules from each page of search results before the page is built. See
 * SearchResultAnchors.kt for how the page, the module and Facebook's own ad roles are found. The
 * hook first in the page's constructor is the one Hide Meta AI in search puts there too
 * (SearchResultsPageHook.kt): it goes in once, and the extension runs each patch's filter in turn
 * behind its own switch. Only the module list changes: a page with no ad keeps the very list
 * Facebook built, and the tabs, filters and every other module stay as they came.
 *
 * On 585 the page class (arm64 LX/0D1n) has two constructors: the full one the GraphQL page
 * builders call (LX/0iK7 A00 and A01), and a short one taking two lists, three strings and a flag
 * that only hands its page to the full one (called by the Meta AI answer builder LX/0DyI A00 and by
 * LX/0lDL BdR). The hook goes in the full one, and pageConstructorOf refuses a page class with any
 * constructor that doesn't hand its page there.
 */
@Suppress("unused")
val hideSponsoredSearchResultsPatch = patch(
    name = "Hide sponsored search results",
    description = "Removes the ads from Facebook's search results, so you see the people, Pages and posts you " +
        "searched for. On by default. Turn it off in Hushfacebook settings > Search.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val search = searchPages()
        fillModuleStubs(search)
        val page = mutableClassDefBy(search.page)
        val constructor = pageConstructorOf(page.methods, page.superclass, PATCH)
        requireSharedPageShape(constructor, search.moduleList)
        constructor.filterPageModulesFirst(PATCH)
        enableStatus("sponsoredSearch")
    }
}

/**
 * The search results page and module, held to the evidence the filter rests on: one enum builds
 * every ad role, one module class holds it in one field its constructor reads from `result_role`,
 * that class's own ad set is exactly [FACEBOOK_AD_ROLES], one class is what the page builders
 * answer, and their calls of its page-shaped constructor fill exactly one parameter with the module
 * converter's answer.
 */
internal fun BytecodePatchContext.searchPages(): SearchPages {
    val roles = classDefByStrings(AD_ROLES.first { it !in FACEBOOK_AD_ROLES }, StringComparisonType.EQUALS)
        .filter(::isRoleEnum)
    val role = roles.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one enum building every search ad role (${AD_ROLES.joinToString()}), found " +
            roles.joinToString { it.type }.ifEmpty { "none" },
    )
    requirePublic(role, "the search result role enum")

    val builders = mutableListOf<Method>()
    val modules = mutableListOf<ClassDef>()
    // A page builder takes the search context, and the module keeps a field of the role.
    classDefForEachReferencing(types = listOf(SEARCH_CONTEXT, role.type)) { classDef ->
        classDef.methods.filterTo(builders, ::isPageBuilder)
        if (isModuleClass(classDef, role.type)) modules += classDef
    }

    val module = modules.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one search module class holding ${role.type} and reading $RESULT_ROLE_FIELD, found " +
            modules.joinToString { it.type }.ifEmpty { "none" },
    )
    requirePublic(module, "the search module class")
    val field = roleFields(module, role.type).single()
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) {
        throw PatchException("$PATCH: ${module.type}->${field.name}, the module's role, isn't public, so the extension can't read it")
    }
    val adSet = adSetRoles(module, role.type, enumConstantFields(role))
    if (adSet != FACEBOOK_AD_ROLES) {
        throw PatchException(
            "$PATCH: ${module.type}'s own ad role set holds ${adSet.sorted().joinToString().ifEmpty { "nothing" }}, " +
                "not ${FACEBOOK_AD_ROLES.sorted().joinToString()}",
        )
    }
    val converter = moduleConverters(module)
        .singleOrPatchException("$PATCH: the module list converter, static (ImmutableList)ImmutableList, on ${module.type}")

    val pages = builders.map { it.returnType }.toSet()
    val page = pages.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the search page builders taking (FbUserSession, GraphQLResult, SearchResultsMutableContext) " +
            "to answer one class, found ${pages.joinToString().ifEmpty { "none" }}",
    )
    // The page-shaped constructor, since 585 gives the page a second, delegating one.
    val pageClass = classDefBy(page)
    val constructor = pageConstructorOf(pageClass.methods, pageClass.superclass, PATCH)
    val lists = moduleListParameters(builders.filter { it.returnType == page }, constructor, converter)
    val moduleList = lists.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the page builders to hand $page's constructor the module converter's list in one parameter, " +
            "found ${lists.sorted().joinToString().ifEmpty { "none" }}",
    )

    val copy = classDefBy(IMMUTABLE_LIST).methods.any {
        it.name == "copyOf" && it.returnType == IMMUTABLE_LIST && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/util/Collection;")
    }
    if (!copy) throw PatchException("$PATCH: ImmutableList has no static copyOf(Collection) to rebuild a filtered page with")

    return SearchPages(page, moduleList, module.type, field.name, role.type)
}

private fun requirePublic(classDef: ClassDef, what: String) {
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) {
        throw PatchException("$PATCH: ${classDef.type}, $what, isn't public, so the extension can't reach it")
    }
}

/**
 * Fills the extension's two stubs: whether an item is a search module, and a module's role. Each
 * uses only its parameter register, so the stub's own register count doesn't matter.
 */
private fun BytecodePatchContext.fillModuleStubs(search: SearchPages) {
    val filter = mutableClassDefBy(SEARCH_AD_FILTER)
    fun stub(name: String, returnType: String) = filter.methods.singleOrNull {
        it.name == name && it.returnType == returnType && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$SEARCH_AD_FILTER has no static $returnType $name(Object)")

    stub(IS_MODULE_STUB, "Z").addInstructions(
        0,
        """
            instance-of p0, p0, ${search.module}
            return p0
        """,
    )
    stub(ROLE_STUB, "Ljava/lang/Object;").addInstructions(
        0,
        """
            check-cast p0, ${search.module}
            iget-object p0, p0, ${search.module}->${search.roleField}:${search.role}
            return-object p0
        """,
    )
}

/**
 * Throws unless [constructor] is the page constructor the shared hook reads: the header, then the
 * module list, two more lists, four strings with the page's name sixth, and two flags, with the
 * module list the parameter the builders fill with the module converter's list. Hide Meta AI in
 * search holds the same shape, so both patches hand the hook the same two arguments.
 */
internal fun requireSharedPageShape(constructor: Method, moduleList: Int) {
    val reference = ImmutableMethodReference(
        constructor.definingClass, constructor.name, constructor.parameterTypes, constructor.returnType,
    )
    if (!isPageConstructor(reference)) {
        throw PatchException(
            "$PATCH: ${constructor.definingClass}-><init>(${constructor.parameterTypes.joinToString("")}) isn't the " +
                "page shape the shared page hook reads",
        )
    }
    if (moduleList != PAGE_MODULES) {
        throw PatchException("$PATCH: the page's module list is parameter $moduleList, and the shared page hook reads $PAGE_MODULES")
    }
}
