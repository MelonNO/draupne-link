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
| 1 | ArduPilot base (A) | A SITL drone arms on overrides and comes home after a 30 s cut; F405 bench test | built; passes in ArduPilot SITL 4.7.3 on the PC; F405 bench test not done |
| 2 | Terrain routes (B) | A ridge route never goes below the set height in SITL; 100 items upload in under 5 s | built; 100 and 300 items up and read back in SITL; RTL over a hill in SITL climbs first |
| 3 | Fleet dispatch (C) | 10 SITL drones, none within 10 m on one height layer | built; 10 SITL drones dispatched at once, each holding at its slot, never inside 10 m sideways and 10 m in height |
| 4 | Follow the leader (E) | A circling lead moves no follower; a 100 m sprint is followed; followers hold the floor; new lead's sticks within 500 ms; two real drones | built; 90 / 90 random runs keep separation; hand-over about 290 ms on loopback; the flow on 5 SITL drones; two real drones not done |
| 5 | Safe autolaunch (F) | A marked sleeping drone joins within its predicted time; an unmarked one never arms; every crash case aborts, the gusty climb does not | built; a reserve called on the radio, launched and joined in SITL; the AL-19 cases as unit tests |
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

## Where stage 1 stands (2026-11-24)

**Stage 1 is built in full** (stage 2, GNSS-denied, is not started). Every PRD stage-1 item has code and tests. Four
test rounds pressed the real windows, menus and radio switches against made-up boards, virtual phones and real
ArduPilot Copter 4.7.3 (SITL, on this PC), and fixed about 100 bugs, each with a test. **Nothing has flown, nothing
has run on a real phone or board.**

How it is proven:
- `gradlew test`: the whole suite, about 2760 tests.
- `gradlew fleetSitl`: 16 tests on real ArduPilot SITL, up to 10 drones at once - setup written, link-loss rule,
  100-item and 300-item routes in parts, dispatch, follow group, DISCARD LEAD and CALL RESERVE on the radio's
  switches, a follower cut, the PC gone, everyone landed at its own home or rally point; separation measured all along.
- `gradlew nightSeparation` (`AIR_SEEDS`, default 30): random 10-minute runs of overlapping failures, and the same
  ending with the PC gone for good - 90 of 90 each keep separation (never inside 10 m sideways and 10 m in height).

**Open:**
1. **Hardware:** the PRD's bench tests (F405), two real drones, a real phone. Not tried on hardware at all: the DFU
   write (Betaflight to ArduPilot), a log download over the real link, the Mission Planner bridge, AUTOTUNE, a real
   power-line answer.
2. **By design, not a plan's:** a lead or a cut-off drone passing over a follower still holding in its cell before the
   new exit plans' start (the PC gone) - while the PC lives the group is kept clear of those paths.
3. **Smaller:** an observation PC shows only what it knows itself (spectators get the pilot PC's group); a hand-over
   gives sticks before the new phone's version is known (old frame layout for a moment) and knows the PC by its address
   (fine on Tailscale, not behind a shared NAT); the radio's mode switch at the hand-over moment is untried.

### The SITL lab: trying and flying it by hand

`.\sitl-fleet.ps1` starts a whole fleet on real ArduPilot Copter (SITL) on this PC; then start DRAUPNE Link as usual -
everything shows as VIRTUAL on the map, in the PHONE list and in FLEET. Ctrl+C ends it all.
- **Phone drones**: the real phone app (its bench build - never released) in an Android emulator, its flight
  controller a SITL drone. Pick one in the PHONE list, PILOT UAV, and fly it with the radio: AUX1 arms, AUX2 the
  modes, camera picture and OSD as on a drone. What needs a flown drone is meant for these: FOLLOW GROUP (the flown
  one leads), TAKE CONTROL, CALL RESERVE, DISCARD LEAD - for that one make the followers phone drones: the new lead is
  the bottom row's emptiest battery, and a fleet-only drone has no pilot session to hand over to. Tried on phone
  drones so far (SitlLabFlightTest): flying on the sticks and DISPATCH; the rest by hand next (SitlFlowTest has them
  with the phone's fleet role on the PC).
- **Fleet-only drones**: the phone's fleet role on the PC, no pilot session - followers, reserves, DISPATCH.
- Every SITL drone is set up as Drone setup sets up a DRAUPNE drone (its plan written, then restarted). Its battery monitor is
  on (BATT_MONITOR 4), as on a real board: SITL has it off, and a battery ArduPilot doesn't know fails the launch check.
- Mission Planner can watch any drone on the TCP port the script prints for it.
- The phone apps' logs are kept in `%LOCALAPPDATA%\DraupneBench\sitl-lab` when the lab ends.

**This PC's settings** live in `%LOCALAPPDATA%\DraupneBench\sitl-lab.settings` (made from the repo's
`sitl-lab.settings` the first time; `.\sitl-fleet.ps1 -Edit` opens it): `phones` (auto: as many as the free memory
and cores allow - 2 on this 28 GB laptop), `fleetOnly`, `emulatorMemoryMb`, `emulatorCores`, `emulatorWindows`, the
place. **On a stronger PC** raise them there - or for one run: `.\sitl-fleet.ps1 -Phones 6 -Drones 10 -Memory 3072
-Cores 4 -Windows`. `-Phones 0` starts no emulators (quick). The phone app's bench build is made again by itself when
its code changed (`-Build` forces it); the lab's own Android device (`draupne-lab`, beside the bench's) is prepared
again for each new build.
- Not together with the virtual fleet (Virtual fleet.bat): they share one file.
- The ground's height at the place is looked up, so routes over the terrain fit SITL's flat ground.
- `gradlew fleetSitl` includes `SitlLabFlightTest`: the PC's link to a phone drone, armed on AUX1, climbed in ALT
  HOLD on the throttle, held, landed and disarmed - through the relay, the emulator, the phone app and ArduPilot. And two phone
  drones DISPATCHed by their own phone apps (GUIDED, arm, the climb, the route uploaded, AUTO), each holding at its slot.

**Done since 2026-11-23, in short:** the SITL lab found two real bugs: the phone app never read an ArduPilot drone's stick map
or asked for its telemetry (it waited for an OSD request DRAUPNE Link never sends - no sticks reached ArduPilot), and a
dispatched drone its phone did not launch stayed LAUNCHING with nobody told; a dispatched drone leaves its column only at its own layer (the 10-drone gate
found one starting its route at the 40 m floor and climbing through the layers below it); separation 90 / 90 (and the PC-gone class); hand-over in about 290 ms; TRAFFIC to
every drone's ArduPilot; stage 1 GNSS loss (AC-12 / AC-20 / AC-42); return heights over terrain (RT-9) and apart
(FD-6); ArduPilot 4.7's renamed parameters; route blocks in parts (the phone reads 1100 bytes); stale datagrams
dropped (sequence numbers); the lead's way home in the exit plans (AC-41); the group's base over rising ground (AC-3).

**History (2026-11-23):**
1. **Separation** was 85 of 90 random runs; now 90 of 90 (above).
2. **Real ArduPilot:** now in SITL on this PC (below).
3. **Hand-over speed (NFR, FL-6): done on loopback, not tried on drones.** It was a new pilot session that waited for
   ArduPilot's armed word twice (its heartbeat, once a second): 1.3-2.1 s, and the window never handed over at all (it
   asked for END LINK first). Now the group's fresh report stands for that word (`RcSession.handedOver`), the link
   to the new lead sends sticks once the phone's session is up (`ControlLink.handOver`), and the phone lets in its
   commander's session on a follower it just told to lead, armed or not (`FleetCommandGate.leadConnect`) - nobody
   else. `LeadHandoverLoopbackTest`: 286-291 ms at a 120 ms round trip. Open: what the radio's mode switch does to the
   new lead at that moment is untried (AC-32's centred pickup is not built); the video waits for its key frame as before.
4. **ADS-B to the lead (FD-8, AC-11):** built since (TRAFFIC, `FleetCmd` 0x3F).
5. **Built 2026-11-23 (late):** AC-10 - every drone the PC sends out (DISPATCH, CALL RESERVE, a route's GO) gets its own
   rally point first, read back on the FC (`RallyPlan`, `FleetOps`; on real ArduPilot in `SitlDispatchTest` and
   `SitlRallyTest`). The follower's own rally upload (exit plans) never read back on real ArduPilot (frame 6 / 3):
   fixed. FL-14 - the floor's - / + and a typed value on the group's map label. FL-9 - GROUP DESIGN, with the size
   (AC-35) and layer (AC-13) checks. Rally points of a group's followers come from the group's exit plans; those of
   drones sent out on their own from `RallyPlan`: the two are not yet checked against each other.
6. **Return heights (RT-9, FD-6, A5), 2026-11-23:** every drone the PC sends out on its own (DISPATCH, CALL RESERVE,
   the route window's GO) gets an RTL_ALT over the highest ground on its straight ways home and to its rally point,
   clear by its own route height (dispatch: its layer; a reserve: its approach height). `ReturnHeights` runs
   `SoloReturns` over all of them at each one that goes out or comes back: ways home within 20 m go a layer apart,
   never under a drone's own terrain height. Dispatched drones and reserves get it by `FleetCmd` RETURN; the launch
   order waits until the drone's report says ArduPilot has it (said after 10 s; an older phone never confirms and
   stays on the ground). The pilot's own drone: through the tunnel after the route, read back before GO; a later
   change goes by RETURN. A reserve that joins the group leaves the set (its exit plan sets RTL_ALT). Drone setup
   writes FS_EKF_ACTION 2: GNSS lost, ArduPilot holds its height (ALT_HOLD) instead of landing (A5).
   - **Not as asked:** RTL_ALT is above ArduPilot's home, the terrain heights above sea level: a home height off the
     map (RT-5) shifts the clearance by as much. A drone's "way home" for FD-6 is the straight line from the far end of
     its way out (a dispatch slot, the group, the route's farthest point) to its rally point. The dispatch mission's own
     return leg (`returnSeq`) is never entered by the PC; it flies in the drone's layer, the same order as the RTL
     heights within one dispatch, not re-planned with later solo drones.
   - **Found in SITL 4.7.3:** Copter 4.7 moved RTL_ALT (cm) to RTL_ALT_M (m); a PARAM_SET of RTL_ALT is not answered.
     The phone now sets both (followers, leads and solo drones alike) and reads back the one there is.
   - **Parameter names audit (Copter 4.7.3 SITL, `SitlParamNamesTest`, instance 12):** renamed in 4.7 (4.6 name -> 4.7
     name): SYSID_THISMAV -> MAV_SYSID, SYSID_MYGCS -> MAV_GCS_SYSID, RTL_ALT (cm) -> RTL_ALT_M (m), WPNAV_SPEED /
     _UP / _DN (cm/s) -> WP_SPD / _UP / _DN (m/s), ATC_ACCEL_R / P / Y_MAX (cdeg/s/s) -> ATC_ACC_R / P / Y_MAX (deg/s/s).
     Every other name DRAUPNE writes or reads (failsafes, battery, AVD_* / ADSB_TYPE, fence, rally, RC maps and options,
     flight modes, serial ports, frame, filters, rates, motors, the phone's OSD reads) is there with the same units in
     4.6.3 and 4.7.3 (checked in both sources). `ArduNames` plans each renamed one under both names: the board has one,
     the other is listed "left out". ArduPilot hides AVD_* while AVD_ENABLE is 0 and BATT_* while BATT_MONITOR is 0:
     planned against a fresh board, the avoidance's distances and action looked missing and were never written. Drone
     setup now writes AVD_ENABLE first and looks for them again (it stops if they don't show); anything nothing on the
     board stands for is listed first as NOT SET. The phone waited for OSD parameters a board without an OSD never has
     (any version): with them it never asked for attitude, battery, GPS or position. It now gives up on the OSD after
     10 s.
   - **Fixed since:** the phone reads at most 1100 bytes a datagram (`UdpCommon.packetLength`), so a route of more
     than about 33 items never arrived; blocks now go in parts of at most 30 items (PROTOCOL.md).
   - Tests: `ReturnHeightsTest`, `DispatchTest`, `RouteWindowTest`, `ReturnHeightsLoopbackTest`; real ArduPilot
     `SitlReturnTest` (instances 10, 11).

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
