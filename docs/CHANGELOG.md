# Changelog

Every DroidDrone PC release, newest first. The release pages on GitHub show only that version's changes.

## 1.0.10

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
