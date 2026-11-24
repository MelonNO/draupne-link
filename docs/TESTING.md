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
.\bench.ps1 sitl                   Betaflight 4.4.1 itself, as a program in WSL (-Betaflight 4.5.5 another version)
.\bench.ps1 hw                     REAL HARDWARE: the Windows app, sealed off, against this bench's one real phone
.\bench.ps1 hw -Tests *HwLinkMatrix* -Quick    the bad-network box on the real phone, two networks and two cuts (-Quick: a suite's quick cut)
.\bench.ps1 hw -Tests *HwNetworkSwitch* -Quick the phone's own network switched under the link: Wi-Fi off, Wi-Fi on, airplane mode 10 s
.\bench.ps1 hw -Tests *HwGlassToGlass* -Quick  the picture delay glass to glass: the phone films the Latency test's code on the screen
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
| The phone app | `emulator` | The built APK in the Android emulator: first start and its questions, the PC's real link to it, the emulated camera's picture, a weak link, a cut, STANDBY; a link dead one way (F-001's measure); the phone's sending standing still until its socket is opened anew (F-039); the app ended by an error while flown, killed asleep, and ended again and again - back by itself each time but the last (F-047, `*PhoneCrash*`, 13 min); the picture's size changed and the camera stopped in the same moment, twenty times (`*PhoneCameraRace*`); the phone among three made-up drones in FLEET, asleep and woken (`*PhoneFleet*`). |
| Betaflight | `sitl` | Betaflight 4.4.1 itself, as a program in WSL, set up as the drone is (see "Betaflight as a program"): a snapshot, settings changed, the restore - the board as the snapshot again; the keeper's lock, a window under the cap, its ends, END safe; the drone's own failsafe when the sticks stop, timed. About 6 min. |
| Findings | `findings` | One test per listed bug; fails until fixed. |
| Real hardware: the PC app | `hw` | Never part of `quick` or `full`. The same sealed app against this bench's one real phone and, through it, its flight controller: battery in, **no propellers**. Three scenarios: the phone found, the link, the picture, STANDBY - nothing armed; the whole chain to the board through the keeper, the sticks at rest counted at the board (an arm window only when the hardware file says `board.mayArm=true`); and the bad-network box - the bad-link table between the app and the real phone, the sticks counted at the board (`*HwLinkMatrix*`, about 25 min). Beside them the phone's own network switched under the link - Wi-Fi to mobile data and back, mobile data off, airplane mode, a quick Wi-Fi flap - the phone put back as found (`*HwNetworkSwitch*`, about 12 min). See "Real hardware", "The keeper", "The bad-network box", "The phone's own network switched". |
| Real hardware: the phone | `phone` | Never part of `quick` or `full`. The emulator's phone scenarios against this bench's one real phone on adb, with the bench build of the app: first start, the PC's link and the camera's picture through the bench's wire, a weak link, a cut, a link dead one way, the camera closed under a size change (a real camera here), the app ended by an error and killed asleep - asleep "on battery" though it is on a cable - and, with `-Tests *PhoneNight* -Hours 8`, the app left running for hours. Ends with `gradlew phoneReset`. See "Real hardware", "The phone run". |

Beside what a scenario checks, every scenario fails on: an error nobody caught, the window not answering for more
than half a second, anything the fence refused.

## The emulator

Set up on this PC (2026-11-19): Android SDK emulator, `system-images;android-34;google_apis;x86_64`, the device
`draupne-bench` in `%LOCALAPPDATA%\DroidDroneBuild\avd`. The suite starts it by itself, without a window (a minute).
It installs the newest bench build in `build\flight-bench\` (`flight-app\build.ps1 -Bench`): since 2.3.0-pc.58 (Q-006) the
released app answers over Tailscale only, and the emulator has none - the bench build's link takes every address in the
emulator alone, and holds the released app's rule on a real phone. The update test flies the released app's package
there (its two builds do the same); before an install the other package is stopped, as both want the link port. Make the
bench build before an emulator run. The emulator has no USB, so the phone has
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

**Other Androids** (2026-11-22): `.\bench.ps1 emulator -Avd draupne-a26` (Android 8), `draupne-a30` (11), `draupne-a33`
(13), `draupne-a35` (15), each on emulator-5584; a run without `-Avd` gets `draupne-bench` (14) back. The rig knows
each image's ways: storage granted (Android 8 and 9 ask), ALLOW in capitals, the forwarded port led through the older
images' own router (root first), sockets read from /proc where `ss` lists none; on Android 15 the network card the
forward ends at given its address (as root, once until the emulator restarts); on Android 11, where uiautomator
cannot read the app's screen, the app's state taken from its process, Android's services and the bench build's STATS,
said in the film. Before Android 12 the update test expects Android's own "update this app?" tap; on Android 11
switching "Install unknown apps" on ends the app, so the test opens it again and waits for the link. On phone app
pc.58-beta all five passed 7 of 7 (2026-11-22).

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
reach real things: one phone on one drone, battery in, **propellers off**. Built 2026-11-21. `phone` has run on the
real phone since 2026-11-22, `hw` with the keeper on the real board since 2026-11-23 - battery out, no arm window
yet ([BENCH-RUNS.md](BENCH-RUNS.md)).

- **The hardware file.** `%LOCALAPPDATA%\DraupneBench\hardware.properties`, this PC's own, outside the repo and never
  committed: the phone's address and link port, its adb serial, the board's port, the pack's cells and the volts per
  cell under which a run ends, the keeper's port; `phone.wifi` (on or off, left out: on) says the phone's Wi-Fi as it
  normally is. Start from [hardware.example.properties](hardware.example.properties) (made-up values). Without the file every hardware scenario skips itself and `bench.ps1` says what is missing.
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
- **The board made safe** is `bench.ps1 hw`'s last step, however the run went: the keeper told "make safe and end"
  (`gradlew fcKeeperEnd`), then `gradlew boardSafe` with the port free again. Both set Betaflight's arming lock and
  STOP every motor; see "The keeper" below.

### The board's settings, kept and put back (`gradlew boardSnapshot`, `boardRestore`)

By hand, around anything that writes to the one real board (nothing writes to it before a snapshot and a restore
are proven). Under `src/test/java/de/droiddrone/bench/hw`; nothing of the app changed for them.

```
gradlew boardSnapshot [-Pport=COM9] [-Pescs=true]   the MSP facts, then one CLI visit for diff all and dump all, each
                                                    asked twice and taken only when both answers are the same; filed
                                                    with SHA-256 under %LOCALAPPDATA%\DraupneBench\board\<id>\<time>.
                                                    Reads only; Betaflight restarts once (the CLI). -Pescs=true: the ESCs'
                                                    firmware and settings too (their start-up tones once, no motor turns)
gradlew boardRestore -Psnapshot=<folder> [-Pport=COM9]
                                                    the board filed as it is, then "defaults nosave" and every line of the
                                                    snapshot's dump all; save only when dump all, asked again in the same
                                                    visit, is the snapshot's line for line - else exit, nothing saved.
                                                    Refuses an armed board, another board or firmware, an edited snapshot;
                                                    a board that is the same already is not written to
gradlew boardRestore -Psnapshot=<folder> -Pcompare=only
                                                    read, compare, report - never a write (-Preplay=always writes even
                                                    when nothing differs)
```

What a board's settings are is `dump all` and the MSP facts: `diff all` is not stable across restarts on a real
board (Betaflight's own defaults move; BENCH-FINDINGS, "The bench's own notes"), so a `diff all` that differs while
those are equal is one line of the report and no failure. The copy left in Betaflight backups for a paste by hand is
the replay's lines, not the diff. ESC settings are compared, never written.

### The keeper (`gradlew fcKeeper`)

Built 2026-11-22 against a made-up board (`gradlew test --tests *FcKeeper* --tests *MspTap* --tests *HwGuard*
--tests *BoardCap*`, `benchSelf --tests *FcKeeperProcess*`); **first run on the real board 2026-11-23 08:01**,
battery out and no arm window (its figures at the end of this part). The flight controller is on this PC's USB, not
on the phone's: the phone app's bench build reaches it over TCP down the USB cable (`adb reverse`), and the
program at this PC's end of that is the keeper (`bench/hw/FcKeeper`). It is a process of its own, the only one that
has the board's port while it runs, and the place where the rig's safety is kept - so it holds when the test's JVM
dies. `bench.ps1 hw` starts it before the suite (when the hardware file has `keeper.port`) and ends it after.

```
gradlew fcKeeper [-Pfolder=...] [-Pbudget=<armed seconds an hour>]
                                   the keeper, until it is told to end (its files: %LOCALAPPDATA%\DraupneBench\keeper)
gradlew fcKeeperState              its state in one line
gradlew fcKeeperEnd                make safe and end; fails unless the board was left locked, disarmed, at rest
gradlew boardSafe [-Pport=COM9]    the same lock and STOP with no keeper running (after one was killed outright)
gradlew boardCap -Pport=COM9 -Plimit=15
                                   with no keeper running: Betaflight's motor_output_limit in every PID profile, saved,
                                   read back (an armed board is refused); -Plimit=100 is Betaflight's own again
```

`boardSafe` without a port does it to every flight controller on this PC's USB, and the lock stays on a board until
that board is restarted: name the port when another one is plugged in (`bench.ps1 hw` names the hardware file's).

- **What the keeper reads of the board before anybody gets a lease** (2026-11-22, second round; a real board's
  always): one visit to its CLI - `profile`, `get failsafe_procedure`, `get failsafe_delay`, `get failsafe_off_delay`,
  `get failsafe_throttle`, `get failsafe_switch_mode`, `get failsafe_throttle_low_delay`, `rxfail`, then `profile n`
  and `get motor_output_limit` for every PID profile, the profile in use selected again, `exit`. Nothing is set.
  Betaflight restarts at `exit`, and the board is taken once more, locked first. MSP_FAILSAFE_CONFIG (75) is read
  too and held against the CLI's word: nothing else in this repo reads that request, so where the two differ the
  CLI's is taken and the keeper says FINDING. The same read is made again after Drone setup had the board, and for
  another board on the same port. Two lines say it, and `bench.ps1 hw` prints both:
  `The board's own settings, read in its CLI ...: failsafe_procedure = ..., failsafe_delay = ..., ...; rxfail 0 a,
  ..., 3 h, ...; motor_output_limit = 100 in PID profile 0 (the one in use), ...` and
  `The board is taken: ...; when its sticks stop: rxfail holds the throttle at its last value (3 h) and holds the ARM
  switch (AUX1: 4 h) for failsafe_delay 200 (20.0 s), then GPS-RESCUE - in an arm window the keeper ends it first:
  ...; motor_output_limit 100 - OVER the 15 this rig allows (board.outputLimitMax): NO ARM WINDOW until gradlew
  boardCap ...; ...; cannot arm now: RXLOSS+RPMFILTER (beside the keeper's own lock).`
- **The board's own failsafe is not let run on a real board.** A throttle the flight controller makes itself - a
  held stick, a landing, a GPS rescue - is in no stick frame, so the 1100 ceiling does not hold it. In an open
  window the keeper ends the window itself - the lock, then STOP, the motors read back, as for any trip - as soon
  as the board's status says RXLOSS or FAILSAFE (asked ten times a second in a window), and at the latest when no
  stick frame has reached the board for the shorter of 2 s and half its failsafe_delay. No `CLEAR` is asked for
  this end: a cut is what the hardware week tests, and the next window opens once sticks reach the board again
  (no window opens while they do not). `rig.guard` takes the radio's hands off and says so in the film; the run
  goes on. `board.letFailsafeRun=true` in the hardware file switches this off, and the keeper refuses to start on
  that word alone: it wants `board.failsafeSeenOnSitl=true` beside it - somebody has seen on Betaflight as a
  program what this failsafe procedure does. The made-up board and Betaflight as a program let it run unless told
  otherwise (`failsafe=end` by hand).
- **The third throttle layer: the board's own output limit.** No arm window opens on a real board unless
  `motor_output_limit` is at or under the hardware file's `board.outputLimitMax` (15) in every PID profile, or
  when it could not be read. `gradlew boardCap` writes it (through the app's all-or-nothing CLI write, `save` last,
  read back in a second visit); the keeper has to be ended for it - one program has the port. A snapshot taken
  before (`boardSnapshot`) and `boardRestore` put the board's own value back.
- **"cannot arm: ..."** is the board's own word and no failure of the keeper's: the arming blocks that hold beside
  the keeper's lock, by Betaflight's names. With the battery out and bidirectional DShot on no ESC answers the
  flight controller: `RPMFILTER`. It is in the "taken" line, in the line a minute, in the keeper's state
  (`cannotArm=`), in `OPEN`'s answer (`OK window=10000 cannotArm=RPMFILTER`), and in `BoardChainScenario`'s film,
  which stays green on it when `board.mayArm` is not set.

- **Who talks to the board through it**, on this PC's loopback, never two at once: the *phone's lease* (keeper.port
  + 1; whole MSP frames only, and in strict mode - always on a real board - only the requests the phone app really
  sends: no byte of text ever, a `#` would open Betaflight's CLI and an `R` restart it into its bootloader); *Drone
  setup's lease* (keeper.port + 2; `KeeperWire` is the app's own door to a flight controller, `rig.usb.plug(...)`
  in the bench): given only while the board is disarmed and no arm window is open, and while it is held the phone's
  side is cut as one USB socket would be; when it ends the keeper takes the board anew (locked, stopped, its
  channel map and switches read again). And the rig's *control channel* (keeper.port; `KeeperClient` lists its
  commands).
- **The arm lock.** Betaflight's arming block "MSP" (MSP_ARMING_DISABLE; the app shows it as the blocker MSP) is set
  the moment the board is taken and stays set, except inside a window the rig opens: `OPEN n`, 60 s at most, never
  made longer without being closed first. Where the block shows in the board's status is measured on each board; a
  board on which it does not show gets no window, nor does one with 3D mode on (there the throttle's minimum is
  full reverse). The lock is in the board's memory only: a restart clears it, and the keeper sets it again before
  anybody gets a lease. A switch that was already up when the window opens does not arm: Betaflight wants it down
  once first - so a scenario opens the window, then PREARM (if the board has one), then ARM.
- **What ends a window** (closed, locked, STOP, said loudly; no window after it until `CLEAR`): a stick frame with
  more throttle than 1100 (not passed on); the battery under `battery.cellFloor` for 2 s (and no window opens under
  it; a line a minute, always); the window's own time with the board still armed; the hour's armed seconds used up
  (`keeper.motorSeconds`, 600; kept across starts); no heartbeat from the rig for 2 s; the board gone. Armed outside
  a window, however it came about, is locked and stopped at once.
- **The end** - `END`, the process told to go, an error of its own: lock, STOP to every motor, the motors read back
  at rest, the port let go. Exit code 0 only when the board was left locked, disarmed and at rest (3: not known
  safe; 2: it did not start - "in use" when another keeper runs; 5: ended by an error of its own, board safe).
  **A keeper that is killed outright runs none of this:** then the board's own failsafe is what happens - on a
  board whose rxfail holds the throttle and the ARM switch, an armed board keeps its last throttle for all of
  failsafe_delay (20 s at 200) and then runs its procedure (a GPS rescue gives its own throttle). What bounds that
  is the output limit above (15 % whoever asks), `gradlew boardSafe`, and the battery out; `bench.ps1 hw` ends with
  `fcKeeperEnd` and `boardSafe` whatever happened.
- **The guard in the test's JVM** (`bench/HwGuard`, `rig.guard`): it is the heartbeat, twenty times a second, and
  stops the run's flying - the radio's hands off for good, the keeper told, `hardware-stop.txt` in the run's folder,
  no hardware scenario after it until the file is deleted - when the keeper is silent or has tripped, when the app's
  word on "armed" (its link log) and the board's differ for 5 s, on an error in the app, on a stick request the radio
  had to cut. A scenario arms only through it: `rig.guard.openWindow(10)` asks the keeper and then puts the radio's
  hands on; `rig.guard.closeWindow()`. Every hardware scenario ends with the keeper's own word that the board is
  locked, disarmed and at rest, or fails.
- **What it measures** (`MspTap`): `keeper\board-*.csv`, a row a second - the stick frames that reached the board,
  the largest and the 99th-percentile gap between two of them at the board, what the keeper itself added, armed,
  locked, volts per cell - with the clock of the app's `link-*.csv` in its first column, so the two join by time;
  and `frames-<scenario>.log`, every frame with the time it came and went.
- **Faults a scenario can ask for** (`FAULT ...`): `cut` (the phone's connection closed and refused: a pulled
  cable), `starve` (its frames taken, none passed on), `stall` (not read), `delay ms`, `garbage phone n`; garbage to
  the board only when the board is the made-up one or Betaflight as a program.
- **Arming** is a word the hardware file has to say: `board.mayArm=true`, for a session with the battery in. Without
  it `BoardChainScenario` - the first run on the whole chain: the phone app told to use the keeper, PILOT UAV, the
  flight controller shown, 20 s of sticks at rest counted at the board - asks for no window, and ends green with the
  battery out: its film has the board's "cannot arm: ..." line and what the keeper read of its failsafe and output
  limit. With it, a board over `board.outputLimitMax` gets no window, and the failed check has the keeper's words.
- **On the phone:** `adb shell setprop debug.draupne.fc tcp:<keeper.port + 1>` is set by the scenario (the one
  property the bench sets; `PhoneGuard`) and left there - gone with the phone's next restart, `... usb` puts USB
  back. The reverse port is the keeper's and is not removed.
- **The first run on the real board** (2026-11-23 08:01, `.\bench.ps1 hw -Tests *BoardChain*`, battery out,
  `board.mayArm` not set): `BoardChainScenario` 1 of 1. The keeper read the board's failsafe - GPS-RESCUE after
  failsafe_delay 200, rxfail holding the throttle and the ARM switch, MSP_FAILSAFE_CONFIG agreeing - and
  motor_output_limit 100 in all four PID profiles. The lock showed in the board's status (flag 16, blocker MSP);
  "cannot arm: NOPREARM+RPMFILTER" (battery out). 20 s of sticks at rest: 188.6 frames a second at the board, the
  largest gap 52.5 ms, the 99th percentile 18.7 ms, the keeper's own share 0.76 ms at most. Ended locked, disarmed, at
  rest. Then `gradlew boardCap -Plimit=15`: 15 in all four profiles, saved, read back. The first arm window waits for
  a session with the battery in.

### The bad-network box on the real phone (`HwLinkMatrixScenario`, R8)

The bad-link table (`link/LinkMatrixScenario`, a made-up drone) between the real, sealed Windows app and the one real
phone, with the sticks counted where they end: at the real board, through the keeper. Built 2026-11-23 and proved on
the made-up pieces only (`gradlew test --tests *HwLinkMatrix*`); not yet run on the phone and the board.

```
.\bench.ps1 hw -Tests *HwLinkMatrix* -Quick     two networks (clean, one bar) and two cuts (1 s and 10 s both ways): about 5 min
.\bench.ps1 hw -Tests *HwLinkMatrix*            the whole table: about 25 min, 30 with the armed part
```

- **The way.** A hardware run as ever (the fence's one way through, the radio's limits, the keeper's guard), with the
  bench's wire between app and phone (`AppRig.Plan.phoneThroughWire`): the made-up Tailscale names no phone, the wire
  announces it on this PC's loopback (VIRTUAL in the PHONE list), and the wire alone speaks to the phone's address. So
  each bad network and each cut comes on top of what the real network does. The phone app is the bench build on the
  phone, told to reach its board through the keeper as `BoardChainScenario` does.
- **Steady cells** (9; the table's networks but doubled packets), the network bad from before PILOT UAV: LINK READY,
  then 40 s of the stick frames at the board (frames a second, the largest gap, the 99th percentile), the picture, the
  app's own link figures from its link log (LINK READY share, ping, telemetry gaps, frames a second), the board's own
  word (RXLOSS, armed) - nothing armed; the network good again, LINK READY.
- **Cut cells** (12: 1, 3, 10 and 60 s, both ways, PC to phone only, phone to PC only), on a good network: the link
  back by itself and in how long, the picture back and in how long, the pilot's sticks at the board again and in how
  long (by their rate: a disarmed board gets the phone's own idle sticks, 20 a second, 300 ms into a cut until the
  phone counts the link lost), the largest stick gap at the board, RXLOSS and FAILSAFE as the board said them, the
  phone's new sessions (F-001: two at most, one more for each 4 s it hears the PC unheard), the phone app's log for a
  jammed socket (F-039: counted and said - what a real phone does is the open question), errors (said) and a crash
  (failed).
- **The armed part**, only with `board.mayArm=true`: after a cut cell's link part, one keeper window of 10 s
  (throttle minimum, PREARM then ARM) and the same cut while armed (12 s at most), the board held to the rules: no
  FAILSAFE for a cut shorter than its failsafe_delay; past the keeper's limit (or failsafe_delay where the failsafe is
  let run) disarmed or FAILSAFE - on the real board the keeper ends the window on RXLOSS first; and no re-arm. The
  scenario's own ARM off comes in the window's last 2 s at the latest. With `board.letFailsafeRun` only cuts up to 3 s
  are armed for. Without `board.mayArm` the film says the armed part is skipped and the rows say "skipped".
- **What it leaves:** `matrix\hw-steady.tsv` and `matrix\hw-cuts.tsv` in the run's folder (the report shows them),
  each cell's phone log and the board's word look by look in `matrix\logs`, a check line in the film for each thing
  held or not.

### The phone's own network switched (`HwNetworkSwitchScenario`)

Real networks on the real phone: the phone's own network changed under the link while the sealed app flies it, the
sticks counted at the real board through the keeper. Built 2026-11-23 and proved on made-up pieces only (`gradlew test
--tests *HwNetworkSwitch* --tests *PhoneRigNetwork*`: the cells, the rows, a made-up phone whose airplane mode takes its
Wi-Fi with it, the guard, `phoneReset` by the note); not yet run on the phone and the board.

```
.\bench.ps1 hw -Tests *HwNetworkSwitch* -Quick     Wi-Fi off (to mobile data), Wi-Fi on (back to Wi-Fi), airplane mode 10 s: about 7 min
.\bench.ps1 hw -Tests *HwNetworkSwitch*            all six cells: about 12 min, 20 with the armed part
```

- **The way.** A hardware run as ever (the fence's one way through, the radio's limits, the keeper's guard) - but no
  bench wire: the app reaches the phone at the hardware file's address itself, as a pilot's PC does over Tailscale, so
  the network under test is the real one. The phone app is the bench build, its board through the keeper as
  `BoardChainScenario` has it. The phone must be on this PC's USB cable: with adb over the network the scenario does
  not begin (a switch would cut adb's own way, and `PhoneGuard` refuses it).
- **The phone as found, and back.** Before anything is switched: airplane mode off, mobile data on, Wi-Fi as
  `phone.wifi` says, and the phone on that network, validated - else NOT BEGUN, nothing switched, and the film says
  what is not so. Before the first switch the scenario notes on the phone what to put back
  (`settings put global draupne_bench_net_was wifi-on`) and leaves `phone-network-switched.txt` in the run's folder.
  After every cell and at the end, whatever happened (a finally), the phone is put back - airplane mode off first,
  then Wi-Fi and mobile data - and read back until it is on its network, validated; the note goes once it is. The
  rig's close switches on once more what it saw switched off. A run killed in the middle: `bench.ps1 hw` ends with
  `gradlew phoneReset` when that file is there, which puts the network back by the note (no note: nothing switched).
  The switch's own orders never wait for the battery watch (a cooling pause in airplane mode would leave the phone
  offline); the battery is looked at between cells instead. A phone whose Wi-Fi is normally off has its Wi-Fi cells
  skipped: the bench switches on no network the phone does not have.
- **The cells**, each from a steady link (LINK READY, the pilot's sticks at the board, the picture, the sound, 5 s
  steady): Wi-Fi off (to mobile data); Wi-Fi on (back to Wi-Fi, from mobile data); mobile data off on Wi-Fi;
  airplane mode 10 s and 60 s; Wi-Fi off and on within 2 s. Each stream is watched until it is back (10 s at least
  after the switch's last order, 60 s at most): the link (LINK READY), the picture (the app's link log), the pilot's
  sticks at the board (by their rate), the phone's sound (its microphone as the app plays it, on the bench's silent
  loudspeaker) - when it went, how long it was away, how long after the last order it was back; the largest stick
  gap, the board's own RXLOSS and FAILSAFE, nothing armed. Which network the phone is on before, half way through a
  long switch and after (`dumpsys connectivity`: Wi-Fi or mobile data, validated, Tailscale's VPN) - the cell's own
  premise checked (Wi-Fi off ends on mobile data) - and Tailscale's path as far as its lines in the phone's log show
  it (direct or relayed, its home relay, the network changes it saw; no address kept). The phone app's new sessions
  (F-001: two at most), its link socket opened anew (F-039) or parked for want of Tailscale (said), errors (said), a
  crash (failed).
- **The armed part**, only with `board.mayArm=true`, as in the bad-network box: per cell the phone back onto the
  cell's first network, one keeper window of 10 s, the same switch while armed (its hold 12 s at most), the board held
  to the same rules, ARM off in the window's last 2 s at the latest. Never with `board.letFailsafeRun`: how long a
  switch keeps the sticks from the board is not known beforehand.
- **What it leaves:** `matrix\hw-netswitch.tsv` (a row a cell, the report shows it), each cell's log in
  `matrix\logs` (the switch's orders on their times, the board's word look by look, Tailscale's lines with the
  addresses left out, the phone app's log), a check line in the film for each thing held or not.

### The picture delay glass to glass (`HwGlassToGlassScenario`)

The real phone films the app's own Latency test (TOOLS > Latency test: its code painted on the screen, read back out
of the phone's picture - the whole way a pilot's eyes have) and every reading is set beside the app's own estimate of
the same delay, the OSD's G2G (`video.GlassToGlass`: the phone's capture to send, the network, this PC, plus the
view's own screen time as the OSD adds it). The sealed app and the real phone with the bench's wire between them, as
in the bad-network box; no flight controller needed, nothing armed. Built 2026-11-23 and proved on the made-up pieces
only (`gradlew test --tests *HwGlassToGlass*`; `benchSelf --tests *HwGlassToGlassSelfTest*`, the same run in the
sealed app against a made-up drone, once with a made-up camera of a known delay and once without); not yet run on the
phone.

```
.\bench.ps1 hw -Tests *HwGlassToGlass* -Quick   the network as it is and one bar, NORMAL, H.265: about 5 min
.\bench.ps1 hw -Tests *HwGlassToGlass*          the whole table, 15 cells: about 25 min
```

- **By hand, before:** the phone on a stand, its back camera facing this PC's main screen 40 to 60 cm away, the whole
  screen in its picture (the left half at least: the code is there); the screen bright, no glare; DRAUPNE Flight
  running on the phone as for any hw run; nothing on top of the screen (no other window, no notification over its
  left half); mouse and keyboard left alone while it runs.
- **The windows** are placed by the scenario: the app's main window maximized on the main screen, the video in it (a
  popped-out video put back, the app's other windows closed), its tooltips off for the run. The Latency test paints
  its code on the left of the video area and the phone's own picture as a small thumbnail at the bottom right (too
  small for the camera to read its copy of the code). The code is read off the painted view and its place said in the
  film. In quiet mode (`BENCH_MODE=quiet`, or a real drone armed on this PC) the windows are off the screen: skipped.
- **The camera check:** the first cell waits 25 s for the first reading of the code. None: the film says so plainly,
  a still of what the camera saw (the app's STILL) goes to `matrix\hw-g2g-camera-check.png`, and the scenario is
  skipped - no failure of the app.
- **Cells** (each one thing changed from the base: the network as it is - the bench adds nothing -, NORMAL, H.265,
  recording on as the app has it, no STANAG, nobody watching): 4G good, one bar, slow 300 ms (the bad-link table's);
  AUTO on one bar and as it is; SHARP and NORMAL in H.264 and H.265 (the hidden setting `videoCodec`, no menu has it);
  ROBUST and SURVIVAL (H.264 by the app's own rule); recording off; STANAG on; a spectator watching (a stand-in on this
  PC's loopback, as a 1.0.71 spectator says HELLO: the app passes the picture on to it); the base again at the end.
  Each: PILOT UAV, the picture, 8 s to settle (AUTO 25 s), then 45 s of readings (`-Quick`: 30 s), STANDBY MODE. The
  pictures, recording and STANAG are set in the menus as a pilot sets them.
- **The readings** are taken one by one from the app's own meter (the screen shows only their median): read only,
  by reflection (`bench/hw/MeterTap`). The app's own figures come from its link log (`g2g_ms`, `g2g_max_ms`, the two
  parts). Not quite the same thing: the camera's figure also holds the screen's own delay and the phone camera's time
  before it stamps a picture.
- **What it leaves:** `matrix\hw-g2g.tsv` (per cell: the codec and size seen, the readings and their median, 90th
  percentile, most and least; the app's own median, 90th percentile and most; the difference; the phone's and the
  network's part; the screen time, frames a second, ping; the stand-in spectator's packets; the app's own test's
  line; the phone's errors), each cell's readings, the app's link-log rows and the phone's log in `matrix\logs`, each
  cell's figures in plain words in the film. A cell whose picture the camera could not read says "no reading" - no
  failure.

### Every Drone setup row on the real board (`HwDroneSetupScenario`)

The app's own Drone setup on the real flight controller, as a pilot opens it - SETUP > Set up a new drone, the drone's
two steps - through the keeper's lease for Drone setup. Battery out, propellers off; no phone needed. Built 2026-11-23
and proved on the made-up board behind a real keeper only (`gradlew test --tests *HwDroneSetupTest*`; `benchSelf
--tests *HwDroneSetupSelfTest*`, the same run in the sealed app); not yet run on the board.

```
.\bench.ps1 hw -Tests *HwDroneSetup*     about five minutes: each CLI visit restarts the board, and the keeper reads it again
```

- **Before anything:** the keeper has the board (taken, disarmed, locked, no window, not tripped), and the board's
  settings as they are - the R1 snapshot: the MSP facts, `diff all`, `dump all` - go into the run's folder
  (`board\<id>\<time>-before-drone-setup`, the copy for a paste by hand beside it). The way back:
  `gradlew boardRestore -Psnapshot=<that folder>` once the keeper has ended.
- **The questions a pilot answers for this drone** are answered as the snapshot has them, so CONFIRM writes what Drone
  setup would write on this drone: GPS Yes with `feature GPS` on (its UART the one with the GPS function), the compass
  Yes unless `mag_hardware` is NONE, the cells as Betaflight forces them. A board that counts its cells itself gets the
  hardware file's `battery.cells`; without one CONFIRM is not pressed (it would force the page's 6S). The rest is the
  page's own default: flight modes on AUX2, ARM on AUX1, PREARM on AUX3, GPS rescue on AUX4 and as the failsafe. On a
  Betaflight older than 2025.12 the lists offer no GPS position hold (their hint says why) and the top is Angle (F-086):
  checked, with the lists' words in the film.
- **Every row's words** go into the film and `drone-setup-rows.txt`: the USB row (port, Betaflight's version, board and
  craft name), RECEIVER, STICK DELAY, BATTERY, GPS and its PORT, COMPASS, FLIGHT MODES, ARM, PREARM, GPS RESCUE,
  SIGNAL LOST, ESCS, what CONFIRM would write, BY HAND's text; on the second step the accelerometer, the motors, the
  orientation and the live view's reading; the motor test window's rows (the board, its layout and props direction,
  the battery, spin directions offered or not), read and closed. The motor protocol, bidirectional DShot, props
  direction and motor order are said from the snapshot.
- **Pressed:** BY HAND, NEXT, BACK, CHECK NOW (the motor test window, to read it) and its Close, CONFIRM, the guide's
  Close - and CALIBRATE only on a drone that reads still and flat within the app's own 3 degrees: Betaflight levels
  its accelerometer where the drone sits and saves it (`acc_calibration`).
- **Never pressed**, and said in the film: the motor test's box, sliders and motors, REORDER MOTORS, PROPS IN / OUT and
  the spin directions (they turn motors or need the battery), READ ESCS and BEACON OFF (F-080's own probes and rules),
  FIND GPS, CALIBRATE COMPASS and the orientation's CHECK IT (the drone turned by hand), USE MEASURED, READ AGAIN and
  DISCONNECT. Checked from the requests themselves (`SetupTap`; all of them in `drone-setup-tap.tsv`): no
  passthrough, no DShot command, no motor order or props write, no EEPROM write or MSP reboot, every motor value STOP.
- **After CONFIRM** Drone setup must rest saying "Betaflight set up - unplug the drone." with every row set, or - with
  the battery out - "Not set: GPS, ..." naming only rows that wait for a GPS that did not answer (said, no failure;
  F-087: done only when every row is set). The guide closed, the board is read again the
  same way; each difference from the snapshot goes into the film and `drone-setup-compare.txt` and must be one Drone
  setup said it made: a command its log says it wrote ("Betaflight CLI: ...") and named in its "Betaflight: ..." line,
  CALIBRATE's accelerometer, the receiver switched over MSP. Anything else is WRONG, and so is a CLI line the board
  was sent that the app's log does not have. On a board Drone setup has set up before with the same choices the page
  shows nothing "Set on Confirm.": CONFIRM writes and saves nothing, and only CALIBRATE's line may differ.
- **The end:** the keeper has the board back (taken, locked, disarmed, no window) and says SAFE; the rig asks it once
  more at its end, and `bench.ps1 hw` ends with fcKeeperEnd and boardSafe as always.
- **Battery out:** the board reads near 0 V; what the page says of the battery, the GPS and the ESCs is in the film,
  and none of it fails the run by itself.

### The phone run (`.\bench.ps1 phone`)

The emulator's phone scenarios against the one real phone - USB debugging on, no root, the model that flies - through
the same `PhoneRig`, which is told which phone by a `PhoneRig.Target`: the emulator's in every run but a hardware
run, this bench's phone in `benchHwPhone`. Built 2026-11-21 against a recorder that stands where adb is (`gradlew test
--tests *PhoneRig*`). First run on the real phone 2026-11-22: it stopped at the phone's PIN lock, and the rig was made
to run behind it (below); the second run passed. Since then: every phone scenario, the crash scenario with no
screen lock and with a PIN, and 8 hours over mobile data ([BENCH-RUNS.md](BENCH-RUNS.md)). The emulator runs
(`emulator`, the night) send what they always sent.

```
.\bench.ps1 phone                                    every phone scenario once, then gradlew phoneReset
.\bench.ps1 phone -Tests *PhoneCrash*                one of them
.\bench.ps1 phone -Tests *PhoneNight* -Hours 8       the app left running for hours
.\bench.ps1 phone -Tests *PhoneNight* -Hours 8 -Sleep both   the same, OVERWATCH and DEEP SLEEP by turns (overwatch: OVERWATCH only)
.\bench.ps1 phone -Tests *PhoneFleet*                the real phone among three made-up drones in FLEET (about 6 min)
gradlew phoneReset [-Pserial=<adb serial>]           by hand, after a run that was killed outright
```

- **FLEET with the real phone** (`PhoneFleetScenario`; in the emulator too: `.\bench.ps1 emulator -Tests *PhoneFleet*`):
  its row by its name, heard, its battery; CONNECTED at PILOT UAV, ASLEEP after the LINK card's DEEP SLEEP (a check
  every minute), CONNECTED again at its next check, STANDBY after STANDBY MODE; the made-up drones' rows as at the
  start, nothing armed. Not FLEET's own mode buttons: their check-ins go around the bench's wire. The wire hands the
  phone's packets to the PC socket that spoke through it last, so a sleeping phone's check-ins often miss the PC and
  its row may say UNKNOWN - counted in the film, judged as asleep.

- **OVERWATCH at night** (`-Sleep overwatch | both`, BENCH_SLEEP; left out: DEEP SLEEP every round, as before): an
  OVERWATCH round has the deep round's proofs and the phone's word that its camera watches; the alerts, clips, a fall to
  DEEP SLEEP by the app's own floor, the battery's heat and the app's memory while it watched go into
  `soak/phone-night.tsv` (it has a mode column) unjudged. Under 25 % battery, or when the PC says NO PICTURE TO WATCH or
  UPDATE THE PHONE APP, the round takes DEEP SLEEP and the film says why.

- **Which phone, which app.** From the hardware file: `phone.adb` (its serial), `phone.ip` and `phone.port` (where
  this PC reaches its link port: adb's cable carries no UDP, so the link goes over Tailscale - since 2.3.0-pc.58 the
  phone app answers there only, the bench build too on a real phone: `phone.ip` is the phone's Tailscale address; the bench's
  wire between as with the emulator), and `phone.package` - left out, the bench build
  (`de.droiddrone.flight.pc.bench`, `flight-app\build.ps1 -Bench`, put on the phone once by hand with `adb install
  -t -r`). These four a run may also say itself, and its word goes before the environment's and that before the
  file's: `gradlew benchHwPhone -Pphone.adb=...`, or `$env:BENCH_PHONE_ADB` (`..._IP`, `..._PORT`, `..._PACKAGE`)
  for `bench.ps1`. The app on the phone is left as it is unless the run names an APK (`-Pphone.apk=`,
  `BENCH_PHONE_APK`). No serial, no run: a phone is never picked by itself.
- **What may be sent to it** is a list (`PhoneGuard`): what is not on it is refused, the scenario stops and its film
  says why. The released app (`de.droiddrone.flight.pc`) is not cleared, stopped, granted, installed over or
  uninstalled unless the run was started with the word for it (`-PreleasedAppMayBeChanged=true`,
  `$env:BENCH_PHONE_RELEASED_APP_MAY_BE_CHANGED = '1'` - never from the file); never: `adb root` / `su`,
  `locksettings`, `install -d`, a file written on the phone; airplane mode and Wi-Fi / mobile data off only while
  adb comes over the cable.
- **The fence.** A scenario with the Windows app puts the fence up with the phone's one pair
  (`AppRig.scenario(..).reach(target.reach())`, the same `Fence.Reach` and the same rule: only in a hardware run's
  JVM). The app itself still speaks to the bench's wire on loopback; it is the wire that speaks to the phone. Nothing
  else of a `hw` run is set up - no Tailscale answer, the radio as in every scenario - and the PHONE list has the
  phone as it has the emulator's, VIRTUAL. `PhoneScenario` starts no Windows app and has no fence up; its wire is
  held to the same rule all the same.
- **The phone's lock screen** (since the first run on the phone, 2026-11-22: it has a PIN, as the phones on the
  drones have, and the app flies, sleeps and wakes behind it). The rig never enters a PIN and never changes the lock;
  nothing it may send could (no text, no key but WAKEUP, BACK and HOME, a finger only on the app's own controls). At
  the start the film says the phone's kind of screen lock, also when it is found unlocked - a phone with nothing to
  enter does not act as a drone's phone (F-083). At each launch the film says which state the phone is in: unlocked;
  a lock screen with nothing to enter, which adb swipes away; or a secure one - and then the run goes on behind it.
  What a scenario would read off the screen is taken the other way there is, and the film says so: the app's own
  log (`PhoneWords` has every line the bench relies on, and `PhoneRigWordsTest` holds them to the app's sources),
  Android's word that the service lives, the bench build's STATS (`pc=connected`). What only the screen could show
  is skipped by name and listed at the scenario's end (ON THE REAL PHONE n CHECK(S) WERE SKIPPED) - never counted as
  passed. When the app puts itself in front of the lock
  screen (woken by a PC, opened again after an error) its screen is read as ever, and what adb sees of that is filed
  under TO LOOK AT. A phone found unlocked is kept awake on its cable for the run (`svc power stayon usb`; its own
  setting is noted on the phone and put back exactly at the end, or by `gradlew phoneReset`) - until the app locks the
  screen itself, which it does at a deep sleep and when it opens again after an error.
- **Instead of root** the bench build's `BenchControlReceiver` forgets the crash guard's count, ends the app's
  process and throws an error on purpose (`BenchControl` has the names; `PhoneRigBenchControlTest` holds them to the
  build's sources). What waits in the phone's sockets cannot be read without root: a round that went wrong files the
  build's own figures instead.
- **Sleep on a cable.** Android does not doze while it charges. The scenarios that put the phone to sleep pretend it
  off the cable and force deep doze (`PhoneRig.onBattery`: `dumpsys battery unplug`, `dumpsys deviceidle
  force-idle`; off with `-Pphone.onBattery=false`). That proves the app's sleep check against doze - not real
  suspend with USB attached, not exact-alarm timing after suspend, not the modem or the network's NAT going idle,
  not the phone maker's own app killers: those need one attended night off the cable. It is taken back when the
  scenario ends, whatever happened, and once more by `gradlew phoneReset`, `bench.ps1 phone`'s last step however
  the run went (beside that it puts the phone's own "stay awake" back where a run left its note, and the phone's
  network where a network-switch run left its note - `bench.ps1 hw` runs it after such a run too - and changes nothing
  else: airplane mode without the bench's note, or a port led back to this PC, are only said).
- **The phone's own good.** Its log is followed into the scenario's film folder from the start
  (`phone-logcat.txt`: the app's lines and Android's word on what ended it). Its battery is read once a minute:
  hotter than 42 degrees the scenario waits until it is 40 (half an hour at most), below 15 % it stops.
- **Which scenarios** (tag `hwphone` beside `emulator`): `PhoneScenario`, `PhoneAndWindowsScenario`,
  `PhoneCameraRaceScenario`, `PhoneCrashScenario`, `PhoneFleetScenario`, `PhoneNightScenario`. `PhoneUpdateScenario` skips itself with the
  bench build: that one is test-only, and Android lets a test-only app be installed by adb alone, not by the app's own
  updater. It runs on the released app's package (`phone.package`) with the word that it may be changed, and only
  with its two test builds in `build\update-test-hw` (the emulator's: `build\update-test`). **Those builds must take
  the bench's own update address:** since 2026-11-22 the phone app takes a remote update only from the project's
  release downloads, and the scenario serves its newer build from this PC over plain http (`http://127.0.0.1:<port>/...`
  through `adb reverse`; in the emulator `http://10.0.2.2:<port>/...`). A normal build refuses it; `flight-app\build.ps1
  -UpdateTest` builds one that takes it, and nothing else besides the release downloads
  ([bench-overlay/README.md](../flight-app/bench-overlay/README.md), "The update test's builds"). The phone keeps the
  newer test build: the released app goes back on by hand before it flies. Not `PhoneSocketScenario`: an artefact of
  the emulator's Wi-Fi, read with root.
- **Before the phone:** `-Pphone.adb=emulator-5584` holds the emulator (running already, the app to fly on it) to a
  real phone's rules - the guard, no root, no console but the forwarded port.

## The installer test (`tools\installer-test.ps1`)

By hand, outside the bench, on a PC with a released DRAUPNE Link installed and nothing flying: a newer installer put
over it as UPDATE puts it, and the pilot's files checked before and after. From a normal PowerShell (Windows asks
once, as for UPDATE); every run but `-Check` writes into a folder of its own under
`%LOCALAPPDATA%\DraupneBench\installer-test` (`-Root`): the copy aside, the hash lists, the state of the PC at each
step, the installer's log, `transcript.txt`, `summary.txt`. Exit code 0 all passed, 1 a check failed, 2 refused
(nothing installed), 3 stopped after the installer ran. `Get-Help tools\installer-test.ps1 -Full` has the rest.

- `-Check [-Installer <setup.exe>]` - read only: what is installed, what would be copied aside and its size, the
  firewall rules, the shortcuts, ProgramData's rights. Writes nothing.
- `-Installer build\release\DRAUPNE-Link-Setup-<version>.exe [-Expect 1.0.69]` - the upgrade: (a) refuses unless
  DRAUPNE Link `-Expect` is installed as released (one entry, jpackage's product code for that version, Program
  Files, its jar); (b) copies aside - robocopy, never a move or a delete - `%APPDATA%\DraupneLink` (settings, logs,
  tiles), `%APPDATA%\DroidDronePC`, `Documents\DRAUPNE Link` (flights, clips, backups), `Videos\DRAUPNE Link`,
  `%TEMP%\DraupneLink-update`, `ProgramData\DraupneLink`, the shortcuts and the program, with a hash list, the copy
  checked against it; (c) closes the app if it runs, by its window - never by force, never while its link log is
  being written; (d) runs the installer with UPDATE's `/passive /norestart` and a log; (e) checks the new version
  and product code, the old one gone, every user file as it was (none may change: the installer writes none), the
  link port's firewall rule narrowed to the PC's networks and Tailscale (`FirewallRulesTest`), the Start menu,
  ProgramData's rights (`PcPrepLogTest`'s rule) and `setup.log` kept with this install's lines; (f) starts the new
  app (`-AppSeconds`, 20; `-NoAppStart`), reads "started" and "Settings:" in its own log, closes it and compares
  again - the settings file, `last-known.txt`, today's app log, the map's caches and the app logs beyond the 14
  newest (`AppLog`, at every start) may change, nothing else; the settings keys compared by name; (g) prints the way
  back.
- `-Back [-From <run folder>] [-BackInstaller <file>]` - the released 1.0.69 from its GitHub release (or the file
  given), checked against its pinned SHA-256 and GitHub's digest, run over what is installed (jpackage's installers
  take a downgrade); then every file missing since the upgrade run's copy copied back, and the settings files that
  differ copied back over the one there (that one kept in the run folder). Copies aside first.
- `-Uninstall` - Windows' uninstall, after a copy aside; it removes the program, the Start menu entry, the
  installer's desktop shortcut and the two firewall rules, and keeps every setting, log, flight and recording.

A test build installed as a version number that is released later blocks that release's install (the same product
code: Windows says another version is installed, and UPDATE offers nothing for the same number): `-Back` first.
`InstallerTestScriptTest` (fast tests) proves what can be proved without installing: the names the app's code gives,
the folder list, the copy aside on folders of its own, the compare, and the refusal - the whole script sealed.

## The video decoding fuzzed (`gradlew decoderFuzz`)

By hand, for hours, beside other work: made-up video through the app's own decoding (the live video's decoder, the
one-thread decoders of the overwatch preview, the OSD recording and STANAG, and the clip player with FFmpeg's demuxers)
in a child JVM at the lowest priority on two cores, so a native crash ends the child, not the run. No hardware, no
network, nothing of the pilot's. Start it at idle priority:

```
gradlew decoderFuzz -Phours=4 -Pseed=1          [-Pcores=2] [-PtimeoutMs=10000] [-PmaxMemMb=2048] [-PseedDir=<recordings, .dfz>]
gradlew decoderFuzz -Preplay=<a finding's input.dfz>       that input once more, in a child
```

Seeds are made each run (libopenh264 H.264 of several kinds; H.265 only from an encoder of the PC's own; MKV by the
app's recorder, MP4 and TS remuxed), then changed at their bytes, NAL units, frame order or file structure. Results in
`%LOCALAPPDATA%\DraupneBench\fuzz\<time>-seed<n>`: `summary.txt` (inputs, a second, crashes, hangs, the child's largest
memory, Java exception kinds), `progress.txt`, `findings\` (the input, the child's last lines, the JVM's crash report,
whether it does it again alone or after the inputs before it), `exceptions\`, `seeds\`. Exit 0: no crash, no hang.
`-PseedsFrom=<run>\seeds -Pseed=<n> -Pfirst= -Plast=` runs a run's inputs again.

A crash is a native fault in FFmpeg that would end the whole app: the answer is an FFmpeg update (the JavaCV presets)
or the decoding moved out of the app's process, as the STANAG encoder was. A Java exception is no crash (the app
catches it). `DecoderFuzzTest` (fast tests) holds the mutator and the watching: a child that ends, hangs, crashes the
JVM and grows on purpose, and sixty real inputs.

## Betaflight as a program

Betaflight's own source built for the PC (SITL) and run in Windows' Linux part (WSL, Ubuntu, as root). Set up on
this PC 2026-11-22. It is the real flight code: the arming rules, the CLI, save, the failsafe - with no sensors but
what a simulator sends it, no battery (it reads 0 V), and motors by PWM only. Nothing of it touches a real board.

```
.\bench.ps1 sitl                         the suite on 4.4.1 (the drone's version): SITL started before it, stopped after
.\bench.ps1 sitl -Betaflight 4.5.5       on another version (4.3.2, 4.5.5, 2025.12.5): what a version cannot show is skipped, with why
tools\sitl\build.ps1 -Version 4.4.1      built from Betaflight's tag (-All: 4.4.1, 4.5.5, 2025.12.5, 4.3.2); a minute each
tools\sitl\start.ps1 -Version 4.4.1 -Feed   one started by hand; returns when it answers MSP through its forwarder, says its folder
tools\sitl\status.ps1                    which runs, where, how often it was started; which are built
tools\sitl\stop.ps1                      ended
gradlew fcKeeper board=tcp:127.0.0.1:5761 battery=none ...   the keeper by hand against it
```

- **Where things are.** In WSL: `/opt/draupne-sitl` - Betaflight's repository (`src`), one checkout a version
  (`tree/<version>`), the program and its `build.txt` (`<version>/`: commit, compiler, make line, the changes made,
  SHA-256). On Windows: a folder a run (`%LOCALAPPDATA%\DraupneBench\sitl\<version>-<time>`, or the bench run's
  `sitl\<version>`) with the board's `eeprom.bin`, `sitl.log` (what Betaflight says, with the time) and `starts.txt`.
  `-Run <folder>` starts it on that folder's board again; `-Eeprom <file>` copies a board's eeprom.bin in first.
- **How it is built.** With the newest Ubuntu compiler that builds each version with Betaflight's own Makefile:
  gcc 14 for 4.3 to 4.5 (gcc 15 refuses them; gcc 11's build of 4.4.1 hangs at start), gcc 15 for 2025.12. With the
  GPS built in (`-DUSE_GPS`, as the drone's firmware has it; SITL leaves it out by itself); 4.5 has one warning
  (an unused function) let through for it. Three small changes to SITL's own glue, never to the flight code: SITL's
  TCP "UART" gets the two functions the GPS calls (they were empty, and a GPS on a UART ended SITL); its answers
  leave within 10 ms instead of up to 0.5 s on 4.3 and 4.4 (as 4.5 has it); 4.3's crash at start taken out (as 4.4
  did).
- **Ports.** Fixed in Betaflight's source: UART n listens on TCP 5760 + n (UART1, MSP, on 5761; the drone's GPS
  UART3 on 5763, where nothing answers - no fix), UDP 9003 takes the simulator's packets. WSL brings TCP to Windows'
  127.0.0.1 by itself. One SITL at a time; it takes one TCP client at a time. The bench's scenarios go through the
  forwarder on 15761 (and 15762, 15764): see below.
- **Save and restart.** `save`, the CLI's `exit` and MSP's reboot end the program (exit code 0); the start script's
  supervisor starts it again in the same folder within a second, eeprom.bin kept - as a board restarts. Five crashes
  in a row at start and it is not started again (`sitl.log` says so). `diff all` and `dump all` read the same twice in
  a visit and across restarts (no brushed-ESC detection in SITL).
- **Arming.** It arms only with the feeder (`-Feed`, `tools\sitl\feeder.py`): forty packets a second of a drone
  level and still. Without them SITL's attitude is never set and ANGLE holds. Set up as the drone is
  (`bench/sitl/Sitl.likeTheDrone`) it also wants its accelerometer calibrated once: `likeTheDrone` sets
  `acc_calibration = 0,0,0,1` (no trim - the feeder's level reads level - and "calibration completed"). Asking for
  the calibration only when the status said NO_ACC_CAL skipped it three times on 2026-11-23: after the set-up's save
  the status did not say so, after the next restart it did. The sticks come over MSP (MSP_SET_RAW_RC on
  UART1, as the phone app sends them): **Betaflight takes one MSP request per run of its serial task**
  (serial_update_rate_hz) - at 100 stick frames a second nothing else was answered, so the scenarios send 25.
  **SITL's gyro loop takes nearly all its time:** at Betaflight's own serial_update_rate_hz 100 its "tasks" showed
  the serial task running 18 to 32 times a second (2026-11-23), stick frames queued, MSP answers 0.1 to 1 s late,
  RXLOSS every two seconds or so - and the suite went 0 of 4. `likeTheDrone` sets 2000, as Drone setup does on the
  drone: about 75 a second, a quarter of the RXLOSS.
  **Its task rates** (CLI `tasks`, task_statistics ON, set up as the drone, 2026-11-23 10:40; desired rate in
  brackets): idle - GYRO 7905 (8000), PID 438, ACC 71 (1000), ATTITUDE 23 (100), RX 8, SERIAL 104 (2000), GPS 23,
  total load 0.2 %; with a window's MSP traffic (sticks 25 a second and MSP_STATUS 10 a second on UART1, 5 a second on
  UART4; disarmed - no CLI opens while armed) - GYRO 8000 (its max load 124 %), SERIAL 434, ACC 182, ATTITUDE 21,
  RX 7, total 0.4 %. So not the CPU: SITL's main loop sleeps 50 microseconds a pass (main.c), and in this WSL a 50
  microsecond sleep takes 121 (median; 162 at the 99th percentile) - about 8000 passes a second, nearly every one
  taken by the 8 kHz gyro task. (Windows' timer resolution asked down to 0.5 ms changed nothing in WSL.)
  **The cause: Linux's timer slack** (50 microseconds by default, a process may sleep that much longer). At 1
  microsecond a 50 microsecond sleep takes 71 (median; 102 at the 99th percentile), and SITL's tasks run near
  their rates: GYRO 8230, PID 496, ACC 902 to 951 (1000), ATTITUDE 97 (100), SERIAL 1325 to 1403 (2000), GPS 96 to
  97 (100), RX 10 (idle) - where the default slack gave ACC 19 to 206, ATTITUDE 12 to 35, SERIAL 22 to 348, GPS 12
  to 30, varying from one start to the next. **tools\sitl\sitl.sh sets 1 microsecond** (DRAUPNE_SITL_SLACK_NS) on
  the supervisor's shell, which every Betaflight it starts - after each restart too - and the feeder inherit;
  `sitl.log` says it at each start, `status.ps1` shows the running one's. Betaflight's builds are as they were.
  **What the slack does not cure: "no connection after a restart"** is WSL's forwarding of Windows' 127.0.0.1, not
  Betaflight: after a restart Betaflight bound UART1 ("bind port 5761") and its log then had no "New connection" for
  a minute, while the keeper on Windows was first refused, then connected and got no answer (1123-1131-sitl,
  11:36). Ending the program (`Sitl.revive`) makes it bind again, and the forwarding takes it up.
  **The forwarder** (`tools\sitl\forwarder.py`, Python's standard library) is the cure: sitl.sh starts it with the
  supervisor and ends it with it, never with Betaflight's restarts; it listens on 15761, 15762 and 15764 (UART1, 2, 4
  plus 10000, DRAUPNE_SITL_FORWARD_OFFSET) and gives each connection from Windows a fresh one to SITL's own port
  inside WSL, both ended together - so WSL's forwarding only ever sees ports that stay. `sitl.log` says its ports,
  `forwarder.log` what it did, `status.ps1` whether it answers. bench.ps1 points the scenarios at it
  (BENCH_SITL_PORT -> bench.sitl.port; `Sitl.uart(n)` for the other UARTs); by hand, without it, they use 5761.
  **The keeper's 10 MSP_STATUS a second in a window do not cause its RXLOSS:** a made-up window (sticks 25 a second,
  armed, throttle 1080, from inside WSL and from Windows) showed no RXLOSS in 38 to 48 s armed with 10 or 25 asks a
  second on the stick port, and one in 38 s with none (a Python test by hand, the rig's eyes on UART4). The keeper
  scenario's film now has the keeper's own figures for every window SITL's RXLOSS ends: in the first (1123-1044-sitl)
  111 frames in 4.4 s passed to the board, 25 a second, the largest gap 54 ms, the keeper holding one 0.14 ms at most -
  the stick frames reached SITL on time; its starved loop did not take them in time.
- **Each scenario starts from a fresh board** (`Sitl.fresh`, 2026-11-23): answering MSP, disarmed (MSP's arming
  block), then the CLI's `defaults` (Betaflight restarts) - one scenario's leftovers (armed, its CLI open, PREARM on
  AUX3) never reach the next. A SITL that bench.ps1 started and that answers no MSP is revived first (also in
  `Sitl.missing`): "exit" for a CLI left open, else `pkill` of its program, which its supervisor starts again. That is SITL's TCP stand-in for USB alone: the real board answered 250 stick frames a
  second with the phone's requests between them in 12 ms (median), none lost (`mspRateProbe`, F-081).
- **What was seen of the drone's failsafe** (4.4.1, `SitlFailsafeScenario`; GPS-RESCUE after failsafe_delay 200,
  rxfail 3 h and 4 h, no GPS fix, arming without a fix allowed): the sticks stop at throttle 1080; RXLOSS 0.15 - 0.17 s
  later; armed and every motor held where it was (1098 - 1100) for 20 s; at 20.05 - 20.08 s GPS rescue, which with no
  fix gives up at once - disarmed 0 - 0.09 s later, the motors at rest, FAILSAFE and ARMSWITCH among the blocks.
  **In that moment the rescue can run every motor up to its hover throttle** (gps_rescue_throttle_hover 1275: the
  motors read 1290) - seen in two runs of five, for no longer than the 20 to 90 ms between two looks. Sticks back with
  ARM off: free again 2.1 s later. The film's `failsafe-timeline.tsv` has every change. When SITL's own receiver gap
  comes as the ARM switch goes up (BADRX), the scenario waits for a second with no arming block and flips the switch
  again, five times at most, and says so (2026-11-23).
- **A new connection is cleaned first** (`Sitl.usb`): SITL sends the next client what it still had to say to the one
  before (answers to requests left in its queue), cut where its buffer ran over - a "diff all" read with that in
  front is not whole. One question is asked and all that comes is thrown away until 150 ms pass with nothing. A long
  answer can come garbled too: "dump all" twice in one CLI visit, 22342 characters, then 32976 (2026-11-23, by
  hand) - `SitlSnapshotRestoreScenario` reads again, three times at most, when the snapshot's or the restore's two
  reads differ.
- **The keeper against it** (`SitlKeeperScenario`): as a real board's in every rule but the battery
  (`noBattery`, `battery=none` by hand - refused for a real board). **Only 4.4 and older can be locked as SITL:**
  Betaflight 4.5 on leaves MSP's arming block out of its SITL build (msp.c, `#ifndef SIMULATOR_BUILD`). On 4.5.5 the
  keeper saw that its lock does not show and opened no window - but sticks that asked for ARM armed the board all the
  same, outside any window, and the keeper could only trip and say NOT SAFE. The scenario checks there only that no
  window opens, and sends no sticks. A window that SITL's own receiver gap ends (the board's RXLOSS, the keeper's
  RECEIVER LOST - as it must) is asked for again, three times at most, and the film says so (2026-11-23).
- **Drone setup on an armed board** (`SitlArmedWritesScenario`, F-077 to F-079): the app itself (SETUP > Set up a
  new drone) on UART1 through a tap that writes down every request and CLI line; the sticks on UART2 and the rig's
  questions on UART4 (both given MSP by the scenario). Armed: PROPS IN / OUT, CALIBRATE and CONFIRM are refused with
  "The drone is armed. Disarm it first.", no '#' and no MSP write reach the board, the yaw direction is read back;
  disarmed: CONFIRM writes, Betaflight's own CLI "status" lists the arming block MSP while the CLI is open, and it is
  gone after the restart. **Sticks and questions on one SITL port do not mix:** the answers came 0.1 to 0.6 s late
  and more, the stick frames with them - RXLOSS, then BADRX when the ARM switch went up; on ports of their own the
  questions take about 10 ms. Even so SITL says RXLOSS for about a tenth of a second every few seconds - also with
  the sticks sent from inside WSL, on time, 25, 50 or 100 a second: its own gaps (2026-11-23). An armed board rides
  them out (failsafe stage 1); one that comes as the ARM switch goes up leaves BADRX, and the rig flips it again.
  Once after a restart SITL could not open UART2 again ("bind port 5762 for UART2 failed!!" in `sitl.log`): a UART
  not back for 10 s gets SITL restarted by MSP's reboot over a port that answers. The scenario ends with the CLI's
  `defaults`: the next scenario finds the board as a fresh run starts it (CONFIRM puts PREARM on AUX3, which no other
  scenario's sticks raise - NOPREARM).
- **Other versions** (`-Betaflight 4.3.2 / 4.5.5 / 2025.12.5`, 2026-11-23). `Sitl.likeTheDrone` asks `get` for each
  setting first and leaves out, and says, what that Betaflight does not have (4.3.2: no
  gps_rescue_allow_arming_without_fix), as Drone setup does. 4.3's SITL ends with a segmentation fault a second after
  it starts with a GPS on a UART (sitl.sh patches that from 4.4 on) and has no GPS rescue: there no GPS, failsafe
  DROP. 2025.12 says its version as 25.12.5 over MSP: `Sitl.version` names it 2025.12.5. A scenario a version cannot
  show is skipped with why, never failed: Drone setup on an armed board on all but 4.4 (no MSP arming lock in SITL
  from 4.5 on), the failsafe on a SITL with no GPS RESCUE; the keeper on 4.5 on checks only that it opens no window.
  2025.12.5 "did not answer MSP on 127.0.0.1:5761 within 30 s" (22 and 23 Nov) looks like WSL's forwarding again: no
  connection reached it in those 30 s (no "New connection" in its sitl.log); started by hand it answered at once,
  inside WSL and from Windows. start.ps1 now waits for Betaflight through its forwarder, there before Betaflight is.

## For whoever adds a scenario

- Classes live under `src/test/java/de/droiddrone/bench/**`, are named `...Scenario` and carry a tag: `ui`,
  `badlink`, `edge`, `soak`, `emulator`, `sitl`, `finding` - or `hw`, `hwphone` for real hardware. One JVM per class.
- A SITL scenario (`bench/sitl`): `Assumptions.assumeTrue(Sitl.missing() == null, ...)` first; `Sitl.open(log)` is
  the board as BetaflightUsb has a USB one (BoardSnapshot and BoardRestore take `Sitl.BOARD`), `FcKeeper.tcp(...)`
  the keeper's way, `SitlLink` sticks and questions at once; `Sitl.likeTheDrone(limit, log)` sets it up as the drone.
- A hardware scenario: `Assumptions.assumeTrue(Hardware.present(), ...)` first, then
  `AppRig.scenario("name").hardware(Hardware.load()).run(rig -> { ... })`; `rig.hardware` has the bench's facts.
  `rig.radio.handsOff()` is the hard stop (ARM off, throttle down, held); a made-up drone's scenario lets go of the
  sticks with `rig.radio.sticksAtRest()` (the switches stay), which is what `handsOff()` meant until 2026-11-21.
  Nothing is armed except between `rig.guard.openWindow(seconds)` and `rig.guard.closeWindow()`, and only when
  `rig.hardware.mayArm()`; `rig.guard.keeper()` is the keeper's control channel (FAULT, MARK, FIGURES, SCENARIO).
- A phone scenario (tags `emulator` and, where it makes sense on a real phone, `hwphone`): `PhoneRig.Target target =
  PhoneRig.Target.ofRun();` first, then `AppRig.scenario("name").drones(0).reach(target.reach()).run(rig -> { ... })`
  with `new PhoneRig(rig.film, target)` and a `Wire` to `phone.linkAddress()`. What a real phone cannot do
  (`phone.can(..)`: root, a made-up GPS or battery) is refused there by name; a command for it that is not on
  `PhoneGuard`'s list goes onto the list first, with its kind.
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
  for 0.8 s and is only for when the pilot asks for it. For F-080's tries: `-Pesc=spinonly` turns each motor with no
  visit to the ESCs before it; `-Pesc=recover` is spin with 4 s of silence on the ESC lines first; `-Porder=4,3,2,1`
  (which ESCs, in that order), `-PfirstWaitMs=3000` (nothing sent to an ESC until then), `-Ptries`, `-PpauseMs`,
  `-Ppasses`. Every connect prints its time since the passthrough began. Drone setup's own read is unchanged.
- How fast a real Betaflight board takes MSP from its USB, the PC sending what the phone sends: `gradlew mspRateProbe
  [-Pport=COM9] [-Prates=250,100,50] [-Pseconds=10] [-Prequests=42] [-Pmix=phone|attitude]` - stick frames at each
  rate (0: none, a baseline) with the phone's requests between them, every answer timed (sent, answered, lost, delay
  median / 90 % / max). Arming blocked throughout, idle sticks only; at the end STOP to the motors and the arming block
  as it was. Enter stops it early; Ctrl+C may end it hard (the block then stays until the board restarts).
- The app's doors for the stand-ins (the app never opens them itself): `Outside` (web, programs, browser,
  Explorer), `Joystick.use`, `BetaflightUsb.use`, the system properties `draupne.appData` and
  `draupne.bench.madeUpSaves`.
