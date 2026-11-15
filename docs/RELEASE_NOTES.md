<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.68

PC only. The phone app stays **DRAUPNE Flight 2.3.0-pc.52** (in this release too).

### Fixed
- **A crash of the whole app in a STANAG stream's encoder.** The streams are now encoded in a process of their own: a crash there no longer closes DRAUPNE Link or the drone link. The streams pause a moment and go on.

### Drone setup
- **Find GPS** on the GPS row: tries each free UART until the GPS answers, and keeps it there. Battery in, props off, about 20 s a port. Ports in use are left alone.

### Mind
- A crash is said in the log, with its crash log in the logs folder. Send it along if you see one.
