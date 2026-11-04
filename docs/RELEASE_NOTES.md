<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.38

Phone app **DRAUPNE Flight 2.3.0-pc.30** goes with it: update the phone (tap its yellow *Update available* line).

- **Deep sleep reports every check** (phone app pc.30): the sleeping phone says *still here* with its battery every 1.5 s for the whole 20 s check window, not three times in the first second - a Tailscale tunnel that slept with the phone drops the first packets after a wake, so whole checks went unheard on the PC.
- **Tour:** the tour's box stays in front of every animation in its window (a window fading in, the menu's rows flying); it no longer pops out from behind one when the animation ends.
- **Boot animation, no more jitter:** the animation's canvas fought the window's layout over its height on every frame (23 px, the root pane's insets), so the mark and the brackets jumped up and down for the whole flight. It now takes the size the layout gives it. (1.0.36's fixed phone-list width was a real fix for a smaller shift, but not this one.)
