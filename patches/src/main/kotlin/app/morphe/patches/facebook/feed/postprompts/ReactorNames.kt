/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.returnFalseWhen
import app.morphe.patches.facebook.reels.returnNullWhen
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Who reacted, named under a post (#483). Read from 587, 2026-11-18, arm64 and armv7; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * The line a device showed (587 arm64, the row under the buttons: a friend's picture and "Name
 * reacted" / "Name and others reacted") is written by the server, not the app. The story's feedback
 * carries it whole in reaction_social_sentence (text, style ranges, the faces), read through tree
 * model getters keyed by that field name's hash. Only one row draws it:
 * - A builder (arm64 LX/03zg;->A00, armv7 LX/03ze;) turns a story into the row's model (arm64
 *   LX/03zo;, armv7 LX/03zm;, the app's one class built from (Spannable, object, GraphQLFeedback,
 *   Integer, List, int, int, boolean)), the sentence's text and the friends' pictures. It's the one
 *   static method taking (object, GraphQLStory) to that model.
 * - The builder's class has one static (object, Integer, boolean) -> boolean (arm64 A02): the mobile
 *   config check that says whether the row is on for this surface. Everything that draws the server
 *   sentence asks it first: the row's gate (A03, which the two footers ask before placing the row),
 *   the row's own render (LX/03zf;, three times, before building its view LX/0aPm;) and the footer
 *   that falls back to the plain count (LX/03ze;->A0A, which keeps the count out only while this
 *   says yes). It answers no while the switch is on, the state Facebook ships to accounts outside
 *   that test, so the row goes and the footer keeps its count. Only the impression logger
 *   (LX/040Y;) asks it besides, and on a no it logs nothing.
 *
 * Other layouts build the sentence in the app. These hooks are read from the dex and fixture-tested;
 * this account's feed never ran them (no hook log on the device):
 * - One builder class (arm64 LX/040A;) is the only code loading the app's own "Name reacted" strings.
 *   Its feedback entry (arm64 A0J, the app's one method taking (FbUserSession, object,
 *   GraphQLFeedback, String, boolean, boolean) to an object) has five callers: a row under the
 *   buttons (its gate, arm64 LX/0Cms;->A01, and its render LX/0Cn6;), two Litho components that lay
 *   the line out themselves (LX/0HlX;, LX/0Hlb;, "ufi_social_sentence_text") and the pills view. Each
 *   tests the answer for null and leaves the line out, the same as for a post nobody reacted to, so
 *   the builder answers null while the switch is on. It's found as the one such call inside the gate.
 *   The gate can still say yes with no sentence (a mobile config fallback), drawing the row's friend
 *   pictures alone, so it answers no as well. The gate is the one method in the app taking (Context,
 *   FbUserSession, GraphQLFeedback, GraphQLStory) to a boolean that calls the builder; its callers
 *   all leave the row out on a no.
 * - The bling bar (BlingBarComponent, LX/03Ah;), in the layout that keeps the reaction count in a
 *   line above the buttons. Its SocialSentenceBlingBarReactionCountPlugin, whose class name Facebook
 *   keeps, names people ("Name and 20 others", its one static (object, boolean, boolean) -> boolean).
 *   The bar's render and the static that asks whether the bar has anything to show are that check's
 *   only callers, so a no leaves the names out and the bar keeps its plain count. 588 dropped the
 *   bar's other naming plugin, the compact social context one, on both ABIs.
 * Hide the reaction preview on posts only skips the bar's pill view and the faces at the end of the
 * buttons, and neither of those is this line.
 */

internal const val SOCIAL_SENTENCE =
    "Lcom/facebook/feedback/reactions/plugins/blingbar/socialsentence/SocialSentenceBlingBarReactionCountPlugin;"
internal const val HIDE_REACTOR_NAMES = "$POST_PROMPTS->hideReactorNames()Z"

private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val REACTOR_FEEDBACK = "Lcom/facebook/graphql/model/GraphQLFeedback;"
internal val REACTOR_ROW_GATE_PARAMETERS =
    listOf("Landroid/content/Context;", USER_SESSION, REACTOR_FEEDBACK, "Lcom/facebook/graphql/model/GraphQLStory;")

private const val REACTOR_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"

private fun Method.hasCode() = implementation != null

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.types() = parameterTypes.map(CharSequence::toString)

/** The server row model's constructor: (Spannable, object, GraphQLFeedback, Integer, List, int, int, boolean). */
internal fun isServerReactorRowModelConstructor(method: Method): Boolean =
    method.name == "<init>" && method.types().let { types ->
        types.size == 8 && types[0] == "Landroid/text/Spannable;" && types[1].startsWith("L") &&
            types[2] == REACTOR_FEEDBACK && types[3] == "Ljava/lang/Integer;" && types[4] == "Ljava/util/List;" &&
            types[5] == "I" && types[6] == "I" && types[7] == "Z"
    }

/**
 * The server row's builder: a static method taking (object, GraphQLStory) to the row's [model]. 588's
 * Android 9 build (2026-11-20) builds the row in a private instance method of the same class instead
 * (LX/03Ac;->A01) and keeps each story's row in a cache, through a static (object, its own class,
 * GraphQLStory) the gate calls (LX/03Ac;->A00), so that is the builder there.
 */
internal fun isServerReactorRowBuilder(method: Method, model: String): Boolean =
    method.isStatic() && method.hasCode() && method.returnType == model &&
        method.types().let { types ->
            (types.size == 2 && types[0].startsWith("L") && types[1] == REACTOR_STORY) ||
                (types.size == 3 && types[0].startsWith("L") && types[1] == method.definingClass && types[2] == REACTOR_STORY)
        }

/** The server row's config check: a static (object, Integer, boolean) -> boolean. */
internal fun isServerReactorRowCheck(method: Method): Boolean =
    method.isStatic() && method.hasCode() && method.returnType == "Z" &&
        method.types().let { types -> types.size == 3 && types[0].startsWith("L") && types[1] == "Ljava/lang/Integer;" && types[2] == "Z" }

private fun Method.calls(target: Method): Boolean =
    implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == target.definingClass && it.name == target.name && it.returnType == target.returnType &&
                it.parameterTypes.map(CharSequence::toString) == target.types()
        } == true
    } == true

/**
 * The config check that decides whether the server-written "Name reacted" row is drawn: the one static
 * (object, Integer, boolean) -> boolean in the class of the row's builder, which that class's own gate
 * asks after building the row. Refuses unless the model, the builder, the check and a gate asking both
 * are each there once. Changes nothing.
 */
internal fun BytecodePatchContext.findServerReactorRowCheck(): Method {
    val models = mutableListOf<String>()
    classDefForEachReferencing(types = listOf(REACTOR_FEEDBACK)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        if (classDef.methods.any(::isServerReactorRowModelConstructor)) models += classDef.type
    }
    val model = models.singleOrNull()
        ?: throw PatchException("$PATCH: expected one model for the server's reactor row, found ${models.size}")
    val builders = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(model)) { classDef ->
        classDef.methods.filterTo(builders) { isServerReactorRowBuilder(it, model) }
    }
    val builder = builders.singleOrNull()
        ?: throw PatchException("$PATCH: expected one builder for the server's reactor row, found ${builders.size}")
    val owner = classDefByOrNull(builder.definingClass)
        ?: throw PatchException("$PATCH: the server row builder's class ${builder.definingClass} isn't in this build")
    val check = owner.methods.filter(::isServerReactorRowCheck).singleOrNull()
        ?: throw PatchException("$PATCH: ${owner.type} has no single config check for the server's reactor row")
    if (owner.methods.none { !it.isStatic() && it.returnType == "Z" && it.calls(builder) && it.calls(check) }) {
        throw PatchException("$PATCH: no gate in ${owner.type} asks both the server row's builder and its check")
    }
    return check
}

/** The sentence's check: [plugin]'s one static method answering a boolean from an object and two booleans. */
internal fun sentenceCheck(plugin: ClassDef): Method? =
    plugin.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.hasCode() &&
            it.parameterTypes.map(CharSequence::toString).let { types ->
                types.size == 3 && types[0].startsWith("L") && types[1] == "Z" && types[2] == "Z"
            }
    }.singleOrNull()

/** A call to the sentence builder: (FbUserSession, object, GraphQLFeedback, String, boolean, boolean). */
private fun buildsSentence(reference: MethodReference): Boolean {
    val types = reference.parameterTypes.map(CharSequence::toString)
    return types.size == 6 && types[0] == USER_SESSION && types[1].startsWith("L") && types[2] == REACTOR_FEEDBACK &&
        types[3] == "Ljava/lang/String;" && types[4] == "Z" && types[5] == "Z"
}

/** The row's gate: an instance method of the row's shape answering a boolean that builds the sentence itself. */
internal fun isReactorRowGate(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == REACTOR_ROW_GATE_PARAMETERS &&
        method.implementation?.instructions?.any { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let(::buildsSentence) == true
        } == true

/** The row's gate. Refuses unless the app has exactly one. Changes nothing. */
internal fun BytecodePatchContext.findReactorRowGate(): Method {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(REACTOR_FEEDBACK)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(found, ::isReactorRowGate)
    }
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one gate for the row naming who reacted, found ${found.size}")
}

/** The row's gate, then the bar's sentence check. Refuses unless each is there once. Changes nothing. */
internal fun BytecodePatchContext.findReactorChecks(): List<Method> {
    val plugin = classDefByOrNull(SOCIAL_SENTENCE) ?: throw PatchException("$PATCH: $SOCIAL_SENTENCE isn't in this build")
    val sentence = sentenceCheck(plugin)
        ?: throw PatchException("$PATCH: $SOCIAL_SENTENCE has no single check of the expected shape")
    return listOf(findReactorRowGate(), sentence)
}

/** The sentence builder [gate] calls, as a method of its class. Refuses unless the gate calls one. Changes nothing. */
internal fun BytecodePatchContext.findReactorSentenceBuilder(gate: Method): Method {
    val called = gate.implementation?.instructions?.toList().orEmpty()
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter(::buildsSentence)
        .distinctBy { it.toString() }
    val reference = called.singleOrNull()
        ?: throw PatchException("$PATCH: the reactor row gate calls ${called.size} sentence builders, expected one")
    val builder = classDefByOrNull(reference.definingClass)
        ?: throw PatchException("$PATCH: the sentence builder's class ${reference.definingClass} isn't in this build")
    return builder.methods.singleOrNull {
        it.name == reference.name && it.returnType == reference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
    }?.takeIf { it.implementation != null && it.returnType.startsWith("L") }
        ?: throw PatchException("$PATCH: $reference has no body of the expected shape")
}

/**
 * Has the server row's config check and every other check answer no, and the app's sentence builder
 * answer nothing, first thing while the switch is on.
 */
internal fun BytecodePatchContext.hideReactorNames() {
    val serverCheck = findServerReactorRowCheck()
    val checks = findReactorChecks()
    val builder = findReactorSentenceBuilder(checks.first())
    (listOf(serverCheck) + checks).forEach { check ->
        mutableClassDefBy(check.definingClass).findMutableMethodOf(check).returnFalseWhen(HIDE_REACTOR_NAMES, PATCH)
    }
    mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder).returnNullWhen(HIDE_REACTOR_NAMES, PATCH)
}
