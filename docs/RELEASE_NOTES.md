<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.21

- **Video for bad coverage: SETTINGS > Video AUTO / SHARP / ROBUST.** ROBUST trades sharpness for a picture that holds on a weak link: 640x360, at most 1 Mbit/s, key frames sent twice, and after every frame a small parity packet from which the PC rebuilds any one lost packet, so a single lost packet no longer leaves a grey or green band. **AUTO** (the default) switches to it by itself after 5 s at the lowest bitrate or after 3 stalls within 30 s, and back to the sharp picture after 20 s of calm (longer if the link keeps falling back). SHARP never switches. Needs phone app 2.3.0-pc.15.
- **Picture size changes without a long blackout:** the phone keeps the camera open and only restarts the encoder (aim: under 0.5 s instead of 1.5-2.5 s). The PC checks that the phone really changed size and asks again if it didn't.
- **Faster reaction to a congested link:** a ping jump of 250 ms lowers the bitrate at once (then once a second while it lasts), no key frames are asked for while the link is congested (each is the biggest burst there is), and key frame requests come at most every 1.5 s.
- **Top bitrate 4 Mbit/s** (was 6; settings on 6 move to 4 once): 6 rarely got through 4G and its bursts cost delay.
- **Smoother spectator picture** (frames spaced out evenly, up to 250 ms of smoothing).
- **Phone app 2.3.0-pc.15:** a camera restart or video start that went wrong could leave a flag set so the phone ignored every later size change; that can no longer happen.
- **Link log:** the bitrate step, key frame requests, bad coverage mode and rebuilt frames (`recovered`).

The phone app in this release is DRAUPNE Flight 2.3.0-pc.15 (SETUP > Phone setup hands it over by QR code). Update the phone app: the bad coverage mode and the quick size change need it.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
