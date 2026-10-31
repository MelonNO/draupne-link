<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.16

- **New name: DRAUPNE Link** (formerly DroidDrone PC), after Draupne, Odin's ring. The phone app is now **DRAUPNE Flight**. The GitHub repository moved to MelonNO/draupne-link; old links redirect.
- **Your setup comes along:** on the first start the settings are copied from %APPDATA%\DroidDronePC to %APPDATA%\DraupneLink (the old folder is left as it was).
- **The installer replaces DroidDrone PC:** it upgrades the old install to Program Files\DRAUPNE Link, waits for either app to be closed, and swaps the old firewall rules for new ones. Desktop and Start menu shortcuts the setup guide made for the old app are removed once it is gone.
- **The phone app updates in place:** same package and signing key, so DRAUPNE Flight 2.3.0-pc.10 installs over DroidDrone Flight and keeps its settings.
- New recordings go to Videos\DRAUPNE Link and Betaflight backups to Documents\DRAUPNE Link; earlier ones stay where they were.
- **From DroidDrone PC 1.0.15 or older, UPDATE opens the release page** instead of installing by itself (it only knows the old file names): download DRAUPNE-Link-Setup-1.0.16.exe there and run it. From 1.0.16 on, UPDATE installs by itself again.

The phone app in this release is DRAUPNE Flight 2.3.0-pc.10 (only the name changed).

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
