<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.7

- **Animated, military-style controls:** buttons get closing corner brackets and a scan line on hover and a lock-on flash on click; the dropdowns (connection, controller) reveal their options one by one behind a red scan bar.
- **Video skeleton:** while there's no video, the video area shows a skeleton of the view - grid, corner brackets, pulsing reticle, shimmering placeholders where the OSD goes, a slow scan line - with the status.
- **One controller, no dropdown:** with a single controller (or none) its name shows in a plain field; the dropdown appears only when there are several to choose from.
- Clicking **SETUP** again now closes its menu (it used to open it again).

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
