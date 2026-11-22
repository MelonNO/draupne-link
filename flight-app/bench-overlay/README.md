# DRAUPNE Flight BENCH: the test rig's build of the phone app

**Never released. Never on a phone that flies.**

On the hardware test rig the phone and the flight controller are both plugged into the PC, so the phone cannot have
the board on its own USB as it has in flight. This build can reach it another way: a TCP connection to the phone's
own `127.0.0.1`, which `adb reverse` carries down the USB cable to a program on the PC that talks to the board's COM
port (or to Betaflight running as a program). The Android emulator uses the same build.

Everything else is the released app: the same sources, the same code shrinking, the same version number - but for
one thing more. The released app answers over Tailscale only (pc.58, Q-006); the Android emulator has no Tailscale, and
the bench's PC reaches the app there through the emulator's forwarded port, from 10.0.2.2. So in the emulator, and only
there, this build's link takes every address, as the app did before pc.58 (`BenchNet`, known by the emulator's hardware
name `ranchu` / `goldfish` or its product `sdk_...`, said once in the log with the marker). On a real phone it holds the
released app's rule: reach it at its Tailscale address (the hardware file's `phone.ip`). The bench's emulator scenarios
fly this build since pc.58.

## What keeps it out of a release

- The code is here, not in [`../overlay`](../overlay), and only `build.ps1 -Bench` copies it in - into a source copy
  of its own (`build\flight-bench-src`). The released app is built from `upstream` + `overlay` alone.
- `overlay` has a `BenchFc` that says `BUILT_IN = false` and does nothing. `Serial` asks that constant before each
  step that could go to the rig, so the compiler leaves those steps out of the released app. The same for the link's
  rule: `overlay`'s `BenchNet` says `BUILT_IN = false`, and `PhoneTailscale` asks it first (`ReleaseHasNoBenchCodeTest`).
- Its own identity: package `de.droiddrone.flight.pc.bench`, version `...-bench`, name **DRAUPNE Flight BENCH**,
  `android:testOnly` (Android installs it only with `adb install -t`; not from a file, a browser or the app's own
  updater), its own signing key (`flight-bench.jks`, never the release key).
- The marker `DRAUPNE-BENCH-FC` is in its code and its manifest. [`../scan-apk.ps1`](../scan-apk.ps1) reads an APK
  for it, and for the package, version, test-only flag and key: `build.ps1` stops when a normal build has any of
  them, and the release workflow stops when the phone app it is about to publish has.
- The PC test `ReleaseHasNoBenchCodeTest` fails when anything of this folder is found in `overlay`.

Do not copy files from here into `overlay`. Do not attach `build\flight-bench\*.apk` to a release.

## Build

```powershell
powershell -File .\flight-app\build.ps1 -Bench   # -> build\flight-bench\DRAUPNE-Flight-<version>-bench.apk
```

Needs what the normal build needs. The rig's key is made on the first bench build, in
`%APPDATA%\DroidDronePC-build\flight-bench.jks` (password in `flight-bench.password` beside it). It is worth
nothing: lose it and the next build makes another (uninstall the app once).

## Install

```powershell
adb install -t -r build\flight-bench\DRAUPNE-Flight-<version>-bench.apk
```

It installs beside the released app (another package). Run one of the two at a time: both want the same link port.

## Choose the flight controller's way

```powershell
adb reverse tcp:5761 tcp:5761                    # the phone's 127.0.0.1:5761 -> the PC's 127.0.0.1:5761
adb shell setprop debug.draupne.fc tcp:5761      # the app: flight controller over TCP, that port
adb shell setprop debug.draupne.fc usb           # back to USB (or restart the phone: the property is gone)
```

- Not set - and it is never set after the phone restarts - the build looks for the flight controller on USB, exactly
  as the released app. So the same APK serves a session with the phone plugged straight into the drone: set it to
  `usb` or restart the phone first, and close the app once.
- The app reads it each time it looks for a flight controller. With none open it follows within 2 s; with one open
  it keeps that one until the app (or the link) is started again: `adb shell am force-stop
  de.droiddrone.flight.pc.bench`, then open it.
- The address is always `127.0.0.1`; only the port is chosen. Any port works; 5761 is where Betaflight as a program
  listens.
- The app's log says which way it took: `adb logcat -s DDLog | findstr DRAUPNE-BENCH-FC`.

To the app the TCP connection is a USB port: the PC closing it is the board pulled out (the app opens it again at
once, then tries every 150 ms for 5 s, then every 2 s); nothing listening is no board plugged in. Two differences:

- A write has no time limit (USB gives up after 50 ms for sticks, 20 ms for requests). How long writes took is in
  the statistics (`slowWrites`, `maxWriteMs`).
- `adb reverse` itself always takes the connection, also with nothing behind it on the PC, and drops it a moment
  later. So "connected" is not yet "a board is there": the status card then shows the port open for that moment.
  A connection that ended without one byte, or within a second, makes the next try wait as a USB probe would
  (150 ms in the 5 s after a board was lost, else 2 s).

For the PC's bridge that means: close the connection when the board goes, and take no connection (or close it at
once) while there is no board.

## Drive it by adb

What the emulator's tests do as root, a real phone does through a receiver that only adb's shell can reach (it asks
for the `DUMP` permission):

```powershell
$r = 'de.droiddrone.flight.pc.bench/de.droiddrone.flight.BenchControlReceiver'
adb shell am broadcast -n $r -a de.droiddrone.flight.bench.STATS               # the connection in numbers
adb shell am broadcast -n $r -a de.droiddrone.flight.bench.RESET_CRASH_GUARD   # the app forgets its restarts
adb shell am broadcast -n $r -a de.droiddrone.flight.bench.KILL                # its process ended outright
adb shell am broadcast -n $r -a de.droiddrone.flight.bench.CRASH               # an error nobody catches
adb shell am broadcast -n $r -a de.droiddrone.flight.bench.CRASH --es thread main   # the same, on the main thread
```

Each answers in the broadcast's result (`am broadcast` prints it) and in logcat, tag `DDLog`. KILL and CRASH happen
0.3 s after the answer. RESET_CRASH_GUARD on an app that was force-stopped needs `--include-stopped-packages` and
starts its process.

`STATS` is one line: `DRAUPNE-BENCH-FC stats: chosen=tcp:5761 readBy=SystemProperties link=up linkMs=... linkBytesIn=...
linkBytesOut=... lastInAgoMs=... connects=... connectsFailed=... heldBack=... linksLost=... bytesIn=... bytesOut=...
writes=... writeErrors=... slowWrites=... maxWriteMs=... nextTryInMs=... lastError="..." serialStatus=7 service=running
pc=connected pid=...` (`serialStatus` 7 is "port open", 1 "no flight controller").

## The update test's builds (`update-test/`)

The bench's update test (`PhoneUpdateScenario`, UPDATE PHONE APP pressed on the PC) needs two builds that the bench
build cannot be: Android's installer takes no test-only app from the app's own updater. So these two are the
released app - its package, its version, the release key, not test-only - with one thing more: a remote update is
also taken from the bench's own web server on the PC, over plain http (`http://10.0.2.2:<port>/<name>.apk` from the
emulator, `http://127.0.0.1:<port>/<name>.apk` from a real phone through `adb reverse`). A released app takes one only
from the project's release downloads (`UpdateSource`).

```powershell
powershell -File .\flight-app\build.ps1 -UpdateTest   # -> build\flight-update-test\DRAUPNE-Flight-<version>-update-test.apk
```

- `build.ps1 -UpdateTest` lays `update-test/` over a source copy of its own (`build\flight-update-test-src`) and adds
  one line to that copy's `UpdateSource.fromReleases`: ask `UpdateTestSource` first. `overlay` is not changed.
- pc.58 (Q-006): its `BenchNet` takes the place of the released app's in that copy - in the emulator the link takes every
  address (the scenario runs there, with no Tailscale); on a real phone it answers over Tailscale only. Builds of the pair
  made before pc.58 do not have it: make them again.
- The marker `DRAUPNE-UPDATE-TEST` is in its code and its manifest. A normal build's scan and the release workflow's
  refuse an APK that has it; `-UpdateTest`'s scan wants it, and nothing of the rig's.
- `UpdateTestSourceTest` (PC tests) compiles the patched rule from these sources and holds it to the bench's two
  addresses; `ReleaseHasNoBenchCodeTest` fails when a word of it is in `overlay`.
- The pair (the receiver, and the sender one version up) is made by the maintainer's `build-update-test.ps1`. A real
  phone that ran the scenario keeps the sender: put the released app back before it flies.

## Files

| File | What |
|---|---|
| `Flight/bench.gradle` | The build type `bench`: a copy of release with the other package, version and key |
| `Flight/src/bench/AndroidManifest.xml` | Test-only, the marker, the control receiver |
| `Flight/src/bench/java/.../BenchFc.java` | The TCP connection, the property, the statistics |
| `Flight/src/bench/java/.../BenchControlReceiver.java` | The four adb actions |
| `Flight/src/bench/java/.../BenchNet.java` | The link takes every address in the emulator (no Tailscale there); on a real phone Tailscale only |
| `Flight/src/bench/res/` | The name on the phone; plain traffic declared for `127.0.0.1` only |
| `update-test/Common/.../UpdateTestSource.java` | The update test's builds only: the bench's server as an update's source |
| `update-test/Flight/src/release/` | Their manifest (the marker, plain traffic) and `update_test_network.xml` (`10.0.2.2`, `127.0.0.1`); their `BenchNet` (over the released app's: the link takes every address in the emulator, Tailscale only on a real phone) |
