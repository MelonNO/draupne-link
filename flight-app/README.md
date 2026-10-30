# DroidDrone Flight, DroidDrone PC build

The phone app that flies on the drone: upstream [DroidDrone](https://github.com/DroidDrone/DroidDrone) Flight 2.3.0
(pinned in [`../upstream`](../upstream)) with these files laid over it ([`overlay/`](overlay)):

| File | Change |
|---|---|
| `Flight/src/main/res/xml/device_filter.xml` | Every flight controller: any virtual COM port (USB CDC: STM32, AT32, APM32, GD32, RP2040; Betaflight, INAV, ArduPilot, PX4) and every USB-serial bridge chip the app's serial library supports (CH340/CH341, CP210x, FTDI, PL2303). Android then offers *Always open DroidDrone Flight* and reconnects without asking |
| `Flight/src/main/res/layout/activity_main.xml`, `res/values/*`, `res/drawable/*`, `res/mipmap-anydpi-v26/*` | The PC app's look: black and red, cards with corner brackets, status pill, the PC app's icon; the LOCK SCREEN button |
| `Flight/src/main/res/layout/dd_touch_lock.xml` | New: the touch lock's cover and its HOLD TO UNLOCK box |
| `Flight/src/main/java/de/droiddrone/flight/MainActivity.java` | Colours for the dark screen and the status pill; asks Android to remove an earlier DroidDrone Flight (`de.droiddrone.flight`) when it opens; the touch lock (LOCK SCREEN: everything ignores touches but a HOLD TO UNLOCK box, screen dimmed, rotation held); the cards side by side in landscape, stacked in portrait; Direct connection as the first-start connection mode (upstream: relay server). The connection logic is unchanged |
| `Flight/src/main/java/de/droiddrone/flight/BracketDrawable.java` | New: the card background |
| `Flight/src/main/java/de/droiddrone/flight/Serial.java` | Reads wait asynchronously (`read(buf, 0)`, a UsbRequest) instead of a 100 ms `bulkTransfer`, which on many phones held every write up to 100 ms (RXLOSS). One writer thread owns the USB port: the newest sticks always go first (50 ms timeout, then the newest again), other MSP requests one at a time in between, skipped while the link is slow |
| `Flight/src/main/java/de/droiddrone/flight/Msp.java` | Sticks go through `Serial.writeRcMsp` |
| `Flight/src/main/java/de/droiddrone/flight/Udp.java` | Measures stick packets arriving; sends `PcLinkStats` every 0.1 s as its own telemetry packet, and the app's version once a second |
| `Flight/src/main/java/de/droiddrone/flight/UpdateCheck.java` | New: asks GitHub for the latest DroidDrone PC release and whether the phone app in it is newer; MainActivity shows a tap-to-download line |
| `Flight/src/main/java/de/droiddrone/flight/PcLinkStats.java` | New: the phone's measurements (record 0x40F0) for DroidDrone PC's link log; the app's version (record 0x40F1) |
| `Flight/src/main/AndroidManifest.xml` | The theme; permission to ask for the earlier app's removal, and to see it; any orientation (`fullUser`, was landscape only) |
| `Flight/build.gradle` | Own package name `de.droiddrone.flight.pc` (installs next to an earlier DroidDrone Flight), version `2.3.0-pc.8`, release signing from outside the repo |
| `settings.gradle` | Builds only the Flight app (not upstream's Control app) |

The network protocol is unchanged (the phone's measurements travel as an extra telemetry packet that other PC versions skip), so DroidDrone PC works with this build and with the original 2.3.0.

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
