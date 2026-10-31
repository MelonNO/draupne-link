<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.19

- **Better video over weak 4G** (with phone app 2.3.0-pc.13). Part of the picture often went grey or green: the phone sent each key frame as one burst and a weak uplink dropped its end. Now the phone paces its packets, sends key frames every 2 s instead of every 0.5 s plus one whenever the PC sees one arrive damaged, and uses intra refresh where its encoder supports it (no bursts at all). The camera now really runs at 30 fps (it often ran at 60), so each frame gets twice the data.
- **The bitrate no longer gets stuck at the bottom:** on a far-away link it sat at 0.25 Mbit/s. A single ping spike or a damaged key frame no longer lowers it, it rises again after 3 s of calm, and the PC keeps it at 1 Mbit/s or more, going by what actually arrives and by the phone's own report. On a really weak link the picture goes to 960x540 (cleaner than a starved 720p) and back to 720p when the link recovers.
- **A key frame that lost its last packet is shown** (damaged) instead of dropped, which smeared the next half second.
- **Connect as: Pilot or Spectator** (next to CONNECT). A spectator never asks to fly and waits when nobody flies. Connecting as pilot while another PC flies makes it unmistakable that you are not flying: a message, a red NOT FLYING - SPECTATOR pill and a blinking line on the video.
- **The camera always runs at a steady 30 fps;** the frame-rate setting is gone.
- **Setup: pick the controller** when several are plugged in (the radio step); after switching it asks for a calibration.
- **The link log records the video in detail:** camera fps, the phone's bitrate, decode and draw times, frames not drawn or arriving bunched, resolution, key frame requests, intra refresh.

The phone app in this release is DRAUPNE Flight 2.3.0-pc.13: install it on the phone for the video fixes (SETUP > Phone setup hands it over by QR code).

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
