# DRAUPNE Flight, DRAUPNE Link build

The phone app that flies on the drone: upstream [DroidDrone](https://github.com/DroidDrone/DroidDrone) Flight 2.3.0
(pinned in [`../upstream`](../upstream)) with these files laid over it ([`overlay/`](overlay)):

| File | Change |
|---|---|
| `Flight/src/main/res/xml/device_filter.xml` | Every flight controller: any virtual COM port (USB CDC: STM32, AT32, APM32, GD32, RP2040; Betaflight, INAV, ArduPilot, PX4) and every USB-serial bridge chip the app's serial library supports (CH340/CH341, CP210x, FTDI, PL2303). Android then offers *Always open DRAUPNE Flight* and reconnects without asking |
| `Flight/src/main/res/layout/activity_main.xml`, `res/values/*`, `res/drawable/*`, `res/mipmap-anydpi-v26/*` | The PC app's look: black and red, cards with corner brackets, status pill, the PC app's icon; the LOCK SCREEN button |
| `Flight/src/main/res/layout/dd_touch_lock.xml` | New: the touch lock's cover and its HOLD TO UNLOCK box |
| `Flight/src/main/java/de/droiddrone/flight/MainActivity.java` | Colours for the dark screen and the status pill; asks Android to remove an earlier DroidDrone Flight (`de.droiddrone.flight`) when it opens; the touch lock (LOCK SCREEN: everything ignores touches but a HOLD TO UNLOCK box, screen dimmed, rotation held); the cards side by side in landscape, stacked in portrait; Direct connection as the first-start connection mode (upstream: relay server). The connection logic is unchanged |
| `Flight/src/main/java/de/droiddrone/flight/BracketDrawable.java` | New: the card background |
| `Flight/src/main/java/de/droiddrone/flight/Serial.java` | Reads wait asynchronously (`read(buf, 0)`, a UsbRequest) instead of a 100 ms `bulkTransfer`, which on many phones held every write up to 100 ms (RXLOSS). One writer thread owns the USB port: the newest sticks always go first (50 ms timeout, then the newest again), other MSP requests one at a time in between, skipped while the link is slow. After a USB glitch it reconnects at once (and looks for the FC every 150 ms for 5 s), closes the old connection, and counts only sticks that were really written |
| `Flight/src/main/java/de/droiddrone/flight/Msp.java` | Sticks go through `Serial.writeRcMsp`, and only once Betaflight's channel map is known (or 2 s after the FC was found), never in the wrong order |
| `Flight/src/main/java/de/droiddrone/flight/DDService.java` | Stops quietly when Android restarts it without the app's settings (it crashed) |
| `Flight/src/main/java/de/droiddrone/flight/Udp.java` | Measures stick packets arriving; sends `PcLinkStats` every 0.1 s as its own telemetry packet, and the app's version once a second. pc.26: a Connect from the pilot's own address and port starts a new session at once; another PC takes over only after 10 s of silence and never while armed; 16-bit stick sequence that starts over after 300 ms without a frame; a video watchdog (no encoder output for 2 s: camera and encoder started again); key frames paced at twice the bitrate; a parity packet per 8 packets (`VIDEO_PARITY_GROUP`); H.264 on the PC's profile; `SET_BITRATE`; telemetry in one datagram per 30 ms; the camera part of a config change on the video starter thread |
| `Flight/src/main/java/de/droiddrone/flight/StreamEncoder.java` | pc.26: a 150 kbit/s step under the 250 (the survival tier); `setBitRateIndex` for the PC's `SET_BITRATE`; a full output queue is cut back to its newest key frame |
| `Flight/src/main/java/de/droiddrone/flight/InternalCamera.java`, `Camera.java` | pc.26: `isOpening`, so a config change that comes while the camera is still opening waits for it (the PC's ROBUST size at start was marked done without being made) |
| `Common/src/main/java/de/droiddrone/common/UdpSender.java`, `ReceiverBuffer.java`, `SavedPacket.java` | pc.26: the resend timer of a session is cancelled when the next starts (upstream's timer task cancelled the new session's timer: no resends after the first reconnect, a timer thread leaked per Connect); no empty RequestPackets; the client side takes the answer to its own re-Connect. 1.0.53: resends, gap waits and clean-up on a monotonic clock (a clock set back stopped every resend). DRAUPNE Link compiles the same files (`build.gradle`) |
| `Flight/src/main/java/de/droiddrone/flight/UpdateCheck.java` | New: asks GitHub for the latest DRAUPNE Link release, whether the phone app in it is newer, and its SHA-256 (the asset's `digest`); MainActivity shows a tap-to-install line |
| `Flight/src/main/java/de/droiddrone/flight/AppUpdater.java`, `AppUpdateReceiver.java` | New (pc.18): the tap downloads the APK into the app's own cache, checks its SHA-256, installs it with `PackageInstaller` (Android asks) and deletes it; leftovers go at the next start. Never while connected: installing ends the app. MainActivity also says once that earlier browser updates left installers in Downloads, and opens Downloads on a tap |
| `Flight/src/main/java/de/droiddrone/flight/PcLinkStats.java` | New: the phone's measurements (record 0x40F0) for DRAUPNE Link's link log; the app's version (record 0x40F1); the power mode (0x40F5) |
| `Flight/src/main/java/de/droiddrone/flight/PowerMode.java`, `PowerAlarmReceiver.java`, `PowerAdminReceiver.java`, `res/xml/device_admin.xml` | New (pc.21): the power modes. Standby (no video, GPS every 30 s, FC polled at 1 Hz, screen dim; the PC wakes it with mode 0) and deep sleep (port, GPS, socket and wake lock let go, screen locked when the app is a device admin; an exact alarm opens the socket every few minutes for 20 s, and a Connect from the PC wakes the phone, screen on over the lock screen). Asked for by the PC (packet 0x6E) or the phone's own buttons; refused while armed. `DDService` does the work, `Udp` carries the packet and the record, `MainActivity` shows it |
| `Flight/src/main/java/de/droiddrone/flight/InternalCamera.java`, `Overwatch.java`, `MotionWatch.java` | pc.47: an overwatch event's session refused with the detection's picture records without it for that event only (it was refused for good, so the watching after it saw nothing); the overwatch check-in says whether the detection gets pictures (`OverwatchProtocol.HELLO_PICTURES` / `HELLO_ANALYSING`) |
| `Flight/src/main/java/de/droiddrone/flight/Silent.java` | New (pc.47): while the service runs the phone's ringer is silent (no sound, no vibration for calls and notifications), its own mode put back when it stops; needs Do Not Disturb access (a yellow line opens the setting). The service starts 0.3 s after the app opens, under the boot animation (was 3 s: its screen changes moved the cards as the animation ended) |
| `Flight/src/main/AndroidManifest.xml` | The theme; permission to ask for the earlier app's removal, and to see it; permission to install its own update and the installer's answer (`AppUpdateReceiver`); any orientation (`fullUser`, was landscape only); exact alarms, full-screen intent and battery optimisation opt-out for deep sleep, and its two receivers |
| `Flight/build.gradle` | Own package name `de.droiddrone.flight.pc` (installs next to an earlier DroidDrone Flight), version `2.3.0-pc.26`, release signing from outside the repo |
| `settings.gradle` | Builds only the Flight app (not upstream's Control app) |

The network protocol is unchanged (the phone's measurements travel as an extra telemetry packet that other PC versions skip), so DRAUPNE Link works with this build and with the original 2.3.0.

## Build

```powershell
.\flight-app\build.ps1   # -> build\flight\DRAUPNE-Flight-<version>.apk and its SHA-256
```

Needs the Android SDK (`platforms;android-34`, `build-tools;34.0.0`, `ndk;26.3.11579264`) and a JDK 17; see the top of
[`build.ps1`](build.ps1). The signing key lives outside the repo (by default in `%APPDATA%\DroidDronePC-build`) and is
created on the first build. Keep a backup: Android only accepts updates signed with the same key.

To ship a new build: attach the APK to a GitHub release tagged `flight-<version>`, then set `VERSION` and `APK_SHA256`
in [`ApkShare.java`](../src/main/java/de/droiddrone/pc/setup/ApkShare.java).

License: GPL-3.0, like upstream.
