<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.28

Phone app **DRAUPNE Flight 2.3.0-pc.21** goes with it: update the phone (tap its yellow *Update available* line). The phone asks once for three permissions (notifications, alarms and reminders, locking the screen): say yes to each; the setup guide lists them.

- **STANDBY and DEEP SLEEP** on the LINK card save the phone's battery while you wait for take-off. **STANDBY** stops the camera, slows the GPS and telemetry and dims the screen; the link stays and **WAKE** brings the picture back in about two seconds. **DEEP SLEEP** drops the link, locks the phone's screen and lets it sleep; every **CHECK EVERY** minutes (1, 2 or 5) it listens for the PC, so WAKE brings it back at its next check with nobody touching the phone. Neither works while the drone is armed, and both end by themselves when it arms. No sticks are sent in standby.
- **Phone app:** CONNECT in Direct mode with Tailscale off on the phone warns "Tailscale is off" with *Open Tailscale* / *Connect anyway*, and the network line says so while waiting.
- **UPDATE made visible.** The helper that installs an update now logs every step, waits for a locked installer, always starts the app again whatever happens, and the next start reports how it went. Pressing UPDATE while connected says why it refuses; a copy that isn't the installed one says where it runs from and opens the release page. (On the maintainer's PC the installed copy had stayed at 1.0.24 while three updates were pressed: the helper never got to the installer.)
- **Tour:** no pop-out and full-screen steps; NEXT reads "Press it" until the highlighted control was pressed, so you learn by doing; the map stays live under the map steps (cursor figures, panning, pins); UNDER THE CURSOR lights the CURSOR and TERRAIN HEIGHT cells together; the main window comes to the front for the last step.
- **Video buttons** (POP OUT VIDEO, FULL SCREEN) at the right of the picture, one under the other.
- The channel bars in the RC check and on the controller card name each AUX after what Drone setup put on it ("AUX1 ARM").

**Known limits:** the power modes have not run on a real phone yet. First trials: STANDBY and WAKE with the drone on the bench, then DEEP SLEEP with the screen locked, a 5-minute wait and WAKE. If the phone does not come back, open DRAUPNE Flight on it: that wakes it too.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
