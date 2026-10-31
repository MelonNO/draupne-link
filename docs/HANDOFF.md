# Handoff

Everything needed to pick up work on DRAUPNE Link from a new session or another computer. Read this first, then
[README](../README.md) (what it does, troubleshooting), [CHANGELOG](CHANGELOG.md) (what changed when),
[PROTOCOL](PROTOCOL.md) and [ROADMAP](ROADMAP.md).

## Where things stand (2026-10-31, 1.0.20)

- **Released:** DRAUPNE Link **1.0.20** (Latest), bundling phone app **DRAUPNE Flight 2.3.0-pc.14**: the fix for pc.13,
  which on a test phone started no video (intra refresh, where its encoder claimed support) and, the video start being
  on the packet thread, held up pings and sticks. pc.14: no intra refresh, the video starts on its own thread
  (`Udp.videoStarter`); the PC counts pongs stopping while telemetry comes as LINK STALL. 1.0.19 brought video over weak
  4G (paced bursts, 30 fps, key frames on request, intra refresh, the PC deciding the bitrate, 540p on a weak link),
  Connect as pilot / spectator, the controller picker in setup. 1.0.18: the spectator picture fix and the link log's
  video figures. 1.0.17 brought spectator mode, the app log, and Drone setup's ARM / flight mode / failsafe /
  GPS rescue switch. 1.0.16 was the rename from
  DroidDrone PC (repository MelonNO/droiddrone-pc became MelonNO/draupne-link; GitHub redirects the old name, so never
  create a repository with the old name). 1.0.15 and older can't install newer versions by themselves: their UPDATE
  opens the release page. 1.0.14 was never released; `fixes-1.0.14` is a fallback tag with the fixes alone.
- **Kept on purpose:** Java packages `de.droiddrone.*`, the phone app's package `de.droiddrone.flight.pc` and its
  signing key, the installer's upgrade UUID, the signing key folder `%APPDATA%\DroidDronePC-build`, and the
  references to the upstream DroidDrone project. Legacy names still looked for: `%APPDATA%\DroidDronePC`
  (settings copied once, `AppPaths`), the old firewall rule names (`WindowsSetup.OLD_RULES`), `DroidDrone PC.exe`
  (installer's running check) and dead `DroidDrone PC.lnk` shortcuts.
- **Flies:** RC, video, OSD and telemetry over 4G (phone on Tailscale) work on the real drone.
- **Waiting on the pilot:**
  - Phone app 2.3.0-pc.14 on the phone (pc.7-pc.12, pc.14 never run on a phone; pc.13 hung on a test phone): LOCK SCREEN (touches ignored but the HOLD TO UNLOCK box, dimmed, rotation
    held, back ignored), portrait (both cards shown, header fits), Direct connection on a fresh install. Built and
    published, never run on a phone or emulator.
  - 1.0.11 on the drone: the FLY timer counts while armed, CORE temperature and the flight mode names show, and the
    phone now sees the drone armed (its travelled distance, used by the OSD's optional "Distance flown", counts).
  - The upgrade from DroidDrone PC 1.0.15 to DRAUPNE Link 1.0.16: settings kept, one app in Program Files, old
    firewall rules gone, phone app updated in place.
  - 1.0.17's Drone setup on the real board, props off: ARM to AUX1, flight modes on AUX2, failsafe GPS rescue and
    AUX4 (check the Modes and Failsafe tabs in Betaflight Configurator); "GPS hardware failure" with the GPS unplugged
    (MSP_STATUS sensor bit 3, MSP_FEATURE_CONFIG, MSP_BOXIDS - all from Betaflight's source, never on a board).
  - **Video over the far 4G link with pc.13** (2026-10-31 before: 0.25 Mbit/s, ~2 damaged key frames a second, the
    top of the screen - the end of each frame, the camera being upside down - grey or green). In the link log:
    `video_damaged` down, `camera_fps` 30, `phone_video_mbps` over 1, `key_requests`, `video_res` (960x540 only on a weak link), `decode_ms` / `draw_skipped` / `bunched` (why
    only half the frames were drawn at 60 fps). Pacing values in the overlay's `Udp.packetGapNs` are a first guess.
  - The steady 30 fps (no longer a setting): does the picture stay smooth and bright enough indoors? The phone's log
    says "Min FPS / Max FPS". The link log's video columns for a poor picture.
  - Spectator mode with two PCs and phone app pc.11 (only tested on loopback, `SpectatorTest`): picture quality at
    600 ms, the pilot's ping and sticks unchanged, the pause when the pilot's link slows.
  - The app log's freeze report (WINDOW NOT RESPONDING with all stacks) on a real freeze.
  - The motor test (SETTINGS > Motor test) on the real flight controller, props off.
  - First real use of **UPDATE** (1.0.9 to 1.0.10): it should download, install with a progress bar and restart.
  - Calibrate again with hands off at the centre step. The old pitch centre was 18 us off, which made the drone lean
    forward when landed and armed.
  - SETUP > Drone setup with the flight controller on USB, to write `force_battery_cell_count`.
  - Phone app to 2.3.0-pc.5. It fixes RXLOSS arming blocks (pc.4) and reports its version (pc.5). Afterwards, check a
    link log for remaining phone-to-FC gaps.
- **Built but never tried on hardware:**
  - UPDATE end to end. The hand-over was tested with stand-in files; the download and checksum were tested against
    the real release.
  - The installer's Retry / Cancel message in the full wizard. Verified only in a quiet install, which cancels without
    showing it.
  - Writing `force_battery_cell_count` to a real board.
  - The phone's version record on a real phone.
  - OSD pitch direction (`osdInvertPitch`).
  - Phone app 2.3.0-pc.7's touch lock and portrait layout.
  - The fixes tagged `fixes-1.0.14` (see CHANGELOG): the phone's fast USB reconnect (aim: sticks back within 1.5 s of
    an OTG blip), LINK STALL, the radio lock, the installer without folder choice and its JScript check (built, never
    run), the CI phone-app job.
  - New since: warning sounds, RADIO FROZEN? / VIDEO FROZEN, the arm-switch rule (Drone setup's "aux" reading on a
    real board), Drone setup's backup and port list, recording (a real phone stream, H.265), hardware decoding (H.265
    from the phone; measured slower than software at 720p H.264 on the development PC).
  - The motor test's PROPS IN / OUT animation and the OSD editor as a maximised window (built; not looked at while a
    flight controller was on this PC's USB).
  - The motor test (SETTINGS > Motor test): MSP_SET_MOTOR over USB, written from Betaflight's MSP and only tested
    without a flight controller. Try it props off first.

## Ideas not done yet

- **In-app phone app updater** (started, not in a release: kept outside the repo for now). Every update through the
  browser leaves a `DRAUPNE-Flight-*.apk` in the phone's Downloads, and Android doesn't let the app delete those. Plan
  the pilot chose: UPDATE downloads into the app's own cache, checks GitHub's SHA-256 (`digest` of the asset), installs
  with `PackageInstaller` (needs REQUEST_INSTALL_PACKAGES and "Install unknown apps"), deletes the file; leftovers
  deleted at start; a one-time note with a button opening Downloads for the old ones; Phone setup says to use UPDATE
  when the app is already there.

- Spectator: a better picture than the pilot's would need a second stream from the phone (costs the phone's 4G
  upload, so the pilot's delay): not done on purpose. A spectator in relay mode isn't supported.
- Pilot side: show who watches (count is in the pill: LINK READY +N WATCHING).
- Drone setup never changes `gps_rescue_allow_arming_without_fix`; it only warns.

## Rules for working on this repo

- **Latency first.** This is FPV over 4G: every choice in the stick and video paths is weighed by the delay it adds.
  Call out any trade-off that adds delay before adopting it.
- **No personal data in the repo, screenshots or release notes.** That means the Windows user name, PC host name,
  Tailscale IPs, phone model, e-mail and similar. Scan the staged diff for these before every commit. The exact
  patterns are kept in the maintainer's private notes, not here.
- **No AI co-author trailers in commits** (`Co-Authored-By: ...`). Commits are by the maintainer only. A force push
  does not clear GitHub's contributor list, so such a line must never reach GitHub.
- **No secrets in the repo.** The phone app's signing key lives outside it (see below).
- **Publishing needs the maintainer's explicit go-ahead.** That covers tags, GitHub releases and the phone app release.
- **Don't close a running DRAUPNE Link that has the drone link open.** A live drone may depend on it. This is also why
  the installer only asks and never closes the app.

## Build environment (new computer)

| What | Needed for | Notes |
|---|---|---|
| JDK 21 (Temurin) | everything | `gradlew.bat test`, `installDist`, `releaseZip` |
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
- The release workflow takes about 3 minutes. Afterwards check `gh release list` (v<version> Latest) and the three
  assets.

## Testing and diagnosing

- `gradlew.bat test`: 169 tests (protocol, video, telemetry, calibration, Betaflight CLI plans, update check, cell
  count, spectator mode end to end on loopback, flight mode / ARM / failsafe planning, setup page composition).
  Loopback tests need full-size packets to catch size bugs: 1.0.17's spectator cut-off passed with a plain test
  picture (see `SpectatorTest.fullSizePacketsReachTheSpectatorWhole`).
- **App log:** `%APPDATA%\DraupneLink\logs\app-DATE.log` (`AppLog`, 14 days): System.out/err, the window's log
  lines, uncaught exceptions with stacks, and **every thread's stack when the window stops responding for 5 s**.
  Start here for a crash or freeze. (A freeze at 12:22 on 2026-10-31, before the log existed, left no trace.)
- **UI checks** are done by painting windows and components off screen into PNGs from small throwaway programs (kept
  in the git-ignored `build/` folder), never by moving the mouse on the desktop.
- **Link log:** `%APPDATA%\DraupneLink\logs\link-*.csv`, 10 rows a second while connected. It has ping, RC and
  telemetry gaps, RXLOSS / failsafe / armed, the **sticks actually sent** (`channels`, AETR + AUX), the Tailscale path,
  and the phone's own measurements (stick gaps in and out, USB write times). Since 1.0.18 also the video:
  `video_rx_fps`, `video_mbps` (as received: the phone's bitrate after `BitrateControl`), `video_damaged`,
  `video_lost`. `video_fps` is the frames **shown**. Start here for any flight or picture complaint.
  Examples: a landed drone that leans means the sticks weren't 1500 at rest; micro failsafes meant the phone held its
  USB writes.
- Installer changes: build with `packageInstaller` and inspect the MSI tables. jpackage's `--temp` keeps the
  intermediate `.msi`. Test with `msiexec /i ... /l*v log`.

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
| Installer | `src/installer/main.wxs`, `check-running.js` |
| Phone app | `flight-app/` (overlay on upstream Flight 2.3.0; see its README). Overlay files replace upstream's whole: `Udp` (stats, spectator answer), `InternalCamera` (frame-rate range), `UpdateCheck`, ... |
