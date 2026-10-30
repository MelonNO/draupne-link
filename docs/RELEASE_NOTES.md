<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.10

- **Calibration takes a true centre:** the "Center the sticks" step now waits until the sticks have rested for half a second and takes the average, instead of the one reading at the moment Next is pressed. The last page warns when roll, pitch or yaw would still sit off 1500 at rest (e.g. **PITCH 17 us**): a constant stick input that makes the drone lean or twitch when landed and armed. If you calibrated before, calibrate again with your hands off the sticks at the centre step.
- **The installer checks that DroidDrone PC isn't open:** when it starts, and again right before it removes the old version, it looks for a running DroidDrone PC. If it finds one it says so: close it (disconnect from the drone first), then **Retry**, or **Cancel** with nothing changed. It never closes the app for you, so a live drone link is never cut. Before, it went ahead and could leave a half-replaced install behind.
- **Battery cell count in the drone setup:** the Betaflight page (SETUP > Drone setup, and the DRONE step of the first-time setup) has a **BATTERY CELLS** field, already set to 6. Confirm it or change it; it's written to Betaflight as `force_battery_cell_count` together with the other settings, so Betaflight never counts a 6S as 1S again. Changing it later writes it again by itself.

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
