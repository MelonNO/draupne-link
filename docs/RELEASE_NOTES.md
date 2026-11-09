<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.54

With **DRAUPNE Flight 2.3.0-pc.42**. Update the PC app, then the phone (UPDATE PHONE APP, drone disarmed).

Not yet tried on a drone or real phone: bench-test with props off first.

### Fixed
- **Video:** the picture could freeze after a stream restart until the next key frame.
- **Overwatch:** the pictures before an alert now end with the one that raised it. Before, something just coming into view could be missing from all of them.
- **Link:** timeouts follow the real clock. A busy phone stretched the 4 s timeout to 6 s or more.
- **Drone setup over USB:** a bad byte no longer loses the reply stream.

### Changed
- Large parts of both apps reorganised so they are easier to maintain and test, with no change in behaviour. 990+ tests.

All details: [CHANGELOG](https://github.com/MelonNO/draupne-link/blob/main/docs/CHANGELOG.md).
