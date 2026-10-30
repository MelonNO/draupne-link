<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.8

- **FPV-style "no signal" screen:** with no video, the video area shows dim analog static with a Betaflight-style crosshair and a gently rocking horizon, and the status under it. The scan line, grid and edge placeholders are gone.
- **Minimize the controller panel:** MINIMIZE (top right of the controller panel) hides the sticks and channel bars, and the video grows into the space. EXPAND brings them back; the choice is remembered.
- **No controller is obvious:** the stick boxes pulse as if searching and say NO CONTROLLER, the controller field's frame and the status line blink red.
- The **SETUP** menu's items come in one by one, like the dropdowns.
- Buttons no longer flash a shine across on hover (the corner brackets and the click flash stay).

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
