<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.72

A small fix to Drone setup. The phone app stays **DRAUPNE Flight 2.3.0-pc.55**: no phone update needed.

### Drone setup
- A drone on this PC's USB no longer beeps without end once CONFIRM is pressed. While Drone setup is open it gets resting sticks (throttle and switches low), as from its phone, so Betaflight doesn't count its link as lost. Nothing is saved to the drone: a lost link in the field still beeps.
- It can still beep for the few seconds CONFIRM or FIND GPS works, and again after Drone setup is closed until its phone is plugged in.
- While Drone setup is open, Betaflight Configurator can't open the drone's port: close Drone setup first.

Tried on made-up boards only - not yet on a real drone.
