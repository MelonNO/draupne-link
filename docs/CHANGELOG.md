# Changelog

Every DRAUPNE Link release (DroidDrone PC up to 1.0.15), newest first. The release pages on GitHub show only that version's changes.

## 1.0.50

With phone app DRAUPNE Flight 2.3.0-pc.39 (sound in the air).

- **TOOLS > Advanced, off by default:** hides what most pilots never need - PC setup, Phone setup and Drone setup (SETUP), Motion detection, Camera view on the map, Hardware video decoding and the OSD with its layout (VIDEO), and both STANAG 4609 rows (LINK), and TOOLS > Virtual fleet. Hidden, each keeps its setting; turning Advanced on shows them all again.
- **Hardware video decoding is on by default** (the GPU; software when it can't). Turned off by hand before, it stays off.
- **Arming mutes the sound by itself; unmute in the air if you want it.** At the arming MUTE goes on (the log says so); M or the loudspeaker brings the phone's microphone back while flying - the phone (pc.39) then sends it armed too, about 60 kbit/s on the uplink. At the disarm the sound comes back unless you changed MUTE yourself in the air. An older phone app stays silent while armed. AUDIO flags bit 1 (PROTOCOL.md).

- **The virtual fleet: try DRAUPNE Link without any hardware.** TOOLS > Virtual fleet (with TOOLS > Advanced on) starts it; or start `"DRAUPNE Link.exe" fleet` (or `Virtual fleet.bat` next to the exe; from source `gradlew run --args=fleet`) and several virtual drones appear in DRAUPNE Link's PHONE list, so the window, the map, OBSERVATION and OVERWATCH can be tried on one PC. DRAUPNE Link looks for them only while the fleet runs (it announces itself in `%APPDATA%\DraupneLink\virtual-fleet.txt`, loopback addresses only), so nothing changes for real drones.
- Virtual drones speak the real phone protocol and fly like Betaflight: arm on AUX1 with the throttle low, ANGLE on AUX2, a draining 4S pack, the RX-loss failsafe (drop or land).
- Their camera is a drawn horizon with the drone's name and a clock, or an old flight of yours looped (with its track, if the CSV is beside it); their microphone has wind, motors and now and then a voice.
- The fleet window plays the bad days: 4G loss, a weak link (loss, jitter, bunching), a cell handover, flight controller lost, phone app restart; and overwatch and observation: a walker, vehicle or light crossing the picture for motion detection, an overwatch alert with pre-roll, live preview and clip, standby / deep sleep / wake and check-ins, voices for the speaking highlight.
- **PLACE PHONE** on a drone's card in the fleet window opens a map with every virtual drone on it: click where that drone should stand and it is put there on the ground (disarmed only); click again to move it again.
- A virtual drone is always marked VIRTUAL (PHONE list, a yellow tag on the video, the maps, its picture) and is never saved to the name map.
- **Pins: copy a position, add one by grid.** Right-click a pin on either map: *Copy <pin>'s position* puts it on the clipboard in the chosen format (MGRS, UTM, lat lon or deg min sec). In PINS, COPY (or Ctrl+C) copies the selected pin's, and ADD AT GRID asks for a name and a position typed or pasted in any of those formats, puts the pin there and centres the map on it.
- **The overwatch alert card shows again.** It was drawn one pixel wide - a black bar beside the map - because its size was read from Java's ImageObserver.WIDTH (1) instead of the panel's 340. It now shows the pictures from before the alert as soon as they arrive, the time, the drone, how many pictures came, CONNECT / DISMISS / CLIPS, and while the preview waits the reason in plain words.
- **The map tells sleeping drones from lost ones.** Each drone has a sign of its own: a diamond (heard now), a moon (STANDBY / DEEP SLEEP, on time with its check-ins), an eye (OVERWATCH, on time), a red diamond with "!" (LOST: it went quiet while flown or armed) and a grey circle with "?" (UNKNOWN: quiet while idle, or asleep but it missed its check-in - a whole check-in interval and a minute past the one it said it would make; a PC started meanwhile gives it one interval first). The drone you put to sleep shows its moon or eye, not LAST KNOWN, while it checks in on time.
- **Every phone on the network shows its mode on the map, connected or not**: its name reply now says STANDBY, DEEP SLEEP or OVERWATCH (phone app pc.39; NAME_REPLY gains a power byte), so a phone another PC put to sleep gets its moon or eye too ("the phone says").
- **LAST KNOWN goes once the drone is heard of again**: an overwatch alert, a sleep check-in or its phone's position after the track's last point - its newer sign (or the alert) shows instead.
- **OBSERVATION: a drone whose phone sees something move stands out on the map** - a red ring (pulsing) round its number and MOTION beside it, while its tile shows the boxes.
- OBSERVATION: a tile's MUTE, volume and CONNECT keep their place on the right; the name and status line give way (cut with "...") instead of pushing them off a narrow tile.
- **No STANDBY button: DISCONNECT leaves the phone in standby** (camera off, GPS and telemetry slowed) when the drone is disarmed and the phone app is pc.21 or newer; the next CONNECT wakes it in about 2 s, and the map shows its moon meanwhile. DEEP SLEEP and OVERWATCH share the row.
- The LINK card is quieter: no hint line under the PHONE list and no status text under CHECK EVERY.
- **WAKE shows only while the phone sleeps, overwatches or stands by** (connected and awake it is hidden).
- **Parts of the LINK card slide open and shut** instead of jumping: the power row, WAKE, the phone and phone-app warnings, UPDATE PHONE APP, LOST DRONE and IT'S DOWN - RESUME.
- **CONNECT on an overwatch alert connects at once** - no confirm box (a PC that may still fly another drone is still refused).
- **Right-click a sleeping or overwatching drone on the map: Wake (then STANDBY).** This PC connects, the phone wakes at its next check, goes to STANDBY by itself and the connection ends: the phone stands by, back in about 2 s at the next CONNECT.
- **STANDBY, DEEP SLEEP and OVERWATCH disconnect by themselves** once the phone is in the mode. A sleeping phone stays in the PHONE list with WAKE and its check-in countdown, and this PC still answers its checks; a phone in STANDBY wakes at the next CONNECT (about 2 s) and shows the moon on the map until then.
- **A drone in OVERWATCH shows its camera's view on both maps** all the time it watches (calm teal, dashed), not only at an alert; an alert draws it red as before The view is worked out for a drone standing on the ground: its camera half a metre over the terrain where it stands, out to 300 m at most (it was drawn to 3 km, then vanished once the terrain came - the drone's own height, over launch, put the camera under the hill).
- **The minimap is always there while the map isn't popped out**, and takes all the LINK card's spare height (a wrapped warning used to hide it and leave empty space round the card). With the map popped out the minimap goes and the log takes the room.
- A drone being connected to keeps its sign on the map until its live position comes (connecting to a phone in OVERWATCH or DEEP SLEEP made it vanish while it woke).
- **Names on hover:** a drone's name and state on the map show while the mouse is on its sign, not all the time.
- **An overwatch alert is drawn over everything else on the map** (it was under the drone's label).
- **The overwatch alert card has an X** in its corner: it closes the card as DISMISS does (the preview stops; a new alert opens it again).
- Virtual fleet: the box at the bottom of its window is the FLEET LOG, titled, with CLEAR, and with the lines from before the window opened; the status line at the top is no longer cut off.
- Virtual fleet: a cuter microphone - a soft breeze, a little bird now and then, a round motor hum, and a critter's gliding bip-boop babble as the voice.
- **"A drone here may be flying" says why and can be ended.** The lost-drone window (10 min after a drone was lost armed) is kept per drone now: connecting to that drone again and hearing DISARMED ends it at once. While it holds, the alert card ("LIVE PREVIEW PAUSED - MobileZulu was lost armed 7 min ago; a drone here may be flying (until 00:06)"), the map ("SAVED TILES ONLY - ..."), the log and UPDATE PHONE APP say why; **IT'S DOWN - RESUME** on the LINK card and on the alert card ends it after a confirm, logged. It never overrides a link that says armed and sends nothing to any drone.
- **Motion detection: tracks failed with a large clock.** The shared detector kept a track's times as floats of the raw clock, so with the PC's wall clock (the virtual fleet) nothing ever "travelled", and a phone up for days lost precision too. Times are now counted from a recent start. (Shared code: the next phone build carries it.)
- **Virtual fleet: what moves is drawn into the camera picture, and only the motion detection raises an alarm.** WALKER (a person walking, 10 m out; a torch at night), VEHICLE (a car with turning wheels, 25 m out at 30 km/h; headlights at night) and LIGHT (a person with a lamp) cross in front of the drone where it stands - they crossed in front of its start point, so a flown or placed drone never saw them - over a looped old flight too. The real motion detection, as on the phone, finds them 2-5 s after the press while they still cross: boxes on the video and the direction on the map when connected, an overwatch alert with them in the pictures before it, the preview and the clip. ALERT NOW, which faked a detection, is gone: SOMEONE NOW sends a person across 6 m from the camera instead. The fleet log says whether motion detection is asked for.
- Virtual fleet: the drones start in the middle of the MGRS square 32V PN (30 m apart); the fleet window shows the start place as MGRS, and START PLACE takes MGRS, UTM or lat lon.
- Tests: 709, among them a virtual drone against DRAUPNE Link's real link, video pipeline and overwatch hub end to end.

## 1.0.49

The fix round from the full review of 1.0.48 (phone app 2.3.0-pc.38): every finding fixed, safety around arming first. Nothing of 1.0.44 - 1.0.49 has run on a phone, a flight controller or a drone yet: bench-test before flying (the bench plan in HANDOFF).

- **Arming.** BEEPER moved from AUX12 to AUX14 and can no longer arm the drone: it does nothing, and says why, when ARM or the radio uses AUX14 or Drone setup hasn't put BEEPER there alone (`drone.beeperSet`); it beeps only while Betaflight reports the drone freshly disarmed, at most 10 s a press, and lets go when the window loses focus. Stick frames carry only the radio's channels again (padded to 8, plus AUX14 for BEEPER, 0 in between): a switch the radio doesn't send no longer turns a mode on; an ARM channel the radio doesn't send, or sends as 0, counts as unknown - the sticks wait with ARM SWITCH NOT SENT.
- **Idle sticks** (phone pc.38): checked by the phone against Betaflight's own arm ranges (MSP_MODE_RANGES, read once a second; unknown = refused), forgotten at every new connection, and sent by the PC only while the radio's own frame has the throttle low and the arm switch off - moving ARM in Configurator can't arm a drone sitting on idle sticks. An idle frame and a PC frame share one lock on the phone.
- **No takeover from the pilot's own address**: a second DRAUPNE Link on the pilot's PC (or behind the same NAT) can't take a drone that isn't known disarmed while the pilot is still heard (2 s). The link probe only ever watches. STANDBY, DEEP SLEEP and OVERWATCH ask first when the drone was armed moments ago, as DISCONNECT does.
- **Drone setup writes all or nothing.** A backup first, or nothing is written; every mode slot is cleared before it is refilled; each aux line Betaflight echoes, and a read-back of `aux` after the writes, is compared with what was sent; any refusal or difference leaves the CLI without saving - the flight controller stays exactly as it was, and the page says why (the orientation check's alignment fix too). CONFIRM belongs to the flight controller it was pressed for (MSP_UID): a drone plugged in afterwards needs its own. A GPS RESCUE switch or PREARM you set yourself is left alone. ARM is saved from the modes as written. The compass counts as calibrated only when its offsets changed. The motor test goes to 1100 at most (was 1300), and every motor order, direction and props change is logged with the value before and after.
- **While a drone on this PC may be flying** (armed, or armed within 10 min before its link went quiet) nothing else uses the network: map tiles and prefetch, area downloads, heights, cell data, overwatch clips and previews, update checks and the phone's remote update all wait. Overwatch alerts in flight are silent and raise no window; CONNECT on an alert card asks first. The drone can't be armed while its phone is installing an update or waiting for the tap; the phone stops a remote update's download the moment the drone isn't known disarmed.
- **The LINK card** turns red with LINK STALLED (0.5 s) or FC LOST instead of staying green. Two phones with the same name are detected; CONNECT to that name is refused and offers to rename one.
- **Video.** A frame lost whole is now noticed: a key frame is asked for and the bitrate steps down, instead of a smeared picture for 2-10 s. A stray frame from before a stream restart no longer freezes the picture for 3 s. The maps redraw only when something changes, the track stays a fixed size however long the flight, and the camera's view on the map is worked out off the thread that shows the video - long flights no longer make the video jittery. The OSD's fonts are made once. A malformed packet can't claim more than a 1080p frame's memory.
- **Recording.** A restarted stream gets its own file from its first picture, and nothing of it goes into the old one; stopping and starting a recording quickly breaks neither file; the sound stays in step with the picture over hours; the OSD copy is closed properly when the app closes. A slow disk logs a summary, not a line per frame.
- **Settings** are written with proper escaping: a folder path with `\u` in it no longer stops the app starting; older files still load; a name from the network can't add lines (only valid DRAUPNE names are taken).
- **Map.** Tiles for what is on screen load first; tiles panned away from are dropped. A broken `graphics.kml` or `pins.txt` is kept aside as a `.broken-` copy instead of being overwritten. The OpenCelliD daily count survives a restart.
- **OVERWATCH.** The phone records an event in 1-minute pieces (an app that dies mid-event loses at most the last minute), keeps waiting clips across a restart, within 2 GB and 1 GB of the phone free, and gives a clip up after 5 tries instead of blocking the rest; the PC shows an event's pieces as one clip and plays them in order, checks a clip only once, retries a damaged one at most twice, and removed clips stay removed. At the end of an event the camera lets go of the recording before it is closed (some phones stopped watching with a camera error); waking a phone after a long event can't close the camera the pilot just opened. A phone on OVERWATCH accepts no observers. The overwatch work and the clip list no longer run on the window's thread.
- **Sound.** Live sound no longer keeps a phone CPU core busy, and a quick microphone restart can't leave two readers. A drone taken out of the observation grid always leaves the mix.
- **Clocks.** Ping, spectator, video and armed-state timing use monotonic clocks on both sides, so a Windows or phone clock correction no longer distorts them.
- **UPDATE** checks the installer's Windows signature as well as GitHub's checksum (unsigned is still accepted until releases are signed).
- **Map tools**: no heading above a tool whose button already names it.
- **Protocol** (PROTOCOL.md): stick frames as before 1.0.44 (AUX14 for BEEPER, 0 = not sent); the phone reads MSP_MODE_RANGES; ping's time is the sender's monotonic ms; CLIP INFO gains `[part][eventStartedAt]` and CLIP_DONE `[startedAt]` (both optional tails); PHONE_UPDATE 2/3 block arming.
- Tests: 670 on the PC, among them a test per arming fix that fails on 1.0.48 (BEEPER with ARM on AUX14 / AUX12 and inverted ranges, idle frames with ARM moved, the takeover, channel 0 = unknown), a fake Betaflight built from its `cliAux` logic for the all-or-nothing writes in 4.4, 4.5 and 2025.12, lost-frame counting across the counter's wrap, and the two timing-flaky video tests made steady.

## 1.0.48

- **OBSERVATION: up to six idle drones at once, phone app 2.3.0-pc.37** (CONNECT AS > *Observation*): CHOOSE DRONES picks them from the name map (remembered as a set; a drone another PC flies, or with an app older than pc.37, is greyed with why), OBSERVE fills the main window's video area with a grid - 1, 2x1, 2x2 or 3x2 by count - and STOP OBSERVING ends it. Each tile: its number and colour, the drone's name, its live picture (640x360 at 15 fps, 0.5-1 Mbit/s), the motion detection's boxes when the drone's setting has it on, a green frame while its phone hears something, MUTE and volume for its sound, and CONNECT. A click selects a tile and its phone sends this PC's NORMAL picture (its camera size at 30 fps, its bitrate limit; the camera restarts once for it, about 2 s); a double-click enlarges it and back. This PC flies none of them: no RcSession, no stick frames, no Connect - and the phone takes no sticks from an observer (it is never a session there).
- **Every drone heard at once**: each phone's sound through the 1.0.45 path (FFmpeg's AAC decoder, a 200 ms jitter buffer of its own) into one mix on one line, each with its own MUTE (remembered per phone, as the pilot's; after 10 s muted that phone is asked to stop sending) and volume (`observe.volume.<phone>`); one thread at the lowest priority for all of it. The speaking highlight is the sound's level against the phone's own background (a steady wind or hum becomes the background within seconds), two frames in a row to start it and 0.6 s held after the last, so it doesn't flicker.
- **CONNECT on a tile**: every stream stops at once - the mix, then each phone told OBSERVE_STOP and its socket closed - and the usual pilot connection opens to that phone, with the usual gates (throttle low, the arm switch off). DISCONNECT afterwards offers *Back to the grid* with the same drones. A drone that gets a pilot while in the grid (another PC connects to it) leaves the grid with a line in the log; the takeover rules are unchanged.
- **The map**: each observed drone as a numbered dot in its tile's colour, with its view cone (the picture's width fanned out from the phone, 400 m or to where it meets the ground). Hovering a tile makes its cone stand out; clicking a cone selects its tile. The heading is the flight controller's compass when it has one (MSP status says so), else the phone's own rotation vector sensor (where its camera looks); the tile says *heading approximate* - and the cone's edge is dashed - until Drone setup has read the drone's compass as calibrated, or while the phone's sensor says its accuracy is low.
- **A drone in deep sleep** can be chosen: it joins at its next check-in (this PC answers it "wake" when it was the last to fly it). A drone in STANDBY says so on its tile.
- **Phone (pc.37)**: OBSERVE is answered while no pilot is connected (nor heard within 10 s, unless it is that same PC coming back to its grid), the flight controller reads DISARMED and the phone is in normal power mode; up to 3 observer PCs, all getting the one encoder's stream (the biggest picture any of them asks for). The observers' profile is H.264 with a parity per group of packets; the stream's config goes ahead of every key frame. A pilot's Connect ends the observation for all of them at once (they are told why) and its Config sets the camera as before. The phone's heading sensor runs only while observed.
- **Protocol** (PROTOCOL.md): `OBSERVE` 0x50, `OBSERVE_STATE` 0x51, `OBSERVE_STOP` 0x52 (0x53-0x57 reserved); record `OBSERVER` 0x40FE (0x40FF reserved) - all in the shared `Common/.../Observe`.
- Tests: `ObserveProtocolTest` (the packets, the record through the telemetry, the phone's observer table, the camera's direction from the rotation matrix), `ObservationTest` (the grid by count, selection and the sharp picture, CONNECT stops every stream first, the idle-only rule, a drone leaving the grid, the heading's choice, the cone), `ObservationAudioTest` (the mix, the speaking highlight and its hold time), `ObserverLinkTest` (against a made-up phone on this PC: what it asks, what it does with the answers, never a stick frame). Nothing of it has run with real phones.

## 1.0.47

- **OVERWATCH SLEEP, phone app 2.3.0-pc.36** (LINK card, under STANDBY and DEEP SLEEP; disarmed only, and arming ends it as it ends deep sleep): the phone sleeps as in deep sleep - screen off, the link down between events, the GPS, the flight controller's port, the telemetry, the microphone and the timer stopped - but its camera runs the motion detection's small picture alone (no encoder) under a partial wake lock in the foreground service. Its checks for the PC and its *still here* with the battery go on as in deep sleep, now saying OVERWATCH.
- **The alert:** a detection that travels (the 1.0.46 detector - not vegetation; moving lights at night) calls the PC that put the phone to sleep at once, again every second until that PC answers: the drone's name, position, the box and the direction, and the last 5 s of the small picture (JPEGs, two a second). Then a live preview (H.264, 1 Mbit/s) while the card asks for it, and a **full-quality recording on the phone** (its own H.264 encoder at the camera's size, 3-12 Mbit/s, with the microphone unless the drone's sound is muted on this PC) until the motion has ended - 30 s without a detection.
- **On the PC, only in the map window:** the drone's symbol pulses red, the map pans to it when it is off the screen, its camera's view in red and the detection's direction dashed; a card with the live preview (the pictures before the alert until it comes), the time, **CONNECT** (wakes the phone and connects as its pilot) and **DISMISS** (the preview stops; the drone stays silent until the motion ends, never longer than 10 min); a rising three-note sound and the window in front. While this PC flies (its drone armed) the alert comes quietly - no sound, nothing raised over the video - and the clip is still recorded. With the map window closed, POP OUT MAP says OVERWATCH and nothing pops up. Not answered, the card keeps its last picture 1.5 min after the motion ended, then shrinks to a badge.
- **The clip:** after the event the phone's clip comes to the PC over the link in 1 KB chunks, 64 at a time, asked for by the PC - never while a drone on this PC is armed (it waits and goes on) - into a `.part` file that a stop never wastes, checked with SHA-256; only then does the phone delete its copy. Until it is here, the preview the PC received is the event's clip (recorded to MKV as it came).
- **CLIPS in the map window** (MAP TOOLS > TOOLS > CLIPS, or right-click a drone): per drone, each event's picture, time, length and place; PLAY plays it in the map window (the pictures before the alert first, then the clip; FFmpeg, its own thread at the lowest priority; pause and seek) and draws the camera's view as it was on the map. REMOVE takes it off the list only; FOLDER opens Explorer on it; the folder's size is shown. Kept until removed by hand: `Documents\DRAUPNE Link\Overwatch`, a folder per drone.
- **The floor:** under 20 % battery (not charging) or hot (Android's thermal status SEVERE or worse, API 29 on; else the battery at 45 C), two readings 30 s apart, the phone goes to deep sleep by itself, tells the PC why (`STATUS`, and its sleep hello), and the PC's log and LINK card say so.
- **Protocol** (PROTOCOL.md, only to phones of pc.36 or newer): POWER_MODE mode 3 with sensitivity, flags and a token; `ALERT` 0x7A, `COMMAND` 0x7B, `PREVIEW` 0x7D, `CLIP` 0x7E, `STATUS` 0x7F, outside the link from the phone's own socket to the PC's link port; the sleep hello gains two bytes; PHONE_POWER caps bit 3. Records 0x40FB-0x40FD set aside, unused.
- A tour step for OVERWATCH (in the sleep buttons' demo) and one for CLIPS.
- Battery: an estimate, not measured - about 15-25 h on a 5000 mAh phone in overwatch (the camera never sleeps), against 2-7 days in deep sleep. Makers' battery savers may end the app with the screen off: README, Troubleshooting.
- Tests: the protocol both ways, the fragments and their assembly, the pre-roll, the floor, the phone's event (30 s quiet, the dismiss and its 10 min cap, the clip's cap), the PC's card (the 1.5 min shrink, lost events), the clip's transfer (windows, gaps, the pause while armed, going on from the .part, the SHA mismatch), the clip list (REMOVE hides only), the hub against a made-up phone, and the preview decoded, recorded and played back (MKV, and MP4 with length-prefixed frames). Never run on a phone.

## 1.0.46

- **Motion detection while the drone is disarmed, phone app 2.3.0-pc.35** (VIDEO > Motion detection: OFF / LOW / NORMAL / HIGH, per drone, NORMAL unless set): the phone watches a small second picture from the camera (about 320 wide, 8 a second, the video's shape) and the PC draws a thin square round whatever travels through it, **MOVING** over it (**LIGHT** in the dark), fading out a second after the phone last saw it - on the video, on spectators' screens (it comes with the telemetry they get), and in the recording with the OSD. People, cars, animals and birds count; leaves and branches in the wind don't. Never in the air: the phone runs it only while the flight controller reads DISARMED and stops within 0.1 s of anything else, and it never touches the encoder's pictures (the internal camera's extra stream is in the camera's request only while disarmed; a USB camera's frame callback likewise).
- **How the wind is told apart** (`MotionDetector`, pure Java in the shared `Common` overlay, run by both apps): the picture's shake measured and taken out; exposure steps and pictures that change as a whole (a shove, a cloud's shadow) not judged; a per-pixel background whose variance grows where leaves keep moving; a thing counts only when it travels - 2 s (NORMAL) of nearly straight way in one direction, not back towards its start, not leaving the ground changed behind it (a shadow's edge does) - and when its vegetation score (how often the pixels round it flickered in the last 20 s, how green, how much it turns back and forth) stays low. No trained model: plain statistics. At night it follows only lights that travel; sensor noise never forms a track.
- **On the map**: a dashed line from the drone along each detection's direction (the heading, turned by where the box is across the picture through camera.hfovDeg and camera.tiltDeg) and, where that ray meets the terrain, a ring marked **EST** with the distance - an estimate; both fade out over 30 s. The log says when something new travels (at most once in 10 s).
- **Protocol** (PROTOCOL.md): `DETECTION_SET` 0x7C PC to phone once a second; record `DETECTION` 0x40FA phone to PC in a packet of its own (flags, the phone's field of view, up to 8 boxes with id, box, angle, confidence, vegetation score, kind).
- Tests: `MotionDetectorTest` on made-up pictures (20 minutes of a gale in four patches of trees with the camera shaking: no alarm; HIGH 5 minutes: none; a walker found within 3 s with one id; a bird in the sky; shake taken out; exposure steps and a cloud's shadow ignored; a torch at night found, sensor noise not), `MotionBoxesTest` (the record, the fading, the rays and marks). Never tried on a phone or on real footage.

## 1.0.45

- **Live sound from the phone's microphone, phone app 2.3.0-pc.34** (`audio.LiveAudio`, `PcAudio`): while the drone is disarmed and the PC is connected, the phone streams its microphone (AAC-LC, 48 kHz mono, 48 kbit/s - about 60 kbit/s on the link) and the PC plays it on the default sound device: FFmpeg's AAC decoder, a 200 ms jitter buffer (back to 200 ms when it passes 450), all on one thread at the lowest priority - nothing of it on the stick, video or Swing threads. The phone stops sending within one status reading of the flight controller saying ARMED (or its state going unknown) and starts again at the disarm, so the uplink in the air carries what it did before. A high-pass filter on the phone (120 Hz, first order) keeps wind rumble out. The PC asks with `AUDIO` 0x79 once a second (`[on][kbps][flags]`); an older phone app is never asked, and the log and the button's tooltip say the sound needs pc.34.
- **MUTE button** (the filled loudspeaker under BEEPER; yellow with a cross when muted) **and the M key**: silent at once; after 10 s muted the PC tells the phone to stop sending (data and battery), unmuting starts it again in about half a second. Remembered per phone (`audio.muted.<phone>`). Alert beeps lower the stream while they play.
- **Spectators hear the pilot's sound** (relayed like the video, the config in the join snapshot), each with their own MUTE.
- **Recordings carry the sound**: the raw `flight-....mkv` and the `-osd.mkv` get an AAC track (the phone's frames copied, timestamps counted from their arrival - `AudioPts`), declared when the stream's config is known or the phone is pc.34+; the muxer waits at most 1 s for the other track.

## 1.0.44

- **The GPS goes on one UART only, with Betaflight 2025.12 too** (`GpsSetup`): 2025.12's CLI lists the ports by name (`serial UART3 2 ...`, its own ids 51-60 for UART1-10); the app understood none of those lines, so a GPS on another port was never taken off it, and the chosen port was written by number with default baud rates and nothing else on it - which is how VTX Tramp and the ESC sensor came off UART1 and UART6 of the pilot's F722. Now both layouts are read, every port is written back named as the CLI named it, the GPS comes off every other port (only its bit: what else that port does stays).
- **A silent drone** (`setup.Beepers`, Drone setup writes it with the rest): every Betaflight beeper condition off but receiver lost (the phone's USB out), the failsafe's landing, BEEPER and ON_USB (no sound; off, it would mute even the button without a battery). By name in the CLI, as the mask's bits moved between 4.5 and 2025.12.
- **BEEPER button** (the loudspeaker under the video's full-screen button): hold it and the drone beeps - the buzzer and, disarmed, the motors (DShot beacon on BEEPER, `beacon RX_SET`). Drone setup puts Betaflight's BEEPER mode alone on **AUX12**, which the app drives itself (a radio's AUX12 is overridden; BEEPER on any other switch and any other mode on AUX12 are taken off).
- **Idle sticks while disarmed, phone app 2.3.0-pc.33** (`IDLE_STICKS` 0x78): Betaflight 2025.12 beeps "receiver lost" whenever no sticks come, disarmed and never armed too, so a drone on the phone beeped while the PC sent none. The PC sends the phone safe sticks once a second (throttle low, sticks centred, the arm switch outside its arm range, BEEPER low, the other AUX as the radio has them); the phone feeds them to Betaflight only with a fresh DISARMED status read after the PC's last stick frame, 300 ms after it. Armed, nothing changes: a lost link is Betaflight's failsafe (and beeps, the pilot's choice). Not in deep sleep, which lets go of the flight controller's port.
- **MAP TOOLS > TOOLS > 4G COVERAGE: an estimate of where the drone has good, fair, weak or no 4G** (`CellCoverage`, `OpenCellId`): no operator publishes an open coverage map of Norway, so it is worked out from where OpenCelliD's open database (CC BY-SA 4.0, a free key: KEY...) puts the 4G and 5G cells of the chosen network (All / Telenor / Telia / Ice) and the terrain between them and the drone (2, 50 or 120 m above the ground): RSRP at 800 MHz from a 30 m mast, free space or two-ray, the highest ridge as a knife edge, ground clutter near the ground. Worked out when the view rests; masts as dots from zoom 11; labelled ESTIMATE with OpenCelliD's attribution; only while TOOLS is open, never in an exported picture. Cells come in fixed boxes under the API's 4 km2 limit, two requests each, kept 30 days on disk (`%APPDATA%\DraupneLink\opencellid`), at most 60 boxes a view and 900 requests a day; or all at once from the country file (242.csv.gz, IMPORT...). Tests: `CellCoverageTest`, `CoverageOverlayTest`.

## 1.0.43

- **Drone setup > Orientation draws a textured FPV drone model** (`DroneModelView`): the mesh in `model/fpv-drone.bin.gz` (47k vertices, 59k triangles, 'DRM1', made from the OBJ by `tools/model/export_fpv_drone.py`: the drone's axes, centred on the motors, scaled to the old quad's motor spacing) with two 1024 textures (ambient occlusion multiplied in), rasterised in software with a depth buffer at 2 x 2 sub-pixels in row bands on all cores, back faces culled; the front props tinted red; the shadow is the hull of every 8th vertex. About 8 ms a frame on 4 cores. Test: `DroneModelViewTest` (red props at the front, nose down moves them down).

## 1.0.42

- **A recording also writes `flight-...-osd.mkv`, the video with the OSD drawn in** (`OsdRecorder`, a `VideoPipeline.RecordingTap` on the record thread): its own one-thread software decoder (`VideoDecoder.oneThread`), the OSD painted with the figures the screen painted last (`VideoView.lastOsdData`, also with the HUD hidden), OpenH264 on the same thread at the lowest priority (`OsdVideoWriter`, MPEG-4 without it), at least 720 lines high; behind, pictures are left out of this file only. `recordOsd=false` turns it off.
- **Drone setup > Orientation** (`OrientationCheckDialog`, `DroneModelView`, `setup.Orientation`): a 3D quad following Betaflight's MSP_ATTITUDE, and the check flat - nose up - flat - right side down; the board's turn from the two tilts (0/90/180/270, or on its back while flat) and **Fix it** (`align_board_yaw`, or `align_board_roll` + 180, after a `diff all` backup; saved; checked again). Found from a flight log of 2026-11-06: an F722 with no board alignment flipped backwards on takeoff while Betaflight reported the nose going down.

## 1.0.41

- **Spectators no longer slow the pilot down:** while anyone watches, AUTO stays off the sharp tier (a chosen SHARP stays) - relaying 8 Mbit/s through the pilot PC's upload took its ping to the drone from ~50 to 300-700 ms.
- **The spectators' pause lasts until the pilot's link has recovered** (`RelayGate`: back to the usual for 3 s; a pause soon after another at least doubles, 3 to 30 s; the usual over a minute, not 10 s) - before, 3 s on a timer and paused again a second later. A spectator waits through a pause it was told about (15 s) instead of rejoining after 3 s.
- **The spectator's OSD mirrors the pilot PC's own warnings** (`PILOT_VIEW` 0x76, 4 a second): RC OFF and why, RADIO FROZEN, ARM SWITCH NOT READ, the live banners, RELAY, LINK STALL, LINK LOST and why, the stick boxes.
- **The camera's way up per phone** (`videoUpsideDown.<phone>`; a spectator follows the pilot's, PILOT_VIEW bit 6).
- **A spectator sees the pilot's phone and whether the pilot disconnected or lost its link** (PILOT_VIEW's phone, BYE with a reason; OSD PILOT DISCONNECTED / PILOT LINK LOST).
- **Earlier flights stay on the map** until taken off by hand (right-click on a line, or all at once), after a restart too (`map.keptFlights`).
- **Sleeping phones in the PHONE list** with the time to their next check; any number of them; another phone's check-in while flying is answered "sleep on".
- **Every drone on the network on the map** (phone app pc.32: the NAME_REPLY carries the drone's position, armed, flown and the pilot PC), and **right-click to connect or watch**.
- **Update phone app** on the LINK card: a remote update over the link (REMOTE_UPDATE 0x77; the phone downloads, checks the SHA-256, installs - disarmed only - and opens again by itself; progress in PHONE_UPDATE 0x40F7). Phone apps from pc.32 on.
- **A phone left in deep sleep after DISCONNECT keeps its WAKE:** the LINK card shows it asleep (last heard, battery, next check) with WAKE; its check-ins are answered "sleep on" by the name service until WAKE connects; remembered across restarts (`phone.asleep`).

## 1.0.40

- **Drone setup, one card:** **Betaflight settings** holds everything - the receiver, stick delay and battery lines, then **GPS**, **SWITCHES** and **FAILSAFE** as sub-points with a small light each, CONFIRM at the bottom; the motors stay their own point. The rows no longer repeat what the choices beside them say; each switch is one line - its choice, and beside it its state in colour (*Set.*, *Set on Confirm.*, or what is wrong) - in aligned columns; *What will change* and *Betaflight settings for DRAUPNE Link* are one row, **Betaflight settings**; a row with nothing to say has no empty line under its title. **Set everything, then CONFIRM:** one CONFIRM button (the cell count's own is gone) writes every setting on the page; until then the rows show empty rings (*Set on Confirm.* where Betaflight differs), green once everything is there and confirmed; a change afterwards brings CONFIRM back; red until CONFIRM (`Combined.override`). **One place per mode:** the managed modes (Angle, Horizon, GPS position hold, GPS rescue) are taken off every other switch when the flight mode switch is written (a drone came out with a mode on AUX2 and AUX5); left as it is, a mode on two switches is reported (`ModeSwitch.doubled`).
- **GPS, asked first:** a **GPS** row (No / Yes) above the switches. *Yes* slides out COMPASS (No / Yes), the GPS's UART (UART3 by default; the board's own list once read) and CALIBRATE COMPASS, and the GPS RESCUE switch under the switches; GPS modes and the GPS rescue failsafe appear in their lists. *No* shows and writes nothing GPS (a GPS choice from before becomes Angle / Leave as it is). Unanswered, the page takes Betaflight's answer on reading the drone. CONFIRM turns the GPS on, puts it on the UART chosen (off any other port), turns on `gps_auto_baud`, and sets `mag_hardware` AUTO / NONE for the compass; the row says *GPS found* (or what to check), whether a compass is seen and whether it is calibrated.
- **Flight modes:** SWITCH, LOW, MIDDLE and HIGH each with its name over its list (`stacked`), the line's state under them across the card (`line(..., below)`), a mode named once.
- **No nagging:** the "calibrate the radio again" warning is gone (the radio step still comes back quietly in the first-time setup); with a GPS, `gps_rescue_allow_arming_without_fix` is always set ON, so the "arms only with a GPS fix" note is gone too; a compass to calibrate and modes on a switch left as it is are plain text, not warnings.
- **Motor test:** REORDER MOTORS (each motor spins in turn, click where it is; Betaflight's motor output order set to match, saved, restarts - Betaflight 4.3 on), SPIN DIRECTION per motor (Normal / Reversed to the ESC with DShot commands, saved in the ESC), PROPS IN / OUT sets Betaflight's `yaw_motors_reversed` (asked first, saved, restarts); the picture follows Betaflight; the drone's picture about twice as large, PROPS and MOTOR ORDER left of it, SPIN DIRECTION right of it, the window opening larger (up to 1440 x 1000, within the screen).
- **Calibration's 3D radio:** the toggles' collar stays, the paddle hinges at the collar's top and throws from its CAD end to the mirror (front and shoulder rows alike); before, the whole lever tipped about a point inside the bushing and hardly moved.
- **Calibration:** what moved while a switch step caught its control is held in the next step until it is back at its rest (a released button, or a second input the radio reports with it, is never the next AUX).
- **Compass calibration:** CALIBRATE COMPASS opens a window like the motor test's: START, turn the drone round every axis for 30 s while two discs fill with dots where the compass has pointed, the offsets read back and kept at the end.

## 1.0.39

- **Safer when the link is weak (phone app 2.3.0-pc.31):** no takeover by another PC after a 4 s fade while the drone may be armed ("not sure" counts as armed on the phone); DISCONNECT and closing the window ask first while armed; Windows and the screen stay awake while connected; RADIO FROZEN sounds the alarm; STICKS x.x S LATE on the OSD (stick frames carry their send time; link log `stick_lag_max_ms`, `sticks_dropped_late`); optional cuts `rc.maxLagMs` and `radio.frozenStopS` (off by default). Phone: deep sleep re-checks armed, a port-restarting setting waits for disarm, the USB reconnect race and the main timer fixed, telemetry no longer falls behind. Link: no revived dead sessions, lost must-arrive packets asked for again, no reconnect echoes, no log floods, timeouts on a monotonic clock, the radio followed by device. Video: key frames paced to the weak tiers, no camera restarts over each other, a repeated config keeps the decoder, the backlog cut by age, AUTO leaves SHARP on a slow decoder, a failed decoder retried.
- **Calibration, more of it right:** the first step finds the four sticks by how they move (smoothly - a switch jumps) and locks them: only they can be throttle, yaw, pitch and roll, and no switch step can take one. However far a stick goes is its end - a radio set to send 1800 at most gets 2000 there (it never got past the first step before). In the final check the 3D radio's switches follow yours too. Its screen shows the DRAUPNE mark large in red with the flight state and ARMED; the TX16S name is off it.
- **Tidier main window:** the controller card's status lines (calibration, RC output, flight controller) sit beside the controller's name, so MINIMIZE folds the card to that one row; the phone list and CONNECT AS go away while connected; the minimap stays longer when room is short (the log gives way first); the VIDEO menu's lines are short, the details in their tooltips.
- **VIDEO quality, every setting a button:** AUTO, SHARP, NORMAL, ROBUST (bad coverage mode) and SURVIVAL - every picture the program can switch to by itself can now be chosen and held, and a choice takes at once, from whatever the picture is (ROBUST did nothing while the sharp picture was on).
- **The tour and the menus:** a menu's fold-out no longer plays behind the tour's notice and then jumps in front - it is on top all the way, like the menu itself.
- **Calibration shows what to do:** a big 3D RadioMaster TX16S (Mode 2), beside the sticks and values in the calibration, acts out every step - a CAD model of the real radio (TX16S MKII), lit like a studio photo with shadows, carbon-fibre backs, chrome toggles and the screen on; its sticks and toggles move - both sticks circling into the corners, all four centred with thumbs off, the stick asked for pushed and held (lit red, an arrow on the radio's face), the switch flipped (seen close up from its side: by the TX16S's own switches: ARM on SF, PREARM on SH (pressed and held: it is momentary), the flight modes on SA, GPS rescue (AUX4) on SC; each lever tilts over its bushing and snaps, never through it), and at the end your own sticks.
- **Calibration steps:** the first step goes on to centring by itself 2 s after all four sticks reached their limits. The centring step now wants **all four sticks centred, the throttle too**, and that centre is what the flight controller gets as the middle (1500) - for the throttle as well (a calibration from before, with its centre taken at throttle down, keeps the throttle straight from 1000 to 2000).
- **A topo map for the rest of the world:** **WORLD TOPO** (OpenTopoMap - OpenStreetMap's data with contours and hill shade, to zoom 17) in MAP TYPE and DOWNLOAD AREA. AUTO and TOPO now show it outside Norway, where Kartverket's map ends (AUTO showed OpenStreetMap there before).
- **RADIO HORIZON** (MAP TOOLS > TOOLS): set the mast's height and the aircraft's altitude (the mast above its ground, the aircraft above the ground under it, wherever it flies), the trees' height and the link's **frequency**, then click the map to put the mast there. Blue (so it reads over a GCS SEARCH heat map) shows where the aircraft can see the mast - a straight line over a 4/3 earth (normal radio refraction) that clears the terrain, a hill blocking outright - and **never beyond 30 km**. Forest costs signal by the metres of canopy the line goes through and the frequency (ITU-R's fitted in-leaf model, FITU-R - Norway's spruce is in leaf all year): past 20 dB the aircraft is out of sight - about 9 m of trees at 5.8 GHz, 37 m at 2.4 GHz, 200 m at 800 MHz. The forest is read off the map's own colours, close in: Kartverket's topo map at zoom 15 (2.4 m a pixel) over Norway, OpenStreetMap at zoom 13 elsewhere; the terrain from the open Terrarium height tiles. The horizon is drawn as soon as the terrain is in and again as the forest map comes in, nearest first; everything is kept, so the next time there is instant. Dashed rings: the 30 km limit and the smooth-earth horizon. One mast at a time: a new click moves it. It stays on the map when the tool ends, until the cross on its label takes it off.
- **GCS SEARCH** (MAP TOOLS > TOOLS): where to put the ground station. With the same four fields (mast, aircraft above the ground over the area, trees, frequency), drag a box round the area you want good reception in: a **heat map 30 km round it** shows, for every 100 m, how much of the area an antenna there would see - green all of it, red none - and rings the best place. A first look comes on the terrain alone, the real one when the forest map is in; a new box replaces it. It stays on the map when the tool ends, until the cross on its label takes it off. Both can be on the map at once.
- The tools' fields **slide out under the button** pressed (RADIO HORIZON or GCS SEARCH) and back in when the tool ends.
- **The minimap stays:** it sits on the LINK card under the figures, with POP OUT MAP, connected or not; the connection fields and figures no longer fold away for it.
- **STANAG 4609 stream and the camera's view on the map:** MENU > LINK > **STANAG 4609 stream** (off by default) sends the video, copied as it comes from the phone, in an MPEG transport stream with **MISB ST 0601** metadata beside it (time, the drone's position, height, heading, pitch and roll, the camera's angles, where the picture's centre and corners land on the ground; an **ST 0102** security set, UNCLASSIFIED) over UDP to one address - ATAK / WinTAK, QGIS, VLC and NATO ground stations play it. **STANAG 4609 settings**: the address (host:port; 239.x.x.x for multicast on the local network), mission id, country, and the camera's angle across and tilt up. MENU > VIDEO > **Camera view on the map** (on by default) draws the ground the camera sees on the map and the minimap, on the real terrain, out to 3 km, with a cross where the picture's centre lands. The stream runs on its own thread off the video's path and leaves frames out rather than queue them; it does use this PC's upload, so on a weak one it can delay the sticks.
- **MAP TOOLS in three groups:** the tab now slides out three flat tabs, **DRAW** (lines, rectangles, arrows, symbols, eraser, place at, pins), **TOOLS** (ruler, radio horizon, GCS search, go to; the measurement, the radio horizon and the search show on the map only while TOOLS is open) and **DATA** (export, import, download area, clear all); each slides its own list out beside it, full height, and folds back on the next press.
- **Symbols:** **MORTAR** and **ROCKET ARTILLERY** (the standard's mortar icon; field artillery with the multiple rocket launcher amplifier), and **RECOVERY / MAINTENANCE** in place of the utility drone - a land unit with the standard's maintenance icon (symbols saved as *Utility* show as it).
- **Tour:** a step for the three groups, one for the radio horizon and one for the GCS search, offered on updated PCs as a tour of just the new parts.

## 1.0.38

- **Deep sleep reports every check** (phone app pc.30): the sleeping phone says *still here* with its battery every 1.5 s for the whole 20 s check window, not three times in the first second - a Tailscale tunnel that slept with the phone drops the first packets after a wake, so whole checks went unheard on the PC.
- **Tour:** the tour's box stays in front of every animation in its window (a window fading in, the menu's rows flying); it no longer pops out from behind one when the animation ends.
- **Boot animation, no more jitter:** the animation's canvas fought the window's layout over its height on every frame (23 px, the root pane's insets), so the mark and the brackets jumped up and down for the whole flight. It now takes the size the layout gives it. (1.0.36's fixed phone-list width was a real fix for a smaller shift, but not this one.)

## 1.0.37

- **CLEAR ALL** on the map tools takes the pins and the measurement off too, not just the drawings and symbols.
- **Name map: RENAME** gives a phone another Mobile name (it must be online: the phone keeps its name itself and is told). The LINK card follows if it was the chosen phone.
- **PREARM switch:** Drone setup has a PREARM choice beside ARM (AUX3 by default, up = prearm; *Leave as it is* keeps Betaflight's). With one, Betaflight arms only while it is on, so a knocked ARM switch alone does nothing. Written to Betaflight's Modes like ARM (PREARM on any other switch is taken off), shown under *By hand*, and the radio calibration names AUX3 after it.
- **Phone app pc.29:** THIS PHONE on the status card shows the phone's DRAUPNE name alone ("MobileCharlie"); the device name and the addresses are gone from it. A tap still picks a name, a long press still copies the Tailscale address, and the line is yellow while Tailscale is off.

## 1.0.36

- **Symbols to the standard:** the unit symbols are now MIL-STD-2525D / APP-6D geometry - the standard's frames (land units a rectangle, diamond, square or quatrefoil; the drones the air frames, open at the bottom), its icons fitted to each frame (the infantry cross, the armour track, the artillery dot, the reconnaissance slash, the anti-tank chevron, the engineer E, the air-defence dome, the supply line, the medical cross across the frame, the headquarters line and staff), the drones as the standard's unmanned-aircraft icons (the fixed-wing UAV chevron, the vertical-take-off UAV) with the role letter above them (R reconnaissance, A attack, U utility), and the echelon amplifiers in the standard's place and size above the frame. A little larger than before (a friendly land frame is 45 x 30 px).
- The boot animation no longer jumps: the LINK card's phone list has a fixed width (its items change as the name service hears answers during the animation, and the card moved with them).

## 1.0.35

- **Names instead of addresses** (PC and phone app 2.3.0-pc.28): every phone is MobileAlpha, MobileBravo ..., every PC PCAlpha, PCBravo ... The PC asks every device on its Tailscale network who it is (NAME_QUERY on the link port), a PC names itself with the first free PC letter, and the pilot PC with the highest letter online names each phone that has none. The LINK card's address field is a PHONE list (as a spectator: the PILOT PC list); MENU > LINK > Name map shows every device seen with its address, this PC's name, the port and the key.
- **One way to the phone:** the connection method (Direct / Connect over server / Host the relay) is gone on both ends; it is Tailscale straight to the phone. The PC setup's connection and relay steps are gone; the firewall step recommends the UDP rule for name queries and spectators.
- **Phone app pc.28:** it listens for the PC by itself as soon as it is open (no CONNECT); the mode, address and start-up choice are gone, the port and key sit behind *Advanced*. THIS PHONE on the status card: its name (a tap picks one by hand), the phone's name and model, its Tailscale address in green (a long press copies it) and its other addresses.
- **Spectators** pick the pilot PC by name and watch that PC whatever phone it flies; the pilot PC still needs *Allow spectators*.
- **MAP TOOLS:** a tab at the map's left edge slides out the tools. RULER: click points, each leg's length and bearing, the total; the first point again closes the loop and gives the area; the terrain's elevation profile under the map; KEEP keeps it. LINE, RECTANGLE (outline or see-through fill) and ARROW in a chosen colour and width. NATO unit symbols (APP-6 style: infantry, mechanised infantry, armour, artillery, reconnaissance, anti-tank, engineer, air defence, headquarters, unit; friend / hostile / neutral / unknown; team to division). PINS, GO TO and DOWNLOAD AREA moved in. EXPORT a box as a PNG picture and a KML of everything drawn in it (pins and the flight track too); IMPORT a KML (DRAUPNE's, Google Earth's, ATAK's). Everything drawn is kept in Documents\DRAUPNE Link\graphics.kml.
- **Tour:** steps for the phone by name and for every map tool; a PC that had the tour before an update is offered a short tour of just the new parts, once.
- Map tools, the pilot's second round: drone symbols (ISR and attack, fixed-wing and multirotor), utility, supply and medical; a double-click on a point of any drawing (or a symbol) picks it up to move, a click drops it; an ERASER; PLACE AT puts a point or symbol at a typed position (MGRS, UTM or latitude, longitude). The tools panel no longer smears the map window (its scroll pane blitted pixels across the window: the map under the toolbar, the figures doubled, white patches).
- LINK card: the NAME MAP button is gone (MENU > LINK > Name map stays).
- Setup guide: *Install the mobile app* turns green by itself when the phone answers by name (the app is open on it); *Settings on the phone* is three boxes - the app's yellow lines, Always-on VPN, OTG - because the phone app now checks Data Saver itself and, once a PC is connected, Wi-Fi, Bluetooth and Battery Saver (phone app pc.28); *Connect DRAUPNE Flight* lost the port and key table, has the phone and its link on one row, FLIP next to the picture, and round trip and Tailscale path in one *Link quality* row.

## 1.0.34

- **Video quality** (PC and phone app 2.3.0-pc.27): the phone's own emergency brakes counted the time its packet pacing takes on purpose (a key frame is spread over up to three frame times, six with the two copies of bad coverage mode), so they fired at nearly every key frame at 2 Mbit/s and under, the bitrate spiralled to 150 kbit/s and the raise lock kept it there - on a perfect link (12 minutes at 45 ms ping). They now count the socket's own time, and the queue check waits out a key frame.
- **The sharp tier:** 1920x1080 at up to 8 Mbit/s (settings sharpWidth / sharpHeight / sharpBitrateLimitMbps). SHARP is always there; AUTO goes there after 20 s calm at the top bitrate and back to the normal picture at the first stall, a relay or 5 s under the top. The bitrate control has the 8 Mbit/s step.
- Bad coverage mode and the survival tier on a phone without a small 16:9 size (phone app pc.27): the smallest size with enough pixels (640x480) instead of 1280x720 again; the PC letterboxes it.
- A 720p picture on a 1080p screen is up-scaled bicubic: sharper edges, 0.4 ms more a frame.
- Video: the PC sends its video profile with the first config of every session, the normal one too. The phone keeps the profile of the session before, so a phone left in bad coverage mode kept sending key frames twice, parity and H.264 to a PC that took it for normal.
- Tour: a tip on the first step that every button can be pressed twice; EXIT TOUR is a small cross top right of the notice, and NEXT spans the box.
- Guide: the connect step no longer scrolls to the bottom by itself (every check line that changed scrolled the page to itself); the *Sleep before take-off* step is gone - the tour's SLEEP BUTTONS step covers it.

## 1.0.33

- **Weak reception** (PC and phone app 2.3.0-pc.26): the sticks and pings keep going through a link loss; a loss keeps the flight controller's state and the video config, and the stream is only asked for again when it really stopped; Connect every 150 ms after a loss, the Config the moment the phone answers; the phone takes the pilot's own re-Connect at once; another PC takes over after 10 s of silence (was 1 s), never while armed; 16-bit stick sequence.
- Transport: the resend timer that died after the first reconnect (upstream bug, both sides), no empty resend requests, the client takes the answer to its own re-Connect.
- Video: ROBUST's small size from the first config (the phone also waits for a camera still opening); the picture shows whenever its config is known; a backlog keeps its newest key frame; key frame requests held at most 0.4 s and at once after a gap; the config asked for (GetVideoConfig) when a restarted stream's config is missing or a key frame gives no picture; the decode thread never ends on an error; GPU decoding falls back to software for good after a failure; VIDEO FROZEN keeps the old picture's age across a restart; the survival tier (480x270 at 15 fps, 150-250 kbit/s) under bad coverage mode; H.264 in bad coverage mode and under; a parity packet per group of 8 packets; key frames paced at twice the bitrate; the phone's video watchdog; the frame counter wraps to 2.
- Bitrate: spikes judged against the last second's median (jitter is no spike), to the floor in one SET_BITRATE, raises not blocked by jitter.
- OSD: NO STICKS AT FC (the phone's stick write age over 300 ms, reported ten times a second), RELAY - MORE DELAY, STICKS STILL SENT under LINK LOST; AUTO holds bad coverage mode over a Tailscale relay.
- Phone app: telemetry in one datagram per 30 ms; the camera part of a config change on the video starter thread.

## 1.0.32

- Boot animation (PC and phone app 2.3.0-pc.25): the mark fades in as one solid shape, not as parts.
- Phone app pc.25: the crosshair shrinks away in the middle instead of flying to the status pill.

## 1.0.31

- Boot animation (PC and phone app 2.3.0-pc.24): black until the mark has morphed into the cards' outlines, then the UI fades in.
- Tour: the LINK card as two steps - CONNECT, then SLEEP BUTTONS with the sleep buttons on the card as a demo that answers presses.

## 1.0.30

- **Boot animation as a morph** (PC and phone app 2.3.0-pc.23): each piece of the mark's D is one shape that slides, turns and grows into a big card's outline (PC: LINK, LOG, the controller card; phone: the status and connection cards, CONNECT) and opens from the inside; the first frame is the mark, 4 s in all.
- Update check every 15 min on the PC and every 10 min on the phone (on resume).
- Phone app: deep sleep also stops the telemetry thread, the microphone and the service timer; no update check and a 5 s screen refresh while asleep.
- Guide: the connect step about connecting only; a new *Sleep before take-off* step with a working copy of STANDBY / DEEP SLEEP / WAKE.
- Tour: POP OUT VIDEO and FULL SCREEN steps back (explained, not pressed); DOWNLOAD AREA explained, not pressed; the pin manager lists the example pins; menu animations in front of the tour's notice; offered once on PCs set up before the tour existed.

## 1.0.29

- **The sleeping phone reports in** (phone app 2.3.0-pc.22): battery, charging and the next check at every sleep check; the PC answers sleep on or wake, so the window closes in seconds; the LINK card shows "Heard n ago, battery n %".
- Phone app: one HOLD TO WAKE button and a MODE line instead of STANDBY / DEEP SLEEP buttons; a quiet notification channel; the boot animation's D and crosshair become the CONNECT button, the LOCK SCREEN outline and the pill.
- LINK card: the sleep buttons slide away while armed. Guide: the sleep modes explained, Wi-Fi / Bluetooth / Battery Saver in one row, the permission rows name the yellow lines.
- Tour: PRESS IT fits, menus in front of the dimming, CURSOR and TERRAIN HEIGHT lit together, example pins.
- Video buttons as small glyphs at the right edge. No flicker at boot or when opening menus. The boot animation's D becomes the video buttons and the CALIBRATE / RC CHECK outlines.
- Phone app unchanged otherwise (pc.22).

## 1.0.28

- **STANDBY and DEEP SLEEP** (phone app 2.3.0-pc.21): the phone saves its battery before take-off; STANDBY is back in two seconds on WAKE, DEEP SLEEP locks the phone's screen and listens for the PC every 1, 2 or 5 minutes, so WAKE brings it back without anyone touching the phone. Refused and left while armed. The phone asks once for notifications, alarms and reminders and locking the screen; the guide lists them.
- Phone app: CONNECT warns when the phone is not on Tailscale.
- UPDATE: the helper logs every step, always starts the app again, and the next start reports the result; clear pop-ups when connected or not the installed copy; the app log is drained before exit.
- Tour: no pop-out / full-screen steps, NEXT locked until the control was pressed, the map live under the map steps, CURSOR and TERRAIN HEIGHT lit together, the main window to the front for the last step.
- Video buttons at the side of the picture; channel bars named after the switches' purposes.

## 1.0.27

- **Review of the whole program, every finding fixed; phone app 2.3.0-pc.19.** Link: sticks resume at once after a loss when the drone is still armed (no mid-air disarm during a GPS rescue), a restarted PC no longer becomes a spectator of itself, stick frames carry a sequence (pc.19) and the phone never sends them unmapped, the phone's stick path is two thread hops shorter with a bigger receive buffer and no spinning sender threads, loss after 2 s of silence, STICKS n MS on the HUD and `pc_to_fc_ms` in the link log.
- Video: a size switch no longer freezes the picture, a backlog flushes instead of rebuilding the decoder, key-frame request backoff, no bitrate oscillation in bad coverage, BT.709 colours, multi-threaded scaling, AGE n MS on the HUD, LINK colour relative to the link's baseline, an OSD CSV next to recordings.
- Setup: Drone setup lists "What will change" and writes only on WRITE; the cell count is forced only once confirmed (or measured); the radio step comes back after a switch change; CLI replies can't be cut at a "# " line; the board never stays in CLI mode; RC check uses the real arm switch; texts clarified; 1S packs in the motor test and a 20 s auto-stop; the tour rewritten (menu first, one step per button, EXIT TOUR, dead buttons while a step shows, MAP TYPE and coordinates); connect step diagnostics; the finale without the stray line.
- Phone app: USB permission denial noticed, camera reopened, one service start, wake lock, Android 13 notification, updater needs a checksum, byte-stream MSP parsing, low-latency encoder hints.
- Map and window: CONNECT keeps the last known position, FIND DRONE live with a file fallback, COPY MGRS and LOST DRONE, phone fix accuracy, streamed DOWNLOAD AREA with PAUSE / RESUME and refusals handled, GO TO, TRACKS, EXPORT GPX, keys, pins from the cursor cell, bounds and maximized state remembered, UPDATE outside the folded menu, POP OUT VIDEO / FULL SCREEN buttons over the video, atomic settings / track / pins writes.

## 1.0.26

- **The setup guide reworked from a pilot's walk-through:** 13 steps (the phone overview, "Measure the delay" and "Ready" pages are gone); FINISH plays SETUP COMPLETE and, the first time, offers a **tour** of the window and the map (MENU > SETUP > *Tour* any time). Betaflight step: "Plug a USB data cable", enter the cell count, a note on the AUX channels, RETRY, DISCONNECT / RECONNECT, other firmware named, "you can unplug the drone". Radio: the controller list always shown; a spare AUX is "reserve" in the calibration. Phone: "Install the mobile app" (neutral until the phone connects), "Settings on the phone", "Connect DRAUPNE Flight" with Always allow and a **camera check** with FLIP. No "USB OTG adapter": a USB data cable.
- **Motor test:** the risk line shakes when the box isn't ticked, a pop-up asks for a battery, a click on a motor in the picture runs it for 3 s.
- **Latency test:** "Press ESC to end the latency test".
- **Download area:** MAP TYPE, a warning instead of a size limit, HIDE with a progress line in the map window, SELECT WITH CURSOR.
- The window opens maximized.
- Phone app unchanged (2.3.0-pc.18).

## 1.0.25

- **Fix: Drone setup could not set the receiver on Betaflight 2025.12.** Its CLI has no name for RX_MSP, so Drone setup logged the change, saved, and left the board with no receiver. The receiver is now read and set over MSP (the feature mask, as Betaflight Configurator does), on 4.5 and 2025.12 alike; the CLI writes the rest.
- **Drone setup says what Betaflight refused** (logged and on the USB row) instead of passing it as success; the backup shows the receiver as it was.
- **Fix:** the setup guide's hint under the page title wraps.
- README: new pictures (the mark with the name, the map window, DOWNLOAD AREA).
- Phone app unchanged (2.3.0-pc.18).

## 1.0.24

- **The map saved for offline use: DOWNLOAD AREA** in the map window (or Alt + drag a box on the map, or right-click > *Download this view*), like Mission Planner's prefetch. One START fetches every tile of the area for all four pictures and every zoom level, each picture to its deepest; the count and rough size first, a bar and the figures while it runs, CANCEL keeps what is saved; one picture or fewer levels can be chosen. Saved tiles are skipped. OpenStreetMap one tile at a time, with a pause: keep its areas small.
- **The phone's GPS works indoors too (phone app 2.3.0-pc.18):** it asked Android's bare GPS only; now the GPS, the network and (Android 12+) the fused position, a fresh GPS fix winning. The phone's screen says what its GPS is doing (not allowed - tap to allow; no position yet; provider, accuracy and age).
- **The phone app updates itself (pc.18):** the *Update available* line downloads, checks (GitHub's SHA-256) and installs the new app from inside the app; nothing left in Downloads; Android asks once to allow installs; refused while connected. A one-time note opens Downloads for the old installers. The update to pc.18 itself still goes through the browser.
- **Link log:** `phone_lat`, `phone_lon`, `phone_acc_m`, `phone_age_s`.
- **Fix:** an uncaught exception in the boot animation when its first frame came late.

## 1.0.23

- **The icon is the DRAUPNE mark without its corner brackets, bigger:** the window, the taskbar, the installer and the phone app's launcher. The boot animation keeps the brackets.
- **No flash of the UI before the boot animation:** the window opens black.
- Phone app pc.17 (bundled): the launcher icon; otherwise as pc.16.

## 1.0.22

- **The map, in a window of its own** for a second screen (MENU > TOOLS or the POP OUT MAP button, F3): where the drone is and has been, over Kartverket's topo or printed map, satellite pictures or OpenStreetMap (AUTO picks Kartverket in Norway). FOLLOW, WHOLE FLIGHT, LAUNCH, FIND DRONE, PINS; + and - buttons, the wheel, drag; the figures under the map: the place under the mouse and the terrain height there in metres and feet, the drone's position, altitude, distance and bearing from the launch point. Coordinates in MGRS (default), UTM, decimal degrees or degrees-minutes-seconds.
- **Pins:** right-click on any map drops one (Alpha, Bravo ... Zulu, then Alpha2 ...), right-click a pin to rename or remove it; each shows distance and bearing from the drone. PINS lists them all with GO TO, RENAME, REMOVE, ADD AT DRONE, ADD AT LAUNCH. Kept between sessions and written into the flight's GPX file.
- **Every flight's path is saved as it comes** to Documents\DRAUPNE Link\Tracks\track-DATE.gpx (opens in any map program), with last-position.txt beside it: the drone's last known position with MGRS and map links. **FIND DRONE** shows it in the program, with the coordinates to copy.
- **Works without internet** for every place looked at before: map tiles are kept for good, and the levels under a place the map rests on are fetched too (not OpenStreetMap, whose policy forbids it).
- **Phone GPS** (MENU > TOOLS > Position from: DRONE / PHONE, with phone app 2.3.0-pc.16): the phone's own position on the map, which stays after a crash that killed the flight controller. The other source fills in when the chosen one has nothing.
- **In flight the LINK card changes:** the connection fields fold away and a minimap with POP OUT MAP unfolds; they come back when the map is popped out or on disconnect. The controller card starts folded (EXPAND shows it).
- **One MENU button** slides out SETUP, VIDEO (with the OSD), LINK and TOOLS; menus wipe in and out row by row with the scan bar.
- **The new DRAUPNE mark** everywhere - window, taskbar, installer, the phone app's icon - and a **boot animation**: the mark fades in, its brackets fly to the corners and its crosshair becomes the OSD's (3 s, a click skips it; the phone app has it too).
- Filled red buttons have black text; the popped-out map and video windows fade in; the menus' reveal no longer shows black bars on label or separator rows.
- Phone app pc.16 (bundled): the phone's GPS record, the new icon, the boot animation.

## 1.0.21

- **Video for bad coverage: SETTINGS > Video AUTO / SHARP / ROBUST.** ROBUST trades sharpness for a picture that holds on a weak link: 640x360, at most 1 Mbit/s, key frames sent twice, and after every frame a small parity packet from which the PC rebuilds any one lost packet, so a single lost packet no longer leaves a grey or green band. **AUTO** (the default) switches to it by itself after 5 s at the lowest bitrate or after 3 stalls within 30 s, and back to the sharp picture after 20 s of calm (longer if the link keeps falling back). SHARP never switches. Needs phone app 2.3.0-pc.15.
- **Picture size changes without a long blackout:** the phone keeps the camera open and only restarts the encoder (aim: under 0.5 s instead of 1.5-2.5 s). The PC checks that the phone really changed size and asks again if it didn't.
- **Faster reaction to a congested link:** a ping jump of 250 ms lowers the bitrate at once (then once a second while it lasts), no key frames are asked for while the link is congested (each is the biggest burst there is), and key frame requests come at most every 1.5 s.
- **Top bitrate 4 Mbit/s** (was 6; settings on 6 move to 4 once): 6 rarely got through 4G and its bursts cost delay.
- **Smoother spectator picture** (frames spaced out evenly, up to 250 ms of smoothing).
- **Phone app 2.3.0-pc.15:** a camera restart or video start that went wrong could leave a flag set so the phone ignored every later size change; that can no longer happen.
- **Link log:** the bitrate step, key frame requests, bad coverage mode and rebuilt frames (`recovered`).

## 1.0.20

- **Fix: phone app 2.3.0-pc.14 replaces pc.13. Update the phone app.** On some phones pc.13 started no video (it used intra refresh where the phone's encoder claimed to support it), and the hung video start also held up the sticks to the flight controller. pc.14 doesn't use intra refresh (key frames every 2 s and whenever the PC asks), and starts the video on a thread of its own, so camera or encoder trouble can never hold up the sticks again. pc.13's other improvements stay: paced packets, 30 fps, the PC deciding the bitrate.
- **A phone app that stops answering is now a LINK STALL** (with the warning sound): before, its telemetry kept the link looking fine while pings went unanswered and the sticks weren't getting through.

## 1.0.19

- **Better video over weak 4G** (with phone app 2.3.0-pc.13). Part of the picture often went grey or green: the phone sent each key frame as one burst and a weak uplink dropped its end. Now the phone paces its packets, sends key frames every 2 s instead of every 0.5 s plus one whenever the PC sees one arrive damaged, and uses intra refresh where its encoder supports it (no bursts at all). The camera now really runs at 30 fps (it often ran at 60), so each frame gets twice the data.
- **The bitrate no longer gets stuck at the bottom:** on a far-away link it sat at 0.25 Mbit/s. A single ping spike or a damaged key frame no longer lowers it, it rises again after 3 s of calm, and the PC keeps it at 1 Mbit/s or more, going by what actually arrives and by the phone's own report. On a really weak link the picture goes to 960x540 (cleaner than a starved 720p) and back to 720p when the link recovers.
- **A key frame that lost its last packet is shown** (damaged) instead of dropped, which smeared the next half second.
- **Connect as: Pilot or Spectator** (next to CONNECT). A spectator never asks to fly and waits when nobody flies. Connecting as pilot while another PC flies makes it unmistakable that you are not flying: a message, a red NOT FLYING - SPECTATOR pill and a blinking line on the video.
- **The camera always runs at a steady 30 fps;** the frame-rate setting is gone.
- **Setup: pick the controller** when several are plugged in (the radio step); after switching it asks for a calibration.
- **The link log records the video in detail:** camera fps, the phone's bitrate, decode and draw times, frames not drawn or arriving bunched, resolution, key frame requests, intra refresh.

## 1.0.18

- **Spectator picture fixed:** it was a coloured mess. Full-size video packets reach the spectator 9 bytes longer (the pilot PC's header in front) and were cut off on arrival, which broke nearly every frame. The spectator now sees exactly the pilot's picture. Update the spectator PC; the pilot's own picture was never affected.
- **Steady camera frame rate** (SETTINGS > Camera frame rate: **60** by default, 30, or auto). The camera used to run at 30-60 fps and, in dim light such as indoors, dropped to 30 and back up, which looked jumpy. Settings still on the old 30-60 move to a steady 60 once. Indoors a steady 60 is darker; choose 30 there for a brighter picture. A steady rate needs phone app 2.3.0-pc.12: with an older one the PC keeps 30-60 and says so.
- **The link log records the video** (video_rx_fps, video_mbps, video_damaged, video_lost), so a poor picture shows whether the bitrate dropped or frames broke.
- **Phone app 2.3.0-pc.12:** picks a frame-rate range the camera supports, nearest to the one asked for, so a steady 60 can't stop the picture on a camera without it.

## 1.0.17

- **Spectator mode** (off by default: SETTINGS > Allow spectators). A second PC that connects while you fly becomes a spectator: it shows your video and OSD a little behind you (600 ms), can record, and never sends sticks. It finds your PC through the phone and watches through it; the phone sends nothing extra, so your link is unchanged. It uses your PC's upload (about one video stream each, at most 2 spectators) and pauses by itself when your link to the drone slows down. A spectator never takes the drone over, also when you disconnect. Needs DRAUPNE Flight 2.3.0-pc.11 on the phone and direct phone mode (Tailscale).
- **Drone setup sets your switches and failsafe:** ARM (AUX1 by default), a 3-position flight mode switch (AUX2: low acro, middle angle, high GPS position hold) and the failsafe (leave as it is, drop, or GPS rescue), with a GPS rescue switch (AUX4) once the failsafe is GPS rescue. Each can be changed or left as it is in Betaflight. ARM on any other switch is taken off, so there is one arm switch. Betaflight's settings are backed up first, as before.
- **GPS checks:** a GPS mode or GPS rescue failsafe turns Betaflight's GPS on, and is only written once the GPS answers (no fix needed, so indoors is fine) and Betaflight is new enough (GPS rescue 4.4, GPS position hold 2025.12). No GPS found shows as a GPS hardware failure, with what to check. With GPS rescue in use, Betaflight arms only with a GPS fix: Drone setup says so.
- **Drone setup is tidier:** five rows (flight controller, Betaflight settings, switches, failsafe, motors); the manual way is behind **By hand**.
- **First-time setup covers everything:** it is made of PC, drone and phone setup's own steps (it was missing the connection method and the phone overview). The radio comes after the drone, and its calibration names each switch after what Drone setup put on it (AUX1 - ARM, AUX2 - flight modes, AUX4 - GPS rescue).
- **An app log for crashes and freezes:** %APPDATA%\DraupneLink\logs\app-DATE.log, with the window's log, errors, and every thread's stack when the window stops responding.
- **Phone app 2.3.0-pc.11:** tells a second PC which PC flies (spectator mode); otherwise unchanged.

## 1.0.16

- **New name: DRAUPNE Link** (formerly DroidDrone PC), after Draupne, Odin's ring. The phone app is now **DRAUPNE Flight**. The GitHub repository moved to MelonNO/draupne-link; old links redirect.
- **Your setup comes along:** on the first start the settings are copied from %APPDATA%\DroidDronePC to %APPDATA%\DraupneLink (the old folder is left as it was).
- **The installer replaces DroidDrone PC:** it upgrades the old install to Program Files\DRAUPNE Link, waits for either app to be closed, and swaps the old firewall rules for new ones. Desktop and Start menu shortcuts the setup guide made for the old app are removed once it is gone.
- **The phone app updates in place:** same package and signing key, so DRAUPNE Flight 2.3.0-pc.10 installs over DroidDrone Flight and keeps its settings.
- New recordings go to Videos\DRAUPNE Link and Betaflight backups to Documents\DRAUPNE Link; earlier ones stay where they were.
- **From DroidDrone PC 1.0.15 or older, UPDATE opens the release page** instead of installing by itself (it only knows the old file names): download DRAUPNE-Link-Setup-1.0.16.exe there and run it. From 1.0.16 on, UPDATE installs by itself again.

## 1.0.15

(1.0.14 was never released: its release check failed. Its tag stays unused; 1.0.15 is the same with that check fixed.)

### Fixes from a full code review

- **Motor test: a motor can no longer start again after Windows shuts down or logs off.** The shutdown hook now closes the test for good (STOP, then nothing more is sent); the motor thread always closes it, whatever ends it; the mouse wheel and dragging only move a slider in the active window; it uses the first port that answers as Betaflight, not just the first port.
- **The sticks never come from another device.** Once a radio is in use only that radio is read; if it disappears there are no sticks (no other joystick, gamepad or virtual device takes over), until it is back or you choose another one.
- **Sticks start only once the link is up and the throttle is low then,** and stop when the link goes down (throttle low again when it is back).
- **LINK STALL:** nothing from the phone for 0.3 s while connected shows **LINK STALL** and the red frame within a third of a second (before, nothing showed for 2-4 s); the ping shows "---" after 0.5 s; a stall is no longer reported as a lost flight controller.
- **Video keeps going after a bad frame** (a fresh decoder from the next key frame, instead of a frozen picture until reconnecting); reordered or doubled packets on 4G are no longer decoded out of turn or twice, and a late packet no longer lowers the bitrate; after a reconnect the new stream's first key frame is kept (no 0.5 s of garbage); the video figures go to 0 when the video stops; the decoder and the screen no longer wait for each other (three picture buffers).
- **Drone setup writes only to Betaflight:** each candidate port is asked over MSP first (no restart, nothing written) and only Betaflight gets the CLI; a CLI answer that was cut off is never read as settings; it leaves the port alone while the motor test is open.
- **Built-in relay: no viewers** (upstream let two in with its default key, with the video and GPS position).
- **Installer: always in Program Files** (no folder choice: it runs the app as administrator from there), and the "is DroidDrone PC running?" check is JScript instead of VBScript, which Windows is phasing out.
- **Updates: the installer is checked again right before it runs and held so it can't be swapped,** a half download is deleted, and nothing is installed while DroidDrone PC still runs; the Tailscale installer is held the same way.
- **Smaller:** the stick-send error no longer floods the log (and only real sends count); a socket is closed when connecting fails half way; a wrong rcRate setting shows a message instead of quitting at start; the phone-app download server stops when the setup step is left; OSD warnings placed low stay in the picture; long tool output no longer holds up the Tailscale check; settings are saved off the Swing thread; the link log shows the sticks only when they were sent.
- **Phone app 2.3.0-pc.9:** reconnects to the flight controller at once after a USB glitch (and looks for it every 150 ms for 5 s - before, 2-4 s, past Betaflight's 1.5 s landing); the touch lock can't open with one tap when Android's animations are off, nor with a second finger; sticks wait for Betaflight's channel map (never in the wrong order); failed USB writes no longer count as delivered sticks; the old USB connection is closed on reconnect; auto-connect at start checks the permissions; the service no longer crashes when Android restarts it.
- **Builds:** CI also builds the phone app; a release checks the phone app it ships matches the phone app source; GitHub Actions pinned to exact versions; the signing-key password no longer goes on a command line.

### New

- **Warning sounds:** a short beep when LINK LOST, LINK STALL, FAILSAFE or NO FLIGHT CONTROLLER starts, again every 3 s while it lasts (SETTINGS > Warning sounds, on by default).
- **RADIO FROZEN? and VIDEO FROZEN on the OSD:** armed with not one bit of the radio's input changing for 5 s (a hung USB radio still reports itself), and no new picture for 0.7 s while the stream runs.
- **The arm switch must be off before the sticks start,** as well as the throttle down: Drone setup reads where Betaflight's ARM mode is (channel and range) and shows it; until then only the throttle rule applies. The OSD says which: RC OFF - THROTTLE DOWN / ARM SWITCH OFF.
- **Drone setup backs up Betaflight first:** before it writes anything, Betaflight's whole "diff all" goes to Documents\DroidDrone PC\Betaflight backups (paste it into the CLI to go back); what changed is in the log. A **Port** list appears when more than one flight controller port is found.
- **Recording:** SETTINGS > Record video records every connection's video to Videos\DroidDrone PC, as it comes from the phone (an MKV file, no re-encoding: no extra delay or CPU, and a file cut short still plays); a red REC on the OSD while it records.
- **Hardware video decoding** (SETTINGS, off by default): decodes on the GPU (D3D11VA), software if it can't. Measured here at 720p H.264 it is slower (about 3.0 ms a frame against 1.3 ms: copying the picture back from the GPU costs more than decoding saves), so it is only worth trying with bigger pictures or a slow CPU.

### Changes

- **Phone setup, "Keep the phone connected": one box per setting,** like the checks on the other steps, with an empty ring (something to do on the phone, not a warning). New box: **OTG switched on (some phones)** - on phones with an OTG switch (e.g. OPPO, realme, OnePlus, vivo) the phone doesn't see the flight controller while it is off, and many of them switch it off again after about 10 minutes without a USB device.
- **The motor test is a normal window:** Windows' own minimise, maximise (full screen) and close, like the OSD editor. Opening it again brings the open one forward; it also works when opened from a setup guide (Drone setup > CHECK NOW).
- **Bigger text in the setup guides:** the hint under each title, the paragraphs and the check boxes are about two points larger.
- **Phone settings are a checklist:** click a box in "Keep the phone connected" when it's set on the phone; it gets a green check (click again to undo) and is remembered for the next time.
- **Arrows in setup texts show:** "Settings > Apps > ..." paths showed an empty box where the arrow should be; they use ">" now.
- **Phone app 2.3.0-pc.8: no emoji.** The LOCK SCREEN button is plain text (still the only yellow button).
- **Windows and setup pages fade in:** a window that opens (OSD editor, motor test, calibration, RC check, setup guides) fades in, and so does a setup guide's page on **Next** and **Back**. The banded wipe from 1.0.13 is gone; the SETUP menu keeps its own animation.
- **Drone setup: Next without a choice about the motors.** The motors and props are assumed checked, so the Betaflight step goes on as soon as Betaflight is set up; **ALREADY CHECKED** and **CHECK NOW** (the motor test) are still there.

## 1.0.13

- **Phone app 2.3.0-pc.7: the connection card shows in portrait.** In pc.6 the status card took the whole height when the phone was upright.
- **Phone app 2.3.0-pc.7: Direct connection by default.** A fresh install starts on *Direct connection* (the phone listens, DroidDrone PC connects to it over Tailscale) instead of the relay server; a mode you already chose stays.
- **Latency test moved to SETTINGS:** *Latency test* is a switch in the SETTINGS menu (F3 no longer does it; Esc still stops it). The setup guide has a new step, **Measure the delay**: choose **TEST AFTER SETUP** and DroidDrone PC connects and starts the test when you press Finish.
- **SETTINGS menu, clearer:** *Camera upside down* and *Latency test* are switches that slide and leave the menu open; *OSD layout* and *Motor test* sit under a line with a chevron, because they open a window of their own.
- **Windows open like the SETUP menu:** the OSD editor, motor test, calibration, RC check and the setup guides are uncovered band by band behind the red scan bar; **Next** in a setup guide brings the next page in the same way.
- **The OSD editor is a normal window:** Windows' own minimise, maximise and close; it opens maximised. The WINDOW / FULL SCREEN button and F11 there are gone.
- **Motor test: PROPS IN / PROPS OUT animates:** the highlight slides over and the arrows sweep round the new way.
- **The CALIBRATE notice goes at a click anywhere else,** before its time runs out.
- **The last picture fits when the video stops:** after the video was lost, EXPAND / MINIMIZE (or resizing the window) left the frozen last frame at its old size. It now scales to fit; live video is still drawn 1:1, with no extra step.

## 1.0.12

- **Phone app 2.3.0-pc.6: touch lock for flying.** A yellow **LOCK SCREEN** button at the top covers the whole screen so stray touches do nothing; only a dashed yellow **HOLD 2 S TO UNLOCK** box in the corner still works (hold it for two seconds). While locked the screen dims (the phone is the drone's radio), back does nothing, the system bars hide and the screen doesn't rotate; the link and the flight controller keep running. Android doesn't let an app block the home gesture or the notification shade.
- **Phone app 2.3.0-pc.6: portrait as well as landscape.** It turns with the phone (following Android's auto-rotate); in portrait the two cards are stacked.
- **SETTINGS is a menu, like SETUP:** a click opens a list under the button - *Camera upside down* (on / off), *OSD layout*, *Motor test* - each with what it does, and the versions at the bottom. The separate SETTINGS window is gone.
- **Motors and props in the drone setup:** the Betaflight step (SETUP > Drone setup, and the DRONE step of the first-time setup) asks whether the motors and props are checked: **ALREADY CHECKED**, or **CHECK NOW**, which opens the motor test there and then. One of the two is needed to go on; the answer is remembered.
- **Motor test: props in or props out.** A **PROPS IN | PROPS OUT** switch above the picture turns the arrows round; **props out** is the default and your choice is remembered. With the flight controller connected, the Motor layout line warns when Betaflight's *Motor direction is reversed* doesn't match the choice (yaw would turn the wrong way) and says where to change it.
- **The OSD editor opens full screen** for the biggest preview; **WINDOW** / **FULL SCREEN** at its top right (or F11) switches.
- **MINIMIZE / EXPAND fold the controller panel smoothly** (a quarter of a second) instead of jumping; the video grows or shrinks with it. A click while it folds turns it round.
- **CALIBRATE without a controller** now says so in the same kind of notice as a locked CALIBRATE: under the button, with a red bar running down until it goes, never over the video. Before, a Windows message box came up.
- **Updates are found at once:** DroidDrone PC asks GitHub the moment it opens (it waited 8 seconds), on its own thread so the phone check never waits for it.

## 1.0.11

- **Your own OSD layout:** SETTINGS > OSD > **EDIT OSD** opens an OSD editor laid out like Betaflight Configurator's OSD tab: every element with a switch on the left, a preview with sample flight data in the middle where you drag elements into place, and the chosen element's details on the right. Changes show on the live OSD at once and are saved. An element lines up by the third of the picture it's in (left, centre, right), so it grows away from the edge. The standard layout stays the default (**RESET ALL** goes back to it). The warnings and the red link-lost frame can be moved but never turned off.
- **New OSD elements, off until you turn them on:** throttle %, current draw, mAh used, battery voltage, power (W), distance flown, RSSI, CPU load, vertical speed, pitch and roll angles, GPS coordinates, on time, clock, and left and right stick overlays.
- **Motor test:** SETTINGS > **MOTOR TEST**, like Betaflight Configurator's Motors tab, over this PC's USB with the drone on the bench. It shows the drone from above with each motor's number and the way it must turn (Quad X, props in or out as Betaflight is set), and a slider per motor plus a master slider, behind Betaflight's "I understand the risks, the propellers are removed" box. Spin one motor and check it sits where its number says and turns the way its arrow points; then put the props on so their leading edges follow the arrows. The motors stop when you untick the box, press SPACE or STOP ALL, leave the window or close it, and when the USB link fails; they go to 30 % at most. It won't run while connected to the drone, on an armed drone or with 3D mode on.
- **Core temperature on the OSD:** **CORE 45°C** next to the timer, the flight controller's processor temperature as Betaflight reports it (Betaflight 4.4 and newer). Yellow from 60 °C, red from 70 °C (Betaflight's default alarm).
- **Every Betaflight flight mode on the OSD:** besides ACRO, ANGLE, HORIZON, GPS RESCUE and FAILSAFE it now names **AIR** (acro with the air mode switch, as Betaflight's OSD does), **ACRO TRAINER**, **HEADFREE**, **ALT HOLD** and **POS HOLD** (both Betaflight 4.5 and 2025.12 names), **CHIRP**, **PASSTHRU** and **TURTLE** (crash flip switch on, in yellow), in Betaflight's order of priority. Air mode switched on permanently (feature, not a switch) can't be seen over MSP and still shows as ACRO.
- **The FLY timer runs:** it stood at 00:00 because the phone app times the flight from an arm flag it never learns (it only works out ARM when asked for the mode IDs, which DroidDrone PC didn't ask for). DroidDrone PC now times it itself from Betaflight's own ARM flag: it counts while armed and adds up across arms, like Betaflight's total arm time. Works with every phone app version.
- **The phone sees the drone armed:** DroidDrone PC now asks the phone app for the mode IDs once per connection, as the original Control app does. Without them the phone never knew the drone was armed, so its own fly time and travelled distance stayed at 0 and the camera switch never started a recording on the phone. No phone app update needed.
- **Why CALIBRATE is locked:** clicking it while connected now also shows a small notice under it saying why (disconnect first). A red bar along its bottom runs down to show when it goes; then it fades and folds away (a click closes it sooner). It never covers the video: where there's no room for it beside the video, it isn't shown, and the log line still says why.

## 1.0.10

- **Calibration takes a true centre:** the "Center the sticks" step now waits until the sticks have rested for half a second and takes the average, instead of the one reading at the moment Next is pressed. The last page warns when roll, pitch or yaw would still sit off 1500 at rest (e.g. **PITCH 17 us**): a constant stick input that makes the drone lean or twitch when landed and armed. If you calibrated before, calibrate again with your hands off the sticks at the centre step.
- **The installer checks that DroidDrone PC isn't open:** when it starts, and again right before it removes the old version, it looks for a running DroidDrone PC. If it finds one it says so: close it (disconnect from the drone first), then **Retry**, or **Cancel** with nothing changed. It never closes the app for you, so a live drone link is never cut. Before, it went ahead and could leave a half-replaced install behind.
- **Battery cell count in the drone setup:** the Betaflight page (SETUP > Drone setup, and the DRONE step of the first-time setup) has a **BATTERY CELLS** field, already set to 6. Confirm it or change it; it's written to Betaflight as `force_battery_cell_count` together with the other settings, so Betaflight never counts a 6S as 1S again. Changing it later writes it again by itself.

## 1.0.9

- **UPDATE now updates:** pressing UPDATE downloads the new version, checks it against the checksum GitHub lists for it, closes DroidDrone PC, installs it (Windows asks for permission once; no setup wizard) and starts it again. Settings stay. It won't run while connected to the drone. The portable zip version still opens the release page.
- **CALIBRATE shows when it's locked:** while connected it's greyed with a padlock and slowly drifting hazard stripes; clicking it shakes it red and the log says why (disconnect first).
- No controller: the stick boxes no longer send out rings; only the small blinking circle in the middle stays.
- **Right cell count when Betaflight gets it wrong:** with the flight controller already on the phone's USB power, Betaflight can count the cells while the battery voltage is still rising, settle on 1S or 2S for a 6S, and keep that until the battery is unplugged. When its count is impossible (over 4.5 V per cell), the OSD now waits for the voltage to settle, counts the cells itself and shows e.g. **BATT 6S (BF SAYS 1S)**, so you know Betaflight's own low-voltage warnings are off too.
- **Phone battery on the OSD:** a battery icon with the charge, green / yellow / red, and a big **PHONE BATTERY LOW** at 15 % or less while not charging (the phone is the radio). The Windows app also shows it under PHONE BATTERY.
- **Old phone app warning:** the Windows app shows the phone app's version (PHONE APP) and warns, with how to update, when the phone runs an older one than this PC brings.
- **One crosshair:** the OSD and the no-video screen use the same Betaflight-style crosshair (the red "W" is gone).
- **Phone app 2.3.0-pc.5:** tells DroidDrone PC its version once a second.

## 1.0.8

- **FPV-style "no signal" screen:** with no video, the video area shows dim analog static with a Betaflight-style crosshair and a gently rocking horizon, and the status under it. The scan line, grid and edge placeholders are gone.
- **Minimize the controller panel:** MINIMIZE (top right of the controller panel) hides the sticks and channel bars, and the video grows into the space. EXPAND brings them back; the choice is remembered.
- **No controller is obvious:** the stick boxes pulse as if searching and say NO CONTROLLER, the controller field's frame and the status line blink red.
- The **SETUP** menu's items come in one by one, like the dropdowns.
- Buttons no longer flash a shine across on hover (the corner brackets and the click flash stay).

## 1.0.7

- **Animated, military-style controls:** buttons get closing corner brackets and a scan line on hover and a lock-on flash on click; the dropdowns (connection, controller) reveal their options one by one behind a red scan bar.
- **Video skeleton:** while there's no video, the video area shows a skeleton of the view - grid, corner brackets, pulsing reticle, shimmering placeholders where the OSD goes, a slow scan line - with the status.
- **One controller, no dropdown:** with a single controller (or none) its name shows in a plain field; the dropdown appears only when there are several to choose from.
- Clicking **SETUP** again now closes its menu (it used to open it again).

## 1.0.6

- **Phone app 2.3.0-pc.4: fixes the arming blocks ("ARMING BLOCKED: RXLOSS") and the micro failsafes.** The phone's measurements showed the cause: sticks reached the phone every ~12 ms, but some USB writes to the flight controller took ~100 ms. The app read from the flight controller with a 100 ms timeout, and on many phones such a read holds every write on the same USB connection until data comes or the timeout runs out. It now reads asynchronously, which never blocks a write.
- **COPY** at the top right of the log copies the whole log to the clipboard, e.g. to send it to someone.

## 1.0.5

- **Both apps tell you when there's an update.** DroidDrone PC asks GitHub at start and every 6 hours; when a newer release is out, an **UPDATE** button appears at the top (it opens the release page) and the log says so. The phone app checks when it starts; if the latest release carries a newer phone app, the status card says so and a tap downloads it (Android installs it as an update). One anonymous request each, no account; nothing is downloaded or installed by itself.
- **Phone app 2.3.0-pc.3: sticks never wait behind other requests on the USB link.** The phone talks to the flight controller over one USB connection; before, a stick packet could wait up to 0.1 s behind a slow telemetry request, which is enough for Betaflight to flag RXLOSS and block arming. Now one writer owns the connection, the newest sticks always go first (older ones are dropped, never queued), a stuck stick write gives up after 50 ms and sends the newest instead, and telemetry requests pause while the link is slow.
- **The phone reports what it sees**, every 0.1 s, into the link log: the longest gap between stick packets arriving from the PC, and between stick writes reaching the flight controller, the slowest USB write, timeouts and skipped requests. So a failsafe can be traced to the network or to the phone's USB link.
- **OSD battery: no more guessed cell count.** The cell voltage uses Betaflight's own cell count; until Betaflight reports it (e.g. on USB power) the OSD shows the pack voltage instead of guessing 1S or 2S from a low or still-rising voltage.

## 1.0.4

- **The link log catches short stalls:** the PC pings the phone 20 times a second (was 4), and the log records the highest round trip and the age of the last reply every 0.1 s, so a 0.1-0.3 s stall (where Betaflight flags RXLOSS) shows up. It also records Tailscale's path to the phone (direct, or through a relay server, which adds delay and stalls), and the log notes when the path changes.
- **See which version you have:** DroidDrone PC shows its version in the window title and in SETTINGS → About, with the phone app version it installs. The phone app shows its own version at the bottom right of its screen.

## 1.0.3

- **Flight mode on the OSD:** ACRO, ANGLE, HORIZON, GPS RESCUE, FAILSAFE ... under ARMED / DISARMED, named like Betaflight's own OSD. ACRO shows when no self-levelling mode is on (it has no switch of its own). Other active switches, like AIR MODE, follow in smaller text.
- **Warning when the phone is off Tailscale.** Every few seconds the PC asks its Tailscale whether the phone is online. If not, the link card shows **PHONE ON TAILSCALE: OFFLINE** with what to do, the log notes it, and the OSD says why under LINK LOST. Also caught: the phone address not on your Tailscale network, and Tailscale on the PC off or missing.
- **A radio USB blip no longer cuts the sticks.** If the radio drops out and comes back within 2 s, the sticks resume at once. Before, they only resumed once the throttle was seen at the bottom, which in the air would have meant a failsafe. After a longer gap (Betaflight has disarmed by then) the throttle-low rule applies again.
- **The link log records the sticks as sent:** throttle and every channel, 10 times a second, so you can tell a radio-side input from something the drone does on its own.

## 1.0.1

- **Phone app 2.3.0-pc.2 replaces any earlier DroidDrone Flight by itself.** It installs next to the original (or 2.3.0-pc.1) and, when it opens, asks Android to remove the earlier app: one tap on OK. No more uninstalling first. After this one-time move, set the phone's Battery and Mobile data settings for DroidDrone Flight once more (Phone setup lists them).

## 1.0.0

- **Version 1.0.** Everything from the 0.5 releases below: installer that sets up the PC, short setup wizard, live connection check, OSD with cell voltage and lost-link / lost-flight-controller warnings, link log, pop-out video, upside-down camera setting.
- **The phone app is part of every release:** `DroidDrone-Flight-<version>.apk`, the exact build this version installs on the phone.

## 0.5.7

- **Link log** for finding the cause of failsafes: each connection writes a timeline (10 rows a second) to `%APPDATA%\DroidDronePC\logs`: ping, stick frames and the longest gap between them, telemetry and its longest gap, the flight controller's last data, RXLOSS / FAILSAFE, radio present, and every log message.
- **The OSD no longer calls a short RC pause a FAILSAFE.** The big blinking FAILSAFE now means Betaflight's real failsafe (drop / land / disarm). A pause of 0.1-0.3 s, where Betaflight flags RXLOSS but keeps your last sticks, shows as a steady yellow **RC SIGNAL GAP** while armed.

## 0.5.6

- **SETTINGS** button (next to SETUP): *Camera mounted upside down* turns the picture there. F4 no longer does it.
- **Phone app: DroidDrone Flight 2.3.0-pc.1**, this project's build of Flight 2.3.0. It knows every flight controller (any native-USB board - STM32, AT32, APM32, GD32, RP2040 - and every USB-serial bridge chip it can drive), so Android can remember the USB permission and the phone reconnects without asking, and it has the PC app's black and red look. Same protocol. Uninstall the original Flight app first: this build is signed differently.
- **Drone setup finds more flight controllers** over USB: APM32, GD32, RP2040, Pixhawk/Cube/Holybro boards and USB-serial bridge chips, besides STM32 and AT32.
- **POP OUT VIDEO** puts the picture with its OSD in a separate, resizable window (e.g. on a second screen). Close it or press VIDEO BACK to put it back.
- The NO FLIGHT CONTROLLER warning now also points to SETUP → Drone setup: a flight controller left at `serial_update_rate_hz = 100` can't keep up and drops out.

## 0.5.5

- **Cell voltage on the OSD:** the battery shows the voltage per cell in big, with the cell count (e.g. *BATT 4S*). Pack voltage, mAh and amps are on the line below.
- **Lost flight controller is obvious:** if the phone loses its USB link to the flight controller while the link to the phone is up, the OSD gets a red frame, a blinking **NO FLIGHT CONTROLLER** and what it means, and the frozen battery and GPS values turn grey. The main window's FC line says the same. Losing the link to the phone also shows the red frame.
- **F4 turns the picture upside down** for a camera mounted that way. The OSD stays the right way up, the setting is remembered, and it adds no delay.
- The setup now tells you to tick **Always open DroidDrone Flight** in Android's USB dialog, so the phone reconnects to the flight controller without asking.

## 0.5.4

- **Simpler setup: less to read, less to click.** Pages that are done are skipped, tick boxes are gone, and the texts are short.
- **Drone setup does just two things**, over USB with nothing to click: receiver mode **MSP** and the stick delay setting (`serial_update_rate_hz`). It no longer touches the arm switch or calibrates the accelerometer; set those in Betaflight Configurator as usual. If Betaflight Configurator holds the USB port, the page now waits and tries again by itself.
- The separate **BETAFLIGHT guide is gone**. Without a USB cable, the drone page shows the same two settings for Configurator.
- **SETUP → First-time setup** runs the whole setup again.
- The first-time setup no longer asks how the PC reaches the phone (Tailscale; change it in SETUP → PC setup).
- **Connect the Flight app** is one step: the values to type in, with the live link status right below. It turns green the moment you tap CONNECT on the phone, then shows the flight controller and the round-trip time. It replaces the separate link test.

## 0.5.3

- **The installer sets up the PC.** Tailscale, the firewall rules and the power plan are done by the installer, with one Windows approval and nothing to click. The setup wizard is left with what needs you, and skips every step that is already done.
- **Works on a brand-new PC.** Tailscale now comes straight from Tailscale (signed package, silent install) instead of through winget, which is missing on many PCs. This fixes *Cannot run program "winget"*.
- **Fewer clicks in the wizard.** Over USB it now also sets Betaflight's stick delay setting (`serial_update_rate_hz`), and it applies the needed Betaflight changes as soon as it has read them. Tailscale's sign-in page, the firewall rules, the power plan and the shortcuts are handled the moment their step opens.
- **SETUP is a menu:** PC setup, Phone setup or Drone setup, each on its own. The full setup wizard opens by itself only on the very first start.
- The installer's last page has a **Launch DroidDrone PC** box, ticked by default.
- Updating from 0.5.2 or older: the old per-user copy is removed automatically the first time the new version starts. Your settings stay.
- Betaflight **4.4 or newer is recommended, not required**. 4.2 and 4.3 also fly; the phone shows a yellow warning. The README has a version table.

## 0.5.2

- **Lower stick latency.** Sticks are now sent 250 times a second instead of 100, and Windows can no longer slow the app down when its window is in the background. Measured on a test PC: gaps between stick updates went from 10 ms (up to 16.5 ms) to 4 ms (up to 6 ms). Existing settings are updated automatically.
- **Latency test (F3):** point the drone's camera at the code on the screen to measure the real camera-to-screen delay.
- **True full screen (F11)** for the video, which can save up to one screen refresh.
- **Video bitrate** drops as soon as the ping climbs above its usual level, before frames are lost.
- **OSD** updates 20 times a second instead of 10.
- **Betaflight guide:** the new **BETAFLIGHT** button walks through Betaflight Configurator tab by tab: receiver, arm switch, the latency setting, accelerometer, failsafe.
- **Important:** run `set serial_update_rate_hz = 2000` then `save` in the Betaflight CLI (the guide shows how). Otherwise Betaflight can't keep up with the stick updates.
- Red accent colour.
