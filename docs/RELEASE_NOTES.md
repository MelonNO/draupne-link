<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.64 + phone pc.51

With the phone app **DRAUPNE Flight 2.3.0-pc.51**. **Update the PC and every phone before flying:** the safety fix below is in both. Not tried on a drone yet - check it with the props off first.

### Safety
- **No arming again on the ground after a link loss.** A drone its failsafe had landed and disarmed could arm when the link came back with ARM still on. The phone no longer tells Betaflight "arm switch off" by itself after such a loss, and the PC stops the sticks until ARM is off and the throttle low ("Link back - FC disarmed - ARM off to fly again.").
- **PC app started while armed in the air:** the OSD says RC OFF - READING FC until the drone's state is known. It said THROTTLE DOWN, ARM OFF - obeyed, a disarm in flight.
- **LOST DRONE** shows after 5 s of loss (not at a short stall), and asks first when the link is back.

### Overwatch
- **Uses less battery.** A small picture (320x240) at 15 fps for the detection; nothing sent to a PC that is switched off; resting loops slowed.
- **A long event rings again.** An event ends with its 15 min clip; what still moves starts a new one - ring, pictures, clip. New motion rings again, once a minute at most.
- **OVERWATCH - BLIND** in FLEET and on the map (crossed eye), with a ring - also when a phone drops to deep sleep at the battery floor.
- An alert that came while flying rings once after landing. **Overwatch ring** has its own switch (LINK menu).
- Detection: a moving light at night is found; a thin figure further off; someone walking slowly straight at the camera sooner (LOW and NORMAL).
- Clips list scrolls; alert cards minimize (-/+).

### Sleeping phones
- **Every PC follows a sleeping phone** - its check-in goes to every PC that knows it, and a mode queued on any of them gets in.
- **Waking works after missed check-ins;** a missed one is said (MISSED CHECK - NEXT m:ss). WAKING - m:ss on the OSD while waiting.
- Check alarms can no longer stop for good; checks keep their interval.
- A queued mode expires after 15 min (or two check-ins), and PILOT UAV drops it.
- **Sleep notice:** a phone going to sleep tells the PCs its position, heading, battery, mode and next check.
- Deep sleep "listening" lets the phone sleep between its minute hellos (PILOT UAV may take a few seconds).

### Link
- The phone's video goes to its lowest step while the PC is silent, and comes back with it.
- AUTO no longer bounces between SURVIVAL and ROBUST; many damaged frames is ROBUST too. ROBUST / SURVIVAL show on the OSD.
- A direction dead for seconds no longer leaves the sticks dead until STANDBY MODE.

### Also
- **AIRSPACE on the map** (MAP TOOLS > TOOLS): R-areas and D-areas (openAIP key) and NOTAMs pasted from IPPC.no.
- **COVERAGE** is kept while the map moves, and counts trees and buildings near the ground (10 m is the default height).
- Previous flights are in MAP TOOLS > DATA.
- UPDATE works from any copy; a broken desktop shortcut is repaired.
