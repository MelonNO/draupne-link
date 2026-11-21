# The test bench

Everything of DRAUPNE Link and DRAUPNE Flight that can be tested on one PC, tested there: the real Windows app run
sealed off and its buttons pressed, the real phone app in an Android emulator, a bad network between them, and a
report with every picture. Built 2026-11-19 on `beta`. What it has found: [BENCH-FINDINGS.md](BENCH-FINDINGS.md).
What has run, what waits: [BENCH-RUNS.md](BENCH-RUNS.md). The 1,700 fast tests (`gradlew test`) are as before and
are not part of it.

## Start it

```
.\bench.ps1 quick                  a quarter of an hour: the bench's own parts, the main scenarios, a few bad links
.\bench.ps1 full                   every suite once (about 40 min), then a 10 min long run
.\bench.ps1 full -Hours 8          every suite, then the long run for what is left of 8 hours
.\bench.ps1 soak -Hours 2          the long run only
.\bench.ps1 night -Until 07:30     the night: four long scenarios one after the other and the map's beside them, until that hour
.\bench.ps1 night -Tests *LongFlight* -Hours 0.2    one of the night's scenarios tried out for a few minutes
.\bench.ps1 ui -Tests *Fleet*      one suite (self ui link edge emulator sitl findings), or some scenarios of it
.\bench.ps1 ui -Tests *Looks* -Scale 1.5    the picture tours at another Windows text scaling
.\bench.ps1 ui -Screen 1093x584    every scenario on a screen that small (a 1366 x 768 laptop at Windows' 125 %)
.\bench.ps1 report -Root <folder>  a run's report written again
.\bench.ps1 hw                     REAL HARDWARE: the Windows app, sealed off, against this bench's one real phone
.\bench.ps1 phone                  REAL HARDWARE: the real phone itself
```

Each run gets a folder under `%LOCALAPPDATA%\DraupneBench\runs\` and ends by printing its report's address:
`report\index.html` (suites, scenarios with their films, the bad-link tables, the long run's curves, what the
pictures' own checks found, the count of buttons used) and `report\pictures.html` (every picture, small).
`%LOCALAPPDATA%\DraupneBench\runs.log` has a line per run. **The bench deletes nothing**: old runs stay until
somebody removes their folders.

## What is safe about it

- **The app under test is sealed off.** Each scenario gets a made-up user: its own settings folder, Documents and
  Videos inside the run's folder. A fence in the test's Java (`bench/Fence`, a security manager) refuses every send
  to an address that is not this PC's loopback, every program that is not on a short list, and every write or
  delete outside the bench's folders - and writes each attempt into the report. Radio, USB, internet and
  loudspeaker are stand-ins. So a bench app cannot reach a real phone, a flight controller on USB, the internet,
  or the pilot's flights, logs and settings. The two hardware runs (`hw`, `phone`) are the one exception, and only
  for one address: see "Real hardware" below.
- **The real mouse and screen are used only while no real drone is armed.** The bench reads (never writes) the
  newest `link-*.csv` of the DRAUPNE Link the pilot flies with; armed there means: let go of the mouse, park the
  bench's windows off the screen, press through the app's own event queue instead ("quiet"), until disarmed.
  `-Mode quiet` never takes the mouse; `-Mode desktop` always does.
- **A real press never lands on another program.** The bench's windows are kept above the others, and the mouse
  goes down only where the screen shows what the control paints; otherwise the press goes through the event queue
  and the film says so. Keys are never typed on the real keyboard.
- A run's pictures show the bench's windows only (the rest of the screen is blacked out); they stay on this PC.

## What it tests

| Suite | `bench.ps1` | What |
|---|---|---|
| The bench's own parts | `self` | The fence refuses what it must, the sandbox is where the app keeps its files, the stand-ins answer. |
| The Windows app | `ui` | The real window, buttons pressed with the real mouse, every step photographed: fly (PILOT UAV, arm, fly, STANDBY MODE), flights kept / not kept, every menu and switch, settings after a restart, FLEET, OUT OF ACTION, LAST KNOWN, the setup guide's pages, the tour's start, picture tours at two window sizes, a random presser. MAP TOOLS used (draw, erase, CLEAR ALL, the ruler and KEEP, GCS SEARCH, the tools' own lines, GO TO), a flight's REPORT and EXPORT, a phone put to sleep from the smallest window, the map on a bad internet (not there, stalling half way through every tile, answering errors: whole again by itself once the internet is), CALIBRATE walked through all its steps with the made-up radio, OBSERVATION of three drones with one taken over, pins and the map's own export and import. A scenario at a window size runs on a made-up screen of that size: a window that does not fit it is remarked. |
| Bad link | `link` | The PC's real link and a made-up drone, 10 kinds of bad network (loss to 50 %, bursts, jitter, 600 ms, narrow band, doubled packets) and 7 cuts (1 s to 1 min, both ways and one way), the safety rules watched 100 times a second: never armed with the switch off, no re-arm after a failsafe. |
| Edge cases | `edge` | The app killed dead in mid-flight and started again; rubbish in the settings file, and numbers there that are none; broken, empty and cut-short flight files; 400 flights. |
| The long run | `soak` | Flight after flight for hours on changing bad links, every third with a cut in the air: no flight lost, no freeze, memory and threads flat. |
| The night | `night -Until HH:mm` | What only hours can test, each with its share of the time until the hour given (one flight for hours first, so that begun before midnight it flies across it; the map's part beside the others all night - it has no window and takes next to no processor, and has a build folder of its own under the run's folder): **sleep and wake** in real time (a phone put to DEEP SLEEP or OVERWATCH checks every 1, 2 or 5 minutes; PILOT UAV must have it at its next check, cycle after cycle); **one flight that lasts for hours** (the same link, track and recording past the hour on every clock, across midnight when begun before it; memory against the flight's length; afterwards one flight with its true length and a REPORT); **the real phone app left running** in the emulator (flown on a poor link with cuts, put to sleep, left through its own check, woken - round after round, its memory and its log watched); **the map on an internet that comes and goes** (the app's own tile cache against a server that says nothing, sends half a tile, hangs up, says 503 or is gone - for seconds or minutes, hundreds of times; after each the map must be whole by itself, and how long that took is the figure; no window); then **flight after flight** as the long run. A part that hangs is ended at its time and the next begins. One table a part in the report. |
| The phone app | `emulator` | The built APK in the Android emulator: first start and its questions, the PC's real link to it, the emulated camera's picture, a weak link, a cut, STANDBY; a link dead one way (F-001's measure); the phone's sending standing still until its socket is opened anew (F-039); the app ended by an error while flown, killed asleep, and ended again and again - back by itself each time but the last (F-047, `*PhoneCrash*`, 13 min); the picture's size changed and the camera stopped in the same moment, twenty times (`*PhoneCameraRace*`). |
| Betaflight | `sitl` | Not built: needs the administrator step below. |
| Findings | `findings` | One test per listed bug; fails until fixed. |
| Real hardware: the PC app | `hw` | Never part of `quick` or `full`. The same sealed app against this bench's one real phone and, through it, its flight controller: battery in, **no propellers**. So far one scenario: the phone found, the link, the picture, STANDBY - nothing armed. See "Real hardware". |
| Real hardware: the phone | `phone` | Never part of `quick` or `full`. The real phone itself. No scenario yet. |

Beside what a scenario checks, every scenario fails on: an error nobody caught, the window not answering for more
than half a second, anything the fence refused.

## The emulator

Set up on this PC (2026-11-19): Android SDK emulator, `system-images;android-34;google_apis;x86_64`, the device
`draupne-bench` in `%LOCALAPPDATA%\DroidDroneBuild\avd`. The suite starts it by itself, without a window (a minute).
It installs the newest APK in `build\flight\` (`flight-app\build.ps1`). The emulator has no USB, so the phone has
no flight controller there - the header says FC LOST on a link that is up (`Pilot.linkUp` takes both). The first
time, Windows' firewall asks about `adb`: either answer does.

Two things of the emulator's own to know: its phone sends by Wi-Fi and hears by another way, and its Wi-Fi keeps a
little of all that is sent and never frees it - an app's socket is full after a minute or two of video (F-039). The
phone app opens its link socket anew when that happens; `emulator -Tests *PhoneSocket*` flies until it has and
measures how long the PC is without its link. `BENCH_KEEP_APP=1` in the environment leaves the app on the phone as
it is instead of installing the newest build.

Since 2026-11-20 the emulator's phone is set up as a drone's phone is (`PhoneRig.grantPermissions`): the app may
lock the screen (the status card's "Allow screen lock for deep sleep"; `dpm set-active-admin`) and the phone has a
lock screen to swipe away (`locksettings set-disabled false`). Until then a deep sleep left its screen lit, and
nothing the app does behind a locked screen - opening itself over it when the PC wakes it, or after an error ended
it - was ever run. `PhoneRig.launch` wakes and unlocks first. The permission stays on the emulator (Android lets
only a test build give it back).

What ended the phone app is in Android's own log, not in the app's: `adb -s emulator-5584 logcat -d -b all` while
the emulator is still up (the night's is kept beside its film). "The phone app's service is running" now asks for a
living process - Android also "knows" a service that crashed and waits to be restarted.

## The map and the internet

The bench's internet is made up, so what the real map servers do is not in it. Three things stand in:

- **A server that behaves as a network does on a bad day** (`testing/TroubledServer`): takes a request and says
  nothing, sends half an answer and stops, hangs up, says 503, is gone and comes back. `TileCacheBadNetworkTest`
  (fast tests) holds the map's tile cache against it - the map must have its tiles a few seconds after the server
  is good, with nobody touching it - and `NetTest` every other fetch of the app.
- **The same in the real window**: `.\bench.ps1 ui -Tests *MapNetwork*`.
- **By hand, on the real internet**: `gradlew mapProbe` asks each of the map's five servers for one tile the way
  the app does and says what came and how fast. For the day the map loads nothing though the PC has internet: a
  server that moved or refuses the app shows in its line; a PC whose own network is at fault fails them all. The
  app's log (`%APPDATA%\DraupneLink\logs\app-DATE.log`) says "Map: its server was not reached (why)" and "answers
  again" when it happens in the field.

A map that loads nothing *on purpose* says so in its corner: "SAVED TILES ONLY - ... is armed": while a drone on
this PC may be flying - also for ten minutes after a link was lost armed, until IT'S DOWN - RESUME - nothing is
fetched, so the sticks and the picture have the link to themselves.

## Real hardware

`.\bench.ps1 hw` and `.\bench.ps1 phone` (`gradlew benchHw`, `benchHwPhone`; tags `hw`, `hwphone`) are the runs that
reach real things: one phone on one drone, battery in, **propellers off**. Built 2026-11-21, not yet run on a phone.

- **The hardware file.** `%LOCALAPPDATA%\DraupneBench\hardware.properties`, this PC's own, outside the repo and never
  committed: the phone's address and link port, its adb serial, the board's port, the pack's cells and the volts per
  cell under which a run ends, the keeper's port. Start from [hardware.example.properties](hardware.example.properties)
  (made-up values). Without the file every hardware scenario skips itself and `bench.ps1` says what is missing.
- **Still sealed off.** The app has its made-up user, radio, USB, internet and loudspeaker as in every run. The fence
  lets it reach exactly one thing outside this PC: the phone's address at its link port (`Fence.Reach`: addresses
  written out, no names, no ranges, no multicast). Another port of the phone, another address, a name looked up are
  refused and reported as ever. A way through can only be made in a JVM started with `-Dbench.hardware=true`, which
  the two hardware tasks set and no other: in any other run the attempt itself fails.
- **Before the app starts** (`HardwareRun`): no DRAUPNE Link running on this PC, none with a drone's link open (the
  armed guard), this PC's port of the phone's number free, Tailscale installed. Anything in the way is said and
  nothing is started. A hardware scenario has no made-up drones and cannot set where the link goes or how the radio's
  axes become sticks.
- **The made-up radio's hard limits** (`FakeRadio`): a throttle ceiling of 1100 that no request gets over (each one cut
  is counted and said in the film), and hands off - ARM off, throttle down, in one move from any thread, held until
  `handsOn()`. A hardware run starts hands off and ends hands off for good; a scenario that arms says `handsOn()`
  first. At the end the throttle the app really sent is read from its link log: over the ceiling fails the scenario.
- **How the phone gets into the PHONE list.** The app finds phones only by asking Tailscale who is on its network, so
  the bench's door answers for it: this PC and one other device, the phone, online at the file's address - whether
  that is a Tailscale address or not. The real Tailscale is never asked. `phone.path=relay` in the file tells the app
  the way is relayed (it then holds bad coverage mode, as on a real relayed link); left out, direct.
- **Not lowered in priority.** Delay figures are measured in these runs, and theirs is the real link.
- **To know:** UPDATE PHONE APP cannot work in a hardware run (the phone would fetch from this PC, and the fence takes
  nothing from the phone but its link port). A phone that has no DRAUPNE name yet would be given one by the app under
  test, which knows no other phone: name it with the real app first (the scenario checks). The run's folder then
  holds the phone's address, its key in the made-up settings file, and where the drone is: it stays on this PC.
- **The board made safe** is `bench.ps1 hw`'s last step, however the run went - a call to `gradlew boardSafe`, which
  does not exist yet and is skipped until it does. The keeper that owns the flight controller's port, the watch on
  the battery and the dead man's switch are not built yet either.

## Betaflight firmware (waits for an administrator)

Real firmware as a program (SITL) needs Windows' Linux part. Once, as administrator: `wsl --install -d Ubuntu`,
then restart the PC (that closes a running DRAUPNE Link). After that the suite can be built: `.\bench.ps1 sitl`
says so until then.

## For whoever adds a scenario

- Classes live under `src/test/java/de/droiddrone/bench/**`, are named `...Scenario` and carry a tag: `ui`,
  `badlink`, `edge`, `soak`, `emulator`, `sitl`, `finding` - or `hw`, `hwphone` for real hardware. One JVM per class.
- A hardware scenario: `Assumptions.assumeTrue(Hardware.present(), ...)` first, then
  `AppRig.scenario("name").hardware(Hardware.load()).run(rig -> { ... })`; `rig.hardware` has the bench's facts.
  `rig.radio.handsOff()` is the hard stop (ARM off, throttle down, held); a made-up drone's scenario lets go of the
  sticks with `rig.radio.sticksAtRest()` (the switches stay), which is what `handsOff()` meant until 2026-11-21.
- `AppRig.scenario("name").drones(2).run(rig -> { ... })` starts the app; `rig.ui` presses (`click("WORDS")`,
  `clickAt`, `rightClickAt`, `choose`, `key`, `fill`), waits (`until`, `waitForControl`) and photographs (`shot`);
  `new Pilot(rig)` flies; `rig.radio` is the radio, `rig.drone(name)` a made-up drone, `rig.web` the internet,
  `rig.doors.asked()` what the app wanted of other programs; `rig.check(ok, "what")` is a check that goes on.
- To see what a press leads to before writing it down: put commands in `explore.txt` in the run's folder and run
  `gradlew benchExplore` (`ui/ExploreScenario` lists them).
- A bug found is fixed (the pilot's word, 2026-11-19) and gets its paragraph in BENCH-FINDINGS.md. One that can't
  be fixed at once is not worked around: `rig.known("F-0xx", "words", "of its message")` in the scenario that
  meets it and a test tagged `finding` that fails until it is fixed; then the test moves to the suite it belongs to.
- While a long run goes, never plain `gradlew` in the repo (the run's app loads its classes from `build/` as it
  goes): `gradlew <tasks> -PaltBuild=build-alt --project-cache-dir <a folder outside the repo>` builds beside it.
- The ESCs of a real drone (battery in, props off), as quiet as each step can be: `gradlew escProbe -Pesc=check
  [-Pport=COM9]` is silent (asks the flight controller only: battery, whether each ESC answers it, the beeper
  settings); `gradlew escProbe` reads each ESC's firmware and beacon - the ESCs' start-up tones once at the end,
  no motor turns; `-Pesc=row` is the same visit made by the code behind Drone setup's button, with the line the row then
  shows; `-Pesc=silence` also switches the beacons off in the same visit; `-Pesc=beeper` is silent (the phone app's
  beeper silence written to the board's memory, read back, put back; nothing saved); `-Pesc=spin` turns each motor
  for 0.8 s and is only for when the pilot asks for it.
- The app's doors for the stand-ins (the app never opens them itself): `Outside` (web, programs, browser,
  Explorer), `Joystick.use`, `BetaflightUsb.use`, the system properties `draupne.appData` and
  `draupne.bench.madeUpSaves`.
