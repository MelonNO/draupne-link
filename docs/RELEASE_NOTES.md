<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.20

- **Fix: phone app 2.3.0-pc.14 replaces pc.13. Update the phone app.** On some phones pc.13 started no video (it used intra refresh where the phone's encoder claimed to support it), and the hung video start also held up the sticks to the flight controller. pc.14 doesn't use intra refresh (key frames every 2 s and whenever the PC asks), and starts the video on a thread of its own, so camera or encoder trouble can never hold up the sticks again. pc.13's other improvements stay: paced packets, 30 fps, the PC deciding the bitrate.
- **A phone app that stops answering is now a LINK STALL** (with the warning sound): before, its telemetry kept the link looking fine while pings went unanswered and the sticks weren't getting through.

The phone app in this release is DRAUPNE Flight 2.3.0-pc.14 (SETUP > Phone setup hands it over by QR code). If your phone has pc.13, update it.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
