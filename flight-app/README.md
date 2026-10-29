# DroidDrone Flight, DroidDrone PC build

The phone app that flies on the drone: upstream [DroidDrone](https://github.com/DroidDrone/DroidDrone) Flight 2.3.0
(pinned in [`../upstream`](../upstream)) with these files laid over it ([`overlay/`](overlay)):

| File | Change |
|---|---|
| `Flight/src/main/res/xml/device_filter.xml` | Artery AT32 flight controllers (USB 2E3C:5740) added, so Android offers *Always open DroidDrone Flight* and reconnects without asking |
| `Flight/src/main/res/layout/activity_main.xml`, `res/values/*`, `res/drawable/*`, `res/mipmap-anydpi-v26/*` | The PC app's look: black and red, cards with corner brackets, status pill, the PC app's icon |
| `Flight/src/main/java/de/droiddrone/flight/MainActivity.java` | Colours for the dark screen and the status pill; the connection logic is unchanged |
| `Flight/src/main/java/de/droiddrone/flight/BracketDrawable.java` | New: the card background |
| `Flight/src/main/AndroidManifest.xml` | The theme |
| `Flight/build.gradle` | Version `2.3.0-pc.1`, release signing from outside the repo |
| `settings.gradle` | Builds only the Flight app (not upstream's Control app) |

The network protocol is untouched, so DroidDrone PC works with this build and with the original 2.3.0.

## Build

```powershell
.\flight-app\build.ps1   # -> build\flight\DroidDrone-Flight-<version>.apk and its SHA-256
```

Needs the Android SDK (`platforms;android-34`, `build-tools;34.0.0`, `ndk;26.3.11579264`) and a JDK 17; see the top of
[`build.ps1`](build.ps1). The signing key lives outside the repo (by default in `%APPDATA%\DroidDronePC-build`) and is
created on the first build. Keep a backup: Android only accepts updates signed with the same key.

To ship a new build: attach the APK to a GitHub release tagged `flight-<version>`, then set `VERSION` and `APK_SHA256`
in [`ApkShare.java`](../src/main/java/de/droiddrone/pc/setup/ApkShare.java).

License: GPL-3.0, like upstream.
