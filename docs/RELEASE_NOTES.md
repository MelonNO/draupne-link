<!-- The release page text: only the version being released. Older versions are in CHANGELOG.md. -->
## New in 1.0.35

Phone app **DRAUPNE Flight 2.3.0-pc.28** goes with it: update the phone (tap its yellow *Update available* line). Both are needed.

- **Names, not addresses.** Every phone is now MobileAlpha, MobileBravo ..., every PC PCAlpha, PCBravo ... Open DRAUPNE Flight on the phone (on Tailscale), and the PC names it and lists it; on the LINK card pick the phone and press CONNECT - that is all. MENU > LINK > *Name map* lists every device seen, its address and when, this PC's own name, and the port and key.
- **One way to connect.** The connection method is gone on both ends: it is Tailscale straight to the phone. The PC setup lost its connection and relay steps.
- **The phone listens by itself** as soon as DRAUPNE Flight is open: no CONNECT, no address to type. THIS PHONE on its status card shows the name it got (tap to pick one yourself), the phone's name and model, and its Tailscale address (long press to copy). Port and key are under *Advanced*.
- **Spectators** pick the pilot PC by name and follow that PC, whatever phone it flies.

Untried on hardware: the first run on the phone and the PC together names the phone within a few seconds; the log says "Names: the phone at ... is MobileAlpha now".
