# Handoff

Everything needed to pick up work on DRAUPNE Link from a new session or another computer. Read this first, then
[README](../README.md) (what it does, troubleshooting), [CHANGELOG](CHANGELOG.md) (what changed when),
[PROTOCOL](PROTOCOL.md) and [ROADMAP](ROADMAP.md).

## Where things stand (2026-11-13, 1.0.65 + phone 2.3.0-pc.52 released; 1.0.66 built, NOT released; last flown: 1.0.43)

- **1.0.66 (PC only, phone pc.52 stays; built 2026-11-13, NOT committed, NOT released - the pilot's "release" is awaited; not tried by the pilot):** flight time and range left, the pilot's figures for a full pack (23 min, 14 km: `FleetCards.FULL_FLIGHT_MIN` / `FULL_RANGE_KM`) times the pack's charge (`cellFill`, the same per cent as the STANAG page's DRONE). On the STANAG page (`StanagShow.stats`: FLIGHT, RANGE) and in FLEET's detail on the GPS line (`FleetManagementPanel.leftText`, `figuresFont`). A third line in the detail was tried first and failed `FleetCardsTest.tenPhonesFitWithoutScrollingOnSmallScreens` - the strip has no pixel to spare at 1093x584, so the figures share the GPS line and its letters shrink to fit. Linear in the voltage, as the gauge: not a discharge curve, and low under load - say so if the pilot finds the figures off in flight.

- **1.0.65 + phone pc.52 RELEASED 2026-11-13** (v1.0.65 Latest: installer, zip, APK; `flight-2.3.0-pc.52`; full suite 1354 green; tried by the pilot in the dev window only - not in ATAK / QGIS, pc.52 on no phone yet: UPDATE PHONE APP on each, then OBSERVE one that sits in standby). The phone app changed: `Udp.onObserve` wakes a phone in standby for an observer; `flight-app/overlay/Flight/build.gradle` says 2.3.0-pc.52 / 2030052; `ApkShare` pins it: SHA-256 072cd1d3..., signer d910997f...2eb9.. The pilot's report 2026-11-12 ~21:10 ("when trying to connect phone to the observing menu it does not work", "Phone alpha"): the app log said "MobileAlpha: no observation - standby, sleep or overwatch" - DISCONNECT had left it in standby (since 1.0.50 every disconnected phone is), and the phone refuses observers in any mode but normal. No PC-only way out: FLEET_MODE knows no "awake" on purpose (`FleetProtocolTest`: "mode 0 isn't a fleet mode (WAKE connects)"). PC side: `MainWindow.wakeForObserving` (notes the phones in standby at OBSERVE and at a later REFUSED_POWER; an older app is said once), `restObserved` (STOP OBSERVING / CONNECT on a tile: `FleetController.ask(phone, STANDBY)`), `FleetController.inStandby`, `ObserveSet.STANDBY_WAKE_BUILD` 52; the virtual phone does the same (`VirtualDrone`, `VirtualDroneEndToEndTest.anObserverWakesADroneInStandbyAndGetsItsStream`, `FleetBooksTest.aPhoneTheGridTookOutOfStandbyIsRestedAgainAfter`). Built as an APK and pinned; NOT on a phone yet.
- **1.0.65, what it is (built 2026-11-12 evening; tried by the pilot in the dev window with the demo and one phone, not in ATAK / QGIS):** STANAG 4609 as one stream per drone, see CHANGELOG 1.0.65 and the plan the pilot approved (one stream each, made on the PC; video with OSD only while piloted or observed here; else the logo page with the mode; an alert: "live picture then clip", a new detection or a change of mode ends the clip; ports by name letter; several PCs: "warning only"; overwatch sectors in the metadata). *Where things are:* `gui/StanagController` owns a `StanagFeed` per phone of `FleetController.cards()` while `stanag.enabled`; `refresh()` once a second from `MainWindow.networkTimer` and at connect / disconnect / OBSERVE / STOP OBSERVING / a tile leaving / a spectator's phone change / the overwatch hub's `onNews` and `onClips` / DISMISS; `alertPictures()` eight times a second (`MainWindow.stanagPictures`). It makes the feed of the piloted phone (`Host.livePhone` / `liveVideo`: `PHONE_NAME_KEY`, a spectator's `getPilotPhone()`) and of each `tileFeeds` entry the `VideoPipeline` tap (one tap per pipeline; nothing else uses it). `StanagFeed`: its thread at MIN_PRIORITY picks `StanagShow.pick(video fresh, alert picture, clip)`; an encoder per kind's pace (`PILOT_FPS` 30 / `TILE_FPS` 10 / `CLIP_FPS` 15 / `ALERT_FPS` 8 / `PAGE_FPS` 5), swapped at a change - `StanagStream` keeps the transport stream over new parameter sets of the same size (`sameShape`, `needKey`); the page's picture goes again without conversion (`H264Encoder.encodeAgain`); a clip through `overwatch.ClipPlayer` at its own time stamps; the video through its own `VideoDecoder.oneThread` + `OsdFrame` (shared with `OsdRecorder`; a picture of another shape keeps it, black beside). The piloted OSD is the screen's (`VideoView.lastOsdData` with `setOsdStreaming`, `keepOsdFresh` for a minimized window), a tile's is `MainWindow.tileOsd` (what `refreshObservation` builds). `StanagClips` holds the clip rules per drone (an event first seen already over is never played; a badge change marks all as played); a clip is "whole" when its event ended and no transfer line showed for `CLIP_QUIET_MS` 3 s (`ClipIndex.baseId` finds its files; a second event in the same second - id "-2" - is not found and not played). Ports: `StanagController.slot` (letter, else `stanag.slot.<name>` from 26; `NameService.rename` moves it with the phone's other settings); a drone's own address `stanag.to.<name>` (`ownTarget`, typed over in the settings' list: `StanagDialog.buildLines` / `ownAddresses` - an untouched box of an automatic address stays automatic, so the list follows a new SEND TO); `StanagDialog.plain` reads `udp://host:port` (the pilot's own setting from 1.0.64) as host:port. After the pilot's first try in the dev window (2026-11-12 ~20:30, 14 demo drones to a multicast group): STANDBY's text no longer says how soon it is back; a resting drone never reads PILOTED on the phone's stale "flown" (`StanagShow.page`: `resting` before `flownBy`; `StanagHost.flownHere` for the drone this PC flew a minute ago); the per-drone address boxes. Then: the page's figures (`StanagShow.stats` / `Stat`, `Page.with`; `StanagPagePainter.stats` - phone battery, the drone's pack in per cent (`dronePercent` = `FleetCards.cellFill`, the pilot wanted no cell voltage), HEARD and NEXT CHECK as clock times to the minute so a page stays a still picture; low in yellow under 20 %). The page's logo is "DRAUPNE FPV" and as wide as the picture takes (`StanagPagePainter.LOGO_*`; the pilot: bigger, and FPV for Link). DEMO: the six stand `FleetLayout.BESIDE_M` 100 m beside the layout's places (`FleetLayout.beside`, `TourShowcase.addSix`) - they had the layout's first four places, exactly where SET UP SCENARIO puts its four (`DemoFleetTest`). KLV: `StanagKlv.frame` (designation = the drone's name; an overwatching drone's `CameraFootprint.sitting` view from `MainWindow.watchedView`, corners from the outline where a corner ray meets no ground), the piloted drone's `CameraView.klv(..., designation)`, a tile's from its observer record. PC to PC: `Names.reply(..., stanag)` block 2 / `Reply.stanag()`, `NameService.setStanag` / `stanagStreams()` (fresh 60 s), `StanagController.clashing` / `warning()` / `announced()` (never the loopback). *Measured (this PC, `StanagPerfTest`):* a page picture 1.4 ms and 36 kbit/s still; a video picture with OSD 13.9 ms (decode 0.9, picture + OSD 0.8, encode 12.2). *Tests:* the full suite 1354 green on the final tree (a scratch copy, 2026-11-12 ~21:50, with the observation fix, the page figures and the per-drone addresses; pc.52 APK built after: `build/flight/DRAUPNE-Flight-2.3.0-pc.52.apk`, SHA-256 072cd1d3...aa1a9 - publish THAT file as `flight-2.3.0-pc.52` and pin it in `ApkShare`, or build anew and pin the new one); before that 1346 green ( the STANAG, names and encoder tests once more after the last small change - a stream that simply goes says nothing in the log); `NamesTest.theServiceNamesThePcAndTheNamelessPhone` failed once beside the heavy stream tests and passed alone and in the full run (a timing flake under load, not followed up); a throwaway run of three page streams to loopback ports nobody listens on: 14 s, no error, no log line. *NOT DONE, and why:* nothing played in VLC / ATAK / QGIS yet (the 5 pictures a second of a page, the swap of parameter sets mid-stream and the sector's corners are the things a player may not like - if a player stalls on pages, raise `PAGE_FPS`); with OSD recording on, the piloted video is decoded and OSD-drawn twice (recorder and stream each their own - could share one); a tile's stream takes its picture's mirror for "not mirrored"; the clip rules follow FLEET's badge, so on a PC that did not set the overwatch (its badge comes from the phone's own word and may flap to ACTIVE / UNKNOWN between scans) a clip can be dropped unplayed; a drone flown from another PC shows a page here (its video is on that PC - a relay PC to PC was not asked for); no sound in the streams. *TO TRY:* "open it here" + the virtual fleet, STANAG on, VLC on `udp://@:15000`, `:15001` ...: a page per mode; PILOT UAV on one (video + OSD on its port, the others stay pages, DISCONNECT: its page again without the player stopping); OBSERVATION (each tile's port); an overwatch walk-past (red bar live, then the clip under the amber bar, then the page) and during the clip a second walk-past, PILOT UAV, DEEP SLEEP from FLEET; `ffprobe udp://127.0.0.1:15000` (h264 1280x720 + a KLV data stream); a TAK client for the sector; two PCs to one TAK address for the warning. Found by the pilot in the dev window 2026-11-13 and fixed: after an alert (DISMISS, or its end) or a clip the stream stood on that last picture "for a while" - `StanagFeed` paints the alert's picture and the clip on the page's own canvas, and a page equal to the one painted before (same mode, same minute in HEARD) was not painted again; `painted = null` where the canvas is drawn on (`StanagFeedTest`: the same page before and after an alert, and after a clip).

- **NEXT (1.0.64 + phone pc.51 released 2026-11-12 on the pilot's "Finish up then release", untried on hardware):** FIRST the props-off checks of the second review round's bullet below (re-arm after a link loss, the PC app started while armed, LOST DRONE) - before anything flies. The phone app is pc.51, not pc.50: pc.50 exists only as test builds on two test phones, made before the second round (same versionCode as a release would have had, so it could not be told apart or updated); code comments tagged pc.50 are in pc.51. Still open from before the release: (1) battery figures on MobileDelta (adb over USB) - 30-60 min unplugged in deep sleep every 5 min, in listening deep sleep, and in quiet overwatch by day and night; read the charge counter per window from `adb shell dumpsys batterystats` (the 18:xx overwatch figures in the bullet below are the baseline); (2) the sleep notice on hardware: FLEET and link DEEP SLEEP / OVERWATCH - the PC log line, FLEET's countdown, the heading pointer on the map; (3) a CONNECT to a listening deep sleeper (no wake lock now: how long it takes); (4) AIRSPACE with the pilot's openAIP key (request format from openAIP's published API, untried) and a NOTAM pasted from IPPC.no (parser tested on ICAO-format samples only). (The release itself is done.)
- **Second review round 2026-11-12 (in 1.0.64 + phone pc.51; NOTHING OF IT TRIED ON A PHONE OR DRONE):** a second full review (five readers: phone power, the link in poor reception, detection in overwatch, the PCs' sleep / fleet books, UX), then the fixes by six workers in scratch copies with disjoint files, merged by hand; the findings are the CHANGELOG's "Second review round" bullets. *Detector* (`MotionDetector`: the night gain from the middle half of 10 x 10 cells and a background that follows it, the upright run's ends kept at LOW / NORMAL by day; `Looming`: the slow look over 18 s with a hold against absorbing, LOW / NORMAL only - both rules alarmed in the gale on HIGH and are off there). Tuned on made-up pictures: `NIGHT_CELL_LUMA`, `SLOW_ALIVE`, `SLOW_MAX_AGE` are guesses until there is real footage. Left: a drop or insect on the lens still alerts through the NEAR rule (a guard broke `someoneInViewWhoWalksOffIsFound`; its prototype was not kept in the repo); the night's shift search follows a bright moving patch (two ids for one pair of headlights); a 2 px walker (about 55 m) and seed 15's approach are not found. Where things are - *safety:* Common `RcPath.IdleGuard` + `Udp.idleSticksRun` (no idle sticks after a frame at an armed / unknown FC, or with ARM on); `ControlLink.sticksUnheard` + `RcSession.watchLoss` (fresh DISARMED for `DISARMED_CONFIRM_MS` with ARM on stops the sticks; never on ARMED or unknown) and `readingFc` / `FC_READ_WAIT_MS` ("RC OFF - READING FC"); `MainWindow.LostDroneShow` / `linkBack`. *Phone link:* `Udp.pcSilentTick` + `StreamEncoder.holdAtFloor` / `releaseFloor`, `bufferRestMs` + `packetWaiting`, `sendSleepHello` to `alertPcs()`, `RcPath.restart(now, samePc)`, the key frame's second copy skipped when a newer one waits (it can't go after the queued frames: `FrameAssembler.retireOlder` lets a gapped key frame out at the next whole frame). *Phone overwatch:* `OverwatchEvent` (ENDED at `MAX_CLIP_MS`, `AGAIN_EVERY_MS`, `resendOwed` / `STRAGGLER_MS`; `STOP_CLIP` is gone), `Overwatch` (`owed`, `pace` 10 Hz / 2 Hz, `eventOver`, `previewJoined`), `CameraWatchdog.Action.SAY_BLIND`, `InternalCamera` (`sessionWatchPicture` / `watchPictureRefusedFor`: the watch's reader in an event's session, with a fall-back), `MotionWatch` (`WatchRun.frameGateMs`, `setSensorClock`, all on `elapsedRealtime`), `PreRoll.add(mono, wall, jpeg)`, `PowerMode` (`guarded`, alarms `ELAPSED_REALTIME_WAKEUP` set first, the stale window, `closeWindow` counts from `windowOpenedAt`), `DDService.stayAwakeToClose`, manifest `USE_EXACT_ALARM`. *PC link:* `TierPolicy` (`entered`, `UP_SETTLE_MS`, `Link.frameLoss` from `VideoPipeline.frameLossRatio`), `ControlLink` (`phoneHearsNothing` from the newest of pong / answer / ready, key-frame request and Disconnect x3, VIDEO_PROFILE twice more, Connect spacing over 700 ms ping, no "sleep on" behind a FLEET mode, tier names as the menu's), Common `UdpSender` on a `ScheduledExecutorService`. *Books:* `PowerController` (`nameReply`, `Asleep.setHere` saved as `|other`, `sleepOn` / `SLEEP_ON_AFTER_MS`, `holdConnects(now, wall, hello)`, `missedCheckIns` from the wait's start, `watchWord`), `NameService` (`rename` / `Assign` resent 30 s, `setPhoneReplies`, `heardCheckIn`, `saidFreshMs`), `ScanPace` (the hello's interval), `FleetManager` (`expire` / `KEEP_MS`, `flown`, `setPilotHere`), `FleetController` (wires them; `renamed`, `overwatchTrouble`), `OverwatchHub` (`expect` / `taken`, `Word` / `Trouble`, DISMISS x3, an unstored event still rings), `ClipTransfer` (gaps only, 1.5 s without progress). *GUI:* `OsdDataBuilder.In` (`awaitingWake`, `wakeInMs`, `tier`), `MainWindow` (`alertingNow`, `ringQuietAlerts`, `refreshWatchLost`, `modeAsked` / `MODE_TAKE_MS`, `dropQueuedFor`, `save` = `PcConfig.apply` + `write` on `settingsWriter`, `videoCloser` / `awaitVideoClosed`, `SLOW_REFRESH_MS` 160), `AlertSound.RING_KEY`, `FleetCards.Card.blind`, `MapView.NetDrone.blind`. Tests: the full suite on the final tree 1286 green (2026-11-12, a scratch copy), after `SpectatorTest.aCongestionPauseLeavesNoGapAndIsAnnounced` was made to wait for the announcement it checks (the "congestion flake" of many release runs: the host sets its flag a moment before it says so); the phone compiles (`phone-compile.ps1`; on a second run in a long-path copy delete `buildlight-src` first). NOT DONE, and why: USB-camera phones in overwatch (no camera to try; found on the way: `UsbCamera` has no `setExtraTarget`, so their events record NO clip while MOTION ENDED says one follows); an event whose recorder fails to start stays unrecorded and its preview encoder is fed all along; DEEP SLEEP -> OVERWATCH still goes through a full wake; noise reduction in the watch-only request left on (the dark figure 785-1030 mA: switch it off and measure current AND false alarms together); the key frame's second copy after the queued frames and a longer key-frame interval in the weak tiers (need the PC's assembler to hold a gapped key frame); the reconnect storm's root (`SessionGate.SAME_SESSION_MS` - with the session id, still left for after this round has flown); `MirrorCodec` doesn't carry the blind flag (a spectator sees it only in the hover text); `Udp.wrongKeyAt` still on the wall clock (MainActivity reads it). MIND: PCs take phone build 51 as "says hello to every PC" (`PowerController.HELLO_TO_ALL_PC_BUILD`; the pc.50 test builds on the two test phones say it to one PC, and count as that); the PC that set a sleep now answers about 3 s into each window (the phone is awake that long per check, was 0.3 s). TO TRY, props off, before anything flies: (1) arm, cut the link until Betaflight's failsafe disarms, bring it back with ARM still on - it must NOT arm, the phone's log says "Idle sticks held back", the PC "Link back - FC disarmed - ARM off to fly again."; then ARM off, STANDBY MODE: idle sticks again; (2) close and start the PC app with the FC armed: READING FC, then "sticks live", no arm-off frame; (3) LOST DRONE after 5 s and its question when the link is back. Then overwatch: a 20 min walk-about (a new event with its ring and clip at 15 min), two PCs with one switched off (the log's "rests for the others"), the event's detection picture (the phone's log: no "refused the watch's picture"), a floor drop (the PC hears it within seconds), 30-60 min unplugged for the battery figures; deep sleep every 5 min from a second PC (it shows the countdown, a mode queued there lands), PILOT UAV after two missed check-ins; the link: block PC->phone 5 s (video at its floor, back by itself), AUTO on a weak link (no bouncing, ROBUST on the OSD).
- **Review round 2026-11-11 (in 1.0.64 + phone pc.51):** a full review (power in overwatch / deep sleep, the link in poor reception, detection in overwatch, UX); the findings and the fix plan are in the CHANGELOG's "Review round" bullets. Done and tested (full suite green before the NOTAM / dialog edits, their classes after; phone compiles, a pc.50 test APK built and started on a test phone over adb - no scenario run on it yet): `OverwatchHub.onAlert` (start time = a new event) + `AlertCard.forget` + `Overwatch.lastEvent`; `ControlLink.phoneHearsNothing` / `PONG_LOST_MS`; `PowerController.wokenOnThePhone` + `NameService.saidMode`; `FcArmedState.closedDisarmed`; `OverwatchProtocol.missedDue` / `endedDue` + `Overwatch.missed`; Common `WatchRun` in `MotionWatch.tick`; `ScanPace.heard`; `Udp.answerName` built on the answer thread, `PowerMode.replyWhileAsleep`, the window lock never shortened (`lockUntil`); `SleepCheck.WINDOW_HELLO_LATER_MS` 3 s; telemetry loop on `elapsedRealtime`; `VideoPipeline.requestKeyFrame` not while disconnected; `RelayGate` keeps its count through an unknown ping; `Dialogs` document-modal; `NotamParser` HEAD / `replaced` / `WIDE_NM` / `endEstimated`. The pilot's answers (2026-11-11 late), built: alerts to EVERY PC (`Udp.alertPcs` = the commanding PC + `knownPcs` of the last day; `Overwatch.pcs` / `alertHave` / `endedHave` / `preRolled` / `previewFor`, commands answered to their sender; `OverwatchHub.setOurPhone` takes a foreign token's ALERT for a phone in the name map at its own address); the ring stays as it is (it already is a sound of its own, once); no alert-storm hold-off; new motion after DISMISS alerts again (`OverwatchEvent.onDetection(now, cx, cy)`, NEW_AFTER_QUIET_MS / NEW_FAR / NEW_MIN_AFTER_DISMISS_MS); the session id later (my recommendation: fly this round first). Also built after: StreamEncoder / Serial timers on `elapsedRealtime`; `Names.movedSettings` on rename; `phoneUpdateSaid` 10 s; no re-pick after CRASHED; the phone's OVERWATCH pill / status and `dd_permission_refused`; old button names; the clip player's picture ring; the pulse at 6/s while possibly flying. Then (2026-11-12, the pilot: "fix everything with your recommendations"): the preview encoder fed only while a PC asks (`InternalCamera.setStreamTarget` / `streamLeftOut` - the surface stays in the session, out of the repeating request; `Overwatch.tick` sets it from `previewing`; only beside an event's recorder); a frame-rate change on the open camera (`Udp` `sizeOnly` no longer asks for the same fps - `startVideoStream` reads the rate anew for the camera, the encoder and the request); `TierPolicy.outage` from "Link back"; `PowerController.missedCheckIns` / `connectText(..., missed)` + the button's tooltip; the power mode's silence logged (`powerSilentSaid`; `powerStatusText` itself stays off the cards - it looks removed on purpose); standby notice clears the asleep record; `Card.alertSeenAt`; `MapWindow.airspaceAsk`; `MainWindow.saveFromMap`; `WindowsSetup.desktopShortcuts` / `desktopLinks`; TourSpectator "Press WATCH"; MotionDetector's track cap culls the newest unconfirmed; `Udp` skips `MotionWatch.tick` while `Overwatch.isWatching()`; `DDService` keeps GPS and the FC's port off while asleep; `Udp.senderError`. BOTH CAMERA CHANGES ARE UNTRIED ON A PHONE WITH SOMETHING MOVING: try an overwatch event with the card open (preview comes within a second or two of asking, the clip is whole) and with it dismissed, and force bad coverage <-> survival (VIDEO) and read the phone's log for "Picture size changed on the open camera". Left on purpose: a session id (after this round has flown); the reliable layer's delay estimate on paths over 1 s (its nine copies of a packet are also its loss protection there - not without a link test); the detector's range and night detection of unlit people (a tuning job on real 320x240 pictures: record the pilot walking at 10/20/30/40 m by day and dusk first); REGAINING CONTROL's press still stops the waiting (now said in its tooltip). Last batch (same night): `TierPolicy.HOLD_FORGET_MS`; `OpenAipAirspace.failed` / `mayAsk` / `Fetcher` (the test's seam); `MapView.setAirspace` keeps the info box by id; `PcConfig.save` sets the properties first and retries the move. Also left: `WindowsSetup.desktopShortcut` checks only the shared desktop's shortcut when there is one (a broken one on the user's own desktop stays); the map window's bounds are saved on the Swing thread. To try on a phone: overwatch twice with the PC left open (second alert rings); OVERWATCH within a minute of a disarm (log says "Motion detection on" at once); a walk-past with the PC app closed, then opened (late alert); block PC->phone UDP 6 s (link returns by itself).
- **1.0.64 + phone pc.51 (released 2026-11-12; flight-2.3.0-pc.51 SHA-256 fdd1d373..., signer d910997f...2eb9, pinned in `ApkShare`; the power work below was built as pc.50):** see CHANGELOG 1.0.64. The power review (the pilot: "what uses power when in overwatch ... calculate the battery time"), measured on MobileDelta (4050 mAh) with `adb shell dumpsys batterystats` (charge counter per window), `dumpsys media.camera`, `top -H` and `/proc/<pid>/task/*/status` wake-ups: quiet overwatch 440 mA by day (7.4 h to the 20 % floor), 785-1030 mA in the dark (3-4 h), events 1.05-1.23 A, standby 662 mA. The sensitivity level hardly matters (detector ~11 % of a core, HIGH ~+40 % of that, about 1-2 % of battery time). pc.50: `AnalysisSize.pickWatch` / `centreCrop` / `watchFps`, `MotionWatch.setVideoShape` (offset shrink), `InternalCamera` watch size + range + OIS/edge off + `spareReader`; `Udp.dormant()` / `dormantFcClosed()`; `OverwatchProtocol.resendEvery`; `Udp.stopAudioVideo` always closes `mp4Recorder` (after the camera); `DDService` no mic pre-open; PhoneHeading stopped at sleep. NOT yet measured after the change: unplug a phone 30 min in quiet overwatch and read the charge counter. Also the update fixes: `SelfUpdate.installedApp()` from any packaged copy, helper `EnsureShortcut` repairs, `WindowsSetup.ensureShortcuts` / `needsRepair`; and the clip list scrolling (`OverwatchPanel.Column`) and card minimize. Tests `WatchPowerTest`, `SelfUpdateTest`, `OverwatchCardTest`.

- **1.0.63 + phone pc.49 (released 2026-11-11; flight-2.3.0-pc.49 SHA-256 53d3b842..., signer d910997f...2eb9, pinned in `ApkShare`; tried on Foxtrot over USB: overwatch started its camera and alerted):** see CHANGELOG 1.0.63. The pilot: "if I walk in front of it 5 m away while connected I get the movement box, however if I do the same in overwatch I get no warning". The log: MobileGolf's first overwatch alerted; after CONNECT from the alert and OVERWATCH again its check-in said "OVERWATCH SEES NOTHING". Foxtrot's logcat (USB, `adb logcat -s DDLog`) then showed the cause: `MotionWatch.tick` set `analysisOn` false (camera not started) between `startPreview` and `onConfigured` (143 ms), so the watch-only request had no target and never started - a race, hence "sometimes"; `onConfigured` now adds the picture whenever `watchOnly`. Also the code had no way back from a lost camera: `InternalCamera`'s `lost()` (onDisconnected / onError) and a refused watch-only session left overwatch blind, and `analysisRefusedFor` (a refusal beside a video) made `canWatchOnly()` false for good. Now `CameraWatchdog` (Common; `Overwatch.watchdog` on its worker) reopens via `Udp.overwatchCameraAgain` (never a camera a pilot or observer took since), `Camera.blindReason()` says why, `OverwatchProtocol.STATE_BLIND` / `BLIND_*` go to the PC (`OverwatchHub.onStatus` logs once per token), and the watch-only session ignores `analysisRefusedFor`. Also the countdown: `PowerController.nextCheckMono` / `awaitedIp` / `linkHello` (LINK card and FLEET from the same check-in), and `DetectionController.sayNoPicture` after `NO_PICTURE_SAY_MS` 3 s. Tests `CameraWatchdogTest`, `OverwatchHubTest.aBlindCameraIsToldOnceAndItsReturnOnce`, `DetectionControllerTest.aMomentWithoutAPictureIsNotSaid`, `PowerControllerTest.theLinkCardAndFleetCountFromTheSameCheckIn`. A USB logcat (`adb logcat -s DDLog`) of a phone repeating the test would confirm the cause.

- **1.0.62 + phone pc.48 (released 2026-11-11; flight-2.3.0-pc.48 SHA-256 1af081f5..., signer d910997f...2eb9, pinned in `ApkShare`):** see CHANGELOG 1.0.62. The pilot: "the orientation in map does not match the aircraft orientation, both the icon and sector, when in Overwatch". MobileGolf / MobileHotel have no GPS module ("drone GPS has none"), so no compass: Betaflight's yaw was drift. `LiveHeading.of(FcTelemetry)` picks the FC's yaw with a compass (`FcTelemetry.fcCompass()` from MSP_STATUS' sensor bits, or the record's FC_COMPASS), else the phone's azimuth from the observer record (pc.48 `Udp.pilotHeadingTick`: `PhoneHeading` on while a pilot is connected, the record to the pilot 5/s), else NaN for a phone that says no compass (an older phone app: the yaw as before). Used by `CameraView.pose` (live view, detection marks, overwatch pose) and `MainWindow.refreshMap` (`lastHeading` kept for the flown phone after disconnect). `MapView.arrowHeading`: the GPS course only while moving (`COURSE_KMH` 4), -1 = a round mark. `FleetController.overwatchPose`: `Host.lastPose` (this PC's last connection) else the hub's old pose, null beyond `MOVED_M` 25 m. `VirtualDrone` reports SENSOR_MAG. The OSD's heading tape still shows the FC's yaw. Tests `LiveHeadingTest`, `HeadingOnMapTest`, `VirtualDroneEndToEndTest.theMapsHeadingIsTheVirtualDronesOwn`; full suite 1117 green. Not tried on a drone.

- **1.0.61 (released 2026-11-11, PC only, phone pc.47 stays):** see CHANGELOG 1.0.61. DEMO round: the other pilot (`TourSpectator(..., observeThese)`) observes `TourShowcase.forOtherPilot` (convoy, patrol, mortar) instead of three plain drones - those made 14 with the scenario, over `Fleet.MAX_DRONES` 12; `VirtualPilotPc.observe` wakes drones not in NORMAL first. `TourShowcase.placeDemoModes` (2 STANDBY, 2 DEEP SLEEP, 1 idle, 1 either, shuffled; no OVERWATCH - an alert would give the scenario away) from `DemoMode.tick` after `PLACE_AFTER_MS`. `MainWindow.demoObserve` makes the six OBSERVATION's set (put back at End demo); `startObservation` in the demo wakes them via `DemoMode.observing` and clears this PC's asleep / standby record. `Fleet.varyBatteries` on every `Fleet.add` (pack 3.85-4.18 V a cell, phone 28-97 %); the other pilot's drone `newBattery`. `DemoFleetTest`. The other pilot's empty tiles in the pilot's test on another PC: cause not found from the code (no log here); the new path streams in `DemoFleetTest`. Not tried in the real window.
- **1.0.60 + phone pc.47 (released 2026-11-10; flight-2.3.0-pc.47 SHA-256 dd9b9371..., signer d910997f...2eb9, pinned in `ApkShare`):** see CHANGELOG 1.0.60 and the notes at the end of the 1.0.59 line (the freeze fix, the tour round, the modes round, the clip timeline, DEMO mode, pc.47). Not tried in the real window or on a phone. Open: the overwatch miss on HIGH (retest with pc.47: the log says "OVERWATCH SEES NOTHING" if the camera feeds no pictures); which notification every phone gets when one is piloted (asked).
- **1.0.59 (released 2026-11-10, PC only, phone pc.46 stays):** see CHANGELOG 1.0.59. Scenes: no screen effects (`SimVoid.screen` / `SimWorld.effects` removed), ghost blocks `Raster3D.GHOST_BLOCK_M` 1.1 with outline rims only, floor grid 4/16 m, nets `Raster3D.groundSkirts` (exported skirts ended 0.43 m up), `DroneLens.apply` bilinear; grass under the drone as in 1.0.58 ("less grass" was taken back). `VirtualDrone.VERSION` = `ApkShare.VERSION`. Tile OSD `ObservationOsd.SCALE` / `MIN_UNIT` via `OsdRenderer.setScale`. Tour: `TourShowcase.wakeAll` before OBSERVER; the map window always opens maximized (`MainWindow.toggleMapWindow`; the tour's un-maximize is gone). `SlideMenu` items unfocusable while shut. The tour's MOVING AROUND freeze (seen on 1.0.58 and 1.0.59) is found and fixed on main, not released: a deadlock - FlatLaf asks `Tour.TourNotice.Dim.captionAt` from the AWT-Windows thread, and `convertRectangle` took the AWT lock (window `getLocationOnScreen`) while the event thread held it in `validate()` waiting on that thread for the cursor. `Dim.content()` now sums parent offsets (`inRoot`), no lock; `TourTest.theTitleBarQuestionNeverWaitsForTheAwtLock` hangs on the old code. Anything handed to FlatLaf's `COMPONENT_TITLE_BAR_CAPTION` must stay lock-free. Also on main, unreleased: the MAP TOOLS step moves on once pressed (`Step.pressMovesOn`); PREVIOUS FLIGHTS lights a `Tour.Stand` (a stand-in for the dialog in the map window) with the dialog moved over by `Tour.roomBeside`, and a box with no room above or below goes beside its control (`TourNotice.side`); the tour scenes' backdrop is the finale's (`SimVoid` palette = `SimWorld` day, `Raster3D.GRID_M` 10 / 50, no ghost trees, `SimWorld.paintStructures`); the finale has `BtrSurroundings` (props off the road, nothing above 0.4 m in front of the route; drawn through `Raster3D.Frame` with its own `fog` / `haze`, the vehicle's depth seeded from it). BTR tests now find the vehicle against `BtrScene.render(..., withVehicle=false)`. Then the modes round (phone pc.47 with it, unreleased): `MapView.Kind.STANDBY` (last: the mirror sends ordinals), signs arrow / diamond / moon / eye / triangle / square, a queued mode never on the sign but `MapView.paintQueued` beside it (`NetDrone.queued`, `Card.queuedAgoMs`; `MirrorCodec` writes both after its lists so older PCs read on), `FleetCards.withQueued` gone; FLEET's PILOTED / STANDBY / OVERWATCH / DEEP SLEEP with `NotPossible` (shake + LockedNotice) on the current one and the queued one dropping on a second press, CLIPS and a CHECK EVERY combo; `MainWindow.fleetRequest` handles the phone this PC flies (STANDBY = STANDBY MODE); the LINK card's DEEP SLEEP / OVERWATCH never fold and `markModeButtons` greys the current; `areasButton` (glyph AREAS) by the video; `pickedOnMap` / `MapWindow.selectFleet` keep the map, FLEET and PHONE list on one phone. Phone: `Silent` (ACCESS_NOTIFICATION_POLICY, ringer silent while the service runs), `MainActivity.BOOT_START_MS` 300. No MainWindow-level test of the areas button or the pick sync. Also: overwatch clips play as one timeline (`overwatch.ClipTimeline`, `OverwatchPanel.runPlayback`); REGAINING CONTROL IN m:ss; the overwatch miss on HIGH (walk 5 m in front, MobileGolf 2026-11-10 20:41-21:01, indoors, whole picture boxed HIGH) is NOT found: the detector catches that walk on synthetic pictures both ways (a test harness, not in the suite); pc.47 makes the check-in say whether the detection gets pictures (`SleepHello.watchesBlind`, logged by PowerController) and stops an event's refused session from switching the picture off for good - a retest with pc.47 tells which. The 'every phone gets a notification when one is piloted' item: no such notification found in the code; the pilot was asked which. DEMO mode (`gui/DemoMode`): one `Fleet` shared by `TourFinale(main, () -> fleet, "Demo: ")` (showcase + scenario via `TourShowcase.startIn` / `TourScenario.startIn`, `closeScenario` for SCENARIO AGAIN) and `TourSpectator(main, () -> fleet, "Demo: ")` (`start`, `observe`, `flyAgain`); the strip in `FleetManagementPanel.setDemoStrip`; the spectator `Card` in the root's PALETTE_LAYER; End demo = `TourFinale.close` (TourHost.cleanUp) then the fleet; the demo and the tour refuse each other; the scenario is never named on screen (the pilot: a surprise) - `DemoModeTest`. Not tried in the real window.

- **1.0.58 + phone pc.46 (released 2026-11-10; flight-2.3.0-pc.46 SHA-256 ac83aae6..., signer d910997f...2eb9, pinned in `ApkShare`):** see CHANGELOG 1.0.58. Detection (`MotionDetector`, HIGH only, per area): the fine picture (`fineForeground`, 2 of 4 fine pixels, camera steady), `halfLeast`, calm-pixel shift (`SHIFT_CALM_SD`/`_FLICKER`), one-off excursions (`learnFine`, `ONE_OFF_*`), long-leg proven (`LEG_*`), a confirmed track's pixels not flicker. Areas: `DetectionAreas` (Common; wire block after DETECTION_SET's byte and after the overwatch token), `MotionDetector.setAreas` -> `levelAt` per working pixel (OFF never foreground, judged by the level where a blob or track is, nothing reported from OFF; Looming per pixel too), `MotionWatch.areas` (kept on the phone), `VirtualDrone.detectionAreas`, `DetectionController.areasFor/setAreas` (`detectionAreas.<phone>`), `DetectionAreaLayer` on `VideoView`. Measured on the synthetic gale with a steady camera (5 seeds x 2 shakes x 5 min): NORMAL 3 alarms, HIGH unboxed many (the trade-off; `onHighTheSwayingLeavesBoxedLowOrOffGiveNoAlarm`). Synthetic sentry (`onHighACamouflagedSentry...`): HIGH > 60 %, NORMAL < 10 %. Known limit: the fine stage needs a still camera (tremble above ~0.35 working px a picture turns it off). Scenes: `ShowScene.BACK_M` (the drone 4 m behind the layout), `ShowScene.eye` (lens `GROUND_EYE_M` over the grass, `UPTILT_DEG`, `REST_ROLL_DEG`, blended into the flown view by 1 m), `DroneLens` (near-ground defocus and grass blades in the kept background, barrel bend on video and analysis alike, vignette on video only), `REAL_TREES_M` 4 (treelines behind the action are ghosts, as in CONVOY - read "the btr scene" as CONVOY; HIDE is "the camouflaged BTR"), HIDE's sentry across the BTR's front (put back). `TourSceneDetectionTest` on HIGH: the patrol (> 80 % of pictures boxed) and the sentry (> 70 %), with the real treeline (`ShowScene(..., realTreesM)`) and as the tour shows them. Tour: `ObservePicker` marks the tour's six (`MainWindow.tourMarkDrones`). Not tried in the real window.

- **1.0.57 + phone pc.45 (released 2026-11-10; flight-2.3.0-pc.45 SHA-256 1b5f2c69..., signer d910997f...2eb9, pinned in `ApkShare`; release run 42760033946 green on the second try, SpectatorTest congestion flake on the first):** see CHANGELOG 1.0.57. Freeze fix: Direct3D off (`Main.main` first line + jpackage `--java-options`; all three freezes in the app log were the Swing thread in `D3DRenderQueue.flushBuffer`). Muted-tile ring: `OBSERVE_STATE` flags byte, bit 0 sound heard (`Observe`, Common `VoiceActivity`, `ObservationSession.speaking`). Map: no AUTO, `TileSource.at` per view centre (`MapView.effectiveSource`). Tour: `Tour.buildSteps` (no LIVE, STANDBY after `WATCH_MS`, MUTE AND LISTEN), `TourSpectator` (free PC name, `tourHost().begin/forget`), `VirtualPilotPc(name)` never naming master, `SoundMaker.beeperless`. Scenes: `ShowScene` DRONES/CONVOY/PATROL/CHECKPOINT/MORTAR/HIDE, `Raster3D` textured, `SimVoid` (the Animus-like look; video-only effects kept out of `VirtualMotionWatch`'s analysis render), models `model/tactical.bin.gz` from `tools/model/export_tactical.py` (credits THIRD-PARTY.md). 1069 tests green; phone built and verified. Not done: a MainWindow-level test of the X clean-up; a ghost tree hides a drone behind it (see-through cost 36-59 ms a frame). Nothing tried in the real window except the tour up to PILOT OBSERVES.

- **1.0.56 + phone pc.44 (released 2026-11-09; flight-2.3.0-pc.44 SHA-256 c160190e..., signer d910997f...2eb9, pinned in `ApkShare`; release run 42700364910 green first try, 24 min):** see CHANGELOG 1.0.56. Close-up motion detection in Common `MotionDetector` (histogram-peak exposure gain the background follows while something big is in view, room-only shift with a hold, `oneThingInFront`, `NEAR_*` rule with the ground's band detail, a shadow's edge told by the detail behind it in `leavesChange`; tests in `MotionDetectorTest` "close to the camera"). Known, not new: a leafy branch right in front of the lens raises alarms through the old travel rules; overwatch STANDBY / preview sender fix (`PowerMode`, `Overwatch`, 0791721). Then the pilot's round in 4 merged branches: spectator mirror (`Mirror`/`MirrorHost`/`MirrorClient`/`MirrorController`/`MirrorCodec`, 0x5A-0x5F, `fleet/VirtualPilotPc`), LINK/fleet (`LinkFleetRoundTest`, `phone.overwatchCheckMin`), tour (`TourShowcase`/`ShowScene`/`Raster3D`, `model/props.bin.gz` Kenney CC0, `TourSpectator` after STOP OBSERVING; the dim's `COMPONENT_TITLE_BAR_CAPTION`), misc (`Dialogs` for every dialog, `TileCache.loading`, desktop shortcut in `SelfUpdate`, clip window, RC check in TOOLS). Not tried in the real window.

- **1.0.55 + phone pc.43 (released 2026-11-09; flight-2.3.0-pc.43 SHA-256 fb6412c8..., signer d910997f...2eb9, pinned in `ApkShare`; release run 42649582108 green first try, 16 min):** see CHANGELOG 1.0.55. Real-phone motion detection (Common `AnalysisSize`, `MotionWatch` block average), `CameraTurn` (one
  upside-down setting per phone for every view), `ObservationOsd` (observers get FC records + FcInfo from the phone),
  tour: `virtual-fleet.stop` request file the fleet program honours, BTR on NEXT only, pins sheet closes, movable box.
  1013 tests; phone compiles.

- **1.0.54 + phone pc.42 (2026-11-09):** the review's maintainability list done, see CHANGELOG 1.0.54. New homes: gui `PowerController` / `FleetController` / `TourHost` / `DetectionController` /
  `OsdDataBuilder`, `pc/TierPolicy`, video `VideoRecording` / `KeyFrameBackoff`, gui `MapProjection` + `Map*Layer`,
  setup `SetupPlanner` (`ModeSwitchGoldenTest` pins every plan), Common `PacketIds` / `Bytes` / `SessionGate` / `RcPath` /
  `Cadence` / `MspFrames` / `MspPages` / `TelemetryRecords`, fleet `VirtualVideo` / `VirtualMotionWatch`. Not done: the card layout builder
  in MainWindow, the drone signs in MapView, observe/overwatch out of Udp. Known flakes under load: SpectatorTest
  congestion, PaintCostTest, PinsAndFlightsTest (re-run alone).

- **1.0.53 + phone pc.41 (2026-11-09, review round):** see CHANGELOG 1.0.53. The approach
  watch is `flight-app/overlay/Common/.../Looming.java` (called from `MotionDetector.process`, day only, its boxes added
  after the tracks'); its tests `MotionDetectorTest` (woodsEdge, Approacher in `SyntheticScene`), `pc/fleet/ApproachDetectionTest`
  (`Scene.addApproacher`), `MotionDetectorPerfTest`. Tuned on made-up pictures only: check it on real footage of
  wind in trees and of someone walking at the camera before trusting the false-alarm rate. Not done, ranked in the
  review: MainWindow split (PowerController, FleetController, TourHost, DetectionController...), `TierPolicy` out of
  ControlLink, one shared packet-id class, `SetupPlanner` out of DroneSetup, Udp.java split, tick-counted timers in
  ReceiverBuffer/Msp to real time (all done since, see above).

- **1.0.52 + phone pc.40 (2026-11-09):** fleet management (queued modes over check-ins), the three-button LINK card, previous flights, pins sheet, CRASHED, the setup round (new drone, Betaflight in two steps, orientation/compass check, RESET SETUP), the rebuilt tour with the BTR finale and Overwatch demo, the virtual fleet in the simulation world (BTR, soldier, real sounds), faster motion detection with colour, scaling fixes. Details in CHANGELOG 1.0.52. Untried on hardware: the accelerometer calibration, board alignment APPLY, align_mag, queued modes on real phones, colour detection on a real camera. Known flakes: SpectatorTest congestion, VideoTest held key frame, OverwatchFleetEndToEndTest.lostArmed (re-run alone).

- **1.0.51 (PC only, 2026-11-08):** README rewritten short and up to date; no visible "4G" left in the PC app (COVERAGE, Net loss, "5G and mobile"). Phone app unchanged (pc.39 pinned).

- **1.0.50 + phone pc.39 (released 2026-11-08; nothing of 1.0.44-1.0.50 run on a drone or real phone yet).** The pilot's
  session of 2026-11-08, mostly tested with the new virtual fleet (TOOLS > Virtual fleet with Advanced on, or
  `gradlew run --args=fleet`; see "The virtual fleet" below). In it: the virtual fleet (`pc/fleet`, real phone protocol,
  Betaflight-like flight model, drawn targets the real MotionDetector must find, link-problem buttons, PLACE PHONE,
  start in MGRS 32V PN); map signs per state (MapView.Kind: ACTIVE / ASLEEP moon / OVERWATCH eye / LOST ! / UNKNOWN ?,
  names on hover, a sleeper that misses a check-in turns UNKNOWN - MainWindow.checkInOverdue); NAME_REPLY's power byte
  (every phone's mode on every PC's map); CHECK EVERY 0 min = always listening (Common SleepCheck.ALWAYS = 255, pc.39
  only); STANDBY / DEEP SLEEP / OVERWATCH auto-disconnect, no STANDBY button (DISCONNECT sends STANDBY when disarmed,
  `standbyPhones`), right-click Wake then STANDBY; overwatch: camera view on both maps while watching
  (CameraFootprint.sitting: 0.5 m over the terrain, 300 m), alerts on top, X on the card, no confirm on CONNECT, card
  width bug fixed (ImageObserver.WIDTH); PossiblyFlying per drone with reason + IT'S DOWN - RESUME; MotionDetector
  float-time bug fixed (Common, big clocks); arming auto-mutes, unmute in the air (AUDIO flag bit 1, pc.39);
  TOOLS > Advanced hides rare settings; hardware decoding on by default; pins copy + ADD AT GRID; minimap always there
  (log grows when popped out); LINK card parts slide (Fold, MainWindow.folded/show/shown); menus wrap at
  MenuRows.TEXT_WIDTH; a text pass over every visible string in both apps (short and concise - the pilot's rule for
  all future text). Known flake: OverwatchFleetEndToEndTest.lostArmed... under load (re-run alone). Network tests use
  fixed UDP ports: one test suite at a time. Open ideas the pilot asked about but didn't order: idle watch (idle
  phones that watch like overwatch). Mission debrief is still the next planned feature.

- **1.0.49 + phone pc.38 (released 2026-11-07, nothing of 1.0.44-1.0.49 run on hardware):** the fix round of the
  full review of 1.0.48 - every finding (C1, H1-H10, M1-M20, L1-L11, T1); the per-finding notes are in CHANGELOG 1.0.49.
  Key places: `RcSession` (BEEPER on `ModeSwitch.BEEPER_AUX` = 14 only when `drone.beeperSet`, fresh DISARMED, 10 s;
  frames padded to 8, AUX14 with 0 between; `armBlocked` while the phone installs), `ArmSwitch` (0 / not sent =
  unknown), overlay `Common/ArmRanges` + `Msp` MSP_MODE_RANGES (the phone's idle-sticks check), `Common/Takeover`
  (same-address rule), `setup/CliWrite` (all-or-nothing: backup, echo + read-back compare, exit without save),
  `setup/SetupConfirm` (CONFIRM tied to MSP_UID), `PossiblyFlying` (pauses every background fetch), `PcConfig`
  (Properties escaping, tolerant read), `NameService` duplicates, `TileQueue` (newest first, 256), `FrameAssembler`
  (frame-number gaps = lost; old-stream frames dropped), `map/TrackPath` + `CameraView.Live` (the Swing thread's load),
  `Common/ClipStore` + `GuardedCopy` (overwatch segments, quota, retries; update download stops when armed), `SelfUpdate`
  Authenticode check (`SIGNER` null: unsigned accepted). Not done: Betaflight's linked modes (MSP_MODE_RANGES_EXTRA)
  aren't read by the idle check; `confirmEndWhileArmed` / `askPower` still keep their own `lastArmedAt` instead of
  `PossiblyFlying`. ApkShare pins pc.38 (SHA 7554f8c7...). Bench plan before flying: the
  review's "Untested and risky" section (Drone setup on both boards incl. a pulled plug mid-write, GPS UART moves on the
  F722, the silent drone and BEEPER, idle sticks with ARM moved, the arming gate and a props-off link loss, audio,
  motion detection, OVERWATCH overnight on each phone, OBSERVATION with three phones, weak 4G). Network tests use fixed
  UDP ports: don't run two test suites at once.

- **1.0.48 OBSERVATION + phone pc.37 (2026-11-07, branch feat-observation, not released, never run with real phones;
  versions left for the merge):** the pilot's agreed spec: a third CONNECT AS, *Observation* - up to six idle drones in a
  grid in the main window's video area, all their sound mixed, the map with each one's numbered view cone, CONNECT on a
  tile to fly it. *Shared:* `flight-app/overlay/Common/.../Observe` (Java 11, both apps): `OBSERVE 0x50 [key UTF][w u16]
  [h u16][fps u8][maxKbps u16][flags u8: 1 sound, 2 video config + key frame, 4 sound config][detection u8]` once a
  second, `OBSERVE_STATE 0x51 [state][w][h][fps][observers]`, `OBSERVE_STOP 0x52 [key UTF]`, record 0x40FE (position,
  FC yaw + compass flag, the phone's camera azimuth + accuracy + error, hfov), the phone's observer table (3 at most,
  3 s expiry, the biggest picture wins), `cameraAzimuthDeg` (the back camera's -Z from the rotation matrix, the top
  edge when flat). 0x53-0x57 and 0x40FF reserved, unused. *Phone:* `Udp` (`onObserve` on the receive thread before the
  session rules - never a session, never sticks; `observeTick` every 0.1 s on the telemetry thread: expiry, the end for
  all at a pilot / not DISARMED / not normal power, `applyObserveVideo` on the video starter thread via the overlaid
  upstream `Config.setObserveVideo`, the restart when the camera stopped, the bitrate step every 10 s, MotionWatch asked
  for, the record 5 a second; `streamOut`: the stream's packets to the pilot's session or a copy to each observer; the
  config ahead of every key frame; the audio gate takes an observer's wish), `PhoneHeading` (TYPE_ROTATION_VECTOR, only
  while observed), `Msp` keeps the attitude's yaw and the status's sensor bits. *PC:* `observe.ObserverLink` (own
  socket, OBSERVE every second, video straight to a `VideoPipeline.observe` pipeline - one FFmpeg thread, normal
  priority), `TileFeed`, `ObservationSession` (CONNECT: the mix, the others, then that one stopped before the pilot
  connection; decoders released off the Swing thread), `ObserveSet` (the remembered set, idle-only + pc.37 rule,
  selection -> sharp = this PC's NORMAL), `TileGrid`, `HeadingChoice` (FC compass, else phone; approximate until
  `compass.calibrated.<phone>` - written by Drone setup when it reads `mag_calibration` with that phone chosen),
  `ObservedCone` (CameraFootprint.ray across the middle row, 400 m), `audio.ObservationAudio` (per phone AAC + jitter
  buffer, one mix, one line, MIN_PRIORITY) + `AudioMixer` + `VoiceActivity`; `gui.ObservationGrid`, `ObservePicker`,
  `MapView.setObserved`, MainWindow (role, CHOOSE DRONES, OBSERVE / STOP OBSERVING, tiles' state, leaving the grid on
  ENDED_PILOT, back to the grid after DISCONNECT, a sleeping observed phone answered "wake"), a tour step.
  *Unsure / to check on the real thing:* 3 observers x ~1 Mbit/s on a phone's uplink; the camera restart (~2 s) when a
  tile is selected (the frame rate changes 15 -> 30); whether `MSP_STATUS`'s sensor bit 2 really marks a compass on
  Betaflight 2025.12; the phone's camera azimuth with the phone mounted as on the drone (and its accuracy reports);
  six FFmpeg decoders + one mix on a small PC; a phone that went to deep sleep from another PC can't be woken by this
  one (its check-in goes to that PC); OVERWATCH SLEEP (the sibling branch) has a TODO in `Udp.onObserve` - a phone in
  that mode is refused with REFUSED_POWER until its preview is wired in.

- **1.0.47 OVERWATCH SLEEP + phone pc.36 (2026-11-07, branch feat-overwatch, not released, never run on a phone; versions
  left for the merge):** the pilot's spec: a third sleep mode where the camera and the motion detection stay on and a
  detection calls the PC. *Shared (overlay/Common, in both builds, tested on the PC):* `OverwatchProtocol` (every
  packet), `OverwatchEvent` (the phone's event: 30 s quiet, dismiss capped at 10 min, 15 min clip cap), `PreRoll`
  (5 s of JPEGs, two a second, 256 KB cap), `PowerFloor` (under 20 % not charging, or thermal >= SEVERE / battery
  >= 45 C; two readings 30 s apart), `UnitAssembler`. *Phone:* `PowerMode.OVERWATCH` (deep sleep's countdown, alarm,
  windows and hellos; `floorDrop`; wake calls `Overwatch.end`), `DDService.powerOverwatch` (deep sleep's closing but
  the wake lock stays; the position taken first), `Overwatch` (own socket to the PC's link port, its worker at
  background priority: alert resend, pre-roll after the ACK, preview sender draining the stream encoder, clip
  serving, the floor every 30 s), `OverwatchRecorder` (own H.264 surface encoder at the camera's size + AAC into
  MP4), `InternalCamera.setWatchOnly` (a session with the analysis reader alone) / `setExtraTarget` (the recorder's
  surface shared with the encoder's), `Udp.overwatchCamera` / `overwatchEventVideo` (on the video starter thread),
  `MotionWatch.overwatch` + `Sink`. *PC:* `overwatch.OverwatchHub` (one MIN_PRIORITY thread; ControlLink's foreign
  packets and NameService's answer thread - now its own thread with a 1 MB buffer - hand datagrams over; tokens per
  drone in `overwatch.watched`), `AlertCard`, `ClipTransfer`, `ClipIndex` (Documents\DRAUPNE Link\Overwatch,
  hidden.txt), `ClipPlayer` (FFmpeg demux + `VideoDecoder.oneThread`), `gui.OverwatchPanel` (cards, badges, clip
  list, player) right of the map, `MapView.OverwatchMark` (pulse, red view, direction), MainWindow's OVERWATCH
  button / `Asleep.overwatch` / POP OUT MAP badge / `AlertSound.ring`. *Design choices:* the preview is the stream
  encoder at the camera's size and 1 Mbit/s (not 640x360: the session shares one size between the encoder and the
  recorder; at the camera's frame rate, not 10-15); the pre-roll is not in the MP4 - the PC keeps the JPEGs and the
  player shows them before the clip; the bearing on the map is the heading the PC saw when it asked OVERWATCH plus
  the box's angle (no FC attitude while asleep; an upside-down phone isn't allowed for); the built-in player shows
  the picture only (the sound is in the file). *Battery estimate (unmeasured):* overwatch ~15-25 h on a 5000 mAh
  phone, deep sleep 2-7 days. *To check on the real thing:* that phones keep a camera session with only an
  ImageReader running with the screen off under the camera-type foreground service (and that makers' savers let
  it), the session switch at an alert (watch-only -> encoder + recorder + reader, ~0.5 s), the alert's time to the PC
  over a sleeping Tailscale path, the clip transfer's pace over 4G, the phone's heat in a closed box, the SHA over a
  big clip on the phone's worker. Ids 0x7A, 0x7B, 0x7D-0x7F and 0x40FB-0x40FD were reserved for this branch (the
  observation-mode branch has the others): check at the merge.

- **1.0.45 live audio + phone pc.34 (2026-11-07, not released, untried on a phone - nothing of it has run on real
  hardware or played through a real sound card):** the pilot: "when the drone is disarmed and the user is connected to
  it, the phone's microphone streams audio to the PC, with a MUTE button". *Phone:* `PcAudio` (overlay/Common, in both
  apps: the AUDIO packet 0x79 `[on][kbps][flags]` and the gate), `Udp.audioGateRun` (every 50 ms: the PC's "on" under
  3 s old, `FcArmedState` DISARMED, normal power, a session; the sender drops frames the moment it says no, the
  encoder stops on the videoStarter thread), upstream's AudioInitialFrame / AudioFrame unchanged, `AudioSource` mono,
  `StreamEncoder` at any video bitrate (upstream: 2 Mbit/s and up) with a 120 Hz high-pass, `PHONE_STATE` bits 5-7.
  *PC:* `ControlLink` (AudioFrame straight from the receive thread like the video; `AudioRequest`: on, off after 10 s
  muted, every second, only to pc.34+; `audioState()`), `audio.LiveAudio` (one MIN_PRIORITY thread: FFmpeg AAC
  (`AacDecoder`), `JitterBuffer` 200 / 450 ms, a ~60 ms SourceDataLine, MUTE flushes it, `AlertSound.isPlaying` ducks
  to 20 %), MUTE `GlyphButton.SOUND/MUTED` under BEEPER and the M key (not while a text field has the focus),
  `audio.muted.<phone>`, spectators get it relayed (config in the snapshot), both recordings get an AAC track
  (`StreamRecorder.addAacStream`, `AudioPts`; `max_interleave_delta` 1 s). *To check on the real thing:* the sound's
  delay and drop-outs over 4G, that ARMED really silences it within ~0.1-0.3 s, Android's AAC encoder at 48 kbit/s mono,
  that the mkv files play with sound in VLC / mpv, a PC without a sound device (logged once, retried every 5 s). To
  release: build the pc.34 APK from a clean HEAD, publish flight-2.3.0-pc.34, pin it in ApkShare.

- **1.0.46 motion detection + phone pc.35 (2026-11-07, branch feat-detection, not released, never run on a phone or
  real footage; versions left for the merge):** the pilot: "When the drone is disarmed the phone should detect
  movements; if movement is detected add a square around the moving part of the screen. This must not trigger on
  movement caused by vegetation." *Where:* `flight-app/overlay/Common/.../MotionDetector` (pure Java 11, no Android) +
  `MotionRecord` (the 0x40FA record's writer and reader) - the overlay's Common is compiled into the phone app and into
  the PC build (build.gradle's srcDir), so the PC's tests run the phone's detector unchanged, no copy. *Phone:*
  `MotionWatch` (DETECTION_SET 0x7C from the PC, fresh 5 s; `tick` on the telemetry thread every 0.1 s: asked +
  `FcArmedState` DISARMED + camera started; one picture at a time to a background-priority thread; the record in its
  own packet), `InternalCamera` (an ImageReader YUV_420_888 of the video's shape 240-720 wide in the session as its own
  OutputConfiguration, in the repeating request only while analysing, under `requestLock` with the recorder's target
  changes; `onConfigureFailed` with it -> the session again without, `analysisRefusedFor` that camera id; the field of
  view from SENSOR_INFO_PHYSICAL_SIZE + the first focal length), `UsbCamera` (now overlaid: `setFrameCallback` NV21
  only while analysing, set again after each open), `Camera.setAnalysis/analysisAvailable`. *Detector:* 160-wide
  working picture; shift by high-passed SAD (coarse +-16 px on a quarter copy, +-3, equiangular fraction); exposure
  step > 8 % rescales the background; > 25 % changed = settling (tracks cleared, fast learning, restart after 3 s);
  foreground = further than k sd + margin from all 3 x 3 background means (NORMAL k 2.5, +8 grey; LOW 3/12; HIGH
  2.2/6), not "other light" (3 x 3 correlation > 0.8); variance up in 3 s (below the line) / 6 s capped 40 (foreground)
  / 30 s (a confirmed track), down 20 s; tracks need age >= 2.0 s (LOW 2.5, HIGH 1.6), seen in 60 % of pictures, net
  2 s way >= 7 px and half their size (x 1.5 in the first 20 s after the 3 s warm-up), straightness 0.6, both halves
  the same way, not returning, no wake on the 8 s slow background; vegetation score = 0.35 flicker (max of 20 s
  foreground frequency within 2 px, bias-corrected while young) + 0.3 green + 0.3 max(back-and-forth, 1 - straight) +
  0.05 texture, cap 0.45 (x 0.5 while young). Night (mean luma < 35): brighter-only, margin 35, peak 60 over the mean.
  *Measured on made-up gale scenes (6 seeds x 10 min + the tests):* no false alarm in 80 minutes at NORMAL and 5 at HIGH; walkers
  found 18 of 20 (the two misses barely differ from the ground's brightness), ~2-3 s after they appear; ~1 ms a
  picture on this PC. *PC:* `MotionBoxes` (FcTelemetry.getMotion: by id, solid 250 ms, gone 1 s), `OsdRenderer.motion`
  (under the HUD, turned with the picture via `setPictureFlip` from VideoView and OsdRecorder), `DetectionMarks` +
  `CameraView.ray` / `CameraFootprint.ray` (bearing and terrain hit; the position may be 2 min old for a sitting drone,
  the attitude 3 s), `MapView.paintDetections` (dashed line, EST ring, 30 s), VIDEO > Motion detection
  (`detection.<phone>`), `ControlLink.setDetection` / DETECTION_SET once a second to pc.35+. *Unsure / to check on
  the real thing:* every threshold is tuned on synthetic pictures only; whether phones accept the third stream (LEGACY
  devices especially) and whether adding/removing it from the repeating request at arming makes a visible hitch (the
  recorder's target already changes the same way in flight); the UVC library's NV21 conversion cost on the capture
  thread; the field of view of zoomed / logical cameras; slow low-contrast walkers and tiny targets over textured
  shaking ground are missed; a uniformly grey thing on smooth ground could be taken for "other light" only where the
  ground has texture (by design). Ids: 0x7C and 0x40FA were picked clear of the live-audio work's likely 0x79-0x7B /
  0x40F8-9 - check at the merge.

- **1.0.44 GPS + beepers + phone pc.33 (2026-11-07, not released, untried on hardware):** the pilot: the GPS must
  never sit on two UARTs; and "the betaflight setup turns off all beeper sounds even when left disarmed", the only
  exception the phone's USB out of the drone, else only a beeper button in the UI. *GPS:* Betaflight 2025.12's CLI
  prints `serial UART3 2 ...` (names; its own ids 51-60 for UART1-10, VCP 20, SOFT1/2 30/31 as before; it takes 0-19
  as the old numbers on input) - `GpsSetup.ports` parsed none of it, so `gpsPlan` saw no ports: no GPS taken off
  another, and the fallback `serial 2 2 ...` (2025.12 reads 2 as UART3) put GPS alone on the port. Now `legacyId`
  reads both, `Port.token` writes the port back as printed. *Beepers:* `Beepers.plan` (`beeper list`, `beeper`,
  `beacon`): all off but RX_LOST, RX_LOST_LANDING, RX_SET, ON_USB; `beacon RX_SET`. BEEPER (13) alone on AUX12
  (`ModeSwitch.BEEPER_AUX`, the 8-argument plan; other modes off AUX12, BEEPER off other switches; not when ARM is
  left on AUX12). `RcSession.withBeeper` pads every frame to 16 channels, AUX12 2000 while the video bar's BEEPER
  button is held. *Why the phone:* 2025.12's failsafe.c beeps RX_LOST whenever no RX data and no USB is sensed,
  disarmed and never armed too (4.5 only after the first arm) - a drone on the phone with no PC sticks beeped.
  `RcSession.idleSticks` (throttle 1000, sticks 1500, ARM outside its ranges - null while `ArmSwitch` is unknown -
  BEEPER low) goes to pc.33+ phones once a second as `IDLE_STICKS` 0x78; `Udp.idleSticksRun` feeds them every 50 ms
  after 300 ms without a PC frame, only on `Msp.disarmedAfter` (a fresh DISARMED from a status read >= 100 ms after
  that frame; `armedFlagStatusMs` is written after `isArmed`). The pilot's choices: armed link loss still beeps
  (failsafe), the button also beeps the motors. *Not covered:* deep sleep closes the FC port (a board without USB
  sensing beeps then); the idle sticks are held in memory only (back within a second of a PC session).
  To release: build the pc.33 APK from a clean HEAD, publish flight-2.3.0-pc.33, pin it in ApkShare.

- **1.0.44 4G COVERAGE (2026-11-07, not released; never run against OpenCelliD with a real key):** the pilot: "a map
  overlay that gets the reception map from the possible mobile network distributors in that area ... identify where
  there is poor reception before entering that area". No open coverage map of Norway exists (Nkom: % per
  municipality only; Telenor / Telia / Ice: private token-gated tiles - not to be used), so the pilot chose a
  prediction from cell positions. `OpenCellId`: `/cell/getInArea` allows 4 km2 a box, 50 cells an answer, 1,000
  requests a day per key (checked on wiki.opencellid.org 2026-11-07); fixed boxes of 0.016 deg x n * 0.016 deg (n by
  latitude, always under 4 km2), LTE then NR, paged, kept as CSV for 30 days (an empty box too), one request at a time
  300 ms apart, 60 boxes a view, 900 a day by its own count, code 7 stops it for the day. IMPORT... reads the country
  file (242.csv.gz from the downloads page; its URL with a token is not documented, so no automatic download) and
  counts its bounding box as known. `CellCoverage`: cells within 60 m (same network) are one site; RSRP = 30 dBm RS
  EIRP - max(free space, two-ray) - one knife edge (ITU-R P.526, 4/3 earth, terrain every 40 m, 8 - 120 looks) - clutter
  (20 dB at 2 m, none from 30 m); 800 MHz, 30 m masts; best of the nearest 16 sites within 20 km; good >= -95, fair >=
  -105, weak >= -115 dBm. Terrain from the radio horizon's Terrarium tiles. Settings `map.coverage.key / operator /
  heightM` (read through `MainWindow.config()`, no MainWindow change). Tour step `coverage`. To check on the real
  thing: whether the levels match what the pilot's phone shows (the constants are textbook, not fitted), and the
  OpenCelliD positions' offset from the masts.

- **1.0.43 (2026-11-07):** the pilot: "change the model for the fc check" to their FPV drone model (OBJ + textures;
  cleared for use by the maintainer, see THIRD-PARTY.md). `DroneModelView` now loads `model/fpv-drone.bin.gz`
  ('DRM1': per vertex xyz in the drone's axes + uv + a flag byte - texture 0/1/none, front prop; uint16 triangles) and
  `fpv-drone-0/1.jpg` (AO baked in), made by `tools/model/export_fpv_drone.py` from the unzipped OBJ folder (OBJ x
  left, y up, z forward; centred on the four `engine` objects; scaled to ARM 0.08). Software raster like
  `ControllerRender` but forward-shaded: 2 x 2 sub-pixels, perspective-correct uv, flat face light, back faces culled
  (the mesh is closed; checked nose up, on its side, upside down), front props half red. ~8 ms a frame on 4 cores
  (10 without culling). `rotation()`, the grid, the label and the API the dialog uses are unchanged. Checked off screen
  only (rendered on a Linux PC); not yet seen in the real window.
  The v1.0.43 tag run failed twice on `FcTelemetryTest.armedNowOnlyFromAFreshStatus`: the test compared
  `System.currentTimeMillis()` with FcTelemetry's `MonoClock` stamps (wall clock at start + nanoTime), which drift
  apart on the runner; the test now uses `MonoClock.ms()`, and the tag was moved to that commit before any release.

- **1.0.42 (2026-11-06):** the pilot: "when recording is turned on one of the video files include OSD" and "a check of
  fc orientation with a 3d render of a drone following the actual movements". Why: an iFlight Blitz F722 (Betaflight
  2025.12.2, no board alignment set) flipped backwards on takeoff; its flight log + video showed the nose rising while
  Betaflight reported pitch +12, +42 (nose down) - the board turned 180 on the frame. *OSD recording:* `OsdRecorder`
  (gui) is the pipeline's `RecordingTap`: its own queue (90) and thread (MIN_PRIORITY), `VideoDecoder.oneThread`
  (thread_count 1, sws threads 1 - FFmpeg's own threads would run at normal priority beside the live decoder),
  pictures decoded always but drawn + encoded only while <= 6 frames wait, the OSD from `VideoView.lastOsdData()`
  (taken at each paint while recording, even with F2's HUD off), `OsdVideoWriter` (libopenh264, 1 thread, MKV, ms
  timestamps from the raw file's first key frame). Checked on the 10:50 flight's MKV: 689 of 692 pictures at
  960x720. *Orientation:* `Orientation.boardTurn` (nose up reads {s,-c}, right down {c,s} in (roll, pitch) for a
  board turned by psi; cosine >= 0.8 for both), `DroneModelView` (flat-shaded painter's 3D, camera behind/above,
  decals biased forward), `OrientationCheckDialog` (MSP_ATTITUDE at 30 Hz; flat = still 1.2 s; moves >= 30 deg;
  Fix it: backup, `get`/`set align_board_yaw|roll`, save, re-check after 6 s). *Unsure:* the sign of Betaflight's
  align_board_yaw for 90/270 (a wrong one shows as 180 left on the re-check, which Fix it then turns). Neither tried
  in the app or on a drone yet. *Also found, not fixed:* the GPS UART choice writes `serial N 2` (GPS only) to the
  port - it took VTX Tramp off UART1 and the ESC sensor off UART6 of that F722 while the pilot tried ports; the motor
  test doesn't log its reorder / direction / props writes.


- **Released 2026-11-05 late:** v1.0.41 (Latest; installer, zip, pc.32 APK) and flight-2.3.0-pc.32 (SHA-256 b645bdf6..., signer d910997f...2eb9, pinned in `ApkShare`); release run 42123657288 green first try. Nothing of 1.0.41 has been tried on hardware: next is the spectator relay with the pilot PC on a weak upload, the mirrored OSD, WAKE after DISCONNECT with a real sleeping phone, the map's drones from pc.32 phones, and - from the next phone app on - the remote update.

- **1.0.41 remote update (2026-11-05 night, released, untried on a phone):** the pilot: "add a remote update for
  connected phones". PC: `ControlLink.sendRemoteUpdate` [0x77][version UTF][url UTF][sha256 UTF] (ApkShare's pinned
  APK), repeated each second until the phone answers (10 s, then "older than pc.32"); `FcTelemetry.PhoneUpdate` from
  record 0x40F7 [state u8][percent u8][text UTF]; `MainWindow.askRemoteUpdate` (disarmed - LockedNotice otherwise; the
  phone app >= `REMOTE_UPDATE_MIN_PHONE` pc.32; confirm) and `refreshRemoteUpdate` (the button and the warning line
  show the progress; done when the reconnected phone reports the new version). Phone: `RemoteUpdate` (newer only,
  `FcArmedState` DISARMED, a thread), `AppUpdater.downloadAndInstallRemote` (SHA-256 checked, disarmed checked again
  before the install, `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on API 31+ for every install), the installer's
  answer with `EXTRA_REMOTE` (a prompt shown while connected only for a remote one; TAP_NEEDED reported),
  `AppReplacedReceiver` (MY_PACKAGE_REPLACED + the pending flag: the full-screen notification + bright wake lock open
  MainActivity, which listens; the PC reconnects), the record twice a second while one runs (statsSent % 5 == 4).
  *Not tried on a phone:* the no-tap install (Android's rule: the app is its own installer of record - true after its
  first self-update), and the reopening after it (the full-screen intent; phones that hold it back need a tap on the
  notification). Usable from the release after the one that ships pc.32 (the PC must pin a newer APK than the phone).

- **1.0.41 second round, phone app 2.3.0-pc.32 (2026-11-05 night, released, untried on hardware; pc.32 APK built
  from the clean HEAD 28e9eab, published as flight-2.3.0-pc.32, SHA-256 b645bdf6..., signer d910997f...2eb9, pinned in
  ApkShare):** the pilot's list. *Upside down per phone:* `upsideDownFor`,
  `videoUpsideDown.<phone>` (unset: the old PC-wide key), applied on choosing a phone and on connect; a spectator takes
  PILOT_VIEW's flip (flags bit 6), saves nothing. *Spectator info:* PILOT_VIEW carries the pilot's phone (UTF at the end,
  read if present); BYE [0x67][1] = the pilot disconnected on purpose (`SpectatorHost.close`); `SpectatorClient.pilotLeft`
  / `pilotPhone`; `ControlLink.spectatorStatus` names the phone and says disconnected (not a link loss) / lost its link;
  OSD `Data.lostText` PILOT DISCONNECTED (yellow) / PILOT LINK LOST. *Kept flights:* `MainWindow.KeptFlight`, kept at
  DISCONNECT and at the next connect (`keepFlight`), a spectator's when the pilot's phone changes (`followWatchedPhone`),
  `map.keptFlights` = name>gpx|... read back with `FlightTrack.read`; `MapView.Kept` drawn in six colours with names,
  right-click to remove one or all (files kept). *Sleeping phones:* `asleepPhones` / `asleepHellos` by address (was one),
  `phone.asleep` = entries;...; the PHONE list item "DEEP SLEEP m:ss" and the hint, refreshed each second while the list
  is closed (`refreshNetwork`); `ControlLink.setForeignSleepHandler`: another phone's check-in while connected is answered
  sleep on (before: none). *Drones on the map:* phone `Msp` keeps the FC's last 2D/3D fix, `Udp.writePosition` appends
  [1][source][lat][lon][age s][armed][flown][pilot ip] to NAME_REPLY; `Names.Position` / `readPosition`, `NameService.
  dronePositions` (every 5 s scan); `MapView.NetDrone` diamonds (blue idle, yellow flown, grey stale or asleep) with
  name and state, all but the drone this PC flies; right-click: connect and fly / watch through the pilot PC
  (`connectFromMap`, the role and the PHONE / PILOT PC chosen for you). Tests: NamesTest position reply,
  SpectatorTest.aPilotThatDisconnectsIsNotALinkLoss, the PILOT_VIEW round trip with phone + flip.

- **1.0.41 spectator and deep-sleep round (2026-11-05 night, released, untried on hardware):** the pilot,
  spectating a close-by drone: "major connection issues". The link logs: the pilot PC (with a spectator, AUTO on the sharp
  tier at 8 Mbit/s) went from ~50 ms ping to 300-700 ms within 7 s; its spectator pause (3 s on a timer, the usual = the
  lowest of the last 10 s) went on with the ping still high and paused again a second later - the spectator got 1 s of
  picture in 4, and twice gave up (PILOT_GONE_MS 3 s) and rejoined for 5-10 s. Fixes: `ControlLink.watched()` keeps AUTO
  off SHARP while anyone watches (`setWatchersForTest`; a forced SHARP stays); `RelayGate` (pause after 1 s at usual + 80,
  on again after usual + 40 for 3 s and the pause's minimum, the minimum doubling for a pause within 30 s of the last
  one, 3 - 30 s; usual = lowest per 5 s slot over 60 s) used by `SpectatorHost.sendLoop`; `SpectatorClient`
  `PILOT_GONE_PAUSED_MS` 15 s while a pause was announced. The pilot also: "in spectator mode the warnings are going
  based off the local pc" - `ControlLink.PilotView` (link ready, RC live, radio frozen, arm switch unknown, relayed,
  stalled, rcWaiting, banner, linkHint, channels) sent by the pilot's `MainWindow.sharePilotView` (5 a second) as
  `Spectate.PILOT_VIEW` 0x76 from the stream port every 250 ms (`SpectatorHost.setPilotView`, through a pause too); the
  spectator's `osdData` takes it over its own values (`ControlLink.getPilotView`, 2 s fresh; an older pilot: as before).
  And: "if a pilot sets a phone to deep sleep then disconnects there is no way to remotely wake it up" - after a
  DISCONNECT the name service owns port 6286 and ignored the phone's SLEEP_HELLO; `NameService.setSleepHandler` answers
  it now (`MainWindow.onSleepCheck`: false = sleep on); `MainWindow.Asleep` (name, ip, since, interval; `phone.asleep`)
  is remembered on DISCONNECT while `phoneAsleep`, shown by `refreshAsleep` (power row: DEEP SLEEP, heard, battery,
  next check, WAKE); WAKE with no link = `wakeAsleep` (pilot role, that phone, `connect` with `wakeOnConnect`: the link
  not dormant, it answers the next check "wake"); a plain CONNECT to it sets the link dormant (as before the
  disconnect); cleared when the phone connects, or when it answers name queries and hasn't checked in for a minute.
  Tests: `RelayGateTest` (3, the log's ping), `ControlLinkTest.autoStaysOffTheSharpTierWhileSomeoneWatches`,
  `SpectatorTest.theSpectatorGetsThePilotsOwnWarnings` + the packet round trip, `NamesTest.aSleepingPhonesCheckInIs
  AnsweredWhileNoLinkIsOpen`. *Not tested:* the WAKE path with a real sleeping phone; the spectator OSD in the window.

- **Released 2026-11-05:** v1.0.40 (Latest; installer, zip and the pinned pc.31 APK), release run 42113772169 green first try. PC only: the phone app is unchanged. Nothing of 1.0.40 has been tried on hardware: next is Drone setup on the real board (CONFIRM, GPS on its UART, a doubled mode taken off), the motor test's REORDER / SPIN DIRECTION / PROPS IN-OUT, the compass calibration, and the calibration with the pilot's TX16S (a momentary button, the 3D toggles).

- **1.0.40 Drone setup round (2026-11-05, released, untried on hardware):** the pilot: "remove as much text
  as possible without losing much meaning ... stuff that is implied via the selectable menus", combine *What will change*
  with *Betaflight settings for DRAUPNE Link*, a GPS question above the switches (Yes slides out compass + UART, default
  UART3; nothing GPS shown on the switches or failsafe without it, animated in with Yes), and a compass calibration.
  Second round (the pilot: "very messy, give it a cleaner and more compact look"): each switch is a `line(part, label,
  choices...)` - label 104 px, same-width AUX combos (`auxCombo`, prototype "Leave as it is"), the part's state in its own
  `Part.cell` (a `WrappingText.fitting` in the line's CENTER, coloured by state via Theme.*_HEX) instead of the row's
  text; `CheckRow.set` hides the detail text when empty; `WrappingText.fitting` measures by its own width (and insets)
  once laid out. Third round (the pilot: "first put all the settings, then click confirm and it sets it on the FC ... if
  all the settings are set and confirmed the checks turn green"): one CONFIRM button on the settings row (`confirmNow`;
  the cell count's own Confirm gone, CONFIRM confirms it); `writeArmed` (this session's press: the rounds write) and
  `confirmed` (`drone.confirmed`, saved once a round after the press finds nothing left to write: the rows may be green);
  `okState()` = OK when confirmed else TODO, `setText()` = "Set." when confirmed else "" (the ring alone); every user
  change calls `choiceChanged()` (both off, CONFIRM back). Fourth round (the pilot: "verify that no channel is mapped
  to the same ... one drone was mapped to aux5 and aux 2 after the setup guide"): `ModeSwitch.plan` takes the MANAGED
  modes (ANGLE, HORIZON, POS HOLD, GPS RESCUE) off every other switch when the flight mode switch (or the rescue switch
  for GPS RESCUE) is chosen and has no problem (`offElsewhere` -> cleared, listed in `replaced`); `elsewhere` only remains
  for a switch left as it is; `Plan.doubled` / `ModeSwitch.doubled(aux)` = managed modes on more than one AUX as read
  ("ANGLE on AUX2, AUX5"), shown as WARN on the flight-modes line when nothing in the plan takes it off. Fifth round (the pilot: "the overall
  point Betaflight settings, underpoints GPS, Switches, Failsafe, the confirm button at the bottom, the motor check its
  own thing"): one `Combined` row holds every `Part`; `Section` (title, `Dot` light = worst of its parts, a note line
  for the radio redo, `build(lines...)` with the lines set in by `SECTION_INSET`); the GPS and failsafe rows became
  parts with cells (lines FITTED / SIGNAL LOST); the link's three lines sit in the same inset; CONFIRM and the footer
  text in a bottom row of the card. Sixth round (the pilot: no radio-recalibrate warning, green when set, no
  GPS-fix arming warning, always allow arming without a fix): `switchChanged` only saves RADIO_REDO_KEY (the radio page
  no longer warns either, `shown` unchanged); `Section.note` unused; with GPS Yes the plan adds `set
  gps_rescue_allow_arming_without_fix = ON` when OFF (rescueNote / failsafeNote always null now); `elsewhere` and an
  uncalibrated compass no longer WARN.
  *Motor test round* (the pilot: "make it possible to reorder the motor number and direction ... change from props out to
  props in"): `BetaflightUsb.msp2` (MSP v2: `$X<`, flag, u16 command, u16 size, CRC-8/DVB-S2; `Msp2Parser`). `MotorTest`:
  `Info.dshot` (MSP_ADVANCED_CONFIG byte 3 = protocol 5..8), `outputOrder` (MSP2 0x3001), `setOutputOrderAndRestart`
  (0x3002 + MSP_EEPROM_WRITE 250 + MSP_REBOOT 68, the test closed), `setPropsOutAndRestart` (MSP_SET_MIXER_CONFIG 43
  with the mixer kept), `setDirection` (MSP2_SEND_DSHOT_COMMAND 0x3003, blocking, [20 normal | 21 reversed, 12 save]),
  `reordered(current, places)` = new[place-1] = current[k]. `MotorTestDialog`: a `Job` queue run by the motor thread
  (it owns the port) between sends; after a save `restartUntil` 6 s, then it reconnects. REORDER MOTORS (Quad X, 4
  motors, box ticked, battery): reads the order, spins motor k at PULSE, the diagram's click = its place (picked ones
  green, no place spins in the picture meanwhile), STOP ALL / untick / leaving the window cancels, a confirm before the
  write. SPIN DIRECTION row under the picture (As is / Normal / Reversed per motor; DShot only, battery in). PROPS IN /
  OUT asks, then writes; `connected` sets the picture to Betaflight's. *Not verified on hardware:* any of it - MSP2
  ids and payloads are from Betaflight's source (4.3 on) and Configurator's motor wizards; the window wasn't rendered
  off screen (its worker opens the USB port). Layout round: no header row over the picture; `toolColumn` left (title, PROPS
  switch, MOTOR ORDER button) and the SPIN DIRECTION column right, both `TOOL_W` 210; the drawing 410 x 348 with the
  drone at `DRONE_CY`; the window opens up to 1440 x 1000. Checked painted off screen (scratchpad `MotorTestShotTest`,
  only when no flight controller is plugged in).
  *Calibration round* (the pilot: a button pressed for one AUX "gets assigned to the next aux channel when it is
  released"): `CalibrationWizard.holdWhatMoved` on leaving a switch step marks every axis / button that differs from
  that step's baseline as held (`heldAxisRest`, `heldButton`); `detectSwitch` ignores held inputs until they are back
  at that rest (`releaseHeld`, then re-baselined). Assigned inputs were already excluded, so a pure button was fine; the
  failing case (`whateverTheRadioReportsWithAButtonIsHeldWithItToo`, red on the old code) is a press reported on two
  inputs: the first went to the AUX, the other's release to the next.
  *Toggles round* (the pilot: the front switches "only move as if they are still switched in the down position ... the
  flat part of the switch should hinge at the cylindrical part ... now both hinge"): every CAD lever part (`SD3 L*`)
  is a 6.5 mm collar on the bushing's axis (front row: z from the face to 12.0 mm; shoulders: y 12.5 mm above the
  bushing) with the flat paddle on its top. `load` finds the collar's top along the axis (the highest point on the
  collar's wall, 0.97-1.03 of the foot ring's radius), `splitCollar` cuts the lever there: the collar a BODY part
  (chrome, PLAIN), the paddle the switch group; pivot on the axis at the collar's top; rest = the paddle's centre line
  off the axis; throw = |rest| (CAD end to mirror, at most `LEVER_MAX_THROW` 40), both rows (SHOULDER_TILT 5 and
  FRONT_MAX_TILT 22 and the LEVER_KEEP cut are gone). Checked on rendered sheets (scratchpad `SwitchShotTest`, SA
  SD SF, off / halfway / on); `noLeverEverComesOutThroughItsBushing` still 0. Not checked: the paddle against the
  shell's wells at the shoulders' new 28-degree throw (trimesh tool not run). `DroneSetup.BetaflightPage`: every row line is a state (`Set.`, `WITH_WRITE` = "Set on Confirm.", a problem), never
  the choice beside it; `Combined` gained `footer` (plain line: what WRITE does / "Still not set" list after a failed
  write) and `Part.hidden` (GPS rescue without a GPS); the CONFIRM button sits on the **Betaflight settings** row (`changes`
  row gone). GPS: `gps` row with `gpsChoice` No / Yes, `gpsFold` (a `Fold`) with COMPASS, UART (`uartItems`: UART1-6
  until `GpsSetup.uarts(serial)` says what the board has) and CALIBRATE COMPASS; `rescueFold` under the ARM / PREARM row;
  `setGps` folds both, hides the part, `offerGpsModes` / `offerGpsFailsafe` rebuild the lists (a GPS mode without a GPS
  becomes ANGLE, a GPS rescue failsafe KEEP). Keys `drone.gps` (yes / no; unset = not asked: the first read takes
  Betaflight's feature GPS as the answer, before that the evidence of a GPS rescue failsafe shows Yes), `drone.gpsUart`,
  `drone.compass`. `setup/GpsSetup`: `ports` / `uarts` / `gpsUart` from the CLI's `serial`, `gpsPlan` (feature GPS, `serial
  <id> 2 <baud fields kept>` with GPS taken off other ports and the chosen port's other functions named, `set gps_auto_baud
  = ON` only when the setting exists), `compassPlan` (`mag_hardware` AUTO / NONE, a named chip kept), `magInStatus` /
  `gpsInStatus` (MSP_STATUS sensor bits), `magFromRawImu`, `calibrated` (`get mag_calibration` not all zero). The round
  reads `serial`, `get gps_auto_baud`, `get mag_hardware`, `get mag_calibration` only with GPS Yes; `waitForGps` only
  with Yes and the feature on. `gui/CompassCalibrationDialog` (a JFrame like the motor test, APPLICATION_EXCLUDE): opens
  the chosen (or first Betaflight) port, MSP_STATUS for the compass, START = MSP_MAG_CALIBRATION (206), 30 s + 2 s
  settle polling MSP_RAW_IMU's mag for two dot discs (from above x/y, from the side x/z), then the CLI: `get
  mag_calibration` and `save` (Betaflight saves by itself at the end; a second save is harmless) or `exit`. The page
  stays off the port while it is open (`compassCalOpen`, `isOpen()`). Tests: `GpsSetupTest` (9). *Not verified:* the
  compass calibration against a real board (MSP 206's 30 s and the auto-save are from Betaflight's source, 4.x); the
  Fold animations in the real window. *Left alone:* the other setup pages' texts (the pilot's examples were all the
  Betaflight page).

- **Released 2026-11-05:** v1.0.39 (Latest) and flight-2.3.0-pc.31 (APK SHA-256 a39772e2..., signer d910997f...2eb9, pinned in `ApkShare`); release workflow green. Nothing of 1.0.39 has been tried on hardware: next is the pilot's bench test (far phone on 4G, link logs) and the STANAG stream in VLC (`udp://@:15000`), then ATAK / WinTAK / QGIS.

- **1.0.39 STANAG 4609 (2026-11-05, built, 389 tests green, untried with a real TAK / player):** the pilot asked for the camera's view on the map and "a stream that can be connected with anything that follows the STANAG". `video/Klv` encodes MISB ST 0601 (0601.17 items: 2 time, 3 mission, 5-7 + 90/91 attitude, 10-12 names, 13-15 sensor, 16/17 FOV, 18-20 camera relative angles, 21 slant range, 23-25 frame centre, 26-33 offset + 82-89 full corners, 48 = ST 0102 security (UNCLASSIFIED, ISO-3166 three letter, object country UTF-16), 56 speed, 65 version 17, 1 checksum) - checked against jMISB's checksum and decoded with the Python `klvdata` library. `video/StanagStream`: FFmpeg's mpegts muxer, video copied (parameter sets put before key frames that lack them) + an AV_CODEC_ID_SMPTE_KLV data stream (async KLV, stream type 0x06 'KLVA'), `resend_headers`, `flush_packets`, `av_write_frame` (not interleaved: the sparse KLV would hold video back), UDP `pkt_size=1316` (multicast `ttl=8`); KLV at most every 100 ms; its own thread at MIN_PRIORITY and a 90-frame queue - full = frames left out until the next key frame, the receive thread never waits. `VideoPipeline.FrameTap` (`setFrameTap`) is the only change on the video path. `map/CameraFootprint`: rays through the picture's edge (8 per side) turned by the camera tilt, roll, pitch, heading (Betaflight's pitch is nose down positive: flipped; `osdInvertPitch` honoured), marched over the Terrarium terrain (z12, 5 km round the drone, reloaded after 2 km; `CameraFootprint.Terrain`) to 3 km, flat ground at the launch height where the terrain isn't in. `CameraView` (pc): height = terrain at launch + baro (else GPS MSL), the KLV per frame. UI: MENU > LINK > STANAG 4609 stream / settings (`StanagDialog`: `stanag.target` default 127.0.0.1:15000, `stanag.missionId`, `stanag.country` NOR, `camera.hfovDeg` 70, `camera.tiltDeg` 0), MENU > VIDEO > Camera view on the map (`camera.viewOnMap`), drawn by `MapView.paintCameraView` under the drone. *Not done:* the phone doesn't send its camera's real FOV (Camera2 has focal length + sensor size: a phone-protocol item); synchronous KLV (ST 1402 metadata AU cells) and a CoT feed; never tried in ATAK / WinTAK / QGIS - first test: VLC `udp://@:15000` on this PC, then a TAK device's Tailscale address.

- **1.0.39 robustness round, phone app 2.3.0-pc.31 (2026-11-05, built, 365 tests green, untried on hardware, APK
  not built):** the pilot asked for a review of the link's weak spots, then "implement everything". *Phone* (overlay):
  `FcArmedState` (DISARMED / ARMED / UNKNOWN; `Msp.armedState`: UNKNOWN when not initialised, box ids unknown, or no
  status for 2.5 s; MAVLink from DD_MAVLINK_MODE; the status poll has its own writer slot, never skipped by
  `slowUntil`) - every guard acts only on DISARMED; the not-connected branch of `Udp`'s receiver takes nothing but a
  keyed Connect, and from another address only with the FC DISARMED and the pilot silent `PILOT_ALIVE_MS` (before: any
  keyed PC after the 4 s timeout, armed or not); `PowerMode.fallAsleep` re-checks, `fcArmed` cancels a deep-sleep
  countdown; an FC-port Config change is deferred until disarmed (`applyDeferredFcConfig`); `Serial` init / open /
  close under `portLock` with a generation per thread; the DDService timer in try/catch; request writes 20 ms;
  `answerName` off the receive thread; the 16-bit sequence reset takes only newer frames for 1 s; StartVideo ignored
  while a start / camera open / watchdog restart runs (`videoStartBusy`), one camera thread, callbacks by generation;
  weak-tier key frames paced at ~1.25 x target; frame numbers per sender (`nextFrameNum(senderId)`); encoder flags as
  bits; telemetry queues drained every tick, split over datagrams. *Stick lag* (PROTOCOL: RcFrame pc.31, record
  0x40F6): the PC's `ControlLink.rcFrame(frame, seq, sentMs, maxLagMs)` (`RC_LAG_MIN_PC_BUILD` 31); the phone's
  baseline = min over two 10 s windows; `rc.maxLagMs` (default 0 = never drop: a lasting slower path would mean a
  failsafe), OSD STICKS x.x S LATE over 300 ms, link log `stick_lag_max_ms` / `sticks_dropped_late`. *Common*:
  `ReceiverBuffer` uses `isConnected()`, waits 1.5 x ping (>= 30 ms, >= 4 packets) before skipping a gap, finds the
  next packet across the wrap, acknowledges true duplicates only, one lock; `UdpSender` doesn't timer-resend Connect,
  tries once, logs every 2 s; the phone ignores a repeat Connect from the pilot within 1 s of a session's start (the
  PC still takes every answer: pc.30 phones start a session per Connect, `TransportTest`). *PC:* `MainWindow.
  confirmEndWhileArmed` (DISCONNECT, window close; also while the link is down within the lost-drone window), the
  CONNECT button not focusable, `WindowsTuning.keepAwake` (SetThreadExecutionState, Swing thread, connect ->
  disconnect), RADIO FROZEN / STICKS LATE in `stickWarning` (the alert sound), `RcSession.onPaused` (STANDBY is not a
  radio blip), buttons count as input, `setFrozenStopMs` (`radio.frozenStopS`, default 0: a still hover on a
  jitter-free radio would be cut), `JoystickService` reads by `readSame`, `MonoClock.ms()` for the link's timeouts
  and freshness (ping stays on the wall clock, UdpSender stamps it), `FcTelemetry.isArmedNow` wants two armed reports
  in a row, the FcInfo refresh asked again until answered, the video watchdog waits while the phone restarts its
  camera, AUTO leaves SHARP on `decodeLoad() > 1` for 5 s. *Video* (`video/`): an identical config is ignored, the
  backlog cut by age (200 ms with the decoder near its limit, 1 s always), key frame 1 a restart only if it differs
  from the last one, the order forgotten after 3 s without a frame, a decoder that couldn't be made tried again
  (software), `VideoDecoder.freeBeforeOpen`. Tests: `StickLagTest`, `TransportRobustnessTest`, RcSessionTest /
  FcTelemetryTest additions, the video tests. *Not done:* the second key-frame copy still goes straight after the
  first; packets have no session id. At release: bump nothing more (pc.31 is in the overlay's build.gradle), build
  the APK from a clean HEAD, publish `flight-2.3.0-pc.31`, pin it in `ApkShare`.

- **1.0.39 second round (same release):** *boot animation mismatched* (the pilot: "the frames are mismatched, the
  crosshair is too low"): a throwaway test (real `MainWindow` off screen, targets every 15 ms) showed the cards never
  move, but FlatLaf's glass pane sits under the title bar (y 30) while the targets were measured in the layered pane
  (y 0): everything landed 30 px low. `MainWindow.open` / `boundsInPane` now convert into the glass pane. The
  aircraft's altitude in RADIO HORIZON is above the ground under it ("above ground from the point of the aircraft"):
  `compute`'s target = ground + `aircraftM` per sample (GCS SEARCH already had it so). The horizon is blue
  (`HORIZON_SEEN`) and painted over the heat map, to read both at once. The tools' status lines are short, 13 px,
  narrower (room for the scrollbar; the long one was cut off). The radio horizon, the search and the ruler's measurement (with its profile) show only while
  TOOLS is out (`MapToolsPanel.toolsShown` / `onGroupChanged` -> `MapView.setToolOverlaysShown`; hidden, not cleared;
  their tool ends). PINS moved to DRAW.
  *World topo* (the pilot: "implement a topo map for the rest of the world as well"): `TileSource.WORLD_TOPO` =
  OpenTopoMap (tile.opentopomap.org, CC-BY-SA, z17, volunteer server so `prefetchAllowed` false like OSM).
  `forPosition` gives it outside Norway (was OSM); `TileSource.at` makes a chosen TOPO a topo map everywhere
  (`MapView.effectiveSource`). MAP TYPE gains WORLD TOPO; DOWNLOAD AREA's "all" takes it outside Norway only.
  RadioHorizon's forest outside Norway still reads OSM's colours (OpenTopoMap's forest colour not measured).
  *3D radio in the calibration* (the pilot: "a 3d render of a FPV controller to illustrate what the user is supposed
  to do during calibration"): `gui/ControllerRender` - a software renderer (superellipsoid parts, perspective, depth
  buffer, per-vertex key / fill / rim light, 2x2 supersampled, ~30 fps while shown), `Show` per step, the asked-for
  stick cap / switch ball lit red, arrows projected onto the face. Camera per step, gliding: nearly square on for the
  sticks (from higher up a stick pushed forward points at the eye and reads as a ball - checked on rendered sheets),
  above and from the side, zoomed, for a switch (its flip is forward / back). `CalibrationDialog`: between the two
  live gimbals, shown on every step; SUMMARY follows the real channels. Tests: `ControllerRenderTest`.
  Second round (the pilot): modelled on the RadioMaster TX16S MK3 (body, grips, big screen with buttons and roller,
  gimbals wide and low, six buttons between them, trims, two top knobs, four toggles per shoulder, handle, antenna -
  from its layout, not CAD). `slotFor`: flight modes on the second toggle from the left end, PREARM the right end,
  ARM the left end, GPS rescue second from the right, spares inside. The centre step is all four centred (the
  render and the texts); `CalibrationWizard.RANGE_RESERVE_MS` 2 s after all four axes are swept the range step goes
  on by itself. Throttle: `AxisCalibration.toThrottle` maps the step-2 centre to 1500 like roll / pitch / yaw (the
  pilot: "make the centered location in step 2 be the centered location sent to the flight controller"), only when
  that centre is inside 25-75 % of the range: old calibrations took it at throttle down and would idle at 1500.
  Throttle down stays 1000, so the motors' start rule (throttle down) is unchanged.
  Third round (the pilot: "does not at all look like the TX 16 ... in a realistic style"): `ControllerRender`
  rewritten. The body is one slab from the TX16S's front outline (`HALF`, Catmull-Rom, mirrored; `slab` sets the
  outline in for rounded front / back edges, faces ear-clipped), the screen frame, keys and trims rounded-rect slabs,
  rings tori; the screen and the printed name are textures (uv). Renderer: a G-buffer pass (triangle + perspective-
  correct barycentrics per sub-pixel), a shadow map from the key light (640 x 640, 3 x 3 soft), per-pixel shading
  (key / fill / rim, hemisphere ambient, Blinn-Phong, a studio sky + softbox in the reflection, Fresnel, metals
  tinted), knurl and boot ribs as normal bumps round the part's axis, ACES tone mapping. The model is built once
  (static) and shared. Checked on rendered sheets (scratchpad ControllerShotTest), not against a photo.
  Fourth round (the pilot: "find a model online and use that instead"): no free full-body model with a clear licence
  was found (Sketchfab's scan is CC BY-NC-SA); the pilot downloaded a GrabCAD CAD model (TX16S MKII, YahyaELone,
  STEP from Inventor). Tessellated with OpenCASCADE (`uv run --with cadquery-ocp==7.7.2`, 0.25 mm deflection; 8.x
  lacks TDF_LabelSequence) into `model/tx16s-mkii.bin.gz` ('TXM1': per part name, CAD colour, xyz mm, int8 normals,
  triangles; 47 parts, 62k triangles, 573 kB); the scripts are in the scratchpad, not the repo. `ControllerRender.load`:
  mm to cm centred on the body, materials by part name, the sticks GL + G1 about the Part1Gimble frame's centre, the
  levers ("SD3 L*") about their nearest bushing ("SD3:"), slots by x from each end (shoulder row and top row
  together), the antenna folded back 72 degrees. Licence: the maintainer confirmed permission to redistribute (2026-11-05), see THIRD-PARTY.md. (The real TX16S: gimbals at the top, the screen below - the made-up models had
  it the other way round.)
  *Main window round* (the pilot): `MainWindow` - the controller card's three status labels (11 px, tooltips with the
  whole line) moved into its top row (BorderLayout CENTER beside the name and buttons), `channelBars` straight in the
  card's CENTER, so minimized the card is the top row only. `linkFields` (PHONE list, CONNECT AS) hidden by
  `setLinkEditable` while connected. `fitMiniMap`: under MINI_MIN the log gives way to LOG_FLOOR 110 before the minimap
  shrinks, gone only under MINI_FLOOR 64 ("the minimap sometimes disappears": it went at the first squeeze). VIDEO menu:
  one-line details, the tiers' sizes in the Video row's tooltip ("takes up half the screen").
  *3D radio, fifth round* (the pilot: "the animation is lagging", "the switch ... just moves", which switch is which):
  slots by row and side (`TOP_*` the shoulder row standing up, `FRONT_*` the row pointing out of the face; outer =
  further from the middle). Flight modes TOP_LEFT_OUTER, PREARM FRONT_LEFT_OUTER, ARM FRONT_RIGHT_OUTER, GPS rescue
  TOP_RIGHT_OUTER. A lever turns about where it leaves its bushing (the bushing's face along the axis its tip points
  to), +-16 degrees about that axis (`leverRest`: the CAD's angle, its OFF side), a 140 ms snap with a small overshoot;
  it turned 48 degrees about the bushing's middle before, so its base slid. Speed: ~21 ms a frame at 440 x 300
  (`ControllerShotTest` timing): the passes run in row bands on all cores, a pixel inside one triangle is lit once
  (edges 4 times), tone mapping from a table, no rim highlight, the camera's sway and the glow's pulse gone so an
  unchanged pose reuses the last picture (`pose`), the timer at 33 ms.
  Sixth round (the pilot, with RadioMaster's layout drawing: "SF2 is arm, SH MOM is Prearm, SA3 is mode, SC3 is
  AUX4/GPS rescue"; "it still clips outside its base ... actually confirm it"; "increase the size ... beside the
  values"): slots are the switch names `SA`..`SH` (front of the shoulders SA SB | SC SD by how far out, the shoulders
  SE SG in front, SF SH behind); `slotFor` ARM SF, PREARM SH (momentary: held 0.8 s, let go), flight modes SA, GPS
  rescue SC. Clipping: the CAD's lever runs right through its bushing and that part swung out through the sides;
  `cutAtBushing` keeps a lever from `LEVER_KEEP` 1.2 mm under the bushing's face. Confirmed by
  `ControllerRenderTest.noLeverEverComesOutThroughItsBushing` (every lever, OFF / ON and the snap's overshoot: 0
  points under the face outside the bushing; without the cut it fails, "SA at 16 degrees: 38 points through the
  bushing") and on close-up renders of all eight. `CalibrationDialog`: the radio 500 x 560 in the card's WEST, the
  stick views (a row, hidden when not used) and the values beside it; the dialog up to 1320 wide.
  Seventh round: `CalibrationWizard.FULL_RANGE` 1.0 (was 1.5: a radio at 80 % never got past step 1; its own min /
  max are saved and mapped to 1000 / 2000), the range step's 2 s wait restarts while a limit still grows (`GROWTH`).
  Sticks: `smoothTravel` (the sum of steps under `SMOOTH_STEP` 0.25) tells a sweeping stick from a jumping switch;
  `isFullySwept` needs `SMOOTH_MIN` 1.5 of it; leaving the range step locks `stickAxes` (the four smoothest); ASSIGN
  only picks among them, SWITCH never (`detectAxis(.., sticks)`); back into the range step finds them again. Tests
  sweep smoothly (`CalibrationWizardTest.sweep`). The final check's radio moves its toggles from the AUX channels
  (`ControllerRender.setLiveSwitch`, by `slotFor`). Its screen: the DRAUPNE mark, an artificial horizon, LQ / RSSI /
  battery (`screenTexture`, 480 x 248 like the CAD screen's 108 x 56 mm); the badge plain carbon.
  *Clipping, confirmed* (the pilot: "there is still some clipping in on the controller"): `tools/model/check_clipping.py`
  poses the model as `ControllerRender` does and counts points of every moving part inside any other part (closed
  parts: trimesh; the shell is not closed: 3-ray parity, majority) - both sticks in 8 directions at half and full
  deflection, every lever at both ends and the snap's overshoot. Found and fixed: the stick (GL) reached the gimbal's
  floor, so any tilt pushed its foot through - `clip` now slices it 1 mm under the inner plate's top (and the levers
  1.2 mm under their bushing's face, exactly, not whole triangles); the inner plate (G1) tilting with the stick came
  up through the frame and sliding went into it - it stays put; the sticks hit the slot's rim from 24 degrees -
  `MAX_TILT` 20; the shoulder levers (SE SF SG SH) stand in wells and touch the rims from 6 degrees - 5 for them,
  the front ones 16 (`leverThrow`); each lever throws about its CAD direction (the CAD has them in the middle, the
  shoulder bushings leaning back). Result: 0 of 64 poses. Run: `uv run --no-project --python 3.12 --with trimesh
  --with numpy --with scipy --with rtree --with shapely --with networkx --with mapbox-earcut python
  tools/model/check_clipping.py <mesh.bin.gz> 10,20` (the mesh: `src/main/resources/model/tx16s-mkii.bin.gz`);
  `tools/model/export_step.py` makes it from the STEP (`uv run --with cadquery-ocp==7.7.2`, 0.25 mm).
  Eighth round: the 2D stick squares (`GimbalView`) are gone from `CalibrationDialog` - the radio (560 x 600) is
  the only picture; the final check's switches snap over to a new place (`liveFrom` / `liveTo` / `liveStart`) instead
  of jumping. The radio's screen: Red ops (`redOpsScreen`: black and red, the DRAUPNE mark large, the flight state,
  ARMED) - the pilot picked it of ten screens tried in the app (HUD, EdgeTX, night vision, map, telemetry, radar,
  synthwave, blueprint, minimal; Minimal's large mark went into it), then had the picker and the rest removed ("make
  nr 2 be the skin, remove the selector and other skins"). The badge between the gimbals plain carbon; the radio its own
  colours (a try at body colours was dropped: "keep the original colour on the controller"). A STYLE picker over the radio in `CalibrationDialog` tries them live and saves the choice
  (`calibrationRadioStyle`, via MainWindow.save); default HUD.
  *Front toggles hinge again* (the pilot: they "no longer hinge like how they are supposed to"): hinged at the
  bushing's face about their CAD direction, their base wandered. A front lever (SA SB SC SD) now hinges where its centre
  line (`centreLine`, the covariance's main axis) meets its bushing's axis - 1.5 mm inside the bushing - and throws
  from its CAD end (15 degrees short ones, 27 long ones) through straight out to the mirror, at most `FRONT_MAX_TILT`
  22 (the long ones touch the face from 24). The shoulder ones are as before (their line meets the axis above the
  bushing: those bushings lean). `check_clipping.py` mirrors it: 0 of 64 poses.
  *Menu wipe behind the tour's notice* (the pilot's recording, tour step SETUP: "the animation happens behind the
  pop up and then jumps in front after it is finished ... should always be on top"): 1.0.38 put the notice at
  POPUP_LAYER + 10, over the menu's wipe (`TacticalCombo.Ghost`, POPUP_LAYER), but the real menu is a heavyweight
  popup, over everything. The wipe is now at `TacticalCombo.GHOST_LAYER` (POPUP_LAYER + 20, over `Tour.NOTICE_LAYER`)
  and `TacticalCombo.menu()` forces heavyweight, so wipe and menu are both always in front. Frames read off the mp4
  with OpenCV (`uv run --with opencv-python-headless`; no ffmpeg on this PC).
  *Video quality buttons* (the pilot: "the video quality buttons do not seem to work ... include buttons for all the
  video adjustments that the program may change it to"): in `ControlLink.adjustResolution` the sharp tier's branch
  never looked at the mode, so ROBUST (and AUTO) did nothing while sharp was on. `VideoMode` is now AUTO, SHARP,
  NORMAL, ROBUST, SURVIVAL; `forced()` gives the held tier and is decided first, from any tier (`decideForcedTier`
  too, before the first config). ROBUST now holds bad coverage mode (before it could still drop to survival: that
  is the SURVIVAL button now). `MenuRows.ChoiceRow` segments widen for the longest label.
- **1.0.39 radio horizon, GCS search, three tool groups, symbols, minimap - built, tests green, not released:** the pilot: "create a new
  tool, called radio horizon check ... aircraft altitude, antenna mast ... places the mast ... a radius showing the
  radio horizon ... no signal goes beyond 30km"; answers: terrain-aware, forest vs open fields counted, both heights
  above the ground at the mast (later: "the altitude is above ground from the point of the aircraft" - `compute`'s
  target is the ground under each sample + `aircraftM`), live only and one mast at a time; "the forest coverage has to be calculated based on
  colours of maps, due to all online sources being wrong" and "from one of the closest levels". `map/RadioHorizon`:
  per degree of bearing, 20 m steps to `MAX_RANGE_M` 30 km, a straight line over a 4/3 earth (`EFFECTIVE_EARTH_M`)
  must clear the terrain (a hill blocks outright) and the aircraft must be above the ground where it is; the aircraft
  keeps a fixed altitude, so a hill ends a bearing for good. Trees (the pilot: "penetration ... dependent on the
  frequency"): the samples where the line runs under the treetops (`treeM`) count as canopy depth (forest looked at
  every 10 m, `FOREST_LOOKS`), `foliageLossDb` = FITU-R in leaf 0.39 f^0.39 d^0.25 (f in MHz) at the `frequencyMHz`
  field, out of sight past `MAX_FOLIAGE_LOSS_DB` 20 (`forestDepthM`: 9 m at 5.8 GHz, 37 m at 2.4 GHz, 204 m at 800 MHz).
  Weissberger (45 m at 5.8 GHz) came first; the pilot: "45m of forest on 5,8Ghz seems too generous". `Sweep`: one
  bearing's ground / treetops / canopy metres, then per sample the hill test (steepest ground slope so far) and the
  canopy sum as a Fenwick tree over the treetops' slopes from the source (a canopy sample catches every flatter line),
  O(n log n); `RadioHorizonTest.theQuickForestCountIsTheSameAsCountingEveryTree` checks it against counting.
  **GCS SEARCH** (the pilot: "selects a area ... heat map 30km around the center ... what areas are good to place a
  antenna"): `RadioHorizon.search` - 5 x 5 points over the box, the aircraft `aircraftM` above the ground at each,
  a `Sweep` from each point with the mast (`mastM` on the ground there) as the target at every sample (line of
  sight works both ways), each 100 m cell of the 30 km circle round the box's centre takes its nearest sample (sweeps every 0.25 deg, `SEARCH_BEARINGS`);
  score = share of points that see it, best = highest, nearest the centre. Data loaded 30 km + half the box's
  diagonal round the centre (`searchRadiusM`). ~1 s per search on the Nordmarka data (25 points in parallel).
  `MapView` Tool.SEARCH (drag a box; the tool stays), the heat map one ARGB picture (a pixel per cell, drawn over its corners, nearest
  neighbour; red - yellow - green, alpha 110). Both results stay when the tool ends (the pilot: "an exit button ...
  it does not disappear ... unless crossed out"): `labelWithCross` puts a cross on each label, a click on it (`clearHorizon`
  / `clearSearch`) takes the result off and tells the window (null), the box, the 30 km ring, the best place ringed. `MapWindow.searchArea`
  computes after the terrain and again when the forest is in. Fields: `MapToolsPanel` shares four
  `SpinnerNumberModel`s between the two tools' field sets, each in a `SlideOut` under its button (220 ms, the bottom
  edge leading; opened by `syncTools` while its tool is on). **Minimap:** always on the LINK card under the figures
  with POP OUT MAP; the fields and figures no longer fold (the pilot: "Make the minimap not replace anything anymore").
  The log stays in the main window (the pilot: "keep it in the main menu" - the taller card had pushed it out):
  `MainWindow.fitMiniMap` on every layout of the link column gives the log `LOG_MIN` 180 px first, the minimap the
  rest up to `MINI_MAX` 190, and hides it under `MINI_MIN` 90 (POP OUT MAP stays). The first try,
  trees as a solid wall, gave a 10 m mast in Nordmarka 0.4-0.6 km all round (live run: 4 min, 8 042 forest tiles,
  ~390 MB the first time; compute 0.2 s). Terrain: Terrarium tiles
  (AWS, zoom 12) via `TileCache.fetchNow` (disk cache id `terrarium`, not kept in memory). Forest: `isForest` on the
  map pictures - Kartverket topo zoom 15 in Norway (forest #d2e096 and its hill-shade darker shades: green over red,
  far over blue; fields #f1ecb1 and bare mountain #e8d7aa have red on top), OSM zoom 13 elsewhere (#add19e; one tile
  at a time, its policy); each tile kept as a 128 x 128 bitmap, 2 of 4 pixels = forest so contour lines and names
  through a wood stay wood. Measured over the same wood near Oslo: 77 % forest at zoom 11, 81 % at 13, 85 % at 14, 90 % at
  15 - why 15 (about 7 600 tiles / 300 MB for a full 30 km circle the first time, nearest first, 4 at a time; cached).
  ESA WorldCover and Terrascope were looked at and dropped (the pilot: online land cover is wrong). `MapWindow`:
  `horizonMast` loads (one thread), `recomputeHorizon` (another, newest only) after the terrain and every 3 s while the
  forest comes in, and on a height change; `MapView` Tool.HORIZON (click places / moves, `cancelTool` clears it and
  tells the window, map hidden ends it), painted as quads in zoom-0 world pixels through a transform, dashed rings for
  30 km and the smooth-earth horizon. `MapToolsPanel`: MAP TOOLS slides out a strip of three flat tabs
  (`Group` DRAW / TOOLS / DATA), each slides its list out full height (one at a time). Symbols: `MORTAR`,
  `ROCKET_ARTILLERY` (artillery dot + the MRL amplifier in sector 1), `UTILITY` is now *Recovery / maintenance*, a land
  unit with the maintenance icon (the enum name stays for saved graphics.kml). Tour: DRAW, TOOLS, DATA and RADIO
  HORIZON steps, `FEATURE_RADIO_HORIZON`. Tests: `RadioHorizonTest`, `MapToolsTest` (horizon tool, groups). *Untried
  in the real window and against the pilot's area.*
- **Released:** DRAUPNE Link **1.0.38** (Latest, 2026-11-04, tag run 41801724895 green first try) with phone app
  **DRAUPNE Flight 2.3.0-pc.30** (release `flight-2.3.0-pc.30`, APK SHA-256 `83fe29a9…`, pinned in `ApkShare`);
  before it 1.0.37 with pc.29 (release `flight-2.3.0-pc.29`, APK SHA-256 `10a57f20…`);
  before it 1.0.36 with pc.28, and 1.0.35 with that phone app (release
  `flight-2.3.0-pc.28`, APK SHA-256 `4a2c346e…`, pinned in `ApkShare`). Nothing of it has flown or been tried on the
  phone; the names, the simplified phone app and the map tools are all untried on hardware.
- **1.0.38 deep sleep hellos (phone pc.30), tour box on top, README - released 2026-11-04:** the pilot:
  "When the phone is in deep sleep mode it does not report its alive and battery % every time it wakes up."
  `PowerMode.openWindow` sent three hellos at 200 / 700 / 1200 ms; now `helloPending` repeats `powerHello` every
  `HELLO_GAP_MS` 1.5 s from `HELLO_FIRST_MS` for the whole window (cancelled by `cancelPending`); the PC's SLEEP_ANSWER
  still ends the window. Reasoning: the phone's Tailscale tunnel sleeps with the phone and drops the first packets
  after a wake; no PC-side change (`ControlLink.onSleepHello` was fine). APK built `build/flight/DRAUPNE-Flight-2.3.0-pc.30.apk`
  (versionCode 2030030, signer d910997f…), SHA pinned in `ApkShare` - *publish that very file*. "The animation bug with the
  tour ... It should be always in front": `TourNotice.pop` adds the notice at layer POPUP_LAYER + 10 (was DRAG_LAYER,
  the same layer as `WindowReveal`, added later = under it). README rewritten round the new features, with rendered
  pictures in docs/images (boot-animation, map-tools, symbols, elevation-profile, tour - from throwaway tests, no
  screen captures: a screen capture here caught the pilot's live session once and was deleted unseen by anyone).
- **1.0.38 boot jitter - released 2026-11-04:** the pilot: "the boot animation on pc still jitters" after
  1.0.36's combo-width fix. Measured with a throwaway print in `BootReveal.paintComponent` (the targets and the glass
  pane's size on every change): the cards never moved; the glass pane's height flipped 1032 <-> 1009 on every frame.
  `BootReveal.follow()` (from the earlier layered-pane version) set the glass pane to the root pane's full size on
  every tick, and `JRootPane`'s layout set it back to the layered pane's size inside the root pane's 23 px insets.
  Everything painted from the height (the mark at h/2, the brackets at h) jumped 11 px every other frame. `follow()`
  is gone; the layout owns the size (as `onOpen`'s comment already said). Measured again: one size, no flips.
- **1.0.37 CLEAR ALL + RENAME - released 2026-11-04:** the pilot: "Clear all should also remove pins and
  rulers" - `MapWindow`'s CLEAR ALL clears `graphics`, `pins` (`Pins.clear`) and the live measurement
  (`MapView.hasMeasurement` / `clearMeasurement`). "Make it possible to rename the names of phones in the name map" -
  `NameMapDialog.renameSelected` (RENAME button, phones only, online only): a free Mobile name from a combo,
  `Names.rename(from, to)` (the entry moves, old key cleared, new key written), `NameService.assign(ip, name)` sends
  NAME_ASSIGN from an ephemeral socket and scans; the LINK card's `phoneName` follows. Test: `NamesTest.renameMovesAPhone...`.
- **1.0.37 PREARM - released 2026-11-04:** the pilot: "Add the possibility for prearm ... controller
  calibration defaults aux 3 to prearm and ... the betaflight setup sets it to betaflight mode as well". `ModeSwitch`:
  `PREARM_KEY` drone.prearmAux (default 3, off = leave), `MODE_PREARM` 36 (msp_box.c permanent id), `PREARM_RANGE` up,
  `parsePrearmAux`, `prearmAuxes`, a 7-arg `plan(..., armAux, prearmAux)` (the 6-arg one leaves PREARM) with
  `Plan.prearmProblem` (refused on the ARM, flight-mode or rescue switch - lowest priority, so the others are written
  as before); PREARM already on the chosen switch keeps its range like ARM, PREARM elsewhere is taken off (one prearm
  switch); `purposes` names the AUX "PREARM" (after ARM / modes / rescue, first come first). `DroneSetup`: `prearmRow`
  under Switches, a PREARM combo beside ARM (`prearmChoiceChanged`), `prearmTarget` / `prearmAuxes` in `Read`, the
  round passes it through, *By hand* lists it. Example properties: drone.armAux / drone.prearmAux. Tests:
  `ModeSwitchTest.prearm*`. *Untried on a flight controller.* Open question for the pilot: with a PREARM mode set
  Betaflight blocks arming (NOPREARM in RC CHECK's blockers) until the switch is up - pilots with no third switch
  should pick *Leave as it is*.
- **1.0.37, phone app pc.29 - released 2026-11-04:** the pilot: "Remove the phone name and [IP] in the
  app, the app should only show the name". `MainActivity.updatePhoneInfo` sets THIS PHONE to `phoneName()` alone (or
  the "No name yet" hint); it still scans the interfaces for the colour (green with a Tailscale address, yellow
  without) and `phoneInfoAddress` (the long press's copy). The unused `phoneNameLine` and the strings
  `dd_phone_tailscale` / `dd_phone_tailscale_off` / `dd_phone_other_ip` are gone; `deviceName()` stays (it goes in
  the NAME_REPLY for the PC's name map). APK built locally as `build/flight/DRAUPNE-Flight-2.3.0-pc.29.apk`
  (versionCode 2030029, signer d910997f…), its SHA-256 pinned in `ApkShare` - *publish that very file* as
  `flight-2.3.0-pc.29` at release (a rebuild gives a different SHA; the release workflow checks the pin).
- **1.0.36 - released 2026-11-04:** *symbols to the standard* (the pilot: "The symbols need to follow the
  standard"): `gui/UnitSymbol` rewritten on MIL-STD-2525D / APP-6D geometry in the standard's 200 x 200 symbol space
  (the paths as spatialillusions' milsymbol, MIT, writes them), scaled by `UnitSymbol.SCALE` 0.3 px per unit. A small
  SVG path parser (`UnitSymbol.path`: M/L/H/V/C/Z, absolute and relative, implicit repeats, cached). Land frames per
  affiliation; `isAir` (ISR_FIXED, ATTACK_FIXED, ISR_ROTOR, ATTACK_ROTOR, UTILITY) takes the air frames (friend and
  unknown open at the bottom: the fill closes, the stroke does not). Land icons are the standard's per-affiliation
  variants (`byAffiliation`), medical a cross across the frame's tight box, HQ the line at y=80 and a 60-unit staff down
  from the frame's left bottom corner. Drones: the fixed-wing ones the UAV main icon (the filled chevron bar), the
  multirotors the vertical-take-off UAV icon, the role as the standard's sector-1 text amplifier (textm1: y 71, size 28,
  bold Arial): R reconnaissance, A attack, U utility - *UTILITY is a utility drone now* (an air symbol); if the pilot
  meant a land unit, give it a land frame and an icon. Echelons (`echelon`) from the frame's top as milsymbol places
  them (team ring r15 at y1-20 with the slash, dots r7.5 at y1-20 at 85/115 or 70/100/130, bars 25 long from y1-10 at
  90/110 or 80/100/120, crosses 25 wide from 87.5 or 70/105), a white halo under them for the dark map.
  `UnitSymbol.bounds(cx, cy, unit, affiliation)` is the frame's tight box (Java 21's `Path2D.getBounds2D` is tight)
  - `MapView.itemAt` / the eraser use it. `MapGraphics.Echelon.mark` is still the text in the tools panel's combo.
  *Boot animation jumped* (the pilot): `phoneCombo.setPrototypeDisplayValue` in `MainWindow` - the combo's width
  followed its items, which the name service changes about a second after the start, so the LINK card (the
  animation's target) moved. Tests: `MapToolsTest.symbolsPaintEveryTypeAndAffiliation` (fill count, bounds).
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
  (a snapshot without the zoom buttons, for EXPORT). `gui/UnitSymbol` (1.0.36: 2525D geometry, see above; before: APP-6-like frames by affiliation, icons by
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
  - **In flight:** up to 1.0.38 the LINK card's fields and detail figures folded (`gui/Fold`) and the minimap + POP OUT
    MAP unfolded; since 1.0.39 the minimap and POP OUT MAP are always on the card under the figures and nothing folds
    (`updateFlightLayout` only sets the minimap following). The controller card
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
