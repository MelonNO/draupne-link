## Install

**`DroidDrone-PC-Setup-*.exe`** is the installer. Run it and click through it (Next → Install → Finish), approving the Windows prompt once. It installs DroidDrone PC for all users and sets up this PC by itself: Tailscale, firewall rules and the High performance power plan. DroidDrone PC starts when you press Finish; follow the setup wizard that opens on the first start.

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
- **Setup:** a single setup wizard covering Tailscale, firewall, radio calibration, Betaflight over USB, the phone apps by QR code, and a link test.
- **RC CHECK:** shows what Betaflight sees and why it might refuse to arm.

> Experimental: bench test with the props off, and set up GPS Rescue before flying out of sight.
