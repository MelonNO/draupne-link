# Handoff

Everything needed to pick up work on DRAUPNE Link from a new session or another computer. Read this first, then
[README](../README.md) (what it does, troubleshooting), [CHANGELOG](CHANGELOG.md) (what changed when),
[PROTOCOL](PROTOCOL.md) and [ROADMAP](ROADMAP.md).

## Where things stand (2026-10-31, 1.0.18)

- **Released:** DRAUPNE Link **1.0.18** (Latest), bundling phone app **DRAUPNE Flight 2.3.0-pc.12**: the spectator
  picture fix (1.0.17 cut off full-size forwarded packets), a steady camera frame rate (60 by default) and the video
  figures in the link log. 1.0.17 brought spectator mode, the app log, and Drone setup's ARM / flight mode / failsafe /
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
  - Phone app 2.3.0-pc.12 on the phone (pc.7-pc.12 never run on a phone): LOCK SCREEN (touches ignored but the HOLD TO UNLOCK box, dimmed, rotation
    held, back ignored), portrait (both cards shown, header fits), Direct connection on a fresh install. Built and
    published, never run on a phone or emulator.
  - 1.0.11 on the drone: the FLY timer counts while armed, CORE temperature and the flight mode names show, and the
    phone now sees the drone armed (its travelled distance, used by the OSD's optional "Distance flown", counts).
  - The upgrade from DroidDrone PC 1.0.15 to DRAUPNE Link 1.0.16: settings kept, one app in Program Files, old
    firewall rules gone, phone app updated in place.
  - 1.0.17's Drone setup on the real board, props off: ARM to AUX1, flight modes on AUX2, failsafe GPS rescue and
    AUX4 (check the Modes and Failsafe tabs in Betaflight Configurator); "GPS hardware failure" with the GPS unplugged
    (MSP_STATUS sensor bit 3, MSP_FEATURE_CONFIG, MSP_BOXIDS - all from Betaflight's source, never on a board).
  - The steady 60 fps with phone app pc.12: does this phone's camera list 60-60 (its log says "Min FPS / Max FPS"),
    and how dark is it indoors? The link log's new video columns for a poor picture.
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

## Testing and diagnosing

- `gradlew.bat test`: 91 tests (protocol, video, telemetry, calibration, Betaflight CLI plans, update check, cell count).
- **UI checks** are done by painting windows and components off screen into PNGs from small throwaway programs (kept
  in the git-ignored `build/` folder), never by moving the mouse on the desktop.
- **Link log:** `%APPDATA%\DraupneLink\logs\link-*.csv`, 10 rows a second while connected. It has ping, RC and
  telemetry gaps, RXLOSS / failsafe / armed, the **sticks actually sent** (`channels`, AETR + AUX), the Tailscale path,
  and the phone's own measurements (stick gaps in and out, USB write times). Start here for any flight complaint.
  Examples: a landed drone that leans means the sticks weren't 1500 at rest; micro failsafes meant the phone held its
  USB writes.
- Installer changes: build with `packageInstaller` and inspect the MSI tables. jpackage's `--temp` keeps the
  intermediate `.msi`. Test with `msiexec /i ... /l*v log`.

## Betaflight facts this code relies on

- One MSP message per serial task cycle; `serial_update_rate_hz` defaults to 100, too slow for sticks plus telemetry.
  Drone setup sets 2000 (1000 on F411).
- No sticks for 100 ms: RXLOSS (last sticks held 300 ms, then stage 1). Stage 2 (disarm / land) after 1.5 s. Arming
  needs 0.5 s of clean data.
- The cell count is detected once, when the battery appears. With the FC already on the phone's USB power it can lock
  onto 1S / 2S: `CellCount` corrects the OSD, and Drone setup writes `force_battery_cell_count`.
- With Air mode, an armed drone on the ground keeps correcting (I-term). Disarm after landing.

## Map of the code

| Area | Files |
|---|---|
| Link, RC, telemetry | `ControlLink`, `Connection`, `RcSession`, `RcMapper`, `Pacer`, `FcTelemetry`, `CellCount`, `LinkLog` |
| Controller | `Joystick`, `JoystickService`, `CalibrationWizard`, `AxisCalibration` |
| Video | `video/` (`VideoPipeline`, `VideoDecoder`, `FrameAssembler`, `BitrateControl`, `LatencyMeter`) |
| Window, OSD, wizards | `gui/` (`MainWindow`, `VideoView`, `OsdRenderer`, `OsdLayout` / `OsdElement` / `OsdSettingsDialog` (SETTINGS > OSD), `SetupWizard`, `PcSetupWizard`, `PhoneGuideWizard`, `DroneSetup`, `Tactical*`) |
| Setup helpers | `setup/` (`Tailscale`, `BetaflightPlan`, `BetaflightUsb`, `ApkShare`, `MotorTest`, `UpdateCheck`, `SelfUpdate`, `PcPrep`) |
| Installer | `src/installer/main.wxs`, `check-running.js` |
| Phone app | `flight-app/` (overlay on upstream Flight 2.3.0; see its README) |
