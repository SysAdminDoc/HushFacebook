/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.ads.sponsoredreels.filterSectionsFirst
import app.morphe.patches.facebook.ads.sponsoredreels.reelPages
import app.morphe.patches.facebook.ads.sponsoredreels.toBinaryName
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef

/*
 * The "Threads you might like" card between reels (#85), found by kept names only (read from 577,
 * 580 and 581, 2026-10-06). The obfuscated names in these comments are for reviewers; the code
 * never writes one down.
 *
 * - The cards Facebook puts between reels (people you may know, groups, games, a Threads post) are
 *   Reels mid-cards, typed by a GraphQL enum whose constant names Redex keeps: THREADS_MIDCARD
 *   beside PYML_MIDCARD, GYSJ_MIDCARD and UNSET_OR_UNRECOGNIZED_ENUM_VALUE (581 LX/55V). One larger
 *   enum names THREADS_MIDCARD too (581 LX/5bI), but no model answers it. 585 has two interfaces
 *   answering the small enum (arm64 LX/12g6; and LX/05rD;, enum LX/05VV;), so the chain below is
 *   followed from each and exactly one has to reach a reel item.
 * - The mid-card model answers its type through one no-argument interface method (581
 *   LX/UeF;->B75), and the unit that holds the model hands it over through one more (581
 *   LX/Udt;->BOZ).
 * - A mid-card's Reels item keeps that unit in a field and answers a story like every reel item
 *   (581 LX/8bO;->A01). Every other class that keeps the unit is a Litho component drawing one, and
 *   none of those answers a story.
 * - The Reels controller's page method, the one the page filters already go on, walks each
 *   section's items, picks out this item class and reads its type the same way (581
 *   LX/5IF;->A0K through LX/58Z;->A02). So a mid-card is in the section lists ReelSections walks.
 * - The "To what extent does this video match your interests?" survey between reels (#405) is a
 *   mid-card of the same enum, USER_SURVEY (585 LX/05VV; and LX/06Zc;, drawn by
 *   FbShortsSingleVideoUserSurveyMidcardComponent, LX/0cYi;).
 */

/** The mid-card type of the Threads card. */
internal const val THREADS_MID_CARD = "THREADS_MIDCARD"

/** The mid-card type of the interest survey (#405). */
internal const val USER_SURVEY = "USER_SURVEY"

private const val MID_CARDS = "Reel mid-cards"

internal const val REEL_MID_CARDS = "$EXTENSION_PACKAGE/reels/ReelMidCards;"
internal const val MID_CARD_TYPE_STUB = "midCardType"
internal const val THREADS_CARD_SECTION_FILTER =
    "$REEL_MID_CARDS->withoutThreadsCards(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

private const val ENUM = "Ljava/lang/Enum;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STORY = "Lcom/facebook/graphql/model/GraphQLStory;"

/** The reads from a Reels mid-card item to its type. */
internal class MidCardType(
    /** The mid-card item class. */
    val item: String,
    /** The item's field holding the mid-card unit. */
    val unitField: String,
    /** The unit interface. */
    val unit: String,
    /** The unit's no-argument getter of the model. */
    val modelGetter: String,
    /** The model interface. */
    val model: String,
    /** The model's no-argument getter of the type. */
    val typeGetter: String,
    /** The mid-card type enum. */
    val type: String,
)

/**
 * One pass over every class, then the chain from the mid-card type enum back to the item, each
 * step required to be the only one. Shared by the patch and its fixture test.
 */
internal class MidCardScan {
    private class Getter(val owner: String, val name: String, val public: Boolean, val type: String)
    private class ItemField(val owner: String, val name: String, val public: Boolean)

    class Result(val found: MidCardType?, val problem: String?)

    private val enums = mutableListOf<String>()

    /** Every no-argument abstract interface method returning an object, by its return type. */
    private val interfaceGetters = HashMap<String, MutableList<Getter>>()

    /** Every instance field of a class that answers a story, by the field's type. */
    private val itemFields = HashMap<String, MutableList<ItemField>>()

    fun visit(classDef: ClassDef) {
        val flags = classDef.accessFlags
        if (classDef.superclass == ENUM &&
            classDef.methods.any { it.name == "<clinit>" && holdsString(it, THREADS_MID_CARD) }
        ) {
            enums += classDef.type
        }

        if (AccessFlags.INTERFACE.isSet(flags)) {
            val public = AccessFlags.PUBLIC.isSet(flags)
            for (method in classDef.methods) {
                if (method.parameterTypes.isNotEmpty() || !AccessFlags.ABSTRACT.isSet(method.accessFlags)) continue
                if (!method.returnType.startsWith("L")) continue
                interfaceGetters.getOrPut(method.returnType) { mutableListOf() } += Getter(classDef.type, method.name, public, method.returnType)
            }
            return
        }

        val answersStory = classDef.methods.any {
            it.parameterTypes.isEmpty() && it.returnType == STORY && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        if (!answersStory) return
        val public = AccessFlags.PUBLIC.isSet(flags)
        for (field in classDef.instanceFields) {
            itemFields.getOrPut(field.type) { mutableListOf() } +=
                ItemField(classDef.type, field.name, public && AccessFlags.PUBLIC.isSet(field.accessFlags))
        }
    }

    /** A type getter followed down to the reel item, or where it stopped. */
    private class Chain(val typeGetter: Getter, val modelGetter: Getter?, val field: ItemField?, val problem: String?)

    private fun chain(typeGetter: Getter): Chain {
        val modelGetters = interfaceGetters[typeGetter.owner].orEmpty()
        val modelGetter = modelGetters.singleOrNull() ?: return Chain(
            typeGetter, null, null,
            "expected one interface getter of the mid-card model ${typeGetter.owner}, found ${modelGetters.size}",
        )

        val fields = itemFields[modelGetter.owner].orEmpty()
        val field = fields.singleOrNull() ?: return Chain(
            typeGetter, modelGetter, null,
            "expected one reel item keeping the mid-card unit ${modelGetter.owner}, " +
                "found ${fields.joinToString { "${it.owner}->${it.name}" }.ifEmpty { "none" }}",
        )
        return Chain(typeGetter, modelGetter, field, null)
    }

    fun resolve(): Result {
        if (enums.isEmpty()) return Result(null, "no enum names $THREADS_MID_CARD")

        val typeGetters = enums.flatMap { interfaceGetters[it].orEmpty() }
        if (typeGetters.isEmpty()) {
            return Result(null, "no interface getter of an enum naming $THREADS_MID_CARD (${enums.joinToString()})")
        }
        // 585: two interfaces answer the enum, and only the mid-card model's unit is kept by a reel item,
        // so the chain down to the item (each step the only one) tells them apart.
        val chains = typeGetters.map(::chain)
        val whole = chains.filter { it.problem == null }
        val reached = whole.singleOrNull() ?: return Result(
            null,
            if (whole.isEmpty()) {
                "no interface getter of an enum naming $THREADS_MID_CARD (${enums.joinToString()}) leads to a reel item: " +
                    chains.joinToString("; ") { "${it.typeGetter.owner}: ${it.problem}" }
            } else {
                "expected one interface getter of an enum naming $THREADS_MID_CARD (${enums.joinToString()}) to lead to a " +
                    "reel item, found ${whole.joinToString { it.typeGetter.owner }}"
            },
        )
        val typeGetter = reached.typeGetter
        val modelGetter = reached.modelGetter!!
        val field = reached.field!!

        if (!typeGetter.public || !modelGetter.public || !field.public) {
            return Result(
                null,
                "the mid-card reads (${field.owner}->${field.name}, ${modelGetter.owner}, ${typeGetter.owner}) " +
                    "aren't all public, so the extension can't make them",
            )
        }

        return Result(
            MidCardType(
                item = field.owner,
                unitField = field.name,
                unit = modelGetter.owner,
                modelGetter = modelGetter.name,
                model = typeGetter.owner,
                typeGetter = typeGetter.name,
                type = typeGetter.type,
            ),
            null,
        )
    }
}

/**
 * The mid-card filter's hook, shared by Clean up Reels (the Threads card, #85) and Hide reel
 * interest prompts (the interest survey, #405), so it goes in once whichever of them is picked. Each
 * page of sections the Reels controller takes goes through the same walk the sponsored reels and
 * GenAI filters use, with the mid-card item's class beside it, and the extension reads each
 * mid-card's type through the stub this fills. What comes off is the extension's call, by which of
 * the two patches the build carries and their switches.
 */
internal val reelMidCardsPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val scan = MidCardScan()
        classDefForEach { classDef -> if (!classDef.type.startsWith(EXTENSION_PACKAGE)) scan.visit(classDef) }
        val resolved = scan.resolve()
        val card = resolved.found ?: throw PatchException("$MID_CARDS: ${resolved.problem}")
        fillMidCardTypeStub(card)
        reelPages(MID_CARDS).addPage.filterSectionsFirst(card.item.toBinaryName(), THREADS_CARD_SECTION_FILTER, MID_CARDS)
    }
}

/**
 * Fills the extension's mid-card type stub with the three reads: the item's unit, the unit's
 * model, the model's type, answering null where one is missing. Only the parameter register is
 * used, so the stub's own register count doesn't matter; the extension passes only an object of
 * the item class.
 */
internal fun BytecodePatchContext.fillMidCardTypeStub(card: MidCardType) {
    val stub = mutableClassDefBy(REEL_MID_CARDS).methods.singleOrNull {
        it.name == MID_CARD_TYPE_STUB && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == OBJECT &&
            it.parameterTypes.map { type -> type.toString() } == listOf(OBJECT)
    } ?: throw PatchException("$REEL_MID_CARDS has no static Object $MID_CARD_TYPE_STUB(Object)")

    stub.addInstructionsWithLabels(0, midCardTypeReads(card))
}

/** The stub's body, on its own so the shapes test can read it. */
internal fun midCardTypeReads(card: MidCardType) =
    """
        check-cast p0, ${card.item}
        iget-object p0, p0, ${card.item}->${card.unitField}:${card.unit}
        if-eqz p0, :no_type
        invoke-interface { p0 }, ${card.unit}->${card.modelGetter}()${card.model}
        move-result-object p0
        if-eqz p0, :no_type
        invoke-interface { p0 }, ${card.model}->${card.typeGetter}()${card.type}
        move-result-object p0
        :no_type
        return-object p0
    """
