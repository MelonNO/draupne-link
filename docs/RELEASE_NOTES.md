<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.27

A full review of the program and the phone app, every finding fixed, plus the pilot's second round of SETUP feedback. Phone app **DRAUPNE Flight 2.3.0-pc.19** goes with it: update the phone (tap its yellow *Update available* line).

**Flight safety and the link**
- **The sticks come back at once after a link loss when the drone is still armed** (a GPS rescue in progress): before, the first frame after recovery carried ARM off and Betaflight would disarm in the air. A disarmed drone still needs throttle low and the arm switch off. The HUD says LINK BACK - ARMED - STICKS LIVE.
- **A restarted PC reconnects at once.** The phone used to answer the new session with "the pilot is at <the same PC>", which made the PC a spectator of itself; both sides are fixed.
- **Stick frames carry a sequence number** (with phone app pc.19): a 4G packet that arrives out of order is dropped on the phone instead of written to the flight controller. The phone **never sends sticks unmapped**: it waits for Betaflight's channel map and keeps asking for it.
- The phone writes the sticks straight from the receive thread (two thread hops less), its receive buffer no longer drops a burst's newest frames, and its sender threads no longer spin a CPU core all flight (heat, throttling).
- The link counts as lost after 2 s of silence (was 4-5 s). The arm-switch guard also covers an unmapped AUX channel. "RC OFF - NO RADIO" / "MAPPING INCOMPLETE" instead of a wrong "THROTTLE DOWN".
- **STICKS n MS** on the HUD: the phone's own word on how old its last write to the flight controller is (red above 100 ms), and the link log gains `pc_to_fc_ms` (stick latency PC to flight controller), the phone's state columns and the radio poll time.

**Video**
- A bad-coverage size switch no longer freezes the picture for 1.5-2 s (the restarted stream's first key frame waits for its settings). A dropped backlog flushes the decoder instead of rebuilding it (no black gap). Key-frame requests back off while they keep arriving damaged.
- The bitrate rescue no longer fights bad-coverage mode (no 1<->2 Mbit/s oscillation). Correct colours (BT.709) for the phone's HD stream; the scaler uses all cores.
- HUD: **AGE n MS** (how old the picture on screen is, rising before VIDEO FROZEN), VID shows the received fps and Mbit/s, LINK colours by the ping over the link's own baseline instead of fixed thresholds. Recording also writes a CSV of the HUD values next to the MKV.

**Setup guide and Betaflight**
- **Drone setup writes nothing until you press WRITE**: a "What will change" row lists the receiver, stick rate, cell count, switches (with what is taken off them) and failsafe first. The cell count is forced only once you confirm it (or take it from the measured battery).
- **The radio step comes back** whenever Drone setup wrote or changed a switch, so an old calibration can't leave ARM on the wrong switch; the RC check uses the real arm switch.
- CLI replies can't be cut at a "# " line any more (Betaflight 2025.12's `diff all`), the board is never left in CLI mode, the board and craft names are shown, every CLI command and reply is logged.
- RETRY / DISCONNECT behave while a read is running; Next waits for it; "Betaflight restarts..." instead of a cable warning; clearer texts throughout (ARM on no switch, By hand with ARM, Configurator's mode names, Tailscale service down, cable advice).
- Motor test: 1S packs no longer read as "no battery"; a hard 20 s auto-stop.
- The **tour** rewritten: MENU opens first, one step per menu button, then the window's buttons and the map (including MAP TYPE and the coordinate format); other buttons are dead while a step shows; EXIT TOUR; it skips what isn't on screen and ends when a window closes.
- Connect step: says what the link and the video are doing (and reconnects when the phone still names the old session as pilot). Finale: no stray line, the mark stays twice as long.
- Phone app: a denied USB permission is noticed and asked again on replug; a lost camera is reopened; a double CONNECT can't stop the service; a wake lock while running; the running notification on Android 13+; the updater refuses an APK without a checksum; MSP replies split across USB reads are no longer dropped.

**Map, main window, files**
- **Pressing CONNECT again no longer wipes the last known position**; FIND DRONE refreshes live, falls back to the last track file after a restart, shows the phone fix's accuracy and has COPY MGRS. **LOST DRONE** appears on the LINK card when the link drops while armed: one press opens FIND DRONE with the MGRS on the clipboard.
- DOWNLOAD AREA can't run the app out of memory any more (tiles are streamed, not listed), has PAUSE / RESUME, a time estimate from the measured pace, skips Kartverket outside Norway, gives up on a server that refuses, and its HIDE / CANCEL buttons only show once a download runs; SELECT WITH CURSOR hides the window while you draw.
- Map window: **GO TO** (paste MGRS, UTM or lat/lon; pin there), **TRACKS** (earlier flights on the map), PINS > EXPORT GPX, keys + / - / Home / End / F3, a pin from the CURSOR cell, pin labels with distance and bearing from the drone and the launch point. The right-click menu lost the map types (the MAP TYPE combo is the place); WHOLE FLIGHT and LAUNCH buttons are gone (double-click, Home, End).
- The window remembers its place and maximized state, also for the map window; an iconified map window counts as closed. UPDATE stays visible with the MENU folded. **POP OUT VIDEO and FULL SCREEN are buttons over the video** (the menu rows are gone).
- Settings, the track and the pins are written atomically (a crash can't empty them); the track is written off the Swing thread; the final position is saved at disconnect.

**Known limits:** none of this has flown yet. The first flight should be a bench test with props off: arm, disarm, pull the phone's cable, and watch the HUD's STICKS and AGE figures. The phone's new encoder settings (low-latency, no B-frames, constant bitrate) need a look at the picture on the first connection.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
