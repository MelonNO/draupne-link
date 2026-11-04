<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.38

Phone app **DRAUPNE Flight 2.3.0-pc.29** (from 1.0.37) is still the one to have; nothing changed on the phone.

- **Boot animation, no more jitter:** the animation's canvas fought the window's layout over its height on every frame (23 px, the root pane's insets), so the mark and the brackets jumped up and down for the whole flight. It now takes the size the layout gives it. (1.0.36's fixed phone-list width was a real fix for a smaller shift, but not this one.)
