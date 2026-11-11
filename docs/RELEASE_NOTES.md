<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.62 + phone pc.48

With the phone app **DRAUPNE Flight 2.3.0-pc.48** (update the phones). Not tried on a drone yet.

### Fixed
- **Map arrow and overwatch view point the right way** on a drone without a compass. Betaflight's yaw there is only drift; the phone's own compass is used instead (needs pc.48). With no heading known: a round mark and no view, never a wrong way.
- **A drone no longer connected keeps its last heading** on the map (it took the GPS course of a drone standing still).
- **FLEET's OVERWATCH** takes the view from this PC's last connection; no view where the drone now stands elsewhere.
