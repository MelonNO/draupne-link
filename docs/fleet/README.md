# The `fleet` branch

Work on [DRAUPNE Fleet & ArduPilot](PRD.md): ArduPilot as a second flight-controller type, then fleet management
(routes, dispatch, follow the leader, safe autolaunch, airspace coordination), then GNSS-denied flight.

## How this branch sits

- **Branched from `beta`** at 2bf634f (1.0.72, the test week, unreleased) so it builds on the week's safety work
  (`RcPath`, `SessionGate`, `ArmedCheck`, `FcUnread`). `beta` itself is not touched from here.
- `beta` is merged into `fleet` now and then; `fleet` goes to `beta` (and from there to `main`) only phase by phase,
  each phase after its gate has passed and only on the maintainer's word.
- The repo's rules hold here too: latency first, no personal data (privacy scan before every commit), no AI
  co-author lines, nothing published without the maintainer's go-ahead.
- Phone test builds from this branch get their own number with `fleet` in the name and are sideloaded only.
- Open decisions are built with the recommended choice (PRD, "Open decisions") until the maintainer decides.

## Phases (PRD, "Stage 1 phasing" and "Stage 2 phasing")

| # | Phase | Gate | State |
|---|---|---|---|
| 1 | ArduPilot base (A) | A SITL drone arms on overrides and comes home after a 30 s cut; F405 bench test | built; gates 1 and 2 pass in ArduPilot SITL on the PC; F405 bench test not done |
| 2 | Terrain routes (B) | A ridge route never goes below the set height in SITL; 100 items upload in under 5 s | built; the ridge gate passes on made-up terrain |
| 3 | Fleet dispatch (C) | 10 SITL drones, none within 10 m on one height layer | built; 4 virtual drones apart on loopback, 3 SITL drones dispatched and held apart |
| 4 | Follow the leader (E) | A circling lead moves no follower; a 100 m sprint is followed; followers hold the floor; new lead's sticks within 500 ms; two real drones | built; random overlapping failures still lose separation (open); hand-over is a new session (1-2 s) |
| 5 | Safe autolaunch (F) | A marked sleeping drone joins within its predicted time; an unmarked one never arms; every crash case aborts, the gusty climb does not | built; a reserve called, launched and joined on loopback and in SITL; the AL-19 cases as unit tests |
| 6-9 | GNSS-denied (stage 2) | See the PRD | after stage 1 |

Phases 2-4 need only phase 1; phase 5 needs phase 4. Each phase brings its own GUI, setup-guide, tour and docs
parts and passes the separation suite (AC-14) for what it adds.

## Phase 1: where the code starts

- **The phone already speaks MAVLink to the flight controller** (upstream DroidDrone's `Mavlink.java`, copied whole in
  `flight-app/overlay`): heartbeat, attitude, battery, sys status, status texts, GPS, position, home, RC channels,
  VFR HUD, parameters, and `RC_CHANNELS_OVERRIDE` for sticks. It forwards each to the PC as a `DD_MAVLINK_*` record
  (`FcCommon`, 0x4008-0x4014).
- **The PC reads only `DD_MAVLINK_ATTITUDE`** today (`FcTelemetry`). Everything else from an ArduPilot drone is skipped.
- **DRAUPNE Link always asks the phone for MSP** in its Config (the phone takes the protocol from the Config; the
  serial path and the MAVLink bridge it keeps as its own, `PhoneOwnConfig`). The FC type therefore has to be known
  per drone before the Config is sent.

First steps of phase 1, in order:

1. **AP-1 / AP-2 (done, PC only, not tried on a drone):** `FcTelemetry` reads all thirteen records the phone makes
   from ArduPilot's MAVLink (mode and armed, battery, GPS, position, home, status texts, timers, RSSI, throttle;
   the OSD config, VTX power and barometer records are read past) into the objects the HUD, map, FLEET and STANAG
   already use. Before, the PC read the attitude only and the first other MAVLink record ended the packet.
   - Armed: ArduPilot's heartbeat flag is its own word (`armedKnown`); armed still needs two reports in a row. No status
     until the phone has read the FC's parameters and sends its first mode record: until then the guards ask.
   - Flight mode: `ArduModes` (Copter's numbers and names); `Status.fcMode`, shown on the OSD and in recordings.
   - Distance and direction home are counted on the PC from the filtered position and ArduPilot's home (the phone
     counts them only while armed). Cell count: `CellCount` from the voltage, not the phone's mV / 4.1 V guess.
   - FC type: `LastKnown` keeps each drone's type from its last connection (a check-in carries none); FLEET's detail
     strip writes it beside the name (ARDU / BTFL). The LINK card already said "ArduPilot 4.6.0". The window title is
     left alone (the bench finds windows by it).
   - **Not as the PRD says:** "an unknown record is skipped by its length" needs a length on every record - a change
     of the phone's telemetry format for both apps. Not done: every record the phone can send is now known, which
     covers it until a new record is added. Decide when one is.
   - Tests: `FcTelemetryMavlinkTest` (records made as `Mavlink.java` makes them, through the phone's
     `TelemetryRecords`), `FleetFcTypeTest`.
2. **AP-1 on the phone (done, not tried on a drone):** the PC asks for the protocol by AUTO. The phone takes MAVLink for
   a board that calls itself ArduPilot / PX4 on USB, and now also for one that sends an autopilot's MAVLink heartbeat
   while MSP gets no answer (`MavlinkSniff`: many ArduPilot boards carry their maker's USB name). No port restart;
   Betaflight answers MSP as before. The phone addresses the FC by the system id in its heartbeat (upstream always
   sent to 1; a fleet has one id per drone). Every heartbeat is the FC's armed word for the phone's guards.
3. **AP-3 (done):** ArduPilot's "PreArm:" / "Arm:" texts of the last 40 s are RC CHECK's blockers while disarmed.
4. **AP-6 / G2 (done):** `FcCommand` (0x30, the pilot's session only): a mode, mission START / PAUSE / CONTINUE;
   the FC's COMMAND_ACK comes back as record 0x40E0. On the video's edge, for an ArduPilot drone only: LOI, RTL, LND,
   GO (GO pauses a running route, goes on after a pause, else starts it); the mode it is in is lit, a refusal red.
5. **AP-5 (done on the phone, not tried):** `ArduLinkLossRule` + `ArduLinkLoss`: the PC silent 1 s - LOITER (ALT_HOLD
   without GNSS); 20 s - Smart RTL alone, RTL as a lead; without GNSS it holds and waits; nothing while ArduPilot is
   already coming home or landing. **Not as the PRD says:** the brake waits 1 s, not 100 ms (4G gaps of 100-500 ms
   are routine). While it runs the phone feeds the pilot's last frame with the sticks centred (switches unmoved).
   The phone heartbeats the FC as its ground station while the PC is heard or the rule runs, so ArduPilot's GCS
   failsafe (5 s) fires only when the app itself has stopped.
6. **AP-7 / area H (done as a first version, not tried on a real board):** `setup/ardu` - `MavSession` (MAVLink as a
   ground station over any wire), `ArduUsb` (a board on USB is taken only when it speaks first: a Betaflight board
   is never sent a byte), `ArduPlan` (the fleet's parameters with reasons: no radio receiver, overrides held by the
   phone, GCS failsafe RTL, battery warnings only and LAND at 3 %, TERRAIN_ENABLE 0, crash check, avoidance of the
   other drones, ARM on AUX1 and the mode switch on AUX2), `ArduSetupWriter` (backup .param first, write, read back
   each, stop at the first mismatch; RESTORE), `ArduTools` (six-position accelerometer calibration with the 3D
   drone, level, compass, motor test capped at 15 % / 3 s, reboot), `ArduBenchCheck` (AS-10: arms on the overrides,
   disarms on the switch). SETUP > ArduPilot drone setup.
   - **H1 / H4 (2026-11-23, not tried on a real board):** FIND's "To ArduPilot (DFU)": the board the pilot picks,
     disarmed (MSP), the CLI's `bl`, exactly one new STM32 DFU device (others in DFU are never opened), erase all,
     write ArduPilot's `_with_bl.hex`, read back, restart (`ArduDfuFlow`, `StmDfu`, `WinUsbDfu` by JNA). Needs
     Windows' WinUSB driver on the DFU device (Betaflight's driver tool or Zadig); the app doesn't install it.
   - **AS-6 PORTS / AS-7 FRAME AND TUNING (2026-11-23):** USB as MAVLink, the GPS's and the radio's SERIALn (a port
     doing something else only with REPLACE); motor order, 5"/7"/10" starting points or "keep its tune", AUTOTUNE
     on a mode switch position. Each a plan and CONFIRM through `ArduSetupWriter`.
7. **The tunnel (done, not tried):** `MavTunnel` (0x32): whole MAVLink frames PC <-> FC through the phone in the
   pilot's session - parameters, missions, guided targets, ADS-B traffic and a short list of commands; never sticks,
   arming, calibration, motor test or reboot. The FC's answers go back only while the PC uses it. `TunnelWire` lets
   `MavSession` run over it: routes, RTL_ALT and rally points in the air with the bench's code.
8. **AP-8:** the virtual fleet's ArduPilot drone (a kinematic copter speaking MAVLink) - with the airspace work. Real
   SITL needs Windows' Linux part (not installed on this PC).

Nothing here has flown. Every step is tried in SITL before the maintainer's hardware.

## Where stage 1 stands (2026-11-23)

Built on this branch, each piece with tests; nothing flown, no ArduPilot SITL on the PC it was built on (Windows'
Linux part isn't installed), so every gate ran against virtual phones and a kinematic model of the drones.

**Open, in the order they matter:**
1. **Separation under overlapping failures (AC-14 random runs):** with single followers giving way (Orca) 85 of 90 random
   10-minute runs keep separation (was about 40 %); five seeds still lose it (closest 6.1 m at one height). The nightly
   run names them (tags `night` + `finding`). The loopback cut-off case on the real wire now passes. **Don't fly a
   group before all of them pass.**
2. **Real ArduPilot:** every gate again in SITL (needs WSL), then the PRD's bench tests (F405, two real drones).
3. **Hand-over speed (NFR, FL-6): done on loopback, not tried on drones.** It was a new pilot session that waited for
   ArduPilot's armed word twice (its heartbeat, once a second): 1.3-2.1 s, and the window never handed over at all (it
   asked for END LINK first). Now the group's fresh report stands for that word (`RcSession.handedOver`), the link
   to the new lead sends sticks once the phone's session is up (`ControlLink.handOver`), and the phone lets in its
   commander's session on a follower it just told to lead, armed or not (`FleetCommandGate.leadConnect`) - nobody
   else. `LeadHandoverLoopbackTest`: 286-291 ms at a 120 ms round trip. Open: what the radio's mode switch does to the
   new lead at that moment is untried (AC-32's centred pickup is not built); the video waits for its key frame as before.
4. **Not built yet:** ADS-B of the other drones to the lead's ArduPilot (FD-8, AC-11: only the virtual airspace has
   it), the rally points sent before take-off (AC-10: the upload exists in `RouteSender`, nothing sends it yet, so RTL
   goes to each drone's own home), the group's floor - / + on its map label (FL-14: it is in the right-click menu),
   the group designer (FL-9). The F405 bench test and two real drones (PRD gates) wait for hardware.

**The pilot GUI (area G), 2026-11-23:**
- GU-1 - GU-7 built (the merges of `fleet-osd`, `fleet-ardx`, `fleet-tour`): words and FC type, right-click fleet items,
  the map's group, routes and dispatch, the mode buttons, the OSD elements, alerts, the radio's fleet switches.
- **GU-4:** the radio's mode switch is the first way; the OSD buttons (LOI, RTL, LND, GO) the backup. ArduPilot drone
  setup now puts ARM and the mode switch on the AUX channels this PC's Drone setup uses (were always AUX1 / AUX2),
  and the switch's default modes make a 3-position switch STABILIZE / ALT HOLD / LOITER (its top was LAND). A route
  paused with GO and then moved by the switch starts anew at the next GO.
- **GU-8:** a spectator mirrors the pilot PC's group, the fleet's OSD figures (reserves, group, TRAFFIC, HOME NOW,
  LEAD DOWN; the launch views' words, not their pictures) and the route, besides the drones' states it had. A
  spectator, a watcher of a drone and an observation PC get no fleet controls: no right-click fleet items, DISPATCH,
  CALL RESERVE, DISCARD LEAD, TAKE CONTROL, VIEW, mode buttons, UPLOAD / GO / FENCE (`FleetOps.Host.watchOnly`,
  `FleetWatchOnlyTest`). Not done: an observation PC shows only what it knows itself - the pilot PC's group reaches
  spectators, not observers.
- **GU-9:** the OSD's fleet figures are worked out in the window's refresh, 4 a second, and the paint only reads them
  (they were worked out inside the video's paint - and with the OSD's PINS off they were not shown at all). The launch
  views' feeds open and close on a thread of their own (they started decoders in the paint). The group's map picture,
  member states and next approach height are a view made at each tick: the Swing thread never waits for a tick (and
  no longer walks the tick thread's own maps). Nothing new runs on the stick or video threads: the radio's fleet
  switches are a few compares per stick frame, their action goes to the Swing thread.
- **AC-14 (real flights):** while this PC flies a real drone, armed, the nearest other DRAUPNE drone in the air - those
  the fleet tracks with their height, others the network says are armed by their place - goes into the flight's record
  once a second; PREVIOUS FLIGHTS shows CLOSEST (who and how, in its tooltip). NONE: watched, none in the air.
5. **Area F, 2026-11-23:** AL-11 (SAFE TO AUTOLAUNCH / CALL RESERVE greyed out without a 3D fix) and AL-10 (a
   reserve on its way joins whoever is the lead now; group lost: a new group round the flown drone) built, loopback
   tested (`ReserveLeadNowLoopbackTest`).

## Real ArduPilot on this PC (SITL)

ArduPilot's own Windows build of Copter's SITL (the one Mission Planner's simulation downloads from
firmware.ardupilot.org/Tools/MissionPlanner/sitl/CopterStable: `ArduCopter.elf` and its Cygwin DLLs) goes in
`%LOCALAPPDATA%\DroidDroneBuild\sitl\copter` (or `DRAUPNE_ARDU_SITL`). No Windows Linux part needed. Then
`gradlew fleetSitl` runs the gates against it (`src/test/java/de/droiddrone/pc/sitl`): gate 1 - Drone setup's plan
written, armed on the phone's overrides, flown away, the PC cut, LOITER, Smart RTL after 20 s, landed at home; gate
2's upload - 100 items up and read back under 5 s. Each instance runs in its own folder (`run0`, `run1`), TCP 5760 + 10 n.

## Wire formats and phone rules: test round (2026-11-23)

Every fleet packet's reader fuzzed (random, cut short, over long, lying lengths, NaN / huge numbers), the phone's rules
driven on made-up clocks, the PC's MAVLink session at its edges, and five new SITL tests (`SitlEdgeTest`, instances
6 - 9). What it found is fixed with a test each; the commits on `fleet-test-proto` say what. Most worth knowing:
- real ArduPilot reads a rally point back in frame 3 (sent 6) and NAV_LAND's NaN param4 as 1: every follower's rally
  upload and every route ending in a LAND failed its read-back; and it answers a repeated item with INVALID_SEQUENCE
  and goes on (the upload failed on it)
- the link-loss rule overrode ArduPilot's own battery-failsafe LAND with Smart RTL at 20 s, and a refused Smart RTL
  (no path) left the drone in LOITER for good: now never over ArduPilot's own, and RTL after three refusals (SITL)
- a copy of a LAUNCH order after the launch reported a flying reserve as NOT LAUNCHED; a heartbeat gap in the climb
  forced the motors off
- one upload at a time on the phone (`MissionBlockQueue`): a route and a fence sent together restarted each other
- GO LOOK sent DO_REPOSITION with a NaN height ("the height it has"): ArduPilot DENIES it - GO LOOK never moved a drone;
  the phone sends the height it has now
- a gimbal's or ADS-B receiver's heartbeat routed by ArduPilot was taken as the FC's (DISARMED, and the PC addressed
  its commands to the gimbal)
