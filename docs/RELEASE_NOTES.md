<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.9

- **UPDATE now updates:** pressing UPDATE downloads the new version, checks it against the checksum GitHub lists for it, closes DroidDrone PC, installs it (Windows asks for permission once; no setup wizard) and starts it again. Settings stay. It won't run while connected to the drone. The portable zip version still opens the release page.
- **CALIBRATE shows when it's locked:** while connected it's greyed with a padlock and slowly drifting hazard stripes; clicking it shakes it red and the log says why (disconnect first).
- No controller: the stick boxes no longer send out rings; only the small blinking circle in the middle stays.
- **Right cell count when Betaflight gets it wrong:** with the flight controller already on the phone's USB power, Betaflight can count the cells while the battery voltage is still rising, settle on 1S or 2S for a 6S, and keep that until the battery is unplugged. When its count is impossible (over 4.5 V per cell), the OSD now waits for the voltage to settle, counts the cells itself and shows e.g. **BATT 6S (BF SAYS 1S)**, so you know Betaflight's own low-voltage warnings are off too.
- **Phone battery on the OSD:** a battery icon with the charge, green / yellow / red, and a big **PHONE BATTERY LOW** at 15 % or less while not charging (the phone is the radio). The Windows app also shows it under PHONE BATTERY.
- **Old phone app warning:** the Windows app shows the phone app's version (PHONE APP) and warns, with how to update, when the phone runs an older one than this PC brings.
- **One crosshair:** the OSD and the no-video screen use the same Betaflight-style crosshair (the red "W" is gone).
- **Phone app 2.3.0-pc.5:** tells DroidDrone PC its version once a second.

Coming from 1.0.8 or older: download the installer below once; from then on UPDATE does it for you.

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
