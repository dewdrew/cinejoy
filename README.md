# Cinejoy — Universal Android + Amazon Fire TV App

One Kotlin WebView app, one APK (`pk.cinejoy.app`), that adapts at runtime to the
device it lands on.

| | Phone / Tablet | Fire TV Stick / Cube / Android TV |
| :-- | :-- | :-- |
| Launcher | `MAIN` + `LAUNCHER` | `LEANBACK_LAUNCHER` with a 320x180 banner |
| Orientation | Sensor portrait + landscape | Locked sensor landscape, immersive bars |
| Input | Native touch | D-pad virtual pointer + synthetic taps |
| User agent | Default WebView UA, `; wv` stripped | Desktop Chrome 122 UA |
| Media keys | — | Play/Pause/FF/Rewind drive the `<video>` element |

## Project layout

```
settings.gradle.kts            Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24
build.gradle.kts
gradle.properties
.github/workflows/build-apk.yml   Cloud build (no Android Studio needed)
app/
├── build.gradle.kts           minSdk 21, targetSdk 34, compileSdk 34, jvmTarget 17
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/pk/cinejoy/app/
    │   ├── MainActivity.kt         WebView engine, fullscreen video, remote keys
    │   ├── AdBlocker.kt            Host-based ad / popup / redirect shield
    │   ├── RemoteConfig.kt         Site address fetched from dewdrew/cinejoy-config
    │   └── TvCursorController.kt   D-pad pointer, edge scrolling, synthetic taps
    └── res/
        ├── layout/activity_main.xml
        ├── drawable/     cursor_pointer, tv_banner, ic_offline, bg_retry_button,
        │                 ic_launcher_background, ic_launcher_foreground
        ├── mipmap-anydpi/ic_launcher.xml        (API 21-25 legacy icon)
        ├── mipmap-anydpi-v26/ic_launcher.xml    (adaptive icon)
        └── values/       strings.xml, colors.xml
```

Every drawable is a vector or shape apart from `tv_banner.jpg`, the Fire TV launcher
banner, so the project needs no other binary assets.

## Building

Android Studio is **not** required. It is only an IDE — the build itself is Gradle
plus the Android SDK. Either route below produces the same APK.

### Route A — Cloud build (GitHub Actions)

`.github/workflows/build-apk.yml` builds both variants on `ubuntu-latest`, which
already ships a JDK and the Android SDK. Push the project to a GitHub repo and the
workflow runs on push, or on demand via **Actions → Build Cinejoy APK → Run
workflow**. Download the finished APK from the run's **Artifacts** section
(`cinejoy-debug-apk` / `cinejoy-release-apk`).

The runner is pinned to `ubuntu-24.04` rather than `ubuntu-latest`: that label began
migrating to Ubuntu 26 on 2026-10-19, and the migration broke the third-party Android
SDK setup action. The workflow now uses the image's own preinstalled SDK instead, with
no third-party setup action at all.

Verified green in 3m 25s — debug 3.77 MB, release 0.66 MB after minification and
resource shrinking.

> Initialise the repo **inside this folder**, not in your home directory — the
> enclosing `C:\Users\andrew.adane` is itself a git repo with no commits, and
> committing there would stage `.ssh/`, `AppData/` and the rest of your profile.

Windows PowerShell 5.1 rejects `&&` as a statement separator, so these are chained
with `;`, which works in both PowerShell and bash:

```powershell
git init; git add .; git commit -m "Cinejoy universal Android + Fire TV app"
```

### Route B — Local build

Requires a JDK 17 (not 25 — AGP 8.5 targets 17–21) and the Android SDK with
`platforms;android-34` and `build-tools;34.0.0`. Point Gradle at the SDK with a
`local.properties` file:

```properties
sdk.dir=C\:\\Users\\username\\AppData\\Local\\Android\\Sdk
```

Then:

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

## Output paths

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

The release variant is minified, resource-shrunk and signed with the debug key so it
installs out of the box. Swap in your own keystore before distributing it.

## Installing

### Android phone / tablet (USB)

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Amazon Fire TV Stick (wireless ADB)

Enable **Settings → My Fire TV → Developer Options → ADB Debugging** and
**Install unknown apps** first. The device IP is under
**Settings → My Fire TV → About → Network**.

```bash
adb connect 192.168.1.50:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p pk.cinejoy.app -c android.intent.category.LEANBACK_LAUNCHER 1
adb disconnect 192.168.1.50:5555
```

For end users, host the signed APK on your server and sideload it with the
**Downloader** app rather than ADB.

## Signing

Every build is signed with one fixed key, so a new APK installs over an existing
one (`adb install -r`, or tapping it on a phone) without an uninstall. Android
rejects an update whose signature differs from the installed copy with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

The key never enters the repo. CI reads it from two repository secrets:

| Secret | Contents |
| :-- | :-- |
| `CINEJOY_KEYSTORE_BASE64` | The PKCS12 keystore, base64-encoded |
| `CINEJOY_KEYSTORE_PASSWORD` | Its password (store and key share it) |

A push without them fails at **Restore signing keystore** rather than producing an
APK that cannot update existing installs. Pull requests from forks never receive
secrets, so they build with a throwaway key and a warning. Each run's
**Show signing certificate** step prints the fingerprint, which should be:

```
SHA256: 2A:0F:D5:D3:1E:01:F3:EF:EE:E0:52:E3:DE:7E:4E:5A:B2:48:1E:86:0D:44:5E:1E:CC:43:44:70:9B:3B:6B:86
```

For a local signed build, put the keystore at `signing/cinejoy-release.jks`
(git-ignored) and set `CINEJOY_KEYSTORE_PASSWORD`. Without both, Gradle falls back to
the local debug key.

Keep a backup of the keystore and its password. Losing either means every installed
copy must be uninstalled once more. Anyone holding both can ship an APK that
replaces yours on users' devices.

## Changing the site address

The address is not baked into the APK. On every launch the app fetches `config.json`
from the separate public repo
[dewdrew/cinejoy-config](https://github.com/dewdrew/cinejoy-config):

```
https://raw.githubusercontent.com/dewdrew/cinejoy-config/main/config.json
```

It lives outside this repo on purpose. This repo is private, and the app has no
GitHub login, so a file here would return 404 to every installed copy. The config
repo must stay public; it holds only site addresses.

```json
{
  "schemaVersion": 1,
  "homeUrl": "https://cinejoy.pk/",
  "mirrors": ["https://cinejoy.to/"],
  "firstPartyDomains": ["cinejoy.pk", "cinejoy.to"]
}
```

When the site moves, edit `homeUrl` (and `mirrors`) in `cinejoy-config` and commit.
Every installed copy picks it up on its next launch, with no rebuild or reinstall,
and since that repo has no workflow, no APK build runs.

How the app uses it:

- **Startup never waits on GitHub.** It opens the last address it cached and fetches
  the fresh file in the background. If the fresh `homeUrl` differs and nothing has
  loaded yet, it switches straight away; otherwise the change applies on next launch.
- **Mirror failover.** If the first load fails outright (no DNS, connection
  refused), the app tries each mirror in order before showing the offline screen.
  Retry walks the list again.
- **Fallback.** With GitHub unreachable and nothing cached, the app uses the
  addresses compiled into `RemoteConfig.FALLBACK`.
- **Bad edits are ignored.** A file that fails to parse, has the wrong
  `schemaVersion`, or uses a non-https address is rejected, and the last good copy
  stays in use. Hosts of `homeUrl` and `mirrors` are always treated as first-party.

Failover only reacts to load *failures*. A dead domain that still answers — a
parking page or a seizure notice — loads normally, so updating `homeUrl` is what
moves users off it. GitHub's raw CDN can take about five minutes to serve an edit.

## Fire TV remote mapping

| Key | Behaviour |
| :-- | :-- |
| D-pad | Moves the pointer; accelerates while held; scrolls the page at the edges |
| Select / OK | Synthetic tap at the pointer tip |
| Menu | Toggles Pointer Mode ↔ native D-pad focus mode |
| Play/Pause | Plays or pauses the active `<video>` |
| FF / Rewind | Seeks ±10s |
| Back | Exits fullscreen → WebView history → double-press to quit |

The pointer fades after 5 seconds of inactivity and reappears on the next D-pad press.

## Known limitations

- **Media keys and cross-origin iframes.** `evaluateJavascript` only reaches the main
  frame, so if a player is embedded in a cross-origin iframe the transport keys cannot
  touch its `<video>` element; that player handles its own keys instead.
- **Banner.** `tv_banner.jpg` is 1280x720 (16:9) at 60 KB — four times the 320x180dp
  the launcher actually draws, so it stays sharp without bloating the APK. Amazon's
  store listing wants a PNG at the same dimensions, but that asset is uploaded to the
  console separately and is not bundled here. `tv_banner_old.xml` holds the original
  generated vector and is unreferenced; git history has it either way.
- **Ad blocking is host-based.** Substring matching on "ad" would break legitimate
  player assets, so only the listed networks are dropped. Extend at runtime with
  `AdBlocker.block("host.example")`. Both ordinary page requests and service-worker
  requests are filtered — the latter bypass `WebViewClient.shouldInterceptRequest`
  entirely and need `ServiceWorkerControllerCompat`, which old WebViews (Fire OS 5,
  API 22) do not have, though they have no service workers to filter either.
