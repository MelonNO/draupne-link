<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.50

With **DRAUPNE Flight 2.3.0-pc.39**. Update the PC app, then the phone (UPDATE PHONE APP, drone disarmed).

Not yet tried on a drone: bench-test with props off first.

### New
- **Virtual fleet:** try the app with no hardware. TOOLS > Virtual fleet (Advanced on). Virtual drones fly, lose link, sleep, overwatch and see people and cars.
- **CHECK EVERY 0 min:** DEEP SLEEP / OVERWATCH that never stops listening - WAKE and CONNECT reach the phone at once.
- **Map signs:** active, asleep (moon), overwatch (eye), lost (!), unknown (?). Every phone shows its mode. Names on hover.
- **Overwatch:** the camera's view on the map while it watches; alerts on top; X closes the alert card; no confirm on CONNECT.
- **Right-click a sleeping drone:** Wake, then it goes to STANDBY.
- **Pins:** copy a position; add a pin by grid (MGRS, UTM, lat lon).
- **Sound:** arming mutes; unmute in the air if you want it.
- **TOOLS > Advanced** (off by default) hides the rarely needed settings. Hardware decoding on by default.

### Changed
- DISCONNECT leaves the phone in standby; no STANDBY button. STANDBY, DEEP SLEEP and OVERWATCH disconnect by themselves.
- Shorter text everywhere. Menus no longer run off the window.
- The minimap always shows; popped out, the log takes the room.
- Observation: a drone that sees motion stands out on the map; tile buttons no longer cut off.

### Fixed
- Overwatch alert card was a black bar. Motion detection never fired with a large clock (phones up for days too).
- LAST KNOWN no longer shows on a drone you put to sleep or have heard from since.
- "A drone may be flying" ends when that drone reconnects disarmed, says why, and IT'S DOWN - RESUME ends it.

All details: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md).
