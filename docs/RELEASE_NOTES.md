<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.17

- **Spectator mode** (off by default: SETTINGS > Allow spectators). A second PC that connects while you fly becomes a spectator: it shows your video and OSD a little behind you (600 ms), can record, and never sends sticks. It finds your PC through the phone and watches through it; the phone sends nothing extra, so your link is unchanged. It uses your PC's upload (about one video stream each, at most 2 spectators) and pauses by itself when your link to the drone slows down. A spectator never takes the drone over, also when you disconnect. Needs DRAUPNE Flight 2.3.0-pc.11 on the phone and direct phone mode (Tailscale).
- **Drone setup sets your switches and failsafe:** ARM (AUX1 by default), a 3-position flight mode switch (AUX2: low acro, middle angle, high GPS position hold) and the failsafe (leave as it is, drop, or GPS rescue), with a GPS rescue switch (AUX4) once the failsafe is GPS rescue. Each can be changed or left as it is in Betaflight. ARM on any other switch is taken off, so there is one arm switch. Betaflight's settings are backed up first, as before.
- **GPS checks:** a GPS mode or GPS rescue failsafe turns Betaflight's GPS on, and is only written once the GPS answers (no fix needed, so indoors is fine) and Betaflight is new enough (GPS rescue 4.4, GPS position hold 2025.12). No GPS found shows as a GPS hardware failure, with what to check. With GPS rescue in use, Betaflight arms only with a GPS fix: Drone setup says so.
- **Drone setup is tidier:** five rows (flight controller, Betaflight settings, switches, failsafe, motors); the manual way is behind **By hand**.
- **First-time setup covers everything:** it is made of PC, drone and phone setup's own steps (it was missing the connection method and the phone overview). The radio comes after the drone, and its calibration names each switch after what Drone setup put on it (AUX1 - ARM, AUX2 - flight modes, AUX4 - GPS rescue).
- **An app log for crashes and freezes:** %APPDATA%\DraupneLink\logs\app-DATE.log, with the window's log, errors, and every thread's stack when the window stops responding.
- **Phone app 2.3.0-pc.11:** tells a second PC which PC flies (spectator mode); otherwise unchanged.

The phone app in this release is DRAUPNE Flight 2.3.0-pc.11: install it on the phone for spectator mode (SETUP > Phone setup hands it over by QR code).

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
