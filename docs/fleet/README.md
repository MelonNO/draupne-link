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
| 1 | ArduPilot base (A) | A SITL drone arms on overrides and comes home after a 30 s cut; F405 bench test | under way: step 1 of 6 done |
| 2 | Terrain routes (B) | A ridge route never goes below the set height in SITL; 100 items upload in under 5 s | |
| 3 | Fleet dispatch (C) | 10 SITL drones, none within 10 m on one height layer | |
| 4 | Follow the leader (E) | A circling lead moves no follower; a 100 m sprint is followed; followers hold the floor; new lead's sticks within 500 ms; two real drones | |
| 5 | Safe autolaunch (F) | A marked sleeping drone joins within its predicted time; an unmarked one never arms; every crash case aborts, the gusty climb does not | |
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
2. **AP-3:** armed state, ArduPilot flight-mode names and PreArm texts in RC CHECK.
3. **AP-4 / AP-6:** sticks as `RC_CHANNELS_OVERRIDE` through the same stick path and guards as MSP; OSD buttons
   LOITER, RTL, LAND, START/PAUSE.
4. **AP-5:** link loss: the phone brakes into LOITER, home after 20 s (Smart RTL alone, plain RTL as a lead); the
   PC heartbeat relayed for ArduPilot's GCS failsafe as the backstop.
5. **AP-8:** the bench's virtual drones backed by ArduPilot SITL (needs SITL on Windows: WSL or Cygwin build).
6. **AP-7:** Drone setup over MAVLink parameters (backup, plan, CONFIRM, read-back).

Nothing here has flown. Every step is tried in SITL before the maintainer's hardware.
