<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.52

With **DRAUPNE Flight 2.3.0-pc.40**. Update the PC app, then the phone (UPDATE PHONE APP, drone disarmed). Phones on pc.39 or older can't take queued modes and show "-" in the fleet.

Not yet tried on a drone or real phone: bench-test with props off first.

### New
- **Fleet management:** a FLEET tab on the map. One row per phone: mode, batteries, last heard, next check-in. Change modes without connecting; they're sent at the next check-in.
- **LINK card:** three buttons - PILOT UAV (STANDBY MODE when connected), DEEP SLEEP, OVERWATCH. WAKE is gone. The minimap has its own box.
- **Previous flights** (MAP TOOLS > TOOLS): video, track and telemetry on one timeline, link quality, export, delete.
- **Pins:** a sheet with GRID, AMSL, BRG, DIST; EDIT LIST to add, rename, remove.
- **Crashed drones:** LOST DRONE (or right-click on a lost drone) marks it CRASHED: standby at once, out of the PHONE list, no longer "possibly flying".
- **Setup:** Set up a new drone; Betaflight in two steps; GPS rescue by default; one mode per AUX switch; accelerometer calibration; a guided orientation check that sets board and compass alignment; RESET SETUP (TOOLS > Advanced).
- **Tour:** rebuilt - new order, fleet part with four drones, an overwatch alert you fly into. SETUP > Overwatch demo (Advanced) jumps to it.
- **Virtual fleet:** your own layout, LOOK per drone, a realistic BTR and soldier you can crash into, real sounds, +60 ms delay, 30 fps.
- **Motion detection:** finds things sooner and keeps the box on them (colour as well as brightness).

### Changed
- No "//" in any text. Screens fit small and large displays and 125-200 % scaling.
- CONNECT on an overwatch alert brings the main window to the front.

### Fixed
- Dropdowns open again. Closing the window no longer leaves the program running.

All details: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md).
