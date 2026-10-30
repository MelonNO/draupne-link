<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.13

- **Phone app 2.3.0-pc.7: the connection card shows in portrait.** In pc.6 the status card took the whole height when the phone was upright.
- **Phone app 2.3.0-pc.7: Direct connection by default.** A fresh install starts on *Direct connection* (the phone listens, DroidDrone PC connects to it over Tailscale) instead of the relay server; a mode you already chose stays.
- **Latency test moved to SETTINGS:** *Latency test* is a switch in the SETTINGS menu (F3 no longer does it; Esc still stops it). The setup guide has a new step, **Measure the delay**: choose **TEST AFTER SETUP** and DroidDrone PC connects and starts the test when you press Finish.
- **SETTINGS menu, clearer:** *Camera upside down* and *Latency test* are switches that slide and leave the menu open; *OSD layout* and *Motor test* sit under a line with a chevron, because they open a window of their own.
- **Windows open like the SETUP menu:** the OSD editor, motor test, calibration, RC check and the setup guides are uncovered band by band behind the red scan bar; **Next** in a setup guide brings the next page in the same way.
- **The OSD editor is a normal window:** Windows' own minimise, maximise and close; it opens maximised. The WINDOW / FULL SCREEN button and F11 there are gone.
- **Motor test: PROPS IN / PROPS OUT animates:** the highlight slides over and the arrows sweep round the new way.
- **The CALIBRATE notice goes at a click anywhere else,** before its time runs out.
- **The last picture fits when the video stops:** after the video was lost, EXPAND / MINIMIZE (or resizing the window) left the frozen last frame at its old size. It now scales to fit; live video is still drawn 1:1, with no extra step.

## Files

- **`DroidDrone-PC-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DroidDrone-PC-*-windows.zip`**: the portable version. Unzip and run `DroidDrone PC.exe`.
- **`DroidDrone-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/droiddrone-pc/blob/main/docs/CHANGELOG.md)
