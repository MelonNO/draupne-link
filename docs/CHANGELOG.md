# Changelog

Every DRAUPNE Link release (DroidDrone PC up to 1.0.15), newest first. The release pages on GitHub show only that version's changes.

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
