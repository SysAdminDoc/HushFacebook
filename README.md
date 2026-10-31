![Hushfacebook. Keep the people. Cut the noise.](assets/readme-hero.png)

<p align="center">
  <a href="https://github.com/SysAdminDoc/Hushfacebook/releases"><img src="https://img.shields.io/badge/version-0.20.0-0866FF" alt="Version 0.20.0"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/platform-Android%2011%2B-3DDC84" alt="Platform Android 11+">
  <img src="https://img.shields.io/badge/Facebook-585.0.0.47.54-0866FF" alt="Facebook 585.0.0.47.54">
  <img src="https://img.shields.io/badge/for-Morphe%20Manager%202.3.0%2B-8A2BE2" alt="For Morphe Manager 2.3.0 or newer">
</p>

<p align="center">
  <a href="https://ko-fi.com/X8K126YVER">
    <img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Buy me a coffee on Ko-fi" />
  </a>
</p>

# Hushfacebook

Hushfacebook is a set of [Morphe](https://morphe.software) patches for the Facebook app on Android. You bring the Facebook APK, Morphe Manager patches it on your phone, and you get a Facebook with a settings screen of its own. Sponsored and suggested posts go away, and so do the ads in Stories, Reels, search and Marketplace. You can save reels, videos, stories and photos, open links in your own browser, hide the Reels tab and send Facebook less about what you do.

Each row in the [patch table](#patches) says how its switch starts. The ad filters and a handful of others start on, and most of the rest start off until you turn them on. On a computer, [HushFacebook Web](https://github.com/SysAdminDoc/HushFacebookWeb) is a separate userscript for Facebook in the browser.

This project has no connection to Meta or to the Morphe project. Neither endorses it, and neither wrote it.

The latest release is [v0.19.0](https://github.com/SysAdminDoc/Hushfacebook/releases/tag/v0.19.0), with 105 patches. It includes all changes since v0.18.1, among them Save and Download buttons under posts, switches that take Like, Comment, Share or Send off posts and reels, a cast button for feed videos and support for 32-bit phones, as the [changelog](CHANGELOG.md) describes.

[Add to Morphe](https://morphe.software/add-source?github=SysAdminDoc%2FHushfacebook) | [Download a release](https://github.com/SysAdminDoc/Hushfacebook/releases/latest) | [Browse the patches](#patches)

> [!IMPORTANT]
> Back up your signing key after your first patch. Android only installs an update over your patched Facebook when it's signed with the same key, and without that key you'd have to uninstall, which deletes Facebook's data. [Keep your signing key](#keep-your-signing-key) shows how.

## Install

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) 2.3.0 or newer.
2. Add Hushfacebook as a patch source: https://morphe.software/add-source?github=SysAdminDoc%2FHushfacebook
3. Download Facebook 585.0.0.47.54 from [APKMirror](https://www.apkmirror.com/apk/facebook-2/facebook/). Take the bundle labelled (arm64-v8a) (120-640dpi) (Android 11+), build 476015655, or on a phone with only a 32-bit processor the one labelled (arm-v7a) (320dpi) (Android 11+), build 476015543. Both are .apkm files.
4. In Morphe Manager, tap **Select other apps**, then **Open APK file**, and pick that file. Android may ask for file access the first time.
5. Patch with the default selection and install. If Meta's own Facebook is on the phone, Android makes you uninstall it first, as [Package conflict](#package-conflict) explains.

The default selection is every patch but three, so you don't need Expert mode. To add `AMOLED black theme`, `Material You theme` or `Disable Play Store updates`, turn on **Settings → Advanced → Expert mode** in Manager before you open the file, then pick them on the **Hushfacebook** tab. If you saved an Expert mode selection before, Manager keeps using it, so tap **Enable recommended patches** once to pick up the patches that joined the default selection since. That unticks the three above, so pick the ones you want again before you patch. Without `AMOLED black theme` dark mode stays Facebook's dark gray, and Hushfacebook's Appearance page says the theme isn't in your build. If you also use another Facebook patch source, check that the two don't patch the same thing ([Patching stops on one patch](#patching-stops-on-one-patch)).

### Keep your signing key

Morphe Manager signs the patched Facebook with a key it makes on your phone, and every update has to carry that same key. Back it up right after your first patch, under **Settings → System → Import & export → Signing key** in Manager, then tap **Export**. The button only shows up once you've patched something. Keep the `Morphe.keystore` file somewhere private, because anyone who has it can sign an APK your phone will take as an update.

On a new phone, import the key in the same place before you patch anything. Reinstalling Manager or clearing its storage makes a new key, and Morphe's settings backup doesn't include it. With a different key, the only way forward is to uninstall the patched Facebook, which deletes its data. A Root Mount install sits over Meta's own Facebook and doesn't have this problem. Morphe's own guide is [Backup and keystore](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md).

## Settings

<p><img src="assets/settings-overview.png" width="320" alt="Hushfacebook settings with search, Pause and expandable sections"><img src="assets/settings-downloads.png" width="320" alt="Downloads expanded beneath its heading on the main settings page"></p>

There are three ways in. Long-press the Facebook logo at the top of your feed. Long-press Facebook's icon on your home screen and tap **Hushfacebook** (if it isn't there, open Facebook once and try again, and check **Settings shortcut on the app icon** under Menu is on). Or open Facebook's Menu, expand **Settings and privacy** and tap **Hushfacebook settings** at the end. Each has a switch under Menu in the settings (**Open settings from the logo**, **Settings shortcut on the app icon** and **Settings row in Menu**), and the last one still on stays on, so you can always get back in.

Every section is on the main settings page. Tap a heading to open its controls underneath, then tap it again to collapse them. Several sections can stay open. Search is below the title, and **Pause** is in the top bar. **Support** expands to show Report a problem and Restart Facebook. Search reaches every setting, and you can flip a switch right in the results. Clearing search returns to your open sections and position.

Switches act straight away, with no restart and no new patching, but what's already on screen stays as it was until Facebook draws it again. A reel keeps the buttons it was built with, and rows already in the feed stay until it loads fresh posts. The Stories tray switch shows the next time you pull the feed down to refresh. A row that tells you to restart Facebook, such as the font and tab switches, waits for that restart.

Changing one of those brings up a **Restart Facebook** row at the top of the page. A tap reopens settings with your sections and position restored. You can also restart from **Support** whenever Facebook needs a fresh start. **Report a problem** opens a GitHub bug report with your versions and switches filled in.

**Pause Hushfacebook** turns every switch off from the next start without forgetting your choices. A few things stay in, Lock Facebook among them, and the screen lists them. If Facebook crashes or freezes within a minute of starting three times in a row, Hushfacebook pauses itself and the screen says so.

Morphe Manager can back up your patch choices but not these switches. **Export settings** saves them to a JSON file, word lists included, and **Import settings** reads one back on this phone or a new one. It shows what the file would change before anything does. **Check for new Hushfacebook releases** under Updates is off until you turn it on. Then it asks GitHub once a day and tells you when there's a newer release, and you update through Morphe Manager as usual.

The settings follow the language Facebook shows. German, Spanish, Indonesian, Brazilian Portuguese, Turkish, Vietnamese, Simplified Chinese and Traditional Chinese (Taiwan) are translated. Other languages get English. [Translating](CONTRIBUTING.md#translating) explains how to fix a string or add a language.

To keep Reel downloads in More without the sidebar button, leave **Download** on under **Reels and Watch** and turn off **Download button on reels** on the same page.

**Cast button on reels**, on the same page, adds a cast button under Download on a reel's sidebar. Like the feed one below, it hands the reel to a casting app you have, and it works with Download off too. It starts off.

**Hide WhatsApp on reels**, also under **Reels and Watch**, removes the direct WhatsApp button beside a reel or video. It starts off. WhatsApp stays available in the Share menu.

**Inline feed video controls**, under **Video and audio**, adds a separate play/pause button to feed videos. It starts off. With **Tap to play** on as well, Facebook's own Autoplay setting stays as you set it so the button can show, and videos still wait for you. Tap the video itself for full screen. Live videos and ads keep their own controls.

**Cast button on feed videos**, right below it, puts a cast button in a feed video's top right corner. Facebook has no casting of its own anymore, so the button hands the video to a casting app you have, like Web Video Caster. It starts off.

### Blocking Reels

Reels turn up in several places, and no one switch covers them all. Searching the settings for "block reels" brings up a map of them.

| Where Reels show up | What blocks them |
|---|---|
| Rows of reels between posts in your feed | **Hide Reels in the feed**, under News feed. |
| Reels and videos that start playing by themselves | **Tap to play**, under Video and audio. |
| The Reels tab in Facebook's tab bar | **Hide the Reels tab**, under Reels and Watch, after a restart. Facebook's own setting works too, under Settings, Tab bar, Customize the bar, then Hide next to Reels. |
| The dot or new count on the Reels tab | **Hide the Reels tab dot**, under Reels and Watch. |
| The "Are you interested in this reel?" prompt | **Hide reel interest prompts**, under Reels and Watch. |
| Everything except Marketplace | **Marketplace only**, under Opening Facebook. After a restart it keeps Marketplace, Notifications and Profile/Menu and drops the other tabs. |
| Everything except Events | **Events only**, under Opening Facebook. After a restart it keeps Events, Notifications and Profile/Menu and drops the other tabs. Marketplace only wins if both are on. |

## Patches

Each row says what the patch does, how its switch starts and which settings section it's in. A row that says "with no switch" works as soon as you patch it in.

| Patch | What it does |
|---|---|
| `Accent color` | Swaps Facebook's blue on links, buttons, switches and the selected tab for a color you like better. Nothing changes until you pick one. Pick a color in Hushfacebook settings > Appearance. |
| `Badge and logo colors` | Paints the count badges on the tabs, the Facebook logo at the top of Home and the selected tab in colors you pick. Nothing changes until you type a color. Set them in Hushfacebook settings > Appearance. |
| `Allow screenshots` | Lets you take screenshots and screen recordings on the pages where Facebook blocks them, so you can keep a copy of what's on screen. Starts off. Turn it on in Hushfacebook settings > Privacy. |
| `AMOLED black theme` | Makes Facebook's dark mode black, or a dark color you pick, instead of dark gray, which can save battery on OLED screens. Cards and menus stay a shade lighter than the page. Turn on Black cards in Hushfacebook settings > Appearance to make them black too, and Lines between posts there for a thin gray line between posts in the feed. Light background color there gives light mode a soft color in place of white. Turn on dark mode in Facebook first. It isn't selected by default because undoing it means patching again. Turn on Expert mode in Manager to pick it. |
| `Block ad telemetry` | Stops Facebook watching for screenshots of ads and reporting which apps you install after seeing ads, and stops several of the ways it sends your phone's advertising ID, so less of what you do feeds its ad tracking. Works as soon as you patch it in, with no switch. |
| `Block background ad prefetch` | Stops the jobs Facebook runs in the background to download ads, and the model it ranks them with, before you ever see them. Works as soon as you patch it in, with no switch. |
| `Block background-return feed refresh` | Keeps your place in the feed when you come back to Facebook within ten minutes, or any time with No time limit on, so you don't lose what you were reading. A second switch, Back leaves Home where it is, stops Back on a scrolled Home from jumping to the top and reloading. Both start off. Turn them on in Hushfacebook settings > News feed. |
| `Block Instant Games ads` | Games you play inside Facebook show no ads, so you can keep playing without breaks. A rewarded ad gives no reward unless you also turn on the switch that counts it as watched. On by default. Turn it off in Hushfacebook settings > Menu. |
| `Block promotional notifications` | Blocks the kinds of notification you pick, such as trending videos, memories and birthdays, so you get fewer pings you don't care about. Messages, friend requests, comments and login alerts always come through. Starts off. Turn on the kinds you want in Hushfacebook settings > Notifications. |
| `Block screenshot detection` | Stops Facebook noticing when you take a screenshot or record the screen, in the feed, Reels, chats and everywhere else, so it can't react to it. Starts off. Turn it on in Hushfacebook settings > Privacy. |
| `Hide typing indicator` | Others don't see that you're typing, in chats that open inside Facebook and in comment boxes, so you can write without anyone watching. Your messages send as usual. Starts off. Turn on the parts you want in Hushfacebook settings > Privacy. |
| `Hide read receipts` | People you chat with in chats that open inside Facebook don't see that you've read their messages, so you can read now and reply later. Replying may still show you've read it. Starts off. Turn it on in Hushfacebook settings > Privacy. |
| `Clean up Reels` | Hides clutter on reels, such as the Follow button, comment previews, Remix and Use template buttons and Threads cards between reels, so more of the video shows, and can play each reel once instead of over and over, keep its original audio instead of a Meta AI dub, drop the search suggestions under the caption, over the comments and at the top of a profile's reels, or hide the like, comment and share counts, the Like, Comment, Share, Save or speaker button, or the line naming the reel's sound. It can also open a video you tap in the feed on its own, with no reels after it, stop the "This is taking a while" message when a video is slow to load, and play whole reels in the feed's Reels row instead of the same second over and over, or keep the Comment button on reels with comments turned off so you can read them, and hide the one-tap Subscribe button some authors get instead of Follow, or Search and the button beside it at the top of a video you open, and show when a reel or video was posted. Its Burn-in guard can fade a reel's buttons, caption and bars when you leave them alone, so they don't burn into the screen. Starts off. Turn on the parts you want in Hushfacebook settings > Reels and Watch. |
| `Default playback quality` | Plays videos, reels and video stories at the quality you choose, such as Data saver or up to 720p, instead of what Facebook picks. Lower saves data, higher looks sharper. Starts off. Turn it on and pick a quality in Hushfacebook settings > Video and audio. |
| `Default comment order` | Opens comments in the order you choose, Most relevant, Newest, All comments or Oldest, instead of the one Facebook picks. Handy if you always want the newest first. Starts off. Turn it on and pick an order in Hushfacebook settings > Comments and writing. |
| `Disable Audience Network` | Stops Facebook serving its ads inside other apps on your phone. Those apps show their own ads or none, and their rewarded ads may fail. Works as soon as you patch it in, with no switch. |
| `Disable Play Store updates` | Stops Google Play offering Facebook updates, which a patched app can't take anyway. It isn't selected by default because once it's installed, a build without it can't install over it. It doesn't work on a Root Mount install. Turn on Expert mode in Manager to pick it. |
| `Don't send reel watch history` | Stops sending Facebook the list of reels you've watched, so it learns less about what you watch. Reels you've already seen may come back. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. |
| `Download any reel` | Adds a Download button beside every reel and a Download row to its More menu, so you can keep reels on your phone. Videos save at the quality you set, best by default. The More menu also gets Copy caption, which copies the reel's whole caption. On by default. Turn either off in Hushfacebook settings > Reels and Watch. |
| `Download any video` | Adds Download to phone to the menu of videos in the feed and in Watch, so you can save them to watch later. A video comment gets Save video when you long-press it. Videos save at the quality you set, best by default. Starts off. Turn it on in Hushfacebook settings > Downloads. |
| `Download any story` | Adds Save to the menu of any story, including stories with music, so you can keep the ones you like. Videos save at the quality you set, best by default. On by default. Turn it off in Hushfacebook settings > Stories. |
| `Download any photo` | Shows Save photo on every photo you open, even where the poster turned saving off, and saves the largest size so you get the best copy. A GIF comment gets Save GIF when you long-press it. On by default. Turn it off in Hushfacebook settings > Downloads. |
| `Following feed on Home` | Makes Home show the newest posts from the friends, groups and Pages you follow instead of Facebook's ranked feed, so you see what they posted rather than what Facebook picks. Home shows, right below it, asks Facebook for one of the Feeds tab's filters (Friends, Favorites, Groups or Pages) instead. When that filter has nothing new, Facebook can fill Home with suggested posts and reels, so pair it with Hide "Suggested for you" posts and Hide Reels in the feed. Starts off. Turn it on in Hushfacebook settings > Opening Facebook. |
| `Force dark mode` | Keeps Facebook in dark mode whatever its own setting says. Useful on tablets, where Facebook's settings have no Dark mode. Starts off. Turn it on in Hushfacebook settings > Appearance, then restart Facebook. |
| `Frame step` | With a reel or video paused, a tap on its right side steps one frame forward and a tap on its left side one frame back, so you can stop on the exact moment you want. Starts off. Turn it on in Hushfacebook settings > Video and audio. |
| `Hide affiliate product links` | Removes the shopping cards that affiliate links add to reels, feed posts and comments, plus Shop now cards on reels, so you see fewer sales pitches while you scroll. On by default. Turn it off in Hushfacebook settings > News feed. |
| `Hide AI-detected posts` | Removes posts and reels Facebook marked as made with AI, posts creators labeled as AI, posts with Meta's AI characters and Meta AI cards in the feed. Only the Meta AI cards switch starts on. Change them in Hushfacebook settings > News feed and Reels and Watch. |
| `Hide carrier data banners` | On carriers with Facebook Flex, hides the Data mode bar with its buy mobile data button, or the purple Free mode bar, so the feed gets that space back. Both switches start off. Turn them on in Hushfacebook settings > Appearance. |
| `Hide sponsored Marketplace listings` | Removes ads and boosted listings from Marketplace's feed and search results, so you see more of what people are selling. Listings people post stay. It also reads rules typed into Marketplace search: "word" keeps titles with the word, -word hides titles with it and in:place keeps listings in that place, and an Adv. Filters button writes them for you. The search rules and the button are off until you turn them on in Hushfacebook settings > Marketplace. |
| `Hide posts by words` | Hides feed posts with words you list, or from people, Pages or sites you list, so you skip topics you're tired of. Photo, video, link and colored background posts, or every post a Page wrote, can go too. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Hide post prompts` | Removes the strip Facebook adds to some posts, like "Are you interested in this post?", "Show less" or follow suggestions, so posts look less cluttered. No gap is left. On by default. Turn it off in Hushfacebook settings > News feed. Three more switches there, Hide the Follow link on posts, Hide counts on posts and Hide the reaction preview on posts, start off. |
| `Hide seen posts` | Keeps posts you've already scrolled past out of the feed when it loads again, for 1, 3, 7, 30 or 90 days, so you see something new. The list stays on your phone. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Hide Meta AI questions under posts` | Removes the row of Meta AI questions Facebook adds under some posts, for a cleaner feed. The post and its buttons stay. On by default. Turn it off in Hushfacebook settings > News feed. |
| `Hide Meta AI comment summaries` | Removes Meta AI's summary of the comments from the top of the comment sheet and from under posts, so you read what people actually wrote. Starts off. Turn it on in Hushfacebook settings > Comments and writing. |
| `Comment sheet options` | Small fixes for comments. Stop a long press on Like from opening reactions, hide the GIF and sticker buttons, show every reply without tapping View replies, load a whole reply thread as you scroll, open more replies with a tap anywhere on their row, hide the comments under feed posts, hide the thumbs-down on comments or turn it into a Block button, hide Like on comments, hide Related groups under group posts, hide comments with shop links or your words, hide who is typing and the new comments pop-up, hide the emoji row over the comment box, and hide the comment box under full-screen videos. Starts off. Turn on the parts you want in Hushfacebook settings > Comments and writing. |
| `Open comments at full height` | Opens the comment sheet at the full height of the screen instead of part way up, on posts, videos, reels and stories. On reels and videos, Comment opens that sheet instead of the split view. A second switch pauses a reel while its comments are open, and a third lets a tap on the reel above a half-open sheet play or pause it. All start off. Turn them on in Hushfacebook settings > Comments and writing. |
| `Hide the Feeds header` | Takes the title row and the All, Favorites, Friends, Groups and Pages filters off the top of the Feeds tab, so it opens straight on posts. Starts off. Turn it on in Hushfacebook settings > News feed, then restart Facebook. |
| `Keep post dates` | Keeps the date under the poster's name, so you can always tell how old a post is. Facebook's newer header can swap it for rotating details, and on some phones the line goes blank. On by default. Turn it off in Hushfacebook settings > News feed. |
| `Show full post text` | Shows all of a post's text in the feed, so you don't have to tap See more. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `See who shared` | Adds See who shared to a post's menu, which opens Facebook's list of the people who shared it. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Turn off auto-translation` | Shows posts and reel captions in the language they were written in, instead of Facebook's automatic translation, so you read people's own words. See translation still works. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Hide sponsored posts` | Removes sponsored and promoted posts from the news feed, with no gap left behind, so you see fewer ads. It also keeps the floating ad button off an ad's comments. On by default. Turn it off in Hushfacebook settings > News feed. |
| `Hide sponsored profile posts` | Removes the ads between posts on someone's profile or a Page, so you only see what they posted. On by default. Turn it off in Hushfacebook settings > News feed. |
| `Hide profile notes` | Hides the note bubble on profile pictures, the Drop a thought prompt on your own profile included. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Local profile counts` | Shows friends, followers and following counts you type yourself on your own profile, on this phone only. Other profiles and Facebook's real numbers aren't touched. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Round stories tray` | Draws the stories row on Home as round pictures at a size you pick, like Instagram. Starts off. Turn it on in Hushfacebook settings > Stories. |
| `Post action buttons` | Adds Facebook's own Save button next to Like, Comment and Share under posts, and a Download button on posts with a video or photos that saves them the way the post's menu does. It can also take Like, Comment, Share or Send out of that row. Each has its own switch, and they all start off. Find them in Hushfacebook settings > News feed. |
| `Hide sponsored reels` | Removes ads from Reels and Watch, including product banners over reels and ads in the middle of videos, so you can watch without interruptions. On by default. Turn it off in Hushfacebook settings > Reels and Watch. |
| `Hide sponsored search results` | Removes the ads from Facebook's search results, so you see the people, Pages and posts you searched for. On by default. Turn it off in Hushfacebook settings > Search. |
| `Hide sponsored stories` | Removes ad cards from the story viewer, so swiping through stories shows only what people posted. On by default. Turn it off in Hushfacebook settings > Stories. |
| `Hide Stories tray` | Hides the row of stories at the top of the feed, the stories between posts, or the "What's on your mind?" row at the top of Home, so you reach posts sooner. Starts off. Turn on the parts you want in Hushfacebook settings > News feed. |
| `Hide suggested stories` | Removes stories Facebook suggests from people and Pages you don't follow, and the "Find friends from contacts" card, so the Stories tray sticks to people you know. On by default. Turn it off in Hushfacebook settings > Stories, where two more switches, both off at first, also hide the Pages you follow and the stories you've already watched. |
| `Hide Reels in the feed` | Removes the rows of reels between posts and the reels Facebook adds where your feed ends, so the feed sticks to posts. A reel a friend posts stays. Starts off. Turn it on in Hushfacebook settings > News feed. |
| `Hide tabs` | Takes the tabs you pick off the tab bar, such as Friends, Marketplace, Groups, Gaming or Events, so the bar only has what you use. Each page stays in the Menu. Starts off. Turn on the ones you want in Hushfacebook settings > Tabs and navigation, then restart Facebook. |
| `Hide the Reels tab` | Takes the Reels tab, called Video on some accounts, off the tab bar, so Reels isn't one tap away. Reels in your feed and reel links still open. Starts off. Turn it on in Hushfacebook settings > Reels and Watch, then restart Facebook. |
| `Hide the Reels tab dot` | Takes the new-item dot and count off the Reels tab, called Video on some accounts, so it stops calling for your attention. Other tabs keep theirs. On by default. Turn it off in Hushfacebook settings > Reels and Watch. |
| `Hide tab badges` | Takes the dot and count off the tabs you pick, and Facebook's count off its app icon, so the app nags you less. Notifications still arrive and still show in the Notifications tab. Starts off. Turn on the ones you want in Hushfacebook settings > Tabs and navigation. |
| `Hide reel interest prompts` | Removes the "Are you interested in this reel?" prompt, so reels play without the interruption. On by default. Turn it off in Hushfacebook settings > Reels and Watch. |
| `Hide suggested and promoted posts` | Removes what Facebook adds to the feed besides ads, like "Suggested for you" posts, "People you may know" and suggested groups, so you see more from people you follow. Most of its switches are on by default. Change them in Hushfacebook settings > News feed. |
| `Hide Meta AI in search` | Removes the Meta AI answer and Ask Meta AI prompts from search results, and stops suggestions from sending your search to Meta AI. You get plain results for people, groups, Pages and posts. On by default. Turn it off in Hushfacebook settings > Search. |
| `Don't save search history` | Stops Facebook from adding what you search for, and the pages and profiles you open from a search, to your search history, so there's nothing to clear by hand. Searches already there stay until you delete them. Starts off. Turn it on in Hushfacebook settings > Search. |
| `Search date range` | Adds a from and to month picker to the Date posted filter in post search, in place of the list of years, so you can narrow results to a stretch of time. Starts off. Turn it on in Hushfacebook settings > Search. |
| `Hide the Get Messenger card` | Hides the "Get the Messenger app" card at the top of Chats when Messenger is already installed. A patched Facebook may not see that Messenger is there, so it keeps showing the card. On by default. Turn it off in Hushfacebook settings > Chats and Messenger. |
| `Clean up Facebook's chat list` | Tidies Chats inside Facebook. One switch empties the row of notes and who's active above your chats, and another removes promotional banners like the one asking you to turn on notifications. A third takes the Meta AI button off the search row and the search screen. Your chats stay. Starts off. Turn on the parts you want in Hushfacebook settings > Chats and Messenger. |
| `Hide Menu promotions` | Hides the Upgrades and Also from Meta sections and the Muse card in Facebook's Menu, so the Menu has fewer ads for Meta's products. Settings, Help and your shortcuts stay. On by default. Turn it off in Hushfacebook settings > Menu. Switches there, off by default, also hide the Meta AI shortcut or the whole Help and support section and keep Settings and privacy open, and a list there hides the Menu tiles and Help and Settings rows you pick, such as Gaming or Orders and payments. |
| `Hide Meta upsells` | Hides Meta's pushes for its other products, such as Edits, Threads cross-posting, Meta Verified, avatar stickers and Meta AI's Imagine, so you see fewer sales pitches. Starts off. Turn on the parts you want in Hushfacebook settings > Meta's other products. |
| `Hold a reel for 2x` | Hold a reel to play it at double speed, and let go to go back to normal. Handy for skimming. The reel's more button still opens Facebook's long-press menu. A second switch does the same for videos playing in the feed and in Watch, and a third keeps the plain 2x pill without Facebook's "Pull down to lock 2x speed" hint. All start off. Turn them on in Hushfacebook settings > Reels and Watch. |
| `Hold back analytics uploads` | Stops Facebook sending its usage reports in the background and skips the learning jobs it runs on your phone, so less about how you use the app leaves it. A second switch holds back the camera roll processing that uploads your photos and details about them for sharing suggestions. Both start off. Turn them on in Hushfacebook settings > Privacy, then restart Facebook. |
| `Hushfacebook in the Menu` | Adds a Hushfacebook settings row to Facebook's Menu, at the end of Settings and privacy, so the settings are easy to find. A Saved row joins it while the Saved shortcut switch is on, a Watch history row while the Watch history shortcut switch is, and a Free stuff row, Marketplace's free listings, while the Free stuff shortcut switch under Marketplace is. On by default. Turn it off with Settings row in Menu in Hushfacebook settings > Menu, unless it's the last way into the settings still on. |
| `Hushfacebook settings` | Adds Hushfacebook settings to Facebook, where you turn features on or off, pause Hushfacebook and back up your choices. To open them, long-press the Facebook logo at the top of your feed or Facebook's app icon. Its App name option shows a name you pick under the icon, and Icon style can make the icon white on black or black on white. Other patches need it, so keep it selected. |
| `Install beside Meta's apps` | Lets the official Messenger, Facebook Lite, Business Suite and Workplace install next to your patched Facebook. Without it, Android refuses them because they share two permissions with Facebook. Root Mount installs don't need it. Works as soon as you patch it in, with no switch. |
| `Keep feed videos muted` | Feed videos start with the sound off every time you open Facebook, and only a tap on a video's speaker or the volume up key turns it on. Starts off. Turn it on in Hushfacebook settings > Video and audio. |
| `Keep the progress bar` | Keeps every reel's progress bar full size and moving smoothly, with the time above it, so you can drag it without tapping first. Also keeps a full-screen video's controls on screen until you tap, and another switch lets a tap anywhere on the bar jump there. Starts off. Turn it on in Hushfacebook settings > Video and audio. |
| `Keep the reel speed` | A speed you pick in a reel's menu stays for the next reels, even after Facebook restarts, so you don't set it every time. Pick normal speed to go back. On by default. A second switch for feed and Watch videos starts off. Live videos that have ended get Playback speed in their gear menu too, also on by default. All of it is in Hushfacebook settings > Reels and Watch. |
| `Marketplace only` | Turns Facebook into a Marketplace app for buying and selling without the feed. The tab bar keeps only Marketplace, Notifications and your profile or Menu, and Facebook opens on Marketplace. The Events only switch does the same for Events once you pin Events in Facebook's Customize the bar. Both start off. Turn one on in Hushfacebook settings > Opening Facebook, then restart Facebook. |
| `Material You theme` | Gives Facebook the colors of your wallpaper on Android 12 and newer, so it matches your phone. Dark mode takes them everywhere, and light mode on its grays, blues and selected tab. It isn't selected by default because undoing it means patching again. Turn on Expert mode in Manager to pick it. |
| `Newest reels first` | Plays the newest posted of the reels Facebook has loaded for you first in the Reels tab, instead of Facebook's own pick. It can't fetch newer reels. A second switch lists a profile's Reels tab with its most viewed reels first. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. |
| `Open links in external browser` | Opens web links in your own browser instead of Facebook's built-in one, without Facebook tracking the click. Facebook pages still open in the app. On by default. Turn it off in Hushfacebook settings > Links and sharing. A second switch, on by default under Privacy, stops Facebook's browser keeping a history of the links you open in it. A Page's Events link opens on that Page's Events tab instead of Home, whichever way the switches are set. |
| `Open Messenger from the top bar` | Makes the Messenger icon at the top of Facebook open the Messenger app instead of Facebook's own Chats, if you'd rather chat in Messenger. Without Messenger installed, Chats opens as before. Starts off. Turn it on in Hushfacebook settings > Chats and Messenger. Also carries Leave calls and chats to Messenger, a separate switch that starts off and leaves calls and chat notifications to Messenger while it's installed and enabled. Restart Facebook after changing the switch or whether Messenger is installed or enabled, so call routing updates. |
| `Open on a chosen tab` | Opens Facebook on the tab you pick when you start it from its icon, so you can skip the feed. It's Marketplace unless you change it. Starts off. Turn it on in Hushfacebook settings > Opening Facebook. |
| `Picture-in-picture` | Keeps reels and videos from the Reels and Video tabs, or full screen, playing in a small window when you leave Facebook, so you can watch while using another app. Needs Android 12 or later. Starts off. Turn it on in Hushfacebook settings > Video and audio. |
| `Pinch to zoom` | Pinch a story or a photo on someone's profile to zoom in, like you can on photos in the feed. It goes back when you let go, unless you turn on Stay zoomed under it, which keeps the zoom until you tap or press Back. Starts off. Turn it on in Hushfacebook settings > Stories. |
| `Restore screens on re-signed builds` | Fixes profiles, photos, posts and some Settings pages that won't open on a patched Facebook, lets a Messenger or Facebook Lite patched in the same Manager sign in through it, and opens Instagram and Threads links in a patched Instagram or Threads. Root Mount installs don't need it. Works as soon as you patch it in, with no switch. |
| `Resume long videos` | A video longer than two minutes picks up where you left off the next time it plays, in the feed or full screen, so you don't lose your place. Reels, live videos and ads start as usual. Remember what you watch, a second switch, keeps a list on your phone of the reels, videos and stories you watched, under Recently watched. Both start off. Turn them on in Hushfacebook settings > Video and audio. |
| `Sanitize sharing links` | Takes Facebook's tracking tags off links you share or copy, so you share a clean link. A facebook.com/share/ link is made for one share, so Facebook can still trace that kind back to you, and a switch that starts off shares the post's own link in its place. On by default. Both are in Hushfacebook settings > Links and sharing. |
| `Send chat photos and videos at original quality` | Photos and videos you send from chats that open inside Facebook go out as the originals instead of smaller copies, so they arrive sharp. Very large files are still shrunk. Starts off. Turn it on in Hushfacebook settings > Chats and Messenger. |
| `Share sheet items` | Lets you hide items you never use from Facebook's share sheet, such as WhatsApp, Meta AI or Copy link, so the ones you want are easier to reach. Nothing is hidden until you pick. Choose them in Hushfacebook settings > Links and sharing, where two switches that start off also take away the buttons that send to the people you picked as a new group chat and the row of people Facebook suggests sending to. |
| `Show View profile on Marketplace sellers` | Adds View profile to every seller's Marketplace page. It opens their regular Facebook profile, so you can check who you're buying from. Facebook shows that button to only some accounts. On by default. Turn it off in Hushfacebook settings > Marketplace. |
| `Skip silent parts` | Plays videos, reels and stories past the silent stretches in their sound, so talks and how-tos get to the point sooner. Starts off. Turn it on in Hushfacebook settings > Video and audio. With Resume long videos patched too, a video's menu can keep the silent parts in that one video. |
| `Start on x86 devices` | Stops Facebook crashing or freezing at start on x86 devices, such as computer emulators and x86 Chromebooks. Most phones and tablets don't need it, and it doesn't change how they start. Works as soon as you patch it in, with no switch. |
| `Stop Story auto-advance` | Keeps each story on screen until you tap or swipe, so you can take your time with it. A second switch replays a finished story from the start. Starts off. Turn it on in Hushfacebook settings > Stories. |
| `Stop update prompts` | Stops Facebook asking you to update, since a patched app can't install Meta's updates anyway. Meta App Manager's update prompts and chat prompts aimed at older versions go too. On by default. Turn it off in Hushfacebook settings > Updates. |
| `Tab bar at the bottom` | Moves Facebook's tab bar to the bottom of the screen on accounts that have it at the top, where it's easier to reach. `Tab bar position` can also put it at the top, as older Facebook had it, or leave it where Facebook puts it. `Tab bar while scrolling` lets it slide away as you scroll or keeps it on screen, and another switch keeps the top bar on screen. One gives tablets and unfolded phones the phone layout so the bar can move there too, and one makes the top bar and the tab bar see-through. Another hides the phone's status bar on the main screen until you swipe down from the top. Three more take the Messenger button, its unread count or the Facebook logo out of the top bar. Starts off. Pick it in Hushfacebook settings > Tabs and navigation, then restart Facebook. The see-through and status bar switches sit under Appearance. |
| `Tag suggestions only after @` | Stops Facebook offering people to tag while you type ordinary words in posts and comments, so names don't pop up in your way. Typing @ still brings up the list. Starts off. Turn it on in Hushfacebook settings > Comments and writing. |
| `Tap to play` | Videos, reels, stories and music wait for your tap instead of starting by themselves, so nothing plays until you want it to. A separate switch adds a play/pause button to feed videos. The switches start off in Hushfacebook settings > Video and audio. |
| `Turn off double tap to like` | Stops a double tap on a reel, a video or a photo in the feed from liking it, so you don't like things by accident. A single tap still plays, pauses or opens the photo, and the Like button still works. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. |
| `Double tap to skip` | A double tap on the left or right edge of a reel or video skips back or forward 10 seconds, or 5 on one under 30 seconds. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. |
| `Tap shows the controls` | A tap on a playing reel shows its controls and lets it keep playing, so you can skip or mute without stopping it. The button in the middle still pauses. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. |
| `Open comments with a gesture` | Open a reel's comments with a double tap or a long press, on the sheet the Comment button opens, instead of liking or holding, and a feed post's with a long press on its photo or name line. Starts off. Turn it on in Hushfacebook settings > Reels and Watch. It also lets a long press on a post's text open that text to select part of it, a switch in Hushfacebook settings > News feed. |
| `Turn off haptics` | Stops the short vibrations Facebook plays when you tap and swipe in it, if you find them distracting. Your keyboard and your phone's own vibrations stay. Starts off. Turn it on in Hushfacebook settings > Appearance. |
| `Turn off HDR brightness` | Stops HDR videos and photos from turning your screen up to full brightness, which can be harsh in the dark. They keep their resolution. A second switch fixes pale, washed-out reels on phones that show them that way. Its switches start off. Turn them on in Hushfacebook settings > Video and audio. |
| `Turn off screen transitions` | Shows Facebook's tabs, Menu, photos and new screens at once, without the slide or zoom between them, so the app feels quicker. Swipes and animations inside a page stay. Starts off. Turn it on in Hushfacebook settings > Appearance. |
| `Unlock app icons` | Lets you pick the app icons Facebook keeps for Facebook Plus on its App icon page (Menu > Settings and privacy > App icon), so your home screen icon can be any of them. A second switch, Use the icon at the top of Home, puts your pick at the top of Home too. Both start off. Turn them on in Hushfacebook settings > Appearance. |
| `Home header text` | Shows your own words or picture at the top of Home instead of the Facebook logo. Words take Facebook's own colors for light and dark mode. Type them in Hushfacebook settings > Appearance > Home header text, or pick a picture under Home header picture. Blank keeps Facebook's logo. |
| `Upload Stories in HD` | Videos you post to your Story ask Facebook for its high upload quality instead of standard, so they can come out sharper. Starts off. Turn it on in Hushfacebook settings > Stories. |
| `Longer music on photo stories` | A song you add to a photo for your Story plays for up to 30 seconds instead of 15. Starts off. Turn it on in Hushfacebook settings > Stories. |
| `Use the phone's emoji` | Draws emoji with your phone's own emoji font instead of Meta's, so emoji in posts, comments and chats match your keyboard. Reactions and stickers stay as they are. Starts off. Turn it on in Hushfacebook settings > Appearance, then restart Facebook. |
| `Use the system font` | Draws Facebook's text in your phone's font instead of Meta's, or in a font file you pick, so Facebook matches the rest of your phone. Icons and emoji keep their own. Starts off. Turn it on in Hushfacebook settings > Appearance, then restart Facebook. |
| `View stories anonymously` | Keeps you off the viewer list of stories you watch, because Facebook isn't told which ones you've seen. Replying or reacting still shows you. Stories you watch to the end show as watched on this phone only, until that person posts a new one. Starts off. Turn it on in Hushfacebook settings > Stories. |

Morphe Manager's default selection has every patch except `AMOLED black theme`, `Material You theme` and `Disable Play Store updates`. The themes put their colors in when you patch, and the Play Store patch changes the version number Android sees, so undoing any of them means patching again. Turn on Expert mode in Manager to pick them.

`AMOLED black theme` shows in Facebook's dark mode, so turn that on first. `Material You theme` colors both modes. A tablet whose Facebook settings have no Dark mode row can use **Force dark mode** under Appearance. `Material You theme` takes its colors from your wallpaper on Android 12 and newer, and Android 11 gets a fixed palette built from Facebook's blue. `AMOLED black theme` has one option, Background color. Leave it blank for black, or give a dark six-digit color such as #0D1117. A color too light to keep text readable is refused, and the message says what to pick instead. You can change that color later without patching again, under **Background color** in Hushfacebook settings > Appearance. **Light background color** next to it swaps light mode's white for a light color such as #FFF8E7 and darkens text where it has to. Both rows take effect after a restart, and they're only there when Material You isn't in the same build. The two themes work together, with the background staying black (or your color) and the palette coloring what sits on top.

Videos save to your gallery at the quality you pick under Downloads, best by default. A running save shows its progress in a notification and at the top of the Downloads page, each with a Cancel button. Turn on **Quiet saves** there if the toasts at the start and end of a save bother you. A save that fails still says why.

Under Video and audio > Recently watched, Clear also discards watches waiting to be saved. Turning Remember what you watch off stops new entries and discards pending watches. It keeps the entries already on the list, and they still leave once they're older than Keep watched entries for (30 days unless you pick 7 days or until you clear it). The remove button on a row takes just that one off, with Undo under the list.

Under Opening Facebook, **Opening pause** makes you wait a few seconds before Facebook opens from its icon, and **Take breaks** covers the feeds and Reels with a break screen after the browsing time you pick. Chats, your profile and the Menu still work during a break. Both start off.

## Help

Questions about setting up, or about whether a Facebook build works, go in [Discussions](https://github.com/SysAdminDoc/Hushfacebook/discussions). For something that's broken, use the [bug form](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) and attach a diagnostic report from Hushfacebook settings. It leaves out links, IDs and the credentials it recognizes, but read it before you share it. If Facebook crashes before you can reach the settings, attach the newest `crash-` file from Download/Hushfacebook instead. The file identifies the Facebook build and the patch bundle that produced it. Ideas go on the [feature form](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=feature_request.yml). When Morphe Manager misbehaves with every app, not only Facebook, [Morphe's own tracker](https://github.com/MorpheApp/morphe-manager/issues) is the place.

### Unsupported Version

Manager shows this before it patches, usually with "This is a different build of the same version". Facebook 585.0.0.47.54 comes in several builds, each compiled separately, and Hushfacebook declares only the two its patches are checked against, 476015655 for 64-bit phones and 476015543 for 32-bit ones. Download one of those as [Install](#install) describes and the warning goes away. **Proceed anyway** patches the build you have, but other builds haven't been tried, and if a patch can't find what it changes, patching stops and names it. The same check is why Manager won't offer the Facebook you installed from Google Play unless it's that exact build.

### Patching stops on one patch

A patch couldn't find the code it changes. The log's `Source:` lines name each patch bundle and its version, and the error below them names the patch that stopped. When that patch belongs to another Facebook source, such as De-ReVanced, the two sources are usually patching the same thing. Turn on **Settings → Advanced → Expert mode**, open the supported bundle again, tap **Disable all** on every other Facebook source tab and pick what you want on the **Hushfacebook** tab. If your Facebook was patched with **Disable Play Store updates** before, keep it picked on one tab. Don't uninstall Facebook or replace your signing key to get past an error like this.

If Hushfacebook still stops on the supported bundle with the other sources off, please [report it](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) with the full error, every `Source:` line and the patches you picked.

### The log says a patch goes on without something

Four patches work down a list of separate targets, `Block background ad prefetch`, `Block ad telemetry`, `Disable Audience Network` and `Hide suggested and promoted posts`. They stop only when a build has none of their list, so a `WARNING:` line naming a missing target means the patch still applied to the rest. On Facebook 582, `Hide suggested and promoted posts` says in one plain line that six feed units left Facebook before this build. That's expected and isn't a warning. Any warning is worth [reporting](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) with the log.

### Unexpected end of ZLIB input stream

The .apkm file is damaged, usually by a download that didn't finish. Delete it, download it again and check it against the SHA-256 under [Supported Facebook builds](#supported-facebook-builds).

### No space left on device

The phone ran out of storage partway through. Keep at least 2 GB free while you patch.

### Update required

Your Morphe Manager is older than this bundle can work with. Update it to the version [Install](#install) names and patch again.

### Package conflict

Android only installs an update signed with the same key as the app it replaces, and Meta's Facebook carries Meta's key, so Manager asks you to uninstall it first. That deletes what Facebook keeps on the phone and you'll sign in again, but your account itself isn't touched. A Facebook you patched earlier with another key hits the same wall ([Keep your signing key](#keep-your-signing-key)). A Root Mount install on a rooted phone goes over Meta's app and doesn't ask.

### INSTALL_FAILED_DUPLICATE_PERMISSION

Messenger, Facebook Lite, Meta Business Suite and Workplace declare two permissions under the same names as Facebook, and Android lets only one signing key own each name. `Install beside Meta's apps` renames Facebook's two, and it's on by default. If you patched without it, patch again with it, install over the top and then install the other app.

### Android blocks apps from unverified developers

Google now checks that apps on certified Android phones come from a developer registered with it. Phones running Android 7 or newer in Brazil, Indonesia, Singapore and Thailand have done this since September 30, 2026, and Google plans to bring it to every country in 2027. Manager signs the patched Facebook with your own key, which Google has no record of, so a phone that checks can stop the install. Updates fail the same way. There are two ways through.

The first is Android's advanced flow. Turn on Developer options (tap **Build number** seven times under **Settings → About phone**), open them and turn on the setting that allows apps from unverified developers. Android asks you to confirm nobody is coaching you through it, restarts the phone and makes you wait a day before you confirm with your fingerprint or PIN. You can allow it for 7 days or for good. After that the install shows a warning that the app comes from an unverified developer, and **Install Anyway** goes ahead. If you picked 7 days, updates stop going through again once the week is up.

The second is a computer with adb, because Google doesn't check apps installed that way. In Manager, open **Settings → Storage management → Patched APKs**, tap Facebook and **Export** (Manager keeps that copy unless you turned off **Keep patched APKs**). Copy the file to the computer, plug the phone in with USB debugging on and run this with your file's name:

```
adb install -r patched-facebook.apk
```

`-r` installs over the Facebook already on the phone and keeps its data, as long as both carry the same key ([Keep your signing key](#keep-your-signing-key)).

If Android refuses Facebook some other way, please [report it](https://github.com/SysAdminDoc/Hushfacebook/issues/new?template=bug_report.yml) with the exact words it shows. Google hasn't said whether an app its owner signed again counts as unregistered, and that message is what will settle it.

### Facebook links from other apps don't open in Facebook

Android ties facebook.com links to Meta's signature, so it may stop sending them to a re-signed Facebook. **Supported links** under Links and sharing shows what Android reports and opens the page where you can select Facebook's addresses by hand. If they won't stay selected, another Meta app such as Messenger, Instagram or Meta App Manager holds them. The official Messenger claims facebook.com, www.facebook.com and m.me for itself, which is why those switches turn back off. Tap that app's row under Links and sharing (or open its Open by default page in Android's settings), turn off **Open supported links** there, or on newer Android pick **In your browser**, then select Facebook's addresses again. Links you tap inside Messenger are Messenger's to open, and the official app may keep them in its own browser.

### Facebook or Google Play offers an update

A patched Facebook can't install Meta's updates, so it stays on the version you patched until you patch a newer one. `Stop update prompts` stops Facebook's own prompts. Meta App Manager, which comes on Samsung and some other phones, posts notices of its own, and you can turn those off in its notification settings. To stop Google Play offering an update, pick `Disable Play Store updates` in Expert mode when you patch. Keep picking it every time after that, because a build without it counts as an older version and won't install over yours.

### Facebook keeps closing

After three crashes or freezes within a minute of starting, Hushfacebook pauses itself. If you can't reach the settings at all, an empty file named `hushfacebook-safe-mode` in `Android/data/com.facebook.katana/files` pauses it too (a clone uses its own package's folder, like `com.facebook.katana.morphe`). Most file managers can't write there, but on newer Android versions a computer with adb can, with no root (seen working on Android 16):

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

That one has been seen working on Android 16. It hasn't been tried on Android 11 yet.

A pause only changes what the switches answer and every patch's code stays in place. So if Facebook keeps closing, or the file can't be made, patch again without the patch you suspect, signed with the same key, and install it over your copy. Your sign-in and settings stay. The next diagnostic report you export shows the error that closed Facebook.

### Going back to an earlier release

If a new Hushfacebook release misbehaves, you can patch the same Facebook build with an earlier release, as long as that release lists your Facebook version. Download its `.mpp` file from the [releases page](https://github.com/SysAdminDoc/Hushfacebook/releases) and add it in Manager under **Sources**, then **+** and **Local** ([Morphe's guide](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md)). Pick the file in Manager's file browser and tap **Add**. It takes the place of the Hushfacebook file you added before. Then open Facebook on the **Apps** tab and tap **Patch**, then **Use saved APK** (or give it the original APK you patched last time). Pick `Disable Play Store updates` again if you used it before, and install over your copy without uninstalling. On Android 16 that kept the sign-in and every setting. If Manager says **Package conflict** and offers to uninstall, it's holding a different signing key from the one your copy was made with. Don't uninstall, since that erases Facebook's data. Import your old key first ([Keep your signing key](#keep-your-signing-key)) and patch again. Switches the older release doesn't have are gone while it runs and come back with their old values once you update again.

An older release that doesn't list your Facebook version won't patch it. Moving Facebook itself to an older version counts as a downgrade, and Android won't install that over the top.

`Export settings` doesn't carry the signing key (Manager backs that up on its own), the Home header picture or a font file you picked, so keep those in mind before any reinstall.

## Your Facebook account

**Can Meta tell?** Assume it can. A patched Facebook carries your signing key instead of Meta's, and some of what Facebook normally sends home goes quiet, like the ad reports `Block ad telemetry` turns off.

**What stays the same?** Your feed, stories and reels still come from Meta's servers, ads included, and Hushfacebook hides things on your phone after they arrive. It never posts, likes, follows or messages for you, and it doesn't change how you sign in.

**Could my account be suspended?** Nobody can promise it won't be. Meta's [Terms of Service](https://www.facebook.com/terms/) ask for its written permission before anyone modifies its apps (section 3.4), and they let Meta suspend or disable an account it decides has broken them (section 4.2). I haven't heard of an account suspended over Hushfacebook, but it's a young project. If you'd rather not risk the account you care about, try it with a test account first.

## Privacy

Hushfacebook doesn't collect anything and has no server. The patched app goes online on its behalf for two things only. One is a save you asked for, downloaded over HTTPS from Meta's media servers (fbcdn.net, fbsbx.com and cdninstagram.com), the same place the player streams it from. When you save from a copied share or fb.watch link, it first asks facebook.com or fb.watch where that link leads, and it stops at any address that isn't Facebook's. The other is the release check, which stays off until you turn it on. It asks `api.github.com` for the newest release once a day, or when you tap **Check now**, and it carries nothing about you or your phone. GitHub sees your IP address, as any site you visit does.

A few rows hand a link to another app instead of going online themselves. **Send the link to an app** under Downloads passes a `www.facebook.com` link built from the video's number. **Facebook's Whitehat settings** opens Facebook's own screen, or hands `https://www.facebook.com/whitehat` to your browser. **Search with a picture** opens Lens in the Google app, or hands `https://lens.google.com/` to your browser when you don't have it, and whatever you pick there goes to Google. **Support Hushfacebook** opens the Ko-fi page on `ko-fi.com`, and the About and Licenses pages link to the source and its licenses on github.com, gitlab.com, www.gnu.org and www.apache.org.

## Supported Facebook builds

| Field | Value |
|---|---|
| Package | `com.facebook.katana` |
| Version | `585.0.0.47.54` |
| 64-bit phones | build `476015655`, the (arm64-v8a) (120-640dpi) (Android 11+) bundle |
| 32-bit phones | build `476015543`, the (arm-v7a) (320dpi) (Android 11+) bundle |
| Minimum Android version | Android 11 |
| Download | [APKMirror](https://www.apkmirror.com/apk/facebook-2/facebook/) |

There are 109 patches for `com.facebook.katana`, and a release only goes out once every one of them has applied to that build. Older Facebook versions like 584 and 583 aren't supported any more, so update Facebook before you patch. When a newer stable Facebook comes out, Hushfacebook moves to it and drops the one before. Meta's builds for Android 9 and 8 can't be patched at all, since their code sits in a compressed archive the patcher can't read. The 64-bit build is the 120-640dpi one because 585 has no 480-640dpi bundle for Android 11; it carries every screen density, so it fits any 64-bit phone.

SHA-256 of the .apkm files the patches are checked against:

```text
476015655  1090dff6c9fe4903dd7c590f4d340bf0a912e43cd0a79c019c43bc265f5dda53  arm64-v8a
476015543  ec6bb38a25349ab6eba4750f04486ceb3e3d8b268efc6733928d8669bd489c28  armeabi-v7a
```

On Windows, compare yours with `Get-FileHash -Algorithm SHA256 .\facebook.apkm`, or use `sha256sum facebook.apkm` on Linux and macOS.

## Verify a download

Every release ships `SHA256SUMS.txt` beside the bundle, with a line for the `.mpp` and for each file published with it. Hash the `.mpp` you downloaded the same way as above and compare it with that line. The release receipt published beside it names the declared Facebook inputs the patches were applied to, with their hashes.

## Building from source

You need JDK 17 or newer, the Android SDK and a GitHub token with `read:packages`, because the Morphe patcher comes from GitHub Packages. On Windows, run the tests on JDK 25 or newer, such as the JBR that comes with Android Studio.

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<a token with read:packages>
./gradlew :patches:generatePatchesList
./gradlew :patches:buildAndroid
```

Run `generatePatchesList` before building, and use the finished bundle in `patches/build/release/patches-<version>.mpp`. Later test tasks can replace the intermediate JAR in `build/libs`. The release folder keeps the Android payload intact. Beside the bundle are its SHA-256 and a CycloneDX SBOM. Tests run with `./gradlew :patches:test :extensions:facebook:testDebugUnitTest`. Set `HUSHFACEBOOK_FIXTURE_DIR` to a folder holding the Facebook bundles for the tests that read real builds, and put FFmpeg and ffprobe on PATH for the codec tests. Without them those tests skip and say so. `scripts/verify-all-patches.ps1 -Apk <facebook .apkm> -DesktopJar <morphe-desktop jar> -WorkDir <scratch folder>` applies every patch to a real build and checks fingerprint uniqueness as well as the result.

For native checks, `scripts/device-audit.ps1 -Serial <serial> -ExpectedDevice <AVD name or model> -LeaseDirectory <folder> -LeaseTokenFile <token file>` borrows an existing device lease and needs a new or empty output folder. It launches Facebook through whichever launcher entry is on, so a changed app icon doesn't matter, and stops before any change if there is none or more than one. It checks destinations before swiping, captures settings pages in dark and light, and restores settings, app language and theme. Use `-MinimumTapY <pixels>` when the device reserves the top of its screen. The walk scrolls a navigation row down before tapping it and fails the required step if it can't reach the permitted area. The native walk currently recognizes English navigation labels. `-Locales de` also checks the language Facebook actually uses. Localized passes need a current patched build so the report can confirm its full language order, including fallback text. A full trusted run with `-WriteBaseline` creates a baseline tied to that build and profile. Partial runs can't replace it, and failed recovery keeps its backups. Hook counts show execution, not proof that a row disappeared.

Where things live:

- `patches/src/main/kotlin/app/morphe/patches/facebook/` holds the patches, one folder per area (ads, feed, reels, stories, downloads and so on). `patches/src/main/kotlin/app/morphe/patches/shared/compat/AppCompatibilities.kt` names the supported builds.
- `extensions/facebook/` is the Java that runs inside Facebook, with the settings screen, the feed filter, downloads and the rest. `settings/PatchFamily.java` there lists each patch's switches and what Pause reaches.
- `extensions/shared/library/` is the extension library that came from Hushfeed. Its translations are the `.tsv` tables in `src/main/l10n/`, which `scripts/gen-l10n.py` turns into Java.
- `patches-list.json` is the generated catalog, and a test holds the patch table above to it. `patches-bundle.json` is the Morphe source index and only changes at release time.
- `scripts/release/release.ps1` runs a release stage by stage, and `scripts/validate-release-facts.ps1` holds this README, the index and the CHANGELOG to the catalog.

[CONTRIBUTING.md](CONTRIBUTING.md) has the patch workflow and the rest of the checks.

## Where the patches come from

- The first Facebook patches here were ported from [Andrew Liang's patches](https://github.com/andrewliang25/morphe-patches) at `5db2e57` and rewritten with fixes rather than copied commit by commit. `Hide post prompts` and `Hide reel interest prompts` came later from its commit `16464b2`, and `Hide affiliate product links` from `a934b40`, each with a switch put in front. Many of the others were written here.
- [SapitoSucio/FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches) gave the idea of dropping promoted posts beside sponsored ones, and of hiding posts Facebook detected as AI. Andrew Liang credits it for some ideas and implementations too.
- `Following feed on Home` is rewritten from the Following feed swap in [ArunTS96/FroggoMorphePatches](https://github.com/ArunTS96/FroggoMorphePatches) at `319cde2`.
- [Hushfeed](https://github.com/SysAdminDoc/hushfeed) at `1f1f81a` gave the Gradle build, the shared extension library with its settings screen and diagnostics, the pause, the bytecode helpers and the release checks. The settings export and import came later, from `bcc57ee`.
- [HushMessenger](https://github.com/SysAdminDoc/HushMessenger) at `e1e90d8` gave the photo hooks and the video idea behind `Send chat photos and videos at original quality`, since Messenger and Facebook's in-app chats share one media transcoder.
- [HushPinterest](https://github.com/SysAdminDoc/HushPinterest) at `c5be4d9` gave four pieces of the settings screen. They're the quick setups, the search, the Report a problem page and the return to the page you were on after a restart.
- [JhouHank's contribution](https://github.com/SysAdminDoc/HushFacebook/pull/227) supplies the Traditional Chinese (Taiwan) translation.
- [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches) gave the patcher and the patch template. `Disable Play Store updates` is Morphe's own patch from [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches) at `1bcd0bc`, which grew from ReVanced's Change version code. Its two files keep Morphe's notice, and their headers say what Hushfacebook changed.

Every source file says where it came from in its header, and [provenance.json](provenance.json) maps each file to the project and commit it came from, with its license. [docs/sources.md](docs/sources.md) covers the other Facebook and Messenger patch projects and what this bundle took from each, and [sources/facebook-sources.json](sources/facebook-sources.json) pins their branches and licenses.

## License

[GPL-3.0](LICENSE), with the Morphe section 7 notices carried in [NOTICE](NOTICE). Facebook, Messenger and Meta are trademarks of Meta Platforms, Inc.
