<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.29

Phone app **DRAUPNE Flight 2.3.0-pc.22** goes with it: update the phone (tap its yellow *Update available* line).

- **The sleeping phone reports in.** In deep sleep, at every check the phone tells the PC it is still there, with its battery percentage, charging state and the time of its next check, and the PC answers at once: "sleep on", so the phone closes its window right away instead of listening for 20 seconds, or "wake" after you pressed WAKE. The LINK card shows "Heard 2 min ago, battery 67 %" and the next check from the phone's own word; the log gets a line per check. Cheaper than before: the window is a couple of seconds instead of 20.
- **Phone app:** the STANDBY and DEEP SLEEP buttons are gone; the PC sets the modes. One **HOLD TO WAKE** button (a two-second hold) and a MODE line that says where the phone is. The running notification is on a quiet channel: deep sleep's text changes no longer buzz or light the screen. The permissions for deep sleep are asked through the yellow lines on the status card; the guide says which to say yes to.
- **LINK card:** STANDBY and DEEP SLEEP slide away while the drone is armed and slide back when it is disarmed.
- **Guide:** the connect step explains STANDBY, DEEP SLEEP, WAKE and the phone's hold-to-wake; one row for Wi-Fi off, Bluetooth off and Battery Saver on before flying; the permission rows name the yellow lines.
- **Tour:** PRESS IT fits; menus and the header show in front of the dimming instead of behind it; the UNDER THE CURSOR step lights CURSOR and TERRAIN HEIGHT together with the map live underneath; the PINS step shows three example pins; the LINK card step mentions the sleep buttons.
- **Video buttons:** two small drawn squares at the right edge of the picture (pop out / video back, full screen) instead of labelled buttons; the picture keeps the room.
- **No flicker:** the boot cover is sized before any paint (the UI no longer shows for a frame as the window grows to maximized); a menu's opening picture goes only after the real menu is there; the header is re-laid out only when its width changed.
- **Boot animation:** the mark's D comes apart into the UI too: its bars become the video buttons on the right, its diagonals CALIBRATE and RC CHECK at the bottom. On the phone the bars become CONNECT, the diagonals LOCK SCREEN, and the crosshair flies to the status pill.

**Known limits:** the sleep path and the phone's new boot animation have not run on a real phone yet. First trials: STANDBY and WAKE on the bench, then DEEP SLEEP with the screen locked, a wait of one check interval, and WAKE.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
