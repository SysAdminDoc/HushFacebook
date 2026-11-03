/*
 * Ported from https://github.com/SysAdminDoc/HushMessenger (its GmsCore support patch), which
 * adapted https://github.com/SysAdminDoc/HushPinterest's, itself forked from
 * https://github.com/MorpheApp/morphe-patches at e72fe6e and
 * https://gitlab.com/ReVanced/revanced-patches at 724e6d6 (GPL-3.0). Copyright 2026 Morphe.
 *
 * Modified for Hushfacebook (Facebook), 2026: Facebook's manifest and certificate, Hushfacebook's
 * own extension classes left with Google's names, SettingsStatus told the patch is in, and the
 * version check left to the compatibility list. See NOTICE.
 */
package app.morphe.patches.facebook.misc.gmscore

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val GMS_CORE_PATCH = "GmsCore support"

/** microG-RE's package, which stands in for Google Play services. */
internal const val GMS_CORE_PACKAGE = "app.revanced.android.gms"

/** The vendor prefix microG-RE answers to wherever Play services would answer to com.google. */
internal const val GMS_CORE_VENDOR = "app.revanced"
private const val GOOGLE_VENDOR = "com.google"
internal const val PLAY_SERVICES = "com.google.android.gms"

/** Cloud Messaging's manifest names, and the ones microG-RE delivers to instead. */
internal const val GOOGLE_C2DM = "com.google.android.c2dm."
internal const val GMS_CORE_C2DM = "app.revanced.android.c2dm."

/**
 * The application meta-data microG-RE reads for the certificate its Google API checks use in place of the app's own.
 * Its cloud messaging registration (7.2.2) still sends the real one, and Google answers with a token all the same.
 */
internal const val SPOOFED_SIGNATURE_METADATA = "app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE"

/**
 * SHA-1 of Facebook's original certificate (CN=Facebook Corporation, O=Facebook Mobile), the first in its signing
 * lineage and the same one Messenger has. Android's GET_SIGNATURES answers the first certificate of a rotated lineage,
 * so it's the one Google has always seen.
 */
internal const val FACEBOOK_CERTIFICATE_SHA1 = "8a3c4b262d721acd49a4bf97d5213199c86fa2b9"

private const val CONTEXT = "Landroid/content/Context;"
private const val CREDENTIAL_MANAGER = "Landroid/credentials/CredentialManager;"

/** Hushfacebook's own classes, which keep Google's names: they ask about the real Play services. */
private const val EXTENSION_ROOT = "Lapp/morphe/"

/** Play services permissions microG-RE defines under its own vendor. Morphe's list. */
internal val GMS_PERMISSIONS = setOf(
    "com.google.android.c2dm.permission.RECEIVE",
    "com.google.android.c2dm.permission.SEND",
    "com.google.android.gms.auth.api.phone.permission.SEND",
    "com.google.android.gms.permission.AD_ID",
    "com.google.android.gms.permission.AD_ID_NOTIFICATION",
    "com.google.android.gms.permission.CAR_FUEL",
    "com.google.android.gms.permission.CAR_INFORMATION",
    "com.google.android.gms.permission.CAR_MILEAGE",
    "com.google.android.gms.permission.CAR_SPEED",
    "com.google.android.gms.permission.CAR_VENDOR_EXTENSION",
    "com.google.android.googleapps.permission.GOOGLE_AUTH",
    "com.google.android.googleapps.permission.GOOGLE_AUTH.cp",
    "com.google.android.googleapps.permission.GOOGLE_AUTH.local",
    "com.google.android.googleapps.permission.GOOGLE_AUTH.mail",
    "com.google.android.googleapps.permission.GOOGLE_AUTH.writely",
    "com.google.android.gtalkservice.permission.GTALK_SERVICE",
    "com.google.android.providers.gsf.permission.READ_GSERVICES",
)

/**
 * Play services intent actions microG-RE serves only under its own vendor. Morphe's list, and the last four, which
 * microG-RE 7.2.2 declares only as app.revanced actions: Credential Manager's identity credentials, App set ID, the SMS
 * retriever and ads.
 */
internal val GMS_ACTIONS = setOf(
    "com.google.android.c2dm.intent.RECEIVE",
    "com.google.android.c2dm.intent.REGISTER",
    "com.google.android.c2dm.intent.UNREGISTER",
    "com.google.android.contextmanager.service.ContextManagerService.START",
    "com.google.android.gcm.intent.SEND",
    "com.google.android.gms.accounts.ACCOUNT_SERVICE",
    "com.google.android.gms.accountsettings.ACCOUNT_PREFERENCES_SETTINGS",
    "com.google.android.gms.accountsettings.action.BROWSE_SETTINGS",
    "com.google.android.gms.accountsettings.action.VIEW_SETTINGS",
    "com.google.android.gms.accountsettings.MY_ACCOUNT",
    "com.google.android.gms.accountsettings.PRIVACY_SETTINGS",
    "com.google.android.gms.accountsettings.SECURITY_SETTINGS",
    "com.google.android.gms.ads.gservice.START",
    "com.google.android.gms.ads.identifier.service.EVENT_ATTESTATION",
    "com.google.android.gms.ads.service.CACHE",
    "com.google.android.gms.ads.service.CONSENT_LOOKUP",
    "com.google.android.gms.ads.service.HTTP",
    "com.google.android.gms.analytics.service.START",
    "com.google.android.gms.appstate.service.START",
    "com.google.android.gms.appusage.service.START",
    "com.google.android.gms.asterism.service.START",
    "com.google.android.gms.audiomodem.service.AudioModemService.START",
    "com.google.android.gms.audit.service.START",
    "com.google.android.gms.auth.account.authapi.START",
    "com.google.android.gms.auth.account.authenticator.auto.service.START",
    "com.google.android.gms.auth.account.authenticator.chromeos.START",
    "com.google.android.gms.auth.account.authenticator.tv.service.START",
    "com.google.android.gms.auth.account.data.service.START",
    "com.google.android.gms.auth.api.credentials.PICKER",
    "com.google.android.gms.auth.api.credentials.service.START",
    "com.google.android.gms.auth.api.identity.service.authorization.START",
    "com.google.android.gms.auth.api.identity.service.credentialsaving.START",
    "com.google.android.gms.auth.api.identity.service.signin.START",
    "com.google.android.gms.auth.api.phone.service.InternalService.START",
    "com.google.android.gms.auth.api.signin.service.START",
    "com.google.android.gms.auth.be.appcert.AppCertService",
    "com.google.android.gms.auth.blockstore.service.START",
    "com.google.android.gms.auth.config.service.START",
    "com.google.android.gms.auth.cryptauth.cryptauthservice.START",
    "com.google.android.gms.auth.GOOGLE_SIGN_IN",
    "com.google.android.gms.auth.login.LOGIN",
    "com.google.android.gms.auth.proximity.devicesyncservice.START",
    "com.google.android.gms.auth.proximity.securechannelservice.START",
    "com.google.android.gms.auth.proximity.START",
    "com.google.android.gms.auth.service.START",
    "com.google.android.gms.backup.ACTION_BACKUP_SETTINGS",
    "com.google.android.gms.backup.G1_BACKUP",
    "com.google.android.gms.backup.G1_RESTORE",
    "com.google.android.gms.backup.GMS_MODULE_RESTORE",
    "com.google.android.gms.beacon.internal.IBleService.START",
    "com.google.android.gms.car.service.START",
    "com.google.android.gms.carrierauth.service.START",
    "com.google.android.gms.cast.firstparty.START",
    "com.google.android.gms.cast.remote_display.service.START",
    "com.google.android.gms.cast.service.BIND_CAST_DEVICE_CONTROLLER_SERVICE",
    "com.google.android.gms.cast_mirroring.service.START",
    "com.google.android.gms.checkin.BIND_TO_SERVICE",
    "com.google.android.gms.chromesync.service.START",
    "com.google.android.gms.clearcut.service.START",
    "com.google.android.gms.common.account.CHOOSE_ACCOUNT",
    "com.google.android.gms.common.download.START",
    "com.google.android.gms.common.service.START",
    "com.google.android.gms.common.telemetry.service.START",
    "com.google.android.gms.config.START",
    "com.google.android.gms.constellation.service.START",
    "com.google.android.gms.credential.manager.service.firstparty.START",
    "com.google.android.gms.deviceconnection.service.START",
    "com.google.android.gms.drive.ApiService.RESET_AFTER_BOOT",
    "com.google.android.gms.drive.ApiService.START",
    "com.google.android.gms.drive.ApiService.STOP",
    "com.google.android.gms.droidguard.service.INIT",
    "com.google.android.gms.droidguard.service.PING",
    "com.google.android.gms.droidguard.service.START",
    "com.google.android.gms.enterprise.loader.service.START",
    "com.google.android.gms.facs.cache.service.START",
    "com.google.android.gms.facs.internal.service.START",
    "com.google.android.gms.feedback.internal.IFeedbackService",
    "com.google.android.gms.fido.credentialstore.internal_service.START",
    "com.google.android.gms.fido.fido2.privileged.START",
    "com.google.android.gms.fido.fido2.regular.START",
    "com.google.android.gms.fido.fido2.zeroparty.START",
    "com.google.android.gms.fido.sourcedevice.service.START",
    "com.google.android.gms.fido.targetdevice.internal_service.START",
    "com.google.android.gms.fido.u2f.privileged.START",
    "com.google.android.gms.fido.u2f.thirdparty.START",
    "com.google.android.gms.fido.u2f.zeroparty.START",
    "com.google.android.gms.fitness.BleApi",
    "com.google.android.gms.fitness.ConfigApi",
    "com.google.android.gms.fitness.GoalsApi",
    "com.google.android.gms.fitness.GoogleFitnessService.START",
    "com.google.android.gms.fitness.HistoryApi",
    "com.google.android.gms.fitness.InternalApi",
    "com.google.android.gms.fitness.RecordingApi",
    "com.google.android.gms.fitness.SensorsApi",
    "com.google.android.gms.fitness.SessionsApi",
    "com.google.android.gms.fonts.service.START",
    "com.google.android.gms.freighter.service.START",
    "com.google.android.gms.games.internal.connect.service.START",
    "com.google.android.gms.games.PLAY_GAMES_UPGRADE",
    "com.google.android.gms.games.service.START",
    "com.google.android.gms.gass.START",
    "com.google.android.gms.gmscompliance.service.START",
    "com.google.android.gms.googlehelp.HELP",
    "com.google.android.gms.googlehelp.service.GoogleHelpService.START",
    "com.google.android.gms.growth.service.START",
    "com.google.android.gms.herrevad.services.LightweightNetworkQualityAndroidService.START",
    "com.google.android.gms.icing.INDEX_SERVICE",
    "com.google.android.gms.icing.LIGHTWEIGHT_INDEX_SERVICE",
    "com.google.android.gms.identity.service.BIND",
    "com.google.android.gms.inappreach.service.START",
    "com.google.android.gms.instantapps.START",
    "com.google.android.gms.kids.service.START",
    "com.google.android.gms.languageprofile.service.START",
    "com.google.android.gms.learning.internal.dynamitesupport.START",
    "com.google.android.gms.learning.intservice.START",
    "com.google.android.gms.learning.predictor.START",
    "com.google.android.gms.learning.trainer.START",
    "com.google.android.gms.learning.training.background.START",
    "com.google.android.gms.location.places.GeoDataApi",
    "com.google.android.gms.location.places.PlaceDetectionApi",
    "com.google.android.gms.location.places.PlacesApi",
    "com.google.android.gms.location.reporting.service.START",
    "com.google.android.gms.location.settings.LOCATION_HISTORY",
    "com.google.android.gms.location.settings.LOCATION_REPORTING_SETTINGS",
    "com.google.android.location.internal.GoogleLocationManagerService",
    "com.google.android.gms.locationsharing.api.START",
    "com.google.android.gms.locationsharingreporter.service.START",
    "com.google.android.gms.lockbox.service.START",
    "com.google.android.gms.matchstick.lighter.service.START",
    "com.google.android.gms.mdm.services.DeviceManagerApiService.START",
    "com.google.android.gms.mdm.services.START",
    "com.google.android.gms.mdns.service.START",
    "com.google.android.gms.measurement.START",
    "com.google.android.gms.nearby.bootstrap.service.NearbyBootstrapService.START",
    "com.google.android.gms.nearby.connection.service.START",
    "com.google.android.gms.nearby.fastpair.START",
    "com.google.android.gms.nearby.messages.service.NearbyMessagesService.START",
    "com.google.android.gms.nearby.sharing.service.NearbySharingService.START",
    "com.google.android.gms.nearby.sharing.START_SERVICE",
    "com.google.android.gms.notifications.service.START",
    "com.google.android.gms.ocr.service.internal.START",
    "com.google.android.gms.ocr.service.START",
    "com.google.android.gms.oss.licenses.service.START",
    "com.google.android.gms.payse.service.BIND",
    "com.google.android.gms.people.contactssync.service.START",
    "com.google.android.gms.people.service.START",
    "com.google.android.gms.phenotype.service.START",
    "com.google.android.gms.photos.autobackup.service.START",
    "com.google.android.gms.playlog.service.START",
    "com.google.android.gms.plus.service.default.INTENT",
    "com.google.android.gms.plus.service.image.INTENT",
    "com.google.android.gms.plus.service.internal.START",
    "com.google.android.gms.plus.service.START",
    "com.google.android.gms.potokens.service.START",
    "com.google.android.gms.pseudonymous.service.START",
    "com.google.android.gms.rcs.START",
    "com.google.android.gms.reminders.service.START",
    "com.google.android.gms.romanesco.MODULE_BACKUP_AGENT",
    "com.google.android.gms.romanesco.service.START",
    "com.google.android.gms.safetynet.service.START",
    "com.google.android.gms.scheduler.ACTION_PROXY_SCHEDULE",
    "com.google.android.gms.search.service.SEARCH_AUTH_START",
    "com.google.android.gms.semanticlocation.service.START_ODLH",
    "com.google.android.gms.sesame.service.BIND",
    "com.google.android.gms.settings.EXPOSURE_NOTIFICATION_SETTINGS",
    "com.google.android.gms.setup.auth.SecondDeviceAuth.START",
    "com.google.android.gms.signin.service.START",
    "com.google.android.gms.smartdevice.d2d.SourceDeviceService.START",
    "com.google.android.gms.smartdevice.d2d.TargetDeviceService.START",
    "com.google.android.gms.smartdevice.directtransfer.SourceDirectTransferService.START",
    "com.google.android.gms.smartdevice.directtransfer.TargetDirectTransferService.START",
    "com.google.android.gms.smartdevice.postsetup.PostSetupService.START",
    "com.google.android.gms.smartdevice.setup.accounts.AccountsService.START",
    "com.google.android.gms.smartdevice.wifi.START_WIFI_HELPER_SERVICE",
    "com.google.android.gms.social.location.activity.service.START",
    "com.google.android.gms.speech.service.START",
    "com.google.android.gms.statementservice.EXECUTE",
    "com.google.android.gms.stats.ACTION_UPLOAD_DROPBOX_ENTRIES",
    "com.google.android.gms.tapandpay.service.BIND",
    "com.google.android.gms.telephonyspam.service.START",
    "com.google.android.gms.testsupport.service.START",
    "com.google.android.gms.thunderbird.service.START",
    "com.google.android.gms.trustagent.BridgeApi.START",
    "com.google.android.gms.trustagent.StateApi.START",
    "com.google.android.gms.trustagent.trustlet.trustletmanagerservice.BIND",
    "com.google.android.gms.trustlet.bluetooth.service.BIND",
    "com.google.android.gms.trustlet.connectionlessble.service.BIND",
    "com.google.android.gms.trustlet.face.service.BIND",
    "com.google.android.gms.trustlet.nfc.service.BIND",
    "com.google.android.gms.trustlet.onbody.service.BIND",
    "com.google.android.gms.trustlet.place.service.BIND",
    "com.google.android.gms.trustlet.voiceunlock.service.BIND",
    "com.google.android.gms.udc.service.START",
    "com.google.android.gms.update.START_API_SERVICE",
    "com.google.android.gms.update.START_SERVICE",
    "com.google.android.gms.update.START_SINGLE_USER_API_SERVICE",
    "com.google.android.gms.update.START_TV_API_SERVICE",
    "com.google.android.gms.usagereporting.service.START",
    "com.google.android.gms.userlocation.service.START",
    "com.google.android.gms.vehicle.cabin.service.START",
    "com.google.android.gms.vehicle.climate.service.START",
    "com.google.android.gms.vehicle.info.service.START",
    "com.google.android.gms.wallet.firstparty.ACTION_PURCHASE_MANAGER",
    "com.google.android.gms.wallet.service.BIND",
    "com.google.android.gms.walletp2p.service.firstparty.BIND",
    "com.google.android.gms.walletp2p.service.zeroparty.BIND",
    "com.google.android.gms.wearable.BIND",
    "com.google.android.gms.wearable.BIND_LISTENER",
    "com.google.android.gms.wearable.DATA_CHANGED",
    "com.google.android.gms.wearable.MESSAGE_RECEIVED",
    "com.google.android.gms.wearable.NODE_CHANGED",
    "com.google.android.gsf.action.GET_GLS",
    "com.google.android.location.settings.LOCATION_REPORTING_SETTINGS",
    "com.google.android.mdd.service.START",
    "com.google.android.mdh.service.listener.START",
    "com.google.android.mdh.service.START",
    "com.google.android.mobstore.service.START",
    "com.google.iid.TOKEN_REQUEST",
    "com.google.android.gms.location.places.ui.PICK_PLACE",
    "com.google.android.gms.identitycredentials.service.START",
    "com.google.android.gms.appset.service.START",
    "com.google.android.gms.auth.api.phone.service.SmsRetrieverApiService.START",
    "com.google.android.gms.ads.service.START",
)

/** Play services content provider authorities microG-RE serves under its own vendor. Morphe's list. */
internal val GMS_AUTHORITIES = setOf(
    "com.google.android.gms.auth.accounts",
    "com.google.android.gms.chimera",
    "com.google.android.gms.fonts",
    "com.google.android.gms.phenotype",
    "com.google.android.gsf.gservices",
    "com.google.android.gsf.subscribedfeeds",
    "com.google.settings",
)

/** Each exact text the reroute rewrites, and what it becomes. */
internal val GMS_CORE_TEXTS: Map<String, String> = buildMap {
    put(GOOGLE_VENDOR, GMS_CORE_VENDOR)
    put(PLAY_SERVICES, GMS_CORE_PACKAGE)
    for (text in GMS_PERMISSIONS + GMS_ACTIONS + GMS_AUTHORITIES) put(text, GMS_CORE_VENDOR + text.removePrefix(GOOGLE_VENDOR))
}

/**
 * Texts Facebook has to name for the reroute to mean anything: Play services itself and the two ways its Cloud
 * Messaging token is asked for, the old c2dm registration and the Instance ID token request. A build missing one has
 * moved on, so the patch refuses rather than half reroute it.
 */
internal val GMS_CORE_REQUIRED_TEXTS = listOf(PLAY_SERVICES, "com.google.android.c2dm.intent.REGISTER", "com.google.iid.TOKEN_REQUEST")

/**
 * What [text] becomes under microG-RE, or null when it stays. An exact name from the lists, or a content:// address
 * whose authority is one of [GMS_AUTHORITIES], followed by nothing or a path.
 */
internal fun gmsCoreText(text: String): String? {
    GMS_CORE_TEXTS[text]?.let { return it }
    if (!text.startsWith("content://")) return null
    val rest = text.removePrefix("content://")
    val authority = GMS_AUTHORITIES.firstOrNull { rest == it || rest.startsWith("$it/") } ?: return null
    return "content://" + GMS_CORE_VENDOR + authority.removePrefix(GOOGLE_VENDOR) + rest.removePrefix(authority)
}

/** One const-string to rewrite: its method, its index, and the text it gets. */
internal data class GmsCoreText(val method: Method, val index: Int, val text: String)

/** One static String field whose value gets rewritten: its class, its name, and the text it gets. */
internal data class GmsCoreField(val type: String, val name: String, val text: String)

/** Everything the patch edits, found before any edit. */
internal data class GmsCorePlan(
    val availability: Method,
    val frameworkProvider: Method,
    val texts: List<GmsCoreText>,
    val fields: List<GmsCoreField>,
)

/**
 * Finds the three edits in [classes], refusing when any of them isn't where 585 has it:
 *
 * - Play services' availability check, the one method taking a Context and the minimum version that reads the
 *   version metadata ("MetadataValueReader") and Play services' package. It answers success, so the library stops
 *   asking whether Google's own Play services is installed and signed by Google.
 * - androidx Credential Manager's framework provider, the one class holding Android's CredentialManager. On Android 14
 *   and later androidx prefers it, and Android hands Google's requests to Google's own Play services whatever the app
 *   asks for, so it answers unavailable and androidx falls back to the Play services provider the reroute points at
 *   microG-RE.
 * - Every const-string outside Hushfacebook's own classes that [gmsCoreText] rewrites, and every static String
 *   field value it rewrites.
 */
internal fun planGmsCore(classes: Iterable<ClassDef>): GmsCorePlan {
    val availability = mutableListOf<Method>()
    val providers = mutableListOf<Method>()
    val texts = mutableListOf<GmsCoreText>()
    val fields = mutableListOf<GmsCoreField>()
    for (owner in classes) {
        if (owner.type.startsWith(EXTENSION_ROOT)) continue
        for (field in owner.staticFields) {
            val text = (field.initialValue as? StringEncodedValue)?.value ?: continue
            gmsCoreText(text)?.let { fields += GmsCoreField(owner.type, field.name, it) }
        }
        val holdsCredentialManager = owner.fields.any { it.type == CREDENTIAL_MANAGER && !AccessFlags.STATIC.isSet(it.accessFlags) }
        for (method in owner.methods) {
            val body = method.implementation?.instructions?.toList() ?: continue
            val strings = mutableSetOf<String>()
            body.forEachIndexed { index, instruction ->
                val text = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return@forEachIndexed
                strings += text
                if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) return@forEachIndexed
                gmsCoreText(text)?.let { texts += GmsCoreText(method, index, it) }
            }
            if (method.returnType == "I" && method.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, "I") &&
                "MetadataValueReader" in strings && "This should never happen." in strings && PLAY_SERVICES in strings
            ) availability += method
            if (holdsCredentialManager && method.name == "isAvailableOnDevice" && method.returnType == "Z" &&
                method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags)
            ) providers += method
        }
    }
    shape(availability.size == 1, "Play services' availability check has ${availability.size} matches")
    shape(providers.size == 1, "Credential Manager's framework provider has ${providers.size} matches")
    val rewritten = texts.map { text -> text.method.texts()[text.index] }.toSet()
    GMS_CORE_REQUIRED_TEXTS.firstOrNull { it !in rewritten }?.let { shape(false, "Facebook no longer names $it") }
    return GmsCorePlan(availability.single(), providers.single(), texts, fields)
}

private fun BytecodePatchContext.planGmsCore() = planGmsCore(mutableListOf<ClassDef>().apply { classDefForEach { add(it) } })

/** Rewrites every planned text and field, then answers the two checks. Texts go first: they keep every index. */
internal fun BytecodePatchContext.applyGmsCore(plan: GmsCorePlan) {
    for ((type, sites) in plan.texts.groupBy { it.method.definingClass }) {
        val owner = mutableClassDefBy(type)
        for (site in sites) {
            val method = owner.methods.single { it.same(site.method) }
            val original = method.implementation!!.instructions[site.index]
            val register = (original as OneRegisterInstruction).registerA
            val reference = ImmutableStringReference(site.text)
            method.replaceInstruction(site.index, when (original.opcode) {
                Opcode.CONST_STRING -> BuilderInstruction21c(Opcode.CONST_STRING, register, reference)
                else -> BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, register, reference)
            })
        }
    }
    for ((type, held) in plan.fields.groupBy { it.type }) {
        val owner = mutableClassDefBy(type)
        for (field in held) (owner.staticFields.single { it.name == field.name }.initialValue as MutableStringEncodedValue).value = field.text
    }
    mutableClassDefBy(plan.availability.definingClass).methods.single { it.same(plan.availability) }.answerZero()
    mutableClassDefBy(plan.frameworkProvider.definingClass).methods.single { it.same(plan.frameworkProvider) }.answerZero()
}

/** Returns 0 (success for the availability check, false for the provider) before the body runs. Both have a v0. */
private fun MutableMethod.answerZero() = addInstructions(0, """
    const/4 v0, 0x0
    return v0
""")

/**
 * Sends Cloud Messaging's manifest names to microG-RE: the receive permission Facebook asks for, the send permission
 * its Firebase receiver demands of a sender, and the receive action that receiver listens for. microG-RE's package
 * joins the first query block, which a manifest without one gets, so Facebook can see it on Android 11 and later, and
 * the application names Facebook's original certificate for microG-RE to show Google. Everything is checked before
 * the first edit: a manifest that doesn't ask for the receive permission, or already names microG-RE or a spoofed
 * certificate, refuses unchanged.
 */
internal fun rerouteManifestToGmsCore(document: Document) {
    val manifest = document.documentElement
    val elements = mutableListOf<Element>()
    fun walk(element: Element) {
        elements += element
        val children = element.childNodes
        for (index in 0 until children.length) (children.item(index) as? Element)?.let(::walk)
    }
    walk(manifest)
    val attributes = elements.flatMap { element -> (0 until element.attributes.length).map { element.attributes.item(it) } }
    val requested = elements.any { it.tagName == "uses-permission" &&
        it.getAttribute("android:name") == "com.google.android.c2dm.permission.RECEIVE" }
    if (!requested) throw PatchException("$GMS_CORE_PATCH: AndroidManifest.xml doesn't ask for Cloud Messaging's receive permission")
    attributes.firstOrNull {
        it.nodeValue.startsWith(GMS_CORE_C2DM) || (it.nodeName == "android:name" && it.nodeValue in setOf(GMS_CORE_PACKAGE, SPOOFED_SIGNATURE_METADATA))
    }?.let { throw PatchException("$GMS_CORE_PATCH: AndroidManifest.xml already names ${it.nodeValue}") }
    val application = elements.firstOrNull { it.tagName == "application" && it.parentNode === manifest }
        ?: throw PatchException("$GMS_CORE_PATCH: AndroidManifest.xml has no application element")

    attributes.filter { it.nodeValue.startsWith(GOOGLE_C2DM) }.forEach { it.nodeValue = GMS_CORE_C2DM + it.nodeValue.removePrefix(GOOGLE_C2DM) }
    val queries = elements.firstOrNull { it.tagName == "queries" && it.parentNode === manifest }
        ?: document.createElement("queries").also { manifest.insertBefore(it, application) }
    queries.appendChild(document.createElement("package").apply { setAttribute("android:name", GMS_CORE_PACKAGE) })
    application.appendChild(document.createElement("meta-data").apply {
        setAttribute("android:name", SPOOFED_SIGNATURE_METADATA)
        setAttribute("android:value", FACEBOOK_CERTIFICATE_SHA1)
    })
}

/** Checks every bytecode edit first, so nothing refuses after the manifest changed. */
internal val gmsCorePreflightPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        requireStatusMethod("gmsCore")
        planGmsCore()
    }
}

/** Points Facebook's manifest at microG-RE, once the preflight found every bytecode edit. */
internal val gmsCoreManifestPatch = resourcePatch {
    dependsOn(gmsCorePreflightPatch)
    execute { document("AndroidManifest.xml").use(::rerouteManifestToGmsCore) }
}

@Suppress("unused")
val gmsCoreSupportPatch = bytecodePatch(
    name = "GmsCore support",
    description = "For phones that run microG-RE instead of Google Play services. Facebook asks microG-RE for its " +
        "push notifications, and microG-RE gets the signature of Meta's own Facebook for the Google services that " +
        "check one. It isn't selected by default because Facebook then stops asking Google Play services, so a " +
        "phone without microG-RE gets no push notifications. Turn on Expert mode in Manager to pick it. Works as " +
        "soon as you patch it in, with no switch.",
    default = false,
) {
    category("Fixes")
    dependsOn(settingsPatch, facebookExtensionPatch, gmsCoreManifestPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Found again rather than carried over: patches that ran since may have edited these classes.
        applyGmsCore(planGmsCore())
        enableStatus("gmsCore")
    }
}

private fun Method.texts(): List<String?> = implementation?.instructions?.map {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
}.orEmpty()

private fun Method.same(other: Method) = definingClass == other.definingClass && name == other.name &&
    returnType == other.returnType && parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun shape(valid: Boolean, reason: String) {
    if (!valid) throw PatchException("$GMS_CORE_PATCH: $reason. Nothing was changed.")
}
