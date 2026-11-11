<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.63 + phone pc.49

With the phone app **DRAUPNE Flight 2.3.0-pc.49** (update the phones). Not tried on a drone yet.

### Fixed
- **Overwatch sometimes saw nothing.** When the camera opened a moment late, its picture was switched off before it started, and overwatch stayed blind (seen in a phone log). Fixed in pc.49.
- **Overwatch no longer stays blind.** A camera that stopped in overwatch (taken away with the screen off, or its session refused) stayed off until PILOT UAV and OVERWATCH again. The phone now opens it again after 5 s without pictures (needs pc.49), and the PC log says so: "overwatch camera stopped (...). Phone restarts it." and "overwatch camera back."
- **Wake countdown:** the LINK card and FLEET now count from the same check-in (they differed after the PC was offline).
- **"No picture from this camera"** is said only when it lasts 3 s, not at every change of video size.
