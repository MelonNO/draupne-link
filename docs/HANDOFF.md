# Handoff

Everything needed to pick up work on DRAUPNE Link from a new session or another computer. Read this first, then
[README](../README.md) (what it does, troubleshooting), [CHANGELOG](CHANGELOG.md) (what changed when),
[PROTOCOL](PROTOCOL.md) and [ROADMAP](ROADMAP.md).

## Where things stand (2026-11-23: 1.0.72 released from main, PC only; 1.0.71 + phone pc.55 before it; not flown yet)

- **1.0.72** (2026-11-23, PC only, phone app stays pc.55): Drone setup keeps a drone on USB quiet - resting sticks
  over USB while it is open (`setup/RestingSticks`), so CONFIRM's lost-link beacon no longer sets the motors beeping
  on the bench. Released from `main` with this fix alone on the maintainer's word; the same fix is on `beta`, whose
  own unreleased work is now 1.0.73. Tried on made-up boards only: the pilot's try on the drone that beeped is next.
- **Now:** DRAUPNE Link **1.0.71** with phone app **2.3.0-pc.55** (released 2026-11-21, on the maintainer's word
  "complete a release and merge to main"). Tested on the test bench only - the real Windows app with made-up drones,
  the real phone app in an Android emulator, nights of flying, sleeping and bad links: **not flown, not on a real
  phone.** Last flown: 1.0.69 with pc.52 (2026-11-17 and 18). Before UPDATE PHONE APP each drone's phone needs
  "Install unknown apps" allowed for DRAUPNE Flight once (F-058).
- **1.0.71** = 1.0.70 (never released on its own) + the link round + the field round + the bench and every finding
  of it ([BENCH-FINDINGS.md](BENCH-FINDINGS.md): none open). The maintainer's tries on hardware still to come: F-045
  (FC re-plug), F-047 (screen-lock setup + crash reopen on a drone phone), F-051 (OUT OF ACTION's beeper with a real
  FC), F-053 (motor direction, props off), F-054 (UPDATE PHONE APP on one phone), the ESCS row's "Beacon off" on a
  BLHeli_S / Bluejay ESC.
- **1.0.69** (2026-11-15, PC only): OSD element mAh/km, on by default (`OsdElement.EFFICIENCY`). Not flown. Released
  from another PC while 1.0.70 waited here unpushed - the number taken, the merged parts moved to 1.0.70.
- **1.0.68** (2026-11-14, PC only): STANAG encoders in a process of their own (a native crash in one had taken the
  whole app down); FIND GPS in Drone setup. Not tried by the pilot.
- **1.0.67** (2026-11-14, PC only): the motor test's click spins a motor at 2 % (was 8 %). Not tried on a motor.
  Both were built and released from another PC; their full notes are at the top of docs/history/HANDOFF-history.md.
- **1.0.66** (2026-11-13, PC only): flight time and range left on the STANAG page and in FLEET's detail.
- **1.0.65 + phone pc.52** (2026-11-13): STANAG 4609 as one stream per drone; a phone in standby can be observed.
- **1.0.64 + phone pc.51** (2026-11-12): two review rounds' fixes, overwatch / deep sleep power, sleep notice, AIRSPACE.
- **New work happens on the `beta` branch** - see "Rules for working on this repo" below.
- **The test bench** (`bench.ps1`, `src/test/java/de/droiddrone/bench/**`, [TESTING.md](TESTING.md)) came with 1.0.71.
  Older open points:
  - 1.0.64: no session id (the reconnect storm's root) - in 1.0.71 (the link round).
  - 1.0.64 detector: a drop or insect on the lens alerts, headlights get two ids, a 2 px walker (about 55 m) is missed.
  - 1.0.58: HIGH's fine stage needs a still camera; HIGH alarms on swaying leaves unless they are boxed LOW / OFF.
  - 1.0.60-1.0.61, not found: the notice every phone gets when one is piloted (asked); DEMO's empty tiles on a PC.
  - 1.0.49: the phone's idle-sticks check doesn't read Betaflight's linked modes (MSP_MODE_RANGES_EXTRA).
  - 1.0.42: the GPS UART choice writes the port as GPS only - what else was on that UART is taken off.
  - Mission debrief: in 1.0.71 (from 1.0.70). Asked about, not ordered: idle watch (idle phones that watch).
- **Flaky tests:** on beta they are tagged `quarantine` and run apart, retried (see "Testing and diagnosing").
- **Refactors still open:** the card layout builder in `MainWindow`, the drone signs in `MapView` (1.0.54); one decode
  shared by the OSD recorder and the STANAG stream (1.0.65). Observe / overwatch's rules left `Udp` on beta.
- **History:** [history/HANDOFF-history.md](history/HANDOFF-history.md) (up to 1.0.66) and [CHANGELOG](CHANGELOG.md).

## Ideas not done yet

- Spectator: a better picture than the pilot's would need a second stream from the phone (costs the phone's 4G
  upload, so the pilot's delay): not done on purpose. A spectator in relay mode isn't supported.
- Pilot side: show who watches (count is in the pill: LINK READY +N WATCHING).

## Rules for working on this repo

- **Latency first.** This is FPV over 4G: every choice in the stick and video paths is weighed by the delay it adds.
  Call out any trade-off that adds delay before adopting it.
- **No personal data in the repo, screenshots or release notes.** That means the Windows user name, PC host name,
  Tailscale IPs, phone model, e-mail and similar. Scan the staged diff for these before every commit. The exact
  patterns are kept in the maintainer's private notes, not here.
- **No AI co-author trailers in commits** (`Co-Authored-By: ...`). Commits are by the maintainer only. A force push
  does not clear GitHub's contributor list, so such a line must never reach GitHub.
- **No secrets in the repo.** The phone app's signing key lives outside it (see below).
- **New work goes to the `beta` branch.** (The maintainer's rule since 2026-11-14.) Every new change is built on
  `beta` and tested there by the maintainer before it is merged to `main`. Nothing reaches `main` or a release except
  very safe changes, and only on the maintainer's word. Phone test builds from `beta` take their own number with
  "beta" in the name, are sideloaded, and are never pinned in `ApkShare` or published as Latest.
- **Publishing needs the maintainer's explicit go-ahead.** That covers tags, GitHub releases and the phone app release.
- **Don't close a running DRAUPNE Link that has the drone link open.** A live drone may depend on it. This is also why
  the installer only asks and never closes the app.

## Build environment (new computer)

| What | Needed for | Notes |
|---|---|---|
| JDK 21 (Temurin) | everything | `gradlew.bat test`, `installDist`, `releaseZip` |
| Gradle plugin `org.gradle.test-retry` 1.6.2 | `quarantineTest` | Gradle fetches it; build-time only, not in the app |
| WiX Toolset 3.14 on PATH | `packageInstaller` | jpackage builds the MSI with it; `src/installer/main.wxs` is jpackage's own with additions marked "DRAUPNE Link" |
| JDK 17, Android SDK (`platforms;android-34`, `build-tools;34.0.0`, `ndk;26.3.11579264`) | phone app | `flight-app/build.ps1`; see the top of that script |
| Phone app signing key | phone app | `flight-release.jks` + `flight-release.password` in `%APPDATA%\DroidDronePC-build` (or `DD_FLIGHT_KEYSTORE` / `DD_FLIGHT_KEYSTORE_PASSWORD`). **Not in the repo; keep a private backup.** Android only installs an update signed with the same key: with a new key, every phone must uninstall the app once |
| GitHub CLI, logged in with the `workflow` scope | releases | |
| `git clone --recurse-submodules` | everything | upstream DroidDrone is the `upstream/` submodule; its Common code is compiled as-is |

## Releasing

1. Bump `version` in `build.gradle`.
2. `docs/RELEASE_NOTES.md` holds **only the version being released** (it becomes the release page). Add the same items
   to `docs/CHANGELOG.md`.
3. **If the phone app changed:**
   1. Bump `versionCode` and `versionName` in `flight-app/overlay/Flight/build.gradle`.
   2. Run `.\flight-app\build.ps1`. It prints the APK and its SHA-256.
   3. Publish the APK as its own release, **not** Latest:
      `gh release create flight-<ver> build/flight/DRAUPNE-Flight-<ver>.apk --latest=false --title "DRAUPNE Flight <ver> (phone app)" --notes-file <notes>`.
   4. Download it back and check the SHA-256.
   5. Set `VERSION` and `APK_SHA256` in `src/main/java/de/droiddrone/pc/setup/ApkShare.java`.
4. Run the tests, commit, push, then `git tag v<version>` and `git push origin v<version>`.
   `.github/workflows/release.yml` then:
   - checks the tag matches `build.gradle`
   - runs the tests - unless a Build run (`build.yml`) for this very commit is completed and successful: tag once
     the push's Build run is green and the release leaves them out; in every other case it runs them itself
   - builds the installer and zip
   - fetches the pinned APK and checks its SHA-256
   - publishes all three with `RELEASE_NOTES.md` as the text
5. Installed copies find the release through UPDATE (`UpdateCheck` + `SelfUpdate`). The phone app finds a newer APK in
   the Latest release by itself.

Lessons from releasing:
- Commit and push **before** publishing the phone app: `gh release create --target` needs a pushed commit, and takes
  `main` or a full SHA (a short SHA fails with "target_commitish is invalid").
- Build the APK from a clean HEAD (`git status` empty) and check it: `aapt dump badging` (label, package, version) and
  `apksigner verify --print-certs` (signer SHA-256 as in the private NOTES.md).
- Claude Code's auto mode may refuse the push / tag step as "creating a public surface": that needs the pilot's
  explicit OK (a "release" from the pilot is that OK; if it is still refused, say so and stop).
- The release workflow takes 16-28 minutes when it is green first try (1.0.55: 16 min, 1.0.56: 24 min, 1.0.66:
  28 min), longer when a flaky test needs `gh run rerun --failed`. Afterwards check `gh release list` (v<version>
  Latest) and the three assets.

## Testing and diagnosing

- **Three test tasks** over the same test classes, told apart by JUnit tags (`build.gradle`):
  - `gradlew.bat test`: every test without a tag, 1559 on 2026-11-14 (protocol, video, telemetry,
    calibration, Betaflight CLI plans, update check, cell count, spectator mode end to end on loopback, flight mode /
    ARM / failsafe planning, setup pages, the map, the virtual fleet, observation, overwatch, STANAG, the motion
    detector on made-up scenes). Its classes run side by side in up to 4 JVMs ("forks": half the cores, 4 at most).
  - `gradlew.bat perfTest`: `@Tag("perf")`, what the code costs in time (17 tests: `PaintCostTest`,
    `PipelinePerfTest`, `StanagPerfTest`, `MotionDetectorPerfTest`, `ShowScenePerfTest`, `LinkPerformanceTest` and
    seven single tests elsewhere). One at a time; meant for a PC doing nothing else - a budget missed on a busy PC
    says little.
  - `gradlew.bat quarantineTest`: `@Tag("quarantine")`, tests known to fail now and then under load. Each is tried up
    to 3 times (the Gradle plugin `org.gradle.test-retry`: build-time only, nothing of it is in the app; the other
    two tasks never retry).
- **A tagged test runs in its own task only:** `gradlew.bat perfTest --tests PaintCostTest`,
  `gradlew.bat quarantineTest --tests "*VideoTest.aHeldKeyFrameIsReleasedWhenNoConfigComes"`. `test --tests ...`
  finds no such test. `--rerun` after the task's name runs it again although nothing changed.
- **Which tag.** `perf`: the test asserts a duration or a rate measured on this PC (a paint under so many ms, the
  pacer's rate). `quarantine`: the test was seen failing without a fault in the code; its reason is a comment at the
  tag, and it leaves quarantine when the cause is found and fixed. Everything else has no tag and must pass every
  time, beside three other forks and a running DRAUPNE Link.
- **In quarantine now**, with the reasons recorded at the tags:
  - `OverwatchFleetEndToEndTest.lostArmedThenAReconnectThatSaysDisarmedEndsTheWindowAndTheHeldAlertSaysWhy`: the
    known flake under load (real seconds of flying, a link loss and a detection).
  - `PinsAndFlightsTest.theRecordingPlaysFromTheRawFileWhenTheOsdCopyWontOpen`: on a loaded PC now and then no
    picture 0.6 s after PLAY (the player runs at the lowest priority); cause not found.
  - `SpectatorTest.aCongestionPauseLeavesNoGapAndIsAnnounced`: the "congestion flake" of release runs; its wait for
    the announcement came with 1.0.64.
  - `PipelineRecoveryTest.aBacklogKeepsItsNewestKeyFrame`: steadied in 1.0.49, still fails now and then under load.
  - `VideoTest.aHeldKeyFrameIsReleasedWhenNoConfigComes`: the held-key-frame flake under load; the cut that caused it
    went with 1.0.54.
  - `RsParityEndToEndTest.aLossWithinTheParityIsMendedBeyondItAsksForAKeyFrame`: red under load twice (2026-11-15,
    2026-11-21), green alone; its 8 s wait for the damaged key frame.
- **Tests of different classes run at the same time**, and the pilot's DRAUPNE Link may run beside them: no test
  counts on a fixed port or file, or reads or writes `%APPDATA%\DraupneLink` or `Documents\DRAUPNE Link`.
  `de.droiddrone.testing.Waits` is the one way to wait for another thread (`waitFor`, `await`, `until`,
  `waitForOnSwing`); `Ports.free()` and `Ports.freeTcp()` hand out free UDP and TCP ports; files go into JUnit's
  `@TempDir`. `TestHygieneTest` reads the test sources and fails on a waiting helper or a port number of a test's own,
  and on a fixed port of the app's (`ApkShare.PORT`).
- **A long test class holds up its fork.** Gradle deals the class files out to the forks in turn as it finds them
  (in name order here; a class that failed in the run before goes first), all before any runs, and a fork runs its
  share one class after the other. The motion detector's five long scenes were one class with its short tests: 10.2
  minutes alone, the suite 11.2. Each is now a class of its own (`MotionDetectorEdgeWindTest`, `...LeavesBoxedTest`,
  `...LongWindTest`, `...CloseUpWoodsTest`, `...NearBranchTest`; shared helpers in `MotionScenes`): 5.3 and 6.2
  minutes, the longest class 2.5 (2026-11-14, `test --rerun`, another build on the same PC). A new scene of a minute
  or more gets a class of its own too.
- **A fork's classes share one JVM:** a thread or a static of the app's that stays for good is still there for the
  classes after it. `PinsAndFlightsTest` waited for every thread named `flight*` to end, and timed out whenever a
  Debrief test had started the notes' writer (one thread for good) in the same fork.
- **CI** (`.github/workflows/build.yml`, on a push to `main` or `beta` and on pull requests; a newer push to the
  same branch cancels the older run): job `test` runs `gradlew test` and is the workflow's result with `phone-app`
  (the phone app still builds). Jobs `perf` and `quarantine` run the other two tasks; they are shown and keep their
  report as an artifact when they fail, but never fail the workflow. `phone-app` keeps its Gradle files and the NDK
  (not on the runner's image) in caches of its own.
- Loopback tests need full-size packets to catch size bugs: 1.0.17's spectator cut-off passed with a plain test
  picture (see `SpectatorTest.fullSizePacketsReachTheSpectatorWhole`).
- **App log:** `%APPDATA%\DraupneLink\logs\app-DATE.log` (`AppLog`, 14 days): System.out/err, the window's log
  lines, uncaught exceptions with stacks, and **every thread's stack when the window stops responding for 5 s**.
  Start here for a crash or freeze. (A freeze at 12:22 on 2026-10-31, before the log existed, left no trace.)
- **The test bench (2026-11-19, on beta):** the whole app run sealed off, its buttons pressed with the real mouse,
  the real phone app in an emulator, bad links, kills, a long run - `.\bench.ps1 quick | full | soak`, see
  [TESTING.md](TESTING.md); what it found and what was fixed is in [BENCH-FINDINGS.md](BENCH-FINDINGS.md), what
  ran and what waits in [BENCH-RUNS.md](BENCH-RUNS.md). While a long run goes, build with `-PaltBuild=build-alt`
  (its app loads classes from `build/`). The pilot's rule since that day: this
  PC's mouse and screen may be used **while no real drone is armed** (the bench reads the real app's link log for
  it), and nothing of the pilot's is ever deleted. The bench's tests carry tags of their own and are not run by
  `gradlew test`.
- **Small UI checks** outside the bench are still done by painting windows and components off screen into PNGs from
  small throwaway programs (kept in the git-ignored `build/` folder).
- **Link log:** `%APPDATA%\DraupneLink\logs\link-*.csv`, 10 rows a second while connected. It has ping, RC and
  telemetry gaps, RXLOSS / failsafe / armed, the **sticks actually sent** (`channels`, AETR + AUX), the Tailscale path,
  and the phone's own measurements (stick gaps in and out, USB write times). Since 1.0.18 also the video:
  `video_rx_fps`, `video_mbps` (as received: the phone's bitrate after `BitrateControl`), `video_damaged`,
  `video_lost`. `video_fps` is the frames **shown**. Start here for any flight or picture complaint.
  Examples: a landed drone that leans means the sticks weren't 1500 at rest; micro failsafes meant the phone held its
  USB writes.
- Installer changes: build with `packageInstaller` and inspect the MSI tables. jpackage's `--temp` keeps the
  intermediate `.msi`. Test with `msiexec /i ... /l*v log`.

### The virtual fleet (testing without hardware)

A separate program: `gradlew.bat run --args=fleet` (or the installed `"DRAUPNE Link.exe" fleet` / `Virtual fleet.bat` next to
it, copied by `packageApp`). Code in `de.droiddrone.pc.fleet`, its window `gui.FleetPanel`.
- **How DRAUPNE Link finds it** (`VirtualFleet`): each virtual phone binds a loopback address of its own (127.0.0.11, .12, ...)
  at an ephemeral port - Windows won't let two programs share the link port, wildcard and specific alike. While it runs the
  fleet writes `%APPDATA%\DraupneLink\virtual-fleet.txt` (heartbeat every 2 s, stale after 10 s, deleted on exit);
  `NameService.scan` asks the announced addresses like Tailscale peers, at their ports; `Connection`, the
  observation's `TileFeed` and NAME_ASSIGN send to `VirtualFleet.portOf(ip, linkPort)` - the link port for any address
  that isn't loopback, so nothing changes for real phones, and without the file nothing is asked. Only 127.x addresses are
  ever taken from the file.
- **Marked VIRTUAL** wherever a loopback drone shows (`Names.Entry.isVirtual`): the PHONE list and its hint, the OBSERVE
  picker, the map's other drones, a yellow tag on the video (`VideoView.setVirtual`), VIRTUAL by the live drone on both
  maps, and burned into its picture. Its name map entry is never saved (filed under host "virtual fleet"), and it never
  takes a name a real address holds. The fleet picks free Mobile letters from Zulu down, never one in the PC's settings.
- **The phone** (`VirtualDrone`) ports overlay `Udp`'s rules onto the shared Common code (ReceiverBuffer, UdpSender,
  Takeover, PcAudio, Observe, MotionDetector, MotionRecord, OverwatchProtocol, OverwatchEvent, PreRoll): sessions,
  re-Connect, takeover, PILOT_AT, the stick sequence and lag, idle sticks against its own MSP_MODE_RANGES, Config,
  StartVideo, profile, SET_BITRATE, key frame requests, AUDIO, DETECTION_SET, POWER_MODE (standby, deep sleep with
  check windows and hellos, overwatch), OBSERVE, NAME_QUERY/ASSIGN, and the telemetry at the phone's rates
  (`TelemetryRecords`, read back through `FcTelemetry` in FleetTelemetryTest). Video: `Scene` draws, OpenH264
  encodes (reopened on a size or bitrate change), sent as the phone fragments it, parity groups in the robust profile.
  A recording (`Recording`) is decoded with ClipPlayer and encoded again at the PC's tier; its AAC track is passed on.
- **The FC** (`FlightModel`): Betaflight's arming rules, RX loss 100 ms / hold 300 ms / stage 1 1.5 s / DROP or LAND,
  angle and acro, a 4S pack. **The 4G** (`LinkImpairment` + `ImpairedSocket`): loss, jitter, bunching, silence,
  handover, both ways under the shared transport.
- Differs from the phone: H.264 only (asked H.265, it answers H.264, as a phone without HEVC does), no OSD/recording
  packets, overwatch clips one MKV per event without sound (no 60 s segments), no battery or heat floor, the remote
  update refused, the channel map is the identity. Tests: `FlightModelTest`, `LinkImpairmentTest`,
  `FleetTelemetryTest`, `VirtualDroneEndToEndTest` (the real ControlLink, VideoPipeline, OverwatchHub on loopback,
  ephemeral ports), `NamesTest.theVirtualFleetsDronesAreFoundOnlyWhileAnnounced`, `FleetPanelTest`.
## How the newer parts work (1.0.17 / 1.0.18)

- **Spectator mode** (`Spectate`, `SpectatorHost`, `SpectatorClient`; phone side in the overlay's `Udp.answerSpectator`).
  A second PC that connects while one flies gets `PILOT_AT` from the phone (only with the right key; the phone sends
  nothing else extra) and from then on talks only to the pilot PC: HELLO to its link port, then STREAM packets from
  the pilot's own spectator socket. The pilot copies what the phone sent (`forward`: one array copy per packet on the
  receive thread, nothing without spectators), resends lost packets on request (a 4096-packet ring) and pauses the
  spectators when its ping stays 80 ms over the usual for 1 s. The spectator plays everything at the pilot's arrival
  time + `spectatorDelayMs` (600) and spaces out bunched frames. A spectator never sends Connect again (no takeover).
  Off by default (`allowSpectators`); direct phone mode only; at most 2 spectators.
- **Drone setup switches** (`setup/ModeSwitch`): ARM (AUX1), a 3-position flight mode switch (AUX2: acro, angle, GPS
  position hold), the failsafe (keep / drop / GPS rescue) and a GPS rescue switch (AUX4, only while the failsafe is GPS
  rescue). One joint planner (`ModeSwitch.plan`) for all switches: they share Betaflight's 20 mode slots, ARM elsewhere
  is taken off, conflicts are refused. GPS modes: Betaflight's GPS is turned on if needed, then written only when
  the GPS answers (MSP_STATUS sensor bit, no fix needed) and Betaflight is new enough (rescue 4.4, position hold
  2025.12). The radio calibration names each switch from these choices (`ModeSwitch.purposes`).
- **First-time setup = the separate setups** (`SetupWizard.firstTimePages` from `PcSetupWizard.pages`,
  `DroneSetup.pages`, `PhoneGuideWizard.pages`): add a step to one of those lists and it is in both. Order: this PC,
  drone, radio (its calibration names the switches), phone, connection.
- **Camera frame rate:** always a steady 30 fps (`ControlLink.CAMERA_FPS`), no setting: 30-60 switched with the light
  (jumpy) and 60 was dark indoors. Old `cameraFpsMin/Max` lines in a settings file are ignored. Phone app pc.12 matches
  the range to one the camera lists (overlay `InternalCamera.supportedRange`).
- **Video over weak 4G** (phone app pc.13 + PC): the phone paces packets (`Udp.packetGapNs`), keeps the asked frame
  rate (`InternalCamera`), sends key frames every 2 s and on `KEY_FRAME_REQUEST` (0x6A) (`StreamEncoder`; intra
  refresh was tried in pc.13 and hung a test phone's video start - don't turn it back on without trying it on the
  phone first), reports its bitrate setting (record 0x40F2). The PC asks for key frames (`VideoPipeline`,
  `ControlLink.requestKeyFrame`), decides the bitrate (`BitrateControl`: floor 1 Mbit/s by what arrives, the phone's
  report as the step), switches to 3/4 size on a weak link (`ControlLink.adjustResolution`), and releases a key frame
  that lost its last packet (`FrameAssembler.retireOlder`). Measured: cutting a damaged frame at its gap gives the
  same picture as zero-filling it; leaving a damaged key frame out is worse than decoding it.
- **Connect as** (next to CONNECT, `connectAs`): pilot or spectator. A spectator by choice (`ControlLink.watchOnly`)
  never sends Connect; it asks the phone who flies (PILOT_QUERY) and waits while nobody does. A pilot that finds another
  PC flying is made a spectator and told so loudly: a message, the red NOT FLYING - SPECTATOR pill, a blinking line on
  the video (`MainWindow.tellNotFlying`).

## Betaflight facts this code relies on

- One MSP message per serial task cycle; `serial_update_rate_hz` defaults to 100, too slow for sticks plus telemetry.
  Drone setup sets 2000 (1000 on F411).
- No sticks for 100 ms: RXLOSS (last sticks held 300 ms, then stage 1). Stage 2 (disarm / land) after 1.5 s. Arming
  needs 0.5 s of clean data.
- The cell count is detected once, when the battery appears. With the FC already on the phone's USB power it can lock
  onto 1S / 2S: `CellCount` corrects the OSD, and Drone setup writes `force_battery_cell_count`.
- With Air mode, an armed drone on the ground keeps correcting (I-term). Disarm after landing.
- Mode ids for the CLI `aux` command (msp_box.c permanent ids): ARM 0, ANGLE 1, HORIZON 2, ALTHOLD 3, POS HOLD 11
  (2025.12), GPS RESCUE 46. A mode is only offered (MSP_BOXIDS) when the board can do it: ANGLE needs the
  accelerometer, GPS RESCUE the GPS feature, POS HOLD position hold built in.
- `failsafe_procedure` values: AUTO-LAND, DROP, GPS-RESCUE. With GPS rescue as the failsafe or on a switch, Betaflight
  arms only with a GPS fix (RESC) unless `gps_rescue_allow_arming_without_fix` is on.
- The GPS counts as there (MSP_STATUS sensors bit 3) as soon as it talks, fix or not; cleared after 2.5 s of silence.
  FEATURE_GPS is 1 << 7 (MSP_FEATURE_CONFIG); a UART with function 2 is the GPS port (`serial`).

## Map of the code

| Area | Files |
|---|---|
| Link, RC, telemetry | `ControlLink`, `Connection`, `RcSession`, `RcMapper`, `Pacer`, `FcTelemetry`, `CellCount`, `LinkLog` |
| Spectator mode | `Spectate` (packets), `SpectatorHost` (pilot side), `SpectatorClient` (spectator side) |
| App log | `AppLog` (file, uncaught exceptions, freeze watch) |
| Controller | `Joystick`, `JoystickService`, `CalibrationWizard`, `AxisCalibration` |
| Video | `video/` (`VideoPipeline`, `VideoDecoder`, `FrameAssembler`, `BitrateControl`, `LatencyMeter`) |
| Window, OSD, wizards | `gui/` (`MainWindow`, `VideoView`, `OsdRenderer`, `OsdLayout` / `OsdElement` / `OsdSettingsDialog` (SETTINGS > OSD), `SetupWizard`, `PcSetupWizard`, `PhoneGuideWizard`, `DroneSetup`, `Tactical*`) |
| Setup helpers | `setup/` (`Tailscale`, `BetaflightPlan`, `BetaflightUsb`, `ModeSwitch` (switches, failsafe, GPS rules), `ApkShare`, `MotorTest`, `UpdateCheck`, `SelfUpdate`, `PcPrep`) |
| Virtual fleet | `fleet/` (`FleetMain`, `Fleet`, `VirtualDrone`, `FlightModel`, `LinkImpairment`, `Scene`, `VirtualOverwatch`), `gui/FleetPanel`; DRAUPNE Link's side `VirtualFleet` |
| Installer | `src/installer/main.wxs`, `check-running.js` |
| Phone app | `flight-app/` (overlay on upstream Flight 2.3.0; see its README). Overlay files replace upstream's whole: `Udp` (stats, spectator answer), `InternalCamera` (frame-rate range), `UpdateCheck`, ... |
