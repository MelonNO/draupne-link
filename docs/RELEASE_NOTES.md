<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.9

- **UPDATE now updates:** pressing UPDATE downloads the new version, checks it against the checksum GitHub lists for it, closes DroidDrone PC, installs it (Windows asks for permission once; no setup wizard) and starts it again. Settings stay. It won't run while connected to the drone. The portable zip version still opens the release page.
- **CALIBRATE shows when it's locked:** while connected it's greyed with a padlock and slowly drifting hazard stripes; clicking it shakes it red and the log says why (disconnect first).
- No controller: the stick boxes no longer send out rings; only the small blinking circle in the middle stays.

Coming from 1.0.8 or older: download the installer below once; from then on UPDATE does it for you.

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
