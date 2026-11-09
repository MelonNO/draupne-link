<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.56

With **DRAUPNE Flight 2.3.0-pc.44**. Update the PC app, then the phone (UPDATE PHONE APP, drone disarmed).

Not yet tried on a drone or real phone: bench-test with props off first.

### Fixed
- **Motion detection close to the camera:** someone a metre or two off is now found (a crossing in under 1 s, a wave in about 1 s). A leafy branch right in front of the lens can still raise alarms.
- **Flickering live video after overwatch:** STANDBY now ends overwatch first.
- **RESET SETUP and other dialogs** show their buttons.

### New
- **Spectator mirrors the pilot PC:** mode, fleet, map drones, pins, drawings and its observation tiles.
- **Fleet:** modes show at once; STANDBY reaches DEEP SLEEP and OVERWATCH phones at their next check-in; OVERWATCH checks in Constant by default.
- **PILOT UAV** on a drone with an overwatch alert connects to it.
- **LINK card** shows PILOT / OBSERVER / SPECTATOR MODE; modes ordered Pilot, Observer, Spectator.
- **Tour:** new steps for pilot, standby, deep sleep, overwatch, observer (six virtual drones, each with its own scene) and spectator. The map window moves and resizes during the tour.
- LOADING MAP indicator, FC connecting in the setup guide, clips play in a large window, RC check in TOOLS, desktop shortcut added on update.

All details: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md).
