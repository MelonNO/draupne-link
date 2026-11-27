# What the test bench found

Bugs and oddities the test bench (docs/TESTING.md) has found, and what became of each. Up to 2026-11-19 06:50 they
were listed only; then the pilot: "start creating fixes for the currently discovered bugs as they are discovered".
A bug that is not fixed yet has a test tagged `finding` that fails until it is (`.\bench.ps1 findings`); a fixed
one's test has moved into the suite it belongs to. Security findings are not here: they go to the private repo's
`security/`.

Found on `beta` at 2c0c151 + the bench's own changes, 2026-11-19 and 20, with made-up drones (the PC's virtual
fleet) and the phone app 2.3.0-pc.54-beta in the Android emulator. From F-059 on: the test week on `beta` (from
1.0.71, 2026-11-21 evening). Nothing here was seen on a real drone.

## Open

**F-080** (Read ESCs can leave motor 1 dead on the real drone): its red warning is built, the pilot asked for a
proper fix, and its cause is being looked for. The test week's other findings (F-059 - F-083) are fixed on `beta`
(below; F-065 is notes, left as they are; F-081 was measured and is no problem). Tried on hardware: F-080 and F-081
on the real drone, F-083 on the real phone; the rest on made-up boards and drones, Betaflight as a program and the
emulator.

## To judge (meant that way, as far as the code says)

None now. What stood here the pilot answered on 2026-11-20 night, and what the answers asked for was built on
2026-11-21 and released in 1.0.71 + phone pc.55: "Judged", next.

## Judged (the pilot's answers of 2026-11-20 night, built 2026-11-21 for 1.0.71)

Each as it was found, and what became of it. The answers in full: "The pilot's answers (2026-11-20 night), built
2026-11-21" and "Fixed before the release", further down.

**F-010's rest - In the smallest window (1093 x 584) PILOT UAV is half hidden and needs a scroll.** The left column's
rule since 1.0.52: the mini-map keeps a least height (70) and the card above it scrolls its rows before the map gets
less. At that size the rows that scroll away are the PILOT UAV button's. One way out: no mini-map at that height,
POP OUT MAP stays. **Answered, built for 1.0.71:** the mini-map is not shown below 140 px; POP OUT MAP stays and the
LINK card's rows get the room.

**F-001 - A link that is dead one way only makes a new session every second.**
The phone hears the PC, the PC hears nothing (6 s, armed): the PC counts the link lost and keeps sending Connect;
each second the phone starts a new session - five for one cut - while the sticks keep arriving and the drone stays
armed. This is the link's own rule, in the code both apps share (`SessionGate`, the link round of 1.0.71 / pc.53):
a Connect that stays unanswered gets a new id after a second, because the answer may have been lost - and the phone
cannot tell a lost answer from a PC that has started over, so a new id is a new session. One way to make it gentler:
keep an unanswered id longer with every try (1, 2, 4 s). Not changed then: it is the heart of the reconnect, reviewed
and tested as it is. **Measured on the real phone app in the emulator (2026-11-19 14:55,
`phone/PhoneAndWindowsScenario`):** in 6 s of hearing the PC and not being heard the phone started 5 new sessions,
as the made-up one does - and they cost it little: its camera was not closed, its video encoder not started anew;
the live sound to the PC went off and on once (with a line "audioStreamRunnable error: codec is released already"
in its log, no crash). LINK READY came 0.3 s after the PC heard the phone again. So this is five lines in a log and
a click in the sound, not a picture lost.
Test: the bad-link table's cell "cut 6 s phone to PC only" (`.\bench.ps1 link`), red until it was judged.
**Read again by a separate reader of the code (2026-11-20), who came to the same:** the ids go out 2.1, 3.1, 4.1,
5.1 and 6.1 s into a 6 s cut - five - and it is the table's "one at most" that disagrees with the rule, not the
rule with itself. What each extra session costs the phone: the stick numbering starts over, the PC's resting
sticks are forgotten, frame times and the extra parity go off until asked for again. A second way to make it
gentler, in the PC alone: give an unanswered Connect a new id only when the phone has been heard since it first
went out (a lost answer), and otherwise every 4 s or so - one session when the link goes, one when it is back.
**Answered, built for 1.0.71:** the second way - a new id after a second only when the phone has been heard since
the id went out, otherwise after 4 s (`SessionGate.Ids.UNHEARD_KEEP_MS`); a 6 s one-way cut makes two sessions at
most, the bad-link table's limit now. What it cost after a cut of 2 - 4 s became F-057, fixed before the release.

**F-002 - On a link with heavy jitter or heavy loss, the ARM switch must be put on several times - and with heavy
jitter the drone may not arm at all.**
The bad-link table, 2026-11-19: with packets up to 0.3 s late and out of order ("jitter 300 ms") the drone armed
at the switch's third try, 13 s after the first; at 50 % loss at the second try, after 6.6 s; every other network
of the table arms at once. Stick frames that arrive after a newer one are dropped, what is left has gaps Betaflight
(as modelled) counts as RX loss, and a switch that is on when the sticks come back is refused until it has been off
(ARMING BLOCKED: ARMSWITCH). Once armed, both stayed armed for the 12 s of the test. The same row on
2026-11-19 10:44: not armed after four tries of 5 s each. How soon it arms there is luck: Betaflight wants half a
second of sticks without a gap of 0.1 s, and the phone passes each stick frame on as it arrives. On the jittery link the
picture meanwhile went up to SHARP (1080p, 8 Mbit/s), because nothing was lost. Safe - but a pilot there sees a
good picture and a drone that will not arm at the first tries.
**Mostly the bench's own doing (2026-11-20, a separate reader of the code):** the table sent its sticks with a
20 ms sleep between frames - on Windows 30 to 50 a second - where the app sends 250. On "jitter 300 ms" two frames
in three are overtaken and dropped; of 50 a second that leaves gaps over 0.1 s several times a second, of 250
hardly one in seven seconds. The table now paces its sticks as the app does. **Measured again with it
(`runs\1120-0928-link`): "jitter 300 ms" armed at the first try, 60 ms after the switch; "loss 50 %" too** - every
row of the table arms at the first try now. So what is left of F-002 is what a pilot on a real link of that kind
would meet: a switch put on in the moment of a gap is refused once (ARMSWITCH) and must go off and on.
**Answered: kept as it is** (Betaflight's ARMSWITCH rule).

**F-019 - Right after PILOT UAV, before the link has ever been up, the OSD blinks "LINK LOST" in red.** The header
says WAITING FOR PHONE and the picture "Waiting for the link..." at the same moment. On a quick link it is gone in
a second; a phone that takes ten seconds to answer shows a red LINK LOST for ten seconds on a link that was never
there. One way: "CONNECTING" until the first link of the session, LINK LOST only after one was up. **Answered,
built for 1.0.71:** CONNECTING in LINK LOST's place until the connection's first link - steady and white, its reason
under it, no red frame, no alarm.

**F-046 - CHECK EVERY set just before a sleep button is pressed can go to the other sleep mode.** DEEP SLEEP and
OVERWATCH each keep their own check interval (1.0.56), and the one box shows one of them: DEEP SLEEP's until
OVERWATCH is pressed, OVERWATCH's from then on, and so on. A value chosen in the box is saved for the mode the box
is showing - not for the button pressed next. So: the box shows OVERWATCH's "5 min"; the pilot sets "1 min" and
presses DEEP SLEEP; the phone is told DEEP SLEEP's old value (2 min, say), the box jumps to "2 min" after the
press, and OVERWATCH now has 1 min. The log says what was sent ("Checks in every 2 min"). Found by the night's
sleep cycles: six red, each a drone that woke in time by the interval it had really been given while the scenario
waited by the one it had chosen (in 16 of the 36 cycles the two differed). One way: a value the pilot has just chosen
goes to whichever sleep button is pressed next (and is kept for that mode). **Answered, built for 1.0.71:** one CHECK
EVERY for DEEP SLEEP and OVERWATCH, on the LINK card and in FLEET (the pilot's own choice, not the suggestion); the
night's sleep cycles now fail on a difference.

**F-043 - What three readers of the window suite's pictures saw** (`runs\1119-2154-ui`; two of them stopped
half-way, so about 180 of the 441 pictures were read - the map scenarios' and PREVIOUS FLIGHTS' not at all). Each
looked at again by me; mended where it was plainly a fault (the red menu row, above), listed here where it is a
matter of layout or wording. **Answered, all six built for 1.0.71:** the small map by F-010's rule; the guide's step
count ("STEP 2" alone until the checks are in); INSTALL THE MOBILE APP's empty box and list; the wording; the OSD's
warnings on a dark ground; OUT OF ACTION's 30 min in CHECK EVERY. As found:
- **The small map is squashed on this very laptop's screen (1280 x 760 at 150 %).** With a drone chosen the PILOT
  MODE card takes the column, scrolls, and the map under it is some 60 px high: the red LAST KNOWN line is cut at
  the map's top edge, the zoom-out button at its lower one (`fly-23488-2\step-007.png`,
  `menus-switches-21456-1\step-009.png`). F-010's rest is the same rule at a smaller window.
- **The setup guide says "STEP 1 OF 9", then "STEP 2 OF 8".** The count waits a second for the checks that decide
  which steps are skipped; one that takes longer changes it after the first page
  (`guide-first-start-20376-2\step-001.png`, `step-002.png`).
- INSTALL THE MOBILE APP with no app file to hand (here: the made-up internet has none) shows an empty box under
  "Scan to download" and "Pick another address under the code" over an empty list; the reason stands in a line
  further down ("App not ready: ...").
- Wording: calibration's "CENTER THE STICKS" over "ALL STICKS CENTRED" / "Sticks centred"; its INVERT row wraps
  ("aux4" alone on a second line) and is in small letters beside a list in capitals; the guide's buttons "By hand
  (needed)" and "Retry" beside "DISCONNECT"; "FC: ARMING BLOCKED BY RXLOSS (TOOLS > RC check)".
- OSD warnings ("ARMING BLOCKED: RXLOSS") lie over the pitch ladder's "10" rung.
- A drone marked OUT OF ACTION sleeps with a check every 30 min (the rule), while CHECK EVERY beside it shows the
  pilot's own "5 min".
- Not faults, as far as could be told: pictures taken while a menu or window fades; a tooltip over the picture
  where the bench's mouse stood; the OSD window's element text cut in half 0.2 s after the window opens (the
  new `OsdEditorScenario` looks again after 1.5 s: nothing cut, for each of the 40 elements, and with an element
  moved - the half line was the window still laying itself out).

**What the reader of the PC's link code found, not changed then** (the first two are the red rows' causes, above).
**All four since settled for 1.0.71:** the late DISARMED, the later LINK READY (F-057) and the moved PC fixed
before the release; DISCONNECT on a one-way link kept by the pilot's answer (the drone beeps). As found:
- A DISARMED that arrives late can be read as fresh and stop the sticks of a drone that has just armed, for half a
  second to a second (`RcSession.watchLoss`): the phone's way up queues, the pilot arms, the queued telemetry
  trickles in over two seconds late and its first ARMED is lost. Narrow - several things must coincide. One way:
  two DISARMED in a row with ARM on, as ARMED already needs two.
- After the phone's way back was gone for 2 to 4 s only, the PC is "connected" again on its old packet numbers
  while the phone began a new session at the PC's Connect: by the code LINK READY comes 1 to 2 s later than it
  could. The bad-link table has a row for it now, and by it the reader's fear was too large: "cut 3 s phone to PC
  only" was LINK READY 0.86 s after the phone was heard again, with one new session (`runs\1120-0928-link`).
- DISCONNECT pressed while the PC hears nothing but the phone still hears the PC sends no Disconnect: the phone
  counts the link as lost four seconds later, and the drone beeps. Whether a drone the pilot gives up on in that
  state should beep (to be found) or not is the pilot's rule.
- Without Tailscale, a PC whose own address changes during a session can end up a spectator of its own old
  session, for good until a reconnect by hand.

## Found and fixed in the test week (2026-11-21 - 23, on `beta`)

F-059 - F-075: the test week's first night. Found by a reader of the PC's code (Drone setup, the motor test, the
armed guards), a reader of the phone app's code (its stick, flight controller and update paths), a reader of the
bench's pictures of the 1.0.71 runs, and test runs. Each is fixed on `beta` (CHANGELOG, 1.0.72) with a test that
failed before the fix. **None of these has been tried on hardware:** Drone setup's fixes ran against made-up
boards, the phone app's in its shared Common code on the PC and in the made-up phone. The window suite ran on them
on 2026-11-22 morning: 28 of 28 ([BENCH-RUNS.md](BENCH-RUNS.md)).

F-076 - F-083: the second day (2026-11-22). Found by the phone app's first runs on Android 8 to 15, a reader of
Drone setup's writes held against Betaflight 4.4.1's source, the bench's real drone (battery in, props off) and the
real phone. **The pilot approved F-059 - F-064, F-066 - F-079, F-082 and F-083** (on the week's page, 2026-11-22 and
23); F-080's answer is in its paragraph; F-065 and F-081 needed none.

**F-059 - `SessionIdEndToEndTest.aReconnectIsOneSessionOnBothSides` was red now and then: the test, not the app.**
Red once on GitHub's runner (three sessions on the phone where it asks for two) and in 11 of 40 runs here, found by
those runs. F-001's rule at work: the PC keeps its try's id while the phone is unheard and begins a new try the
moment it hears the phone again. When the test cleared the wire, a Connect of the old try that left in the 50 ms
before could get through as well - a session for it and one for the new try, the two the rule allows. Which a run
saw hung on where the test's sleep ended in the PC's Connect pace. **Mended in the test:** the wire's cut (`Cut`)
now opens on the first Connect of a new try - one session on both sides, exactly. A second test,
`aReconnectOnAConnectOfTheOldTryIsTwoSessionsOnThePhoneAndOneOnThePc`, opens on a Connect of the old try and holds
its answer back a second: two sessions on the phone and one start on the PC, the most the rule lets a reconnect
cost. The app untouched; 40 of 40 runs green (20 beside a perfTest). F-072 was found in the same test's runs.

**F-060 - The motor test could turn another board's motors.** Drone setup read one board, and the motor test
opened the first Betaflight board that answered: with two flight controllers on USB the props came off one and the
other's motors turned - or its motor order, props direction or ESC direction was saved. And Motors and props said
"Checked." for a window closed with no motor turned. Found by a reader of the PC's code. **Mended:** CHECK NOW hands
the window the USB port and board id of the last read, the ones CONFIRM is for (`SetupConfirm.boardId`). Another
board found there is asked what it is and sent nothing more, not even STOP ("COM7: not the board Drone setup read.
Nothing sent. Plug that one in, or Back and Read again."; "Drone setup's board is not on COM7. Plug it in, or Back
and Read again."), and the window goes on by itself once the right one is there; a window still open for another
board is closed. Opened with nothing read, it takes a board alone on USB, never one of two ("2 flight controllers on
USB. Unplug all but one."). "Checked." only when a motor was commanded to turn ("No motor turned. Check now."; an
earlier check stands). Tests: `MotorTestBoardTest`, `DroneSetupMotorsCheckTest` - 7 of 11 red before. Not tried on
hardware.

**F-061 - A test motor kept turning while the window hung.** Every stop of the motor test (the 20 s auto-stop, a
click's 3 s, SPACE, STOP ALL, leaving the window) was a Swing event, and Betaflight keeps the last value it was
sent. Found by a reader of the PC's code. **Mended:** the motor thread asks `MotorLimits` on every round and itself
ends a click's motor after 3 s, every motor after 20 s of running, and every motor when the Swing thread has not
answered for 1 s (the window says it is there ten times a second: ten beats missed, five times sooner than
`AppLog`'s 5 s freeze watch). What it stopped stays stopped until the window has taken it; after a window that did
not answer the box is unticked too, so a click that waited in the hung window starts nothing ("Window froze: motors
stopped. Tick the box to go on."). A board lost: what was asked of it is forgotten, and a board that is back is not
sent the old values. Tests: `MotorTestLimitsTest` - 4 red before (three motors still turning after 10 s, one
started again on a board cut off and back); `MotorTestRig` builds the window off screen on a made-up USB. Not tried
on hardware. Left as it is: no line for a pulled cable - the warning over the sliders already says "If USB comes out
while a motor spins, unplug the battery."

**F-062 - "Armed: not known yet" was DISARMED to the PC's guards.** For a link's first seconds there is no status,
then statuses read before Betaflight's mode names have come, which say "not armed" whatever the drone does - and
again after a loss of 60 s, when the names are forgotten. With the app started again while its drone was in the
air, STANDBY MODE, closing the window and OUT OF ACTION ended the link without the question, DEEP SLEEP and
OVERWATCH stopped the sticks, an overwatch card's CONNECT cut the link, and a new link's first status ended a lost
drone's "possibly flying" hold. Found by a reader of the PC's code. **Mended:** not known counts as armed.
`FcTelemetry.Status` says whether it was read with the names (`armedKnown`); `ArmedCheck` is the guards' one rule
(ARMED, MAY_FLY, NOT_KNOWN, CLEAR) and the question's words ("Not known yet if the drone is armed."); `PossiblyFlying`
takes such a status as not known. Nobody is held: every guard asks and the answer goes; a phone with no flight
controller (FC LOST) and a connection with no link up yet ask nothing; not known ends 10 min after the flight
controller was last heard. With it: LOST DRONE asks while a link is up before its first status, FLEET refuses a mode
on a phone's own fresh "not known", the OSD and the controller card say nothing of armed until it is read, and the
link log's armed column is blank meanwhile. Tests: `ArmedCheckTest`, `ArmedNotKnownRoundTest`,
`ArmedNotKnownEndToEndTest` (a virtual drone in the air, the link closed and opened anew, the names' answer lost on
the wire; a phone with no flight controller) - 13 of 15 red with the old rules; `OverwatchFleetEndToEndTest`'s
quarantined test now waits for the flight controller's DISARMED. Not tried on hardware. The sticks' rules
(`RcSession`) are untouched.

**F-063 - CALIBRATE with no flight controller calibrated the next board plugged in.** The level calibration waited
without limit and was done on the next board that came - perhaps held in a hand, and saved as "flat"; a press made
with board A there was done on board B. Found by a reader of the PC's code. **Mended:** the wish names the live
view's open connection. With none open nothing is kept and the row says at once "No flight controller: not
calibrated. USB to this PC, then Calibrate." ("Close the other check first, then Calibrate." while the motor test or
another check has the port); a board lost between the press and the calibration, or in it, ends it with "Flight
controller lost: not calibrated. Calibrate again." - the row no longer stays on "Calibrating". Tests:
`DroneSetupLevelTest` - 3 of 4 red before; `MotorsPageRig` builds the step off screen. Not tried on hardware.

**F-064 - A motor's spin direction was saved on a pick in the list.** No props-off tick, no question - and an ESC's
direction can't be read back. A direction picked as the cable came out was saved in the next board plugged in.
Found by a reader of the PC's code. **Mended:** saved only with "Propellers removed" ticked and after one question
that names the motor and the direction ("Set motor 2 to reversed? Saved in its ESC."); without the box the list goes
back to As is ("Tick the box first."), on No "Motor 2 direction not changed.". A direction, the motor order or props
in / out is made only on the board it was asked of: the queue is emptied when the board is lost and when another
opens ("Flight controller lost: nothing saved."). Tests: `MotorTestDirectionTest` - 4 red before. Not tried on
hardware.

**F-065 - Notes from the same safety read, not changed.** Nothing is changed for these unless the pilot says so or
a hardware test shows a fault. The motor order's save waits 1 s for the board's answer: a slower real board would
be told "no answer" for an order that was saved - to be timed on the real board. ESC "Beacon off" erases the ESC's
settings page, then writes it: a cable pulled in between leaves it blank (the ESC starts on its defaults, a reversed
motor turns the normal way); the app keeps a backup, but nothing says so at the next start (this drone's AM32 ESCs
are not written at all). A radio that hangs while still plugged in keeps sending its last sticks; the cut for that is
off by default (the pilot's decision of 5 Nov), only the OSD warns. At start, with the saved radio missing, the app
takes any other joystick it finds; the start rule (throttle down, ARM off) still applies.

**F-066 - An app started anew while the link was out let the ARM switch left on arm the drone on the ground
(phone app).** Armed, the link lost, Betaflight's failsafe disarmed the drone and kept its "switch must be seen
off". The app's process started again in the outage (an error and its own reopening, Android ending it), and what
its idle guard knew - the PC's last frame came at an armed flight controller - was gone with it. It read DISARMED,
fed its own idle sticks (ARM off, pc.54), Betaflight took that for the switch seen off, and the pilot's frames, ARM
still on and throttle low, armed it when the link came back; the PC's own stop comes 0.6 s later. Found by a reader
of the phone app's code. **Mended:** the idle sticks can't wait for the pilot there (a drone resting after an app
start would beep for its lost receiver), so the phone keeps what it ends: `RcPath.IdleGuard` - fed before any stick
frame of a PC's, the pilot's own ARM off is owed, and a stick frame that could arm by the flight controller's own ARM
ranges is not written until one has come that cannot. Such a frame is still the pilot's last word: the idle sticks
stop with it. Never held while the flight controller reads ARMED; nothing owed where a stick frame came first (an app
started anew in the air). One volatile read a frame while nothing is owed; `Udp.handleRcFrame` asks it before the
write (`Msp.frameMayArm`). The made-up phone (`VirtualDrone`) has the same guard now, and for tests the phone's own
idle sticks (off unless turned on). Tests: `RcPathTest` (five; four fail without the rule),
`VirtualDroneEndToEndTest.anAppStartedAnewInAnOutageDoesNotLetTheSwitchLeftOnArmTheDrone` (without the rule the
drone arms by itself), `anAppStartedAnewInTheAirPassesThePilotsSticksAtOnce`,
`FlightModelTest.armOffFromAnyoneEndsTheSwitchLockoutAfterAFailsafe`. Not tried on hardware.

**F-067 - One old stick frame could arm a drone that rests (phone app).** With a 16-bit sequence a frame was taken
only if newer than the last one - until a second had passed without a frame taken: then anything was (pc.31, "the
PC may have started counting again"), and no age is looked at while `rc.maxLagMs` is 0, its default. A session up,
the drone disarmed, the pilot's frames stopped (standby, the radio at rest): one frame come late or twice, from
before the landing - ARM on, throttle low - was written, and Betaflight armed where it stood until its failsafe took
it 1.9 s later. Nothing in DRAUPNE Link counts anew inside a session (`ControlLink.rcSeq` is one counter a link, and a
new link's Connect makes the phone forget the sequence); the second was there for upstream's relay, which no phone
app since pc.28 can be set to. Found by a reader of the phone app's code. **Mended** (`RcPath.Seq16`, the phone's
rule and the made-up phone's): a frame that is not newer than the last one taken is old for 120 s after that one -
30 000 frames at the PC's fastest, short of the 32 768 where older and newer can't be told apart; after that,
anything. Frames that are not newer but come as a stream - each newer than the one before, none more than 300 ms
after it, for a second - are a PC counting from elsewhere and are taken from there; a late frame, a doubled one, a
bunch a queue let go and a trickle with gaps are not a stream. A newer frame costs what it cost. Tests: `RcPathTest` -
the old test's last line turned round, seven new; seven fail on the rule as it was. Not tried on hardware. As they
were: `rc.maxLagMs` and its default, 8-bit frames' 300 ms, a frame without a sequence.

**F-068 - An app that had just started read "disarmed" before it had read the flight controller (phone app).**
`FcArmedState` said DISARMED whenever no flight controller was initialised and the app had not seen it armed within
the minute: for the second or two an app started anew needs for the board's first answers (in the air perhaps), and
for good when the board was there and never answered (its USB permission not given again). Every guard that waits
for a DISARMED went on: a queued FLEET_MODE was taken and its countdown dropped the stick frames for up to 3 s, the
remote update began. Found by a reader of the phone app's code. **Mended** (`FcUnread`, in Common): a board on the
port that has not answered is not read - UNKNOWN, counted as armed, for as long as it does not answer; no board on
USB at all for 3 s without a break is DISARMED as before (UNKNOWN when it was armed within the minute); a port not
looked for is DISARMED only where a sleep closed it right after a DISARMED read, else UNKNOWN. Asked by the guards,
never for a stick frame; `Msp.portStatus` hands the port's state on. The made-up phone's flight controller is not
read for 1.5 s after `restartApp`. Tests: `FcUnreadTest` (six; four fail on the old rule),
`FleetModeEndToEndTest.aPhoneAppThatHasJustStartedRefusesItUntilItHasReadItsFlightController`. Not tried on hardware.

**F-069 - The drone's beeper could stay off after a sleep (phone app).** At the wake the kept settings were deleted
first and then written back once - through the one-place slot the channel map's request shares, 20 ms, no second
try, no read-back - and to whichever flight controller was on the port. One lost write left the lost-receiver buzzer
and the motors' beacon off until the flight controller lost power. Found by a reader of the phone app's code.
**Mended** (`BeeperBack`, in Common): written, read back (MSP 184) and written again until the flight controller
answers with them - one write every 0.4 s at most, five a connection, then the log says they are NOT back and the
next connection tries again; forgotten only when the read-back equals them. Kept with the board's id (MSP_UID, 160,
asked with the slow requests until it answers) and never written to another board; a board that is not identified
is neither switched off nor written to. Going to sleep the off write is read back too (three times 150 ms at most),
and the log says when the drone may beep through the sleep. OUT OF ACTION's wake for the beeper (F-051) goes through
the same read-back. Tests: `BeeperBackTest` - 11 of 13 red before. Not tried on hardware. Left as it is: no record
tells the PC of the beeper, so the phone's log is where it is said. The keeper's strict list (the rig) learned
MSP_UID with it (`FcKeeperStrictTest`, red on `beta` until then).

**F-070a - New flight controller port settings sent in flight were half applied (phone app).** The port's restart
waits for the disarm (pc.31), but the stick writer asked Config at every write whether to use the native serial port:
changed in flight, the sticks went to a port that was not open; a port lost in flight was looked for with the new
settings. Found by a reader of the phone app's code. **Mended** (`FcPortLatch`, in Common): `Serial` asks it for the
port's settings, which change in one place - where the port is about to be opened, after `restart()` was asked with
the flight controller known disarmed. One volatile read where Config was called. Test: `FcPortLatchTest` - 4 of 4 red
before. Not tried on hardware. As before: Config takes these fields from the network.

**F-070b - A remote update that waited for a tap was never taken back (phone app).** The phone said "tap needed" for
five minutes (the PC refuses to arm meanwhile), then nothing more, and Android's "update this app?" stayed on the
screen for good: armed and flying, one tap installed the update and ended the app, and the sticks with it. Found by a
reader of the phone app's code. **Mended** (`UpdateWithdraw`, in Common): `AppUpdater` keeps the id of the install
session it makes (in memory, and on the disk for the next start) and abandons exactly that one - the moment the
flight controller is not known disarmed (asked once a second), when the phone no longer tells the PC that a remote
one waits, and at the app's next start when an earlier run left one. The PC is told why once Android says the
session is gone ("FC not known disarmed: update taken back", "no tap on the phone in 5 min: taken back"). A second
update takes the first one's session back. Test: `UpdateWithdrawTest` - 4 of 5 red before. **Not tried on a phone:**
what Android's prompt does once its session is abandoned. Left as it is: an install Android has already begun can't
be taken back, and stays "installing" to the PC.

**F-070c - Failed writes to the flight controller's USB were only logged (phone app).** Only a failed read closed the
port and looked for the device again, so a port whose reads stood still while every write failed stayed as it was,
and no stick reached the flight controller again. Found by a reader of the phone app's code. **Mended** (`WriteRun`,
in Common): the MSP writer counts its failed writes; from the second in a row Android is asked whether the device is
still on USB (every 100 ms at most) - gone, it is lost at once; still there, after four failures that span 300 ms
with none out between. Writes that only time out are six or more by then (a stick write gives up after 50 ms), and
errors that come back at once need the 300 ms too: a burst of failed transfers closes nothing. 300 ms because
Betaflight holds the last sticks that long, leaving a second and more to open the port before its stage 2.
A write that goes out costs one store. Test: `WriteRunTest` - 7 of 8 red before. Not tried on hardware. As before:
MAVLink's own writes.

**F-070d - A phone clock set back N seconds meant no sticks for N seconds (phone app).** The pace of the stick writes
(a frame within half a frame time of the one before is dropped) was kept on the wall clock, which the network
corrects in flight too: set back, every frame was too soon until the clock had caught up. MSP only below the PC's
rate of 255, MAVLink at every rate. Found by a reader of the phone app's code. **Mended** (`RcPace`, in Common): both
ask it with `SystemClock.elapsedRealtime`, and it holds nothing back when a clock reads less than at the frame
before; at the PC's rate one field read and no clock read at all. `Mavlink.java` is in the overlay now: a copy of
upstream's (473608d) with the pace changed and nothing else - its fields are private and the check is inside the
method, so it could not be mended from outside. **When the `upstream/` pin moves, this file is taken again from
upstream and changed the same way.** Test: `RcPaceTest` - 3 of 5 red before. Not tried on hardware. The phone app's
other wall clock readings measure nothing on the way from a stick frame to its write and decide no link loss.

**F-071 - Drone setup's other USB tools took any board too.** With the port on Automatic the orientation check (it
writes the board alignment and align_mag), the compass calibration and the live view behind CALIBRATE took the first
port that answered as Betaflight, and FIND GPS and the ESC row (READ ESCS, BEACON OFF) opened the port last read
whatever board was on it - any port, when none was known. With two flight controllers on USB, or after a swap on the
cable, one board was read on the page and another levelled, calibrated or written to. Found while F-060 was mended,
by going through every place Drone setup opens USB. **Mended:** all of them go the motor test's way, through one
place (`setup/SetupBoard.open`; the motor test's own code moved there): the port and the board id of the last read
(`BetaflightPage.board`), the same words as F-060's, none chosen between two. A port the pilot chose with nothing
read on it yet, and a board that does not say its id, are that port alone. A window of the orientation check or the
compass calibration still open for another board is closed. Tests: `DroneSetupUsbToolsTest` (13) and `SetupBoardTest`
(7); with the old way put back for one run 11 of the 13 were red. Not tried on hardware. As before: the first step's
own read (on Automatic the first Betaflight board, written only for the id CONFIRM was pressed for), and what the
orientation check writes, its tries and its restore.

**F-072 - After a cut, the answer to the try kept through it was taken for a slow way (PC).** `SessionGate.Ids`
counted an older try's answer from that try's first Connect - 3.6 s back, in the cut; half as long again was the 5 s
cap, and when the next try's answer was then lost the PC asked again only 5 s later: LINK READY about 4 s later than
F-001's second means it to. Found in F-059's test runs (a real run's times). **Mended:** a try sends many Connects
and none says which one was answered. With the phone heard it is the try's first, counted from as before; a Connect
that went out into the silence - nothing heard from the phone since the try began, nor for 2 s, the time after which
the PC counts a link lost - may be lost with all before it, so the newest of those is counted from. Which id a
Connect carries is decided as before (checked against the class of before over 300 000 random runs). The phone app
compiles the class and never calls it. Tests, red before:
`SessionGateIdTest.anAnswerAfterACutIsCountedFromTheLastConnectSentIntoTheSilence` (79 ms, was 3631; the next try renewed after its second, was 5 s) and
`SessionIdEndToEndTest.anAnswerAfterACutIsNotTakenForASlowWay` (that try kept 1.02 s, was 5.05 s; LINK READY 1.25 s
after the link came back, about 5.3 s before); green before and after:
`anAnswerOnASlowWayIsCountedFromItsTrysFirstConnectAsBefore`. Not tried on a real link.

**F-073 - The setup guide still said "STEP 1 OF 9", then "STEP 2 OF 8".** F-043's rule (no total while a check that
decides the skipped steps is out) counted the last check as done before its result was in, so its page still
counted as shown when the total was made. Found by the reader of the bench's pictures (`guide-first-start`).
**Mended:** a page's background job counts as done after its result is applied (also when that throws). Test:
`WizardStepCountTest` - both red before.

**F-074 - A link that was up and lost in flight was worded as a first connection.** The header said CONNECTING in
yellow, the controller card FC: NO STATUS YET and the log "Waiting for the phone... (DRAUPNE Flight open? On
Tailscale? Same key?)", while the video said LINK LOST. Found by the reader of the bench's pictures
(`rescue-link-lost`). **Mended:** F-019's rule holds there too - CONNECTING and the questions until the
connection's first link; after that LINK LOST in red in the header, "Link lost - reconnecting." once in the log, and
FC: NO DATA on the card (also when the link is back and the status from before the loss is old). Not for a phone that
sleeps as asked (WAKING), not when watching. Tests: `LinkLostWordsEndToEndTest` (red against the old wording),
`LinkReviewRoundTest.aLinkThatWasUpIsLostInTheHeaderAndTheCard`.

**F-075 - Three texts cut or miscounted.** OSD settings' ELEMENT card stopped an element's words after the first
line; QUEUED: OVERWATCH (AT CHECK-IN) opened cut in half at the LINK card's edge in the smallest window (1093 x 584);
IMPORT said "1 drawings and symbols, 1 pins imported.". Found by the reader of the bench's pictures (`menus-windows`,
`sleep-small-window`). **Mended:** the words wrap to the card's width at the height of all their lines
(`OsdSettingsDialog.helpLabel`); the card's rows scroll the line into sight once it has opened, the view's top at a
row's top (`LinkCardLayout.reveal`, `Fold.whenOpened`); "1 drawing or symbol, 1 pin imported."
(`MapWindow.importedText`). Tests: `ScreenSizeTest.theOsdElementsWordsAreWholeInTheirCard`,
`aLineThatOpensAtTheFootOfTheLinkCardIsScrolledIntoSightWhole` (both red before),
`theViewsTopGoesToTheNextRowsTopWhileTheLineStaysInSight`, `MapToolsTest.importSaysWhatItBroughtInTheRightNumber`.

**F-076 - On Android 8 and 9 an error left the phone app half alive until "has stopped" was tapped (phone app).**
Android's own handling of an uncaught error showed "DRAUPNE Flight has stopped - Open app again" and ended the
process only once that was answered (on Android 8 and 9 at every error; from Android 10 only after errors close
together, "keeps stopping"). Until then the app's window and main thread were gone and its camera, video and link
still ran: the PC kept a link and saw nothing wrong, and pc.55's reopen alarm (`CrashGuard`, 1.5 s) rang for a process
that could not take it - Android closes the question by itself after about 5 minutes. Found by the bench's first run
of the phone app on its lowest Android (`PhoneCrashScenario` on the Android 8 emulator, `bench.ps1 emulator -Avd
draupne-a26`): no new process within 30 s, no reopen after any of three errors; by hand, the process still there 24 s
after `am crash` with Android's question in front. **Mended (pc.57-beta):** while the app is at work (its service
running) `CrashGuard` ends the process itself at once, after writing Android's own two lines (`AndroidRuntime`,
"FATAL EXCEPTION: <thread>", the trace) - what Android's handler does after the question; the alarm then finds no
process and opens a new one. An app that is only open, nothing running, gets Android's handling as before. Test: the
crash scenario on Android 8 (red before) and on the bench's Android 14 (green before and after).

**F-077 - The motor test's PROPS IN / OUT switch worked on an armed drone and reversed yaw at once.** The motor test
looked for an armed drone only when it opened. A drone armed after that (by a radio on its receiver), PROPS pressed
and answered yes: Betaflight takes the new side at once - its yaw control reads it on every loop - and refuses the
save while armed, so it does not restart either, and the wrong side stays until the flight controller loses power.
The pilot would have read "The flight controller refused MSP command 250", the switch on the new side, and "ARMED -
motor test stopped. Disarm." Motor order and spin direction had the same missing check, harmless in flight (the
order is used only at start, a direction is ignored while armed). Found by a reader of the PC's code against
Betaflight 4.4.1's source (mixer.c, msp.c); not tried. **Mended:** props in / out, motor order and spin direction
read the board's status right before they write, and an armed board gets nothing ("The drone is armed. Disarm it
first."). While the board says armed the PROPS switch, REORDER and the direction lists are grey, and the switch
turns only once Betaflight has saved. A write whose save fails is put back and said so ("Not saved. Props in / out
put back as it was.", the same for the motor order). Tests: `MotorTestArmedTest`, `MotorTestArmedWindowTest` - 7
new, red before. Next: Betaflight as a program, armed - PROPS OUT refused, yaw unchanged. **The pilot: approved.**

**F-078 - Drone setup's CLI writes never checked for an armed drone: Betaflight blocked them, with the wrong words
and one short gap.** CONFIRM, FIND GPS, the orientation check's SAVE and Apply and the compass calibration write
through Betaflight's CLI, and none read "armed?" first. Betaflight 4.4.1 ignores the CLI's `#` while armed, so
nothing was written - but the page said "No answer from the flight controller - is it running Betaflight?" and later
"Replug the drone's USB", which sends the pilot after the wrong thing. And Betaflight opens its CLI 0.1 s after the
`#` without looking again: a drone armed in that 0.1 s would have been restarted at the session's end. Found by the
same reader; not tried. **Mended:** every CLI write enters through one place (`BetaflightUsb.enterCli`) that reads
the board's status first: armed, nothing is sent and the page says "The drone is armed. Disarm it first.", with no
Replug hint; not armed, arming is blocked (MSP_SET_ARMING_DISABLED) before the `#`. `exit` and `save` restart
Betaflight, which takes the block off; a CLI that never opened has it taken off at close - a block someone else set
(Configurator, the rig) is left alone. CONFIRM switches the receiver only after the check; the orientation check
keeps its question and the board open when refused. Tests: `BetaflightUsbCliArmedTest`, `DroneSetupArmedTest`, with
`OrientationCheckCompassTest` and `BetaflightTest` - 11 new, red before. Next: Betaflight as a program - armed,
refused with these words; disarmed, the block shows while the CLI is open and is gone after the restart. **The
pilot: approved.**

**F-079 - CALIBRATE could say "done" when Betaflight had done nothing (armed drone).** Betaflight answers OK to the
level calibration while armed and calibrates nothing (msp.c), so the row said "Calibrated: reads flat." for a level
never taken; a spin direction is the same (DShot commands dropped while armed). Found by the same reader; not tried.
**Mended:** CALIBRATE reads the board's status first and asks nothing of an armed board: "The drone is armed. Disarm
it first." A spin direction on an armed board is refused since F-077. Left for later: the compass calibration's
START is ignored by an armed Betaflight too - with F-078 it can no longer end in a false "calibrated", but the pilot
turns the drone for 30 s before being told. Test: `DroneSetupLevelTest` - one new, red before. Next: Betaflight as a
program, armed. **The pilot: approved.**

**F-080 - Read ESCs could leave motor 1 dead while its ESC looked healthy - a flip at take-off (the real drone).**
Seen on the bench's drone (Betaflight 4.4.1, four AM32 2.17 ESCs, props off, battery in), 2026-11-22 15:22 - 15:24:
fresh after a battery unplug motor 1 turned at 1,471 rpm; after a restart of the flight controller alone, 1,485 rpm;
after an ESC settings read (what Drone setup's ESC row runs) that did not reach ESC 1, 0 rpm - with ESC 1's
telemetry complete, so the drone would have armed and flipped. Another restart does not bring it back (ESC 1 then
falls silent, and a droning starts); only a battery unplug does. Seen 7 times that day and once on 19 Nov; only ESC
1, never 2 - 4. The row then said "[WARN] 1: no answer (battery on?)" - with the battery on. In the field CONFIRM
restarts the flight controller, and Read ESCs after it with the battery in is exactly this. **Built meanwhile, a
warning and not the cure:** an ESC that does not answer with the battery in makes the ESC row red with "ESC 1 did
not answer - it may not turn now. Unplug the battery, plug it in, Read ESCs again." (several: "ESC 1, 3 did not
answer - they may not turn now."), and Drone setup is not done for that board until a read in which all answer: the
step's status line says the same in red, and the USB row never says Done. The hold is kept by board id
(`drone.escsSilent`), so closing Drone setup does not end it and another drone is not held by it. "battery on?" is
said only when Betaflight reads no battery after the read; Beacon off is not offered while an ESC did not answer and
writes only those that answered; the app's log gets the same line. Tests: `EscLinkTest`, `DroneSetupUsbToolsTest` -
7 new, 6 red before; on the drone: a restart, Read ESCs missing ESC 1 - red with the words; the battery unplugged,
Read ESCs - all four, done. **The pilot: "change - find a proper fix"** (2026-11-22). The cause is being looked
for, so that the read no longer leaves ESC 1 that way at all: Betaflight's ESC passthrough and AM32's own code read
side by side, then short tries on the drone that tell the causes apart (`gradlew escProbe` with `-Porder`,
`-PfirstWaitMs`, `-Ptries`, `-PpauseMs`, `-Ppasses`, `-Pesc=recover`, `-Pesc=spinonly`; each failed try costs a
battery unplug). The first lead, from the sources and not proven: the read asks ESC 1 at once, six times 250 ms
apart, while an AM32 that has just lost its signal may still be deciding whether to restart into its bootloader.
The red row stays as a second line of defence. **Until it is fixed: no Read ESCs after CONFIRM or another restart
with the battery in - or unplug and replug the battery after it, before flying.**

**F-081 - Stick frames queuing at the flight controller: measured, no problem.** On Betaflight as a program, sticks
at 100 a second left no room for anything else. Read in the code: Betaflight takes one MSP request per run of its
serial task, 100 runs a second, and the phone passes on every stick frame the PC sends (about 250 a second) and
about 42 requests of its own. Had that held on an F4 board, about 2 KB would have queued there, and sticks, ARM and
disarm would have come 0.5 - 0.8 s late while the sticks flow - unseen by the PC's STICKS LATE, which is measured at
the phone. **Measured on the bench's drone** (2026-11-22 16:25, `gradlew mspRateProbe`, arming blocked, idle sticks;
the phone's mix of sticks and requests over USB, 10 s at each rate): at 250 stick frames a second 2,500 of 2,500
answered, median 12 ms (90 % 16 ms, max 28 ms), the requests 9 ms, none lost; 10 ms at 100 a second, 8 ms at 50; no
queue left after the last frame. The one-request-a-run limit is SITL's own stand-in for USB (TCP), not the real
board. Nothing changed. The bench's lesson: measure before believing a code read.

**F-082 - UPDATE PHONE APP on Android 11: switching on "Install unknown apps" ended the phone app, and the PC then
gave a wrong reason.** When the PC asks for "Install unknown apps" for DRAUPNE Flight and it is switched on with the
link up, Android 11 ends the app (Android 12 and newer only when it is switched off; the drones' phones are Android
15). The link is down until the app is opened again - about 100 s when nobody touches it - and an update asked
meanwhile is lost. The PC then said "No answer to the update: phone app older than 2.3.0-pc.32." though it knew the
phone ran pc.57-beta. Seen twice in the bench's update test on the Android 11 emulator (2026-11-22). **Mended
(PC):** for a phone app known to take the update (pc.32 on) the log says "No answer from the phone (link lost) -
press UPDATE PHONE APP again."; the old words stay for a version not known or older. Not built: a hint that Android
11 ends the app at that switch (those words would be the phone app's own; the drones' Android 15 phones do not end
it). The
bench's update test now opens the app again after the switch and waits for the link. Test: `RemoteUpdateAnswerTest`
- red before; the Android 11 emulator run after it: 7 of 7. **The pilot: approved.**

**F-083 - On a phone with no screen lock the phone app did not come back by itself after an error (phone app).**
The real phone (Android 15) had no screen lock that evening (a PIN that morning). After an error, and after being
killed while asleep, the app's own way back fired on time - but Android showed only a small banner, and the app
stayed closed until someone opened it. The app lit the screen together with its full-screen "open me" notice, and
Android opens an app from such a notice only while the screen is off or a lock screen shows (SystemUI's rule on
Android 14 and 15): on a phone with no lock the lit screen was an unlocked one. With the PIN it had opened each
time. On a drone: a phone with no screen lock whose app crashed in flight would stay closed - no link to the PC until
someone opened it. Found by the bench's crash scenario on the real phone (`runs\1122-2012-phone`). **Mended
(pc.59-beta):** `WakeNotice` posts the notice with the screen as it is, and only if the main screen has not come up
2 s later takes the same wake lock; over a lock screen it then posts the notice once more. The log says at each
notice what the screen was and what Android will do with it, and at the start whether Android 14+ lets the app use
full-screen notices. The count of reopens and the refusal after too many are unchanged. Test: `ScreenWakeTest` (the
rule, in Common; it also reads the sources: no wake lock beside the notice). On the real phone the crash scenario
**passed with no screen lock** (2026-11-22 21:19: open again 0.3 s after each notice) **and with a PIN** (2026-11-23
07:59: back 5.5 s after an error, the PC's link 6.5 s; killed asleep, back in 54 s; the fourth error in ten minutes
stays down). Until pc.59 is on the drones a screen lock does it; the pilot says every drone phone has one (Q-010).
**The pilot: approved.**

**F-084 - SETUP > Set up a new drone froze the window for about half a second as it opened.** The bench's freeze
watch (0.5 s) caught it in about half of ten runs of Betaflight as a program on 2026-11-23 (0.46 - 0.61 s), always
at the guide's opening. Measured: nothing slow was asked of the network, Tailscale, the phone or the USB ports. The
guide built and laid out all seven of its pages at once, and the motors step read its 3D drone (a model and two large
pictures, 0.12 - 0.16 s) on the window's thread, the first time in a run. **Mended (PC):** a page is built when it is
first shown; the 3D drone is read in the background as the guide opens; every page's own checks still start at once.
The window now stands still 0.25 - 0.28 s at the first open (later opens about 0.1 s); the suite of Betaflight as a
program passed twice in a row on it. Same in every setup guide; words and steps unchanged. Test:
`WizardPagesBuiltWhenShownTest` - red before.

**F-085 - The arming block MSP's words blamed Configurator alone.** Since F-078, Drone setup sets this block itself
before it opens Betaflight's CLI. If the link drops then, the block stays until the drone restarts (seen on
Betaflight as a program when a connection broke), and TOOLS > RC check said "Configurator connected. Disconnect it,
or power-cycle the FC." **Mended (PC):** "Arming blocked over MSP (Drone setup or Configurator). Restart the drone."
The FC line ("FC: ARMING BLOCKED BY MSP (TOOLS > RC CHECK)") was right and stays. Test: `FcTelemetryTest` - red
before.

**F-086 (safety) - Drone setup took ANGLE off the top of the flight mode switch on Betaflight 4.4.** Seen 2026-11-23
on the bench's 4.4.1 drone, battery out (`runs\1123-1512-hw`, `HwDroneSetupScenario`). The board had ANGLE at the top
of AUX2 (`aux 5 1 1 1700 2100`). The page's default switch is Acro / Angle / GPS position hold, and GPS position hold
needs Betaflight 2025.12: CONFIRM wrote Angle in the middle, cleared the top and wrote nothing there - the top flew
acro. The row said "Partly set ... needs Betaflight 2025.12 or newer" and the page said done (F-087). **Mended (PC):** a
position whose chosen mode the board can't have keeps the mode the board has there; with none there the row is red
and the page is not done. On a board older than 2025.12 the lists don't offer GPS position hold (their hint says why)
and the top is Angle; the pilot's saved choice is kept, so 2025.12 drones still get GPS position hold. The same rule
stops a 2025.12 drone losing its GPS position hold when its GPS isn't found for a moment. On the real drone again
(`runs\1123-1604-hw`): "LOW Acro, MIDDLE Angle, HIGH Angle: Set", the switch unchanged. Tests: `ModeSwitchTest`,
`SetupPlannerTest`, `DroneSetupModesTest`, `HwDroneSetupSelfTest`.

**F-087 - "Betaflight set up - unplug the drone." while a row said not set.** Seen on the made-up board and on the real
drone: GPS rescue said "No GPS found" and the flight modes "Partly set". Since 1.0.71 a CONFIRM that wrote everything is
spent, and the page then took every row as still to be written, so none could keep it from done. **Mended (PC):** after
CONFIRM a row not as chosen counts as not set; done only when every row is set, else the status names the rows in red.
On the real drone, battery out: "Not set: GPS, GPS rescue." Tests: `DroneSetupDoneTest`, `HwDroneSetupSelfTest`.

**F-088 - BY HAND named 6 cells while the page kept the board's 4S.** BY HAND's words were made when the page opened,
before the board was read. **Mended (PC):** made when BY HAND is pressed and again when the page takes the board's
count. Test: `DroneSetupByHandTest`. (The bench's drone forces 6, as its battery: not seen there.)

**F-089 - A Betaflight 4.4 or 4.5 built without a GPS was taken to have switched it on.** Those versions answer
"feature GPS" with "unavailable", small letters, on its own line (cli.c); the app knew only 2025.12's "Unavailable GPS"
at the very start, so CONFIRM went on and saved the rest. Found by the reader of the board restore's fix. **Mended
(PC):** the word on any line of the answer, in any case, is a refusal: the write stops, nothing is saved, and the GPS
row says "Not in this Betaflight (built without GPS). Answer No." Tests: `BetaflightTest` (red before),
`FakeFlightBoardTest`.

**F-090 - At 200 % text scaling the CONTROLLER card's sticks were dots and its channel list was cut.** Seen
2026-11-24 in the bench's looks at 200 % on this 1920 x 1200 screen (960 x 564; `runs\1124-0554-ui`,
`looks-random-presser`, `looks-full-size`): with the card open, each stick was the red dot in a 36 px square - no
circle, no travel - and the channel list beside them was 96 px wide: THROTTLE half there, the AUX rows gone, each bar
a red tick over the last digit of its number. Whole at 100 %, 125 % and 150 %. The card has about 554 x 86 px there
for the sticks and the list (F-010 leaves the sticks 86 px); the sticks took 440 of the width as always. The ticks: the
bar's track is the list's width less the name and number columns, and 96 px is less than the name column alone (78 px,
142 with Drone setup's AUX names), so the track came out negative and the bar's 3 px least width drew in the number's
column. **Mended (PC):** the channel list keeps the width its rows need (the name, a bar of 40 px at least, the
number) and scrolls when it has more rows than height; the sticks get the rest, half at least. A stick box under 72 px
with its words under it puts the name over the box and the values beside it, with a smaller dot, when the dot travels
further so: at 960 x 564 a 63 px box with 20 px of travel each way. A track too short for a bar gets none, a bar stays
in its track, a name is cut before its number. At 1280 x 752 and up the card is laid out and painted as before, pixel
for pixel. Test: `ControllerSticksTest` (3; its narrow-list check is red on the old `ChannelBars`). Seen again on the
bench: 200 % with the circle and travel (`runs\1124-0645-ui`), 150 % as before (`1124-0649-ui`), calibration 1 of 1.

**F-091 - At some widths a picture shown at its own size came out wrong or ended the app.** Found 2026-11-24 by the
bench's new decoder fuzz (`gradlew decoderFuzz`: 5 minutes, 70,086 made-up pictures and clips, two ends of the child
process, both again when replayed alone), then seen with clean 360 x 640 and 1080 x 1920 streams. FFmpeg turns a
picture into screen pixels at its own size 16 pixels at a time and expects its own picture layout; the app gave it
rows packed tight. Widths that are multiples of 16 (320, 640, 1280, 1920), scaled pictures and stills were fine. Where
the app converts at a picture's own size: the OSD recording of a stream 720 or more high (a portrait 1080 x 1920), an
overwatch preview, the live view or a clip when the view is exactly the picture's size. What a pilot could have seen:
the app closing by itself with no message, e.g. while recording a portrait stream with the OSD; at 854 wide a thin
strip at the right edge. Not seen in flight. **Mended (PC):** the picture is converted into FFmpeg's own layout - rows
aligned to 64 bytes, room after the last - and copied out row by row; the still the same, the encoders' input with the
same room. Conversion time unchanged (1920 x 1080: 0.81 - 0.84 ms before, 0.78 - 0.79 after). Test:
`ConvertBufferTest` (2; red before: 32 of 96 and 188 of 896 conversions). The fuzz's finding replayed: the process
ended before, runs through now; 5 minutes of the fuzz on the fix: 74,785 inputs, nothing.

**F-092 - A damaged stream's picture of an absurd shape ended the app when scaled to the view.** Found 2026-11-24 by
the decoder fuzz's 4-hour run on the F-091 fix (`fuzz\20261124-093603-seed1`, two ends of the child process in 26
minutes, both again when replayed alone): a damaged H.264 stream whose first picture came out 128 x 131072, converted
to 320 x 180. Not new with F-091: the code before it ended the same way. FFmpeg makes a scale of about 64 times or more
in a direction in two steps of its own, through a picture of the in-between size (here 202 x 4857); with the colour
matrix the app sets (BT.709 for pictures 720 or more high) it took a further way there that ended the process. Where
the app could meet it: the live view and the observation tiles, which take their size from what the phone announced,
not from the picture; the STANAG feed fitting such a picture into 1280 x 720. The overwatch preview and the OSD
recording convert at the picture's own size and never met it. What a pilot could have seen: the app closing by itself,
with no message, as a damaged stream started; only a damaged stream makes such a picture. Not seen in flight.
**Mended (PC):** a picture more than 32 times the view's size in a direction is sampled point by point, which FFmpeg
does in one step; every other conversion as before. Test: `ConvertFarTest` (2, each in a JVM of its own: the fuzz's
picture to 320 x 180, 1536 x 864 and 2 x 720 - the JVM ended before, converts now; the app's scaler for 5 picture
kinds, 8 picture and 10 view sizes while listening to FFmpeg - 102 two-step conversions before, none now). Both
findings replayed: the process ended before, runs through now; 5 minutes of the fuzz (seed 1) on the fix: 45,443
inputs, nothing.

**F-093 - A damaged stream's oversized picture stopped the overwatch cards.** Found 2026-11-24 by the decoder fuzz's
4-hour run (`fuzz\20261124-100413-seed2`, an error at input 496,635 on the overwatch preview's path, no crash):
parameter sets of a 640 x 360 stream with a few bits changed made every picture 640 x 196480. The decoder handed it
out; the overwatch card's copy of it is 479 MB, made eight times a second, and the fuzz's child (512 MB for Java) could
not make room for it. The app is given a quarter of the PC's memory (2 GB on an 8 GB PC): where the card's copy did not
fit, the overwatch hub's thread ended, and every overwatch card stood still - no pictures, alerts or clips - until the
app was restarted; the live video, the link and the sticks went on. With more memory the copies fit but came eight a
second. The OSD recording stops only its own file; the live view, the STANAG feed and the clip player convert to sizes
of their own. What a pilot could have seen: the overwatch cards standing still after one phone's preview went bad. Not
seen in flight. **Mended (PC):** every decoder tells FFmpeg the most pixels a picture may have, 4096 x 4096 (the phone
sends 1920 x 1080 at most); a bigger one is left out before any room is made for it, the log says so once ("Video: a
picture of 640 x 196480 left out - larger than any phone sends"), and the stream goes on at its next good picture.
Tests: `OversizedPictureTest` (live, one-thread and GPU decoders: no picture, one log line, then the next good key
frame - red before), `OverwatchVideoTest` (the same through the overwatch hub - before, it ran out of room making the
card's picture). The fuzz's input replayed: runs through, 108 MB at most (693 before); 5 minutes of the fuzz (seed 2)
on the fix: 44,494 inputs, nothing.

**F-094 - The phone app's main screen cut lines on its side and at large text.** Found 2026-11-24 looking at the
phone app's screens in the emulator (Android 14, 1080 x 2400) at Android's text sizes 100, 130 and 200 % and display
sizes default and largest, upright and on its side (`wm size 2400x1080`: this emulator does not turn). The screen did
not scroll. On its side at normal text the status card's last three lines (Install unknown apps for the PC's updates,
the phone's GPS, props off) were not drawn and could not be reached - on a phone with more notices, more. Upright at
200 % the status card took the whole height: the connection card (STOP, HOLD TO WAKE, Advanced: port and key) got
none, and the link's pill (WAITING / LINK READY) was laid out past the top row's right edge. 130 % was whole. Not a
flight problem: the PC shows the same states. **Mended (phone, pc.60-beta):** the two cards sit in one ScrollView with
`fillViewport` - when they fit they are laid out as before (every control at the same place at 100 %), when they do
not the page scrolls; in the top row the name (and FLIGHT UNIT on its side) gives way with "..." so LOCK SCREEN and the
pill keep their room. Checked: the same pictures and screen dumps on pc.60 - every line and button reachable in all
eight cases; at 200 % with the largest display size the pill is still cut at the right edge (the rest whole).

**The map tools against the outside world (2026-11-24, F-095 - F-101).** Plan section 7 beyond the map suites and
`mapProbe`: each tool fed real-world input or read back by a reader that is not the app's own. STANAG 4609: the
stream read back with a byte-level transport stream reader, FFmpeg's own demuxer and H.264 decoder and an ST 0601
reader written for the test (`StanagReadBackTest`): tables, KLV as stream type 0x06 with 'KLVA', one whole ST 0601
packet a PES, keys, BER and item lengths, tag 1's checksum, rising tag 2, positions south and west within the
standard's precision, 90 of 90 pictures decoded, each KLV packet with its own picture's PTS, a player joining
mid-stream - all right. Open: FFmpeg 7.1 marks the KLV PES as stream id 0xFC (metadata stream); a reader that goes by
that id rather than the stream table could miss it - to be tried with a real ground station (nothing on this PC).
NOTAM paste: about 25 real-world shapes; no crash, times right as UTC; found **F-095** (no-break, thin and zero-width
spaces from a web page: the NOTAM left out, or its E) area lost to the Q) circle), **F-096** (radius before its word,
or in feet: the Q) circle drawn instead - 500 m as 5.5 km) and **F-097** (ICAO's brackets in the ceiling and the
text). Not fixed, small: a NOTAM across 180 degrees is drawn across the world; a D) schedule is text only; "F)GND"
with no space leaves F) and G) in the text; a NOTAMR and the NOTAM it replaces, pasted together, are both kept. KML:
the app's output read with the JDK's XML reader against KML 2.2's element order (`KmlStandardTest`) - **F-098**
(ExtendedData before Style); imports shaped like Google Earth Pro, My Maps and QGIS with BOM, CRLF, UTF-16 and
ISO-8859-1 (`KmlFromOtherProgramsTest`) - **F-099** (spaces after a tuple's commas dropped every point); 5,000 areas
of 70 points read in 0.3 s and 74 MB, painted in 260 - 280 ms with all in view (proposal I-016); not read: KMZ,
every part of a MultiGeometry after the first, gx:Track, shared styles (proposal I-015). Radio horizon: the 4/3-earth
figure within 0.04 % of 4.12 x (root h1 + root h2), the path losses at the textbook's figures; real terrain tiles at
known places (Galdhopiggen 2,441 m at the point, 2,454 m within 150 m, 2,469 known; Mjosa 123 m; the fjords 0) -
**F-101** (all ground above 3,276.7 m cut flat there: heights in decimetres in a short; now half-metres). Out at
sea the tiles give the sea floor (-305 m off Jaeren): question Q-011. DOWNLOAD AREA: one real download, 13 Kartverket
topo tiles over Aker Brygge, levels 12 - 16, 1.0 s, all whole, drawn from disk with no network - **F-100** (PAUSE had
no action). Small: the size shown assumes 20 KB a tile, town tiles were 86 - 111 KB. Fast tests after: 2,555 of 2,555.

**A disk that really runs full (2026-11-24, F-102 - F-108).** Plan item "nothing lost - disk full", until now only
read in the code and unit-tested. The bench's new `DiskFullScenario` (ui suite, `SmallDisk`: a 64 or 16 MB tmpfs in
WSL mounted with `wsl -u root`, no Windows admin; the app's Documents and Videos on it through the rig's new
`homeOn` / `appDataOn`; unmounted at the end) flies a made-up drone with REC on, the track, pins and drawings, lets
the disk run full, frees it and goes on; also the settings folder on a full disk, and an overwatch alert. Writes
really fail there (Java: "There is not enough space on the disk"; FFmpeg: "No space left on device"); Windows reports
about 1 TB free on it, so LOW DISK and the overwatch's 2 GB floor cannot show (unit tests cover them). Full in
flight: no crash, the window's slowest answer 230 ms (frozen is 500), the longest gap between stick frames 38 ms,
18.7 of 18.6 pictures a second shown, the header LINK READY and RC OUTPUT LIVE throughout, the drone following the
sticks; nothing saved before was damaged (pins.txt, graphics.kml, the recordings begun before play back); TRACK /
PINS / MAP DRAWINGS NOT SAVED, then "saved again" with room; the flight in PREVIOUS FLIGHTS. Found and mended:
**F-102** an older settings file on a full disk stopped the app from opening (the upgrade's write ended the start);
**F-103** rubbish after FFmpeg's reason (its buffer read past the words); **F-104** a half point and no end in the
GPX; **F-105** the recording stopped without a word in the window (now "RECORDING STOPPED: ... - VIDEO > RECORD
VIDEO off and on to record again"); **F-106** three empty files a try, named in the flight's record; **F-107**
"IOException:" in the pilot's words; **F-108** the overwatch clip's line every 5 s. Proposal I-017: the recording
starts again by itself when there is room (today it stays off for the connection). Runs: `1124-1600-ui` (4 of 4
with F-102 - F-104), `1124-1631-ui` (4 of 4 with F-105 - F-108: RECORDING STOPPED once, 0 empty files - 15 before -,
no "IOException:", the clip line once). Fast tests after: 2,565 of 2,565. With the pilot's I-017 (recording again by
itself when there is room): `1124-1811-ui` 4 of 4 - RECORDING STOPPED and RECORDING AGAIN each said once, again 14 s
after room, the new part playing with its OSD copy and figures and named in the flight's record.

**The link's packets fuzzed, both ways (2026-11-24, F-109 - F-111).** `gradlew protocolFuzz` (the decoder fuzz's
frame: a child JVM at the lowest priority, findings replayed alone, results under `fuzz\`): valid streams of every
kind made by the apps' own senders - Connect and its answer, config, StartVideo, sticks in every layout, audio,
video fragments with parity, telemetry, detection areas, power modes, overwatch, update offers, names, fleet modes,
observers, spectators, sleep check-ins - lost, doubled, replayed, reordered, late, cut, spliced, bit-damaged, with
edge values in lengths, counts and sequence numbers, fed to the PC's real receive and buffer threads, frame assembler
and readers, and to the phone's receive rules (its session gate, buffer, sticks, gates and readers, in its order).
Checked: no session without the key, a refused packet changes nothing, no numbered packet handed on twice, no stick
frame taken that is not newer or past its lag limit, whole frames as sent, values in range, budgets kept, no throw.
Runs: `20261124-172200-protocol-seed1` (3 min, 862,171 inputs), `20261124-172813-protocol-seed2` (30 min, 9,876,556
inputs, 5,485 a second). Found: **F-109** a numbered packet of 1 - 2 bytes, or a Connect cut inside its key, threw on
the receive thread (both apps); **F-110** the PC counted a Connect answer before its check, so a damaged one could
leave it at "reconnecting" until a restart; **F-111** (below) after a short fade the PC's resends of
commands the phone already took push the phone's buffer to start over and hand them on again - StartVideo or config
again (the picture black about 2 s), ChangeCamera again (the other camera), RECORD again; the PC's buffer the same for
the phone's VideoInitialFrame. Since then an answer that says it took the session by the Connect's id counts only
for an id among this PC's last eight tries, and the fuzz checks that too. Not covered: the phone's Android code itself (a model of its receive thread runs),
the state machines of spectators, mirror, observer, names and fleet (only their readers), threads racing each other.
The link has no integrity check of its own beyond UDP's and WireGuard's: a damaged mode byte is another command.

**F-113 - After a wake the phone app stays in front of the lock screen (2026-11-27, the bench's fifth night).** When
the PC wakes the phone (PILOT UAV), or the app opens itself again, its screen comes over the lock screen with the
display lit, for that one showing; once that screen is left, the lock screen is in front again (since 2026-11-22). In
round 12 of the night's 19 with the phone app in the emulator, the phone showed its lock screen instead of the app;
the link, the picture and the wake were as in every other round. Android's own log of that wake (the emulator's
events buffer): the screen resumed while the display was still dark, the display lit 16 ms later, and Android
stopped the screen once for the sleep 45 ms after the resume. The app took the resume for the showing, so that stop
ended it: show-when-locked was cleared, and the lock screen came back in front until the next sleep. In the bench's
phone logs since 2026-11-22, 35 of 254 wakes ended their showing within half a second (Android 8 to 15, and the real
phone). In the night's 18 good wakes the screen got the input ("Focus entering ... MainActivity"); in round 12's it
never did. Now the showing counts only once the screen's window has the input (`onWindowFocusChanged`), not at its
resume; a stop before that keeps the flags (`WakeIntent.OverLock`, `MainActivity`; phone app pc.63-beta). Cost: a
screen that never gets the input (something of the system's over it) stays over the lock screen until a showing that
does is left, as every showing did before 2026-11-22. Tests:
`WakeIntentTest.aStopForTheSleepBeforeTheScreenHadTheInputKeepsTheShowing`, and its wiring test (fails on the code
before).

**F-112 - The phone's version answer counts only while the link connects (2026-11-25, a code read).** The phone sends
VersionMismatch in answer to a Config it can't take. The PC took it at any time, also with the link READY, and then
sent no stick frames and stopped asking until DISCONNECT. Now it counts only while this session's Config is not taken
yet; with the link up it changes nothing (one log line at most every 5 s). VERSION MISMATCH is no longer for good:
the handshake goes on every 5 s (the Config while the link is up, a Connect while it is down); an old phone app
answers each Config again, so VERSION MISMATCH and its words stay; a phone that takes the Config ends it and the link
comes up READY (`ControlLink`). Tests: `ControlLinkTest.aVersionAnswerWhileTheLinkIsUpChangesNothing`,
`...aPhoneThatCantServeThisVersionIsAskedAgainEveryFiveSeconds`,
`...oneVersionAnswerWhileConnectingThenTheConfigTakenBringsTheLinkUp` (the first and third fail on the code before).
**F-111 - A packet sent again is acted on once (2026-11-24 - 26, the link fuzz; approved 2026-11-26).** On a link that
loses the small receipts, the sender sends a packet again up to 8 times, about 125 ms apart. The receiving buffer
(Common's `ReceiverBuffer`, both apps) counted each copy as a sign the two sides' numbers had drifted, started its
numbering over at the ninth and handed the packet on a second time. Now it remembers the last 256 packets it handed
on, in the order taken (length and CRC32 in fixed arrays, nothing made per packet): a copy is acknowledged again,
never handed on again, and does not count toward starting over; only a different packet under an old number does,
mended at the ninth as before. A new session forgets the list. The first version kept each packet in the slot of
its number's low byte; the 4-hour fuzz found a start-over 256 numbers away taking that slot (only with damaged
packets), so the list is in the order taken. Cost: if a phone really starts its numbering over and the PC does not
see it as a new session, a packet byte for byte the same as one of the last 256 taken is acknowledged but not acted
on (before, such first packets were dropped too). Tests:
`TransportRobustnessTest.theFarSidesResendsOfPacketsTakenAreAcknowledgedAndNotHandedOnAgain`,
`...aRealRestartOfTheFarSidesNumberingIsStillMendedAtTheNinthPacket`,
`...aPacketTakenBeforeAStartOverIsStillKnownAfterANumber256ApartIsTaken` (the first and third fail on the code
before). Runs: the link fuzz 30 min, then 4 h on the newest code (`20261125-073100-protocol-seed7`, 108 million
inputs, no rule broken); the phone app pc.62-beta: the emulator set 12 of 12 and the fourth bench night, all passed.
**S-LINK-2 - The PC listens on its Tailscale address only (2026-11-25 - 26, a code read; built on the maintainer's
answer 2026-11-26).** Since pc.58-beta the phone app talks to Tailscale addresses only (Q-006); the PC still opened
its sockets for phones and other PCs on every address it had. Now each is opened by its peer (`LinkPlace`): a phone
or PC on Tailscale on this PC's own Tailscale address, found by the adapter that says Tailscale (`OwnTailscale`, a
look every 500 ms, 2 - 4 ms each), the PC's own peers (made-up drones, DEMO, the bench, an emulator's forwarded
port) on loopback, any other address refused ("Not a Tailscale address"). With no Tailscale address a socket is
parked on loopback, takes and sends nothing, and the header says NO TAILSCALE; when the address comes, changes or
goes, the socket is opened anew where it belongs and a waiting receive goes on on the new one (`PeerSocket`, on
`LinkSocket`). Kept to one address by Windows' strong host model (not with Internet Connection Sharing, Hyper-V's
forwarding, or the PC as a Tailscale subnet router or exit node). Left on every address: the phone app's download
page (TCP, the public APK) and the STANAG output. Tests: `LinkPlaceTest` (a socket bound to 127.0.0.1 takes nothing
sent to 127.0.0.2; parked, comes, changes, goes; ports kept), `OwnTailscaleTest`, `TailscaleLinkTest` (the link
waits, starts and follows; a LAN peer gets the words and holds no port), `PcSocketsSourcesTest` (no socket in the
app's code opened outside `LinkPlace` but the fleet's, the encoder's and the download page's),
`NoTailscaleWordsTest`. Fast tests 2,629 of 2,629; the link fuzz 30 min (`20261126-142144-protocol-seed11`,
9,347,632 inputs) passed.

## Fixed (2026-11-19, on `beta`)

**F-003 - Switching STANAG 4609 STREAMS off froze the window for 1.3 to 2.6 s.** The switch waited on the window's
own thread for each stream's thread to end. Now the streams stop sending at once and are waited for on a thread of
their own (`StanagController.closer`); only the app's exit still waits. Test: `MenusScenario.switchingTheStanag...`.

**F-004 - "ARM SWITCH NOT READ - RUN DRONE SETUP" stood on the OSD also while armed and flying.** Now only while
disarmed (`OsdRenderer`).

**F-005 - The mini-map's banner was cut off on the left and lay over the scale bar.** The line now takes what fits
beside the scale bar: all of it, or without the reason, or the map's attribution alone (`MapView.attributionThatFits`,
`MapAttributionTest`). On a map so low
that the zoom buttons reach its bottom line (the mini-map in the smallest window) the line ends left of them.

**F-010 - In a small window with the controller card expanded, five of the video's seven buttons could not be
reached.** 1093 x 584 (a 1366 x 768 laptop at 125 %), EXPAND pressed: the picture shrank to a stamp of 160 x 80, its
button column to two and a half buttons, and "NO VIDEO FEED" lay over "Not connected". The open card now leaves the
video the height of its button column - the sticks are drawn smaller instead - and the two lines keep apart on a
small picture. Seen in the window at that size (`.\bench.ps1 ui -Tests *Looks*`, 10:50). Its rest is under "To
judge".

**F-007 - After a drone was marked OUT OF ACTION, PILOT UAV still meant that drone, though the PHONE list showed
another.** The chosen drone now stays in the PHONE list, marked OUT OF ACTION, until the pilot picks another: the
list always shows the drone PILOT UAV means, and the question "is OUT OF ACTION. Clear the mark and connect?" is
about the drone on the screen. (Other OUT OF ACTION drones are off the list as before.) Test: `FleetScenario`.

**F-009 - One byte in the settings file that is not UTF-8 and the app did not start, without a word.**
`pcName=Bjørn` saved by Notepad as ANSI was enough. The file is now read whatever wrote it - UTF-8, else Windows'
own code page; a UTF-8 mark at its start is no part of the first key - and is UTF-8 again after the next save
(`PcConfig.readLines`, `PcConfigTest`).

**F-011 - "Phone address not on your Tailscale network. Run SETUP" for a VIRTUAL phone** (DEMO, the tour, the
virtual fleet). A virtual phone is no longer looked for on Tailscale.

**F-012 - "This PC is PCAlpha (BENCH was taken)."** A name that is none of the app's is now said to be "not a PC
name".

**F-013 - "Play" in PREVIOUS FLIGHTS was the only button not in capitals.** PLAY / PAUSE.

**F-014 - FLEET cut "MobileYankee" to "MobileYank."** The name has the queued sign's room while nothing is queued,
and is set a little smaller before it is cut.

**F-015 - Every flight stayed on the map, in memory and in the settings file until the pilot took it off.** The long
run, 2026-11-19: after 549 flights the settings file was 110 KB (one line, 200 bytes a flight, written anew at every
save), the mini-map drew 549 earlier paths with their labels on each other, and memory in use had grown evenly from
37 to 78 MB. The map now keeps the 30 newest earlier flights (`MainWindow.MAX_KEPT_FLIGHTS`); older ones leave the
map, their files stay, and PREVIOUS FLIGHTS > ON MAP brings any of them back. 30 is a first guess - the pilot's to
change. Shown by a long run after the fix (1 h, 190 flights, 2026-11-19 10:54): memory level at 48 to 49 MB from the
20th flight to the last, the settings file 7 KB.

**F-016 - A UTM position just north of the equator, or a grid on the date line, was not read by GO TO and "Place
at...".** Found by writing every format for 20,000 places all over the world and reading each back
(`CoordinatesWorldTest`, 2026-11-19 afternoon): "38N 551248 E 93052 N" - less than 100 km north of the equator the
northing has fewer than six digits, and six were asked for; and a grid of exactly 180 degrees came back a hair past
it and was thrown out as no place. Both are read now (`Coordinates`). MGRS, decimal and degrees-minutes-seconds read
back everywhere between 80 S and 84 N.

**F-017 - FIND DRONE without a position said "No position yet." twice**, once in white and again at the start of
the yellow line under it. Seen on a picture of the random presser. The yellow line is now "Last flight's:
last-position.txt in Tracks." alone.

**F-018 - On a small screen FIND DRONE's buttons were below the screen's edge.** The window took the height it
wished (696) whatever the screen had: on a 1366 x 768 laptop at 125 % (584 high above the taskbar) COPY POSITION,
GOOGLE MAPS and CLOSE could not be reached, and on a 1920 x 1080 one at 150 % (680) their lower edge was cut. Found
when the picture tours ran at 200 % text scaling. The window now fits the screen it opens on; its map gives way.
The bench's small-window scenarios now run on a made-up screen of that size (`.\bench.ps1 ui -Screen 1093x584` for
every scenario), so a window that does not fit is remarked.

**F-020 - With a phone asleep, the LINK card of a small window read "STAN...", "DEEP SLEEP", "OVER...".** STANDBY
joins DEEP SLEEP and OVERWATCH for a sleeping phone, and three buttons did not fit the card of a 1093-wide window.
The room beside the words is cut first, then the words are set smaller, all three alike (`MainWindow.fitSleepButtons`).
Found by the random presser.

**F-021 - MAP TOOLS > TOOLS > AIRSPACE: the line that says why nothing shows was cut in half.** "R 0, D 0, NOTAM 0"
stood whole, "no openAIP key (Key...)" under it showed its upper half only: the row's height was fixed for one line
when the list was built. The line now is as high as its words and wraps at the list's width. The tools' other
status lines wrapped 20 px too late ("Loading forest map 400 / 678" lost its last figure at the list's edge):
Swing draws its HTML's "px" 1.3 wide; they wrap in time now.

**F-022 - GCS SEARCH with a box drawn on a zoomed-out map said "Loading terrain..." without end.** A box 400 km
across asked for the terrain of half a country - on the real internet a download of that size - for an area no mast
can see all of (a mast's range is 30 km). A box whose corners are more than 30 km from its centre now gets "Area
too big - 60 km across at most" and loads nothing.

**F-023 - COVERAGE's network list read "All ne...".** "All networks" did not fit its drop-down. It reads "All".

**F-024 - The first REPORT or EXPORT of a flight opened its save dialog in plain Documents**, not in
Documents\DRAUPNE Link\Exports as the map's own EXPORT does: the folder was not there yet, and a dialog pointed at
a folder that is not there opens one level up. The folder is made first now (`AppPaths.exportsDir`).

**F-025 - One wrong number in the settings file - `cameraWidth=wide` - and PILOT UAV waited for the phone for
ever, said nothing, and filled the disk.** The link's thread failed at the same line at every turn, before the
turn's own wait: 418,940 times "Session thread error" in ten seconds, 64 MB of log (6 MB a second for as long as
the window said WAITING FOR PHONE). The same slip in `sharpWidth` also broke the VIDEO menu. Now: the link's
numbers (picture sizes, bitrates, telemetry rate, baud rate) fall back to the built-in value and the window's log
says so once ("Settings file: cameraWidth=wide is not a number. Using 1280."); and the link's thread, whatever
fails in it, says a repeating error once in 5 s and waits a turn (`PcConfig.linkInt`, `PcConfigTest`,
`ControlLink.sessionRun`).

**F-026 - GO TO said "Unreadable position." once more for every position it could not read**: "Unreadable
position. Unreadable position. MGRS (...)" after the second, and so on, the dialog growing wider. Said once now.

**F-027 - A phone put to DEEP SLEEP from this PC was "flown by another PC" for the next half minute.** OVERWATCH or
STANDBY pressed in that time: "NOT NOW - MobileYankee is flown by another PC." There was no other PC. While this PC
flew it the phone's answers said "a pilot flies me", and the last of them was kept for 30 s - a sleeping phone sends
no later one to take it back. What the phone says of this PC's own link is no longer filed as another PC's
(`FleetManager.onReply`, `FleetManagerTest`). Found by pressing OVERWATCH ten seconds after DEEP SLEEP.

**F-028 - The ruler's figures lay on each other when its legs were short on the screen.** A triangle of 100 px
legs read "362.93 KM 333° KM 211°", and a leg's figure lay under the yellow TOTAL. A leg's figure that would lie on
the total or on another is left out now, as that of a leg too short always was; zoomed in, each is there again.

**F-029 - The 27th phone got no name, and no word said so.** The name map keeps every phone this PC ever saw, so
26 names are used up by phones over time, not only by phones at once; a new phone then was simply never named and
never came into the PHONE list. Read in the code while looking for what "26 and more phones" does (the made-up
fleet stops at 12, so the window was not tried). The log now says, once a phone: "Names: no name left for the phone
at ... - all 26 are taken. Remove one no longer used: MENU > LINK > Name map."

**F-030 - A save dialog holds the window for 0.3 to 0.6 s as it opens. Not a bug of the app, and not changed.**
REPORT's "Save report", the map's EXPORT: Windows is asked for its folders and their pictures and the dialog's
window is made, on the window's own thread. Seven openings measured 250 to 650 ms. Asking Windows ahead as the app
starts was tried and did not shorten it reliably, so it was taken out again. The bench's rule - no answer for half
a second fails a scenario - now lets the opening of a file dialog pass (up to 1.5 s).

**F-031 - "Report saved:" with a long folder path was wider than a small screen** (1208 px on 1093): its right end
and OK off the screen. The file's name and its folder stand on two lines now, and no message box is ever wider or
higher than the screen it is on (`Dialogs.fit`). Also: the save dialog's side bar read "Recent Ite..."; its buttons
are wider.

**F-032 - STANAG 4609's window was 700 high whatever the screen**: on a 584-high one SAVE and CLOSE were below its
edge. It fits the screen now; the list of streams gives way.

**F-033 - The map could stay "LOADING MAP" for the rest of the session with the internet working** (the pilot,
2026-11-19: "the map is stuck loading even though the computer has internet"). Found by a server on this PC that
does what a network does on a bad day (`TroubledServer`, `TileCacheBadNetworkTest`: six of ten tests failed on the
code as it was) and seen fixed in the real window (`ui/MapNetworkScenario`). Five things, each enough by itself:

- *A tile whose transfer stopped half way held its loader for ever.* The time limit of a request ended when the
  answer's head had come; half a tile and then nothing - a weak link, a PC going from Wi-Fi to 4G, a VPN coming up,
  a PC woken from sleep - was waited for without end. The map has two loaders: two such tiles, and no tile loaded
  again until the app was started anew, LOADING MAP turning all the while. Now every fetch has a limit for the whole
  of it (12 s for the head, 20 s in all) and is given up after it.
- *A tile that failed was not asked for again unless the pilot moved the map* - and then not for a minute. The app
  started before the PC's network was up: every tile on the screen failed at once and the map sat blank on a
  working internet. Now a failed tile is asked for again after 2 s, then 4, 8 ... a minute at most, by itself (the
  maps that show it are called), and when a server answers again every waiting tile is asked for at once.
- *A connection that had gone dead was used again.* Every tile shared one HTTP/2 connection; after a change of
  network it could be dead without a word and each tile timed out on it. Tiles are fetched over HTTP/1.1 now (the
  four map servers were asked and answer so), and after three misses in a row the connections are made anew.
- *A tile left "loading" by an unexpected failure was never asked for again*, and one broken file on disk that made
  the picture reader fail outright was never fetched anew. Both are.
- *"SAVED TILES ONLY" stayed for the rest of the session* after one miss when everything shown was on disk. It goes
  two and a half minutes after the last miss.

The map now says "NO MAP SERVER - RETRYING" where LOADING MAP stands while tiles are missing and its server is not
reached (the words are the pilot's to change), and the app's log says when the server is lost and when it answers
again, with the reason - what was missing to tell these apart afterwards. The same half-way stall held the terrain heights, the airspace and the cell lookups
(one thread each, for good) and the three downloads (the phone app, Tailscale, the update: "Downloading" for ever):
all have a limit now (`Net.send`, `Net.noStall`, `NetTest`). What the map says when it loads nothing *on purpose* is
unchanged: "SAVED TILES ONLY - ... is armed" while a drone here may be flying, for up to ten minutes after a link
lost armed, until IT'S DOWN - RESUME.

**F-034 - CALIBRATE's first step ticked an axis the count beside it did not count.** The tick beside a bar went by
the bar's range alone; "Full range: 3 of 4 stick axes" by the range and a smooth travel - so a switch flipped in
that step got its tick, and four ticks could stand beside "3 of 4". The tick follows the count now
(`RawAxesView.setSwept`, `RawAxesViewTest`). Met when the made-up radio first jumped its sticks from corner to
corner: four ticks, "0 of 4".

**F-035 - The line under an OBSERVATION tile was cut: "640x360 15 FPS | heading approxi...".** With three or four
tiles in a 1280-wide window. The note is "heading approx." now and its divider narrower. Still cut in tiles narrower
than that.

**F-036 - CALIBRATE's last step: with four AUX switches the INVERT box of aux4 was off the window's edge** at 1280
wide - seven boxes showed for eight channels, and a channel that ran backwards could not be inverted there. The row
wraps now. Found by walking all eleven steps with the made-up radio (`ui/CalibrationScenario`).

**F-037 - With the ARM switch left on, the controller card said "LOWER THROTTLE TO ENABLE" - with the throttle
down.** The sticks wait for the throttle down and the ARM switch off; the card's line named the throttle whatever
they waited for, so a pilot who had left the switch on lowered a throttle that was low and nothing happened. It
names what is waited for now: "ARM SWITCH OFF TO ENABLE", "THROTTLE DOWN, ARM OFF", "READING FC" ...
(`MainWindow.rcOffLine`, `LinkCardTest`). Found by the first scenario that flies as a pilot who has been through
Drone setup (`ui/FlyScenario`, the ARM switch known): every scenario before it flew with "ARM SWITCH NOT READ".

**F-038 - A map that waits on purpose looked like a broken one.** For ten minutes after a link that last said
ARMED went quiet - a crash, a battery pulled before disarming, a phone that died - this PC fetches nothing (a drone
here may be flying: the pilot's rule of 1.0.49). The LINK card says so in yellow and offers IT'S DOWN - RESUME; the
map itself was dark squares with one small grey line at its lower edge ("SAVED TILES ONLY - MobileYankee was lost
armed 20 s ago ..."). Very likely part of "stuck loading even though the computer has internet". The rule is
unchanged; the map now says **MAP WAITS - A DRONE MAY BE FLYING** where LOADING MAP stands, while tiles are missing
and it holds back - in a flight over a place it has no tiles of, too. Scenario: `ui/LostArmedScenario` (a drone
lost in the air; nothing is asked of the internet until IT'S DOWN - RESUME, then the map fills by itself); fast test
`MapViewTest`.

**F-039 - The phone app's sending could stand still for good, and the link with it (phone app; seen in the emulator
only).** Everything the phone sends - telemetry, video, sound, its answers to the PC's Connect - goes through one
socket, and a send waits while that socket's send buffer is full. The emulator's Wi-Fi keeps a little of what is sent
and never lets go of it: after a minute and a half of flying the buffer (66 KB) was full and stayed full. From then on
all four sender threads of the app stood in their send - eight minutes later they still did - while the phone heard
the PC and began a new session at every Connect it could not answer (333 of them). The PC said "Waiting for the
phone..." until the phone app was ended; on the phone nothing showed. Found by the first scenario that flies the real
app for longer than a minute (`night/PhoneNightScenario`); the threads' places and the socket's count are in
`runs\1119-1544-night`.
Whether a real phone's network can do this to a socket is not known - it was not seen on one, and a healthy phone
frees what it has sent. But a link that depends on one socket never jamming, with nothing but ending the app to get
it back, is the app's own weakness, and no pilot can end the app in the air. **Fixed in the phone app (joins the
pending beta, pc.54-beta; the release takes pc.55):** the link's socket can be opened anew under everything that uses
it (`LinkSocket`): when nothing has left it for 5 s though packets from the PC arrive, it is closed - which lets
every waiting send go - and opened on the same port; the session, the camera and the port stay (`SendWatch`: only
while the PC is heard, so no coverage at all is waited out as before). Slow on purpose: heard-but-mute is also what
a weak uplink at range looks like, and there a new socket lets one more socket's worth of old video (30 packets)
queue up in front - so five seconds before the first, and half a minute before the next if it did not help.
Measured in the emulator with the fix while its wait was still 3 s (`.\bench.ps1 emulator -Tests *PhoneSocket*`,
`runs\1119-1617-emulator`): the buffer filled twice in 150 s, the phone opened its socket anew both times, and the
PC had its link back 1.3 s after it said "Link lost" (about 4.5 s without a word from the phone in all) - before:
never. **Measured again with the 5 s wait and the reviews' fixes (F-041) in the app built at 22:36**
(`runs\1119-2259-emulator`): flown for ten minutes, the phone made to scan for Wi-Fi from the third minute on; the
socket filled once (66,816 bytes waiting), the phone said "nothing left the phone for 5 s though the PC is heard -
opened anew", the PC had its link back 4.9 s after it lost it (3.7 s without it at the longest), and nothing waited
on the new socket.
Fast tests: `LinkSocketTest`
(9: waiting sends are let go, a waiting receive carries on with the new socket, a slow uplink that moves is not
"standing still", a port that cannot be bound is tried again), `SendWatchTest` (8). **Not tried on a real phone.**

**F-040 - After a minute without its server the map stayed empty for up to a minute after the internet was back.**
Found by the new long scenario for the pilot's own report (`night/MapNightScenario`: the app's tile cache against a
server that says nothing, sends half a tile, hangs up, says 503 or is gone, cycle after cycle). Never stuck - but
each missed tile waits twice as long before its next try, a minute at most, so after 62 s of 503s the map had its
tiles 59 s after the server was fine, after half a minute 32 s. And a server that answered 503 counted as
"reached", so its first good answer did not call the other tiles in. Now, while tiles miss and nothing comes from
the server, one tile is asked for every 8 s whatever the tiles' own waits; when it comes, the others are asked for
at once (their miss counts kept, so a server busy for some tiles is not asked for all of them again and again), and
nothing is asked for a map no window shows. Same scenario after the change (`runs\1119-1712-mapnight-try`): 0.6 s
after 62 s of 503s, 3.9 s after three minutes of trouble. What is left is the stalling kind: a request that hangs
keeps its loader for its 12 to 20 s time limit - 15 to 18 s was measured there, as before. Fast tests:
`TileCacheBadNetworkTest` (16; the two new ones: back within seconds after half a minute of 503s - failed before;
no request for a map nobody shows).

**F-041 - What a second pair of eyes found in today's own fixes (two code reviews by separate agents, 2026-11-19
evening, while the regression ran).** In the phone's socket fix (F-039): the port is not free the moment the old
socket is closed - every thread that stood in a send or a receive holds it until it has left - and five tries in
80 ms gave up too soon: the phone then had no socket, and heard no sticks, for a second (now tried every 5 ms for
a second); after a hole in the coverage the first packet heard reopened the socket at once though the way up would
have moved a moment later (now the PC must be heard through the last 3 s of the standstill); the wrapper's own
unused socket was never closed on Android - one descriptor lost at every deep-sleep check; a send after a long
quiet time could be taken for one that had stood all that time. In the map's fixes (F-033, F-040): every answer of
any server - a 503, or the terrain's server while the map's own hung up - threw all the tiles' waits away, so a
server in trouble could be asked without a pause (now only a tile that really comes calls the others in, and twice
as long passes before the next time); while fetches failed slowly one was always "on its way", so the map said
LOADING MAP for as long as the outage lasted - the reported symptom to the letter - instead of NO MAP SERVER -
RETRYING (now said first, also when the server answers but gives no tiles); the wake timers multiplied with two
maps waiting through an outage; renewing the connections broke off everybody else's request on them (an area
download's, the terrain a radio horizon was reading); the probe is asked every 8 s for five minutes, then every
32 s. Left as they are, noted: a tile's 20 s limit is for the whole tile, so a link slower than about 1.5 KB/s
never finishes one; the phone app's download through the PC has 15 minutes in all, Tailscale's 20. Tests:
`LinkSocketTest` 9, `SendWatchTest` 8, `TileCacheBadNetworkTest` 20.

## Fixed (2026-11-20, on `beta`)

Found in the first whole night run (`runs\1119-2309-night`, 23:09 - 06:16) and, while it ran, by four separate
readers: three of the code (the phone's link and power code, the PC's side of the link, everything the PC writes to
disk) and three of the window suite's 441 pictures. Each thing they named was looked up in the code or the pictures
before it was believed; what is fixed here has a test that fails without the fix.

**F-054 - UPDATE PHONE APP would stop at Android's "Update this app?" on the phone (a reader of the update code,
2026-11-20 evening).** Android 12 and later installs an app's update of itself without that question only when the
app holds the permission for it (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`, given at install) - the phone app did not.
So every remote update went as far as the download (some 23 MB over the mobile link) and the check, then put
Android's question up over the flight app, on a phone nobody can reach; the PC said "tap Update on the phone
(Android asks once)" - it asks every time. And the question stays up: after five minutes the PC lets the drone arm
again, and a stray touch on the screen in flight would install the update and end the app. **Mended in the phone
app:** the permission is in it. Also: the drone is asked once more for "disarmed" right before the install is
committed (copying the update into Android's installer takes seconds); the updated app opens itself again the way
it does after an error (a lit screen locked first, where the app may - its notification alone was only a
notification there, and the updated app stayed closed); the app's own update check no longer deletes the PC's
download under way; "open again after the update" is on the disk before the install ends the app. On the PC,
UPDATE is refused while a drone of this PC may still be flying (a link lost while armed leaves no connection, and
UPDATE then closed the app). **Not tried:** a remote update has never been run in the emulator or on a phone; the
first one with this build is the test. Android's question still comes on Android 11 and older, and where the
pilot never allowed "Install unknown apps" for the app. **Listed, not changed:** an old "failed" report taken as
the answer to a new UPDATE pressed within 30 s; the PC's own installer and a second copy of DRAUPNE Link running.

**F-055 - Finding a lost drone: what a reader of LOST DRONE, FIND DRONE and the last-known places found.** The
coordinates themselves (MGRS in Norway's and Svalbard's zones, the southern half, distance and bearing over the date
line and near the poles) looked sound. Mended, with tests:
- **An older fix could become the "last position".** Once the drone's GPS had said nothing for 5 s, the phone's own
  fix filled in - one up to 30 s old, or the mobile network's a kilometre off - and became the last point: FIND
  DRONE, the copied MGRS and last-position.txt sent the pilot to where the drone was half a minute before it came
  down. Now no point older than the last one is added. Test: `LastPositionTest.anOlderPhoneFixDoesNotBecomeTheLastPosition`.
- **last-position.txt is one file for all drones**, and a connection that never armed wrote it too: the next drone
  tried on the bench put its place over the lost one's. Now only a track that flew writes it.
  Test: `LastPositionTest.aConnectionThatNeverArmedDoesNotWriteOverTheLostDronesPosition`.
- **FIND DRONE, opened by LOST DRONE, followed whatever was connected next** - the drone connected to search with:
  its position replaced the lost one's, and Copy MGRS copied that. Now it stays on the lost drone (its name in the
  title) and takes the drone's own newer place once it checks in from where it came down (that went to the map's
  sign only; FIND DRONE kept the track's end). Test: `MapViewTest.findDroneTakesTheDronesOwnNewerPlace`.
**Listed, not changed (the map's drawing, for the pilot to look at with it):** after a loss the lost drone's named
sign can sit where it answered a name scan before take-off, while the real end of the track loses its LAST KNOWN
label; the LAST KNOWN label of a track follows the phone chosen in the PHONE list, not the track's drone; a rename
leaves the last-known place and OUT OF ACTION under the old name; track times on the monotonic clock and ages on
the wall clock (a stepped clock shows the wrong age); a check-in's fix age stops at 18 h; after a restart "From
launch" is measured from the file's first armed point; "possibly flying" is not kept across a restart and LOST DRONE
ends it at once (downloads resume while the drone may be on GPS rescue); FIND DRONE reads the disk on the window's
thread every second while it has nothing to show.

**F-052 - The PC's video: what a second reader found (2026-11-20 evening).** Mended, each with a test:
- **The whole app could end while an overwatch clip played.** A clip in several parts, a seek into another part,
  and that part's file not opening (deleted, held, rewritten meanwhile): the player of the last part was closed
  twice - and its decoder's close freed its colour converter and picture buffer again (the two native calls take
  the pointer itself, so the freed addresses stayed in the fields). A double free ends the program at once. Now a
  close is safe to call twice, and the panel no longer makes the second one.
  Test: `OverwatchVideoTest.aPlayerClosedTwiceFreesNothingTwice`.
- **A restart of the phone's stream while its frame counter stood high was taken for the counter wrapping** (any
  key frame 1 after a frame from 31,770 up - some 1 in 30 restarts): its key frame was dropped as "far ahead", the
  picture stood for three seconds and more. Now it is a wrap only when the phone can have numbered that far since.
  Test: `VideoTest.aRestartWhileTheCounterStandsHighIsARestart`.
- PREVIOUS FLIGHTS' player ended quietly on a picture it could not convert (the picture stood, sound and clock went
  on); now it says "Can't play the video". The recording's thread could end on an Error with "recording" left on.
**Listed, not changed:** a late frame of the old stream in the moment of a restart can hold the new picture for
about three seconds (narrow); a restart seen after a blackout can let a mostly empty key frame through right after
connecting; a slow disk can make the next flight's OSD copy end at once (one OSD recorder for all connections);
a full recording queue drops single frames and leaves the following ones smeared in the file until the next key
frame; switching RECORD VIDEO off, and connecting right after a disconnect, wait on the window's thread (up to 2
and 3.5 s on a slow disk).

**F-053 - Drone setup, what a reader of the writes to the flight controller found.** Board identity, the
all-or-nothing Betaflight writes with their read-back, the mode switch plan, MSP framing and the motor test's own
safety (stops on close, errors, shutdown; 10 % at most; 20 s auto-stop) looked sound. Mended:
- **CONFIRM stayed in force after its write was done**, for as long as the page was open: DISCONNECT, a change in
  Betaflight Configurator (ARM onto another switch), RECONNECT - and the next round wrote the page's choices back
  over it, ARM onto the old switch, with no new CONFIRM and no word. Now a CONFIRM is spent once its write is done,
  and by DISCONNECT. Test: `SetupConfirmTest.aConfirmIsSpentByItsWriteAndByDisconnect`.
- **CONFIRM sets the cell count shown, also the 6 nobody chose** while the battery row said only "Betaflight counts
  the cells itself" - on a 4S pack that is 2.8 V a cell, "empty" at once. The write is left as it is (forcing the
  count may be meant: Li-ion packs fool Betaflight's own count); the row now says "Confirm sets 6S".
**For the pilot - spin direction, to be tried on the drone before it is trusted:** the motor-direction tool sends
the ESC DShot commands 20 / 21 and then 12 (save). In Betaflight's list 20 / 21 are the ones its crash-flip uses,
for the time it flips; the ESC directions that are kept are 7 / 8 (then 12). Whether 20 / 21 + 12 stay through a
battery change depends on the ESC's firmware - on Bluejay and BLHeli_S they may be relative and not saved, on AM32
not saved. If they are not, a motor set to REVERSED here turns the old way after the next battery and the drone
flips on take-off. To try, props off: set one motor reversed, spin it, unplug the battery, plug it in, spin it
again. Not changed without that try.
**Listed, not changed:** the motor test does not take Drone setup's chosen port (two Betaflight boards on USB: the
first found spins); the guide or the app can close in the middle of an ESC write or a GPS search; a choice changed
while a confirmed round waits for the GPS is still written; a "diff all" cut short by a pause is taken as the
backup; an ESC passthrough that does not end is only written in the log; linked mode slots (Configurator's
"link") count as free.

**F-049 - The phone's camera and encoder: what a reader of that code found after the night's crash (2026-11-20
afternoon).** The crash of F-047 was one of a kind: the camera's own thread and the thread that starts and stops
camera and encoder ran into each other. The reader went through every place where they do. Mended in the phone app:
- **The start after the camera opens ran unguarded on the camera's thread**, side by side with the other thread,
  which is free the moment the open returns and may stop or restart the encoder under it. Now nothing there ends
  the app, the encoder's start and its stop wait for each other (one lock), and the camera counts as "opening" until
  that start has been asked for - so the waits the other thread already had (a size change, a StartVideo) wait for
  this part too.
- **A size change that came while another was being made was marked done without being made**, and the PC's
  repeats of it changed nothing (the same values): the picture kept the wrong size until the next change of tier -
  the PC's own log line "Video: phone kept the picture size" was this. The same at the start of a session in a small
  tier: the camera just opened at the big size, the small size marked done before it was made. Now a change is
  marked done before its sizes are read, so one that comes meanwhile is made by its own task; and a camera that has
  just opened is waited for (two seconds at most).
- **The encoder's answers came on the screen's thread after every size change** (an encoder made on a thread
  without a message loop answers on the main one): a screen held for two seconds - woken, its window made anew -
  was "no encoder output for 2000 ms" and a video restart for nothing. Now they have a thread of their own.
- Smaller: the recording's start after a camera restart ran on a thread with no guard; a queue read that throws
  when another thread emptied it; a release of the encoder that could throw on a codec the system had taken back.
**Listed, not changed:** a size change stops the old camera session without waiting for it, and its last pictures
may land on the encoder being released ("some phones raise a camera error" - the code's own note; then about 7 s
without a picture); a session's callbacks carry no mark of which session they belong to (a late one can say
"started" of a session that is gone - overwatch then records blind until its event ends); after a start that fails
nothing on the phone watches the picture any more (the PC asks again every 2.5 s, so it comes back, later); a USB
camera's open runs unguarded on the USB library's thread; an overwatch clip whose microphone gives nothing comes out
empty. Each would want a phone to see it happen.
Measured with the phone app built at 14:06 (`.\bench.ps1 emulator`, `runs\1120-1406-emulator`): the phone suite
green but for one thing of the day's own making - Android starts the service of an app that an error ended again by
itself, and that start was counted as a second end, so the app came back twice where it should three times. Now it
is not counted (`CrashGuard.serviceWithoutApp`); with the app built at 14:30 (`runs\1120-1430-emulator`) it came back
three times and stayed down the fourth, the PC's link and picture back 8 s after the end.

**F-050 - Saved flights, the afternoon's part.** A recording that failed one write - a file held a moment by a
virus scanner, a disk full for a while - ended for the rest of the connection; nobody started it again. Now the file
in hand is closed whole and the next key frame begins a new one; a disk that keeps refusing (five times, none a
minute after the last) still ends it, and the log says RECORDING STOPPED. And the once-a-second "to the disk" now
runs with the sound too: a picture that stopped while the sound went on left its last frames in memory.
Test: `StreamRecorderTest.aFailedWriteBeginsANewFileAtTheNextKeyFrame`.

**F-051 - FLEET and OVERWATCH, what a reader found.**
- **Mark OUT OF ACTION on the drone flown here could lose its deep sleep.** The PC disconnects, then queues the
  deep sleep; the phone's side of the session outlives the DISCONNECT by 4 s, and a name reply in that time says "a
  PC flies it" - taken for another PC, the deep sleep was dropped ("flown by another PC") and the drone stayed awake.
  Not seen in any bench run (the replies came too late), but nothing stood in the way. Now the seconds after this
  PC's own link ended are its own. Test: `FleetManagerTest.thePhonesOwnSessionTailAfterThisPcLeftDropsNothing`.
- **An overwatch clip that was whole but not yet checked stayed "Clip coming" for good** - and the phone kept its
  copy: when the PC app ended after the clip's last piece came and before it was checked, or when the check could
  not read the file once (a virus scanner holding it). Now it is checked and finished within a second. Test:
  `OverwatchHubTest.aClipWhosePartFileIsAlreadyWholeIsCheckedAndFinished`.
- **Listed, for the pilot:** a drone marked OUT OF ACTION while it is already asleep keeps its beeper switched off
  - the deep sleep it is asked for is the one it is in, so nothing is done (`PowerMode.apply`), and the beeper was
  quieted when it fell asleep. To be found it should beep. A fix wakes it, lets the flight controller have its
  sounds back and puts it to sleep again - which wants a real flight controller to try.
- Named by the reader and **not checked yet** (its review was cut off half way): the fleet's queue and the
  sleepers' times run on the PC's wall clock (a clock that steps would show every sleeper as late, or keep a queued
  mode for ever); a name handed out by the newest PC could be one a sleeping phone already has; changing a sleeping
  phone's check from "always listening" to minutes from the FLEET card would leave the PC saying LISTENING until it
  calls the phone late.

**F-047 - The phone app could end itself in the air and stay gone (phone app; seen in the emulator, in the night's
phone part).** 38 minutes in, on a failing link: the picture's size was being changed (AUTO going down a step)
when a cut closed the camera. The camera's "session ready" answer then met a camera that was gone -
`IllegalStateException: CameraDevice was already closed`, on the camera's own thread, caught by nobody - and
Android ended the whole app. It planned the app's service to start again 77 minutes later (and such a start finds
no settings and stops). From then on the PC said CONNECTING; nothing would have brought the link back but a hand
opening the app on the phone. The bench did that after a minute. The whole of the emulator's log is kept
(`film\night-phone-5916-1\emulator-logcat-all.txt`, 03:17:10). This is no emulator matter: it is the app's own
order of things on a link that is bad enough to change the picture and cut out at the same time.
**Fixed in the phone app (pc.54-beta as built 2026-11-20; the release takes pc.55), in two layers:**
- The error itself: that answer of the camera now takes "the camera was closed meanwhile" for what it is and starts
  nothing (`InternalCamera`, `onConfigured`).
- Any other error that ends the app (`CrashGuard`): said in the phone's log with its thread and text, kept for the
  next start ("The app was last ended by: ..."), and the app opens itself again a few seconds later - the same way
  it opens after an update from the PC or a wake from deep sleep. Opened, it starts listening for the PC as it
  always does. The same when a sleep check's alarm rings and finds the app gone (ended while asleep, as a phone
  maker's battery saver does). Not for ever: three times in ten minutes at most (`CrashRestarts`), and Android's own
  rule comes sooner - an app that an error ends twice within two minutes is not started again by anything until a
  hand opens it.
- What it needs on the phone, found by trying in the emulator: Android lets an app that is not on the screen put
  itself there only over a screen that is off or locked. So a screen that is on is locked first - which the app may
  do only where the status card's "Allow screen lock for deep sleep" has been set up - and the phone must have a lock
  screen (a swipe is enough). Without them a lit, unlocked phone shows a notification to tap and no more; the log
  says which it was.
- It comes back awake and listening whatever it was doing before: a phone that was asleep is awake after it, until
  the PC puts it to sleep again.
**Measured in the emulator** (`.\bench.ps1 emulator -Tests *PhoneCrash*`, `phone/PhoneCrashScenario`): `runs\1120-0817-emulator`: ended by an error while it was flown, the PC had its link back 7.8 s later, with a
picture; killed outright while asleep (a check every minute) it was open again 39 s later - that time by Android's
own restart of its service, which came before the app's own alarm - and PILOT UAV reached it; ended three more
times, more than two minutes apart, it opened itself each time; the fourth time within ten minutes it stayed down
and said so. **The camera's own moment, made on purpose** (`phone/PhoneCameraRaceScenario`: the picture's size
changed and STANDBY asked for while it runs, twenty times): 52 tries against the app as it was before the fix did
not meet it; in the suite's run with the fixed app (`runs\1120-0938-emulator`, 09:40:33) it came in the first round -
the same sequence as in the night to the line (size changed, session ready, camera closed, "CameraDevice was already
closed" in the session's answer) - and the app said "the camera was closed meanwhile" and went on: same process,
link up, all twenty rounds done. So the fix is seen to hold at the real moment, but no test fails without it every
time: the moment is a few milliseconds wide.
Fast tests: `CrashRestartsTest` (3). **Not tried on a real phone** - and the emulator has one maker's Android: how a
Samsung or Xiaomi treats an app that restarts itself is not known.

**F-044 - Saved flights and files: what a reader of the code found, each checked and tested.** The pilot's rule is
"always save a log". Seven places where a flight, or part of one, could be lost without a word:
- **A second copy of the app deleted the first copy's flight in progress.** Started while a drone was connected and
  not armed yet (a second double-click on the shortcut), its start-up took the open connection for one a closed app
  had left: track and recording deleted under the flight about to be flown; armed already, "APP CLOSED" was written
  into it. Now the app that has a connection open holds a mark beside its record
  (`flight-DATE.record.open`, locked while it runs; Windows lets go of it however the app ends) and another copy
  leaves such a flight alone.
- **"Last seen armed" did not count as armed.** A record whose one "armed" line never reached the disk, but whose
  later lines did, read as never armed - and a flight never armed is deleted with its track and recording at the
  next start. Now those lines say armed too; and before the files of an unclosed, "never armed" connection go, its
  link log and its track are asked: if either has the drone armed, the flight is kept.
- **An armed flight with neither a position nor a recording was no flight.** No GPS and REC off, or a full disk:
  nothing in PREVIOUS FLIGHTS, and its link log - the only log of it - went with the old ones twenty connections
  later. Now it is listed with its link log, which is kept like any flight's.
- **DELETE worked on the flight being flown** - the newest, so the one chosen when PREVIOUS FLIGHTS opens: its
  track, record and link log went while they were being written. Now DELETE is off for it ("Flying now").
- **The last seconds of video were in memory, not in the file.** The once-a-second "to the disk" emptied the file's
  own buffer only; the video writer keeps the piece it is filling in memory until it is 5 s long or a key frame
  comes, and the flush ran only when a frame was shown. So an app killed in flight lost up to 5 s of picture, and
  after a lost link - the picture standing - the frames just before the loss stayed in memory until the recording
  was closed properly. Now the piece in hand is ended and written every second, and a second after the last frame.
  Test: three seconds of picture written and the file copied as it stands - before the fix the copy had none of it.
- **A track, pins or map drawings that could not be saved said nothing.** A full disk, a folder that refuses: the
  track's file ended there, the pins dropped that flight were gone at the next start. Now the log says once
  "TRACK NOT SAVED: why" / "PINS NOT SAVED" / "MAP DRAWINGS NOT SAVED", and "saved again" when it works; a track
  whose file refused for a while is added to, not begun again; and a recording that starts on a disk with under
  2 GB free says "LOW DISK".
- **pins.txt held by another program for the moment the app started was "a broken file":** renamed
  `pins.txt.broken-...` and the app started without the pilot's pins. Now it is tried three times, then left where
  it is and never written over, and the log says "PINS NOT READ".
- Settings: a save that failed (the file held through every try) was said once and never tried again, so that one
  change - a calibration, say - was back at its old value at the next start; now it goes into the file with the
  next save. The new file is put on the disk before it takes the old one's place (a power cut could leave a
  settings file of nothing, read as "all defaults"). And one damaged line in the list of kept flights can no longer
  stop the app starting.
- Downloads: the phone app's file through the PC (23 MB) had 15 minutes in all and Tailscale's package 20, each new
  try beginning at nothing - on a slow link that works, "Download failed" for ever. Now they may take as long as it
  takes while something keeps coming, and are given up when nothing came for a minute. The PC's own update waited
  up to 10 minutes for a first answer; now one.
Tests: `FlightRecordTest` (+5), `gui/DebriefLiveAndBareFlightTest` (2), `map/NotSavedIsSaidTest` (4), `PinsTest`
(+2), `video/StreamRecorderTest` (+2), `PcConfigTest` (+1), `NetTest` (+3). Listed by the same reader and **not
changed**: with some 2,000 flights every list reads every track whole, again after each DISCONNECT (seconds to tens
of seconds; start-up reads the kept flights on the window's thread); the times of recordings and link logs come
from their file names in local time, so a PC whose time zone changes shows older flights' video hours off their
track; two flights begun in the same minute carry the same title, and REPORT writes over a report of the same name
without asking; a settings file that is there but holds nothing is read as defaults without a word.

**F-042 - On a very slow internet the map never came.** A tile had 20 s for the whole of it however well it was
coming; at a kilobyte or two a second - one bar, or the downlink shared with a drone's video - no tile was ever
whole in time, each was thrown away and begun again from nothing, and the map stayed empty for as long as the link
stayed slow. Now a tile that keeps coming is waited for (given up when nothing more of it came for 15 s, and after
two minutes at the latest). Tests: `TileCacheBadNetworkTest` (+3: a slow server that keeps sending, one that
trickles without end, one that stands still).

**F-045 - The phone's link and power code: what a reader found; three small things mended.** No path was found by
which the phone sends old sticks, arms by itself or arms again after a loss, and no deadlock.
- Tasks on the phone's "video starter" thread - the stop of camera and encoder at a Disconnect, at standby and at
  deep sleep, the sound's start, the recording's start and stop - were not guarded: one exception there ended the
  app as F-047 did. Now nothing thrown by a task there ends the app.
- DISCONNECT and PILOT UAV again within four seconds left "ended on purpose" standing for the new session: when
  that link was later really lost, the drone did not beep. Now every session taken counts as a pilot being there.
- A telemetry rate of 0 from a PC made the flight controller's poll spin and was kept: now 1 at least.
These three were in the app the night's phone part ran (built 00:23). **Listed, not changed - each touches a rule
that is the pilot's or needs a real flight controller to try:** for up to half a second after the flight controller
is plugged in again, an armed drone can read as DISARMED (its status comes before its mode list; unknown should
count as armed); waking from sleep opens the flight controller's port and the socket once each, with no second
try; the phone keeps sending telemetry to a PC that has gone without a Disconnect for as long as the app runs
(data and battery - but also what lets a PC that hears only one way still see the drone); a PC whose address
changes while the drone is armed cannot get its session back; a phone woken by a check-in that nobody then connects
to stays awake.

**F-048 - "The phone doesn't answer the power mode: its app is older than 2.3.0-pc.21. Update it." said of a phone
that is not old.** Waking a sleeping phone, the ask waits a minute or more for the phone's check; the "no answer
for ten seconds" was counted from the ask, so at the link's first moment - before the phone had said anything of
itself - the log told the pilot to update. Seen once each in three runs' logs. Now counted from the first time the
mode was sent to a phone that was there. Test: `PowerControllerTest` (+1).

**From the pictures (F-043), mended:** a switch row of MENU under the mouse turns red like every row - and its
"OFF" stood grey on red, a switch that is on red on red. There the switch is now dark when off, white with a red
knob when on, and the word white. Test: `gui/MenuRowLitTest`.

**The bench itself, mended:**
- The bad-link table sent its sticks at some 30 to 50 a second where the app sends 250 (F-002 was mostly this).
- "The phone app's service is running" asked only whether Android knows of the service - true, too, for one that
  crashed and waits to be restarted: in the night it stayed green for the minute the app was gone.
- The emulator's phone is now set up as a drone's phone is: the app may lock the screen, and there is a lock
  screen. Until then a deep sleep left its screen lit and nothing the app does behind a locked screen was ever run.
- The sleep-cycle scenario believed the check interval it had chosen; the app had sent another (F-046). It now
  reads what the app says it sent.
- New: `ui/OsdEditorScenario` (the OSD window used, not only opened), `phone/PhoneCrashScenario`, a row "cut 3 s
  phone to PC only" in the bad-link table.

## Fixed (2026-11-21, on `beta`)

Found in the second long run (`runs\1120-1449-night`, 2026-11-20 14:49 - 2026-11-21 07:00), in the part where the
real phone app in the emulator was flown, put to DEEP SLEEP and woken 28 times over 3 h 30 min.

**F-056 - A phone told DEEP SLEEP could stay awake with its camera on, while the PC showed it asleep (phone app).**
The power modes' one thread sent the "going to sleep" notice itself, and a send waits while the link socket's send
buffer is full (the jam of F-039, which the emulator's network makes again and again). Round 27: the notice stood
138 s in such a send. The countdown's "fall asleep" waited behind it: camera, video and screen on. The PC had heard
the first notice and showed the phone asleep, so it stopped calling - and the socket's watch opens it anew only while
the PC is heard. Only PILOT UAV, two minutes later, freed it; without it the phone would have stayed awake until its
battery was empty, and no check-in would ever have come. Round 12: the same send stood 5 s, the watch opened the
socket anew, and all three notices went into the socket being replaced - the PC said "didn't take DEEP SLEEP" and
went on calling; the phone slept a minute and woke fully at its first check. The check-in hello in deep sleep was
sent from the same thread: a jam there would have stopped every check after it. **Mended in the phone app:** the
notice and the hello leave from a thread of their own (`SleepSends`), so the countdown and the sleep come on time;
right before the link closes, if no notice got out, the socket is opened anew and one more notice sent - 1.5 s at
most, then the phone sleeps in any case. Tests: `SleepSendsTest` (7): a notice standing in a full socket does not
hold the countdown; the next one is dropped, not queued behind it; before the sleep the jammed socket is opened anew
and the PC gets the notice; one that left in the countdown is enough; one that went into a reopen is sent again; a
socket that stays jammed still lets the phone sleep after the bound; a notice that throws does not stop the next.
With the reopen taken out of the last chance, the test fails. **Not tried yet:** the night's phone part with this
build. **Still open:** why the emulator's socket fills (F-039; whether a real phone does it is the pilot's question).

**F-058 - The first remote update in the emulator stopped at Android's question (phone app and PC; the bench's
`phone/PhoneUpdateScenario`, 2026-11-21).** UPDATE PHONE APP on the real Windows app, the real phone app in the
emulator: it downloaded the newer app, checked it, and then Android put up its own install screen - on a drone's
phone, nobody to tap it. F-054's permission was there and granted; what was missing is the phone's "Install unknown
apps" for the app (REQUEST_INSTALL_PACKAGES not allowed): without it Android asks whatever the app holds. F-054 had
named that case; nothing in the app told the pilot, and the PC said "tap Update on its screen" while the phone's
screen said the phone was not allowed to install from this source. **Mended:** the phone refuses UPDATE PHONE APP
at once, before 23 MB are fetched, and the PC says why: "update failed - allow "Install unknown apps" for DRAUPNE
Flight on the phone, once by hand"; the phone's status card shows "Updates from the PC need "Install unknown apps".
Tap to allow." until it is allowed. **And on the PC:** the phone reports its update state every half second for
30 s, and the last try's FAILED was taken as the answer to the next UPDATE PHONE APP before its request had gone out
- given up, nothing sent: the very press a pilot makes after allowing it (F-054 had listed this, "an old failed
report"). A report counts as the answer only once the request had time to reach the phone. Tests:
`RemoteUpdateAnswerTest`; the scenario runs both: not allowed (refused at once, nothing fetched, nothing asked on the
phone) and allowed (updated with no question, open again by itself, the link back).

## Fixed before the release (2026-11-21, on `beta`, released as 1.0.71 + phone pc.55)

The pilot (2026-11-21): "Create fixes for all found issues and prepare and do all that is required and appropriate
to complete a release and merge to main." What was still open or "to judge":

**F-057 - fixed.** In a one-way cut of 2 - 4 s the PC's reconnect began the phone's new session and only the
answer was lost; the PC then took that session's first packets as strangers to its old one until `recoverCounter`
mended the numbering (about a second). The PC's receive buffer now takes them as the answer, but only while a Connect
of its own is out unanswered, only for a new session's first numbers (0 - 999), and never for one up to 256 behind
the next number (a late copy of the old session's). The phone's side has no such rule. Tests:
`TransportRobustnessTest.theFarSidesNewSessionIsTakenWithoutItsLostAnswer`, `aLateCopyOrTheOldSessionGoingOnStartsNoNewOne`;
the bad-link table's cell "cut 3 s phone to PC only".

**The link reader's late DISARMED - fixed.** After a loss, the pilot arming the moment the link is back, statuses
queued on the phone's way up from before the arming could stop the sticks of the drone just armed. An ARM switch
put on with the link heard now counts as arming on purpose: for `LOSS_SETTLE_MS` (3 s) a DISARMED stops nothing; one
that goes on after that stops the sticks as before. The switch left on through a loss (the case the stop is for) is
unchanged. Test: `RcSessionTest.lateDisarmedStatusesStopNothingRightAfterThePilotArmsOnAHeardLink`.

**The link reader's moved PC - fixed.** Without Tailscale, a PC whose own address changed during a session was
refused by the phone while the drone could fly and became a spectator of its own old session, for good. The phone
(pc.55) now lets a Connect in from another address when it carries the next ids of the live session's count (1 to
32 ahead) and the pilot's old address has been silent 2 s - the rule for the pilot's address on a new port; the PC
keeps asking for 10 s after losing its own session instead of turning spectator at the first PILOT_AT. Tests:
`SessionGateIdTest.thePilotPcAtANewAddressIsKnownByItsIds`, `SpectatorTest.aPilotNamedAtAnOldAddressRightAfterALossIsAskedAgain`,
`anotherPilotNamedLongAfterTheLossIsWatched`. What it means for who may take a drone over is in the private repo.

**`RsParityEndToEndTest.aLossWithinTheParityIsMendedBeyondItAsksForAKeyFrame` - quarantined**: red under load twice,
green alone 3 of 3.

**GitHub's Build run of the release commit** was red on two tests the same commit passed on `beta` and here:
`NetTest.aSlowDownloadThatKeepsComingGetsThroughHoweverLongItTakes` (a 500 ms standstill limit against pieces every
150 ms: too tight for a loaded runner - 1.5 s now, the download 3 s long) and
`SessionIdEndToEndTest.aLinkCutWithTheDroneArmedNeverArmsItAgainByItself` (red 1 in 8 here too). The latter asked for
one new session at most after a 3 s and a 6 s cut, as before F-001; with F-001's rule a cut that ends just as the PC
renews its try's id (heard again, or 4 s unheard: a 6 s cut ends at 2 + 4 s) lets a Connect of the old id through and
then one of the new - two sessions, which is the pilot's rule ("one when it goes, one when it is back"). Its failing
run's log shows F-057's new rule never came into it. The test now holds the rule (two at most on the phone, the PC's
one start); its safety checks (never armed again by the reconnect, the sticks back, the PC reads disarmed) are as
they were.

**The release workflow** read the phone app's pin with a pattern the bench's switch (`ApkShare.bench`) no longer
matched: the release would have stopped at "VERSION / APK_SHA256 not found". The pattern takes both forms now.

**Left as they are:** F-039 (why the emulator's socket fills; the phone mends it by itself since F-039's fix, and
whether a real phone does it is a real phone's log to watch), F-002's rest (Betaflight's ARMSWITCH rule), and what
the pilot kept by answer. The hardware tries (F-045, F-047, F-051, F-053's spin, F-054 on a phone) stay the pilot's.

## The pilot's answers (2026-11-20 night), built 2026-11-21

The "For you" form on the findings page: 16 of 18 answered. What was built, each with its test:
- **F-001 - one-way link:** the PC gives an unanswered Connect a new session id after a second only when the phone
  has been heard since the id went out; otherwise after 4 s (`SessionGate.Ids.UNHEARD_KEEP_MS`). A 6 s one-way cut:
  two sessions at most, the bad-link table's limit now. Tests: `SessionGateIdTest.aPhoneHeardNotAtAllKeepsItsIdFourSeconds`,
  `aPhoneHeardSinceTheIdWentOutGetsANewIdAfterASecondAsBefore`, `aOneWayCutOfSixSecondsMakesTwoSessionsNotFive`;
  `ControlLinkTest.theConnectCarriesOneIdATryAndANewOneAfterASession` on the wire (4 s unheard, a second once heard).
- **F-019 - CONNECTING:** in LINK LOST's place until the connection's first link, steady and white, its reason under
  it, no red frame, no alarm. Test: `OsdDataBuilderTest.beforeTheFirstLinkItIsConnectingNotLinkLost`.
- **F-046 - one CHECK EVERY** for DEEP SLEEP and OVERWATCH, LINK card and FLEET (the pilot's own choice, not the
  suggestion). Tests: `LinkFleetRoundTest.deepSleepAndOverwatchShareOneCheckInterval`,
  `theFleetsButtonsSendOneIntervalForEitherSleep`; the night's sleep cycles now fail on a difference.
- **F-010's rest - small windows:** the mini-map is not shown below 140 px; POP OUT MAP stays and the LINK card's
  rows get the room. Tests: `ScreenSizeTest.popOutMapStaysWhenTheRowsDoNotFit`, `theMinimapShowsWhereThereIsRoomForIt`.
- **F-043 - all six:** the small map (F-010's rule); the guide's step count (`WizardDialog.counterText`: "STEP 2" alone
  until the checks are in); INSTALL THE MOBILE APP's empty box and list; "Centre the sticks", INVERT in capitals and
  even rows, capitals kept on every red-lined button (`TacticalButton.setText`), "(TOOLS > RC CHECK)"; the OSD's
  warnings on a dark ground (`OsdDataBuilderTest.theWarningsStandOnADarkGround` - fails without the ground); OUT OF
  ACTION's 30 min in CHECK EVERY on the LINK card and FLEET.
- **LINK - DISCONNECT on a one-way link:** the drone beeps, as it does (the PC sends its Disconnect only while it
  hears the phone). Held by `BeeperRuleTest.aPilotWhoLeavesALinkDeadOneWayLeavesADroneThatBeeps`.
- **F-055 - after a loss:** the lost drone's sign where its track ends when that is newer than the network's place
  of it; the track's LAST KNOWN by the track's own drone, not the phone chosen since. Tests:
  `MapViewTest.theLostDronesSignStandsWhereItsTrackEnds`, `theTracksLastKnownIsItsOwnDronesNotTheChosenPhones`.
- **F-051 - OUT OF ACTION on a drone already asleep:** it wakes, its beeper is written back, and it sleeps again with
  the beeper on - once the flight controller reads disarmed, 20 s at most. Test: `BeeperRuleTest.outOfActionWhileAsleepWakesForTheBeeperThenSleepsAgain`
  (the rule; the emulator has no flight controller). **The pilot tries it with a real flight controller.**
- **F-053 - spin direction:** DShot 7 / 8 + 12 (kept), not 20 / 21 (crash-flip's). Test:
  `MotorTestTest.dshotCommandsAndProtocol`. **The pilot tries it props off** before it is trusted.

**F-057 - to judge: F-001's rule costs about 0.2 s after a one-way cut of 2 to 4 s** (the bad-link table, 2026-11-21:
"cut 3 s phone to PC only" LINK READY 1.06 and 1.09 s after the phone was heard again, 0.86 s before; the table's limit
is 1 s). In such a cut the phone's own session has not timed out (4 s), the PC has counted the link lost (2 s) and
sent a new id, the phone began that session, and its answer was lost. When the way back opens, the PC hears the
phone and counts itself connected again on its old packet numbers, while the phone is in the new session: the
settings round takes about a second (a reader named this on 2026-11-20 as "1 to 2 s later than it could"). Up to
F-001 the PC gave out a new id every second, and one happened to go out right at the end of the cut - that hid it.
What could win it back: when the PC hears the phone again after a try whose answer never came, it begins with a
fresh Connect at once instead of carrying on - one session more as the link comes back, which is what the pilot's
rule allows ("one when it is back"). Not changed: it is the link's heart. The cell stays red until judged. (Built
meanwhile: a Connect with the new id goes out the moment the phone is heard again in a longer cut, not at the next
turn of the pace - `SessionGateIdTest.aNewIdIsDueTheMomentThePhoneIsHeardAgain`.) **Fixed before the release
(above).**

**Kept as they are:** F-002 (Betaflight's ARMSWITCH rule), F-033 (the words), F-015 (30 flights), F-053's cell count
(CONFIRM writes the count shown - not the suggestion). **The pilot's own tries on hardware:** F-045, F-047, F-051,
F-053's spin, F-054 (the emulator first, then one phone). **Not answered:** F-039 (a real phone's log watched for the
jammed socket), "anything else".

## The bench's own notes (not bugs of the app)

- The start-up animation takes every press for its first seconds (its layer lies over the window): by design, the
  bench waits for it.
- A pilot who puts ARM on in the first half second after "RC OUTPUT: LIVE" gets ARMING BLOCKED: ARMSWITCH and must
  put it off and on again: Betaflight's rule (modelled), and the OSD says so.
- STANDBY MODE pressed in the very moment a lost link returns (before the flight controller's first fresh word)
  asks "The drone was armed moments ago and may still be flying" with Keep flying as the default: right. The
  question stays up when the screen behind it says DISARMED a moment later. The first long run met it 5 times in
  183 cuts and did not answer; the bench now waits for LINK READY and answers the question if it comes.
- On a link with loss the picture drops to ROBUST a few seconds into most flights, and each change of picture size
  starts a new recording file: the long run's 549 flights left 2,136 video files (each piece with its OSD copy).
- "Disarmed after the cut" in the long runs is the made-up drone's failsafe set to Drop. With GPS rescue set
  (`.\bench.ps1 link`, the two rescue scenarios, first run 2026-11-19 10:46) neither app disarms it: armed through an
  8 s cut and after it, the rescue flying on until a stick is moved, no resting sticks fed by the phone. And what a
  pilot on GPS rescue must know: ARM put off during the cut disarms the drone in the air when the link returns
  (26 m up in the test) - Betaflight obeys the switch again. The model is Betaflight as read in its code, not the
  firmware itself.
- The phone app has the kind of loop F-025 was on the PC: its sound thread (`StreamEncoder.audioStreamRunnable`) and
  a few like it log an error and go round again without a pause. Seen once, harmless (one line as the sound was
  switched off in a one-way cut); if such an error ever stayed, the phone would spin and fill its log. Not changed:
  nothing in the phone app was touched this afternoon, and it has not been seen to stay.
- 2026-11-21: two bench runs started in the same minute shared one run folder, and the second's results replaced
  the first's (the first remote update's run was lost that way). `bench.ps1` now adds -2, -3 to a folder that is
  there already.
- 2026-11-21: a Windows notification ("Device Setup - Let's finish setting up") stood at the screen's lower right all
  morning. Windows 11 notifications take clicks in an invisible margin round what they show: the bench's real mouse
  press on LAST KNOWN's CLOSE, a few pixels above the notification's edge, went to it, twice. The bench's check
  "the screen shows the control there" compares pixels and cannot see such a margin. Run in quiet mode instead; the
  notification is the pilot's to dismiss.
- 2026-11-21: two scenarios read the app's log twice in one line (`rig.log().subList(.., rig.log().size())`): the
  log grew between the reads - an IndexOutOfBounds that stopped the one-way cut scenario. Read once now.
- 2026-11-21: an Android emulator left running since the day before gave a slow camera picture: the first-start
  scenario's weak-link step counted 31 - 39 frames in 15 s, with today's and with yesterday's phone app alike, and 146
  right after the emulator was started anew. A long phone run now begins on a freshly started emulator.
- 2026-11-21: the phone app on the emulator is the phone's device admin (it may lock the screen), and Android lets
  the shell remove only test admins: "pm uninstall" fails. The update scenario puts the normal app back with
  "install -r -d" (a debug image takes a lower version code that way).
- 2026-11-21: the bench's button driver took a control folded away by the app (a card part folded to height 0, its
  content still "visible" to Swing) as still on the screen: the update scenario saw UPDATE PHONE APP and "Old phone
  app" after the update, when the pilot's screen showed neither, and "offered" was true before the button had
  unfolded. The driver now leaves out what lies inside something of no height (`Ui.collect`;
  `BenchSelfTest.aButtonFoldedAwayIsNotSeen`).
- 2026-11-21: switching "Install unknown apps" off for an app makes Android end that app. The update scenario did it
  while connected: the link was lost and the PC's question went nowhere. It now switches it off before the app opens.
  For the pilot: switching it off on a drone's phone ends DRAUPNE Flight; switching it on does not (on Android 11 it
  does: 2026-11-22, below).
- 2026-11-21: **`diff all` is not stable across restarts on a real board.** Two reads of the bench's flight
  controller (Betaflight 4.4.1) 41 s apart, nothing written between: `dump all` and the MSP facts the same, `diff all`
  two lines apart (`min_throttle`, `use_unsynced_pwm`) - Betaflight took the ESCs for brushed ones at one start and
  not at the other, and its defaults with them. A diff replayed after `defaults nosave` at the other kind of start
  leaves those settings at the wrong defaults. So the rig's `boardRestore` replays and compares `dump all` and the MSP
  facts; a `diff all` that differs while those are equal is one line of its report. Not looked at yet: whether the
  app's own `diff all` backup before Drone setup's writes (the one for pasting into Betaflight Configurator) can be
  fooled the same way.
- The night of 2026-11-21: **the long flight failed at 126 min on two window freezes of 1.0 and 1.4 s** while other
  test runs of mine ran beside it at normal priority (the bench's app runs below normal). The stacks are ordinary
  painting (a GDI blit, font shaping in the OSD's heading tape): a starved window, not a hang, not a finding of the
  app. Everything else of the flight passed - one flight on disk, its track whole, the recording, memory flat, the
  REPORT. The rule since: everything beside a long run at low priority (`start /low`), never normal.
- 2026-11-22: `OsdRecorderTest.aRecordingAlsoWritesACopyWithTheOsdDrawnIn` fails 3 of 3 on a heavily loaded PC, before
  and after the week's changes ("pictures in the copy: 11"); it passed in the quiet baseline. The OSD copy's thread
  runs at low priority and starves. `FleetModeEndToEndTest.aSleepingPhoneGetsTheNextModeAheadOfTheAnswerToItsHello`
  and `StanagFeedTest.theVideoWithItsOsdThenThePageAgainInOneStream` were red under the same load and green alone.
  A fast test run made beside heavy work is judged so: its reds run again alone.
- 2026-11-22: **on Android 11 the phone's screen cannot be read while the app shows it.** uiautomator waits for a
  second in which nothing on the screen changes (10 s at most, fixed on that image), and the app's lines change every
  second: every read failed after about 36 s with "could not get idle state", and the rig took it for an empty
  screen - three scenarios red, the phone's screen right in the film. The rig now says uiautomator's own words, and
  on the emulator takes the app's state another way (its process and window, Android's living services, the bench
  build's STATS), saying so in the film. A real phone does as before.
- 2026-11-22: **the Android 15 emulator image never brings up the network card the emulator's port forward ends at**
  (no SIM chosen for data), so every packet from the PC was lost: 0 of 7 the first time. The rig gives that card its
  address as root, once, until the emulator restarts, and says so in the film; then 7 of 7.
- 2026-11-22: **switching "Install unknown apps" on ends the app on Android 11** (Android 12 on: only off; Android 8:
  never). The update test now notes whether Android ended the app, opens it again and waits for the link before
  UPDATE PHONE APP. What the PC said meanwhile was F-082.
- 2026-11-22: **`bench.ps1` did not come back after a run whose suite had ended**, twice (a sitl run, the Android 11
  emulator run): `Start-Process -Wait` also waits for every process gradlew started, and a Gradle daemon started by
  the run lives on for hours. It now waits for gradlew itself.
- 2026-11-22 / 23: the real phone's 8-hour night counted one round of 63 failed (round 7, 00:41): the minutes the
  phone was in the pilot's hands while its PIN was set. Not a fault.
