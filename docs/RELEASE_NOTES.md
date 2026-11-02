<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.23

- **The map, in a window of its own** for a second screen (MENU > TOOLS or the POP OUT MAP button, F3): where the drone is and has been, over Kartverket's topo or printed map, satellite pictures or OpenStreetMap (AUTO picks Kartverket in Norway). FOLLOW, WHOLE FLIGHT, LAUNCH, FIND DRONE, PINS; + and - buttons, the wheel, drag; the figures under the map: the place under the mouse and the terrain height there in metres and feet, the drone's position, altitude, distance and bearing from the launch point. Coordinates in MGRS (default), UTM, decimal degrees or degrees-minutes-seconds.
- **Pins:** right-click on any map drops one (Alpha, Bravo ... Zulu, then Alpha2 ...), right-click a pin to rename or remove it; each shows distance and bearing from the drone. PINS lists them all with GO TO, RENAME, REMOVE, ADD AT DRONE, ADD AT LAUNCH. Kept between sessions and written into the flight's GPX file.
- **Every flight's path is saved as it comes** to Documents\DRAUPNE Link\Tracks\track-DATE.gpx (opens in any map program), with last-position.txt beside it: the drone's last known position with MGRS and map links. **FIND DRONE** shows it in the program, with the coordinates to copy.
- **Works without internet** for every place looked at before: map tiles are kept for good, and the levels under a place the map rests on are fetched too (not OpenStreetMap, whose policy forbids it).
- **Phone GPS** (MENU > TOOLS > Position from: DRONE / PHONE, with phone app 2.3.0-pc.16): the phone's own position on the map, which stays after a crash that killed the flight controller. The other source fills in when the chosen one has nothing.
- **In flight the LINK card changes:** the connection fields fold away and a minimap with POP OUT MAP unfolds; they come back when the map is popped out or on disconnect. The controller card starts folded (EXPAND shows it).
- **One MENU button** slides out SETUP, VIDEO (with the OSD), LINK and TOOLS; menus wipe in and out row by row with the scan bar.
- **The new DRAUPNE mark** everywhere - window, taskbar, installer, the phone app's icon - and a **boot animation**: the mark fades in, its brackets fly to the corners and its crosshair becomes the OSD's (3 s, a click skips it; the phone app has it too).
- Filled red buttons have black text; the popped-out map and video windows fade in; the menus' reveal no longer shows black bars on label or separator rows.
- Phone app pc.16 (bundled): the phone's GPS record, the new icon, the boot animation.

The phone app in this release is DRAUPNE Flight 2.3.0-pc.16 (SETUP > Phone setup hands it over by QR code). Update it for the phone GPS option; everything else works with pc.15 too.

## Files

- **`DRAUPNE-Link-Setup-*.exe`**: the installer. Run it, approve the Windows prompt once; the setup wizard opens on the first start. Windows may say *"Windows protected your PC"* because it isn't code-signed: **More info → Run anyway**.
- **`DRAUPNE-Link-*-windows.zip`**: the portable version. Unzip and run `DRAUPNE Link.exe`.
- **`DRAUPNE-Flight-*.apk`**: the phone app this version installs. The setup wizard hands it to the phone by QR code.

All changes: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md)
