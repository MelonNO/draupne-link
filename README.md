# DroidDrone PC

**Fly your Betaflight drone over 4G from a Windows PC.**
Your radio plugs into the PC; an Android phone on the drone carries the sticks, the live video and the telemetry over the mobile network.

![OSD over the live video](docs/images/osd.png)

> **Experimental.** This is a hobby project. Bench test everything with the propellers off, set up and test GPS Rescue
> before you fly out of sight, keep your first flights close and in line of sight, and follow your local drone rules.

## Get started in 3 steps

1. **Download** `DroidDrone-PC-Setup-<version>.exe` from the [latest release](../../releases/latest).
2. **Run it** and click through the installer (Next → Install → Finish). It installs for your user only, with no admin rights needed, and puts **DroidDrone PC** on the desktop and in the Start menu.
   Windows may say *"Windows protected your PC"*, because the installer isn't code-signed. Click **More info → Run anyway**.
3. **Start DroidDrone PC** from the desktop and **follow the setup wizard**. It opens on first start and takes about 20 minutes.

Prefer no installer? Download `DroidDrone-PC-<version>-windows.zip`, unzip it anywhere and run `DroidDrone PC.exe`.

## What you need

- A Windows 10/11 PC on the internet (cable or Wi-Fi)
- A radio that works as a USB joystick (RadioMaster or any EdgeTX radio)
- A drone with a **Betaflight 4.4+** flight controller, plus a USB cable for it
- An Android phone (8 or newer, 11+ recommended) with mobile data, and a **USB OTG adapter** to plug it into the flight controller

## The setup wizard does the work

| | The wizard does this for you | You do this, guided step by step |
|---|---|---|
| **This PC** | Installs and signs in to **Tailscale** (private network between PC and phone), adds firewall rules, switches to the High performance power plan, creates shortcuts | Choose how the PC reaches the phone |
| **Radio** | Detects it, then calibrates every stick and switch by having you move them ([like a flight sim](docs/images/setup-radio.png)) | Plug it in and pick *USB Joystick (HID)* on the radio |
| **Flight controller** | Over USB: sets the receiver to **MSP**, puts **ARM on AUX1**, **calibrates the accelerometer**, and shows Betaflight's arming check live | Plug it in. Without a USB cable you get a short checklist for Betaflight Configurator instead |
| **Phone** | Downloads the official DroidDrone Flight app, checks it, and serves it to your phone by **QR code**. Spots the phone joining Tailscale and saves its address | Scan two QR codes, tick off a few phone settings, plug the phone into the flight controller |
| **Test** | Connects to the drone and measures the path (direct or relayed) and the round-trip time, and gives you a verdict | Put the phone on 4G |

<p>
<img src="docs/images/setup-welcome.png" width="49%" alt="Setup wizard: welcome">
<img src="docs/images/setup-betaflight.png" width="49%" alt="Setup wizard: Betaflight">
</p>

## Flying

![Main window](docs/images/main-window.png)

- Press **CONNECT**. The status turns **LINK READY** and the phone's camera appears with the **OSD**: horizon and pitch ladder, heading tape with home marker, battery, 4G signal and ping, GPS, timer, flight modes and warnings.
- RC is only sent once the throttle has been seen at the bottom (**RC OUTPUT: LIVE**).
- **Double-click** the video for a full-window picture. **F2** turns the OSD on or off.
- **RC CHECK** shows what Betaflight actually sees: whether the sticks arrive, throttle low, the arm switch, active modes, and every reason it refuses to arm, with the fix for each.

## Latency

Every part is built for the least delay:
- **Sticks:** read and sent 100 times a second in a single step, with no extra queues.
- **Video:** each frame is decoded the moment its last packet arrives, and only the newest picture is drawn. The PC side takes about 2 ms per 720p frame.
- **Connection:** Tailscale normally finds a **direct** path. The setup's link test tells you if it doesn't.

The 4G network itself (typically 40–100 ms round trip) is the biggest delay. A wired PC helps a little.

## Troubleshooting

| What you see | What it means / what to do |
|---|---|
| Phone app: **FC disconnected** | Tap **Connect** in the Flight app first. Then use a USB-A cable plus an OTG adapter (many flight controllers ignore USB-C to USB-C), check the phone's **OTG** setting, and tap **Allow** on the USB pop-up |
| OSD says **ARMING BLOCKED: ANGLE** | The drone isn't level, or the accelerometer isn't calibrated. Run SETUP → *Set up Betaflight* → **Calibrate** |
| OSD says **ARMING BLOCKED: MSP** | Betaflight Configurator disabled arming and was unplugged without *Disconnect*. Power-cycle the flight controller. The setup wizard's Betaflight step also clears it |
| **Video is black** | It's what the camera sees: is the lens covered, or is it dark? Point it at something bright. To use another lens, set `cameraId` in the settings (often `2` or `3` is the wide-angle) |
| Link test says **relayed** | Tailscale couldn't go direct through your mobile network, which adds latency. Run the test again after a minute. If it stays relayed, choose *Host the relay on this PC* in SETUP |
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
.\gradlew.bat test               # 55 tests: protocol, video decode, telemetry, calibration, Betaflight CLI/MSP, setup helpers
.\gradlew.bat installDist        # build\install\droiddrone-pc\bin\droiddrone-pc.bat
.\gradlew.bat releaseZip         # portable zip (app + Java runtime)
.\gradlew.bat packageInstaller   # installer, needs WiX Toolset 3
```
Needs JDK 21. To release, set `version` in `build.gradle`, then push a tag `v<version>`. GitHub Actions builds and publishes the installer and the zip.

| Package | What's in it |
|---|---|
| `de.droiddrone.pc` | Link (`ControlLink`), connection modes, joystick, calibration, RC mapping, telemetry (`FcTelemetry`) |
| `de.droiddrone.pc.video` | Frame reassembly, FFmpeg decoder, bitrate control, low-latency pipeline |
| `de.droiddrone.pc.setup` | Tailscale, firewall, power plan, shortcuts, Betaflight over USB (CLI + MSP), Flight app download/serving |
| `de.droiddrone.pc.gui` | Main window, OSD, setup wizard, calibration, RC check |

More: [protocol notes](docs/PROTOCOL.md) and [roadmap](docs/ROADMAP.md).

## License

GPL-3.0, the same as DroidDrone, whose code this build includes. Third-party components and their licenses are listed in [THIRD-PARTY.md](THIRD-PARTY.md).
