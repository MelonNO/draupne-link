<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.18

- **Spectator picture fixed:** it was a coloured mess. Full-size video packets reach the spectator 9 bytes longer (the pilot PC's header in front) and were cut off on arrival, which broke nearly every frame. The spectator now sees exactly the pilot's picture. Update the spectator PC; the pilot's own picture was never affected.
- **Steady camera frame rate** (SETTINGS > Camera frame rate: **60** by default, 30, or auto). The camera used to run at 30-60 fps and, in dim light such as indoors, dropped to 30 and back up, which looked jumpy. Settings still on the old 30-60 move to a steady 60 once. Indoors a steady 60 is darker; choose 30 there for a brighter picture. A steady rate needs phone app 2.3.0-pc.12: with an older one the PC keeps 30-60 and says so.
- **The link log records the video** (video_rx_fps, video_mbps, video_damaged, video_lost), so a poor picture shows whether the bitrate dropped or frames broke.
- **Phone app 2.3.0-pc.12:** picks a frame-rate range the camera supports, nearest to the one asked for, so a steady 60 can't stop the picture on a camera without it.

The phone app in this release is DRAUPNE Flight 2.3.0-pc.12: install it on the phone for the steady frame rate (SETUP > Phone setup hands it over by QR code).

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
