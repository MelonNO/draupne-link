# DroidDrone PC

**Fly your Betaflight drone over 4G from a Windows PC.**
Your radio plugs into the PC; an Android phone on the drone carries the sticks, the live video and the telemetry over the mobile network.

![OSD over the live video](docs/images/osd.png)

## Get started in 3 steps

1. **Download** `DroidDrone-PC-Setup-<version>.exe` from the [latest release](../../releases/latest).
2. **Run it** and click through the installer (Next → Install → Finish). Approve the Windows prompt once: the installer then also sets up this PC by itself (**Tailscale**, firewall rules, High performance power plan) and puts **DroidDrone PC** on the desktop and in the Start menu.
   Windows may say *"Windows protected your PC"*, because the installer isn't code-signed. Click **More info → Run anyway**.
3. **DroidDrone PC starts by itself** when you press Finish (untick *Launch DroidDrone PC* to skip that). **Follow the setup wizard**: it opens on the very first start, skips whatever is already done, and takes about 15 minutes. Later, **SETUP** opens it again (*First-time setup*) or each part on its own: PC, phone or drone.

## What you need

- A Windows 10/11 PC on the internet (cable or Wi-Fi)
- A radio that works as a USB joystick (RadioMaster or any EdgeTX radio)
- A drone with a **Betaflight** flight controller, plus a USB cable for it ([which version?](#which-betaflight-version))
- An Android phone (8 or newer, 11+ recommended) with mobile data, and a **USB OTG adapter** to plug it into the flight controller

### Which Betaflight version?

Betaflight 4.4 is **not required**, only recommended:

| Betaflight | Result |
|---|---|
| **4.4, 4.5 or newer** | Best. The phone's Flight app is made for these versions, so everything works |
| **4.2 or 4.3** | Flies. The Flight app shows the version in **yellow** as a warning but still sends your sticks. RC CHECK may name some arming blocks wrongly |
| **Older than 4.2** | Not recommended: it lacks the stick delay setting (`serial_update_rate_hz`, see [Latency](#latency)), so stick updates queue up in the flight controller. Update with Betaflight Configurator's *Firmware Flasher* tab |

## The setup wizard does the work

| | The wizard does this for you | You do this, guided step by step |
|---|---|---|
| **This PC** | The **installer** installs **Tailscale** (private network between PC and phone), adds the firewall rules and switches to the High performance power plan. The wizard opens Tailscale's sign-in page and skips every step that is already done | Sign in to Tailscale |
| **Radio** | Detects it, then calibrates every stick and switch by having you move them ([like a flight sim](docs/images/setup-radio.png)) | Plug it in and pick *USB Joystick (HID)* on the radio |
| **Drone** | Over USB, with nothing to click: sets the receiver to **MSP** and the **stick delay setting**. Nothing else on the drone is touched | Plug it in. No USB cable? The page shows the same two settings for Betaflight Configurator |
| **Phone** | Downloads the official DroidDrone Flight app, checks it, and serves it to your phone by **QR code**. Spots the phone joining Tailscale and saves its address | Scan two QR codes, set four phone settings from a short list |
| **Test** | Connects to the drone by itself and measures the path (direct or relayed) and the round-trip time, and gives you a verdict | Plug the phone into the flight controller, phone on 4G |

<p>
<img src="docs/images/setup-welcome.png" width="49%" alt="Setup wizard: welcome">
<img src="docs/images/setup-betaflight.png" width="49%" alt="Setup wizard: drone">
</p>

## Flying

![Main window](docs/images/main-window.png)

- Press **CONNECT**. The status turns **LINK READY** and the phone's camera appears with the **OSD**: horizon and pitch ladder, heading tape with home marker, battery, 4G signal and ping, GPS, timer, flight modes and warnings.
- RC is only sent once the throttle has been seen at the bottom (**RC OUTPUT: LIVE**).
- **Double-click** the video for a full-window picture. **F11** puts the video in true full screen, which can save up to one screen refresh of delay. **F2** turns the OSD on or off.
- **F3** runs a **latency test**: point the drone's camera at the code on the screen, and the app shows the real camera-to-screen delay.
- **RC CHECK** shows what Betaflight actually sees: whether the sticks arrive, throttle low, the arm switch, active modes, and every reason it refuses to arm, with the fix for each.

## Latency

Every part is built for the least delay:
- **Sticks:** read and sent 250 times a second in a single step, with no extra queues.
- **Steady timing:** the app holds Windows' 1 ms timer, opts out of power throttling and runs at above-normal priority, so the stick rate holds even when the window is in the background. Without this, Windows can stretch the gaps between stick updates to 16 ms.
- **Video:** each frame is decoded the moment its last packet arrives, and only the newest picture is drawn. The PC side takes about 3–4 ms per 720p frame from the last packet to the screen; the stats line shows the live numbers.
- **Video bitrate:** lowered as soon as the ping climbs above its usual level, before frames get lost. Mobile networks queue up a too-high bitrate, and queueing is delay.
- **Measure it:** **F3** shows the whole camera-to-screen delay. Try it in a window and in **F11** full screen.
- **Connection:** Tailscale normally finds a **direct** path. The setup's link test tells you if it doesn't.

The 4G network itself (typically 40–100 ms round trip) is the biggest delay. A wired PC helps a little.

**Betaflight setting that matters:** Betaflight handles one MSP message per serial cycle, and that cycle runs only 100 times a second by default. Stick updates plus the OSD's telemetry requests are more than that, so stick updates queue up in the flight controller. SETUP → *Drone setup* sets it over USB by itself (1000 on F411 boards). Without USB, type this once in Betaflight Configurator's **CLI** tab (Betaflight 4.2 or newer):
```
set serial_update_rate_hz = 2000
save
```

## Troubleshooting

| What you see | What it means / what to do |
|---|---|
| Phone app: **FC disconnected** | Tap **Connect** in the Flight app first. Then use a USB-A cable plus an OTG adapter (many flight controllers ignore USB-C to USB-C), check the phone's **OTG** setting, and tap **Allow** on the USB pop-up |
| OSD says **ARMING BLOCKED: ANGLE** | The drone isn't level, or the accelerometer isn't calibrated. Calibrate it in Betaflight Configurator's **Setup** tab, drone level |
| Flight app shows the Betaflight version in **yellow** | Betaflight is older than 4.4. It still flies; update when convenient ([which version?](#which-betaflight-version)) |
| Betaflight ignores the **arm switch** | DroidDrone sends your arm switch on AUX1. In Betaflight Configurator's **Modes** tab, put ARM on AUX 1. **RC CHECK** shows what Betaflight sees |
| OSD says **ARMING BLOCKED: MSP** | Betaflight Configurator disabled arming and was unplugged without *Disconnect*. Power-cycle the flight controller. SETUP → *Drone setup* also clears it |
| **Video is black** | It's what the camera sees: is the lens covered, or is it dark? Point it at something bright. To use another lens, set `cameraId` in the settings (often `2` or `3` is the wide-angle) |
| Link test says **relayed** | Tailscale couldn't go direct through your mobile network, which adds latency. Run the test again after a minute. If it stays relayed, choose *Host the relay on this PC* in SETUP → *PC setup* |
| Channels look wrong | **CALIBRATE**, then **RC CHECK** |

Settings live in `%APPDATA%\DroidDronePC\droiddrone-pc.properties`.

## How it works

```
Radio --USB--> Windows PC  ==== 4G / Tailscale ====>  Android phone on the drone --USB OTG (MSP)--> Betaflight
               DroidDrone PC  <== video + telemetry ==  DroidDrone Flight app (unmodified)
```

DroidDrone PC replaces [DroidDrone](https://github.com/DroidDrone/DroidDrone)'s *Control* phone app. It speaks the same protocol as the unmodified **DroidDrone Flight 2.3.0** app, and compiles upstream's own protocol code from a pinned submodule, so it can't drift from the original.

## For developers

```powershell
git clone --recurse-submodules https://github.com/<owner>/droiddrone-pc
cd droiddrone-pc
.\gradlew.bat test               # 62 tests: protocol, video decode, telemetry, calibration, Betaflight CLI, setup helpers
.\gradlew.bat installDist        # build\install\droiddrone-pc\bin\droiddrone-pc.bat
.\gradlew.bat releaseZip         # portable zip (app + Java runtime)
.\gradlew.bat packageInstaller   # installer, needs WiX Toolset 3
```
Needs JDK 21. To release, set `version` in `build.gradle`, then push a tag `v<version>`. GitHub Actions builds and publishes the installer and the zip.

| Package | What's in it |
|---|---|
| `de.droiddrone.pc` | Link (`ControlLink`), connection modes, joystick, calibration, RC mapping, telemetry (`FcTelemetry`) |
| `de.droiddrone.pc.video` | Frame reassembly, FFmpeg decoder, bitrate control, low-latency pipeline |
| `de.droiddrone.pc.setup` | Tailscale, firewall, power plan, shortcuts, Betaflight over USB (CLI), Flight app download/serving |
| `de.droiddrone.pc.gui` | Main window, OSD, setup wizard, calibration, RC check |

More: [protocol notes](docs/PROTOCOL.md) and [roadmap](docs/ROADMAP.md).

## License

GPL-3.0, the same as DroidDrone, whose code this build includes. Third-party components and their licenses are listed in [THIRD-PARTY.md](THIRD-PARTY.md).
