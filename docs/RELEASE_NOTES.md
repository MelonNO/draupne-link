## Install

**`DroidDrone-PC-Setup-*.exe`** is the installer. Run it and click through it (Next → Install → Finish), approving the Windows prompt once. It installs DroidDrone PC for all users and sets up this PC by itself: Tailscale, firewall rules and the High performance power plan. DroidDrone PC starts when you press Finish; follow the setup wizard that opens on the first start.

New in 0.5.6:
- **SETTINGS** button (next to SETUP): *Camera mounted upside down* turns the picture there. F4 no longer does it.
- **POP OUT VIDEO** puts the picture with its OSD in a separate, resizable window (e.g. on a second screen). Close it or press VIDEO BACK to put it back.
- The NO FLIGHT CONTROLLER warning now also points to SETUP → Drone setup: a flight controller left at `serial_update_rate_hz = 100` can't keep up and drops out.

New in 0.5.5:
- **Cell voltage on the OSD:** the battery shows the voltage per cell in big, with the cell count (e.g. *BATT 4S*). Pack voltage, mAh and amps are on the line below.
- **Lost flight controller is obvious:** if the phone loses its USB link to the flight controller while the link to the phone is up, the OSD gets a red frame, a blinking **NO FLIGHT CONTROLLER** and what it means, and the frozen battery and GPS values turn grey. The main window's FC line says the same. Losing the link to the phone also shows the red frame.
- **F4 turns the picture upside down** for a camera mounted that way. The OSD stays the right way up, the setting is remembered, and it adds no delay.
- The setup now tells you to tick **Always open DroidDrone Flight** in Android's USB dialog, so the phone reconnects to the flight controller without asking.

New in 0.5.4:
- **Simpler setup: less to read, less to click.** Pages that are done are skipped, tick boxes are gone, and the texts are short.
- **Drone setup does just two things**, over USB with nothing to click: receiver mode **MSP** and the stick delay setting (`serial_update_rate_hz`). It no longer touches the arm switch or calibrates the accelerometer; set those in Betaflight Configurator as usual. If Betaflight Configurator holds the USB port, the page now waits and tries again by itself.
- The separate **BETAFLIGHT guide is gone**. Without a USB cable, the drone page shows the same two settings for Configurator.
- **SETUP → First-time setup** runs the whole setup again.
- The first-time setup no longer asks how the PC reaches the phone (Tailscale; change it in SETUP → PC setup).
- **Connect the Flight app** is one step: the values to type in, with the live link status right below. It turns green the moment you tap CONNECT on the phone, then shows the flight controller and the round-trip time. It replaces the separate link test.

New in 0.5.3:
- **The installer sets up the PC.** Tailscale, the firewall rules and the power plan are done by the installer, with one Windows approval and nothing to click. The setup wizard is left with what needs you, and skips every step that is already done.
- **Works on a brand-new PC.** Tailscale now comes straight from Tailscale (signed package, silent install) instead of through winget, which is missing on many PCs. This fixes *Cannot run program "winget"*.
- **Fewer clicks in the wizard.** Over USB it now also sets Betaflight's stick delay setting (`serial_update_rate_hz`), and it applies the needed Betaflight changes as soon as it has read them. Tailscale's sign-in page, the firewall rules, the power plan and the shortcuts are handled the moment their step opens.
- **SETUP is a menu:** PC setup, Phone setup or Drone setup, each on its own. The full setup wizard opens by itself only on the very first start.
- The installer's last page has a **Launch DroidDrone PC** box, ticked by default.
- Updating from 0.5.2 or older: the old per-user copy is removed automatically the first time the new version starts. Your settings stay.
- Betaflight **4.4 or newer is recommended, not required**. 4.2 and 4.3 also fly; the phone shows a yellow warning. The README has a version table.

New in 0.5.2:
- **Lower stick latency.** Sticks are now sent 250 times a second instead of 100, and Windows can no longer slow the app down when its window is in the background. Measured on a test PC: gaps between stick updates went from 10 ms (up to 16.5 ms) to 4 ms (up to 6 ms). Existing settings are updated automatically.
- **Latency test (F3):** point the drone's camera at the code on the screen to measure the real camera-to-screen delay.
- **True full screen (F11)** for the video, which can save up to one screen refresh.
- **Video bitrate** drops as soon as the ping climbs above its usual level, before frames are lost.
- **OSD** updates 20 times a second instead of 10.
- **Betaflight guide:** the new **BETAFLIGHT** button walks through Betaflight Configurator tab by tab: receiver, arm switch, the latency setting, accelerometer, failsafe.
- **Important:** run `set serial_update_rate_hz = 2000` then `save` in the Betaflight CLI (the guide shows how). Otherwise Betaflight can't keep up with the stick updates.
- Red accent colour.

Windows may say *"Windows protected your PC"* because the installer isn't code-signed. Click **More info → Run anyway**.

**`DroidDrone-PC-*-windows.zip`** is the portable version: unzip it and run `DroidDrone PC.exe`.

The phone needs **DroidDrone Flight 2.3.0**. The setup wizard installs it for you by QR code.

## What's in this version

- **Control:** fly a Betaflight drone over 4G. Sticks are sent 250 times a second, and failsafe is built in.
- **Video:** live H.265/H.264 video with a military-style OSD (horizon, heading, battery, link, GPS, warnings).
- **Setup:** the installer sets up the PC; a short setup wizard does the rest: Tailscale sign-in, radio calibration, two Betaflight settings over USB, the phone apps by QR code, and a live connection check.
- **RC CHECK:** shows what Betaflight sees and why it might refuse to arm.

> Experimental: bench test with the props off, and set up GPS Rescue before flying out of sight.
