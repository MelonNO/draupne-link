# Handoff

Everything needed to pick up work on DRAUPNE Link from a new session or another computer. Read this first, then
[README](../README.md) (what it does, troubleshooting), [CHANGELOG](CHANGELOG.md) (what changed when),
[PROTOCOL](PROTOCOL.md) and [ROADMAP](ROADMAP.md).

## Where things stand (2026-11-03, 1.0.35)

- **Released:** DRAUPNE Link **1.0.35** with phone app **DRAUPNE Flight 2.3.0-pc.28** (release
  `flight-2.3.0-pc.28`, APK SHA-256 `4a2c346e…`, pinned in `ApkShare`). Nothing of it has flown or been tried on the
  phone; the names, the simplified phone app and the map tools are all untried on hardware.
- **1.0.35, the pilot's second map round - released 2026-11-03:** more symbols (`UnitType` ISR_FIXED,
  ATTACK_FIXED, ISR_ROTOR, ATTACK_ROTOR with `UnitSymbol.role` - an eye for ISR, an arrowhead for attack - and
  UTILITY, SUPPLY, MEDIC); moving (`MapView.pickUpAt` on a double-click at a point or symbol while no tool is on,
  `movingLive` painted instead of the item, a click `dropMoving` -> `MapGraphics.replace`, a right click or Esc
  `cancelMoving`); `Tool.ERASER` (`eraseAt`, stays until Esc); `placeAt` (`MapToolsPanel.placeAt` -> `MapWindow.placeAtPosition`
  with `Coordinates.parse`: a point of the drawing on, else a symbol in the chosen style; the map centres there).
  *The smeared map window (the pilot's screenshots after FIND DRONE):* the tools panel's `JScrollPane` sat WEST in a
  clip panel of width 0..196 and its viewport blit-scrolled: `copyArea` moved pixels across the whole window (the
  map under the toolbar, the figures row doubled, white where nothing had painted). Now `SIMPLE_SCROLL_MODE`, the
  viewport in PANEL_2, CENTER of the clip. The LINK card's NAME MAP button is gone (the menu row stays). *Guide:*
  `FlightAppPage.refresh` turns the app row green when `main.names()` has an online phone; `PhoneSettingsPage` is
  three tickable rows; `ConnectPage` has no table, `phoneRow` carries the phone and the link (`setPhone` /
  `setLink` / `renderPhone`), `quality` carries round trip and path (`latencyState` / `pathState` / `renderQuality`),
  FLIP sits on the camera row beside the preview. *Phone (pc.28):* `updateChecks` every 2 s - `tvDataSaver`
  (`ConnectivityManager.getRestrictBackgroundStatus`) always, `tvRadios` (Wi-Fi on, Bluetooth on, Battery Saver
  off) once a PC is connected; a tap opens the matching setting (`openSetting`); BLUETOOTH permission up to API 30.
  Tests: `MapToolsTest.pointsMoveByDoubleClickTheEraserErasesAndPlaceAtPlaces`, the symbol loop covers the new types.
- **1.0.35 map tools:** the pilot's list (ruler with an elevation profile, lines,
  rectangles, arrows, NATO symbols, a MAP TOOLS slide-out, export / import, tour steps, an update-specific tour).
  `map/MapGraphics` (the items: MEASUREMENT, LINE, AREA, ARROW, SYMBOL with `LatLon` points, colour, width, fill,
  closed, unit / affiliation / echelon; `legsM`, `lengthM`, `areaM2` (shoelace on a flat local projection),
  `inside`; kept in Documents\DRAUPNE Link\graphics.kml) and `map/Kml` (writer and reader; DRAUPNE's details in
  ExtendedData, so Google Earth and ATAK read the file and a foreign file comes in as lines, areas and pins);
  `Elevation.profile` (the path sampled every 20 m, at most 80 points, looked up one by one on the lookup thread,
  partial results every 8). `MapView`: `Tool` (RULER, LINE, AREA, ARROW, SYMBOL, EXPORT_BOX; `setTool` toggles),
  the mouse (a left click adds a point, a double-click or Enter ends, Esc cancels, a right-click takes the last
  point back; the ruler closes on its first point; AREA and EXPORT_BOX are drags like the download box), the
  transient `measurement` (KEEP makes it a graphic), `itemAt` (symbols by `UnitSymbol.bounds`, lines within 6 px
  of a leg), the right-click menu's rename / remove / elevation profile, `paintGraphics` / `paintItem` (dashed red
  measurement with leg labels, total and area; halo + colour for lines; arrowheads; `UnitSymbol.paint`), `picture`
  (a snapshot without the zoom buttons, for EXPORT). `gui/UnitSymbol` (APP-6 frames by affiliation, icons by
  type, echelon marks). `gui/MapToolsPanel` (the tab, a sliding body, the buttons the window wires), `gui/
  ElevationProfileView` (height over distance, NaN gaps). `MapWindow`: the panel west of the map, the profile card
  under it (`showProfile`), `exportArea` (KML of the items, pins and the shown track in the box; a PNG from an
  off-screen `MapView` at the current zoom, at most 4096 px a side; Documents\DRAUPNE Link\Exports, a Save-as
  dialog), `importKml`, Esc / Enter keys, the tour anchors; PINS, GO TO and DOWNLOAD AREA moved into the panel.
  Tests: `MapGraphicsTest` (lengths, areas, the KML round trip, a foreign KML, the file), `MapToolsTest` (the
  tools by mouse events: the ruler closing and kept, a line by double-click, an arrow by Enter, a rectangle by
  drag, a symbol placed and painted, the export box, right-click undo; every symbol type paints). *Tour:* steps
  MAP TOOLS (press), RULER, DRAW, SYMBOLS, EXPORT (explained) tagged `FEATURE_MAP_TOOLS`, the CONNECT step
  `FEATURE_NAMES`; `Tour.SEEN_KEY` (tour.seen) lists the features offered; a PC with `tour.offered` and unseen
  features is offered `Tour.offerNew` once after the boot animation (`MainWindow.open`), a tour of those steps
  only; the whole tour marks every feature seen. *Not done:* a measurement's points can't be dragged after the
  fact (draw again); symbols have no label text beyond their name. *Untried on the real window.*
- **1.0.35 (names), phone app 2.3.0-pc.28:** the
  pilot's ask: "each phone is MobileAlpha, MobileBravo ..., each PC PCAlpha ...; the user only picks the phone's name
  and connects; remove the Direct connection option on both ends; a name map in the settings". *The names:*
  `Names` (the map: `name.<Name>=kind|ip|device|host|version|lastSeen` in the settings; letters; the packets
  NAME_QUERY 0x73 / NAME_REPLY 0x74 / NAME_ASSIGN 0x75, see PROTOCOL) and `NameService` (every 5 s: `Tailscale.status()`,
  a query to each online peer's link port from an ephemeral socket, replies filed, `onlineOnly`; this PC's own name
  `pcName` = the first free PC letter; the master = the pilot PC with the highest letter online (`Names.isMaster`)
  names each phone that answers without a name; other PCs' queries answered from the link port while no link holds
  it (`pause` / `resume` round `Connection.open` in the main window and the connect step), the open link answers them
  itself (`ControlLink.setNameReply`, `onForeignPacket`)). Tests: `NamesTest` (letters, map, packets, and the service
  against fake devices on loopback: the PC letter, the master rule, the nameless phone named). *The PC:* `LinkMode` and
  `RelayHost` are gone; `Connection.open` goes straight to `ip` (the chosen device's address, saved with `phoneName`);
  watching uses the pilot PC's address straight (`ControlLink.watchOnly(true)`, `SpectatorClient.pilotIsFixed`: no
  PILOT_QUERY to a phone). LINK card: the PHONE list (`updatePhoneList` / `choosePhone`, as a spectator the PILOT PC
  list) with a hint line and NAME MAP (`NameMapDialog`: the table, THIS PC IS, port and key, REMOVE, ASK AGAIN);
  MENU > LINK > Name map too. PC setup: `ConnectionPage` and `RelayPage` gone, `FirewallPage` recommends the UDP rule
  (name queries, spectators). Phone setup: the Tailscale page only shows the phone; the connect step has a Phone row
  with the name list (`fillPhones` / `choosePhone`, the session restarts on a change) and no address to type.
  *The phone (pc.28):* `Udp.answerName` (queries and assigns in any state, before the session checks; the name kept in
  the preferences, `MainActivity.phoneName` / `setPhoneName`, `deviceName`); the mode spinner, its hint, the address
  field and the start-up checkbox are hidden (kept: upstream's `Config.updateConfig` reads them; direct is forced),
  port and key under *Advanced* (`llAdvanced`), the service starts by itself 3 s after the app opens, the button is
  START / STOP. THIS PHONE on the status card (`updatePhoneInfo`, every 2 s): the name (or "No name yet"; a tap
  `pickPhoneName`, a long press copies the address), the device name, the Tailscale address (green; yellow when
  off), the other addresses. *Untried:* the whole thing. First try: open DRAUPNE Flight on the phone, start DRAUPNE
  Link: the PC's log should say "This PC is PCAlpha" and "Names: the phone at ... is MobileAlpha now", the LINK
  card's PHONE list should show MobileAlpha online, CONNECT should work without any address typed. If the name map
  stays empty: is the phone's key the PC's (Advanced on the phone), is UDP 6286 open on the phone's side (it always
  was: the link uses it), does `tailscale status` list the phone online?
- **Released:** DRAUPNE Link **1.0.34** with phone app **DRAUPNE Flight 2.3.0-pc.27** (release
  `flight-2.3.0-pc.27`, APK SHA-256 `034a424c…`, pinned in `ApkShare`). Nothing of it has flown yet.
- **1.0.34 (pilot's eighth round), phone app 2.3.0-pc.27 - released 2026-11-03, nothing flown:** the pilot's list after a day on 1.0.33, then "the video quality is very bad, SHARP is exactly the same".
  *Why the picture was bad - from the day's link log (18:36-18:54, 45 ms ping, direct path, no damaged frames):*
  the phone's bitrate fell to 150-250 kbit/s within six minutes and stayed there twelve; AUTO then went to bad
  coverage mode and the survival tier (15 fps) on the phone's own low bitrate, and the phone never took the small
  sizes ("the phone didn't change the picture size (still 1280 wide)" at every switch). Two causes, both on the
  phone: (1) its emergency brakes (`Udp.processFrameSendTime`: a key frame over 3 frame times to send; the sender
  loop's queue check, `videoStreamOutputBuffer.size() > 5`) counted the time 1.0.33's own pacing takes
  (`packetGapNs`: a key frame over up to KEY_FRAME_TIMES = 3 frame times, twice that with the two copies of bad
  coverage mode), so they fired at nearly every key frame at 2 Mbit/s and under, each a step down plus a 2 s
  raise lock (`StreamEncoder.emergencyDown`, `lockIncreaseBitrate`): the PC's raises never landed. pc.27:
  `sendFragments` returns the socket's own time and the brake judges that; `queueJudgeAfterNs` holds the queue
  check for twice the key frame's spread. (2) `InternalCamera` took the nearest 16:9 size for 640x360 and 480x270,
  which on the pilot's phone is 1280x720 itself: `pickSize` takes the same shape only within 1.5x the asked width,
  else the smallest size of any shape with at least the asked pixels (640x480 there); `VideoView.fit` letterboxes.
  *The sharp tier (the pilot's ask):* `Tier.SHARP` above NORMAL (the enum is best-first; `isRobust` is
  ordinal >= ROBUST, `wantHevc` / `profileFlags` follow it); `sharpWidth` / `sharpHeight` / `sharpBitrateLimitMbps`
  in the settings (1920x1080, 8; the template has them, old files get the defaults); `cameraWidth/Height`,
  `maxBitrateStep` and the Config's bitrate-limit byte (`bitrateLimitMbps()`) follow the tier; `BitrateControl`
  has the 8 Mbit/s step (TOP_STEP 7; the phone's table has it). AUTO (`adjustResolution`): NORMAL -> SHARP after
  `sharpHoldMs` (SHARP_AFTER_MS 20 s; doubled when a sharp spell ended within a minute, back to 20 s after one that
  lasted) calm with the phone's reported bitrate at the normal top (a phone that reports none never goes sharp);
  SHARP -> NORMAL at a stall, a relay, or SHARP_DROP_MS 5 s under the normal top; bad coverage mode is judged from
  NORMAL as before. SHARP in the settings forces the tier (`decideForcedTier`: the first config carries 1080p); a
  new AUTO session starts on NORMAL. Measured on the pilot's PC (i7-14700KF): 1080p H.264 software decode 1.9 ms
  + 0.7 ms convert; 720p -> 1080p up-scaling 2.6 ms fast bilinear, 3.0 ms bicubic - `VideoDecoder` scales up
  bicubic now. Latency: the sharp tier adds about 2 ms of decoding and bigger frames at the same pacing; AUTO
  leaves it at the first stall. Tests: `ControlLinkTest.autoGoesSharpWhenTheLinkIsCalmAndBackAtAStall`,
  `aPhoneThatReportsNoBitrateNeverGoesSharp`, the SHARP ends of `videoRobustAndSharpAreObeyedAtOnce` and the
  survival test (`setSharpAfterMsForTest`). The phone's changes have no test (no phone unit tests): read
  `pickSize` and the brake with care. *The rest of the round:* the video profile with the first config
  (`theVideoProfileGoesOutWithTheFirstConfigOfEverySession`: the phone keeps the flags of the session before),
  the tour's tip (`Step.tip`), EXIT TOUR as `TourNotice.CloseButton` with NEXT across the box, the connect step's
  scroll (`WrappingText`'s caret NEVER_UPDATE; `WrappingTextTest`), "step 9" = `PhoneGuideWizard.SleepPage`
  removed (the tour's SLEEP BUTTONS step has it). *Untried on hardware: all of it.* First flight: AUTO, the far
  phone on 4G, link logs kept; the log should say "Video: sharp (calm at 4 Mbit/s for 20 s)" and the phone's
  bitrate must no longer sink under the PC's 1 Mbit/s floor on a calm link.
- **Released:** DRAUPNE Link **1.0.33** with phone app **DRAUPNE Flight 2.3.0-pc.26** (release
  `flight-2.3.0-pc.26`, APK SHA-256 `b2a46c33…`, pinned in `ApkShare`). Nothing of it has flown yet.
- **1.0.33 (weak reception), phone app pc.26 - tested on the PC, not flown:** the pilot asked
  for a review of the weak-reception handling and then for every finding to be fixed. Both apps changed, and the
  protocol grew (PROTOCOL.md: the Transport, RC and Video sections). The PC build compiles upstream's
  `UdpSender` and `ReceiverBuffer` from `flight-app/overlay/Common` now (the resend timer that died after the
  first reconnect, the client taking the answer to its own re-Connect). Where to look:
  - *Sticks:* `ControlLink.sendRc` / `sticksWanted` (sticks and pings go on through a loss once the link has been
    READY; `everReady`), `RcSession` (its gate waits for `sticksWanted`; a loss no longer stops it),
    `sessionRun` (Connect every `CONNECT_FAST_MS`, Config every 250 ms, the FC state kept `FC_STATE_KEEP_MS`,
    `refreshFc`, `videoCheckAt` and the video watchdog `VIDEO_DEAD_MS`). Phone: `Udp.receiverRun` (the pilot's
    own Connect starts a new session), `PILOT_ALIVE_MS` 10 s and `msp.isArmed()`, `handleRcFrame` (16-bit
    sequence from pc.26 PCs, `RC_SEQ_RESET_MS`).
  - *Video:* `ControlLink.Tier` (NORMAL / ROBUST / SURVIVAL: `adjustResolution`, `decideForcedTier` before the
    first config, `sendTier`, `profileFlags` bits 0-2, `wantHevc`), `handleVideoFragment` (delivered whenever the
    config is known), `requestVideoConfig`, `sendBitrateStep` (`SET_BITRATE`, pc.26). `VideoPipeline.dropBacklog`,
    `KEY_REQUEST_PENDING_MS`, `GAP_MS`, `CONFIG_ASK_MS`, `NO_PICTURE_RUN`, `hardwareFailed`, the decode loop's
    per-iteration try. `FrameAssembler.PARITY_GROUP` / `putParityGroup`, the wrap rule. `BitrateControl`: the
    steps table has 150 kbit/s at 0 (FLOOR 3, START 4, TOP 6), `typical()` median, `SPIKE_ABSOLUTE_MS`,
    `SPIKE_DEEP_MS`, `Sink.setStep`. `VideoView.msSinceStreamStart` and `MainWindow.VIDEO_RESTART_GRACE_MS`.
    Phone: `Udp.sendFragments` (pacing `packetGapNs(size, keyFrame)`, group parity), `restartVideo` (the
    watchdog), `wantedHevc`, `checkCameraConfigUpdate` on the video starter thread (waits for `isOpening`),
    `nextFrameNum` wraps to 2; `StreamEncoder.setBitRateIndex`, `trimOutputQueue`.
  - *OSD:* `OsdRenderer` NO STICKS AT FC (`STICKS_LOST_ALARM_MS`), RELAY - MORE DELAY (`Data.relayed`),
    `MainWindow.osdLinkHint` STICKS STILL SENT; `ControlLink.setLinkPath` from the Tailscale check.
  - *Tests:* `TransportTest` (the overlay), `PipelineRecoveryTest` (backlog, missing config, no picture),
    `ControlLinkTest` (`sticksAndFcStateSurviveALinkLoss`, `aReconnectLeavesARunningStreamAlone`,
    `aStreamThatStoppedIsAskedForAgainAfterAReconnect`, the survival tier, 16-bit sequence, SET_BITRATE),
    `VideoTest` (group parity, the wrap, jitter, the huge ping, the floor in one go).
  - *Not done, on purpose:* the stick rate stays 250 Hz (a lower rate with the previous frame appended was
    weighed and left: at 250 Hz the next frame is 4 ms behind a lost one); the second copy of key frames in
    bad coverage mode stays next to the group parity. *Untested on hardware:* all of it. The far phone on 4G
    first, with `%APPDATA%\DraupneLink\logs\link-*.csv` kept; the PC's log says "Link lost, reconnecting...
    The sticks keep going." and "Link back after N ms."; the phone's logcat "The pilot connects again".

- **Released:** DRAUPNE Link **1.0.32** (Latest) with phone app **DRAUPNE Flight 2.3.0-pc.25** (release
  `flight-2.3.0-pc.25`, APK SHA-256 `05ffec7f…`, pinned in `ApkShare`): the 1.0.32 bullet below. The tag's release
  run failed twice on `ControlLinkTest.powerModeReachesThePhoneAndADormantLinkSendsNoConnect` ("Timed out waiting
  for a Connect after WAKE", 2 s), both times about 8 s into the test task, and passed on the third rerun of the
  same job; the push build of the same commit had passed the whole suite. Cause not found: the fake phone counts
  every Connect at its socket before any other check, `UdpSender.sendConnect` sends from a thread of its own, and
  the PC's `isConnected()` is the buffer's flag plus 2 s of silence - none of it explains a Connect that never
  comes. The test's event waits are 8 s now (a wait ends as soon as the event comes, so a pass costs nothing). If
  it fails again on the runner, that is a pattern, not the runner: `gh run rerun --failed` is the way out
  meanwhile (a failed tag run makes no release, so the tag can be rerun).
- **1.0.32 (pilot's seventh round), phone app pc.25:** the pilot saw the mark fade in as parts
  on the PC. `Logo.paint` painted each piece with its own alpha, so where the X runs under a bar's end the red
  doubled and the dark disc let the X's crossing through until alpha reached 1. Now `BootReveal.paintMarkFading`
  paints the mark solid into an image once (at the mark's sub-pixel place) and draws the image with the fade's
  alpha; at alpha 1 the direct painting takes over, pixel for pixel. The phone (`BootRevealView.onDraw`) does the
  same through `Canvas.saveLayerAlpha`. Test: `BootAndFoldTest.theMarkFadesInAsOneShape` (189 vs 126 red without
  the fix). The phone's crosshair no longer flies to the status pill ("goes to a random button and fades away"):
  it stays in the middle and shrinks out over the first 60 % of the fly, as the D leaves it. The `pill` target is
  gone from `BootRevealView`. The PC frames were checked off screen and the pilot saw the PC's fade-in from the
  dev window; the phone's not run on a phone.

- **1.0.31 (pilot's sixth round), phone app pc.24:** the boot cover stays opaque through the morph (`BootReveal`:
  the black's alpha is `1 - out`, not `1 - fly`; `BootRevealView` the same; OUT_MS 700, 4.2 s in all), so nothing
  of the UI shows before the mark has become its outlines, and the UI fades in as the outlines fade out. Tour: the
  LINK card's step is two - CONNECT (`tourLinkCard`, no press) and SLEEP BUTTONS (`tourPowerRow`, press): the step's
  `before` calls `MainWindow.tourPowerDemo(true)`, which shows the power row while nothing is connected and makes
  STANDBY / DEEP SLEEP / WAKE answer in the status line (`demoPower`) instead of asking a phone; the new `Step.after`
  (run by `Tour.next` / `end` for the step that is over) hides it again; `refreshLink` leaves the row alone while
  the demo is on. The pilot reported the menu wipe still behind the tour notice and the connect step still one
  step after the 1.0.30 release: both are 1.0.30 changes, so the running copy was most likely still 1.0.29 (its
  update check ran every 6 h); 1.0.31 adds nothing to the wipe's layering (`TacticalCombo.Ghost` is in
  POPUP_LAYER at position 0 since 1.0.30) - if it is still behind on 1.0.31, look at where the notice's window
  differs from the menu's (both should be the main window's layered pane).

- **Released:** DRAUPNE Link **1.0.30** (Latest) with phone app **DRAUPNE Flight 2.3.0-pc.23** (release
  `flight-2.3.0-pc.23`, APK SHA-256 `0173711c…`, pinned in `ApkShare`): the 1.0.30 bullets below. Nothing of the
  sleep path or the boot animations has run on a real phone yet.
- **1.0.30 (pilot's fifth round), phone app pc.23:** the boot animation is a morph. `BootReveal.paintD` /
  `BootRevealView.drawD`: each of the D's four pieces is a thick line cut by a box, in the mark (a bar: a 42-wide
  line down its middle cut by its bounds; a diagonal: the X's line cut by the bars' box, exactly what `Logo.paintLink`
  / `drawLink` draws, so the first fly frame is the mark) and as a card (a line across the card's middle as thick
  as the card, cut by the card's bounds); line, thickness and cut lerp, and from fly 0.3 the piece is hollowed (an
  inner line cut by the inset box subtracted: `Area.subtract` / `Path.op DIFFERENCE`) down to a 1.5 px wall. Targets:
  PC LINK + LOG cards (bars) and the controller card (both diagonals); phone the status + connection cards and the
  CONNECT button. 4 s in all (fly 2.4 s). The PC's `bottomTargets` may be one card (the `>= 1` check: it was `>= 2`,
  which silently fell back to the old fold - the first cards version never ran). Checked off screen (build/shot/BootShot).
- **1.0.30:** update checks every 15 min on the PC (`MainWindow`, `scheduleWithFixedDelay`) and every 10 min on
  the phone (`MainActivity.checkForUpdate` from onCreate and onResume, `updateCheckedAt`) - the pilot saw no update
  because the PC asked every 6 h and the phone once per process.
- **1.0.30, phone app pc.23, deep sleep quieter:** `DDService.powerSleep` also closes `phoneTelemetry` (its
  thread read the camera fps, the battery and the telephony state once a second), `audioSource` (the microphone's
  thread) and the service's `mainTimer` (`stopMainTimer`); `powerListen` starts the timer for the check window and
  `powerListenEnd` stops it again; `powerWake(fromSleep)` re-initializes the telemetry, the audio (the two config
  lines from onStartCommand) and the timer. `MainActivity.onResume` skips `checkOldApp` / `checkForUpdate` in deep
  sleep and its uiTimer acts on every fifth tick. Still running in sleep: the alarm (`PowerAlarmReceiver`) and the
  foreground notification. Untried on a phone.
- **1.0.30, guide:** the connect step talks about connecting only; `PhoneGuideWizard.SleepPage` ("Sleep before
  take-off", section CONNECT, last in both Phone setup and the first-time setup) is a working copy of the LINK
  card's power row (the same `Theme` buttons, CHECK EVERY combo, status line) whose `refresh()` (twice a second
  from `WizardDialog`) plays STANDBY / DEEP SLEEP / WAKE: the status text and a countdown to the pretend phone's
  next check; nothing is sent. Painted off screen (build/shot/SleepPageShot).
- **1.0.30, tour:** POP OUT VIDEO and FULL SCREEN steps are back after MINIMIZE / EXPAND, `press=false` (pressing
  would pop the video out or cover the window over the tour); DOWNLOAD AREA `press=false`; `MapView.allPins` is
  what `PinManagerDialog.refresh` lists, so the example pins are in PINS too (`isExamplePin` / `changeExamplePin`
  for rename and remove there; never saved). `TacticalCombo.Ghost` is added to POPUP_LAYER at position 0 (it was
  DRAG_LAYER, below the tour notice in the same layer: the wipe played behind the notice and the real menu, in
  POPUP_LAYER, then jumped in front). `Tour.OFFERED_KEY` ("tour.offered"): `Tour.offer` sets it; `MainWindow.open`
  offers the tour once, after the boot animation, on a PC with `setup.done` but no `tour.offered` (a PC set up
  before 1.0.26 had the tour; a PC still without setup gets it from the wizard's finish and the key is just set).
- **Not committed by the repo:** two stray files in the repo root, `droiddrone-pc.properties` (a copy of the
  defaults from 2026-11-02) and `never-written.properties` (2026-11-03): not from this work, left alone, not added.

- **Released:** DRAUPNE Link **1.0.29** (Latest) with phone app **DRAUPNE Flight 2.3.0-pc.22**: the 1.0.29 bullets
  below (the sleep check's hello, the phone's hold-to-wake and mode line, the quiet notification, the folding sleep
  buttons, the guide's sleep text, the tour's fourth round, the glyph video buttons, the two flickers, the boot
  animation's D). The sleep path has still not run on a phone.
- **1.0.29, phone app pc.22 and the PC side:** the sleep check's
  "still here": in each window the phone sends `SleepHello 0x6F` (battery %, charging, next check, mV) to the PC it
  last flew with (`Udp.lastPcIp/lastPcPort`, kept across the socket's close; `DDService.powerHello`), the PC answers
  `SleepAnswer 0x70` 0/1 at once (`ControlLink.onSleepHello`, before the link's liveness mark: a hello is not the link)
  and the phone closes its window on 0 (`PowerMode.sleepAnswer`) or wakes on 1; the LINK card shows "Heard n ago,
  battery n %" and takes the next-check time from the hello. The phone's own STANDBY / DEEP SLEEP buttons are gone:
  the PC sets the modes; the phone has a MODE line and one HOLD TO WAKE button (2 s hold); the permissions are asked
  through the yellow status lines (the guide says so). On the PC the two sleep buttons fold away (`Fold`) while the
  FC reports armed and unfold on disarm. The guide's connect step and the tour's LINK card step explain the modes.
- **1.0.29, the boot animation's D:** on the PC the mark's "D" (the two bars and the X between them) comes apart
  too: the bars fly to the video's two glyph buttons on the right and end their size, the two diagonals swing level
  and stretch into the outlines of CALIBRATE and RC CHECK at the bottom (`BootReveal.paintD`; MainWindow hands the
  targets' bounds over with `boundsInPane`; without targets the D folds away as before, which the frame tests use).
  On the phone (`BootRevealView.drawD`) the bars become the two halves of the CONNECT button, the diagonals the LOCK
  SCREEN button's outline, and the crosshair flies to the status pill and shrinks to its height. Checked off screen
  on the PC (build/shot/BootShot); the phone's needs a look on a real phone.
- **1.0.29, two flickers:** the boot cover (`BootReveal`) is the root pane's glass pane, which the root layout
  sizes before any paint - on the layered pane it got its size a frame after the window grew to maximized, and the
  UI showed in that frame. A menu's opening picture (`TacticalCombo.Ghost`) now shows the real menu first and goes
  two frames later (it went first, and the window showed through for a frame); `SlideMenu` re-lays out the header
  only when its width changed (the slide's last frame did it once more for nothing).
- **1.0.29 (pilot's fourth round):** the phone's running notification is on a new quiet channel
  (`DDService.QUIET_CHANNEL_ID`, IMPORTANCE_LOW, silent, only-alert-once): deep sleep changes its text at every
  check and the old high channel would sound or light the screen each time. Tour: NEXT is 124 px wide (PRESS IT was
  cut), the dim sits in MODAL_LAYER so menus and the rows that fly as they close show over it, the live area (the
  header on the menu steps, the map on the map steps) is un-dimmed as well as mouse-live, and the PINS step shows
  three made-up pins (`MapView.showExamplePins`, never saved) until the tour ends. Deep sleep and the phone's own
  power: the app can't switch on Battery Saver or airplane mode (Android keeps both from apps), it releases its wake
  lock and lets the phone doze; the pilot can turn Battery Saver on by hand.
- **1.0.29:** the video's POP OUT / FULL SCREEN are two 30 px drawn buttons (`GlyphButton`:
  a window with an arrow out, the arrow back in once popped out, the four brackets for full screen) in a 38 px strip
  at the right edge of the picture; the names stay as the buttons' text for the tour and the tooltips.

- **Released:** DRAUPNE Link **1.0.28** (Latest) with phone app **DRAUPNE Flight 2.3.0-pc.21**: everything in the
  1.0.28 bullets below (STANDBY / DEEP SLEEP, the Tailscale warning, UPDATE made visible, the tour's third round, the
  video buttons at the side, the channel bar labels). None of it tried on a phone yet.
- **1.0.28, phone app (pc.20, never published on its own, folded into pc.21):** CONNECT in Direct mode with no
  Tailscale address on the phone (no interface in 100.64.0.0/10, `MainActivity.onTailscale`) shows "Tailscale is
  off" with Open Tailscale / Connect anyway / Cancel, and the network line says "Waiting for the PC - Tailscale is
  off on this phone" while it runs that way. Release order as always: push, `gh release create flight-2.3.0-pc.20`
  (not Latest) with build/flight/DRAUPNE-Flight-2.3.0-pc.20.apk, download and check the SHA, then tag v1.0.28.
- **1.0.28, phone app pc.21 (built from the merged tree, SHA pinned in ApkShare):** `PowerMode` (one worker thread) owns the modes; `DDService` hooks do the work,
  `Udp` drops sticks and StartVideo outside mode 0 and re-`initialize()`s its socket for the 20 s check window,
  `Msp.setSlowPoll` / `PhoneLocation.setInterval` are the standby rates. Deep sleep keeps the foreground service; the
  alarm receiver (`PowerAlarmReceiver`, `setExactAndAllowWhileIdle`) takes a wake lock before handing to the worker;
  `PowerAdminReceiver` + `res/xml/device_admin.xml` for `lockNow()`. The activity dims via `applyPowerLook`, shows over
  the lock screen only on `ACTION_WAKE` (full-screen-intent notification), and treats an onResume while asleep as a
  wake. The phone asks once for notifications, exact alarms, device admin and battery optimisation off; the PC's
  first deep-sleep request doesn't pop the admin prompt (the phone's own DEEP SLEEP button and a status line do).
  The guide's "Settings on the phone" step lists the three answers to give. Untried on a phone: doze with the screen
  locked, the alarm firing on time and the socket getting network in the window, lockNow with a PIN, the full-screen
  intent lighting the screen (some makers need "display over other apps"), the USB port reopening after sleep.
- **1.0.28, tour:** the UNDER THE CURSOR step lights the CURSOR and TERRAIN HEIGHT cells together
  (`TourNotice.setExtra`, the union of both).
- **1.0.28, STANDBY / DEEP SLEEP (PC side):** the LINK card's power row
  (`MainWindow.powerPanel/refreshPower`): STANDBY, DEEP SLEEP, WAKE, CHECK EVERY 1/2/5 min (`phone.sleepCheckMin`).
  `ControlLink.sendPowerMode` (0x6E), `setDormant` (no Connect while the phone sleeps), `restartVideo`;
  `FcTelemetry.getPhonePower` (0x40F5). In standby the stick listener feeds the session null (as if the radio were
  gone), so the sticks wait for a low throttle on waking; the HUD banner says STANDBY - PRESS WAKE; both modes are
  refused and left while the FC reports armed. Deep sleep: once the phone reports mode 2 and the link drops, the PC
  goes dormant and counts down to the phone's next listening window; WAKE clears dormant and the Connects resume.
  Protocol in docs/PROTOCOL.md. Untried on a phone.
- **1.0.28, tour (pilot's third round):** no POP OUT VIDEO / FULL SCREEN steps; a step at a button or a choice
  keeps NEXT locked as "Press it" until that control was pressed (`TourNotice.requirePress`: an AWT mouse-released
  watch on the control and its children, plus the button's action; UPDATE, the LINK card, the CURSOR and DRONE cells
  and the last step don't require it); on the map steps the map itself stays live under the dimming
  (`Step.live`, `Dim.contains`), so the CURSOR figures, panning, the wheel and pins work during the tour; the last
  step brings the main window to the front over the map window.
- **1.0.28:** UPDATE made visible end to end after the pilot's "it downloads, closes, and
  nothing opens": the helper script (`SelfUpdate.script`) writes every step to `%TEMP%\DraupneLink-update\update.log`,
  waits up to 30 s for a locked installer and 2 min for the app, and whatever fails it starts the app again and
  leaves a `RESULT` line, which the next start reports (`SelfUpdate.takeHelperResult`, `MainWindow.reportLastUpdate`:
  a pop-up when it failed). `startUpdate` logs and explains each early exit (connected: "disconnect first"; not the
  installed copy: the release page, with where it runs from, `AppInfo.runningFrom`). `AppLog.drain` runs in
  `shutdown()` so the last lines reach the file. Found on this PC: Program Files still held 1.0.24 while 1.0.27 was
  out, Windows Installer never ran for 1.0.25-1.0.27, and the app log had no "Downloading" line for them - the
  pilot's presses never reached the download in the installed copy (a build/install run was in use too). The video
  buttons (POP OUT VIDEO / FULL SCREEN) stand at the right of the picture, one under the other. Also: the channel
  bars (RC check and the controller card) name each AUX after what Drone setup put on it ("AUX1 ARM",
  `ChannelBars.setPurposes`), the last item of the review left open. Still open
  from the review, by choice: per-window double buffering for the popped-out video (unmeasurable here; the comment
  in `VideoView` says what the setting does), and the OSD outline drawing (measured: the nine-pass drawString is
  4-6x cheaper than stroked glyphs, so it stays).

- **Released:** DRAUPNE Link **1.0.27** (Latest) with phone app **DRAUPNE Flight 2.3.0-pc.19**: a full code review
  (five areas, read-only, then five parallel workers in git worktrees) with every finding fixed and every suggested
  feature built, plus the pilot's second SETUP feedback round. **None of it has flown**: first a bench test, props off.
  Where the pieces live:
  - **Link:** after a link loss the sticks resume at once when the FC's status (no older than 2 s) says armed
    (`RcSession`, `fcArmedNow`; a GPS-rescue drone must not get an ARM-off frame); otherwise throttle low + arm off as
    before. Stick frames carry a trailing sequence byte for phone apps pc.19+ (`ControlLink.rcFrame`,
    `FcTelemetry.getPhonePcBuild`); older apps get the old frame. The phone's PHONE_STATE (0x40F4) feeds the link log's
    `pc_to_fc_ms` = ping/2 + its in-to-out time, and the HUD's STICKS n MS. The link counts as lost after 2 s of
    silence (upstream's 4000 ticks kept as the other bound). A PILOT_AT naming this PC's own address/port is ignored
    and Connect keeps going (seen 2026-11-02: the connect step became a spectator of itself). Spectator pauses are
    announced with PAUSE 0x6D. `ArmSwitch.on` treats an unsent AUX as 1000. `JoystickService.getPollMaxMs` (GLFW's
    event pump) goes to the link log as `glfw_poll_max_ms`.
  - **Phone (pc.19):** `Udp.receiverRun` hands an RcFrame to `handleRcFrame` on the receive thread itself; it parses the
    channels in place and calls `Msp.setRawRc(rc, count)`, which maps them with MSP_RX_MAP (required - no map, no
    sticks; PHONE_STATE bit0) and sets `Serial.pendingRc`; the serial writer (newest sticks first, then `pendingUrgent`
    = the RX_MAP request, then the queue) writes it. `(byte)(seq-last) <= 0` is dropped. A Connect from the pilot's IP
    or while the pilot is silent > 1 s creates a new `ReceiverBuffer` (the session keeps alive on the PC's pings).
    USB permission answers are received in `Serial`; after Deny only a replug/device change asks again. Senders use
    blocking queues; recording start/stop on `videoStarter`; a lost camera reopens; one service start; a partial wake
    lock; POST_NOTIFICATIONS; the updater refuses an APK without a digest; `Msp.parsePackets` carries bytes over.
    Encoder hints (KEY_LATENCY, PRIORITY 0, no B-frames, CBR) fall back to the plain format if configure throws -
    **needs a phone trial**.
  - **Video:** a restarted stream's key frame 1 waits up to 1 s for its config (`VideoPipeline.startsAhead`); a
    dropped backlog flushes the decoder (`FLUSH`) rather than rebuilding it; key-frame requests back off 1.5/3/6 s
    while requested key frames come damaged (`KeyFrameBackoff`, 3 s base in bad coverage); the bitrate rescue only
    fires when the phone's reported target is below 1 Mbit/s and never above `maxStep`; the scaler uses the picture's
    colour matrix (BT.709 for HD) and slice threads; recording writes an OSD CSV next to the MKV. OSD: LINK colour is
    the ping over the link's own 15-30 s minimum, VID shows received fps, AGE is the picture's age, STICKS the phone's
    write age, a BANNER element, ARM SWITCH NOT READ, PAUSED for spectators. Text outlines stay 9-pass drawString
    (measured 4-6x cheaper than stroked glyphs).
  - **Setup:** Drone setup no longer writes by itself: a round reads, lists the plan in "What will change" and waits
    for WRITE (then changed choices write as before); the cell count is forced only once confirmed
    (`drone.cellCountConfirmed`), with "Use measured" from MSP_BATTERY_STATE. `BetaflightUsb` has a `Wire` seam (fake
    ports in BetaflightTest), a 60 ms prompt settle, `inCli` with `exit` on close, and board/craft names. A switch
    written or changed sets `setup.radioNeedsRedo`, which brings the radio step back until a calibration is saved
    (`MainWindow.calibrate` clears it). The tour (18 steps) opens the menu first, skips controls that aren't shown,
    ends when a window closes, and dims the rest dead to the mouse; the Connect step logs its link events and says
    what the video pipeline is doing. `CheckRow`'s detail is a `WrappingText.fitting`.
  - **Map / window:** `TileCache.Walk` streams an area's tiles (no list; `Download.total` is a long), counts refusals
    (a 403/429 picture is skipped), pauses. `FlightTrack` writes on its own thread (`flush()` in tests), keeps the
    phone's accuracy and fix time, reads GPX back (`read/files/newest`). MainWindow: `liveTrack` vs shown `track`,
    LOST DRONE, `mainWindow` bounds key, UPDATE outside the slide-out, the video bar (POP OUT VIDEO / FULL SCREEN).
    MapWindow: GO TO (`Coordinates.parse/fromUtm`), TRACKS (`MapView.setOldTrack`), keys, `Bounds` helper,
    `isShown()`. FindDroneDialog refreshes from suppliers. `PcConfig.save` and the pins/GPX rewrites go through
    `.part` + ATOMIC_MOVE.
- **1.0.26**, phone app unchanged (**2.3.0-pc.18**): the pilot's SETUP feedback, all of it. The first-time guide is 13 steps (the
  phone overview, "Measure the delay" and "Ready" pages are gone; `WizardDialog` is unchanged). FINISH plays
  `SetupFinale` (the mark, SETUP COMPLETE) and, the first time, offers the **tour** (`Tour`: a `TourNotice` with
  NEXT / SKIP TOUR and a dimming overlay, MENU > controller fold > pop-out video > the LINK card, then the map
  window opens for FOLLOW, PINS, the CURSOR cell, the DRONE cell, DOWNLOAD AREA; SETUP > Tour any time; the
  anchors are `tour*()` accessors on MainWindow / MapWindow). Betaflight page: "Plug a USB data cable into the drone
  and this computer", the cell-count line says to enter it, a short AUX note under the switches (`WrappingText.fitting`),
  RETRY (the Read again button while nothing answers), DISCONNECT / RECONNECT (`paused`: the rounds stop, the
  port is free for Betaflight Configurator), INAV / ArduPilot named when found, "Done - you can unplug the drone".
  Radio page: the controller list is always shown. Calibration: an AUX with nothing from Drone setup is
  "AUXn - reserve". Phone pages: "Install the mobile app" (the app row stays neutral: this PC can't see the install),
  "Settings on the phone" (short, DRAUPNE Flight by name, names differ by phone), "Connect DRAUPNE Flight" with
  Always allow / Use by default, and the **camera check**: the live picture in a small `VideoView` (the page's
  session now opens the video too, `VideoPipeline` on the preview) and FLIP, which is VIDEO > Camera upside down.
  No "USB OTG adapter" anywhere: "USB data cable". Motor test: a slider or the picture without the box ticked
  shakes the risk line; without a battery a pop-up says to plug one in; a click on a motor in the picture runs it
  for 3 s at 40 % of the test range (`pulse`). Latency test text: "Press ESC to end the latency test". Download
  area: "MAP TYPE", no hard limit (a warning above `TileCache.MAX_DOWNLOAD`, with a rough time), HIDE (the map
  window shows DOWNLOADING n / m, a click brings it back), SELECT WITH CURSOR (`MapView.selectAreaNext`). The main
  window opens maximized. README's guide pictures redone (13 steps). None of it tried on hardware; the pictures
  came from the throwaway `FeedbackShot` in build/shot (git-ignored), painted off screen.
- **1.0.25**, phone app unchanged: the two items below.
- **1.0.25 - Drone setup on Betaflight 2025.12:** the receiver could not be set. 2025.12's
  CLI has no name for RX_MSP (`feature RX_MSP` answers "Invalid name", and `feature` can't list it; the bit,
  1 << 14, is unchanged and master has the name back). Found on the pilot's iFlight Blitz F722 on 2025.12.2: three
  tries, three identical backups, and the board left with no receiver at all (`feature -RX_SERIAL`, nothing on).
  Now the receiver is read and set through the feature mask over MSP (`BetaflightUsb.featureMask` /
  `setFeatureMask`, MSP_FEATURE_CONFIG / MSP_SET_FEATURE_CONFIG, as Betaflight Configurator's Receiver tab does)
  before the CLI opens; the CLI's `save` stores it. `BetaflightPlan` works on the mask (`isMspReceiver`,
  `mspReceiverMask`, `hasGps`), the backup's receiver lines are put back to what they were
  (`restoreReceiver`, since the "diff all" is taken after the switch), and a CLI answer with `###ERROR` or
  `Unavailable` is logged ("Betaflight refused: ...") and shown on the USB row instead of passing as success.
  Not yet tried on the board.
- **1.0.25:** the setup guide's hint under the title wraps (`WizardDialog.hint` is a
  `WrappingText` sized to the window's width; the Betaflight page's hint was cut off at the right edge). The README's
  pictures were redone from a throwaway `ReadmeShot` (build/shot, git-ignored): the mark with the name, the main
  window as it opens, the OSD over a drawn landscape, four guide pages, and the map window with a made-up flight
  over Reine (Lofoten) on topo and satellite, the Alt + drag box and the DOWNLOAD AREA dialog. Real tiles were
  fetched into a temp cache for them; nothing was shown on screen.
- **1.0.24**, bundling phone app **DRAUPNE Flight 2.3.0-pc.18**: the map's
  DOWNLOAD AREA, the phone's position from every location provider, the in-app phone updater, the link log's phone
  position columns (all three below). 1.0.23 (pc.17): the icons without the brackets, the boot window opening black.
  1.0.22 (pc.16) brought everything below. (Its first two
  tags failed on the release runner: the boot test measured real time while Java2D's first use took seconds, and one
  UDP loopback frame broke in SpectatorTest; both tests are tolerant now, and test failures print their message.) The map and
  everything round it, the MENU header, the boot animation and the new mark. Where the pieces live:
  - **Map:** `map/Mercator`, `map/TileSource` (Kartverket topo/paper, Esri satellite, OSM; `inNorway` is a 22-point
    outline of the mainland + Svalbard + Jan Mayen, so Sweden and Finland are outside), `map/TileCache`
    (`%APPDATA%\DraupneLink\tiles`, kept for good, `prefetch` of the levels under a rest, not for OSM; `download` of a
    whole area for every picture, `gui/MapDownloadDialog`: DOWNLOAD AREA, Alt + drag, the right-click menu),
    `map/Coordinates` (MGRS / UTM Krüger with Norway's zone exceptions, tested against GeographicLib's 38SMB example),
    `map/Elevation` (Kartverket's height service in Norway, Open-Meteo elsewhere and as fallback), `map/FlightTrack`
    (GPX as it comes, `last-position.txt`, both GPS sources with a 5 s fallback, pins as waypoints on close),
    `map/Pins` (Documents\DRAUPNE Link\pins.txt, Alpha..Zulu, Alpha2..), `gui/MapView` (tiles, track, launch "L",
    rings, pins, LAST KNOWN badge, + / - buttons, right-click menu), `gui/MapWindow` (toolbar + figures, remembers
    its bounds in `mapWindow`, fades in on every opening), `gui/PinManagerDialog`, `gui/FindDroneDialog`.
  - **Phone GPS:** phone app pc.16 sends record `0x40F3` (`PcLinkStats.writePhonePositionTo`, `PhoneLocation`; the
    location permission is asked once, the service runs without it; foreground type `location` only when granted).
    pc.16/17 asked the bare GPS provider only: no fix indoors, where Google Maps (fused: Wi-Fi + cell) has one - the
    pilot's report of 2026-11-02. pc.18 asks GPS, network and (Android 12+) fused, a fresh GPS fix winning; the phone's
    status card says what the GPS is doing (not allowed - a tap asks; no position yet; provider, accuracy, age), and
    the link log has `phone_lat/lon/acc_m/age_s`, blank when the phone sends none: start there for the next report.
    `FcTelemetry.PhonePosition`; `gpsSource` setting (MENU > TOOLS > Position from).
  - **Header:** `gui/SlideMenu` - MENU (the one filled, black-text button) slides out SETUP, VIDEO (+OSD rows), LINK,
    TOOLS (Find drone, Position from, Tracks folder, tests, versions), UPDATE when there is one; stays open until
    pressed again. The map picture and coordinate format are chosen in the map window only.
  - **Menus:** FlatLaf gives popups a window of their own on Windows (for the shadow), so nothing inside the main
    window can cover them. `TacticalCombo.show` therefore wipes in a *picture* of the menu (`Ghost`, row by row with
    the scan bar, frame and all) and shows the real menu only afterwards; closing wipes a picture out, bottom row
    first. Dropdowns (JComboBox) open through Swing itself and keep the old cell reveal; they close with the ghost.
  - **In flight:** `MainWindow.updateFlightLayout` - the LINK card's fields and detail figures fold (`gui/Fold`) and
    the minimap + POP OUT MAP unfold; the map popped out (or disconnect) brings everything back. The controller card
    starts folded (`controllerMinimized=true` in the template).
  - **Boot:** `gui/BootReveal` over the layered pane (3 s: fade in 560, hold 320, fly 1560, out 560 ms; click or key
    skips): brackets to the window corners, the link folds, the crosshair flies to the video's middle, splits into
    the OSD's crosshair (`OsdRenderer.crosshair(g, cx, cy, unit, color, outline)` at the end, so it sits pixel for
    pixel on the real one) and turns white. `gui/Logo` draws the mark from its SVG; the icons (`AppIcon`, the .ico, the
    phone app's launcher PNGs) are the mark *without* its brackets, 1.75 x bigger (`Logo.paintIconMark`); only the
    boot animation keeps the brackets. The launcher PNGs were generated with a throwaway test (scratchpad `IconShot`). The phone has `BootRevealView`.
  - Measured on a far phone with pc.14 (1.0.21): 720p ~4-5 damaged frames/s from random packet loss, which is what
    the parity is for. **Not yet measured with pc.15/16:** the size switch gap, the parity's `recovered` count; the
    phone GPS on a real phone; the map on a real flight.
- **1.0.24 / phone app 2.3.0-pc.18: the in-app updater** (overlay `AppUpdater`, `AppUpdateReceiver`).
  Every update through the browser left a `DRAUPNE-Flight-*.apk` in the phone's Downloads, which Android doesn't let
  the app delete. Now the update line's tap downloads into the app's cache, checks GitHub's SHA-256 (`digest` of the
  asset, `UpdateCheck.sha256`; no digest, no check: Android's signature check still holds), installs with
  `PackageInstaller` (REQUEST_INSTALL_PACKAGES; "Install unknown apps" asked once) and deletes the file; leftovers go
  at start. Refused while connected, checked again before the install and before Android's prompt: installing ends
  the app and the sticks. A note on the first start of pc.18 opens Downloads for the old installers. Phone setup says
  to use the update line when pc.18+ is already there. **Never run on a phone or emulator**; the first real test is
  pc.17 to pc.18 through the browser, then pc.18 to the next version in the app. (The draft lived in the private
  repo's `wip/in-app-updater/`.)
- **1.0.24: DOWNLOAD AREA**, the map saved for offline use like Mission Planner's prefetch
  (`TileCache.download`, `MapDownloadDialog`; the map window's button, Alt + drag a box, or the right-click menu's
  "Download this view"). One START takes every tile of the area for all four pictures, one after another, from level
  2 to each picture's deepest (or one picture, a narrower range), coarse first, those on disk skipped, at most
  250 000 tiles a go; two connections, OpenStreetMap one with a 250 ms pause (its policy forbids bulk
  downloads; the pilot chose to include it anyway - if OSM ever blocks the app's User-Agent, drop it from
  `download`). The rest prefetch is unchanged (never OSM). Tried off screen only, never with a server.
- 1.0.21 brought video for bad coverage (AUTO / SHARP / ROBUST, parity packets, fast size switch) with pc.15.
- 1.0.20 bundled phone app 2.3.0-pc.14: the fix for pc.13,
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

- `gradlew.bat test`: 226 tests (protocol, video, telemetry, calibration, Betaflight CLI plans, update check, cell
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
