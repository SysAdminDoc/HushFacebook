# Hushfacebook help

Questions about setting up, or about whether a Facebook build works, go in [Discussions](https://github.com/SysAdminDoc/Hushfacebook/discussions). For something that's broken, use the [bug form](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) and attach a diagnostic report from Hushfacebook settings. It leaves out links, IDs and the credentials it recognizes, but read it before you share it. If Facebook crashes before you can reach the settings, attach the newest `crash-` file from Download/Hushfacebook instead. The file identifies the Facebook build and the patch bundle that produced it. Ideas go on the [feature form](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=feature_request.yml). When Morphe Manager misbehaves with every app, not only Facebook, [Morphe's own tracker](https://github.com/MorpheApp/morphe-manager/issues) is the place.

## Unsupported Version

Manager shows this before it patches, usually with "This is a different build of the same version". Facebook 588.0.0.42.55 comes in several builds, each compiled separately, and Hushfacebook declares only the two its patches are checked against, 707615136 for 64-bit phones and 707615135 for 32-bit ones. Download one of those as [Install](../README.md#install) describes and the warning goes away. **Proceed anyway** patches the build you have, but other builds haven't been tried, and if a patch can't find what it changes, patching stops and names it. The same check is why Manager won't offer the Facebook you installed from Google Play unless it's that exact build.

## Patching stops on one patch

A patch couldn't find the code it changes. The log's `Source:` lines name each patch bundle and its version, and the error below them names the patch that stopped. When that patch belongs to another Facebook source, such as De-ReVanced, the two sources are usually patching the same thing. Turn on **Settings → Advanced → Expert mode**, open the supported bundle again, tap **Disable all** on every other Facebook source tab and pick what you want on the **Hushfacebook** tab. If your Facebook was patched with **Disable Play Store updates** before, keep it picked on one tab. Don't uninstall Facebook or replace your signing key to get past an error like this.

If Hushfacebook still stops on the supported bundle with the other sources off, please [report it](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) with the full error, every `Source:` line and the patches you picked.

## The log says a patch goes on without something

Four patches work down a list of separate targets, `Block background ad prefetch`, `Block ad telemetry`, `Disable Audience Network` and `Hide suggested and promoted posts`. They stop only when a build has none of their list, so a `WARNING:` line naming a missing target means the patch still applied to the rest. On Facebook 582, `Hide suggested and promoted posts` says in one plain line that six feed units left Facebook before this build. That's expected and isn't a warning. Any warning is worth [reporting](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) with the log.

## Unexpected end of ZLIB input stream

The .apkm file is damaged, usually by a download that didn't finish. Delete it, download it again and check it against the SHA-256 under [Supported Facebook builds](../README.md#supported-facebook-builds).

## No space left on device

The phone ran out of storage partway through. Keep at least 2 GB free while you patch.

## Update required

Your Morphe Manager is older than this bundle can work with. Update it to the version [Install](../README.md#install) names and patch again.

## Package conflict

Android only installs an update signed with the same key as the app it replaces, and Meta's Facebook carries Meta's key, so Manager asks you to uninstall it first. That deletes what Facebook keeps on the phone and you'll sign in again, but your account itself isn't touched. A Facebook you patched earlier with another key hits the same wall ([Keep your signing key](../README.md#keep-your-signing-key)). A Root Mount install on a rooted phone goes over Meta's app and doesn't ask.

## INSTALL_FAILED_DUPLICATE_PERMISSION

Messenger, Facebook Lite, Meta Business Suite and Workplace declare two permissions under the same names as Facebook, and Android lets only one signing key own each name. `Install beside Meta's apps` renames Facebook's two, and it's on by default. If you patched without it, patch again with it, install over the top and then install the other app.

## Android blocks apps from unverified developers

Google has started checking that apps on certified Android phones come from a developer registered with it. Since [September 30, 2026](https://developer.android.com/developer-verification/guides) the check runs in Brazil, Indonesia, Singapore and Thailand on Android 7 or newer, and only for apps installed from the stores Google lists: Google Play and six phone makers' own stores, Samsung's Galaxy Store and Xiaomi's GetApps among them. Manager isn't on that list, so a patched Facebook still installs as before, even in those four countries. Google plans to check every app on certified phones everywhere in 2027.

Once that reaches your phone, expect the patched Facebook to be stopped. Manager signs it with your own key, which Google has no record of. Android Authority [passed on](https://www.androidauthority.com/how-to-bypass-android-developer-verification-sideloading-rules-3718002/) a user's report that Morphe-patched apps already count as unverified for that reason. Google's own pages don't say how it treats an app signed again. Updates would fail the same way. There are two ways through.

The first is Android's advanced flow. Turn on Developer options (tap **Build number** seven times under **Settings → About phone**), open them and turn on the setting that allows apps from unverified developers. Android asks you to confirm nobody is coaching you through it, restarts the phone and makes you wait a day before you confirm with your fingerprint or PIN. You can allow it for 7 days or for good. After that the install shows a warning that the app comes from an unverified developer, and **Install Anyway** goes ahead. If you picked 7 days, updates stop going through again once the week is up.

The second is a computer with adb, because Google doesn't check apps installed that way. In Manager, open **Settings → Storage management → Patched APKs**, tap Facebook and **Export** (Manager keeps that copy unless you turned off **Keep patched APKs**). Copy the file to the computer, plug the phone in with USB debugging on and run this with your file's name:

```
adb install -r patched-facebook.apk
```

`-r` installs over the Facebook already on the phone and keeps its data, as long as both carry the same key ([Keep your signing key](../README.md#keep-your-signing-key)).

If Android refuses Facebook some other way, please [report it](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) with the exact words it shows. That message is what will show how Google really handles a patched app.

## Facebook links from other apps don't open in Facebook

Android ties facebook.com links to Meta's signature, so it may stop sending them to a re-signed Facebook. **Supported links** under Links and sharing shows what Android reports and opens the page where you can select Facebook's addresses by hand. If they won't stay selected, another Meta app such as Messenger, Instagram or Meta App Manager holds them. The official Messenger claims facebook.com, www.facebook.com and m.me for itself, which is why those switches turn back off. Tap that app's row under Links and sharing (or open its Open by default page in Android's settings), turn off **Open supported links** there, or on newer Android pick **In your browser**, then select Facebook's addresses again. Links you tap inside Messenger are Messenger's to open, and the official app may keep them in its own browser. From there, share the page (or long-press the link and share it) and pick **Open in Facebook**.

## A second Facebook beside the first

If your phone came with Facebook built in, a patched Facebook can't install over it. Pick `Clone install under another package name` in Expert mode and the patched app installs beside it as `com.facebook.katana.hush`, called Facebook Clone. Both can be changed in the patch's options. Don't pick Morphe's Clone app as well.

Morphe's own **Clone app** patch, in the Morphe patches source that Manager already lists, installs Facebook again as `com.facebook.katana.morphe`. Select it with **Expert mode** together with Hushfacebook's patches. Open the copy once before other apps send it links, because Facebook only turns on its link handler at first start. A HushMessenger clone sends its profile and Facebook links there when you set its **Facebook package to open** to that ID.

## Facebook or Google Play offers an update

A patched Facebook can't install Meta's updates, so it stays on the version you patched until you patch a newer one. `Stop update prompts` stops Facebook's own prompts. Meta App Manager, which comes on Samsung and some other phones, posts notices of its own, and you can turn those off in its notification settings. To stop Google Play offering an update, pick `Disable Play Store updates` in Expert mode when you patch. Keep picking it every time after that, because a build without it counts as an older version and won't install over yours.

## Signing in keeps going back to verification

Some people get through the password and then land on a browser or Google verification that keeps coming back, even after they approve it. So far it's been accounts with a passkey. What got them in: sign in to facebook.com in a browser, remove the passkey in Accounts Center under Password and security, then sign in on the patched app again. Facebook then asks you to approve the login from another device, and that goes through. A patched Messenger that loops the same way gets in after the same change.

## Facebook through VLESS or another proxy

**In-app proxy** under Privacy sends Facebook's connections through an HTTP proxy, so you don't have to put the whole phone on a VPN. For VLESS, VMess, Trojan or Shadowsocks, add your server to a proxy app like v2rayNG, set its mode to **Proxy only** and connect. Then type `127.0.0.1:10808` in **In-app proxy** and restart Facebook. That's v2rayNG's local port, and it takes HTTP as well as SOCKS. In another app, use the local HTTP or mixed port it shows.

Facebook can't connect while the proxy app is off, so clear the row when you want your phone's normal connection back. Messenger and Instagram are separate apps. v2rayNG's **Per-app proxy** can send just those two through VLESS.

## Facebook keeps closing

After three crashes, freezes or memory kills within a minute of starting, Hushfacebook pauses itself. If you can't reach the settings at all, an empty file named `hushfacebook-safe-mode` in `Android/data/com.facebook.katana/files` pauses it too (a clone uses its own package's folder, like `com.facebook.katana.morphe`). Most file managers can't write there, but on newer Android versions a computer with adb can, with no root (seen working on Android 16):

```
adb shell touch /sdcard/Android/data/com.facebook.katana/files/hushfacebook-safe-mode
adb shell am force-stop com.facebook.katana
```

Open Facebook and the settings say Hushfacebook is paused. `Resume` deletes the file.

Android 11 answers that adb line with "Permission denied". The same file in `Android/media/com.facebook.katana` works too, and adb can usually write there when it can't write in `Android/data`:

```
adb shell mkdir -p /sdcard/Android/media/com.facebook.katana
adb shell touch /sdcard/Android/media/com.facebook.katana/hushfacebook-safe-mode
adb shell am force-stop com.facebook.katana
```

That one has been seen working on Android 11 and Android 16.

A pause only changes what the switches answer and every patch's code stays in place. So if Facebook keeps closing, or the file can't be made, patch again without the patch you suspect, signed with the same key, and install it over your copy. Your sign-in and settings stay. The next diagnostic report you export shows the error that closed Facebook.

## Going back to an earlier release

If a new Hushfacebook release misbehaves, you can patch the same Facebook build with an earlier release, as long as that release lists your Facebook version. Download its `.mpp` file from the [releases page](https://github.com/SysAdminDoc/Hushfacebook/releases) and add it in Manager under **Sources**, then **+** and **Local** ([Morphe's guide](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md)). Pick the file in Manager's file browser and tap **Add**. It takes the place of the Hushfacebook file you added before. Then open Facebook on the **Apps** tab and tap **Patch**, then **Use saved APK** (or give it the original APK you patched last time). Pick `Disable Play Store updates` again if you used it before, and install over your copy without uninstalling. On Android 16 that kept the sign-in and every setting. If Manager says **Package conflict** and offers to uninstall, it's holding a different signing key from the one your copy was made with. Don't uninstall, since that erases Facebook's data. Import your old key first ([Keep your signing key](../README.md#keep-your-signing-key)) and patch again. Switches the older release doesn't have are gone while it runs and come back with their old values once you update again.

An older release that doesn't list your Facebook version won't patch it. Moving Facebook itself to an older version counts as a downgrade, and Android won't install that over the top.

`Export settings` doesn't carry the signing key (Manager backs that up on its own), the Home header picture or a font file you picked, so keep those in mind before any reinstall.
